package com.uasrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.uasrelay.rid.RidMessage;
import com.uasrelay.rid.RidScanner;
import com.uasrelay.rid.WifiRidScanner;
import com.uasrelay.tak.CotBuilder;
import com.uasrelay.tak.MulticastCot;
import com.uasrelay.tak.TakChannels;
import com.uasrelay.tak.TakClient;
import com.uasrelay.video.ScreenStreamer;
import com.uasrelay.video.VideoProfile;

import java.io.File;

/**
 * RelayService — foreground service that runs the whole pipeline while DJI Fly is in front:
 *
 *   screen (Fly live view) → crop → H.264 → RTSP/SRT push to MediaMTX  ({@link ScreenStreamer})
 *   position: broadcast Remote ID when the drone is heard, else the phone's GPS
 *   CoT: one drone track carrying {@code <__video>} → TAK Server and/or SA multicast
 *
 * Started by MainActivity with the MediaProjection consent result. The CoT uid is fixed
 * per device, so viewers keep one marker (and its feed) as the position source changes.
 * Each status line carries a {@code STATE_*} level so the UI can show a colored dot.
 */
public class RelayService extends Service {
    private static final String TAG = "RelayService";
    private static final String CHANNEL_ID = "uasrelay";
    private static final int NOTIF_ID = 1;
    private static final long PUBLISH_INTERVAL_MS = 2000;
    /** A RID position older than this falls back to the phone's GPS. */
    private static final long RID_FRESH_MS = 6000;
    private static final String P12_PASSWORD = "atakatak"; // TakCertEnroller default

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    /** Notification "Stop" button. */
    public static final String ACTION_STOP = "com.uasrelay.STOP";
    /** Settings changed the TAK channels while we run: send the new set to the server. */
    public static final String ACTION_APPLY_CHANNELS = "com.uasrelay.APPLY_CHANNELS";
    /** The server only knows our clientUid once a CoT has gone out on the connection. */
    private static final long CHANNELS_AFTER_CONNECT_MS = 3000;

    /** Broadcast back to the UI so the main screen shows live state. */
    public static final String ACTION_STATUS = "com.uasrelay.STATUS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_VIDEO_STATE = "videoState";
    public static final String EXTRA_COT = "cot";
    public static final String EXTRA_COT_STATE = "cotState";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_POSITION_STATE = "positionState";

    public static final int STATE_OFF = 0;
    public static final int STATE_PENDING = 1;
    public static final int STATE_OK = 2;
    public static final int STATE_ERROR = 3;

    /** True while the pipeline is up; the UI reads it on resume to pick Start vs Stop. */
    public static volatile boolean running;
    /** Set first thing in onDestroy; late callbacks from this instance must not report. */
    private volatile boolean destroyed;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private String uid;
    private MediaProjection projection;
    private ScreenStreamer streamer;
    private TakClient takClient;
    private volatile boolean takConnected;
    private MulticastCot multicast;
    private LocationManager locationManager;
    private RidScanner bleRid;
    private WifiRidScanner wifiRid;

    private volatile Location phoneFix;
    private volatile RidFix ridFix;
    private volatile String lockedSerial;

    private String videoText = "Starting…";
    private int videoState = STATE_PENDING;
    private String takText = "";
    /** " · 3 channels" once the server has told us, appended to the TAK status. */
    private String channelsText = "";
    private int takState = STATE_OFF;
    private String positionText = "Waiting for GPS…";
    private int positionState = STATE_PENDING;

    /** Snapshot of the RID fields we publish, copied out of the scanner's accumulator. */
    private static final class RidFix {
        final String serial;
        final double lat, lon, hae;
        final float heading, speed;
        final long time = System.currentTimeMillis();

        RidFix(RidMessage m) {
            serial = m.bestUid();
            lat = m.latitude;
            lon = m.longitude;
            hae = m.altitudeGeo;
            heading = m.direction;
            speed = m.speedHorizontal;
        }
    }

