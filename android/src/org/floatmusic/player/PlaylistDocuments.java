package org.floatmusic.player;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** A user-initiated SAF / clipboard flow. Large documents stay out of Intent extras. */
final class PlaylistDocuments {
    static final int REQUEST = 7104;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static JSONObject pending;
    private static boolean launched = false, waitingClipboard = false;

    static void report(String action, JSONObject args) {
        try { PlayerBridge.event("libraryAction", new JSONObject().put("action", action).put("args", args).toString()); }
        catch (Exception ignored) { }
    }
    private static void reply(JSONObject request, String text, String error, boolean cancelled) {
        try { report("documentResult", new JSONObject().put("token", request.optString("token"))
                .put("text", text).put("error", error).put("cancelled", cancelled)); }
        catch (Exception ignored) { }
    }
    static void start(Context context, String json) {
        JSONObject request;
        try { request = new JSONObject(json); } catch (Exception e) { return; }
        if (pending != null || PlayerBridge.picking) { reply(request, "", "请先完成当前文件操作。", false); return; }
        pending = request; launched = false; waitingClipboard = false;
        try {
            context.startActivity(new Intent(context, PlayerActivity.class).putExtra("libraryDocument", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception e) { pending = null; reply(request, "", "无法打开歌单文件窗口：" + e.getMessage(), false); }
    }
    static void open(PlayerActivity activity) {
        if (pending == null || launched) return;
        launched = true; PlayerBridge.picking = true;
        if (PlaybackService.instance != null) PlaybackService.instance.syncOverlay();
        if (pending.optString("operation").equals("paste")) {
            waitingClipboard = true;
            if (activity.hasWindowFocus()) focused(activity);
            return;
        }
        boolean exporting = pending.optString("operation").equals("export");
        Intent picker = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(exporting ? "application/json" : "*/*");
        if (exporting) picker.putExtra(Intent.EXTRA_TITLE, pending.optString("name", "浮音歌单").replaceAll("[\\\\/:*?\"<>|]", "_") + ".json");
        else picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try { activity.startActivityForResult(picker, REQUEST); }
        catch (Exception e) { finish(activity, "", "无法打开文件选择器：" + e.getMessage(), false); }
    }
    static void focused(PlayerActivity activity) {
        if (!waitingClipboard || pending == null) return;
        waitingClipboard = false;
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard.getPrimaryClip();
            CharSequence value = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
            finish(activity, value == null ? "" : value.toString(), "", false);
        } catch (Exception e) { finish(activity, "", "无法读取剪贴板，请重新复制歌单内容。", false); }
    }
    static boolean cancelPaste(PlayerActivity activity) {
        if (!waitingClipboard) return false;
        finish(activity, "", "", true); return true;
    }
    private static void finish(PlayerActivity activity, String text, String error, boolean cancelled) {
        JSONObject request = pending; pending = null; waitingClipboard = false; launched = false;
        PlayerBridge.picking = false;
        if (request != null) reply(request, text, error, cancelled);
        if (PlaybackService.instance != null) PlaybackService.instance.syncOverlay();
        activity.moveTaskToBack(true);
    }
    static void result(PlayerActivity activity, int result, Intent data) {
        final JSONObject request = pending;
        if (request == null) { PlayerBridge.picking = false; return; }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) { finish(activity, "", "", true); return; }
        final Uri uri = data.getData();
        pending = null; launched = false; waitingClipboard = false; PlayerBridge.picking = false;
        if (PlaybackService.instance != null) PlaybackService.instance.syncOverlay();
        activity.moveTaskToBack(true);
        final Context context = activity.getApplicationContext();
        new Thread(() -> {
            String text = "", error = "";
            try {
                if (request.optString("operation").equals("export")) {
                    byte[] bytes = request.optString("text").getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_BYTES) throw new java.io.IOException("内容超过 8 MiB");
                    try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new java.io.IOException("目标文件不可写");
                        out.write(bytes); out.flush();
                    }
                } else {
                    try (InputStream in = context.getContentResolver().openInputStream(uri);
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        if (in == null) throw new java.io.IOException("文件不可读");
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (out.size() + count > MAX_BYTES) throw new java.io.IOException("歌单文件不能超过 8 MiB");
                            out.write(buffer, 0, count);
                        }
                        text = out.toString(StandardCharsets.UTF_8.name());
                    }
                }
            } catch (Exception e) { error = "歌单文件操作失败：" + e.getMessage(); }
            reply(request, text, error, false);
        }, "playlist-document").start();
    }
    static void copy(Context context, String text) {
        String error = "";
        try { ((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("浮音歌单", text)); }
        catch (Exception e) { error = "复制失败，内容可能过大，请改用文件导出。"; }
        try { report("clipboardResult", new JSONObject().put("error", error)); } catch (Exception ignored) { }
    }
}
