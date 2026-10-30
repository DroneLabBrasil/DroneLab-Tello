package com.duvitech.tello;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Network;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.duvitech.tello.calibration.ImuCalibrationClient;

/** Experimental IMU calibration. No SDK flight controller is active in this screen. */
public class CalibrationActivity extends Activity {
    private ImuCalibrationClient client;
    private TextView status;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button start;
    private Button leave;
    private boolean monitoring;
    private boolean started;
    private boolean destroyed;
    private int sessionId;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        label(content, "Calibração da IMU — teste", 25);
        label(content, "Remova as hélices. Deixe o drone apoiado em uma superfície plana, "
                + "com os motores parados e pelo menos 50% de bateria. Feche outros aplicativos "
                + "do Tello e conecte o celular ao Wi-Fi dele.", 17);
        label(content, "Esta implementação ainda não foi testada no seu drone. "
                + "Não é a calibração do centro de gravidade.", 15);
        status = label(content, "Pronto para conectar. Nenhuma calibração foi iniciada.", 17);
        progressText = label(content, "Aguardando dados do drone", 18);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        content.addView(progressBar);
        addPositionGuide(content);
        start = new Button(this);
        start.setText("Preparar e iniciar");
        start.setOnClickListener(v -> confirmStart());
        content.addView(start);
        leave = new Button(this);
        leave.setText("Voltar");
        leave.setOnClickListener(v -> askLeave());
        content.addView(leave);
        setContentView(scroll);
        if (getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE)
                .getBoolean(HomeActivity.RESTART_REQUIRED, false)) {
            start.setEnabled(false);
            status.setText("Uma calibração foi iniciada anteriormente. Reinicie o drone antes "
                    + "de tentar novamente.");
            Button restarted = new Button(this);
            restarted.setText("Já desliguei e liguei o drone");
            restarted.setOnClickListener(v -> {
                getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(HomeActivity.RESTART_REQUIRED, false).apply();
                start.setEnabled(true);
                restarted.setEnabled(false);
                status.setText("Pronto para conectar ao drone reiniciado.");
            });
            content.addView(restarted);
        }
    }

    private void addPositionGuide(LinearLayout content) {
        final String[] names = {
            "1/6 — Apoiado normalmente", "2/6 — Lado direito para baixo",
            "3/6 — Lado esquerdo para baixo", "4/6 — Câmera para baixo",
            "5/6 — Câmera para cima", "6/6 — De cabeça para baixo"
        };
        final int[] photos = { R.drawable.imu_pose_0, R.drawable.imu_pose_1,
            R.drawable.imu_pose_2, R.drawable.imu_pose_3,
            R.drawable.imu_pose_4, R.drawable.imu_pose_5 };
        final int[] selected = {0};
        label(content, "Guia visual — seleção manual", 21);
        TextView caption = label(content, names[0], 20);
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setImageResource(photos[0]);
        photo.setContentDescription(names[0]);
        content.addView(photo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (220 * getResources().getDisplayMetrics().density)));
        LinearLayout navigation = new LinearLayout(this);
        Button previous = new Button(this);
        previous.setText("Anterior");
        Button next = new Button(this);
        next.setText("Próxima posição");
        navigation.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(navigation);
        Runnable refresh = () -> {
            caption.setText(names[selected[0]]);
            photo.setImageResource(photos[selected[0]]);
            photo.setContentDescription(names[selected[0]]);
            previous.setEnabled(selected[0] > 0);
            next.setEnabled(selected[0] < photos.length - 1);
        };
        previous.setOnClickListener(v -> { if (selected[0] > 0) selected[0]--; refresh.run(); });
        next.setOnClickListener(v -> { if (selected[0] < photos.length - 1) selected[0]++; refresh.run(); });
        refresh.run();
        label(content, "Direita e esquerda são as do próprio drone. Use a foto como referência. "
                + "Mantenha-o imóvel em cada posição e avance manualmente após o sinal do LED. "
                + "A referência relata LED verde piscando rapidamente quando uma posição é aceita. "
                + "Trocar a foto não confirma uma etapa nem envia comandos ao drone. "
                + "A confirmação continua sendo o progresso recebido acima.", 16);
    }

    private TextView label(LinearLayout parent, String message, int size) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextSize(size);
        text.setPadding(0, 10, 0, 10);
        parent.addView(text);
        return text;
    }

    private void confirmStart() {
        new AlertDialog.Builder(this)
                .setTitle("Confirme a preparação")
                .setMessage("O drone está no chão, imóvel, com as hélices removidas? "
                        + "Depois de iniciar, interromper o acompanhamento não cancela "
                        + "a calibração dentro do drone. Será necessário reiniciá-lo.")
                .setNegativeButton("Voltar", null)
                .setPositiveButton("Sim, iniciar", (dialog, which) -> connect())
                .show();
    }

    private void connect() {
        Network wifi = WifiRoute.findWifiNetwork(this);
        if (wifi == null) {
            status.setText("Não há Wi-Fi conectado. Conecte-se à rede do Tello.");
            return;
        }
        monitoring = true;
        started = true;
        start.setEnabled(false);
        leave.setText("Interromper acompanhamento");
        getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean(HomeActivity.RESTART_REQUIRED, true).apply();
        final int id = ++sessionId;
        client = new ImuCalibrationClient(wifi::bindSocket, new ImuCalibrationClient.Listener() {
            private void ui(Runnable action) {
                runOnUiThread(() -> { if (!destroyed && id == sessionId) action.run(); });
            }
            @Override public void onMessage(String message) { ui(() -> status.setText(message)); }
            @Override public void onProgress(int percent, int steps) {
                ui(() -> {
                    progressBar.setProgress(percent);
                    progressText.setText("Progresso recebido: " + percent + "% — posições aceitas: " + steps + "/6");
                });
            }
            @Override public void onFinished() {
                ui(() -> {
                    monitoring = false;
                    status.setText("O drone informou calibração concluída. Desligue e ligue "
                            + "o drone antes de pilotar.");
                    leave.setText("Voltar ao menu");
                });
            }
            @Override public void onError(String message) {
                ui(() -> {
                    monitoring = false;
                    status.setText(message + "\nNão é possível confirmar a conclusão. "
                            + "Desligue e ligue o drone antes de tentar novamente.");
                    leave.setText("Voltar ao menu");
                });
            }
        });
        client.start();
    }

    private void askLeave() {
        if (!monitoring) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("Interromper acompanhamento?")
                .setMessage("Isso não envia um comando de cancelamento ao drone. "
                        + "Desligue e ligue o drone antes de voltar a pilotar.")
                .setNegativeButton("Continuar aqui", null)
                .setPositiveButton("Interromper e voltar", (d, w) -> finish()).show();
    }

    @Override public void onBackPressed() { askLeave(); }

    @Override protected void onPause() {
        super.onPause();
        stopMonitoring();
        if (started && !isFinishing()) {
            status.setText("Acompanhamento interrompido ao sair da tela. Reinicie o drone, "
                    + "volte ao menu e reabra Calibrar IMU para tentar novamente. "
                    + "A conclusão não foi confirmada nesta sessão.");
        }
    }

    private void stopMonitoring() {
        monitoring = false;
        ++sessionId;
        if (client != null) { client.close(); client = null; }
        if (leave != null) leave.setText("Voltar ao menu");
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopMonitoring();
        super.onDestroy();
    }
}
