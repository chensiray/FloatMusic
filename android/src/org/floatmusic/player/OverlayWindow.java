package org.floatmusic.player;

import android.content.*;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.text.SimpleDateFormat;
import java.util.Date;

/** The only Android player surface. Playback and the Qt library remain outside this class. */
final class OverlayWindow {
    private final PlaybackService service;
    private final WindowManager manager;
    private final SharedPreferences prefs;
    private View window;
    private WindowManager.LayoutParams params;
    private ScaledFrame scaled;
    private ScrollView scroll, detailScroll;
    private LinearLayout shell, drawer, dynamic;
    private TextView title, artist, time, volumeText, error, feedback, sourceInfo, qualityHint, qualitySources;
    private IconButton play, modeButton, volumeButton, lyricReturn, sleepButton;
    private TextView sleepStatusText;
    private Button cancelSleepButton, afterCurrentButton;
    private FrameLayout popupLayer;
    private ScrollView lyricScroll;
    private FrameLayout lyricViewport;
    private LinearLayout lyricRows;
    private TextView lyricMessage, timingValue;
    private final ArrayList<TextView> lyricTexts=new ArrayList<>();
    private JSONArray lyricLines=new JSONArray();
    private String lyricTrack="";
    private boolean manualLyrics=false;
    private int activeLyric=-1;
    private SeekBar progress, volume;
    private Button lyricsTab, playlistTab, moreTab;
    private Button searchButton, qualityButton;
    private Button outputButton;
    private final ArrayList<Button> sourceQualityButtons=new ArrayList<>();
    private final JSONObject pendingSourceQualities=new JSONObject();
    private LinearLayout customServiceFields;
    private Button customServiceButton;
    private boolean customServiceExpanded=false, customServiceInitialized=false, volumePopupOpen=false;
    private final ArrayList<CheckBox> searchSourceButtons=new ArrayList<>();
    private JSONArray pendingSearchSources;
    private Integer pendingSearchResultLimit;
    private Button searchResultLimitButton;
    private String pendingRankingSource;
    private final ArrayList<Button> rankingSourceButtons=new ArrayList<>();
    private boolean syncingSourceChoices=false;
    private EditText searchInput, apiInput, nameInput, sleepInput;
    private JSONObject playback=new JSONObject(), ui=new JSONObject();
    private String section="", detail="", searchDraft="", apiDraft=null, nameDraft="", rendered="";
    private String searchKind="songs", selectionPlaylist="", requestedOnlineId="";
    private String onlineSource="search";
    private boolean rankingsRequested=false;
    private int tracksPage=0, resultsPage=0, onlinePage=0, playlistResultsPage=0;
    private int queuePage=0, historyPage=0;
    private String sleepDraft="30";
    private static final int SONG_PAGE_SIZE=80, PLAYLIST_PAGE_SIZE=30;
    private boolean selecting=false;
    private final LinkedHashSet<String> selectedIds=new LinkedHashSet<>();
    private Button selectionCount;
    private Button playlistPicker;
    private TextView playlistDescription;
    private DescriptionBlock playlistDescriptionBlock;
    private String settingsPage="";
    private DragReorder dragReorder;
    private View popupFocusReturn;
    private int lyricMode=0, lyricFontSize=18;
    private float lyricLineSpacing=1f;
    private int lyricSpacingRevision=0, lyricGestureRevision=0;
    private ValueAnimator lyricFollow;
    private int lyricFollowRevision=0;
    private boolean lyricTouching=false;
    private boolean expanded=false, seeking=false, updating=false, closed=false, keyboard=false;
    private boolean detailTouching=false, dynamicRefreshQueued=false;
    private float scale=1f;
    private static final int BASE_WIDTH=340;
    private static final String[] SEARCH_SOURCE_IDS={"netease","tencent","kuwo"};
    private static final int[] SEARCH_RESULT_LIMITS={10,20,30,50,100};

