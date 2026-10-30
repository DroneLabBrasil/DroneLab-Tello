package com.duvitech.tello.nativeflight;

/** Empirical CG sequence from the user's successful original-app capture.
 * ACK alone, old completion, timers and the LED never imply success. */
public final class GravityCalibrationTracker {
    public enum State { IDLE, REQUESTED, RUNNING, SUCCEEDED, UNCONFIRMED, REJECTED }
    private volatile State state = State.IDLE;
    private long requestedAt;
    public State state() { return state; }
    public boolean active() { return state == State.REQUESTED || state == State.RUNNING; }
    public void reset() { state = State.IDLE; requestedAt=0; }
    public void request(long now) { state = State.REQUESTED; requestedAt = now; }
    public void acknowledge(int code) { if (active() && code != 0) state = State.REJECTED; }
    public void observe(int byte22, long now) {
        if (!active()) return;
        int code = (byte22 >>> 3) & 3;
        if (code == 1) state = State.RUNNING;
        else if (code == 2 && state == State.RUNNING) state = State.SUCCEEDED;
        else if (code == 3 || (code == 0 && state == State.RUNNING)) state = State.UNCONFIRMED;
        tick(now);
    }
    public void tick(long now) { if (active() && now - requestedAt > 45000) state = State.UNCONFIRMED; }
    public void interrupt() { if (active()) state = State.UNCONFIRMED; }
}
