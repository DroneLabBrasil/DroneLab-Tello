package com.duvitech.network.udp;
public interface FlightTransport {
    boolean sendBytes(byte[] bytes);
    void suspendControls();
    void close();
}
