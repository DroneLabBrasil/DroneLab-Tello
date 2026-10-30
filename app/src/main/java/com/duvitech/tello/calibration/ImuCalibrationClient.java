package com.duvitech.tello.calibration;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot native-protocol IMU calibration session, isolated from the flight SDK.
 * Caller must stop ALL other Tello clients and physically confirm the drone is
 * grounded with motors stopped before start(). All callbacks run on this worker.
 * close() stops monitoring only: the protocol has no known calibration-abort command.
 * Protocol behavior researched from TelloPy develop-0.8.0 (Apache-2.0).
 */
public final class ImuCalibrationClient implements AutoCloseable {
    public interface SocketBinder { void bind(DatagramSocket socket) throws IOException; }
    public interface Listener {
        void onMessage(String message);
        void onProgress(int percent, int steps);
        void onFinished();
        void onError(String message);
    }
    private final SocketBinder binder;
    private final Listener listener;
    private final InetSocketAddress target;
    private final int localPort;
    private final long connectTimeoutMs, silenceTimeoutMs, totalTimeoutMs;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean closed;
    private volatile DatagramSocket socket;
    private int sequence = 0x01e4;

    public ImuCalibrationClient(SocketBinder binder, Listener listener) {
        this(binder, listener, new InetSocketAddress("192.168.10.1", 8889), 9000, 8000, 6000, 600000);
    }

    // Package-visible configuration is used by the loopback protocol tests.
    ImuCalibrationClient(SocketBinder binder, Listener listener, InetSocketAddress target, int localPort,
                         long connectTimeoutMs, long silenceTimeoutMs, long totalTimeoutMs) {
        if (binder == null || listener == null) throw new IllegalArgumentException("callbacks");
        this.binder = binder; this.listener = listener; this.target = target; this.localPort = localPort;
        this.connectTimeoutMs = connectTimeoutMs; this.silenceTimeoutMs = silenceTimeoutMs;
        this.totalTimeoutMs = totalTimeoutMs;
    }

