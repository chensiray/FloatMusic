package org.floatmusic.player;

import java.lang.reflect.Method;
import org.json.JSONObject;

/** Real overlay rules; runs without an emulator or network. */
public final class OverlayV1ContractTest {
    public static void main(String[] arguments) throws Exception {
        expect(30, call("searchResultLimit", new JSONObject()), "old state defaults to 30 total results");
        for (int limit : new int[]{10,20,30,50,100})
            expect(limit, call("searchResultLimit", new JSONObject().put("searchResultLimit",limit)), "supported result count survives a snapshot");
        for (Object invalid : new Object[]{0,-1,15,101,"invalid"})
            expect(30, call("searchResultLimit", new JSONObject().put("searchResultLimit",invalid)), "invalid result settings use the safe default");
        System.out.println("PASS search result count defaults and allowed settings");
        expect("netease", call("rankingSource", new JSONObject()), "legacy state opens Net rankings");
        expect("tencent", call("rankingSource", new JSONObject().put("rankingSource","tencent")), "QQ selection survives snapshots");
        expect("kuwo", call("rankingSource", new JSONObject().put("rankingSource","kuwo")), "Kuwo selection survives snapshots");
        expect("netease", call("rankingSource", new JSONObject().put("rankingSource","search")), "return view must not become a platform");
        System.out.println("PASS ranking platform state and legacy default");
        expect("QQ音乐 · 120 首 · Fixture curator · 每日更新", call("onlineMetadata", new JSONObject()
            .put("id","tencent:ranking:26").put("source","tencent").put("trackCount",120)
            .put("creator","Fixture curator").put("updateFrequency","每日更新")), "online cards expose the platform and all summary metadata");
        expect("酷我音乐 · 9 首", call("onlineMetadata", new JSONObject().put("source","kuwo").put("trackCount",9)), "Kuwo playlist metadata identifies its source");
        expect("网易云", call("onlineMetadata", new JSONObject().put("id","123")), "legacy Net summary has no fabricated count");
        expect("Published source · 0 首", call("onlineMetadata", new JSONObject().put("sourceName","Published source").put("trackCount",0)), "explicit published source and zero count stay visible");
        System.out.println("PASS online summary source, count, creator and frequency");
    }
    private static Object call(String name, JSONObject data) throws Exception {
        Method method;
        try {method=Class.forName("org.floatmusic.player.OverlayWindow").getDeclaredMethod(name,JSONObject.class);}
        catch(NoSuchMethodException absent){throw new AssertionError("Missing overlay behavior: "+name,absent);}
        method.setAccessible(true);return method.invoke(null,data);
    }
    private static void expect(Object expected,Object actual,String message) {
        if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);
    }
}
