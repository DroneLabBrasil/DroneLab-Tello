package com.duvitech.tello.nativeflight;

import com.duvitech.tello.calibration.TelloBinaryProtocol;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class MotorStartTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final byte[] NEUTRAL = {0,4,32,0,1,8};
    static final byte[] CSC = {0x6c,0x61,0x0b,0x5b,0x28,0x0d};
    static class Results implements NativeFlightClient.Listener {
        volatile boolean fresh, confirmed, blocked;
        public void onTelemetry(int b,int h,boolean flying,boolean fresh) { this.fresh=fresh; }
        public void onMessage(String s) {
            if (s.startsWith("Motores ligados, confirmado")) confirmed=true;
            if (s.startsWith("Ligar motores bloqueado")) blocked=true;
        }
        public void onCalibration(GravityCalibrationTracker.State state) {}
    }
    public static void main(String[] args) throws Exception {
        check(MotorStartGesture.matches(70,70,-70,70),"circular diagonal recognized");
        check(!MotorStartGesture.matches(60,60,-60,60),"partial diagonal refused");
        check(!MotorStartGesture.matches(70,70,70,70),"outward/right direction refused");
        check(!MotorStartGesture.matches(70,70,0,0),"single stick refused");
        MotorStartGesture g=new MotorStartGesture();
        check(g.update(0,true,true,false)==MotorStartGesture.State.STARTING,"start");
        check(g.update(2999,true,true,false)==MotorStartGesture.State.STARTING,"hold while valid");
        check(g.update(3000,true,true,false)==MotorStartGesture.State.TIMEOUT,"bounded duration");
        check(g.update(3100,true,true,true)==MotorStartGesture.State.TIMEOUT,"no false late confirmation");
        g.update(3200,false,true,false);
        check(g.update(3300,true,false,false)==MotorStartGesture.State.BLOCKED,"unsafe state refused");
        check(g.update(3400,true,true,false)==MotorStartGesture.State.BLOCKED,"no restart without release");
        g.update(3500,false,true,false);
        check(g.update(3600,true,true,false)==MotorStartGesture.State.STARTING,"intentional new attempt");
        check(g.update(3700,true,false,true)==MotorStartGesture.State.RUNNING,"telemetry confirmation");
        check(Arrays.equals(Arrays.copyOf(NativeFlightClient.sticks(new int[]{-100,-100,-100,100}),6),CSC),"known CSC packet vector");
        loopback();
        staleInput();
        System.out.println("PASS: diagonal, exact CSC, 3s timeout, release latch, motor telemetry confirmation, airborne block, stale input, no takeoff");
    }
    static void staleInput() throws Exception {
        try (DatagramSocket server=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15); Results r=new Results();
            NativeFlightClient c=new NativeFlightClient(s->{},r,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),0);
            InetSocketAddress remote=null; long start=System.nanoTime()/1000000L,lastFlight=0,lastInput=0,first=0;
            int count=0; boolean resumed=false;
            try {
                while(System.nanoTime()/1000000L-start<3000) {
                    long t=System.nanoTime()/1000000L;
                    if(remote!=null && t-lastFlight>=30) {
                        byte[] p=new byte[24];p[12]=80;p[18]=6;
                        NativeFlightLoopbackTest.send(server,remote,0x56,0,p);lastFlight=t;
                    }
                    if(r.fresh && first==0){first=t;c.setMotorStartGesture(true);}
                    if(first!=0 && t-first>550 && t-lastInput>40){c.setMotorStartGesture(true);lastInput=t;resumed=true;}
                    if(first!=0 && t-first>900)break;
                    byte[] b=new byte[4096];DatagramPacket packet=new DatagramPacket(b,b.length);
                    try{server.receive(packet);}catch(SocketTimeoutException e){continue;}
                    remote=new InetSocketAddress(packet.getAddress(),packet.getPort());
                    if(b[0]=='c'){byte[] a=Arrays.copyOf(b,packet.getLength());a[5]='a';a[6]='c';a[7]='k';server.send(new DatagramPacket(a,a.length,remote));continue;}
                    TelloBinaryProtocol.Reply reply=TelloBinaryProtocol.parse(b,packet.getLength());check(reply!=null,"valid watchdog frame");
                    check(reply.command!=0x54,"watchdog never takes off");
                    if(reply.command==0x50 && Arrays.equals(Arrays.copyOf(reply.payload,6),CSC)){
                        check(first!=0 && t-first<350,"stale gesture cancelled and cannot resume without release");count++;
                    }
                }
                check(count>0 && resumed && r.blocked,"watchdog scenario exercised");
            } finally {c.close();}
        }
    }
    static void loopback() throws Exception {
        try (DatagramSocket server=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15); Results r=new Results();
            NativeFlightClient c=new NativeFlightClient(s->{},r,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),0);
            InetSocketAddress remote=null; long start=System.nanoTime()/1000000L,lastFlight=0,lastInput=0,confirmedAt=0;
            int cscCount=0,afterConfirmation=0; boolean airborne=false,held=true;
            try {
                while(System.nanoTime()/1000000L-start<4500) {
                    long t=System.nanoTime()/1000000L;
                    if(remote!=null && t-lastFlight>=30) {
                        byte[] p=new byte[24];p[12]=80;p[18]=6;
                        if(cscCount>=3)p[17]=4;
                        if(airborne){p[17]=5;p[0]=10;}
                        NativeFlightLoopbackTest.send(server,remote,0x56,0,p);lastFlight=t;
                    }
                    if(r.fresh && t-lastInput>=40) {
                        c.sendBytes("rc 0 0 0 0".getBytes(StandardCharsets.US_ASCII));
                        c.setMotorStartGesture(held);lastInput=t;
                    }
                    if(r.confirmed && confirmedAt==0)confirmedAt=t;
                    if(confirmedAt!=0 && t-confirmedAt>250 && !airborne) {
                        c.setMotorStartGesture(false);held=false;airborne=true;
                    }
                    if(airborne && confirmedAt!=0 && t-confirmedAt>400) held=true;
                    if(airborne && r.blocked && afterConfirmation>8) break;
                    byte[] b=new byte[4096];DatagramPacket packet=new DatagramPacket(b,b.length);
                    try{server.receive(packet);}catch(SocketTimeoutException e){continue;}
                    remote=new InetSocketAddress(packet.getAddress(),packet.getPort());
                    if(b[0]=='c') {byte[] a=Arrays.copyOf(b,packet.getLength());a[5]='a';a[6]='c';a[7]='k';server.send(new DatagramPacket(a,a.length,remote));continue;}
                    TelloBinaryProtocol.Reply reply=TelloBinaryProtocol.parse(b,packet.getLength());check(reply!=null,"valid CRC");
                    check(reply.command!=0x54,"motor gesture must never issue takeoff");
                    if(reply.command==0x50) {
                        byte[] axes=Arrays.copyOf(reply.payload,6);
                        if(Arrays.equals(axes,CSC)){check(!airborne&&!r.confirmed,"CSC only before ground confirmation");cscCount++;}
                        else check(Arrays.equals(axes,NEUTRAL),"neutral outside CSC");
                        if(r.confirmed)afterConfirmation++;
                    }
                }
                check(cscCount>=3 && r.confirmed && r.blocked,"motors confirmed and airborne gesture refused");
                // An unrefreshed held gesture must not leave a persistent CSC command.
                c.setMotorStartGesture(false);c.suspendControls();
            } finally {c.close();}
        }
    }
}
