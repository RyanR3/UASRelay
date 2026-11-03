package com.uasrelay.rid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WifiRidScanner — receives FAA Standard Remote ID broadcast over Wi-Fi Beacon.
 *
 * DJI DoC-listed drones (Avata, Mini, possibly Neo) broadcast compliant OpenDroneID over Wi-Fi
 * once motors are started — it appears as a "RID-&lt;serial&gt;" network. The RID payload
 * rides in a Wi-Fi **vendor-specific information element** (element id 221) with:
 *
 *   [CID 3 bytes = FA:0B:BC][vendor type 1 byte = 0x0D][msg counter 1 byte][ODID message...]
 *
 * So the 25-byte ODID message starts at offset 5 within the IE bytes. Identifiers/offset
 * verified against opendroneid/receiver-android (WiFiBeaconScanner). The decoded message
 * goes to the same {@link RidParser} as BLE.
 *
 * This is the transport that carries DJI's parseable RID — the BLE 0xFEF3 path is
 * DJI's encrypted proprietary DroneID and is not used here.
 *
 * Note: Android throttles app-initiated Wi-Fi scans (a few per minute on API 28+), so RID
 * updates over Beacon are slower than BLE. Wi-Fi NaN (not implemented here) is faster where
 * supported; Beacon is the broadly-available path and matches DJI's RID network.
 */
public class WifiRidScanner {
    private static final String TAG = "WifiRidScanner";

    private static final int VENDOR_IE_ID = 221;
    private static final int[] DRI_CID = {0xFA, 0x0B, 0xBC};
    private static final int VENDOR_TYPE = 0x0D;
    /** CID(3) + vendor type(1) + msg counter(1) before the ODID message. */
    private static final int MSG_OFFSET = 5;

    public interface WifiRidListener {
        void onDrone(RidMessage message);
        void onStatus(String message, boolean isError);
    }

    private final Context appContext;
    private final WifiRidListener listener;
    private final WifiManager wifiManager;
    private boolean scanning;

    private final ConcurrentHashMap<String, RidMessage> byBssid = new ConcurrentHashMap<>();

    public WifiRidScanner(Context context, WifiRidListener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (updated) handleResults();
            requestScan(); // keep polling
        }
    };

    public boolean start() {
        if (scanning) return true;
        if (wifiManager == null) {
            report("Wi-Fi unavailable", true);
            return false;
        }
        if (!wifiManager.isWifiEnabled()) {
            report("Wi-Fi is off — enable it to receive Remote ID", true);
            return false;
        }
        appContext.registerReceiver(scanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        scanning = true;
        requestScan();
        report("Scanning Wi-Fi for Remote ID", false);
        return true;
    }

    public void stop() {
        if (!scanning) return;
        try { appContext.unregisterReceiver(scanReceiver); } catch (IllegalArgumentException ignored) {}
        scanning = false;
        byBssid.clear();
    }

    public boolean isScanning() { return scanning; }

    @SuppressWarnings("deprecation") // startScan throttled but still the available trigger
    private void requestScan() {
        try {
            wifiManager.startScan();
        } catch (SecurityException se) {
            report("Wi-Fi scan permission missing", true);
        }
    }

    private void handleResults() {
        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException se) {
            report("Location permission needed for Wi-Fi scan results", true);
            return;
        }
        if (results == null) return;
        for (ScanResult r : results) {
            byte[] msg = extractOdidFromBeacon(r);
            if (msg == null) continue;

            Log.d(TAG, "Wi-Fi ODID from " + r.BSSID + " (" + r.SSID + ") type="
                    + RidParser.messageType(msg, 0) + " rssi=" + r.level
                    + " bytes=" + hex(msg));

            RidMessage acc = byBssid.computeIfAbsent(r.BSSID, k -> new RidMessage());
            boolean publish;
            synchronized (acc) {
                publish = RidParser.decodeInto(msg, acc) && acc.hasValidPosition();
            }
            if (publish) listener.onDrone(acc);
        }
    }

    /**
     * Find the DRI vendor IE and return the ODID payload after the CID/type/counter
     * header. May be a single 25-byte message or a packed container (type 0xF) of
     * several — return the whole thing and let RidParser handle it.
     */
    private static byte[] extractOdidFromBeacon(ScanResult result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null; // need public IE API
        List<ScanResult.InformationElement> ies = result.getInformationElements();
        if (ies == null) return null;
        for (ScanResult.InformationElement ie : ies) {
            if (ie == null || ie.getId() != VENDOR_IE_ID) continue;
            ByteBuffer buf = ie.getBytes();
            if (buf == null || buf.remaining() < MSG_OFFSET + 1) continue;
            byte[] b = new byte[buf.remaining()];
            buf.get(b);
            if ((b[0] & 0xFF) == DRI_CID[0] && (b[1] & 0xFF) == DRI_CID[1]
                    && (b[2] & 0xFF) == DRI_CID[2] && (b[3] & 0xFF) == VENDOR_TYPE) {
                byte[] payload = new byte[b.length - MSG_OFFSET];
                System.arraycopy(b, MSG_OFFSET, payload, 0, payload.length);
                return payload;
            }
        }
        return null;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void report(String msg, boolean isError) {
        if (isError) Log.w(TAG, msg); else Log.i(TAG, msg);
        if (listener != null) listener.onStatus(msg, isError);
    }
}
