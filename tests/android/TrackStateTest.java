package org.floatmusic.player;

import org.json.JSONObject;

// Exercises the pure policy called by PlaybackService; no Android service or user data.
public final class TrackStateTest {
    static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    static JSONObject track(String source,String id) throws Exception {
        return new JSONObject().put("id",source+":"+id).put("source",source).put("name","Test").put("artist","Artist");
    }
    public static void main(String[] args) throws Exception {
        check(TrackState.requestedQuality("tencent","lossless").equals("lossless")
            &&TrackState.requestedQuality("tencent","master").equals("master")
            &&TrackState.requestedQuality("kuwo","exhigh").equals("exhigh"),
            "independent QQ and Kuwo preferences must request their supported higher qualities");
        System.out.println("PASS independent source quality selection");
        JSONObject preferences=TrackState.sourceQualities(new JSONObject().put("tencent","master").put("kuwo","hires"),"hires");
        check(preferences.optString("netease").equals("hires")&&preferences.optString("tencent").equals("master")
            &&preferences.optString("kuwo").equals("standard"),"source preference migration must retain the legacy NetEase quality and validate each platform");
        preferences.put("netease","higher");
        check(TrackState.requestedQuality("tencent",preferences).equals("master")
            &&TrackState.requestedQuality("kuwo",preferences).equals("standard")
            &&TrackState.requestedQuality("netease",preferences).equals("higher"),"changing one source must leave other source requests unchanged");
        check(!TrackState.reloadForQuality("kuwo",track("tencent","002SongMID9"),false)
            &&!TrackState.reloadForQuality("tencent",null,false)
            &&!TrackState.reloadForQuality("tencent",track("tencent","002SongMID9"),true)
            &&TrackState.reloadForQuality("tencent",track("tencent","002SongMID9"),false),
            "quality changes must reload only an active matching platform, never an idle or paused restored session");
        System.out.println("PASS independent preferences and reload policy");
        Object oldPlayer=new Object(),replacementPlayer=new Object();
        TrackState.SeekState seeks=new TrackState.SeekState();
        long initialSeek=seeks.begin(oldPlayer,18000,1,true);
        check(seeks.complete(oldPlayer,oldPlayer,initialSeek,18000,2)==TrackState.SeekState.ACKNOWLEDGED
            &&seeks.pendingFor(oldPlayer)==-1,
            "current player seek must be acknowledged after a quality resolution starts without finishing that newer load");
        long latestSeek=seeks.begin(oldPlayer,55000,2,false);
        check(seeks.complete(oldPlayer,oldPlayer,initialSeek,18000,2)==TrackState.SeekState.IGNORED
            &&seeks.pendingFor(oldPlayer)==55000,"stale seek request must not clear the latest target");
        check(seeks.complete(oldPlayer,oldPlayer,latestSeek,18000,2)==TrackState.SeekState.IGNORED
            &&seeks.pendingFor(oldPlayer)==55000,"old completion delivered to the latest listener must not acknowledge the wrong target");
        check(seeks.complete(oldPlayer,oldPlayer,latestSeek,55020,3)==TrackState.SeekState.ACKNOWLEDGED
            &&seeks.pendingFor(oldPlayer)==-1,"user seek must continue working while a quality replacement resolves");
        check(TrackState.replacementPosition(true,18000,55020)==55020,
            "same-song replacement must follow the current player's latest seek position, not a target captured before resolution");
        long replacingSeek=seeks.begin(replacementPlayer,55020,3,true);
        check(seeks.complete(replacementPlayer,oldPlayer,latestSeek,55020,3)==TrackState.SeekState.IGNORED
            &&seeks.pendingFor(replacementPlayer)==55020,"replaced player completion must never mutate the replacement's seek");
        check(!seeks.waiting(replacementPlayer,replacingSeek,4)&&seeks.waiting(replacementPlayer,replacingSeek,3),
            "old resume deadline must never fail a newer source request");
        check(seeks.complete(replacementPlayer,replacementPlayer,replacingSeek,55020,3)==TrackState.SeekState.RESUME_READY,
            "matching replacement seek must finish that load exactly once");
        check(seeks.complete(replacementPlayer,replacementPlayer,replacingSeek,55020,3)==TrackState.SeekState.IGNORED,
            "duplicate completion must not resume twice");
        long originalResume=seeks.begin(replacementPlayer,55020,4,true);
        check(seeks.resuming(replacementPlayer,4)&&!seeks.resuming(replacementPlayer,5),
            "only the current player's own resume seek may finish its preparation");
        long changedResume=seeks.begin(replacementPlayer,70000,4,seeks.resuming(replacementPlayer,4));
        check(!seeks.waiting(replacementPlayer,originalResume,4)&&seeks.waiting(replacementPlayer,changedResume,4)
            &&seeks.complete(replacementPlayer,replacementPlayer,changedResume,70000,4)==TrackState.SeekState.RESUME_READY,
            "user seek during the replacement's resume must supersede its deadline and still finish the preparation");
        check(!TrackState.qualityPlayIntent(false,true,false,false)&&!TrackState.qualityPlayIntent(false,false,false,false)
            &&TrackState.qualityPlayIntent(true,true,false,false)&&TrackState.qualityPlayIntent(false,false,true,false)
            &&TrackState.qualityPlayIntent(false,false,false,true),
            "quality reload must preserve paused/ended, active loading, playing, and focus-resume intentions");
        System.out.println("PASS seek identity, live quality replacement position and paused/ended intent");
        JSONObject qq=track("tencent","002SongMID9");
        JSONObject normalized=TrackState.normalize(qq);
        check(normalized!=null&&normalized.optString("songId").equals("002SongMID9")&&normalized.optString("id").equals("tencent:002SongMID9"),
            "restored QQ identity must retain its source and recover the missing MID");
        check(!qq.has("songId"),"identity normalization must not mutate the incoming bridge object");
        JSONObject kuwo=TrackState.normalize(track("kuwo","101")),netease=TrackState.normalize(track("netease","101"));
        check(kuwo!=null&&netease!=null&&kuwo.optString("songId").equals("101")&&!kuwo.optString("id").equals(netease.optString("id")),
            "same numeric ids from separate platforms must remain separate tracks");
        System.out.println("PASS online identity normalization and same-id platform separation");

        check(TrackState.normalize(track("tencent","bad-mid"))==null&&TrackState.normalize(track("tencent","中文"))==null,
            "invalid Tencent MID must not be restored or played");
        check(TrackState.normalize(track("kuwo","0"))==null&&TrackState.normalize(track("netease","01"))==null,
            "positive numeric platform ids must not accept zero or leading zeros");
        check(TrackState.normalize(track("unknown","101"))==null&&TrackState.normalize(track("kuwo","101").put("songId","202"))==null
            &&TrackState.normalize(track("kuwo","101").put("source","netease"))==null,
            "source, full id and songId must agree before playing or restoring");
        System.out.println("PASS invalid and conflicting track identities are rejected");

        qq.put("songId","002SongMID9").put("sourceName","QQ音乐").put("url","https://cdn.test/temp?token=secret")
            .put("address","https://cdn.test/temp").put("path","https://cdn.test/temp").put("loadedQuality","hires")
            .put("cookies","secret").put("pic","https://cdn.test/art");
        JSONObject saved=TrackState.sessionTrack(qq);
        check(saved!=null&&saved.length()==6&&saved.optString("id").equals("tencent:002SongMID9")&&saved.optString("sourceName").equals("QQ音乐"),
            "session must retain only stable online identity and display metadata");
        check(!saved.has("url")&&!saved.has("address")&&!saved.has("path")&&!saved.has("cookies")&&!saved.has("loadedQuality")&&!saved.has("pic"),
            "temporary media URLs, account fields and playback quality must not persist in the session");
        JSONObject restored=TrackState.sessionTrack(saved);
        check(qq.has("url")&&restored!=null&&restored.optString("songId").equals("002SongMID9")&&restored.optString("source").equals("tencent"),
            "session filtering must preserve the live object and allow paused restore");
        System.out.println("PASS paused online session whitelist excludes temporary URLs");

        JSONObject local=track("local","import-1").put("path","/app/files/imports/one.flac").put("url","https://cdn.test/temp");
        JSONObject savedLocal=TrackState.sessionTrack(local);
        check(savedLocal!=null&&savedLocal.optString("path").equals("/app/files/imports/one.flac")&&!savedLocal.has("url"),
            "local paused restore must retain its imported file path without unrelated URLs");
        check(TrackState.normalize(track("local","import-2"))==null,"local playback must require an actual stored file path");
        System.out.println("PASS local import path survives session filtering");

        for(String source:new String[]{"tencent","kuwo"}) {
            JSONObject timed=track(source,source.equals("tencent")?"002SongMID9":"101").put("duration",12.5);
            JSONObject roundTrip=TrackState.sessionTrack(new JSONObject(TrackState.sessionTrack(timed).toString()));
            check(roundTrip.optDouble("duration")==12.5,"valid short-song duration must survive session JSON round trip");
            for(Object invalid:new Object[]{0,-2,86401,"not-seconds",Double.POSITIVE_INFINITY}) {
                JSONObject candidate=track(source,source.equals("tencent")?"002SongMID9":"101");
                if(invalid instanceof Double)candidate.put("duration","Infinity");else candidate.put("duration",invalid);
                check(!TrackState.sessionTrack(candidate).has("duration"),"invalid target duration must not survive session filtering");
            }
        }
        System.out.println("PASS target duration survives session JSON round trip");
        check(TrackState.decodedDurationValid("kuwo",12.5,12.5,true)
            &&TrackState.decodedDurationValid("tencent",12.5,12.5,true),"genuine short songs with an independent matching target duration must be accepted");
        check(!TrackState.decodedDurationValid("kuwo",12,200,true)
            &&!TrackState.decodedDurationValid("tencent",12,200,true)
            &&!TrackState.decodedDurationValid("kuwo",12,12,false)
            &&!TrackState.decodedDurationValid("tencent",12,0,false)
            &&!TrackState.decodedDurationValid("kuwo",0,200,true),"mismatched, unverifiable short, and unknown decoded durations must be blocked before autoplay");
        check(TrackState.decodedDurationValid("kuwo",203,200,true)
            &&TrackState.decodedDurationValid("kuwo",25,0,false)
            &&!TrackState.decodedDurationValid("kuwo",211,200,true),"duration tolerance must accept decoder rounding without accepting another song");
        System.out.println("PASS decoded duration validation and genuine short songs");

        String selected="hires";
        check(TrackState.requestedQuality("netease",selected).equals("hires")&&TrackState.requestedQuality("tencent",selected).equals("standard")
            &&TrackState.requestedQuality("kuwo",selected).equals("standard")&&TrackState.requestedQuality("netease",selected).equals("hires"),
            "unsupported NetEase qualities must not spill into QQ/Kuwo requests or alter the NetEase preference");
        check(TrackState.requestedQuality("netease","invalid").equals("standard"),"invalid restored NetEase quality must fall back to standard");
        check(TrackState.qualitySelectable("netease")&&TrackState.qualitySelectable("tencent")&&TrackState.qualitySelectable("kuwo")
            &&TrackState.qualitySelectable("local"),"all supported platforms must allow source quality controls");
        System.out.println("PASS unsupported source quality falls back without changing other preferences");

        check(TrackState.online("netease")&&TrackState.online("tencent")&&TrackState.online("kuwo")&&!TrackState.online("local")&&!TrackState.online("unknown"),
            "playback and lyric routing must recognize exactly the supported online platforms");
        check(TrackState.sourceName("netease").equals("网易云")&&TrackState.sourceName("tencent").equals("QQ音乐")
            &&TrackState.sourceName("kuwo").equals("酷我音乐")&&TrackState.sourceName("local").equals("本地"),"playback source labels must follow the loaded track");
        check(TrackState.retryHint("netease").contains("音质")&&TrackState.retryHint("tencent").contains("音质")&&TrackState.retryHint("kuwo").contains("音质"),
            "online playback failures may suggest a supported lower quality");
        System.out.println("PASS source-aware playback labels and recovery hints");
    }
}
