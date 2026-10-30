package com.duvitech.tello;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.LinearLayout;
import com.duvitech.tello.nativeflight.NativeFlightClient;
import com.duvitech.tello.nativeflight.GravityCalibrationTracker;
import android.hardware.input.InputManager;
import android.net.Network;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.duvitech.network.udp.UDPClient;
import com.duvitech.network.udp.UDPStatusServer;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements SurfaceHolder.Callback, InputManager.InputDeviceListener {

    private static final String TAG = "TelloDemo";

    private static final int[] SPEEDS   = {30,    60,    100};
    private static final float[] SCALES = {0.25f, 0.50f, 1.0f};
    private static final String[] SPEED_LABELS = {"VEL: BAIXA", "VEL: MÉDIA", "VEL: ALTA"};
    private int speedIndex = 1;

    private Button btnVideo, btnSpeed, btnRecord, btnPhoto;
    private TextView tvStatus, tvConnection;
    private SurfaceView surfaceView;
    private boolean videoRunning = false;

    private TelloVideoDecoder videoDecoder;
    private UDPClient client;
    private NativeFlightClient nativeClient;
    private boolean nativeMode, nativeFlying, switchingToImu;
    private int nativeHeight;
    private Button settingsButton, imuSetting, cgSetting;
    private TextView calibrationStatus;
    private AlertDialog settingsDialog;
    private UDPStatusServer statusServer;
    private SurfaceHolder surfaceHolder;

    private volatile int touchLX, touchLY, touchRX, touchRY;
    private volatile int padLX, padLY, padRX, padRY;

    private boolean lowBatteryWarned = false;
    private boolean isArmed = false;
    private boolean activityActive;
    private boolean telemetryConnected;
    private boolean destroyed;
    private Network wifiNetwork;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private int nativeSession;
    private boolean videoWanted=true;
    private final Runnable reconnectRunnable = new Runnable() {
        public void run() {
            if (destroyed || switchingToImu || !activityActive || !nativeMode) return;
            ensureNativeConnection();
            rcHandler.postDelayed(this, 2000);
        }
    };
    private InputManager inputManager;
    private JoystickView leftStick, rightStick;


    private final Handler rcHandler = new Handler(Looper.getMainLooper());
    private final Runnable rcRunnable = new Runnable() {
        @Override public void run() {
            if (!activityActive || destroyed) return;
            sendRc();
            rcHandler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        enterImmersive();
        nativeMode = getIntent().getBooleanExtra("native_cg", true);
        calibrationStatus = findViewById(R.id.tvCalibration);
        settingsButton = findViewById(R.id.btnSettings);
        settingsButton.setVisibility(nativeMode ? View.VISIBLE : View.GONE);
        settingsButton.setEnabled(false);
        settingsButton.setOnClickListener(v -> openSettings());
        calibrationStatus.setVisibility(View.GONE);
        calibrationStatus.setText("");
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(this, rcHandler);

        btnVideo    = findViewById(R.id.btnVideo);
        btnSpeed    = findViewById(R.id.btnSpeed);
        btnRecord   = findViewById(R.id.btnRecord);
        btnPhoto    = findViewById(R.id.btnPhoto);
        tvStatus    = findViewById(R.id.textview_message);
        tvConnection = findViewById(R.id.tvConnection);
        surfaceView = findViewById(R.id.surface_video);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        leftStick  = findViewById(R.id.joystickLeft);
        rightStick = findViewById(R.id.joystickRight);
        leftStick.setListener((x, y)  -> { touchLX = x; touchLY = y; });
        rightStick.setListener((x, y) -> { touchRX = x; touchRY = y; });

        findViewById(R.id.btnTakeoff).setOnClickListener(v -> requestTakeoff());
        findViewById(R.id.btnLand).setOnClickListener(v -> { sendCommand("land"); setStatus("Pouso solicitado"); isArmed = false; });
        findViewById(R.id.btnEmergency).setOnClickListener(v -> confirmEmergency());

        btnSpeed.setOnClickListener(v -> cycleSpeed(1));
        findViewById(R.id.btnSpeedUp).setOnClickListener(v -> cycleSpeed(1));
        findViewById(R.id.btnSpeedDown).setOnClickListener(v -> cycleSpeed(-1));

        btnVideo.setOnClickListener(v -> toggleVideo());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnPhoto.setOnClickListener(v -> capturePhoto());

        findViewById(R.id.btnFlipF).setOnClickListener(v -> sendCommand("flip f"));
        findViewById(R.id.btnFlipB).setOnClickListener(v -> sendCommand("flip b"));
        findViewById(R.id.btnFlipL).setOnClickListener(v -> sendCommand("flip l"));
        findViewById(R.id.btnFlipR).setOnClickListener(v -> sendCommand("flip r"));

        wifiNetwork = WifiRoute.findWifiNetwork(this);
        if (nativeMode) {
            setupNetworkMonitoring();
            setStatus("Aguardando conexão ao Tello...");
        } else {
            try {
                client = new UDPClient(wifiNetwork, message -> setStatus(message));
                sendCommand("command"); sendCommand("speed " + SPEEDS[speedIndex]);
                setStatus("Aguardando telemetria do Tello...");
                statusServer = new UDPStatusServer(wifiNetwork, (display, battery, connected) ->
                    runOnUiThread(() -> { if (!destroyed) updateStatus(display,battery,connected); }));
                statusServer.start();
            } catch (Exception e) { setStatus("Conecte ao Wi-Fi do Tello e reabra Pilotar"); }
        }
    }

    private boolean isCurrentSession(int session) {
        return !destroyed && !switchingToImu && session==nativeSession;
    }

    private void startNativeSession(Network network) {
        stopNativeSession();
        wifiNetwork=network;
        final int session=nativeSession;
            nativeClient = new NativeFlightClient(wifiNetwork::bindSocket, new NativeFlightClient.Listener() {
                public void onTelemetry(int battery, int heightDm, boolean flying, boolean fresh) {
                    runOnUiThread(() -> {
                        if (!isCurrentSession(session)) return;
                        boolean reconnected=fresh && !telemetryConnected;
                        nativeFlying=flying; nativeHeight=heightDm;
                        if (!fresh) pausePreview();
                        updateStatus(fresh ? "BAT: " + battery + "%  ALT: " + (heightDm * 10) + " cm" : "SEM TELEMETRIA ATUAL", battery, fresh);
                        refreshSettings();
                        if (reconnected && videoWanted) rcHandler.postDelayed(() -> {
                            if (isCurrentSession(session) && activityActive && nativeClient!=null && nativeClient.fresh() && !videoRunning) startWantedPreview();
                        }, 100);
                    });
                }
                public void onMessage(String message) {
                    runOnUiThread(() -> { if (isCurrentSession(session)) {
                        setStatus(message);
                        if (!message.startsWith("Aguardando")) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }});
                }
                public void onSessionReset() {
                    runOnUiThread(() -> {
                        if (!isCurrentSession(session)) return;
                        pausePreview(); resetInputs();
                        calibrationStatus.setVisibility(View.GONE);calibrationStatus.setText("");
                        updateStatus("Aguardando reconexão ao Tello...", -1, false);refreshSettings();
                    });
                }
                public void onCalibration(GravityCalibrationTracker.State state) {
                    runOnUiThread(() -> { if (!isCurrentSession(session)) return;
                        String text;
                        switch (state) {
                            case REQUESTED: text="CG solicitado — aguardando início confirmado"; break;
                            case RUNNING: text="Calibrando CG — mantenha pairando. Pousar disponível."; break;
                            case SUCCEEDED: text="Calibração CG concluída — sequência confirmada pelo drone"; break;
                            case REJECTED: text="O drone recusou a calibração CG"; break;
                            default: text="Conclusão CG não confirmada. Pousar continua disponível.";
                        }
                        calibrationStatus.setVisibility(View.VISIBLE);
                        calibrationStatus.setText(text);
                        calibrationStatus.setTextColor(state==GravityCalibrationTracker.State.SUCCEEDED ? 0xFF2ECC71 : 0xFFFFFFFF);
                        refreshSettings();
                    });
                }
            });
    }

    private void stopNativeSession() {
        ++nativeSession;
        resetInputs();
        if(nativeClient!=null){nativeClient.close();nativeClient=null;}
        pausePreview();
        telemetryConnected=false;
        calibrationStatus.setText("");calibrationStatus.setVisibility(View.GONE);
        refreshSettings();
    }

    private void ensureNativeConnection() {
        if(destroyed || switchingToImu || !activityActive)return;
        Network available=WifiRoute.findWifiNetwork(this);
        if(available==null) {
            if(nativeClient!=null)stopNativeSession();
            wifiNetwork=null;
            updateStatus("Aguardando Wi-Fi do Tello...",-1,false);
            return;
        }
        if(nativeClient==null || !nativeClient.isRunning() || !available.equals(wifiNetwork))startNativeSession(available);
    }

    private void setupNetworkMonitoring() {
        connectivityManager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(connectivityManager==null)return;
        networkCallback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                runOnUiThread(() -> { if(!destroyed && !switchingToImu)ensureNativeConnection(); });
            }
            @Override public void onLost(Network network) {
                runOnUiThread(() -> {
                    if(destroyed || switchingToImu)return;
                    if(wifiNetwork!=null && wifiNetwork.equals(network)) {
                        stopNativeSession();wifiNetwork=null;
                        updateStatus("Aguardando Wi-Fi do Tello...",-1,false);
                    }
                });
            }
        };
        try {
            connectivityManager.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),networkCallback);
        } catch(RuntimeException e) { networkCallback=null; } // polling remains available
    }

    private void pausePreview() {
        if(videoDecoder!=null){videoDecoder.stopDecoding();videoDecoder=null;}
        videoRunning=false;
        if(btnVideo!=null)btnVideo.setText(R.string.start_video);
        if(btnRecord!=null)btnRecord.setText("⏺ REC");
    }

    private void startWantedPreview() {
        if(videoWanted && !videoRunning && surfaceHolder!=null && surfaceHolder.getSurface().isValid())toggleVideo();
    }

    private void updateStatus(String display, int battery, boolean connected) {
        if (connected && !telemetryConnected) resetInputs();
        telemetryConnected = connected;
        tvStatus.setText(display);
        if (!connected) resetInputs();
        if (connected) {
            tvConnection.setText("●");
            tvConnection.setTextColor(battery > 20 ? 0xFF2ECC71 : 0xFFE74C3C);
            // Low battery warning
            if (battery <= 20 && battery > 0 && !lowBatteryWarned) {
                lowBatteryWarned = true;
                setStatus("Bateria baixa: " + battery + "%");
                vibrate(new long[]{0, 300, 100, 300, 100, 300});
                Toast.makeText(this, "Bateria baixa. Pouse em breve.", Toast.LENGTH_LONG).show();
            }
            if (battery > 25) lowBatteryWarned = false;
        } else {
            tvConnection.setText("○");
            tvConnection.setTextColor(0xFFE74C3C);
        }
    }

    private void vibrate(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        }
    }

    private void sendRc() {
        if (!telemetryConnected || !hasWindowFocus() || (client == null && nativeClient == null)) return;
        float s = SCALES[speedIndex];
        int roll     = (int)((padRX != 0 ? padRX : touchRX) * s);
        int pitch    = (int)((padRY != 0 ? -padRY : -touchRY) * s);
        int throttle = (int)((padLY != 0 ? -padLY : -touchLY) * s);
        int yaw      = (int)((padLX != 0 ? padLX : touchLX) * s);
        sendCommand("rc " + roll + " " + pitch + " " + throttle + " " + yaw);
    }

    private void armSafety() {
        if (!isArmed) {
            sendCommand("command");
            isArmed = true;
            setStatus("Preparação solicitada; confirme a decolagem");
            vibrate(new long[]{0, 100, 50, 100});
        } else {
            setStatus("Confirme a decolagem pelo botão Decolar");
        }
    }

    private void cycleSpeed(int dir) {
        speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, speedIndex + dir));
        btnSpeed.setText(SPEED_LABELS[speedIndex]);
        sendCommand("speed " + SPEEDS[speedIndex]);
        setStatus("Speed: " + SPEED_LABELS[speedIndex]);
    }

    private void toggleVideo() {
        if (!videoRunning) {
            videoWanted=true;
            if ((client == null && nativeClient == null) || wifiNetwork == null) {
                setStatus("Conecte ao Wi-Fi do Tello e reabra Pilotar");
                return;
            }
            sendCommand("streamon");
            if (surfaceHolder != null && surfaceHolder.getSurface().isValid()) {
                videoDecoder = new TelloVideoDecoder(surfaceHolder.getSurface(), wifiNetwork, nativeMode);
                videoDecoder.startDecoding();
                videoRunning = true;
                btnVideo.setText(R.string.stop_video);
            }
        } else {
            videoWanted=false;
            sendCommand("streamoff");
            if (videoDecoder != null) { videoDecoder.stopDecoding(); videoDecoder = null; }
            videoRunning = false;
            btnVideo.setText(R.string.start_video);
            btnRecord.setText("⏺ REC");
        }
    }

    private void toggleRecording() {
        if (videoDecoder == null) { Toast.makeText(this, "Start video first", Toast.LENGTH_SHORT).show(); return; }
        if (videoDecoder.isRecording()) {
            videoDecoder.stopRecording();
            btnRecord.setText("⏺ REC");
            Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show();
        } else {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                File file = new File(dir, "tello_" + ts + ".mp4");
                videoDecoder.startRecording(file.getAbsolutePath());
                btnRecord.setText("⏹ STOP");
                Toast.makeText(this, "Recording...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Record failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void capturePhoto() {
        if (!videoRunning || surfaceView == null) {
            Toast.makeText(this, "Start video first", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(surfaceView.getWidth(), surfaceView.getHeight(), Bitmap.Config.ARGB_8888);
        HandlerThread ht = new HandlerThread("PixelCopy");
        ht.start();
        PixelCopy.request(surfaceView, bitmap, result -> {
            if (result == PixelCopy.SUCCESS) saveBitmapToGallery(bitmap);
            else runOnUiThread(() -> Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show());
            ht.quitSafely();
        }, new Handler(ht.getLooper()));
    }

    private void saveBitmapToGallery(Bitmap bitmap) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "tello_" + ts + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TelloNO");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                runOnUiThread(() -> Toast.makeText(this, "📷 Photo saved!", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void sendCommand(String cmd) {
        if ((cmd.equals("takeoff") || cmd.startsWith("flip "))
                && (!activityActive || !telemetryConnected || destroyed)) {
            setStatus("Sem sessão ativa com telemetria; comando de voo não enviado");
            return;
        }
        if (nativeClient != null) {
            if (!nativeClient.sendBytes(cmd.getBytes(StandardCharsets.UTF_8))) setStatus("Comando não enfileirado");
        } else if (client != null) {
            try {
                if (!client.sendBytes(cmd.getBytes(StandardCharsets.UTF_8)))
                    setStatus("Comando não enviado: conexão indisponível ou fila cheia");
            }
            catch (Exception e) { Log.e(TAG, "CMD: " + e.getMessage()); }
        }
    }

    private void setStatus(final String msg) {
        runOnUiThread(() -> { if (!destroyed && tvStatus != null) tvStatus.setText(msg); });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ++nativeSession;
        activityActive = false;
        if(connectivityManager!=null && networkCallback!=null) {
            try {connectivityManager.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
        }
        resetInputs();
        rcHandler.removeCallbacksAndMessages(null);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        if (videoDecoder != null) videoDecoder.stopDecoding();
        if (statusServer != null) statusServer.stopServer();
        if (client != null) client.close();
        if (nativeClient != null) nativeClient.close();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        activityActive = true;
        rcHandler.removeCallbacks(rcRunnable);
        rcHandler.postDelayed(rcRunnable, 100);
        rcHandler.removeCallbacks(reconnectRunnable);
        if(nativeMode)rcHandler.post(reconnectRunnable);
    }

    @Override protected void onPause() {
        activityActive = false;
        rcHandler.removeCallbacks(rcRunnable);
        rcHandler.removeCallbacks(reconnectRunnable);
        resetInputs();
        super.onPause();
    }

    private void resetInputs() {
        if (leftStick != null) leftStick.resetToCenter();
        if (rightStick != null) rightStick.resetToCenter();
        touchLX = touchLY = touchRX = touchRY = 0;
        padLX = padLY = padRX = padRY = 0;
        isArmed = false;
        if (client != null) client.suspendControls();
        if (nativeClient != null) nativeClient.suspendControls();
    }

    private void requestTakeoff() {
        if (!telemetryConnected || (client == null && nativeClient == null)) {
            setStatus("Sem telemetria válida. Verifique a conexão com o Tello.");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Decolar?")
            .setMessage("Confirme que o local está livre. Esta versão ainda requer testes no drone.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Decolar", (dialog, which) -> {
                if (!activityActive || !telemetryConnected || destroyed) return;
                isArmed = true;
                sendCommand("takeoff");
                setStatus("Decolagem solicitada; aguarde o drone");
            }).show();
    }

    private void refreshSettings() {
        boolean ready=telemetryConnected && nativeClient!=null && nativeClient.fresh();
        settingsButton.setEnabled(ready);
        boolean active=nativeClient!=null && nativeClient.calibrationActive();
        for(int id:new int[]{R.id.btnFlipF,R.id.btnFlipB,R.id.btnFlipL,R.id.btnFlipR})
            findViewById(id).setEnabled(ready && nativeFlying && !active);
        if(imuSetting!=null)imuSetting.setEnabled(ready && nativeClient.onGround() && !active);
        if(cgSetting!=null)cgSetting.setEnabled(ready && nativeFlying && !active && !nativeClient.calibrationUsed());
    }

    private void openSettings() {
        if(nativeClient==null || !nativeClient.fresh())return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density);content.setPadding(pad,pad,pad,pad);
        TextView info=new TextView(this);info.setText("IMU: no solo. Centro de gravidade: em voo pairado, sem vento. Ambas as funções são experimentais.");content.addView(info);
        imuSetting=new Button(this);imuSetting.setText("Calibrar IMU — no solo");content.addView(imuSetting);
        cgSetting=new Button(this);cgSetting.setText("Calibrar centro de gravidade — em voo");content.addView(cgSetting);
        settingsDialog=new AlertDialog.Builder(this).setTitle("Configurações do drone").setView(content).setPositiveButton("Fechar",null).create();
        imuSetting.setOnClickListener(v -> {
            if(!nativeClient.onGround())return;
            settingsDialog.dismiss(); switchingToImu=true; resetInputs();
            if(videoDecoder!=null)videoDecoder.stopDecoding();
            nativeClient.close();
            startActivity(new Intent(this,CalibrationActivity.class));finish();
        });
        cgSetting.setOnClickListener(v -> {
            settingsDialog.dismiss();
            new AlertDialog.Builder(this).setTitle("Calibrar centro de gravidade?")
                .setMessage("Mantenha o drone pairando em local sem vento e deixe os controles neutros. A sequência de sucesso foi identificada na sua captura. Esta implementação ainda precisa de teste no aparelho. Pousar permanece disponível.")
                .setNegativeButton("Voltar",null)
                .setPositiveButton("Iniciar",(d,w)-> { if(activityActive && nativeClient.fresh())nativeClient.requestGravityCalibration(); }).show();
        });
        settingsDialog.setOnDismissListener(d -> { imuSetting=null;cgSetting=null; });
        refreshSettings();settingsDialog.show();
    }

    @Override public void onBackPressed() {
        if(nativeClient!=null && nativeClient.flying()) {
            new AlertDialog.Builder(this).setTitle("Drone ainda em voo")
                .setMessage("Pouse antes de voltar ao menu. Encerrar a conexão remove os controles desta tela.")
                .setNegativeButton("Continuar pilotando",null)
                .setPositiveButton("Pousar",(d,w)->sendCommand("land"))
                .setNeutralButton("Encerrar conexão",(d,w)->super.onBackPressed()).show();
        } else super.onBackPressed();
    }

    private void confirmEmergency() {
        new AlertDialog.Builder(this)
            .setTitle("Parar os motores?")
            .setMessage("O comando emergency desliga os motores e pode derrubar o drone. Para descer normalmente, use Pousar.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Parar motores", (dialog, which) -> {
                sendCommand("emergency");
                isArmed = false;
                setStatus("Parada de motores solicitada");
            }).show();
    }

    @Override public void onInputDeviceAdded(int deviceId) { }
    @Override public void onInputDeviceChanged(int deviceId) { resetInputs(); }
    @Override public void onInputDeviceRemoved(int deviceId) { resetInputs(); }

    // --- Gamepad ---

    private static float getCenteredAxis(MotionEvent event, InputDevice device, int axis, int historyPos) {
        final InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range != null) {
            final float flat = range.getFlat();
            final float value = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);
            if (Math.abs(value) > flat) return value;
        }
        return 0;
    }

    private void processJoystickInput(MotionEvent event, int historyPos) {
        InputDevice dev = event.getDevice();
        if (dev == null) return;
        padLX = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_X,  historyPos) * 100);
        padLY = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_Y,  historyPos) * 100);
        padRX = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_Z,  historyPos) * 100);
        padRY = (int)(getCenteredAxis(event, dev, MotionEvent.AXIS_RZ, historyPos) * 100);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getHistorySize(); i++) processJoystickInput(event, i);
            processJoystickInput(event, -1);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                && event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_L2:     requestTakeoff(); return true;
                case KeyEvent.KEYCODE_BUTTON_L1:     sendCommand("land");      setStatus("Pouso solicitado"); isArmed = false; return true;
                case KeyEvent.KEYCODE_BUTTON_R1:     toggleRecording();        return true;
                case KeyEvent.KEYCODE_BUTTON_R2:     capturePhoto();           return true;
                case KeyEvent.KEYCODE_BUTTON_START:  armSafety();              return true;
                case KeyEvent.KEYCODE_BUTTON_A:      toggleVideo();            return true;
                case KeyEvent.KEYCODE_BUTTON_Y:      sendCommand("flip f");    return true;
                case KeyEvent.KEYCODE_BUTTON_X:      sendCommand("flip l");    return true;
                case KeyEvent.KEYCODE_BUTTON_B:      sendCommand("flip r");    return true;
                case KeyEvent.KEYCODE_BUTTON_SELECT: confirmEmergency(); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
        else resetInputs();
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.systemBars());
                ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        rcHandler.postDelayed(() -> {
            if (!destroyed && activityActive && !videoRunning && videoWanted) startWantedPreview();
        }, 1000);
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) { surfaceHolder = holder; }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (videoDecoder != null) { videoDecoder.stopDecoding(); videoDecoder = null; videoRunning = false; }
    }
}
