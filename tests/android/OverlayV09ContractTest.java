package org.floatmusic.player;

import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** JVM checks for the real overlay's platform/default rules; no device or online requests. */
public final class OverlayV09ContractTest {
    public static void main(String[] arguments) throws Exception {
        expect("[\"netease\",\"tencent\",\"kuwo\"]", sources(new JSONObject()),
                "an older snapshot defaults to all three song libraries");
        expect("[]", sources(new JSONObject().put("searchSources", new JSONArray())),
                "explicitly clearing every library must stay empty");
        expect("[\"netease\",\"tencent\"]", sources(new JSONObject().put("searchSources",
                new JSONArray().put("tencent").put("netease").put("tencent").put("invalid"))),
                "the outgoing selection has unique supported platforms in stable order");
        System.out.println("PASS song library defaults, empty selection and normalization");

        expect("QQ音乐 · Fixture artist", subtitle(new JSONObject().put("source", "tencent")
                .put("songId", "AbC12xY").put("artist", "Fixture artist")),
                "QQ rows identify their library even without a sourceName field");
        expect("酷我音乐", subtitle(new JSONObject().put("source", "kuwo")),
                "an artist-free row still identifies its library");
        expect("Custom label · Singer", subtitle(new JSONObject().put("source", "netease")
                .put("sourceName", "Custom label").put("artist", "Singer")),
                "a published sourceName takes precedence over the source fallback");
        expect("本地 · Local artist", subtitle(new JSONObject().put("source", "local")
                .put("artist", "Local artist")), "mixed playlists identify local tracks");
        System.out.println("PASS compact row source labels and missing artist fallback");

        JSONObject busy = new JSONObject().put("busy",true).put("qualitySelectable",false)
                .put("sourceQualities",new JSONObject().put("netease","lossless").put("tencent","master").put("kuwo","exhigh"));
        expect("master", quality(busy,new JSONObject().put("quality","hires"),"tencent"),
                "busy playback keeps QQ's independent requested tier visible");
        expect("exhigh", quality(busy,new JSONObject(),"kuwo"), "Kuwo's request is independent of the loaded song");
        expect("hires", quality(new JSONObject(),new JSONObject().put("quality","hires"),"netease"),
                "an old shared choice migrates only to Net");
        System.out.println("PASS independent requested tiers during loading and legacy Net migration");

        JSONObject loading = new JSONObject().put("busy", true).put("currentSourceName", "酷我音乐")
                .put("loadedTrack", new JSONObject().put("source", "tencent"));
        expect("QQ音乐", currentSource(loading, new JSONObject().put("currentSourceName", "酷我音乐")),
                "a pending Kuwo request must not relabel the still-loaded QQ track");
        expect("", currentSource(new JSONObject().put("busy", true), new JSONObject().put("currentSourceName", "QQ音乐")),
                "without a loaded track the source label stays empty during loading");
        System.out.println("PASS loaded track source stays stable while another platform loads");
    }

    private static String sources(JSONObject data) throws Exception {
        return ((JSONArray)call("searchSourceIds", new Class<?>[]{JSONObject.class}, data)).toString();
    }
    private static String subtitle(JSONObject track) throws Exception {
        return (String)call("trackSubtitle", new Class<?>[]{JSONObject.class}, track);
    }
    private static String quality(JSONObject playback, JSONObject ui,String source) throws Exception {
        return (String)call("sourceQuality", new Class<?>[]{JSONObject.class, JSONObject.class,String.class}, playback, ui,source);
    }
    private static String currentSource(JSONObject playback, JSONObject ui) throws Exception {
        return (String)call("currentSourceName", new Class<?>[]{JSONObject.class, JSONObject.class}, playback, ui);
    }
    private static Object call(String name, Class<?>[] parameters, Object... values) throws Exception {
        Method method;
        try { method = Class.forName("org.floatmusic.player.OverlayWindow").getDeclaredMethod(name, parameters); }
        catch (NoSuchMethodException missing) { throw new AssertionError("Missing overlay behavior: " + name, missing); }
        method.setAccessible(true);
        return method.invoke(null, values);
    }
    private static void expect(String expected, String actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
