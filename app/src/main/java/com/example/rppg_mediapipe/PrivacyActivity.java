package com.example.rppg_mediapipe;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class PrivacyActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy);

        CheckBox checkBox = findViewById(R.id.privacyCheck);
        Button agreeButton = findViewById(R.id.agreeButton);
        Button deleteButton = findViewById(R.id.deleteButton);
        findViewById(R.id.pageBackButton).setOnClickListener(v -> finish());

        agreeButton.setEnabled(false);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> agreeButton.setEnabled(isChecked));
        agreeButton.setOnClickListener(v -> {
            getSharedPreferences("face_health", MODE_PRIVATE)
                    .edit()
                    .putBoolean("privacy_accepted", true)
                    .apply();
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        });
        deleteButton.setOnClickListener(v -> {
            new HealthDatabaseHelper(this).clearAll();
            Toast.makeText(this, "本地历史数据已删除", Toast.LENGTH_SHORT).show();
        });
    }
}
