from pathlib import Path

service_path = Path("app/src/main/java/com/mensagemstudio/jarvis/JarvisService.java")
text = service_path.read_text(encoding="utf-8")

text = text.replace(
    "import android.media.AudioAttributes;\n",
    "import android.media.AudioAttributes;\nimport android.media.MediaPlayer;\n",
    1,
)
text = text.replace(
    "import java.io.BufferedReader;\n",
    "import java.io.BufferedReader;\nimport java.io.File;\nimport java.io.FileOutputStream;\n",
    1,
)
text = text.replace(
    '    private static final String BACKEND = "https://jarvis-gpt-mobile.floot.app/_api/native";\n',
    '    private static final String BACKEND = "https://jarvis-gpt-mobile.floot.app/_api/native";\n'
    '    private static final String SPEECH_BACKEND = "https://jarvis-gpt-mobile.floot.app/_api/speech";\n',
    1,
)
text = text.replace(
    "    private TextToSpeech tts;\n",
    "    private TextToSpeech tts;\n    private MediaPlayer remotePlayer;\n",
    1,
)

# Prefer the phone's normal recognition service. Samsung can advertise on-device
# pt-BR support before the offline model is usable, causing silent wake-word failures.
text = text.replace("        initRecognizer(true);\n", "        initRecognizer(false);\n", 1)

old = '''        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) handleTranscript(matches.get(0));
        else scheduleListen(350);
'''
new = '''        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String chosen = matches.get(0);
            if (!awaitingCommand) {
                for (String candidate : matches) {
                    if (candidate != null && findWake(normalize(candidate)) != null) {
                        chosen = candidate;
                        break;
                    }
                }
            }
            handleTranscript(chosen);
        } else {
            scheduleListen(350);
        }
'''
assert old in text, "onResults anchor not found"
text = text.replace(old, new, 1)

old = '''            try {
                finalReply = runAgentLoop(command, previousHistory);
            } catch (Exception e) {
                finalReply = "Não consegui acessar o agente GPT agora. Tente novamente em alguns segundos.";
            }
'''
new = '''            try {
                finalReply = runAgentLoop(command, previousHistory);
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                sendStatus("Falha de conexão com GPT: " + detail, "error");
                finalReply = "Não consegui acessar o agente GPT agora. Tente novamente em alguns segundos.";
            }
'''
assert old in text, "GPT exception anchor not found"
text = text.replace(old, new, 1)

