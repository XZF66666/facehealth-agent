package com.example.rppg_mediapipe;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class StateInputActivity extends AppCompatActivity {
    private HealthData healthData;
    private EditText sleepInput;
    private EditText noteInput;
    private RadioGroup stressGroup;
    private CheckBox lateSleepCheck;
    private CheckBox coffeeCheck;
    private CheckBox exerciseCheck;
    private CheckBox symptomFatigue;
    private CheckBox symptomPalpitation;
    private CheckBox symptomChest;
    private CheckBox symptomDizzy;
    private Button saveButton;
    private Button skipButton;
    private View saveLoadingPanel;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_state_input);

        healthData = (HealthData) getIntent().getSerializableExtra("health_data");
        if (healthData == null) {
            healthData = MockRppgAnalyzer.generate();
            Toast.makeText(this, "未收到测量结果，已生成模拟数据", Toast.LENGTH_SHORT).show();
        }

        TextView metricsView = findViewById(R.id.metricsText);
        metricsView.setText(formatMetrics(healthData));
        sleepInput = findViewById(R.id.sleepInput);
        noteInput = findViewById(R.id.noteInput);
        stressGroup = findViewById(R.id.stressGroup);
        lateSleepCheck = findViewById(R.id.lateSleepCheck);
        coffeeCheck = findViewById(R.id.coffeeCheck);
        exerciseCheck = findViewById(R.id.exerciseCheck);
        symptomFatigue = findViewById(R.id.symptomFatigue);
        symptomPalpitation = findViewById(R.id.symptomPalpitation);
        symptomChest = findViewById(R.id.symptomChest);
        symptomDizzy = findViewById(R.id.symptomDizzy);

        saveButton = findViewById(R.id.saveAnalyzeButton);
        skipButton = findViewById(R.id.skipSaveButton);
        saveLoadingPanel = findViewById(R.id.saveLoadingPanel);
        findViewById(R.id.pageBackButton).setOnClickListener(v -> {
            if (!saving) finish();
        });
        saveButton.setOnClickListener(v -> saveAndAnalyze());
        skipButton.setOnClickListener(v -> {
            noteInput.setText("");
            saveAndAnalyze();
        });
    }

    private String formatMetrics(HealthData data) {
        String metrics = "心率：" + data.getHeartRate() + " 次/分钟\n"
                + "呼吸率：" + data.getRespiratoryRate() + " 次/分钟\n"
                + "HRV：" + data.getHrvMillis() + " ms\n"
                + "压力指数：" + data.getStressScore() + "/100\n"
                + "疲劳指数：" + data.getFatigueScore() + "/100";
        if ("EfficientPhys".equals(data.getModelName())) {
            metrics += "\n信号质量：" + Math.round(data.getSignalQuality()) + "/100"
                    + "\n本地模型：EfficientPhys";
        }
        return metrics;
    }

    private void saveAndAnalyze() {
        if (saving) return;
        setSaving(true);
        HealthRecord record = HealthRecord.fromHealthData(healthData);
        record.sleepHours = parseDouble(sleepInput.getText().toString());
        record.stressLevel = selectedStressLevel();
        record.lateSleep = lateSleepCheck.isChecked();
        record.coffee = coffeeCheck.isChecked();
        record.afterExercise = exerciseCheck.isChecked();
        record.symptoms = selectedSymptoms();
        record.userNote = noteInput.getText().toString().trim();

        HealthDatabaseHelper db = new HealthDatabaseHelper(this);
        long id = db.insertRecord(record);
        if (id <= 0) {
            setSaving(false);
            Toast.makeText(this, "保存失败，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        record.id = id;
        new AnalyzeTask(this, record).execute();
    }

    private void setSaving(boolean value) {
        saving = value;
        saveButton.setEnabled(!value);
        skipButton.setEnabled(!value);
        saveButton.setText(value ? "正在生成分析..." : "保存并生成分析");
        saveLoadingPanel.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private String selectedStressLevel() {
        int checkedId = stressGroup.getCheckedRadioButtonId();
        RadioButton button = checkedId == -1 ? null : findViewById(checkedId);
        return button == null ? "中" : button.getText().toString();
    }

    private String selectedSymptoms() {
        List<String> symptoms = new ArrayList<>();
        if (symptomFatigue.isChecked()) symptoms.add("疲劳");
        if (symptomPalpitation.isChecked()) symptoms.add("心慌");
        if (symptomChest.isChecked()) symptoms.add("胸闷");
        if (symptomDizzy.isChecked()) symptoms.add("头晕");
        return android.text.TextUtils.join(",", symptoms);
    }

    private double parseDouble(String value) {
        try {
            return value == null || value.trim().isEmpty() ? 0 : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressLint("StaticFieldLeak")
    private static class AnalyzeTask extends AsyncTask<Void, Void, String> {
        private final WeakReference<StateInputActivity> activityReference;
        private final HealthRecord record;

        AnalyzeTask(StateInputActivity activity, HealthRecord record) {
            this.activityReference = new WeakReference<>(activity);
            this.record = record;
        }

        @Override
        protected String doInBackground(Void... voids) {
            StateInputActivity activity = activityReference.get();
            if (activity == null) return "";
            HealthDatabaseHelper db = new HealthDatabaseHelper(activity);
            WeeklySummary summary = WeeklyAnalyzer.analyze(db.getRecentRecords(7));
            try {
                return new AgentApiClient(activity).analyzeToday(record, summary);
            } catch (Exception e) {
                return "后端连接失败，已生成本地简易分析。\n\n" + FallbackReportGenerator.todayReport(record, summary);
            }
        }

        @Override
        protected void onPostExecute(String report) {
            StateInputActivity activity = activityReference.get();
            if (activity == null) return;
            new HealthDatabaseHelper(activity).updateAgentReport(record.id, report);
            Intent intent = new Intent(activity, ReportActivity.class);
            intent.putExtra("record_id", record.id);
            activity.startActivity(intent);
            activity.finish();
        }
    }
}
