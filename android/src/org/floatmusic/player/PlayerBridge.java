package org.floatmusic.player;

import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.lang.ref.WeakReference;
import org.json.JSONObject;

// Java has sole ownership of the Android player; QML polls this immutable snapshot.
public final class PlayerBridge {
    static WeakReference<PlayerActivity> activity = new WeakReference<>(null);
    static final Handler main = new Handler(Looper.getMainLooper());
    static boolean foreground = false, picking = false;
    private static volatile String state = "{\"title\":\"还没有导入音乐\",\"status\":\"选择一首本地音频，开始试听\"}";
    static void publish(JSONObject object) { state = object.toString(); }
    public static String snapshot() { return state; }
    static void idle(String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("title", "还没有导入音乐"); o.put("status", "选择一首本地音频，开始试听"); o.put("error", error);
            PlayerActivity a = activity.get();
            o.put("overlayAllowed", a != null && Settings.canDrawOverlays(a));
            o.put("volume", a == null ? 70 : a.getSharedPreferences("audio", 0).getInt("volume", 70));
            o.put("outputs", new org.json.JSONArray().put(new JSONObject().put("id", "").put("name", "跟随系统默认")));
            o.put("outputName", "跟随系统默认");
            publish(o);
        } catch (Exception ignored) { }
    }
    public static void command(String command, String value) {
        main.post(() -> {
            PlayerActivity a = activity.get();
            PlaybackService service = PlaybackService.instance;
            try {
                if (command.equals("float")) { if (a != null) a.enableOverlay(); return; }
                if (command.equals("pick")) { if (a != null) a.pickMusic(false); return; }
                if (command.equals("theme") && a != null) {
                    a.getSharedPreferences("appearance",0).edit().putBoolean("dark", value.equals("dark")).apply();
                    a.applySystemBarTheme();
                    if (service != null) service.dispatch(command,value);
                    return;
                }
                if (command.equals("import")) { if (a != null) a.send("import", value); return; }
                if (service == null && a != null && command.equals("volume")) {
                    a.getSharedPreferences("audio", 0).edit().putInt("volume", Integer.parseInt(value)).apply(); idle(""); return;
                }
                if (service == null && a != null && (command.equals("outputs") || command.equals("output") || command.equals("queue") || command.equals("track") || command.equals("play"))) { a.send(command, value); return; }
                if (service != null) service.dispatch(command, value);
            } catch (Exception e) {
                if (service != null) service.report("系统操作失败：" + e.getMessage());
                else idle("系统操作失败：" + e.getMessage());
            }
        });
    }
}
