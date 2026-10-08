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
            normalized.put("songId",song);
            double duration=durationSeconds(track.opt("duration"));
            if(duration>0)normalized.put("duration",duration);else normalized.remove("duration");
            return normalized;
        } catch(Exception error){return null;}
    }
    static JSONObject sessionTrack(JSONObject track) {
        JSONObject normalized=normalize(track);if(normalized==null)return null;
        try {
            JSONObject saved=new JSONObject();
            for(String key:new String[]{"id","source","songId","name","artist","sourceName"})
                if(normalized.has(key))saved.put(key,normalized.get(key));
            if("local".equals(normalized.optString("source")))saved.put("path",normalized.get("path"));
            if(online(normalized.optString("source"))&&normalized.has("duration"))saved.put("duration",normalized.get("duration"));
            return saved;
        } catch(Exception error){return null;}
    }
    static String requestedQuality(String source,String quality) {
        return AudioResolver.validQuality(source,quality)?quality:"standard";
    }
    static JSONObject sourceQualities(JSONObject saved,String legacyQuality) {
        JSONObject values=new JSONObject();
        try {
            for(String source:new String[]{"netease","tencent","kuwo"}) {
                String quality=saved==null?"":saved.optString(source,"");
                if(quality.isEmpty()&&source.equals("netease"))quality=legacyQuality;
                values.put(source,requestedQuality(source,quality));
            }
        } catch(Exception ignored){}
        return values;
    }
    static String requestedQuality(String source,JSONObject preferences) {
        return requestedQuality(source,preferences==null?"":preferences.optString(source,"standard"));
    }
    static boolean reloadForQuality(String source,JSONObject track,boolean deferred) {
        return !deferred&&online(source)&&track!=null&&source.equals(track.optString("source"));
    }
    static int replacementPosition(boolean followCurrent,int capturedPosition,int currentPosition) {
        return followCurrent?currentPosition:capturedPosition>=0?capturedPosition:0;
    }
    static boolean qualityPlayIntent(boolean loading,boolean requested,boolean playing,boolean resumeOnFocus) {
        return loading?requested:playing||resumeOnFocus;
    }
    // Audio resolution and seek acknowledgment have separate identities. The seek
    // owner stays loaded while a replacement URL/decoder is being prepared.
    static final class SeekState {
        static final int IGNORED=0,ACKNOWLEDGED=1,RESUME_READY=2;
        private Object owner;
        private long sequence;
        private int target=-1,loadGeneration=-1;
        private boolean resumesLoad;
        long begin(Object player,int position,int generation,boolean resume) {
            owner=player;target=Math.max(0,position);loadGeneration=generation;resumesLoad=resume;return ++sequence;
        }
        int pendingFor(Object player) {return owner==player?target:-1;}
        boolean resuming(Object player,int generation) {
            return owner==player&&target>=0&&resumesLoad&&loadGeneration==generation;
        }
        boolean waiting(Object player,long request,int generation) {
            return request==sequence&&resuming(player,generation);
        }
        int complete(Object currentPlayer,Object callbackPlayer,long request,int actualPosition,int generation) {
            if(owner!=currentPlayer||owner!=callbackPlayer||target<0||request!=sequence
                ||actualPosition<0||Math.abs((long)actualPosition-target)>2000)return IGNORED;
            boolean resumes=resumesLoad&&loadGeneration==generation;
            clear();return resumes?RESUME_READY:ACKNOWLEDGED;
        }
        void clear() {owner=null;target=-1;loadGeneration=-1;resumesLoad=false;sequence++;}
    }
    static boolean qualitySelectable(String source) {return true;}
    static double durationSeconds(Object value) {
        try {
            double seconds=value instanceof Number?((Number)value).doubleValue():value instanceof String?Double.parseDouble((String)value):0;
            return !Double.isNaN(seconds)&&!Double.isInfinite(seconds)&&seconds>0&&seconds<=86400?seconds:0;
        } catch(Exception ignored){return 0;}
    }
    static boolean durationMatches(double actual,double expected) {
        return durationSeconds(actual)>0&&durationSeconds(expected)>0&&Math.abs(actual-expected)<=Math.max(3,expected*0.05);
    }
    static boolean decodedDurationValid(String source,double actual,double expected,boolean independentTarget) {
        if(!"tencent".equals(source)&&!"kuwo".equals(source))return true;
        if(durationSeconds(actual)<=0||(!independentTarget&&actual<20))return false;
        return durationSeconds(expected)<=0||durationMatches(actual,expected);
    }
    static String sourceName(String source) {
        if("netease".equals(source))return "网易云";
        if("tencent".equals(source))return "QQ音乐";
        if("kuwo".equals(source))return "酷我音乐";
        return "local".equals(source)?"本地":"";
    }
    static String retryHint(String source) {
        return online(source)?"请重试、更换音质或选择其他歌曲。":"请重试。";
    }
}
