package com.uasrelay.rid;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RidScanner — receives a drone's broadcast OpenDroneID over the phone's BLE radio
 * and feeds raw message bytes to {@link RidParser}.
 *
 * DJI broadcast Remote ID is carried in BLE Service Data under the ASTM Remote ID
 * 16-bit UUID 0xFFFA, application code 0x0D (Open Drone ID), and DJI transmits it via
 * BLE5 Long Range (coded PHY) + Extended Advertising. On the wire (scanRecord.getBytes())
 * the ODID 25-byte message starts at offset 6: [len][AD type 0x16][UUID 0xFFFA LE]
 * [app code 0x0D][msg counter] then the message. (Identifiers/offset verified against
 * opendroneid/receiver-android.)
 *
 * Per-transmitter (MAC) frames are accumulated into one {@link RidMessage} so a full
 * picture (BasicID + Location + System) builds up across advertisements; each update
 * with a valid position is delivered to the listener.
 *
 * Target devices are Samsung S23+, which support extended advertising + coded PHY; we
 * still gate at runtime and report capability so unsupported phones fail loudly, not
 * silently. WiFi Beacon/NaN reception is a future addition (see receiver-android);
 * BLE is the primary path for DJI.
 */
public class RidScanner {
    private static final String TAG = "RidScanner";

    /** ASTM Remote ID service UUID (0xFFFA) used to filter OpenDroneID advertisements. */
    private static final UUID SERVICE_UUID = UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid SERVICE_PUUID = new ParcelUuid(SERVICE_UUID);
    private static final byte AD_CODE_OPEN_DRONE_ID = 0x0D;

    public interface RidListener {
        /** A drone with a valid position was seen/updated. */
        void onDrone(RidMessage message);
        /** Reception capability changed or scanning failed (message is human-readable). */
        void onStatus(String message, boolean isError);
    }

    private final Context appContext;
    private final RidListener listener;
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;

    // One accumulating picture per transmitter MAC, so multi-message frames merge.
    private final ConcurrentHashMap<String, RidMessage> byMac = new ConcurrentHashMap<>();

    public RidScanner(Context context, RidListener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    /** True if this phone can receive DJI's BLE5 Long-Range / Extended-Advertising RID. */
    public boolean isLongRangeRidSupported() {
        if (adapter == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        return adapter.isLeCodedPhySupported() && adapter.isLeExtendedAdvertisingSupported();
    }

    /** Start BLE scanning for OpenDroneID. Returns false if BLE is unavailable. */
    public boolean start() {
        if (scanning) return true;
        if (adapter == null || !adapter.isEnabled()) {
            report("Bluetooth is off or unavailable", true);
            return false;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            report("BLE scanner unavailable", true);
            return false;
        }
        if (!isLongRangeRidSupported()) {
            // Not fatal — legacy BLE RID still works; but DJI Long-Range RID will be missed.
            report("This phone lacks BLE5 Long Range / Extended Advertising — "
                    + "DJI Long-Range Remote ID may not be received. Use an S23 or newer.", true);
        }

        // Scan UNFILTERED and match OpenDroneID in the callback. A ScanFilter on the
        // 0xFFFA service data does not reliably match DJI's BT5 extended advertisements
        // (the system scanner receives them but the filter drops them before our
        // callback). receiver-android also inspects every result rather than filtering.
        List<ScanFilter> filters = new ArrayList<>();

        ScanSettings.Builder settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // legacy=false + ALL_SUPPORTED phy is required to receive BT5 extended advertisements.
            settings.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
        }

        try {
            scanner.startScan(filters, settings.build(), scanCallback);
        } catch (SecurityException se) {
            report("BLUETOOTH_SCAN permission not granted", true);
            return false;
        }
        scanning = true;
        report("Scanning for Remote ID" + (isLongRangeRidSupported() ? " (BT5 long range)" : ""), false);
        return true;
    }

    public void stop() {
        if (!scanning || scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
        }
        scanning = false;
        byMac.clear();
    }

    public boolean isScanning() { return scanning; }

    private int rawSeen = 0;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null) return;

            // DEBUG: log first results raw so we can see what's actually arriving.
            if (rawSeen < 40) {
                rawSeen++;
                byte[] raw = record.getBytes();
                Log.d(TAG, "raw scan #" + rawSeen + " " + result.getDevice().getAddress()
                        + " rssi=" + result.getRssi()
                        + " bytes=" + (raw == null ? "null" : bytesToHex(raw)));
            }

            byte[] odid = extractOdid(record);
            if (odid == null) return;

            String mac = result.getDevice().getAddress();
            int type = RidParser.messageType(odid, 0);
            Log.d(TAG, "ODID frame from " + mac + " type=" + type
                    + " rssi=" + result.getRssi());

            RidMessage acc = byMac.computeIfAbsent(mac, k -> new RidMessage());
            boolean publish = false;
            synchronized (acc) {
                if (RidParser.decodeInto(odid, acc) && acc.hasValidPosition()) {
                    publish = true;
                }
            }
            if (publish) listener.onDrone(acc);
        }

        @Override
        public void onScanFailed(int errorCode) {
            report("BLE scan failed (code " + errorCode + ")", true);
            scanning = false;
        }
    };

    /**
     * Pull the 25-byte ODID message out of the scan record. Tries the parsed service-data
     * map first; if that's empty (common for BT5 extended ads), walks the raw AD structures
     * looking for the 0xFFFA service-data AD with the Open Drone ID app code.
     */
    private static byte[] extractOdid(ScanRecord record) {
        // 1. Parsed service-data map keyed by UUID.
        byte[] sd = record.getServiceData(SERVICE_PUUID);
        if (sd != null && sd.length >= 1 + RidParser.MESSAGE_SIZE
                && sd[0] == AD_CODE_OPEN_DRONE_ID) {
            return slice(sd, 1, RidParser.MESSAGE_SIZE); // app code stripped, msg follows
        }

        // 2. Walk raw AD structures: [len][type][data...]. Service Data 16-bit = type 0x16,
        //    data = [uuid_lo][uuid_hi][app code 0x0D][odid 25 bytes].
        byte[] raw = record.getBytes();
        if (raw == null) return null;
        int i = 0;
        while (i < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0) break;
            int type = (i + 1 < raw.length) ? (raw[i + 1] & 0xFF) : -1;
            if (type == 0x16 && i + 1 + len <= raw.length && len >= 4) {
                int uuidLo = raw[i + 2] & 0xFF;
                int uuidHi = raw[i + 3] & 0xFF;
                int appCode = raw[i + 4] & 0xFF;
                // 0xFFFA little-endian = lo 0xFA, hi 0xFF; app code 0x0D.
                if (uuidLo == 0xFA && uuidHi == 0xFF && appCode == AD_CODE_OPEN_DRONE_ID) {
                    int msgStart = i + 5;
                    if (msgStart + RidParser.MESSAGE_SIZE <= raw.length) {
                        return slice(raw, msgStart, RidParser.MESSAGE_SIZE);
                    }
                }
            }
            i += 1 + len;
        }
        return null;
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void report(String msg, boolean isError) {
        if (isError) Log.w(TAG, msg); else Log.i(TAG, msg);
        if (listener != null) listener.onStatus(msg, isError);
    }
}
