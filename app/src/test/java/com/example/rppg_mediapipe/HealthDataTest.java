package com.example.rppg_mediapipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HealthDataTest {
    @Test
    public void preservesEfficientPhysDiagnostics() {
        HealthData data = new HealthData(
                72, 15, 55, 50, 50, "可用",
                "EfficientPhys", "fp16-v1", 74.5, 29.9, 380, true, ""
        );

        assertEquals("EfficientPhys", data.getModelName());
        assertEquals("fp16-v1", data.getModelVersion());
        assertEquals(74.5, data.getSignalQuality(), 0.001);
        assertEquals(29.9, data.getActualFps(), 0.001);
        assertEquals(380, data.getInferenceTimeMs());
        assertTrue(data.isResultValid());
    }
}
