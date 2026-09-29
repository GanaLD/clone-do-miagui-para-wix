package com.mensagemstudio.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

public final class AssistantPreferences {
    private static final String PREFS = "jarvis_settings";
    private static final String KEY_IDENTITY = "assistant_identity";

    private AssistantPreferences() {}

    public static AssistantProfile getProfile(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return AssistantProfile.fromId(prefs.getString(KEY_IDENTITY, AssistantProfile.JARVIS.id));
    }

    public static void setProfile(Context context, AssistantProfile profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_IDENTITY, profile.id)
                .apply();
    }
}
