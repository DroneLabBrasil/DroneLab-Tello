package com.duvitech.tello.nativeflight;
import com.duvitech.tello.calibration.TelloBinaryProtocol;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;
public class NativeFlightLoopbackTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static class Results implements NativeFlightClient.Listener {
  volatile boolean fresh,flying,success,unconfirmed,blocked;
  public void onTelemetry(int b,int h,boolean flying,boolean fresh){this.fresh=fresh;this.flying=flying;}
  public void onMessage(String s){if(s.startsWith("CG bloqueado"))blocked=true;}
  public void onCalibration(GravityCalibrationTracker.State s){if(s==GravityCalibrationTracker.State.SUCCEEDED)success=true;if(s==GravityCalibrationTracker.State.UNCONFIRMED)unconfirmed=true;}
 }
 public static void main(String[] args)throws Exception {run(false);run(true);System.out.println("PASS: native loopback handshake, ground refusal, no automatic takeoff, fresh hovering gate, one-shot CG, ACK insufficient, landing remains available during CG, same native socket throughout");}
 static void run(boolean interrupt)throws Exception {
  try(DatagramSocket server=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
   server.setSoTimeout(20);Results result=new Results();NativeFlightClient c=new NativeFlightClient(s->{},result,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),0);
   long begin=System.nanoTime()/1000000L,lastFlight=0,flyAt=0,cgAt=0;InetSocketAddress remote=null;int cgCount=0;boolean takeoffRequested=false,takeoffSeen=false,groundRequested=false,cgRequested=false,landRequested=false,landSeen=false;int stablePort=-1;
   try {
    while(System.nanoTime()/1000000L-begin<8000) {
     long t=System.nanoTime()/1000000L;
     if(remote!=null && t-lastFlight>=40) {
      byte[] p=new byte[24];p[12]=80;p[17]=(byte)(takeoffSeen?5:0);p[18]=6;p[0]=(byte)(takeoffSeen?13:0);
      if(cgAt!=0){p[22]=(byte)(t-cgAt<250?8:16);}send(server,remote,0x56,0,p);lastFlight=t;
     }
     if(result.fresh && !groundRequested){c.requestGravityCalibration();groundRequested=true;}
     if(result.blocked&&!takeoffRequested){c.sendBytes("takeoff".getBytes(StandardCharsets.US_ASCII));takeoffRequested=true;}
     if(takeoffSeen && t-flyAt>1700 && !cgRequested){c.requestGravityCalibration();cgRequested=true;}
     if(interrupt && cgAt!=0 && !landRequested && t-cgAt>100){c.sendBytes("land".getBytes(StandardCharsets.US_ASCII));landRequested=true;}
     if((!interrupt&&result.success)||(interrupt&&landSeen&&result.unconfirmed))break;
     byte[] b=new byte[4096];DatagramPacket packet=new DatagramPacket(b,b.length);try{server.receive(packet);}catch(SocketTimeoutException e){continue;}
     if(stablePort<0)stablePort=packet.getPort();check(stablePort==packet.getPort(),"same socket");remote=new InetSocketAddress(packet.getAddress(),packet.getPort());
     if(b[0]=='c'){byte[] ack=Arrays.copyOf(b,packet.getLength());ack[5]='a';ack[6]='c';ack[7]='k';server.send(new DatagramPacket(ack,ack.length,remote));continue;}
     TelloBinaryProtocol.Reply r=TelloBinaryProtocol.parse(b,packet.getLength());check(r!=null,"valid native frame");
     if(r.command==0x54){check(takeoffRequested,"never auto takeoff");takeoffSeen=true;flyAt=t;send(server,remote,0x54,r.sequence,new byte[]{0});}
     if(r.command==0x1054){check(cgRequested&&result.blocked,"ground refused first");check(++cgCount==1,"one-shot request");check(Arrays.equals(r.payload,new byte[]{1}),"CG type");cgAt=t;send(server,remote,0x1054,r.sequence,new byte[]{0});check(!result.success,"ACK not success");}
     if(r.command==0x55){check(landRequested,"no automatic landing");landSeen=true;}
    }
    check(cgCount==1&&takeoffSeen,"CG and takeoff observed");
    check(interrupt?(landSeen&&result.unconfirmed&&!result.success):result.success,"expected result");
   } finally{c.close();}
  }
 }
 static void send(DatagramSocket s,InetSocketAddress address,int cmd,int seq,byte[] p)throws Exception{byte[] b=TelloBinaryProtocol.packet(cmd,0x88,seq,p);s.send(new DatagramPacket(b,b.length,address));}
}
