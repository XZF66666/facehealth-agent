package com.example.rppg_mediapipe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ModernCaptureActivity extends AppCompatActivity {

    private static final String TAG = "ModernCapture";
    private static final int REQUEST_PERMISSIONS = 200;
    private static final long MEASUREMENT_DURATION_MS = 15_000L;
    private static final int TARGET_VIDEO_WIDTH = 1920;
    private static final int TARGET_VIDEO_HEIGHT = 1080;

    private TextureView textureView;
    private TextView tvCaptureStatus;
    private TextView tvCaptureHint;
    private TextView tvCaptureTimer;
    private TextView tvCaptureQuality;
    private ProgressBar progressCapture;
    private Button startButton;
    private Button stopButton;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String videoPath;
    private Size previewSize = new Size(TARGET_VIDEO_WIDTH, TARGET_VIDEO_HEIGHT);
    private Size videoSize = new Size(TARGET_VIDEO_WIDTH, TARGET_VIDEO_HEIGHT);

    private boolean isRecording = false;
    private long recordingStartedAtMs = 0L;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) {
                return;
            }

            long elapsed = System.currentTimeMillis() - recordingStartedAtMs;
            long remaining = Math.max(0L, MEASUREMENT_DURATION_MS - elapsed);
            updateProgress(elapsed, remaining);

            if (elapsed >= MEASUREMENT_DURATION_MS) {
                stopRecording(true);
            } else {
                uiHandler.postDelayed(this, 250L);
            }
        }
    };

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCameraIfPermitted();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_modern);

        textureView = findViewById(R.id.textureView);
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus);
        tvCaptureHint = findViewById(R.id.tvCaptureHint);
        tvCaptureTimer = findViewById(R.id.tvCaptureTimer);
        tvCaptureQuality = findViewById(R.id.tvCaptureQuality);
        progressCapture = findViewById(R.id.progressCapture);
        startButton = findViewById(R.id.btnStart);
        stopButton = findViewById(R.id.btnStop);

        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording(false));
        textureView.setSurfaceTextureListener(surfaceTextureListener);

        resetCaptureUi();
        requestPermissions();
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCameraIfPermitted();
            } else {
                Toast.makeText(this, "需要相机权限才能测量", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void openCameraIfPermitted() {
        if (!textureView.isAvailable() || cameraDevice != null) {
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        openCamera();
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = findFrontCameraId(manager);
            if (cameraId == null) {
                Toast.makeText(this, "没有找到前置摄像头", Toast.LENGTH_SHORT).show();
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
                previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class), videoSize);
                tvCaptureQuality.setText(String.format(Locale.US, "%dp / 30fps", videoSize.getHeight()));
            }

            configureTransform(textureView.getWidth(), textureView.getHeight());
            manager.openCamera(cameraId, stateCallback, null);
            tvCaptureStatus.setText("摄像头已就绪");
            tvCaptureHint.setText("头顶和下巴都保持在框内，点击开始后连续录制 15 秒。");
        } catch (CameraAccessException e) {
            Log.e(TAG, "Open camera failed", e);
            Toast.makeText(this, "打开摄像头失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String findFrontCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        return null;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            finish();
        }
    };

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) {
            return;
        }

        try {
            closeCaptureSession();
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                return;
            }
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(surfaceTexture);
            CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            applyVideoCaptureControls(previewRequestBuilder);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Preview request failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(ModernCaptureActivity.this, "预览配置失败", Toast.LENGTH_SHORT).show();
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Start preview failed", e);
        }
    }

    private void startRecording() {
        if (isRecording) {
            Toast.makeText(this, "正在录制中", Toast.LENGTH_SHORT).show();
            return;
        }
        if (cameraDevice == null || !textureView.isAvailable()) {
            Toast.makeText(this, "摄像头尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            closeCaptureSession();
            setupMediaRecorder();

            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                return;
            }
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recorderSurface = mediaRecorder.getSurface();

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recorderSurface);
            applyVideoCaptureControls(builder);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(builder.build(), null, null);
                                mediaRecorder.start();
                                onRecordingStarted();
                            } catch (CameraAccessException | RuntimeException e) {
                                Log.e(TAG, "Start recording failed", e);
                                Toast.makeText(ModernCaptureActivity.this, "录制启动失败", Toast.LENGTH_SHORT).show();
                                resetCaptureUi();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(ModernCaptureActivity.this, "录制配置失败", Toast.LENGTH_SHORT).show();
                        }
                    },
                    null
            );
        } catch (CameraAccessException | IOException e) {
            Log.e(TAG, "Recording setup failed", e);
            Toast.makeText(this, "录制配置失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void onRecordingStarted() {
        isRecording = true;
        recordingStartedAtMs = System.currentTimeMillis();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        tvCaptureStatus.setText("正在录制");
        tvCaptureHint.setText("保持静止和自然呼吸，录制满 15 秒后自动分析。");
        progressCapture.setProgress(0);
        uiHandler.post(progressRunnable);
    }

    private void stopRecording(boolean autoFinished) {
        if (!isRecording) {
            Toast.makeText(this, "还没有开始录制", Toast.LENGTH_SHORT).show();
            return;
        }

        uiHandler.removeCallbacks(progressRunnable);
        isRecording = false;

        try {
            mediaRecorder.stop();
            tvCaptureStatus.setText(autoFinished ? "录制完成" : "已停止录制");
            tvCaptureHint.setText("正在进入本机端视频分析...");
            Toast.makeText(this, "录制完成，开始分析", Toast.LENGTH_SHORT).show();
            navigateToAnalysis();
        } catch (RuntimeException e) {
            Log.e(TAG, "Stop recording failed", e);
            Toast.makeText(this, "录制失败，请重试", Toast.LENGTH_SHORT).show();
            startPreview();
        } finally {
            releaseMediaRecorder();
            resetCaptureUi();
        }
    }

    private void setupMediaRecorder() throws IOException {
        releaseMediaRecorder();
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        File videoFile = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "recorded_video.mp4");
        videoPath = videoFile.getAbsolutePath();
        mediaRecorder.setOutputFile(videoPath);

        mediaRecorder.setVideoEncodingBitRate(12 * 1024 * 1024);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setMaxDuration((int) MEASUREMENT_DURATION_MS + 1000);
        mediaRecorder.prepare();
    }

    private void navigateToAnalysis() {
        Intent intent = new Intent(this, PhoneAnalysisActivity.class);
        intent.putExtra("video_path", videoPath);
        startActivity(intent);
    }

    private void applyVideoCaptureControls(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    private Size chooseVideoSize(Size[] choices) {
        if (choices == null || choices.length == 0) {
            return new Size(TARGET_VIDEO_WIDTH, TARGET_VIDEO_HEIGHT);
        }

        List<Size> sixteenNine = new ArrayList<>();
        for (Size size : choices) {
            boolean isSixteenNine = size.getWidth() * 9 == size.getHeight() * 16;
            boolean isUnderTarget = size.getWidth() <= TARGET_VIDEO_WIDTH && size.getHeight() <= TARGET_VIDEO_HEIGHT;
            if (isSixteenNine && isUnderTarget) {
                sixteenNine.add(size);
            }
        }
        if (!sixteenNine.isEmpty()) {
            return Collections.max(sixteenNine, Comparator.comparingInt(s -> s.getWidth() * s.getHeight()));
        }
        return Collections.max(Arrays.asList(choices), Comparator.comparingInt(s -> s.getWidth() * s.getHeight()));
    }

    private Size choosePreviewSize(Size[] choices, Size preferred) {
        if (choices == null || choices.length == 0) {
            return preferred;
        }

        for (Size size : choices) {
            if (size.equals(preferred)) {
                return size;
            }
        }
        return chooseVideoSize(choices);
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (viewWidth == 0 || viewHeight == 0 || previewSize == null) {
            return;
        }

        Matrix matrix = new Matrix();
        RectF textureRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        matrix.setRectToRect(textureRect, viewRect, Matrix.ScaleToFit.CENTER);
        textureView.setTransform(matrix);
    }

    private void updateProgress(long elapsedMs, long remainingMs) {
        progressCapture.setProgress((int) Math.min(MEASUREMENT_DURATION_MS, elapsedMs));
        long seconds = (long) Math.ceil(remainingMs / 1000.0);
        tvCaptureTimer.setText(String.format(Locale.US, "00:%02d", seconds));
    }

    private void resetCaptureUi() {
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        progressCapture.setMax((int) MEASUREMENT_DURATION_MS);
        progressCapture.setProgress(0);
        tvCaptureTimer.setText("00:15");
        if (cameraDevice == null) {
            tvCaptureStatus.setText("正在准备摄像头");
            tvCaptureHint.setText("请让头顶和下巴都在框内，保持光线稳定。录制 15 秒后自动分析。");
        }
    }

    private void closeCaptureSession() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(progressRunnable);
        closeCaptureSession();
        releaseMediaRecorder();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }
}
