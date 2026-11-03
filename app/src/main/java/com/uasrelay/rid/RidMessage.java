package com.uasrelay.rid;

/**
 * Accumulated Remote ID picture for one aircraft, filled across the message types
 * that make up a full ODID frame (BasicID + Location + System + OperatorID). Field
 * names/units follow the OpenDroneID decoded structs.
 *
 * A plain mutable holder: {@link RidParser#decodeInto} merges each message into one
 * of these; the caller checks {@link #hasLocation} before producing a CoT track.
 */
public class RidMessage {
    // BasicID
    public boolean hasBasicId;
    public int idType;
    public int uaType;
    public String uasId = "";

    // Location (UA)
    public boolean hasLocation;
    public double latitude;        // deg
    public double longitude;       // deg
    public double altitudeGeo;     // m WGS84-HAE
    public double altitudeBaro;    // m
    public double height;          // m above takeoff
    public float direction;        // deg true, 0..360
    public float speedHorizontal;  // m/s
    public float speedVertical;    // m/s

    // System (operator)
    public boolean hasOperator;
    public double operatorLatitude;
    public double operatorLongitude;

    // OperatorID
    public String operatorId = "";

    /** Best available aircraft identifier for the CoT uid (serial, else operator id). */
    public String bestUid() {
        if (uasId != null && !uasId.isEmpty()) return uasId;
        if (operatorId != null && !operatorId.isEmpty()) return operatorId;
        return "RID-UNKNOWN";
    }

    /** True when there's a usable UA position to place a track. */
    public boolean hasValidPosition() {
        return hasLocation && RidParser.validLatLon(latitude, longitude);
    }

    /** True when the operator location is usable for an operator marker. */
    public boolean hasValidOperator() {
        return hasOperator && RidParser.validLatLon(operatorLatitude, operatorLongitude);
    }

    /** Reset for reuse across frames. */
    public void clear() {
        hasBasicId = hasLocation = hasOperator = false;
        idType = uaType = 0;
        uasId = operatorId = "";
        latitude = longitude = altitudeGeo = altitudeBaro = height = 0;
        direction = speedHorizontal = speedVertical = 0;
        operatorLatitude = operatorLongitude = 0;
    }
}
