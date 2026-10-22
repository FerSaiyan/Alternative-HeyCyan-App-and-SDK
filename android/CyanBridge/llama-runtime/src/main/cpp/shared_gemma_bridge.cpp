#include <jni.h>
#include "llama.h"
#include "chat.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
const std::string OPEN = "\xE2\x8A\xB2", CLOSE = "\xE2\x8A\xB3";
struct Context {
    llama_model * model = nullptr;
    llama_context * llm = nullptr;
    common_chat_templates_ptr templates;
    std::atomic<bool> cancelled{false};
    std::mutex inference_mutex;
    int batch = 128;
    ~Context() { if (llm) llama_free(llm); if (model) llama_model_free(model); }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Context>> registry;
std::atomic<jlong> next_id{1};
std::once_flag backend_init;
void fail(JNIEnv * env, const std::exception & e) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
std::shared_ptr<Context> get(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it = registry.find(id);
    if (it == registry.end()) throw std::runtime_error("Model is unloaded");
    return it->second;
}
std::string text(JNIEnv * env, jstring value) {
    if (!value) return {};
    auto chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read JNI string");
    std::string out(chars); env->ReleaseStringUTFChars(value, chars); return out;
}
std::string bytes(JNIEnv * env, jbyteArray value) {
    if (!value) throw std::runtime_error("Missing UTF-8 input");
    std::string out(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, out.size(), reinterpret_cast<jbyte *>(out.data())); return out;
}
jbyteArray output_bytes(JNIEnv * env, const std::string & value) {
    auto out = env->NewByteArray(value.size());
    if (out) env->SetByteArrayRegion(out, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return out;
}
std::vector<llama_token> tokenize(Context & ctx, const std::string & input, bool special = true) {
    auto vocab = llama_model_get_vocab(ctx.model);
    int n = llama_tokenize(vocab, input.data(), input.size(), nullptr, 0, special, special);
    if (n >= 0) return {};
    std::vector<llama_token> tokens(-n);
    n = llama_tokenize(vocab, input.data(), input.size(), tokens.data(), tokens.size(), special, special);
    if (n < 0) throw std::runtime_error("Tokenization failed");
    tokens.resize(n); return tokens;
}
void prefill(Context & ctx, std::vector<llama_token> & tokens) {
    if (tokens.empty() || tokens.size() >= llama_n_ctx(ctx.llm)) throw std::runtime_error("Prompt exceeds context or is empty");
    if (auto memory = llama_get_memory(ctx.llm)) llama_memory_clear(memory, true);
    for (size_t i = 0; i < tokens.size(); i += ctx.batch) {
        if (ctx.cancelled) throw std::runtime_error("Inference cancelled");
        auto count = std::min<size_t>(ctx.batch, tokens.size() - i);
        if (llama_decode(ctx.llm, llama_batch_get_one(tokens.data() + i, count)) != 0)
            throw std::runtime_error("Prompt decode failed or cancelled");
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fersaiyan_cyanbridge_llama_SharedGemmaBridge_load(JNIEnv * env, jobject, jstring path, jint size, jint threads) {
    try {
        if (size < 32 || threads < 1) throw std::runtime_error("Invalid context size/threads");
        std::call_once(backend_init, [] { llama_backend_init(); });
        auto ctx = std::make_shared<Context>();
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        ctx->model = llama_model_load_from_file(text(env, path).c_str(), mp);
        if (!ctx->model || !llama_model_has_decoder(ctx->model)) throw std::runtime_error("Cannot load a generative GGUF");
        auto cp = llama_context_default_params();
        cp.n_ctx = size; cp.n_batch = ctx->batch; cp.n_ubatch = ctx->batch;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.abort_callback = [](void * p) { return static_cast<Context *>(p)->cancelled.load(); };
        cp.abort_callback_data = ctx.get();
        ctx->llm = llama_init_from_model(ctx->model, cp);
        if (!ctx->llm) throw std::runtime_error("Cannot create llama context");
        ctx->templates = common_chat_templates_init(ctx->model, "");
        auto id = next_id++;
        std::lock_guard<std::mutex> lock(registry_mutex); registry[id] = ctx; return id;
    } catch (const std::exception & e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_fersaiyan_cyanbridge_llama_SharedGemmaBridge_release(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it = registry.find(id);
    if (it != registry.end()) { it->second->cancelled = true; registry.erase(it); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_fersaiyan_cyanbridge_llama_SharedGemmaBridge_cancel(JNIEnv * env, jobject, jlong id) {
    try { get(id)->cancelled = true; } catch (const std::exception & e) { fail(env, e); }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_fersaiyan_cyanbridge_llama_SharedGemmaBridge_tokenCount(JNIEnv * env, jobject, jlong id, jbyteArray input) {
    try { auto ctx = get(id); return tokenize(*ctx, bytes(env, input)).size(); }
    catch (const std::exception & e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fersaiyan_cyanbridge_llama_SharedGemmaBridge_decide(JNIEnv * env, jobject, jlong id, jbyteArray input, jobjectArray labels) {
    try {
        auto ctx = get(id); std::lock_guard<std::mutex> lock(ctx->inference_mutex);
        ctx->cancelled = false;
        common_chat_msg msg; msg.role = "user"; msg.content = bytes(env, input);
        common_chat_templates_inputs request; request.messages = {msg}; request.enable_thinking = false;
        const auto prompt = common_chat_templates_apply(ctx->templates.get(), request).prompt + OPEN;
        auto tokens = tokenize(*ctx, prompt);
        auto close = tokenize(*ctx, CLOSE, false);
        std::vector<llama_token> ids;
        const int count = labels ? env->GetArrayLength(labels) : 0;
        if (count < 2 || count > 26) throw std::runtime_error("Need 2..26 labels");
        for (int i = 0; i < count; ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(labels, i));
            auto label = text(env, value); env->DeleteLocalRef(value);
            if (label.size() != 1 || label[0] < 'A' || label[0] > 'Z') throw std::runtime_error("Invalid label");
            auto token = tokenize(*ctx, label, false);
            if (token.size() != 1) throw std::runtime_error("Label is not a single token: " + label);
            auto expected = tokens; expected.push_back(token[0]); expected.insert(expected.end(), close.begin(), close.end());
            if (tokenize(*ctx, prompt + label + CLOSE) != expected)
                throw std::runtime_error("Label merges with delimiters in this vocabulary: " + label);
            if (std::find(ids.begin(), ids.end(), token[0]) != ids.end()) throw std::runtime_error("Duplicate label token");
            ids.push_back(token[0]);
        }
        prefill(*ctx, tokens);
        const float * logits = llama_get_logits_ith(ctx->llm, -1);
        if (!logits || ctx->cancelled) throw std::runtime_error("Missing logits or cancelled");
        const int vocab_size = llama_vocab_n_tokens(llama_model_get_vocab(ctx->model));
        float max_value = -INFINITY;
        for (int i = 0; i < vocab_size; ++i) {
            if (std::isnan(logits[i]) || logits[i] == INFINITY) throw std::runtime_error("Invalid vocabulary logits");
            max_value = std::max(max_value, logits[i]);
        }
        if (!std::isfinite(max_value)) throw std::runtime_error("No finite vocabulary logits");
        double total = 0, label_mass = 0;
        for (int i = 0; i < vocab_size; ++i) total += std::exp(double(logits[i]) - max_value);
        std::vector<float> values;
        for (auto token : ids) {
            if (!std::isfinite(logits[token])) throw std::runtime_error("Non-finite label logit");
            values.push_back(logits[token]); label_mass += std::exp(double(logits[token]) - max_value);
        }
        values.push_back(label_mass / total);
        auto out = env->NewFloatArray(values.size());
        if (out) env->SetFloatArrayRegion(out, 0, values.size(), values.data()); return out;
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_fersaiyan_cyanbridge_llama_SharedGemmaBridge_generate(JNIEnv * env, jobject, jlong id, jbyteArray input,
    jint max_tokens, jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty, jint seed,
    jstring grammar, jobject sink, jintArray stats) {
    try {
        auto ctx = get(id); std::lock_guard<std::mutex> lock(ctx->inference_mutex);
        if (max_tokens < 1 || !stats || env->GetArrayLength(stats) < 2 || !sink) throw std::runtime_error("Invalid generation request");
        ctx->cancelled = false;
        auto tokens = tokenize(*ctx, bytes(env, input)); prefill(*ctx, tokens);
        using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto rules = text(env, grammar);
        if (!rules.empty()) {
            auto constrained = llama_sampler_init_grammar(llama_model_get_vocab(ctx->model), rules.c_str(), "root");
            if (!constrained) throw std::runtime_error("Invalid grammar");
            llama_sampler_chain_add(sampler.get(), constrained);
        }
        auto vocab = llama_model_get_vocab(ctx->model);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0, 0));
        if (temperature <= 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(top_k));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed));
        }
        auto sink_class = env->GetObjectClass(sink);
        auto callback = env->GetMethodID(sink_class, "onToken", "([B)V"); env->DeleteLocalRef(sink_class);
        if (!callback) throw std::runtime_error("Missing token callback");
        std::string response; int count = 0; bool eog = false;
        size_t past = tokens.size();
        while (!ctx->cancelled && count < max_tokens && past < llama_n_ctx(ctx->llm)) {
            auto token = llama_sampler_sample(sampler.get(), ctx->llm, -1);
            if (llama_vocab_is_eog(vocab, token)) { eog = true; break; }
            int n = llama_token_to_piece(vocab, token, nullptr, 0, 0, false);
            std::string piece(n < 0 ? -n : n, '\0');
            n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) throw std::runtime_error("Cannot decode output token");
            piece.resize(n); response += piece;
            auto chunk = output_bytes(env, piece); env->CallVoidMethod(sink, callback, chunk); env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) return nullptr;
            ++count;
            if (llama_decode(ctx->llm, llama_batch_get_one(&token, 1)) != 0) throw std::runtime_error("Decode failed or cancelled");
            ++past;
        }
        if (ctx->cancelled) throw std::runtime_error("Inference cancelled");
        jint values[] = {count, eog ? 0 : 1}; env->SetIntArrayRegion(stats, 0, 2, values);
        return output_bytes(env, response);
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}
