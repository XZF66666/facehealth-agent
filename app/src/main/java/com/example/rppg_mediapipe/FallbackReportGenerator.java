package com.example.rppg_mediapipe;

import java.util.List;

public class FallbackReportGenerator {
    public static String todayReport(HealthRecord record, WeeklySummary summary) {
        String trend = summary == null ? "暂无足够历史数据" : summary.trendText;
        String status = record.stressScore >= 70 || record.fatigueScore >= 70 || record.hrv < 35
                ? "今天的压力或疲劳负荷偏高，建议降低强度并关注恢复。"
                : "今天的核心生理指标处于可观察范围内，建议保持稳定作息。";
        return "【今日状态】\n" + status + "\n\n"
                + "【一周趋势】\n根据近 7 天数据，当前趋势为：" + trend + "。\n\n"
                + "【可能原因】\n结合你的睡眠、压力和症状输入，当前状态可能与作息、咖啡因、运动后检测或近期压力有关。\n\n"
                + "【健康建议】\n建议保持规律作息，避免连续熬夜。如果今日压力或疲劳指数偏高，减少高强度运动，适当补水休息，并在稍后复测。\n\n"
                + "【风险提示】\n本系统不提供医学诊断。如出现胸痛、明显胸闷、头晕、出冷汗或持续心悸，请及时就医。";
    }

    public static String weeklyReport(List<HealthRecord> records, WeeklySummary summary) {
        if (records == null || records.isEmpty()) {
            return "近 7 天暂无健康记录。建议先完成一次本地检测。";
        }
        return "本周共记录 " + records.size() + " 次检测。平均心率 " + Math.round(summary.avgHeartRate)
                + " 次/分，平均呼吸率 " + Math.round(summary.avgRespiratoryRate)
                + " 次/分，平均 HRV " + Math.round(summary.avgHrv)
                + " ms。趋势判断：" + summary.trendText
                + "。建议下周继续保持规律睡眠，压力偏高时优先进行低强度活动和呼吸放松训练。\n\n"
                + "风险提示：本系统不作为医学诊断依据，出现胸痛、胸闷、头晕、出冷汗或持续心悸时请及时就医。";
    }

    public static String chatReply(String question, HealthRecord today, WeeklySummary summary) {
        if (today == null) {
            return "你今天还没有检测数据，建议先进行一次 15 秒本地检测，再结合趋势给出建议。";
        }
        return "根据你今天的心率 " + Math.round(today.heartRate)
                + "、呼吸率 " + Math.round(today.respiratoryRate)
                + "、HRV " + Math.round(today.hrv)
                + " 以及趋势“" + summary.trendText + "”，建议优先选择低到中等强度活动。"
                + "如果感到胸闷、头晕或持续心悸，请停止运动并及时就医。";
    }
}