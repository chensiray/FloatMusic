package org.floatmusic.player;

import org.json.JSONObject;

// Exercises the pure policy called by PlaybackService; no Android service or user data.
public final class TrackStateTest {
    static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    static JSONObject track(String source,String id) throws Exception {
        return new JSONObject().put("id",source+":"+id).put("source",source).put("name","Test").put("artist","Artist");
    }
    public static void main(String[] args) throws Exception {
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

        String selected="hires";
        check(TrackState.requestedQuality("netease",selected).equals("hires")&&TrackState.requestedQuality("tencent",selected).equals("standard")
            &&TrackState.requestedQuality("kuwo",selected).equals("standard")&&TrackState.requestedQuality("netease",selected).equals("hires"),
            "cross-platform playback must use ordinary QQ/Kuwo quality and preserve the NetEase selection");
        check(TrackState.requestedQuality("netease","invalid").equals("standard"),"invalid restored NetEase quality must fall back to standard");
        check(TrackState.qualitySelectable("netease")&&!TrackState.qualitySelectable("tencent")&&!TrackState.qualitySelectable("kuwo")
            &&TrackState.qualitySelectable("local"),"QQ and Kuwo must suppress unusable quality controls");
        System.out.println("PASS cross-platform ordinary quality preserves the NetEase selection");

        check(TrackState.online("netease")&&TrackState.online("tencent")&&TrackState.online("kuwo")&&!TrackState.online("local")&&!TrackState.online("unknown"),
            "playback and lyric routing must recognize exactly the supported online platforms");
        check(TrackState.sourceName("netease").equals("网易云")&&TrackState.sourceName("tencent").equals("QQ音乐")
            &&TrackState.sourceName("kuwo").equals("酷我音乐")&&TrackState.sourceName("local").equals("本地"),"playback source labels must follow the loaded track");
        check(TrackState.retryHint("netease").contains("音质")&&!TrackState.retryHint("tencent").contains("音质")&&!TrackState.retryHint("kuwo").contains("音质"),
            "ordinary-quality playback failures must not suggest choosing an unavailable quality");
        System.out.println("PASS source-aware playback labels and recovery hints");
    }
}
