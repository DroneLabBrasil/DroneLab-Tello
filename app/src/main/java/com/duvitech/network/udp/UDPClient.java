package com.duvitech.network.udp;

import android.net.Network;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One background sender. RC updates replace old values rather than queueing stale motion. */
public final class UDPClient implements FlightTransport {
    public interface ErrorCallback { void onError(String message); }

    public static final int PORT = 8889;
    public static final String ADDRESS = "192.168.10.1";
    private static final String TAG = "UDPClient";
    private static final byte[] NEUTRAL = "rc 0 0 0 0".getBytes(StandardCharsets.UTF_8);

    private final DatagramSocket socket;
    private final InetAddress address;
    private final ArrayBlockingQueue<byte[]> commands = new ArrayBlockingQueue<>(16);
    private final AtomicReference<byte[]> latestRc = new AtomicReference<>();
    private final ErrorCallback errorCallback;
    private final Thread sender;
    private volatile boolean closing;

    public UDPClient(Network network, ErrorCallback errorCallback) throws IOException {
        if (network == null) throw new IOException("Conecte o telefone ao Wi-Fi do Tello");
        this.errorCallback = errorCallback;
        address = InetAddress.getByName(ADDRESS);
        socket = new DatagramSocket();
        try {
            network.bindSocket(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        sender = new Thread(this::sendLoop, "TelloCommandSender");
        sender.start();
    }

    public boolean sendCommand(String msg) {
        return sendBytes(msg.getBytes(StandardCharsets.UTF_8));
    }

    /** True means locally queued, never a drone acknowledgement. */
    public boolean sendBytes(byte[] bytes) {
        if (closing) return false;
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        String text = new String(copy, StandardCharsets.UTF_8);
        if (text.startsWith("rc ")) {
            latestRc.set(copy);
            return true;
        }
        if (text.equals("land") || text.equals("emergency")) {
            // Never execute an old takeoff/flip after a landing or emergency request.
            commands.clear();
            latestRc.set(NEUTRAL);
        }
        if (commands.offer(copy)) return true;
        reportError("Fila de comandos cheia; comando não enviado");
        return false;
    }

    /** Remove queued actions and replace any queued movement with neutral. */
    public void suspendControls() {
        commands.removeIf(bytes -> !isStopRequest(bytes));
        latestRc.set(NEUTRAL);
    }

    public void close() {
        closing = true;
        latestRc.set(NEUTRAL);
        sender.interrupt();
    }

    private void sendLoop() {
        try {
            while (!closing) {
                byte[] command = commands.poll(20, TimeUnit.MILLISECONDS);
                if (closing) break;
                if (command != null) send(command);
                byte[] rc = latestRc.getAndSet(null);
                if (rc != null) send(rc);
            }
        } catch (InterruptedException interrupted) {
            // close() interrupts the short queue wait; finally owns socket cleanup.
        } finally {
            try {
                // Preserve an explicit landing/emergency request even if the screen closes
                // before the worker dequeues it. Never replay queued takeoff or flips.
                byte[] queued;
                while ((queued = commands.poll()) != null) {
                    if (isStopRequest(queued)) send(queued);
                }
                send(NEUTRAL);
            } finally { socket.close(); }
        }
    }

    private static boolean isStopRequest(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.equals("land") || text.equals("emergency") || text.equals("streamoff");
    }

    private void send(byte[] bytes) {
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, address, PORT));
        } catch (IOException e) {
            if (!closing) reportError("Falha ao enviar ao Tello: " + e.getMessage());
        }
    }

    private void reportError(String message) {
        Log.e(TAG, message);
        if (errorCallback != null) errorCallback.onError(message);
    }
}
