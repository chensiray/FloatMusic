package org.floatmusic.player;

/** Pure timing checks without an Android runtime. */
public final class LyricProgressTest {
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try { type=Class.forName("org.floatmusic.player.LyricProgress"); }
        catch(ClassNotFoundException absent){throw new AssertionError("Shared lyric progress clock is missing",absent);}
        java.lang.reflect.Method fraction=type.getDeclaredMethod("fraction",long.class,long.class,long.class);fraction.setAccessible(true);
        near((Float)fraction.invoke(null,1000L,5000L,3000L),.5f);
        near((Float)fraction.invoke(null,1000L,5000L,0L),0);
        near((Float)fraction.invoke(null,1000L,5000L,9000L),1);
        near((Float)fraction.invoke(null,6000L,10000L,8000L),.5f);
        near((Float)fraction.invoke(null,6000L,0L,8000L),1);
        Object clock=type.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method update=type.getDeclaredMethod("update",long.class,int.class,long.class,boolean.class,long.class);
        java.lang.reflect.Method position=type.getDeclaredMethod("position",long.class);update.setAccessible(true);position.setAccessible(true);
        update.invoke(clock,3000L,500,10000L,true,100L);equal((Long)position.invoke(clock,350L),3750);
        update.invoke(clock,3250L,500,10000L,false,350L);equal((Long)position.invoke(clock,9999L),3750);
        update.invoke(clock,1000L,-500,10000L,true,1000L);equal((Long)position.invoke(clock,1100L),600);
        update.invoke(clock,9900L,0,10000L,true,1000L);equal((Long)position.invoke(clock,1500L),10000);
        System.out.println("PASS lyric progress timing, pause, seek, offset and final line");
    }
    private static void near(float actual,float expected){if(Math.abs(actual-expected)>.001f)throw new AssertionError(actual+" != "+expected);}
    private static void equal(long actual,long expected){if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
}
