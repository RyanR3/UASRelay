package com.uasrelay.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pins the letterbox + Fly live-view crop math: S25 (2340x1080) and Tab A9+ (1920x1200) into 1920x1080. */
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

    /** The Tab A9+ is pillarboxed to 1728x1080; Fly's 16:9 picture is its middle 1728x972. */
    @Test
    public void tablet_cropsCenteredSixteenByNineOutOfPillarboxedFrame() {
        float[] c = FlyCrop.cropArea(1920, 1200, 1920, 1080, FlyCrop.DEFAULT_LEFT_PCT);
        assertEquals(5f, c[0], EPS);
        assertEquals(90f, c[2], EPS);
        assertEquals(5f, c[1], EPS);
        assertEquals(90f, c[3], EPS);
        double w = c[2] / 100.0 * 1920, h = c[3] / 100.0 * 1080;
        assertEquals(16.0 / 9.0, w / h, 0.01);
    }

    /** A full 100 % makes CropFilterRender divide 0 by 0 and render black. */
    @Test
    public void cropNeverSpansTheWholeFrame() {
        int[][] screens = {{1920, 1080}, {1920, 1200}, {2048, 1536}, {2340, 1080}, {1340, 800}};
        for (int[] s : screens) {
            float[] c = FlyCrop.cropArea(s[0], s[1], 1920, 1080, FlyCrop.DEFAULT_LEFT_PCT);
            assertTrue(c[2] < 100f && c[3] < 100f);
        }
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
