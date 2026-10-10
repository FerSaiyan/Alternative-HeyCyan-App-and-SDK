package com.fersaiyan.cyanbridge.llama;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Same JNI signatures as Kotlin; uses an existing local GGUF, never downloads. */
public final class SharedGemmaBridge {
    public interface TokenSink { void onToken(byte[] bytes); }
    public native long load(String path, int context, int threads);
    public native void release(long handle);
    public native void cancel(long handle);
    public native int tokenCount(long handle, byte[] text);
    public native float[] decide(long handle, byte[] user, String[] labels);
    public native byte[] generate(long handle, byte[] prompt, int maxTokens, float temperature,
        float topP, int topK, float repeatPenalty, int seed, String grammar, TokenSink sink, int[] stats);

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void checkScores(float[] values, int count) {
        if (values.length != count + 1) throw new AssertionError("Wrong logit shape");
        for (float value : values) if (!Float.isFinite(value)) throw new AssertionError("Non-finite score");
        if (values[count] < 0 || values[count] > 1) throw new AssertionError("Invalid label mass");
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        var bridge = new SharedGemmaBridge();
        long handle = bridge.load(args[1], 2048, 4);
        try {
            String user = "# State\nOpen YouTube search.\n# Options\n⊲A⊳ Tap Search\n⊲B⊳ Done\nRespond with only ⊲label⊳.";
            float[] first = bridge.decide(handle, utf8(user), new String[]{"A", "B"});
            checkScores(first, 2);
            String prompt = "<|im_start|>user\nSay hello.<|im_end|>\n<|im_start|>assistant\n";
            var streamed = new ByteArrayOutputStream();
            var stats = new int[2];
            byte[] reply = bridge.generate(handle, utf8(prompt), 8, 0, .9f, 40, 1, 1, null, streamed::writeBytes, stats);
            if (reply.length == 0 || stats[0] == 0 || !Arrays.equals(reply, streamed.toByteArray())) throw new AssertionError("Chat/stream failed");
            float[] afterChat = bridge.decide(handle, utf8(user), new String[]{"A", "B"});
            checkScores(afterChat, 2);
            for (int i = 0; i < first.length; ++i) if (Math.abs(first[i] - afterChat[i]) > 1e-5f) throw new AssertionError("Chat contaminated decision state");
            var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var scoring = workers.submit(() -> bridge.decide(handle, utf8(user), new String[]{"A", "B"}));
                var chatting = workers.submit(() -> bridge.generate(handle, utf8(prompt), 8, 0, .9f, 40, 1, 1, null, bytes -> {}, new int[2]));
                float[] concurrent = scoring.get();
                if (chatting.get().length == 0) throw new AssertionError("Concurrent chat failed");
                for (int i = 0; i < first.length; ++i) if (Math.abs(first[i] - concurrent[i]) > 1e-5f) throw new AssertionError("Concurrent prefill contaminated logits");
            } finally { workers.shutdownNow(); }
            byte[] constrained = bridge.generate(handle, utf8(prompt), 24, 0, .9f, 40, 1, 1,
                "root ::= \"{\\\"ok\\\":true}\"", bytes -> {}, stats);
            if (!new String(constrained, StandardCharsets.UTF_8).equals("{\"ok\":true}")) throw new AssertionError("Grammar failed");
            try { bridge.decide(handle, utf8(user), new String[]{"A", "A"}); throw new AssertionError("Duplicate labels accepted"); }
            catch (IllegalStateException expected) {}
            try { bridge.decide(handle, utf8(user), new String[]{"AA", "B"}); throw new AssertionError("Multitoken label accepted"); }
            catch (IllegalStateException expected) {}
            try { bridge.decide(handle, utf8("test ".repeat(10000)), new String[]{"A", "B"}); throw new AssertionError("Overflow silently clipped"); }
            catch (IllegalStateException expected) { if (!expected.getMessage().contains("context")) throw expected; }
            checkScores(bridge.decide(handle, utf8(user), new String[]{"A", "B"}), 2);
            System.out.println("SHARED_MODEL_CHAT_LOGITS_STATE_RESET_GRAMMAR_OVERFLOW_OK logits=" + Arrays.toString(first));
        } finally { bridge.release(handle); }
        try { bridge.decide(handle, utf8("state"), new String[]{"A", "B"}); throw new AssertionError("Unloaded handle accepted"); }
        catch (IllegalStateException expected) {}
    }
}
