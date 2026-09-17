package com.example.rppg_mediapipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class AgentApiClientInstrumentedTest {
    @Test
    public void streamsTwoTurnsAndReusesConversationId() throws Exception {
        HealthRecord today = record("2026-07-24", 49, 8, 65, 45, 42);
        today.sleepHours = 5;
        today.stressLevel = "较高";
        today.lateSleep = true;
        today.userNote = "昨晚睡眠不足";
        HealthRecord previous = record("2026-07-21", 70, 14, 51, 56, 48);

        WeeklySummary summary = new WeeklySummary();
        summary.avgHeartRate = 60;
        summary.avgRespiratoryRate = 11;
        summary.avgHrv = 58;
        summary.avgStressScore = 51;
        summary.avgFatigueScore = 45;
        summary.abnormalDays = 1;
        summary.trendText = "压力分逐步下降";

        Capture first = request(
                "结合今天的指标，我适合进行什么强度的活动？",
                "",
                today,
                previous,
                summary
        );
        Capture second = request(
                "请结合刚才的建议，说明今晚如何恢复。",
                first.conversationId,
                today,
                previous,
                summary
        );

        assertTrue(first.done);
        assertTrue(second.done);
        assertFalse(first.text.toString().isEmpty());
        assertFalse(second.text.toString().isEmpty());
        assertFalse(first.conversationId.isEmpty());
        assertEquals(first.conversationId, second.conversationId);
    }

    private Capture request(
            String message,
            String conversationId,
            HealthRecord today,
            HealthRecord previous,
            WeeklySummary summary
    ) throws Exception {
        Capture capture = new Capture();
        new AgentApiClient(
                InstrumentationRegistry.getInstrumentation().getTargetContext()
        ).streamChat(
                message,
                UUID.randomUUID().toString(),
                today,
                Arrays.asList(today, previous),
                summary,
                conversationId,
                new AgentApiClient.ChatStreamListener() {
                    @Override
                    public void onMeta(String value) {
                        capture.conversationId = value;
                    }

                    @Override
                    public void onDelta(String text) {
                        capture.text.append(text);
                    }

                    @Override
                    public void onDone() {
                        capture.done = true;
                    }

                    @Override
                    public void onError(String code, String message) {
                        capture.error = code + ": " + message;
                    }
                }
        );
        assertTrue(capture.error, capture.error.isEmpty());
        return capture;
    }

    private HealthRecord record(
            String date,
            double heartRate,
            double respiratoryRate,
            double hrv,
            double stress,
            double fatigue
    ) {
        HealthRecord record = new HealthRecord();
        record.date = date;
        record.heartRate = heartRate;
        record.respiratoryRate = respiratoryRate;
        record.hrv = hrv;
        record.stressScore = stress;
        record.fatigueScore = fatigue;
        record.videoQuality = "可用";
        return record;
    }

    private static final class Capture {
        final StringBuilder text = new StringBuilder();
        String conversationId = "";
        String error = "";
        boolean done;
    }
}