old = '''    private void speak(String text, boolean completedCommand) {
        busy = true;
        stopListeningQuietly();
        sendStatus(profile.name + ": " + text, "reply");
        updateNotification(profile.name + " respondendo");

        if (tts == null || !ttsReady) {
            pendingSpeech = text;
            pendingSpeechCompletedCommand = completedCommand;
            sendStatus("Preparando voz…", "tts_loading");
            return;
        }
        speakNow(text, completedCommand);
    }
'''
new = '''    private void speak(String text, boolean completedCommand) {
        busy = true;
        stopListeningQuietly();
        sendStatus(profile.name + ": " + text, "reply");
        updateNotification(profile.name + " respondendo");

        if (profile == AssistantProfile.JARVIS) {
            speakJarvisRemote(text, completedCommand);
            return;
        }
        speakLocal(text, completedCommand);
    }

    private void speakLocal(String text, boolean completedCommand) {
        if (tts == null || !ttsReady) {
            pendingSpeech = text;
            pendingSpeechCompletedCommand = completedCommand;
            sendStatus("Preparando voz…", "tts_loading");
            return;
        }
        speakNow(text, completedCommand);
    }

    private void speakJarvisRemote(String text, boolean completedCommand) {
        currentSpeechCompletedCommand = completedCommand;
        sendStatus("Gerando voz cinematográfica do JARVIS…", "tts_loading");
        executor.submit(() -> {
            File audioFile = null;
            try {
                audioFile = downloadJarvisAudio(text);
                File ready = audioFile;
                main.post(() -> playJarvisAudio(ready, text, completedCommand));
            } catch (Exception error) {
                if (audioFile != null) try { audioFile.delete(); } catch (Exception ignored) {}
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                main.post(() -> {
                    sendStatus("ElevenLabs indisponível · usando voz local · " + detail, "tts_fallback");
                    speakLocal(text, completedCommand);
                });
            }
        });
    }

    private File downloadJarvisAudio(String text) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(SPEECH_BACKEND).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "audio/mpeg");
        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("identity", "jarvis");
        byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(requestBytes);
        }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream errorStream = connection.getErrorStream();
            StringBuilder detail = new StringBuilder();
            if (errorStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) detail.append(line);
                }
            }
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code + (detail.length() > 0 ? " · " + detail : ""));
        }
        File file = File.createTempFile("jarvis-voice-", ".mp3", getCacheDir());
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } finally {
            connection.disconnect();
        }
        return file;
    }

    private void playJarvisAudio(File file, String fallbackText, boolean completedCommand) {
        try {
            releaseRemotePlayer();
            remotePlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                remotePlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(Build.VERSION.SDK_INT >= 26 ? AudioAttributes.USAGE_ASSISTANT : AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            remotePlayer.setDataSource(file.getAbsolutePath());
            remotePlayer.setOnCompletionListener(player -> {
                try { player.release(); } catch (Exception ignored) {}
                remotePlayer = null;
                try { file.delete(); } catch (Exception ignored) {}
                finishSpeech(completedCommand);
            });
            remotePlayer.setOnErrorListener((player, what, extra) -> {
                try { player.release(); } catch (Exception ignored) {}
                remotePlayer = null;
                try { file.delete(); } catch (Exception ignored) {}
                sendStatus("Falha ao reproduzir voz ElevenLabs · usando voz local", "tts_fallback");
                speakLocal(fallbackText, completedCommand);
                return true;
            });
            remotePlayer.prepare();
            sendStatus("Voz JARVIS · ElevenLabs", "tts_ready");
            remotePlayer.start();
        } catch (Exception error) {
            try { file.delete(); } catch (Exception ignored) {}
            releaseRemotePlayer();
            sendStatus("Falha ao iniciar voz ElevenLabs · usando voz local", "tts_fallback");
            speakLocal(fallbackText, completedCommand);
        }
    }

    private void releaseRemotePlayer() {
        if (remotePlayer != null) {
            try { remotePlayer.stop(); } catch (Exception ignored) {}
            try { remotePlayer.release(); } catch (Exception ignored) {}
            remotePlayer = null;
        }
    }
'''
assert old in text, "speak anchor not found"
text = text.replace(old, new, 1)

old = '''        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
'''
new = '''        releaseRemotePlayer();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
'''
assert old in text, "onDestroy TTS anchor not found"
text = text.replace(old, new, 1)

service_path.write_text(text, encoding="utf-8")

activity_path = Path("app/src/main/java/com/mensagemstudio/jarvis/MainActivity.java")
activity = activity_path.read_text(encoding="utf-8")
orb_anchor = '''        orb.setGravity(Gravity.CENTER);
        GradientDrawable orbBg = new GradientDrawable();
'''
orb_replacement = '''        orb.setGravity(Gravity.CENTER);
        orb.setOnClickListener(v -> {
            requestAndStart();
            if (statusView != null) statusView.setText("REATIVANDO MICROFONE…");
        });
        GradientDrawable orbBg = new GradientDrawable();
'''
assert orb_anchor in activity, "orb anchor not found"
activity = activity.replace(orb_anchor, orb_replacement, 1)
activity_path.write_text(activity, encoding="utf-8")

# Wire the install-safe main APK to the separate JARVIS Control APK through a
# signature-protected ContentProvider. Calls are synchronous and only the two APKs
# signed by the same key can use this bridge.
tool_path = Path("app/src/main/java/com/mensagemstudio/jarvis/AgentToolExecutor.java")
tool = tool_path.read_text(encoding="utf-8")
if "import android.os.Bundle;" not in tool:
    tool = tool.replace("import android.net.Uri;\n", "import android.net.Uri;\nimport android.os.Bundle;\n", 1)
