package com.appletv.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NowPlayingIndicatorViewTest {
    @Test
    public void silenceProducesNoEqualizerEnergy() {
        float[] levels = NowPlayingIndicatorView.levelsFromFft(new byte[1024], 48_000_000);

        for (float level : levels) assertEquals(0f, level, 0.001f);
    }

    @Test
    public void fftEnergyRaisesTheMatchingFrequencyBand() {
        byte[] fft = new byte[1024];
        fft[4 * 2] = 96;

        float[] levels = NowPlayingIndicatorView.levelsFromFft(fft, 48_000_000);

        assertTrue(levels[0] > 0f);
        for (int i = 1; i < levels.length; i++) {
            assertEquals(0f, levels[i], 0.001f);
        }
    }
}
