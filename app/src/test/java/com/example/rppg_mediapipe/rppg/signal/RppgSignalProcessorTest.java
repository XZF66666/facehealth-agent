package com.example.rppg_mediapipe.rppg.signal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RppgSignalProcessorTest {
    @Test
    public void recoversSyntheticHeartRateFromDifferentialWaveform() {
        int samples = 180;
        double fps = 30.0;
        double expectedBpm = 72.0;
        double[] bvp = new double[samples];
        for (int i = 0; i < samples; i++) {
            double seconds = i / fps;
            bvp[i] = Math.sin(2.0 * Math.PI * expectedBpm / 60.0 * seconds)
                    + 0.15 * Math.sin(4.0 * Math.PI * expectedBpm / 60.0 * seconds);
        }
        float[] differential = new float[samples];
        for (int i = 0; i < samples - 1; i++) {
            differential[i] = (float) (bvp[i + 1] - bvp[i]);
        }
        differential[samples - 1] = 0;

        RppgSignalProcessor.Result result =
                RppgSignalProcessor.process(differential, fps, 1.0, 1.0);

        assertTrue(result.valid);
        assertTrue(Math.abs(result.heartRate - expectedBpm) <= 4.0);
        assertTrue(result.signalQuality >= 40.0);
    }

    @Test
    public void rejectsNonFiniteModelOutput() {
        float[] differential = new float[180];
        differential[40] = Float.NaN;

        RppgSignalProcessor.Result result =
                RppgSignalProcessor.process(differential, 30.0, 1.0, 1.0);

        assertFalse(result.valid);
        assertTrue(result.invalidReason.contains("非有限值"));
    }
}
