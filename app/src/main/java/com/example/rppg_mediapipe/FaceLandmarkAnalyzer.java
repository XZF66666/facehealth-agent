package com.example.rppg_mediapipe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.example.rppg_mediapipe.rppg.pipeline.EfficientPhysVideoAnalyzer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

public class FaceLandmarkAnalyzer {
    private static final String TAG = "Analysis";
    private static final int ANALYSIS_FRAME_COUNT = 60;
    private static final int MAX_ANALYSIS_WIDTH = 640;

    public interface ProgressListener {
        void onProgress(String stage, int progress, int validFrames);
    }

    public static HealthData processVideoFromPath(Context context, String videoPath) throws IOException {
        return processVideoFromPath(context, videoPath, null);
    }

    public static HealthData processVideoFromPath(Context context, String videoPath,
                                                  ProgressListener progressListener) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        FaceLandmarker faceLandmarker = null;
        JSONArray frames = new JSONArray();

        try {
            notifyProgress(progressListener, "正在读取视频", 3, 0);
            retriever.setDataSource(videoPath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;
            if (durationMs <= 0) {
                Log.e(TAG, "Invalid video duration: " + durationMs);
                return null;
            }

            notifyProgress(progressListener, "正在加载人脸模型", 10, 0);

            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build();
            FaceLandmarkerOptions options = FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build();
            faceLandmarker = FaceLandmarker.createFromOptions(context, options);
            notifyProgress(progressListener, "正在定位人脸", 15, 0);

            for (int i = 0; i < ANALYSIS_FRAME_COUNT; i++) {
                long timestampMs = (durationMs * i) / ANALYSIS_FRAME_COUNT;
                Bitmap frameBitmap = retriever.getFrameAtTime(
                        timestampMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST
                );
                if (frameBitmap == null) {
                    continue;
                }

                if (frameBitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                    frameBitmap = frameBitmap.copy(Bitmap.Config.ARGB_8888, false);
                }
                frameBitmap = downscaleForAnalysis(frameBitmap, MAX_ANALYSIS_WIDTH);

                MPImage mpImage = new BitmapImageBuilder(frameBitmap).build();
                FaceLandmarkerResult result = faceLandmarker.detectForVideo(mpImage, timestampMs);
                if (result.faceLandmarks().isEmpty()) {
                    notifyProgress(progressListener, "正在定位人脸", 15 + ((i + 1) * 38 / ANALYSIS_FRAME_COUNT), frames.length());
                    continue;
                }

                List<NormalizedLandmark> faceLandmarks = result.faceLandmarks().get(0);
                int width = frameBitmap.getWidth();
                int height = frameBitmap.getHeight();

                int[] foreheadIdx = {108, 107, 55, 8, 285, 336, 337, 151};
                int[] leftCheekIdx = {116, 123, 50, 36, 100, 118, 117};
                int[] rightCheekIdx = {329, 371, 266, 280, 352, 345, 347};
                int[] fullFaceIdx = {162, 132, 136, 152, 365, 401, 389, 332, 10, 103};

                float[] foreheadRGB = computeRegionMeanRGB(frameBitmap, faceLandmarks, foreheadIdx, width, height);
                float[] leftCheekRGB = computeRegionMeanRGB(frameBitmap, faceLandmarks, leftCheekIdx, width, height);
                float[] rightCheekRGB = computeRegionMeanRGB(frameBitmap, faceLandmarks, rightCheekIdx, width, height);
                float[] fullFaceRGB = computeRegionMeanRGB(frameBitmap, faceLandmarks, fullFaceIdx, width, height);

                JSONObject frameData = new JSONObject();
                frameData.put("left_cheek_r", leftCheekRGB[0]);
                frameData.put("left_cheek_g", leftCheekRGB[1]);
                frameData.put("left_cheek_b", leftCheekRGB[2]);
                frameData.put("right_cheek_r", rightCheekRGB[0]);
                frameData.put("right_cheek_g", rightCheekRGB[1]);
                frameData.put("right_cheek_b", rightCheekRGB[2]);
                frameData.put("forehead_r", foreheadRGB[0]);
                frameData.put("forehead_g", foreheadRGB[1]);
                frameData.put("forehead_b", foreheadRGB[2]);
                frameData.put("full_face_r", fullFaceRGB[0]);
                frameData.put("full_face_g", fullFaceRGB[1]);
                frameData.put("full_face_b", fullFaceRGB[2]);
                frameData.put("timestamp", timestampMs);
                frames.put(frameData);
                notifyProgress(progressListener, "正在提取 rPPG 信号",
                        15 + ((i + 1) * 38 / ANALYSIS_FRAME_COUNT), frames.length());
            }

            notifyProgress(progressListener, "正在计算传统算法对照", 55, frames.length());
            HealthData baselineData = VitalSignsEstimator.estimate(frames, durationMs);
            HealthData resultData = baselineData;
            if (baselineData == null) {
                Log.e(TAG, "No valid physiological signal result. validFrames=" + frames.length());
            } else {
                try {
                    EfficientPhysVideoAnalyzer.Result modelResult =
                            EfficientPhysVideoAnalyzer.analyze(
                                    context, videoPath, progressListener
                            );
                    if (modelResult.signal.valid) {
                        int modelHeartRate = (int) Math.round(modelResult.signal.heartRate);
                        resultData = new HealthData(
                                modelHeartRate,
                                baselineData.getRespiratoryRate(),
                                baselineData.getHrvMillis(),
                                HealthData.calculateStressScore(
                                        modelHeartRate,
                                        baselineData.getRespiratoryRate(),
                                        baselineData.getHrvMillis()
                                ),
                                HealthData.calculateFatigueScore(
                                        modelHeartRate,
                                        baselineData.getRespiratoryRate(),
                                        baselineData.getHrvMillis()
                                ),
                                modelResult.signal.qualityLabel,
                                modelResult.inference.modelName,
                                modelResult.inference.modelVersion,
                                modelResult.signal.signalQuality,
                                modelResult.actualFps,
                                modelResult.inference.inferenceTimeMs,
                                true,
                                ""
                        );
                    } else {
                        Log.w(TAG, "EfficientPhys quality rejected; using GREEN+FFT fallback. reason="
                                + modelResult.signal.invalidReason);
                    }
                } catch (Exception modelError) {
                    Log.e(TAG, "EfficientPhys failed; using GREEN+FFT fallback", modelError);
                }
                Log.i(TAG, "Result HR=" + resultData.getHeartRate()
                        + ", RR=" + resultData.getRespiratoryRate()
                        + ", HRV=" + resultData.getHrvMillis()
                        + ", model=" + resultData.getModelName()
                        + ", SQI=" + resultData.getSignalQuality());
            }
            notifyProgress(progressListener, resultData == null ? "有效信号不足" : "分析完成",
                    100, frames.length());
            return resultData;
        } catch (Exception e) {
            Log.e(TAG, "Error processing video", e);
            return null;
        } finally {
            retriever.release();
            if (faceLandmarker != null) {
                faceLandmarker.close();
            }
        }
    }

