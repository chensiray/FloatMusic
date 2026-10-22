package org.floatmusic.player;

/** Runs on the host JVM without starting an Activity or changing preferences. */
public final class UiStringsTest {
    public static void main(String[] args) {
        equal(UiStrings.text("显示独立悬浮歌词","en"),"Show floating lyrics");
        equal(UiStrings.text("显示独立悬浮歌词","zh_CN"),"显示独立悬浮歌词");
        equal(UiStrings.text("收藏夹","en"),"Favorites");
        equal(UiStrings.text("音乐播放","en"),"Music playback");
        equal(UiStrings.text("系统操作失败：$1 %2 C:\\Music","en"),"System operation failed: $1 %2 C:\\Music");
        equal(UiStrings.text("我自己的歌单 $1 %2 C:\\Music","en"),"我自己的歌单 $1 %2 C:\\Music");
        equal(UiStrings.text("显示独立悬浮歌词\n收藏夹","en"),"Show floating lyrics\nFavorites");
        equal(UiStrings.text(null,"en"),"");
        for(java.util.Map.Entry<String,String> entry:UiCatalog.EN.entrySet())
            equal(UiStrings.text(entry.getKey(),"en"),entry.getValue());
        System.out.println("PASS bilingual UI catalog, multiline notices and untouched custom metadata");
    }
    private static void equal(String actual,String expected){if(!expected.equals(actual))throw new AssertionError("Expected "+expected+"; got "+actual);}
}
