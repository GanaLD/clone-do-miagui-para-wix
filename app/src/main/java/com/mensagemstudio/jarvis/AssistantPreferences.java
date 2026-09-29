package com.mensagemstudio.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

public final class AssistantPreferences {
    private static final String PREFS = "jarvis_settings";
    private static final String KEY_IDENTITY = "assistant_identity";
    private static final String KEY_ADDRESS_NAME = "assistant_address_name";
    private static final String KEY_BACKGROUND_ENABLED = "assistant_background_enabled";
    private static final String KEY_ASSISTANT_ENABLED = "assistant_enabled";

    private AssistantPreferences() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static AssistantProfile getProfile(Context context) {
        return AssistantProfile.fromId(prefs(context).getString(KEY_IDENTITY, AssistantProfile.JARVIS.id));
    }

    public static void setProfile(Context context, AssistantProfile profile) {
        prefs(context).edit().putString(KEY_IDENTITY, profile.id).apply();
    }

    public static String getAddressName(Context context) {
        return prefs(context).getString(KEY_ADDRESS_NAME, "Gabriel");
    }

    public static void setAddressName(Context context, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > 80) clean = clean.substring(0, 80);
        prefs(context).edit().putString(KEY_ADDRESS_NAME, clean).apply();
    }

    public static boolean isBackgroundEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BACKGROUND_ENABLED, false);
    }

    public static void setBackgroundEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BACKGROUND_ENABLED, enabled).apply();
    }

    public static boolean isAssistantEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ASSISTANT_ENABLED, false);
    }

    public static void setAssistantEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ASSISTANT_ENABLED, enabled).apply();
    }
}
