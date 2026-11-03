package com.uasrelay.tak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Pins the groups/all → channel list and the groups/active PUT body ATAK sends. */
public class TakChannelsTest {

    private static final String ALL = "{\"version\":\"3\",\"type\":\"com.bbn.marti.remote.groups.Group\",\"data\":["
            + "{\"name\":\"Patrol\",\"direction\":\"OUT\",\"created\":\"2026-11-02\",\"type\":\"SYSTEM\",\"bitpos\":8,\"active\":true},"
            + "{\"name\":\"SAR\",\"direction\":\"OUT\",\"created\":\"2026-11-02\",\"type\":\"SYSTEM\",\"bitpos\":31,\"active\":false,\"description\":\"Search\"},"
            + "{\"name\":\"__ANON__\",\"direction\":\"OUT\",\"created\":\"2026-11-02\",\"type\":\"SYSTEM\",\"bitpos\":2,\"active\":true},"
            + "{\"name\":\"Patrol\",\"direction\":\"IN\",\"created\":\"2026-11-02\",\"type\":\"SYSTEM\",\"bitpos\":8,\"active\":true},"
            + "{\"name\":\"SAR\",\"direction\":\"IN\",\"created\":\"2026-11-02\",\"type\":\"SYSTEM\",\"bitpos\":31,\"active\":false},"
            + "{\"name\":\"__ANON__\",\"direction\":\"IN\",\"created\":\"2026-11-02\",\"type\":\"SYSTEM\",\"bitpos\":2,\"active\":true}]}";

    @Test
    public void parse_oneChannelPerName_hidesSystemGroups() throws Exception {
        List<TakChannels.Channel> ch = TakChannels.parse(ALL);
        assertEquals(2, ch.size());
        assertEquals("Patrol", ch.get(0).name);
        assertTrue(ch.get(0).active);
        assertEquals("SAR", ch.get(1).name);
        assertFalse(ch.get(1).active);
        assertEquals("Search", ch.get(1).description);
    }

    @Test
    public void activeBody_setsBothDirections_keepsHiddenGroups() throws Exception {
        JSONArray body = new JSONArray(TakChannels.activeBody(ALL, Arrays.asList("SAR")));
        assertEquals(6, body.length());
        for (int i = 0; i < body.length(); i++) {
            JSONObject g = body.getJSONObject(i);
            String name = g.getString("name");
            boolean expect = !name.equals("Patrol"); // SAR on, __ANON__ untouched (true)
            assertEquals(name + " " + g.getString("direction"), expect, g.getBoolean("active"));
            assertTrue("server fields are sent back", g.has("bitpos") && g.has("created") && g.has("type"));
        }
    }
}
