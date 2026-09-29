package com.mensagemstudio.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JarvisService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.mensagemstudio.jarvis.START";
    public static final String ACTION_STOP = "com.mensagemstudio.jarvis.STOP";
    public static final String ACTION_COMMAND = "com.mensagemstudio.jarvis.COMMAND";
    public static final String ACTION_SET_IDENTITY = "com.mensagemstudio.jarvis.SET_IDENTITY";
    public static final String ACTION_SET_BACKGROUND = "com.mensagemstudio.jarvis.SET_BACKGROUND";
    public static final String ACTION_SET_ADDRESS = "com.mensagemstudio.jarvis.SET_ADDRESS";
    public static final String ACTION_UI_VISIBILITY = "com.mensagemstudio.jarvis.UI_VISIBILITY";
    public static final String ACTION_TEST_VOICE = "com.mensagemstudio.jarvis.TEST_VOICE";
    public static final String ACTION_STATUS = "com.mensagemstudio.jarvis.STATUS";

    private static final String CHANNEL_ID = "jarvis_voice";
    private static final int NOTIFICATION_ID = 77;
    private static final String BACKEND = "https://jarvis-gpt-mobile.floot.app/_api/native";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<ConversationTurn> history = new ArrayList<>();
    private final Runnable listenRunnable = this::startListening;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private AssistantProfile profile;
    private AgentToolExecutor toolExecutor;
    private boolean recognizerReady;
    private boolean usingOnDeviceRecognizer;
    private boolean listening;
    private boolean busy;
    private boolean awaitingCommand;
    private boolean destroyed;
    private boolean uiVisible;
    private boolean partialTriggered;
    private boolean ttsReady;
    private String pendingSpeech;
    private boolean pendingSpeechCompletedCommand;
    private boolean currentSpeechCompletedCommand;

    @Override
    public void onCreate() {
        super.onCreate();
        profile = AssistantPreferences.getProfile(this);
        toolExecutor = new AgentToolExecutor(this);
        createChannel();
        Notification notification = buildNotification(profile.name + " inicializando");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        initRecognizer(true);
        tts = new TextToSpeech(this, this);
        sendStatus("Inicializando microfone e voz…", "status");
    }

    private void initRecognizer(boolean preferOnDevice) {
        destroyRecognizer();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recognizerReady = false;
            sendStatus("Permissão de microfone necessária", "error");
            return;
        }
        try {
            if (preferOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                usingOnDeviceRecognizer = true;
            } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                usingOnDeviceRecognizer = false;
            }
            if (recognizer != null) {
                recognizer.setRecognitionListener(this);
                recognizerReady = true;
                sendStatus("Reconhecimento de voz pronto", "microphone_ready");
            } else {
                recognizerReady = false;
                sendStatus("Reconhecimento de voz indisponível neste dispositivo", "error");
            }
        } catch (Throwable error) {
            recognizerReady = false;
            recognizer = null;
            sendStatus("Falha ao iniciar reconhecimento de voz", "error");
        }
    }

    private void destroyRecognizer() {
        listening = false;
        recognizerReady = false;
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                AssistantPreferences.setAssistantEnabled(this, false);
                stopListeningQuietly();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_UI_VISIBILITY.equals(action)) {
                uiVisible = intent.getBooleanExtra("visible", false);
                if (!uiVisible && !AssistantPreferences.isBackgroundEnabled(this)) {
                    stopListeningQuietly();
                    stopSelf();
                    return START_NOT_STICKY;
                }
                scheduleListen(120);
                return START_STICKY;
            }
            if (ACTION_SET_BACKGROUND.equals(action)) {
                boolean enabled = intent.getBooleanExtra("enabled", false);
                AssistantPreferences.setBackgroundEnabled(this, enabled);
                sendStatus("Segundo plano " + (enabled ? "ativado" : "desativado"), "background");
                if (!enabled && !uiVisible) {
                    stopListeningQuietly();
                    stopSelf();
                    return START_NOT_STICKY;
                }
                scheduleListen(120);
                return START_STICKY;
            }
            if (ACTION_SET_ADDRESS.equals(action)) {
                AssistantPreferences.setAddressName(this, intent.getStringExtra("address"));
                sendStatus("Forma de tratamento salva", "address");
                return START_STICKY;
            }
            if (ACTION_SET_IDENTITY.equals(action)) {
                AssistantProfile next = AssistantProfile.fromId(intent.getStringExtra("identity"));
                setIdentity(next);
                scheduleListen(150);
                return START_STICKY;
            }
            if (ACTION_TEST_VOICE.equals(action)) {
                speak(profile.randomTestPhrase(), false);
                return START_STICKY;
            }
            if (ACTION_COMMAND.equals(action)) {
                String command = intent.getStringExtra("command");
                if (command != null && !command.trim().isEmpty()) {
                    processCommand(command.trim());
                    return START_STICKY;
                }
            }
            if (ACTION_START.equals(action)) {
                if (intent.hasExtra("ui_visible")) uiVisible = intent.getBooleanExtra("ui_visible", uiVisible);
                AssistantPreferences.setAssistantEnabled(this, true);
            }
        }
        scheduleListen(220);
        return START_STICKY;
    }

    private boolean shouldListen() {
        return !destroyed
                && AssistantPreferences.isAssistantEnabled(this)
                && (uiVisible || AssistantPreferences.isBackgroundEnabled(this));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(profile.name + " ativo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS / HELENA — Assistente de voz",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantém o assistente disponível para chamadas por voz");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void startListening() {
        if (!shouldListen() || busy || listening || !recognizerReady || recognizer == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendStatus("Permissão de microfone necessária", "error");
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 950L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L);
        partialTriggered = false;
        try {
            recognizer.startListening(intent);
            listening = true;
            String text = awaitingCommand ? "Pode falar o comando…" : "Aguardando “JARVIS” ou “HELENA”…";
            sendStatus(text, "listening");
            updateNotification(awaitingCommand ? "Ouvindo seu comando" : "Aguardando JARVIS ou HELENA");
        } catch (Exception e) {
            listening = false;
            sendStatus("Microfone reiniciando…", "status");
            scheduleListen(900);
        }
    }

    private void stopListeningQuietly() {
        main.removeCallbacks(listenRunnable);
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    private void scheduleListen(long delayMs) {
        main.removeCallbacks(listenRunnable);
        if (!shouldListen() || busy) return;
        main.postDelayed(listenRunnable, delayMs);
    }

    private void handleTranscript(String raw) {
        if (raw == null) return;
        String heard = raw.trim();
        if (heard.isEmpty()) return;
        sendStatus("Você: " + heard, "heard");
        String normalized = normalize(heard);

        if (awaitingCommand) {
            WakeHit repeatedWake = findWake(normalized);
            if (repeatedWake == null || !normalized.equals(repeatedWake.profile.wakeWord)) {
                awaitingCommand = false;
                processCommand(heard);
                return;
            }
            awaitingCommand = false;
        }

        WakeHit wakeHit = findWake(normalized);
        if (wakeHit == null) {
            scheduleListen(250);
            return;
        }

        if (!wakeHit.profile.id.equals(profile.id)) setIdentity(wakeHit.profile);
        String remainder = extractAfterWakeWord(heard, wakeHit.profile.wakeWord);
        if (remainder.isEmpty()) {
            awaitingCommand = true;
            speak(profile.randomGreeting(AssistantPreferences.getAddressName(this)), false);
        } else {
            processCommand(remainder);
        }
    }

    private WakeHit findWake(String normalized) {
        int jarvisIndex = normalized.indexOf("jarvis");
        int helenaIndex = normalized.indexOf("helena");
        if (jarvisIndex < 0 && helenaIndex < 0) return null;
        if (jarvisIndex >= 0 && (helenaIndex < 0 || jarvisIndex <= helenaIndex)) {
            return new WakeHit(AssistantProfile.JARVIS, jarvisIndex);
        }
        return new WakeHit(AssistantProfile.HELENA, helenaIndex);
    }

    private String extractAfterWakeWord(String original, String wakeWord) {
        String lower = original.toLowerCase(Locale.ROOT);
        int rawIdx = lower.indexOf(wakeWord.toLowerCase(Locale.ROOT));
        if (rawIdx < 0) return "";
        String remainder = original.substring(Math.min(original.length(), rawIdx + wakeWord.length())).trim();
        return remainder.replaceFirst("^[\\s,.:;!?\\-]+", "").trim();
    }

    private void processCommand(String command) {
        if (command == null || command.trim().isEmpty()) return;

        String requestedAddress = extractAddressPreference(command);
        if (requestedAddress != null) {
            AssistantPreferences.setAddressName(this, requestedAddress);
            speak("Certo. Vou chamar você de " + requestedAddress + ".", true);
            sendStatus("Forma de tratamento: " + requestedAddress, "address");
            return;
        }

        AssistantProfile requested = requestedIdentity(command);
        if (requested != null) {
            busy = true;
            stopListeningQuietly();
            if (!requested.id.equals(profile.id)) setIdentity(requested);
            speak(profile.randomGreeting(AssistantPreferences.getAddressName(this)), true);
            return;
        }

        busy = true;
        stopListeningQuietly();
        sendStatus("Processando: " + command, "processing");
        updateNotification("Consultando agente GPT");

        JSONArray previousHistory = historyJson();
        executor.submit(() -> {
            String finalReply;
            try {
                finalReply = runAgentLoop(command, previousHistory);
            } catch (Exception e) {
                finalReply = "Não consegui acessar o agente GPT agora. Tente novamente em alguns segundos.";
            }
            final String answer = finalReply;
            main.post(() -> {
                remember("user", command);
                remember("assistant", answer);
                speak(answer, true);
            });
        });
    }

    private String runAgentLoop(String command, JSONArray previousHistory) throws Exception {
        ToolFeedback feedback = null;
        String lastToolResult = "";
        for (int step = 0; step < 3; step++) {
            AgentResponse response = askAssistant(command, previousHistory, feedback);
            if (response.toolName == null || response.toolName.trim().isEmpty()) {
                String reply = response.reply == null ? "" : response.reply.trim();
                if (!reply.isEmpty()) return reply;
                if (!lastToolResult.isEmpty()) return lastToolResult;
                return "Concluído.";
            }

            sendStatus("Executando: " + response.toolName, "tool");
            updateNotification("Executando " + response.toolName);
            AgentToolExecutor.ToolResult result = toolExecutor.execute(response.toolName, response.toolArguments);
            lastToolResult = result.result;
            feedback = new ToolFeedback(response.toolName, result.ok, result.result);
            sendStatus((result.ok ? "Ferramenta concluída: " : "Ferramenta falhou: ") + result.result, result.ok ? "tool_done" : "tool_error");
        }
        return lastToolResult.isEmpty() ? "Não consegui concluir essa ação." : lastToolResult;
    }

    private String extractAddressPreference(String command) {
        String lower = command.toLowerCase(Locale.ROOT).trim();
        String[] prefixes = new String[]{"me chame de ", "pode me chamar de ", "me chama de ", "chame-me de "};
        for (String prefix : prefixes) {
            int index = lower.indexOf(prefix);
            if (index >= 0) {
                String chosen = command.substring(index + prefix.length()).replaceAll("[.!?]+$", "").trim();
                if (!chosen.isEmpty() && chosen.length() <= 80) return chosen;
            }
        }
        return null;
    }

    private AssistantProfile requestedIdentity(String command) {
        String n = normalize(command);
        boolean switchVerb = n.contains("mudar") || n.contains("mude") || n.contains("trocar")
                || n.contains("troque") || n.contains("usar") || n.contains("use")
                || n.contains("chamar") || n.contains("chame") || n.contains("voltar");
        if (!switchVerb) return null;
        if (n.contains("helena")) return AssistantProfile.HELENA;
        if (n.contains("jarvis")) return AssistantProfile.JARVIS;
        return null;
    }

    private void setIdentity(AssistantProfile next) {
        profile = next;
        awaitingCommand = false;
        AssistantPreferences.setProfile(this, next);
        applyTtsProfile();
        sendStatus("Identidade ativa: " + profile.name, "identity");
        updateNotification("Aguardando JARVIS ou HELENA");
    }

    private AgentResponse askAssistant(String command, JSONArray previousHistory, ToolFeedback feedback) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BACKEND).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        JSONObject body = new JSONObject();
        body.put("command", command);
        body.put("identity", profile.id);
        String address = AssistantPreferences.getAddressName(this);
        if (address != null && !address.trim().isEmpty()) body.put("addressName", address.trim());
        body.put("history", previousHistory);
        if (feedback != null) {
            JSONObject toolResult = new JSONObject();
            toolResult.put("name", feedback.name);
            toolResult.put("ok", feedback.ok);
            toolResult.put("result", feedback.result);
            body.put("toolResult", toolResult);
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(bytes);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
        }
        connection.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + sb);

        JSONObject result = new JSONObject(sb.toString());
        String reply = result.optString("reply", "");
        JSONObject tool = result.optJSONObject("tool");
        if (tool == null) return new AgentResponse(reply, null, new JSONObject());
        return new AgentResponse(
                reply,
                tool.optString("name", ""),
                tool.optJSONObject("arguments") == null ? new JSONObject() : tool.optJSONObject("arguments")
        );
    }

    private JSONArray historyJson() {
        JSONArray array = new JSONArray();
        synchronized (history) {
            for (ConversationTurn turn : history) {
                JSONObject item = new JSONObject();
                try {
                    item.put("role", turn.role);
                    item.put("content", turn.content);
                    array.put(item);
                } catch (Exception ignored) {}
            }
        }
        return array;
    }

    private void remember(String role, String content) {
        synchronized (history) {
            history.add(new ConversationTurn(role, content));
            while (history.size() > 10) history.remove(0);
        }
    }

    private String executeLocalAction(String command) {
        String n = normalize(command);
        if (n.contains("que horas") || n.equals("horas") || n.contains("horario")) {
            return "Agora são " + new SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR")).format(new Date()) + ".";
        }
        if (n.contains("abrir") || n.contains("abre") || n.contains("abra")) {
            if (n.contains("youtube")) return launchPackage("com.google.android.youtube", "https://youtube.com", "Abrindo o YouTube.");
            if (n.contains("whatsapp")) return launchPackage("com.whatsapp", "https://wa.me", "Abrindo o WhatsApp.");
            if (n.contains("spotify")) return launchPackage("com.spotify.music", "https://open.spotify.com", "Abrindo o Spotify.");
            if (n.contains("chatgpt") || n.contains("chat gpt")) return launchPackage("com.openai.chatgpt", "https://chatgpt.com", "Abrindo o ChatGPT.");
            if (n.contains("configur")) return launchIntent(new Intent(Settings.ACTION_SETTINGS), "Abrindo as configurações.");
            if (n.contains("bluetooth")) return launchIntent(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Abrindo o Bluetooth.");
            if (n.contains("wifi") || n.contains("wi-fi")) return launchIntent(new Intent(Settings.ACTION_WIFI_SETTINGS), "Abrindo o Wi-Fi.");
            if (n.contains("camera") || n.contains("câmera")) return launchIntent(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Abrindo a câmera.");
            if (n.contains("calendario") || n.contains("calendário") || n.contains("agenda")) {
                Intent cal = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR);
                return launchIntent(cal, "Abrindo o calendário.");
            }
            if (n.contains("mapa") || n.contains("maps")) return launchIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), "Abrindo os mapas.");
            if (n.contains("navegador") || n.contains("chrome")) return launchIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com")), "Abrindo o navegador.");
        }
        return null;
    }

    private String launchPackage(String packageName, String fallbackUrl, String reply) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) launch = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
        return launchIntent(launch, reply);
    }

    private String launchIntent(Intent intent, String reply) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return reply;
        } catch (ActivityNotFoundException | SecurityException e) {
            return "Não encontrei esse aplicativo ou o Android bloqueou a abertura.";
        }
    }

    private void speak(String text, boolean completedCommand) {
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

    private void speakNow(String text, boolean completedCommand) {
        currentSpeechCompletedCommand = completedCommand;
        String utteranceId = profile.id + "-" + System.currentTimeMillis();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        if (result == TextToSpeech.ERROR) finishSpeech(completedCommand);
    }

    private void finishSpeech(boolean completedCommand) {
        busy = false;
        if (completedCommand) awaitingCommand = false;
        updateNotification(awaitingCommand ? "Ouvindo seu comando" : "Aguardando JARVIS ou HELENA");
        scheduleListen(260);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false;
            sendStatus("Voz do Android indisponível", "error");
            busy = false;
            scheduleListen(350);
            return;
        }
        ttsReady = true;
        applyTtsProfile();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onError(String utteranceId) { main.post(() -> finishSpeech(currentSpeechCompletedCommand)); }
            @Override public void onDone(String utteranceId) { main.post(() -> finishSpeech(currentSpeechCompletedCommand)); }
        });
        sendStatus("Voz pronta · " + profile.name, "tts_ready");
        if (pendingSpeech != null) {
            String pending = pendingSpeech;
            boolean completed = pendingSpeechCompletedCommand;
            pendingSpeech = null;
            speakNow(pending, completed);
        } else {
            scheduleListen(200);
        }
    }

    private void applyTtsProfile() {
        if (tts == null || !ttsReady) return;
        Locale ptBr = new Locale("pt", "BR");
        tts.setLanguage(ptBr);
        tts.setSpeechRate(profile.speechRate);
        tts.setPitch(profile.speechPitch);
        if (Build.VERSION.SDK_INT >= 21) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(Build.VERSION.SDK_INT >= 26 ? AudioAttributes.USAGE_ASSISTANT : AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            tts.setAudioAttributes(attrs);
            Voice best = null;
            int bestScore = Integer.MIN_VALUE;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice voice : voices) {
                    Locale locale = voice.getLocale();
                    if (locale == null || !"pt".equalsIgnoreCase(locale.getLanguage()) || !"BR".equalsIgnoreCase(locale.getCountry())) continue;
                    int score = voice.getQuality();
                    String voiceName = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
                    for (String token : profile.preferredVoiceTokens) {
                        if (voiceName.contains(token.toLowerCase(Locale.ROOT))) score += 10000;
                    }
                    if (best == null || score > bestScore) {
                        best = voice;
                        bestScore = score;
                    }
                }
            }
            if (best != null) tts.setVoice(best);
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private void sendStatus(String message, String state) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("message", message);
        i.putExtra("state", state);
        i.putExtra("identity", profile == null ? AssistantProfile.JARVIS.id : profile.id);
        sendBroadcast(i);
    }

    @Override public void onReadyForSpeech(Bundle params) {
        listening = true;
        sendStatus(awaitingCommand ? "Pode falar…" : "Microfone ativo · diga JARVIS ou HELENA", "listening");
    }
    @Override public void onBeginningOfSpeech() { sendStatus("Ouvindo…", "hearing"); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { listening = false; }

    @Override
    public void onError(int error) {
        listening = false;
        if (destroyed || busy) return;
        if (usingOnDeviceRecognizer && Build.VERSION.SDK_INT >= 31
                && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            initRecognizer(false);
            scheduleListen(250);
            return;
        }
        long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1100 : 450;
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            sendStatus("Microfone reiniciando · código " + error, "recognition_restart");
        }
        scheduleListen(delay);
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        if (partialTriggered) {
            partialTriggered = false;
            return;
        }
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) handleTranscript(matches.get(0));
        else scheduleListen(350);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty() || partialTriggered) return;
        String transcript = matches.get(0) == null ? "" : matches.get(0).trim();
        if (transcript.isEmpty()) return;
        sendStatus("Ouvindo: " + transcript, "partial");
        String normalized = normalize(transcript);
        WakeHit hit = findWake(normalized);
        if (hit != null) {
            String remainder = extractAfterWakeWord(transcript, hit.profile.wakeWord);
            if (remainder.split("\\s+").length >= 2) {
                partialTriggered = true;
                stopListeningQuietly();
                handleTranscript(transcript);
            }
        }
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(listenRunnable);
        destroyRecognizer();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class WakeHit {
        final AssistantProfile profile;
        final int index;
        WakeHit(AssistantProfile profile, int index) {
            this.profile = profile;
            this.index = index;
        }
    }

    private static final class ConversationTurn {
        final String role;
        final String content;
        ConversationTurn(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static final class ToolFeedback {
        final String name;
        final boolean ok;
        final String result;
        ToolFeedback(String name, boolean ok, String result) {
            this.name = name;
            this.ok = ok;
            this.result = result;
        }
    }

    private static final class AgentResponse {
        final String reply;
        final String toolName;
        final JSONObject toolArguments;
        AgentResponse(String reply, String toolName, JSONObject toolArguments) {
            this.reply = reply;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
        }
    }
}
