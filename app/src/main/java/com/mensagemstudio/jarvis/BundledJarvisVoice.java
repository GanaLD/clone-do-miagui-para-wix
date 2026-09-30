package com.mensagemstudio.jarvis;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.PlaybackParams;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fully bundled/offline neural voice for the JARVIS identity.
 *
 * The model is packaged inside the APK under assets/jarvis_voice and copied once
 * to internal storage because sherpa-onnx's Java API loads VITS models by path.
 * No remote TTS service and no Android-installed JARVIS voice are required.
 */
public final class BundledJarvisVoice {
    public interface Callback {
        void onComplete();
        void onError(String detail);
    }

    private static final String ASSET_ROOT = "jarvis_voice/vits-piper-pt_BR-faber-medium";
    private static final String MODEL_FILE = "pt_BR-faber-medium.onnx";
    private static final String TOKENS_FILE = "tokens.txt";
    private static final String DATA_DIR = "espeak-ng-data";
    private static final String VERSION_MARKER = ".jarvis-voice-v1";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();

    private volatile OfflineTts tts;
    private volatile AudioTrack activeTrack;
    private volatile boolean closed;

    public BundledJarvisVoice(Context context) {
        this.context = context.getApplicationContext();
    }

    public void speak(String text, Callback callback) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            callback.onComplete();
            return;
        }
        executor.execute(() -> {
            try {
                if (closed) throw new IllegalStateException("motor de voz encerrado");
                OfflineTts engine = ensureEngine();
                // 1.0 keeps articulation natural; the cinematic character is applied
                // during playback using the same low-pitch/slower JARVIS profile.
                GeneratedAudio audio = engine.generate(clean, 0, 1.0f);
                float[] samples = audio.getSamples();
                if (samples == null || samples.length == 0) {
                    throw new IllegalStateException("modelo retornou áudio vazio");
                }
                play(samples, audio.getSampleRate());
                callback.onComplete();
            } catch (Throwable error) {
                callback.onError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        });
    }

    private OfflineTts ensureEngine() throws Exception {
        OfflineTts current = tts;
        if (current != null) return current;
        synchronized (lock) {
            if (tts != null) return tts;
            File root = ensureModelFiles();
            File model = new File(root, MODEL_FILE);
            File tokens = new File(root, TOKENS_FILE);
            File data = new File(root, DATA_DIR);
            if (!model.isFile() || !tokens.isFile() || !data.isDirectory()) {
                throw new IllegalStateException("arquivos da voz JARVIS não foram encontrados no APK");
            }

            OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                    .setModel(model.getAbsolutePath())
                    .setTokens(tokens.getAbsolutePath())
                    .setDataDir(data.getAbsolutePath())
                    .setNoiseScale(0.58f)
                    .setNoiseScaleW(0.72f)
                    .setLengthScale(1.03f)
                    .build();

            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                    .setVits(vits)
                    .setNumThreads(threads)
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();

            OfflineTtsConfig config = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .setMaxNumSentences(2)
                    .setSilenceScale(0.16f)
                    .build();

            tts = new OfflineTts(config);
            return tts;
        }
    }

    private File ensureModelFiles() throws Exception {
        File voiceRoot = new File(context.getFilesDir(), ASSET_ROOT);
        File marker = new File(voiceRoot, VERSION_MARKER);
        if (marker.isFile()) return voiceRoot;

        deleteRecursively(voiceRoot);
        if (!voiceRoot.mkdirs() && !voiceRoot.isDirectory()) {
            throw new IllegalStateException("não consegui preparar armazenamento da voz");
        }
        copyAssetTree(context.getAssets(), ASSET_ROOT, voiceRoot);
        if (!marker.createNewFile() && !marker.isFile()) {
            throw new IllegalStateException("não consegui finalizar a instalação da voz");
        }
        return voiceRoot;
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File outDir) throws Exception {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            File parent = outDir.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream input = assets.open(assetPath); FileOutputStream output = new FileOutputStream(outDir)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            return;
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("não consegui criar pasta da voz: " + outDir.getName());
        }
        for (String child : children) {
            copyAssetTree(assets, assetPath + "/" + child, new File(outDir, child));
        }
    }

    private void play(float[] samples, int sampleRate) {
        stopPlayback();
        int minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        int buffer = Math.max(minBuffer, 16384);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        AudioTrack track = new AudioTrack(
                attributes,
                format,
                buffer,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        activeTrack = track;
        try {
            PlaybackParams params = new PlaybackParams()
                    .allowDefaults()
                    .setPitch(0.84f)
                    .setSpeed(0.96f);
            track.setPlaybackParams(params);
        } catch (Throwable ignored) {
            // Some OEM audio stacks reject custom pitch; the neural model still plays.
        }
        track.play();
        int offset = 0;
        while (offset < samples.length && !closed) {
            int written = track.write(samples, offset, samples.length - offset, AudioTrack.WRITE_BLOCKING);
            if (written <= 0) break;
            offset += written;
        }
        try { track.stop(); } catch (Throwable ignored) {}
        try { track.release(); } catch (Throwable ignored) {}
        if (activeTrack == track) activeTrack = null;
    }

    private void stopPlayback() {
        AudioTrack track = activeTrack;
        activeTrack = null;
        if (track != null) {
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
    }

    public void close() {
        closed = true;
        stopPlayback();
        executor.shutdownNow();
        synchronized (lock) {
            if (tts != null) {
                try { tts.release(); } catch (Throwable ignored) {}
                tts = null;
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        try { file.delete(); } catch (Throwable ignored) {}
    }
}
