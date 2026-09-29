from pathlib import Path

path = Path("app/src/main/java/com/mensagemstudio/jarvis/JarvisService.java")
text = path.read_text(encoding="utf-8")

# Separate voice-test actions so the UI exposes both voices explicitly.
old = '    public static final String ACTION_TEST_VOICE = "com.mensagemstudio.jarvis.TEST_VOICE";\n'
new = old + '    public static final String ACTION_TEST_JARVIS_VOICE = "com.mensagemstudio.jarvis.TEST_JARVIS_VOICE";\n    public static final String ACTION_TEST_HELENA_VOICE = "com.mensagemstudio.jarvis.TEST_HELENA_VOICE";\n'
assert old in text, "voice action anchor not found"
text = text.replace(old, new, 1)

old = "    private boolean ttsReady;\n"
new = "    private boolean ttsReady;\n    private boolean googleTtsRequested = true;\n    private String selectedVoiceName = \"\";\n"
assert old in text, "ttsReady field not found"
text = text.replace(old, new, 1)

old = "        tts = new TextToSpeech(this, this);\n"
new = "        tts = new TextToSpeech(this, this, \"com.google.android.tts\");\n"
assert old in text, "TTS initialization not found"
text = text.replace(old, new, 1)

old = '''            if (ACTION_TEST_VOICE.equals(action)) {
                speak(profile.randomTestPhrase(), false);
                return START_STICKY;
            }
'''
new = '''            if (ACTION_TEST_VOICE.equals(action)) {
                speak(profile.randomTestPhrase(), false);
                return START_STICKY;
            }
            if (ACTION_TEST_JARVIS_VOICE.equals(action)) {
                setIdentity(AssistantProfile.JARVIS);
                speak(AssistantProfile.JARVIS.randomTestPhrase(), false);
                return START_STICKY;
            }
            if (ACTION_TEST_HELENA_VOICE.equals(action)) {
                setIdentity(AssistantProfile.HELENA);
                speak(AssistantProfile.HELENA.randomTestPhrase(), false);
                return START_STICKY;
            }
'''
assert old in text, "voice test handler not found"
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

old = '''        sendStatus("Voz pronta · " + profile.name, "tts_ready");
'''
new = '''        String engineLabel = googleTtsRequested ? "Google" : "Android";
        String voiceLabel = selectedVoiceName == null || selectedVoiceName.isEmpty() ? "voz padrão" : selectedVoiceName;
        sendStatus("Voz pronta · " + profile.name + " · " + engineLabel + " · " + voiceLabel, "tts_ready");
'''
assert old in text, "tts ready status not found"
text = text.replace(old, new, 1)

old = '''            Voice best = null;
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
'''
new = '''            Voice best = null;
            Voice firstFallback = null;
            Voice secondFallback = null;
            int bestScore = Integer.MIN_VALUE;
            boolean foundGenderMatch = false;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice voice : voices) {
                    Locale locale = voice.getLocale();
                    if (locale == null || !"pt".equalsIgnoreCase(locale.getLanguage()) || !"BR".equalsIgnoreCase(locale.getCountry())) continue;
                    String voiceNameRaw = voice.getName() == null ? "" : voice.getName();
                    String voiceName = voiceNameRaw.toLowerCase(Locale.ROOT);

                    if (firstFallback == null || voiceNameRaw.compareToIgnoreCase(firstFallback.getName()) < 0) {
                        secondFallback = firstFallback;
                        firstFallback = voice;
                    } else if (secondFallback == null || voiceNameRaw.compareToIgnoreCase(secondFallback.getName()) < 0) {
                        secondFallback = voice;
                    }

                    int score = voice.getQuality() * 10;
                    boolean looksFemale = voiceName.contains("female") || voiceName.contains("femin") || voiceName.contains("mulher") || voiceName.contains("female_1") || voiceName.contains("female_2") || voiceName.contains("female_3");
                    boolean looksMale = ((voiceName.contains("male") && !looksFemale) || voiceName.contains("mascul") || voiceName.contains("homem") || voiceName.contains("male_1") || voiceName.contains("male_2") || voiceName.contains("male_3"));
                    boolean thisGenderMatch = profile == AssistantProfile.HELENA ? looksFemale : looksMale;
                    if (thisGenderMatch) {
                        score += 50000;
                        foundGenderMatch = true;
                    }
                    if (profile == AssistantProfile.HELENA && looksMale) score -= 50000;
                    if (profile == AssistantProfile.JARVIS && looksFemale) score -= 50000;
                    if (voice.isNetworkConnectionRequired()) score += 1800;
                    for (String token : profile.preferredVoiceTokens) {
                        if (voiceName.contains(token.toLowerCase(Locale.ROOT))) score += 10000;
                    }
                    if (best == null || score > bestScore) {
                        best = voice;
                        bestScore = score;
                    }
                }
            }

            // Some Google TTS builds hide gender in the voice name. In that case force
            // two different pt-BR voices when at least two are installed.
            if (!foundGenderMatch && firstFallback != null) {
                if (profile == AssistantProfile.JARVIS) {
                    best = firstFallback;
                } else if (secondFallback != null) {
                    best = secondFallback;
                } else {
                    best = firstFallback;
                }
            }
            if (best != null) {
                tts.setVoice(best);
                selectedVoiceName = best.getName() == null ? "" : best.getName();
            } else {
                selectedVoiceName = "";
            }
'''
assert old in text, "voice scoring block not found"
text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Google TTS dual-voice tuning applied")
