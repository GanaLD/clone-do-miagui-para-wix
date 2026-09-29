package com.mensagemstudio.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1101;
    private TextView titleView;
    private TextView statusView;
    private TextView hintView;
    private TextView logView;
    private EditText commandInput;
    private Button startButton;
    private Button jarvisButton;
    private Button helenaButton;
    private AssistantProfile profile;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String text = intent.getStringExtra("message");
            String state = intent.getStringExtra("state");
            String identity = intent.getStringExtra("identity");
            if (identity != null) {
                profile = AssistantProfile.fromId(identity);
                updateIdentityUi();
            }
            if (text != null) {
                statusView.setText(text);
                if ("reply".equals(state) || "heard".equals(state) || "identity".equals(state)) {
                    logView.setText(text + "\n\n" + logView.getText());
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profile = AssistantPreferences.getProfile(this);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(5, 7, 10));
        window.setNavigationBarColor(Color.rgb(5, 7, 10));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());

        IntentFilter filter = new IntentFilter(JarvisService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        updateIdentityUi();
        requestAndStart();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(5, 7, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));

        TextView eyebrow = label("OPENAI AGENT · ANDROID VOICE CORE", 11, 0xFF72DFF4);
        root.addView(eyebrow);

        titleView = label(profile.name, 42, Color.WHITE);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(titleView);

        TextView online = label("●  ONLINE", 12, 0xFF49E7FF);
        online.setPadding(0, dp(4), 0, dp(14));
        root.addView(online);

        LinearLayout identityPanel = new LinearLayout(this);
        identityPanel.setOrientation(LinearLayout.VERTICAL);
        identityPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        identityPanel.setBackground(makeRounded(0xFF0B1016, 0xFF26343C));
        root.addView(identityPanel, fullWidthWrapParams(0, 0, 0, dp(14)));

        TextView identityLabel = label("ASSISTENTE", 10, 0xFF72DFF4);
        identityLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        identityPanel.addView(identityLabel);

        LinearLayout identityRow = new LinearLayout(this);
        identityRow.setOrientation(LinearLayout.HORIZONTAL);
        identityRow.setPadding(0, dp(8), 0, 0);
        identityPanel.addView(identityRow);

        jarvisButton = choiceButton("JARVIS", profile == AssistantProfile.JARVIS);
        helenaButton = choiceButton("HELENA", profile == AssistantProfile.HELENA);
        Button testVoice = choiceButton("TESTAR VOZ", false);
        jarvisButton.setOnClickListener(v -> selectIdentity(AssistantProfile.JARVIS));
        helenaButton.setOnClickListener(v -> selectIdentity(AssistantProfile.HELENA));
        testVoice.setOnClickListener(v -> testVoice());

        LinearLayout.LayoutParams identityButtonParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        identityButtonParams.setMargins(0, 0, dp(6), 0);
        identityRow.addView(jarvisButton, identityButtonParams);
        LinearLayout.LayoutParams helenaParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        helenaParams.setMargins(0, 0, dp(6), 0);
        identityRow.addView(helenaButton, helenaParams);
        identityRow.addView(testVoice, new LinearLayout.LayoutParams(0, dp(44), 1.15f));

        TextView orb = label("◉", 112, 0xFF49E7FF);
        orb.setGravity(Gravity.CENTER);
        GradientDrawable orbBg = new GradientDrawable();
        orbBg.setShape(GradientDrawable.OVAL);
        orbBg.setColor(0xFF07151A);
        orbBg.setStroke(dp(2), 0xFF49E7FF);
        orb.setBackground(orbBg);
        LinearLayout.LayoutParams orbParams = new LinearLayout.LayoutParams(dp(190), dp(190));
        orbParams.gravity = Gravity.CENTER_HORIZONTAL;
        orbParams.setMargins(0, dp(10), 0, dp(16));
        root.addView(orb, orbParams);

        statusView = label("INICIANDO " + profile.name + "…", 15, Color.WHITE);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(statusView);

        hintView = label("Diga “" + profile.name + "” e depois seu comando. O serviço continua ativo em segundo plano enquanto o Android permitir.", 12, 0xFF94A3AD);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(dp(8), dp(8), dp(8), dp(18));
        root.addView(hintView);

        startButton = button("ATIVAR " + profile.name);
        startButton.setOnClickListener(v -> requestAndStart());
        root.addView(startButton, fullWidthParams(0, 0, 0, dp(8)));

        Button stop = button("PARAR ESCUTA");
        stop.setTextColor(0xFFB7C1C7);
        stop.setBackground(makeRounded(0xFF10161B, 0xFF2B353C));
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, JarvisService.class));
            statusView.setText(profile.name + " PARADO");
        });
        root.addView(stop, fullWidthParams(0, 0, 0, dp(18)));

        TextView actions = label("AÇÕES COMPARTILHADAS: YouTube · WhatsApp · Spotify · câmera · configurações · calendário · mapas · ChatGPT", 11, 0xFF72DFF4);
        actions.setPadding(dp(2), 0, dp(2), dp(14));
        root.addView(actions);

        LinearLayout commandRow = new LinearLayout(this);
        commandRow.setOrientation(LinearLayout.HORIZONTAL);
        commandInput = new EditText(this);
        commandInput.setHint("Digite um comando…");
        commandInput.setHintTextColor(0xFF64727C);
        commandInput.setTextColor(Color.WHITE);
        commandInput.setSingleLine(true);
        commandInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        commandInput.setBackground(makeRounded(0xFF0B1116, 0xFF25323A));
        commandRow.addView(commandInput, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button send = button("ENVIAR");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(94), dp(52));
        sendParams.setMargins(dp(8), 0, 0, 0);
        commandRow.addView(send, sendParams);
        send.setOnClickListener(v -> sendManualCommand());
        root.addView(commandRow);

        TextView logTitle = label("CANAL DE VOZ", 11, 0xFF72DFF4);
        logTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(logTitle);

        logView = label("Sistema pronto para iniciar.", 13, 0xFFC8D0D5);
        logView.setPadding(dp(14), dp(14), dp(14), dp(14));
        logView.setBackground(makeRounded(0xFF090E12, 0xFF1F2A31));
        root.addView(logView, fullWidthWrapParams(0, 0, 0, dp(10)));

        TextView footer = label("UM AGENTE · DUAS IDENTIDADES · GPT VOICE ASSISTANT", 10, 0xFF53616A);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);
        return scroll;
    }

    private void selectIdentity(AssistantProfile next) {
        profile = next;
        AssistantPreferences.setProfile(this, next);
        updateIdentityUi();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, JarvisService.class);
            intent.setAction(JarvisService.ACTION_SET_IDENTITY);
            intent.putExtra("identity", next.id);
            startServiceCompat(intent);
        }
    }

    private void testVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAndStart();
            return;
        }
        Intent intent = new Intent(this, JarvisService.class);
        intent.setAction(JarvisService.ACTION_TEST_VOICE);
        startServiceCompat(intent);
    }

    private void updateIdentityUi() {
        if (titleView != null) titleView.setText(profile.name);
        if (hintView != null) hintView.setText("Diga “" + profile.name + "” e depois seu comando. O serviço continua ativo em segundo plano enquanto o Android permitir.");
        if (startButton != null) startButton.setText("ATIVAR " + profile.name);
        if (jarvisButton != null) applyChoiceStyle(jarvisButton, profile == AssistantProfile.JARVIS);
        if (helenaButton != null) applyChoiceStyle(helenaButton, profile == AssistantProfile.HELENA);
    }

    private void requestAndStart() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            startAssistant();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAssistant();
        } else if (requestCode == REQUEST_PERMISSIONS) {
            statusView.setText("PERMISSÃO DE MICROFONE NECESSÁRIA");
        }
    }

    private void startAssistant() {
        Intent intent = new Intent(this, JarvisService.class);
        intent.setAction(JarvisService.ACTION_START);
        startServiceCompat(intent);
        statusView.setText(profile.name + " ATIVO · AGUARDANDO CHAMADA");
    }

    private void sendManualCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) return;
        Intent intent = new Intent(this, JarvisService.class);
        intent.setAction(JarvisService.ACTION_COMMAND);
        intent.putExtra("command", command);
        startServiceCompat(intent);
        commandInput.setText("");
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFF001014);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(makeRounded(0xFF49E7FF, 0xFF49E7FF));
        return b;
    }

    private Button choiceButton(String text, boolean active) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setAllCaps(false);
        applyChoiceStyle(b, active);
        return b;
    }

    private void applyChoiceStyle(Button button, boolean active) {
        button.setTextColor(active ? 0xFF001014 : 0xFFB7C1C7);
        button.setBackground(makeRounded(active ? 0xFF49E7FF : 0xFF10161B, active ? 0xFF49E7FF : 0xFF2B353C));
    }

    private GradientDrawable makeRounded(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams fullWidthParams(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams fullWidthWrapParams(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
