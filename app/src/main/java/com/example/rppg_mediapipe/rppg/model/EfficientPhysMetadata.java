package com.example.rppg_mediapipe.rppg.model;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class EfficientPhysMetadata {
    public final String modelName;
    public final String modelVersion;
    public final String inputName;
    public final String outputName;
    public final int frameCount;
    public final int channels;
    public final int height;
    public final int width;

    private EfficientPhysMetadata(JSONObject json) {
        modelName = json.optString("model_name", "EfficientPhys");
        modelVersion = json.optString("model_version", "unknown");
        inputName = json.optString("input_name", "");
        outputName = json.optString("output_name", "");
        JSONArray shape = json.optJSONArray("input_shape");
        if (inputName.isEmpty() || outputName.isEmpty() || shape == null || shape.length() != 4) {
            throw new IllegalArgumentException("EfficientPhys input_shape must be TCHW");
        }
        frameCount = shape.optInt(0, -1);
        channels = shape.optInt(1, -1);
        height = shape.optInt(2, -1);
        width = shape.optInt(3, -1);
        if (frameCount != 180 || channels != 3 || height != 72 || width != 72) {
            throw new IllegalArgumentException("Unsupported EfficientPhys input shape: " + shape);
        }
    }

    public static EfficientPhysMetadata load(Context context, String assetPath) throws IOException {
        try (InputStream input = context.getAssets().open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            try {
                return new EfficientPhysMetadata(new JSONObject(text));
            } catch (Exception error) {
                throw new IOException("Invalid EfficientPhys metadata", error);
            }
        }
    }

    public int inputElementCount() {
        return frameCount * channels * height * width;
    }
}
