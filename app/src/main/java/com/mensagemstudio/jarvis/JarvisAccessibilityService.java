package com.mensagemstudio.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class JarvisAccessibilityService extends AccessibilityService {
    private static volatile JarvisAccessibilityService instance;

    public static boolean isConnected() {
        return instance != null;
    }

    public static String readVisibleText() {
        JarvisAccessibilityService service = instance;
        if (service == null) return "Acesso de Acessibilidade não está ativado.";
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return "Não consegui ler a janela atual.";

        StringBuilder out = new StringBuilder();
        Set<String> seen = new HashSet<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 500 && out.length() < 8000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            append(out, seen, node.getText());
            append(out, seen, node.getContentDescription());
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        String result = out.toString().trim();
        return result.isEmpty() ? "A tela atual não expôs texto legível ao Android." : result;
    }

    public static boolean clickText(String query) {
        JarvisAccessibilityService service = instance;
        if (service == null || TextUtils.isEmpty(query)) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        String target = normalize(query);
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        AccessibilityNodeInfo partial = null;
        while (!queue.isEmpty() && visited < 600) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            String text = node.getText() == null ? "" : normalize(node.getText().toString());
            String desc = node.getContentDescription() == null ? "" : normalize(node.getContentDescription().toString());
            boolean exact = target.equals(text) || target.equals(desc);
            boolean contains = (!text.isEmpty() && text.contains(target)) || (!desc.isEmpty() && desc.contains(target));
            if (exact && performClick(node)) return true;
            if (partial == null && contains) partial = node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return partial != null && performClick(partial);
    }

    public static boolean typeText(String text) {
        JarvisAccessibilityService service = instance;
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo candidate = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (candidate == null || !candidate.isEditable()) {
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited < 600) {
                AccessibilityNodeInfo node = queue.removeFirst();
                visited++;
                if (node.isEditable()) {
                    candidate = node;
                    break;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
            }
        }
        if (candidate == null) return false;
        candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
        return candidate.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public static boolean globalAction(String action) {
        JarvisAccessibilityService service = instance;
        if (service == null) return false;
        if ("back".equals(action)) return service.performGlobalAction(GLOBAL_ACTION_BACK);
        if ("home".equals(action)) return service.performGlobalAction(GLOBAL_ACTION_HOME);
        if ("recents".equals(action)) return service.performGlobalAction(GLOBAL_ACTION_RECENTS);
        if ("notifications".equals(action)) return service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        if ("quick_settings".equals(action)) return service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        return false;
    }

    private static boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static void append(StringBuilder out, Set<String> seen, CharSequence value) {
        if (value == null) return;
        String clean = value.toString().replaceAll("\\s+", " ").trim();
        if (clean.isEmpty() || clean.length() > 1200 || !seen.add(clean)) return;
        if (out.length() > 0) out.append("\n");
        out.append(clean);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // O agente consulta a árvore apenas quando o usuário pede uma ação.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
