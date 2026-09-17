package com.example.rppg_mediapipe.debug;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.example.rppg_mediapipe.rppg.model.EfficientPhysEngine;
import com.example.rppg_mediapipe.rppg.model.EfficientPhysMetadata;
import com.example.rppg_mediapipe.rppg.model.RppgInferenceResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EfficientPhysSmokeTestActivity extends Activity {
    private static final String TAG = "EfficientPhysSmoke";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor.execute(() -> {
            try {
                EfficientPhysEngine engine = EfficientPhysEngine.getInstance(this);
                EfficientPhysMetadata metadata = engine.getMetadata();
                float[] input = new float[metadata.inputElementCount()];
                int plane = metadata.height * metadata.width;
                for (int frame = 0; frame < metadata.frameCount; frame++) {
                    float pulse = (float) Math.sin(2.0 * Math.PI * 1.2 * frame / 30.0);
                    int frameOffset = frame * metadata.channels * plane;
                    for (int pixel = 0; pixel < plane; pixel++) {
                        float spatial = ((pixel % metadata.width) - metadata.width / 2.0f)
                                / metadata.width;
                        input[frameOffset + pixel] = 0.25f * pulse + spatial;
                        input[frameOffset + plane + pixel] = 0.55f * pulse + spatial;
                        input[frameOffset + 2 * plane + pixel] = 0.15f * pulse + spatial;
                    }
                }
                RppgInferenceResult result = engine.run(input);
                boolean finite = true;
                for (float value : result.diffNormalizedBvp) {
                    finite &= Float.isFinite(value);
                }
                Log.i(TAG, "SMOKE_PASS model=" + result.modelName
                        + " version=" + result.modelVersion
                        + " samples=" + result.diffNormalizedBvp.length
                        + " finite=" + finite
                        + " initMs=" + result.initializationTimeMs
                        + " inferenceMs=" + result.inferenceTimeMs);
            } catch (Throwable error) {
                Log.e(TAG, "SMOKE_FAIL", error);
            } finally {
                runOnUiThread(this::finish);
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
