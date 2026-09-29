package com.mensagemstudio.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.util.Locale;

public final class DeviceControlTools {
    private DeviceControlTools() {}

    public static AgentToolExecutor.ToolResult tryExecute(Context context, String name, JSONObject args) {
        switch (name) {
            case "read_screen": {
                if (!JarvisAccessibilityService.isConnected()) {
                    return new AgentToolExecutor.ToolResult(false, "Ative Controle de tela/Acessibilidade nas configurações do JARVIS.");
                }
                return new AgentToolExecutor.ToolResult(true, JarvisAccessibilityService.readVisibleText());
            }
            case "click_text": {
                String text = args.optString("text", "").trim();
                boolean ok = JarvisAccessibilityService.clickText(text);
                return new AgentToolExecutor.ToolResult(ok, ok ? "Toque executado em “" + text + "”." : "Não encontrei um elemento clicável com “" + text + "”.");
            }
            case "type_text": {
                String text = args.optString("text", "");
                boolean ok = JarvisAccessibilityService.typeText(text);
                return new AgentToolExecutor.ToolResult(ok, ok ? "Texto inserido no campo ativo." : "Não encontrei um campo de texto editável na tela atual.");
            }
            case "system_action": {
                String action = args.optString("action", "").toLowerCase(Locale.ROOT);
                boolean ok = JarvisAccessibilityService.globalAction(action);
                return new AgentToolExecutor.ToolResult(ok, ok ? "Ação do sistema executada: " + action + "." : "Não consegui executar a ação do sistema: " + action + ".");
            }
            case "list_notifications": {
                String result = JarvisNotificationListenerService.listActiveNotifications();
                boolean ok = !result.startsWith("Acesso às notificações");
                return new AgentToolExecutor.ToolResult(ok, result);
            }
            case "open_notification": {
                String query = args.optString("query", "");
                boolean ok = JarvisNotificationListenerService.openMatchingNotification(query);
                return new AgentToolExecutor.ToolResult(ok, ok ? "Notificação aberta." : "Não encontrei ou não consegui abrir essa notificação.");
            }
            case "media_play": return media(context, "play", "Reprodução iniciada.");
            case "media_pause": return media(context, "pause", "Reprodução pausada.");
            case "media_next": return media(context, "next", "Avançando para a próxima faixa.");
            case "media_previous": return media(context, "previous", "Voltando para a faixa anterior.");
            case "play_local_audio": return playLocalAudio(context, args.optString("query", ""));
            case "open_whatsapp_chat": return openWhatsAppChat(context, args.optString("target", ""), args.optString("text", ""));
            default: return null;
        }
    }

    private static AgentToolExecutor.ToolResult media(Context context, String action, String okMessage) {
        boolean ok = JarvisNotificationListenerService.mediaCommand(context, action);
        return new AgentToolExecutor.ToolResult(ok, ok ? okMessage : "Ative o acesso às notificações para controle direto do player ativo.");
    }

    private static AgentToolExecutor.ToolResult playLocalAudio(Context context, String query) {
        boolean granted;
        if (Build.VERSION.SDK_INT >= 33) {
            granted = context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else {
            granted = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        if (!granted) return new AgentToolExecutor.ToolResult(false, "Dê permissão de músicas e áudio ao JARVIS.");

        String clean = query == null ? "" : query.trim();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST};
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        String[] selectionArgs = null;
        if (!clean.isEmpty()) {
            selection += " AND (" + MediaStore.Audio.Media.TITLE + " LIKE ? OR " + MediaStore.Audio.Media.ARTIST + " LIKE ?)";
            selectionArgs = new String[]{"%" + clean + "%", "%" + clean + "%"};
        }
        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, selectionArgs, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor == null || !cursor.moveToFirst()) return new AgentToolExecutor.ToolResult(false, "Não encontrei essa música no armazenamento local.");
            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
            String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
            Uri contentUri = Uri.withAppendedPath(collection, String.valueOf(id));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "audio/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return new AgentToolExecutor.ToolResult(true, "Abrindo “" + title + "” no player do aparelho.");
        } catch (Exception e) {
            return new AgentToolExecutor.ToolResult(false, "Não consegui abrir a música: " + e.getMessage());
        }
    }

    private static AgentToolExecutor.ToolResult openWhatsAppChat(Context context, String target, String text) {
        String clean = target == null ? "" : target.trim();
        if (clean.isEmpty()) return new AgentToolExecutor.ToolResult(false, "Informe o contato ou número do WhatsApp.");
        String number = clean.replaceAll("[^0-9+]", "");
        if (number.replaceAll("\\D", "").length() < 6) number = resolveContactNumber(context, clean);
        if (number == null || number.replaceAll("\\D", "").length() < 6) {
            return new AgentToolExecutor.ToolResult(false, "Não encontrei o número de “" + clean + "”. Dê acesso aos contatos ou informe o número.");
        }
        String digits = number.replaceAll("\\D", "");
        Uri.Builder builder = Uri.parse("https://wa.me/" + digits).buildUpon();
        if (text != null && !text.trim().isEmpty()) builder.appendQueryParameter("text", text.trim());
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, builder.build());
            intent.setPackage("com.whatsapp");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return new AgentToolExecutor.ToolResult(true, text == null || text.trim().isEmpty()
                    ? "Conversa do WhatsApp aberta para " + clean + "."
                    : "Conversa aberta com a mensagem preenchida para " + clean + ".");
        } catch (Exception e) {
            return new AgentToolExecutor.ToolResult(false, "Não consegui abrir essa conversa no WhatsApp.");
        }
    }

    private static String resolveContactNumber(Context context, String name) {
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
}
