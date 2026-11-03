package com.uasrelay.rid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Cross-validates RidParser against the reference opendroneid-core-c ENCODER.
 *
 * The hex vectors below were produced by encoding known field values with the
 * reference C library (encodeBasicIDMessage/encodeLocationMessage/encodeSystemMessage).
 * If our decoder recovers those same values, the byte layout + scaling match the spec.
 *
 *   BASICID  serial="AVATA2-SN-12345"
 *   LOCATION dir=215  hspd=12.25  vspd=-3.5  lat=41.7100123  lon=-74.7600456
 *            altGeo=152.5  height=120.0
 *   SYSTEM   oplat=41.7080789  oplon=-74.7610111
 */
public class RidParserTest {

    private static final byte[] BASICID  = hex("02124156415441322d534e2d31323334350000000000000000");
    private static final byte[] LOCATION = hex("12222331f95b71dc18b88570d3fc080109c008000000000000");
    private static final byte[] SYSTEM   = hex("4200d525dc18016070d3000000d007d0070098080000000000");

    private static final double LL_EPS = 1e-5;   // ODID lat/lon quantization ~1e-7 deg
    private static final double F_EPS = 0.3;     // direction/altitude quantization

    @Test
    public void messageType_readFromHighNibble() {
        assertEquals(RidParser.MSG_BASIC_ID, RidParser.messageType(BASICID, 0));
        assertEquals(RidParser.MSG_LOCATION, RidParser.messageType(LOCATION, 0));
        assertEquals(RidParser.MSG_SYSTEM, RidParser.messageType(SYSTEM, 0));
    }

    @Test
    public void basicId_recoversSerial() {
        RidMessage m = new RidMessage();
        assertTrue(RidParser.decodeInto(BASICID, m));
        assertTrue(m.hasBasicId);
        assertEquals("AVATA2-SN-12345", m.uasId);
        assertEquals("AVATA2-SN-12345", m.bestUid());
    }

    @Test
    public void location_recoversPositionHeadingSpeed() {
        RidMessage m = new RidMessage();
        assertTrue(RidParser.decodeInto(LOCATION, m));
        assertTrue(m.hasLocation);
        assertEquals(41.7100123, m.latitude, LL_EPS);
        assertEquals(-74.7600456, m.longitude, LL_EPS);
        assertEquals(215.0, m.direction, F_EPS);
        assertEquals(12.25, m.speedHorizontal, 0.25);   // 0.25 m/s quantization
        assertEquals(-3.5, m.speedVertical, 0.5);       // 0.5 m/s quantization
        assertEquals(152.5, m.altitudeGeo, F_EPS);
        assertEquals(120.0, m.height, F_EPS);
        assertTrue(m.hasValidPosition());
    }

    @Test
    public void system_recoversOperatorLocation() {
        RidMessage m = new RidMessage();
        assertTrue(RidParser.decodeInto(SYSTEM, m));
        assertTrue(m.hasOperator);
        assertEquals(41.7080789, m.operatorLatitude, LL_EPS);
        assertEquals(-74.7610111, m.operatorLongitude, LL_EPS);
        assertTrue(m.hasValidOperator());
    }

    @Test
    public void fullFrame_mergesAllMessagesIntoOnePicture() {
        // A real aircraft emits these as separate messages; we merge into one RidMessage.
        RidMessage m = new RidMessage();
        RidParser.decodeInto(BASICID, m);
        RidParser.decodeInto(LOCATION, m);
        RidParser.decodeInto(SYSTEM, m);
        assertEquals("AVATA2-SN-12345", m.bestUid());
        assertTrue(m.hasValidPosition());
        assertTrue(m.hasValidOperator());
        assertEquals(41.7100123, m.latitude, LL_EPS);
        assertEquals(41.7080789, m.operatorLatitude, LL_EPS);
    }

    @Test
    public void invalidPosition_zeroZeroRejected() {
        assertFalse(RidParser.validLatLon(0.0, 0.0));
        assertTrue(RidParser.validLatLon(41.71, -74.76));
    }

    @Test
    public void shortBuffer_failsGracefully() {
        RidMessage m = new RidMessage();
        assertFalse(RidParser.decodeInto(new byte[]{0x12, 0x00}, m));
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
