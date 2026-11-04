package com.uasrelay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

import com.uasrelay.video.FlyCrop;

/** All UAS Relay settings, persisted so the service reads exactly what the UI last saved. */
public final class Prefs {
    private static final String FILE = "uasrelay";

    public String callsign = "UAS-1";

    // Video → MediaMTX
    public String mtxHost = "";
    public int mtxPort = 8554;
    public String mtxPath = "uas";
    public String mtxUser = "";
    public String mtxPass = "";
    public boolean mtxTcp = true;
    /** Push protocol: SRT when true, RTSP otherwise. */
    public boolean mtxSrt = false;
    public int mtxSrtPort = 8890;
    /** With SRT, put the SRT link in the map marker instead of RTSP. */
    public boolean mtxSrtLink = false;
    public int bitrateKbps = 4000;
    /** 720p, 15 fps, 1.5 Mbps cap for cellular links ({@link com.uasrelay.video.VideoProfile}). */
    public boolean lowBandwidth = false;
    public boolean cropToFly = true;
    public float flyLeftPct = FlyCrop.DEFAULT_LEFT_PCT;

    // CoT
    public boolean takEnabled = false;
    public String takHost = "";
    public int takEnrollPort = 8446;
    public int takStreamPort = 8089;
    public int takApiPort = 8443;
    /** True once channels were picked here; until then the server's own selection is left alone. */
    public boolean takChannelsChosen = false;
    public Set<String> takChannels = new HashSet<>();
    public String takUser = "";
    public boolean multicastEnabled = true;

    // Remote ID
    public boolean ridEnabled = true;
    /** Lock onto this RID serial; empty = the first drone heard this session. */
    public String ridSerial = "";

    /**
     * This phone's CoT uid. Built from ANDROID_ID, not the model: two phones of one model
     * must not share a marker, and a uid that another client has reused (ATAK saving the
     * feed under the marker's uid) must not be ours.
     */
    public static String deviceUid(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return "UASRelay-" + (id == null || id.isEmpty() ? Build.MODEL : id.substring(0, Math.min(8, id.length())));
    }

    public static Prefs load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs s = new Prefs();
        s.callsign = p.getString("callsign", s.callsign);
        s.mtxHost = p.getString("mtxHost", s.mtxHost);
        s.mtxPort = p.getInt("mtxPort", s.mtxPort);
        s.mtxPath = p.getString("mtxPath", s.mtxPath);
        s.mtxUser = p.getString("mtxUser", s.mtxUser);
        s.mtxPass = p.getString("mtxPass", s.mtxPass);
        s.mtxTcp = p.getBoolean("mtxTcp", s.mtxTcp);
        s.mtxSrt = p.getBoolean("mtxSrt", s.mtxSrt);
        s.mtxSrtPort = p.getInt("mtxSrtPort", s.mtxSrtPort);
        s.mtxSrtLink = p.getBoolean("mtxSrtLink", s.mtxSrtLink);
        s.bitrateKbps = p.getInt("bitrateKbps", s.bitrateKbps);
        s.lowBandwidth = p.getBoolean("lowBandwidth", s.lowBandwidth);
        s.cropToFly = p.getBoolean("cropToFly", s.cropToFly);
        s.flyLeftPct = p.getFloat("flyLeftPct", s.flyLeftPct);
        s.takEnabled = p.getBoolean("takEnabled", s.takEnabled);
        s.takHost = p.getString("takHost", s.takHost);
        s.takEnrollPort = p.getInt("takEnrollPort", s.takEnrollPort);
        s.takStreamPort = p.getInt("takStreamPort", s.takStreamPort);
        s.takApiPort = p.getInt("takApiPort", s.takApiPort);
        s.takChannelsChosen = p.getBoolean("takChannelsChosen", s.takChannelsChosen);
        // getStringSet returns the stored instance, which must not be modified.
        s.takChannels = new HashSet<>(p.getStringSet("takChannels", s.takChannels));
        s.takUser = p.getString("takUser", s.takUser);
        s.multicastEnabled = p.getBoolean("multicastEnabled", s.multicastEnabled);
        s.ridEnabled = p.getBoolean("ridEnabled", s.ridEnabled);
        s.ridSerial = p.getString("ridSerial", s.ridSerial);
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("callsign", callsign)
                .putString("mtxHost", mtxHost)
                .putInt("mtxPort", mtxPort)
                .putString("mtxPath", mtxPath)
                .putString("mtxUser", mtxUser)
                .putString("mtxPass", mtxPass)
                .putBoolean("mtxTcp", mtxTcp)
                .putBoolean("mtxSrt", mtxSrt)
                .putInt("mtxSrtPort", mtxSrtPort)
                .putBoolean("mtxSrtLink", mtxSrtLink)
                .putInt("bitrateKbps", bitrateKbps)
                .putBoolean("lowBandwidth", lowBandwidth)
                .putBoolean("cropToFly", cropToFly)
                .putFloat("flyLeftPct", flyLeftPct)
                .putBoolean("takEnabled", takEnabled)
                .putString("takHost", takHost)
                .putInt("takEnrollPort", takEnrollPort)
                .putInt("takStreamPort", takStreamPort)
                .putInt("takApiPort", takApiPort)
                .putBoolean("takChannelsChosen", takChannelsChosen)
                .putStringSet("takChannels", new HashSet<>(takChannels))
                .putString("takUser", takUser)
                .putBoolean("multicastEnabled", multicastEnabled)
                .putBoolean("ridEnabled", ridEnabled)
                .putString("ridSerial", ridSerial)
                .apply();
    }
}
