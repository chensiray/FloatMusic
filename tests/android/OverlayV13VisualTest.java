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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android views, identical fixture data for 1.2/1.3 geometry and visual comparison. */
public final class OverlayV13VisualTest extends Instrumentation {
    static final String[] VIEWS={"compact","lyrics","playlist","settings","popup","long-list"};
    private OverlayWindow overlay;
    private PlaybackService service;
    private Context fixtureContext;
    private File isolatedRoot;
    private String prefix;
    private boolean wasForeground,wasPicking,screenshots;
    private final StringBuilder report=new StringBuilder();
    private ConcurrentLinkedQueue<String> bridgeEvents;
    private final ArrayList<String> savedBridgeEvents=new ArrayList<>();

    @Override public void onCreate(Bundle args){super.onCreate(args);screenshots=args!=null&&"true".equals(args.getString("screenshots"));start();}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] failure=new Throwable[1];super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Error)throw (Error)failure[0];if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }
    @Override public void onStart(){
        runOnMainSync(()->{try{
            @SuppressWarnings("unchecked") ConcurrentLinkedQueue<String> events=(ConcurrentLinkedQueue<String>)declaredField(PlayerBridge.class,"events").get(null);
            bridgeEvents=events;savedBridgeEvents.addAll(events);events.clear();
        }catch(Exception failure){throw new RuntimeException(failure);}});
        int passed=0;
        for(String theme:new String[]{"light","dark"})for(String page:VIEWS){
            String scene=theme+"-"+page;
            try{
                runOnMainSync(()->prepare(scene,page,theme.equals("dark")));waitForIdleSync();awaitLayout();
                if(page.equals("popup")){runOnMainSync(()->((View)field(overlay,"volumeButton")).performClick());waitForIdleSync();awaitLayout();}
                if(page.equals("long-list")){runOnMainSync(this::shortViewport);waitForIdleSync();awaitLayout();}
                runOnMainSync(()->verify(page));waitForIdleSync();awaitLayout();
                capture(scene);report.append("PASS ").append(scene).append('\n');passed++;
            }catch(Throwable failure){report.append("FAIL ").append(scene).append(": ").append(failure).append('\n');}
            finally{runOnMainSync(this::dispose);}
        }
        Bundle result=new Bundle();result.putString("stream",report.toString());result.putBoolean("uiPassed",passed==12);result.putInt("passed",passed);
        runOnMainSync(()->{bridgeEvents.clear();bridgeEvents.addAll(savedBridgeEvents);});
        result.putString("artifacts",new File(getTargetContext().getCacheDir(),"v13-ui-fixture").toString());finish(passed==12?-1:0,result);
    }
    private int dp(float value){return Math.round(value*getTargetContext().getResources().getDisplayMetrics().density);}
    private void prepare(String scene,String page,boolean dark){
        try{
            prefix="v13-ui-"+scene+"-"+SystemClock.uptimeMillis()+"-";
            isolatedRoot=new File(getTargetContext().getCacheDir(),prefix);require(isolatedRoot.mkdirs(),"isolated fixture directory");
            File files=new File(isolatedRoot,"files"),cache=new File(isolatedRoot,"cache");require(files.mkdirs()&&cache.mkdirs(),"isolated file/cache directories");
            fixtureContext=new ContextWrapper(getTargetContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return getTargetContext().getSharedPreferences(prefix+name,mode);}
                @Override public File getFilesDir(){return files;}
                @Override public File getCacheDir(){return cache;}
                @Override public File getNoBackupFilesDir(){return files;}
                @Override public File getFileStreamPath(String name){return new File(files,name);}
            };
            fixtureContext.getSharedPreferences("window",0).edit().putBoolean("animationsEnabled",false).putInt("scale",page.equals("long-list")?130:100)
                .putInt("opacity",100).putInt("x",dp(12)).putInt("y",dp(80)).putInt("lyricMode",2).putInt("lyricFontSize",18).putFloat("lyricLineSpacing",1f).apply();
            fixtureContext.getSharedPreferences("appearance",0).edit().putInt("mode",dark?2:1).apply();
            wasForeground=PlayerBridge.foreground;wasPicking=PlayerBridge.picking;PlayerBridge.foreground=false;PlayerBridge.picking=false;
            service=new PlaybackService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,fixtureContext);
            setField(service,"destroyed",true);setField(service,"sourceQualities",qualities());
            overlay=new OverlayWindow(service);setField(overlay,"ui",library());setField(overlay,"playback",snapshot());overlay.show();invoke(overlay,"expand");
            require(root()!=null,"installed target requires overlay permission");
            if(page.equals("lyrics"))invoke(overlay,"toggle",new Class<?>[]{String.class},"lyrics");
            else if(page.equals("playlist")||page.equals("long-list"))invoke(overlay,"toggle",new Class<?>[]{String.class},"playlist");
            else if(page.equals("settings")){invoke(overlay,"toggle",new Class<?>[]{String.class},"more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"settings");}
            PlayerBridge.takeEvents();
        }catch(Exception failure){throw new RuntimeException(failure);}
    }
    private JSONObject qualities() throws Exception{return new JSONObject().put("netease","hires").put("tencent","exhigh").put("kuwo","standard");}
    private JSONObject track(int index) throws Exception{
        String source=new String[]{"netease","tencent","kuwo"}[index%3];
        return new JSONObject().put("id",source+":"+(1000+index)).put("source",source).put("songId",Integer.toString(1000+index))
            .put("name",index==0?"慢慢听见生活":"手边的音乐 · 第 "+(index+1)+" 首").put("artist",index==0?"浮音视觉样本":"原生界面样本");
    }
    private JSONObject library() throws Exception{
        JSONArray tracks=new JSONArray(),lines=new JSONArray();for(int i=0;i<95;i++)tracks.put(track(i));
        for(int i=0;i<40;i++)lines.put(new JSONObject().put("time",i*5000).put("original",i==4?"让每一束微光陪着音乐，缓缓留在手边":"第 "+(i+1)+" 句 · 在熟悉的旋律里慢下来")
            .put("translation",i==4?"Let every glimmer stay close to the music":"Slow down with a familiar melody"));
        return new JSONObject().put("sourceQualities",qualities()).put("activePlaylist","fixture").put("tracks",tracks)
            .put("playlists",new JSONArray().put(new JSONObject().put("id","fixture").put("name","让音乐留在手边").put("description","熟悉的旋律，片刻的安静。").put("trackCount",95)))
            .put("lyricTrack","netease:1000").put("lyricLines",lines).put("lyricsMessage","");
    }
    private JSONObject snapshot() throws Exception{return new JSONObject().put("sourceQualities",qualities()).put("qualitySelectable",false).put("ready",true).put("busy",false)
        .put("playing",false).put("loadedTrack",track(0)).put("currentTrack","netease:1000").put("title","慢慢听见生活").put("volume",70)
        .put("position",21000).put("duration",180000).put("qualityInfo","FLAC · 48 kHz / 24 bit").put("playbackMode","sequential")
        .put("outputs",new JSONArray().put(new JSONObject().put("id","").put("name","系统默认")));}
    private View root(){return (View)field(overlay,"window");}
    private void awaitLayout() throws Exception{
        View root=root();CountDownLatch drawn=new CountDownLatch(1);android.view.ViewTreeObserver.OnPreDrawListener[] listener=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        runOnMainSync(()->{listener[0]=()->{if(!root.isLayoutRequested()){root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);drawn.countDown();}else root.postInvalidateOnAnimation();return true;};root.getViewTreeObserver().addOnPreDrawListener(listener[0]);root.invalidate();});
        require(drawn.await(3,TimeUnit.SECONDS),"native view has completed layout");
    }
    private void shortViewport(){
        View window=root();ViewGroup.LayoutParams params=window.getLayoutParams();params.height=Math.min(dp(320),((Rect)invoke(overlay,"available")).height());window.setLayoutParams(params);
        ((android.view.WindowManager)service.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(window,params);
    }
    private void verify(String page){
        View window=root();Rect available=(Rect)invoke(overlay,"available");
        require(window.getWidth()>0&&window.getHeight()>0&&window.getWidth()<=available.width()&&window.getHeight()<=available.height(),"native window fits the available viewport");
        for(String name:new String[]{"play","modeButton","volumeButton","lyricsTab","playlistTab","moreTab"}){
            View control=(View)field(overlay,name);require(control.getWidth()>=dp(48)&&control.getHeight()>=dp(48),"existing 48dp native touch target: "+name);
        }
        if(page.equals("lyrics"))require(((ArrayList<?>)field(overlay,"lyricTexts")).size()==40,"native bilingual lyric rows remain available");
        if(page.equals("settings"))require(((ArrayList<?>)field(overlay,"sourceQualityButtons")).size()==3,"three independent quality selectors remain available");
        if(page.equals("popup")){
            ViewGroup layer=(ViewGroup)field(overlay,"popupLayer");require(layer.getVisibility()==View.VISIBLE&&layer.getChildCount()==1,"volume opens the existing native popup");
            View popup=layer.getChildAt(0);require(popup.getLeft()>=0&&popup.getTop()>=0&&popup.getRight()<=layer.getWidth()&&popup.getBottom()<=layer.getHeight(),"volume popup stays inside overlay");
            SeekBar slider=(SeekBar)field(overlay,"volume");require(slider.getWidth()>=dp(48)&&slider.getHeight()>=dp(48),"volume keeps its original drag area");
            for(int value:new int[]{0,50,100}){
                float y=value==0?slider.getHeight()-dp(8):value==100?dp(8):slider.getHeight()/2f;
                gesture(slider,y,MotionEvent.ACTION_DOWN);gesture(slider,y,MotionEvent.ACTION_UP);require((Integer)field(service,"volume")==value,"native volume touch reaches "+value);
            }
            try{overlay.update(snapshot());}catch(Exception failure){throw new RuntimeException(failure);}
        }
        if(page.equals("long-list")){
            float scale=(Float)field(overlay,"scale");require(scale>0f&&scale<=1.3f,"existing scale clamp remains within 130 percent");
            require(Math.abs(window.getWidth()-Math.min(dp(442),available.width()-dp(8)))<=1,"maximum available 1.2 width remains unchanged");
            ScrollView detail=(ScrollView)field(overlay,"detailScroll"),outer=(ScrollView)field(overlay,"scroll");
            detail.scrollTo(0,detail.getChildAt(0).getHeight());outer.scrollTo(0,outer.getChildAt(0).getHeight());require(detail.getScrollY()>0,"long list still scrolls in a short card");
            View next=findText(window,"下一页");require(next!=null&&next.getLayoutParams().height>=dp(48),"long-list paging retains its native touch target");next.performClick();
            require(findDescription(window,"播放，手边的音乐 · 第 95 首")!=null,"last playlist item remains reachable");
        }
        require(PlayerBridge.takeEvents().equals("[]"),"fixture rendering and local volume do not request library/network actions");
    }
    private void gesture(View view,float y,int action){MotionEvent event=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,view.getWidth()/2f,y,0);try{view.dispatchTouchEvent(event);}finally{event.recycle();}}
    private void capture(String scene) throws Exception{
        File folder=new File(getTargetContext().getCacheDir(),"v13-ui-fixture");require(folder.isDirectory()||folder.mkdirs(),"fixture artifact directory");
        JSONObject[] data=new JSONObject[1];Bitmap[] bitmap=new Bitmap[1];
        runOnMainSync(()->{try{
            View window=root();JSONArray nodes=new JSONArray();collect(window,"root",nodes);
            data[0]=new JSONObject().put("scene",scene).put("density",getTargetContext().getResources().getDisplayMetrics().density)
                .put("scaledDensity",getTargetContext().getResources().getDisplayMetrics().scaledDensity).put("scale",field(overlay,"scale")).put("nodes",nodes);
            if(screenshots){bitmap[0]=Bitmap.createBitmap(window.getWidth(),window.getHeight(),Bitmap.Config.ARGB_8888);window.draw(new Canvas(bitmap[0]));}
        }catch(Exception failure){throw new RuntimeException(failure);}});
        try(FileOutputStream out=new FileOutputStream(new File(folder,scene+".geometry.json"))){out.write(data[0].toString(2).getBytes(StandardCharsets.UTF_8));}
        if(bitmap[0]!=null)try{try(FileOutputStream out=new FileOutputStream(new File(folder,scene+".png"))){require(bitmap[0].compress(Bitmap.CompressFormat.PNG,100,out),"actual native view bitmap saved");}}finally{bitmap[0].recycle();}
    }
    private void collect(View view,String path,JSONArray nodes) throws Exception{
        ViewGroup.LayoutParams params=view.getLayoutParams();JSONObject node=new JSONObject().put("path",path).put("class",view.getClass().getName())
            .put("bounds",new JSONArray(new int[]{view.getLeft(),view.getTop(),view.getRight(),view.getBottom()}))
            .put("padding",new JSONArray(new int[]{view.getPaddingLeft(),view.getPaddingTop(),view.getPaddingRight(),view.getPaddingBottom()}))
            .put("minimum",new JSONArray(new int[]{view.getMinimumWidth(),view.getMinimumHeight()}))
            .put("visibility",view.getVisibility()).put("clickable",view.isClickable()).put("longClickable",view.isLongClickable())
            .put("enabled",view.isEnabled()).put("focusable",view.isFocusable()).put("scroll",new JSONArray(new int[]{view.getScrollX(),view.getScrollY()}));
        if(params!=null)node.put("layout",new JSONArray(new int[]{params.width,params.height}));
        if(params instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams margin=(ViewGroup.MarginLayoutParams)params;node.put("margins",new JSONArray(new int[]{margin.leftMargin,margin.topMargin,margin.rightMargin,margin.bottomMargin}));}
        if(view instanceof TextView){TextView text=(TextView)view;node.put("textSize",text.getTextSize()).put("gravity",text.getGravity()).put("maxLines",text.getMaxLines()).put("fontPadding",text.getIncludeFontPadding());}
        nodes.put(node);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),path+"/"+i,nodes);
    }
    private void dispose(){
        if(overlay!=null)overlay.close();if(service!=null){((Handler)field(service,"handler")).removeCallbacksAndMessages(null);((ExecutorService)field(service,"importer")).shutdownNow();}
        PlayerBridge.foreground=wasForeground;PlayerBridge.picking=wasPicking;PlayerBridge.takeEvents();overlay=null;service=null;
        if(prefix!=null)for(String name:new String[]{"window","appearance","audio"})getTargetContext().deleteSharedPreferences(prefix+name);
        delete(isolatedRoot);fixtureContext=null;isolatedRoot=null;prefix=null;
    }
    private static void delete(File path){if(path==null)return;if(path.isDirectory()){File[] children=path.listFiles();if(children!=null)for(File child:children)delete(child);}path.delete();}
    private static View findText(View view,String prefix){if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(prefix))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),prefix);if(found!=null)return found;}return null;}
    private static View findDescription(View view,String prefix){if(view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith(prefix))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findDescription(((ViewGroup)view).getChildAt(i),prefix);if(found!=null)return found;}return null;}
    private static Field declaredField(Class<?> type,String name) throws Exception{for(Class<?> at=type;at!=null;at=at.getSuperclass())try{Field field=at.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static Object field(Object target,String name){try{return declaredField(target.getClass(),name).get(target);}catch(Exception error){throw new RuntimeException(error);}}
    private static void setField(Object target,String name,Object value) throws Exception{declaredField(target.getClass(),name).set(target,value);}
    private static Object invoke(Object target,String name){return invoke(target,name,new Class<?>[]{});}
    private static Object invoke(Object target,String name,Class<?>[] types,Object... values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);}catch(Exception error){throw new RuntimeException(error);}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
