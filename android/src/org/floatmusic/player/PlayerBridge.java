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
    static boolean qtReady = false;
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> events = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile String ui = "{}";
    static void event(String action, String value) {
        try { if(events.size()<128) events.add(new JSONObject().put("action",action).put("value",value).toString()); }
        catch(Exception ignored) { }
    }
    public static String takeEvents() {
        org.json.JSONArray result = new org.json.JSONArray(); String next;
        while((next=events.poll())!=null) try { result.put(new JSONObject(next)); } catch(Exception ignored) { }
        return result.toString();
    }
    public static void updateUi(String value) {
        ui=value;
        main.post(() -> { if(PlaybackService.instance!=null) PlaybackService.instance.refreshOverlayData(); });
    }
    static JSONObject uiData() { try { return new JSONObject(ui); } catch(Exception e) { return new JSONObject(); } }
    private static volatile String state = "{\"title\":\"还没有导入音乐\",\"status\":\"选择一首本地音频，开始试听\"}";
    static void publish(JSONObject object) { state = object.toString(); }
    public static String snapshot() { return state; }
    public static int themeMode() {
        android.content.Context context = activity.get();
        if(context==null)context=PlaybackService.instance;
        return context==null ? 0 : themeMode(context);
    }
    private static int themeMode(android.content.Context context) {
        android.content.SharedPreferences appearance=context.getSharedPreferences("appearance",0);
        return appearance.getInt("mode",appearance.getBoolean("dark",false)?2:0);
    }
    static boolean isDark(android.content.Context context) {
        int mode=themeMode(context);
        return mode==2 || (mode==0 && (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
    }
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
                if (command.equals("ready")) { qtReady=true; if(a!=null)a.completeOverlayRequest(); return; }
                if (command.equals("float")) { if (a != null) a.enableOverlay(); return; }
                if (command.equals("pick")) { if (a != null) a.pickMusic(true); return; }
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
                // Initial library synchronization must not start playback or its notification.
                // The service reads this queue when the user explicitly opens the overlay.
                if (service == null && a != null && command.equals("queue")) {
                    a.getSharedPreferences("queue",0).edit().putString("data",value).apply(); return;
                }
                if (service == null && a != null && (command.equals("outputs") || command.equals("output") || command.equals("track") || command.equals("play"))) { a.send(command, value); return; }
                if (service != null) service.dispatch(command, value);
            } catch (Exception e) {
                if (service != null) service.report("系统操作失败：" + e.getMessage());
                else idle("系统操作失败：" + e.getMessage());
            }
        });
    }
}
