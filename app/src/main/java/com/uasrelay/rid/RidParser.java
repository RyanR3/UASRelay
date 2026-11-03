package com.uasrelay.rid;

/**
 * RidParser — decodes ASTM F3411 / OpenDroneID message packs into the fields
 * UAS Relay needs (UA position/heading/speed, UAS id, operator location).
 *
 * The byte layout, field order, and scaling here are ported directly from the
 * reference implementation (opendroneid/opendroneid-core-c, opendroneid.h/.c) so
 * we match the wire format exactly rather than guessing:
 *
 *  - Each message is {@value #MESSAGE_SIZE} bytes. Byte 0 = [MessageType:high
 *    nibble][ProtoVersion:low nibble].
 *  - Multi-byte integers are little-endian (ODID transmits LE).
 *  - Scaling constants: LATLON_MULT=1e7, ALT_DIV=0.5, ALT_ADDER=1000,
 *    VSPEED_DIV=0.5, SPEED_DIV={0.25, 0.75}.
 *
 * Pure Java (no Android deps) so it is covered by JVM unit tests against the
 * reference vectors. RidScanner (BLE/WiFi) feeds raw message bytes in; this turns
 * them into a {@link RidMessage} that RelayService maps to CoT.
 */
public final class RidParser {

    public static final int MESSAGE_SIZE = 25;
    public static final int ID_SIZE = 20;

    // Message types (high nibble of byte 0).
    public static final int MSG_BASIC_ID = 0;
    public static final int MSG_LOCATION = 1;
    public static final int MSG_SYSTEM = 4;
    public static final int MSG_OPERATOR_ID = 5;
    public static final int MSG_PACKED = 0xF;

    // Scaling, from opendroneid.c.
    private static final double LATLON_MULT = 10_000_000.0;
    private static final double ALT_DIV = 0.5;
    private static final double ALT_ADDER = 1000.0;
    private static final double VSPEED_DIV = 0.5;
    private static final double[] SPEED_DIV = {0.25, 0.75};

    // ODID "invalid / no value" sentinels.
    private static final double ALT_INVALID = -1000.0;
    private static final float DIR_INVALID = 361f;
    private static final float HSPEED_INVALID = 255f;

    private RidParser() {}

    /** Top nibble of byte 0 = message type. */
    public static int messageType(byte[] msg, int offset) {
        return (msg[offset] & 0xFF) >> 4;
    }

    /**
     * Decode one 25-byte message into {@code out}, merging fields by type (a full
     * RID picture spans BasicID + Location + System messages). Returns true if the
     * message was a recognized type and decoded.
     *
     * For a {@link #MSG_PACKED} container, decodes each contained message in turn.
     */
    public static boolean decodeInto(byte[] msg, int offset, RidMessage out) {
        if (msg == null || offset + MESSAGE_SIZE > msg.length) return false;
        int type = messageType(msg, offset);
        switch (type) {
            case MSG_BASIC_ID:    return decodeBasicId(msg, offset, out);
            case MSG_LOCATION:    return decodeLocation(msg, offset, out);
            case MSG_SYSTEM:      return decodeSystem(msg, offset, out);
            case MSG_OPERATOR_ID: return decodeOperatorId(msg, offset, out);
            case MSG_PACKED:      return decodePacked(msg, offset, out);
            default:              return false;
        }
    }

    /** Convenience: decode a single message at offset 0. */
    public static boolean decodeInto(byte[] msg, RidMessage out) {
        return decodeInto(msg, 0, out);
    }

    private static boolean decodeBasicId(byte[] m, int o, RidMessage out) {
        out.uaType = (m[o + 1] & 0xFF) & 0x0F;
        out.idType = (m[o + 1] & 0xFF) >> 4;
        out.uasId = readString(m, o + 2, ID_SIZE);
        out.hasBasicId = true;
        return true;
    }

    private static boolean decodeLocation(byte[] m, int o, RidMessage out) {
        int flags = m[o + 1] & 0xFF;
        int speedMult = flags & 0x01;
        int ewDirection = (flags >> 1) & 0x01;

        int dirEnc = m[o + 2] & 0xFF;
        out.direction = (ewDirection != 0) ? dirEnc + 180f : (float) dirEnc;

        int spEnc = m[o + 3] & 0xFF;
        out.speedHorizontal = (speedMult != 0)
                ? (float) (spEnc * SPEED_DIV[1] + 255 * SPEED_DIV[0])
                : (float) (spEnc * SPEED_DIV[0]);

        out.speedVertical = (float) (((byte) m[o + 4]) * VSPEED_DIV); // int8

        out.latitude = i32le(m, o + 5) / LATLON_MULT;
        out.longitude = i32le(m, o + 9) / LATLON_MULT;

        out.altitudeBaro = decodeAltitude(u16le(m, o + 13));
        out.altitudeGeo = decodeAltitude(u16le(m, o + 15));
        out.height = decodeAltitude(u16le(m, o + 17));

        out.hasLocation = true;
        return true;
    }

    private static boolean decodeSystem(byte[] m, int o, RidMessage out) {
        out.operatorLatitude = i32le(m, o + 2) / LATLON_MULT;
        out.operatorLongitude = i32le(m, o + 6) / LATLON_MULT;
        out.hasOperator = true;
        return true;
    }

    private static boolean decodeOperatorId(byte[] m, int o, RidMessage out) {
        out.operatorId = readString(m, o + 2, ID_SIZE);
        return true;
    }

    /** Packed container: byte1 = msgSize, byte2 = count, then count messages back-to-back. */
    private static boolean decodePacked(byte[] m, int o, RidMessage out) {
        int msgSize = m[o + 1] & 0xFF;
        int count = m[o + 2] & 0xFF;
        if (msgSize != MESSAGE_SIZE) return false;
        boolean any = false;
        int base = o + 3;
        for (int i = 0; i < count; i++) {
            int off = base + i * MESSAGE_SIZE;
            if (off + MESSAGE_SIZE > m.length) break;
            any |= decodeInto(m, off, out);
        }
        return any;
    }

    private static double decodeAltitude(int altEnc) {
        return altEnc * ALT_DIV - ALT_ADDER;
    }

    // --- little-endian readers ---

    private static int u16le(byte[] m, int o) {
        return (m[o] & 0xFF) | ((m[o + 1] & 0xFF) << 8);
    }

    private static int i32le(byte[] m, int o) {
        return (m[o] & 0xFF) | ((m[o + 1] & 0xFF) << 8)
                | ((m[o + 2] & 0xFF) << 16) | ((m[o + 3] & 0xFF) << 24);
    }

    /** Read a NUL-padded ASCII field of up to len bytes. */
    private static String readString(byte[] m, int o, int len) {
        int end = o;
        int limit = Math.min(o + len, m.length);
        while (end < limit && m[end] != 0) end++;
        return new String(m, o, end - o, java.nio.charset.StandardCharsets.US_ASCII);
    }

    // --- validity helpers (ODID sentinels) ---

    public static boolean validLatLon(double lat, double lon) {
        // ODID uses 0,0 as "no value". Treat exact 0,0 as invalid.
        return !(lat == 0.0 && lon == 0.0)
                && lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0;
    }

    public static boolean validAltitude(double alt) { return alt > ALT_INVALID; }
    public static boolean validDirection(float dir) { return dir < DIR_INVALID; }
    public static boolean validHorizontalSpeed(float sp) { return sp < HSPEED_INVALID; }
}
