package com.uasrelay.tak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the proven drone+video CoT shape (adapted from AvataTAK) so refactors can't
 * silently break the ATAK contract: the air-domain icon type, the embedded video, the
 * operator link, and well-formed XML.
 */
public class CotBuilderTest {

    private static final String UID = "UASRelay-SM-S931U";
    private static final String RTSP = "rtsp://10.0.0.5:8554/uas";
    private static final String OP_UID = UID + "-OP";

    private String dronePli() {
        return CotBuilder.buildDronePLI(
                UID, "UAS-1",
                41.7100, -74.7600, 120.5, 12.0,
                270.0, 8.25,
                RTSP, OP_UID, "Position: Remote ID");
    }

    @Test
    public void dronePli_usesAirDomainDroneIconType() {
        // a-f-A-M-H-Q is what ATAK recognizes as a drone (and what DJI's UAS tool emits).
        assertTrue("drone type must be the air-domain UAS type",
                dronePli().contains("type=\"a-f-A-M-H-Q\""));
    }

    @Test
    public void dronePli_embedsVideoUrlInDetail() {
        // The whole point: one icon carries its own clickable live feed.
        assertTrue("video must be embedded as <__video url=...> (no uid: ATAK then ignores the url)",
                dronePli().contains("<__video url=\"" + RTSP + "\" />"));
    }

    @Test
    public void dronePli_carriesPositionAccuracyAndTrack() {
        String xml = dronePli();
        assertTrue(xml.contains("lat=\"41.71\""));
        assertTrue(xml.contains("lon=\"-74.76\""));
        assertTrue(xml.contains("hae=\"120.5\""));
        assertTrue(xml.contains("ce=\"12.0\""));
        assertTrue(xml.contains("<track course=\"270.0\" speed=\"8.25\" />"));
        assertTrue(xml.contains("<remarks>Position: Remote ID</remarks>"));
    }

    @Test
    public void dronePli_linksToOperator() {
        assertTrue("drone must link to operator uid",
                dronePli().contains("<link uid=\"" + OP_UID + "\" type=\"a-f-G-U-C\" relation=\"p-p\" />"));
    }

    @Test
    public void dronePli_omitsOptionalElementsWhenNull() {
        String xml = CotBuilder.buildDronePLI(UID, "UAS-1",
                41.71, -74.76, 120.0, 9999999, 0.0, 0.0, null, null, null);
        assertFalse("no <__video> when url is null", xml.contains("__video"));
        assertFalse("no operator link when uid is null", xml.contains("<link"));
        assertFalse("no remarks when null", xml.contains("<remarks>"));
    }

    @Test
    public void dronePli_escapesXml() {
        String xml = CotBuilder.buildDronePLI(UID, "A&B<\"'>",
                41.71, -74.76, 120.0, 5.0, 0.0, 0.0, RTSP, OP_UID, null);
        assertTrue(xml.contains("callsign=\"A&amp;B&lt;&quot;&apos;&gt;\""));
        assertFalse("raw ampersand must not leak", xml.contains("A&B"));
    }

    @Test
    public void operatorMarker_isFriendlyGroundLinkedToDrone() {
        String xml = CotBuilder.buildOperatorMarker(OP_UID, "UAS-1 OP", UID, 41.7080, -74.7610);
        assertTrue(xml.contains("type=\"a-f-G\""));
        assertTrue(xml.contains("lat=\"41.708\""));
        assertTrue(xml.contains("<link uid=\"" + UID + "\" type=\"a-f-A-M-H-Q\" relation=\"p-p\" />"));
    }

    @Test
    public void allEvents_areWellFormedAndSingleRooted() {
        for (String xml : new String[]{dronePli(),
                CotBuilder.buildOperatorMarker(OP_UID, "op", UID, 41.7, -74.7)}) {
            assertTrue("has xml decl", xml.startsWith("<?xml version=\"1.0\""));
            assertTrue("opens event", xml.contains("<event "));
            assertEquals("exactly one </event>", 1, countOccurrences(xml, "</event>"));
            assertEquals("balanced <detail>", countOccurrences(xml, "<detail>"),
                    countOccurrences(xml, "</detail>"));
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) { count++; i += needle.length(); }
        return count;
    }
}
