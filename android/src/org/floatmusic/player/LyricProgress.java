package org.floatmusic.player;

/** Playback snapshots anchor a monotonic visual clock; never drives audio or lyric layout. */
final class LyricProgress {
    private long anchor, duration, sampledAt;
    private int offset;
    private boolean playing;
    void update(long position,int offset,long duration,boolean playing,long now){
        this.anchor=Math.max(0,position);this.offset=offset;this.duration=Math.max(0,duration);this.playing=playing;sampledAt=now;
    }
    long position(long now){
        long value=anchor+(playing?Math.max(0,now-sampledAt):0);
        if(duration>0)value=Math.min(duration,value);
        return value+offset;
    }
    static float fraction(long start,long end,long position){
        if(end<=start)return 1f; // Untimed/final lyrics without a known duration keep a readable highlight.
        return Math.max(0f,Math.min(1f,(float)((double)(position-start)/(end-start))));
    }
    static float lineFraction(float progress,float before,float width,float total){
        return width<=0?0:Math.max(0,Math.min(1,(progress*total-before)/width));
    }
}
