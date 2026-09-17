package com.example.rppg_mediapipe;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.view.View;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;

import java.lang.ref.WeakReference;
import java.util.List;

public class WeeklyReportActivity extends AppCompatActivity {
    private TextView summaryView;
    private TextView listView;
    private TextView reportView;
    private HealthDatabaseHelper db;
    private List<HealthRecord> records;
    private WeeklySummary summary;
    private WeeklyTrendView trendChart;
    private TextView emptyState;
    private Button generateButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weekly_report);
        db = new HealthDatabaseHelper(this);
        ScrollView weeklyScroll = findViewById(R.id.weeklyScroll);
        weeklyScroll.postDelayed(() -> weeklyScroll.scrollTo(0, 0), 120);
        summaryView = findViewById(R.id.weeklySummaryText);
        listView = findViewById(R.id.weeklyListText);
        reportView = findViewById(R.id.weeklyAgentReport);
        trendChart = findViewById(R.id.weeklyTrendChart);
        emptyState = findViewById(R.id.weeklyEmptyState);
        generateButton = findViewById(R.id.generateWeeklyButton);
        Button homeButton = findViewById(R.id.weeklyBackHomeButton);
        findViewById(R.id.pageBackButton).setOnClickListener(v -> finish());

        records = db.getRecentRecords(7);
        summary = WeeklyAnalyzer.analyze(records);
        render();

        generateButton.setOnClickListener(v -> generateReport());
        homeButton.setOnClickListener(v -> goHome());
    }

    private void render() {
        trendChart.setRecords(records);
        boolean isEmpty = records.isEmpty();
        trendChart.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        generateButton.setEnabled(!isEmpty);
        summaryView.setText("近 7 天健康报告\n"
                + "平均心率：" + Math.round(summary.avgHeartRate) + " 次/分钟\n"
                + "平均呼吸率：" + Math.round(summary.avgRespiratoryRate) + " 次/分钟\n"
                + "平均 HRV：" + Math.round(summary.avgHrv) + " ms\n"
                + "平均压力指数：" + Math.round(summary.avgStressScore) + "/100\n"
                + "平均疲劳指数：" + Math.round(summary.avgFatigueScore) + "/100\n"
                + "异常天数：" + summary.abnormalDays + " 天\n"
                + "趋势判断：" + summary.trendText);

        StringBuilder builder = new StringBuilder("日期 | 心率 | 呼吸 | HRV | 压力 | 疲劳");
        for (HealthRecord record : records) {
            builder.append("\n")
                    .append(record.date).append(" | ")
                    .append(Math.round(record.heartRate)).append(" | ")
                    .append(Math.round(record.respiratoryRate)).append(" | ")
                    .append(Math.round(record.hrv)).append(" | ")
                    .append(Math.round(record.stressScore)).append(" | ")
                    .append(Math.round(record.fatigueScore));
        }
        listView.setText(builder.toString());
        reportView.setText(FallbackReportGenerator.weeklyReport(records, summary));
    }

    private void generateReport() {
        if (records.isEmpty() || !generateButton.isEnabled()) return;
        generateButton.setEnabled(false);
        generateButton.setText("正在生成...");
        reportView.setText("正在连接脉镜助手并整理一周趋势，请稍候...");
        new WeeklyTask(this).execute();
    }

    private void goHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @SuppressLint("StaticFieldLeak")
    private static class WeeklyTask extends AsyncTask<Void, Void, String> {
        private final WeakReference<WeeklyReportActivity> activityReference;

        WeeklyTask(WeeklyReportActivity activity) {
            this.activityReference = new WeakReference<>(activity);
        }

        @Override
        protected String doInBackground(Void... voids) {
            WeeklyReportActivity activity = activityReference.get();
            if (activity == null) return "";
            try {
                return new AgentApiClient(activity)
                        .generateWeeklyReport(activity.records, activity.summary);
            } catch (Exception e) {
                return "后端连接失败，已生成本地简易周报。\n\n"
                        + FallbackReportGenerator.weeklyReport(activity.records, activity.summary);
            }
        }

        @Override
        protected void onPostExecute(String report) {
            WeeklyReportActivity activity = activityReference.get();
            if (activity != null) {
                activity.reportView.setText(report);
                activity.generateButton.setEnabled(true);
                activity.generateButton.setText("重新生成智能周报");
            }
        }
    }
}
