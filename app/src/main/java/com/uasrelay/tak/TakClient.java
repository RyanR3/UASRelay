package com.uasrelay.tak;

import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class TakClient extends Thread {
    private static final String TAG = "TakClient";
    private static final long RECONNECT_DELAY_MS = 5000;

    private final String serverAddress;
    private final int port;
    private final String trustStorePath;
    private final String trustStorePassword;
    private final String clientCertPath;
    private final String clientCertPassword;
    private final TakClientListener listener;

    private volatile boolean mRun;
    /** Raw TLS output. Not a PrintWriter: that swallows write errors, so a broken link
     *  kept "sending" into nothing while the app still showed connected. */
    private volatile OutputStream mOut;
    private InputStream mInputStream;
    private volatile SSLSocket sslSocket;
    private Socket socket;
    /** One writer thread, so messages go out whole and in order. */
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "TakClient-Send"));
    private long sentCount;

    public interface TakClientListener {
        void onConnected();
        void onDisconnected();
        void onCotReceived(String xml);
    }

    public TakClient(String serverAddress, int port, String trustStorePath, String trustStorePassword,
                     String clientCertPath, String clientCertPassword, TakClientListener listener) {
        super("TakClient-Thread");
        this.serverAddress = serverAddress;
        this.port = port;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.clientCertPath = clientCertPath;
        this.clientCertPassword = clientCertPassword;
        this.listener = listener;
        setDaemon(true);
    }

    public void sendMessage(final String message) {
        final OutputStream out = mOut;
        if (out == null) return;
        sendExecutor.execute(() -> {
            try {
                out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                long n = ++sentCount;
                if (n == 1 || n % 30 == 0) Log.i(TAG, "Sent " + n + " CoT message(s)");
            } catch (IOException e) {
                // Drop the link: the read loop sees the closed socket and reconnects.
                Log.e(TAG, "Send failed, dropping connection: " + e.getMessage());
                SSLSocket s = sslSocket;
                try { if (s != null) s.close(); } catch (IOException ignored) {}
            }
        });
    }

    /** Safe to call from the main thread: closing a TLS socket writes close_notify. */
    public void stopClient() {
        mRun = false;
        sendExecutor.shutdownNow();
        new Thread(this::closeConnection, "TakClient-Close").start();
    }

    public boolean isConnected() {
        if (sslSocket != null) {
            return sslSocket.isConnected() && !sslSocket.isClosed();
        }
        if (socket != null) {
            return socket.isConnected() && !socket.isClosed();
        }
        return false;
    }

    @Override
    public void run() {
        mRun = true;
        while (mRun) {
            try {
                connect();
                if (listener != null) listener.onConnected();

                byte[] buf = new byte[8192];
                StringBuilder recvBuffer = new StringBuilder();

                while (mRun) {
                    int bytesRead;
                    try {
                        bytesRead = mInputStream.read(buf);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (SocketException e) {
                        Log.e(TAG, "Socket error: " + e.getMessage());
                        break;
                    }
                    if (bytesRead == -1) {
                        Log.w(TAG, "Server closed connection (EOF)");
                        break;
                    }
                    String chunk = new String(buf, 0, bytesRead, "UTF-8");
                    recvBuffer.append(chunk);

                    int endIdx;
                    while ((endIdx = recvBuffer.indexOf("</event>")) != -1) {
                        int end = endIdx + "</event>".length();
                        String message = recvBuffer.substring(0, end).trim();
                        recvBuffer.delete(0, end);
                        if (!message.isEmpty() && listener != null) {
                            listener.onCotReceived(message);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            }

            closeConnection();
            if (listener != null) listener.onDisconnected();

            if (mRun) {
                Log.i(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void connect() throws Exception {
        InetAddress addr = InetAddress.getByName(serverAddress);
        boolean useTls = trustStorePath != null && !trustStorePath.isEmpty()
                && clientCertPath != null && !clientCertPath.isEmpty();

        if (!useTls) {
            Log.e(TAG, "Refusing to connect: no client certificate / trust store present. Plaintext connections are not permitted.");
            throw new SocketException("Not enrolled: TLS certificates required");
        }

        Log.i(TAG, "Connecting via TLS to " + serverAddress + ":" + port);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        FileInputStream trustIs = new FileInputStream(trustStorePath);
        trustStore.load(trustIs, trustStorePassword.toCharArray());
        trustIs.close();

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        FileInputStream clientIs = new FileInputStream(clientCertPath);
        clientStore.load(clientIs, clientCertPassword.toCharArray());
        clientIs.close();

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, clientCertPassword.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        SSLSocketFactory factory = sslContext.getSocketFactory();
        sslSocket = (SSLSocket) factory.createSocket(addr, port);
        sslSocket.setSoTimeout(1000);

        mOut = sslSocket.getOutputStream();
        mInputStream = sslSocket.getInputStream();
        Log.i(TAG, "Connected to " + serverAddress + ":" + port);
    }

    private void closeConnection() {
        mOut = null;
        try { if (sslSocket != null) sslSocket.close(); } catch (IOException e) {}
        try { if (socket != null) socket.close(); } catch (IOException e) {}
        try { if (mInputStream != null) mInputStream.close(); } catch (IOException e) {}
        mInputStream = null;
        sslSocket = null;
        socket = null;
    }
}
