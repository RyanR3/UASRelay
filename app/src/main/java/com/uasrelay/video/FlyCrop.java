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
 * A screen narrower than 16:9 (a 16:10 or 4:3 tablet) is pillarboxed instead, and the
 * 16:9 picture is taken at full width, centered vertically.
 *
 * Pure math, no Android dependencies, so it is covered by a JVM unit test.
 */
public final class FlyCrop {
    /** The S25 measurement: Fly's picture starts at 255 of 2340 px. */
    public static final float DEFAULT_LEFT_PCT = 10.9f;

    /**
     * CropFilterRender divides by (100 - size) to place the crop, so a full 100 % gives
     * 0/0, a NaN matrix and a black picture. That is every 16:10 tablet, whose screen
     * fills the frame's height.
     */
    static final float MAX_SIZE_PCT = 99.99f;

    private FlyCrop() {}

    /**
     * @param screenW,screenH screen size in px (either orientation; landscape is assumed)
     * @param frameW,frameH   encoder frame size in px
     * @param leftPct         left edge of Fly's picture, as % of the screen width; only
     *                        used on screens wider than 16:9
     * @return {x, y, width, height} in % of the frame
     */
    public static float[] cropArea(int screenW, int screenH, int frameW, int frameH, float leftPct) {
        double sw = Math.max(screenW, screenH);
        double sh = Math.min(screenW, screenH);

        // Letterbox: the mirrored screen is scaled to fit inside the frame, centered.
        double scale = Math.min(frameW / sw, frameH / sh);
        double offX = (frameW - sw * scale) / 2.0;
        double offY = (frameH - sh * scale) / 2.0;

        // Fly's live view, 16:9.
        double vidW, vidH, vidLeft, vidTop;
        if (sw * 9.0 >= sh * 16.0) {
            // Phone: full height, starting at leftPct of the width.
            vidH = sh;
            vidW = sh * 16.0 / 9.0;
            vidLeft = Math.max(0, Math.min(sw * leftPct / 100.0, sw - vidW));
            vidTop = 0;
        } else {
            // Tablet: full width, centered.
            vidW = sw;
            vidH = sw * 9.0 / 16.0;
            vidLeft = 0;
            vidTop = (sh - vidH) / 2.0;
        }

        return new float[]{
                (float) ((offX + vidLeft * scale) / frameW * 100.0),
                (float) ((offY + vidTop * scale) / frameH * 100.0),
                Math.min(MAX_SIZE_PCT, (float) (vidW * scale / frameW * 100.0)),
                Math.min(MAX_SIZE_PCT, (float) (vidH * scale / frameH * 100.0)),
        };
    }
}