    /** Call only after the user's explicit calibration confirmation. Cannot be reused. */
    public void start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Sessão já iniciada");
        if (closed) return;
        Thread worker = new Thread(this::run, "Tello-IMU-calibration");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        boolean calibrationSent = false;
        try (DatagramSocket active = new DatagramSocket(null)) {
            if (closed) return;
            socket = active;
            active.setReuseAddress(false);
            active.bind(new InetSocketAddress(localPort));
            binder.bind(active);
            active.setSoTimeout(50);
            listener.onMessage("Conectando ao Tello para verificar bateria e estado no solo…");
            long begin = now(), lastConnect = 0, lastHeartbeat = 0, lastPoll = 0, lastValid = begin;
            long connectedAt = 0, lastStatusAt = 0;
            boolean connected = false, observedIncompleteStatus = false, warnedPreviousStatus = false;
            int previousPercent = -1, previousSteps = -1;
            byte[] buffer = new byte[4096];
            while (!closed) {
                long time = now();
                if (time - begin > totalTimeoutMs) throw new IOException("Tempo total esgotado");
                if (!connected) {
                    if (time - begin > connectTimeoutMs) throw new IOException("Tello não confirmou a conexão");
                    if (lastConnect == 0 || time - lastConnect >= 1000) {
                        send(TelloBinaryProtocol.CONNECT); lastConnect = time;
                    }
                } else {
                    if (!calibrationSent && time - connectedAt > connectTimeoutMs)
                        throw new IOException("Sem telemetria válida para confirmar o drone no solo");
                    if (time - lastValid > silenceTimeoutMs) throw new IOException("Conexão com o drone perdida");
                    if (calibrationSent && time - lastStatusAt > silenceTimeoutMs)
                        throw new IOException("O drone não confirmou o progresso da calibração");
                    if (time - lastHeartbeat >= 50) {
                        sendCommand(0x0050, 0x60, TelloBinaryProtocol.neutralPayload()); lastHeartbeat = time;
                    }
                    if (calibrationSent && time - lastPoll >= 200) {
                        sendCommand(TelloBinaryProtocol.CALIBRATION_STATUS, 0x48, new byte[0]); lastPoll = time;
                    }
                }

                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                try { active.receive(incoming); } catch (SocketTimeoutException ignored) { continue; }
                if (closed) break;
                if (!incoming.getAddress().equals(target.getAddress()) || incoming.getPort() != target.getPort()) continue;
                int length = incoming.getLength();
                if (!connected && TelloBinaryProtocol.isConnectionAck(buffer, length)) {
                    connected = true; connectedAt = now(); lastValid = connectedAt;
                    byte[] clock = TelloBinaryProtocol.timePayload();
                    byte[] payload = new byte[clock.length + 1];
                    System.arraycopy(clock, 0, payload, 1, clock.length);
                    sendCommand(0x0046, 0x50, payload);
                    continue;
                }
                TelloBinaryProtocol.Reply reply = TelloBinaryProtocol.parse(buffer, length);
                if (!connected || reply == null) continue;
                // A valid binary frame from the expected drone proves link activity.
                lastValid = now();
                if (reply.command == TelloBinaryProtocol.LOG_HEADER && reply.payload.length >= 2) {
                    sendCommand(TelloBinaryProtocol.LOG_HEADER, 0x50,
                            new byte[]{0, reply.payload[0], reply.payload[1]});
                } else if (reply.command == TelloBinaryProtocol.FLIGHT_STATUS && reply.payload.length >= 24) {
                    byte[] p = reply.payload;
                    int flags = p[17] & 255;
                    boolean flying = (flags & 1) != 0, hovering = (flags & 8) != 0;
                    int mode=p[18]&255;
                    int height=(short)TelloBinaryProtocol.u16(p,0);
                    // Both user captures report flags=0 while resting. em_ground bit
                    // is not required: use fresh known landed mode, zero height,
                    // motors flag off and the user's physical confirmation.
                    boolean onGround=!flying && (flags&4)==0 && height==0 && (mode==1 || mode==6);
                    int battery = p[12] & 255;
                    if (!calibrationSent) {
                        if (flying || hovering || !onGround)
                            throw new IOException("Drone não confirmou estar no solo com motores parados");
                        if (battery < 50 || battery > 100 || (flags & 0x60) != 0)
                            throw new IOException("A calibração exige bateria de pelo menos 50%");
                        sendCommand(TelloBinaryProtocol.CALIBRATION_START, 0x48, new byte[0]);
                        calibrationSent = true; lastStatusAt = now();
                        listener.onMessage("Calibração solicitada. Apresente as seis posições, mantendo o drone imóvel até o LED verde piscar rapidamente. Não há comando de cancelamento conhecido.");
                    } else if (flying || hovering) {
                        throw new IOException("O drone informou estado de voo durante a calibração");
                    }
                } else if (reply.command == TelloBinaryProtocol.CALIBRATION_STATUS && calibrationSent) {
                    byte[] p = reply.payload;
                    if (p.length != 5 || (p[1] & 0xc0) != 0 || (p[3] & 255) > 100) continue;
                    int percent = p[3] & 255, steps = Integer.bitCount(p[1] & 0x3f);
                    if (percent < 100) observedIncompleteStatus = true;
                    if (percent == 100 && !observedIncompleteStatus) {
                        if (!warnedPreviousStatus) {
                            warnedPreviousStatus = true;
                            listener.onMessage("O drone respondeu com um estado já concluído. Aguardando evidência de uma nova calibração; não há conclusão confirmada.");
                        }
                        continue;
                    }
                    lastStatusAt = now();
                    if (percent != previousPercent || steps != previousSteps) {
                        previousPercent = percent; previousSteps = steps;
                        listener.onProgress(percent, steps);
                    }
                    if (percent == 100 && steps == 6) {
                        active.close();
                        if (!closed) listener.onFinished();
                        return;
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            if (!closed) listener.onError(error.getMessage() + (calibrationSent
                    ? ". Monitoramento encerrado; isso não cancela a calibração no drone. Reinicie o drone antes de voar."
                    : ". Nenhuma calibração confirmada. Verifique a conexão e reinicie o drone antes de tentar novamente."));
        } finally { socket = null; }
    }

    private void sendCommand(int command, int type, byte[] payload) throws IOException {
        send(TelloBinaryProtocol.packet(command, type, sequence++ & 0xffff, payload));
    }
    private void send(byte[] value) throws IOException {
        DatagramSocket current = socket;
        if (current == null || closed) throw new IOException("Sessão encerrada");
        current.send(new DatagramPacket(value, value.length, target));
    }
    private static long now() { return System.nanoTime() / 1000000L; }
    @Override public void close() {
        closed = true;
        DatagramSocket current = socket;
        if (current != null) current.close();
    }
}
