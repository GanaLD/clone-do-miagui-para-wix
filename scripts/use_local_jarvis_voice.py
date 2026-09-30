from pathlib import Path
import re

path = Path("app/src/main/java/com/mensagemstudio/jarvis/JarvisService.java")
text = path.read_text(encoding="utf-8")

# runtime_response_fix.py historically injects remote ElevenLabs playback. Strip it
# completely and wire both identities to neural models packaged inside the APK.
text = text.replace("import android.media.MediaPlayer;\n", "")
text = text.replace("import java.io.File;\n", "")
text = text.replace("import java.io.FileOutputStream;\n", "")
text = text.replace('    private static final String SPEECH_BACKEND = "https://jarvis-gpt-mobile.floot.app/_api/speech";\n', "")
text = text.replace("    private MediaPlayer remotePlayer;\n", "")

if "private BundledJarvisVoice jarvisVoice;" not in text:
    text = text.replace(
        "    private TextToSpeech tts;\n",
        "    private TextToSpeech tts;\n    private BundledJarvisVoice jarvisVoice;\n    private BundledHelenaVoice helenaVoice;\n",
        1,
    )
elif "private BundledHelenaVoice helenaVoice;" not in text:
    text = text.replace(
        "    private BundledJarvisVoice jarvisVoice;\n",
        "    private BundledJarvisVoice jarvisVoice;\n    private BundledHelenaVoice helenaVoice;\n",
        1,
    )

init_anchor = "        toolExecutor = new AgentToolExecutor(this);\n"
if "jarvisVoice = new BundledJarvisVoice(this);" not in text:
    if init_anchor not in text:
        raise SystemExit("JarvisService init anchor not found")
    text = text.replace(
        init_anchor,
        init_anchor + "        jarvisVoice = new BundledJarvisVoice(this);\n        helenaVoice = new BundledHelenaVoice(this);\n",
        1,
    )
elif "helenaVoice = new BundledHelenaVoice(this);" not in text:
    text = text.replace(
        "        jarvisVoice = new BundledJarvisVoice(this);\n",
        "        jarvisVoice = new BundledJarvisVoice(this);\n        helenaVoice = new BundledHelenaVoice(this);\n",
        1,
    )

remote_branch = '''        if (profile == AssistantProfile.JARVIS) {
            speakJarvisRemote(text, completedCommand);
            return;
        }
        speakLocal(text, completedCommand);
'''
bundled_branch = '''        if (profile == AssistantProfile.JARVIS) {
            speakJarvisBundled(text, completedCommand);
            return;
        }
        if (profile == AssistantProfile.HELENA) {
            speakHelenaBundled(text, completedCommand);
            return;
        }
        speakLocal(text, completedCommand);
'''
if remote_branch not in text:
    raise SystemExit("Remote JARVIS branch not found")
text = text.replace(remote_branch, bundled_branch, 1)

# Delete all remote-network voice methods and keep the local speakNow method only
# as an emergency fallback.
text, count = re.subn(
    r'\n    private void speakJarvisRemote\(String text, boolean completedCommand\) \{.*?\n    private void speakNow\(String text, boolean completedCommand\) \{',
    '\n    private void speakNow(String text, boolean completedCommand) {',
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit("Remote JARVIS voice method block not found")
text = text.replace("        releaseRemotePlayer();\n", "")

bundled_methods = '''
    private void speakJarvisBundled(String text, boolean completedCommand) {
        currentSpeechCompletedCommand = completedCommand;
        sendStatus("Voz JARVIS · neural integrada no APK", "tts_loading");
        if (jarvisVoice == null) {
            sendStatus("Motor de voz JARVIS não inicializado · usando fallback local", "tts_fallback");
            speakLocal(text, completedCommand);
            return;
        }
        jarvisVoice.speak(text, new BundledJarvisVoice.Callback() {
            @Override
            public void onComplete() {
                main.post(() -> finishSpeech(completedCommand));
            }

            @Override
            public void onError(String detail) {
                main.post(() -> {
                    sendStatus("Falha na voz JARVIS integrada · " + detail + " · usando fallback local", "tts_fallback");
                    speakLocal(text, completedCommand);
                });
            }
        });
    }

    private void speakHelenaBundled(String text, boolean completedCommand) {
        currentSpeechCompletedCommand = completedCommand;
        sendStatus("Voz HELENA · neural integrada no APK", "tts_loading");
        if (helenaVoice == null) {
            sendStatus("Motor de voz HELENA não inicializado · usando fallback local", "tts_fallback");
            speakLocal(text, completedCommand);
            return;
        }
        helenaVoice.speak(text, new BundledHelenaVoice.Callback() {
            @Override
            public void onComplete() {
                main.post(() -> finishSpeech(completedCommand));
            }

            @Override
            public void onError(String detail) {
                main.post(() -> {
                    sendStatus("Falha na voz HELENA integrada · " + detail + " · usando fallback local", "tts_fallback");
                    speakLocal(text, completedCommand);
                });
            }
        });
    }

'''
speak_now_anchor = "    private void speakNow(String text, boolean completedCommand) {\n"
if speak_now_anchor not in text:
    raise SystemExit("speakNow anchor not found")
text = text.replace(speak_now_anchor, bundled_methods + speak_now_anchor, 1)

shutdown_anchor = '''        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
'''
shutdown_replacement = '''        if (jarvisVoice != null) {
            try { jarvisVoice.close(); } catch (Exception ignored) {}
            jarvisVoice = null;
        }
        if (helenaVoice != null) {
            try { helenaVoice.close(); } catch (Exception ignored) {}
            helenaVoice = null;
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
'''
if shutdown_anchor not in text:
    raise SystemExit("JarvisService shutdown anchor not found")
text = text.replace(shutdown_anchor, shutdown_replacement, 1)

for forbidden in ["ElevenLabs", "SPEECH_BACKEND", "speakJarvisRemote", "downloadJarvisAudio", "remotePlayer"]:
    if forbidden in text:
        raise SystemExit(f"External voice residue still present: {forbidden}")

for required in ["speakJarvisBundled", "BundledJarvisVoice", "speakHelenaBundled", "BundledHelenaVoice"]:
    if required not in text:
        raise SystemExit(f"Bundled voice wiring missing: {required}")

path.write_text(text, encoding="utf-8")
print("JARVIS + HELENA wired to neural voice models bundled inside the APK")
