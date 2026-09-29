from pathlib import Path

path = Path("app/src/main/java/com/mensagemstudio/jarvis/JarvisService.java")
text = path.read_text(encoding="utf-8")

old = "    private boolean ttsReady;\n"
new = "    private boolean ttsReady;\n    private boolean googleTtsRequested = true;\n"
assert old in text, "ttsReady field not found"
text = text.replace(old, new, 1)

old = "        tts = new TextToSpeech(this, this);\n"
new = "        tts = new TextToSpeech(this, this, \"com.google.android.tts\");\n"
assert old in text, "TTS initialization not found"
text = text.replace(old, new, 1)

old = '''        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false;
            sendStatus("Voz do Android indisponível", "error");
            busy = false;
            scheduleListen(350);
            return;
        }
'''
new = '''        if (status != TextToSpeech.SUCCESS) {
            if (googleTtsRequested) {
                googleTtsRequested = false;
                sendStatus("Voz Google indisponível · usando mecanismo padrão do Android", "tts_fallback");
                try { if (tts != null) tts.shutdown(); } catch (Exception ignored) {}
                tts = new TextToSpeech(this, this);
                return;
            }
            ttsReady = false;
            sendStatus("Voz do Android indisponível", "error");
            busy = false;
            scheduleListen(350);
            return;
        }
'''
assert old in text, "onInit failure block not found"
text = text.replace(old, new, 1)

old = '''                    int score = voice.getQuality();
                    String voiceName = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
                    for (String token : profile.preferredVoiceTokens) {
                        if (voiceName.contains(token.toLowerCase(Locale.ROOT))) score += 10000;
                    }
                    if (best == null || score > bestScore) {
'''
new = '''                    int score = voice.getQuality() * 10;
                    String voiceName = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
                    boolean looksFemale = voiceName.contains("female") || voiceName.contains("femin") || voiceName.contains("mulher");
                    boolean looksMale = (voiceName.contains("male") && !looksFemale) || voiceName.contains("mascul") || voiceName.contains("homem");
                    if (profile == AssistantProfile.HELENA) {
                        if (looksFemale) score += 50000;
                        if (looksMale) score -= 50000;
                    } else {
                        if (looksMale) score += 50000;
                        if (looksFemale) score -= 50000;
                    }
                    if (voice.isNetworkConnectionRequired()) score += 1800;
                    for (String token : profile.preferredVoiceTokens) {
                        if (voiceName.contains(token.toLowerCase(Locale.ROOT))) score += 10000;
                    }
                    if (best == null || score > bestScore) {
'''
assert old in text, "voice scoring block not found"
text = text.replace(old, new, 1)

old = '        sendStatus("Voz pronta · " + profile.name, "tts_ready");\n'
new = '        sendStatus("Voz pronta · " + profile.name + (googleTtsRequested ? " · Google" : " · Android"), "tts_ready");\n'
assert old in text, "tts ready status not found"
text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Google TTS tuning applied")
