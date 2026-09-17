package com.example.rppg_mediapipe.rppg.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EfficientPhysEngineInstrumentedTest {
    @Test
    public void loadsFp16AssetAndRunsArmCpuInference() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        EfficientPhysEngine engine = EfficientPhysEngine.getInstance(context);
        EfficientPhysMetadata metadata = engine.getMetadata();
        float[] input = new float[metadata.inputElementCount()];
        int plane = metadata.height * metadata.width;

        for (int frame = 0; frame < metadata.frameCount; frame++) {
            double pulse = Math.sin(2.0 * Math.PI * 1.2 * frame / 30.0);
            int frameOffset = frame * metadata.channels * plane;
            for (int pixel = 0; pixel < plane; pixel++) {
                double spatial = ((pixel % metadata.width) - metadata.width / 2.0)
                        / metadata.width;
                input[frameOffset + pixel] = (float) (0.25 * pulse + spatial);
                input[frameOffset + plane + pixel] = (float) (0.55 * pulse + spatial);
                input[frameOffset + 2 * plane + pixel] = (float) (0.15 * pulse + spatial);
            }
        }

        RppgInferenceResult result = engine.run(input);

        assertEquals(180, result.diffNormalizedBvp.length);
        assertEquals("EfficientPhys", result.modelName);
        assertTrue(result.inferenceTimeMs >= 0);
        for (float value : result.diffNormalizedBvp) {
            assertTrue(Float.isFinite(value));
        }
    }
}
