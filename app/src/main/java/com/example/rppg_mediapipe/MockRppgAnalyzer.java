package com.example.rppg_mediapipe;

import java.util.Random;

public class MockRppgAnalyzer {
    private static final Random RANDOM = new Random();

    public static HealthData generate() {
        int heartRate = 70 + RANDOM.nextInt(26);
        int respiratoryRate = 15 + RANDOM.nextInt(10);
        int hrv = 25 + RANDOM.nextInt(36);
        return new HealthData(
                heartRate,
                respiratoryRate,
                hrv,
                HealthData.calculateStressScore(heartRate, respiratoryRate, hrv),
                HealthData.calculateFatigueScore(heartRate, respiratoryRate, hrv),
                "合格"
        );
    }
}