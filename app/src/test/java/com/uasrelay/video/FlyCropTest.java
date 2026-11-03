package com.uasrelay.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the letterbox + Fly live-view crop math for the S25 (2340x1080) into 1920x1080. */
public class FlyCropTest {

    private static final float EPS = 0.05f;

    @Test
    public void s25_cropsFlyPictureOutOfLetterboxedFrame() {
        float[] c = FlyCrop.cropArea(2340, 1080, 1920, 1080, FlyCrop.DEFAULT_LEFT_PCT);
        // Screen is scaled to 1920x886 and centered: 97 px bars top and bottom (8.97 %).
        assertEquals(8.97f, c[1], EPS);
        assertEquals(82.05f, c[3], EPS);
        // Fly's 1920 px picture is 82.05 % of the screen width; starts at 10.9 %.
        assertEquals(10.9f, c[0], EPS);
        assertEquals(82.05f, c[2], EPS);
    }

    @Test
    public void croppedRegionIsSixteenByNine() {
        float[] c = FlyCrop.cropArea(2340, 1080, 1920, 1080, FlyCrop.DEFAULT_LEFT_PCT);
        double w = c[2] / 100.0 * 1920, h = c[3] / 100.0 * 1080;
        assertEquals(16.0 / 9.0, w / h, 0.01);
    }

    @Test
    public void portraitMetricsAreTreatedAsLandscape() {
        float[] a = FlyCrop.cropArea(2340, 1080, 1920, 1080, 10.9f);
        float[] b = FlyCrop.cropArea(1080, 2340, 1920, 1080, 10.9f);
        for (int i = 0; i < 4; i++) assertEquals(a[i], b[i], 1e-4);
    }

    @Test
    public void leftOffsetIsClampedSoThePictureStaysOnScreen() {
        float[] c = FlyCrop.cropArea(2340, 1080, 1920, 1080, 90f);
        assertEquals(100f, c[0] + c[2], EPS);
    }

    @Test
    public void sixteenByNineScreenIsTheWholeFrame() {
        float[] c = FlyCrop.cropArea(1920, 1080, 1920, 1080, 0f);
        assertEquals(0f, c[0], EPS);
        assertEquals(0f, c[1], EPS);
        assertEquals(100f, c[2], EPS);
        assertEquals(100f, c[3], EPS);
    }
}
