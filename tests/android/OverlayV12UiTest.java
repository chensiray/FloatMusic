package org.floatmusic.player;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real views and isolated native store; only media-start commands are recorded at the service boundary. */
public final class OverlayV12UiTest extends Instrumentation {
    static final String[] CASES={"more-navigation","queue-occurrences","queue-sort","queue-drag-refresh","history-actions",
        "song-placement","sleep-presets","sleep-draft","notification-volume","small-scaled-scroll","playlist-filter","queue-jump","artist-browser"};
    private OverlayWindow overlay;
    private RecordingService service;
    private Context fixtureContext;
    private String fixturePrefix;
    private File fixtureRoot;
    private final ArrayList<String> fixturePreferenceNames=new ArrayList<>();
    private Object store;
    private boolean screenshots,wasForeground,wasPicking;
    private final StringBuilder report=new StringBuilder();
    private final ArrayList<File> screenshotFiles=new ArrayList<>();
    private Object activeDrag;
    private View dragOrigin;
    private int dragScrollBefore;

    /** Queue policy is exercised for real; no fixture action resolves or starts audio. */
    public static final class RecordingService extends PlaybackService {
        final ArrayList<String> commands=new ArrayList<>(),values=new ArrayList<>();
        @Override void dispatch(String command,String value){
            commands.add(command);values.add(value);
            if(command.equals("playQueueEntry")||command.equals("playHistoryTrack"))return;
            super.dispatch(command,value);
        }
        String last(String name){for(int i=commands.size()-1;i>=0;i--)if(name.equals(commands.get(i)))return values.get(i);return null;}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);screenshots=args!=null&&"true".equals(args.getString("screenshots"));start();}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] failure=new Throwable[1];super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Error)throw (Error)failure[0];if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }
    @Override public void onStart(){
        int passed=0;
        for(String scene:CASES){
            try{
                runOnMainSync(()->prepare(scene));waitForIdleSync();
                runOnMainSync(()->runScene(scene));waitForIdleSync();
                if(scene.equals("queue-drag-refresh"))runOnMainSync(this::finishDrag);
                if(scene.equals("notification-volume"))runOnMainSync(this::verifyNotificationVolume);
                if(scene.equals("small-scaled-scroll")){runOnMainSync(this::forceSmallViewport);waitForIdleSync();runOnMainSync(this::verifySmallScroll);}
                if(scene.equals("queue-jump")||scene.equals("playlist-filter")){waitForDraw();runOnMainSync(()->verifyJump(scene));}
                if(screenshots)capture(scene);
                report.append("PASS ").append(scene).append('\n');passed++;
            }catch(Throwable failure){report.append("FAIL ").append(scene).append(": ").append(failure).append('\n');}
            finally{runOnMainSync(this::dispose);}
        }
        Bundle result=new Bundle();result.putString("stream",report.toString());result.putBoolean("uiPassed",passed==CASES.length);result.putInt("passed",passed);
        result.putString("screenshots",screenshotFiles.toString());finish(passed==CASES.length?-1:0,result);
    }
    private int dp(float value){return Math.round(value*getTargetContext().getResources().getDisplayMetrics().density);}
    private void prepare(String scene){
        try{
            String prefix="v12-ui-"+scene+"-"+SystemClock.uptimeMillis()+"-";
            fixturePrefix=prefix;fixturePreferenceNames.clear();
            File root=new File(getTargetContext().getCacheDir(),prefix);fixtureRoot=root;require(root.mkdirs(),"fixture file root created independently of user state");
            File files=new File(root,"files"),cache=new File(root,"cache");require(files.mkdirs()&&cache.mkdirs(),"isolated file and cache directories");
            fixtureContext=new ContextWrapper(getTargetContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){if(!fixturePreferenceNames.contains(name))fixturePreferenceNames.add(name);return getTargetContext().getSharedPreferences(prefix+name,mode);}
                @Override public File getFilesDir(){return files;}
                @Override public File getCacheDir(){return cache;}
                @Override public File getNoBackupFilesDir(){return files;}
                @Override public File getFileStreamPath(String name){return new File(files,name);}
            };
            fixtureContext.getSharedPreferences("window",0).edit().putBoolean("animationsEnabled",false).putInt("scale",scene.equals("small-scaled-scroll")?130:100).apply();
            fixtureContext.getSharedPreferences("appearance",0).edit().putInt("mode",scene.equals("small-scaled-scroll")?2:1).apply();
            wasForeground=PlayerBridge.foreground;wasPicking=PlayerBridge.picking;PlayerBridge.foreground=false;PlayerBridge.picking=false;
            service=new RecordingService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,fixtureContext);
            setField(service,"destroyed",true);
            Constructor<?> ctor=Class.forName("org.floatmusic.player.PlaybackQueueStore").getDeclaredConstructor(File.class);ctor.setAccessible(true);store=ctor.newInstance(new File(files,"playback-queue.json"));
            setField(service,"playbackStore",store);
            boolean jump=scene.equals("queue-jump");
            JSONArray tracks=tracks(jump||scene.equals("queue-drag-refresh")||scene.equals("small-scaled-scroll")?95:3);
            if(jump)tracks.put(89,track("netease:101","重复歌曲"));
            invoke(store,"replaceQueue",new Class<?>[]{JSONArray.class,int.class},tracks,jump?89:1);
            invoke(store,"recordPlayed",new Class<?>[]{JSONObject.class,long.class},tracks.getJSONObject(0),1704067200000L);
            invoke(store,"recordPlayed",new Class<?>[]{JSONObject.class,long.class},track("tencent:101","QQ history"),1704067260000L);
            setField(service,"loadedTrack",tracks.getJSONObject(1));setField(service,"currentTrack",tracks.getJSONObject(1).getString("id"));setField(service,"ready",true);
            overlay=new OverlayWindow(service);setField(overlay,"ui",libraryFixture());setField(overlay,"playback",snapshot());overlay.show();invoke(overlay,"expand");
            require(field(overlay,"window")!=null,"rebuilt target APK requires overlay permission");PlayerBridge.takeEvents();
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private JSONObject track(String id,String name) throws Exception{return new JSONObject().put("id",id).put("source",id.substring(0,id.indexOf(':'))).put("songId",id.substring(id.indexOf(':')+1)).put("name",name).put("artist","Fixture artist");}
    private JSONArray tracks(int count) throws Exception{
        JSONArray tracks=new JSONArray();for(int i=0;i<count;i++)tracks.put(track(i<2?"netease:101":"kuwo:"+(200+i),i<2?"重复歌曲":"队列歌曲 "+i));return tracks;
    }
    private JSONObject libraryFixture() throws Exception{
        JSONArray songs=new JSONArray().put(track("netease:301","Library fixture")).put(track("tencent:301","Search fixture"));
        return new JSONObject().put("activePlaylist","fixture").put("tracks",songs).put("results",songs)
            .put("playlists",new JSONArray().put(new JSONObject().put("id","fixture").put("name","Fixture library").put("trackCount",2)))
            .put("onlinePlaylist",new JSONObject().put("id","netease:999").put("name","Fixture online").put("tracks",songs));
    }
    private JSONArray entries(){return (JSONArray)invoke(store,"entries");}
    private JSONObject snapshot() throws Exception{
        Object timer=field(service,"sleepTimer");return new JSONObject().put("title","重复歌曲").put("currentTrack","netease:101").put("loadedTrack",track("netease:101","重复歌曲"))
            .put("ready",true).put("busy",false).put("volume",70).put("duration",180000).put("position",10000)
            .put("queueEntries",entries()).put("currentQueueEntry",invoke(store,"currentEntryId")).put("historyEntries",invoke(store,"history"))
            .put("sleepMode",invoke(timer,"mode")).put("sleepRemaining",invoke(timer,"remainingSeconds")).put("sleepText",invoke(timer,"text"));
    }
    private void render(){try{overlay.update(snapshot());}catch(Exception error){throw new RuntimeException(error);}}
    private View root(){return (View)field(overlay,"window");}
    private View popup(){return (View)field(overlay,"popupLayer");}
    private void open(String detail){if(!"more".equals(field(overlay,"section")))invoke(overlay,"toggle",new Class<?>[]{String.class},"more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},detail);}
    private void runScene(String scene){
        if(scene.equals("more-navigation"))testMore();
        else if(scene.equals("queue-occurrences"))testOccurrences();
        else if(scene.equals("queue-sort"))testSort();
        else if(scene.equals("queue-drag-refresh"))beginDrag();
        else if(scene.equals("history-actions"))testHistory();
        else if(scene.equals("song-placement"))testSongPlacement();
        else if(scene.equals("sleep-presets"))testSleepPresets();
        else if(scene.equals("sleep-draft"))testSleepDraft();
        else if(scene.equals("notification-volume")){invoke(overlay,"collapse");overlay.showVolumeFromNotification();}
        else if(scene.equals("playlist-filter"))testPlaylistFilter();
        else if(scene.equals("queue-jump"))testQueueJump();
        else if(scene.equals("artist-browser"))testArtistBrowser();
        else open("queue");
    }
    private void testPlaylistFilter(){
        try{
            File local=new File(fixtureRoot,"playable.wav");
            try(FileOutputStream out=new FileOutputStream(local)){out.write("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));}
            JSONArray tracks=new JSONArray().put(track("netease:301","Blue fixture"))
                .put(track("local:playable.wav","Offline fixture").put("path",local.getAbsolutePath()))
                .put(track("local:missing.wav","Missing fixture").put("path",new File(fixtureRoot,"missing.wav").getAbsolutePath()))
                .put(track("netease:101","Current fixture")).put(track("tencent:301","Search fixture"));
            overlay.refreshData(libraryFixture().put("activePlaylist","favorites").put("tracks",tracks));
            invoke(overlay,"toggle",new Class<?>[]{String.class},"playlist");
            EditText query=root().findViewWithTag("playlistFilter");query.setText("SEARCH   artist");
            require(findText(root(),"Search fixture")!=null&&findText(root(),"Blue fixture")==null,"Favorites matches both title and artist terms");
            findText(root(),"Search fixture").performClick();
            JSONArray actions=new JSONArray(PlayerBridge.takeEvents());
            expect("tencent:301",actions.getJSONObject(actions.length()-1).getString("value"),"filtered playback keeps the original song ID");
            findDescription(root(),"排序 Search fixture").performClick();findText(popup(),"上移一首").performClick();
            JSONObject move=libraryPayload();expect("moveTrack",move.getString("action"),"filtered sort action reaches the existing store");
            require(move.getJSONObject("args").getInt("from")==4&&move.getJSONObject("args").getInt("to")==3,"filtered sort keeps the original position");
            findText(root(),"多选歌曲").performClick();findText(root(),"全选 / 清空").performClick();
            query=root().findViewWithTag("playlistFilter");query.setText("Blue");findText(root(),"全选 / 清空").performClick();
            require(findText(root(),"已选 2")!=null,"filtered select-all retains selection from the preceding filter");
            findText(root(),"全选 / 清空").performClick();require(findText(root(),"已选 1")!=null,"clearing the current filter's selection preserves hidden selections");
            query.setText("fixture artist");CheckBox offline=root().findViewWithTag("playlistOfflineOnly");offline.performClick();
            require(findText(root(),"Offline fixture")!=null&&findText(root(),"Missing fixture")==null&&findText(root(),"Current fixture")==null,"offline-only shows only the verified local file");
            require(findDescription(root(),"Offline fixture，可离线")!=null,"offline availability is exposed in the row description");
            View jump=root().findViewWithTag("jumpCurrentTrack");require(jump.isShown(),"jump is available when filters hide the current song");jump.performClick();
            require(query.length()==0&&!offline.isChecked()&&findText(root(),"已选 1")!=null,"jump clears hiding filters and preserves selection");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testQueueJump(){
        open("queue");
        require((Integer)field(overlay,"queuePage")==0,"opening the queue keeps the first page while playback is on a later page");
        View first=findDescription(root(),"播放队列第 1 首");require(first!=null&&!first.getContentDescription().toString().contains("正在播放"),"the earlier occurrence is not active");
        View jump=root().findViewWithTag("jumpCurrentTrack");require(jump.isShown(),"the jump arrow remains available across pages");jump.performClick();
    }
    private void verifyJump(String scene){
        View row=scene.equals("queue-jump")?findDescription(root(),"播放队列第 90 首"):findDescription(root(),"Current fixture");
        require(row!=null&&row.getContentDescription().toString().contains(scene.equals("queue-jump")?"正在播放":"当前歌曲"),"jump locates the active row");
        Rect visible=new Rect();
        ScrollView inner=(ScrollView)field(overlay,"detailScroll"),outer=(ScrollView)field(overlay,"scroll");
        int[] rowAt=new int[2],innerAt=new int[2]; row.getLocationOnScreen(rowAt); inner.getLocationOnScreen(innerAt);
        require(row.getGlobalVisibleRect(visible)&&visible.height()>0,"jump scrolls the active row into the actual viewport"
            +" (row y="+rowAt[1]+", inner y="+innerAt[1]+", inner height="+inner.getHeight()
            +", scroll="+inner.getScrollY()+" / "+outer.getScrollY()+")");
        if(scene.equals("queue-jump")){
            require((Integer)field(overlay,"queuePage")==1,"the jump loads the exact occurrence's page");
            try{row.performClick();expect(entries().getJSONObject(89).getString("queueId"),service.last("playQueueEntry"),"jumped queue row plays the exact occurrence");}
            catch(Exception error){throw new RuntimeException(error);}
        }
    }
    private void testArtistBrowser(){
        try{
            open("search");((EditText)field(overlay,"searchInput")).setText("Song draft");findText(root(),"歌手").performClick();
            EditText artist=(EditText)field(overlay,"searchInput");require(artist.length()==0,"artist browsing has a separate draft");artist.setText("Fixture singer");((Button)field(overlay,"searchButton")).performClick();
            JSONObject search=artistPayload("search");
            expect("Fixture singer",search.getString("query"),"artist search sends the query through the library bridge");
            JSONObject result=new JSONObject().put("id","123").put("source","netease").put("name","Fixture <b>Singer</b>");
            JSONObject state=new JSONObject().put("searchToken","older-request").put("results",new JSONArray().put(result));
            JSONObject ui=libraryFixture().put("musicArtists",state);
            ui.getJSONArray("playlists").put(new JSONObject().put("id","favorites").put("name","收藏夹").put("trackCount",0));
            overlay.refreshData(ui);
            require(findDescription(root(),"查看歌手")==null,"a late result from the previous artist search cannot be opened");
            String token=search.getString("searchToken");
            require(!token.isEmpty(),"artist search carries a bridge acknowledgement token");
            state.put("searchToken",token);
            overlay.refreshData(ui);findDescription(root(),"查看歌手").performClick();
            JSONObject open=artistPayload("open");expect("123",open.getString("id"),"artist detail keeps the provider ID");expect("netease",open.getString("source"),"artist detail keeps the provider");
            JSONObject profile=new JSONObject(result.toString()).put("description","<b>Plain biography</b>").put("hasMore",true)
                .put("tracks",new JSONArray().put(track("netease:101","Current work")));
            state.put("detail",profile);overlay.refreshData(ui);
            require(findText(root(),"<b>Plain biography</b>")!=null,"artist detail displays literal biography");
            findText(root(),"显示全部作品").performClick();artistPayload("loadAll");
            findText(root(),"Current work").performClick();expect("netease:101",artistPayload("play").getString("id"),"artist work playback keeps the source-qualified track ID");
            EditText works=root().findViewWithTag("artistWorkFilter");
            works.setText("ENCORE moonrise");artistPayload("loadAll");
            require(findText(root(),"Current work")==null,"work search filters out unrelated results");
            JSONArray complete=new JSONArray(profile.getJSONArray("tracks").toString())
                .put(track("netease:102","Distant Encore").put("album","Moonrise"));
            profile.put("hasMore",false).put("tracks",complete);
            overlay.refreshData(ui);
            require(findText(root(),"Distant Encore")!=null,"search includes works arriving on a later page");
            require(!((TextView)findText(root(),"Distant Encore")).getText().toString().contains("不可用"),
                "unknown track availability does not show a restriction");
            ui.put("unavailableTrackIds",new JSONArray().put("netease:102"));overlay.refreshData(ui);
            require(((TextView)findText(root(),"Distant Encore")).getText().toString().contains("不可用"),
                "a confirmed playback restriction updates the visible row");
            complete.getJSONObject(1).put("unavailable",true);
            ui.remove("unavailableTrackIds");overlay.refreshData(ui);
            require(((TextView)findText(root(),"Distant Encore")).getText().toString().contains("不可用"),
                "a catalog-restricted track stays listed with an unavailable label");
            ui.put("verifiedOnlineTrackIds",new JSONArray().put("netease:102"));overlay.refreshData(ui);
            require(!((TextView)findText(root(),"Distant Encore")).getText().toString().contains("不可用"),
                "verified playback overrides a stale catalog restriction");
            findText(root(),"导入搜索结果").performClick();
            findText(popup(),"收藏夹").performClick();
            JSONObject placement=artistPayload("import");
            expect("favorites",placement.getString("target"),"works can be imported into Favorites");
            expect("ENCORE moonrise",placement.getString("query"),"import uses the visible filtered scope");
            expect("123",placement.getString("id"),"import retains the opened artist identity");
            View jump=root().findViewWithTag("jumpCurrentTrack");
            require(jump.isShown(),"artist jump remains available when the current work is filtered out");
            jump.performClick();
            require(works.length()==0&&findText(root(),"Current work")!=null&&findText(root(),"Distant Encore")!=null,
                "jumping clears the artist filter and reveals the current work");
            findText(root(),"返回搜索").performClick();artistPayload("back");require(findDescription(root(),"查看歌手")!=null,"returning to search retains artist results");
            findText(root(),"歌曲").performClick();artistPayload("close");expect("Song draft",((EditText)field(overlay,"searchInput")).getText().toString(),"artist browsing preserves the previous song query");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private JSONObject libraryPayload()throws Exception{
        JSONArray events=new JSONArray(PlayerBridge.takeEvents());
        for(int i=events.length()-1;i>=0;i--){JSONObject event=events.getJSONObject(i);if(event.getString("action").equals("libraryAction"))return new JSONObject(event.getString("value"));}
        throw new AssertionError("No library command was emitted: "+events);
    }
    private JSONObject artistPayload(String action)throws Exception{
        JSONObject outer=libraryPayload();expect("artist",outer.getString("action"),"artist action uses the independent browser route");
        JSONObject inner=outer.getJSONObject("args");expect(action,inner.getString("action"),"artist action uses the expected command");return inner.getJSONObject("args");
    }
    private void waitForDraw()throws Exception{
        java.util.concurrent.CountDownLatch drawn=new java.util.concurrent.CountDownLatch(1);
        runOnMainSync(()->{
            View view=root();view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
                public boolean onPreDraw(){view.getViewTreeObserver().removeOnPreDrawListener(this);drawn.countDown();return true;}
            });view.postInvalidateOnAnimation();
        });
        require(drawn.await(3,java.util.concurrent.TimeUnit.SECONDS),"overlay completed a layout frame");
    }
    private void testMore(){
        invoke(overlay,"toggle",new Class<?>[]{String.class},"more");
        for(String label:new String[]{"播放队列","最近播放","睡眠定时"}){
            View button=findText(root(),label);require(button!=null&&button.getLayoutParams().height>=dp(48),"more entry has a native touch target: "+label);
            button.performClick();require(findText(root(),"返回更多")!=null,"native detail exposes back navigation");findText(root(),"返回更多").performClick();
        }
        require(PlayerBridge.takeEvents().equals("[]"),"native state pages work independently of Qt library events");
    }
    private void testOccurrences(){
        try{
            open("queue");JSONArray before=entries();String second=before.getJSONObject(1).getString("queueId");
            ArrayList<View> buttons=findDescriptions(root(),"播放队列第");require(buttons.size()==3,"duplicate songs render as separate occurrences");
            require(buttons.get(1).getContentDescription().toString().contains("正在播放"),"current marker follows the queue occurrence");
            require(!buttons.get(0).getContentDescription().toString().contains("正在播放"),"same song id does not mark the earlier duplicate");
            buttons.get(1).performClick();expect(second,service.last("playQueueEntry"),"queue playback targets occurrence UUID");
            findDescriptions(root(),"队列歌曲操作").get(1).performClick();findText(popup(),"从队列移除").performClick();
            require(entries().length()==3,"removal waits for confirmation");findText(popup(),"取消").performClick();require(entries().length()==3,"cancel preserves queue");
            findDescriptions(root(),"队列歌曲操作").get(1).performClick();findText(popup(),"从队列移除").performClick();findText(popup(),"确认移除").performClick();
            require(entries().length()==2&&!entries().toString().contains(second),"only the selected duplicate occurrence is removed");render();
            findText(root(),"清空队列").performClick();require(entries().length()==2,"queue clear waits for confirmation");findText(popup(),"确认清空队列").performClick();render();
            require(entries().length()==0&&findText(root(),"队列为空")!=null,"empty native queue stays visible after clear");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testSort(){
        try{
            open("queue");String id=entries().getJSONObject(1).getString("queueId");findDescriptions(root(),"排序队列").get(1).performClick();findText(popup(),"上移一首").performClick();
            expect(id,entries().getJSONObject(0).getString("queueId"),"tap-to-sort moves the occurrence through native service");render();
            findDescriptions(root(),"排序队列").get(0).performClick();String captured=entries().toString();invoke(store,"move",new Class<?>[]{String.class,int.class},id,2);render();
            findText(popup(),"下移一首").performClick();expect(id,entries().getJSONObject(2).getString("queueId"),"stale sort menu cannot move a newly reordered queue");require(!captured.equals(entries().toString()),"test introduced a real external reorder");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void beginDrag(){
        try{
            open("queue");ArrayList<View> names=findDescriptions(root(),"播放队列第");dragOrigin=(View)names.get(1).getParent();LinearLayout songs=(LinearLayout)dragOrigin.getParent();
            Constructor<?> ctor=Class.forName("org.floatmusic.player.OverlayWindow$DragReorder").getDeclaredConstructor(OverlayWindow.class,LinearLayout.class,View.class,String.class,int.class,String.class);ctor.setAccessible(true);
            activeDrag=ctor.newInstance(overlay,songs,dragOrigin,"queue",1,invokeStatic("queueOrderKey",new Class<?>[]{JSONArray.class},entries()));setField(overlay,"dragReorder",activeDrag);
            View oldList=(View)field(overlay,"dynamic");JSONObject state=snapshot().put("currentQueueEntry",entries().getJSONObject(2).getString("queueId"));overlay.update(state);
            require(field(overlay,"dynamic")==oldList&&dragOrigin.getParent()==songs,"status refresh cannot detach a row while dragging");
            ScrollView detail=(ScrollView)field(overlay,"detailScroll");dragScrollBefore=detail.getScrollY();
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void finishDrag(){
        try{
            ScrollView detail=(ScrollView)field(overlay,"detailScroll");int[] origin=new int[2];detail.getLocationOnScreen(origin);
            float scale=(Float)field(overlay,"scale");setField(activeDrag,"rawY",origin[1]+detail.getHeight()*scale-1f);
            for(int i=0;i<8;i++)invoke(activeDrag,"run");require(detail.getScrollY()>dragScrollBefore||((ScrollView)field(overlay,"scroll")).getScrollY()>0,"drag near edge automatically advances a scrollable list");
            setField(activeDrag,"target",3);String id=entries().getJSONObject(1).getString("queueId");invoke(overlay,"commitReorder",new Class<?>[]{activeDrag.getClass()},activeDrag);
            expect(id,entries().getJSONObject(3).getString("queueId"),"drag commit preserves duplicate occurrence identity");
            invoke(overlay,"stopReorder");activeDrag=null;render();require(dragOrigin.getAlpha()==1f,"drop restores row presentation");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testHistory(){
        try{
            open("history");require(findText(root(),"QQ history")!=null&&findText(root(),"QQ音乐")!=null,"history shows song and source");require(((TextView)findText(root(),"QQ音乐")).getText().toString().matches(".*[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}.*"),"history renders the actual played time");
            ArrayList<View> replay=findDescriptions(root(),"重播历史歌曲");replay.get(0).performClick();expect("tencent:101",service.last("playHistoryTrack"),"history replay preserves complete source identity");
            ArrayList<View> menus=findDescriptions(root(),"历史歌曲操作");menus.get(0).performClick();findText(popup(),"下一首播放").performClick();
            JSONObject payload=new JSONObject(service.last("enqueueTrack"));expect("tencent:101",payload.getJSONObject("track").getString("id"),"history next action sends complete track");require(!payload.getJSONObject("track").has("playedAtMs"),"history timestamp is not a new song identity");
            findText(root(),"清空历史").performClick();require(((JSONArray)invoke(store,"history")).length()==2,"history clear waits for confirmation");findText(popup(),"取消").performClick();
            findText(root(),"清空历史").performClick();findText(popup(),"确认清空历史").performClick();render();require(((JSONArray)invoke(store,"history")).length()==0,"confirmed history clear changes native state");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testSongPlacement(){
        try{
            for(String context:new String[]{"tracks","results","online"}){
                if(context.equals("tracks"))invoke(overlay,"toggle",new Class<?>[]{String.class},"playlist");
                else{open(context.equals("results")?"search":"online");if(context.equals("online")){setField(overlay,"requestedOnlineId","netease:999");setField(overlay,"rendered","");invoke(overlay,"refreshDynamic");}}
                View more=findDescription(root(),"歌曲操作，");require(more!=null,"every song context exposes the same placement menu");more.performClick();
                findText(popup(),context.equals("results")?"加入队尾":"下一首播放").performClick();JSONObject payload=new JSONObject(service.last("enqueueTrack"));
                expect(context.equals("results")?"tail":"next",payload.getString("placement"),"song menu forwards placement in "+context);expect("netease:301",payload.getJSONObject("track").getString("id"),"full song payload in "+context);
            }
            require(entries().length()==6,"three song placement actions append independent queue occurrences");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testSleepPresets(){
        open("sleep");
        for(int minutes:new int[]{15,30,60}){findText(root(),minutes+" 分钟").performClick();expect(Integer.toString(minutes),service.last("startSleepTimer"),"preset dispatches native minutes");render();require(((View)field(overlay,"sleepButton")).getVisibility()==View.VISIBLE,"running timer shows a header icon");}
        findText(root(),"播完本首").performClick();render();expect("endOfTrack",invoke(field(service,"sleepTimer"),"mode").toString(),"end-current timer is native");
        findText(root(),"取消定时").performClick();render();expect("off",invoke(field(service,"sleepTimer"),"mode").toString(),"cancel clears the native timer");require(((View)field(overlay,"sleepButton")).getVisibility()==View.GONE,"off timer hides header status");
    }
    private void testSleepDraft(){
        try{
            open("sleep");EditText input=(EditText)field(overlay,"sleepInput");input.setText("181");findText(root(),"开始定时").performClick();require(input.getError()!=null&&service.last("startSleepTimer")==null,"invalid custom timer stays in field with an inline error");
            input.setText("42");input.setSelection(1);JSONObject data=snapshot().put("sleepMode","countdown").put("sleepRemaining",539).put("sleepText","还剩 8:59");overlay.update(data);
            require(field(overlay,"sleepInput")==input&&input.getText().toString().equals("42")&&input.getSelectionStart()==1,"timer tick preserves custom editing field and cursor");
            findText(root(),"开始定时").performClick();expect("42",service.last("startSleepTimer"),"valid custom minutes dispatch through native service");
            invoke(overlay,"selectDetail",new Class<?>[]{String.class},"");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"sleep");expect("42",((EditText)field(overlay,"sleepInput")).getText().toString(),"timer draft survives leaving and returning");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void verifyNotificationVolume(){require((Boolean)field(overlay,"expanded"),"notification expands the existing overlay");require((Boolean)field(overlay,"volumePopupOpen"),"notification volume uses the existing vertical popup");require(field(overlay,"volume")!=null,"existing accessible slider is available");ViewGroup layer=(ViewGroup)popup();require(layer.getChildCount()==1&&layer.getChildAt(0).getLayoutParams().width>=dp(48),"notification popup waits for expanded card layout before measuring its slider");}
    private void forceSmallViewport(){View root=root();Rect available=(Rect)invoke(overlay,"available");ViewGroup.LayoutParams params=root.getLayoutParams();params.height=Math.min(dp(320),available.height());root.setLayoutParams(params);((android.view.WindowManager)service.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(root,params);}
    private void verifySmallScroll(){
        Rect available=(Rect)invoke(overlay,"available");View window=root();float scale=(Float)field(overlay,"scale");
        require(scale>0f&&scale<=1.3f,"maximum configured scale respects the existing 130 percent limit and screen clamp");
        require(window.getWidth()>0&&window.getHeight()>0&&window.getWidth()<=available.width()&&window.getHeight()<=available.height(),"measured window dimensions stay inside the available viewport");
        require(Math.abs(window.getWidth()-Math.min(dp(442),available.width()-dp(8)))<=1,"maximum available scale uses the supported 130 percent width unless screen clearance limits it");
        ScrollView detail=(ScrollView)field(overlay,"detailScroll"),outer=(ScrollView)field(overlay,"scroll");detail.scrollTo(0,detail.getChildAt(0).getHeight());outer.scrollTo(0,outer.getChildAt(0).getHeight());
        require(detail.getScrollY()>0,"scaled short card can reach its queue footer");View next=findText(root(),"下一页");require(next!=null&&next.getLayoutParams().height>=dp(48),"long queue retains paging with 48dp touch targets");next.performClick();
        require(findDescription(root(),"播放队列第 95")!=null,"second queue page can reach the final item at the maximum available scale");
    }
    private void capture(String scene) throws Exception{
        View root=root();java.util.concurrent.CountDownLatch drawn=new java.util.concurrent.CountDownLatch(1);android.view.ViewTreeObserver.OnPreDrawListener[] listener=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        runOnMainSync(()->{listener[0]=()->{if(!root.isLayoutRequested()){root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);drawn.countDown();}else root.postInvalidateOnAnimation();return true;};root.getViewTreeObserver().addOnPreDrawListener(listener[0]);root.invalidate();});
        require(drawn.await(3,java.util.concurrent.TimeUnit.SECONDS),"screenshot waits for layout");Bitmap[] bitmap=new Bitmap[1];runOnMainSync(()->{bitmap[0]=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap[0]));});
        try{File folder=new File(getTargetContext().getCacheDir(),"v12-ui-fixture");require(folder.isDirectory()||folder.mkdirs(),"fixture screenshots directory");File output=new File(folder,scene+".png");try(FileOutputStream out=new FileOutputStream(output)){require(bitmap[0].compress(Bitmap.CompressFormat.PNG,100,out),"screenshot saved");}screenshotFiles.add(output);}finally{bitmap[0].recycle();}
    }
    private void dispose(){
        if(overlay!=null)overlay.close();if(service!=null){((Handler)field(service,"handler")).removeCallbacksAndMessages(null);((ExecutorService)field(service,"importer")).shutdownNow();}
        PlayerBridge.foreground=wasForeground;PlayerBridge.picking=wasPicking;overlay=null;service=null;store=null;activeDrag=null;PlayerBridge.takeEvents();
        if(fixturePrefix!=null)for(String name:fixturePreferenceNames)getTargetContext().deleteSharedPreferences(fixturePrefix+name);
        deleteFixtureTree(fixtureRoot);fixturePreferenceNames.clear();fixturePrefix=null;fixtureRoot=null;fixtureContext=null;
    }
    private static void deleteFixtureTree(File path){if(path==null)return;if(path.isDirectory()){File[] children=path.listFiles();if(children!=null)for(File child:children)deleteFixtureTree(child);}path.delete();}
    private static ArrayList<View> findDescriptions(View view,String prefix){ArrayList<View> result=new ArrayList<>();collectDescriptions(view,prefix,result);return result;}
    private static void collectDescriptions(View view,String prefix,ArrayList<View> result){if(view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith(prefix))result.add(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectDescriptions(((ViewGroup)view).getChildAt(i),prefix,result);}
    private static View findDescription(View view,String prefix){ArrayList<View> matches=findDescriptions(view,prefix);return matches.isEmpty()?null:matches.get(0);}
    private static View findText(View view,String prefix){if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(prefix))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),prefix);if(found!=null)return found;}return null;}
    private static Field declaredField(Class<?> type,String name) throws Exception{for(Class<?> at=type;at!=null;at=at.getSuperclass())try{Field field=at.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static Object field(Object target,String name){try{return declaredField(target.getClass(),name).get(target);}catch(Exception error){throw new RuntimeException(error);}}
    private static void setField(Object target,String name,Object value) throws Exception{declaredField(target.getClass(),name).set(target,value);}
    private static Object invoke(Object target,String name){return invoke(target,name,new Class<?>[]{});}
    private static Object invoke(Object target,String name,Class<?>[] types,Object... values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);}catch(Exception error){throw new RuntimeException(error);}}
    private static Object invokeStatic(String name,Class<?>[] types,Object... values){try{Method method=OverlayWindow.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,values);}catch(Exception error){throw new RuntimeException(error);}}
    private static void expect(String expected,String actual,String message){if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
