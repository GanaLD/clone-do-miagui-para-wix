from pathlib import Path

service_path = Path("app/src/main/java/com/mensagemstudio/jarvis/JarvisService.java")
text = service_path.read_text(encoding="utf-8")

# Imports for remote JARVIS audio playback.
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

# Voice endpoint lives beside the GPT endpoint.
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

# Samsung/on-device recognition can advertise support before the pt-BR pack is actually usable.
# Prefer the phone's normal recognition service; it is considerably more reliable for wake words.
text = text.replace("        initRecognizer(true);\n", "        initRecognizer(false);\n", 1)

# Improve result selection: if one of Google's alternate hypotheses contains the wake word,
# use it instead of blindly accepting the first hypothesis.
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

# Surface the actual network failure in the in-app diagnostic instead of silently swallowing it.
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

# JARVIS uses ElevenLabs through the backend. HELENA keeps the female Android/Google voice.
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
                main.post(() -> playJarvisAudio(ready, completedCommand));
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

    private void playJarvisAudio(File file, boolean completedCommand) {
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
                speakLocal(profile.randomGreeting(AssistantPreferences.getAddressName(this)), completedCommand);
                return true;
            });
            remotePlayer.prepare();
            sendStatus("Voz JARVIS · ElevenLabs", "tts_ready");
            remotePlayer.start();
        } catch (Exception error) {
            try { file.delete(); } catch (Exception ignored) {}
            releaseRemotePlayer();
            sendStatus("Falha ao iniciar voz ElevenLabs · usando voz local", "tts_fallback");
            speakLocal(profile.randomGreeting(AssistantPreferences.getAddressName(this)), completedCommand);
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

# Make the central orb useful as a manual recovery path: tapping it restarts listening.
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

print("Runtime response + ElevenLabs playback fixes applied")
