package com.kareem.cortex;

public final class LocalBrainRun {

    public final CognitiveResult result;
    public final String rawOutput;
    public final long durationMs;
    public final long modelLoadMs;
    /** Wall time spent in the completion call, including prompt evaluation and token decode. */
    public final long generationMs;
    /** Native llama.cpp prompt-evaluation time. */
    public final long promptEvalMs;
    /** Native llama.cpp output-token generation time. */
    public final long tokenGenerationMs;
    public final int tokensGenerated;
    public final float tokensPerSecond;
    public final boolean cacheHit;

    public final long enqueuedAt;
    public final long nativeStartedAt;
    public final long nativeFinishedAt;
    public final long queueWaitMs;
    public final long nativeTotalMs;
    public final long totalMs;
    public final int promptChars;
    public final String wireSchema;
    public final String confidenceSource;

    public LocalBrainRun(
            CognitiveResult result,
            String rawOutput,
            long durationMs,
            long modelLoadMs,
            long generationMs,
            int tokensGenerated,
            float tokensPerSecond,
            boolean cacheHit
    ) {
        this(
                result,
                rawOutput,
                durationMs,
                modelLoadMs,
                generationMs,
                tokensGenerated,
                tokensPerSecond,
                cacheHit,
                0L,
                0L,
                0L,
                0L,
                durationMs,
                durationMs,
                0,
                "",
                "",
                0L,
                0L
        );
    }

    public LocalBrainRun(
            CognitiveResult result,
            String rawOutput,
            long durationMs,
            long modelLoadMs,
            long generationMs,
            int tokensGenerated,
            float tokensPerSecond,
            boolean cacheHit,
            long enqueuedAt,
            long nativeStartedAt,
            long nativeFinishedAt,
            long queueWaitMs,
            long nativeTotalMs,
            long totalMs,
            int promptChars,
            String wireSchema
    ) {
        this(
                result,
                rawOutput,
                durationMs,
                modelLoadMs,
                generationMs,
                tokensGenerated,
                tokensPerSecond,
                cacheHit,
                enqueuedAt,
                nativeStartedAt,
                nativeFinishedAt,
                queueWaitMs,
                nativeTotalMs,
                totalMs,
                promptChars,
                wireSchema,
                "",
                0L,
                0L
        );
    }

    public LocalBrainRun(
            CognitiveResult result,
            String rawOutput,
            long durationMs,
            long modelLoadMs,
            long generationMs,
            int tokensGenerated,
            float tokensPerSecond,
            boolean cacheHit,
            long enqueuedAt,
            long nativeStartedAt,
            long nativeFinishedAt,
            long queueWaitMs,
            long nativeTotalMs,
            long totalMs,
            int promptChars,
            String wireSchema,
            String confidenceSource
    ) {
        this(
                result,
                rawOutput,
                durationMs,
                modelLoadMs,
                generationMs,
                tokensGenerated,
                tokensPerSecond,
                cacheHit,
                enqueuedAt,
                nativeStartedAt,
                nativeFinishedAt,
                queueWaitMs,
                nativeTotalMs,
                totalMs,
                promptChars,
                wireSchema,
                confidenceSource,
                0L,
                0L
        );
    }

    public LocalBrainRun(
            CognitiveResult result,
            String rawOutput,
            long durationMs,
            long modelLoadMs,
            long generationMs,
            int tokensGenerated,
            float tokensPerSecond,
            boolean cacheHit,
            long enqueuedAt,
            long nativeStartedAt,
            long nativeFinishedAt,
            long queueWaitMs,
            long nativeTotalMs,
            long totalMs,
            int promptChars,
            String wireSchema,
            String confidenceSource,
            long promptEvalMs,
            long tokenGenerationMs
    ) {
        this.result = result;
        this.rawOutput = rawOutput == null ? "" : rawOutput;
        this.durationMs = Math.max(0L, durationMs);
        this.modelLoadMs = Math.max(0L, modelLoadMs);
        this.generationMs = Math.max(0L, generationMs);
        this.promptEvalMs = Math.max(0L, promptEvalMs);
        this.tokenGenerationMs = Math.max(0L, tokenGenerationMs);
        this.tokensGenerated = Math.max(0, tokensGenerated);
        this.tokensPerSecond = tokensPerSecond;
        this.cacheHit = cacheHit;
        this.enqueuedAt = Math.max(0L, enqueuedAt);
        this.nativeStartedAt = Math.max(0L, nativeStartedAt);
        this.nativeFinishedAt = Math.max(0L, nativeFinishedAt);
        this.queueWaitMs = Math.max(0L, queueWaitMs);
        this.nativeTotalMs = Math.max(0L, nativeTotalMs);
        this.totalMs = Math.max(0L, totalMs);
        this.promptChars = Math.max(0, promptChars);
        this.wireSchema = wireSchema == null ? "" : wireSchema;
        this.confidenceSource = confidenceSource == null ? "" : confidenceSource;
    }
}
