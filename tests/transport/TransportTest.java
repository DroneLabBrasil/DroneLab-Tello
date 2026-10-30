import com.duvitech.network.udp.UDPClient;
import com.duvitech.network.udp.UDPStatusServer;
import android.net.Network;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
public class TransportTest {
 static void redirect(Object obj,String field) throws Exception {Field f=obj.getClass().getDeclaredField(field);f.setAccessible(true);f.set(obj,InetAddress.getByName("127.0.0.1"));}
 static String receive(DatagramSocket sock) throws Exception { byte[] b=new byte[256];DatagramPacket p=new DatagramPacket(b,b.length);sock.receive(p);return new String(b,0,p.getLength(),StandardCharsets.UTF_8); }
 static void send(DatagramSocket sock,String text) throws Exception {byte[] b=text.getBytes(StandardCharsets.UTF_8);sock.send(new DatagramPacket(b,b.length,InetAddress.getByName("127.0.0.1"),8890));}
 public static void main(String[] args) throws Exception {
  try(DatagramSocket receiver=new DatagramSocket(8889,InetAddress.getByName("127.0.0.1"))) {
   receiver.setSoTimeout(1500);
   UDPClient client=new UDPClient(new Network(),message->{throw new AssertionError(message);});
   redirect(client,"address");
   client.sendCommand("rc 30 0 0 0");
   if(!receive(receiver).equals("rc 30 0 0 0"))throw new AssertionError("motion packet missing");
   client.sendCommand("rc 0 0 0 0");
   if(!receive(receiver).equals("rc 0 0 0 0"))throw new AssertionError("release neutral missing");
   long start=System.nanoTime();
   for(int i=0;i<10000;i++)client.sendCommand("rc 40 0 0 0");
   long elapsed=(System.nanoTime()-start)/1000000;
   if(elapsed>1000)throw new AssertionError("nonblocking mailbox took "+elapsed+"ms");
   client.suspendControls();
   boolean neutral=false;
   for(int i=0;i<200 && !neutral;i++)neutral=receive(receiver).equals("rc 0 0 0 0");
   if(!neutral)throw new AssertionError("suspend neutral missing");
   client.close();
   if(!receive(receiver).equals("rc 0 0 0 0"))throw new AssertionError("close neutral missing");
   System.out.println("PASS actual UDP sender: motion, release neutral, 10000 updates without blocking, suspend and close neutral");
  }
  AtomicInteger valid=new AtomicInteger();AtomicInteger timeout=new AtomicInteger();
  UDPStatusServer server=new UDPStatusServer(new Network(),(display,battery,connected)->{if(connected)valid.incrementAndGet();else timeout.incrementAndGet();});
  redirect(server,"droneAddress");server.start();
  try(DatagramSocket sender=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"))) {
   send(sender,"garbage");send(sender,"bat:101;h:30;");send(sender,"bat:xx;h:30;");send(sender,"bat:90;");
   Thread.sleep(100);
   if(valid.get()!=0)throw new AssertionError("invalid telemetry accepted");
   send(sender,"bat:90;h:30;pitch:0;roll:0;");Thread.sleep(100);
   if(valid.get()!=1)throw new AssertionError("valid telemetry rejected");
   long stop=System.currentTimeMillis()+3500;
   while(System.currentTimeMillis()<stop){send(sender,"junk");Thread.sleep(30);}
   if(timeout.get()!=1)throw new AssertionError("invalid traffic hid telemetry timeout");
  } finally {server.stopServer();server.join(1000);}
  System.out.println("PASS actual UDP receiver: malformed telemetry rejected, valid telemetry accepted, invalid traffic cannot hide timeout");
 }
}
