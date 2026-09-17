package com.example.rppg_mediapipe.rppg.model;

public final class RppgInferenceResult {
    public final float[] diffNormalizedBvp;
    public final long inferenceTimeMs;
    public final long initializationTimeMs;
    public final String modelName;
    public final String modelVersion;

    public RppgInferenceResult(float[] diffNormalizedBvp,
                               long inferenceTimeMs,
                               long initializationTimeMs,
                               String modelName,
                               String modelVersion) {
        this.diffNormalizedBvp = diffNormalizedBvp;
        this.inferenceTimeMs = inferenceTimeMs;
        this.initializationTimeMs = initializationTimeMs;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
    }
}
