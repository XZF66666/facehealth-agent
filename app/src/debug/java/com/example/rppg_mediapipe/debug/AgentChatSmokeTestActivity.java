package com.example.rppg_mediapipe.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import com.example.rppg_mediapipe.AgentApiClient;
import com.example.rppg_mediapipe.HealthRecord;
import com.example.rppg_mediapipe.WeeklySummary;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentChatSmokeTestActivity extends Activity {
    private static final String TAG = "AgentChatSmoke";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        executor.execute(() -> {
            try {
                HealthRecord today = createRecord("2026-07-24", 49, 8, 65, 45, 42);
                today.sleepHours = 5;
                today.stressLevel = "较高";
                today.lateSleep = true;
                today.userNote = "昨晚睡眠不足";
                HealthRecord previous = createRecord("2026-07-21", 70, 14, 51, 56, 48);

                WeeklySummary summary = new WeeklySummary();
                summary.avgHeartRate = 60;
                summary.avgRespiratoryRate = 11;
                summary.avgHrv = 58;
                summary.avgStressScore = 51;
                summary.avgFatigueScore = 45;
                summary.abnormalDays = 1;
                summary.trendText = "压力分逐步下降";

                String firstMessage = "结合今天的指标，我适合进行什么强度的活动？";
                String firstMessageId = UUID.randomUUID().toString();
                StreamCapture first = runChat(
                        firstMessage,
                        firstMessageId,
                        today,
                        previous,
                        summary,
                        ""
                );
                StreamCapture second = runChat(
                        firstMessage,
                        firstMessageId,
                        today,
                        previous,
                        summary,
                        first.conversationId
                );
                boolean passed = first.done
                        && second.done
                        && first.text.length() > 0
                        && second.text.length() > 0
                        && first.text.toString().equals(second.text.toString())
                        && first.conversationId.equals(second.conversationId);
                if (!passed) {
                    throw new IllegalStateException(
                            "Invalid stream state firstDone=" + first.done
                                    + " secondDone=" + second.done
                                    + " firstLength=" + first.text.length()
                                    + " secondLength=" + second.text.length()
                                    + " idempotentRetry="
                                    + first.text.toString().equals(second.text.toString())
                                    + " conversationReuse="
                                    + first.conversationId.equals(second.conversationId)
                    );
                }
                Log.i(TAG, "SMOKE_PASS requests=2 firstLength=" + first.text.length()
                        + " secondLength=" + second.text.length()
                        + " idempotentRetry=true"
                        + " conversationReuse=true");
            } catch (Throwable error) {
                Log.e(TAG, "SMOKE_FAIL", error);
            } finally {
                runOnUiThread(this::finish);
            }
        });
    }

    private StreamCapture runChat(
            String message,
            String clientMessageId,
            HealthRecord today,
            HealthRecord previous,
            WeeklySummary summary,
            String conversationId
    ) throws Exception {
        StreamCapture capture = new StreamCapture();
        new AgentApiClient(this).streamChat(
                message,
                clientMessageId,
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
        if (!capture.error.isEmpty()) {
            throw new IllegalStateException(capture.error);
        }
        return capture;
    }

    private HealthRecord createRecord(
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

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class StreamCapture {
        final StringBuilder text = new StringBuilder();
        String conversationId = "";
        String error = "";
        boolean done;
    }
}
