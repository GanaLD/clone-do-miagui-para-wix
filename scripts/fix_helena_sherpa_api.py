from pathlib import Path

path = Path('app/src/main/java/com/mensagemstudio/jarvis/BundledHelenaVoice.java')
text = path.read_text(encoding='utf-8')
old = '''            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig(
                    model.getAbsolutePath(),
                    "",
                    tokens.getAbsolutePath(),
                    data.getAbsolutePath(),
                    0.60f,
                    0.78f,
                    1.0f,
                    0.2f,
                    "");

            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig(
                    vits,
                    new com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig(),
                    threads,
                    false,
                    "cpu");

            OfflineTtsConfig config = new OfflineTtsConfig(modelConfig, 2, 0.14f);
            tts = new OfflineTts(context.getAssets(), config);
'''
new = '''            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
            vits.setModel(model.getAbsolutePath());
            vits.setTokens(tokens.getAbsolutePath());
            vits.setDataDir(data.getAbsolutePath());
            vits.setNoiseScale(0.60f);
            vits.setNoiseScaleW(0.78f);
            vits.setLengthScale(1.0f);

            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setVits(vits);
            modelConfig.setNumThreads(threads);
            modelConfig.setDebug(false);
            modelConfig.setProvider("cpu");

            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(modelConfig);
            config.setMaxNumSentences(2);
            config.setSilenceScale(0.14f);
            tts = new OfflineTts(null, config);
'''
if old not in text:
    raise SystemExit('HELENA sherpa API anchor not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('HELENA sherpa Android API fixed')
