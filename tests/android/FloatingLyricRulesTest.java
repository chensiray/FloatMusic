package org.floatmusic.player;

/** Device-free checks for the actual mobile lyric layout/timing rules. */
public final class FloatingLyricRulesTest {
    public static void main(String[] args) {
        for (int rows : new int[]{1,3,5,7,9}) require(FloatingLyricRules.rows(rows)==rows,"supported odd row count");
        require(FloatingLyricRules.rows(2)==3&&FloatingLyricRules.rows(4)==5,"migrate old even values upward");
        require(FloatingLyricRules.rows(-4)==1&&FloatingLyricRules.rows(100)==9,"bound persisted values");
        for(int rows:new int[]{1,3,5,7,9}){
            near(FloatingLyricRules.opacity(0,rows,80,20),.8f,"current opacity endpoint");
            int edge=rows/2;
            if(edge>0){
                near(FloatingLyricRules.opacity(edge,rows,80,20),.2f,"outermost visible line uses the edge endpoint");
                float previous=.8f;
                for(int distance=1;distance<=edge;distance++){float value=FloatingLyricRules.opacity(distance,rows,80,20);require(value<previous,"fade decreases outward");near(value,FloatingLyricRules.opacity(-distance,rows,80,20),"symmetric fade");previous=value;}
            }
            near(FloatingLyricRules.opacity(edge+1,rows,80,20),0,"offscreen guard fades out");
            require(Math.abs(FloatingLyricRules.opacity(.5f,rows,80,20)-FloatingLyricRules.opacity(.501f,rows,80,20))<.002f,"subpixel transitions are continuous");
        }
        near(FloatingLyricRules.opacity(1,5,80,20),.5f,"intermediate opacity interpolates both endpoints");
        near(FloatingLyricRules.opacity(0,1,60,0),.6f,"single line ignores edge opacity");
        near(FloatingLyricRules.opacity(2,5,80,0),0,"edge may be fully transparent");
        require(FloatingLyricRules.highlightOpacity(-100)==10&&FloatingLyricRules.highlightOpacity(999)==100,"current stays readable for corrupt preferences");
        require(FloatingLyricRules.edgeOpacity(99,40)==40&&FloatingLyricRules.edgeOpacity(-2,40)==0,"edge cannot exceed current");
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
    private static void near(float actual,float expected,String message){require(Math.abs(actual-expected)<.001f,message+": "+actual+" != "+expected);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
