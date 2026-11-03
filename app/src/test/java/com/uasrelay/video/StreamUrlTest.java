package com.uasrelay.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the push and viewer urls for RTSP and MediaMTX's SRT streamid addressing. */
public class StreamUrlTest {

    private static ScreenStreamer.Config config(boolean srt, boolean srtLink) {
        ScreenStreamer.Config c = new ScreenStreamer.Config();
        c.host = "video.example.org";
        c.path = "/uas1/";
        c.username = "relay";
        c.password = "p@ss";
        c.srt = srt;
        c.srtLink = srtLink;
        return c;
    }

    @Test
    public void rtsp_pushAndAdvertise() {
        ScreenStreamer.Config c = config(false, false);
        assertEquals("rtsp://video.example.org:8554/uas1", c.pushUrl());
        assertEquals("rtsp://relay:p%40ss@video.example.org:8554/uas1?tcp", c.advertiseUrl());
        assertEquals("rtsp://relay:***@video.example.org:8554/uas1?tcp", c.urlSafe());
    }

    @Test
    public void srt_pushesWithPublishStreamIdButViewersKeepRtsp() {
        ScreenStreamer.Config c = config(true, false);
        assertEquals("srt://video.example.org:8890?streamid=publish:uas1:relay:p@ss", c.pushUrl());
        assertEquals("rtsp://relay:p%40ss@video.example.org:8554/uas1?tcp", c.advertiseUrl());
        assertEquals("srt://video.example.org:8890?streamid=publish:uas1:relay:***", c.urlSafe());
    }

    @Test
    public void srtLink_advertisesReadStreamId() {
        assertEquals("srt://video.example.org:8890?streamid=read:uas1:relay:p@ss",
                config(true, true).advertiseUrl());
    }

    @Test
    public void srt_withoutLogin_hasBareStreamId() {
        ScreenStreamer.Config c = config(true, true);
        c.username = "";
        assertEquals("srt://video.example.org:8890?streamid=publish:uas1", c.pushUrl());
        assertEquals("srt://video.example.org:8890?streamid=read:uas1", c.advertiseUrl());
    }
}
