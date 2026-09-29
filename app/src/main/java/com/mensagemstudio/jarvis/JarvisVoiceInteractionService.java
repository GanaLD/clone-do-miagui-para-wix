package com.mensagemstudio.jarvis;

import android.content.Intent;
import android.os.Build;
import android.service.voice.VoiceInteractionService;

public class JarvisVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        if (AssistantPreferences.isBackgroundEnabled(this) && AssistantPreferences.isAssistantEnabled(this)) {
            startVoiceCore();
        }
    }

    @Override
    public void onPrepareToShowSession(android.os.Bundle args, int flags) {
        super.onPrepareToShowSession(args, flags);
        if (AssistantPreferences.isAssistantEnabled(this)) startVoiceCore();
    }

    @Override
    public void onLaunchVoiceAssistFromKeyguard() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
    }

    @Override
    public void onShutdown() {
        stopService(new Intent(this, JarvisService.class));
        super.onShutdown();
    }

    private void startVoiceCore() {
        Intent intent = new Intent(this, JarvisService.class);
        intent.setAction(JarvisService.ACTION_START);
        intent.putExtra("ui_visible", false);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }
}
