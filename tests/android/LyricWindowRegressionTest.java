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
        String[] cases=selectedCases.isEmpty()?new String[]{"floating-no-motion-identity","floating-animated-identity","floating-horizontal-anchor","floating-ui-refresh","floating-stable-raster","floating-stable-surfaces","floating-nine-line-surfaces","floating-idle-stops","floating-touch-through","floating-cross-app-touch","floating-frame-pacing","floating-nine-frame-pacing","player-frame-pacing","player-long-frame-pacing","player-idle-layout","player-animated-follow","player-neighbor-follow","player-manual-return","player-no-motion-return","player-accessible-browse","player-no-motion-follow"}:selectedCases.split(",");int passed=0;
        for(String scene:cases){
            Bundle progress=new Bundle();progress.putString("currentCase",scene);sendStatus(0,progress);
            try{
                boolean motion=!scene.equals("floating-no-motion-identity")&&!scene.equals("floating-horizontal-anchor")&&!scene.startsWith("player-no-motion-");
                runOnMainSync(()->prepare(motion,scene.equals("floating-horizontal-anchor")?.20f:.5f));SystemClock.sleep(500);
                if(scene.equals("player-idle-layout"))testPlayerIdle();
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
