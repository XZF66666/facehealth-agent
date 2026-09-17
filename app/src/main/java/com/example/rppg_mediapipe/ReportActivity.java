package com.example.rppg_mediapipe;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class ReportActivity extends AppCompatActivity {
    private HealthDatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);
        db = new HealthDatabaseHelper(this);

        long recordId = getIntent().getLongExtra("record_id", -1L);
        HealthRecord record = recordId > 0 ? db.getRecordById(recordId) : db.getTodayRecord();
        if (record == null) {
            Toast.makeText(this, "暂无今日报告", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        TextView metrics = findViewById(R.id.reportMetrics);
        TextView comparison = findViewById(R.id.reportComparison);
        TextView report = findViewById(R.id.agentReport);
        Button homeButton = findViewById(R.id.backHomeButton);
        Button retestButton = findViewById(R.id.retestButton);
        Button weeklyButton = findViewById(R.id.openWeeklyButton);
        findViewById(R.id.pageBackButton).setOnClickListener(v -> goHome());

        metrics.setText("今日检测结果\n"
                + "心率：" + Math.round(record.heartRate) + " 次/分钟\n"
                + "呼吸率：" + Math.round(record.respiratoryRate) + " 次/分钟\n"
                + "HRV：" + Math.round(record.hrv) + " ms\n"
                + "压力指数：" + Math.round(record.stressScore) + "/100\n"
                + "疲劳指数：" + Math.round(record.fatigueScore) + "/100\n"
                + "视频质量：" + record.videoQuality);

        List<HealthRecord> recent = db.getRecentRecords(7);
        WeeklySummary summary = WeeklyAnalyzer.analyze(recent);
        comparison.setText("历史对比\n"
                + "较近 7 天平均心率：" + signed(record.heartRate - summary.avgHeartRate) + "\n"
                + "较近 7 天平均呼吸率：" + signed(record.respiratoryRate - summary.avgRespiratoryRate) + "\n"
                + "HRV 较近 7 天平均：" + signed(record.hrv - summary.avgHrv));

        report.setText(record.agentReport == null || record.agentReport.isEmpty()
                ? FallbackReportGenerator.todayReport(record, summary)
                : record.agentReport);

        homeButton.setOnClickListener(v -> goHome());
        retestButton.setOnClickListener(v -> startActivity(new Intent(this, DetectActivity.class)));
        weeklyButton.setOnClickListener(v -> startActivity(new Intent(this, WeeklyReportActivity.class)));
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    private String signed(double value) {
        long rounded = Math.round(value);
        return rounded > 0 ? "+" + rounded : String.valueOf(rounded);
    }

    private void goHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }
}
