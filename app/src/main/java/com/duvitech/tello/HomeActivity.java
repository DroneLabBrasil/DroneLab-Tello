package com.duvitech.tello;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Keeps the SDK flight screen and the binary IMU session separate. */
public class HomeActivity extends Activity {
    static final String PREFS = "a54_calibration";
    static final String RESTART_REQUIRED = "restart_required";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        // An optional local logo is added by Aplicar_Logo_DroneLab.ps1.
        int logoId = getResources().getIdentifier("dronelab_logo", "drawable", getPackageName());
        if (logoId != 0) {
            ImageView logo = new ImageView(this);
            logo.setImageResource(logoId);
            logo.setContentDescription("Logo DroneLab");
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(logo, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (180 * getResources().getDisplayMetrics().density)));
        }
        centeredText(content, "Drone Lab Tello", 28);
        centeredText(content, "Versão 2.5 teste", 16);
        text(content, "Conecte o telefone ao Wi-Fi do Tello antes de continuar.", 17);
        Button fly = new Button(this);
        fly.setText("Pilotar");
        fly.setOnClickListener(v -> {
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(RESTART_REQUIRED, false)) {
                new AlertDialog.Builder(this)
                        .setTitle("Reinicie o drone primeiro")
                        .setMessage("Após iniciar ou interromper a calibração, desligue e ligue "
                                + "o drone antes de pilotar. Você já fez isso?")
                        .setNegativeButton("Ainda não", null)
                        .setPositiveButton("Sim, reiniciei", (dialog, which) -> {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean(RESTART_REQUIRED, false).apply();
                            openFlight();
                        }).show();
            } else openFlight();
        });
        content.addView(fly);
        text(content, "Após conectar, use a engrenagem na tela de voo para acessar IMU (no solo) e centro de gravidade (em voo). O modo de conexão nativo desta versão é experimental.", 15);
        setContentView(scroll);
    }

    private void openFlight() {
        if (WifiRoute.findWifiNetwork(this) == null) {
            new AlertDialog.Builder(this).setMessage("Conecte-se ao Wi-Fi do Tello primeiro.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        startActivity(new Intent(this, MainActivity.class));
    }

    private void centeredText(LinearLayout parent, String message, int size) {
        TextView view=new TextView(this);
        view.setText(message);view.setTextSize(size);view.setGravity(Gravity.CENTER);
        view.setPadding(0,12,0,12);
        parent.addView(view,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void text(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, 12, 0, 12);
        parent.addView(view);
    }
}
