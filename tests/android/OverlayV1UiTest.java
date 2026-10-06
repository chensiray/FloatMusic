package org.floatmusic.v1uitest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual-overlay fixture. Uses in-memory snapshots and drains its bridge events before native work. */
public final class OverlayV1UiTest extends Instrumentation {
    private Object overlay;
    private final StringBuilder report=new StringBuilder();
    private boolean screenshots;

    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);screenshots=arguments!=null&&"true".equals(arguments.getString("screenshots"));start();}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] failure=new Throwable[1];super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Error)throw (Error)failure[0];if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }
    @Override public void onStart(){
        int passed=0;String[] cases={"sources","empty-and-playlist-search","mixed-identities","quality-editing","gesture-refresh","result-count","ranking-platforms","online-metadata"};
        for(String scene:cases){
            try{
                runOnMainSync(()->prepare(scene));waitForIdleSync();
                runOnMainSync(()->{
                    if(scene.equals("sources"))testSources();
                    else if(scene.equals("empty-and-playlist-search"))testEmptyAndPlaylistSearch();
                    else if(scene.equals("mixed-identities"))testMixedIdentities();
                    else if(scene.equals("quality-editing"))testQualityEditing();
                    else if(scene.equals("result-count"))testResultCount();
                    else if(scene.equals("ranking-platforms"))testRankingPlatforms();
                    else if(scene.equals("online-metadata"))testOnlineMetadata();
                    else beginGestureRefresh();
                });
                if(scene.equals("gesture-refresh")){waitForIdleSync();runOnMainSync(this::finishGestureRefresh);}
                if(screenshots)capture(scene);
                report.append("PASS ").append(scene).append('\n');passed++;
            }catch(Throwable failure){report.append("FAIL ").append(scene).append(": ").append(failure).append('\n');}
            finally{runOnMainSync(()->{if(overlay!=null)invoke(overlay,"close");overlay=null;});}
        }
        Bundle result=new Bundle();result.putString("stream",report.toString());result.putBoolean("uiPassed",passed==cases.length);result.putInt("passed",passed);finish(passed==cases.length?-1:0,result);
    }
    private void capture(String scene) throws Exception{
        View root=(View)field(overlay,"window");
        java.util.concurrent.CountDownLatch drawn=new java.util.concurrent.CountDownLatch(1);
        android.view.ViewTreeObserver.OnPreDrawListener[] listener=new android.view.ViewTreeObserver.OnPreDrawListener[1];int[] frames=new int[1];
        runOnMainSync(()->{listener[0]=()->{if(++frames[0]>=2&&!root.isLayoutRequested()){root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);drawn.countDown();}else root.postInvalidateOnAnimation();return true;};root.getViewTreeObserver().addOnPreDrawListener(listener[0]);root.invalidate();});
        require(drawn.await(3,java.util.concurrent.TimeUnit.SECONDS),"fixture screenshot needs a completed frame");
        Bitmap[] image=new Bitmap[1];
        runOnMainSync(()->{require(root.getWidth()>0&&root.getHeight()>0,"fixture screenshot has measured bounds");settleDrawables(root);image[0]=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image[0]));});
        try{
            File folder=new File(getTargetContext().getCacheDir(),"v1-ui-fixture");require(folder.isDirectory()||folder.mkdirs(),"fixture screenshot directory is available");
            try(FileOutputStream out=new FileOutputStream(new File(folder,scene+".png"))){require(image[0].compress(Bitmap.CompressFormat.PNG,100,out),"fixture screenshot saved");}
        }finally{image[0].recycle();}
    }
    private static void settleDrawables(View view){view.jumpDrawablesToCurrentState();if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)settleDrawables(((ViewGroup)view).getChildAt(i));}
    private void prepare(String scene){
        try{
            Class<?> serviceType=Class.forName("org.floatmusic.player.PlaybackService");Object service=serviceType.getDeclaredConstructor().newInstance();
            Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,getTargetContext());
            Class<?> overlayType=Class.forName("org.floatmusic.player.OverlayWindow");Constructor<?> constructor=overlayType.getDeclaredConstructor(serviceType);constructor.setAccessible(true);overlay=constructor.newInstance(service);
            JSONObject data=fixture();if(scene.equals("empty-and-playlist-search"))data.put("searchSources",new JSONArray());
            setField(overlay,"ui",data);setField(overlay,"playback",snapshot("netease",true,false,"网易云 · Fixture MP3"));
            invoke(overlay,"show");invoke(overlay,"expand");require(field(overlay,"window")!=null,"the overlay permission and installed target APK are required");
            invoke(overlay,"toggle","more");invoke(overlay,"selectDetail",scene.equals("quality-editing")||scene.equals("result-count")?"settings":scene.equals("ranking-platforms")?"rankings":"search");events();
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private JSONObject fixture() throws Exception{
        JSONArray tracks=new JSONArray().put(new JSONObject().put("id","netease:101").put("source","netease").put("songId","101").put("name","Net fixture").put("artist","Fixture artist"))
                .put(new JSONObject().put("id","tencent:AbC12xY").put("source","tencent").put("songId","AbC12xY").put("name","QQ fixture").put("artist","Fixture artist"))
                .put(new JSONObject().put("id","kuwo:202").put("source","kuwo").put("songId","202").put("name","Kuwo fixture").put("artist","Fixture artist"));
        return new JSONObject().put("activePlaylist","fixture").put("tracks",tracks).put("results",tracks)
                .put("searchResultLimit",30).put("rankingSource","netease").put("rankings",new JSONArray().put(new JSONObject().put("id","99").put("name","Net chart").put("source","netease")))
                .put("quality","hires").put("qualitySelectable",true).put("searchSources",new JSONArray().put("netease").put("tencent").put("kuwo"))
                .put("playlists",new JSONArray().put(new JSONObject().put("id","fixture").put("name","Mixed fixture").put("trackCount",3)));
    }
    private JSONObject snapshot(String source,boolean selectable,boolean busy,String info){
        try{return new JSONObject().put("loadedTrack",new JSONObject().put("source",source).put("name","Loaded fixture").put("artist","Fixture artist"))
                .put("title","Loaded fixture").put("currentSourceName",source.equals("tencent")?"QQ音乐":source.equals("kuwo")?"酷我音乐":"网易云")
                .put("qualitySelectable",selectable).put("busy",busy).put("qualityInfo",info);}
        catch(Exception error){throw new RuntimeException(error);}
    }
    private void testSources(){
        try{
            EditText input=(EditText)field(overlay,"searchInput");input.setText("Typed draft");input.setSelection(5);
            List<?> controls=(List<?>)field(overlay,"searchSourceButtons");require(controls.size()==3,"song search exposes three library choices");
            ScrollView detail=(ScrollView)field(overlay,"detailScroll");float density=getTargetContext().getResources().getDisplayMetrics().density;
            for(Object item:controls){CheckBox choice=(CheckBox)item;require(choice.isChecked(),"libraries are checked by default");require(descendantOf(choice,detail),"library choices scroll with the input and results");require(choice.getLayoutParams().height>=48*density-1,"library choices retain native 48dp touch targets");}
            CheckBox qq=(CheckBox)controls.get(1);qq.performClick();
            require(field(overlay,"searchInput")==input&&input.getText().toString().equals("Typed draft")&&input.getSelectionStart()==5,"changing libraries must retain the editing field and cursor");
            require(findText((View)field(overlay,"dynamic"),"QQ fixture\n")==null,"old result rows disappear while the new source selection is being acknowledged");
            expect("[\"netease\",\"kuwo\"]",eventValue(events(),"searchSources"),"the bridge receives the complete multi-select JSON array");
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},fixture());require(!qq.isChecked(),"an older snapshot cannot overwrite an unacknowledged choice");
            JSONObject acknowledged=fixture().put("searchSources",new JSONArray().put("netease").put("kuwo")).put("results",new JSONArray());
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},acknowledged);
            require(field(overlay,"searchInput")==input&&((List<?>)field(overlay,"searchSourceButtons")).get(1)==qq&&!qq.isChecked(),"acknowledging sources updates in place");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testEmptyAndPlaylistSearch(){
        try{
            EditText input=(EditText)field(overlay,"searchInput");input.setText("Fixture query");
            require(!((Button)field(overlay,"searchButton")).isEnabled(),"empty song libraries disable search");input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            require(eventValue(events(),"search")==null,"IME search must not query when every library is cleared");
            require(((TextView)field(overlay,"feedback")).getText().toString().contains("勾选曲库"),"IME search gives a useful empty-library message");
            setField(overlay,"searchKind","playlists");invoke(overlay,"buildDrawer");
            require(((List<?>)field(overlay,"searchSourceButtons")).size()==3,"playlist search exposes the same three library choices");
            Button submit=(Button)field(overlay,"searchButton");require(!submit.isEnabled(),"empty playlist libraries disable search");((EditText)field(overlay,"searchInput")).onEditorAction(EditorInfo.IME_ACTION_SEARCH);require(eventValue(events(),"libraryAction")==null,"empty-source IME must not query playlists");
            ((CheckBox)((List<?>)field(overlay,"searchSourceButtons")).get(1)).performClick();expect("[\"tencent\"]",eventValue(events(),"searchSources"),"playlist library choice sends QQ only");require(submit.isEnabled(),"selecting QQ immediately enables playlist search");((EditText)field(overlay,"searchInput")).setText("Fixture playlist");submit.performClick();
            JSONObject event=new JSONObject(eventValue(events(),"libraryAction"));expect("searchPlaylists",event.getString("action"),"playlist query keeps the common library action");expect("Fixture playlist",event.getJSONObject("args").getString("query"),"playlist query keeps the typed text");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testMixedIdentities(){
        View root=(View)field(overlay,"window");Button qq=(Button)findText(root,"QQ fixture\n");require(qq!=null&&qq.getText().toString().contains("QQ音乐"),"mixed result rows show the source fallback");qq.performClick();
        expect("tencent:AbC12xY",eventValue(events(),"playResult"),"playing a QQ result preserves the full mixed ID and case-sensitive MID");
        invoke(overlay,"toggle","playlist");((Button)findText(root,"多选歌曲")).performClick();
        CheckBox choice=(CheckBox)findText(root,"QQ fixture\n");choice.performClick();
        require(((java.util.Set<?>)field(overlay,"selectedIds")).contains("tencent:AbC12xY"),"mixed playlist multi-select also preserves the full QQ ID");
    }
    private void testQualityEditing(){
        try{
            EditText api=(EditText)field(overlay,"apiInput");api.setText("https://typed.example/api");api.setSelection(8);Button quality=(Button)field(overlay,"qualityButton");
            invoke(overlay,"update",new Class<?>[]{JSONObject.class},snapshot("tencent",false,false,"QQ音乐 · 普通音质 · Fixture MP3"));
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},fixture().put("quality","standard").put("qualitySelectable",false).put("currentSourceName","QQ音乐"));
            require(field(overlay,"apiInput")==api&&api.getText().toString().equals("https://typed.example/api")&&api.getSelectionStart()==8,"source and quality refresh keep the API draft, field and cursor");
            require(field(overlay,"qualityButton")==quality&&!quality.isEnabled()&&quality.getText().toString().equals("普通音质"),"QQ exposes one disabled ordinary-quality surface");
            expect("QQ音乐 · 普通音质 · Fixture MP3",((TextView)field(overlay,"sourceInfo")).getText().toString(),"settings show actual service quality information");
            JSONObject pending=snapshot("tencent",false,true,"QQ音乐 · 普通音质 · Fixture MP3").put("currentSourceName","酷我音乐");invoke(overlay,"update",new Class<?>[]{JSONObject.class},pending);
            require(((TextView)field(overlay,"artist")).getText().toString().startsWith("QQ音乐"),"pending Kuwo playback does not relabel the loaded QQ song");
            invoke(overlay,"update",new Class<?>[]{JSONObject.class},snapshot("netease",true,false,"网易云 · Fixture FLAC"));invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},fixture());
            require(quality.isEnabled()&&quality.getText().toString().startsWith("Hi-Res"),"returning to Net restores its stored quality selection");require(field(overlay,"apiInput")==api,"returning to Net also preserves an edited API field");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testResultCount(){
        try{
            EditText api=(EditText)field(overlay,"apiInput");api.setText("typed-address");api.setSelection(3);
            Button count=(Button)field(overlay,"searchResultLimitButton");expect("30 条  ▾",count.getText().toString(),"default total count is visible");
            require(descendantOf(count,(View)field(overlay,"detailScroll")),"result setting scrolls with other settings");
            count.performClick();((Button)findText((View)field(overlay,"popupLayer"),"100 条")).performClick();
            expect("100",eventValue(events(),"searchResultLimit"),"chosen count uses integer command text");expect("100 条  ▾",count.getText().toString(),"choice appears before bridge acknowledgement");
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},fixture());expect("100 条  ▾",count.getText().toString(),"an older snapshot cannot undo the pending count");
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},fixture().put("searchResultLimit",100));require(field(overlay,"pendingSearchResultLimit")==null,"matching snapshot acknowledges count");
            require(field(overlay,"apiInput")==api&&api.getSelectionStart()==3&&api.getText().toString().equals("typed-address"),"count choice and refresh preserve settings draft/cursor");
            require(findText((View)field(overlay,"drawer"),"歌曲与歌单共用，所选曲库合计")!=null,"count explains shared total and next search behavior");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testRankingPlatforms(){
        try{
            View root=(View)field(overlay,"window");Button qq=(Button)findText(root,"QQ音乐");qq.performClick();
            JSONObject request=new JSONObject(eventValue(events(),"libraryAction"));expect("loadRankings",request.getString("action"),"platform choice loads rankings");expect("tencent",request.getJSONObject("args").getString("source"),"QQ choice uses the platform ID");
            require(qq.isSelected()&&findText((View)field(overlay,"dynamic"),"Net chart")==null,"selection is immediate and pending platform hides old chart rows");
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},fixture());require(qq.isSelected(),"old ranking state cannot overwrite pending choice");
            JSONObject next=fixture().put("rankingSource","tencent").put("rankings",new JSONArray().put(new JSONObject().put("id","tencent:ranking:26").put("name","QQ fixture chart").put("source","tencent").put("trackCount",120).put("updateFrequency","每日更新")));
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},next);require(field(overlay,"pendingRankingSource")==null,"matching platform snapshot acknowledges selection");
            require(findText(root,"QQ音乐 · 120 首 · 每日更新")!=null,"ranking card identifies QQ and chart details");
            View card=(View)findText(root,"QQ fixture chart").getParent();card.performClick();
            JSONObject open=new JSONObject(eventValue(events(),"libraryAction"));expect("tencent:ranking:26",open.getJSONObject("args").getString("id"),"opening ranking preserves canonical resource ID");expect("rankings",(String)field(overlay,"onlineSource"),"return view stays independent of platform");
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},next.put("rankingsLoading",true));
            invoke(overlay,"selectDetail","rankings");((Button)findText(root,"酷我")).performClick();JSONObject kw=new JSONObject(eventValue(events(),"libraryAction"));expect("kuwo",kw.getJSONObject("args").getString("source"),"Kuwo choice routes correctly even during a previous load");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void testOnlineMetadata(){
        try{
            JSONObject state=fixture().put("playlistResults",new JSONArray().put(new JSONObject().put("id","kuwo:playlist:123").put("source","kuwo").put("name","Kuwo playlist").put("trackCount",100).put("creator","Curator").put("description","Long fixture description")));
            setField(overlay,"searchKind","playlists");invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},state);invoke(overlay,"buildDrawer");
            View root=(View)field(overlay,"window");require(findText(root,"酷我音乐 · 100 首 · Curator")!=null,"playlist search card exposes platform/count/creator");((Button)findText(root,"查看歌单")).performClick();
            JSONObject open=new JSONObject(eventValue(events(),"libraryAction"));expect("kuwo:playlist:123",open.getJSONObject("args").getString("id"),"playlist details preserve canonical resource ID");expect("search",(String)field(overlay,"onlineSource"),"playlist return view stays search");
            JSONObject online=state.getJSONArray("playlistResults").getJSONObject(0);online.put("tracks",fixture().getJSONArray("tracks"));state.put("onlinePlaylist",online);invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},state);
            require(findText(root,"酷我音乐 · 100 首 · Curator · 已加载 3 首")!=null,"detail identifies platform and loaded count");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private View beforeGestureChild;
    private EditText beforeGestureInput;
    private void beginGestureRefresh(){
        try{
            ScrollView detail=(ScrollView)field(overlay,"detailScroll");beforeGestureChild=((ViewGroup)field(overlay,"dynamic")).getChildAt(0);beforeGestureInput=(EditText)field(overlay,"searchInput");
            long at=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(at,at,MotionEvent.ACTION_DOWN,8,8,0);try{detail.dispatchTouchEvent(down);}finally{down.recycle();}
            invoke(overlay,"refreshData",new Class<?>[]{JSONObject.class},fixture().put("searchSources",new JSONArray()).put("results",new JSONArray()));
            require(((ViewGroup)field(overlay,"dynamic")).getChildAt(0)==beforeGestureChild,"incoming source/results snapshots cannot detach rows during a touch gesture");
            MotionEvent cancel=MotionEvent.obtain(at,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,8,8,0);try{detail.dispatchTouchEvent(cancel);}finally{cancel.recycle();}
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void finishGestureRefresh(){
        require(((ViewGroup)field(overlay,"dynamic")).getChildAt(0)!=beforeGestureChild,"deferred results are applied after the gesture ends");require(field(overlay,"searchInput")==beforeGestureInput,"deferred results leave the input attached");
    }
    private JSONArray events(){try{return new JSONArray((String)Class.forName("org.floatmusic.player.PlayerBridge").getMethod("takeEvents").invoke(null));}catch(Exception error){throw new RuntimeException(error);}}
    private static String eventValue(JSONArray events,String action){for(int i=0;i<events.length();i++){JSONObject event=events.optJSONObject(i);if(event!=null&&action.equals(event.optString("action")))return event.optString("value");}return null;}
    private static boolean descendantOf(View child,View parent){for(android.view.ViewParent at=child.getParent();at!=null;at=at.getParent())if(at==parent)return true;return false;}
    private static View findText(View view,String prefix){if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(prefix))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),prefix);if(found!=null)return found;}return null;}
    private static Object field(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new RuntimeException(error);}}
    private static void setField(Object target,String name,Object value) throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void invoke(Object target,String name,String... argument){invoke(target,name,argument.length==0?new Class<?>[]{}:new Class<?>[]{String.class},(Object[])argument);}
    private static void invoke(Object target,String name,Class<?>[] types,Object... argument){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,argument);}catch(Exception error){throw new RuntimeException(error);}}
    private static void expect(String expected,String actual,String message){if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
