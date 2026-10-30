package com.duvitech.tello.nativeflight;
import com.duvitech.tello.calibration.TelloBinaryProtocol;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public final class GravityReplayTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
 public static void main(String[] args)throws Exception {
  byte[] expected=TelloBinaryProtocol.packet(0x1054,0x68,0x0143,new byte[]{1});
  check(hex(expected).equals("cc600027685410430101d8c7"),"actual captured CG vector");
  GravityCalibrationTracker g=new GravityCalibrationTracker();
  g.request(0);g.acknowledge(0);check(g.state()==GravityCalibrationTracker.State.REQUESTED,"ACK not success");
  g.observe(16,10);check(g.state()!=GravityCalibrationTracker.State.SUCCEEDED,"old completion rejected");
  g.tick(45001);check(g.state()==GravityCalibrationTracker.State.UNCONFIRMED,"timer not success");
  g=new GravityCalibrationTracker();g.request(0);g.observe(8,10);g.observe(0,20);check(g.state()==GravityCalibrationTracker.State.UNCONFIRMED,"missing completion");
  g=new GravityCalibrationTracker();g.request(0);g.acknowledge(1);check(g.state()==GravityCalibrationTracker.State.REJECTED,"negative ACK");
  g=new GravityCalibrationTracker();g.request(0);g.observe(8,10);g.observe(24,20);check(g.state()==GravityCalibrationTracker.State.UNCONFIRMED,"unknown state");
  check(hex(Arrays.copyOf(NativeFlightClient.sticks(new int[4]),6)).equals("000420000108"),"neutral vector matches real capture");
  g=new GravityCalibrationTracker();byte[] data=Files.readAllBytes(Paths.get(args[0]));ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);b.position(24);
  int frames=0,starts=0;long start=0,completed=0;boolean ackSeen=false;List<GravityCalibrationTracker.State> changes=new ArrayList<>();
  while(b.remaining()>=16){long sec=b.getInt()&0xffffffffL,us=b.getInt()&0xffffffffL;int n=b.getInt();b.getInt();byte[] ip=new byte[n];b.get(ip);
   if(n<28||(ip[0]>>>4&15)!=4||(ip[9]&255)!=17)continue;int h=(ip[0]&15)*4,ul=((ip[h+4]&255)<<8)|(ip[h+5]&255);byte[] wire=Arrays.copyOfRange(ip,h+8,h+ul);
   TelloBinaryProtocol.Reply r=TelloBinaryProtocol.parse(wire,wire.length);if(r==null)continue;frames++;
   boolean rx=(ip[12]&255)==192&&(ip[13]&255)==168&&(ip[14]&255)==10&&(ip[15]&255)==1;long t=sec*1000+us/1000;
   if(!rx&&r.command==0x1054){check(r.type==0x68&&Arrays.equals(r.payload,new byte[]{1}),"real CG request");starts++;start=t;g.request(t);}
   if(rx&&r.command==0x1054){g.acknowledge(r.payload[0]&255);check(g.state()!=GravityCalibrationTracker.State.SUCCEEDED,"real ACK not success");ackSeen=true;}
   if(rx&&r.command==0x56&&start>0){g.observe(r.payload[22]&255,t);if(changes.isEmpty()||changes.get(changes.size()-1)!=g.state())changes.add(g.state());if(g.state()==GravityCalibrationTracker.State.SUCCEEDED&&completed==0)completed=t;}
  }
  check(frames==4597,"all real binary packets parsed with CRC");check(starts==1&&ackSeen,"one CG transaction");
  check(changes.contains(GravityCalibrationTracker.State.RUNNING)&&g.state()==GravityCalibrationTracker.State.SUCCEEDED,"replayed capture successful");
  check(completed-start>=14000&&completed-start<=17000,"completion timing from observed data");
  System.out.println("PASS: real CG packet vector; "+frames+" captured CRC-valid packets; completion after "+(completed-start)+"ms; ACK/stale/timer/unknown states never report success; neutral RC vector");
 }
}
