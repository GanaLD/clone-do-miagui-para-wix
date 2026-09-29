package com.mensagemstudio.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.AudioAttributes;
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
import android.net.Uri;

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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JarvisService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.mensagemstudio.jarvis.START";
    public static final String ACTION_COMMAND = "com.mensagemstudio.jarvis.COMMAND";
    public static final String ACTION_STATUS = "com.mensagemstudio.jarvis.STATUS";

    private static final String CHANNEL_ID = "jarvis_voice";
    private static final int NOTIFICATION_ID = 77;
    private static final String BACKEND = "https://jarvis-gpt-mobile.floot.app/_api/native";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean recognizerReady;
    private boolean listening;
    private boolean busy;
    private boolean awaitingCommand;
    private boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("JARVIS aguardando chamada"));

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            recognizerReady = true;
        } else {
            sendStatus("Reconhecimento de voz indisponível neste dispositivo", "status");
        }

        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_COMMAND.equals(action)) {
                String command = intent.getStringExtra("command");
                if (command != null && !command.trim().isEmpty()) {
                    processCommand(command.trim());
                    return START_STICKY;
                }
            }
        }
        scheduleListen(350);
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("JARVIS ativo")
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
                    "JARVIS Voice Assistant",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantém o JARVIS ouvindo em segundo plano");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void startListening() {
        if (destroyed || busy || listening || !recognizerReady || recognizer == null) return;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L);
        try {
            recognizer.startListening(intent);
            listening = true;
            sendStatus(awaitingCommand ? "Pode falar o comando…" : "Aguardando “JARVIS”…", "status");
            updateNotification(awaitingCommand ? "Ouvindo seu comando" : "Aguardando JARVIS");
        } catch (Exception e) {
            listening = false;
            scheduleListen(1200);
        }
    }

    private void stopListeningQuietly() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    private void scheduleListen(long delayMs) {
        if (destroyed) return;
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::startListening, delayMs);
    }

    private void handleTranscript(String raw) {
        if (raw == null) return;
        String heard = raw.trim();
        if (heard.isEmpty()) return;
        sendStatus("Você: " + heard, "heard");

        String normalized = normalize(heard);
        if (awaitingCommand) {
            awaitingCommand = false;
            if (!normalized.equals("jarvis")) {
                processCommand(heard);
                return;
            }
        }

        int idx = normalized.indexOf("jarvis");
        if (idx < 0) {
            scheduleListen(350);
            return;
        }

        String remainder = extractAfterWakeWord(heard);
        if (remainder.isEmpty()) {
            awaitingCommand = true;
            speak("Sim?", false);
        } else {
            processCommand(remainder);
        }
    }

    private String extractAfterWakeWord(String original) {
        String lower = normalize(original);
        int idx = lower.indexOf("jarvis");
        if (idx < 0) return "";

        String originalLower = original.toLowerCase(Locale.ROOT);
        int rawIdx = originalLower.indexOf("jarvis");
        if (rawIdx < 0) return "";
        String remainder = original.substring(Math.min(original.length(), rawIdx + 6)).trim();
        return remainder.replaceFirst("^[\\s,.:;!?\\-]+", "").trim();
    }

    private void processCommand(String command) {
        if (command == null || command.trim().isEmpty()) return;
        busy = true;
        stopListeningQuietly();
        sendStatus("Processando: " + command, "status");
        updateNotification("Processando comando");

        String localReply = executeLocalAction(command);
        if (localReply != null) {
            speak(localReply, true);
            return;
        }

        executor.submit(() -> {
            String reply;
            try {
                reply = askJarvis(command);
            } catch (Exception e) {
                reply = "Não consegui acessar o GPT agora. Tente novamente em alguns segundos.";
            }
            final String answer = reply;
            main.post(() -> speak(answer, true));
        });
    }

    private String askJarvis(String command) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BACKEND).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        JSONObject body = new JSONObject();
        body.put("command", command);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(bytes);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } finally {
            connection.disconnect();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        JSONObject result = new JSONObject(sb.toString());
        return result.optString("reply", "Estou ouvindo.");
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
            if (n.contains("mapa") || n.contains("maps")) {
                Intent map = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
                return launchIntent(map, "Abrindo os mapas.");
            }
            if (n.contains("navegador") || n.contains("chrome")) {
                return launchIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com")), "Abrindo o navegador.");
            }
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
        sendStatus("JARVIS: " + text, "reply");
        updateNotification("JARVIS respondendo");

        if (tts == null) {
            busy = false;
            if (completedCommand) awaitingCommand = false;
            scheduleListen(600);
            return;
        }

        String utteranceId = "jarvis-" + System.currentTimeMillis();
        Bundle params = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Locale ptBr = new Locale("pt", "BR");
            tts.setLanguage(ptBr);
            tts.setSpeechRate(0.96f);
            tts.setPitch(0.92f);
            if (Build.VERSION.SDK_INT >= 21) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                tts.setAudioAttributes(attrs);
                Voice best = null;
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice voice : voices) {
                        Locale l = voice.getLocale();
                        if (l != null && "pt".equalsIgnoreCase(l.getLanguage()) && "BR".equalsIgnoreCase(l.getCountry())) {
                            if (best == null || voice.getQuality() > best.getQuality()) best = voice;
                        }
                    }
                }
                if (best != null) tts.setVoice(best);
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onError(String utteranceId) { onDone(utteranceId); }
                @Override public void onDone(String utteranceId) {
                    main.post(() -> {
                        busy = false;
                        updateNotification(awaitingCommand ? "Ouvindo seu comando" : "Aguardando JARVIS");
                        scheduleListen(350);
                    });
                }
            });
        }
        scheduleListen(300);
    }

    private String normalize(String value) {
        String n = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return n;
    }

    private void sendStatus(String message, String state) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("message", message);
        i.putExtra("state", state);
        sendBroadcast(i);
    }

    @Override public void onReadyForSpeech(Bundle params) { listening = true; }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { listening = false; }

    @Override
    public void onError(int error) {
        listening = false;
        if (!busy && !destroyed) scheduleListen(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200 : 650);
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) handleTranscript(matches.get(0));
        else scheduleListen(500);
    }

    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
