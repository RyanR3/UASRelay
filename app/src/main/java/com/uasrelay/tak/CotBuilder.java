package com.uasrelay.tak;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * CoT XML builders for UAS Relay.
 *
 * Adapted from AvataTAK (itself from the proven ATAKPilot2 CotBuilder). Consumer DJI drones are
 * not all in DJI MSDK, so there is no telemetry: the track position comes from broadcast
 * OpenDroneID when the drone is heard, otherwise from the phone's own GPS (the pilot).
 *
 * The key piece is {@link #buildDronePLI}: a single CoT event that is BOTH the drone
 * air-track AND its live video, via a {@code <__video url="..."/>} detail element. One
 * icon in ATAK/CloudTAK that carries its clickable feed. The uid stays the same whether
 * the position source is RID or the phone, so viewers keep the same marker and feed.
 */
public class CotBuilder {
    private static final SimpleDateFormat COT_DATE_FORMAT;
    private static final String COT_VERSION = "2.0";

    // Drone (air) track: friendly-Air-Military-rotorcraftH-unmanned(Q). ATAK's CotParser
    // detects the air domain ("-A-") as a drone and draws it correctly; this is what DJI's
    // own UAS tool emits. Short stale so a stopped app clears quickly.
    private static final String DRONE_TYPE = "a-f-A-M-H-Q";
    private static final long DRONE_STALE_DURATION_MS = 15000; // 15s

    static {
        COT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Build the AIRCRAFT (drone) CoT as an air track that also advertises its video feed.
     *
     * @param uid         stable per-app uid (same for RID and phone-GPS positions)
     * @param callsign    drone callsign shown on TAK
     * @param lat,lon     position (deg)
     * @param hae         height above ellipsoid (m)
     * @param ce          circular error (m); 9999999 when unknown
     * @param heading     true heading / course (deg, 0..360)
     * @param speed       horizontal ground speed (m/s)
     * @param videoUrl    RTSP url viewers pull (null/empty to omit)
     * @param operatorUid operator's CoT uid to link to (null/empty to omit)
     * @param remarks     free text shown in the marker details (null/empty to omit)
     */
    public static String buildDronePLI(String uid, String callsign,
                                       double lat, double lon, double hae, double ce,
                                       double heading, double speed,
                                       String videoUrl, String operatorUid, String remarks) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + DRONE_STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(DRONE_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(hae).append("\"");
        sb.append(" ce=\"").append(ce).append("\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<track course=\"").append(heading).append("\"");
        sb.append(" speed=\"").append(speed).append("\" />");
        sb.append("<precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\" />");
        // THE merge: the drone icon carries its own live feed.
        if (videoUrl != null && !videoUrl.isEmpty()) {
            // url only: a uid attribute makes ATAK look up a saved alias by that uid instead
            // of using the url, and it shows "invalid video information".
            sb.append("<__video url=\"").append(escapeXml(videoUrl)).append("\" />");
        }
        // Associate the drone with its operator (parent-point), like the DJI UAS tool.
        if (operatorUid != null && !operatorUid.isEmpty()) {
            sb.append("<link uid=\"").append(escapeXml(operatorUid)).append("\"");
            sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        }
        if (remarks != null && !remarks.isEmpty()) {
            sb.append("<remarks>").append(escapeXml(remarks)).append("</remarks>");
        }
        sb.append("<takv device=\"UAS Relay\" os=\"Android\"");
        sb.append(" platform=\"UAS Relay\" version=\"0.1\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    /**
     * Build the OPERATOR ground marker (the phone / pilot). A friendly ground point (a-f-G)
     * linked back to the drone, so TAK shows both aircraft and pilot once RID places the
     * drone somewhere other than the phone.
     *
     * @param uid       stable operator uid (e.g. "<drone-uid>-OP")
     * @param callsign  label shown on TAK
     * @param droneUid  the drone's CoT uid, for the association link
     * @param lat,lon   operator position (deg)
     */
    public static String buildOperatorMarker(String uid, String callsign, String droneUid,
                                             double lat, double lon) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + DRONE_STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"a-f-G\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"0\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        if (droneUid != null && !droneUid.isEmpty()) {
            sb.append("<link uid=\"").append(escapeXml(droneUid)).append("\"");
            sb.append(" type=\"").append(DRONE_TYPE).append("\" relation=\"p-p\" />");
        }
        sb.append("<remarks>UAS pilot</remarks>");
        sb.append("<precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    private static String formatTime(long millis) {
        synchronized (COT_DATE_FORMAT) {
            return COT_DATE_FORMAT.format(new Date(millis));
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