execute_anchor = '''    public ToolResult execute(String name, JSONObject arguments) {
        try {
            switch (name) {
'''
execute_replacement = '''    public ToolResult execute(String name, JSONObject arguments) {
        try {
            ToolResult extended = tryExtendedTool(name, arguments);
            if (extended != null) return extended;
            switch (name) {
'''
assert execute_anchor in tool, "AgentToolExecutor execute anchor not found"
tool = tool.replace(execute_anchor, execute_replacement, 1)
helper_anchor = '''    public static final class ToolResult {
'''
helper_code = '''    private ToolResult tryExtendedTool(String name, JSONObject arguments) {
        if ("play_local_audio".equals(name) || "open_whatsapp_chat".equals(name)) {
            return DeviceControlTools.tryExecute(context, name, arguments);
        }
        switch (name) {
            case "read_screen":
            case "click_text":
            case "type_text":
            case "system_action":
            case "list_notifications":
            case "open_notification":
            case "media_play":
            case "media_pause":
            case "media_next":
            case "media_previous":
                return callControlModule(name, arguments);
            default:
                return null;
        }
    }

    private ToolResult callControlModule(String name, JSONObject arguments) {
        try {
            Bundle extras = new Bundle();
            if (arguments != null) {
                java.util.Iterator<String> keys = arguments.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    extras.putString(key, arguments.optString(key, ""));
                }
            }
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://com.mensagemstudio.jarvis.control.bridge"),
                    name,
                    null,
                    extras);
            if (result == null) {
                return new ToolResult(false, "JARVIS Control não respondeu. Abra o módulo e confirme as permissões especiais.");
            }
            return new ToolResult(result.getBoolean("ok", false), result.getString("result", "Sem resposta do JARVIS Control."));
        } catch (SecurityException error) {
            return new ToolResult(false, "JARVIS Control está instalado, mas a ponte segura não foi autorizada. Reinstale os dois APKs desta mesma versão.");
        } catch (Exception error) {
            return new ToolResult(false, "JARVIS Control não está instalado ou não está disponível. Instale e ative o módulo Control.");
        }
    }

'''
assert helper_anchor in tool, "AgentToolExecutor helper anchor not found"
tool = tool.replace(helper_anchor, helper_code + helper_anchor, 1)
tool_path.write_text(tool, encoding="utf-8")

# Generate the separate control APK from the proven service implementations that
# existed in the earlier monolithic build. The main APK itself remains free of the
# restricted Accessibility/NotificationListener declarations.
control_java = Path("control/src/main/java/com/mensagemstudio/jarvis/control")
control_res_xml = Path("control/src/main/res/xml")
control_java.mkdir(parents=True, exist_ok=True)
control_res_xml.mkdir(parents=True, exist_ok=True)

Path("control/build.gradle").write_text('''plugins {
    id 'com.android.application'
}

android {
    namespace 'com.mensagemstudio.jarvis.control'
    compileSdk 36

    defaultConfig {
        applicationId 'com.mensagemstudio.jarvis.control'
        minSdk 26
        targetSdk 36
        versionCode 1
        versionName '1.0.0'
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
''', encoding="utf-8")

for source_name in ["JarvisAccessibilityService.java", "JarvisNotificationListenerService.java"]:
    source = Path("app/src/main/java/com/mensagemstudio/jarvis") / source_name
    copied = source.read_text(encoding="utf-8").replace(
        "package com.mensagemstudio.jarvis;",
        "package com.mensagemstudio.jarvis.control;",
        1,
    )
    (control_java / source_name).write_text(copied, encoding="utf-8")

Path("control/src/main/res/xml/accessibility_service_config.xml").write_text(
    Path("app/src/main/res/xml/accessibility_service_config.xml").read_text(encoding="utf-8"),
    encoding="utf-8",
)

