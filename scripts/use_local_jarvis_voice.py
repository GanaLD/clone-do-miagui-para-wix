from pathlib import Path

path = Path("app/src/main/java/com/mensagemstudio/jarvis/JarvisService.java")
text = path.read_text(encoding="utf-8")

remote_block = '''        if (profile == AssistantProfile.JARVIS) {
            speakJarvisRemote(text, completedCommand);
            return;
        }
        speakLocal(text, completedCommand);
'''
local_block = '''        // JARVIS and HELENA are synthesized by the phone TTS engine using
        // separate persisted profiles. No external voice provider is required.
        speakLocal(text, completedCommand);
'''

if remote_block not in text:
    raise SystemExit("Remote JARVIS voice block not found")

text = text.replace(remote_block, local_block, 1)
text = text.replace(
    'sendStatus("Voz JARVIS · ElevenLabs", "tts_ready");',
    'sendStatus("Voz JARVIS · perfil local", "tts_ready");'
)

path.write_text(text, encoding="utf-8")
print("Integrated local JARVIS voice restored")
