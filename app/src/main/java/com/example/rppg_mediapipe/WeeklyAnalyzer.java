package com.example.rppg_mediapipe;

import java.util.List;

public class WeeklyAnalyzer {
    public static WeeklySummary analyze(List<HealthRecord> records) {
        WeeklySummary summary = new WeeklySummary();
        if (records == null || records.isEmpty()) {
            return summary;
        }

        for (HealthRecord record : records) {
            summary.avgHeartRate += record.heartRate;
            summary.avgRespiratoryRate += record.respiratoryRate;
            summary.avgHrv += record.hrv;
            summary.avgStressScore += record.stressScore;
            summary.avgFatigueScore += record.fatigueScore;
        }
        int count = records.size();
        summary.avgHeartRate /= count;
        summary.avgRespiratoryRate /= count;
        summary.avgHrv /= count;
        summary.avgStressScore /= count;
        summary.avgFatigueScore /= count;

        for (HealthRecord record : records) {
            if (record.heartRate > summary.avgHeartRate + 8
                    || record.respiratoryRate > summary.avgRespiratoryRate + 3
                    || record.hrv < summary.avgHrv - 5) {
                summary.abnormalDays++;
            }
        }

        if (summary.avgStressScore >= 70) {
            summary.trendText = "本周压力偏高";
        } else if (summary.avgFatigueScore >= 70) {
            summary.trendText = "本周疲劳偏高";
        } else if (summary.avgHrv < 35) {
            summary.trendText = "HRV 偏低，恢复状态可能不足";
        } else {
            summary.trendText = "本周状态整体平稳";
        }
        return summary;
    }
}