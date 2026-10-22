package org.floatmusic.player;

/** Device-free checks for the actual mobile lyric layout/timing rules. */
public final class FloatingLyricRulesTest {
    public static void main(String[] args) {
        for (int rows : new int[]{1,3,5,7,9}) require(FloatingLyricRules.rows(rows)==rows,"supported odd row count");
        require(FloatingLyricRules.rows(2)==3&&FloatingLyricRules.rows(4)==5,"migrate old even values upward");
        require(FloatingLyricRules.rows(-4)==1&&FloatingLyricRules.rows(100)==9,"bound persisted values");
        float previous=1f;
        for(int distance=1;distance<=4;distance++) {float value=FloatingLyricRules.opacity(distance);require(value<previous&&value>0,"symmetric fade gets weaker outward");previous=value;}
        require(FloatingLyricRules.scale(0,true)>FloatingLyricRules.scale(1,false),"current lyric is larger");
        require(FloatingLyricRules.scale(1,false)>FloatingLyricRules.scale(4,false),"outer context gets smaller");
        require(Math.abs(FloatingLyricRules.visualScale(0)-1.12f)<.001f,"centred lyric keeps the current emphasis");
        require(Math.abs(FloatingLyricRules.visualScale(.001f)-FloatingLyricRules.visualScale(0))<.001f,"emphasis changes continuously across animation frames");
        require(FloatingLyricRules.emphasis(.5f)==.5f&&FloatingLyricRules.emphasis(1)==0,"highlight blends with visual distance");
        long[] times={1000,4000,8000};
        require(FloatingLyricRules.current(times,0)==-1,"before first lyric");
        require(FloatingLyricRules.current(times,4000)==1,"exact timestamp");
        require(FloatingLyricRules.current(times,99999)==2,"seek after final lyric");
        require(FloatingLyricRules.current(new long[0],999)==-1,"empty lyric data");
        for(int rows:new int[]{1,3,5,7,9}) {int step=FloatingLyricRules.rowHeight(96,rows,300);require(step*rows<=300,"large font fits small screen");}
        System.out.println("PASS mobile lyric rows, migration, fade, scale, timing, seek and small-screen fit");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
