from pathlib import Path

path = Path("app/src/main/java/com/mensagemstudio/jarvis/MainActivity.java")
text = path.read_text(encoding="utf-8")

old = '''        jarvisButton = choiceButton("JARVIS", profile == AssistantProfile.JARVIS);
        helenaButton = choiceButton("HELENA", profile == AssistantProfile.HELENA);
        Button testVoice = choiceButton("TESTAR VOZ", false);
        jarvisButton.setOnClickListener(v -> selectIdentity(AssistantProfile.JARVIS));
        helenaButton.setOnClickListener(v -> selectIdentity(AssistantProfile.HELENA));
        testVoice.setOnClickListener(v -> testVoice());
        identityRow.addView(jarvisButton, weightedButtonParams());
        identityRow.addView(helenaButton, weightedButtonParams());
        identityRow.addView(testVoice, weightedButtonParams());
'''
new = '''        jarvisButton = choiceButton("JARVIS", profile == AssistantProfile.JARVIS);
        helenaButton = choiceButton("HELENA", profile == AssistantProfile.HELENA);
        jarvisButton.setOnClickListener(v -> selectIdentity(AssistantProfile.JARVIS));
        helenaButton.setOnClickListener(v -> selectIdentity(AssistantProfile.HELENA));
        identityRow.addView(jarvisButton, weightedButtonParams());
        identityRow.addView(helenaButton, weightedButtonParams());

        TextView voiceLabel = label("VOZES", 10, 0xFF72DFF4);
        voiceLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        voiceLabel.setPadding(0, dp(12), 0, dp(4));
        identityPanel.addView(voiceLabel);

        LinearLayout voiceRow = new LinearLayout(this);
        voiceRow.setOrientation(LinearLayout.HORIZONTAL);
        identityPanel.addView(voiceRow);
        Button testJarvisVoice = choiceButton("OUVIR JARVIS", false);
        Button testHelenaVoice = choiceButton("OUVIR HELENA", false);
        testJarvisVoice.setOnClickListener(v -> testVoice(AssistantProfile.JARVIS));
        testHelenaVoice.setOnClickListener(v -> testVoice(AssistantProfile.HELENA));
        voiceRow.addView(testJarvisVoice, weightedButtonParams());
        voiceRow.addView(testHelenaVoice, weightedButtonParams());

        TextView voiceHint = label("JARVIS: grave/cinemático · HELENA: feminina Google pt-BR", 10, 0xFF94A3AD);
        voiceHint.setPadding(dp(2), dp(6), dp(2), 0);
        identityPanel.addView(voiceHint);
'''
if old not in text:
    raise SystemExit("identity voice UI anchor not found")
text = text.replace(old, new, 1)

old = '''    private void testVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAndStart();
            return;
        }
        Intent intent = new Intent(this, JarvisService.class);
        intent.setAction(JarvisService.ACTION_TEST_VOICE);
        startServiceCompat(intent);
    }
'''
new = '''    private void testVoice(AssistantProfile target) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAndStart();
            return;
        }
        profile = target;
        AssistantPreferences.setProfile(this, target);
        updateIdentityUi();
        Intent intent = new Intent(this, JarvisService.class);
        intent.setAction(target == AssistantProfile.HELENA
                ? JarvisService.ACTION_TEST_HELENA_VOICE
                : JarvisService.ACTION_TEST_JARVIS_VOICE);
        startServiceCompat(intent);
    }
'''
if old not in text:
    raise SystemExit("testVoice method anchor not found")
text = text.replace(old, new, 1)

old = '''                        || "identity".equals(state) || "address".equals(state)
                        || "error".equals(state))) {
'''
new = '''                        || "identity".equals(state) || "address".equals(state)
                        || "tts_ready".equals(state) || "error".equals(state))) {
'''
if old in text:
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Dual voice UI applied")
