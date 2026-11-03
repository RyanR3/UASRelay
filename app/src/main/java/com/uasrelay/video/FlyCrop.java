package com.uasrelay.video;

/**
 * FlyCrop — where DJI Fly's camera picture sits inside the encoded frame, as the
 * percentages RootEncoder's CropFilterRender takes (x, y, width, height; 0..100).
 *
 * The screen is mirrored into a virtual display at the encoder size, so a wide phone
 * screen (e.g. 2340x1080, 2.17:1) is letterboxed into a 16:9 frame. Inside the screen,
 * Fly draws the 16:9 live view at full height with its control bars on either side; on
 * the S25 the picture starts 255 px from the left (10.9 %). Cropping to that rect and
 * scaling it back up to the 16:9 frame drops the side bars without distorting the image.
 *
 * Pure math, no Android dependencies, so it is covered by a JVM unit test.
 */
public final class FlyCrop {
    /** The S25 measurement: Fly's picture starts at 255 of 2340 px. */
    public static final float DEFAULT_LEFT_PCT = 10.9f;

    private FlyCrop() {}

    /**
     * @param screenW,screenH screen size in px (either orientation; landscape is assumed)
     * @param frameW,frameH   encoder frame size in px
     * @param leftPct         left edge of Fly's picture, as % of the screen width
     * @return {x, y, width, height} in % of the frame
     */
    public static float[] cropArea(int screenW, int screenH, int frameW, int frameH, float leftPct) {
        double sw = Math.max(screenW, screenH);
        double sh = Math.min(screenW, screenH);

        // Letterbox: the mirrored screen is scaled to fit inside the frame, centered.
        double scale = Math.min(frameW / sw, frameH / sh);
        double contentW = sw * scale;
        double contentH = sh * scale;
        double offX = (frameW - contentW) / 2.0;
        double offY = (frameH - contentH) / 2.0;

        // Fly's live view: 16:9 at full screen height, starting at leftPct of the width.
        double vidW = Math.min(sh * 16.0 / 9.0, sw);
        double vidLeft = Math.max(0, Math.min(sw * leftPct / 100.0, sw - vidW));

        double x = offX + vidLeft * scale;
        double w = vidW * scale;
        return new float[]{
                (float) (x / frameW * 100.0),
                (float) (offY / frameH * 100.0),
                (float) (w / frameW * 100.0),
                (float) (contentH / frameH * 100.0),
        };
    }
}
