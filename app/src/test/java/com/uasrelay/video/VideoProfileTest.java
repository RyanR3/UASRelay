package com.uasrelay.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the low-bandwidth profile: 1080p30 → 720p15, bitrate capped at 1.5 Mbps. */
public class VideoProfileTest {

    @Test
    public void fullIs1080p30AtTheRequestedBitrate() {
        VideoProfile p = VideoProfile.full(4_000_000);
        assertEquals(1920, p.width);
        assertEquals(1080, p.height);
        assertEquals(30, p.fps);
        assertEquals(4_000_000, p.bitrateBps);
    }

    @Test
    public void lowBandwidthIs720p15CappedAt1500kbps() {
        VideoProfile p = VideoProfile.lowBandwidth(4_000_000);
        assertEquals(1280, p.width);
        assertEquals(720, p.height);
        assertEquals(15, p.fps);
        assertEquals(1_500_000, p.bitrateBps);
    }

    @Test
    public void lowBandwidthKeepsABitrateAlreadyBelowTheCap() {
        assertEquals(800_000, VideoProfile.lowBandwidth(800_000).bitrateBps);
    }

    @Test
    public void capAboveTheCurrentSizeChangesNothing() {
        VideoProfile p = VideoProfile.full(4_000_000).capped(1080, 30, 4_000_000);
        assertEquals(1920, p.width);
        assertEquals(1080, p.height);
        assertEquals(30, p.fps);
        assertEquals(4_000_000, p.bitrateBps);
    }

    @Test
    public void cappedFrameKeepsAspectAndSixteenPixelAlignment() {
        VideoProfile p = VideoProfile.full(4_000_000).capped(700, 15, 1_500_000);
        assertEquals(704, p.height);
        assertEquals(1248, p.width);
        assertEquals(0, p.width % 16);
        assertEquals(0, p.height % 16);
        assertEquals(16.0 / 9.0, (double) p.width / p.height, 0.02);
    }
}
