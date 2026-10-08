package org.floatmusic.v11uitest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual 1.1 overlay and service dispatch, with isolated preferences and local snapshots. */
public final class OverlayV11UiTest extends Instrumentation {
    private Object overlay,service;
    private Context fixtureContext;
    private final StringBuilder report=new StringBuilder();
    private boolean screenshots;

    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);screenshots=arguments!=null&&"true".equals(arguments.getString("screenshots"));start();}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] failure=new Throwable[1];super.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Error)throw (Error)failure[0];if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }
    @Override public void onStart(){
        String[] cases={"quality-editing","volume-popup","volume-short","lyrics-small","lyrics-plain","font-range","service-fold","line-spacing"};int passed=0;
        for(String scene:cases){
            try{
                runOnMainSync(()->prepare(scene));waitForIdleSync();
                if(scene.startsWith("volume")){
                    if(scene.equals("volume-short")){runOnMainSync(this::shortViewport);waitForIdleSync();}
                    runOnMainSync(this::openVolume);waitForIdleSync();runOnMainSync(()->testVolume(scene.equals("volume-short")));waitForIdleSync();
                }else if(scene.equals("quality-editing"))runOnMainSync(this::testQualityEditing);
                else if(scene.equals("lyrics-small")){runOnMainSync(this::testSmallLyrics);waitForIdleSync();runOnMainSync(this::testLyricFollow);waitForIdleSync();runOnMainSync(this::verifyLyricFollow);}
                else if(scene.equals("lyrics-plain"))runOnMainSync(this::testPlainLyrics);
                else if(scene.equals("font-range"))runOnMainSync(this::testFontRange);
                else if(scene.equals("line-spacing"))runLineSpacingScene();
                else runOnMainSync(this::testServiceFold);
                if(screenshots)capture(scene);
                report.append("PASS ").append(scene).append('\n');passed++;
            }catch(Throwable failure){report.append("FAIL ").append(scene).append(": ").append(failure).append('\n');}
            finally{runOnMainSync(()->{if(overlay!=null)invoke(overlay,"close");overlay=null;service=null;});}
        }
        Bundle result=new Bundle();result.putString("stream",report.toString());result.putBoolean("uiPassed",passed==cases.length);result.putInt("passed",passed);finish(passed==cases.length?-1:0,result);
    }
    private float density(){return getTargetContext().getResources().getDisplayMetrics().density;}
    private int dp(float value){return Math.round(value*density());}
    private void prepare(String scene){
        try{
            final String prefix="v11-ui-"+scene+"-"+SystemClock.uptimeMillis()+"-";
            fixtureContext=new ContextWrapper(getTargetContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return getTargetContext().getSharedPreferences(prefix+name,mode);}
            };
            fixtureContext.getSharedPreferences("window",0).edit().putBoolean("animationsEnabled",false).putInt("scale",100)
                .putInt("lyricFontSize",scene.startsWith("lyrics")?10:18).putInt("lyricMode",scene.startsWith("lyrics")?2:0).apply();
            Class<?> serviceType=Class.forName("org.floatmusic.player.PlaybackService");service=serviceType.getDeclaredConstructor().newInstance();
            Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,fixtureContext);
            setField(service,"sourceQualities",qualities());
            setField(service,"destroyed",true); // No native playback, notification, or publication work in this local fixture.
            Class<?> overlayType=Class.forName("org.floatmusic.player.OverlayWindow");Constructor<?> constructor=overlayType.getDeclaredConstructor(serviceType);constructor.setAccessible(true);overlay=constructor.newInstance(service);
            JSONObject ui=new JSONObject().put("sourceQualities",qualities()).put("lyricTrack","fixture-song").put("lyricsMessage","");
            if(scene.equals("lyrics-plain"))ui.put("lyrics","[00:00.00]让音乐留在手边\n第二句在这里").put("translation","Keep the music close\nThe second line is here");
            else if(scene.equals("lyrics-small")||scene.equals("line-spacing"))ui.put("lyricLines",lyrics());
            setField(overlay,"ui",ui);setField(overlay,"playback",snapshot());invoke(overlay,"show");invoke(overlay,"expand");
            require(field(overlay,"window")!=null,"fixture needs target overlay permission");
            if(scene.startsWith("lyrics"))invoke(overlay,"toggle",new Class<?>[]{String.class},"lyrics");
            else if(!scene.startsWith("volume")){invoke(overlay,"toggle",new Class<?>[]{String.class},"more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"settings");}
            drainEvents();
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private JSONObject qualities() throws Exception{return new JSONObject().put("netease","hires").put("tencent","exhigh").put("kuwo","standard");}
    private JSONObject snapshot() throws Exception{return new JSONObject().put("sourceQualities",qualities()).put("qualitySelectable",false).put("busy",false)
        .put("loadedTrack",new JSONObject().put("source","local").put("artist","Fixture artist")).put("currentTrack","fixture-song").put("title","让音乐留在手边")
        .put("volume",70).put("position",21000).put("duration",80000).put("qualityInfo","本地 WAV · 48 kHz / 16 bit")
        .put("outputs",new JSONArray().put(new JSONObject().put("id","").put("name","系统默认")));
    }
    private JSONArray lyrics() throws Exception{
        JSONArray lines=new JSONArray();String longLine="在每一个安静的清晨让音乐陪着我们慢慢走过城市里的街道听见那些熟悉又陌生的声音";
        for(int i=0;i<80;i++)lines.put(new JSONObject().put("time",i*1000).put("original",i==0?longLine+longLine:"第 "+i+" 句让音乐留在手边").put("translation",i==0?"Keep the music close through every quiet morning":"Line "+i));return lines;
    }
    private void testQualityEditing(){
        try{
            List<?> buttons=(List<?>)field(overlay,"sourceQualityButtons");require(buttons.size()==3,"each library has its own quality choice");
            EditText api=(EditText)field(overlay,"apiInput");api.setText("https://typed.example/api");api.setSelection(8);
            for(Object item:buttons){Button choice=(Button)item;require(choice.isEnabled(),"quality can be edited while a local song is loaded");require(choice.getLayoutParams().height>=dp(48),"quality selectors keep native touch targets");}
            Button net=(Button)buttons.get(0),qq=(Button)buttons.get(1),kw=(Button)buttons.get(2);
            require(net.getText().toString().startsWith("Hi-Res")&&qq.getText().toString().startsWith("高音质 320")&&kw.getText().toString().startsWith("标准"),"snapshot renders three independent stored tiers");
            qq.performClick();ViewGroup choices=(ViewGroup)field(overlay,"popupLayer");require(countButtons(choices)==5,"QQ exposes four tiers plus sheet close, including master");
            ((Button)findText(choices,"实验母带")).performClick();require(qq.getText().toString().startsWith("实验母带"),"chosen QQ master appears immediately");
            JSONObject saved=new JSONObject(fixtureContext.getSharedPreferences("audio",0).getString("sourceQualities","{}"));expect("master",saved.optString("tencent"),"native selector dispatch persists QQ master through the service");expect("hires",saved.optString("netease"),"QQ dispatch preserves Net's stored choice");expect("standard",saved.optString("kuwo"),"QQ dispatch preserves Kuwo's stored choice");
            require(net.getText().toString().startsWith("Hi-Res")&&kw.getText().toString().startsWith("标准"),"changing QQ leaves Net and Kuwo requests intact");
            require(field(overlay,"apiInput")==api&&api.getSelectionStart()==8,"native quality dispatch retains API field identity and cursor");
            invoke(overlay,"update",new Class<?>[]{JSONObject.class},snapshot().put("busy",true));require(qq.getText().toString().startsWith("实验母带"),"older busy snapshots do not overwrite an unacknowledged choice");
            for(Object item:buttons)require(((Button)item).isEnabled(),"all library choices remain editable while another song loads");
            JSONObject accepted=snapshot().put("sourceQualities",qualities().put("tencent","master")).put("qualityInfo","QQ音乐 · 返回 FLAC · 96 kHz / 24 bit");
            invoke(overlay,"update",new Class<?>[]{JSONObject.class},accepted);
            require(!((JSONObject)field(overlay,"pendingSourceQualities")).has("tencent"),"service snapshot acknowledges the native source choice");
            expect("QQ音乐 · 返回 FLAC · 96 kHz / 24 bit",((TextView)field(overlay,"sourceInfo")).getText().toString(),"actual return specifications come directly from the service");
            require(((TextView)field(overlay,"qualityHint")).getVisibility()==View.VISIBLE,"experimental master has its format caveat");
            kw.performClick();require(countButtons((ViewGroup)field(overlay,"popupLayer"))==4,"Kuwo exposes three tiers plus sheet close");
            ((Button)findText((View)field(overlay,"popupLayer"),"无损 FLAC")).performClick();require(kw.getText().toString().startsWith("无损 FLAC"),"Kuwo lossless can be chosen while idle or local");
            saved=new JSONObject(fixtureContext.getSharedPreferences("audio",0).getString("sourceQualities","{}"));expect("lossless",saved.optString("kuwo"),"native Kuwo choice persists through service dispatch");expect("master",saved.optString("tencent"),"Kuwo dispatch preserves QQ master");
            require(field(overlay,"apiInput")==api&&api.getText().toString().equals("https://typed.example/api")&&api.getSelectionStart()==8,"all in-place settings refreshes preserve typed drafts");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void shortViewport(){
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)field(overlay,"params");params.height=dp(180);invoke(overlay,"move");
        ((ScrollView)field(overlay,"scroll")).post(()->((ScrollView)field(overlay,"scroll")).scrollTo(0,dp(400)));
    }
    private void openVolume(){
        require(field(overlay,"volume")==null,"player has no permanent volume slider row");View button=(View)field(overlay,"volumeButton");ViewGroup controls=(ViewGroup)button.getParent();
        require(controls.getChildCount()==5&&controls.getChildAt(3)==field(overlay,"modeButton"),"volume shares the row with previous, play, next and mode");
        require(button.getLayoutParams().width>=dp(48)&&button.getLayoutParams().height>=dp(48),"volume icon retains a native touch target");button.performClick();
    }
    private void testVolume(boolean shortScreen){
        ViewGroup layer=(ViewGroup)field(overlay,"popupLayer");View popup=layer.getChildAt(0);SeekBar slider=(SeekBar)field(overlay,"volume");
        require(popup.getLeft()>=0&&popup.getTop()>=0&&popup.getRight()<=layer.getWidth()&&popup.getBottom()<=layer.getHeight(),"volume popup bounds are inside its unscaled overlay layer");
        require(slider.getWidth()>=dp(48)&&slider.getHeight()>=dp(48),"even the short viewport leaves a usable volume drag region");
        if(!shortScreen){int[] anchor=new int[2],origin=new int[2];((View)field(overlay,"volumeButton")).getLocationOnScreen(anchor);layer.getLocationOnScreen(origin);require(popup.getBottom()<=anchor[1]-origin[1],"normal popup opens upward");}
        String scene=shortScreen?"volume-short":"volume-popup";
        for(int value:new int[]{0,50,100}){
            float y=value==0?slider.getHeight()-dp(8):value==100?dp(8):slider.getHeight()/2f;
            gesture(slider,y,MotionEvent.ACTION_DOWN);gesture(slider,y,MotionEvent.ACTION_UP);require((Integer)field(service,"volume")==value,"vertical drag dispatches "+value+" percent to the service");verifyVolumeThumb(slider,value,scene+"-drag-"+value);
        }
        try{for(int value:new int[]{0,50,100}){invoke(overlay,"update",new Class<?>[]{JSONObject.class},snapshot().put("volume",value));require((Integer)field(service,"volume")==100,"external snapshots move the thumb without sending a volume command");verifyVolumeThumb(slider,value,scene+"-snapshot-"+value);}}
        catch(Exception failure){throw new RuntimeException(failure);}
        View button=(View)field(overlay,"volumeButton");require(button.isSelected()&&button.getContentDescription().toString().contains("已展开"),"volume announces its value and expanded state");
        View root=(View)field(overlay,"window");root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));require(layer.getVisibility()==View.GONE,"Back dismisses the volume popup");
        button.performClick();MotionEvent outside=MotionEvent.obtain(0,0,MotionEvent.ACTION_OUTSIDE,-1,-1,0);try{root.dispatchTouchEvent(outside);}finally{outside.recycle();}require(layer.getVisibility()==View.GONE,"outside touch dismisses the volume popup");button.performClick();
    }
    private void verifyVolumeThumb(SeekBar slider,int value,String scene){
        Bitmap bitmap=Bitmap.createBitmap(slider.getWidth(),slider.getHeight(),Bitmap.Config.ARGB_8888);
        try{
            slider.draw(new Canvas(bitmap));float expected=value==0?slider.getHeight()-dp(8):value==100?dp(8):slider.getHeight()/2f;
            android.graphics.Rect thumb=slider.getThumb().getBounds();require(Math.abs(thumb.exactCenterY()-expected)<=1,"drawn thumb center follows actual vertical height at "+value+" percent");require(Math.abs(thumb.exactCenterX()-slider.getWidth()/2f)<=1,"drawn thumb stays centered across the drag region");
            int offTrack=bitmap.getPixel(slider.getWidth()/2+dp(4),Math.round(expected));require(android.graphics.Color.alpha(offTrack)>0,"actual thumb paints beside the thin track at "+value+" percent");
            require(android.graphics.Color.alpha(bitmap.getPixel(slider.getWidth()/2+dp(12),Math.round(expected)))==0,"thumb remains a compact circular handle");
            if(screenshots)saveBitmap(bitmap,scene);
        }catch(Exception failure){throw new RuntimeException(failure);}finally{bitmap.recycle();}
    }
    private void saveBitmap(Bitmap bitmap,String scene) throws Exception{
        File folder=new File(getTargetContext().getCacheDir(),"v11-ui-fixture");require(folder.isDirectory()||folder.mkdirs(),"screenshot folder is available");try(FileOutputStream out=new FileOutputStream(new File(folder,scene+".png"))){require(bitmap.compress(Bitmap.CompressFormat.PNG,100,out),"screenshot saved");}
    }
    private void testSmallLyrics(){
        List<?> words=(List<?>)field(overlay,"lyricTexts");require(words.size()==80,"timed bilingual fixture is rendered");TextView first=(TextView)words.get(0),second=(TextView)words.get(1);
        require(first.getLineCount()>2&&first.getText().toString().contains("Keep the music"),"long Chinese text and translations wrap without truncation");
        require(second.getTop()==first.getBottom(),"small lyric rows are adjacent without fixed vertical gaps");
        for(Object item:words){TextView line=(TextView)item;require(!line.getIncludeFontPadding()&&line.getPaddingTop()==0&&line.getPaddingBottom()==0,"lyric rows have no fixed font or vertical padding");require(line.getHeight()==line.getLayout().getHeight(),"each lyric row takes only its natural wrapped text height");}
        float sp=getTargetContext().getResources().getDisplayMetrics().scaledDensity;
        require(Math.abs(second.getTextSize()-10*sp)<.5f,"inactive small lyrics render at ten sp");TextView active=(TextView)words.get((Integer)field(overlay,"activeLyric"));require(Math.abs(active.getTextSize()-12*sp)<.5f,"current lyric is exactly two sp larger");
    }
    private void testLyricFollow(){
        ScrollView lyrics=(ScrollView)field(overlay,"lyricScroll");gesture(lyrics,dp(40),MotionEvent.ACTION_DOWN);gesture(lyrics,dp(40),MotionEvent.ACTION_CANCEL);lyrics.scrollTo(0,0);
        require((Boolean)field(overlay,"manualLyrics"),"manual lyric gesture pauses automatic following");View back=(View)field(overlay,"lyricReturn");require(back.getVisibility()==View.VISIBLE,"manual scrolling exposes return to current lyric");back.performClick();
    }
    private void verifyLyricFollow(){
        require(!(Boolean)field(overlay,"manualLyrics"),"return action restores automatic following");require(((View)field(overlay,"lyricReturn")).getVisibility()==View.GONE,"return control hides after following resumes");require(((ScrollView)field(overlay,"lyricScroll")).getScrollY()>0,"current lyric remains centered after shrinking its row spacing");
    }
    private void testPlainLyrics(){
        TextView text=(TextView)((ViewGroup)field(overlay,"lyricRows")).getChildAt(0);require(text.getText().toString().contains("让音乐留在手边")&&text.getText().toString().contains("Keep the music close"),"plain bilingual text preserves both languages");require(text.getLineCount()>=4&&!text.getIncludeFontPadding()&&text.getHeight()==text.getLayout().getHeight(),"plain lyrics use the same natural one-times text height");
    }
    private void testFontRange(){
        SeekBar size=(SeekBar)findDescription((View)field(overlay,"drawer"),"歌词字号");require(size!=null&&size.getProgress()==8,"default eighteen-sp font is represented in the wider range");
        horizontalGesture(size,0);require((Integer)field(overlay,"lyricFontSize")==10&&fixtureContext.getSharedPreferences("window",0).getInt("lyricFontSize",0)==10,"minimum font size can be selected and persisted through the native slider");
        horizontalGesture(size,size.getWidth());require((Integer)field(overlay,"lyricFontSize")==30&&fixtureContext.getSharedPreferences("window",0).getInt("lyricFontSize",0)==30,"maximum thirty-sp font can be selected and persisted");
    }
    private void runLineSpacingScene(){
        runOnMainSync(this::testSpacingControl);waitForIdleSync();runOnMainSync(this::testSpacingReload);waitForIdleSync();
        runOnMainSync(()->invoke(overlay,"toggle",new Class<?>[]{String.class},"lyrics"));waitForIdleSync();
        runOnMainSync(()->{verifySpacingRows(1.7f,false);beginManualSpacing();});waitForIdleSync();
        runOnMainSync(()->{verifyManualSpacing();verifySpacingRows(.8f,true);invoke(overlay,"changeLyricLineSpacing",new Class<?>[]{float.class,boolean.class},3f,true);});waitForIdleSync();
        runOnMainSync(()->verifySpacingRows(3f,false));
        for(int mode:new int[]{1,2}){
            final int selected=mode;runOnMainSync(()->{try{setField(overlay,"lyricMode",selected);invoke(overlay,"renderLyrics");}catch(Exception error){throw new RuntimeException(error);}});waitForIdleSync();
            runOnMainSync(()->{verifySpacingRows(3f,false);TextView line=(TextView)((List<?>)field(overlay,"lyricTexts")).get(1);require(line.getText().toString().contains("Line 1"),"translation is retained at the selected line spacing");if(selected==2)require(line.getText().toString().contains("第 1 句"),"bilingual line spacing preserves original text");});
        }
        for(int mode:new int[]{0,1,2}){
            final int selected=mode;runOnMainSync(()->preparePlainSpacing(selected));waitForIdleSync();runOnMainSync(()->verifyPlainSpacing(selected));
        }
        runOnMainSync(()->{invoke(overlay,"toggle",new Class<?>[]{String.class},"more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"settings");});waitForIdleSync();
        runOnMainSync(()->{SeekBar spacing=(SeekBar)findDescription((View)field(overlay,"drawer"),"歌词行距");((ScrollView)field(overlay,"detailScroll")).scrollTo(0,Math.max(0,spacing.getTop()-dp(90)));});waitForIdleSync();
    }
    private void testSpacingControl(){
        SeekBar spacing=(SeekBar)findDescription((View)field(overlay,"drawer"),"歌词行距");require(spacing!=null&&spacing.getProgress()==2&&spacing.getLayoutParams().height>=dp(48),"old preferences open a one-times slider with native touch bounds");
        horizontalGesture(spacing,0);expectSpacing(.8f,"compact endpoint");TextView label=(TextView)findText((View)field(overlay,"drawer"),"歌词行距：");require(label.getText().toString().contains("0.8×"),"readout updates during compact spacing selection");
        horizontalGesture(spacing,spacing.getWidth());expectSpacing(3f,"roomy endpoint");require(label.getText().toString().contains("3.0×"),"readout updates during roomy spacing selection");
        float x=spacing.getPaddingLeft()+(spacing.getWidth()-spacing.getPaddingLeft()-spacing.getPaddingRight())*9f/22f;horizontalGesture(spacing,x);expectSpacing(1.7f,"tenth-step choice");
        TextView preview=(TextView)findText((View)field(overlay,"drawer"),"让音乐留在手边");require(preview.getText().toString().contains("\n")&&Math.abs(preview.getLineSpacingMultiplier()-1.7f)<.01f,"two-line preview updates without a settings rebuild");
    }
    private void expectSpacing(float expected,String message){
        require(Math.abs((Float)field(overlay,"lyricLineSpacing")-expected)<.01f,message+" applies to the live overlay");require(Math.abs(fixtureContext.getSharedPreferences("window",0).getFloat("lyricLineSpacing",0)-expected)<.01f,message+" persists the same multiplier");
    }
    private Object newOverlay() throws Exception{
        Constructor<?> constructor=Class.forName("org.floatmusic.player.OverlayWindow").getDeclaredConstructor(service.getClass());constructor.setAccessible(true);return constructor.newInstance(service);
    }
    private void testSpacingReload(){
        try{
            JSONObject data=(JSONObject)field(overlay,"ui"),state=(JSONObject)field(overlay,"playback");invoke(overlay,"close");overlay=newOverlay();require(Math.abs((Float)field(overlay,"lyricLineSpacing")-1.7f)<.01f,"new overlay construction reloads the saved multiplier");
            setField(overlay,"ui",data);setField(overlay,"playback",state);invoke(overlay,"show");invoke(overlay,"expand");invoke(overlay,"toggle",new Class<?>[]{String.class},"more");invoke(overlay,"selectDetail",new Class<?>[]{String.class},"settings");
            SharedPreferences preferences=fixtureContext.getSharedPreferences("window",0);preferences.edit().putFloat("lyricLineSpacing",Float.NaN).apply();require((Float)field(newOverlay(),"lyricLineSpacing")==1f,"NaN preferences reload at the safe default");preferences.edit().putString("lyricLineSpacing","corrupt").apply();require((Float)field(newOverlay(),"lyricLineSpacing")==1f,"wrong preference types reload without a ClassCastException");preferences.edit().putFloat("lyricLineSpacing",1.7f).apply();
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private TextView spacingAnchor;
    private void beginManualSpacing(){
        ScrollView scroll=(ScrollView)field(overlay,"lyricScroll");gesture(scroll,dp(40),MotionEvent.ACTION_DOWN);gesture(scroll,dp(40),MotionEvent.ACTION_CANCEL);spacingAnchor=(TextView)((List<?>)field(overlay,"lyricTexts")).get(40);scroll.scrollTo(0,spacingAnchor.getTop());
        require((Boolean)field(overlay,"manualLyrics"),"spacing scenario enters manual browsing");invoke(overlay,"changeLyricLineSpacing",new Class<?>[]{float.class,boolean.class},.8f,true);
    }
    private void verifyManualSpacing(){
        require((Boolean)field(overlay,"manualLyrics"),"spacing change never resumes automatic following during manual browsing");ScrollView scroll=(ScrollView)field(overlay,"lyricScroll");require(Math.abs(scroll.getScrollY()-spacingAnchor.getTop())<=1,"spacing change retains the manually viewed lyric anchor");require(((View)field(overlay,"lyricReturn")).getVisibility()==View.VISIBLE,"manual browse keeps its return control after spacing changes");
    }
    private void verifySpacingRows(float expected,boolean compact){
        List<?> words=(List<?>)field(overlay,"lyricTexts");TextView one=(TextView)words.get(1),two=(TextView)words.get(2);android.text.Layout layout=one.getLayout();int last=layout.getLineCount()-1,natural=layout.getLineBottom(last)-layout.getLineTop(last),step=two.getTop()-one.getTop();
        require(one.getHeight()==layout.getHeight()&&one.getHeight()>=natural,"row keeps its natural complete glyph height at "+expected+" times spacing");int delta=step-one.getHeight();require(Math.abs(delta-natural*(expected-1f))<=1,"even single-line rows advance by the selected proportional gap");if(compact)require(delta<0,"compact spacing overlaps row steps without reducing glyph bounds");else require(delta>0,"roomier spacing separates lyric rows");
        for(Object item:words){TextView text=(TextView)item;require(Math.abs(text.getLineSpacingMultiplier()-expected)<.01f,"long and bilingual text use the same live spacing multiplier");require(text.getPaddingTop()==0&&text.getPaddingBottom()==0&&!text.getIncludeFontPadding(),"spacing never adds fixed row or font padding");}
        TextView active=(TextView)words.get((Integer)field(overlay,"activeLyric"));float sp=getTargetContext().getResources().getDisplayMetrics().scaledDensity;require(Math.abs(active.getTextSize()-(((Integer)field(overlay,"lyricFontSize"))+2)*sp)<.5f,"current lyric remains two sp larger at every spacing");
        TextView first=(TextView)words.get(0);if((Integer)field(overlay,"lyricMode")!=1)require(first.getLineCount()>2,"long Chinese original stays wrapped at the chosen spacing");
    }
    private void preparePlainSpacing(int mode){
        try{JSONObject ui=(JSONObject)field(overlay,"ui");ui.remove("lyricLines");ui.put("lyrics","[00:00.00]原文的第一行\n原文的第二行").put("translation","First translated line\nSecond translated line");setField(overlay,"lyricMode",mode);invoke(overlay,"renderLyrics");}catch(Exception error){throw new RuntimeException(error);}
    }
    private void verifyPlainSpacing(int mode){
        TextView text=(TextView)((ViewGroup)field(overlay,"lyricRows")).getChildAt(0);require(Math.abs(text.getLineSpacingMultiplier()-3f)<.01f&&text.getLineCount()>=2,"plain multiline text uses persisted spacing in each display mode");require(text.getHeight()==text.getLayout().getHeight(),"plain text retains its complete wrapped glyph height");if(mode!=1)require(text.getText().toString().contains("原文的第一行"),"plain original text is retained");if(mode!=0)require(text.getText().toString().contains("First translated line"),"plain translation text is retained");
    }
    private void testServiceFold(){
        try{
            View fields=(View)field(overlay,"customServiceFields");Button fold=(Button)field(overlay,"customServiceButton");EditText input=(EditText)field(overlay,"apiInput");require(fields.getVisibility()==View.GONE,"default music service starts folded");fold.performClick();require(fields.getVisibility()==View.VISIBLE,"custom service can be expanded");
            input.setText("https://draft.example/api");input.setSelection(7);fold.performClick();invoke(overlay,"update",new Class<?>[]{JSONObject.class},snapshot());fold.performClick();require(field(overlay,"apiInput")==input&&input.getSelectionStart()==7&&input.getText().toString().equals("https://draft.example/api"),"folding and snapshot refresh retain the service draft and cursor");
            require(((ScrollView)field(overlay,"detailScroll")).canScrollVertically(1),"compact settings keep all sections reachable by scrolling");
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private void horizontalGesture(SeekBar view,float x){long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,view.getHeight()/2f,0),up=MotionEvent.obtain(now,now+10,MotionEvent.ACTION_UP,x,view.getHeight()/2f,0);try{view.dispatchTouchEvent(down);view.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}}
    private void gesture(View view,float y,int action){long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,view.getWidth()/2f,y,0);try{view.dispatchTouchEvent(event);}finally{event.recycle();}}
    private void capture(String scene) throws Exception{
        View root=(View)field(overlay,"window");java.util.concurrent.CountDownLatch drawn=new java.util.concurrent.CountDownLatch(1);android.view.ViewTreeObserver.OnPreDrawListener[] listener=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        runOnMainSync(()->{listener[0]=()->{if(!root.isLayoutRequested()){root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);drawn.countDown();}else root.postInvalidateOnAnimation();return true;};root.getViewTreeObserver().addOnPreDrawListener(listener[0]);root.invalidate();});require(drawn.await(3,java.util.concurrent.TimeUnit.SECONDS),"screenshot requires completed layout");Bitmap[] image=new Bitmap[1];
        runOnMainSync(()->{image[0]=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image[0]));});
        try{File folder=new File(getTargetContext().getCacheDir(),"v11-ui-fixture");require(folder.isDirectory()||folder.mkdirs(),"screenshot folder is available");try(FileOutputStream out=new FileOutputStream(new File(folder,scene+".png"))){require(image[0].compress(Bitmap.CompressFormat.PNG,100,out),"screenshot saved");}}finally{image[0].recycle();}
    }
    private void drainEvents(){try{Class.forName("org.floatmusic.player.PlayerBridge").getMethod("takeEvents").invoke(null);}catch(Exception error){throw new RuntimeException(error);}}
    private static int countButtons(View view){int count=view instanceof Button?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countButtons(((ViewGroup)view).getChildAt(i));return count;}
    private static View findText(View view,String prefix){if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(prefix))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=findText(((ViewGroup)view).getChildAt(i),prefix);if(result!=null)return result;}return null;}
    private static View findDescription(View view,String prefix){if(view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith(prefix))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=findDescription(((ViewGroup)view).getChildAt(i),prefix);if(result!=null)return result;}return null;}
    private static Object field(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new RuntimeException(error);}}
    private static void setField(Object target,String name,Object value) throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void invoke(Object target,String name,Class<?>[] types,Object... values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,values);}catch(Exception error){throw new RuntimeException(error);}}
    private static void invoke(Object target,String name){invoke(target,name,new Class<?>[]{});}
    private static void expect(String expected,String actual,String message){if(!expected.equals(actual))throw new AssertionError(message+": expected "+expected+", got "+actual);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