    private static void notifyProgress(ProgressListener listener, String stage, int progress, int validFrames) {
        if (listener != null) listener.onProgress(stage, progress, validFrames);
    }

    private static float[] computeRegionMeanRGB(Bitmap image, List<NormalizedLandmark> landmarks,
                                                int[] indices, int imageWidth, int imageHeight) {
        if (indices == null || indices.length == 0) {
            return new float[]{0f, 0f, 0f};
        }

        Path path = new Path();
        NormalizedLandmark startLm = landmarks.get(indices[0]);
        path.moveTo(startLm.x() * imageWidth, startLm.y() * imageHeight);
        for (int i = 1; i < indices.length; i++) {
            NormalizedLandmark lm = landmarks.get(indices[i]);
            path.lineTo(lm.x() * imageWidth, lm.y() * imageHeight);
        }
        path.close();

        Bitmap maskBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(maskBitmap);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);

        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        long pixelCount = 0;
        int[] pixels = new int[imageWidth * imageHeight];
        image.getPixels(pixels, 0, imageWidth, 0, 0, imageWidth, imageHeight);

        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                int maskPixel = maskBitmap.getPixel(x, y);
                if ((maskPixel & 0xFFFFFF) == 0xFFFFFF) {
                    int pixel = pixels[y * imageWidth + x];
                    sumR += (pixel >> 16) & 0xFF;
                    sumG += (pixel >> 8) & 0xFF;
                    sumB += pixel & 0xFF;
                    pixelCount++;
                }
            }
        }
        maskBitmap.recycle();

        if (pixelCount == 0) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
                (float) sumR / pixelCount,
                (float) sumG / pixelCount,
                (float) sumB / pixelCount
        };
    }

    private static Bitmap downscaleForAnalysis(Bitmap source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return source;
        }
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (maxWidth / (float) source.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(source, maxWidth, targetHeight, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }
}
