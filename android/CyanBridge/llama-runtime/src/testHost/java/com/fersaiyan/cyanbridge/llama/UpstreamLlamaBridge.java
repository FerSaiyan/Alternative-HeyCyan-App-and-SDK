package com.fersaiyan.cyanbridge.llama;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Executable JNI contract smoke test, using the same signatures as the Android Kotlin bridge. */
public final class UpstreamLlamaBridge {
    public interface TokenSink { void onToken(byte[] bytes); }
    public native long load(String model, String projector, int context, int threads, boolean embeddings);
    public native void release(long handle);
    public native int capabilities(long handle);
    public native int tokenCount(long handle, byte[] text);
    public native byte[] generate(long handle, byte[] prompt, String[] images, String audio, int maxTokens,
        float temperature, float topP, int topK, float repeatPenalty, int seed, String grammar, TokenSink sink, int[] stats);
    public native float[] embed(long handle, byte[] text);

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        var bridge = new UpstreamLlamaBridge();
        long id = bridge.load(args[1], null, 1024, 2, false);
        try {
            if (bridge.capabilities(id) != 0) throw new AssertionError("Text model incorrectly advertised media support");
            if (bridge.tokenCount(id, "Olá 日本語".getBytes(StandardCharsets.UTF_8)) <= 0) throw new AssertionError("Tokenizer returned no tokens");
            String prompt = "<|im_start|>system\nAnswer briefly.<|im_end|>\n<|im_start|>user\nSay hello.<|im_end|>\n<|im_start|>assistant\n";
            var streamed = new ByteArrayOutputStream();
            int[] stats = new int[2];
            byte[] reply = bridge.generate(id, prompt.getBytes(StandardCharsets.UTF_8), new String[0], null,
                24, 0f, .9f, 40, 1.1f, 1, null, bytes -> streamed.writeBytes(bytes), stats);
            if (reply.length == 0 || stats[0] == 0) throw new AssertionError("Empty generation");
            if (!java.util.Arrays.equals(reply, streamed.toByteArray())) throw new AssertionError("Streaming lost bytes");
            byte[] constrained = bridge.generate(id, prompt.getBytes(StandardCharsets.UTF_8), new String[0], null,
                24, 0f, .9f, 40, 1f, 1, "root ::= \"{\\\"ok\\\":true}\"", bytes -> {}, stats);
            if (!new String(constrained, StandardCharsets.UTF_8).equals("{\"ok\":true}")) throw new AssertionError("Grammar was not applied");
            try {
                bridge.generate(id, prompt.getBytes(StandardCharsets.UTF_8), new String[0], args[3],
                    24, 0f, .9f, 40, 1f, 1, null, bytes -> {}, stats);
                throw new AssertionError("Audio was silently ignored on a text-only model");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("projector")) throw expected;
            }
            System.out.println("CHAT_STREAM_GRAMMAR_MEDIA_REJECTION_OK: " + new String(reply, StandardCharsets.UTF_8));
        } finally { bridge.release(id); }
        try { bridge.tokenCount(id, new byte[]{65}); throw new AssertionError("Unloaded handle accepted"); }
        catch (IllegalStateException expected) {}
        long embedding = bridge.load(args[2], null, 1024, 2, true);
        try {
            float[] vector = bridge.embed(embedding, "A useful offline embedding.".getBytes(StandardCharsets.UTF_8));
            if (vector.length == 0) throw new AssertionError("Empty embedding");
            for (float f : vector) if (!Float.isFinite(f)) throw new AssertionError("Non-finite embedding");
            System.out.println("EMBEDDING_OK: dimension=" + vector.length);
        } finally { bridge.release(embedding); }
    }
}
