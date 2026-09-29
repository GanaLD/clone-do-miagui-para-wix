package com.mensagemstudio.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class PermissionSetupActivity extends Activity {
    private static final int REQUEST_CORE = 2201;
    private LinearLayout statusList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(5, 7, 10));
        getWindow().setNavigationBarColor(Color.rgb(5, 7, 10));
        setContentView(buildUi());
        requestRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 7, 10));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(30));
        scroll.addView(root);

        TextView eyebrow = text("JARVIS / HELENA · CONTROLE DO DISPOSITIVO", 11, 0xFF72DFF4);
        eyebrow.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(eyebrow);

        TextView title = text("Permissões do agente", 32, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(8), 0, dp(8));
        root.addView(title);

        TextView intro = text("Para o agente abrir apps, fazer ligações, ler a tela, escrever em campos, acessar notificações e controlar mídia, o Android exige permissões separadas. As permissões especiais precisam ser ativadas por você nas telas do sistema.", 14, 0xFFC7D0D6);
        intro.setPadding(0, 0, 0, dp(18));
        root.addView(intro);

        statusList = new LinearLayout(this);
        statusList.setOrientation(LinearLayout.VERTICAL);
        statusList.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusList.setBackgroundColor(0xFF0A1116);
        root.addView(statusList, full(dp(12)));

        root.addView(button("PEDIR PERMISSÕES DO APP", v -> requestRuntimePermissions()), full(dp(8)));
        root.addView(button("ATIVAR CONTROLE DE TELA / ACESSIBILIDADE", v -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS)), full(dp(8)));
        root.addView(button("ATIVAR ACESSO ÀS NOTIFICAÇÕES", v -> open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)), full(dp(8)));
        root.addView(button("DEFINIR JARVIS COMO ASSISTENTE DO ANDROID", v -> openDefaultAssistant()), full(dp(8)));
        root.addView(button("LIBERAR BATERIA / SEGUNDO PLANO", v -> requestBatteryExemption()), full(dp(8)));
        root.addView(button("ABRIR TODAS AS PERMISSÕES DO APP", v -> openAppDetails()), full(dp(16)));

        Button continueButton = button("ABRIR JARVIS / HELENA", v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        continueButton.setTextColor(Color.rgb(0, 20, 24));
        continueButton.setBackgroundColor(0xFF49E7FF);
        root.addView(continueButton, full(dp(10)));

        TextView note = text("Observação: o Android não oferece uma permissão única de “controle total”. Campos protegidos, apps bancários e conteúdo marcado como seguro podem continuar inacessíveis mesmo com Acessibilidade ativada.", 11, 0xFF7F909A);
        note.setGravity(Gravity.CENTER);
        root.addView(note);
        return scroll;
    }

    private void requestRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.RECORD_AUDIO);
        addIfMissing(missing, Manifest.permission.CALL_PHONE);
        addIfMissing(missing, Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
            addIfMissing(missing, Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            addIfMissing(missing, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQUEST_CORE);
        refreshStatus();
    }

    private void addIfMissing(List<String> list, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) list.add(permission);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusList == null) return;
        statusList.removeAllViews();
        addStatus("Microfone", has(Manifest.permission.RECORD_AUDIO));
        addStatus("Ligações", has(Manifest.permission.CALL_PHONE));
        addStatus("Contatos", has(Manifest.permission.READ_CONTACTS));
        addStatus("Músicas / áudio", Build.VERSION.SDK_INT >= 33 ? has(Manifest.permission.READ_MEDIA_AUDIO) : has(Manifest.permission.READ_EXTERNAL_STORAGE));
        addStatus("Notificações do próprio JARVIS", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS));
        addStatus("Controle de tela / escrever / clicar", JarvisAccessibilityService.isConnected() || isAccessibilityEnabled());
        addStatus("Ler notificações / controlar player", JarvisNotificationListenerService.isConnected() || isNotificationAccessEnabled());
        ComponentName assistant = new ComponentName(this, JarvisVoiceInteractionService.class);
        addStatus("Assistente padrão do Android", VoiceInteractionService.isActiveService(this, assistant));
        addStatus("Bateria sem restrição", isIgnoringBatteryOptimizations());
    }

    private boolean has(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void addStatus(String name, boolean ok) {
        TextView row = text((ok ? "✓  " : "○  ") + name, 13, ok ? 0xFF67F5B1 : 0xFFFFB86B);
        row.setPadding(0, dp(5), 0, dp(5));
        statusList.addView(row);
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String component = new ComponentName(this, JarvisAccessibilityService.class).flattenToString();
        return enabled.toLowerCase().contains(component.toLowerCase());
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        String component = new ComponentName(this, JarvisNotificationListenerService.class).flattenToString();
        return enabled.toLowerCase().contains(component.toLowerCase());
    }

    private void open(String action) {
        try { startActivity(new Intent(action)); }
        catch (Exception e) { openAppDetails(); }
    }

    private void openDefaultAssistant() {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
        catch (Exception e) { open(Settings.ACTION_SETTINGS); }
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            openAppDetails();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(0xFF111A20);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams full(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
