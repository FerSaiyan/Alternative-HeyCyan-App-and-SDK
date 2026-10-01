#include <jni.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Context {
    llama_model * model = nullptr;
    llama_context * llm = nullptr;
    mtmd_context * media = nullptr;
    std::atomic<bool> cancelled{false};
    std::mutex inference_mutex;
    bool embedding = false;
    int batch = 512;
    ~Context() {
        if (media) mtmd_free(media);
        if (llm) llama_free(llm);
        if (model) llama_model_free(model);
    }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Context>> registry;
std::atomic<jlong> next_id{1};
std::once_flag backend_init;

void fail(JNIEnv * env, const std::exception & e) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
std::shared_ptr<Context> get(jlong id) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto it = registry.find(id);
    if (it == registry.end()) throw std::runtime_error("llama.cpp context is unloaded");
    return it->second;
}
std::string path(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read model/media path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
std::string bytes(JNIEnv * env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
jbyteArray byte_array(JNIEnv * env, const std::string & value) {
    auto out = env->NewByteArray(value.size());
    if (out) env->SetByteArrayRegion(out, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return out;
}
std::vector<llama_token> tokenize(Context & ctx, const std::string & text) {
    auto vocab = llama_model_get_vocab(ctx.model);
    int n = llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
    if (n >= 0) return {};
    std::vector<llama_token> tokens(-n);
    n = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), true, true);
    if (n < 0) throw std::runtime_error("llama.cpp tokenization failed");
    tokens.resize(n);
    return tokens;
}
void clear(Context & ctx) {
    if (auto memory = llama_get_memory(ctx.llm)) llama_memory_clear(memory, true);
}
void decode(Context & ctx, std::vector<llama_token> & tokens) {
    if (tokens.empty()) throw std::runtime_error("Prompt is empty");
    if (tokens.size() >= llama_n_ctx(ctx.llm)) throw std::runtime_error("Prompt exceeds model context size");
    for (size_t i = 0; i < tokens.size(); i += ctx.batch) {
        auto count = std::min<size_t>(ctx.batch, tokens.size() - i);
        if (ctx.cancelled) throw std::runtime_error("Generation cancelled");
        if (llama_decode(ctx.llm, llama_batch_get_one(tokens.data() + i, count)) != 0)
            throw std::runtime_error("llama.cpp prompt decode failed");
    }
}
using Bitmap = std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>;
using Chunks = std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)>;
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fersaiyan_cyanbridge_llama_UpstreamLlamaBridge_load(JNIEnv * env, jobject, jstring model, jstring projector, jint size, jint threads, jboolean embedding) {
    try {
        std::call_once(backend_init, [] { llama_backend_init(); });
        auto ctx = std::make_shared<Context>();
        ctx->embedding = embedding;
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        ctx->model = llama_model_load_from_file(path(env, model).c_str(), mp);
        if (!ctx->model) throw std::runtime_error("Cannot load GGUF with upstream llama.cpp");
        auto cp = llama_context_default_params();
        cp.n_ctx = size;
        cp.n_batch = ctx->batch;
        cp.n_ubatch = ctx->batch;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.embeddings = embedding;
        if (embedding) cp.pooling_type = LLAMA_POOLING_TYPE_MEAN;
        cp.abort_callback = [](void * p) { return static_cast<Context *>(p)->cancelled.load(); };
        cp.abort_callback_data = ctx.get();
        ctx->llm = llama_init_from_model(ctx->model, cp);
        if (!ctx->llm) throw std::runtime_error("Cannot create upstream llama.cpp context");
        auto proj = path(env, projector);
        if (!proj.empty()) {
            auto params = mtmd_context_params_default();
            params.use_gpu = false;
            params.n_threads = threads;
            params.warmup = false;
            ctx->media = mtmd_init_from_file(proj.c_str(), ctx->model, params);
            if (!ctx->media) throw std::runtime_error("Projector does not match this GGUF model");
        }
        auto id = next_id++;
        std::lock_guard<std::mutex> guard(registry_mutex);
        registry[id] = ctx;
        return id;
    } catch (const std::exception & e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fersaiyan_cyanbridge_llama_UpstreamLlamaBridge_release(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto it = registry.find(id);
    if (it != registry.end()) { it->second->cancelled = true; registry.erase(it); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_fersaiyan_cyanbridge_llama_UpstreamLlamaBridge_cancel(JNIEnv * env, jobject, jlong id) {
    try { get(id)->cancelled = true; } catch (const std::exception & e) { fail(env, e); }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_fersaiyan_cyanbridge_llama_UpstreamLlamaBridge_capabilities(JNIEnv * env, jobject, jlong id) {
    try {
        auto ctx = get(id);
        return ctx->media ? (mtmd_support_vision(ctx->media) ? 1 : 0) | (mtmd_support_audio(ctx->media) ? 2 : 0) : 0;
    } catch (const std::exception & e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_fersaiyan_cyanbridge_llama_UpstreamLlamaBridge_tokenCount(JNIEnv * env, jobject, jlong id, jbyteArray text) {
    try { auto ctx = get(id); return tokenize(*ctx, bytes(env, text)).size(); }
    catch (const std::exception & e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_fersaiyan_cyanbridge_llama_UpstreamLlamaBridge_generate(JNIEnv * env, jobject, jlong id, jbyteArray input, jobjectArray images,
    jstring audio, jint max_tokens, jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty, jint seed,
    jstring grammar, jobject sink, jintArray stats) {
    try {
        auto ctx = get(id);
        std::lock_guard<std::mutex> inference(ctx->inference_mutex);
        ctx->cancelled = false;
        clear(*ctx);
        auto prompt = bytes(env, input);
        const auto image_count = env->GetArrayLength(images);
        const auto audio_path = path(env, audio);
        const bool has_media = image_count > 0 || !audio_path.empty();
        llama_pos past = 0;
        if (has_media) {
            if (!ctx->media) throw std::runtime_error("This GGUF needs a matching mmproj projector for media input");
            if (image_count && !mtmd_support_vision(ctx->media)) throw std::runtime_error("Selected GGUF/projector does not support images");
            if (!audio_path.empty() && !mtmd_support_audio(ctx->media)) throw std::runtime_error("Selected GGUF/projector does not support audio");
            std::vector<Bitmap> owned;
            std::vector<const mtmd_bitmap *> bitmaps;
            std::string markers;
            auto add_media = [&](const std::string & name) {
                auto decoded = mtmd_helper_bitmap_init_from_file(ctx->media, name.c_str(), false, mtmd_helper_init_opt_default());
                if (!decoded.bitmap || decoded.video_ctx) throw std::runtime_error("Cannot decode attached image/audio: " + name);
                owned.emplace_back(decoded.bitmap, mtmd_bitmap_free);
                bitmaps.push_back(decoded.bitmap);
                markers += std::string(mtmd_default_marker()) + "\n";
            };
            for (int i = 0; i < image_count; ++i) {
                auto value = static_cast<jstring>(env->GetObjectArrayElement(images, i));
                auto name = path(env, value);
                env->DeleteLocalRef(value);
                add_media(name);
            }
            if (!audio_path.empty()) add_media(audio_path);
            // Kotlin places the media marker inside the user turn before rendering the template.
            const std::string slot = "<cyanbridge_media>";
            auto at = prompt.find(slot);
            if (at == std::string::npos) throw std::runtime_error("Multimodal prompt has no user media slot");
            prompt.replace(at, slot.size(), markers);
            Chunks chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
            mtmd_input_text text{prompt.data(), prompt.size(), true, true};
            if (mtmd_tokenize(ctx->media, chunks.get(), &text, bitmaps.data(), bitmaps.size()) != 0)
                throw std::runtime_error("Multimodal tokenization failed");
            if (mtmd_helper_get_n_pos(chunks.get()) >= llama_n_ctx(ctx->llm))
                throw std::runtime_error("Image/audio question exceeds model context size");
            if (ctx->cancelled || mtmd_helper_eval_chunks(ctx->media, ctx->llm, chunks.get(), 0, 0, ctx->batch, true, &past) != 0)
                throw std::runtime_error("Multimodal encoding failed or was cancelled");
        } else {
            auto tokens = tokenize(*ctx, prompt);
            decode(*ctx, tokens);
            past = tokens.size();
        }
        auto vocab = llama_model_get_vocab(ctx->model);
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto rules = path(env, grammar);
        if (!rules.empty()) {
            auto constrained = llama_sampler_init_grammar(vocab, rules.c_str(), "root");
            if (!constrained) throw std::runtime_error("Invalid structured-output grammar");
            llama_sampler_chain_add(sampler.get(), constrained);
        }
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0, 0));
        if (temperature <= 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(top_k));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed));
        }
        auto callback = env->GetMethodID(env->GetObjectClass(sink), "onToken", "([B)V");
        std::string response;
        int count = 0;
        bool eog = false;
        while (!ctx->cancelled && count < max_tokens && past < llama_n_ctx(ctx->llm)) {
            auto token = llama_sampler_sample(sampler.get(), ctx->llm, -1);
            if (llama_vocab_is_eog(vocab, token)) { eog = true; break; }
            int size = llama_token_to_piece(vocab, token, nullptr, 0, 0, false);
            std::string piece(size < 0 ? -size : size, '\0');
            size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (size < 0) throw std::runtime_error("Cannot decode output token");
            piece.resize(size);
            response += piece;
            auto chunk = byte_array(env, piece);
            env->CallVoidMethod(sink, callback, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) return nullptr;
            ++count;
            if (llama_decode(ctx->llm, llama_batch_get_one(&token, 1)) != 0)
                throw std::runtime_error("llama.cpp output decode failed or was cancelled");
            ++past;
        }
        jint values[2] = {count, (!eog && !ctx->cancelled) ? 1 : 0};
        env->SetIntArrayRegion(stats, 0, 2, values);
        return byte_array(env, response);
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fersaiyan_cyanbridge_llama_UpstreamLlamaBridge_embed(JNIEnv * env, jobject, jlong id, jbyteArray text) {
    try {
        auto ctx = get(id);
        std::lock_guard<std::mutex> inference(ctx->inference_mutex);
        if (!ctx->embedding) throw std::runtime_error("Context was not loaded for embeddings");
        ctx->cancelled = false;
        clear(*ctx);
        auto tokens = tokenize(*ctx, bytes(env, text));
        // Pooled embeddings require one complete sequence in one decode batch.
        if (tokens.empty() || tokens.size() > ctx->batch || tokens.size() > llama_n_ctx(ctx->llm))
            throw std::runtime_error("Embedding input exceeds the batch/context limit");
        auto batch = llama_batch_init(tokens.size(), 0, 1);
        batch.n_tokens = tokens.size();
        for (size_t i = 0; i < tokens.size(); ++i) {
            batch.token[i] = tokens[i]; batch.pos[i] = i; batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0; batch.logits[i] = true;
        }
        int rc = llama_model_has_encoder(ctx->model) ? llama_encode(ctx->llm, batch) : llama_decode(ctx->llm, batch);
        llama_batch_free(batch);
        if (rc != 0) throw std::runtime_error("Embedding evaluation failed");
        auto vector = llama_get_embeddings_seq(ctx->llm, 0);
        if (!vector) throw std::runtime_error("Model returned no pooled embedding");
        auto dimension = llama_model_n_embd_out(ctx->model);
        auto result = env->NewFloatArray(dimension);
        env->SetFloatArrayRegion(result, 0, dimension, vector);
        return result;
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}