Path("control/src/main/AndroidManifest.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <permission
        android:name="com.mensagemstudio.jarvis.control.permission.BRIDGE"
        android:protectionLevel="signature" />

    <application
        android:allowBackup="false"
        android:label="JARVIS Control"
        android:icon="@android:drawable/ic_menu_manage"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name=".ControlProvider"
            android:authorities="com.mensagemstudio.jarvis.control.bridge"
            android:exported="true"
            android:permission="com.mensagemstudio.jarvis.control.permission.BRIDGE" />

        <service
            android:name=".JarvisAccessibilityService"
            android:label="JARVIS Control — Tela"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name=".JarvisNotificationListenerService"
            android:label="JARVIS Control — Notificações e mídia"
            android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
    </application>
</manifest>
''', encoding="utf-8")

(control_java / "ControlProvider.java").write_text(r'''package com.mensagemstudio.jarvis.control;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Locale;

public class ControlProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        String name = method == null ? "" : method;
        try {
            switch (name) {
                case "read_screen": {
                    if (!JarvisAccessibilityService.isConnected()) return result(false, "Ative JARVIS Control em Acessibilidade.");
                    return result(true, JarvisAccessibilityService.readVisibleText());
                }
                case "click_text": {
                    String text = value(extras, "text");
                    boolean ok = JarvisAccessibilityService.clickText(text);
                    return result(ok, ok ? "Toque executado em “" + text + "”." : "Não encontrei um elemento clicável com “" + text + "”.");
                }
                case "type_text": {
                    String text = value(extras, "text");
                    boolean ok = JarvisAccessibilityService.typeText(text);
                    return result(ok, ok ? "Texto inserido no campo ativo." : "Não encontrei um campo editável na tela atual.");
                }
                case "system_action": {
                    String action = value(extras, "action").toLowerCase(Locale.ROOT);
                    boolean ok = JarvisAccessibilityService.globalAction(action);
                    return result(ok, ok ? "Ação do sistema executada: " + action + "." : "Não consegui executar a ação: " + action + ".");
                }
                case "list_notifications": {
                    if (!JarvisNotificationListenerService.isConnected()) return result(false, "Ative o acesso às notificações do JARVIS Control.");
                    return result(true, JarvisNotificationListenerService.listActiveNotifications());
                }
                case "open_notification": {
                    String query = value(extras, "query");
                    boolean ok = JarvisNotificationListenerService.openMatchingNotification(query);
                    return result(ok, ok ? "Notificação aberta." : "Não encontrei ou não consegui abrir essa notificação.");
                }
                case "media_play":
                    return media("play", "Reprodução iniciada.");
                case "media_pause":
                    return media("pause", "Reprodução pausada.");
                case "media_next":
                    return media("next", "Próxima faixa.");
                case "media_previous":
                    return media("previous", "Faixa anterior.");
                case "status":
                    return result(true,
                            "Acessibilidade=" + JarvisAccessibilityService.isConnected()
                                    + "; notificações=" + JarvisNotificationListenerService.isConnected());
                default:
                    return result(false, "Comando não reconhecido pelo JARVIS Control: " + name);
            }
        } catch (Exception error) {
            return result(false, "Falha no JARVIS Control: " + error.getMessage());
        }
    }

    private Bundle media(String action, String success) {
        if (!JarvisNotificationListenerService.isConnected()) return result(false, "Ative o acesso às notificações do JARVIS Control.");
        boolean ok = JarvisNotificationListenerService.mediaCommand(getContext(), action);
        return result(ok, ok ? success : "Nenhuma sessão de mídia ativa respondeu ao comando.");
    }

    private static String value(Bundle extras, String key) {
        return extras == null ? "" : extras.getString(key, "");
    }

    private static Bundle result(boolean ok, String text) {
        Bundle out = new Bundle();
        out.putBoolean("ok", ok);
        out.putString("result", text == null ? "" : text);
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
''', encoding="utf-8")

(control_java / "MainActivity.java").write_text(r'''package com.mensagemstudio.jarvis.control;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 40);
        root.setBackgroundColor(Color.rgb(5, 7, 10));

        TextView title = text("JARVIS CONTROL", 30, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView info = text("Módulo local do JARVIS/HELENA para leitura da tela, clique, escrita, notificações e controle de mídia. As autorizações especiais são concedidas manualmente pelo Android.", 15, 0xFFB7C1C7);
        info.setPadding(0, 22, 0, 28);
        root.addView(info);

        status = text("", 14, 0xFF72DFF4);
        status.setPadding(0, 0, 0, 20);
        root.addView(status);

        Button accessibility = button("1 · ATIVAR CONTROLE DE TELA / ACESSIBILIDADE");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button notifications = button("2 · ATIVAR ACESSO ÀS NOTIFICAÇÕES");
        notifications.setOnClickListener(v -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        root.addView(notifications);

        Button openJarvis = button("ABRIR JARVIS / HELENA");
        openJarvis.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.mensagemstudio.jarvis");
            if (launch != null) startActivity(launch);
        });
        root.addView(openJarvis);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean accessibility = JarvisAccessibilityService.isConnected() || secureContains(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(this, JarvisAccessibilityService.class).flattenToString());
        boolean notifications = JarvisNotificationListenerService.isConnected() || secureContains(
                "enabled_notification_listeners",
                new ComponentName(this, JarvisNotificationListenerService.class).flattenToString());
        status.setText("Controle de tela: " + (accessibility ? "ATIVO" : "DESATIVADO")
                + "\nNotificações/mídia: " + (notifications ? "ATIVO" : "DESATIVADO"));
    }

    private boolean secureContains(String key, String component) {
        try {
            String value = Settings.Secure.getString(getContentResolver(), key);
            return value != null && value.contains(component);
        } catch (Exception ignored) {
            return false;
        }
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(0xFF001014);
        button.setBackgroundColor(0xFF49E7FF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 18);
        button.setLayoutParams(params);
        return button;
    }
}
''', encoding="utf-8")

print("Runtime response + ElevenLabs + JARVIS Control bridge applied")
