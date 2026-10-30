package com.duvitech.tello.nativeflight;
import com.duvitech.tello.calibration.TelloBinaryProtocol;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class ReconnectFlipTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Results implements NativeFlightClient.Listener {
  volatile boolean fresh,airborne,success,flipBlocked;volatile int resets,lastBattery;
  NativeFlightClient client;
  public void onTelemetry(int b,int h,boolean f,boolean fresh){this.fresh=fresh;airborne=f;lastBattery=b;}
  public void onMessage(String m){if(m.startsWith("Flip bloqueado"))flipBlocked=true;}
  public void onCalibration(GravityCalibrationTracker.State s){if(s==GravityCalibrationTracker.State.SUCCEEDED)success=true;}
  public void onSessionReset(){resets++;if(client!=null){client.sendBytes("takeoff".getBytes(StandardCharsets.US_ASCII));client.sendBytes("flip f".getBytes(StandardCharsets.US_ASCII));client.sendBytes("rc 50 0 0 0".getBytes(StandardCharsets.US_ASCII));}}
 }
 public static void main(String[] args)throws Exception {
  try(DatagramSocket server=new DatagramSocket(0,InetAddress.getLoopbackAddress())) {
   server.setSoTimeout(20);Results r=new Results();NativeFlightClient c=new NativeFlightClient(s->{},r,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),0);r.client=c;
   long begin=now(),lastStatus=0,lastFlipRequest=0,offlineAt=0,reconnectedAt=0;
   InetSocketAddress remote=null;boolean flying=false,takeoffRequested=false,lowFlipRequested=false;int battery=42,flipRequests=0,flipPackets=0,handshakes=0;boolean secondPhase=false;
   try {
    while(now()-begin<24000) {
     long t=now();boolean offline=offlineAt!=0 && !secondPhase;
     if(remote!=null && !offline && t-lastStatus>=40){byte[] p=new byte[24];p[12]=(byte)battery;p[17]=(byte)(flying?5:0);p[18]=6;p[0]=(byte)(flying?13:0);send(server,remote,0x56,p);lastStatus=t;}
     if(r.fresh&&!takeoffRequested&&!offline){c.sendBytes("takeoff".getBytes(StandardCharsets.US_ASCII));takeoffRequested=true;}
     if(flying&&r.airborne&&!lowFlipRequested){c.sendBytes("flip f".getBytes(StandardCharsets.US_ASCII));lowFlipRequested=true;}
     if(r.flipBlocked&&!secondPhase){battery=80;}
     if(r.lastBattery==80&&flipRequests<4&&t-lastFlipRequest>2200&&!offline&&!secondPhase&&t-lastStatus>0){
      String[] directions={"f","l","b","r"};c.sendBytes(("flip "+directions[flipRequests]).getBytes(StandardCharsets.US_ASCII));flipRequests++;lastFlipRequest=t;
     }
     if(flipPackets==4 && offlineAt==0){offlineAt=t;r.fresh=false;}
     if(secondPhase && t-reconnectedAt>700)break;
     byte[] buf=new byte[8192];DatagramPacket packet=new DatagramPacket(buf,buf.length);try{server.receive(packet);}catch(SocketTimeoutException e){continue;}
     remote=new InetSocketAddress(packet.getAddress(),packet.getPort());
     if(buf[0]=='c') {
      if(offlineAt!=0 && !secondPhase) {
       check(r.resets>=1,"native session reset before handshake");
       if(t-offlineAt<3500)continue;
       secondPhase=true;flying=false;reconnectedAt=t;
      }
      byte[] ack=Arrays.copyOf(buf,packet.getLength());ack[5]='a';ack[6]='c';ack[7]='k';server.send(new DatagramPacket(ack,ack.length,remote));handshakes++;continue;
     }
     TelloBinaryProtocol.Reply p=TelloBinaryProtocol.parse(buf,packet.getLength());check(p!=null,"CRC valid native frame");
     if(p.command==0x54){check(!secondPhase,"no replayed takeoff on reconnection");check(takeoffRequested,"no automatic takeoff");flying=true;send(server,remote,0x54,new byte[]{0});}
     if(p.command==0x5c){check(!secondPhase&&!offline,"no replayed flip");check(r.flipBlocked,"low battery refused first");check(p.type==0x70,"flip packet type");check(p.payload.length==1&&(p.payload[0]&255)==flipPackets,"direction mapping f l b r");flipPackets++;send(server,remote,0x5c,new byte[]{0});}
     if(secondPhase){check(p.command!=0x1054,"no replayed calibration");if(p.command==0x50)check(hex(Arrays.copyOf(p.payload,6)).equals("000420000108"),"reconnection neutral axes");}
    }
    check(flipPackets==4,"all four flip directions");check(handshakes>=2&&secondPhase&&r.fresh,"reconnected with fresh telemetry");
    System.out.println("PASS: low-battery flip refused; native flip f/l/b/r mapping and type; 3s loss triggers new handshake; telemetry returns; queued flight actions and axes discarded on reconnection");
   }finally{c.close();}
  }
 }
 static long now(){return System.nanoTime()/1000000L;}
 static void send(DatagramSocket s,InetSocketAddress a,int cmd,byte[] p)throws Exception{byte[] b=TelloBinaryProtocol.packet(cmd,0x88,0,p);s.send(new DatagramPacket(b,b.length,a));}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
}
