package com.uasrelay.tak;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TakChannels — TAK Server "channels" (groups), the same API ATAK's Channels overlay uses:
 *
 *   GET  https://host:8443/Marti/api/groups/all?useCache=true
 *   PUT  https://host:8443/Marti/api/groups/active?clientUid=UID   (body: every group, IN + OUT)
 *
 * The server lists each group twice, once per direction. Like ATAK, a channel here is one
 * name, and choosing it turns both directions on. The active set belongs to the TAK account,
 * so another device on the same login sees the change too.
 *
 * Network calls block — call them off the UI thread.
 */
public final class TakChannels {

    public static final int DEFAULT_API_PORT = 8443;

    /** One channel as shown to the user. */
    public static final class Channel {
        public final String name;
        public final boolean active;
        public final String description;

        Channel(String name, boolean active, String description) {
            this.name = name;
            this.active = active;
            this.description = description;
        }
    }

    private final String host;
    private final int apiPort;
    private final SSLContext ssl;

    public TakChannels(String host, int apiPort, String trustStorePath, String clientCertPath,
                       String p12Password) throws Exception {
        this.host = host;
        this.apiPort = apiPort;
        this.ssl = sslContext(trustStorePath, clientCertPath, p12Password);
    }

    /** The account's channels with their current on/off state. */
    public List<Channel> list() throws Exception {
        return parse(fetchAll());
    }

    /**
     * Turn on exactly {@code selected} (by name) for this connection's account. {@code clientUid}
     * must be the uid the CoT connection is sending as, so call this once it has sent.
     */
    public void apply(String clientUid, Collection<String> selected) throws Exception {
        String body = activeBody(fetchAll(), selected);
        request("PUT", "/Marti/api/groups/active?clientUid="
                + URLEncoder.encode(clientUid, StandardCharsets.UTF_8.name()), body);
    }

    private String fetchAll() throws Exception {
        return request("GET", "/Marti/api/groups/all?useCache=true", null);
    }

    /** Channels from a groups/all response: OUT entries, in server order. */
    static List<Channel> parse(String json) throws JSONException {
        Map<String, Channel> byName = new LinkedHashMap<>();
        JSONArray data = new JSONObject(json).getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject g = data.getJSONObject(i);
            String name = g.optString("name", "");
            if (name.isEmpty() || name.startsWith("__")) continue; // __ANON__ etc.
            if ("IN".equals(g.optString("direction")) && byName.containsKey(name)) continue;
            byName.put(name, new Channel(name, g.optBoolean("active", true),
                    g.optString("description", "")));
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * The PUT body: every group from a groups/all response, both directions, with
     * {@code active} set from {@code selected}. Groups we hide (__ANON__) keep their state.
     */
    static String activeBody(String json, Collection<String> selected) throws JSONException {
        JSONArray data = new JSONObject(json).getJSONArray("data");
        JSONArray out = new JSONArray();
        for (int i = 0; i < data.length(); i++) {
            JSONObject g = data.getJSONObject(i);
            String name = g.optString("name", "");
            if (!name.isEmpty() && !name.startsWith("__")) g.put("active", selected.contains(name));
            out.put(g);
        }
        return out.toString();
    }

    private String request(String method, String path, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + host + ":" + apiPort + path)
                .openConnection();
        c.setSSLSocketFactory(ssl.getSocketFactory());
        // TAK Server certs rarely match the hostname; trust comes from the enrolled CA.
        c.setHostnameVerifier((h, s) -> true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    private static SSLContext sslContext(String trustPath, String clientPath, String pw) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(trustPath)) { trust.load(in, pw.toCharArray()); }
        KeyStore client = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(clientPath)) { client.load(in, pw.toCharArray()); }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(client, pw.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}
