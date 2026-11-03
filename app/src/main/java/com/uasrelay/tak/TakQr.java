package com.uasrelay.tak;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * TakQr — parses the TAK Server QR codes our ATAK QR generator makes:
 *
 *   ATAK enrollment:  tak://com.atakmap.app/enroll?host=H&username=U&token=P
 *   iTAK connection:  Name,host,port,ssl
 *
 * The ATAK form carries credentials, so a scan can enroll straight away; the iTAK form
 * only says where the server is. Pure Java, covered by a JVM unit test.
 */
public final class TakQr {

    public final String host;
    /** CoT streaming port, or -1 when the code doesn't say. */
    public final int port;
    /** Empty when the code carries no credentials. */
    public final String username;
    public final String password;

    private TakQr(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public boolean hasCredentials() { return !username.isEmpty() && !password.isEmpty(); }

    /** @return the parsed code, or null if it isn't a TAK Server QR code */
    public static TakQr parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.regionMatches(true, 0, "tak://", 0, 6)) return parseEnrollLink(s);
        return parseConnectionString(s);
    }

    private static TakQr parseEnrollLink(String s) {
        int q = s.indexOf('?');
        if (q < 0 || !s.substring(0, q).toLowerCase().endsWith("/enroll")) return null;
        String host = "", user = "", pass = "";
        for (String pair : s.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            switch (key) {
                case "host": host = value; break;
                case "username": user = value; break;
                case "token": pass = value; break;
                default: break;
            }
        }
        HostPort hp = HostPort.of(host);
        return hp == null ? null : new TakQr(hp.host, hp.port, user, pass);
    }

    private static TakQr parseConnectionString(String s) {
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        String protocol = parts[3].trim().toLowerCase();
        if (!protocol.equals("ssl") && !protocol.equals("tcp")) return null;
        HostPort hp = HostPort.of(parts[1]);
        int port;
        try {
            port = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (hp == null || port <= 0 || port > 65535) return null;
        return new TakQr(hp.host, port, "", "");
    }

    /** Host without scheme or trailing slash; an inline ":port" is kept separately. */
    private static final class HostPort {
        final String host;
        final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static HostPort of(String raw) {
            String h = raw.trim().replaceFirst("(?i)^(https?|ssl|tcp)://", "").replaceAll("/+$", "");
            int port = -1;
            int colon = h.lastIndexOf(':');
            if (colon > 0 && h.indexOf(':') == colon) { // one colon: host:port (not IPv6)
                try {
                    port = Integer.parseInt(h.substring(colon + 1));
                    h = h.substring(0, colon);
                } catch (NumberFormatException ignored) {}
            }
            return h.isEmpty() || h.contains(" ") ? null : new HostPort(h, port);
        }
    }
}
