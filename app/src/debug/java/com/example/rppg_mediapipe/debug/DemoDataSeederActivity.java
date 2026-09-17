package com.example.rppg_mediapipe.debug;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.rppg_mediapipe.AgentApiClient;
import com.example.rppg_mediapipe.DateUtils;
import com.example.rppg_mediapipe.HealthDatabaseHelper;
import com.example.rppg_mediapipe.HealthRecord;
import com.example.rppg_mediapipe.HomeActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug-only entry point used to prepare a repeatable seven-day demo on a real device.
 * Existing measurements always win, so running this activity never overwrites user data.
 */
public final class DemoDataSeederActivity extends Activity {
    private static final String TAG = "DemoDataSeeder";
    private static final String DEMO_USER_ID = "debug_demo_user";

    private static final double[] HEART_RATE = {78, 77, 76, 74, 73, 72, 70};
    private static final double[] RESPIRATORY_RATE = {17, 17, 16, 16, 15, 15, 14};
    private static final double[] HRV = {40, 42, 45, 48, 51, 54, 57};
    private static final double[] STRESS = {68, 65, 61, 57, 53, 48, 43};
    private static final double[] FATIGUE = {64, 62, 59, 55, 51, 47, 44};
    private static final double[] SLEEP = {6.0, 6.2, 6.5, 6.8, 7.0, 7.2, 7.5};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createLoadingView());
        executor.execute(this::prepareDemo);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout createLoadingView() {
        int padding = dp(28);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(246, 248, 255));

        ProgressBar progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(dp(52), dp(52)));

        statusView = new TextView(this);
        statusView.setText("\u6b63\u5728\u51c6\u5907 7 \u5929\u6f14\u793a\u6570\u636e\u2026");
        statusView.setTextColor(Color.rgb(34, 39, 67));
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = dp(20);
        root.addView(statusView, textParams);
        return root;
    }

    private void prepareDemo() {
        HealthDatabaseHelper database = new HealthDatabaseHelper(this);
        int inserted = 0;
        try {
            Map<String, HealthRecord> recordsByDate = new HashMap<>();
            for (HealthRecord record : database.getRecentRecords(500)) {
                if (record.date != null && !recordsByDate.containsKey(record.date)) {
                    recordsByDate.put(record.date, record);
                }
            }

            List<String> targetDates = createTargetDates();
            for (int i = 0; i < targetDates.size(); i++) {
                String date = targetDates.get(i);
                HealthRecord existing = recordsByDate.get(date);
                boolean isToday = i == targetDates.size() - 1;
                if (existing == null || (isToday && !isDemoReady(existing))) {
                    HealthRecord record = createDemoRecord(date, i);
                    database.insertRecord(record);
                    recordsByDate.put(date, record);
                    inserted++;
                }
            }

            List<HealthRecord> weekRecords = new ArrayList<>();
            for (String date : targetDates) {
                weekRecords.add(recordsByDate.get(date));
            }
            Log.i(TAG, "LOCAL_READY days=" + weekRecords.size() + " inserted=" + inserted);

            runOnUiThread(() -> statusView.setText(
                    "\u672c\u5730\u6570\u636e\u5df2\u5c31\u7eea\uff0c\u6b63\u5728\u540c\u6b65\u540e\u7aef\u2026"
            ));
            new AgentApiClient(this).syncHealthRecords(weekRecords);
            Log.i(TAG, "BACKEND_SYNCED days=" + weekRecords.size());
            showHome("\u5df2\u51c6\u5907\u5e76\u540c\u6b65 7 \u5929\u6f14\u793a\u6570\u636e");
        } catch (Exception error) {
            Log.e(TAG, "DEMO_PREPARE_FAILED inserted=" + inserted, error);
            showHome("\u672c\u5730\u6570\u636e\u5df2\u51c6\u5907\uff0c\u540e\u7aef\u540c\u6b65\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u670d\u52a1");
        } finally {
            database.close();
        }
    }

    private List<String> createTargetDates() {
        List<String> dates = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar today = Calendar.getInstance();
        for (int index = 0; index < 7; index++) {
            Calendar date = (Calendar) today.clone();
            date.add(Calendar.DAY_OF_YEAR, index - 6);
            dates.add(format.format(date.getTime()));
        }
        return dates;
    }

    private HealthRecord createDemoRecord(String date, int index) {
        HealthRecord record = new HealthRecord();
        record.date = date;
        record.userId = DEMO_USER_ID;
        record.createdAt = index == HEART_RATE.length - 1
                ? DateUtils.now()
                : date + " 08:30:00";
        record.heartRate = HEART_RATE[index];
        record.respiratoryRate = RESPIRATORY_RATE[index];
        record.hrv = HRV[index];
        record.stressScore = STRESS[index];
        record.fatigueScore = FATIGUE[index];
        record.sleepHours = SLEEP[index];
        record.videoQuality = "\u5408\u683c";
        record.stressLevel = index < 2
                ? "\u8f83\u9ad8"
                : (index < 5 ? "\u4e2d\u7b49" : "\u8f83\u4f4e");
        record.lateSleep = index < 2;
        record.coffee = index == 1 || index == 3;
        record.afterExercise = index >= 4;
        record.symptoms = index == 0 ? "\u8f7b\u5fae\u75b2\u52b3" : "";
        record.userNote = demoNote(index);
        record.agentReport = "";
        return record;
    }

    private boolean isDemoReady(HealthRecord record) {
        boolean qualityAccepted = "\u5408\u683c".equals(record.videoQuality)
                || "good".equalsIgnoreCase(record.videoQuality);
        return DEMO_USER_ID.equals(record.userId)
                || (qualityAccepted
                && record.heartRate >= 45
                && record.heartRate <= 120
                && record.respiratoryRate >= 10
                && record.respiratoryRate <= 30
                && record.hrv > 0);
    }

    private String demoNote(int index) {
        String[] notes = {
                "\u9879\u76ee\u51b2\u523a\uff0c\u7761\u7720\u504f\u5c11",
                "\u5de5\u4f5c\u8f83\u7d27\u5f20\uff0c\u665a\u95f4\u6563\u6b65",
                "\u51cf\u5c11\u5496\u5561\uff0c\u63d0\u524d\u4f11\u606f",
                "\u72b6\u6001\u9010\u6b65\u6062\u590d",
                "\u5b8c\u6210 30 \u5206\u949f\u6162\u8dd1",
                "\u7761\u7720\u5145\u8db3\uff0c\u7cbe\u529b\u8f83\u597d",
                "\u4eca\u65e5\u72b6\u6001\u7a33\u5b9a"
        };
        return notes[index];
    }

    private void showHome(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
