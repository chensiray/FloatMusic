package org.floatmusic.player;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.hardware.input.InputManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** One drawing surface with text-only touch regions; legacy systems use input windows. */
final class FloatingLyricsWindow implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final PlaybackService service;
    private final WindowManager manager;
    private final SharedPreferences prefs;
    private final LinkedHashMap<Integer,Glyph> windows=new LinkedHashMap<>();
    private JSONObject state=new JSONObject(),ui=new JSONObject();
    private JSONObject parsedUi;
    private String parsedTrack="";
    private String parsedLyrics="";
    private ArrayList<String> lyrics=new ArrayList<>();
    private long[] times=new long[0];
    private Rect area=new Rect();
    private int count=3,step=50,current=-1;
    private float centerX,topY,centerIndex=0,textPx;
    private boolean positioned=false,closed=false,dragging=false;
    private ValueAnimator scroll;
    private DrawingLayer drawing;
    private WindowManager.LayoutParams drawingParams;
    private long lastInputSync;
    private final boolean regionInput=Build.VERSION.SDK_INT>=33;

    FloatingLyricsWindow(PlaybackService service){
        this.service=service;manager=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);
        prefs=service.getSharedPreferences("window",0);prefs.registerOnSharedPreferenceChangeListener(this);
    }
    private int dp(float value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    private Rect workArea(){
        if(Build.VERSION.SDK_INT>=30){
            android.view.WindowMetrics metrics=manager.getCurrentWindowMetrics();Rect bounds=new Rect(metrics.getBounds());
            android.graphics.Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            bounds.inset(insets.left,insets.top,insets.right,insets.bottom);return bounds;
        }
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();manager.getDefaultDisplay().getMetrics(metrics);return new Rect(0,0,metrics.widthPixels,metrics.heightPixels);
    }
    void update(JSONObject nextState,JSONObject nextUi){
        state=nextState;ui=nextUi;
        boolean enabled=prefs.getBoolean("desktopLyricsEnabled",false)&&Settings.canDrawOverlays(service)&&!PlayerBridge.foreground&&!PlayerBridge.picking;
        if(closed||!enabled){cancelAnimation();remove();return;}
        String track=state.optString("currentTrack");boolean changed=!parsedTrack.equals(track),uiChanged=parsedUi!=ui;
        if(parsedUi!=ui||changed){
            JSONArray source=track.equals(ui.optString("lyricTrack"))?ui.optJSONArray("lyricLines"):null;
            if(source==null)source=new JSONArray();String signature=source.toString();
            changed=changed||!signature.equals(parsedLyrics);parsedUi=ui;
            if(changed){
                parsedTrack=track;parsedLyrics=signature;lyrics.clear();times=new long[source.length()];remove();
                for(int i=0;i<source.length();i++){JSONObject line=source.optJSONObject(i);times[i]=line==null?0:line.optLong("time");lyrics.add(line==null?"":line.optString("original").replace('\n',' '));}
            }
        }
        Rect available=workArea();boolean resized=!positioned||!available.equals(area);area=available;
        int nextCount=FloatingLyricRules.rows(prefs.getInt("desktopLyricsRows",3));
        if(nextCount!=count){count=nextCount;resized=true;}
        float requested=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,Math.max(18,Math.min(48,prefs.getInt("desktopLyricsFont",30))),service.getResources().getDisplayMetrics());
        step=FloatingLyricRules.rowHeight(requested,count,Math.max(1,area.height()-dp(16)));textPx=Math.min(requested,step/1.75f);
        if(!positioned||resized){
            centerX=area.left+prefs.getFloat("desktopLyricsX",.5f)*area.width();
            topY=area.top+dp(8)+prefs.getFloat("desktopLyricsY",.75f)*Math.max(0,area.height()-dp(16)-step*count);positioned=true;
        }
        int next=FloatingLyricRules.current(times,(long)state.optInt("position")+state.optInt("lyricOffset"));
        int target=Math.max(0,next);
        if(changed||resized||windows.isEmpty()){cancelAnimation();current=next;centerIndex=target;render();}
        else if(next!=current){
            current=next;cancelAnimation();
            settleCurrent();
        }else if(uiChanged&&(scroll==null||!scroll.isRunning()))render();
    }
    private void settleCurrent(){
        int target=Math.max(0,current);cancelAnimation();
        if(!dragging&&motionEnabled()&&Math.abs(target-centerIndex)<=3&&Math.abs(target-centerIndex)>.001f){
            scroll=ValueAnimator.ofFloat(centerIndex,target);scroll.setDuration(260);scroll.setInterpolator(new DecelerateInterpolator(1.5f));
            scroll.addUpdateListener(animation->{centerIndex=(Float)animation.getAnimatedValue();render();});
            scroll.addListener(new AnimatorListenerAdapter(){
                boolean cancelled;
                @Override public void onAnimationCancel(Animator animation){cancelled=true;}
                @Override public void onAnimationEnd(Animator animation){if(!cancelled&&!closed&&scroll==animation){scroll=null;centerIndex=target;render();}}
            });scroll.start();
        }else{centerIndex=target;render();}
    }
    private boolean motionEnabled(){return prefs.getBoolean("animationsEnabled",true)&&(Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled());}
    private void cancelAnimation(){if(scroll!=null){scroll.cancel();scroll=null;}}
    private boolean dark(){int mode=Math.max(0,Math.min(2,prefs.getInt("desktopLyricsTheme",0)));return mode==2||mode==0&&PlayerBridge.isDark(service);}
    private void render(){
        if(closed)return;
        int needed=lyrics.isEmpty()?1:count+2;
        try{
            int first=(int)Math.floor(centerIndex)-count/2-1;
            LinkedHashSet<Integer> retained=new LinkedHashSet<>();
            for(int slot=0;slot<needed;slot++)retained.add(lyrics.isEmpty()?-1:first+slot);
            Iterator<Map.Entry<Integer,Glyph>> old=windows.entrySet().iterator();
            while(old.hasNext()){Map.Entry<Integer,Glyph> entry=old.next();if(!retained.contains(entry.getKey())){detach(entry.getValue());old.remove();}}
            // Keep one window per lyric identity, rather than reassigning all
            // visible windows whenever floor(centerIndex) advances by one.
            int anchorInset=Math.min(dp(24),area.width()/2);
            centerX=Math.max(area.left+anchorInset,Math.min(centerX,area.right-anchorInset));
            topY=Math.max(area.top+dp(8),Math.min(topY,area.bottom-dp(8)-count*step));
            for(int index:retained){
                Glyph label=windows.get(index);if(label==null){label=new Glyph();windows.put(index,label);}
                boolean empty=lyrics.isEmpty();
                float distance=empty?0:index-centerIndex;
                String words=empty?UiStrings.text(ui.optString("lyricsMessage","暂无逐句歌词")):(index>=0&&index<lyrics.size()?lyrics.get(index):"");
                // Guard windows are hidden outside the viewport; empty timestamp
                // slots never create a touch target or an invented lyric.
                boolean visible=!words.trim().isEmpty()&&(empty||Math.abs(distance)<=count/2f+1);
                label.setVisibility(visible?View.VISIBLE:View.GONE);if(!visible)continue;
                label.configure(words,textPx,dark());
                label.focus=empty||current<0?0:FloatingLyricRules.emphasis(distance);
                label.visualScale=(empty?.7f:current<0?FloatingLyricRules.scale(distance,false):FloatingLyricRules.visualScale(distance))/1.12f;
                label.measuredTextWidth=Math.max(1,Math.round(label.normal.getWidth()*label.visualScale));
                label.measuredTextHeight=Math.max(1,Math.round(label.normal.getHeight()*label.visualScale));
                label.rowCenter=empty?count*step/2f:(distance+count/2f)*step;
                label.distance=distance;
            }
            updateDrawingLayer();
            long now=SystemClock.uptimeMillis();boolean syncInputs=regionInput||dragging||scroll==null||!scroll.isRunning()||now-lastInputSync>=32;
            for(Glyph label:windows.values()){
                if(label.getVisibility()!=View.VISIBLE)continue;
                int width=label.measuredTextWidth,height=label.measuredTextHeight;
                int originalTop=Math.round(topY+label.rowCenter-height/2f);
                int clippedTop=Math.max(Math.round(topY),originalTop),clippedBottom=Math.min(Math.round(topY+count*step),originalTop+height);
                if(clippedBottom<=clippedTop){label.setVisibility(View.GONE);continue;}
                label.crop=clippedTop-originalTop;
                if(syncInputs||!label.attached){
                    int x=Math.round(centerX-width/2f),heightNow=clippedBottom-clippedTop;
                    boolean moved=label.params.width!=width||label.params.height!=heightNow||label.params.x!=x||label.params.y!=clippedTop;
                    label.params.width=width;label.params.height=heightNow;label.params.x=x;label.params.y=clippedTop;
                    if(regionInput)label.layout(0,0,width,heightNow);
                    else if(!label.attached){manager.addView(label,label.params);label.attached=true;}
                    else if(moved)manager.updateViewLayout(label,label.params);
                }
            }
            if(syncInputs)lastInputSync=now;
            if(regionInput)drawing.updateTouchableRegion();
            drawing.invalidate();
        }catch(SecurityException|IllegalArgumentException failure){remove();service.report(UiStrings.text("无法显示悬浮窗，请检查悬浮权限。"));}
    }
    private void updateDrawingLayer(){
        int width=area.width(),height=count*step,x=area.left,y=Math.round(topY);
        if(drawing==null){
            drawing=new DrawingLayer();
            drawingParams=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|(regionInput?WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL:WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT);
            drawingParams.gravity=Gravity.TOP|Gravity.LEFT;drawingParams.x=x;drawingParams.y=y;drawingParams.setTitle("FloatMusic lyric drawing");
            // Android 12+ blocks taps through an untrusted opaque overlay even
            // in its transparent pixels. Stay within the system's public limit.
            if(Build.VERSION.SDK_INT>=31){InputManager input=service.getSystemService(InputManager.class);drawingParams.alpha=Math.min(.8f,input.getMaximumObscuringOpacityForTouch());}
            manager.addView(drawing,drawingParams);
        }else if(drawingParams.width!=width||drawingParams.height!=height||drawingParams.x!=x||drawingParams.y!=y){
            drawingParams.width=width;drawingParams.height=height;drawingParams.x=x;drawingParams.y=y;manager.updateViewLayout(drawing,drawingParams);
        }
    }
    private final class DrawingLayer extends View {
        final Paint composite=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final Region touchRegion=new Region(),appliedRegion=new Region();
        DrawingLayer(){super(service);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);if(regionInput)bindDrag(this);}
        void updateTouchableRegion(){
            if(Build.VERSION.SDK_INT<33||getRootSurfaceControl()==null)return;
            touchRegion.setEmpty();
            for(Glyph label:windows.values())if(label.getVisibility()==View.VISIBLE){
                WindowManager.LayoutParams p=label.params;
                touchRegion.op(p.x-drawingParams.x,p.y-drawingParams.y,p.x-drawingParams.x+p.width,p.y-drawingParams.y+p.height,Region.Op.UNION);
            }
            if(!touchRegion.equals(appliedRegion)){getRootSurfaceControl().setTouchableRegion(touchRegion);appliedRegion.set(touchRegion);}
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(regionInput)updateTouchableRegion();}
        @Override protected void onDraw(Canvas canvas){
            for(Glyph label:windows.values()){
                if(label.getVisibility()!=View.VISIBLE||label.normal==null)continue;
                int save=canvas.save();canvas.translate(centerX-area.left,label.rowCenter);canvas.scale(label.visualScale,label.visualScale);
                float x=-label.normal.getWidth()/2f,y=-label.normal.getHeight()/2f;
                int alpha=Math.round(255*FloatingLyricRules.opacity(label.distance));
                if(label.focus<=0||label.focus>=1){composite.setAlpha(alpha);canvas.drawBitmap(label.focus>=1?label.highlight:label.normal,x,y,composite);}
                else{
                    int layer=canvas.saveLayerAlpha(x,y,-x,-y,alpha);
                    composite.setAlpha(255);canvas.drawBitmap(label.normal,x,y,composite);
                    composite.setAlpha(Math.round(255*label.focus));canvas.drawBitmap(label.highlight,x,y,composite);canvas.restoreToCount(layer);
                }
                canvas.restoreToCount(save);
            }
        }
    }
    void configurationChanged(){positioned=false;parsedUi=null;update(state,ui);}
    void syncVisibility(){update(state,ui);}
    void close(){closed=true;cancelAnimation();prefs.unregisterOnSharedPreferenceChangeListener(this);remove();}
    private void detach(Glyph label){if(label.attached)try{manager.removeViewImmediate(label);}catch(IllegalArgumentException ignored){}finally{label.attached=false;}}
    private void remove(){for(Glyph label:windows.values())detach(label);windows.clear();if(drawing!=null){try{manager.removeViewImmediate(drawing);}catch(IllegalArgumentException ignored){}drawing=null;drawingParams=null;}}
    @Override public void onSharedPreferenceChanged(SharedPreferences ignored,String key){if(key!=null&&(key.startsWith("desktopLyrics")||key.equals("animationsEnabled"))&&!dragging)configurationChanged();}
    private void savePosition(){
        float x=(centerX-area.left)/Math.max(1,area.width());float y=(topY-area.top-dp(8))/Math.max(1,area.height()-dp(16)-count*step);
        prefs.edit().putFloat("desktopLyricsX",Math.max(0,Math.min(1,x))).putFloat("desktopLyricsY",Math.max(0,Math.min(1,y))).apply();
    }
    private void bindDrag(View target){
        final boolean[] moved={false};final float[] down=new float[4];
        target.setOnLongClickListener(view->{if(!moved[0])service.openFloatingLyricSettings();return true;});
        target.setOnTouchListener((view,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:down[0]=event.getRawX();down[1]=event.getRawY();down[2]=centerX;down[3]=topY;moved[0]=false;dragging=true;cancelAnimation();break;
                case MotionEvent.ACTION_MOVE:
                    if(Math.hypot(event.getRawX()-down[0],event.getRawY()-down[1])>ViewConfiguration.get(service).getScaledTouchSlop()){moved[0]=true;view.cancelLongPress();}
                    if(moved[0]){centerX=down[2]+event.getRawX()-down[0];topY=down[3]+event.getRawY()-down[1];render();}break;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                    if(moved[0])savePosition();dragging=false;settleCurrent();break;
            }
            return false;
        });target.setClickable(true);
    }
    private final class Glyph extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        final WindowManager.LayoutParams params;
        String words="";int fill,outline,measuredTextWidth=1,measuredTextHeight=1,crop;float baseline,rowCenter,distance;
        Bitmap normal,highlight;float visualScale=1,focus,cachedSize=-1;int cachedLimit=-1;boolean cachedNight;
        boolean attached;
        Glyph(){
            super(service);params=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.LEFT;params.setTitle("FloatMusic lyric text");setWillNotDraw(true);
            setVisibility(View.GONE);if(!regionInput)bindDrag(this);
        }
        void configure(String value,float size,boolean night){
            // Adapt the line to the user's anchor; an incoming long line must
            // not push the entire lyric group horizontally towards the centre.
            int limit=Math.max(1,(int)(2*Math.min(centerX-area.left-dp(8),area.right-dp(8)-centerX))-dp(6));
            if(normal!=null&&words.equals(value)&&cachedSize==size&&cachedLimit==limit&&cachedNight==night)return;
            words=value;cachedSize=size;cachedLimit=limit;cachedNight=night;paint.setTextSize(Math.max(1,size*1.12f));
            paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));float normalWidth=paint.measureText(words);
            paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));float width=Math.max(normalWidth,paint.measureText(words));
            if(width>limit)paint.setTextSize(paint.getTextSize()*limit/width);
            int padding=dp(3),bitmapWidth=Math.max(1,(int)Math.ceil(Math.min(width,limit))+padding*2);
            Paint.FontMetrics metrics=paint.getFontMetrics();int bitmapHeight=Math.max(1,(int)Math.ceil(metrics.descent-metrics.ascent)+padding*2);baseline=padding-metrics.ascent;
            outline=night?0xc40c1123:0xb8ffffff;
            normal=raster(bitmapWidth,bitmapHeight,false,night?0xfff4f0fc:0xff293047);
            highlight=raster(bitmapWidth,bitmapHeight,true,night?0xffcdaaff:0xff703aff);setContentDescription(words);
        }
        private Bitmap raster(int width,int height,boolean bold,int color){
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
            paint.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,dp(.8f)));paint.setColor(outline);canvas.drawText(words,width/2f,baseline,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawText(words,width/2f,baseline,paint);return bitmap;
        }
    }
}
