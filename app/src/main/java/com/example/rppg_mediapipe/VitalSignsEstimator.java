package com.example.rppg_mediapipe;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class VitalSignsEstimator {
    private static final String TAG = "VitalSignsEstimator";

    public static HealthData estimate(JSONArray frames, long durationMs) {
        if (frames == null || frames.length() < 20 || durationMs <= 0) {
            Log.e(TAG, "Not enough frames. frames="
                    + (frames == null ? 0 : frames.length()) + ", durationMs=" + durationMs);
            return null;
        }

        List<Double> greenSignal = new ArrayList<>();
        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.optJSONObject(i);
            if (frame == null) {
                continue;
            }
            double left = frame.optDouble("left_cheek_g", Double.NaN);
            double right = frame.optDouble("right_cheek_g", Double.NaN);
            double forehead = frame.optDouble("forehead_g", Double.NaN);
            if (!Double.isNaN(left) && !Double.isNaN(right) && !Double.isNaN(forehead)) {
                greenSignal.add((left + right + forehead) / 3.0);
            }
        }

        if (greenSignal.size() < 20) {
            Log.e(TAG, "Not enough valid face frames: " + greenSignal.size());
            return null;
        }

        double[] normalized = normalize(greenSignal);
        double sampleRate = greenSignal.size() / (durationMs / 1000.0);
        int heartRate = clamp(Math.round(estimateDominantFrequency(normalized, sampleRate, 0.75, 3.0, 1.2) * 60), 45, 180);
        int respiratoryRate = clamp(Math.round(estimateDominantFrequency(normalized, sampleRate, 0.12, 0.5, 0.25) * 60), 8, 30);
        int hrvMillis = estimateHrvProxy(greenSignal, heartRate);

        HealthData healthData = new HealthData(heartRate, respiratoryRate, hrvMillis);
        Log.i(TAG, "Local estimation result: " + healthData);
        return healthData;
    }

    private static double[] normalize(List<Double> values) {
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.size();

        double std = 0.0;
        for (double value : values) {
            double diff = value - mean;
            std += diff * diff;
        }
        std = Math.sqrt(std / values.size());
        if (std < 1e-6) {
            std = 1.0;
        }

        double[] output = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            output[i] = (values.get(i) - mean) / std;
        }
        return output;
    }

    private static double estimateDominantFrequency(double[] signal, double sampleRate,
                                                    double minHz, double maxHz, double fallbackHz) {
        double nyquistMax = sampleRate / 2.0 - 0.05;
        double upperHz = Math.min(maxHz, nyquistMax);
        if (upperHz <= minHz) {
            return fallbackHz;
        }

        double bestHz = fallbackHz;
        double bestPower = Double.NEGATIVE_INFINITY;
        int steps = 180;
        for (int i = 0; i <= steps; i++) {
            double hz = minHz + (upperHz - minHz) * i / steps;
            double real = 0.0;
            double imag = 0.0;
            for (int n = 0; n < signal.length; n++) {
                double angle = 2.0 * Math.PI * hz * n / sampleRate;
                real += signal[n] * Math.cos(angle);
                imag -= signal[n] * Math.sin(angle);
            }
            double power = real * real + imag * imag;
            if (power > bestPower) {
                bestPower = power;
                bestHz = hz;
            }
        }
        return bestHz;
    }

    private static int estimateHrvProxy(List<Double> values, int heartRate) {
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.size();

        double variance = 0.0;
        for (double value : values) {
            double diff = value - mean;
            variance += diff * diff;
        }
        double std = Math.sqrt(variance / values.size());
        double coefficient = mean == 0.0 ? 0.0 : Math.abs(std / mean);
        int estimate = (int) Math.round(55 + coefficient * 240 - Math.max(0, heartRate - 75) * 0.45);
        return clamp(estimate, 25, 110);
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
