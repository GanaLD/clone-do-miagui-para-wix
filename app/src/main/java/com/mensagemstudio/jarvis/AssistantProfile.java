package com.mensagemstudio.jarvis;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class AssistantProfile {
    public static final AssistantProfile JARVIS = new AssistantProfile(
            "jarvis",
            "JARVIS",
            "jarvis",
            new String[]{"Sim?", "Estou ouvindo.", "Pois não?"},
            0.91f,
            0.82f,
            new String[]{"male_1", "male_2", "male_3", "masculine", "masculino", "antonio", "antônio", "homem", "male", "natural", "neural", "network"},
            new String[]{
                    "Sim. Estou ouvindo.",
                    "À disposição. Como posso ajudar?",
                    "Sistema pronto. Pode falar."
            }
    );

    public static final AssistantProfile HELENA = new AssistantProfile(
            "helena",
            "HELENA",
            "helena",
            new String[]{"Sim?", "Estou ouvindo.", "Pois não?", "À disposição."},
            0.97f,
            1.02f,
            new String[]{"female_1", "female_2", "female_3", "feminine", "feminino", "francisca", "luciana", "maria", "mulher", "female", "pt-br-x-afs", "natural", "neural", "network"},
            new String[]{
                    "Pois não? Estou ouvindo.",
                    "Claro. Pode falar.",
                    "Encontrei uma inconsistência no arquivo. Não é grave, mas achei melhor avisar antes de fazer qualquer alteração.",
                    "O projeto continua aberto como você deixou. Não alterei a animação.",
                    "Posso continuar daqui."
            }
    );

    public final String id;
    public final String name;
    public final String wakeWord;
    public final String[] greetingVariants;
    public final float speechRate;
    public final float speechPitch;
    public final String[] preferredVoiceTokens;
    public final String[] testPhrases;

    private AssistantProfile(
            String id,
            String name,
            String wakeWord,
            String[] greetingVariants,
            float speechRate,
            float speechPitch,
            String[] preferredVoiceTokens,
            String[] testPhrases
    ) {
        this.id = id;
        this.name = name;
        this.wakeWord = wakeWord;
        this.greetingVariants = greetingVariants;
        this.speechRate = speechRate;
        this.speechPitch = speechPitch;
        this.preferredVoiceTokens = preferredVoiceTokens;
        this.testPhrases = testPhrases;
    }

    public static AssistantProfile fromId(String value) {
        return value != null && "helena".equals(value.trim().toLowerCase(Locale.ROOT)) ? HELENA : JARVIS;
    }

    public String randomGreeting() {
        return randomGreeting("");
    }

    public String randomGreeting(String addressName) {
        String clean = addressName == null ? "" : addressName.trim();
        if (!clean.isEmpty() && ThreadLocalRandom.current().nextInt(4) == 0) {
            return "Sim, " + clean + "?";
        }
        if (greetingVariants.length == 0) return "Estou ouvindo.";
        return greetingVariants[ThreadLocalRandom.current().nextInt(greetingVariants.length)];
    }

    public String randomTestPhrase() {
        if (testPhrases.length == 0) return randomGreeting();
        return testPhrases[ThreadLocalRandom.current().nextInt(testPhrases.length)];
    }
}
