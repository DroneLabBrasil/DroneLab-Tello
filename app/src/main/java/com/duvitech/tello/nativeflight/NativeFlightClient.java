package com.duvitech.tello.nativeflight;

import com.duvitech.network.udp.FlightTransport;
import com.duvitech.tello.calibration.TelloBinaryProtocol;
import com.duvitech.tello.calibration.ImuCalibrationClient;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

/** A single native connection from before takeoff through landing. Never changes
 * to/from the text SDK in flight. Reconnection discards all actions and starts with neutral controls; no automatic flight actions. */
public final class NativeFlightClient implements FlightTransport {
    public interface Listener {
        void onTelemetry(int battery, int heightDm, boolean flying, boolean fresh);
        void onMessage(String message);
        void onCalibration(GravityCalibrationTracker.State state);
        default void onSessionReset() {}
    }
    private final ImuCalibrationClient.SocketBinder binder;
    private final Listener listener;
    private final InetSocketAddress target;
    private final int localPort;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(16);
    private final GravityCalibrationTracker calibration = new GravityCalibrationTracker();
    private volatile DatagramSocket socket;
    private volatile boolean closed, connected, video, workerRunning=true;
    private volatile long lastFlipAt;
    private volatile int[] axes = new int[4];
    private volatile long axesAt;
    private volatile long lastFlightAt;
    private volatile int battery, height, flags, mode, sensorFlags, rawCg;
    private long hoverSince;
    private volatile boolean cgUsed;
    private boolean initialGroundSeen;
    private int sequence = 0x01e4;
    private GravityCalibrationTracker.State announced = GravityCalibrationTracker.State.IDLE;

    public NativeFlightClient(ImuCalibrationClient.SocketBinder binder, Listener listener) {
        this(binder, listener, new InetSocketAddress("192.168.10.1",8889),9000);
    }
    // Endpoint override is package-private for local UDP tests only.
    NativeFlightClient(ImuCalibrationClient.SocketBinder binder, Listener listener, InetSocketAddress target, int localPort) {
        this.binder=binder; this.listener=listener; this.target=target; this.localPort=localPort;
        Thread worker=new Thread(this::run,"DroneLab-native-flight"); worker.setDaemon(true); worker.start();
    }
    private static long now() { return System.nanoTime()/1000000L; }
    public boolean isRunning() { return workerRunning && !closed; }
    public boolean fresh() { return connected && lastFlightAt!=0 && now()-lastFlightAt<1000; }
    public boolean onGround() { return fresh() && !flying() && (flags & 4)==0 && height==0 && (mode==1 || mode==6); }
    public boolean flying() { return (flags&1)!=0; }
    public boolean calibrationUsed() { return cgUsed; }
    public boolean calibrationActive() { return calibration.active(); }
    public boolean sendBytes(byte[] bytes) {
        String command=new String(bytes,StandardCharsets.UTF_8);
        if (closed) return false;
        if(command.startsWith("rc ")) {
            String[] p=command.split(" ");
            if(p.length!=5)return false;
            try {
                int[] value=new int[4];for(int i=0;i<4;i++)value[i]=Math.max(-100,Math.min(100,Integer.parseInt(p[i+1])));
                axes=value;axesAt=now();return true;
            } catch(NumberFormatException e){return false;}
        }
        if(command.equals("command") || command.startsWith("speed "))return true;
        if(command.equals("streamon")){video=true;return true;}
        if(command.equals("streamoff")){video=false;return true;}
        if(command.equals("land")||command.equals("emergency")){queue.clear();axes=new int[4];}
        return queue.offer(command);
    }
    public boolean requestGravityCalibration() { return !closed && queue.offer("calibrate_cg"); }
    public void suspendControls() {
        axes=new int[4];axesAt=0;
        queue.removeIf(s -> !s.equals("land") && !s.equals("emergency"));
    }
    public void close() { closed=true; axes=new int[4]; DatagramSocket s=socket; if(s!=null)s.close(); }

