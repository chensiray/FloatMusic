package org.floatmusic.player;

import java.lang.reflect.Method;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Queue occurrence commands, timer input and native status derivation without a device. */
public final class OverlayV12ContractTest {
    public static void main(String[] arguments) throws Exception {
        JSONObject first=entry("a", "netease:101"), repeat=entry("b", "netease:101"), qq=entry("c", "tencent:101");
        JSONArray queue=new JSONArray().put(first).put(repeat).put(qq);
        expect(1,call("queueEntryIndex",new Class<?>[]{JSONArray.class,String.class},queue,"b"),"duplicate song lookup selects its queue occurrence");
        expect(-1,call("queueEntryIndex",new Class<?>[]{JSONArray.class,String.class},queue,"netease:101"),"song identity cannot select one of two occurrences");
        String order=(String)call("queueOrderKey",new Class<?>[]{JSONArray.class},queue);
        JSONObject move=(JSONObject)call("queueMovePayload",new Class<?>[]{JSONArray.class,String.class,int.class,String.class},queue,"b",0,order);
        expect("b",move.getString("entryId"),"reorder dispatch carries the stable occurrence id");
        expect(0,move.getInt("to"),"reorder dispatch carries an absolute native queue index");
        JSONObject metadataChanged=entry("b","netease:101").put("name","New display title");
        JSONArray sameOrder=new JSONArray().put(first).put(metadataChanged).put(qq);
        require(call("queueMovePayload",new Class<?>[]{JSONArray.class,String.class,int.class,String.class},sameOrder,"b",2,order)!=null,"metadata refresh does not discard a valid drag");
        for(int target:new int[]{-1,3})require(call("queueMovePayload",new Class<?>[]{JSONArray.class,String.class,int.class,String.class},queue,"b",target,order)==null,"invalid target does not dispatch a move");
        JSONArray changed=new JSONArray().put(repeat).put(first).put(qq);
        require(call("queueMovePayload",new Class<?>[]{JSONArray.class,String.class,int.class,String.class},changed,"b",2,order)==null,"an externally reordered queue invalidates a captured drag");
        require(call("queueMovePayload",new Class<?>[]{JSONArray.class,String.class,int.class,String.class},queue,"missing",0,order)==null,"removed occurrence cannot dispatch a stale move");
        System.out.println("PASS duplicate occurrences and stale-safe queue reorder commands");

        JSONObject payload=(JSONObject)call("enqueuePayload",new Class<?>[]{JSONObject.class,String.class},repeat,"next");
        expect("next",payload.getString("placement"),"next action is an explicit queue placement");
        JSONObject track=payload.getJSONObject("track");expect("netease:101",track.getString("id"),"enqueue preserves full source identity");
        require(!track.has("queueId")&&!track.has("playedAtMs"),"a re-added track cannot reuse occurrence or history metadata");
        track.put("name","Edited clone");expect("Fixture song",repeat.getString("name"),"command payload must not mutate the snapshot");
        JSONObject tail=(JSONObject)call("enqueuePayload",new Class<?>[]{JSONObject.class,String.class},qq,"tail");
        expect("tencent:101",tail.getJSONObject("track").getString("id"),"QQ identity stays separate from NetEase with the same numeric id");
        require(call("enqueuePayload",new Class<?>[]{JSONObject.class,String.class},qq,"now")==null,"unsupported placement cannot become an accidental immediate-play action");
        System.out.println("PASS enqueue payload identity, placement and snapshot isolation");

        for(String invalid:new String[]{"","0","181","-1","1.5","NaN","1e2","999999999999999999999"})expect(-1,call("sleepMinutes",new Class<?>[]{String.class},invalid),"custom sleep minutes reject malformed or out-of-range values");
        expect(1,call("sleepMinutes",new Class<?>[]{String.class},"1"),"minimum custom timer");
        expect(180,call("sleepMinutes",new Class<?>[]{String.class}," 180 "),"maximum custom timer and surrounding whitespace");
        expect("",status(new JSONObject().put("sleepMode","off").put("sleepText","Old timer")),"off mode hides a stale status");
        expect("播完本首停止",status(new JSONObject().put("sleepMode","endOfTrack")),"end-of-track has a readable fallback");
        expect("还剩 1:01",status(new JSONObject().put("sleepMode","countdown").put("sleepRemaining",61)),"countdown fallback uses native remaining seconds");
        expect("剩余 15 分钟",status(new JSONObject().put("sleepMode","countdown").put("sleepText","剩余 15 分钟")),"published native status takes priority");
        System.out.println("PASS custom timer bounds and authoritative sleep status");

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        String history=(String)call("historySubtitle",new Class<?>[]{JSONObject.class},qq.put("playedAtMs",1704067200000L));
        require(history.contains("QQ音乐")&&history.contains("01-01 08:00"),"history exposes source and actual-play wall-clock time");
        System.out.println("PASS history source and played time captions");
    }
    private static JSONObject entry(String occurrence,String identity) throws Exception {
        return new JSONObject().put("queueId",occurrence).put("id",identity).put("source",identity.substring(0,identity.indexOf(':')))
            .put("songId",identity.substring(identity.indexOf(':')+1)).put("name","Fixture song").put("artist","Fixture artist").put("playedAtMs",1L);
    }
    private static String status(JSONObject state) throws Exception{return (String)call("sleepStatus",new Class<?>[]{JSONObject.class},state);}
    private static Object call(String name,Class<?>[] types,Object... values) throws Exception {
        Method method;
        try{method=Class.forName("org.floatmusic.player.OverlayWindow").getDeclaredMethod(name,types);}
        catch(NoSuchMethodException absent){throw new AssertionError("Missing overlay behavior: "+name,absent);}
        method.setAccessible(true);return method.invoke(null,values);
    }
    private static void expect(Object expected,Object actual,String message){if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
