package org.floatmusic.player;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android WindowManager/View rendering, isolated from Qt startup and audio. */
public final class LyricWindowRegressionTest extends Instrumentation {
    private PlaybackService service;
    private FloatingLyricsWindow floating;
    private OverlayWindow overlay;
    private Context context;
    private JSONObject ui,state;
    private File screenshotDirectory;
    private String selectedCases="";
    private int windowMoves;
    private long windowMoveNanos;
    private boolean crossAppInput;
    private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null)selectedCases=args.getString("cases","");start();}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] failure={null};super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    @Override public void onStart(){
        screenshotDirectory=new File(getTargetContext().getExternalFilesDir(null),"run-"+SystemClock.uptimeMillis());screenshotDirectory.mkdirs();
        String[] cases=selectedCases.isEmpty()?new String[]{"player-bottom-visible","player-description-fold","player-settings-navigation","floating-empty-touch-through","floating-opacity-settings","floating-lock-recenter","floating-settings-ui","floating-drag-cache","floating-cache-invalidation","player-rebuild-no-lyrics","floating-no-motion-identity","floating-animated-identity","floating-horizontal-anchor","floating-ui-refresh","floating-stable-raster","floating-stable-surfaces","floating-nine-line-surfaces","floating-idle-stops","floating-touch-through","floating-cross-app-touch","floating-frame-pacing","floating-nine-frame-pacing","player-frame-pacing","player-long-frame-pacing","player-idle-layout","player-animated-follow","player-neighbor-follow","player-manual-return","player-no-motion-return","player-accessible-browse","player-no-motion-follow","player-copy-all","player-copy-description","player-copy-drag","player-copy-ranking"}:selectedCases.split(",");int passed=0;
        for(String scene:cases){
            Bundle progress=new Bundle();progress.putString("currentCase",scene);sendStatus(0,progress);
            try{
                boolean motion=!scene.equals("floating-no-motion-identity")&&!scene.equals("floating-horizontal-anchor")&&!scene.startsWith("player-no-motion-");
                runOnMainSync(()->prepare(motion,scene.equals("floating-horizontal-anchor")?.20f:.5f));SystemClock.sleep(500);
                if(scene.equals("player-description-fold"))testDescriptionFold();
                else if(scene.equals("player-settings-navigation"))testSettingsNavigation();
                else if(scene.equals("player-copy-ranking"))testCopyRanking();
                else if(scene.equals("player-copy-drag"))testCopyDrag();
                else if(scene.equals("player-copy-all"))testCopyAllLyrics();
                else if(scene.equals("player-copy-description"))testCopyDescription();
                else if(scene.equals("player-bottom-visible"))testPlayerBottom();
                else if(scene.equals("floating-empty-touch-through"))testEmptyTouchThrough();
                else if(scene.equals("floating-settings-ui"))testFloatingSettingsUi();
                else if(scene.equals("floating-opacity-settings"))testOpacitySettings();
                else if(scene.equals("floating-lock-recenter"))testLockRecenter();
                else if(scene.equals("player-rebuild-no-lyrics"))testPlayerRebuild();
                else if(scene.equals("floating-drag-cache"))testDragCache();
                else if(scene.equals("floating-cache-invalidation"))testCacheInvalidation();
                else if(scene.equals("player-idle-layout"))testPlayerIdle();
                else if(scene.equals("player-accessible-browse"))testAccessibleBrowse();
                else if(scene.endsWith("frame-pacing")){
                    if(scene.contains("nine")){runOnMainSync(()->context.getSharedPreferences("window",0).edit().putInt("desktopLyricsRows",9).apply());SystemClock.sleep(300);}
                    testFramePacing(scene.startsWith("player"),scene.contains("long"));
                }
                else if(scene.equals("player-manual-return")||scene.equals("player-no-motion-return"))testPlayerManual(motion);
                else if(scene.startsWith("player-"))testPlayer(scene.equals("player-neighbor-follow"),motion);
                else if(scene.equals("floating-ui-refresh"))testRefresh();
                else if(scene.startsWith("floating-stable-"))testRenderStability(scene.endsWith("raster"));
                else if(scene.equals("floating-nine-line-surfaces")){runOnMainSync(()->context.getSharedPreferences("window",0).edit().putInt("desktopLyricsRows",9).apply());SystemClock.sleep(300);testRenderStability(false);}
                else if(scene.equals("floating-idle-stops"))testIdleStops();
                else if(scene.equals("floating-touch-through"))testTouchThrough();
                else if(scene.equals("floating-cross-app-touch"))testCrossAppTouch();
                else testFloating(scene);
                capture(scene);report.append("PASS ").append(scene).append('\n');passed++;
            }catch(Throwable error){report.append("FAIL ").append(scene).append(": ").append(error).append('\n');capture(scene+"-failure");}
            finally{runOnMainSync(()->{if(floating!=null)floating.close();if(overlay!=null)overlay.close();floating=null;overlay=null;});}
        }
        Bundle result=new Bundle();result.putString("stream",report.toString());result.putString("artifactDir",screenshotDirectory.getAbsolutePath());result.putInt("passed",passed);result.putBoolean("uiPassed",passed==cases.length);finish(passed==cases.length?-1:0,result);
    }
    private void prepare(boolean motion,float x){
        try{
            final String prefix="lyric-"+SystemClock.uptimeMillis()+"-";
            final android.view.WindowManager realManager=(android.view.WindowManager)getTargetContext().getSystemService(Context.WINDOW_SERVICE);
            Object trackedManager=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{android.view.WindowManager.class},(proxy,method,args)->{
                long started=System.nanoTime();try{return method.invoke(realManager,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                finally{if(method.getName().equals("updateViewLayout")){windowMoves++;windowMoveNanos+=System.nanoTime()-started;}}
            });
            context=new ContextWrapper(getTargetContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return getTargetContext().getSharedPreferences(prefix+name,mode);}
                @Override public Object getSystemService(String name){return Context.WINDOW_SERVICE.equals(name)?trackedManager:super.getSystemService(name);}
            };
            context.getSharedPreferences("window",0).edit().putBoolean("desktopLyricsEnabled",true).putInt("desktopLyricsRows",3).putInt("desktopLyricsFont",28)
                .putFloat("desktopLyricsX",x).putFloat("desktopLyricsY",.35f).putBoolean("animationsEnabled",motion).putInt("lyricFontSize",18).apply();
            UiStrings.initialize(context);PlayerBridge.foreground=false;PlayerBridge.picking=false;
            service=new PlaybackService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);set(service,"destroyed",true);
            JSONArray lines=new JSONArray();for(int i=0;i<36;i++)lines.put(new JSONObject().put("time",i*1000).put("original",i==6?"A much longer line arrives without moving every other lyric sideways":"Line "+i).put("translation",""));
            ui=new JSONObject().put("lyricTrack","fixture").put("lyricLines",lines).put("lyricsMessage","");
            state=new JSONObject().put("currentTrack","fixture").put("position",3000).put("lyricOffset",0).put("duration",50000).put("title","Fixture").put("volume",70);
            floating=new FloatingLyricsWindow(service);floating.update(state,ui);
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void next(int position){try{state=new JSONObject(state.toString()).put("position",position);floating.update(state,ui);}catch(Exception e){throw new RuntimeException(e);}}
    private void testFloating(String scene){
        Map<String,View> before=new LinkedHashMap<>();float[] anchor={0};
        runOnMainSync(()->{before.putAll(visible());anchor[0]=((Number)get(floating,"centerX")).floatValue();next(4000);});
        if(scene.equals("floating-animated-identity"))SystemClock.sleep(180);else SystemClock.sleep(450);
        runOnMainSync(()->{
            Map<String,View> after=visible();View original=before.get("Line 3"),now=after.get("Line 3");
            require(original!=null&&now!=null,"common lyric remains visible");
            if(scene.equals("floating-horizontal-anchor"))require(Math.abs(((Number)get(floating,"centerX")).floatValue()-anchor[0])<1,"new long lyric must not move horizontal anchor");
            else require(original==now,"same lyric must retain its window through a line transition");
        });
        SystemClock.sleep(300);
        runOnMainSync(()->{float center=((Number)get(floating,"centerX")).floatValue();for(View view:visible().values()){android.graphics.Rect at=glyphBounds(view);require(Math.abs(at.exactCenterX()-center)<=2,"visible lyric stays centered on the shared anchor");}});
    }
    private void testRefresh(){
        runOnMainSync(()->next(4000));SystemClock.sleep(100);
        float[] before={0},after={0};
        runOnMainSync(()->{before[0]=((Number)get(floating,"centerIndex")).floatValue();try{ui=new JSONObject(ui.toString()).put("message","Unrelated library status");}catch(Exception e){throw new RuntimeException(e);}floating.update(state,ui);after[0]=((Number)get(floating,"centerIndex")).floatValue();});
        report.append("TRACE floating refresh ").append(before[0]).append(" -> ").append(after[0]).append('\n');
        require(before[0]>3&&before[0]<4,"sample during the actual Android animator");require(Math.abs(after[0]-before[0])<.0001,"unrelated UI update must not snap the in-flight lyric scroll");
    }
    private void testRenderStability(boolean raster){
        int[] resizes={0};float[] before={0},after={0};
        runOnMainSync(()->{
            View active=visible().get("Line 3");require(active!=null,"current line is visible");
            before[0]=((android.graphics.Paint)get(active,"paint")).getTextSize();
            for(View view:visible().values())if(!view.willNotDraw())view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)resizes[0]++;});
            if(!raster){View drawing=(View)get(floating,"drawing");require(drawing.isHardwareAccelerated(),"the shared drawing surface uses hardware composition");drawing.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)resizes[0]++;});}
            next(4000);after[0]=((android.graphics.Paint)get(active,"paint")).getTextSize();
        });
        SystemClock.sleep(600);
        report.append("TRACE raster ").append(before[0]).append(" -> ").append(after[0]).append("; visible surface resizes=").append(resizes[0]).append('\n');
        if(raster)require(Math.abs(before[0]-after[0])<.01,"line change must not abruptly rerasterize the centred text at a different size");
        else require(resizes[0]==0,"scroll must not resize visible text surfaces on animation frames");
    }
    private void testIdleStops(){
        int[] draws={0};runOnMainSync(()->((View)get(floating,"drawing")).getViewTreeObserver().addOnDrawListener(()->draws[0]++));
        for(int i=0;i<4;i++){SystemClock.sleep(100);runOnMainSync(()->next(3000));}
        require(draws[0]==0,"position ticks between lyric lines do not wake the drawing surface");
    }
    private void testOpacitySettings(){
        runOnMainSync(()->{
            context.getSharedPreferences("window",0).edit().putInt("desktopLyricsRows",5).putInt("desktopLyricsHighlightOpacity",80).putInt("desktopLyricsEdgeOpacity",20).apply();
            next(10000);
        });SystemClock.sleep(400);
        runOnMainSync(()->{
            require(Math.abs(((Number)get(visible().get("Line 10"),"opacity")).floatValue()-.8f)<.001f,"current row uses saved opacity");
            require(Math.abs(((Number)get(visible().get("Line 9"),"opacity")).floatValue()-.5f)<.001f,"middle row interpolates both endpoints");
            View outer=visible().get("Line 8");require(outer!=null,"edge row exists");require(Math.abs(((Number)get(outer,"opacity")).floatValue()-.2f)<.001f,"outer row uses saved edge opacity");
            View layer=(View)get(floating,"drawing");Bitmap bitmap=Bitmap.createBitmap(layer.getWidth(),layer.getHeight(),Bitmap.Config.ARGB_8888);layer.draw(new android.graphics.Canvas(bitmap));
            android.graphics.Rect bounds=glyphBounds(outer);android.view.WindowManager.LayoutParams p=(android.view.WindowManager.LayoutParams)get(floating,"drawingParams");int maximum=0;
            for(int y=Math.max(0,bounds.top-p.y);y<Math.min(bitmap.getHeight(),bounds.bottom-p.y);y++)for(int x=Math.max(0,bounds.left-p.x);x<Math.min(bitmap.getWidth(),bounds.right-p.x);x++)maximum=Math.max(maximum,android.graphics.Color.alpha(bitmap.getPixel(x,y)));
            bitmap.recycle();require(maximum>=45&&maximum<=53,"edge opacity is applied to actual drawing pixels");
            next(11000);
        });SystemClock.sleep(60);
        runOnMainSync(()->{
            Object animator=get(floating,"scroll");require(animator!=null&&((android.animation.ValueAnimator)animator).isRunning(),"adjust opacity during actual animation");
            float before=((Number)get(floating,"centerIndex")).floatValue();Object raster=get(visible().get("Line 10"),"normal");
            context.getSharedPreferences("window",0).edit().putInt("desktopLyricsHighlightOpacity",60).apply();
            require(get(floating,"scroll")==animator&&((android.animation.ValueAnimator)animator).isRunning(),"opacity change preserves the running animator");
            require(Math.abs(((Number)get(floating,"centerIndex")).floatValue()-before)<.0001f,"opacity change does not snap the current position");
            require(get(visible().get("Line 10"),"normal")==raster,"opacity change reuses the text raster");
        });SystemClock.sleep(350);
        runOnMainSync(()->{
            context.getSharedPreferences("window",0).edit().putInt("desktopLyricsEdgeOpacity",0).apply();
            View outer=(View)((Map<?,?>)get(floating,"windows")).get(9);require(outer!=null,"transparent edge keeps lyric identity");
            android.graphics.Rect at=glyphBounds(outer);android.graphics.Region touch=(android.graphics.Region)get(get(floating,"drawing"),"touchRegion");android.view.WindowManager.LayoutParams p=(android.view.WindowManager.LayoutParams)get(floating,"drawingParams");
            require(!touch.contains(Math.round(at.exactCenterX())-p.x,Math.round(at.exactCenterY())-p.y),"zero-opacity edge does not block taps");
            floating.close();floating=new FloatingLyricsWindow(service);floating.update(state,ui);
            require(Math.abs(((Number)get(visible().get("Line 11"),"opacity")).floatValue()-.6f)<.001f,"opacity persists when recreating the window");
        });
    }
    private void testLockRecenter(){
        runOnMainSync(()->context.getSharedPreferences("window",0).edit().putBoolean("desktopLyricsLocked",true).apply());
        float[] center={0};runOnMainSync(()->center[0]=((Number)get(floating,"centerX")).floatValue());
        runOnMainSync(()->{View target=(View)get(floating,"drawing");long down=SystemClock.uptimeMillis();for(int action:new int[]{0,2,1}){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down+action+1,action,200+(action==0?0:60),100,0);target.dispatchTouchEvent(event);event.recycle();}});
        runOnMainSync(()->{
            require(Math.abs(((Number)get(floating,"centerX")).floatValue()-center[0])<.001f,"locked lyrics resist dragging");
            context.getSharedPreferences("window",0).edit().putFloat("desktopLyricsX",.1f).putFloat("desktopLyricsY",.1f).apply();
            invoke(floating,"recenter");android.graphics.Rect area=(android.graphics.Rect)get(floating,"area");
            require(Math.abs(((Number)get(floating,"centerX")).floatValue()-area.exactCenterX())<1,"recenter restores horizontal position");
            require(Math.abs(context.getSharedPreferences("window",0).getFloat("desktopLyricsY",0)-.5f)<.001f,"center position is persisted");
            require(context.getSharedPreferences("window",0).getBoolean("desktopLyricsLocked",false),"recenter preserves lock");
        });
    }
    private void testFloatingSettingsUi(){
        runOnMainSync(()->{overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.openLyricSettings();});SystemClock.sleep(400);
        for(String language:new String[]{"zh_CN","en"})for(int mode:new int[]{1,2}){
            runOnMainSync(()->{UiStrings.setLanguage(context,language);context.getSharedPreferences("appearance",0).edit().putInt("mode",mode).apply();overlay.configurationChanged();});SystemClock.sleep(200);
            runOnMainSync(()->{
                View root=(View)get(overlay,"window");android.widget.SeekBar high=root.findViewWithTag("desktopLyricsHighlightOpacity"),edge=root.findViewWithTag("desktopLyricsEdgeOpacity");
                require(high!=null&&edge!=null,"both opacity sliders exist in the actual settings");require(high.getHeight()>=40*context.getResources().getDisplayMetrics().density,"slider keeps a usable touch height");
                high.setProgress(0);high.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                int peak=context.getSharedPreferences("window",0).getInt("desktopLyricsHighlightOpacity",100);
                require(peak>=10&&peak<100,"keyboard adjustment writes current-line opacity");require(edge.getProgress()<=peak&&edge.getMax()==peak,"edge control follows the current opacity limit");
                require(context.getSharedPreferences("window",0).getInt("desktopLyricsEdgeOpacity",35)<=peak,"clamped edge is saved with the current opacity");
                require(high.getContentDescription().toString().equals(UiStrings.text("高亮歌词可见度")),"visible controls follow the selected language");
                ((ScrollView)get(overlay,"detailScroll")).scrollTo(0,Math.max(0,high.getTop()-Math.round(30*context.getResources().getDisplayMetrics().density)));
            });SystemClock.sleep(160);capture("v15-native-settings-"+language+"-"+mode);
            runOnMainSync(()->{View root=(View)get(overlay,"window");android.widget.SeekBar edge=root.findViewWithTag("desktopLyricsEdgeOpacity");((ScrollView)get(overlay,"detailScroll")).scrollTo(0,edge.getTop());});SystemClock.sleep(160);capture("v15-native-position-"+language+"-"+mode);
        }
    }
    private void testEmptyTouchThrough(){
        crossAppInput=true;final int[] taps={0};
        android.content.BroadcastReceiver receiver=new android.content.BroadcastReceiver(){@Override public void onReceive(Context c,android.content.Intent intent){taps[0]++;}};
        android.content.IntentFilter filter=new android.content.IntentFilter("org.floatmusic.lyricfixture.TAP");
        if(android.os.Build.VERSION.SDK_INT>=33)getTargetContext().registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);else getTargetContext().registerReceiver(receiver,filter);
        try{
            getTargetContext().startActivity(new android.content.Intent().setComponent(new android.content.ComponentName("org.floatmusic.lyricbackdrop",Backdrop.class.getName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(300);
            float[] point={0,0};runOnMainSync(()->{
                context.getSharedPreferences("window",0).edit().putInt("desktopLyricsEdgeOpacity",0).apply();
                try{JSONArray lines=new JSONArray(ui.getJSONArray("lyricLines").toString());lines.getJSONObject(3).put("original","");ui=new JSONObject(ui.toString()).put("lyricLines",lines);}catch(Exception e){throw new RuntimeException(e);}
                floating.update(state,ui);require(visible().isEmpty(),"no text is visible in this timestamp slot");
                android.view.WindowManager.LayoutParams p=(android.view.WindowManager.LayoutParams)get(floating,"drawingParams");point[0]=p.x+p.width/2f;point[1]=p.y+p.height/2f;
            });SystemClock.sleep(200);touch(point[0],point[1],false);require(taps[0]==1,"an initially empty lyric surface passes clicks to another app");
        }finally{crossAppInput=false;getTargetContext().unregisterReceiver(receiver);try{getUiAutomation().executeShellCommand("am force-stop org.floatmusic.lyricbackdrop").close();}catch(Exception ignored){}}
    }
    private void testDragCache(){
        int[] rebuilt={0},moves={0};long[] elapsed={0};
        runOnMainSync(()->{
            context.getSharedPreferences("window",0).edit().putInt("desktopLyricsRows",9).apply();
            try{JSONArray lines=new JSONArray();for(int i=0;i<36;i++)lines.put(new JSONObject().put("time",i*1000).put("original","Line "+i));ui=new JSONObject(ui.toString()).put("lyricLines",lines);}catch(Exception e){throw new RuntimeException(e);}
            floating.update(state,ui);
            Map<Object,Object> bitmaps=new LinkedHashMap<>();for(Object glyph:((Map<?,?>)get(floating,"windows")).values())bitmaps.put(glyph,get(glyph,"normal"));
            View target=(View)get(floating,"drawing");long down=SystemClock.uptimeMillis();
            android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,200,100,0);target.dispatchTouchEvent(event);event.recycle();
            long started=System.nanoTime();
            for(int i=0;i<80;i++){
                event=android.view.MotionEvent.obtain(down,down+i+1,android.view.MotionEvent.ACTION_MOVE,240+i%20,100,0);target.dispatchTouchEvent(event);event.recycle();moves[0]++;
                for(Object glyph:bitmaps.keySet()){Object bitmap=get(glyph,"normal");if(bitmap!=bitmaps.get(glyph)){rebuilt[0]+=2;bitmaps.put(glyph,bitmap);}}
            }
            elapsed[0]=System.nanoTime()-started;
            event=android.view.MotionEvent.obtain(down,down+81,android.view.MotionEvent.ACTION_UP,259,100,0);target.dispatchTouchEvent(event);event.recycle();
        });
        report.append("TRACE drag moves=").append(moves[0]).append(", bitmap allocations=").append(rebuilt[0]).append(", mainThreadMs=").append(elapsed[0]/1000000.0).append('\n');
        require(rebuilt[0]==0,"moving short lyrics horizontally must reuse unchanged text bitmaps");
    }
    private void testCacheInvalidation(){
        runOnMainSync(()->{
            Object glyph=visible().get("Line 3");require(glyph!=null,"active glyph exists");
            Bitmap original=(Bitmap)get(glyph,"normal");float font=((android.graphics.Paint)get(glyph,"paint")).getTextSize();float center=((Number)get(floating,"centerX")).floatValue();
            android.graphics.Rect area=(android.graphics.Rect)get(floating,"area");set(floating,"centerX",area.left+32f);invoke(floating,"render");
            require(get(glyph,"normal")!=original,"narrow anchor refits text");require(((android.graphics.Paint)get(glyph,"paint")).getTextSize()<font,"constrained text shrinks");
            set(floating,"centerX",center);invoke(floating,"render");require(Math.abs(((android.graphics.Paint)get(glyph,"paint")).getTextSize()-font)<.01f,"expanding available width restores full text size");
            Object normal=get(glyph,"normal");context.getSharedPreferences("window",0).edit().putInt("desktopLyricsTheme",2).apply();
            require(get(glyph,"normal")!=normal,"theme changes invalidate raster colors");
            normal=get(glyph,"normal");context.getSharedPreferences("window",0).edit().putInt("desktopLyricsFont",36).apply();require(get(glyph,"normal")!=normal,"font changes invalidate glyph size");
        });
    }
    private void testPlayerRebuild(){
        testPlayer(true,true);
        runOnMainSync(()->{try{state=new JSONObject(state.toString()).put("position",16000);overlay.update(state);}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(55);
        runOnMainSync(()->{
            android.animation.ValueAnimator previous=(android.animation.ValueAnimator)get(overlay,"lyricFollow");require(previous!=null&&previous.isRunning(),"replace lyrics during a running scroll");
            try{ui=new JSONObject().put("lyricTrack","replacement").put("lyricLines",new JSONArray()).put("lyrics","").put("translation","").put("lyricsMessage","暂无逐句歌词");state=new JSONObject(state.toString()).put("currentTrack","replacement").put("position",0);}catch(Exception e){throw new RuntimeException(e);}
            overlay.update(state);overlay.refreshData(ui);
            require(!previous.isRunning(),"rebuilding lyrics cancels the previous track's animator");require(get(overlay,"lyricFollow")==null,"no stale follow animator remains");
        });
        for(int i=0;i<8;i++){SystemClock.sleep(16);runOnMainSync(()->{require(((View)get(overlay,"lyricRows")).getTranslationY()==0,"new empty state has no stale subpixel offset");require(((ScrollView)get(overlay,"lyricScroll")).getScrollY()==0,"new empty state remains visible at the top");});}
    }
    /** Measures the same native views on a real display, including input-window work. */
    private void testFramePacing(boolean player,boolean longLines){
        if(longLines)runOnMainSync(()->{try{
            JSONArray lines=new JSONArray();for(int i=0;i<160;i++)lines.put(new JSONObject().put("time",i*1000).put("original",i%2==0?"让每一段旋律轻轻流动，在长长的句子里也能读到自然与从容，第 "+i+" 句\nKeep the music moving softly through a longer bilingual line":"轻轻流动 · "+i).put("translation",""));
            ui=new JSONObject(ui.toString()).put("lyricLines",lines);
        }catch(Exception error){throw new RuntimeException(error);}});
        if(player)testPlayer(true,true);
        java.util.ArrayList<Long> draws=new java.util.ArrayList<>();
        java.util.ArrayList<Long> vsyncs=new java.util.ArrayList<>();
        View[] surface={null};boolean[] collecting={false},hardware={false};
        int[] layouts={0},columnContentHeight={0};float[] progress={0};long[] early={0};
        android.view.ViewTreeObserver.OnDrawListener listener=()->{if(collecting[0])draws.add(System.nanoTime());};
        android.view.Choreographer.FrameCallback clock=new android.view.Choreographer.FrameCallback(){public void doFrame(long time){if(collecting[0]){vsyncs.add(time);android.view.Choreographer.getInstance().postFrameCallback(this);}}};
        runOnMainSync(()->{
            surface[0]=player?(View)get(overlay,"window"):(View)get(floating,"drawing");hardware[0]=surface[0].isHardwareAccelerated();
            surface[0].getViewTreeObserver().addOnDrawListener(listener);
            if(player){View column=(View)get(overlay,"lyricRows");columnContentHeight[0]=column.getHeight()-column.getPaddingTop()-column.getPaddingBottom();
                column.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int content=b-t-v.getPaddingTop()-v.getPaddingBottom();if(collecting[0]){if(content!=columnContentHeight[0])layouts[0]++;report.append("TRACE column bounds ").append(or-ol).append('x').append(ob-ot).append(" -> ").append(r-l).append('x').append(b-t).append("; content ").append(columnContentHeight[0]).append(" -> ").append(content).append('\n');}columnContentHeight[0]=content;});
                for(Object item:(java.util.List<?>)get(overlay,"lyricTexts")){android.widget.TextView line=(android.widget.TextView)item;line.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(collecting[0]&&(b-t!=ob-ot||r-l!=or-ol))report.append("TRACE row metrics ").append(line.getText().toString().replace('\n',' ')).append(' ').append(or-ol).append('x').append(ob-ot).append(" -> ").append(r-l).append('x').append(b-t).append('\n');});}}
            windowMoves=0;windowMoveNanos=0;
        });
        java.util.ArrayList<Double> gaps=new java.util.ArrayList<>();
        java.util.ArrayList<Double> clockGaps=new java.util.ArrayList<>();
        for(int turn=0;turn<6;turn++){
            final int target=player?turn+2:turn+4;final float[] from={0};
            runOnMainSync(()->{
                draws.clear();vsyncs.clear();collecting[0]=true;android.view.Choreographer.getInstance().postFrameCallback(clock);
                if(player){from[0]=((ScrollView)get(overlay,"lyricScroll")).getScrollY();try{state=new JSONObject(state.toString()).put("position",target*1000);}catch(Exception error){throw new RuntimeException(error);}overlay.update(state);}
                else{from[0]=((Number)get(floating,"centerIndex")).floatValue();next(target*1000);}
            });
            SystemClock.sleep(160);
            runOnMainSync(()->{
                if(player){android.animation.ValueAnimator animator=(android.animation.ValueAnimator)get(overlay,"lyricFollow");if(animator!=null)progress[0]+=animator.getAnimatedFraction();}
                else progress[0]+=(((Number)get(floating,"centerIndex")).floatValue()-from[0])/(target-from[0]);
            });
            SystemClock.sleep(160);
            runOnMainSync(()->{
                android.animation.ValueAnimator animator=(android.animation.ValueAnimator)get(player?overlay:floating,player?"lyricFollow":"scroll");
                if(animator==null||!animator.isRunning())early[0]++;
            });
            SystemClock.sleep(220);
            runOnMainSync(()->{
                collecting[0]=false;for(int i=1;i<draws.size();i++)gaps.add((draws.get(i)-draws.get(i-1))/1000000.0);for(int i=1;i<vsyncs.size();i++)clockGaps.add((vsyncs.get(i)-vsyncs.get(i-1))/1000000.0);
            });
        }
        runOnMainSync(()->surface[0].getViewTreeObserver().removeOnDrawListener(listener));
        java.util.Collections.sort(gaps);double p95=gaps.isEmpty()?999:gaps.get(Math.min(gaps.size()-1,(int)(gaps.size()*.95)));
        java.util.Collections.sort(clockGaps);double clock95=clockGaps.isEmpty()?999:clockGaps.get(Math.min(clockGaps.size()-1,(int)(clockGaps.size()*.95)));
        int slow=0;for(double gap:gaps)if(gap>32)slow++;
        report.append("TRACE ").append(player?"player":"floating").append(" pacing: hardware=").append(hardware[0]).append(", drawGaps=").append(gaps.size()).append(", p95ms=").append(p95).append(", vsyncP95ms=").append(clock95).append(", gapsOver32ms=").append(slow).append(", windowMoves=").append(windowMoves).append(", windowMoveMs=").append(windowMoveNanos/1000000.0).append(", layouts=").append(layouts[0]).append(", progressAt160ms=").append(progress[0]/6).append(", settledBy320ms=").append(early[0]).append("/6\n");
        require(hardware[0],"scrolling lyrics must use hardware rendering on the actual overlay window");
        if(player)require(layouts[0]==0,"highlighting a sentence must not change the measured height of the entire lyric column");
        require(early[0]==6,"each ordinary lyric change settles within 320ms instead of lingering");
        if(!player)require(windowMoves<=36,"ordinary scrolling must not transact native input-window bounds on every frame");
        require(gaps.size()>=24,"measure multiple presented draw callbacks across ordinary line changes");
        require(p95<=35,"95 percent of lyric drawing intervals stay within two 60Hz display frames plus sampling tolerance");
        if(longLines){runOnMainSync(()->{try{state=new JSONObject(state.toString()).put("position",6000);overlay.update(state);}catch(Exception error){throw new RuntimeException(error);}});SystemClock.sleep(350);runOnMainSync(()->{
            android.widget.TextView active=(android.widget.TextView)((java.util.List<?>)get(overlay,"lyricTexts")).get((Integer)get(overlay,"activeLyric"));
            android.text.Layout layout=active.getLayout();
            for(int i=0;i<layout.getLineCount();i++)require(active.getPaddingLeft()+layout.getLineRight(i)*active.getScaleX()<=active.getWidth()+1,"enlarged long text must remain within the lyric viewport");
        });}
    }
    private void testPlayerIdle(){
        testPlayer(true,true);int[] layouts={0};View[] root={null};
        android.view.ViewTreeObserver.OnGlobalLayoutListener listener=()->layouts[0]++;
        runOnMainSync(()->{root[0]=(View)get(overlay,"window");root[0].getViewTreeObserver().addOnGlobalLayoutListener(listener);});
        for(int i=0;i<4;i++){SystemClock.sleep(250);runOnMainSync(()->overlay.update(state));}
        runOnMainSync(()->root[0].getViewTreeObserver().removeOnGlobalLayoutListener(listener));
        report.append("TRACE player idle global layouts=").append(layouts[0]).append('\n');
        require(layouts[0]==0,"unchanged playback ticks must not remeasure the complete player window");
    }
    private void testAccessibleBrowse(){
        testPlayer(true,true);int[] before={0};
        runOnMainSync(()->{ScrollView viewport=(ScrollView)get(overlay,"lyricScroll");before[0]=viewport.getScrollY();require(viewport.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null),"native accessibility accepts forward browsing");});
        SystemClock.sleep(450);
        runOnMainSync(()->{require((Boolean)get(overlay,"manualLyrics"),"accessibility browsing takes over automatic follow");require(((ScrollView)get(overlay,"lyricScroll")).getScrollY()>before[0]+5,"accessibility browsing actually moves the lyrics");((View)get(overlay,"lyricReturn")).performClick();});
        SystemClock.sleep(400);
        runOnMainSync(()->{ScrollView viewport=(ScrollView)get(overlay,"lyricScroll");before[0]=viewport.getScrollY();viewport.executeKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_PAGE_DOWN));});
        SystemClock.sleep(450);
        runOnMainSync(()->{require((Boolean)get(overlay,"manualLyrics"),"keyboard browsing takes over automatic follow");require(((ScrollView)get(overlay,"lyricScroll")).getScrollY()>before[0]+5,"keyboard browsing actually moves the lyrics");});
    }
    public static final class Backdrop extends android.app.Activity {
        static int taps;
        @Override public void onCreate(Bundle state){super.onCreate(state);taps=0;View view=new View(this);view.setBackgroundColor(0xffe8edf5);view.setOnTouchListener((v,event)->{if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){taps++;sendBroadcast(new android.content.Intent("org.floatmusic.lyricfixture.TAP").setPackage("org.floatmusic.lyricfixture"));}return true;});setContentView(view);}
    }
    private void testTouchThrough(){
        android.content.Intent intent=new android.content.Intent().setComponent(new android.content.ComponentName(getTargetContext(),Backdrop.class)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        Backdrop backdrop=(Backdrop)startActivitySync(intent);SystemClock.sleep(350);
        float[] point=new float[4];
        runOnMainSync(()->{Map<String,View> labels=visible();android.graphics.Rect a=glyphBounds(labels.get("Line 3")),b=glyphBounds(labels.get("Line 4"));point[0]=a.exactCenterX();point[1]=a.exactCenterY();point[2]=(a.bottom+b.top)/2f;point[3]=((Number)get(floating,"centerX")).floatValue();});
        touch(point[0],point[1],false);require(Backdrop.taps==0,"lyric text receives touches instead of the app behind");
        touch(point[0],point[2],false);require(Backdrop.taps==1,"blank space between lines passes through to the real app behind");
        touch(point[0],point[1],true);SystemClock.sleep(200);
        runOnMainSync(()->require(((Number)get(floating,"centerX")).floatValue()>point[3]+10,"transparent hit windows still allow dragging the text"));
        require(Backdrop.taps==1,"dragging text does not click the app behind");runOnMainSync(backdrop::finish);
    }
    private void testCrossAppTouch(){
        crossAppInput=true;
        final int[] taps={0};android.content.BroadcastReceiver receiver=new android.content.BroadcastReceiver(){@Override public void onReceive(Context c,android.content.Intent intent){taps[0]++;}};
        android.content.IntentFilter filter=new android.content.IntentFilter("org.floatmusic.lyricfixture.TAP");
        if(android.os.Build.VERSION.SDK_INT>=33)getTargetContext().registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);else getTargetContext().registerReceiver(receiver,filter);
        try{
            getTargetContext().startActivity(new android.content.Intent().setComponent(new android.content.ComponentName("org.floatmusic.lyricbackdrop",Backdrop.class.getName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(450);
            float[] point=new float[4];
            runOnMainSync(()->{Map<String,View> labels=visible();android.graphics.Rect a=glyphBounds(labels.get("Line 3")),b=glyphBounds(labels.get("Line 4"));point[0]=a.exactCenterX();point[1]=a.exactCenterY();point[2]=(a.bottom+b.top)/2f;point[3]=((Number)get(floating,"centerX")).floatValue();});
            touch(point[0],point[1],false);require(taps[0]==0,"another app does not receive text taps");
            touch(point[0],point[2],false);require(taps[0]==1,"blank lyric pixels pass through to a distinct app UID");
            touch(point[0],point[1],true);SystemClock.sleep(200);
            runOnMainSync(()->require(((Number)get(floating,"centerX")).floatValue()>point[3]+10,"text drags above another app"));
            require(taps[0]==1,"dragging does not click the other app");
        }finally{crossAppInput=false;getTargetContext().unregisterReceiver(receiver);try{getUiAutomation().executeShellCommand("am force-stop org.floatmusic.lyricbackdrop").close();}catch(Exception ignored){}}
    }
    private void injectPointer(android.view.MotionEvent event){if(crossAppInput)require(getUiAutomation().injectInputEvent(event,true),"system injects a cross-app test touch");else sendPointerSync(event);}
    private void touch(float x,float y,boolean drag){long down=SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,x,y,0);injectPointer(event);event.recycle();if(drag){SystemClock.sleep(40);event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,x+24,y+18,0);injectPointer(event);event.recycle();}SystemClock.sleep(40);event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x+(drag?24:0),y+(drag?18:0),0);injectPointer(event);event.recycle();SystemClock.sleep(80);}
    private void testPlayer(boolean neighbor,boolean motion){
        runOnMainSync(()->{floating.close();floating=null;if(neighbor)try{state.put("position",0);}catch(Exception e){throw new RuntimeException(e);}overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.show();invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"lyrics");});
        SystemClock.sleep(600);
        int[] positions=new int[4];
        java.util.LinkedHashSet<Integer> frames=new java.util.LinkedHashSet<>();
        runOnMainSync(()->{ScrollView viewport=(ScrollView)get(overlay,"lyricScroll");positions[0]=viewport.getScrollY();frames.add(positions[0]);viewport.getViewTreeObserver().addOnScrollChangedListener(()->frames.add(viewport.getScrollY()));try{state=new JSONObject(state.toString()).put("position",neighbor?1000:16000);}catch(Exception e){throw new RuntimeException(e);}overlay.update(state);});
        SystemClock.sleep(70);runOnMainSync(()->positions[1]=((ScrollView)get(overlay,"lyricScroll")).getScrollY());
        SystemClock.sleep(100);runOnMainSync(()->positions[2]=((ScrollView)get(overlay,"lyricScroll")).getScrollY());
        SystemClock.sleep(330);runOnMainSync(()->positions[3]=((ScrollView)get(overlay,"lyricScroll")).getScrollY());
        report.append("TRACE player scroll ").append(java.util.Arrays.toString(positions)).append("; frames=").append(frames).append('\n');
        require(positions[3]>positions[0]+5,"player follows each timed lyric, including the first two lines");
        if(motion)require(frames.size()>=3,"player scroll shows intermediate frames instead of one jump");
        else require(frames.size()<=2,"disabled motion jumps directly to the centered target");
    }
    private void testPlayerManual(boolean motion){
        testPlayer(false,motion);
        runOnMainSync(()->{set(overlay,"manualLyrics",true);((ScrollView)get(overlay,"lyricScroll")).scrollTo(0,0);try{state=new JSONObject(state.toString()).put("position",22000);}catch(Exception e){throw new RuntimeException(e);}overlay.update(state);});
        SystemClock.sleep(450);
        runOnMainSync(()->{require(((ScrollView)get(overlay,"lyricScroll")).getScrollY()==0,"manual browsing is not pulled back by playback ticks");View back=(View)get(overlay,"lyricReturn");require(back.getVisibility()==View.VISIBLE,"manual browsing offers return-to-current");back.performClick();});
        SystemClock.sleep(650);
        runOnMainSync(()->{require(((ScrollView)get(overlay,"lyricScroll")).getScrollY()>200,"return resumes follow at the current sentence");require(!(Boolean)get(overlay,"manualLyrics"),"return restores automatic follow");});
    }
    private void testPlayerBottom(){
        for(int font:new int[]{16,18,30})for(float spacing:new float[]{.8f,1f,1.5f}){
            runOnMainSync(()->{
                if(floating!=null){floating.close();floating=null;}if(overlay!=null)overlay.close();
                context.getSharedPreferences("window",0).edit().putInt("lyricMode",font==16?0:2).putInt("lyricFontSize",font).putFloat("lyricLineSpacing",spacing).apply();
                try{JSONArray lines=new JSONArray();for(int i=0;i<48;i++)lines.put(new JSONObject().put("time",i*1000).put("original",i==47?"最后一句完整显示":"向前走，第 "+i+" 句").put("translation",font==16?"":i==47?"The final lyric, including gyp and its descending strokes, stays fully visible.":"Keep moving · "+i));ui=new JSONObject(ui.toString()).put("lyricLines",lines);}catch(Exception e){throw new RuntimeException(e);}
                overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.show();invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"lyrics");
            });SystemClock.sleep(450);
            swipePlayerLyrics(true);swipePlayerLyrics(false);swipePlayerLyrics(true);
            runOnMainSync(()->{ScrollView viewport=(ScrollView)get(overlay,"lyricScroll");viewport.fling(10000);});SystemClock.sleep(1200);
            runOnMainSync(()->{
                java.util.List<?> texts=(java.util.List<?>)get(overlay,"lyricTexts");android.widget.TextView last=(android.widget.TextView)texts.get(texts.size()-1);ScrollView viewport=(ScrollView)get(overlay,"lyricScroll");View rows=(View)get(overlay,"lyricRows");
                android.graphics.Rect clip=new android.graphics.Rect();boolean shown=last.getGlobalVisibleRect(clip);android.text.Layout layout=last.getLayout();int textBottom=last.getPaddingTop()+layout.getLineBottom(layout.getLineCount()-1);int[] at=new int[2];last.getLocationInWindow(at);float actualBottom=at[1]+textBottom*last.getScaleY()*((Number)get(overlay,"scale")).floatValue();
                report.append("TRACE bottom font=").append(font).append(", spacing=").append(spacing).append(", scroll=").append(viewport.getScrollY()).append(", viewport=").append(viewport.getHeight()).append(", column=").append(rows.getHeight()).append(", padding=").append(rows.getPaddingBottom()).append(", last=").append(last.getTop()).append("..").append(last.getBottom()).append(", visible=").append(clip).append(", textBottom=").append(textBottom).append(", location=").append(java.util.Arrays.toString(at)).append(", scale=").append(last.getScaleY()).append("*").append(get(overlay,"scale")).append(", actualBottom=").append(actualBottom).append(", shift=").append(rows.getTranslationY()).append('\n');
                require(shown&&clip.bottom+2>=actualBottom,"last lyric glyphs remain fully visible after manually browsing to the bottom");
                require((Boolean)get(overlay,"manualLyrics"),"dragging lyric text starts manual browsing even when text handles long presses");
                Bitmap overflow=Bitmap.createBitmap(last.getWidth(),last.getHeight()+Math.round(last.getTextSize()*2),Bitmap.Config.ARGB_8888);layout.draw(new android.graphics.Canvas(overflow));int extraInk=0;
                for(int y=last.getHeight()-last.getPaddingTop();y<overflow.getHeight();y++)for(int x=0;x<overflow.getWidth();x++)if(android.graphics.Color.alpha(overflow.getPixel(x,y))>16)extraInk++;
                overflow.recycle();report.append("TRACE glyph pixels beyond last row=").append(extraInk).append('\n');require(extraInk==0,"last TextView reserves enough height for its actual font glyphs");
            });capture("bottom-font"+font+"-spacing"+spacing);
        }
    }
    private void swipePlayerLyrics(boolean up){
        float[] bounds=new float[3];runOnMainSync(()->{ScrollView viewport=(ScrollView)get(overlay,"lyricScroll");View root=(View)get(overlay,"window");int[] origin=new int[2],position=new int[2];root.getLocationOnScreen(origin);viewport.getLocationOnScreen(position);float scale=((Number)get(overlay,"scale")).floatValue();bounds[0]=origin[0]+root.getWidth()/2f;bounds[1]=Math.max(origin[1]+20,position[1]+20);bounds[2]=Math.min(origin[1]+root.getHeight()-24,position[1]+viewport.getHeight()*scale-20);require(bounds[2]>bounds[1],"lyric viewport has a visible drag region");});
        long down=SystemClock.uptimeMillis();float start=up?bounds[2]:bounds[1],finish=up?bounds[1]:bounds[2];
        for(int i=0;i<=11;i++){int action=i==0?0:i==11?1:2;android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds[0],start+(finish-start)*Math.min(10,i)/10,0);sendPointerSync(event);event.recycle();SystemClock.sleep(12);}SystemClock.sleep(80);
    }
    private void showCopyPlayer(int mode,boolean empty){
        runOnMainSync(()->{
            if(floating!=null){floating.close();floating=null;}if(overlay!=null)overlay.close();
            context.getSharedPreferences("window",0).edit().putInt("lyricMode",mode).apply();
            try{JSONArray lines=new JSONArray();if(!empty){lines.put(new JSONObject().put("time",0).put("original","第一句 <b>原文</b> 🌙").put("translation","First line"));lines.put(new JSONObject().put("time",1000).put("original","第二句").put("translation",""));lines.put(new JSONObject().put("time",2000).put("original","结尾 gyp").put("translation","Last line"));}ui=new JSONObject(ui.toString()).put("lyricLines",lines).put("lyrics","").put("translation","");}catch(Exception e){throw new RuntimeException(e);}
            overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.show();invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"lyrics");
        });SystemClock.sleep(400);
    }
    private void longPress(View target){
        float[] at={0,0};runOnMainSync(()->{android.graphics.Rect bounds=new android.graphics.Rect();require(target.getGlobalVisibleRect(bounds),"long-press target is visible");int[] origin=new int[2];((View)get(overlay,"window")).getLocationOnScreen(origin);at[0]=origin[0]+bounds.exactCenterX();at[1]=origin[1]+bounds.exactCenterY();});
        long down=SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,0,at[0],at[1],0);sendPointerSync(event);event.recycle();SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+160);
        event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,at[0],at[1],0);sendPointerSync(event);event.recycle();SystemClock.sleep(120);
    }
    private android.content.ClipboardManager clipboard(){return (android.content.ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);}
    private String copied(){android.content.ClipData data=clipboard().getPrimaryClip();return data==null?"":String.valueOf(data.getItemAt(0).getText());}
    private void testCopyAllLyrics(){
        getTargetContext().startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(getTargetContext(),Backdrop.class)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(200);
        String[] expected={"第一句 <b>原文</b> 🌙\n第二句\n结尾 gyp","First line\nLast line","第一句 <b>原文</b> 🌙\nFirst line\n第二句\n结尾 gyp\nLast line"};
        for(int mode=0;mode<3;mode++){
            showCopyPlayer(mode,false);View[] target={null};runOnMainSync(()->{clipboard().setPrimaryClip(android.content.ClipData.newPlainText("test","keep clipboard"));target[0]=(View)((java.util.List<?>)get(overlay,"lyricTexts")).get(2);});
            longPress(target[0]);String actual=copied();require(expected[mode].equals(actual),"long-pressing any line copies the complete current-mode song: "+actual);
        }
        showCopyPlayer(0,true);runOnMainSync(()->clipboard().setPrimaryClip(android.content.ClipData.newPlainText("test","keep clipboard")));
        View[] placeholder={null};runOnMainSync(()->placeholder[0]=((android.view.ViewGroup)get(overlay,"lyricRows")).getChildAt(0));longPress(placeholder[0]);require(copied().equals("keep clipboard"),"empty lyric placeholder never overwrites the clipboard");
    }
    private void testCopyDescription(){
        getTargetContext().startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(getTargetContext(),Backdrop.class)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(200);
        String description="收藏夹 <b>原样</b> 🌙\n"+new String(new char[40]).replace("\0","长简介原文保持完整，")+"\n最后一个字。";
        runOnMainSync(()->{
            if(floating!=null){floating.close();floating=null;}
            try{ui=new JSONObject(ui.toString()).put("activePlaylist","favorites").put("playlists",new JSONArray().put(new JSONObject().put("id","favorites").put("name","收藏夹").put("description",description))).put("tracks",new JSONArray());}catch(Exception e){throw new RuntimeException(e);}
            overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.show();invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"playlist");clipboard().setPrimaryClip(android.content.ClipData.newPlainText("test","keep clipboard"));
        });SystemClock.sleep(350);View[] target={null};runOnMainSync(()->target[0]=(View)get(overlay,"playlistDescription"));longPress(target[0]);require(copied().equals(description),"long press copies the complete untruncated description");
        runOnMainSync(()->{try{ui.getJSONArray("playlists").getJSONObject(0).put("description","");}catch(Exception e){throw new RuntimeException(e);}invoke(overlay,"updatePlaylistHeader");clipboard().setPrimaryClip(android.content.ClipData.newPlainText("test","keep clipboard"));});longPress(target[0]);require(copied().equals("keep clipboard"),"placeholder description never overwrites the clipboard");
    }
    private void testCopyDrag(){
        getTargetContext().startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(getTargetContext(),Backdrop.class)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(200);showCopyPlayer(0,false);
        float[] start={0};runOnMainSync(()->{clipboard().setPrimaryClip(android.content.ClipData.newPlainText("test","keep clipboard"));ScrollView viewport=(ScrollView)get(overlay,"lyricScroll");View line=(View)((java.util.List<?>)get(overlay,"lyricTexts")).get(1);start[0]=line.getTop()-viewport.getScrollY()+line.getHeight()/2f;});
        long down=SystemClock.uptimeMillis();for(int i=0;i<=11;i++){final int step=i;runOnMainSync(()->{int action=step==0?0:step==11?1:2;android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,30,start[0]-Math.min(10,step)*7,0);((ScrollView)get(overlay,"lyricScroll")).dispatchTouchEvent(event);event.recycle();});SystemClock.sleep(12);}SystemClock.sleep(650);
        require(copied().equals("keep clipboard"),"scrolling across lyric text does not trigger copying");runOnMainSync(()->require((Boolean)get(overlay,"manualLyrics"),"starting a drag on copyable lyric text enters manual browsing"));
    }
    private void testCopyRanking(){
        getTargetContext().startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(getTargetContext(),Backdrop.class)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(200);
        // Earlier clipboard cases also show a system-owned preview over the
        // lower card. It must expire before the first gesture in this case.
        SystemClock.sleep(7000);
        String description="榜单完整简介 <b>原样</b> 🌙\n第二行说明。";
        View[] target={null};runOnMainSync(()->{
            if(floating!=null){floating.close();floating=null;}
            try{ui=new JSONObject(ui.toString()).put("rankings",new JSONArray().put(new JSONObject().put("id","netease:fixture").put("name","测试榜单").put("description",description)));}catch(Exception e){throw new RuntimeException(e);}
            overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.show();invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"rankings");
            target[0]=findText((View)get(overlay,"window"),description);require(target[0]!=null,"ranking description exists");
        });SystemClock.sleep(350);runOnMainSync(()->((ScrollView)get(overlay,"detailScroll")).fullScroll(View.FOCUS_DOWN));SystemClock.sleep(400);longPress(target[0]);require(copied().equals(description),"ranking long press copies its complete description");
        runOnMainSync(()->{require("rankings".equals(get(overlay,"detail")),"long press does not navigate away from rankings");require("".equals(get(overlay,"requestedOnlineId")),"long press does not request online content");});
        // Android 13's clipboard preview is a separate system window. Let it
        // disappear before injecting another app-targeted pointer gesture.
        SystemClock.sleep(7000);
        float[] at={0,0};runOnMainSync(()->{android.graphics.Rect bounds=new android.graphics.Rect();require(target[0].getGlobalVisibleRect(bounds),"short-tap target stays visible");int[] origin=new int[2];((View)get(overlay,"window")).getLocationOnScreen(origin);at[0]=origin[0]+bounds.exactCenterX();at[1]=origin[1]+bounds.exactCenterY();});
        long down=SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,0,at[0],at[1],0);sendPointerSync(event);event.recycle();SystemClock.sleep(40);event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,at[0],at[1],0);sendPointerSync(event);event.recycle();SystemClock.sleep(150);
        runOnMainSync(()->require("netease:fixture".equals(get(overlay,"requestedOnlineId")),"short tapping a copyable ranking description still opens its card"));
    }
    private void testDescriptionFold(){
        String description=new String(new char[12]).replace("\0","完整简介，保留换行。\n")+"最后一段。";
        runOnMainSync(()->{
            floating.close();floating=null;
            try{ui.put("activePlaylist","favorites").put("playlists",new JSONArray().put(new JSONObject().put("id","favorites").put("name","收藏夹").put("description",description))).put("tracks",new JSONArray());}catch(Exception e){throw new RuntimeException(e);}
            overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.show();invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"playlist");
        });SystemClock.sleep(350);runOnMainSync(()->{View fold=((View)get(overlay,"window")).findViewWithTag("descriptionToggle");require(fold.getHeight()>=40*context.getResources().getDisplayMetrics().density,"the expansion button has an actual touchable layout height");});capture("description-collapsed");
        runOnMainSync(()->{View block=((View)get(overlay,"window")).findViewWithTag("localDescription");android.widget.TextView words=(android.widget.TextView)get(overlay,"playlistDescription");View fold=block.findViewWithTag("descriptionToggle");require(fold.getVisibility()==View.VISIBLE,"long description offers an expansion button");require(words.getMaxLines()==2,"local description starts collapsed");fold.performClick();});SystemClock.sleep(180);capture("description-expanded");
        runOnMainSync(()->{android.widget.TextView words=(android.widget.TextView)get(overlay,"playlistDescription");require(words.getMaxLines()==Integer.MAX_VALUE,"expanded description permits all lines");require(description.contentEquals(words.getText()),"expansion preserves the complete original");invoke(overlay,"updatePlaylistHeader");require(words.getMaxLines()==Integer.MAX_VALUE,"unrelated header refresh preserves expansion");((View)get(overlay,"window")).findViewWithTag("descriptionToggle").performClick();require(words.getMaxLines()==2,"collapse restores its line budget");try{ui.getJSONArray("playlists").getJSONObject(0).put("description","短简介");}catch(Exception e){throw new RuntimeException(e);}invoke(overlay,"updatePlaylistHeader");});SystemClock.sleep(180);
        runOnMainSync(()->{require(((View)get(overlay,"window")).findViewWithTag("descriptionToggle").getVisibility()==View.GONE,"short content does not show an expansion button");try{ui.put("onlinePlaylist",new JSONObject().put("id","fixture").put("name","在线歌单").put("description",description).put("tracks",new JSONArray()));}catch(Exception e){throw new RuntimeException(e);}set(overlay,"requestedOnlineId","fixture");set(overlay,"section","more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"online");});SystemClock.sleep(180);
        runOnMainSync(()->{View block=((View)get(overlay,"window")).findViewWithTag("onlineDescription");require(block!=null,"online details use the same disclosure control");View fold=block.findViewWithTag("descriptionToggle");require(fold.getVisibility()==View.VISIBLE,"long online description can expand");fold.performClick();fold.performClick();});
    }
    private void testSettingsNavigation(){
        runOnMainSync(()->{floating.close();floating=null;overlay=new OverlayWindow(service);set(overlay,"ui",ui);set(overlay,"playback",state);overlay.show();invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"settings");});SystemClock.sleep(180);
        for(String page:new String[]{"general","playback","lyrics","floating","service","about"}){
            runOnMainSync(()->{View root=(View)get(overlay,"window");View entry=root.findViewWithTag("settingsCategory_"+page);require(entry!=null,"settings entry exists: "+page);entry.performClick();require(page.equals(get(overlay,"settingsPage")),"entry opens its category: "+page);require(root.findViewWithTag("settingsCategory_general")==null,"subpage hides the category index");root.findViewWithTag("settingsBack").performClick();require("".equals(get(overlay,"settingsPage")),"back returns to settings index");});
        }
        for(String language:new String[]{"zh_CN","en"})for(int mode:new int[]{1,2}){runOnMainSync(()->{UiStrings.setLanguage(context,language);context.getSharedPreferences("appearance",0).edit().putInt("mode",mode).apply();overlay.configurationChanged();});SystemClock.sleep(200);capture("settings-index-"+language+"-"+mode);}
        runOnMainSync(()->{overlay.openLyricSettings();require("floating".equals(get(overlay,"settingsPage")),"lyric context opens its dedicated subpage");require(((View)get(overlay,"window")).findViewWithTag("desktopLyricsHighlightOpacity")!=null,"lyric controls remain accessible");});capture("settings-floating-page");
    }
    private static View findText(View view,String text){
        if(view instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)view).getText()))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View match=findText(group.getChildAt(i),text);if(match!=null)return match;}}return null;
    }
    private Map<String,View> visible(){
        Object pool=get(floating,"windows");Collection<?> values=pool instanceof Map?((Map<?,?>)pool).values():(Collection<?>)pool;
        Map<String,View> result=new LinkedHashMap<>();for(Object item:values){View view=(View)item;if(view.getVisibility()==View.VISIBLE&&view.getWidth()>1&&view.getHeight()>1)result.put(String.valueOf(get(view,"words")),view);}return result;
    }
    private android.graphics.Rect glyphBounds(View glyph){
        android.view.WindowManager.LayoutParams p=(android.view.WindowManager.LayoutParams)get(glyph,"params");return new android.graphics.Rect(p.x,p.y,p.x+p.width,p.y+p.height);
    }
    private void capture(String name){try{Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)return;try(FileOutputStream output=new FileOutputStream(new File(screenshotDirectory,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();}catch(Exception ignored){}}
    private static Object get(Object object,String name){try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(Exception e){throw new RuntimeException(e);}}
    private static void set(Object object,String name,Object value){try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}catch(Exception e){throw new RuntimeException(e);}}
    private static void invoke(Object object,String name){invoke(object,name,new Class<?>[0]);}
    private static void invoke(Object object,String name,Class<?>[] types,Object... values){try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(object,values);}catch(Exception e){throw new RuntimeException(e);}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
