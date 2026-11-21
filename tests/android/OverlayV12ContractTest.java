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
        localizedDynamicStatus();
        allLyricsCopyContent();
        offlineLibraryFiles();
        playlistFilterPositions();
        repeatedQueueJumpPosition();
    }
    private static void offlineLibraryFiles() throws Exception {
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("floatmusic-library-files");
        byte[] audio="RIFF\u0000\u0000\u0000\u0000WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        JSONObject track=entry("a","netease:101");
        String key=OfflineCache.key(track,"lossless");
        java.nio.file.Path cached=directory.resolve(key+".audio"), index=directory.resolve("index.json");
        java.nio.file.Path local=directory.resolve("fixture.wav");
        java.nio.file.Files.write(cached,audio);
        JSONObject cacheEntry=new JSONObject().put("key",key).put("track",track).put("status","ready").put("quality","lossless").put("bytes",audio.length);
        java.nio.file.Files.write(index,new JSONObject().put("entries",new JSONArray().put(cacheEntry)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OfflineCache cache=new OfflineCache(directory.toFile(),(t,q,a)->{throw new AssertionError("file validation does not use the network");},()->true,new OfflineCache.Listener(){public void changed(){}public void lyrics(String id,JSONObject row){}});
        try {
            Method usable=OfflineCache.class.getDeclaredMethod("usableTrackIds");
            expect(java.util.Collections.singleton("netease:101"),usable.invoke(cache),"offline library discovers a valid song independently of quality");
            cache.pin(key,false);cache.action("remove",key);
            require(java.nio.file.Files.exists(cached),"active cache deletion keeps the file until playback releases it");
            expect(java.util.Collections.emptySet(),usable.invoke(cache),"pending-deletion cache cannot mark a library track playable");
            cache.close();
            java.nio.file.Files.write(index,new JSONObject().put("entries",new JSONArray().put(cacheEntry)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cache=new OfflineCache(directory.toFile(),(t,q,a)->{throw new AssertionError("file validation does not use the network");},()->true,new OfflineCache.Listener(){public void changed(){}public void lyrics(String id,JSONObject row){}});
            java.nio.file.Files.write(cached,new byte[audio.length]);
            expect(java.util.Collections.emptySet(),usable.invoke(cache),"offline library rechecks the actual cached file");
            Method localPlayable=PlaybackService.class.getDeclaredMethod("hasPlayableLocalFile",String.class);
            require(!(Boolean)localPlayable.invoke(null,local.toString()),"a missing import is unavailable");
            java.nio.file.Files.write(local,audio);
            require((Boolean)localPlayable.invoke(null,local.toString()),"an available local audio file qualifies");
            java.nio.file.Files.write(local,new byte[audio.length]);
            require(!(Boolean)localPlayable.invoke(null,local.toString()),"a corrupt local file is unavailable");
        } finally {
            cache.close();
            java.nio.file.Files.deleteIfExists(cached);
            java.nio.file.Files.deleteIfExists(local);
            java.nio.file.Files.deleteIfExists(index);
            java.nio.file.Files.deleteIfExists(directory.resolve("index.part"));
            java.nio.file.Files.delete(directory);
        }
        System.out.println("PASS offline library verifies cached files and local audio headers");
    }
    private static void playlistFilterPositions() throws Exception {
        JSONArray tracks=new JSONArray()
            .put(entry("a","netease:101").put("name","Blue Hour").put("artist","Ada"))
            .put(entry("b","tencent:101").put("name","Blue Hour").put("artist","Ada"))
            .put(new JSONObject().put("id","local:fixture.wav").put("source","local").put("name","蓝调").put("artist","Ada"))
            .put(entry("d","netease:102").put("name","Night").put("artist","Lin"));
        java.util.Set<String> offline=new java.util.HashSet<>(java.util.Arrays.asList("tencent:101","local:fixture.wav"));
        Class<?>[] signature={JSONArray.class,String.class,boolean.class,java.util.Set.class};
        expect(java.util.Arrays.asList(0,1),call("playlistIndices",signature,tracks," HOUR   ada ",false,offline),"every term can match the title or artist");
        expect(java.util.Arrays.asList(1),call("playlistIndices",signature,tracks,"hour",true,offline),"offline filtering keeps the original position and platform identity");
        expect(java.util.Arrays.asList(1,2),call("playlistIndices",signature,tracks," ",true,offline),"an empty query shows all playable offline rows");
        expect(java.util.Arrays.asList(2),call("playlistIndices",signature,tracks,"蓝调",false,offline),"playlist searches preserve Unicode text");
        System.out.println("PASS playlist filters retain source positions and platform identities");
    }
    private static void repeatedQueueJumpPosition() throws Exception {
        JSONArray queue=new JSONArray();
        for(int i=0;i<=80;i++)queue.put(entry("entry-"+i,"netease:101"));
        Class<?>[] signature={JSONArray.class,String.class,String.class};
        expect(80,call("currentSongIndex",signature,queue,"queue","entry-80"),"queue jump finds the later occurrence across pages");
        expect(-1,call("currentSongIndex",signature,queue,"queue","netease:101"),"a song ID cannot identify a queue occurrence");
        expect(0,call("currentSongIndex",signature,queue,"tracks","netease:101"),"ordinary song lists locate tracks by song identity");
        System.out.println("PASS queue jump targets the loaded occurrence across page boundaries");
    }
    private static void allLyricsCopyContent() throws Exception {
        JSONObject playback=new JSONObject().put("currentTrack","fixture");
        JSONObject ui=new JSONObject().put("lyricTrack","fixture").put("lyricLines",new JSONArray()
            .put(new JSONObject().put("original","收藏夹 <b>原文</b> 🌙").put("translation","First"))
            .put(new JSONObject().put("original","第二句").put("translation","")));
        Class<?>[] signature={JSONObject.class,JSONObject.class,int.class};
        expect("收藏夹 <b>原文</b> 🌙\n第二句",call("lyricCopyText",signature,playback,ui,0),"copy contains the complete original song");
        expect("First",call("lyricCopyText",signature,playback,ui,1),"missing translations do not copy a system placeholder");
        expect("收藏夹 <b>原文</b> 🌙\nFirst\n第二句",call("lyricCopyText",signature,playback,ui,2),"bilingual copy preserves user text and order");
        ui.put("lyricLines",new JSONArray()).put("lyrics","[AR:艺术家]\n[00:01.00]原文\n[00:02.00]结尾").put("translation","[00:01.00]Translation");
        expect("原文\n结尾\n\nTranslation",call("lyricCopyText",signature,playback,ui,2),"plain copy removes timestamps and metadata");
        playback.put("currentTrack","next");expect("",call("lyricCopyText",signature,playback,ui,2),"stale lyrics from the previous song are not copied");
        System.out.println("PASS whole-song clipboard content, display modes, missing translations and stale-track protection");
    }
    private static void localizedDynamicStatus() throws Exception {
        java.lang.reflect.Field language=UiStrings.class.getDeclaredField("code");language.setAccessible(true);
        Object previous=language.get(null);
        try {
            language.set(null,"en");
            JSONObject search=new JSONObject().put("searchMessage","找到 23 首，可直接点击播放。")
                .put("playlistSearchMessage","找到 7 个歌单");
            expect("Found 23 tracks. Click to play.",call("searchStatus",new Class<?>[]{JSONObject.class,boolean.class},search,true),"completed song searches translate the selected status");
            expect("Found 7 playlists",call("searchStatus",new Class<?>[]{JSONObject.class,boolean.class},search,false),"completed playlist searches translate the selected status");
            search.put("searchMessage","没有找到歌曲。");
            expect("No tracks found.",call("searchStatus",new Class<?>[]{JSONObject.class,boolean.class},search,true),"empty search feedback is translated");
            search.put("searching",true);
            expect("Searching…",call("searchStatus",new Class<?>[]{JSONObject.class,boolean.class},search,true),"loading status takes priority over the previous result");
            JSONObject playback=new JSONObject().put("qualityInfo","QQ音乐 · 请求：标准\n实际：FLAC 16-bit 44.1kHz");
            JSONObject ui=new JSONObject().put("qualityInfo","音质选项用于在线歌曲；本地文件保持原格式。");
            expect("QQ Music · Requested: Standard\nActual: FLAC 16-bit 44.1kHz",call("qualityStatus",new Class<?>[]{JSONObject.class,JSONObject.class},playback,ui),"published quality information is translated after snapshot selection");
            expect("Quality settings apply to online tracks. Local files keep their original format.",call("qualityStatus",new Class<?>[]{JSONObject.class,JSONObject.class},new JSONObject(),ui),"fallback quality information is translated");
            language.set(null,"zh_CN");search.put("searching",false);
            expect("没有找到歌曲。",call("searchStatus",new Class<?>[]{JSONObject.class,boolean.class},search,true),"switching back immediately restores Chinese dynamic feedback");
            expect(playback.getString("qualityInfo"),call("qualityStatus",new Class<?>[]{JSONObject.class,JSONObject.class},playback,ui),"Chinese quality information stays unchanged");
        } finally { language.set(null,previous); }
        System.out.println("PASS native dynamic search and quality feedback follows the active language");
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
