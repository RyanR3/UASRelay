package com.uasrelay.tak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pins both QR formats our ATAK QR generator emits, plus junk rejection. */
public class TakQrTest {

    @Test
    public void atakEnrollLink_givesHostAndCredentials() {
        TakQr qr = TakQr.parse("tak://com.atakmap.app/enroll?host=tak.example.org"
                + "&username=pilot1&token=s3cr%26t%20pw");
        assertNotNull(qr);
        assertEquals("tak.example.org", qr.host);
        assertEquals(-1, qr.port);
        assertEquals("pilot1", qr.username);
        assertEquals("s3cr&t pw", qr.password); // URL-decoded
        assertTrue(qr.hasCredentials());
    }

    @Test
    public void atakEnrollLink_stripsSchemeAndKeepsInlinePort() {
        TakQr qr = TakQr.parse("tak://com.atakmap.app/enroll?host=https%3A%2F%2Ftak.example.org%3A8089%2F"
                + "&username=u&token=p");
        assertNotNull(qr);
        assertEquals("tak.example.org", qr.host);
        assertEquals(8089, qr.port);
    }

    @Test
    public void itakConnectionString_givesHostAndPortOnly() {
        TakQr qr = TakQr.parse("TAK Server,tak.example.org,8089,ssl");
        assertNotNull(qr);
        assertEquals("tak.example.org", qr.host);
        assertEquals(8089, qr.port);
        assertFalse(qr.hasCredentials());
    }

    @Test
    public void enrollLinkWithoutToken_hasNoCredentials() {
        TakQr qr = TakQr.parse("tak://com.atakmap.app/enroll?host=tak.example.org&username=u");
        assertNotNull(qr);
        assertFalse(qr.hasCredentials());
    }

    @Test
    public void rejectsCodesThatAreNotTakServers() {
        assertNull(TakQr.parse(null));
        assertNull(TakQr.parse(""));
        assertNull(TakQr.parse("https://example.com"));
        assertNull(TakQr.parse("tak://com.atakmap.app/import?url=x"));
        assertNull(TakQr.parse("tak://com.atakmap.app/enroll?username=u&token=p")); // no host
        assertNull(TakQr.parse("TAK Server,host,notaport,ssl"));
        assertNull(TakQr.parse("a,b,c"));
    }
}
