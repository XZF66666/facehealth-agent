package com.example.rppg_mediapipe;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Spinner ageSpinner;
    private Spinner activitySpinner;
    private EditText exerciseHabits;
    private CheckBox focusStress;
    private CheckBox focusSleep;
    private CheckBox focusRecovery;
    private CheckBox focusExercise;
    private RadioGroup responseStyleGroup;
    private SeekBar baselineSeekBar;
    private TextView baselineValue;
    private TextView profileStatus;
    private Button saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);
        applySystemBarInsets();
        bindViews();
        configureOptions();
        findViewById(R.id.profileBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.profilePrivacyButton).setOnClickListener(v ->
                startActivity(new Intent(this, PrivacyActivity.class)));
        saveButton.setOnClickListener(v -> saveProfile());
        loadProfile();
    }

    private void bindViews() {
        ageSpinner = findViewById(R.id.profileAgeRange);
        activitySpinner = findViewById(R.id.profileActivityLevel);
        exerciseHabits = findViewById(R.id.profileExerciseHabits);
        focusStress = findViewById(R.id.focusStress);
        focusSleep = findViewById(R.id.focusSleep);
        focusRecovery = findViewById(R.id.focusRecovery);
        focusExercise = findViewById(R.id.focusExercise);
        responseStyleGroup = findViewById(R.id.responseStyleGroup);
        baselineSeekBar = findViewById(R.id.baselineDays);
        baselineValue = findViewById(R.id.baselineValue);
        profileStatus = findViewById(R.id.profileStatus);
        saveButton = findViewById(R.id.profileSaveButton);
    }

    private void configureOptions() {
        setSpinner(ageSpinner, new String[]{"未设置", "18-24", "25-34", "35-44", "45-54", "55+"});
        setSpinner(activitySpinner, new String[]{"未设置", "较少运动", "轻度活动", "中等活动", "规律运动"});
        baselineSeekBar.setMax(83);
        baselineSeekBar.setProgress(21);
        baselineSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                baselineValue.setText((progress + 7) + " 天");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        baselineValue.setText("28 天");
    }

    private void setSpinner(Spinner spinner, String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void loadProfile() {
        setLoading(true, "正在加载健康档案...");
        executor.execute(() -> {
            try {
                JSONObject profile = new AgentApiClient(this).getUserProfile();
                runOnUiThread(() -> {
                    if (profile != null) applyProfile(profile);
                    setLoading(false, "档案只保存结构化偏好，不保存原始人脸视频");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setLoading(false, "网络异常，暂时显示本地默认设置");
                    Toast.makeText(this, "健康档案加载失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void applyProfile(JSONObject profile) {
        selectSpinner(ageSpinner, profile.optString("age_range"));
        selectSpinner(activitySpinner, profile.optString("activity_level"));
        exerciseHabits.setText(join(profile.optJSONArray("exercise_habits")));
        JSONArray focus = profile.optJSONArray("focus_areas");
        focusStress.setChecked(contains(focus, "压力"));
        focusSleep.setChecked(contains(focus, "睡眠"));
        focusRecovery.setChecked(contains(focus, "恢复"));
        focusExercise.setChecked(contains(focus, "运动"));
        String style = profile.optString("response_style", "balanced");
        responseStyleGroup.check("concise".equals(style) ? R.id.responseConcise
                : "detailed".equals(style) ? R.id.responseDetailed
                : R.id.responseBalanced);
        int days = Math.max(7, Math.min(90, profile.optInt("baseline_days", 28)));
        baselineSeekBar.setProgress(days - 7);
    }

    private void saveProfile() {
        setLoading(true, "正在保存并更新 Agent 偏好...");
        JSONObject profile = buildProfile();
        executor.execute(() -> {
            try {
                new AgentApiClient(this).updateUserProfile(profile);
                runOnUiThread(() -> {
                    setLoading(false, "已保存，后续分析将使用新的档案和偏好");
                    Toast.makeText(this, "健康档案已保存", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setLoading(false, "保存失败，请检查后端连接后重试");
                    Toast.makeText(this, "健康档案保存失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private JSONObject buildProfile() {
        JSONObject profile = new JSONObject();
        JSONArray focus = new JSONArray();
        JSONArray habits = new JSONArray();
        try {
            String age = String.valueOf(ageSpinner.getSelectedItem());
            String activity = String.valueOf(activitySpinner.getSelectedItem());
            profile.put("age_range", "未设置".equals(age) ? "" : age);
            profile.put("activity_level", "未设置".equals(activity) ? "" : activity);
            for (String value : exerciseHabits.getText().toString().split("[,，]")) {
                if (!value.trim().isEmpty()) habits.put(value.trim());
            }
            if (focusStress.isChecked()) focus.put("压力");
            if (focusSleep.isChecked()) focus.put("睡眠");
            if (focusRecovery.isChecked()) focus.put("恢复");
            if (focusExercise.isChecked()) focus.put("运动");
            profile.put("exercise_habits", habits);
            profile.put("focus_areas", focus);
            int selected = responseStyleGroup.getCheckedRadioButtonId();
            profile.put("response_style", selected == R.id.responseConcise ? "concise"
                    : selected == R.id.responseDetailed ? "detailed" : "balanced");
            profile.put("baseline_days", baselineSeekBar.getProgress() + 7);
        } catch (Exception ignored) {
            // All values are locally constructed and remain valid JSON primitives.
        }
        return profile;
    }

    private void setLoading(boolean loading, String status) {
        saveButton.setEnabled(!loading);
        saveButton.setText(loading ? "保存中..." : "保存健康档案");
        profileStatus.setText(status);
    }

    private void selectSpinner(Spinner spinner, String value) {
        if (value == null || value.isEmpty()) return;
        for (int index = 0; index < spinner.getCount(); index++) {
            if (value.equals(String.valueOf(spinner.getItemAtPosition(index)))) {
                spinner.setSelection(index);
                return;
            }
        }
    }

    private boolean contains(JSONArray array, String value) {
        if (array == null) return false;
        for (int index = 0; index < array.length(); index++) {
            if (value.equals(array.optString(index))) return true;
        }
        return false;
    }

    private String join(JSONArray array) {
        if (array == null) return "";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < array.length(); index++) {
            if (result.length() > 0) result.append("，");
            result.append(array.optString(index));
        }
        return result.toString();
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.profileRoot);
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
