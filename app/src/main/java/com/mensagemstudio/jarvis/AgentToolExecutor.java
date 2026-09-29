package com.mensagemstudio.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AgentToolExecutor {
    private static final String PREFS = "jarvis_agent_tools";
    private static final String KEY_NOTES = "notes";

    private final Context context;

    public AgentToolExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    public ToolResult execute(String name, JSONObject arguments) {
        try {
            switch (name) {
                case "call_phone":
                    return callPhone(arguments.optString("target", ""), true);
                case "dial_phone":
                    return callPhone(arguments.optString("target", ""), false);
                case "create_note":
                    return createNote(arguments.optString("text", ""));
                case "list_notes":
                    return listNotes();
                case "open_app":
                    return openApp(arguments.optString("app", ""));
                case "spotify_play":
                    return spotifyMedia(KeyEvent.KEYCODE_MEDIA_PLAY, "Reprodução iniciada no Spotify.");
                case "spotify_pause":
                    return spotifyMedia(KeyEvent.KEYCODE_MEDIA_PAUSE, "Spotify pausado.");
                case "spotify_next":
                    return spotifyMedia(KeyEvent.KEYCODE_MEDIA_NEXT, "Avançando para a próxima faixa.");
                case "spotify_previous":
                    return spotifyMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Voltando para a faixa anterior.");
                case "spotify_search":
                    return spotifySearch(arguments.optString("query", ""));
                case "get_time":
                    return new ToolResult(true, "Agora são " + new SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR")).format(new Date()) + ".");
                default:
                    return new ToolResult(false, "Ferramenta não implementada no aparelho: " + name);
            }
        } catch (Exception error) {
            return new ToolResult(false, "Falha ao executar " + name + ": " + error.getMessage());
        }
    }

    private ToolResult callPhone(String target, boolean direct) {
        String clean = target == null ? "" : target.trim();
        if (clean.isEmpty()) return new ToolResult(false, "Nenhum contato ou número foi informado.");

        String number = clean.replaceAll("[^0-9+]", "");
        if (number.replaceAll("\\D", "").length() < 6) {
            number = resolveContactNumber(clean);
        }
        if (number == null || number.trim().isEmpty()) {
            return new ToolResult(false, "Não encontrei um número para “" + clean + "”. Dê acesso aos contatos ou informe o número.");
        }

        Uri uri = Uri.parse("tel:" + Uri.encode(number));
        if (direct && context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent call = new Intent(Intent.ACTION_CALL, uri);
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(call);
            return new ToolResult(true, "Ligação iniciada para " + clean + ".");
        }
        Intent dial = new Intent(Intent.ACTION_DIAL, uri);
        dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(dial);
        return new ToolResult(true, direct
                ? "A permissão de ligação direta não está ativa; abri o discador com " + clean + "."
                : "Discador aberto para " + clean + ".");
    }

    private String resolveContactNumber(String name) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        String[] projection = new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
        String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
        String[] args = new String[]{"%" + name + "%"};
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
            }
        }
        return null;
    }

    private ToolResult createNote(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return new ToolResult(false, "A anotação está vazia.");
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray notes;
        try { notes = new JSONArray(prefs.getString(KEY_NOTES, "[]")); }
        catch (Exception ignored) { notes = new JSONArray(); }
        JSONObject note = new JSONObject();
        try {
            note.put("text", clean);
            note.put("createdAt", System.currentTimeMillis());
            notes.put(note);
            while (notes.length() > 100) {
                JSONArray trimmed = new JSONArray();
                for (int i = Math.max(0, notes.length() - 100); i < notes.length(); i++) trimmed.put(notes.get(i));
                notes = trimmed;
            }
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY_NOTES, notes.toString()).apply();
        return new ToolResult(true, "Anotação salva: " + clean);
    }

    private ToolResult listNotes() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray notes;
        try { notes = new JSONArray(prefs.getString(KEY_NOTES, "[]")); }
        catch (Exception ignored) { notes = new JSONArray(); }
        if (notes.length() == 0) return new ToolResult(true, "Não há anotações salvas.");
        StringBuilder result = new StringBuilder("Anotações recentes: ");
        int start = Math.max(0, notes.length() - 8);
        for (int i = start; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            if (result.length() > 20) result.append(" | ");
            result.append(note.optString("text", ""));
        }
        return new ToolResult(true, result.toString());
    }

    private ToolResult openApp(String app) {
        String key = app == null ? "" : app.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "youtube": return launchPackage("com.google.android.youtube", "https://youtube.com", "YouTube aberto.");
            case "whatsapp": return launchPackage("com.whatsapp", "https://wa.me", "WhatsApp aberto.");
            case "spotify": return launchPackage("com.spotify.music", "https://open.spotify.com", "Spotify aberto.");
            case "chatgpt": return launchPackage("com.openai.chatgpt", "https://chatgpt.com", "ChatGPT aberto.");
            case "camera": {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return new ToolResult(true, "Câmera aberta.");
            }
            case "calendar": {
                Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return new ToolResult(true, "Calendário aberto.");
            }
            case "maps": {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return new ToolResult(true, "Mapas aberto.");
            }
            case "settings": {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return new ToolResult(true, "Configurações abertas.");
            }
            default: return new ToolResult(false, "Aplicativo não reconhecido: " + app);
        }
    }

    private ToolResult launchPackage(String packageName, String fallbackUrl, String result) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) launch = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return new ToolResult(true, result);
    }

    private ToolResult spotifySearch(String query) {
        String clean = query == null ? "" : query.trim();
        if (clean.isEmpty()) return new ToolResult(false, "Nenhuma música ou busca foi informada.");
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(clean)));
            intent.setPackage("com.spotify.music");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return new ToolResult(true, "Abri a busca “" + clean + "” no Spotify.");
        } catch (Exception error) {
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(clean)));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            return new ToolResult(true, "Abri a busca “" + clean + "” no Spotify.");
        }
    }

    private ToolResult spotifyMedia(int keyCode, String result) {
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage("com.spotify.music");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
            }
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            long now = android.os.SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            return new ToolResult(true, result);
        } catch (Exception error) {
            return new ToolResult(false, "Não consegui controlar a reprodução do Spotify.");
        }
    }

    public static final class ToolResult {
        public final boolean ok;
        public final String result;
        public ToolResult(boolean ok, String result) {
            this.ok = ok;
            this.result = result;
        }
    }
}
