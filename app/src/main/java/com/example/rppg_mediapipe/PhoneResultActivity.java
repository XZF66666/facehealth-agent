package com.example.rppg_mediapipe;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class PhoneResultActivity extends AppCompatActivity {
    private static final String TAG = "PhoneResult";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_result);

        TextView heartRateValue = findViewById(R.id.heartRateValue);
        TextView heartRateStatus = findViewById(R.id.heartRateStatus);
        TextView respiratoryRateValue = findViewById(R.id.respiratoryRateValue);
        TextView respiratoryRateStatus = findViewById(R.id.respiratoryRateStatus);
        TextView hrvValue = findViewById(R.id.hrvValue);
        TextView hrvStatus = findViewById(R.id.hrvStatus);
        Button btnBackHome = findViewById(R.id.btnBackHome);
        btnBackHome.setOnClickListener(v -> goHome());

        HealthData healthData = (HealthData) getIntent().getSerializableExtra("health_data");
        if (healthData == null) {
            Log.e(TAG, "Missing health_data extra");
            heartRateValue.setText("--");
            respiratoryRateValue.setText("--");
            hrvValue.setText("--");
            heartRateStatus.setText("数据错误");
            respiratoryRateStatus.setText("数据错误");
            hrvStatus.setText("数据错误");
            return;
        }

        Log.i(TAG, "Render result: " + healthData);
        int heartRate = healthData.getHeartRate();
        int respiratoryRate = healthData.getRespiratoryRate();
        int hrvMillis = healthData.getHrvMillis();

        heartRateValue.setText(heartRate + " BPM");
        respiratoryRateValue.setText(respiratoryRate + " 次/分");
        hrvValue.setText(hrvMillis + " ms");

        setRangeStatus(heartRateStatus, heartRate, 60, 100);
        setRangeStatus(respiratoryRateStatus, respiratoryRate, 12, 20);
        setHrvStatus(hrvStatus, hrvMillis);
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    private void goHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void setRangeStatus(TextView statusView, int value, int min, int max) {
        if (value < min) {
            setWarning(statusView, "偏低");
        } else if (value > max) {
            setWarning(statusView, "偏高");
        } else {
            setNormal(statusView, "正常");
        }
    }

    private void setHrvStatus(TextView statusView, int hrvMillis) {
        if (hrvMillis < 35) {
            setWarning(statusView, "恢复偏弱");
        } else if (hrvMillis < 50) {
            setNormal(statusView, "一般");
        } else {
            setNormal(statusView, "恢复良好");
        }
    }

    private void setNormal(TextView view, String text) {
        view.setText(text);
        view.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
    }

    private void setWarning(TextView view, String text) {
        view.setText(text);
        view.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
    }
}
