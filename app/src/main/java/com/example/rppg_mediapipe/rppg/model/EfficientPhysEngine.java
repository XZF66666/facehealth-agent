package com.example.rppg_mediapipe.rppg.model;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public final class EfficientPhysEngine implements AutoCloseable {
    private static final String MODEL_ASSET = "models/efficientphys_pure.onnx";
    private static final String METADATA_ASSET = "models/efficientphys_metadata.json";
    private static volatile EfficientPhysEngine instance;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final EfficientPhysMetadata metadata;
    private final long initializationTimeMs;

    private EfficientPhysEngine(Context context) throws IOException, OrtException {
        long started = System.nanoTime();
        Context appContext = context.getApplicationContext();
        metadata = EfficientPhysMetadata.load(appContext, METADATA_ASSET);
        byte[] modelBytes = readAsset(appContext, MODEL_ASSET);
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            session = environment.createSession(modelBytes, options);
        }
        if (!session.getInputNames().contains(metadata.inputName)
                || !session.getOutputNames().contains(metadata.outputName)) {
            session.close();
            throw new OrtException("EfficientPhys metadata does not match ONNX graph");
        }
        initializationTimeMs = elapsedMillis(started);
    }

    public static EfficientPhysEngine getInstance(Context context) throws IOException, OrtException {
        EfficientPhysEngine local = instance;
        if (local == null) {
            synchronized (EfficientPhysEngine.class) {
                local = instance;
                if (local == null) {
                    local = new EfficientPhysEngine(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public EfficientPhysMetadata getMetadata() {
        return metadata;
    }

    public synchronized RppgInferenceResult run(float[] input) throws OrtException {
        if (input == null || input.length != metadata.inputElementCount()) {
            throw new IllegalArgumentException(
                    "EfficientPhys input elements must equal " + metadata.inputElementCount()
            );
        }
        for (float value : input) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("EfficientPhys input contains NaN or Inf");
            }
        }

        FloatBuffer buffer = ByteBuffer
                .allocateDirect(input.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(input);
        buffer.rewind();
        long[] shape = {
                metadata.frameCount, metadata.channels, metadata.height, metadata.width
        };
        long started = System.nanoTime();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer, shape);
             OrtSession.Result result = session.run(
                     Collections.singletonMap(metadata.inputName, tensor))) {
            Object raw = result.get(metadata.outputName)
                    .orElseThrow(() -> new OrtException("EfficientPhys output is missing"))
                    .getValue();
            float[] output = flatten(raw);
            if (output.length != metadata.frameCount) {
                throw new OrtException("Unexpected EfficientPhys output length: " + output.length);
            }
            for (float value : output) {
                if (!Float.isFinite(value)) {
                    throw new OrtException("EfficientPhys output contains NaN or Inf");
                }
            }
            return new RppgInferenceResult(
                    output,
                    elapsedMillis(started),
                    initializationTimeMs,
                    metadata.modelName,
                    metadata.modelVersion
            );
        }
    }

    private static float[] flatten(Object value) throws OrtException {
        if (value instanceof float[]) {
            return ((float[]) value).clone();
        }
        if (value instanceof float[][]) {
            float[][] rows = (float[][]) value;
            int count = 0;
            for (float[] row : rows) count += row.length;
            float[] output = new float[count];
            int offset = 0;
            for (float[] row : rows) {
                System.arraycopy(row, 0, output, offset, row.length);
                offset += row.length;
            }
            return output;
        }
        throw new OrtException("Unsupported EfficientPhys output type: " + value.getClass());
    }

    private static byte[] readAsset(Context context, String path) throws IOException {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    @Override
    public void close() throws OrtException {
        session.close();
        synchronized (EfficientPhysEngine.class) {
            if (instance == this) instance = null;
        }
    }
}
