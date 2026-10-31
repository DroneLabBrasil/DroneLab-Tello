package com.duvitech.tello.nativeflight;

/** CSC: left stick down/in, right stick down/in (screen Y grows downwards).
 * Circular sticks reach about 70 per axis in a diagonal, so recognize before
 * applying the flight speed scale. Only the native transport sends full CSC. */
public final class MotorStartGesture {
    public enum State { IDLE, STARTING, RUNNING, BLOCKED, TIMEOUT }
    private State state = State.IDLE;
    private long startedAt;

    public static boolean matches(int lx, int ly, int rx, int ry) {
        return lx >= 60 && ly >= 60 && rx <= -60 && ry >= 60
            && lx * lx + ly * ly >= 8100 && rx * rx + ry * ry >= 8100;
    }

    public State update(long now, boolean held, boolean allowed, boolean motorsRunning) {
        if (!held) { state = State.IDLE; return state; }
        // A new attempt always requires releasing the gesture first.
        if (state == State.RUNNING || state == State.BLOCKED || state == State.TIMEOUT) return state;
        if (state == State.STARTING && motorsRunning) return state = State.RUNNING;
        if (!allowed) return state = State.BLOCKED;
        if (state == State.IDLE) { startedAt = now; state = State.STARTING; }
        if (now - startedAt >= 3000) state = State.TIMEOUT;
        return state;
    }
}
