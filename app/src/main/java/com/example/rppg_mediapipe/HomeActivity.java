package com.example.rppg_mediapipe;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {
    private TextView dateLabel;
    private TextView greetingText;
    private TextView todayStatus;
    private TextView heartRateValue;
    private TextView respRateValue;
    private TextView hrvValue;
    private TextView agentHint;
    private TextView weeklySummaryView;
    private HealthTrendView trendChart;
    private RingGaugeView stressGauge;
    private RingGaugeView fatigueGauge;
    private ScrollView homeScroll;
    private HealthDatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        db = new HealthDatabaseHelper(this);

        homeScroll = findViewById(R.id.homeScroll);
        dateLabel = findViewById(R.id.dateLabel);
        greetingText = findViewById(R.id.greetingText);
        todayStatus = findViewById(R.id.todayStatus);
        heartRateValue = findViewById(R.id.heartRateValue);
        respRateValue = findViewById(R.id.respRateValue);
        hrvValue = findViewById(R.id.hrvValue);
        agentHint = findViewById(R.id.agentHint);
        weeklySummaryView = findViewById(R.id.weeklySummary);
        trendChart = findViewById(R.id.trendChart);
        stressGauge = findViewById(R.id.stressGauge);
        fatigueGauge = findViewById(R.id.fatigueGauge);

        findViewById(R.id.startDetectButton).setOnClickListener(v -> startActivity(new Intent(this, DetectActivity.class)));
        findViewById(R.id.cameraShortcut).setOnClickListener(v -> startActivity(new Intent(this, DetectActivity.class)));
        findViewById(R.id.navHome).setOnClickListener(v -> homeScroll.smoothScrollTo(0, 0));
        findViewById(R.id.navTrend).setOnClickListener(v -> startActivity(new Intent(this, WeeklyReportActivity.class)));
        findViewById(R.id.navProfile).setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
        findViewById(R.id.todayReportButton).setOnClickListener(v -> openTodayReport());
        findViewById(R.id.weeklyButton).setOnClickListener(v -> startActivity(new Intent(this, WeeklyReportActivity.class)));
        findViewById(R.id.chatButton).setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));
        findViewById(R.id.chatInputBar).setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));
        findViewById(R.id.privacyButton).setOnClickListener(v -> startActivity(new Intent(this, PrivacyActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderDashboard();
    }

    private void renderDashboard() {
        dateLabel.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date()));
        HealthRecord today = db.getTodayRecord();
        List<HealthRecord> recent = db.getRecentRecords(7);
        WeeklySummary summary = WeeklyAnalyzer.analyze(recent);
        trendChart.setRecords(recent);
        homeScroll.postDelayed(() -> homeScroll.scrollTo(0, 0), 120);

        if (today == null) {
            greetingText.setText("你好～\n今天也关注一下身体状态");
            todayStatus.setText("待检测");
            heartRateValue.setText("--");
            respRateValue.setText("--");
            hrvValue.setText("--");
            stressGauge.setGauge(0, "压力", Color.rgb(92, 108, 255));
            fatigueGauge.setGauge(0, "疲劳", Color.rgb(36, 185, 154));
            agentHint.setText("今天还没有健康记录，完成一次本地检测后我会生成建议");
        } else {
            greetingText.setText("你好～\n今日状态已更新");
            todayStatus.setText("已检测");
            heartRateValue.setText(Math.round(today.heartRate) + "");
            respRateValue.setText(Math.round(today.respiratoryRate) + "");
            hrvValue.setText(Math.round(today.hrv) + "");
            stressGauge.setGauge((int) Math.round(today.stressScore), "压力", Color.rgb(92, 108, 255));
            fatigueGauge.setGauge((int) Math.round(today.fatigueScore), "疲劳", Color.rgb(36, 185, 154));
            agentHint.setText(buildAgentHint(today));
        }

        if (recent.isEmpty()) {
            weeklySummaryView.setText("一周摘要\n暂无历史数据。完成检测后，这里会展示近 7 天趋势、异常天数和恢复状态。");
        } else {
            weeklySummaryView.setText("一周摘要\n"
                    + "平均心率 " + Math.round(summary.avgHeartRate) + "  ·  平均呼吸 " + Math.round(summary.avgRespiratoryRate) + "\n"
                    + "平均 HRV " + Math.round(summary.avgHrv) + " ms  ·  异常 " + summary.abnormalDays + " 天\n"
                    + "趋势判断：" + summary.trendText);
        }
    }

    private String buildAgentHint(HealthRecord today) {
        if (today.stressScore >= 70 || today.fatigueScore >= 70) {
            return "今天压力或疲劳指数偏高，建议降低运动强度并关注休息";
        }
        if (today.hrv < 35) {
            return "HRV 偏低，恢复可能不足，建议稍后复测并保证睡眠";
        }
        return "健康数据已记录，我可以结合趋势给你更贴心建议";
    }

    private void openTodayReport() {
        HealthRecord today = db.getTodayRecord();
        if (today == null) {
            Toast.makeText(this, "今天还没有检测记录", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ReportActivity.class);
        intent.putExtra("record_id", today.id);
        startActivity(intent);
    }
}
