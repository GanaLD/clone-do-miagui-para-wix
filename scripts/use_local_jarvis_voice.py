from pathlib import Path
import re

path = Path("app/src/main/java/com/mensagemstudio/jarvis/JarvisService.java")
text = path.read_text(encoding="utf-8")

# Remove external voice provider imports/fields/constants added by the runtime patch.
text = text.replace("import android.media.MediaPlayer;\n", "")
text = text.replace("import java.io.File;\n", "")
text = text.replace("import java.io.FileOutputStream;\n", "")
text = text.replace('    private static final String SPEECH_BACKEND = "https://jarvis-gpt-mobile.floot.app/_api/speech";\n', "")
text = text.replace("    private MediaPlayer remotePlayer;\n", "")

remote_branch = '''        if (profile == AssistantProfile.JARVIS) {
            speakJarvisRemote(text, completedCommand);
            return;
        }
        speakLocal(text, completedCommand);
'''
local_branch = '''        // Both identities use local Android/Google TTS with separate voice profiles.
        // JARVIS keeps the integrated masculine cinematic profile; HELENA keeps
        // the feminine profile. No external TTS credential or provider is used.
        speakLocal(text, completedCommand);
'''
if remote_branch not in text:
    raise SystemExit("Remote JARVIS branch not found")
text = text.replace(remote_branch, local_branch, 1)

# Remove all now-dead remote audio methods.
text, count = re.subn(
    r'\n    private void speakJarvisRemote\(String text, boolean completedCommand\) \{.*?\n    private void speakNow\(String text, boolean completedCommand\) \{',
    '\n    private void speakNow(String text, boolean completedCommand) {',
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit("Remote JARVIS methods not found")

# Remove the remote player shutdown hook left by the earlier patch.
text = text.replace("        releaseRemotePlayer();\n", "")

if "ElevenLabs" in text or "SPEECH_BACKEND" in text or "speakJarvisRemote" in text:
    raise SystemExit("External JARVIS voice code still present")

path.write_text(text, encoding="utf-8")
print("JARVIS external voice code removed; local integrated profile active")