    private final Runnable publishTick = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, PUBLISH_INTERVAL_MS);
        }
    };

    private final LocationListener locationListener = location -> phoneFix = location;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_APPLY_CHANNELS.equals(intent.getAction())) {
            if (projection != null) {
                prefs = Prefs.load(this);
                if (takConnected) applyChannels();
            } else {
                stopSelf(); // not running: nothing to apply to
            }
            return START_NOT_STICKY;
        }

        // Must be foreground (with the mediaProjection type) before getMediaProjection().
        // The location type throws unless location is granted, so only claim it then.
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        startForeground(NOTIF_ID, buildNotification("Starting…"), types);

        Intent data = intent == null ? null : intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        if (data == null) {
            // Restarted without a consent token (a projection can't be resumed): give up cleanly.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY; // already running

        prefs = Prefs.load(this);
        uid = Prefs.deviceUid(this);
        running = true;

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                // User revoked capture (status-bar chip / notification): stop everything.
                Log.i(TAG, "projection stopped by system/user");
                handler.post(RelayService.this::stopSelf);
            }
        }, handler);

        startVideo();
        startCot();
        startLocation();
        if (prefs.ridEnabled) startRid();

        handler.post(publishTick);
        return START_NOT_STICKY;
    }

    private void startVideo() {
        if (prefs.mtxHost.isEmpty()) {
            setVideo(STATE_ERROR, "No video server set");
            return;
        }
        ScreenStreamer.Config cfg = new ScreenStreamer.Config();
        cfg.host = prefs.mtxHost;
        cfg.port = prefs.mtxPort;
        cfg.path = prefs.mtxPath;
        cfg.username = prefs.mtxUser;
        cfg.password = prefs.mtxPass;
        cfg.tcp = prefs.mtxTcp;
        cfg.srt = prefs.mtxSrt;
        cfg.srtPort = prefs.mtxSrtPort;
        cfg.srtLink = prefs.mtxSrtLink;
        VideoProfile video = prefs.lowBandwidth
                ? VideoProfile.lowBandwidth(prefs.bitrateKbps * 1000)
                : VideoProfile.full(prefs.bitrateKbps * 1000);
        cfg.width = video.width;
        cfg.height = video.height;
        cfg.fps = video.fps;
        cfg.bitrateBps = video.bitrateBps;
        cfg.cropToFly = prefs.cropToFly;
        cfg.flyLeftPct = prefs.flyLeftPct;

        Rect bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        streamer = new ScreenStreamer(this, cfg, (streaming, text) -> handler.post(() -> {
            if (streaming) setVideo(STATE_OK, "Live → " + prefs.mtxHost + "/" + prefs.mtxPath
                    + (prefs.lowBandwidth ? " (" + video.height + "p" + video.fps + ")" : ""));
            else if (text.startsWith("Connecting") || text.contains("retrying")) setVideo(STATE_PENDING, text);
            else setVideo(STATE_ERROR, text);
        }));
        streamer.start(projection, bounds.width(), bounds.height());
    }

    private void setVideo(int state, String text) {
        videoState = state;
        videoText = text;
        report();
    }

    private void startCot() {
        if (prefs.multicastEnabled) multicast = new MulticastCot();
        if (!prefs.takEnabled) return;

        File trust = new File(getFilesDir(), "tak_truststore.p12");
        File client = new File(getFilesDir(), "tak_clientcert.p12");
        if (!trust.exists() || !client.exists()) {
            takState = STATE_ERROR;
            takText = "TAK Server not enrolled";
            return;
        }
        takState = STATE_PENDING;
        takText = "TAK Server connecting…";
        takClient = new TakClient(prefs.takHost, prefs.takStreamPort,
                trust.getAbsolutePath(), P12_PASSWORD, client.getAbsolutePath(), P12_PASSWORD,
                new TakClient.TakClientListener() {
                    @Override public void onConnected() { setTak(true); }
                    @Override public void onDisconnected() { setTak(false); }
                    @Override public void onCotReceived(String xml) {
                        // UAS Relay is a producer; inbound CoT is not consumed.
                    }
                });
        takClient.start();
    }

    private void setTak(boolean connected) {
        takConnected = connected;
        handler.post(() -> {
            takState = connected ? STATE_OK : STATE_PENDING;
            takText = connected ? "TAK Server" + channelsText : "TAK Server reconnecting…";
            report();
            // Every (re)connect: the server ties channels to this connection's uid.
            handler.removeCallbacks(applyChannelsTask);
            if (connected) handler.postDelayed(applyChannelsTask, CHANNELS_AFTER_CONNECT_MS);
        });
    }

    private final Runnable applyChannelsTask = this::applyChannels;

    /**
     * Send the channels picked in Settings, or — if none were picked — just read the
     * server's selection for the status line. Runs the HTTPS calls off the main thread.
     */
    private void applyChannels() {
        Prefs p = prefs;
        File trust = new File(getFilesDir(), "tak_truststore.p12");
        File client = new File(getFilesDir(), "tak_clientcert.p12");
        new Thread(() -> {
            String text;
            try {
                TakChannels api = new TakChannels(p.takHost, p.takApiPort,
                        trust.getAbsolutePath(), client.getAbsolutePath(), P12_PASSWORD);
                if (p.takChannelsChosen) api.apply(uid, p.takChannels);
                int on = 0;
                for (TakChannels.Channel c : api.list()) if (c.active) on++;
                text = " · " + on + (on == 1 ? " channel" : " channels");
                Log.i(TAG, (p.takChannelsChosen ? "applied " : "server has ") + on + " active channel(s)");
            } catch (Exception e) {
                Log.w(TAG, "channels: " + e.getMessage());
                text = p.takChannelsChosen ? " · channels not set (" + e.getMessage() + ")" : "";
            }
            String t = text;
            handler.post(() -> {
                channelsText = t;
                if (takConnected && !destroyed) {
                    takText = "TAK Server" + channelsText;
                    report();
                }
            });
        }, "TakChannels").start();
    }

    @SuppressLint("MissingPermission") // MainActivity requests location before starting us
    private void startLocation() {
        locationManager = getSystemService(LocationManager.class);
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!locationManager.isProviderEnabled(provider)) continue;
            try {
                Location last = locationManager.getLastKnownLocation(provider);
                if (last != null && phoneFix == null) phoneFix = last;
                locationManager.requestLocationUpdates(provider, 1000, 0, locationListener, Looper.getMainLooper());
            } catch (SecurityException e) {
                positionState = STATE_ERROR;
                positionText = "Location permission missing";
            }
        }
    }

    private void startRid() {
        bleRid = new RidScanner(this, new RidScanner.RidListener() {
            @Override public void onDrone(RidMessage message) { onRid(message); }
            @Override public void onStatus(String message, boolean isError) { Log.i(TAG, "BLE RID: " + message); }
        });
        wifiRid = new WifiRidScanner(this, new WifiRidScanner.WifiRidListener() {
            @Override public void onDrone(RidMessage message) { onRid(message); }
            @Override public void onStatus(String message, boolean isError) { Log.i(TAG, "Wi-Fi RID: " + message); }
        });
        bleRid.start();
        wifiRid.start();
    }

    /** Shared by BLE + Wi-Fi scanners. Locks onto one drone so a neighbor's RID can't steal the marker. */
    private void onRid(RidMessage message) {
        RidFix fix;
        synchronized (message) {
            if (!message.hasValidPosition()) return;
            fix = new RidFix(message);
        }
        String want = prefs.ridSerial.isEmpty() ? lockedSerial : prefs.ridSerial;
        if (want == null) {
            lockedSerial = fix.serial;
            Log.i(TAG, "RID locked onto " + fix.serial);
        } else if (!want.equalsIgnoreCase(fix.serial)) {
            return;
        }
        ridFix = fix;
    }

    private void publish() {
        RidFix rid = ridFix;
        Location phone = phoneFix;
        boolean ridFresh = rid != null && System.currentTimeMillis() - rid.time < RID_FRESH_MS;
        String videoUrl = streamer != null && streamer.isStreaming() ? streamer.advertiseUrl() : null;

        String drone, operator = null;
        if (ridFresh) {
            String opUid = phone != null ? uid + "-OP" : null;
            drone = CotBuilder.buildDronePLI(uid, prefs.callsign, rid.lat, rid.lon, rid.hae, 10,
                    rid.heading, rid.speed, videoUrl, opUid, "Position: Remote ID (" + rid.serial + ")");
            if (phone != null) {
                operator = CotBuilder.buildOperatorMarker(opUid, prefs.callsign + " pilot", uid,
                        phone.getLatitude(), phone.getLongitude());
            }
            positionState = STATE_OK;
            positionText = "Drone (Remote ID)";
        } else if (phone != null) {
            double ce = phone.hasAccuracy() ? phone.getAccuracy() : 9999999;
            double hae = phone.hasAltitude() ? phone.getAltitude() : 0;
            drone = CotBuilder.buildDronePLI(uid, prefs.callsign, phone.getLatitude(), phone.getLongitude(),
                    hae, ce, 0, 0, videoUrl, null, "Position: pilot phone GPS (no Remote ID heard)");
            positionState = STATE_OK;
            positionText = "Your phone (±" + Math.round(ce) + " m)";
        } else {
            positionState = STATE_PENDING;
            positionText = "Waiting for GPS…";
            report();
            return;
        }

        send(drone);
        if (operator != null) send(operator);
        report();
    }

    private void send(String xml) {
        if (multicast != null) multicast.send(xml);
        if (takClient != null && takConnected) takClient.sendMessage(xml);
    }

    private void report() {
        // Stopping fires disconnect callbacks (stream, TAK) that post a report after the
        // final "stopped" broadcast; drop them, or the UI flips back to running and the
        // notification is re-posted.
        if (destroyed) return;
        String cot;
        int cotState;
        if (multicast == null && !prefs.takEnabled) {
            cot = "Off";
            cotState = STATE_OFF;
        } else if (!prefs.takEnabled) {
            cot = "Local network (multicast)";
            cotState = STATE_OK;
        } else {
            cot = multicast != null ? "Multicast + " + takText : takText;
            cotState = takState;
        }
        updateNotification(videoText);
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_VIDEO, videoText).putExtra(EXTRA_VIDEO_STATE, videoState)
                .putExtra(EXTRA_COT, cot).putExtra(EXTRA_COT_STATE, cotState)
                .putExtra(EXTRA_POSITION, positionText).putExtra(EXTRA_POSITION_STATE, positionState));
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (streamer != null) streamer.stop();
        if (projection != null) projection.stop();
        if (bleRid != null) bleRid.stop();
        if (wifiRid != null) wifiRid.stop();
        if (locationManager != null) locationManager.removeUpdates(locationListener);
        if (takClient != null) takClient.stopClient();
        if (multicast != null) multicast.close();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_RUNNING, false));
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "UAS Relay",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RelayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("UAS Relay")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_uasrelay)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }
}
