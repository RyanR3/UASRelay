package com.uasrelay.video;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.util.Log;

import androidx.annotation.NonNull;

import com.pedro.common.ConnectChecker;
import com.pedro.encoder.input.gl.render.filters.CropFilterRender;
import com.pedro.encoder.input.sources.OrientationForced;
import com.pedro.encoder.input.sources.audio.NoAudioSource;
import com.pedro.encoder.input.sources.video.ScreenSource;
import com.pedro.library.rtsp.RtspStream;
import com.pedro.library.util.streamclient.RtspStreamClient;
import com.pedro.rtsp.rtsp.Protocol;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * ScreenStreamer — DJI Fly's live view → H.264 → RTSP push to MediaMTX.
 *
 * Consumer DJI drones (Neo, Avata, Mini…) are not all in DJI MSDK, so there is no camera feed to tap: we mirror the screen
 * with MediaProjection (RootEncoder's ScreenSource), crop to Fly's 16:9 picture with
 * CropFilterRender ({@link FlyCrop}), and push with RootEncoder's RtspStream — the same
 * library and retry/advertise rules as ATAKPilot2's DroneVideoStreamer.
 *
 * The advertised url is only reported once the push actually connects, so CoT never
 * carries a link that opens nothing.
 */
public class ScreenStreamer {
    private static final String TAG = "ScreenStreamer";

    public interface Listener {
        /** @param streaming true once MediaMTX accepted the push */
        void onStreamState(boolean streaming, String text);
    }

    public static class Config {
        public String host;
        public int port = 8554;
        public String path = "uas";
        public String username = "";
        public String password = "";
        public boolean tcp = true;
        public int width = 1920;
        public int height = 1080;
        public int fps = 30;
        public int bitrateBps = 4_000_000;
        public boolean cropToFly = true;
        public float flyLeftPct = FlyCrop.DEFAULT_LEFT_PCT;

        private String cleanPath() { return path.replaceAll("^/+|/+$", ""); }

        public String pushUrl() { return "rtsp://" + host + ":" + port + "/" + cleanPath(); }

        /** What viewers pull: creds inline and ?tcp, the UAS-tool style ATAK understands. */
        public String advertiseUrl() {
            String cred = username.isEmpty() ? "" : enc(username) + ":" + enc(password) + "@";
            return "rtsp://" + cred + host + ":" + port + "/" + cleanPath() + (tcp ? "?tcp" : "");
        }

        public String urlSafe() {
            String who = username.isEmpty() ? "" : username + ":***@";
            return "rtsp://" + who + host + ":" + port + "/" + cleanPath() + (tcp ? "?tcp" : "");
        }

        private static String enc(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    private final Context context;
    private final Config config;
    private final Listener listener;
    private RtspStream stream;
    private volatile boolean streaming;

    public ScreenStreamer(Context context, Config config, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
    }

    public boolean isStreaming() { return streaming; }

    public String advertiseUrl() { return config.advertiseUrl(); }

    private final ConnectChecker connectChecker = new ConnectChecker() {
        @Override public void onConnectionStarted(@NonNull String url) {
            Log.i(TAG, "connecting " + config.urlSafe());
        }

        @Override public void onConnectionSuccess() {
            streaming = true;
            listener.onStreamState(true, "Streaming → " + config.urlSafe());
        }

        @Override public void onConnectionFailed(@NonNull String reason) {
            streaming = false;
            Log.w(TAG, "connection failed: " + reason);
            // setReTries alone is inert — RootEncoder only retries when asked here.
            RtspStream s = stream;
            boolean retried = false;
            try {
                retried = s != null && s.getStreamClient().reTry(5000, reason);
            } catch (Throwable ignored) {}
            listener.onStreamState(false, retried
                    ? "Stream failed (" + reason + "), retrying…"
                    : "Stream failed: " + reason);
        }

        @Override public void onDisconnect() {
            streaming = false;
            listener.onStreamState(false, "Stream disconnected");
        }

        @Override public void onAuthError() {
            streaming = false;
            listener.onStreamState(false, "Stream auth error (check MediaMTX user/pass)");
        }

        @Override public void onAuthSuccess() { Log.i(TAG, "auth ok"); }
    };

    /**
     * Start mirroring + pushing. Must be called from a foreground service of type
     * mediaProjection that already holds {@code projection}.
     *
     * @param screenW,screenH real screen size, for the Fly crop
     */
    public void start(MediaProjection projection, int screenW, int screenH) {
        try {
            ScreenSource source = new ScreenSource(context, projection);
            RtspStream s = new RtspStream(context, connectChecker, source, new NoAudioSource());
            RtspStreamClient client = s.getStreamClient();
            client.setProtocol(config.tcp ? Protocol.TCP : Protocol.UDP);
            client.setOnlyVideo(true);
            if (!config.username.isEmpty()) client.setAuthorization(config.username, config.password);
            client.setReTries(10);
            client.setLogs(false); // per-packet logging floods logcat at 30 fps

            // 1 s keyframe interval: a viewer joining mid-stream waits at most 1 s for a picture.
            boolean okV = s.prepareVideo(config.width, config.height, config.bitrateBps, config.fps,
                    1, 0, -1, -1, config.width, config.height, config.bitrateBps);
            // startStream() starts the audio encoder even with NoAudioSource, so it must be
            // prepared or it throws "AudioEncoder not prepared yet". setOnlyVideo keeps it off the wire.
            boolean okA = s.prepareAudio(44100, true, 64 * 1024);
            if (!okV || !okA) {
                listener.onStreamState(false, "Encoder prepare failed (video=" + okV + " audio=" + okA + ")");
                s.getVideoSource().release();
                return;
            }
            s.getGlInterface().forceOrientation(OrientationForced.LANDSCAPE);
            // A virtual display only produces frames when the screen changes, so a still
            // screen dropped the stream to ~1 fps and ATAK's player timed out on the first
            // pull. Re-render the last frame at a steady rate instead.
            s.getGlInterface().setForceRender(true, config.fps);
            if (config.cropToFly) {
                float[] c = FlyCrop.cropArea(screenW, screenH, config.width, config.height, config.flyLeftPct);
                CropFilterRender crop = new CropFilterRender();
                crop.setCropArea(c[0], c[1], c[2], c[3]);
                s.getGlInterface().setFilter(crop);
                Log.i(TAG, String.format("crop x=%.2f y=%.2f w=%.2f h=%.2f", c[0], c[1], c[2], c[3]));
            }
            stream = s;
            s.startStream(config.pushUrl());
            Log.i(TAG, "push=" + config.pushUrl() + "  advertise=" + config.urlSafe());
            listener.onStreamState(false, "Connecting to " + config.urlSafe());
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            stop();
            listener.onStreamState(false, "Stream start error: " + t.getMessage());
        }
    }

    public void stop() {
        RtspStream s = stream;
        stream = null;
        streaming = false;
        if (s == null) return;
        try {
            if (s.isStreaming()) s.stopStream();
            s.release();
        } catch (Throwable t) {
            Log.w(TAG, "stop: " + t.getMessage());
        }
    }
}
