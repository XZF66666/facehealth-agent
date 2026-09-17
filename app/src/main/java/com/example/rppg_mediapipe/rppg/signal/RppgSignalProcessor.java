package com.example.rppg_mediapipe.rppg.signal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RppgSignalProcessor {
    private RppgSignalProcessor() {
    }

    public static final class Result {
        public final double heartRate;
        public final double fftHeartRate;
        public final double peakHeartRate;
        public final double signalQuality;
        public final String qualityLabel;
        public final boolean valid;
        public final String invalidReason;
        public final double[] normalizedBvp;

        Result(double heartRate, double fftHeartRate, double peakHeartRate,
               double signalQuality, String qualityLabel, boolean valid,
               String invalidReason, double[] normalizedBvp) {
            this.heartRate = heartRate;
            this.fftHeartRate = fftHeartRate;
            this.peakHeartRate = peakHeartRate;
            this.signalQuality = signalQuality;
            this.qualityLabel = qualityLabel;
            this.valid = valid;
            this.invalidReason = invalidReason;
            this.normalizedBvp = normalizedBvp;
        }
    }

    public static Result process(float[] differentialBvp, double fps,
                                 double faceValidRatio, double exposureStability) {
        if (differentialBvp == null || differentialBvp.length < 120 || fps <= 0) {
            return invalid("模型输出长度或帧率无效");
        }
        double[] cumulative = new double[differentialBvp.length];
        double sum = 0;
        for (int i = 0; i < differentialBvp.length; i++) {
            if (!Float.isFinite(differentialBvp[i])) return invalid("模型输出包含非有限值");
            sum += differentialBvp[i];
            cumulative[i] = sum;
        }

        double[] detrended = smoothnessDetrend(cumulative, 100.0);
        double[] filtered = zeroPhaseBandPass(detrended, fps, 0.75, 2.5);
        double[] normalized = normalize(filtered);
        if (!allFinite(normalized)) return invalid("BVP 后处理产生非有限值");

        Spectrum spectrum = dominantFrequency(normalized, fps, 42, 180);
        double peakHr = peakHeartRate(normalized, fps);
        double agreement = Double.isFinite(peakHr)
                ? Math.abs(spectrum.heartRate - peakHr) : 30.0;

        double spectralScore = clamp((spectrum.peakRatio - 0.08) / 0.35, 0, 1);
        double agreementScore = clamp(1.0 - agreement / 25.0, 0, 1);
        double fpsScore = clamp(1.0 - Math.abs(fps - 30.0) / 12.0, 0, 1);
        double sqi = 35 * spectralScore
                + 25 * agreementScore
                + 20 * clamp(faceValidRatio, 0, 1)
                + 10 * fpsScore
                + 10 * clamp(exposureStability, 0, 1);
        sqi = clamp(sqi, 0, 100);

        boolean heartRateValid = spectrum.heartRate >= 42 && spectrum.heartRate <= 210;
        boolean valid = heartRateValid && sqi >= 20;
        String reason = valid ? "" : (heartRateValid ? "信号质量不足" : "心率超出有效范围");
        double combinedHr = Double.isFinite(peakHr) && agreement <= 12
                ? (spectrum.heartRate + peakHr) / 2.0
                : spectrum.heartRate;
        return new Result(
                combinedHr,
                spectrum.heartRate,
                peakHr,
                sqi,
                qualityLabel(sqi),
                valid,
                reason,
                normalized
        );
    }

    private static Result invalid(String reason) {
        return new Result(Double.NaN, Double.NaN, Double.NaN,
                0, "无效", false, reason, new double[0]);
    }

    static double[] smoothnessDetrend(double[] values, double lambda) {
        int n = values.length;
        if (n < 3) return normalize(values);
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) matrix[i][i] = 1.0;
        double lambdaSquared = lambda * lambda;
        double[] coefficients = {1.0, -2.0, 1.0};
        for (int row = 0; row < n - 2; row++) {
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    matrix[row + a][row + b] +=
                            lambdaSquared * coefficients[a] * coefficients[b];
                }
            }
        }
        double[] trend = solvePositiveDefinite(matrix, values);
        double[] output = new double[n];
        for (int i = 0; i < n; i++) output[i] = values[i] - trend[i];
        return output;
    }

    private static double[] solvePositiveDefinite(double[][] matrix, double[] values) {
        int n = values.length;
        double[][] lower = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = matrix[i][j];
                for (int k = 0; k < j; k++) sum -= lower[i][k] * lower[j][k];
                if (i == j) {
                    lower[i][j] = Math.sqrt(Math.max(sum, 1e-12));
                } else {
                    lower[i][j] = sum / lower[j][j];
                }
            }
        }
        double[] intermediate = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = values[i];
            for (int j = 0; j < i; j++) sum -= lower[i][j] * intermediate[j];
            intermediate[i] = sum / lower[i][i];
        }
        double[] output = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = intermediate[i];
            for (int j = i + 1; j < n; j++) sum -= lower[j][i] * output[j];
            output[i] = sum / lower[i][i];
        }
        return output;
    }

    static double[] zeroPhaseBandPass(double[] values, double fps,
                                      double lowHz, double highHz) {
        double[] forward = lowPass(highPass(values, fps, lowHz), fps, highHz);
        reverse(forward);
        double[] backward = lowPass(highPass(forward, fps, lowHz), fps, highHz);
        reverse(backward);
        return backward;
    }

    private static double[] highPass(double[] values, double fps, double cutoff) {
        double[] output = new double[values.length];
        double dt = 1.0 / fps;
        double rc = 1.0 / (2.0 * Math.PI * cutoff);
        double alpha = rc / (rc + dt);
        for (int i = 1; i < values.length; i++) {
            output[i] = alpha * (output[i - 1] + values[i] - values[i - 1]);
        }
        return output;
    }

    private static double[] lowPass(double[] values, double fps, double cutoff) {
        double[] output = new double[values.length];
        if (values.length == 0) return output;
        double dt = 1.0 / fps;
        double rc = 1.0 / (2.0 * Math.PI * cutoff);
        double alpha = dt / (rc + dt);
        output[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            output[i] = output[i - 1] + alpha * (values[i] - output[i - 1]);
        }
        return output;
    }

    private static Spectrum dominantFrequency(double[] values, double fps,
                                              int minBpm, int maxBpm) {
        double bestPower = Double.NEGATIVE_INFINITY;
        double totalPower = 0;
        double bestBpm = 72;
        for (int bpm = minBpm; bpm <= maxBpm; bpm++) {
            double hz = bpm / 60.0;
            double real = 0;
            double imaginary = 0;
            for (int i = 0; i < values.length; i++) {
                double angle = 2.0 * Math.PI * hz * i / fps;
                real += values[i] * Math.cos(angle);
                imaginary -= values[i] * Math.sin(angle);
            }
            double power = real * real + imaginary * imaginary;
            totalPower += power;
            if (power > bestPower) {
                bestPower = power;
                bestBpm = bpm;
            }
        }
        return new Spectrum(bestBpm, bestPower / Math.max(totalPower, 1e-12));
    }

    private static double peakHeartRate(double[] values, double fps) {
        int minimumDistance = Math.max(1, (int) Math.floor(fps * 60.0 / 210.0));
        List<Integer> peaks = new ArrayList<>();
        for (int i = 1; i < values.length - 1; i++) {
            if (values[i] <= values[i - 1] || values[i] < values[i + 1] || values[i] < 0.1) {
                continue;
            }
            if (peaks.isEmpty() || i - peaks.get(peaks.size() - 1) >= minimumDistance) {
                peaks.add(i);
            } else if (values[i] > values[peaks.get(peaks.size() - 1)]) {
                peaks.set(peaks.size() - 1, i);
            }
        }
        if (peaks.size() < 3) return Double.NaN;
        double[] intervals = new double[peaks.size() - 1];
        int count = 0;
        for (int i = 1; i < peaks.size(); i++) {
            double seconds = (peaks.get(i) - peaks.get(i - 1)) / fps;
            if (seconds >= 60.0 / 210.0 && seconds <= 60.0 / 42.0) {
                intervals[count++] = seconds;
            }
        }
        if (count < 2) return Double.NaN;
        Arrays.sort(intervals, 0, count);
        double median = count % 2 == 0
                ? (intervals[count / 2 - 1] + intervals[count / 2]) / 2.0
                : intervals[count / 2];
        return 60.0 / median;
    }

    private static double[] normalize(double[] values) {
        double mean = 0;
        for (double value : values) mean += value;
        mean /= Math.max(1, values.length);
        double variance = 0;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        double standardDeviation = Math.sqrt(variance / Math.max(1, values.length));
        standardDeviation = Math.max(standardDeviation, 1e-8);
        double[] output = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            output[i] = (values[i] - mean) / standardDeviation;
        }
        return output;
    }

    private static boolean allFinite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }

    private static void reverse(double[] values) {
        for (int left = 0, right = values.length - 1; left < right; left++, right--) {
            double temporary = values[left];
            values[left] = values[right];
            values[right] = temporary;
        }
    }

    private static String qualityLabel(double score) {
        if (score >= 80) return "优秀";
        if (score >= 60) return "可用";
        if (score >= 40) return "偏低";
        return "无效";
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Spectrum {
        final double heartRate;
        final double peakRatio;

        Spectrum(double heartRate, double peakRatio) {
            this.heartRate = heartRate;
            this.peakRatio = peakRatio;
        }
    }
}