    private void run() {
        byte[] hello={'c','o','n','n','_','r','e','q',':',0x67,0x2b}; // native video UDP11111, little endian
        byte[] buffer=new byte[8192];
        long start=now(),lastHello=0,lastStick=0,lastVideo=0;boolean lostNotified=false;
        try(DatagramSocket active=new DatagramSocket(null)) {
            socket=active;active.bind(new InetSocketAddress(localPort));binder.bind(active);active.setSoTimeout(20);
            while(!closed) {
                long t=now();
                if(!connected) {
                    if(t-start>10000){listener.onMessage("Aguardando reconexão ao Tello...");start=t;}
                    if(lastHello==0||t-lastHello>=1000){send(hello);lastHello=t;}
                } else {
                    boolean valid=fresh();
                    if(lastFlightAt!=0 && !valid && !lostNotified){lostNotified=true;axes=new int[4];hoverSince=0;calibration.interrupt();listener.onTelemetry(battery,height,flying(),false);listener.onMessage("Telemetria desatualizada; controles neutros. Pousar continua disponível.");}
                    if(valid)lostNotified=false;
                    if(lastFlightAt!=0 && t-lastFlightAt>3000) {
                        // Power cycle or radio loss: negotiate again on this Wi-Fi socket.
                        // Never replay actions from the old drone/session.
                        listener.onSessionReset();
                        calibration.interrupt();publishCalibration();calibration.reset();announced=GravityCalibrationTracker.State.IDLE;
                        queue.clear();axes=new int[4];axesAt=0;initialGroundSeen=false;cgUsed=false;hoverSince=0;lastFlipAt=0;
                        lastFlightAt=0;connected=false;start=t;lastHello=0;lastVideo=0;
                        continue;
                    }
                    if(t-lastStick>=50){int[] a=valid && t-axesAt<250 ? axes : new int[4];sendPacket(0x50,0x60,sticks(a));lastStick=t;}
                    if(video && t-lastVideo>=1000){sendPacket(0x25,0x60,new byte[0]);lastVideo=t;}
                    String command=queue.poll();if(command!=null)handleCommand(command,t);
                    calibration.tick(t);publishCalibration();
                }
                DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
                try{active.receive(packet);}catch(SocketTimeoutException timeout){continue;}
                if(closed)break;
                if(!packet.getAddress().equals(target.getAddress())||packet.getPort()!=target.getPort())continue;
                int size=packet.getLength();
                if(!connected && size==hello.length && sameAck(buffer,hello)) {
                    connected=true;
                    java.util.Calendar c=java.util.Calendar.getInstance();
                    int[] values={c.get(java.util.Calendar.YEAR),c.get(java.util.Calendar.MONTH)+1,c.get(java.util.Calendar.DAY_OF_MONTH),c.get(java.util.Calendar.HOUR_OF_DAY),c.get(java.util.Calendar.MINUTE),c.get(java.util.Calendar.SECOND),c.get(java.util.Calendar.MILLISECOND)};
                    byte[] payload=new byte[15];for(int i=0;i<values.length;i++){payload[1+i*2]=(byte)values[i];payload[2+i*2]=(byte)(values[i]>>>8);}
                    sendPacket(0x46,0x50,payload);
                    listener.onMessage("Conexão nativa estabelecida; aguardando telemetria");continue;
                }
                TelloBinaryProtocol.Reply reply=TelloBinaryProtocol.parse(buffer,size);
                if(!connected||reply==null)continue;
                byte[] p=reply.payload;
                if(reply.command==0x56 && p.length>=24 && (p[12]&255)<=100) {
                    battery=p[12]&255;height=(short)TelloBinaryProtocol.u16(p,0);flags=p[17]&255;mode=p[18]&255;sensorFlags=p[10]&255;rawCg=p[22]&255;lastFlightAt=now();
                    if(!flying() && height==0)initialGroundSeen=true;
                    if(flying() && mode==6 && fresh()){if(hoverSince==0)hoverSince=lastFlightAt;}else hoverSince=0;
                    calibration.observe(rawCg,lastFlightAt);publishCalibration();
                    listener.onTelemetry(battery,height,flying(),true);
                } else if(reply.command==0x1054 && p.length==1) {
                    calibration.acknowledge(p[0]&255);publishCalibration();
                } else if(reply.command==0x1050 && p.length>=2) {
                    sendPacket(0x1050,0x50,new byte[]{0,p[0],p[1]});
                } else if(reply.command==0x1052 && p.length>=6) {
                    byte[] ack=new byte[7];System.arraycopy(p,0,ack,0,6);ack[0]=0;sendPacket(0x1052,0x50,ack);
                } else if(reply.command==0x5c && p.length>=1) {
                    listener.onMessage((p[0]&255)==0 ? "Flip aceito pelo drone" : "Flip recusado pelo drone: " + (p[0]&255));
                } else if((reply.command==0x54||reply.command==0x55) && p.length>=1) {
                    listener.onMessage((reply.command==0x54?"Decolagem":"Pouso")+((p[0]&255)==0?" aceito pelo drone":" recusado pelo drone"));
                }
            }
        } catch(IOException|RuntimeException e) {
            if(!closed){calibration.interrupt();publishCalibration();listener.onTelemetry(battery,height,flying(),false);listener.onMessage("Conexão nativa: "+e.getMessage());}
        } finally {socket=null;workerRunning=false;}
    }
    private boolean sameAck(byte[] value,byte[] hello) {
        byte[] prefix="conn_ack:".getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<9;i++)if(value[i]!=prefix[i])return false;
        return value[9]==hello[9]&&value[10]==hello[10];
    }
    private void handleCommand(String command,long time)throws IOException {
        if(command.equals("land")||command.equals("emergency")) {
            calibration.interrupt();publishCalibration();axes=new int[4];
            if(command.equals("land"))sendPacket(0x55,0x68,new byte[]{0});
            else send("emergency".getBytes(StandardCharsets.US_ASCII));
        } else if(command.equals("takeoff")) {
            if(!initialGroundSeen||!onGround()||battery<=20||calibration.active()){listener.onMessage("Decolagem bloqueada: verifique telemetria, solo e bateria");return;}
            sendPacket(0x54,0x68,new byte[0]);
        } else if(command.startsWith("flip ")) {
            String direction=command.substring(5);
            int d=direction.equals("f")?0:direction.equals("l")?1:direction.equals("b")?2:direction.equals("r")?3:-1;
            if(d<0)return;
            if(!initialGroundSeen || !fresh() || !flying() || mode!=6 || battery<50 || sensorFlags!=0 || calibration.active()) {
                listener.onMessage("Flip bloqueado: exige voo pairado, bateria ≥50%, telemetria válida e nenhuma calibração ativa");return;
            }
            if(time-lastFlipAt<2000){listener.onMessage("Aguarde antes de repetir o flip");return;}
            lastFlipAt=time;
            sendPacket(0x5c,0x70,new byte[]{(byte)d});
        } else if(command.equals("calibrate_cg")) {
            int[] a=axes;boolean neutral=true;for(int v:a)if(v!=0)neutral=false;
            if(cgUsed||!initialGroundSeen||!fresh()||!flying()||mode!=6||hoverSince==0||time-hoverSince<1500||battery<50||sensorFlags!=0||((rawCg>>>3)&3)!=0||!neutral){listener.onMessage("CG bloqueado: exige voo pairado estável, controles neutros, bateria ≥50% e telemetria válida. Uma tentativa por sessão.");return;}
            cgUsed=true;calibration.request(time);publishCalibration();sendPacket(0x1054,0x68,new byte[]{1});
        }
    }
    private void publishCalibration() {
        GravityCalibrationTracker.State s=calibration.state();if(s!=announced){announced=s;listener.onCalibration(s);}
    }
    static byte[] sticks(int[] axes) {
        long packed=0;for(int i=0;i<4;i++){int a=(int)(1024+6.6*axes[i]);packed|=((long)(a&0x7ff))<<(i*11);}
        java.util.Calendar c=java.util.Calendar.getInstance();byte[] result=new byte[11];for(int i=0;i<6;i++)result[i]=(byte)(packed>>>(i*8));
        result[6]=(byte)c.get(java.util.Calendar.HOUR_OF_DAY);result[7]=(byte)c.get(java.util.Calendar.MINUTE);result[8]=(byte)c.get(java.util.Calendar.SECOND);int ms=c.get(java.util.Calendar.MILLISECOND);result[9]=(byte)ms;result[10]=(byte)(ms>>>8);return result;
    }
    private void sendPacket(int command,int type,byte[] payload)throws IOException {send(TelloBinaryProtocol.packet(command,type,sequence++&65535,payload));}
    private void send(byte[] data)throws IOException {DatagramSocket s=socket;if(s==null||closed)throw new IOException("Sessão encerrada");s.send(new DatagramPacket(data,data.length,target));}
}
