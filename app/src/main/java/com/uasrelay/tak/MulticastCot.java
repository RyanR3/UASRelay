package com.uasrelay.tak;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MulticastCot — sends CoT to the ATAK SA multicast group (239.2.3.1:6969), the
 * serverless path for ATAK on the same LAN or mesh.
 *
 * Sends on every up, multicast-capable IPv4 interface rather than the default route, so
 * the marker reaches a mesh radio's network even while LTE carries the default route.
 * Sending needs no MulticastLock (that is only for receiving).
 */
public class MulticastCot {
    private static final String TAG = "MulticastCot";
    private static final String GROUP = "239.2.3.1";
    private static final int PORT = 6969;
    private static final int TTL = 8;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MulticastSocket socket;

    public void send(String xml) {
        executor.execute(() -> {
            try {
                if (socket == null) {
                    socket = new MulticastSocket();
                    socket.setTimeToLive(TTL);
                }
                byte[] data = xml.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length,
                        InetAddress.getByName(GROUP), PORT);
                for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast() || !hasIpv4(nif)) continue;
                    try {
                        socket.setNetworkInterface(nif);
                        socket.send(packet);
                    } catch (IOException e) {
                        Log.d(TAG, "send on " + nif.getName() + " failed: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "multicast send failed: " + e.getMessage());
            }
        });
    }

    public void close() {
        executor.execute(() -> {
            if (socket != null) socket.close();
            socket = null;
        });
        executor.shutdown();
    }

    private static boolean hasIpv4(NetworkInterface nif) {
        for (InterfaceAddress a : nif.getInterfaceAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return true;
        }
        return false;
    }
}
