package com.example.rppg_mediapipe.rppg.pipeline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.example.rppg_mediapipe.FaceLandmarkAnalyzer;
import com.example.rppg_mediapipe.rppg.model.EfficientPhysEngine;
import com.example.rppg_mediapipe.rppg.model.EfficientPhysMetadata;
import com.example.rppg_mediapipe.rppg.model.RppgInferenceResult;
import com.example.rppg_mediapipe.rppg.signal.RppgSignalProcessor;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.List;
import java.util.Locale;

public final class EfficientPhysVideoAnalyzer {
    private static final String TAG = "EfficientPhysVideo";
    private static final int MAX_ANALYSIS_WIDTH = 640;
    private static final int VALIDITY_CHECK_INTERVAL = 15;

    private EfficientPhysVideoAnalyzer() {
    }

    public static final class Result {
        public final RppgInferenceResult inference;
        public final RppgSignalProcessor.Result signal;
        public final double actualFps;
        public final double validFrameRatio;

        Result(RppgInferenceResult inference, RppgSignalProcessor.Result signal,
               double actualFps, double validFrameRatio) {
            this.inference = inference;
            this.signal = signal;
            this.actualFps = actualFps;
            this.validFrameRatio = validFrameRatio;
        }
    }

    public static Result analyze(Context context, String videoPath,
                                 FaceLandmarkAnalyzer.ProgressListener progressListener)
            throws Exception {
        EfficientPhysEngine engine = EfficientPhysEngine.getInstance(context);
        EfficientPhysMetadata metadata = engine.getMetadata();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        FaceLandmarker landmarker = null;
        try {
            notifyProgress(progressListener, "正在准备 EfficientPhys", 58, 0);
            retriever.setDataSource(videoPath);
            long durationMs = parseLong(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0
            );
            if (durationMs <= 0) throw new IllegalArgumentException("视频时长无效");

            double sourceFps = parseDouble(
                    retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                    ),
                    30.0
            );
            sourceFps = Math.max(15.0, Math.min(60.0, sourceFps));
            double requestedSpanMs = (metadata.frameCount - 1) * 1000.0 / sourceFps;
            double spanMs = Math.min(Math.max(1.0, durationMs - 1.0), requestedSpanMs);
            long segmentStartMs = Math.max(0L, Math.round((durationMs - spanMs) / 2.0));
            long[] timestampsMs = new long[metadata.frameCount];
            for (int i = 0; i < timestampsMs.length; i++) {
                timestampsMs[i] = segmentStartMs
                        + Math.round(spanMs * i / (timestampsMs.length - 1.0));
            }
            double actualFps = (timestampsMs.length - 1) * 1000.0
                    / Math.max(1.0, timestampsMs[timestampsMs.length - 1] - timestampsMs[0]);

            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build();
            FaceLandmarkerOptions options = FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .build();
            landmarker = FaceLandmarker.createFromOptions(context, options);

            Rect cropBox = locateStableFace(retriever, landmarker, timestampsMs);
            if (cropBox == null) throw new IllegalStateException("未检测到完整人脸");

            float[] input = new float[metadata.inputElementCount()];
            double[] luma = new double[metadata.frameCount];
            double sum = 0;
            double sumSquares = 0;
            int checkedFrames = 0;
            int validFrames = 0;
            int planeSize = metadata.height * metadata.width;

            for (int frameIndex = 0; frameIndex < metadata.frameCount; frameIndex++) {
                Bitmap frame = frameAt(retriever, timestampsMs[frameIndex]);
                if (frame == null) throw new IllegalStateException("视频解码帧不足");
                Bitmap analysisFrame = downscale(frame, MAX_ANALYSIS_WIDTH);
                if (frameIndex % VALIDITY_CHECK_INTERVAL == 0) {
                    checkedFrames++;
                    if (hasFace(analysisFrame, landmarker)) validFrames++;
                }

                Rect safeCrop = clampRect(cropBox, analysisFrame.getWidth(), analysisFrame.getHeight());
                Bitmap cropped = Bitmap.createBitmap(
                        analysisFrame,
                        safeCrop.left,
                        safeCrop.top,
                        safeCrop.width(),
                        safeCrop.height()
                );
                Bitmap resized = Bitmap.createScaledBitmap(
                        cropped, metadata.width, metadata.height, true
                );
                int[] pixels = new int[planeSize];
                resized.getPixels(
                        pixels, 0, metadata.width, 0, 0, metadata.width, metadata.height
                );
                double frameLuma = 0;
                int frameOffset = frameIndex * metadata.channels * planeSize;
                for (int pixelIndex = 0; pixelIndex < pixels.length; pixelIndex++) {
                    int pixel = pixels[pixelIndex];
                    float red = (pixel >> 16) & 0xFF;
                    float green = (pixel >> 8) & 0xFF;
                    float blue = pixel & 0xFF;
                    input[frameOffset + pixelIndex] = red;
                    input[frameOffset + planeSize + pixelIndex] = green;
                    input[frameOffset + 2 * planeSize + pixelIndex] = blue;
                    sum += red + green + blue;
                    sumSquares += red * red + green * green + blue * blue;
                    frameLuma += 0.299 * red + 0.587 * green + 0.114 * blue;
                }
                luma[frameIndex] = frameLuma / planeSize;

                if (resized != cropped) resized.recycle();
                cropped.recycle();
                analysisFrame.recycle();
                notifyProgress(
                        progressListener,
                        "正在构建人脸时序",
                        60 + ((frameIndex + 1) * 28 / metadata.frameCount),
                        validFrames
                );
            }

            int elementCount = input.length;
            double mean = sum / elementCount;
            double variance = Math.max(0, sumSquares / elementCount - mean * mean);
            double standardDeviation = Math.sqrt(variance);
            if (standardDeviation < 1e-6) throw new IllegalStateException("画面变化不足");
            for (int i = 0; i < input.length; i++) {
                input[i] = (float) ((input[i] - mean) / standardDeviation);
            }

            notifyProgress(progressListener, "正在运行 EfficientPhys", 90, validFrames);
            RppgInferenceResult inference = engine.run(input);
            double faceRatio = validFrames / (double) Math.max(1, checkedFrames);
            double exposureStability = exposureStability(luma);
            notifyProgress(progressListener, "正在重建脉搏波形", 95, validFrames);
            RppgSignalProcessor.Result signal = RppgSignalProcessor.process(
                    inference.diffNormalizedBvp,
                    actualFps,
                    faceRatio,
                    exposureStability
            );
            Log.i(TAG, String.format(
                    Locale.US,
                    "Local model result HR=%.1f SQI=%.1f fps=%.2f validRatio=%.2f inferenceMs=%d",
                    signal.heartRate,
                    signal.signalQuality,
                    actualFps,
                    faceRatio,
                    inference.inferenceTimeMs
            ));
            return new Result(inference, signal, actualFps, faceRatio);
        } finally {
            retriever.release();
            if (landmarker != null) landmarker.close();
        }
    }

    private static Rect locateStableFace(MediaMetadataRetriever retriever,
                                         FaceLandmarker landmarker,
                                         long[] timestampsMs) {
        for (int i = 0; i < Math.min(30, timestampsMs.length); i += 5) {
            Bitmap frame = frameAt(retriever, timestampsMs[i]);
            if (frame == null) continue;
            Bitmap analysisFrame = downscale(frame, MAX_ANALYSIS_WIDTH);
            FaceLandmarkerResult result = detect(analysisFrame, landmarker);
            Rect box = result.faceLandmarks().isEmpty()
                    ? null
                    : faceBox(
                            result.faceLandmarks().get(0),
                            analysisFrame.getWidth(),
                            analysisFrame.getHeight()
                    );
            analysisFrame.recycle();
            if (box != null) return box;
        }
        return null;
    }

    private static boolean hasFace(Bitmap bitmap, FaceLandmarker landmarker) {
        return !detect(bitmap, landmarker).faceLandmarks().isEmpty();
    }

    private static FaceLandmarkerResult detect(Bitmap bitmap, FaceLandmarker landmarker) {
        MPImage image = new BitmapImageBuilder(bitmap).build();
        return landmarker.detect(image);
    }

    private static Rect faceBox(List<NormalizedLandmark> landmarks, int width, int height) {
        if (landmarks == null || landmarks.isEmpty()) return null;
        float minX = 1;
        float minY = 1;
        float maxX = 0;
        float maxY = 0;
        for (NormalizedLandmark landmark : landmarks) {
            minX = Math.min(minX, landmark.x());
            minY = Math.min(minY, landmark.y());
            maxX = Math.max(maxX, landmark.x());
            maxY = Math.max(maxY, landmark.y());
        }
        double centerX = (minX + maxX) * width / 2.0;
        double centerY = (minY + maxY) * height / 2.0;
        double side = Math.max((maxX - minX) * width, (maxY - minY) * height) * 1.5;
        Rect box = new Rect(
                (int) Math.round(centerX - side / 2.0),
                (int) Math.round(centerY - side / 2.0),
                (int) Math.round(centerX + side / 2.0),
                (int) Math.round(centerY + side / 2.0)
        );
        return clampRect(box, width, height);
    }

    private static Rect clampRect(Rect source, int width, int height) {
        int left = Math.max(0, Math.min(width - 1, source.left));
        int top = Math.max(0, Math.min(height - 1, source.top));
        int right = Math.max(left + 1, Math.min(width, source.right));
        int bottom = Math.max(top + 1, Math.min(height, source.bottom));
        return new Rect(left, top, right, bottom);
    }

    private static Bitmap frameAt(MediaMetadataRetriever retriever, long timestampMs) {
        Bitmap bitmap = retriever.getFrameAtTime(
                timestampMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST
        );
        if (bitmap != null && bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();
            return converted;
        }
        return bitmap;
    }

    private static Bitmap downscale(Bitmap source, int maxWidth) {
        if (source.getWidth() <= maxWidth) return source;
        int height = Math.max(
                1,
                Math.round(source.getHeight() * (maxWidth / (float) source.getWidth()))
        );
        Bitmap scaled = Bitmap.createScaledBitmap(source, maxWidth, height, true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private static double exposureStability(double[] values) {
        double mean = 0;
        for (double value : values) mean += value;
        mean /= Math.max(1, values.length);
        double variance = 0;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        double standardDeviation = Math.sqrt(variance / Math.max(1, values.length));
        double variationScore = 1.0 - standardDeviation / 35.0;
        double levelScore = Math.min(mean / 55.0, (255.0 - mean) / 55.0);
        return clamp(Math.min(variationScore, levelScore), 0, 1);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void notifyProgress(FaceLandmarkAnalyzer.ProgressListener listener,
                                       String stage, int progress, int validFrames) {
        if (listener != null) listener.onProgress(stage, progress, validFrames);
    }
}
