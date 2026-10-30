package com.duvitech.tello.calibration;

import java.util.Arrays;
import java.util.Calendar;

/**
 * Independent encoding of the Tello binary wire format. Calibration semantics:
 * https://github.com/hanyazou/TelloPy/tree/develop-0.8.0 (Apache-2.0).
 * Ground/flying flag descriptions: https://github.com/SMerrony/tello (MIT).
 * There is deliberately no takeoff, motor-start, or center-of-gravity command.
 */
public final class TelloBinaryProtocol {
    public static final int CALIBRATION_START = 0x005a;
    public static final int CALIBRATION_STATUS = 0x005b;
    public static final int FLIGHT_STATUS = 0x0056;
    public static final int LOG_HEADER = 0x1050;
    public static final byte[] CONNECT = {'c','o','n','n','_','r','e','q',':',(byte)0x96,0x17};
    private static final byte[] ACK_PREFIX = {'c','o','n','n','_','a','c','k',':'};

    private TelloBinaryProtocol() { }

    public static byte[] packet(int command, int type, int sequence, byte[] payload) {
        if (payload == null || payload.length > 2048) throw new IllegalArgumentException("payload");
        int size = 11 + payload.length;
        byte[] result = new byte[size];
        result[0] = (byte)0xcc;
        put16(result, 1, size << 3);
        result[3] = (byte)crc8(result, 0, 3);
        result[4] = (byte)type;
        put16(result, 5, command);
        put16(result, 7, sequence);
        System.arraycopy(payload, 0, result, 9, payload.length);
        put16(result, size - 2, crc16(result, 0, size - 2));
        return result;
    }

    /** Returns null for malformed packets, including invalid lengths or CRCs. */
    public static Reply parse(byte[] data, int size) {
        if (data == null || size < 11 || size > data.length || (data[0] & 255) != 0xcc) return null;
        int encodedSize = u16(data, 1);
        if ((encodedSize & 7) != 0 || (encodedSize >>> 3) != size) return null;
        if ((data[3] & 255) != crc8(data, 0, 3)) return null;
        if (u16(data, size - 2) != crc16(data, 0, size - 2)) return null;
        return new Reply(u16(data, 5), u16(data, 7), data[4] & 255,
                Arrays.copyOfRange(data, 9, size - 2));
    }

    public static boolean isConnectionAck(byte[] data, int length) {
        if (length != 11 || length > data.length) return false;
        for (int i = 0; i < ACK_PREFIX.length; i++) if (data[i] != ACK_PREFIX[i]) return false;
        return data[9] == (byte)0x96 && data[10] == 0x17;
    }

    /** Neutral sticks are heartbeat only. All four 11-bit axes are centered at 1024. */
    public static byte[] neutralPayload() {
        byte[] time = timePayload();
        byte[] value = new byte[6 + time.length];
        byte[] sticks = {0x00, 0x04, 0x20, 0x00, 0x01, 0x08};
        System.arraycopy(sticks, 0, value, 0, sticks.length);
        System.arraycopy(time, 0, value, 6, time.length);
        return value;
    }

    public static byte[] timePayload() {
        Calendar now = Calendar.getInstance();
        byte[] value = new byte[10];
        put16(value, 0, now.get(Calendar.HOUR_OF_DAY));
        put16(value, 2, now.get(Calendar.MINUTE));
        put16(value, 4, now.get(Calendar.SECOND));
        int milliseconds = now.get(Calendar.MILLISECOND);
        put16(value, 6, milliseconds & 255);
        put16(value, 8, (milliseconds >>> 8) & 255);
        return value;
    }

    public static int crc8(byte[] bytes, int offset, int length) {
        int crc = 0x77;
        for (int i = offset; i < offset + length; i++) {
            crc ^= bytes[i] & 255;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8c : crc >>> 1;
        }
        return crc & 255;
    }

    public static int crc16(byte[] bytes, int offset, int length) {
        int crc = 0x3692;
        for (int i = offset; i < offset + length; i++) {
            crc ^= bytes[i] & 255;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8408 : crc >>> 1;
        }
        return crc & 65535;
    }

    private static void put16(byte[] data, int at, int value) {
        data[at] = (byte)value;
        data[at + 1] = (byte)(value >>> 8);
    }

    public static int u16(byte[] data, int at) { return (data[at] & 255) | ((data[at + 1] & 255) << 8); }

    public static final class Reply {
        public final int command, sequence, type;
        public final byte[] payload;
        Reply(int command, int sequence, int type, byte[] payload) {
            this.command = command; this.sequence = sequence; this.type = type; this.payload = payload;
        }
    }
}
