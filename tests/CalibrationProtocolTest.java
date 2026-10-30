package com.duvitech.tello.calibration;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone tests: no Android/JUnit dependency. Wire CRC vectors generated with TelloPy's crc.py. */
public final class CalibrationProtocolTest {
    public static void main(String[] args) throws Exception {
        check(hex(TelloBinaryProtocol.packet(0x5a, 0x48, 0x1e4, new byte[0])).equals("cc58007c485a00e40111d8"), "TelloPy start vector");
        check(hex(TelloBinaryProtocol.packet(0x5b, 0x48, 0x1e5, new byte[0])).equals("cc58007c485b00e50172dd"), "TelloPy poll vector");
        byte[] valid = unhex("cc800052505b000000003f006400c598");
        check(TelloBinaryProtocol.parse(valid, valid.length).command == 0x5b, "TelloPy completion vector");
        for (int i = 0; i < valid.length; i++) {
            byte[] bad = valid.clone(); bad[i] ^= 1;
            check(TelloBinaryProtocol.parse(bad, bad.length) == null, "reject corrupted byte " + i);
        }
        check(TelloBinaryProtocol.parse(valid, 10) == null, "reject truncated frame");
        runSession(false, 80, true, true, false);
        runSession(true, 80, false, false, false);
        runSession(false, 49, false, false, false);
        runSession(false, 80, true, false, false); // telemetry remains alive but missing calibration status must time out
        runSession(false, 80, true, false, true); // an old completed status cannot confirm a new calibration
        System.out.println("PASS: 3 reference vectors, 16 byte corruptions, truncation, loopback success, airborne refusal, low-battery refusal, status timeout, stale-completion refusal");
    }

    private static void runSession(boolean airborne, int battery, boolean expectStart, boolean finish, boolean oldComplete) throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(50);
            CountDownLatch done = new CountDownLatch(1);
            State state = new State();
            ImuCalibrationClient client = new ImuCalibrationClient(socket -> { }, new ImuCalibrationClient.Listener() {
                public void onMessage(String m) { }
                public void onProgress(int percent, int steps) { state.percent = percent; state.steps = steps; }
                public void onFinished() { state.finished = true; done.countDown(); }
                public void onError(String error) { state.error = error; done.countDown(); }
            }, new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort()), 0, 1500, 400, 2000);
            client.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            int startCount = 0;
            boolean bound = false;
            InetSocketAddress remote = null;
            while (done.getCount() != 0 && System.nanoTime() < end) {
                byte[] buf = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try { server.receive(packet); } catch (SocketTimeoutException ignored) { continue; }
                remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
                if (Arrays.equals(Arrays.copyOf(buf, packet.getLength()), TelloBinaryProtocol.CONNECT)) {
                    byte[] ack = TelloBinaryProtocol.CONNECT.clone(); ack[5] = 'a'; ack[6] = 'c'; ack[7] = 'k';
                    server.send(new DatagramPacket(ack, ack.length, remote));
                    byte[] payload = new byte[24]; payload[12] = (byte)battery; payload[17] = (byte)(airborne ? 5 : 0); payload[18] = 6;
                    send(server, remote, 0x56, payload);
                    bound = true;
                } else {
                    TelloBinaryProtocol.Reply reply = TelloBinaryProtocol.parse(buf, packet.getLength());
                    if (reply == null) throw new AssertionError("client frame invalid");
                    if (reply.command == 0x5a) startCount++;
                    check(reply.command == 0x46 || reply.command == 0x50 || reply.command == 0x5a || reply.command == 0x5b, "no motor command");
                    if (reply.command == 0x5b && finish) {
                        send(server, remote, 0x5b, new byte[]{0, 0x10, 0, 95, 0});
                        send(server, remote, 0x5b, new byte[]{0, 0x3f, 0, 100, 0});
                    } else if (reply.command == 0x5b && oldComplete) {
                        send(server, remote, 0x5b, new byte[]{0, 0x3f, 0, 100, 0});
                    } else if (reply.command == 0x50 && !finish && expectStart) {
                        // Keep regular telemetry alive; status-specific timeout should still fire.
                        byte[] payload = new byte[24]; payload[12] = 80; payload[17] = 0; payload[18] = 6;
                        send(server, remote, 0x56, payload);
                    }
                }
            }
            check(done.await(1, TimeUnit.SECONDS), "session must terminate");
            client.close();
            check(bound, "handshake performed");
            check(startCount == (expectStart ? 1 : 0), "calibration starts exactly once only when allowed");
            check(state.finished == finish, "completion result");
            if (finish) check(state.percent == 100 && state.steps == 6, "completion progress");
            else check(state.error != null, "refusal/timeout reported");
        }
    }
    private static void send(DatagramSocket socket, InetSocketAddress target, int cmd, byte[] payload) throws Exception {
        byte[] data = TelloBinaryProtocol.packet(cmd, 0x50, 0, payload);
        socket.send(new DatagramPacket(data, data.length, target));
    }
    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (byte b : data) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static byte[] unhex(String value) {
        byte[] result = new byte[value.length()/2];
        for (int i=0;i<result.length;i++) result[i] = (byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return result;
    }
    private static void check(boolean good, String message) { if (!good) throw new AssertionError(message); }
    private static final class State { volatile boolean finished; volatile String error; volatile int percent, steps; }
}
