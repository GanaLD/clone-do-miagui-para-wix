package com.mensagemstudio.jarvis;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class JarvisNotificationListenerService extends NotificationListenerService {
    private static volatile JarvisNotificationListenerService instance;

    public static boolean isConnected() {
        return instance != null;
    }

    public static String listActiveNotifications() {
        JarvisNotificationListenerService service = instance;
        if (service == null) return "Acesso às notificações não está ativado.";
        StatusBarNotification[] items;
        try {
            items = service.getActiveNotifications();
        } catch (Exception e) {
            return "Não consegui consultar as notificações agora.";
        }
        if (items == null || items.length == 0) return "Não há notificações ativas.";
        List<StatusBarNotification> list = new ArrayList<>();
        for (StatusBarNotification item : items) list.add(item);
        list.sort(Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (StatusBarNotification item : list) {
            if (count >= 12 || out.length() > 6000) break;
            CharSequence title = item.getNotification().extras.getCharSequence("android.title");
            CharSequence text = item.getNotification().extras.getCharSequence("android.text");
            if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) continue;
            if (out.length() > 0) out.append("\n");
            out.append(item.getPackageName()).append(": ");
            if (title != null) out.append(title);
            if (text != null && text.length() > 0) out.append(" — ").append(text);
            count++;
        }
        return out.length() == 0 ? "Não há notificações com texto legível." : out.toString();
    }

    public static boolean openMatchingNotification(String query) {
        JarvisNotificationListenerService service = instance;
        if (service == null) return false;
        String target = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        try {
            StatusBarNotification[] items = service.getActiveNotifications();
            if (items == null) return false;
            for (StatusBarNotification item : items) {
                CharSequence title = item.getNotification().extras.getCharSequence("android.title");
                CharSequence text = item.getNotification().extras.getCharSequence("android.text");
                String haystack = (item.getPackageName() + " " + String.valueOf(title) + " " + String.valueOf(text)).toLowerCase(Locale.ROOT);
                if (target.isEmpty() || haystack.contains(target)) {
                    PendingIntent pi = item.getNotification().contentIntent;
                    if (pi != null) {
                        pi.send();
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean mediaCommand(Context context, String command) {
        JarvisNotificationListenerService service = instance;
        if (service == null) return false;
        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName listener = new ComponentName(context, JarvisNotificationListenerService.class);
            List<MediaController> controllers = manager.getActiveSessions(listener);
            if (controllers == null || controllers.isEmpty()) return false;
            MediaController controller = controllers.get(0);
            MediaController.TransportControls controls = controller.getTransportControls();
            switch (command) {
                case "play": controls.play(); return true;
                case "pause": controls.pause(); return true;
                case "next": controls.skipToNext(); return true;
                case "previous": controls.skipToPrevious(); return true;
                default: return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
