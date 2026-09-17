package com.example.rppg_mediapipe;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.lang.ref.WeakReference;

public class PhoneAnalysisActivity extends AppCompatActivity {
    private static final String TAG = "PhoneAnalysis";
    private TextView titleView;
    private TextView subtitleView;
    private TextView frameStatusView;
    private TextView stageFace;
    private TextView stageSignal;
    private TextView stageEstimate;
    private ProgressBar progressBar;
    private View failureActions;
    private Button retryButton;
    private String videoPath;
    private ProcessVideoTask processTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_analysis);
        titleView = findViewById(R.id.analysisTitle);
        subtitleView = findViewById(R.id.analysisSubtitle);
        frameStatusView = findViewById(R.id.analysisFrameStatus);
        stageFace = findViewById(R.id.stageFace);
        stageSignal = findViewById(R.id.stageSignal);
        stageEstimate = findViewById(R.id.stageEstimate);
        progressBar = findViewById(R.id.analysisProgress);
        failureActions = findViewById(R.id.analysisFailureActions);
        retryButton = findViewById(R.id.retryAnalysisButton);
        retryButton.setOnClickListener(v -> startAnalysis());
        findViewById(R.id.reRecordButton).setOnClickListener(v -> {
            Intent intent = new Intent(this, DetectActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        videoPath = getIntent().getStringExtra("video_path");
        Log.i(TAG, "Analysis page opened. videoPath=" + videoPath);
        if (videoPath == null || !new File(videoPath).exists()) {
            showFailure("没有找到录制视频", "视频文件可能已被删除，请重新录制后再分析。", false);
            return;
        }
        startAnalysis();
    }

    private void startAnalysis() {
        if (processTask != null && processTask.getStatus() != AsyncTask.Status.FINISHED) return;
        if (videoPath == null || !new File(videoPath).exists()) {
            showFailure("没有找到录制视频", "请重新录制后再分析。", false);
            return;
        }
        failureActions.setVisibility(View.GONE);
        retryButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(3);
        titleView.setText("正在本机分析视频");
        subtitleView.setText("读取视频并准备人脸关键点模型...");
        frameStatusView.setText("所有处理均在手机本地完成");
        updateStages(3);
        processTask = new ProcessVideoTask(this, videoPath);
        processTask.execute();
    }

    private void showProgress(AnalysisUpdate update) {
        progressBar.setProgress(update.progress);
        titleView.setText(update.stage);
        subtitleView.setText(progressDescription(update.progress));
        frameStatusView.setText(update.validFrames > 0
                ? "已提取 " + update.validFrames + " 个有效人脸信号帧"
                : "正在寻找稳定、完整的人脸区域");
        updateStages(update.progress);
    }

    private String progressDescription(int progress) {
        if (progress < 15) return "正在读取视频并加载本地模型...";
        if (progress < 55) return "正在定位人脸并计算传统算法对照...";
        if (progress < 90) return "正在构建 180 帧 EfficientPhys 输入...";
        if (progress < 96) return "正在手机 CPU 上重建脉搏波形...";
        return "正在计算心率并评估信号质量...";
    }

    private void updateStages(int progress) {
        stageFace.setTextColor(getColor(progress >= 15 ? R.color.primary_dark : R.color.muted));
        stageSignal.setTextColor(getColor(progress >= 40 ? R.color.blue : R.color.muted));
        stageEstimate.setTextColor(getColor(progress >= 90 ? R.color.amber : R.color.muted));
    }

    private void showFailure(String title, String detail, boolean canRetry) {
        titleView.setText(title);
        subtitleView.setText(detail);
        frameStatusView.setText("请保持正脸、光线均匀，并减少头部移动");
        progressBar.setVisibility(View.GONE);
        failureActions.setVisibility(View.VISIBLE);
        retryButton.setEnabled(canRetry);
        retryButton.setAlpha(canRetry ? 1f : 0.45f);
    }

    @Override
    protected void onDestroy() {
        if (processTask != null) processTask.cancel(true);
        super.onDestroy();
    }

    @SuppressLint("StaticFieldLeak")
    private static class ProcessVideoTask extends AsyncTask<Void, AnalysisUpdate, HealthData> {
        private final WeakReference<PhoneAnalysisActivity> activityReference;
        private final String videoPath;

        ProcessVideoTask(PhoneAnalysisActivity activity, String videoPath) {
            activityReference = new WeakReference<>(activity);
            this.videoPath = videoPath;
        }

        @Override
        protected HealthData doInBackground(Void... voids) {
            try {
                PhoneAnalysisActivity activity = activityReference.get();
                if (activity == null) return null;
                return FaceLandmarkAnalyzer.processVideoFromPath(activity, videoPath,
                        (stage, progress, validFrames) -> {
                            if (!isCancelled()) publishProgress(new AnalysisUpdate(stage, progress, validFrames));
                        });
            } catch (Exception e) {
                Log.e(TAG, "Video processing failed", e);
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(AnalysisUpdate... values) {
            PhoneAnalysisActivity activity = activityReference.get();
            if (activity != null && values.length > 0) activity.showProgress(values[0]);
        }

        @Override
        protected void onPostExecute(HealthData healthData) {
            PhoneAnalysisActivity activity = activityReference.get();
            if (activity == null || activity.isFinishing()) return;
            if (healthData == null) {
                activity.showFailure("未获得稳定信号",
                        "有效人脸帧不足或面部颜色变化过弱。可以重新分析当前视频，或调整光线后重新录制。", true);
                return;
            }
            Intent intent = new Intent(activity, StateInputActivity.class);
            intent.putExtra("health_data", healthData);
            activity.startActivity(intent);
            activity.finish();
        }
    }

    private static class AnalysisUpdate {
        final String stage;
        final int progress;
        final int validFrames;

        AnalysisUpdate(String stage, int progress, int validFrames) {
            this.stage = stage;
            this.progress = progress;
            this.validFrames = validFrames;
        }
    }
}
