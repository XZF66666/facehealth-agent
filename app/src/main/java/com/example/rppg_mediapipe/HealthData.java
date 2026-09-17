package com.example.rppg_mediapipe;

import java.io.Serializable;

public class HealthData implements Serializable {
    private static final long serialVersionUID = 2L;

    private final int heartRate;
    private final int respiratoryRate;
    private final int hrvMillis;
    private final int stressScore;
    private final int fatigueScore;
    private final String videoQuality;
    private final String modelName;
    private final String modelVersion;
    private final double signalQuality;
    private final double actualFps;
    private final long inferenceTimeMs;
    private final boolean resultValid;
    private final String invalidReason;

    public HealthData(int heartRate, int respiratoryRate, int hrvMillis) {
        this(
                heartRate,
                respiratoryRate,
                hrvMillis,
                calculateStressScore(heartRate, respiratoryRate, hrvMillis),
                calculateFatigueScore(heartRate, respiratoryRate, hrvMillis),
                "合格"
        );
    }

    public HealthData(int heartRate, int respiratoryRate, int hrvMillis,
                      int stressScore, int fatigueScore, String videoQuality) {
        this(
                heartRate,
                respiratoryRate,
                hrvMillis,
                stressScore,
                fatigueScore,
                videoQuality,
                "GREEN+FFT",
                "legacy",
                0,
                0,
                0,
                true,
                ""
        );
    }

    public HealthData(int heartRate, int respiratoryRate, int hrvMillis,
                      int stressScore, int fatigueScore, String videoQuality,
                      String modelName, String modelVersion, double signalQuality,
                      double actualFps, long inferenceTimeMs, boolean resultValid,
                      String invalidReason) {
        this.heartRate = heartRate;
        this.respiratoryRate = respiratoryRate;
        this.hrvMillis = hrvMillis;
        this.stressScore = clamp(stressScore);
        this.fatigueScore = clamp(fatigueScore);
        this.videoQuality = videoQuality == null ? "合格" : videoQuality;
        this.modelName = modelName == null ? "unknown" : modelName;
        this.modelVersion = modelVersion == null ? "unknown" : modelVersion;
        this.signalQuality = Math.max(0, Math.min(100, signalQuality));
        this.actualFps = Math.max(0, actualFps);
        this.inferenceTimeMs = Math.max(0, inferenceTimeMs);
        this.resultValid = resultValid;
        this.invalidReason = invalidReason == null ? "" : invalidReason;
    }

    public int getHeartRate() { return heartRate; }
    public int getRespiratoryRate() { return respiratoryRate; }
    public int getHrvMillis() { return hrvMillis; }
    public int getStressScore() { return stressScore; }
    public int getFatigueScore() { return fatigueScore; }
    public String getVideoQuality() { return videoQuality; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public double getSignalQuality() { return signalQuality; }
    public double getActualFps() { return actualFps; }
    public long getInferenceTimeMs() { return inferenceTimeMs; }
    public boolean isResultValid() { return resultValid; }
    public String getInvalidReason() { return invalidReason; }

    public static int calculateStressScore(int heartRate, int respiratoryRate, int hrvMillis) {
        int score = 50;
        if (heartRate > 85) score += 15;
        if (respiratoryRate > 20) score += 15;
        if (hrvMillis < 35) score += 20;
        return clamp(score);
    }

    public static int calculateFatigueScore(int heartRate, int respiratoryRate, int hrvMillis) {
        int score = 50;
        if (hrvMillis < 35) score += 20;
        if (heartRate > 90) score += 10;
        if (respiratoryRate > 22) score += 10;
        return clamp(score);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    @Override
    public String toString() {
        return "HealthData{" +
                "heartRate=" + heartRate +
                ", respiratoryRate=" + respiratoryRate +
                ", hrvMillis=" + hrvMillis +
                ", stressScore=" + stressScore +
                ", fatigueScore=" + fatigueScore +
                ", videoQuality='" + videoQuality + '\'' +
                ", modelName='" + modelName + '\'' +
                ", signalQuality=" + signalQuality +
                ", actualFps=" + actualFps +
                ", inferenceTimeMs=" + inferenceTimeMs +
                '}';
    }
}
