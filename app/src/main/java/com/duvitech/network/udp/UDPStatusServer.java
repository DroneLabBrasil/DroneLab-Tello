package com.duvitech.network.udp;

import android.net.Network;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class UDPStatusServer extends Thread {
    private static final String TAG = "UDPStatusServer";
    private static final long TIMEOUT_MS = 3000;
    public interface StatusCallback {
        void onStatus(String display, int battery, boolean connected);
    }

    private final DatagramSocket socket;
    private final InetAddress droneAddress;
    private final StatusCallback callback;
    private volatile boolean running = true;

    public UDPStatusServer(Network network, StatusCallback callback) throws IOException {
        super("TelloStatusReceiver");
        if (network == null) throw new IOException("Wi-Fi do Tello indisponível");
        this.callback = callback;
        droneAddress = InetAddress.getByName(UDPClient.ADDRESS);
        socket = new DatagramSocket(8890);
        try {
            network.bindSocket(socket);
            socket.setSoTimeout(500);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public void stopServer() {
        running = false;
        socket.close();
    }

    @Override public void run() {
        byte[] buffer = new byte[2048];
        long lastValidPacket = SystemClock.elapsedRealtime();
        boolean timedOut = false;
        try {
            while (running) {
                if (!timedOut && SystemClock.elapsedRealtime() - lastValidPacket >= TIMEOUT_MS) {
                    timedOut = true;
                    if (callback != null && running)
                        callback.onStatus("SEM TELEMETRIA DO TELLO", -1, false);
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    if (!droneAddress.equals(packet.getAddress())) continue;
                    String data = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), StandardCharsets.UTF_8).trim();
                    Integer battery = parseField(data, "bat");
                    Integer height = parseField(data, "h");
                    if (battery == null || height == null || battery < 0 || battery > 100
                            || height < -1000 || height > 100000) continue;
                    lastValidPacket = SystemClock.elapsedRealtime();
                    timedOut = false;
                    if (callback != null && running)
                        callback.onStatus("BAT: " + battery + "%  ALT: " + height + " cm", battery, true);
                } catch (SocketTimeoutException ignored) {
                    // Check age below, so invalid traffic cannot keep connection alive.
                }
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Telemetry receive failure", e);
                if (callback != null) callback.onStatus("FALHA NA TELEMETRIA", -1, false);
            }
        } finally {
            socket.close();
        }
    }

    private static Integer parseField(String data, String field) {
        for (String part : data.split(";")) {
            int colon = part.indexOf(':');
            if (colon < 0 || !field.equals(part.substring(0, colon).trim())) continue;
            try { return Integer.valueOf(part.substring(colon + 1).trim()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}
