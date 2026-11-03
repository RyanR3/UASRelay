package com.uasrelay.video;

/**
 * VideoProfile — the encoder settings for the stream: frame size, frame rate and bitrate.
 *
 * The screen is mirrored into a virtual display at this frame size and the hardware
 * H.264 encoder reads straight from it, so sending a smaller picture is just asking
 * for a smaller frame here. There is no second encode pass and no added latency.
 *
 * Pure math, no Android dependencies, so it is covered by a JVM unit test.
 */
public final class VideoProfile {
    /** What the app has always sent. */
    public static final int FULL_WIDTH = 1920, FULL_HEIGHT = 1080, FULL_FPS = 30;

    /**
     * Low bandwidth, for cellular links that can't carry 1080p30. 720p at 15 fps is about
     * a quarter of the pixels per second, and the bitrate is capped at 1.5 Mbps.
     */
    public static final int LOW_MAX_HEIGHT = 720, LOW_FPS = 15, LOW_MAX_BITRATE_BPS = 1_500_000;

    public final int width, height, fps, bitrateBps;

    VideoProfile(int width, int height, int fps, int bitrateBps) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrateBps = bitrateBps;
    }

    public static VideoProfile full(int bitrateBps) {
        return new VideoProfile(FULL_WIDTH, FULL_HEIGHT, FULL_FPS, bitrateBps);
    }

    /** The full profile under the low-bandwidth caps. A bitrate set lower than the cap is kept. */
    public static VideoProfile lowBandwidth(int bitrateBps) {
        return full(bitrateBps).capped(LOW_MAX_HEIGHT, LOW_FPS, LOW_MAX_BITRATE_BPS);
    }

    /**
     * This profile scaled down (never up) so the height is at most {@code maxHeight},
     * keeping the aspect ratio, with fps and bitrate capped. A scaled frame has both
     * sides rounded to a multiple of 16, which every H.264 encoder accepts.
     */
    public VideoProfile capped(int maxHeight, int maxFps, int maxBitrateBps) {
        int w = width, h = height;
        if (h > maxHeight) {
            w = align16((int) Math.round((double) width * maxHeight / height));
            h = align16(maxHeight);
        }
        return new VideoProfile(w, h, Math.min(fps, maxFps), Math.min(bitrateBps, maxBitrateBps));
    }

    private static int align16(int v) {
        return Math.max(16, Math.round(v / 16f) * 16);
    }

    @Override
    public String toString() {
        return width + "x" + height + "@" + fps + " " + bitrateBps / 1000 + " kbps";
    }
}