    OverlayWindow(PlaybackService service) {
        this.service=service; manager=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);
        prefs=service.getSharedPreferences("window",0); ui=PlayerBridge.uiData();
        lyricMode=Math.max(0,Math.min(2,prefs.getInt("lyricMode",0)));
        lyricFontSize=Math.max(10,Math.min(30,prefs.getInt("lyricFontSize",18)));
        lyricLineSpacing=lineSpacingValue(prefs.getAll().get("lyricLineSpacing"));
    }
    private int dp(float n){return Math.round(n*service.getResources().getDisplayMetrics().density);}
    private boolean dark(){return PlayerBridge.isDark(service);}
    private int color(String light,String night){return Color.parseColor(dark()?night:light);}
    private int ink(){return color("#18213D","#F2F1FB");}
    private int muted(){return color("#68718D","#ADB1C8");}
    private int accent(){return color("#703AFF","#B79BFF");}
    private int accentInk(){return color("#FFFFFF","#191C2C");}
    private int primaryInk(){return color("#FFFFFF","#FBF9FF");}
    private int selection(){return color("#EBE5FA","#3E2D65");}
    private GradientDrawable background(int fill,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));return d;}
    private Drawable materialBackground(int radius,boolean elevated){return new PrismaticDrawable(dark(),elevated,dp(radius),service.getResources().getDisplayMetrics().density);}
    private GradientDrawable primaryBackground(int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{color("#A58BFF","#B097FF"),color("#703AFF","#8554EE"),color("#6635E8","#683CE0")});
        d.setCornerRadius(dp(radius));return d;
    }
    /** 36dp visual surfaces inside 48dp native touch bounds. */
    private Drawable buttonBackground(boolean selected){
        GradientDrawable idle=background(selected?selection():Color.TRANSPARENT,10);
        GradientDrawable focus=background(selected?selection():Color.TRANSPARENT,10);focus.setStroke(dp(1),accent());
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},idle);
        return new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(selection()),states,background(Color.WHITE,10)),dp(4),dp(6),dp(4),dp(6));
    }
    private Drawable iconBackground(boolean primary){
        GradientDrawable idle=primary?primaryBackground(20):background(Color.TRANSPARENT,20);
        GradientDrawable focus=primary?primaryBackground(20):background(Color.TRANSPARENT,20);focus.setStroke(dp(1),primary?primaryInk():accent());
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},idle);
        return new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(selection()),states,background(Color.WHITE,20)),dp(4));
    }
    private boolean animationsEnabled(){return prefs.getBoolean("animationsEnabled",true)&&(Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled());}
    private void pressFeedback(View view,int action,boolean allowed){
        if(action!=MotionEvent.ACTION_DOWN&&action!=MotionEvent.ACTION_UP&&action!=MotionEvent.ACTION_CANCEL)return;
        view.animate().cancel();
        if(action==MotionEvent.ACTION_CANCEL||!allowed||!view.isEnabled()||!animationsEnabled()||dragReorder!=null){view.setScaleX(1f);view.setScaleY(1f);return;}
        boolean pressed=action==MotionEvent.ACTION_DOWN;
        view.animate().scaleX(pressed?.96f:1f).scaleY(pressed?.96f:1f).setDuration(pressed?80:160)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    private void resetPress(View view){view.animate().cancel();view.setScaleX(1f);view.setScaleY(1f);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(service);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(service);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String content,int size,boolean secondary){
        TextView v=new TextView(service);v.setText(content);v.setTextSize(size);v.setTextColor(secondary?muted():ink());v.setFontFeatureSettings("kern");return v;
    }
    private void textIfChanged(TextView view,String content){if(!content.contentEquals(view.getText()))view.setText(content);}
    private Button button(String name,Runnable action){
        Button b=new PressButton();b.setText(name);b.setTextSize(UiStrings.english()?12:13);b.setMaxLines(2);b.setAllCaps(false);b.setTextColor(ink());b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(8),0,dp(8),0);b.setBackground(buttonBackground(false));b.setOnClickListener(v->action.run());return b;
    }
    private void add(LinearLayout parent,View child,int height){parent.addView(child,new LinearLayout.LayoutParams(-1,height<0?height:dp(height)));}
    private void gap(LinearLayout parent,int h){add(parent,new View(service),h);}
    private void weighted(LinearLayout row,View child,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(height),1);if(row.getChildCount()>0)p.leftMargin=dp(8);row.addView(child,p);}
    private void control(LinearLayout row,View child){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(48),dp(48));if(row.getChildCount()>0)p.leftMargin=dp(8);row.addView(child,p);}
    private void moreItem(String kind,String label,Runnable action){
        LinearLayout item=row();IconButton icon=new IconButton(kind,label,false);icon.setClickable(false);icon.setFocusable(false);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.setOnClickListener(v->action.run());item.setBackground(buttonBackground(false));icon.setBackground(null);
        item.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));Button entry=button(label,action);entry.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);item.addView(entry,new LinearLayout.LayoutParams(0,dp(48),1));add(drawer,item,48);
    }
    private TextView hint(LinearLayout parent,String label){TextView v=text(label,13,true);v.setPadding(0,dp(8),0,dp(8));add(parent,v,-2);return v;}
    private void send(String action,String value){PlayerBridge.event(action,value);}
    private void act(String action,String value){service.dispatch(action,value);}
    private JSONArray array(JSONObject data,String key){JSONArray a=data.optJSONArray(key);return a==null?new JSONArray():a;}
    private static JSONArray searchSourceIds(JSONObject data){
        JSONArray selected=data.optJSONArray("searchSources"),result=new JSONArray();
        for(String source:SEARCH_SOURCE_IDS){
            if(selected==null){result.put(source);continue;}
            for(int i=0;i<selected.length();i++)if(source.equals(selected.optString(i))){result.put(source);break;}
        }
        return result;
    }
    private static String trackSourceName(JSONObject track){
        String name=track.optString("sourceName");if(!name.isEmpty())return UiStrings.text(name);
        String source=track.optString("source");
        if(source.equals("netease"))return UiStrings.text("网易云");
        if(source.equals("tencent"))return UiStrings.text("QQ音乐");
        if(source.equals("kuwo"))return UiStrings.text("酷我音乐");
        return UiStrings.text("本地");
    }
    private static int searchResultLimit(JSONObject data){
        int value=data.optInt("searchResultLimit",30);
        for(int allowed:SEARCH_RESULT_LIMITS)if(value==allowed)return value;
        return 30;
    }
    private static String rankingSource(JSONObject data){
        String value=data.optString("rankingSource","netease");
        for(String source:SEARCH_SOURCE_IDS)if(source.equals(value))return source;
        return "netease";
    }
    private static String onlineMetadata(JSONObject list){
        String source=list.optString("sourceName");
        if(source.isEmpty()){
            String id=list.optString("id"),platform=list.optString("source");
            if(platform.isEmpty()&&id.contains(":"))platform=id.substring(0,id.indexOf(':'));
            source=platform.equals("tencent")?UiStrings.text("QQ音乐"):platform.equals("kuwo")?UiStrings.text("酷我音乐"):UiStrings.text("网易云");
        }
        int count=list.optInt("trackCount",-1);if(count>=0)source+=" · "+count+UiStrings.text(" 首");
        String creator=list.optString("creator"),frequency=list.optString("updateFrequency");
        if(!creator.isEmpty())source+=" · "+creator;if(!frequency.isEmpty())source+=" · "+frequency;
        return source;
    }
    private static String trackSubtitle(JSONObject track){String by=track.optString("artist");return trackSourceName(track)+(by.isEmpty()?"":" · "+by);}
    private static int queueEntryIndex(JSONArray entries,String entryId){
        if(entryId==null||entryId.isEmpty())return -1;
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry!=null&&entryId.equals(entry.optString("queueId")))return i;}return -1;
    }
    /** Compare occurrence order only: a title or playback-position refresh must not invalidate a drag. */
    private static String queueOrderKey(JSONArray entries){
        JSONArray ids=new JSONArray();for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);ids.put(entry==null?"":entry.optString("queueId"));}return ids.toString();
    }
    private static JSONObject queueMovePayload(JSONArray entries,String entryId,int to,String expectedOrder){
        if(to<0||to>=entries.length()||queueEntryIndex(entries,entryId)<0||!queueOrderKey(entries).equals(expectedOrder))return null;
        try{return new JSONObject().put("entryId",entryId).put("to",to);}catch(JSONException ignored){return null;}
    }
    private static JSONObject enqueuePayload(JSONObject track,String placement){
        if(track==null||!(placement.equals("next")||placement.equals("tail")))return null;
        try{JSONObject song=new JSONObject(track.toString());song.remove("queueId");song.remove("playedAtMs");return new JSONObject().put("track",song).put("placement",placement);}catch(JSONException ignored){return null;}
    }
    private void enqueue(JSONObject track,String placement){JSONObject payload=enqueuePayload(track,placement);if(payload!=null){dismissPopup();act("enqueueTrack",payload.toString());}}
    private static int sleepMinutes(String value){
        if(value==null)return -1;String text=value.trim();if(!text.matches("[0-9]{1,3}"))return -1;
        try{int minutes=Integer.parseInt(text);return minutes>=1&&minutes<=180?minutes:-1;}catch(NumberFormatException ignored){return -1;}
    }
    private static String sleepStatus(JSONObject state){
        String mode=state.optString("sleepMode","off");if(!mode.equals("countdown")&&!mode.equals("endOfTrack"))return "";
        String published=UiStrings.text(state.optString("sleepText"));if(!published.isEmpty())return published;
        if(mode.equals("endOfTrack"))return UiStrings.text("播完本首停止");
        int seconds=Math.max(0,state.optInt("sleepRemaining"));return String.format(Locale.ROOT,UiStrings.text("还剩 %d:%02d"),seconds/60,seconds%60);
    }
    private static String historySubtitle(JSONObject track){
        long played=track.optLong("playedAtMs");return trackSubtitle(track)+" · "+(played>0?new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(played)):UiStrings.text("播放时间未知"));
    }
    private static String currentSourceName(JSONObject playback,JSONObject ui){
        JSONObject track=playback.optJSONObject("loadedTrack");if(track!=null)return trackSourceName(track);
        if(playback.optBoolean("busy"))return "";
        return playback.optString("currentSourceName",ui.optString("currentSourceName"));
    }
    private static String[] qualityLevels(String source){
        if(source.equals("netease"))return new String[]{"standard","higher","exhigh","lossless","hires"};
        if(source.equals("tencent"))return new String[]{"standard","exhigh","lossless","master"};
        if(source.equals("kuwo"))return new String[]{"standard","exhigh","lossless"};
        return new String[]{"standard"};
    }
    private static String sourceQuality(JSONObject playback,JSONObject ui,String source){
        JSONObject choices=playback.optJSONObject("sourceQualities");if(choices==null)choices=ui.optJSONObject("sourceQualities");
        String value=choices==null?(source.equals("netease")?playback.optString("quality",ui.optString("quality","standard")):"standard"):choices.optString(source,"standard");
        for(String allowed:qualityLevels(source))if(allowed.equals(value))return value;
        return "standard";
    }
    private static String sourceLabel(String source){return source.equals("tencent")?UiStrings.text("QQ音乐"):source.equals("kuwo")?UiStrings.text("酷我音乐"):UiStrings.text("网易云");}
    private String selectedSourceQuality(String source){return pendingSourceQualities.optString(source,sourceQuality(playback,ui,source));}
    private void acknowledgeSourceQualities(JSONObject data){
        JSONObject choices=data.optJSONObject("sourceQualities");if(choices==null)return;
        for(String source:SEARCH_SOURCE_IDS)if(pendingSourceQualities.has(source)&&pendingSourceQualities.optString(source).equals(choices.optString(source)))pendingSourceQualities.remove(source);
    }
    private JSONObject args(Object... pairs){JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);}catch(JSONException ignored){}return value;}
    private void library(String action,JSONObject value){send("libraryAction",args("action",action,"args",value).toString());}
    private void notice(String message){String translated=UiStrings.text(message);if(feedback!=null){feedback.setText(translated);feedback.setVisibility(View.VISIBLE);}Toast.makeText(service,translated,Toast.LENGTH_SHORT).show();}
    private String activePlaylist(){return ui.optString("activePlaylist");}
    private boolean currentSource(String id){if(id.equals(activePlaylist()))return true;dismissPopup();notice(UiStrings.text("当前歌单已切换，请重新选择要操作的歌曲。"));return false;}
    private JSONObject currentList(){JSONArray lists=array(ui,"playlists");for(int i=0;i<lists.length();i++){JSONObject list=lists.optJSONObject(i);if(list!=null&&list.optString("id").equals(activePlaylist()))return list;}return new JSONObject();}
    private JSONArray selectedTracks(){JSONArray ids=new JSONArray();JSONArray tracks=array(ui,"tracks");for(int i=0;i<tracks.length();i++){JSONObject track=tracks.optJSONObject(i);if(track!=null&&selectedIds.contains(track.optString("id")))ids.put(track.optString("id"));}return ids;}
    private String feedbackMessage(){if(section.equals("lyrics"))return "";if(detail.equals("queue")||detail.equals("history")||detail.equals("sleep"))return UiStrings.text(playback.optString("playbackMessage"));String message=UiStrings.text(ui.optString("message"));return message.isEmpty()?UiStrings.text(ui.optString("libraryMessage")):message;}

    void show() {
        if(closed || !Settings.canDrawOverlays(service))return;
        if(window!=null){syncVisibility();return;}
        expanded=false;
        IconButton icon=new IconButton("music", UiStrings.text("浮音，点击展开，拖动移动"), true);
        int iconId=service.getResources().getIdentifier("floatmusic_floating","drawable",service.getPackageName());
        if(iconId!=0){icon.kind="brand";icon.setBackground(service.getDrawable(iconId));}else icon.setBackground(primaryBackground(28));
        icon.setOnClickListener(v->expand());drag(icon);
        window=icon;
        params=new WindowManager.LayoutParams(dp(56),dp(56),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.x=prefs.getInt("x",dp(12));params.y=prefs.getInt("y",dp(80));
        params.alpha=1f;params.setTitle("FloatMusic overlay");attach();
    }
    void syncVisibility(){
        if(window==null)return;
        boolean hidden=PlayerBridge.foreground || PlayerBridge.picking;
        if(hidden && keyboard)hideKeyboard();
        if(hidden)dismissPopup();
        window.setVisibility(hidden?View.GONE:View.VISIBLE);
    }
    void close(){closed=true;cancelLyricFollow();hideKeyboard();remove();}
    void openLyricSettings(){show();if(!expanded)expand();rememberDrafts();hideKeyboard();clearSelection();section="more";settingsPage="floating";selectDetail("settings");}
    void showVolumeFromNotification(){
        if(closed)return;show();if(window==null)return;if(!expanded)expand();if(window==null||volumeButton==null)return;
        View opened=window;IconButton anchor=volumeButton;
        Runnable show=()->{if(closed||!expanded||window!=opened||volumeButton!=anchor)return;anchor.requestRectangleOnScreen(new Rect(0,0,anchor.getWidth(),anchor.getHeight()),false);anchor.post(()->{if(!closed&&expanded&&window==opened&&volumeButton==anchor)showVolume();});};
        if(opened.isLaidOut()&&anchor.getWidth()>0)show.run();
        else opened.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){if(r>l&&b>t&&anchor.getWidth()>0){v.removeOnLayoutChangeListener(this);show.run();}}});
    }
    private void remove(){stopReorder();dismissPopup();if(window!=null){try{manager.removeView(window);}catch(IllegalArgumentException ignored){}window=null;}}
    private void attach(){try{clamp();manager.addView(window,params);syncVisibility();}catch(Exception e){window=null;service.report(UiStrings.text("无法显示悬浮窗，请检查悬浮权限。"));}}
    private void collapse(){hideKeyboard();rememberDrafts();remove();section="";detail="";settingsPage="";show();}
    private void expand(){
        remove();expanded=true;keyboard=false;
        shell=column();shell.setPadding(dp(16),dp(8),dp(16),dp(12));applyOpacity();
        LinearLayout header=row();TextView brand=text(UiStrings.text("浮音"),14,true);brand.setTypeface(null,Typeface.BOLD);brand.setContentDescription(UiStrings.text("浮音，拖动此处移动窗口"));drag(brand);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));brand.setGravity(Gravity.CENTER_VERTICAL);
        sleepButton=new IconButton("timing",UiStrings.text("睡眠定时"),false);sleepButton.setOnClickListener(v->{rememberDrafts();hideKeyboard();clearSelection();section="more";selectDetail("sleep");});sleepButton.setVisibility(View.GONE);header.addView(sleepButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        IconButton minimize=new IconButton("minus",UiStrings.text("收为图标"),false);minimize.setOnClickListener(v->collapse());header.addView(minimize,new LinearLayout.LayoutParams(dp(48),dp(48)));add(shell,header,48);
        title=text(UiStrings.text("选择一首音乐"),22,false);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);add(shell,title,-2);
        artist=text(UiStrings.text("更多里搜索或导入音乐"),13,true);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);add(shell,artist,24);
        progress=slider();progress.setContentDescription(UiStrings.text("播放进度"));add(shell,progress,48);
        time=text("0:00 / 0:00",12,true);add(shell,time,18);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){seeking=true;}
            public void onProgressChanged(SeekBar b,int value,boolean user){if(user)time.setText(clock(value)+" / "+clock(playback.optInt("duration")));}
            public void onStopTrackingTouch(SeekBar b){act("seek",Integer.toString(b.getProgress()));seeking=false;}
        });
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);
        IconButton previous=new IconButton("previous",UiStrings.text("上一首"),false);previous.setOnClickListener(v->act("previous",""));control(controls,previous);
        play=new IconButton("play",UiStrings.text("播放"),true);play.setOnClickListener(v->act("toggle",""));control(controls,play);
        IconButton next=new IconButton("next",UiStrings.text("下一首"),false);next.setOnClickListener(v->act("next",""));control(controls,next);
        modeButton=new IconButton("sequential",UiStrings.text("播放模式"),false);modeButton.setOnClickListener(v->showModes());control(controls,modeButton);
        volumeButton=new IconButton("volume",UiStrings.text("音量"),false);volumeButton.setOnClickListener(v->{if(volumePopupOpen)dismissPopup();else showVolume();});control(controls,volumeButton);gap(shell,4);add(shell,controls,48);
        LinearLayout tabs=row();tabs.setBackground(background(color("#EEEDF8","#2A2D49"),12));lyricsTab=button(UiStrings.text("歌词"),()->toggle("lyrics"));playlistTab=button(UiStrings.text("歌单"),()->toggle("playlist"));moreTab=button(UiStrings.text("更多"),()->toggle("more"));
        weighted(tabs,lyricsTab,48);weighted(tabs,playlistTab,48);weighted(tabs,moreTab,48);add(shell,tabs,48);
        error=text("",13,false);error.setTextColor(color("#B42318","#FF9B93"));error.setPadding(0,dp(8),0,0);error.setOnClickListener(v->send("retryPlayback",""));error.setContentDescription(UiStrings.text("播放失败，点击重试"));add(shell,error,-2);
        drawer=column();
        detailScroll=new ScrollView(service){
            @Override public boolean dispatchTouchEvent(MotionEvent event){
                int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN)detailTouching=true;
                // Reserve the gesture before a clickable row handles DOWN; otherwise the
                // outer card intercepts MOVE and the list never starts scrolling.
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN
                    && (canScrollVertically(-1)||canScrollVertically(1))){
                    android.view.ViewParent parent=getParent();
                    if(parent!=null)parent.requestDisallowInterceptTouchEvent(true);
                }
                try{return super.dispatchTouchEvent(event);}
                finally{if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){detailTouching=false;if(dynamicRefreshQueued){dynamicRefreshQueued=false;post(()->refreshDynamic());}}}
            }
            @Override protected void onMeasure(int w,int h){
                int room=Math.max(dp(200),Math.min(dp(520),(int)(available().height()/scale)-dp(330)));
                super.onMeasure(w,section.equals("lyrics")?MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED):MeasureSpec.makeMeasureSpec(room,MeasureSpec.AT_MOST));
            }
        };
        detailScroll.setFillViewport(false);detailScroll.addView(drawer);add(shell,detailScroll,-2);
        scroll=new ScrollView(service);scroll.setFillViewport(false);scroll.setClipToPadding(true);scroll.addView(shell,new ScrollView.LayoutParams(-1,-2));
        scaled=new ScaledFrame();scaled.addView(scroll);
        popupLayer=new FrameLayout(service);popupLayer.setVisibility(View.GONE);popupLayer.setClickable(true);popupLayer.setOnClickListener(v->dismissPopup());
        scaled.addView(popupLayer);window=scaled;
        params.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;params.alpha=1f;
        resize(false);buildDrawer();attach();update(playback);refreshData(ui);
        window.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot){clamp();move();}});
    }
    private void applyOpacity(){
        int opacity=Math.max(20,Math.min(100,prefs.getInt("opacity",100)));
        // Keep the window and all controls opaque; only the decorative surface is translucent.
        Drawable material=materialBackground(22,false);material.setAlpha(Math.round(255*opacity/100f));shell.setBackground(material);
    }
    private SeekBar slider(){SeekBar b=new SeekBar(service);b.setPadding(dp(8),0,dp(8),0);b.setProgressTintList(ColorStateList.valueOf(accent()));b.setThumbTintList(ColorStateList.valueOf(accent()));b.setProgressBackgroundTintList(ColorStateList.valueOf(color("#DFDFEB","#343950")));return b;}
    private static String clock(int ms){int s=ms/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    void update(JSONObject data){
        playback=data;
        acknowledgeSourceQualities(data);
        if(!expanded || window==null)return;
        updateQualityControls();
        updating=true;
        title.setText(data.optString("currentTrack").isEmpty()?UiStrings.text(data.optString("title", "选择一首音乐")):data.optString("title"));updateCurrentSource();
        boolean playing=data.optBoolean("playing");play.kind=playing?"pause":"play";play.setContentDescription(playing?UiStrings.text("暂停"):UiStrings.text("播放"));play.invalidate();play.setEnabled(data.optBoolean("ready")&&!data.optBoolean("busy"));
        modeButton.kind=data.optString("playbackMode","sequential");modeButton.setContentDescription(UiStrings.text("播放模式：")+modeName(modeButton.kind));modeButton.invalidate();
        if(!seeking){progress.setMax(Math.max(1,data.optInt("duration")));progress.setProgress(data.optInt("position"));time.setText(clock(data.optInt("position"))+" / "+clock(data.optInt("duration")));}
        progress.setEnabled(data.optBoolean("ready")&&data.optInt("duration")>0);
        int gain=Math.max(0,Math.min(100,data.optInt("volume",70)));
        if(volume!=null&&!volume.isPressed()){volume.setProgress(gain);volume.setContentDescription(UiStrings.text("音量，")+gain+UiStrings.text("%，向上增加"));if(volumeText!=null)volumeText.setText(gain+"%");}
        updateVolumeControl(volume!=null&&volume.isPressed()?volume.getProgress():gain);updateOutputControl();
        String message=UiStrings.text(data.optString("error"));error.setText(message.isEmpty()?"":message+UiStrings.text("  点击重试"));error.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
        updating=false;
        updateLyrics(false);
        updateSleepControls();
        if(feedback!=null&&(detail.equals("queue")||detail.equals("history")||detail.equals("sleep"))){String note=feedbackMessage();textIfChanged(feedback,note);feedback.setVisibility(note.isEmpty()?View.GONE:View.VISIBLE);}
        if(detail.equals("queue")||detail.equals("history"))refreshDynamic();
    }
    private void updateCurrentSource(){
        if(artist==null)return;JSONObject track=playback.optJSONObject("loadedTrack");String name=currentSourceName(playback,ui);
        String by=track==null?"":track.optString("artist");String caption=name+(by.isEmpty()?"":(name.isEmpty()?"":" · ")+by);
        if(playback.optBoolean("busy"))caption+=(caption.isEmpty()?"":" · ")+UiStrings.text("正在加载…");
        textIfChanged(artist,caption.isEmpty()?UiStrings.text("更多里搜索或导入音乐"):caption);
    }
    private void toggle(String name){
        hideKeyboard();rememberDrafts();clearSelection();section=section.equals(name)?"":name;detail="";settingsPage="";buildDrawer();
        // A tab change starts at the top immediately; a parent scroll animation
        // would intercept the first DOWN before the new list can reserve it.
        scroll.post(()->scroll.scrollTo(0,0));
    }
    private void selectDetail(String name){hideKeyboard();rememberDrafts();detail=name;if(!name.equals("settings"))settingsPage="";if(detailScroll!=null)detailScroll.scrollTo(0,0);buildDrawer();if(name.equals("rankings"))ensureRankings();}
    private void openSettingsPage(String page){settingsPage=page;selectDetail("settings");}
    private void ensureRankings(){if(!rankingsRequested&&array(ui,"rankings").length()==0&&!ui.optBoolean("rankingsLoading"))loadRankings();}
    private void loadRankings(){if(ui.optBoolean("rankingsLoading")||pendingRankingSource!=null)return;rankingsRequested=true;library("loadRankings",args("source",selectedRankingSource()));}
    private String selectedRankingSource(){return pendingRankingSource==null?rankingSource(ui):pendingRankingSource;}
    private void chooseRankingSource(String source){
        if(source.equals(selectedRankingSource()))return;
        pendingRankingSource=source;rankingsRequested=true;updateRankingControls();refreshDynamic();library("loadRankings",args("source",source));
    }
    private void rememberDrafts(){if(searchInput!=null)searchDraft=searchInput.getText().toString();if(apiInput!=null)apiDraft=apiInput.getText().toString();if(nameInput!=null)nameDraft=nameInput.getText().toString();if(sleepInput!=null)sleepDraft=sleepInput.getText().toString();}
    private void styleTab(Button b,String name){boolean selected=section.equals(name);b.setSelected(selected);b.setBackground(buttonBackground(selected));b.setTextColor(selected?accent():muted());b.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);b.setContentDescription(b.getText()+(selected?UiStrings.text("，已展开，再次点击收起"):UiStrings.text("，点击展开")));}
    private void buildDrawer(){
        if(drawer==null)return;
        cancelLyricFollow();
        stopReorder();
        if(detailScroll!=null)detailScroll.setOnDragListener(null);
        dismissPopup();rememberDrafts();searchInput=null;apiInput=null;nameInput=null;sleepInput=null;sleepStatusText=null;cancelSleepButton=null;afterCurrentButton=null;dynamic=null;rendered="";
        searchButton=null;searchSourceButtons.clear();qualityButton=null;qualityHint=null;qualitySources=null;sourceQualityButtons.clear();outputButton=null;customServiceFields=null;customServiceButton=null;
        searchResultLimitButton=null;rankingSourceButtons.clear();
        selectionCount=null;playlistPicker=null;playlistDescription=null;playlistDescriptionBlock=null;
        lyricScroll=null;lyricViewport=null;lyricRows=null;lyricReturn=null;lyricMessage=null;lyricTexts.clear();activeLyric=-1;lyricTouching=false;
        drawer.removeAllViews();sourceInfo=null;drawer.setVisibility(section.isEmpty()?View.GONE:View.VISIBLE);
        styleTab(lyricsTab,"lyrics");styleTab(playlistTab,"playlist");styleTab(moreTab,"more");
        if(section.isEmpty())return;
        gap(drawer,section.equals("lyrics")?6:12);View line=new View(service);line.setBackgroundColor(color("#DFDFEB","#343950"));add(drawer,line,1);gap(drawer,section.equals("lyrics")?6:12);
        feedback=text(feedbackMessage(),13,true);feedback.setVisibility(feedbackMessage().isEmpty()?View.GONE:View.VISIBLE);add(drawer,feedback,-2);
        if(section.equals("lyrics"))buildLyrics();
        else if(section.equals("playlist"))buildPlaylist();
        else if(detail.equals("search"))buildSearch();
        else if(detail.equals("rankings"))buildRankings();
        else if(detail.equals("online"))buildOnline();
        else if(detail.equals("settings"))buildSettings();
        else if(detail.equals("queue"))buildQueue();
        else if(detail.equals("history"))buildHistory();
        else if(detail.equals("sleep"))buildSleep();
        else buildMore();
        refreshDynamic();if(scaled!=null)scaled.requestLayout();
    }
    private void detailHeader(String label){
        detailHeader(label,UiStrings.text("返回更多"),()->selectDetail(""));
    }
    private void detailHeader(String label,String backLabel,Runnable onBack){
        LinearLayout head=row();TextView heading=text(label,18,false);heading.setTypeface(null,Typeface.BOLD);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button back=button(backLabel,onBack);back.setTag("settingsBack");head.addView(back,new LinearLayout.LayoutParams(dp(96),dp(48)));add(drawer,head,48);gap(drawer,8);
    }
    private void buildMore(){
        moreItem("queue",UiStrings.text("播放队列"),()->selectDetail("queue"));
        moreItem("history",UiStrings.text("最近播放"),()->selectDetail("history"));
        moreItem("timing",UiStrings.text("睡眠定时"),()->selectDetail("sleep"));
        moreItem("search",UiStrings.text("搜索音乐"),()->selectDetail("search"));
        moreItem("rankings",UiStrings.text("排行榜"),()->selectDetail("rankings"));
        moreItem("favorite",UiStrings.text("收藏歌单"),()->{clearSelection();section="playlist";detail="";send("selectPlaylist","favorites");buildDrawer();});
        moreItem("import",UiStrings.text("导入音乐"),this::showImportMenu);
        moreItem("settings",UiStrings.text("设置"),()->selectDetail("settings"));
        gap(drawer,8);divider(drawer);gap(drawer,4);
        add(drawer,button(UiStrings.text("收藏 / 取消收藏当前歌曲"),()->send("favoriteCurrent","")),48);
        Button quit=button(UiStrings.text("退出浮音"),()->act("stop",""));quit.setTextColor(color("#B42318","#FF9B93"));add(drawer,quit,48);
    }
    private void buildQueue(){detailHeader(UiStrings.text("播放队列"));dynamic=column();add(drawer,dynamic,-2);}
    private void queueRows(LinearLayout parent){
        JSONArray entries=array(playback,"queueEntries");LinearLayout tools=row();TextView count=text(UiStrings.text("共 ")+entries.length()+UiStrings.text(" 首"),13,true);tools.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=button(UiStrings.text("清空队列"),()->confirm(UiStrings.text("清空播放队列"),UiStrings.text("清空待播放队列？当前歌曲会继续播放，结束后停止。"),UiStrings.text("确认清空队列"),()->act("clearPlaybackQueue","")));clear.setEnabled(entries.length()>0);clear.setAlpha(entries.length()>0?1f:.45f);tools.addView(clear,new LinearLayout.LayoutParams(dp(96),dp(48)));add(parent,tools,48);
        if(entries.length()==0){hint(parent,UiStrings.text("队列为空 · 在歌曲操作中选择下一首播放或加入队尾。"));return;}
        hint(parent,UiStrings.text("点歌名播放 · 长按手柄拖动，或点击选择排序"));queuePage=Math.min(queuePage,(entries.length()-1)/SONG_PAGE_SIZE);
        LinearLayout songs=column();add(parent,songs,-2);String current=playback.optString("currentQueueEntry");String order=queueOrderKey(entries);
        for(int i=queuePage*SONG_PAGE_SIZE;i<Math.min(entries.length(),(queuePage+1)*SONG_PAGE_SIZE);i++){
            final int index=i;JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;String entryId=entry.optString("queueId"),name=entry.optString("name"),by=trackSubtitle(entry);boolean active=entryId.equals(current);LinearLayout song=row();song.setTag(index);
            Button nameButton=button(name,()->act("playQueueEntry",entryId));nameButton.setText(songCaption((active?UiStrings.text("正在播放 · "):"")+name,by));nameButton.setTextSize(14);nameButton.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);nameButton.setMaxLines(2);nameButton.setEllipsize(TextUtils.TruncateAt.END);nameButton.setContentDescription(UiStrings.text("播放队列第 ")+(index+1)+UiStrings.text(" 首，")+name+"，"+by+(active?UiStrings.text("，正在播放"):""));nameButton.setBackground(buttonBackground(active));if(active)nameButton.setTextColor(accent());song.addView(nameButton,new LinearLayout.LayoutParams(0,dp(48),1));
            IconButton handle=new IconButton("grip",UiStrings.text("排序队列，")+name+UiStrings.text("，长按拖动，点击选择上移或下移"),false);handle.motionEnabled=false;handle.setOnClickListener(v->showQueueReorderMenu(entryId));handle.setOnLongClickListener(v->{if(queueEntryIndex(array(playback,"queueEntries"),entryId)<0)return true;resetPress(handle);dragReorder=new DragReorder(songs,song,"queue",index,order);boolean started=handle.startDragAndDrop(ClipData.newPlainText("queue-entry",entryId),new View.DragShadowBuilder(song),dragReorder,0);if(started){song.setAlpha(.45f);handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}else stopReorder();return true;});control(song,handle);
            IconButton more=new IconButton("more",UiStrings.text("队列歌曲操作，")+name,false);more.setOnClickListener(v->showQueueTrackMenu(entry));control(song,more);add(songs,song,-2);divider(songs);
        }
        pageControls(parent,"queue",queuePage,entries.length(),SONG_PAGE_SIZE);detailScroll.setOnDragListener((v,event)->handleSongDrag(event));
    }
    private void showQueueTrackMenu(JSONObject entry){
        String id=entry.optString("queueId");LinearLayout menu=column();menuItem(menu,UiStrings.text("播放这首"),()->{dismissPopup();act("playQueueEntry",id);});menuItem(menu,UiStrings.text("下一首播放"),()->enqueue(entry,"next"));menuItem(menu,UiStrings.text("加入队尾"),()->enqueue(entry,"tail"));menuItem(menu,UiStrings.text("调整队列顺序"),()->showQueueReorderMenu(id));
        menuItem(menu,UiStrings.text("从队列移除"),()->confirm(UiStrings.text("移除队列歌曲"),UiStrings.text("移除这次添加的“")+entry.optString("name")+UiStrings.text("”？当前歌曲会继续播放。"),UiStrings.text("确认移除"),()->act("removeQueueEntry",id)));showPanel(entry.optString("name"),menu);
    }
    private void showQueueReorderMenu(String entryId){
        JSONArray entries=array(playback,"queueEntries");int from=queueEntryIndex(entries,entryId);if(from<0){notice(UiStrings.text("队列已更新，请重新选择歌曲。"));return;}String order=queueOrderKey(entries);LinearLayout menu=column();int count=entries.length();
        int[] positions={from-1,from+1,0,count-1};String[] labels={UiStrings.text("上移一首"),UiStrings.text("下移一首"),UiStrings.text("移到最前"),UiStrings.text("移到最后")};
        for(int i=0;i<positions.length;i++){final int to=positions[i];if(to<0||to>=count||to==from)continue;menuItem(menu,labels[i],()->{dismissPopup();JSONObject change=queueMovePayload(array(playback,"queueEntries"),entryId,to,order);if(change!=null)act("moveQueueEntry",change.toString());else notice(UiStrings.text("队列已更新，请重新选择排序位置。"));});}showPanel(UiStrings.text("调整队列顺序"),menu);
    }
    private void buildHistory(){detailHeader(UiStrings.text("最近播放"));dynamic=column();add(drawer,dynamic,-2);}
    private void historyRows(LinearLayout parent){
        JSONArray entries=array(playback,"historyEntries");LinearLayout tools=row();tools.addView(text(UiStrings.text("最近实际播放的 ")+entries.length()+UiStrings.text(" 首"),13,true),new LinearLayout.LayoutParams(0,-2,1));Button clear=button(UiStrings.text("清空历史"),()->confirm(UiStrings.text("清空最近播放"),UiStrings.text("清空最近播放记录？播放队列和歌单会保留。"),UiStrings.text("确认清空历史"),()->act("clearPlaybackHistory","")));clear.setEnabled(entries.length()>0);clear.setAlpha(entries.length()>0?1f:.45f);tools.addView(clear,new LinearLayout.LayoutParams(dp(96),dp(48)));add(parent,tools,48);
        if(entries.length()==0){hint(parent,UiStrings.text("还没有最近播放记录。音乐成功开始播放后会出现在这里。"));return;}historyPage=Math.min(historyPage,(entries.length()-1)/SONG_PAGE_SIZE);
        for(int i=historyPage*SONG_PAGE_SIZE;i<Math.min(entries.length(),(historyPage+1)*SONG_PAGE_SIZE);i++){
            JSONObject track=entries.optJSONObject(i);if(track==null)continue;String id=track.optString("id"),name=track.optString("name"),subtitle=historySubtitle(track);LinearLayout item=row();LinearLayout info=column();info.setPadding(0,dp(8),0,dp(8));
            Button replay=button(name,()->act("playHistoryTrack",id));replay.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);replay.setTextSize(14);replay.setMaxLines(2);replay.setEllipsize(TextUtils.TruncateAt.END);replay.setContentDescription(UiStrings.text("重播历史歌曲，")+name+"，"+subtitle);add(info,replay,48);TextView metadata=text(subtitle,12,true);metadata.setPadding(dp(8),0,0,0);add(info,metadata,-2);item.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            IconButton more=new IconButton("more",UiStrings.text("历史歌曲操作，")+name,false);more.setOnClickListener(v->{LinearLayout menu=column();menuItem(menu,UiStrings.text("重新播放"),()->{dismissPopup();act("playHistoryTrack",id);});menuItem(menu,UiStrings.text("下一首播放"),()->enqueue(track,"next"));menuItem(menu,UiStrings.text("加入队尾"),()->enqueue(track,"tail"));menuItem(menu,UiStrings.text("加入歌单"),()->addTrackTo(track));showPanel(name,menu);});control(item,more);add(parent,item,-2);divider(parent);
        }pageControls(parent,"history",historyPage,entries.length(),SONG_PAGE_SIZE);
    }
    private void buildSleep(){
        detailHeader(UiStrings.text("睡眠定时"));sleepStatusText=text("",14,false);sleepStatusText.setPadding(0,dp(8),0,dp(8));add(drawer,sleepStatusText,-2);hint(drawer,UiStrings.text("时间到后停止播放。后台播放时也生效。"));
        LinearLayout presets=row();for(int minutes:new int[]{15,30,60})weighted(presets,button(minutes+UiStrings.text(" 分钟"),()->{hideKeyboard();act("startSleepTimer",Integer.toString(minutes));}),48);add(drawer,presets,48);gap(drawer,8);
        hint(drawer,UiStrings.text("自定义分钟数 · 1–180 分钟"));sleepInput=field(UiStrings.text("睡眠定时分钟数，1至180"),sleepDraft);sleepInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);sleepInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});sleepInput.setImeOptions(EditorInfo.IME_ACTION_DONE);add(drawer,sleepInput,48);
        Runnable start=()->{int minutes=sleepMinutes(sleepInput.getText().toString());if(minutes<0){sleepInput.setError(UiStrings.text("请输入 1–180 的整数分钟"));sleepInput.requestFocus();return;}sleepDraft=sleepInput.getText().toString();sleepInput.setError(null);hideKeyboard();act("startSleepTimer",Integer.toString(minutes));};sleepInput.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){start.run();return true;}return false;});add(drawer,button(UiStrings.text("开始定时"),start),48);gap(drawer,8);divider(drawer);gap(drawer,8);
        afterCurrentButton=button(UiStrings.text("播完本首"),()->{hideKeyboard();act("sleepAfterCurrentTrack","");});add(drawer,afterCurrentButton,48);cancelSleepButton=button(UiStrings.text("取消定时"),()->act("cancelSleepTimer",""));add(drawer,cancelSleepButton,48);updateSleepControls();
    }
    private void updateSleepControls(){
        String status=sleepStatus(playback);boolean active=!status.isEmpty();if(sleepButton!=null){sleepButton.setVisibility(active?View.VISIBLE:View.GONE);sleepButton.setContentDescription(UiStrings.text("睡眠定时：")+status+UiStrings.text("，点击调整"));if(sleepButton.isSelected()!=active){sleepButton.setSelected(active);sleepButton.setBackground(buttonBackground(active));}if(Build.VERSION.SDK_INT>=30)sleepButton.setStateDescription(status);}
        if(sleepStatusText!=null)textIfChanged(sleepStatusText,active?status:UiStrings.text("未开启睡眠定时"));if(cancelSleepButton!=null){cancelSleepButton.setEnabled(active);cancelSleepButton.setAlpha(active?1f:.45f);}if(afterCurrentButton!=null){boolean ready=!playback.optString("currentTrack").isEmpty();afterCurrentButton.setEnabled(ready);afterCurrentButton.setAlpha(ready?1f:.45f);}
    }
    private void buildRankings(){
        detailHeader(UiStrings.text("排行榜"));LinearLayout sources=row();String[] labels={UiStrings.text("网易云"),UiStrings.text("QQ音乐"),UiStrings.text("酷我")};
        for(int i=0;i<SEARCH_SOURCE_IDS.length;i++){final String source=SEARCH_SOURCE_IDS[i];Button choice=button(labels[i],()->chooseRankingSource(source));rankingSourceButtons.add(choice);weighted(sources,choice,48);}
        add(drawer,sources,48);gap(drawer,8);updateRankingControls();dynamic=column();add(drawer,dynamic,-2);
    }
    private void updateRankingControls(){
        String source=selectedRankingSource();
        for(int i=0;i<rankingSourceButtons.size();i++){Button choice=rankingSourceButtons.get(i);boolean selected=SEARCH_SOURCE_IDS[i].equals(source);choice.setSelected(selected);choice.setBackground(buttonBackground(selected));choice.setTextColor(selected?accent():ink());choice.setContentDescription(choice.getText()+UiStrings.text("排行榜")+(selected?UiStrings.text("，已选中"):UiStrings.text("，点击查看")));}
    }
    private void rankingRows(LinearLayout parent){
        boolean waiting=pendingRankingSource!=null,loading=waiting||ui.optBoolean("rankingsLoading");JSONArray lists=waiting?new JSONArray():array(ui,"rankings");String message=waiting?"":UiStrings.text(ui.optString("rankingsMessage"));
        String platform=selectedRankingSource().equals("tencent")?UiStrings.text("QQ音乐"):selectedRankingSource().equals("kuwo")?UiStrings.text("酷我"):UiStrings.text("网易云");
        LinearLayout tools=row();TextView status=text(platform+" · "+(loading?UiStrings.text("正在加载排行榜…"):lists.length()>0?lists.length()+UiStrings.text(" 个榜单"):UiStrings.text("查看热门与新歌榜单")),13,true);tools.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        Button refresh=button(loading?UiStrings.text("加载中"):UiStrings.text("刷新"),this::loadRankings);refresh.setEnabled(!loading);refresh.setAlpha(loading?.45f:1f);tools.addView(refresh,new LinearLayout.LayoutParams(dp(64),dp(48)));add(parent,tools,48);
        if(!message.isEmpty()){TextView note=text(message,13,true);note.setTextColor(color("#B42318","#FF9B93"));add(parent,note,-2);gap(parent,8);}
        if(lists.length()==0){if(!loading){hint(parent,message.isEmpty()?UiStrings.text("暂时没有可用榜单，点击刷新再试。"):UiStrings.text("排行榜未能加载，请重试。"));add(parent,button(UiStrings.text("重试"),this::loadRankings),48);}return;}
        for(int i=0;i<lists.length();i++){
            JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id"),name=list.optString("name"),frequency=list.optString("updateFrequency");
            LinearLayout item=new PressRow();item.setOrientation(LinearLayout.VERTICAL);item.setPadding(dp(8),dp(12),dp(8),dp(12));item.setBackground(buttonBackground(false));item.setOnClickListener(v->openOnline(id,"rankings"));item.setFocusable(true);item.setContentDescription(UiStrings.text("查看")+name+(frequency.isEmpty()?"":"，"+frequency));
            TextView heading=text(name,16,false);heading.setTypeface(null,Typeface.BOLD);heading.setMaxLines(2);heading.setEllipsize(TextUtils.TruncateAt.END);add(item,heading,-2);
            String meta=onlineMetadata(list);item.setContentDescription(UiStrings.text("查看")+name+"，"+meta);
            if(!meta.isEmpty()){TextView info=text(meta,13,true);info.setPadding(0,dp(4),0,0);add(item,info,-2);}
            String description=list.optString("description");if(!description.isEmpty()){TextView note=text(description,13,true);note.setMaxLines(2);note.setEllipsize(TextUtils.TruncateAt.END);note.setPadding(0,dp(4),0,0);copyOnLongPress(note,()->description);note.setOnClickListener(v->item.performClick());add(item,note,-2);}
            item.setMinimumHeight(dp(64));add(parent,item,-2);divider(parent);
        }
    }
    private void openOnline(String id,String source){requestedOnlineId=id;onlineSource=source;onlinePage=0;selectDetail("online");library("openOnline",args("id",id));}
    private void buildLyrics(){
        LinearLayout info=row();lyricMessage=text("",12,true);lyricMessage.setMaxLines(1);lyricMessage.setEllipsize(TextUtils.TruncateAt.END);info.addView(lyricMessage,new LinearLayout.LayoutParams(0,-2,1));
        IconButton timing=new IconButton("timing",UiStrings.text("歌词时间微调"),false);timing.setOnClickListener(v->showTiming(timing));info.addView(timing,new LinearLayout.LayoutParams(dp(48),dp(48)));
        IconButton refresh=new IconButton("refresh",UiStrings.text("重新获取歌词"),false);refresh.setOnClickListener(v->send("retryLyrics",""));LinearLayout.LayoutParams refreshLayout=new LinearLayout.LayoutParams(dp(48),dp(48));refreshLayout.leftMargin=dp(8);info.addView(refresh,refreshLayout);add(drawer,info,48);
        FrameLayout viewport=new FrameLayout(service);lyricViewport=viewport;lyricScroll=new ScrollView(service){
            @Override public boolean dispatchTouchEvent(MotionEvent event){
                int action=event.getActionMasked();
                // Copyable TextViews receive DOWN themselves. Observe it before
                // dispatch so dragging text still cancels automatic following.
                if(action==MotionEvent.ACTION_DOWN){lyricTouching=true;beginLyricBrowsing();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}
                try{return super.dispatchTouchEvent(event);}
                finally{if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)lyricTouching=false;}
            }
            // Automatic follow owns the scroll position. A residual native
            // fling must not overwrite it after returning from manual browsing.
            @Override public void computeScroll(){if(manualLyrics)super.computeScroll();}
            @Override public boolean performAccessibilityAction(int action,android.os.Bundle arguments){
                if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ||action==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()||action==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())beginLyricBrowsing();
                return super.performAccessibilityAction(action,arguments);
            }
            @Override public boolean executeKeyEvent(KeyEvent event){
                int key=event.getKeyCode();
                if(event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_PAGE_UP||key==KeyEvent.KEYCODE_PAGE_DOWN||key==KeyEvent.KEYCODE_MOVE_HOME||key==KeyEvent.KEYCODE_MOVE_END||key==KeyEvent.KEYCODE_SPACE))beginLyricBrowsing();
                return super.executeKeyEvent(event);
            }
            @Override public boolean onGenericMotionEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_SCROLL)beginLyricBrowsing();return super.onGenericMotionEvent(event);}
            @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
                super.onSizeChanged(w,h,oldW,oldH);
                if(h>0&&lyricRows!=null){
                    // Give the first and last sentences room to pass through
                    // the centre, just like sentences in the middle of a song.
                    lyricRows.setPadding(dp(4),h/2,dp(12),h/2);updateLyrics(true);
                }
            }
        };lyricScroll.setFillViewport(false);
        lyricRows=new LyricColumn();lyricRows.setPadding(dp(4),dp(12),dp(12),dp(64));lyricScroll.addView(lyricRows);viewport.addView(lyricScroll,new FrameLayout.LayoutParams(-1,-1));
        lyricScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->updateLyricArrow());
        lyricReturn=new IconButton("down",UiStrings.text("回到当前歌词并恢复跟随"),false);lyricReturn.setBackground(buttonBackground(true));lyricReturn.setOnClickListener(v->{manualLyrics=false;updateLyrics(true);});
        FrameLayout.LayoutParams arrow=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM);arrow.rightMargin=dp(4);arrow.bottomMargin=dp(4);viewport.addView(lyricReturn,arrow);
        // ScaledFrame allocates the remaining screen height after measuring the controls.
        add(drawer,viewport,320);
        renderLyrics();
    }
    private void beginLyricBrowsing(){cancelLyricFollow();lyricGestureRevision++;manualLyrics=true;updateLyrics(false);}
    private static String modeName(String key){return key.equals("loop")?UiStrings.text("列表循环"):key.equals("single")?UiStrings.text("单曲循环"):key.equals("shuffle")?UiStrings.text("随机播放"):UiStrings.text("顺序播放");}
    private void dismissPopup(){
        boolean hadVolume=volumePopupOpen;volumePopupOpen=false;volume=null;volumeText=null;
        if(popupLayer!=null){if(popupLayer.findFocus() instanceof EditText)hideKeyboard();popupLayer.removeAllViews();popupLayer.setBackgroundColor(Color.TRANSPARENT);popupLayer.setVisibility(View.GONE);}
        if(hadVolume&&params!=null&&!keyboard){params.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;move();}
        updateVolumeControl(playback.optInt("volume",70));
        if(popupFocusReturn!=null&&popupFocusReturn.isAttachedToWindow())popupFocusReturn.requestFocus();popupFocusReturn=null;timingValue=null;
    }
    /** Popup coordinates are physical pixels in the unscaled layer, including on short screens. */
    private static int[] popupBounds(int layerWidth,int layerHeight,int anchorLeft,int anchorTop,int anchorWidth,int desiredWidth,int desiredHeight,int margin,int gap,int minimumHeight){
        int widthLimit=Math.max(1,layerWidth),heightLimit=Math.max(1,layerHeight);
        int inset=Math.max(0,Math.min(margin,(Math.min(widthLimit,heightLimit)-1)/2));
        int width=Math.max(1,Math.min(desiredWidth,widthLimit-inset*2)),cap=heightLimit-inset*2;
        int height=Math.max(1,Math.min(desiredHeight,Math.max(Math.min(minimumHeight,cap),Math.min(cap,anchorTop-gap-inset))));
        int left=Math.max(inset,Math.min(anchorLeft+anchorWidth-width,widthLimit-inset-width));
        int top=Math.max(inset,Math.min(anchorTop-height-gap,heightLimit-inset-height));
        return new int[]{left,top,width,height};
    }
    private int[] popupBounds(View anchor,int width,int height,int minimumHeight){
        int[] at=new int[2],origin=new int[2];anchor.getLocationOnScreen(at);scaled.getLocationOnScreen(origin);
        return popupBounds(scaled.getWidth(),scaled.getHeight(),at[0]-origin[0],at[1]-origin[1],Math.round(anchor.getWidth()*scale),width,height,dp(8),dp(8),minimumHeight);
    }
    private void showPopup(View anchor,LinearLayout content,int widthDp){
        if(popupLayer==null||!expanded)return;dismissPopup();
        int width=Math.min(dp(widthDp),scaled.getWidth()-dp(16));
        content.setPadding(dp(8),dp(8),dp(8),dp(8));content.setBackgroundColor(Color.TRANSPARENT);content.setClickable(true);
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int[] bounds=popupBounds(anchor,width,content.getMeasuredHeight(),dp(56));
        ScrollView menu=new ScrollView(service);menu.setBackground(materialBackground(16,true));menu.setClipToOutline(true);menu.setElevation(dp(8));menu.addView(content);
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(bounds[2],bounds[3]);layout.leftMargin=bounds[0];layout.topMargin=bounds[1];
        popupLayer.addView(menu,layout);popupLayer.setVisibility(View.VISIBLE);popupLayer.bringToFront();
    }
    private void updateVolumeControl(int value){
        if(volumeButton==null)return;int gain=Math.max(0,Math.min(100,value));
        volumeButton.setContentDescription(UiStrings.text("音量：")+gain+"%，"+(volumePopupOpen?UiStrings.text("已展开，再次点击收起"):UiStrings.text("点击展开")));
        if(volumeButton.isSelected()!=volumePopupOpen){volumeButton.setSelected(volumePopupOpen);volumeButton.setBackground(volumePopupOpen?buttonBackground(true):iconBackground(false));}
        if(Build.VERSION.SDK_INT>=30)volumeButton.setStateDescription(volumePopupOpen?UiStrings.text("已展开"):UiStrings.text("已收起"));
    }
    private void showVolume(){
        if(popupLayer==null||!expanded)return;hideKeyboard();dismissPopup();popupFocusReturn=volumeButton;
        LinearLayout content=column();content.setPadding(dp(8),dp(8),dp(8),dp(8));content.setBackground(materialBackground(16,true));content.setClipToOutline(true);content.setElevation(dp(8));content.setClickable(true);
        int gain=Math.max(0,Math.min(100,playback.optInt("volume",70)));
        volumeText=text(gain+"%",12,true);volumeText.setGravity(Gravity.CENTER);volumeText.setIncludeFontPadding(false);add(content,volumeText,24);
        volume=new VerticalVolumeSlider();volume.setMax(100);volume.setProgress(gain);volume.setContentDescription(UiStrings.text("音量，")+gain+UiStrings.text("%，向上增加"));content.addView(volume,new LinearLayout.LayoutParams(-1,0,1));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(!updating){volumeText.setText(value+"%");bar.setContentDescription(UiStrings.text("音量，")+value+UiStrings.text("%，向上增加"));updateVolumeControl(value);act("volume",Integer.toString(value));}}
        });
        int[] bounds=popupBounds(volumeButton,dp(72),dp(204),dp(88));FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(bounds[2],bounds[3]);layout.leftMargin=bounds[0];layout.topMargin=bounds[1];
        popupLayer.addView(content,layout);popupLayer.setVisibility(View.VISIBLE);popupLayer.bringToFront();volumePopupOpen=true;updateVolumeControl(gain);
        // Take native focus for Back and accessible volume adjustment, without opening the keyboard.
        params.flags&=~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;move();volume.requestFocus();
    }
    private static int volumeAtY(float y,int height,int padding){
        float length=Math.max(1,height-padding*2);return Math.max(0,Math.min(100,Math.round((height-padding-y)*100f/length)));
    }
    private static float volumeThumbY(int value,int height,int padding){
        float top=Math.min(padding,height/2f),bottom=Math.max(top,height-padding);return bottom-(bottom-top)*Math.max(0,Math.min(100,value))/100f;
    }
    /** Full-card, unscaled sheets keep long menus and text fields usable on a small phone. */
    private void showPanel(String label,LinearLayout content){
        if(popupLayer==null||!expanded)return;View previous=window.findFocus();dismissPopup();popupFocusReturn=previous;
        LinearLayout panel=column();panel.setPadding(dp(12),dp(12),dp(12),dp(12));panel.setBackgroundColor(Color.TRANSPARENT);panel.setClickable(true);
        LinearLayout header=row();TextView heading=text(label,18,false);heading.setTypeface(null,Typeface.BOLD);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button close=button(UiStrings.text("关闭"),this::dismissPopup);LinearLayout.LayoutParams closeLayout=new LinearLayout.LayoutParams(dp(64),dp(48));closeLayout.leftMargin=dp(8);header.addView(close,closeLayout);add(panel,header,48);gap(panel,8);add(panel,content,-2);
        int width=Math.max(dp(160),scaled.getWidth()-dp(16));int maxHeight=Math.max(dp(80),scaled.getHeight()-dp(16));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        ScrollView menu=new ScrollView(service){
            @Override protected void onMeasure(int w,int h){
                // Reflow the sheet when the keyboard reduces the floating window.
                int cap=Math.max(dp(64),scaled.getMeasuredHeight()-dp(16));
                super.onMeasure(w,MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(h),cap),MeasureSpec.AT_MOST));
            }
        };menu.setFillViewport(false);menu.setBackground(materialBackground(16,true));menu.setClipToOutline(true);menu.setElevation(dp(8));menu.addView(panel);
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(width,Math.min(panel.getMeasuredHeight(),maxHeight),Gravity.CENTER);layout.setMargins(dp(8),dp(8),dp(8),dp(8));
        popupLayer.setBackgroundColor(0x55000000);popupLayer.addView(menu,layout);popupLayer.setVisibility(View.VISIBLE);popupLayer.bringToFront();
        heading.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }
    private void menuItem(LinearLayout menu,String label,Runnable action){Button item=button(label,action);item.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);add(menu,item,48);}
    private void confirm(String title,String explanation,String label,Runnable action){LinearLayout content=column();hint(content,explanation);Button yes=button(label,()->{dismissPopup();action.run();});yes.setTextColor(color("#B42318","#FF9B93"));add(content,yes,48);gap(content,8);add(content,button(UiStrings.text("取消"),this::dismissPopup),48);showPanel(title,content);}
    private void showModes(){
        LinearLayout choices=column();for(String key:new String[]{"sequential","loop","single","shuffle"}){
            boolean selected=key.equals(playback.optString("playbackMode","sequential"));
            Button item=button((selected?"✓  ":"")+modeName(key),()->{act("mode",key);dismissPopup();});item.setSelected(selected);if(selected)item.setTextColor(accent());add(choices,item,48);
        }showPopup(modeButton,choices,184);
    }
    private void showTiming(View anchor){
        if(playback.optString("currentTrack").isEmpty())return;
        LinearLayout content=column();hint(content,UiStrings.text("歌词微调 · 正值提前"));
        LinearLayout actions=row();
        weighted(actions,button("−0.5s",()->adjustTiming(-500)),48);
        TextView value=button("",()->{act("lyricOffset","0");updateTimingValue();});weighted(actions,value,48);
        weighted(actions,button("+0.5s",()->adjustTiming(500)),48);add(content,actions,48);
        showPopup(anchor,content,300);timingValue=value;timingValue.setContentDescription(UiStrings.text("歌词偏移，点击归零"));updateTimingValue();
    }
    private void adjustTiming(int delta){act("lyricOffset",Integer.toString(playback.optInt("lyricOffset")+delta));updateTimingValue();}
    private void updateTimingValue(){if(timingValue!=null)timingValue.setText(String.format(Locale.ROOT,"%+.1fs",playback.optInt("lyricOffset")/1000f));}
    private void renderLyrics(){
        if(lyricRows==null)return;
        cancelLyricFollow();
        String track=playback.optString("currentTrack");
        if(!track.equals(lyricTrack)){lyricTrack=track;manualLyrics=false;}
        lyricLines=track.equals(ui.optString("lyricTrack"))?array(ui,"lyricLines"):new JSONArray();
        lyricRows.removeAllViews();lyricTexts.clear();activeLyric=-1;
        lyricMessage.setText(UiStrings.text(ui.optString("lyricsMessage",UiStrings.text("播放在线音乐后查看歌词"))));
        if(lyricLines.length()==0){
            lyricScroll.scrollTo(0,0);
            String original=cleanLyrics(ui.optString("lyrics")),translation=cleanLyrics(ui.optString("translation"));
            String plain=lyricMode==1?translation:lyricMode==2?original+(translation.isEmpty()?"":"\n"+translation):original;
            TextView words=lyricText(track.equals(ui.optString("lyricTrack")) && !plain.trim().isEmpty() ? plain.trim() : UiStrings.text("暂无逐句歌词"));copyOnLongPress(words,()->lyricCopyText(playback,ui,lyricMode));add(lyricRows,words,-2);
        }else for(int i=0;i<lyricLines.length();i++){
            JSONObject line=lyricLines.optJSONObject(i);if(line==null)continue;
            String original=line.optString("original"),translation=line.optString("translation");
            String content=lyricMode==1?(translation.isEmpty()?UiStrings.text("暂无该句译文"):translation):(original.isEmpty()?"♪":original);
            if(lyricMode==2&&!translation.isEmpty())content+="\n"+translation;
            TextView words=lyricText(content);copyOnLongPress(words,()->lyricCopyText(playback,ui,lyricMode));
            // Reserve the highlighted size once. Both states share wrapping
            // and enough room, including long bilingual lines.
            words.setTextSize(lyricFontSize+2);words.setPadding(dp(8),0,dp(2),0);words.setPivotX(words.getPaddingLeft());
            float resting=lyricFontSize/(lyricFontSize+2f);words.setScaleX(resting);words.setScaleY(resting);
            lyricTexts.add(words);add(lyricRows,words,-2);
        }
        lyricRows.post(()->updateLyrics(true));
    }
    private TextView lyricText(String content){
        TextView words=text(content,lyricFontSize,true);words.setPadding(dp(8),0,0,0);words.setIncludeFontPadding(false);words.setMinLines(0);words.setLineSpacing(0f,lyricLineSpacing);
        if(Build.VERSION.SDK_INT>=28)words.setFallbackLineSpacing(true);
        words.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);words.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);return words;
    }
    private static float lineSpacingValue(Object saved){
        if(!(saved instanceof Number))return 1f;double value=((Number)saved).doubleValue();
        if(Double.isNaN(value)||Double.isInfinite(value)||value<.8||value>3)return 1f;return Math.round(value*10)/10f;
    }
    private static int lyricRowGap(int naturalHeight,float spacing){return Math.round(Math.max(1,naturalHeight)*(lineSpacingValue(spacing)-1f));}
    private void changeLyricLineSpacing(float value,boolean persist){
        float next=lineSpacingValue(value);if(persist)prefs.edit().putFloat("lyricLineSpacing",next).apply();if(next==lyricLineSpacing)return;lyricLineSpacing=next;
        if(lyricRows==null)return;
        final LinearLayout rows=lyricRows;final ScrollView viewport=lyricScroll;final boolean browsing=manualLyrics;final int scrollY=viewport.getScrollY(),gesture=lyricGestureRevision,revision=++lyricSpacingRevision;
        View visible=null;
        for(int i=0;i<rows.getChildCount();i++){View child=rows.getChildAt(i);visible=child;if(child.getBottom()>scrollY)break;}
        final View anchor=visible;final int offset=anchor==null?0:scrollY-anchor.getTop();
        for(int i=0;i<rows.getChildCount();i++)((TextView)rows.getChildAt(i)).setLineSpacing(0f,lyricLineSpacing);
        rows.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
            public void onGlobalLayout(){
                if(rows.getViewTreeObserver().isAlive())rows.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if(closed||rows!=lyricRows||viewport!=lyricScroll||revision!=lyricSpacingRevision)return;
                if(manualLyrics){if(browsing&&!lyricTouching&&gesture==lyricGestureRevision&&anchor!=null&&anchor.getParent()==rows)viewport.scrollTo(0,Math.max(0,anchor.getTop()+offset));}
                else updateLyrics(true);updateLyricArrow();
            }
        });rows.requestLayout();
    }
    /** Last-line leading is excluded by TextView; add proportional row advance separately. */
    private final class LyricColumn extends LinearLayout {
        // The padding centres edge lyrics; it is not a drawing viewport.
        // Scaled text can extend slightly into it after proportional row spacing.
        // Only the enclosing ScrollView should clip the lyric content.
        LyricColumn(){super(service);setOrientation(LinearLayout.VERTICAL);setClipChildren(false);setClipToPadding(false);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            super.onMeasure(widthSpec,heightSpec);boolean changed=false;
            for(int i=0;i<getChildCount();i++){
                TextView words=(TextView)getChildAt(i);LinearLayout.LayoutParams layout=(LinearLayout.LayoutParams)words.getLayoutParams();android.text.Layout text=words.getLayout();int gap=0;
                if(i+1<getChildCount()&&text!=null&&text.getLineCount()>0){int last=text.getLineCount()-1;gap=lyricRowGap(text.getLineBottom(last)-text.getLineTop(last),lyricLineSpacing);}
                if(layout.bottomMargin!=gap){layout.bottomMargin=gap;changed=true;}
            }
            if(changed)super.onMeasure(widthSpec,heightSpec);
        }
    }
    private void updateLyrics(boolean force){
        if(lyricScroll==null||!section.equals("lyrics"))return;
        if(!lyricTrack.equals(playback.optString("currentTrack"))){renderLyrics();return;}
        long time=(long)playback.optInt("position")+playback.optInt("lyricOffset");int low=0,high=lyricLines.length();
        while(low<high){int middle=(low+high)/2;if(lyricLines.optJSONObject(middle).optLong("time")<=time)low=middle+1;else high=middle;}
        int index=low-1;boolean changed=index!=activeLyric;
        if(changed){
            if(activeLyric>=0&&activeLyric<lyricTexts.size())styleLyric(lyricTexts.get(activeLyric),false);
            activeLyric=index;if(index>=0&&index<lyricTexts.size())styleLyric(lyricTexts.get(index),true);
        }
        if((changed||force)&&!manualLyrics){
            if(index>=0&&index<lyricTexts.size())scheduleLyricFollow();
            else{cancelLyricFollow();lyricScroll.scrollTo(0,0);}
        }
        updateLyricArrow();updateTimingValue();
    }
    private void cancelLyricFollow(){lyricFollowRevision++;if(lyricFollow!=null){ValueAnimator running=lyricFollow;lyricFollow=null;running.cancel();}if(lyricRows!=null)lyricRows.setTranslationY(0);}
    private void scheduleLyricFollow(){
        cancelLyricFollow();final int revision=lyricFollowRevision;
        final ScrollView viewport=lyricScroll;final LinearLayout rows=lyricRows;
        final ViewTreeObserver observer=rows.getViewTreeObserver();
        // Highlighting changes font metrics. Measure the target after layout,
        // before drawing, instead of following the previous sentence's bounds.
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
            public boolean onPreDraw(){
                if(observer.isAlive())observer.removeOnPreDrawListener(this);
                if(closed||revision!=lyricFollowRevision||lyricScroll!=viewport||lyricRows!=rows||manualLyrics||activeLyric<0||activeLyric>=lyricTexts.size())return true;
                TextView current=lyricTexts.get(activeLyric);int target=Math.max(0,current.getTop()+current.getHeight()/2-viewport.getHeight()/2);
                if(!animationsEnabled()||target==viewport.getScrollY()){viewport.scrollTo(0,target);updateLyricArrow();}
                else{
                    lyricFollow=ValueAnimator.ofFloat(viewport.getScrollY(),target);lyricFollow.setDuration(260);lyricFollow.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
                    lyricFollow.addUpdateListener(animation->{if(!closed&&revision==lyricFollowRevision&&lyricScroll==viewport&&!manualLyrics){
                        float y=(Float)animation.getAnimatedValue();viewport.scrollTo(0,(int)Math.floor(y));
                        // ScrollView stores integer pixels; retain the fractional
                        // remainder in the render transform instead of pausing
                        // for several frames at each pixel near the slow tail.
                        rows.setTranslationY(viewport.getScrollY()-y);updateLyricArrow();
                    }});
                    lyricFollow.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator animation){
                        if(lyricFollow==animation&&revision==lyricFollowRevision&&lyricScroll==viewport&&!manualLyrics){lyricFollow=null;viewport.scrollTo(0,target);rows.setTranslationY(0);}
                    }});lyricFollow.start();
                }
                return true;
            }
        });
        rows.postInvalidateOnAnimation();
    }
    private void styleLyric(TextView words,boolean active){
        // Keep wrapping and row metrics fixed, like the desktop delegates.
        // Highlight enlargement is a render transform, never a new text layout.
        words.setTextColor(active?accent():muted());words.getPaint().setFakeBoldText(active);words.invalidate();
        words.setPivotX(words.getPaddingLeft());float target=active?1f:lyricFontSize/(lyricFontSize+2f);
        words.animate().cancel();
        if(animationsEnabled())words.animate().scaleX(target).scaleY(target).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        else{words.setScaleX(target);words.setScaleY(target);}
    }
    private void updateLyricArrow(){
        if(lyricReturn==null)return;boolean visible=manualLyrics&&activeLyric>=0&&activeLyric<lyricTexts.size();lyricReturn.setVisibility(visible?View.VISIBLE:View.GONE);
        if(visible){TextView current=lyricTexts.get(activeLyric);lyricReturn.kind=current.getTop()+current.getHeight()/2<lyricScroll.getScrollY()+lyricScroll.getHeight()/2?"up":"down";lyricReturn.invalidate();}
    }
    private EditText field(String label,String value){
        EditText e=new EditText(service);e.setSingleLine(true);e.setTextColor(ink());e.setHintTextColor(muted());e.setTextSize(14);e.setHint(label);e.setContentDescription(label);e.setText(value);e.setPadding(dp(12),0,dp(12),0);e.setMinHeight(dp(48));
        GradientDrawable bg=background(color("#EEEDF8","#2A2D49"),10);
        GradientDrawable focused=background(color("#EEEDF8","#2A2D49"),10);focused.setStroke(dp(1),accent());
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},bg);e.setBackground(new InsetDrawable(states,0,dp(4),0,dp(4)));
        e.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_DOWN)enableKeyboard(e);return false;});
        return e;
    }
    private void buildSearch(){
        detailHeader(searchKind.equals("songs")?UiStrings.text("搜索音乐"):UiStrings.text("搜索歌单"));LinearLayout kinds=row();
        for(String kind:new String[]{"songs","playlists"}){Button choice=button(kind.equals("songs")?UiStrings.text("歌曲"):UiStrings.text("歌单"),()->{rememberDrafts();hideKeyboard();searchKind=kind;buildDrawer();});boolean selected=searchKind.equals(kind);choice.setSelected(selected);choice.setBackground(buttonBackground(selected));choice.setTextColor(selected?accent():ink());weighted(kinds,choice,48);}add(drawer,kinds,48);gap(drawer,8);
        {
            // Keep the existing palette and 36dp surfaces; all three native checkboxes
            // share one 48dp touch row inside detailScroll, above the search field.
            LinearLayout sources=row();String[] labels={UiStrings.text("网易云"),UiStrings.text("QQ音乐"),UiStrings.text("酷我音乐")};
            for(int i=0;i<SEARCH_SOURCE_IDS.length;i++){
                final String source=SEARCH_SOURCE_IDS[i];CheckBox choice=new SourceCheckBox();
                choice.setText(labels[i]);choice.setTextSize(12);choice.setTextColor(ink());choice.setSingleLine(true);choice.setEllipsize(TextUtils.TruncateAt.END);
                choice.setMinHeight(dp(48));choice.setMinimumHeight(dp(48));choice.setMinWidth(0);choice.setMinimumWidth(0);choice.setPadding(dp(4),0,dp(4),0);
                choice.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent(),muted()}));
                choice.setContentDescription(labels[i]+UiStrings.text("曲库"));choice.setBackground(buttonBackground(false));
                choice.setOnCheckedChangeListener((v,checked)->{if(!syncingSourceChoices)toggleSearchSource(source,checked);});
                searchSourceButtons.add(choice);weighted(sources,choice,48);
            }add(drawer,sources,48);gap(drawer,8);
        }
        searchInput=field(searchKind.equals("songs")?UiStrings.text("输入歌名或歌手"):UiStrings.text("输入歌单关键词"),searchDraft);searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);add(drawer,searchInput,48);gap(drawer,8);
        Runnable submit=()->{searchDraft=searchInput.getText().toString().trim();if(selectedSearchSources().length()==0){notice(UiStrings.text("勾选曲库后即可搜索。"));return;}hideKeyboard();if(searchDraft.isEmpty()){notice(UiStrings.text("请先输入搜索内容。"));return;}if(searchKind.equals("playlists"))library("searchPlaylists",args("query",searchDraft));else send("search",searchDraft);};
        searchInput.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEARCH||(e!=null&&e.getKeyCode()==KeyEvent.KEYCODE_ENTER&&e.getAction()==KeyEvent.ACTION_DOWN)){submit.run();return true;}return false;});searchButton=button(UiStrings.text("搜索"),submit);add(drawer,searchButton,48);updateSearchControls();
        dynamic=column();add(drawer,dynamic,-2);
    }
    private JSONArray selectedSearchSources(){return pendingSearchSources==null?searchSourceIds(ui):pendingSearchSources;}
    private void toggleSearchSource(String source,boolean checked){
        JSONArray current=selectedSearchSources(),next=new JSONArray();
        for(String candidate:SEARCH_SOURCE_IDS){boolean selected=candidate.equals(source)?checked:false;if(!candidate.equals(source))for(int i=0;i<current.length();i++)if(candidate.equals(current.optString(i))){selected=true;break;}if(selected)next.put(candidate);}
        pendingSearchSources=next;resultsPage=0;playlistResultsPage=0;updateSearchControls();send("searchSources",next.toString());refreshDynamic();
    }
    private void updateSearchControls(){
        if(searchButton==null)return;JSONArray sources=selectedSearchSources();syncingSourceChoices=true;
        for(int i=0;i<searchSourceButtons.size();i++){
            CheckBox choice=searchSourceButtons.get(i);boolean checked=false;for(int j=0;j<sources.length();j++)if(SEARCH_SOURCE_IDS[i].equals(sources.optString(j))){checked=true;break;}
            if(choice.isChecked()!=checked)choice.setChecked(checked);
            if(choice.isSelected()!=checked){choice.setSelected(checked);choice.setBackground(buttonBackground(checked));choice.setTextColor(checked?accent():ink());}
        }syncingSourceChoices=false;
        boolean loading=ui.optBoolean(searchKind.equals("songs")?"searching":"playlistSearching"),enabled=!loading&&sources.length()>0;
        textIfChanged(searchButton,loading?UiStrings.text("搜索中"):UiStrings.text("搜索"));searchButton.setEnabled(enabled);searchButton.setAlpha(enabled?1f:.45f);
    }
    private void buildPlaylist(){
        playlistPicker=button("",this::showPlaylistPicker);playlistPicker.setMaxLines(2);playlistPicker.setEllipsize(TextUtils.TruncateAt.END);playlistPicker.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);playlistPicker.setTextSize(15);add(drawer,playlistPicker,48);
        playlistDescriptionBlock=new DescriptionBlock(2);playlistDescriptionBlock.setTag("localDescription");playlistDescription=playlistDescriptionBlock.words;add(drawer,playlistDescriptionBlock,-2);updatePlaylistHeader();
        LinearLayout actions=row();weighted(actions,button(UiStrings.text("管理歌单"),this::showPlaylistMenu),48);weighted(actions,button(selecting?UiStrings.text("完成多选"):UiStrings.text("多选歌曲"),()->{selecting=!selecting;selectedIds.clear();selectionPlaylist=activePlaylist();buildDrawer();}),48);add(drawer,actions,48);
        if(selecting){gap(drawer,8);LinearLayout selection=row();weighted(selection,button(UiStrings.text("全选 / 清空"),()->{JSONArray tracks=array(ui,"tracks");if(selectedIds.size()==tracks.length())selectedIds.clear();else for(int i=0;i<tracks.length();i++){JSONObject track=tracks.optJSONObject(i);if(track!=null)selectedIds.add(track.optString("id"));}rendered="";refreshDynamic();}),48);selectionCount=button("",this::showSelectionMenu);weighted(selection,selectionCount,48);add(drawer,selection,48);updateSelectionCount();}
        else hint(drawer,UiStrings.text("点歌名播放 · 长按右侧手柄拖动排序"));
        dynamic=column();add(drawer,dynamic,-2);
    }
    private String playlistLabel(JSONObject list){return "favorites".equals(list.optString("id"))?UiStrings.text("收藏夹"):list.optString("name",UiStrings.text("当前歌单"));}
    private void updatePlaylistHeader(){if(playlistPicker==null||!section.equals("playlist"))return;JSONObject list=currentList();playlistPicker.setText(playlistLabel(list)+" · "+list.optInt("trackCount",array(ui,"tracks").length())+UiStrings.text(" 首  ▾"));playlistPicker.setContentDescription(UiStrings.text("切换歌单，")+playlistPicker.getText());playlistDescriptionBlock.setContent(list.optString("description"),activePlaylist().equals("favorites")?UiStrings.text("收藏保存在本机；收藏歌单不能删除。"):UiStrings.text("暂无简介 · 在管理歌单中编辑"),activePlaylist());}
    private void clearSelection(){selecting=false;selectedIds.clear();selectionPlaylist="";}
    private void updateSelectionCount(){if(selectionCount!=null){selectionCount.setText(UiStrings.text("已选 ")+selectedIds.size()+UiStrings.text(" · 操作"));selectionCount.setEnabled(!selectedIds.isEmpty());selectionCount.setAlpha(selectedIds.isEmpty()?.5f:1f);}}
    private void showPlaylistPicker(){LinearLayout menu=column();JSONArray lists=array(ui,"playlists");for(int i=0;i<lists.length();i++){JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id");menuItem(menu,(id.equals(activePlaylist())?"✓ ":"")+playlistLabel(list)+" · "+list.optInt("trackCount")+UiStrings.text(" 首"),()->{dismissPopup();clearSelection();send("selectPlaylist",id);});}menuItem(menu,UiStrings.text("新建歌单"),()->showPlaylistEditor(true));showPanel(UiStrings.text("切换歌单"),menu);}
    private void showPlaylistMenu(){
        String source=activePlaylist();LinearLayout menu=column();menuItem(menu,UiStrings.text("编辑名称与简介"),()->showPlaylistEditor(false));menuItem(menu,UiStrings.text("新建歌单"),()->showPlaylistEditor(true));
        menuItem(menu,UiStrings.text("复制整个歌单"),()->{dismissPopup();if(currentSource(source))library("duplicate",args());});
        menuItem(menu,UiStrings.text("导入歌曲或歌单"),this::showImportMenu);menuItem(menu,UiStrings.text("导出整个歌单"),()->showExportMenu(source,new JSONArray()));
        if(!source.equals("favorites"))menuItem(menu,UiStrings.text("删除歌单"),()->confirm(UiStrings.text("删除歌单"), UiStrings.text("删除“")+currentList().optString("name")+UiStrings.text("”及其中的歌曲记录？本地音频文件会保留。"), UiStrings.text("确认删除歌单"),()->{if(currentSource(source)){clearSelection();send("deletePlaylist","");}}));
        showPanel(UiStrings.text("管理歌单"),menu);
    }
    private void showPlaylistEditor(boolean create){
        String source=activePlaylist();JSONObject list=currentList();boolean protectedList=!create&&source.equals("favorites");LinearLayout content=column();hint(content,UiStrings.text("歌单名称"));
        EditText name=field(UiStrings.text("歌单名称"),create?"":playlistLabel(list));name.setEnabled(!protectedList);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});add(content,name,48);
        if(protectedList)hint(content,UiStrings.text("收藏歌单使用固定名称，可以修改简介。"));
        hint(content,UiStrings.text("歌单简介"));EditText description=field(UiStrings.text("写下这份歌单的心情或用途"),create?"":list.optString("description"));description.setSingleLine(false);description.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});description.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);description.setGravity(Gravity.TOP);description.setPadding(dp(12),dp(12),dp(12),dp(12));add(content,description,104);gap(content,8);
        add(content,button(create?UiStrings.text("创建歌单"):UiStrings.text("保存修改"),()->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError(UiStrings.text("请输入歌单名称"));name.requestFocus();return;}String note=description.getText().toString().trim();if(!create&&!currentSource(source))return;hideKeyboard();dismissPopup();if(create){library("importText",args("text",args("name",value,"description",note,"tracks",new JSONArray()).toString(),"target","new"));}else{if(!protectedList&&!value.equals(list.optString("name")))send("renamePlaylist",value);library("describe",args("description",note));}}),48);
        showPanel(create?UiStrings.text("新建歌单"):UiStrings.text("编辑歌单"),content);
    }
    private interface TargetAction {void choose(String id);}
    private void showTargets(String title,boolean includeNew,String excluded,TargetAction action){
        LinearLayout choices=column();String capturedCurrent=activePlaylist();JSONArray lists=array(ui,"playlists");
        for(int pass=0;pass<2;pass++)for(int i=0;i<lists.length();i++){JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id");if(id.equals(excluded)||(pass==0)!=id.equals(capturedCurrent))continue;menuItem(choices,playlistLabel(list)+(id.equals(capturedCurrent)?UiStrings.text(" · 当前"):"")+" · "+list.optInt("trackCount")+UiStrings.text(" 首"),()->{dismissPopup();action.choose(id);});}
        if(includeNew)menuItem(choices,UiStrings.text("新建歌单"),()->{dismissPopup();action.choose("new");});
        if(choices.getChildCount()==0)hint(choices,UiStrings.text("请先创建另一份歌单，再复制或移动歌曲。"));showPanel(title,choices);
    }
    private void showImportMenu(){
        LinearLayout menu=column();menuItem(menu,UiStrings.text("剪贴板：歌单 JSON / 链接 / ID"),()->showTargets(UiStrings.text("剪贴板导入到"),true,"",target->{library("paste",args("target",target));}));
        menuItem(menu,UiStrings.text("JSON 文件"),()->showTargets(UiStrings.text("JSON 文件导入到"),true,"",target->{library("chooseImport",args("target",target));}));
        menuItem(menu,UiStrings.text("本地音频文件 → 当前歌单"),()->{dismissPopup();service.openPicker();});hint(menu,UiStrings.text("支持网易云、QQ、酷我完整歌单链接；数字 ID 默认按网易云读取。"));hint(menu,UiStrings.text("导入目标会在选择时固定；重复歌曲自动跳过。"));showPanel(UiStrings.text("导入"),menu);
    }
    private void showExportMenu(String source,JSONArray ids){LinearLayout menu=column();hint(menu,ids.length()==0?UiStrings.text("导出整份歌单的 JSON。"):UiStrings.text("导出所选 ")+ids.length()+UiStrings.text(" 首歌曲，保留歌单顺序。"));menuItem(menu,UiStrings.text("复制 JSON 到剪贴板"),()->{dismissPopup();if(currentSource(source))library("copyExport",args("ids",ids));});menuItem(menu,UiStrings.text("保存为 JSON 文件"),()->{dismissPopup();if(currentSource(source))library("chooseExport",args("ids",ids));});showPanel(UiStrings.text("导出歌单"),menu);}
    private void showSelectionMenu(){JSONArray ids=selectedTracks();if(ids.length()==0)return;String source=activePlaylist();LinearLayout menu=column();menuItem(menu,UiStrings.text("复制到其他歌单"),()->transferTo(source,ids,false));menuItem(menu,UiStrings.text("移动到其他歌单"),()->transferTo(source,ids,true));menuItem(menu,UiStrings.text("导出所选歌曲"),()->showExportMenu(source,ids));menuItem(menu,UiStrings.text("从歌单移除"),()->removeTracks(source,ids));showPanel(UiStrings.text("已选 ")+ids.length()+UiStrings.text(" 首"),menu);}
    private void transferTo(String source,JSONArray ids,boolean move){showTargets(move?UiStrings.text("移动到"):UiStrings.text("复制到"),false,source,target->{if(currentSource(source))library("transfer",args("ids",ids,"target",target,"move",move));});}
    private void removeTracks(String source,JSONArray ids){confirm(UiStrings.text("移除歌曲"),UiStrings.text("从当前歌单移除 ")+ids.length()+UiStrings.text(" 首歌曲？本地音频文件会保留。"),UiStrings.text("确认移除"),()->{if(currentSource(source))library("removeTracks",args("ids",ids));});}
    private void showTrackMenu(JSONObject track,int index){
        String id=track.optString("id"),source=activePlaylist();JSONArray ids=new JSONArray().put(id);LinearLayout menu=column();
        menuItem(menu,UiStrings.text("下一首播放"),()->enqueue(track,"next"));menuItem(menu,UiStrings.text("加入队尾"),()->enqueue(track,"tail"));
        menuItem(menu,UiStrings.text("选择这首歌曲"),()->{if(!currentSource(source))return;dismissPopup();selecting=true;selectionPlaylist=source;selectedIds.add(id);buildDrawer();});
        menuItem(menu,UiStrings.text("复制到其他歌单"),()->transferTo(source,ids,false));menuItem(menu,UiStrings.text("移动到其他歌单"),()->transferTo(source,ids,true));menuItem(menu,UiStrings.text("调整歌曲顺序"),()->showReorderMenu(source,index));menuItem(menu,UiStrings.text("从歌单移除"),()->removeTracks(source,ids));showPanel(track.optString("name"),menu);
    }
    private void showReorderMenu(String source,int from){
        String snapshot=array(ui,"tracks").toString();int count=array(ui,"tracks").length();LinearLayout menu=column();
        int[] destinations={from-1,from+1,0,count-1};String[] labels={UiStrings.text("上移一首"),UiStrings.text("下移一首"),UiStrings.text("移到最前"),UiStrings.text("移到最后")};
        for(int i=0;i<destinations.length;i++){final int to=destinations[i];if(to<0||to>=count||to==from)continue;menuItem(menu,labels[i],()->{dismissPopup();if(currentSource(source)&&snapshot.equals(array(ui,"tracks").toString()))library("moveTrack",args("from",from,"to",to));else notice(UiStrings.text("歌单已更新，请重新选择排序位置。"));});}showPanel(UiStrings.text("调整顺序"),menu);
    }
    private void addTrackTo(JSONObject track){String payload=args("name",track.optString("name",UiStrings.text("新歌单")),"tracks",new JSONArray().put(track)).toString();showTargets(UiStrings.text("加入歌单"),true,"",target->library("importText",args("text",payload,"target",target)));}
    private void showExternalTrackMenu(JSONObject track){LinearLayout menu=column();menuItem(menu,UiStrings.text("下一首播放"),()->enqueue(track,"next"));menuItem(menu,UiStrings.text("加入队尾"),()->enqueue(track,"tail"));menuItem(menu,UiStrings.text("加入歌单"),()->addTrackTo(track));showPanel(track.optString("name"),menu);}
    private void buildOnline(){LinearLayout head=row();boolean ranking=onlineSource.equals("rankings");TextView heading=text(ranking?UiStrings.text("榜单歌曲"):UiStrings.text("在线歌单"),18,false);heading.setTypeface(null,Typeface.BOLD);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));head.addView(button(ranking?UiStrings.text("返回榜单"):UiStrings.text("返回搜索"),()->selectDetail(onlineSource)),new LinearLayout.LayoutParams(dp(96),dp(48)));add(drawer,head,48);dynamic=column();add(drawer,dynamic,-2);}
    private void playlistResults(LinearLayout parent){
        if(pendingSearchSources!=null)return;
        JSONArray lists=array(ui,"playlistResults");if(lists.length()==0){hint(parent,ui.optBoolean("playlistSearching")?UiStrings.text("正在搜索歌单…"):UiStrings.text("暂无歌单结果，换个关键词试试。"));return;}
        playlistResultsPage=Math.min(playlistResultsPage,(lists.length()-1)/PLAYLIST_PAGE_SIZE);int start=playlistResultsPage*PLAYLIST_PAGE_SIZE;
        for(int i=start;i<Math.min(lists.length(),start+PLAYLIST_PAGE_SIZE);i++){JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id");LinearLayout item=column();item.setPadding(0,dp(12),0,dp(12));TextView name=text(list.optString("name"),16,false);name.setTypeface(null,Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);add(item,name,-2);hint(item,onlineMetadata(list));String summary=list.optString("description");if(!summary.isEmpty()){TextView note=text(summary,13,true);note.setMaxLines(2);note.setEllipsize(TextUtils.TruncateAt.END);copyOnLongPress(note,()->summary);add(item,note,-2);gap(item,8);}add(item,button(UiStrings.text("查看歌单"),()->openOnline(id,"search")),48);add(parent,item,-2);divider(parent);}
        pageControls(parent,"playlistResults",playlistResultsPage,lists.length(),PLAYLIST_PAGE_SIZE);
    }
    private void onlineRows(LinearLayout parent){
        JSONObject online=ui.optJSONObject("onlinePlaylist");boolean matching=online!=null&&online.optString("id").equals(requestedOnlineId);
        if(ui.optBoolean("onlinePlaylistLoading")||!matching){hint(parent,ui.optBoolean("onlinePlaylistLoading")?UiStrings.text("正在加载歌单…"):UiStrings.text("歌单未能加载，请重试或返回列表。"));if(!ui.optBoolean("onlinePlaylistLoading")&&!requestedOnlineId.isEmpty())add(parent,button(UiStrings.text("重试加载"),()->library("openOnline",args("id",requestedOnlineId))),48);return;}
        TextView name=text(online.optString("name"),18,false);name.setTypeface(null,Typeface.BOLD);add(parent,name,-2);hint(parent,onlineMetadata(online)+UiStrings.text(" · 已加载 ")+array(online,"tracks").length()+UiStrings.text(" 首"));
        String summary=online.optString("description");if(!summary.isEmpty()){DescriptionBlock note=new DescriptionBlock(3);note.setTag("onlineDescription");note.setContent(summary,"",online.optString("id"));add(parent,note,-2);}String warning=UiStrings.text(online.optString("warning"));if(!warning.isEmpty()){TextView note=text(warning,13,false);note.setTextColor(color("#B42318","#FF9B93"));add(parent,note,-2);gap(parent,8);}
        if(array(online,"tracks").length()>0){String onlineId=online.optString("id");LinearLayout actions=row();IconButton first=new IconButton("play",UiStrings.text("播放第一首"),true);first.setOnClickListener(v->{if(onlineCurrent(onlineId))library("playOnline",args("index",0));});control(actions,first);TextView label=text(UiStrings.text("播放榜单"),13,false);label.setText(onlineSource.equals("rankings")?UiStrings.text("播放榜单"):UiStrings.text("播放歌单"));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(8);actions.addView(label,lp);actions.addView(button(UiStrings.text("整单加入"),()->showTargets(UiStrings.text("歌单加入到"),true,"",target->{if(onlineCurrent(onlineId))library("addOnline",args("target",target));})),new LinearLayout.LayoutParams(dp(88),dp(48)));add(parent,actions,48);}
        songRows(parent,array(online,"tracks"),"online");
    }
    private boolean onlineCurrent(String id){JSONObject online=ui.optJSONObject("onlinePlaylist");if(online!=null&&!ui.optBoolean("onlinePlaylistLoading")&&id.equals(online.optString("id")))return true;notice(UiStrings.text("在线歌单已更新，请重新打开详情。"));return false;}
    private void divider(LinearLayout parent){View line=new View(service);line.setBackgroundColor(color("#DFDFEB","#343950"));add(parent,line,1);}
    private void songRows(LinearLayout parent,JSONArray tracks,String source){
        if(tracks.length()==0){hint(parent,source.equals("results")?(selectedSearchSources().length()==0?UiStrings.text("勾选曲库后即可搜索歌曲。"):UiStrings.text("输入歌名，查找想听的音乐。")) :UiStrings.text("这里还没有歌曲。可在更多中搜索或导入。"));return;}
        LinearLayout songs=column();add(parent,songs,-2);boolean local=source.equals("tracks");String sourceId=activePlaylist();JSONObject online=ui.optJSONObject("onlinePlaylist");String onlineId=online==null?"":online.optString("id");
        int page=Math.min(source.equals("tracks")?tracksPage:source.equals("results")?resultsPage:onlinePage,(tracks.length()-1)/SONG_PAGE_SIZE);setPage(source,page);int start=page*SONG_PAGE_SIZE;
        for(int i=start;i<Math.min(tracks.length(),start+SONG_PAGE_SIZE);i++){
            final int index=i;JSONObject track=tracks.optJSONObject(i);if(track==null)continue;String id=track.optString("id"),name=track.optString("name"),by=trackSubtitle(track);
            LinearLayout song=row();song.setTag(index);
            if(local&&selecting){CheckBox choice=new CheckBox(service);choice.setText(songCaption(name,by));choice.setTextColor(ink());choice.setTextSize(14);choice.setMaxLines(2);choice.setEllipsize(TextUtils.TruncateAt.END);choice.setButtonTintList(ColorStateList.valueOf(accent()));choice.setChecked(selectedIds.contains(id));choice.setContentDescription(name+"，"+by);choice.setPadding(0,0,dp(8),0);choice.setMinHeight(dp(48));choice.setOnCheckedChangeListener((v,checked)->{if(checked)selectedIds.add(id);else selectedIds.remove(id);updateSelectionCount();});choice.setOnLongClickListener(v->{showSelectionMenu();return true;});song.addView(choice,new LinearLayout.LayoutParams(-1,dp(48)));}
            else{
                boolean ranking=source.equals("online")&&onlineSource.equals("rankings");int position=track.optInt("playlistPosition",index+1);if(ranking){TextView rank=text(Integer.toString(position),14,true);rank.setGravity(Gravity.CENTER);rank.setTypeface(null,position<=3?Typeface.BOLD:Typeface.NORMAL);if(position<=3)rank.setTextColor(accent());rank.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);int rankWidth=Math.max(dp(32),(int)Math.ceil(rank.getPaint().measureText(rank.getText().toString()))+dp(8));song.addView(rank,new LinearLayout.LayoutParams(rankWidth,dp(48)));}
                Button songName=button(name,()->{if(local){if(currentSource(sourceId))send("playTrack",id);}else if(source.equals("online")){if(onlineCurrent(onlineId))library("playOnline",args("index",index));}else send("playResult",id);});songName.setText(songCaption(name,by));songName.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);songName.setTextSize(14);songName.setMaxLines(2);songName.setEllipsize(TextUtils.TruncateAt.END);songName.setContentDescription(UiStrings.text("播放，")+(ranking?UiStrings.text("第 ")+position+UiStrings.text(" 名，"):"")+name+"，"+by);songName.setBackground(new RippleDrawable(ColorStateList.valueOf(selection()),null,null));song.addView(songName,new LinearLayout.LayoutParams(0,dp(48),1));
                if(local){IconButton handle=new IconButton("grip",UiStrings.text("排序 ")+name+UiStrings.text("，长按拖动，点击选择上移或下移"),false);handle.motionEnabled=false;handle.setOnClickListener(v->showReorderMenu(sourceId,index));handle.setOnLongClickListener(v->{if(selecting||!currentSource(sourceId))return true;resetPress(handle);dragReorder=new DragReorder(songs,song,sourceId,index,tracks.toString());boolean started=handle.startDragAndDrop(ClipData.newPlainText("song",id),new View.DragShadowBuilder(song),dragReorder,0);if(started){song.setAlpha(.45f);handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}else stopReorder();return true;});LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(48),dp(48));hp.leftMargin=dp(8);song.addView(handle,hp);}
                IconButton more=new IconButton("more",UiStrings.text("歌曲操作，")+name,false);more.setOnClickListener(v->{if(local){if(currentSource(sourceId))showTrackMenu(track,index);}else showExternalTrackMenu(track);});LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(48),dp(48));mp.leftMargin=dp(8);song.addView(more,mp);
            }
            add(songs,song,-2);divider(songs);
        }
        pageControls(parent,source,page,tracks.length(),SONG_PAGE_SIZE);
        if(local)detailScroll.setOnDragListener((v,event)->handleSongDrag(event));
    }
    private CharSequence songCaption(String name,String artist){
        if(artist.isEmpty())return name;SpannableString caption=new SpannableString(name+"\n"+artist);int start=name.length()+1;
        caption.setSpan(new ForegroundColorSpan(muted()),start,caption.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);caption.setSpan(new RelativeSizeSpan(13f/14f),start,caption.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return caption;
    }
    private void setPage(String source,int page){if(source.equals("tracks"))tracksPage=page;else if(source.equals("results"))resultsPage=page;else if(source.equals("playlistResults"))playlistResultsPage=page;else if(source.equals("queue"))queuePage=page;else if(source.equals("history"))historyPage=page;else onlinePage=page;}
    private void pageControls(LinearLayout parent,String source,int page,int count,int pageSize){
        if(count<=pageSize)return;hint(parent,UiStrings.text("第 ")+(page*pageSize+1)+"–"+Math.min(count,(page+1)*pageSize)+UiStrings.text(" 项，共 ")+count+UiStrings.text(" 项"));LinearLayout pages=row();
        Button previous=button(UiStrings.text("上一页"),()->changePage(source,page-1));previous.setEnabled(page>0);previous.setAlpha(page>0?1f:.45f);weighted(pages,previous,48);
        boolean more=(page+1)*pageSize<count;Button next=button(UiStrings.text("下一页"),()->changePage(source,page+1));next.setEnabled(more);next.setAlpha(more?1f:.45f);weighted(pages,next,48);add(parent,pages,48);gap(parent,8);
    }
    private void changePage(String source,int page){setPage(source,page);rendered="";detailScroll.scrollTo(0,0);refreshDynamic();}
    private boolean handleSongDrag(DragEvent event){
        if(!(event.getLocalState() instanceof DragReorder))return false;DragReorder drag=(DragReorder)event.getLocalState();if(drag!=dragReorder)return false;
        switch(event.getAction()){
            case DragEvent.ACTION_DRAG_STARTED:return true;
            case DragEvent.ACTION_DRAG_ENTERED:detailScroll.requestDisallowInterceptTouchEvent(true);return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                int[] origin=new int[2];detailScroll.getLocationOnScreen(origin);drag.rawY=origin[1]+event.getY()*scale;drag.locate();detailScroll.removeCallbacks(drag);detailScroll.postDelayed(drag,32);return true;
            case DragEvent.ACTION_DRAG_EXITED:detailScroll.removeCallbacks(drag);return true;
            case DragEvent.ACTION_DROP:
                drag.locate();commitReorder(drag);stopReorder();rendered="";refreshDynamic();return true;
            case DragEvent.ACTION_DRAG_ENDED:stopReorder();rendered="";refreshDynamic();return true;
            default:return true;
        }
    }
    private void commitReorder(DragReorder drag){
        if(drag.source.equals("queue")){
            JSONObject change=queueMovePayload(array(playback,"queueEntries"),drag.entryId,drag.target,drag.snapshot);
            if(change==null)notice(UiStrings.text("队列已更新，本次拖动未保存，请重新排序。"));else if(drag.from!=drag.target)act("moveQueueEntry",change.toString());
        }else if(drag.source.equals(activePlaylist())&&drag.snapshot.equals(array(ui,"tracks").toString())){if(drag.from!=drag.target)library("moveTrack",args("from",drag.from,"to",drag.target));}
        else notice(UiStrings.text("歌单已更新，本次拖动未保存，请重新排序。"));
    }
    private void stopReorder(){if(dragReorder!=null){DragReorder old=dragReorder;dragReorder=null;if(detailScroll!=null){detailScroll.removeCallbacks(old);detailScroll.requestDisallowInterceptTouchEvent(false);}old.origin.setAlpha(1f);if(old.highlight!=null)old.highlight.setBackground(null);}}
    /** Only the final drop writes to the store; index validity is checked against the original list. */
    private final class DragReorder implements Runnable {
        final LinearLayout songs;final View origin;final String source,snapshot,entryId;final int from;int target;float rawY;View highlight;
        DragReorder(LinearLayout songs,View origin,String source,int from,String snapshot){this.songs=songs;this.origin=origin;this.source=source;this.from=from;this.target=from;this.snapshot=snapshot;JSONObject entry=source.equals("queue")?array(playback,"queueEntries").optJSONObject(from):null;this.entryId=entry==null?"":entry.optString("queueId");}
        void locate(){float nearest=Float.MAX_VALUE;View next=null;for(int i=0;i<songs.getChildCount();i++){View song=songs.getChildAt(i);if(!(song.getTag() instanceof Integer))continue;int[] at=new int[2];song.getLocationOnScreen(at);float distance=Math.abs(rawY-(at[1]+song.getHeight()*scale/2));if(distance<nearest){nearest=distance;target=(Integer)song.getTag();next=song;}}if(next!=highlight){if(highlight!=null)highlight.setBackground(null);highlight=next;if(highlight!=null)highlight.setBackground(background(selection(),12));}}
        public void run(){if(dragReorder!=this||detailScroll==null||!detailScroll.isAttachedToWindow())return;int[] at=new int[2];detailScroll.getLocationOnScreen(at);float top=at[1],bottom=top+detailScroll.getHeight()*scale;float edge=Math.min(dp(48),(bottom-top)/3);int direction=rawY<top+edge?-1:rawY>bottom-edge?1:0;if(direction!=0){if(detailScroll.canScrollVertically(direction))detailScroll.scrollBy(0,direction*dp(12));else if(scroll.canScrollVertically(direction))scroll.scrollBy(0,direction*dp(12));locate();}detailScroll.postDelayed(this,32);}
    }
    private void buildSettings(){
        if(settingsPage.isEmpty()){
            detailHeader(UiStrings.text("设置"));
            settingsEntry("general","通用与外观","语言、主题、窗口与动效","settings");
            settingsEntry("playback","播放与音频","各曲库音质与输出设备","volume");
            settingsEntry("lyrics","播放器歌词","显示方式、字号与行距","lyrics");
            settingsEntry("floating","独立悬浮歌词","行数、主题、可见度与位置","lyrics");
            settingsEntry("service","搜索与服务","结果数量与自定义服务","search");
            settingsEntry("about","关于浮音","版本与操作提示","info");return;
        }
        String title=settingsPage.equals("general")?"通用与外观":settingsPage.equals("playback")?"播放与音频":settingsPage.equals("lyrics")?"播放器歌词":settingsPage.equals("floating")?"独立悬浮歌词":settingsPage.equals("service")?"搜索与服务":"关于浮音";
        detailHeader(UiStrings.text(title),UiStrings.text("返回设置"),()->openSettingsPage(""));
        if(settingsPage.equals("general")){
        add(drawer,button(UiStrings.text("界面语言：")+(UiStrings.english()?"English":"简体中文"),()->{rememberDrafts();PlayerBridge.setLanguage(UiStrings.english()?"zh_CN":"en");}),48);
        }
        if(settingsPage.equals("floating")){
        Switch floating=new Switch(service);floating.setText(UiStrings.text("显示独立悬浮歌词"));floating.setTextColor(ink());floating.setTextSize(13);floating.setMinimumHeight(dp(48));floating.setChecked(prefs.getBoolean("desktopLyricsEnabled",false));floating.setOnCheckedChangeListener((view,checked)->{prefs.edit().putBoolean("desktopLyricsEnabled",checked).apply();service.refreshFloatingLyrics();});add(drawer,floating,48);
        int rows=FloatingLyricRules.rows(prefs.getInt("desktopLyricsRows",3));
        add(drawer,button(UiStrings.text("悬浮歌词行数：")+rows+UiStrings.text(" · 点击切换"),()->{prefs.edit().putInt("desktopLyricsRows",rows==9?1:rows+2).apply();buildDrawer();}),48);
        int lyricTheme=Math.max(0,Math.min(2,prefs.getInt("desktopLyricsTheme",0)));String[] lyricThemes={UiStrings.text("跟随播放器"),UiStrings.text("浅色"),UiStrings.text("深色")};
        add(drawer,button(UiStrings.text("悬浮歌词主题：")+lyricThemes[lyricTheme]+UiStrings.text(" · 点击切换"),()->{prefs.edit().putInt("desktopLyricsTheme",(lyricTheme+1)%3).apply();buildDrawer();}),48);
        int floatingFont=Math.max(18,Math.min(48,prefs.getInt("desktopLyricsFont",30)));TextView floatingFontLabel=text(UiStrings.text("悬浮歌词字号：")+floatingFont,13,true);add(drawer,floatingFontLabel,-2);
        SeekBar floatingSize=slider();floatingSize.setMax(30);floatingSize.setProgress(floatingFont-18);floatingSize.setContentDescription(UiStrings.text("悬浮歌词字号，18 至 48"));add(drawer,floatingSize,48);
        floatingSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){prefs.edit().putInt("desktopLyricsFont",value+18).apply();floatingFontLabel.setText(UiStrings.text("悬浮歌词字号：")+(value+18));}}});
        floatingOpacityControls(rows);
        Switch lock=new Switch(service);lock.setText(UiStrings.text("锁定歌词位置"));lock.setTextColor(ink());lock.setTextSize(13);lock.setMinimumHeight(dp(48));lock.setChecked(prefs.getBoolean("desktopLyricsLocked",false));lock.setOnCheckedChangeListener((view,checked)->prefs.edit().putBoolean("desktopLyricsLocked",checked).apply());add(drawer,lock,48);
        add(drawer,button(UiStrings.text("居中歌词"),()->prefs.edit().putFloat("desktopLyricsX",.5f).putFloat("desktopLyricsY",.5f).apply()),48);
        hint(drawer,UiStrings.text("仅歌词文字可拖动，留白点击穿透；长按歌词打开设置。支持 1、3、5、7、9 行，屏幕空间不足时自动缩小。") );
        }
        if(settingsPage.equals("playback")){
        settingsHeading(UiStrings.text("播放音质"));
        for(String source:SEARCH_SOURCE_IDS){Button choice=button("",()->showSourceQualityChoices(source));sourceQualityButtons.add(choice);settingChoice(sourceLabel(source),choice);}
        qualityButton=sourceQualityButtons.get(0);
        qualitySources=hint(drawer,UiStrings.text("各曲库单独保存；切换当前歌曲音质会保留进度。高档不可用时自动尝试较低档。"));
        qualityHint=hint(drawer,UiStrings.text("实验母带文件较大，可能增加加载时间；采样规格不代表原始母带品质。"));
        sourceInfo=hint(drawer,"");updateQualityControls();
        outputButton=button("",this::showOutputChoices);settingChoice(UiStrings.text("输出设备"),outputButton);updateOutputControl();
        }
        if(settingsPage.equals("lyrics")){
        LinearLayout languages=row();String[] lyricLabels={UiStrings.text("原文"),UiStrings.text("译文"),UiStrings.text("双语")};
        ArrayList<Button> languageButtons=new ArrayList<>();
        for(int i=0;i<lyricLabels.length;i++){
            final int choice=i;
            Button option=button(lyricLabels[i],()->{
                lyricMode=choice;prefs.edit().putInt("lyricMode",choice).apply();
                for(int j=0;j<languageButtons.size();j++){
                    Button item=languageButtons.get(j);boolean selected=j==choice;
                    item.setSelected(selected);item.setBackground(buttonBackground(selected));item.setTextColor(selected?accent():ink());
                }
            });
            boolean selected=i==lyricMode;option.setSelected(selected);option.setBackground(buttonBackground(selected));option.setTextColor(selected?accent():ink());
            languageButtons.add(option);weighted(languages,option,48);
        }add(drawer,languages,48);
        TextView fontLabel=text(UiStrings.text("歌词字号：")+lyricFontSize,13,true);add(drawer,fontLabel,-2);
        SeekBar fontSize=slider();fontSize.setMax(20);fontSize.setProgress(lyricFontSize-10);fontSize.setContentDescription(UiStrings.text("歌词字号，10 至 30"));add(drawer,fontSize,48);
        TextView spacingLabel=text(String.format(Locale.ROOT,UiStrings.text("歌词行距：%.1f×"),lyricLineSpacing),13,true);add(drawer,spacingLabel,-2);
        SeekBar spacing=slider();spacing.setMax(22);spacing.setProgress(Math.round((lyricLineSpacing-.8f)*10));spacing.setContentDescription(String.format(Locale.ROOT,UiStrings.text("歌词行距，%.1f 倍，范围 0.8 至 3.0 倍，步进 0.1 倍"),lyricLineSpacing));add(drawer,spacing,48);
        TextView preview=lyricText(UiStrings.text("让音乐留在手边\n让每一句靠近一点"));preview.setTextSize(lyricFontSize+2);preview.setTextColor(accent());preview.setTypeface(null,Typeface.BOLD);add(drawer,preview,-2);
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){prefs.edit().putInt("lyricFontSize",lyricFontSize).apply();}
            public void onProgressChanged(SeekBar b,int value,boolean user){if(user){lyricFontSize=10+value;fontLabel.setText(UiStrings.text("歌词字号：")+lyricFontSize);preview.setTextSize(lyricFontSize+2);}}
        });
        spacing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){prefs.edit().putFloat("lyricLineSpacing",lyricLineSpacing).apply();}
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){changeLyricLineSpacing((8+value)/10f,true);spacingLabel.setText(String.format(Locale.ROOT,UiStrings.text("歌词行距：%.1f×"),lyricLineSpacing));bar.setContentDescription(String.format(Locale.ROOT,UiStrings.text("歌词行距，%.1f 倍，范围 0.8 至 3.0 倍，步进 0.1 倍"),lyricLineSpacing));preview.setLineSpacing(0f,lyricLineSpacing);}}
        });
        }
        if(settingsPage.equals("general")){
        settingsHeading(UiStrings.text("外观"));SharedPreferences appearance=service.getSharedPreferences("appearance",0);int mode=appearance.getInt("mode",appearance.getBoolean("dark",false)?2:0);
        String[] modes={UiStrings.text("跟随系统"),UiStrings.text("浅色"),UiStrings.text("深色")};add(drawer,button(UiStrings.text("主题：")+modes[Math.max(0,Math.min(2,mode))]+UiStrings.text("  · 点击切换"),()->{rememberDrafts();appearance.edit().putInt("mode",(mode+1)%3).apply();rebuild();service.refreshFloatingLyrics();}),48);
        Switch motion=new Switch(service);motion.setText(UiStrings.text("界面动效"));motion.setTextColor(ink());motion.setTextSize(13);motion.setPadding(dp(8),0,dp(8),0);motion.setMinimumHeight(dp(48));motion.setChecked(prefs.getBoolean("animationsEnabled",true));motion.setContentDescription(UiStrings.text("界面动效，按压缩放与回弹"));motion.setOnCheckedChangeListener((v,enabled)->prefs.edit().putBoolean("animationsEnabled",enabled).apply());add(drawer,motion,48);
        if(Build.VERSION.SDK_INT>=26&&!ValueAnimator.areAnimatorsEnabled())hint(drawer,UiStrings.text("系统已关闭动画，按钮仅保留按压颜色反馈。"));
        hint(drawer,UiStrings.text("整体大小（最大值随屏幕调整）"));SeekBar size=slider();size.setContentDescription(UiStrings.text("悬浮窗整体缩放"));
        int maxScale=Math.max(90,Math.min(130,(int)((available().width()-dp(8))*100f/dp(BASE_WIDTH))));
        size.setMax(maxScale-90);size.setProgress(Math.max(0,Math.min(maxScale-90,prefs.getInt("scale",100)-90)));add(drawer,size,48);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int n,boolean user){if(user){prefs.edit().putInt("scale",90+n).apply();resize(true);}}});
        hint(drawer,UiStrings.text("背景不透明度"));SeekBar opacity=slider();opacity.setContentDescription(UiStrings.text("背景不透明度"));opacity.setMax(80);opacity.setProgress(Math.max(0,Math.min(80,prefs.getInt("opacity",100)-20)));add(drawer,opacity,48);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int n,boolean user){if(user){prefs.edit().putInt("opacity",20+n).apply();applyOpacity();}}});
        add(drawer,button(UiStrings.text("恢复大小、背景和位置"),()->{prefs.edit().putInt("scale",100).putInt("opacity",100).putInt("x",dp(12)).putInt("y",dp(80)).apply();params.x=dp(12);params.y=dp(80);rebuild();}),48);
        }
        if(settingsPage.equals("service")){
        searchResultLimitButton=button("",this::showSearchResultLimits);settingChoice(UiStrings.text("搜索数量"),searchResultLimitButton);updateSearchResultLimitControl();
        hint(drawer,UiStrings.text("歌曲与歌单共用，所选曲库合计；修改后下次搜索生效。"));
        if(!customServiceInitialized){customServiceExpanded=!(apiDraft==null?ui.optString("api"):apiDraft).isEmpty();customServiceInitialized=true;}
        customServiceButton=button("",()->{customServiceExpanded=!customServiceExpanded;updateCustomServiceFold();});add(drawer,customServiceButton,48);
        customServiceFields=column();hint(customServiceFields,UiStrings.text("仅影响网易云，留空使用内置音乐服务。"));
        apiInput=field(UiStrings.text("https://你的服务地址"),apiDraft==null?ui.optString("api"):apiDraft);apiInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);add(customServiceFields,apiInput,48);
        LinearLayout apiActions=row();weighted(apiActions,button(UiStrings.text("保存地址"),()->{apiDraft=apiInput.getText().toString();hideKeyboard();send("api",apiDraft);}),48);weighted(apiActions,button(UiStrings.text("恢复内置"),()->{apiDraft="";apiInput.setText("");hideKeyboard();send("api","");}),48);add(customServiceFields,apiActions,48);add(drawer,customServiceFields,-2);updateCustomServiceFold();
        }
        if(settingsPage.equals("about")){
            hint(drawer,UiStrings.text("浮音 1.5.0-preview.2 · 悬浮播放"));
            hint(drawer,UiStrings.text("点歌名播放 · 长按右侧手柄拖动排序"));
            hint(drawer,UiStrings.text("仅歌词文字可拖动，留白点击穿透；长按歌词打开设置。支持 1、3、5、7、9 行，屏幕空间不足时自动缩小。"));
        }
    }
    private void settingsEntry(String key,String title,String note,String glyph){
        LinearLayout entry=new PressRow();entry.setOrientation(LinearLayout.VERTICAL);entry.setPadding(dp(10),dp(10),dp(10),dp(10));entry.setBackground(buttonBackground(false));entry.setTag("settingsCategory_"+key);entry.setContentDescription(UiStrings.text(title)+", "+UiStrings.text(note));entry.setOnClickListener(v->openSettingsPage(key));entry.setFocusable(true);
        LinearLayout heading=row();IconButton icon=new IconButton(glyph,"",false);icon.setClickable(false);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);heading.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(28)));TextView caption=text(UiStrings.text(title),14,false);caption.setTypeface(null,Typeface.BOLD);heading.addView(caption,new LinearLayout.LayoutParams(0,-2,1));IconButton arrow=new IconButton("chevron","",false);arrow.setClickable(false);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);heading.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(24)));add(entry,heading,-2);TextView summary=text(UiStrings.text(note),12,true);summary.setPadding(dp(28),dp(2),0,0);add(entry,summary,-2);add(drawer,entry,-2);gap(drawer,4);
    }
    private final class DescriptionBlock extends LinearLayout {
        final TextView words;
        final Button fold;
        private final int collapsedLines;
        private String raw="",key="";
        private boolean expanded=false;
        DescriptionBlock(int lines){
            super(service);setOrientation(VERTICAL);collapsedLines=lines;
            words=text("",13,true);words.setPadding(0,dp(6),0,dp(6));words.setMaxLines(lines);words.setEllipsize(TextUtils.TruncateAt.END);copyOnLongPress(words,()->raw);add(this,words,-2);
            fold=button(UiStrings.text("展开简介"),()->{expanded=!expanded;applyFold();});fold.setTag("descriptionToggle");fold.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);fold.setBackground(buttonBackground(false));fold.setVisibility(GONE);add(this,fold,48);
        }
        void setContent(String content,String placeholder,String identity){
            if(!raw.equals(content)||!key.equals(identity)){expanded=false;raw=content;key=identity;}
            textIfChanged(words,raw.isEmpty()?placeholder:raw);applyFold();
        }
        private void applyFold(){int lines=expanded?Integer.MAX_VALUE:collapsedLines;if(words.getMaxLines()!=lines)words.setMaxLines(lines);textIfChanged(fold,UiStrings.text(expanded?"收起简介":"展开简介"));fold.setContentDescription(fold.getText());if(Build.VERSION.SDK_INT>=30)fold.setStateDescription(UiStrings.text(expanded?"已展开":"已收起"));}
        @Override protected void onMeasure(int widthSpec,int heightSpec){super.onMeasure(widthSpec,heightSpec);int visibility=fold.getVisibility();updateToggle();if(fold.getVisibility()!=visibility)super.onMeasure(widthSpec,heightSpec);}
        private void updateToggle(){android.text.Layout layout=words.getLayout();boolean overflow=false;if(layout!=null)for(int i=0;i<layout.getLineCount();i++)if(layout.getEllipsisCount(i)>0){overflow=true;break;}int visibility=!raw.isEmpty()&&(expanded||overflow)?VISIBLE:GONE;if(fold.getVisibility()!=visibility)fold.setVisibility(visibility);}
    }
    private void settingsHeading(String label){
        if(!sourceQualityButtons.isEmpty())gap(drawer,8);
        LinearLayout heading=row();TextView caption=text(label,13,false);caption.setTypeface(null,Typeface.BOLD);heading.addView(caption,new LinearLayout.LayoutParams(-2,-2));
        View line=new View(service);line.setBackgroundColor(color("#DFDFEB","#343950"));LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,dp(1),1);layout.leftMargin=dp(12);heading.addView(line,layout);add(drawer,heading,28);
    }
    private void settingChoice(String label,Button choice){
        LinearLayout setting=row();TextView caption=text(label,13,false);caption.setMaxLines(2);setting.addView(caption,new LinearLayout.LayoutParams(dp(UiStrings.english()?96:80),-2));choice.setSingleLine(true);choice.setEllipsize(TextUtils.TruncateAt.END);setting.addView(choice,new LinearLayout.LayoutParams(0,dp(48),1));add(drawer,setting,48);
    }
    private void updateCustomServiceFold(){
        if(customServiceFields==null)return;if(!customServiceExpanded&&apiInput.hasFocus()){rememberDrafts();hideKeyboard();}
        customServiceFields.setVisibility(customServiceExpanded?View.VISIBLE:View.GONE);customServiceButton.setText(UiStrings.text("自定义网易云服务  ")+(customServiceExpanded?"▴":"▾"));
        customServiceButton.setContentDescription(UiStrings.text("自定义网易云服务，")+(customServiceExpanded?UiStrings.text("已展开，点击收起"):UiStrings.text("已收起，点击展开")));
        if(Build.VERSION.SDK_INT>=30)customServiceButton.setStateDescription(customServiceExpanded?UiStrings.text("已展开"):UiStrings.text("已收起"));
    }
    private void updateOutputControl(){
        if(outputButton==null)return;String selected=playback.optString("selectedOutput"),name=UiStrings.text("系统默认");JSONArray outputs=array(playback,"outputs");
        for(int i=0;i<outputs.length();i++){JSONObject output=outputs.optJSONObject(i);if(output!=null&&selected.equals(output.optString("id")))name=selected.isEmpty()?UiStrings.text(output.optString("name",name)):output.optString("name",name);}
        textIfChanged(outputButton,name+"  ▾");outputButton.setContentDescription(UiStrings.text("音频输出设备，")+name+UiStrings.text("，点击选择"));
    }
    private void showOutputChoices(){
        LinearLayout choices=column();JSONArray outputs=array(playback,"outputs");boolean hasDefault=false;
        for(int i=0;i<outputs.length();i++){JSONObject output=outputs.optJSONObject(i);if(output==null)continue;String id=output.optString("id");if(id.isEmpty())hasDefault=true;menuItem(choices,(playback.optString("selectedOutput").equals(id)?"✓ ":"")+(id.isEmpty()?UiStrings.text(output.optString("name")):output.optString("name")),()->{dismissPopup();act("output",id);});}
        if(!hasDefault)menuItem(choices,UiStrings.text("系统默认"),()->{dismissPopup();act("output","");});
        menuItem(choices,UiStrings.text("刷新输出设备"),()->{dismissPopup();act("outputs","");});showPanel(UiStrings.text("音频输出设备"),choices);
    }
    private int selectedSearchResultLimit(){return pendingSearchResultLimit==null?searchResultLimit(ui):pendingSearchResultLimit;}
    private void updateSearchResultLimitControl(){
        if(searchResultLimitButton==null)return;int count=selectedSearchResultLimit();textIfChanged(searchResultLimitButton,count+UiStrings.text(" 条  ▾"));searchResultLimitButton.setContentDescription(UiStrings.text("搜索显示数量，所选曲库合计 ")+count+UiStrings.text(" 条，点击选择"));
    }
    private void showSearchResultLimits(){
        LinearLayout choices=column();
        for(int value:SEARCH_RESULT_LIMITS)menuItem(choices,(value==selectedSearchResultLimit()?"✓ ":"")+value+UiStrings.text(" 条"),()->{dismissPopup();pendingSearchResultLimit=value;updateSearchResultLimitControl();send("searchResultLimit",Integer.toString(value));});
        showPanel(UiStrings.text("搜索显示数量"),choices);
    }
    private static String qualityLabel(String source,String key){
        if(key.equals("higher"))return UiStrings.text("较高");if(key.equals("exhigh"))return source.equals("netease")?UiStrings.text("极高"):UiStrings.text("高音质 320");if(key.equals("lossless"))return UiStrings.text("无损 FLAC");if(key.equals("hires"))return "Hi-Res";if(key.equals("master"))return UiStrings.text("实验母带");return UiStrings.text("标准");
    }
    private void showSourceQualityChoices(String source){
        LinearLayout choices=column();
        for(String key:qualityLevels(source))menuItem(choices,(selectedSourceQuality(source).equals(key)?"✓ ":"")+qualityLabel(source,key),()->{
            dismissPopup();try{pendingSourceQualities.put(source,key);}catch(JSONException ignored){}updateQualityControls();act("sourceQuality",args("source",source,"quality",key).toString());
        });
        showPanel(sourceLabel(source)+UiStrings.text("音质"),choices);
    }
    private void floatingOpacityControls(int rows){
        int highlight=FloatingLyricRules.highlightOpacity(prefs.getInt("desktopLyricsHighlightOpacity",100));
        int edge=FloatingLyricRules.edgeOpacity(prefs.getInt("desktopLyricsEdgeOpacity",35),highlight);
        TextView highLabel=text(UiStrings.text("高亮歌词可见度")+" · "+highlight+"%",13,true);
        SeekBar high=slider();high.setTag("desktopLyricsHighlightOpacity");high.setMax(90);high.setProgress(highlight-10);high.setContentDescription(UiStrings.text("高亮歌词可见度"));
        TextView edgeLabel=text(UiStrings.text("边缘歌词可见度")+" · "+edge+"%",13,true);
        SeekBar outer=slider();outer.setTag("desktopLyricsEdgeOpacity");outer.setMax(highlight);outer.setProgress(edge);outer.setEnabled(rows>1);outer.setContentDescription(UiStrings.text("边缘歌词可见度"));
        add(drawer,highLabel,-2);add(drawer,high,48);add(drawer,edgeLabel,-2);add(drawer,outer,48);
        high.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(!user)return;int peak=value+10,edge=FloatingLyricRules.edgeOpacity(outer.getProgress(),peak);
                prefs.edit().putInt("desktopLyricsHighlightOpacity",peak).putInt("desktopLyricsEdgeOpacity",edge).apply();
                outer.setMax(peak);outer.setProgress(edge);highLabel.setText(UiStrings.text("高亮歌词可见度")+" · "+peak+"%");edgeLabel.setText(UiStrings.text("边缘歌词可见度")+" · "+edge+"%");
            }
        });
        outer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){prefs.edit().putInt("desktopLyricsEdgeOpacity",value).apply();edgeLabel.setText(UiStrings.text("边缘歌词可见度")+" · "+value+"%");}}
        });
        hint(drawer,UiStrings.text("100% 最清晰，0% 完全透明；中间各行逐级渐变，单行时仅使用高亮可见度。"));
    }
    private void updateQualityControls(){
        if(sourceQualityButtons.isEmpty())return;
        for(int i=0;i<sourceQualityButtons.size();i++){String source=SEARCH_SOURCE_IDS[i],label=qualityLabel(source,selectedSourceQuality(source));Button choice=sourceQualityButtons.get(i);textIfChanged(choice,label+"  ▾");choice.setEnabled(true);choice.setAlpha(1f);choice.setContentDescription(sourceLabel(source)+UiStrings.text("音质，")+label+UiStrings.text("，点击选择"));}
        qualityHint.setVisibility(selectedSourceQuality("tencent").equals("master")?View.VISIBLE:View.GONE);
        String info=qualityStatus(playback,ui);textIfChanged(sourceInfo,info.isEmpty()?UiStrings.text("播放在线歌曲后显示实际来源与格式。"):info);
    }
    private static String qualityStatus(JSONObject playback,JSONObject ui){return UiStrings.text(playback.optString("qualityInfo",ui.optString("qualityInfo")));}
    private static String searchStatus(JSONObject ui,boolean songs){return UiStrings.text(ui.optBoolean(songs?"searching":"playlistSearching")?"正在搜索…":ui.optString(songs?"searchMessage":"playlistSearchMessage"));}
    void refreshData(JSONObject next){
        JSONObject previous=ui;ui=next;
        if(playback.optJSONObject("sourceQualities")==null)acknowledgeSourceQualities(next);
        if(pendingSearchSources!=null&&pendingSearchSources.toString().equals(searchSourceIds(next).toString()))pendingSearchSources=null;
        if(pendingSearchResultLimit!=null&&pendingSearchResultLimit==searchResultLimit(next))pendingSearchResultLimit=null;
        if(pendingRankingSource!=null&&pendingRankingSource.equals(rankingSource(next)))pendingRankingSource=null;
        boolean apiChanged=!previous.optString("api").equals(next.optString("api"));if(apiChanged)rankingsRequested=false;
        if(!expanded || window==null)return;
        boolean switched=!previous.optString("activePlaylist").equals(next.optString("activePlaylist"));
        if(switched){clearSelection();tracksPage=0;}
        if(!array(previous,"results").toString().equals(array(next,"results").toString()))resultsPage=0;
        if(!array(previous,"playlistResults").toString().equals(array(next,"playlistResults").toString()))playlistResultsPage=0;
        if(selecting){LinkedHashSet<String> valid=new LinkedHashSet<>();JSONArray tracks=array(ui,"tracks");for(int i=0;i<tracks.length();i++){JSONObject track=tracks.optJSONObject(i);if(track!=null)valid.add(track.optString("id"));}selectedIds.retainAll(valid);}
        boolean rebuildLists=section.equals("playlist")&&switched;
        if(rebuildLists){int y=scroll.getScrollY();buildDrawer();detailScroll.scrollTo(0,0);scroll.post(()->scroll.scrollTo(0,y));}
        updatePlaylistHeader();updateSelectionCount();updateSearchControls();updateSearchResultLimitControl();updateRankingControls();updateQualityControls();updateCurrentSource();
        if(feedback!=null){feedback.setText(feedbackMessage());feedback.setVisibility(feedbackMessage().isEmpty()?View.GONE:View.VISIBLE);}
        refreshDynamic();
        if(apiChanged&&section.equals("more")&&detail.equals("rankings"))ensureRankings();
    }
    private void refreshDynamic(){
        if(section.equals("lyrics")&&lyricRows!=null){
            String signature=ui.optString("lyricTrack")+array(ui,"lyricLines").toString()+ui.optString("lyrics")+ui.optString("translation")+UiStrings.text(ui.optString("lyricsMessage"))+lyricMode;
            if(!signature.equals(rendered)){rendered=signature;renderLyrics();}else updateLyrics(false);
            return;
        }
        if(dynamic==null||dragReorder!=null)return;
        if(detailTouching){dynamicRefreshQueued=true;return;}
        String signature=section+detail+searchKind;
        if(section.equals("playlist"))signature+=array(ui,"tracks").toString()+selecting;
        else if(detail.equals("search"))signature+=(searchKind.equals("songs")?array(ui,"results").toString()+UiStrings.text(ui.optString("searchMessage"))+ui.optBoolean("searching"):array(ui,"playlistResults").toString()+UiStrings.text(ui.optString("playlistSearchMessage"))+ui.optBoolean("playlistSearching"))+selectedSearchSources().toString()+(pendingSearchSources!=null);
        else if(detail.equals("rankings"))signature+=array(ui,"rankings").toString()+ui.optBoolean("rankingsLoading")+UiStrings.text(ui.optString("rankingsMessage"))+selectedRankingSource()+(pendingRankingSource!=null);
        else if(detail.equals("online"))signature+=ui.optString("onlinePlaylist")+ui.optBoolean("onlinePlaylistLoading")+requestedOnlineId+onlineSource;
        else if(detail.equals("queue"))signature+=array(playback,"queueEntries").toString()+playback.optString("currentQueueEntry");
        else if(detail.equals("history"))signature+=array(playback,"historyEntries").toString();
        if(signature.equals(rendered))return;rendered=signature;int scrollY=detailScroll.getScrollY();dynamic.removeAllViews();
        if(section.equals("playlist")){songRows(dynamic,array(ui,"tracks"),"tracks");updateSelectionCount();}
        else if(detail.equals("search")){boolean songs=searchKind.equals("songs"),waiting=pendingSearchSources!=null;hint(dynamic,waiting?"":searchStatus(ui,songs));if(songs)songRows(dynamic,waiting?new JSONArray():array(ui,"results"),"results");else playlistResults(dynamic);}
        else if(detail.equals("rankings"))rankingRows(dynamic);
        else if(detail.equals("online"))onlineRows(dynamic);
        else if(detail.equals("queue"))queueRows(dynamic);
        else if(detail.equals("history"))historyRows(dynamic);
        detailScroll.post(()->{if(detailScroll!=null)detailScroll.scrollTo(0,scrollY);});
    }
    private static String cleanLyrics(String raw){return raw.replaceAll("(?i)\\[(?:\\d+:\\d+(?:[.:]\\d+)?|(?:ar|ti|al|by|offset):[^\\]]*)\\]","").trim();}
    private static String lyricCopyText(JSONObject playback,JSONObject ui,int mode){
        if(playback.optString("currentTrack").isEmpty()||!playback.optString("currentTrack").equals(ui.optString("lyricTrack")))return "";
        JSONArray lines=ui.optJSONArray("lyricLines");if(lines==null)lines=new JSONArray();
        if(lines.length()>0){
            ArrayList<String> content=new ArrayList<>();
            for(int i=0;i<lines.length();i++){JSONObject line=lines.optJSONObject(i);if(line==null)continue;String original=line.optString("original"),translation=line.optString("translation");if(mode!=1&&!original.trim().isEmpty())content.add(original);if(mode!=0&&!translation.trim().isEmpty())content.add(translation);}
            return String.join("\n",content);
        }
        String original=cleanLyrics(ui.optString("lyrics")),translation=cleanLyrics(ui.optString("translation"));
        return mode==0?original:mode==1?translation:original+(original.isEmpty()||translation.isEmpty()?"":"\n\n")+translation;
    }
    private boolean copyText(String content){
        if(content==null||content.trim().isEmpty())return false;
        try{ClipboardManager clipboard=(ClipboardManager)service.getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard==null)return false;clipboard.setPrimaryClip(ClipData.newPlainText(UiStrings.text("浮音"),content));Toast.makeText(service,UiStrings.text("已复制"),Toast.LENGTH_SHORT).show();return true;}
        catch(RuntimeException failure){Toast.makeText(service,UiStrings.text("复制失败，请重试。"),Toast.LENGTH_SHORT).show();return false;}
    }
    private void copyOnLongPress(TextView view,java.util.function.Supplier<String> content){view.setOnLongClickListener(target->{copyText(content.get());return true;});}
    private void enableKeyboard(EditText input){
        if(params==null)return;keyboard=true;params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;move();
        input.post(()->{input.requestFocus();((InputMethodManager)service.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);input.requestRectangleOnScreen(new Rect(0,0,input.getWidth(),input.getHeight()),false);});
    }
    private void hideKeyboard(){
        if(window==null)return;((InputMethodManager)service.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(window.getWindowToken(),0);
        window.clearFocus();keyboard=false;if(params!=null){params.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;move();}
    }
    private void resize(boolean apply){
        dismissPopup();
        Rect area=available();float requested=Math.max(.9f,Math.min(1.3f,prefs.getInt("scale",100)/100f));
        scale=Math.min(requested,(area.width()-dp(8))/(float)dp(BASE_WIDTH));
        params.width=Math.round(dp(BASE_WIDTH)*scale);params.height=WindowManager.LayoutParams.WRAP_CONTENT;params.alpha=1f;
        if(scaled!=null)scaled.requestLayout();if(apply){clamp();move();}
    }
    private Rect available(){
        if(Build.VERSION.SDK_INT>=30){WindowMetrics metrics=manager.getCurrentWindowMetrics();android.graphics.Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());Rect b=metrics.getBounds();return new Rect(0,0,b.width()-insets.left-insets.right,b.height()-insets.top-insets.bottom);}
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();manager.getDefaultDisplay().getMetrics(metrics);return new Rect(0,0,metrics.widthPixels,metrics.heightPixels-dp(24));
    }
    private void clamp(){if(params==null)return;Rect a=available();int h=expanded&&window!=null&&window.getHeight()>0?window.getHeight():dp(56);params.x=Math.max(0,Math.min(params.x,a.width()-params.width));params.y=Math.max(0,Math.min(params.y,a.height()-h));}
    private void move(){if(window!=null && window.isAttachedToWindow())try{manager.updateViewLayout(window,params);}catch(IllegalArgumentException ignored){}}
    private void drag(View handle){if(handle instanceof IconButton)((IconButton)handle).motionEnabled=false;handle.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;
        public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:dismissPopup();sx=e.getRawX();sy=e.getRawY();ox=params.x;oy=params.y;moved=false;return true;
            case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.hypot(dx,dy)>ViewConfiguration.get(service).getScaledTouchSlop())moved=true;if(moved){params.x=ox+(int)dx;params.y=oy+(int)dy;clamp();move();}return true;
            case MotionEvent.ACTION_UP:if(!moved)v.performClick();else prefs.edit().putInt("x",params.x).putInt("y",params.y).apply();return true;
            case MotionEvent.ACTION_CANCEL:return true;
        }return false;}
    });}
    void configurationChanged(){if(window==null)return;rememberDrafts();rebuild();}
    private void rebuild(){boolean wasExpanded=expanded;hideKeyboard();remove();show();if(wasExpanded)expand();}

    /** Measure the scaled content, not an invisible unscaled rectangle. ViewGroup maps touch coordinates through the same transform. */
    private final class ScaledFrame extends FrameLayout {
        ScaledFrame(){super(service);setClipChildren(true);setClipToPadding(true);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getAction()==MotionEvent.ACTION_OUTSIDE){dismissPopup();if(keyboard)hideKeyboard();return false;}return super.dispatchTouchEvent(event);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int availableHeight=Math.max(dp(120),available().height());int mode=MeasureSpec.getMode(heightSpec);if(mode!=MeasureSpec.UNSPECIFIED)availableHeight=Math.min(availableHeight,MeasureSpec.getSize(heightSpec));
            View child=getChildAt(0);child.measure(MeasureSpec.makeMeasureSpec(dp(BASE_WIDTH),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(1,(int)(availableHeight/scale)),MeasureSpec.AT_MOST));
            if(section.equals("lyrics")&&lyricViewport!=null){
                int overhead=shell.getMeasuredHeight()-lyricViewport.getMeasuredHeight();
                int lyricHeight=Math.max(dp(160),Math.min(dp(420),(int)(availableHeight/scale)-overhead-dp(8)));
                android.view.ViewGroup.LayoutParams layout=lyricViewport.getLayoutParams();
                if(layout.height!=lyricHeight){layout.height=lyricHeight;lyricViewport.setLayoutParams(layout);
                    child.measure(MeasureSpec.makeMeasureSpec(dp(BASE_WIDTH),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(1,(int)(availableHeight/scale)),MeasureSpec.AT_MOST));}
            }
            setMeasuredDimension(Math.round(dp(BASE_WIDTH)*scale),Math.round(child.getMeasuredHeight()*scale));
            if(getChildCount()>1)getChildAt(1).measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(getMeasuredHeight(),MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){View child=getChildAt(0);child.layout(0,0,child.getMeasuredWidth(),child.getMeasuredHeight());child.setPivotX(0);child.setPivotY(0);child.setScaleX(scale);child.setScaleY(scale);if(getChildCount()>1)getChildAt(1).layout(0,0,r-l,b-t);}
        @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP){if(popupLayer!=null&&popupLayer.getVisibility()==View.VISIBLE)dismissPopup();else if(keyboard)hideKeyboard();else if(section.equals("more")&&detail.equals("settings")&&!settingsPage.isEmpty())openSettingsPage("");else if(section.equals("more")&&detail.equals("online"))selectDetail(onlineSource);else if(section.equals("more")&&!detail.isEmpty())selectDetail("");else if(!section.isEmpty())toggle(section);else collapse();}return true;}return super.dispatchKeyEvent(e);}
    }
    private final class VerticalVolumeSlider extends SeekBar {
        final Paint trackPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        VerticalVolumeSlider(){super(service);setPadding(dp(8),0,dp(8),0);setBackground(null);setDefaultFocusHighlightEnabled(false);setThumb(background(accent(),7));setThumbTintList(ColorStateList.valueOf(accent()));}
        @Override public synchronized void setProgress(int value){super.setProgress(value);invalidate();}
        @Override public void invalidateDrawable(Drawable drawable){super.invalidateDrawable(drawable);if(drawable==getThumb())invalidate();}
        @Override protected void onDraw(Canvas canvas){
            // AbsSeekBar positions its thumb from physical getWidth() on every progress change.
            // Draw directly in vertical coordinates so snapshots, keys and touch share the same track.
            float x=getWidth()/2f,top=Math.min(getPaddingLeft(),getHeight()/2f),bottom=Math.max(top,getHeight()-getPaddingLeft()),y=volumeThumbY(getProgress(),getHeight(),getPaddingLeft());
            trackPaint.setStyle(Paint.Style.STROKE);trackPaint.setStrokeCap(Paint.Cap.ROUND);trackPaint.setStrokeWidth(dp(3));trackPaint.setColor(color("#DFDFEB","#343950"));canvas.drawLine(x,top,x,bottom,trackPaint);
            trackPaint.setColor(accent());canvas.drawLine(x,y,x,bottom,trackPaint);
            int radius=dp(7),cx=Math.round(x),cy=Math.round(y);Drawable thumb=getThumb();thumb.setBounds(cx-radius,cy-radius,cx+radius,cy+radius);thumb.draw(canvas);
            if(isPressed()||hasFocus()){trackPaint.setColor(ink());trackPaint.setStrokeWidth(dp(1));canvas.drawCircle(x,y,dp(6),trackPaint);}
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!isEnabled())return false;int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){requestFocus();setPressed(true);getParent().requestDisallowInterceptTouchEvent(true);}
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)setProgress(volumeAtY(event.getY(),getHeight(),getPaddingLeft()));
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);if(action==MotionEvent.ACTION_UP)performClick();}
            return true;
        }
        @Override public boolean onKeyDown(int key,KeyEvent event){
            if(isEnabled()&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)){setProgress(getProgress()+(key==KeyEvent.KEYCODE_DPAD_UP?1:-1)*getKeyProgressIncrement());return true;}
            return super.onKeyDown(key,event);
        }
    }
    private final class PressButton extends Button {
        PressButton(){super(service);}
        @Override public boolean onTouchEvent(MotionEvent event){pressFeedback(this,event.getActionMasked(),true);return super.onTouchEvent(event);}
        @Override protected void onDetachedFromWindow(){resetPress(this);super.onDetachedFromWindow();}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled)resetPress(this);}
    }
    private final class SourceCheckBox extends CheckBox {
        SourceCheckBox(){super(service);}
        @Override public boolean onTouchEvent(MotionEvent event){pressFeedback(this,event.getActionMasked(),true);return super.onTouchEvent(event);}
        @Override protected void onDetachedFromWindow(){resetPress(this);super.onDetachedFromWindow();}
    }
    private final class PressRow extends LinearLayout {
        PressRow(){super(service);}
        @Override public boolean onTouchEvent(MotionEvent event){pressFeedback(this,event.getActionMasked(),true);return super.onTouchEvent(event);}
        @Override protected void onDetachedFromWindow(){resetPress(this);super.onDetachedFromWindow();}
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
    }
    private final class IconButton extends View {
        String kind;final boolean primary;boolean motionEnabled=true;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        IconButton(String kind,String label,boolean primary){super(service);this.kind=kind;this.primary=primary;setContentDescription(label);setClickable(true);setFocusable(true);setBackground(iconBackground(primary));setMinimumHeight(dp(48));setMinimumWidth(dp(48));}
        @Override public boolean onTouchEvent(MotionEvent event){pressFeedback(this,event.getActionMasked(),motionEnabled);return super.onTouchEvent(event);}
        @Override protected void onDetachedFromWindow(){resetPress(this);super.onDetachedFromWindow();}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:.45f);if(!enabled)resetPress(this);}
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());if(kind.equals("volume"))info.setCanOpenPopup(true);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(kind.equals("brand"))return;c.save();float size=dp(20);c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);paint.setColor(primary?primaryInk():ink());paint.setAlpha(isEnabled()?255:90);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(kind.equals("minus"))c.drawLine(5,12,19,12,paint);
            else if(kind.equals("chevron")){c.drawLine(9,5,16,12,paint);c.drawLine(16,12,9,19,paint);}
            else if(kind.equals("info")){c.drawCircle(12,12,9,paint);c.drawLine(12,10,12,17,paint);c.drawPoint(12,6,paint);}
            else if(kind.equals("grip")){paint.setStyle(Paint.Style.FILL);for(int x:new int[]{8,16})for(int y:new int[]{6,12,18})c.drawCircle(x,y,1.5f,paint);}
            else if(kind.equals("up")||kind.equals("down")){if(kind.equals("down")){c.translate(0,24);c.scale(1,-1);}c.drawLine(12,20,12,4,paint);c.drawLine(12,4,6,10,paint);c.drawLine(12,4,18,10,paint);}
            else if(kind.equals("timing")){c.drawCircle(12,12,8,paint);c.drawLine(12,6,12,12,paint);c.drawLine(12,12,16,14,paint);}
            else if(kind.equals("history")){c.drawArc(4,4,20,20,-140,310,false,paint);c.drawLine(4,8,3,3,paint);c.drawLine(4,8,9,7,paint);c.drawLine(12,7,12,12,paint);c.drawLine(12,12,16,14,paint);}
            else if(kind.equals("queue")){c.drawLine(3,5,17,5,paint);c.drawLine(3,11,13,11,paint);c.drawLine(3,17,10,17,paint);Path p=new Path();p.moveTo(17,12);p.lineTo(22,16);p.lineTo(17,20);p.close();c.drawPath(p,paint);}
            else if(kind.equals("search")){c.drawCircle(10,10,6,paint);c.drawLine(15,15,21,21,paint);}
            else if(kind.equals("rankings")){c.drawLine(5,20,5,12,paint);c.drawLine(12,20,12,5,paint);c.drawLine(19,20,19,9,paint);}
            else if(kind.equals("import")){c.drawLine(12,3,12,15,paint);c.drawLine(12,15,7,10,paint);c.drawLine(12,15,17,10,paint);Path p=new Path();p.moveTo(4,15);p.lineTo(4,21);p.lineTo(20,21);p.lineTo(20,15);c.drawPath(p,paint);}
            else if(kind.equals("favorite")){Path p=new Path();p.moveTo(12,21);p.cubicTo(10,19,3,14,3,8);p.cubicTo(3,2,10,2,12,7);p.cubicTo(14,2,21,2,21,8);p.cubicTo(21,14,14,19,12,21);p.close();c.drawPath(p,paint);}
            else if(kind.equals("lyrics")){c.drawLine(4,6,20,6,paint);c.drawLine(4,12,20,12,paint);c.drawLine(4,18,14,18,paint);}
            else if(kind.equals("settings")){c.drawCircle(12,12,4,paint);for(int i=0;i<8;i++){double angle=i*Math.PI/4;c.drawLine(12+(float)Math.cos(angle)*8,12+(float)Math.sin(angle)*8,12+(float)Math.cos(angle)*10,12+(float)Math.sin(angle)*10,paint);}c.drawCircle(12,12,8,paint);}
            else if(kind.equals("volume")){Path p=new Path();p.moveTo(3,9);p.lineTo(7,9);p.lineTo(12,5);p.lineTo(12,19);p.lineTo(7,15);p.lineTo(3,15);p.close();c.drawPath(p,paint);c.drawArc(6,6,18,18,-45,90,false,paint);c.drawArc(2,2,22,22,-45,90,false,paint);}
            else if(kind.equals("plus")){c.drawLine(5,12,19,12,paint);c.drawLine(12,5,12,19,paint);}
            else if(kind.equals("more")){paint.setStyle(Paint.Style.FILL);for(int x:new int[]{5,12,19})c.drawCircle(x,12,1.5f,paint);}
            else if(kind.equals("sequential")){c.drawLine(4,6,20,6,paint);c.drawLine(4,12,20,12,paint);c.drawLine(4,18,20,18,paint);c.drawLine(20,18,16,14,paint);c.drawLine(20,18,16,22,paint);}
            else if(kind.equals("loop")||kind.equals("single")||kind.equals("refresh")){
                c.drawLine(5,10,5,6,paint);c.drawLine(5,6,20,6,paint);c.drawLine(20,6,17,3,paint);c.drawLine(20,6,17,9,paint);
                c.drawLine(19,14,19,18,paint);c.drawLine(19,18,4,18,paint);c.drawLine(4,18,7,15,paint);c.drawLine(4,18,7,21,paint);
                if(kind.equals("single")){c.drawLine(10,11,12,9,paint);c.drawLine(12,9,12,15,paint);}
            }
            else if(kind.equals("shuffle")){
                c.drawLine(3,6,7,6,paint);c.drawLine(7,6,17,18,paint);c.drawLine(17,18,21,18,paint);c.drawLine(21,18,18,15,paint);c.drawLine(21,18,18,21,paint);
                c.drawLine(3,18,7,18,paint);c.drawLine(7,18,17,6,paint);c.drawLine(17,6,21,6,paint);c.drawLine(21,6,18,3,paint);c.drawLine(21,6,18,9,paint);
            }
            else if(kind.equals("music")){c.drawLine(10,5,10,17,paint);c.drawLine(10,5,19,3,paint);c.drawLine(19,3,19,15,paint);paint.setStyle(Paint.Style.FILL);c.drawOval(4,15,10,20,paint);c.drawOval(13,13,19,18,paint);}
            else if(kind.equals("pause")){paint.setStyle(Paint.Style.FILL);c.drawRoundRect(6,5,10,19,1,1,paint);c.drawRoundRect(14,5,18,19,1,1,paint);}
            else {if(kind.equals("previous")){c.translate(24,0);c.scale(-1,1);}Path p=new Path();p.moveTo(7,5);p.lineTo(18,12);p.lineTo(7,19);p.close();paint.setStyle(kind.equals("play")?Paint.Style.FILL:Paint.Style.STROKE);c.drawPath(p,paint);if(!kind.equals("play"))c.drawLine(20,5,20,19,paint);}
            c.restore();
        }
    }
}
