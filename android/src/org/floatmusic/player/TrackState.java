package org.floatmusic.player;

import org.json.JSONObject;

final class TrackState {
    private TrackState() {}
    static boolean online(String source) {
        return "netease".equals(source)||"tencent".equals(source)||"kuwo".equals(source);
    }
    static JSONObject normalize(JSONObject track) {
        if(track==null)return null;
        String source=track.optString("source"),id=track.optString("id");
        try {
            JSONObject normalized=new JSONObject(track.toString());
            if("local".equals(source))return !id.isEmpty()&&!track.optString("path").isEmpty()?normalized:null;
            if(!online(source)||!id.startsWith(source+":"))return null;
            String song=track.optString("songId");if(song.isEmpty())song=id.substring(source.length()+1);
            if(!AudioResolver.validPlatformId(source,song)||!id.equals(source+":"+song))return null;
            normalized.put("songId",song);return normalized;
        } catch(Exception error){return null;}
    }
    static JSONObject sessionTrack(JSONObject track) {
        JSONObject normalized=normalize(track);if(normalized==null)return null;
        try {
            JSONObject saved=new JSONObject();
            for(String key:new String[]{"id","source","songId","name","artist","sourceName"})
                if(normalized.has(key))saved.put(key,normalized.get(key));
            if("local".equals(normalized.optString("source")))saved.put("path",normalized.get("path"));
            return saved;
        } catch(Exception error){return null;}
    }
    static String requestedQuality(String source,String quality) {
        return "netease".equals(source)&&AudioResolver.validQuality(quality)?quality:"standard";
    }
    static boolean qualitySelectable(String source) {return !online(source)||"netease".equals(source);}
    static String sourceName(String source) {
        if("netease".equals(source))return "网易云";
        if("tencent".equals(source))return "QQ音乐";
        if("kuwo".equals(source))return "酷我音乐";
        return "local".equals(source)?"本地":"";
    }
    static String retryHint(String source) {
        return "netease".equals(source)?"请重试或更换音质。":online(source)?"请重试或选择其他歌曲。":"请重试。";
    }
}
