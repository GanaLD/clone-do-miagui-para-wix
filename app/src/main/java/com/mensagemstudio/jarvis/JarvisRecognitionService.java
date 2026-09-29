package com.mensagemstudio.jarvis;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

public class JarvisRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        listener.error(SpeechRecognizer.ERROR_CLIENT);
    }

    @Override
    protected void onCancel(Callback listener) {
        // The actual wake-word listener lives in JarvisService.
    }

    @Override
    protected void onStopListening(Callback listener) {
        listener.error(SpeechRecognizer.ERROR_CLIENT);
    }
}
