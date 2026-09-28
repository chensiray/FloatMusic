package org.floatmusic.player;

import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;

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
    private TextView title, artist, time, volumeText, error, feedback;
    private IconButton play, modeButton, lyricReturn;
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
    private EditText searchInput, apiInput, nameInput;
    private JSONObject playback=new JSONObject(), ui=new JSONObject();
    private String section="", detail="", searchDraft="", apiDraft=null, nameDraft="", rendered="";
    private String searchKind="songs", selectionPlaylist="", requestedOnlineId="";
    private int tracksPage=0, resultsPage=0, onlinePage=0, playlistResultsPage=0;
    private static final int SONG_PAGE_SIZE=80, PLAYLIST_PAGE_SIZE=30;
    private boolean selecting=false;
    private final LinkedHashSet<String> selectedIds=new LinkedHashSet<>();
    private Button selectionCount;
    private Button playlistPicker;
    private TextView playlistDescription;
    private DragReorder dragReorder;
    private View popupFocusReturn;
    private int lyricMode=0, lyricFontSize=18;
    private boolean expanded=false, seeking=false, updating=false, closed=false, keyboard=false;
    private float scale=1f;
    private static final int BASE_WIDTH=340;

    OverlayWindow(PlaybackService service) {
        this.service=service; manager=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);
        prefs=service.getSharedPreferences("window",0); ui=PlayerBridge.uiData();
        lyricMode=Math.max(0,Math.min(2,prefs.getInt("lyricMode",0)));
        lyricFontSize=Math.max(14,Math.min(26,prefs.getInt("lyricFontSize",18)));
    }
    private int dp(float n){return Math.round(n*service.getResources().getDisplayMetrics().density);}
    private boolean dark(){return PlayerBridge.isDark(service);}
    private int color(String light,String night){return Color.parseColor(dark()?night:light);}
    private int ink(){return color("#182338","#EDF2FA");}
    private int muted(){return color("#56657A","#ABB8CC");}
    private int accent(){return color("#2458D3","#83ABFF");}
    private int accentInk(){return color("#FFFFFF","#101722");}
    private int surface(){return color("#FFFFFF","#192333");}
    private int selection(){return color("#E8EFFF","#243B60");}
    private GradientDrawable background(int fill,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));return d;}
    private RippleDrawable buttonBackground(boolean primary){return new RippleDrawable(ColorStateList.valueOf(selection()),background(primary?accent():selection(),14),null);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(service);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(service);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String content,int size,boolean secondary){
        TextView v=new TextView(service);v.setText(content);v.setTextSize(size);v.setTextColor(secondary?muted():ink());v.setFontFeatureSettings("kern");return v;
    }
    private Button button(String name,Runnable action){
        Button b=new Button(service);b.setText(name);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(ink());b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(8),0,dp(8),0);b.setBackground(buttonBackground(false));b.setOnClickListener(v->action.run());return b;
    }
    private void add(LinearLayout parent,View child,int height){parent.addView(child,new LinearLayout.LayoutParams(-1,height<0?height:dp(height)));}
    private void gap(LinearLayout parent,int h){add(parent,new View(service),h);}
    private void weighted(LinearLayout row,View child,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(height),1);if(row.getChildCount()>0)p.leftMargin=dp(8);row.addView(child,p);}
    private void hint(LinearLayout parent,String label){TextView v=text(label,13,true);v.setPadding(0,dp(8),0,dp(8));add(parent,v,-2);}
    private void send(String action,String value){PlayerBridge.event(action,value);}
    private void act(String action,String value){service.dispatch(action,value);}
    private JSONArray array(JSONObject data,String key){JSONArray a=data.optJSONArray(key);return a==null?new JSONArray():a;}
    private JSONObject args(Object... pairs){JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);}catch(JSONException ignored){}return value;}
    private void library(String action,JSONObject value){send("libraryAction",args("action",action,"args",value).toString());}
    private void notice(String message){if(feedback!=null){feedback.setText(message);feedback.setVisibility(View.VISIBLE);}Toast.makeText(service,message,Toast.LENGTH_SHORT).show();}
    private String activePlaylist(){return ui.optString("activePlaylist");}
    private boolean currentSource(String id){if(id.equals(activePlaylist()))return true;dismissPopup();notice("当前歌单已切换，请重新选择要操作的歌曲。");return false;}
    private JSONObject currentList(){JSONArray lists=array(ui,"playlists");for(int i=0;i<lists.length();i++){JSONObject list=lists.optJSONObject(i);if(list!=null&&list.optString("id").equals(activePlaylist()))return list;}return new JSONObject();}
    private JSONArray selectedTracks(){JSONArray ids=new JSONArray();JSONArray tracks=array(ui,"tracks");for(int i=0;i<tracks.length();i++){JSONObject track=tracks.optJSONObject(i);if(track!=null&&selectedIds.contains(track.optString("id")))ids.put(track.optString("id"));}return ids;}
    private String feedbackMessage(){if(section.equals("lyrics"))return "";String message=ui.optString("message");return message.isEmpty()?ui.optString("libraryMessage"):message;}

    void show() {
        if(closed || !Settings.canDrawOverlays(service))return;
        if(window!=null){syncVisibility();return;}
        expanded=false;
        IconButton icon=new IconButton("music", "浮音，点击展开，拖动移动", true);
        icon.setBackground(background(accent(),28));icon.setOnClickListener(v->expand());drag(icon);
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
    void close(){closed=true;hideKeyboard();remove();}
    private void remove(){dismissPopup();if(window!=null){try{manager.removeView(window);}catch(IllegalArgumentException ignored){}window=null;}}
    private void attach(){try{clamp();manager.addView(window,params);syncVisibility();}catch(Exception e){window=null;service.report("无法显示悬浮窗，请检查悬浮权限。");}}
    private void collapse(){hideKeyboard();rememberDrafts();remove();section="";detail="";show();}
    private void expand(){
        remove();expanded=true;keyboard=false;
        shell=column();shell.setPadding(dp(16),dp(8),dp(16),dp(12));applyOpacity();
        LinearLayout header=row();TextView brand=text("浮音",14,true);brand.setTypeface(null,Typeface.BOLD);brand.setContentDescription("浮音，拖动此处移动窗口");drag(brand);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));brand.setGravity(Gravity.CENTER_VERTICAL);
        IconButton minimize=new IconButton("minus","收为图标",false);minimize.setOnClickListener(v->collapse());header.addView(minimize,new LinearLayout.LayoutParams(dp(48),dp(48)));add(shell,header,48);
        title=text("选择一首音乐",23,false);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);add(shell,title,-2);
        artist=text("更多里搜索或导入音乐",13,true);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);add(shell,artist,24);
        progress=slider();progress.setContentDescription("播放进度");add(shell,progress,40);
        time=text("0:00 / 0:00",12,true);add(shell,time,18);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){seeking=true;}
            public void onProgressChanged(SeekBar b,int value,boolean user){if(user)time.setText(clock(value)+" / "+clock(playback.optInt("duration")));}
            public void onStopTrackingTouch(SeekBar b){act("seek",Integer.toString(b.getProgress()));seeking=false;}
        });
        LinearLayout controls=row();
        IconButton previous=new IconButton("previous","上一首",false);previous.setOnClickListener(v->act("previous",""));weighted(controls,previous,48);
        play=new IconButton("play","播放",true);play.setBackground(buttonBackground(true));play.setOnClickListener(v->act("toggle",""));weighted(controls,play,48);
        IconButton next=new IconButton("next","下一首",false);next.setOnClickListener(v->act("next",""));weighted(controls,next,48);
        modeButton=new IconButton("sequential","播放模式",false);modeButton.setOnClickListener(v->showModes());weighted(controls,modeButton,48);add(shell,controls,48);
        LinearLayout gain=row();TextView volumeLabel=text("音量",12,true);gain.addView(volumeLabel,new LinearLayout.LayoutParams(dp(38),-2));
        volume=slider();volume.setMax(100);volume.setContentDescription("音量");gain.addView(volume,new LinearLayout.LayoutParams(0,dp(48),1));volumeText=text("0%",12,true);volumeText.setGravity(Gravity.RIGHT);gain.addView(volumeText,new LinearLayout.LayoutParams(dp(40),-2));add(shell,gain,48);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int n,boolean user){if(user){volumeText.setText(n+"%");act("volume",Integer.toString(n));}}});
        LinearLayout tabs=row();lyricsTab=button("歌词",()->toggle("lyrics"));playlistTab=button("歌单",()->toggle("playlist"));moreTab=button("更多",()->toggle("more"));
        weighted(tabs,lyricsTab,48);weighted(tabs,playlistTab,48);weighted(tabs,moreTab,48);add(shell,tabs,48);
        error=text("",13,false);error.setTextColor(color("#B42318","#FF9B93"));error.setPadding(0,dp(8),0,0);error.setOnClickListener(v->send("retryPlayback",""));error.setContentDescription("播放失败，点击重试");add(shell,error,-2);
        drawer=column();
        detailScroll=new ScrollView(service){
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
        shell.setBackground(background((surface() & 0x00ffffff) | (Math.round(255*opacity/100f)<<24),22));
    }
    private SeekBar slider(){SeekBar b=new SeekBar(service);b.setPadding(dp(8),0,dp(8),0);b.setProgressTintList(ColorStateList.valueOf(accent()));b.setThumbTintList(ColorStateList.valueOf(accent()));b.setProgressBackgroundTintList(ColorStateList.valueOf(color("#D5DEEB","#3A465A")));return b;}
    private static String clock(int ms){int s=ms/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    void update(JSONObject data){
        playback=data;
        if(!expanded || window==null)return;
        updating=true;
        title.setText(data.optString("title","选择一首音乐"));JSONObject track=data.optJSONObject("loadedTrack");
        artist.setText(data.optBoolean("busy")?"正在加载…":track==null?"更多里搜索或导入音乐":track.optString("artist","本地音乐"));
        boolean playing=data.optBoolean("playing");play.kind=playing?"pause":"play";play.setContentDescription(playing?"暂停":"播放");play.invalidate();play.setEnabled(data.optBoolean("ready")&&!data.optBoolean("busy"));
        modeButton.kind=data.optString("playbackMode","sequential");modeButton.setContentDescription("播放模式："+modeName(modeButton.kind));modeButton.invalidate();
        if(!seeking){progress.setMax(Math.max(1,data.optInt("duration")));progress.setProgress(data.optInt("position"));time.setText(clock(data.optInt("position"))+" / "+clock(data.optInt("duration")));}
        progress.setEnabled(data.optBoolean("ready")&&data.optInt("duration")>0);
        if(!volume.isPressed())volume.setProgress(data.optInt("volume",70));volumeText.setText(data.optInt("volume",70)+"%");
        String message=data.optString("error");error.setText(message.isEmpty()?"":message+"  点击重试");error.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
        updating=false;
        updateLyrics(false);
    }
    private void toggle(String name){
        hideKeyboard();rememberDrafts();clearSelection();section=section.equals(name)?"":name;detail="";buildDrawer();
        scroll.post(()->scroll.smoothScrollTo(0,0));
    }
    private void selectDetail(String name){hideKeyboard();rememberDrafts();detail=name;if(detailScroll!=null)detailScroll.scrollTo(0,0);buildDrawer();}
    private void rememberDrafts(){if(searchInput!=null)searchDraft=searchInput.getText().toString();if(apiInput!=null)apiDraft=apiInput.getText().toString();if(nameInput!=null)nameDraft=nameInput.getText().toString();}
    private void styleTab(Button b,String name){boolean selected=section.equals(name);b.setSelected(selected);b.setBackground(buttonBackground(selected));b.setTextColor(selected?accentInk():ink());b.setContentDescription(b.getText()+(selected?"，已展开，再次点击收起":"，点击展开"));}
    private void buildDrawer(){
        if(drawer==null)return;
        stopReorder();
        dismissPopup();rememberDrafts();searchInput=null;apiInput=null;nameInput=null;dynamic=null;rendered="";
        selectionCount=null;playlistPicker=null;playlistDescription=null;
        lyricScroll=null;lyricViewport=null;lyricRows=null;lyricReturn=null;lyricMessage=null;lyricTexts.clear();activeLyric=-1;
        drawer.removeAllViews();drawer.setVisibility(section.isEmpty()?View.GONE:View.VISIBLE);
        styleTab(lyricsTab,"lyrics");styleTab(playlistTab,"playlist");styleTab(moreTab,"more");
        if(section.isEmpty())return;
        gap(drawer,section.equals("lyrics")?6:12);View line=new View(service);line.setBackgroundColor(color("#D5DEEB","#3A465A"));add(drawer,line,1);gap(drawer,section.equals("lyrics")?6:12);
        feedback=text(feedbackMessage(),13,true);feedback.setVisibility(feedbackMessage().isEmpty()?View.GONE:View.VISIBLE);add(drawer,feedback,-2);
        if(section.equals("lyrics"))buildLyrics();
        else if(section.equals("playlist"))buildPlaylist();
        else if(detail.equals("search"))buildSearch();
        else if(detail.equals("online"))buildOnline();
        else if(detail.equals("settings"))buildSettings();
        else buildMore();
        refreshDynamic();if(scaled!=null)scaled.requestLayout();
    }
    private void detailHeader(String label){
        LinearLayout head=row();TextView heading=text(label,18,false);heading.setTypeface(null,Typeface.BOLD);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button back=button("返回更多",()->selectDetail(""));head.addView(back,new LinearLayout.LayoutParams(dp(96),dp(56)));add(drawer,head,56);gap(drawer,8);
    }
    private void buildMore(){
        LinearLayout first=row();weighted(first,button("搜索",()->selectDetail("search")),56);weighted(first,button("收藏歌单",()->{clearSelection();section="playlist";detail="";send("selectPlaylist","favorites");buildDrawer();}),56);add(drawer,first,56);gap(drawer,8);
        LinearLayout second=row();weighted(second,button("导入",this::showImportMenu),56);weighted(second,button("设置",()->selectDetail("settings")),56);add(drawer,second,56);gap(drawer,8);
        add(drawer,button("收藏 / 取消收藏当前歌曲",()->send("favoriteCurrent","")),56);gap(drawer,8);
        Button quit=button("退出浮音",()->act("stop",""));quit.setTextColor(color("#B42318","#FF9B93"));add(drawer,quit,56);
    }
    private void buildLyrics(){
        LinearLayout info=row();lyricMessage=text("",12,true);lyricMessage.setMaxLines(1);lyricMessage.setEllipsize(TextUtils.TruncateAt.END);info.addView(lyricMessage,new LinearLayout.LayoutParams(0,-2,1));
        IconButton timing=new IconButton("timing","歌词时间微调",false);timing.setOnClickListener(v->showTiming(timing));info.addView(timing,new LinearLayout.LayoutParams(dp(48),dp(48)));
        IconButton refresh=new IconButton("refresh","重新获取歌词",false);refresh.setOnClickListener(v->send("retryLyrics",""));LinearLayout.LayoutParams refreshLayout=new LinearLayout.LayoutParams(dp(48),dp(48));refreshLayout.leftMargin=dp(8);info.addView(refresh,refreshLayout);add(drawer,info,48);
        FrameLayout viewport=new FrameLayout(service);lyricViewport=viewport;lyricScroll=new ScrollView(service);lyricScroll.setFillViewport(false);
        lyricRows=column();lyricRows.setPadding(dp(4),dp(12),dp(12),dp(64));lyricScroll.addView(lyricRows);viewport.addView(lyricScroll,new FrameLayout.LayoutParams(-1,-1));
        lyricScroll.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN){manualLyrics=true;v.getParent().requestDisallowInterceptTouchEvent(true);updateLyrics(false);}return false;});
        lyricScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->updateLyricArrow());
        lyricReturn=new IconButton("down","回到当前歌词并恢复跟随",true);lyricReturn.setOnClickListener(v->{lyricScroll.fling(0);manualLyrics=false;updateLyrics(true);});
        FrameLayout.LayoutParams arrow=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM);arrow.rightMargin=dp(4);arrow.bottomMargin=dp(4);viewport.addView(lyricReturn,arrow);
        // ScaledFrame allocates the remaining screen height after measuring the controls.
        add(drawer,viewport,320);
        renderLyrics();
    }
    private static String modeName(String key){return key.equals("loop")?"列表循环":key.equals("single")?"单曲循环":key.equals("shuffle")?"随机播放":"顺序播放";}
    private void dismissPopup(){
        if(popupLayer!=null){if(popupLayer.findFocus() instanceof EditText)hideKeyboard();popupLayer.removeAllViews();popupLayer.setBackgroundColor(Color.TRANSPARENT);popupLayer.setVisibility(View.GONE);}
        if(popupFocusReturn!=null&&popupFocusReturn.isAttachedToWindow())popupFocusReturn.requestFocus();popupFocusReturn=null;timingValue=null;
    }
    private void showPopup(View anchor,LinearLayout content,int widthDp){
        if(popupLayer==null||!expanded)return;dismissPopup();
        int[] at=new int[2],origin=new int[2];anchor.getLocationOnScreen(at);scaled.getLocationOnScreen(origin);
        int width=Math.min(dp(widthDp),scaled.getWidth()-dp(16));
        content.setPadding(dp(8),dp(8),dp(8),dp(8));content.setBackground(background(surface(),16));content.setClickable(true);
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(content.getMeasuredHeight(),Math.max(dp(56),at[1]-origin[1]-dp(12)));
        ScrollView menu=new ScrollView(service);menu.setBackground(background(surface(),16));menu.setElevation(dp(8));menu.addView(content);
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(width,height);
        layout.leftMargin=Math.max(dp(8),Math.min(at[0]-origin[0]+Math.round(anchor.getWidth()*scale)-width,scaled.getWidth()-width-dp(8)));
        layout.topMargin=Math.max(dp(4),at[1]-origin[1]-height-dp(8));
        popupLayer.addView(menu,layout);popupLayer.setVisibility(View.VISIBLE);popupLayer.bringToFront();
    }
    /** Full-card, unscaled sheets keep long menus and text fields usable on a small phone. */
    private void showPanel(String label,LinearLayout content){
        if(popupLayer==null||!expanded)return;View previous=window.findFocus();dismissPopup();popupFocusReturn=previous;
        LinearLayout panel=column();panel.setPadding(dp(12),dp(12),dp(12),dp(12));panel.setBackground(background(surface(),16));panel.setClickable(true);
        LinearLayout header=row();TextView heading=text(label,18,false);heading.setTypeface(null,Typeface.BOLD);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button close=button("关闭",this::dismissPopup);LinearLayout.LayoutParams closeLayout=new LinearLayout.LayoutParams(dp(64),dp(56));closeLayout.leftMargin=dp(8);header.addView(close,closeLayout);add(panel,header,56);gap(panel,8);add(panel,content,-2);
        int width=Math.max(dp(160),scaled.getWidth()-dp(16));int maxHeight=Math.max(dp(80),scaled.getHeight()-dp(16));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        ScrollView menu=new ScrollView(service){
            @Override protected void onMeasure(int w,int h){
                // Reflow the sheet when the keyboard reduces the floating window.
                int cap=Math.max(dp(64),scaled.getMeasuredHeight()-dp(16));
                super.onMeasure(w,MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(h),cap),MeasureSpec.AT_MOST));
            }
        };menu.setFillViewport(false);menu.setBackground(background(surface(),16));menu.setElevation(dp(8));menu.addView(panel);
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(width,Math.min(panel.getMeasuredHeight(),maxHeight),Gravity.CENTER);layout.setMargins(dp(8),dp(8),dp(8),dp(8));
        popupLayer.setBackgroundColor(0x55000000);popupLayer.addView(menu,layout);popupLayer.setVisibility(View.VISIBLE);popupLayer.bringToFront();
        heading.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }
    private void menuItem(LinearLayout menu,String label,Runnable action){if(menu.getChildCount()>0)gap(menu,8);add(menu,button(label,action),56);}
    private void confirm(String title,String explanation,String label,Runnable action){LinearLayout content=column();hint(content,explanation);Button yes=button(label,()->{dismissPopup();action.run();});yes.setTextColor(color("#B42318","#FF9B93"));add(content,yes,56);gap(content,8);add(content,button("取消",this::dismissPopup),56);showPanel(title,content);}
    private void showModes(){
        LinearLayout choices=column();for(String key:new String[]{"sequential","loop","single","shuffle"}){
            boolean selected=key.equals(playback.optString("playbackMode","sequential"));
            Button item=button((selected?"✓  ":"")+modeName(key),()->{act("mode",key);dismissPopup();});item.setSelected(selected);if(selected)item.setTextColor(accent());add(choices,item,56);
        }showPopup(modeButton,choices,184);
    }
    private void showTiming(View anchor){
        if(playback.optString("currentTrack").isEmpty())return;
        LinearLayout content=column();hint(content,"歌词微调 · 正值提前");
        LinearLayout actions=row();
        weighted(actions,button("−0.5s",()->adjustTiming(-500)),56);
        TextView value=button("",()->{act("lyricOffset","0");updateTimingValue();});weighted(actions,value,56);
        weighted(actions,button("+0.5s",()->adjustTiming(500)),56);add(content,actions,56);
        showPopup(anchor,content,300);timingValue=value;timingValue.setContentDescription("歌词偏移，点击归零");updateTimingValue();
    }
    private void adjustTiming(int delta){act("lyricOffset",Integer.toString(playback.optInt("lyricOffset")+delta));updateTimingValue();}
    private void updateTimingValue(){if(timingValue!=null)timingValue.setText(String.format(Locale.ROOT,"%+.1fs",playback.optInt("lyricOffset")/1000f));}
    private void renderLyrics(){
        if(lyricRows==null)return;
        String track=playback.optString("currentTrack");
        if(!track.equals(lyricTrack)){lyricTrack=track;manualLyrics=false;}
        lyricLines=track.equals(ui.optString("lyricTrack"))?array(ui,"lyricLines"):new JSONArray();
        lyricRows.removeAllViews();lyricTexts.clear();activeLyric=-1;
        lyricMessage.setText(ui.optString("lyricsMessage","播放在线音乐后查看歌词"));
        if(lyricLines.length()==0){
            String original=cleanLyrics(ui.optString("lyrics")),translation=cleanLyrics(ui.optString("translation"));
            String plain=lyricMode==1?translation:lyricMode==2?original+"\n\n"+translation:original;
            add(lyricRows,text(track.equals(ui.optString("lyricTrack"))&&!plain.trim().isEmpty()?plain.trim():"暂无逐句歌词",lyricFontSize,true),-2);
        }else for(int i=0;i<lyricLines.length();i++){
            JSONObject line=lyricLines.optJSONObject(i);if(line==null)continue;
            String original=line.optString("original"),translation=line.optString("translation");
            String content=lyricMode==1?(translation.isEmpty()?"暂无该句译文":translation):(original.isEmpty()?"♪":original);
            if(lyricMode==2&&!translation.isEmpty())content+="\n"+translation;
            TextView words=text(content,lyricFontSize,true);words.setPadding(dp(8),dp(7),0,dp(7));words.setLineSpacing(dp(3),1f);lyricTexts.add(words);add(lyricRows,words,-2);
        }
        lyricRows.post(()->updateLyrics(true));
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
        if((changed||force)&&!manualLyrics&&index>=0&&index<lyricTexts.size())lyricScroll.post(()->{
            if(lyricScroll!=null&&!manualLyrics&&activeLyric>=0&&activeLyric<lyricTexts.size()){
                TextView current=lyricTexts.get(activeLyric);lyricScroll.scrollTo(0,Math.max(0,current.getTop()+current.getHeight()/2-lyricScroll.getHeight()/2));updateLyricArrow();
            }
        });
        updateLyricArrow();updateTimingValue();
    }
    private void styleLyric(TextView words,boolean active){words.setTextColor(active?accent():muted());words.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);words.setTextSize(lyricFontSize+(active?2:0));}
    private void updateLyricArrow(){
        if(lyricReturn==null)return;boolean visible=manualLyrics&&activeLyric>=0&&activeLyric<lyricTexts.size();lyricReturn.setVisibility(visible?View.VISIBLE:View.GONE);
        if(visible){TextView current=lyricTexts.get(activeLyric);lyricReturn.kind=current.getTop()+current.getHeight()/2<lyricScroll.getScrollY()+lyricScroll.getHeight()/2?"up":"down";lyricReturn.invalidate();}
    }
    private EditText field(String label,String value){
        EditText e=new EditText(service);e.setSingleLine(true);e.setTextColor(ink());e.setHintTextColor(muted());e.setTextSize(14);e.setHint(label);e.setContentDescription(label);e.setText(value);e.setPadding(dp(12),0,dp(12),0);e.setMinHeight(dp(56));
        GradientDrawable bg=background(color("#F3F5F8","#233044"),12);bg.setStroke(dp(1),color("#7B899D","#718198"));e.setBackground(bg);
        e.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_DOWN)enableKeyboard(e);return false;});
        return e;
    }
    private void buildSearch(){
        detailHeader("搜索音乐");LinearLayout kinds=row();
        for(String kind:new String[]{"songs","playlists"}){Button choice=button(kind.equals("songs")?"歌曲":"歌单",()->{rememberDrafts();hideKeyboard();searchKind=kind;buildDrawer();});boolean selected=searchKind.equals(kind);choice.setSelected(selected);choice.setBackground(buttonBackground(selected));choice.setTextColor(selected?accentInk():ink());weighted(kinds,choice,56);}add(drawer,kinds,56);gap(drawer,8);
        searchInput=field(searchKind.equals("songs")?"输入歌名或歌手":"输入歌单关键词",searchDraft);searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);add(drawer,searchInput,56);gap(drawer,8);
        Runnable submit=()->{searchDraft=searchInput.getText().toString().trim();hideKeyboard();if(searchDraft.isEmpty()){notice("请先输入搜索内容。");return;}if(searchKind.equals("playlists"))library("searchPlaylists",args("query",searchDraft));else send("search",searchDraft);};
        searchInput.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEARCH){submit.run();return true;}return false;});add(drawer,button("搜索",submit),56);
        dynamic=column();add(drawer,dynamic,-2);
    }
    private void buildPlaylist(){
        playlistPicker=button("",this::showPlaylistPicker);playlistPicker.setMaxLines(2);playlistPicker.setEllipsize(TextUtils.TruncateAt.END);add(drawer,playlistPicker,64);
        playlistDescription=text("",13,true);playlistDescription.setPadding(0,dp(8),0,dp(8));playlistDescription.setMaxLines(2);playlistDescription.setEllipsize(TextUtils.TruncateAt.END);add(drawer,playlistDescription,-2);updatePlaylistHeader();
        LinearLayout actions=row();weighted(actions,button("管理歌单",this::showPlaylistMenu),56);weighted(actions,button(selecting?"完成多选":"多选歌曲",()->{selecting=!selecting;selectedIds.clear();selectionPlaylist=activePlaylist();buildDrawer();}),56);add(drawer,actions,56);
        if(selecting){gap(drawer,8);LinearLayout selection=row();weighted(selection,button("全选 / 清空",()->{JSONArray tracks=array(ui,"tracks");if(selectedIds.size()==tracks.length())selectedIds.clear();else for(int i=0;i<tracks.length();i++){JSONObject track=tracks.optJSONObject(i);if(track!=null)selectedIds.add(track.optString("id"));}rendered="";refreshDynamic();}),56);selectionCount=button("",this::showSelectionMenu);weighted(selection,selectionCount,56);add(drawer,selection,56);updateSelectionCount();}
        else hint(drawer,"点歌名播放 · 长按右侧手柄拖动排序");
        dynamic=column();add(drawer,dynamic,-2);
    }
    private void updatePlaylistHeader(){if(playlistPicker==null||!section.equals("playlist"))return;JSONObject list=currentList();playlistPicker.setText(list.optString("name","当前歌单")+" · "+list.optInt("trackCount",array(ui,"tracks").length())+" 首  ▾");playlistPicker.setContentDescription("切换歌单，"+playlistPicker.getText());String description=list.optString("description");playlistDescription.setText(description.isEmpty()?(activePlaylist().equals("favorites")?"收藏保存在本机；收藏歌单不能删除。":"暂无简介 · 在管理歌单中编辑"):description);}
    private void clearSelection(){selecting=false;selectedIds.clear();selectionPlaylist="";}
    private void updateSelectionCount(){if(selectionCount!=null){selectionCount.setText("已选 "+selectedIds.size()+" · 操作");selectionCount.setEnabled(!selectedIds.isEmpty());selectionCount.setAlpha(selectedIds.isEmpty()?.5f:1f);}}
    private void showPlaylistPicker(){LinearLayout menu=column();JSONArray lists=array(ui,"playlists");for(int i=0;i<lists.length();i++){JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id");menuItem(menu,(id.equals(activePlaylist())?"✓ ":"")+list.optString("name")+" · "+list.optInt("trackCount")+" 首",()->{dismissPopup();clearSelection();send("selectPlaylist",id);});}menuItem(menu,"新建歌单",()->showPlaylistEditor(true));showPanel("切换歌单",menu);}
    private void showPlaylistMenu(){
        String source=activePlaylist();LinearLayout menu=column();menuItem(menu,"编辑名称与简介",()->showPlaylistEditor(false));menuItem(menu,"新建歌单",()->showPlaylistEditor(true));
        menuItem(menu,"复制整个歌单",()->{dismissPopup();if(currentSource(source))library("duplicate",args());});
        menuItem(menu,"导入歌曲或歌单",this::showImportMenu);menuItem(menu,"导出整个歌单",()->showExportMenu(source,new JSONArray()));
        if(!source.equals("favorites"))menuItem(menu,"删除歌单",()->confirm("删除歌单", "删除“"+currentList().optString("name")+"”及其中的歌曲记录？本地音频文件会保留。", "确认删除歌单",()->{if(currentSource(source)){clearSelection();send("deletePlaylist","");}}));
        showPanel("管理歌单",menu);
    }
    private void showPlaylistEditor(boolean create){
        String source=activePlaylist();JSONObject list=currentList();boolean protectedList=!create&&source.equals("favorites");LinearLayout content=column();hint(content,"歌单名称");
        EditText name=field("歌单名称",create?"":list.optString("name"));name.setEnabled(!protectedList);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});add(content,name,56);
        if(protectedList)hint(content,"收藏歌单使用固定名称，可以修改简介。");
        hint(content,"歌单简介");EditText description=field("写下这份歌单的心情或用途",create?"":list.optString("description"));description.setSingleLine(false);description.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});description.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);description.setGravity(Gravity.TOP);description.setPadding(dp(12),dp(12),dp(12),dp(12));add(content,description,104);gap(content,8);
        add(content,button(create?"创建歌单":"保存修改",()->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("请输入歌单名称");name.requestFocus();return;}String note=description.getText().toString().trim();if(!create&&!currentSource(source))return;hideKeyboard();dismissPopup();if(create){library("importText",args("text",args("name",value,"description",note,"tracks",new JSONArray()).toString(),"target","new"));}else{if(!protectedList&&!value.equals(list.optString("name")))send("renamePlaylist",value);library("describe",args("description",note));}}),56);
        showPanel(create?"新建歌单":"编辑歌单",content);
    }
    private interface TargetAction {void choose(String id);}
    private void showTargets(String title,boolean includeNew,String excluded,TargetAction action){
        LinearLayout choices=column();String capturedCurrent=activePlaylist();JSONArray lists=array(ui,"playlists");
        for(int pass=0;pass<2;pass++)for(int i=0;i<lists.length();i++){JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id");if(id.equals(excluded)||(pass==0)!=id.equals(capturedCurrent))continue;menuItem(choices,list.optString("name")+(id.equals(capturedCurrent)?" · 当前":"")+" · "+list.optInt("trackCount")+" 首",()->{dismissPopup();action.choose(id);});}
        if(includeNew)menuItem(choices,"新建歌单",()->{dismissPopup();action.choose("new");});
        if(choices.getChildCount()==0)hint(choices,"请先创建另一份歌单，再复制或移动歌曲。");showPanel(title,choices);
    }
    private void showImportMenu(){
        LinearLayout menu=column();menuItem(menu,"剪贴板：歌单 JSON / 链接 / ID",()->showTargets("剪贴板导入到",true,"",target->{library("paste",args("target",target));}));
        menuItem(menu,"JSON 文件",()->showTargets("JSON 文件导入到",true,"",target->{library("chooseImport",args("target",target));}));
        menuItem(menu,"本地音频文件 → 当前歌单",()->{dismissPopup();service.openPicker();});hint(menu,"导入目标会在选择时固定；重复歌曲自动跳过。");showPanel("导入",menu);
    }
    private void showExportMenu(String source,JSONArray ids){LinearLayout menu=column();hint(menu,ids.length()==0?"导出整份歌单的 JSON。":"导出所选 "+ids.length()+" 首歌曲，保留歌单顺序。");menuItem(menu,"复制 JSON 到剪贴板",()->{dismissPopup();if(currentSource(source))library("copyExport",args("ids",ids));});menuItem(menu,"保存为 JSON 文件",()->{dismissPopup();if(currentSource(source))library("chooseExport",args("ids",ids));});showPanel("导出歌单",menu);}
    private void showSelectionMenu(){JSONArray ids=selectedTracks();if(ids.length()==0)return;String source=activePlaylist();LinearLayout menu=column();menuItem(menu,"复制到其他歌单",()->transferTo(source,ids,false));menuItem(menu,"移动到其他歌单",()->transferTo(source,ids,true));menuItem(menu,"导出所选歌曲",()->showExportMenu(source,ids));menuItem(menu,"从歌单移除",()->removeTracks(source,ids));showPanel("已选 "+ids.length()+" 首",menu);}
    private void transferTo(String source,JSONArray ids,boolean move){showTargets(move?"移动到":"复制到",false,source,target->{if(currentSource(source))library("transfer",args("ids",ids,"target",target,"move",move));});}
    private void removeTracks(String source,JSONArray ids){confirm("移除歌曲","从当前歌单移除 "+ids.length()+" 首歌曲？本地音频文件会保留。","确认移除",()->{if(currentSource(source))library("removeTracks",args("ids",ids));});}
    private void showTrackMenu(JSONObject track,int index){
        String id=track.optString("id"),source=activePlaylist();JSONArray ids=new JSONArray().put(id);LinearLayout menu=column();
        menuItem(menu,"选择这首歌曲",()->{if(!currentSource(source))return;dismissPopup();selecting=true;selectionPlaylist=source;selectedIds.add(id);buildDrawer();});
        menuItem(menu,"复制到其他歌单",()->transferTo(source,ids,false));menuItem(menu,"移动到其他歌单",()->transferTo(source,ids,true));menuItem(menu,"调整歌曲顺序",()->showReorderMenu(source,index));menuItem(menu,"从歌单移除",()->removeTracks(source,ids));showPanel(track.optString("name"),menu);
    }
    private void showReorderMenu(String source,int from){
        String snapshot=array(ui,"tracks").toString();int count=array(ui,"tracks").length();LinearLayout menu=column();
        int[] destinations={from-1,from+1,0,count-1};String[] labels={"上移一首","下移一首","移到最前","移到最后"};
        for(int i=0;i<destinations.length;i++){final int to=destinations[i];if(to<0||to>=count||to==from)continue;menuItem(menu,labels[i],()->{dismissPopup();if(currentSource(source)&&snapshot.equals(array(ui,"tracks").toString()))library("moveTrack",args("from",from,"to",to));else notice("歌单已更新，请重新选择排序位置。");});}showPanel("调整顺序",menu);
    }
    private void addTrackTo(JSONObject track){String payload=args("name",track.optString("name","新歌单"),"tracks",new JSONArray().put(track)).toString();showTargets("加入歌单",true,"",target->library("importText",args("text",payload,"target",target)));}
    private void buildOnline(){LinearLayout head=row();TextView heading=text("在线歌单",18,false);heading.setTypeface(null,Typeface.BOLD);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));head.addView(button("返回搜索",()->selectDetail("search")),new LinearLayout.LayoutParams(dp(96),dp(56)));add(drawer,head,56);dynamic=column();add(drawer,dynamic,-2);}
    private void playlistResults(LinearLayout parent){
        JSONArray lists=array(ui,"playlistResults");if(lists.length()==0){hint(parent,ui.optBoolean("playlistSearching")?"正在搜索歌单…":"暂无歌单结果，换个关键词试试。");return;}
        playlistResultsPage=Math.min(playlistResultsPage,(lists.length()-1)/PLAYLIST_PAGE_SIZE);int start=playlistResultsPage*PLAYLIST_PAGE_SIZE;
        for(int i=start;i<Math.min(lists.length(),start+PLAYLIST_PAGE_SIZE);i++){JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id");LinearLayout item=column();item.setPadding(0,dp(12),0,dp(12));TextView name=text(list.optString("name"),16,false);name.setTypeface(null,Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);add(item,name,-2);String creator=list.optString("creator");hint(item,list.optInt("trackCount")+" 首"+(creator.isEmpty()?"":" · "+creator));String summary=list.optString("description");if(!summary.isEmpty()){TextView note=text(summary,13,true);note.setMaxLines(3);note.setEllipsize(TextUtils.TruncateAt.END);add(item,note,-2);gap(item,8);}add(item,button("查看歌单",()->{requestedOnlineId=id;onlinePage=0;selectDetail("online");library("openOnline",args("id",id));}),56);add(parent,item,-2);divider(parent);}
        pageControls(parent,"playlistResults",playlistResultsPage,lists.length(),PLAYLIST_PAGE_SIZE);
    }
    private void onlineRows(LinearLayout parent){
        JSONObject online=ui.optJSONObject("onlinePlaylist");boolean matching=online!=null&&online.optString("id").equals(requestedOnlineId);
        if(ui.optBoolean("onlinePlaylistLoading")||!matching){hint(parent,ui.optBoolean("onlinePlaylistLoading")?"正在加载歌单…":"歌单尚未加载，请返回搜索重试。");return;}
        TextView name=text(online.optString("name"),18,false);name.setTypeface(null,Typeface.BOLD);add(parent,name,-2);hint(parent,online.optInt("trackCount")+" 首 · 已加载 "+array(online,"tracks").length()+" 首");
        String summary=online.optString("description");if(!summary.isEmpty())hint(parent,summary);String warning=online.optString("warning");if(!warning.isEmpty()){TextView note=text(warning,13,false);note.setTextColor(color("#B42318","#FF9B93"));add(parent,note,-2);gap(parent,8);}
        if(array(online,"tracks").length()>0){String onlineId=online.optString("id");LinearLayout actions=row();weighted(actions,button("播放第一首",()->{if(onlineCurrent(onlineId))library("playOnline",args("index",0));}),56);weighted(actions,button("整单加入",()->showTargets("歌单加入到",true,"",target->{if(onlineCurrent(onlineId))library("addOnline",args("target",target));})),56);add(parent,actions,56);}
        songRows(parent,array(online,"tracks"),"online");
    }
    private boolean onlineCurrent(String id){JSONObject online=ui.optJSONObject("onlinePlaylist");if(online!=null&&!ui.optBoolean("onlinePlaylistLoading")&&id.equals(online.optString("id")))return true;notice("在线歌单已更新，请重新打开详情。");return false;}
    private void divider(LinearLayout parent){View line=new View(service);line.setBackgroundColor(color("#D5DEEB","#3A465A"));add(parent,line,1);}
    private void songRows(LinearLayout parent,JSONArray tracks,String source){
        if(tracks.length()==0){hint(parent,source.equals("results")?"输入歌名，查找想听的音乐。":"这里还没有歌曲。可在更多中搜索或导入。");return;}
        LinearLayout songs=column();add(parent,songs,-2);boolean local=source.equals("tracks");String sourceId=activePlaylist();JSONObject online=ui.optJSONObject("onlinePlaylist");String onlineId=online==null?"":online.optString("id");
        int page=Math.min(source.equals("tracks")?tracksPage:source.equals("results")?resultsPage:onlinePage,(tracks.length()-1)/SONG_PAGE_SIZE);setPage(source,page);int start=page*SONG_PAGE_SIZE;
        for(int i=start;i<Math.min(tracks.length(),start+SONG_PAGE_SIZE);i++){
            final int index=i;JSONObject track=tracks.optJSONObject(i);if(track==null)continue;String id=track.optString("id"),name=track.optString("name"),by=track.optString("artist");
            LinearLayout song=row();song.setPadding(0,dp(8),0,dp(8));song.setTag(index);
            if(local&&selecting){CheckBox choice=new CheckBox(service);choice.setText(name+(by.isEmpty()?"":"\n"+by));choice.setTextColor(ink());choice.setTextSize(14);choice.setMaxLines(3);choice.setEllipsize(TextUtils.TruncateAt.END);choice.setButtonTintList(ColorStateList.valueOf(accent()));choice.setChecked(selectedIds.contains(id));choice.setContentDescription(name+"，"+by);choice.setPadding(0,0,dp(8),0);choice.setMinHeight(dp(64));choice.setOnCheckedChangeListener((v,checked)->{if(checked)selectedIds.add(id);else selectedIds.remove(id);updateSelectionCount();});choice.setOnLongClickListener(v->{showSelectionMenu();return true;});song.addView(choice,new LinearLayout.LayoutParams(-1,dp(72)));}
            else{
                Button songName=button(name+(by.isEmpty()?"":"\n"+by),()->{if(local){if(currentSource(sourceId))send("playTrack",id);}else if(source.equals("online")){if(onlineCurrent(onlineId))library("playOnline",args("index",index));}else send("playResult",id);});songName.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);songName.setTextSize(14);songName.setMaxLines(3);songName.setEllipsize(TextUtils.TruncateAt.END);songName.setContentDescription("播放，"+name+"，"+by);songName.setBackground(new RippleDrawable(ColorStateList.valueOf(selection()),null,null));song.addView(songName,new LinearLayout.LayoutParams(0,dp(72),1));
                if(local){IconButton handle=new IconButton("grip","排序 "+name+"，长按拖动，点击选择上移或下移",false);handle.setOnClickListener(v->showReorderMenu(sourceId,index));handle.setOnLongClickListener(v->{if(selecting||!currentSource(sourceId))return true;dragReorder=new DragReorder(songs,song,sourceId,index,tracks.toString());boolean started=handle.startDragAndDrop(ClipData.newPlainText("song",id),new View.DragShadowBuilder(song),dragReorder,0);if(started){song.setAlpha(.45f);handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}else stopReorder();return true;});LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(56),dp(56));hp.leftMargin=dp(8);song.addView(handle,hp);}
                Button more=button(local?"更多":"加入",()->{if(local){if(currentSource(sourceId))showTrackMenu(track,index);}else addTrackTo(track);});more.setContentDescription((local?"歌曲操作，":"加入歌单，")+name);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(56),dp(56));mp.leftMargin=dp(8);song.addView(more,mp);
            }
            add(songs,song,-2);divider(songs);
        }
        pageControls(parent,source,page,tracks.length(),SONG_PAGE_SIZE);
        if(local)detailScroll.setOnDragListener((v,event)->handleSongDrag(event));
    }
    private void setPage(String source,int page){if(source.equals("tracks"))tracksPage=page;else if(source.equals("results"))resultsPage=page;else if(source.equals("playlistResults"))playlistResultsPage=page;else onlinePage=page;}
    private void pageControls(LinearLayout parent,String source,int page,int count,int pageSize){
        if(count<=pageSize)return;hint(parent,"第 "+(page*pageSize+1)+"–"+Math.min(count,(page+1)*pageSize)+" 项，共 "+count+" 项");LinearLayout pages=row();
        Button previous=button("上一页",()->changePage(source,page-1));previous.setEnabled(page>0);previous.setAlpha(page>0?1f:.45f);weighted(pages,previous,56);
        boolean more=(page+1)*pageSize<count;Button next=button("下一页",()->changePage(source,page+1));next.setEnabled(more);next.setAlpha(more?1f:.45f);weighted(pages,next,56);add(parent,pages,56);gap(parent,8);
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
                drag.locate();int from=drag.from,to=drag.target;String source=drag.source,snapshot=drag.snapshot;stopReorder();
                if(source.equals(activePlaylist())&&snapshot.equals(array(ui,"tracks").toString())){if(from!=to)library("moveTrack",args("from",from,"to",to));}
                else notice("歌单已更新，本次拖动未保存，请重新排序。");rendered="";refreshDynamic();return true;
            case DragEvent.ACTION_DRAG_ENDED:stopReorder();rendered="";refreshDynamic();return true;
            default:return true;
        }
    }
    private void stopReorder(){if(dragReorder!=null){DragReorder old=dragReorder;dragReorder=null;if(detailScroll!=null){detailScroll.removeCallbacks(old);detailScroll.requestDisallowInterceptTouchEvent(false);}old.origin.setAlpha(1f);if(old.highlight!=null)old.highlight.setBackground(null);}}
    /** Only the final drop writes to the store; index validity is checked against the original list. */
    private final class DragReorder implements Runnable {
        final LinearLayout songs;final View origin;final String source,snapshot;final int from;int target;float rawY;View highlight;
        DragReorder(LinearLayout songs,View origin,String source,int from,String snapshot){this.songs=songs;this.origin=origin;this.source=source;this.from=from;this.target=from;this.snapshot=snapshot;}
        void locate(){float nearest=Float.MAX_VALUE;View next=null;for(int i=0;i<songs.getChildCount();i++){View song=songs.getChildAt(i);if(!(song.getTag() instanceof Integer))continue;int[] at=new int[2];song.getLocationOnScreen(at);float distance=Math.abs(rawY-(at[1]+song.getHeight()*scale/2));if(distance<nearest){nearest=distance;target=(Integer)song.getTag();next=song;}}if(next!=highlight){if(highlight!=null)highlight.setBackground(null);highlight=next;if(highlight!=null)highlight.setBackground(background(selection(),12));}}
        public void run(){if(dragReorder!=this||detailScroll==null||!detailScroll.isAttachedToWindow())return;int[] at=new int[2];detailScroll.getLocationOnScreen(at);float top=at[1],bottom=top+detailScroll.getHeight()*scale;float edge=Math.min(dp(48),(bottom-top)/3);int direction=rawY<top+edge?-1:rawY>bottom-edge?1:0;if(direction!=0){if(detailScroll.canScrollVertically(direction))detailScroll.scrollBy(0,direction*dp(12));else if(scroll.canScrollVertically(direction))scroll.scrollBy(0,direction*dp(12));locate();}detailScroll.postDelayed(this,32);}
    }
    private void buildSettings(){
        detailHeader("设置");
        hint(drawer,"歌词显示");
        LinearLayout languages=row();String[] lyricLabels={"原文","译文","双语"};
        ArrayList<Button> languageButtons=new ArrayList<>();
        for(int i=0;i<lyricLabels.length;i++){
            final int choice=i;
            Button option=button(lyricLabels[i],()->{
                lyricMode=choice;prefs.edit().putInt("lyricMode",choice).apply();
                for(int j=0;j<languageButtons.size();j++){
                    Button item=languageButtons.get(j);boolean selected=j==choice;
                    item.setSelected(selected);item.setBackground(buttonBackground(selected));item.setTextColor(selected?accentInk():ink());
                }
            });
            boolean selected=i==lyricMode;option.setSelected(selected);option.setBackground(buttonBackground(selected));option.setTextColor(selected?accentInk():ink());
            languageButtons.add(option);weighted(languages,option,48);
        }add(drawer,languages,48);
        TextView fontLabel=text("歌词字号："+lyricFontSize,13,true);fontLabel.setPadding(0,dp(12),0,0);add(drawer,fontLabel,-2);
        SeekBar fontSize=slider();fontSize.setMax(12);fontSize.setProgress(lyricFontSize-14);fontSize.setContentDescription("歌词字号，14 至 26");add(drawer,fontSize,48);
        TextView preview=text("让音乐留在手边",lyricFontSize+2,false);preview.setTextColor(accent());preview.setTypeface(null,Typeface.BOLD);add(drawer,preview,-2);
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){prefs.edit().putInt("lyricFontSize",lyricFontSize).apply();}
            public void onProgressChanged(SeekBar b,int value,boolean user){if(user){lyricFontSize=14+value;fontLabel.setText("歌词字号："+lyricFontSize);preview.setTextSize(lyricFontSize+2);}}
        });
        gap(drawer,8);
        hint(drawer,"外观");SharedPreferences appearance=service.getSharedPreferences("appearance",0);int mode=appearance.getInt("mode",appearance.getBoolean("dark",false)?2:0);
        String[] modes={"跟随系统","浅色","深色"};add(drawer,button("主题："+modes[Math.max(0,Math.min(2,mode))]+"  · 点击切换",()->{rememberDrafts();appearance.edit().putInt("mode",(mode+1)%3).apply();rebuild();}),56);
        hint(drawer,"整体大小（宽高等比例，最大值随屏幕调整）");SeekBar size=slider();
        int maxScale=Math.max(90,Math.min(130,(int)((available().width()-dp(8))*100f/dp(BASE_WIDTH))));
        size.setMax(maxScale-90);size.setProgress(Math.max(0,Math.min(maxScale-90,prefs.getInt("scale",100)-90)));add(drawer,size,56);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int n,boolean user){if(user){prefs.edit().putInt("scale",90+n).apply();resize(true);}}});
        hint(drawer,"背景不透明度（文字和按钮保持清晰）");SeekBar opacity=slider();opacity.setMax(80);opacity.setProgress(Math.max(0,Math.min(80,prefs.getInt("opacity",100)-20)));add(drawer,opacity,56);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int n,boolean user){if(user){prefs.edit().putInt("opacity",20+n).apply();applyOpacity();}}});
        add(drawer,button("恢复大小、背景和位置",()->{prefs.edit().putInt("scale",100).putInt("opacity",100).putInt("x",dp(12)).putInt("y",dp(80)).apply();params.x=dp(12);params.y=dp(80);rebuild();}),56);
        hint(drawer,"在线音质");String[] keys={"standard","higher","exhigh","lossless","hires"},labels={"标准","较高","极高","无损 FLAC","Hi-Res"};
        for(int i=0;i<keys.length;i++){String key=keys[i];add(drawer,button((ui.optString("quality").equals(key)?"✓ ":"")+labels[i],()->send("quality",key)),56);gap(drawer,6);}
        hint(drawer,"Hi-Res 为请求档位，实际音源可能回落。");
        hint(drawer,"音频输出");JSONArray outputs=array(playback,"outputs");for(int i=0;i<outputs.length();i++){JSONObject output=outputs.optJSONObject(i);if(output==null)continue;String id=output.optString("id");add(drawer,button((playback.optString("selectedOutput").equals(id)?"✓ ":"")+output.optString("name"),()->{act("output",id);buildDrawer();}),56);gap(drawer,6);}
        hint(drawer,"自定义音乐服务（留空使用内置）");apiInput=field("https://你的服务地址",apiDraft==null?ui.optString("api"):apiDraft);apiInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);add(drawer,apiInput,56);gap(drawer,8);
        LinearLayout apiActions=row();weighted(apiActions,button("保存地址",()->{apiDraft=apiInput.getText().toString();hideKeyboard();send("api",apiDraft);}),56);weighted(apiActions,button("恢复内置",()->{apiDraft="";apiInput.setText("");hideKeyboard();send("api","");}),56);add(drawer,apiActions,56);
        hint(drawer,"浮音 0.7 · 悬浮播放");
    }
    void refreshData(JSONObject next){
        JSONObject previous=ui;ui=next;
        if(!expanded || window==null)return;
        boolean switched=!previous.optString("activePlaylist").equals(next.optString("activePlaylist"));
        if(switched){clearSelection();tracksPage=0;}
        if(!array(previous,"results").toString().equals(array(next,"results").toString()))resultsPage=0;
        if(!array(previous,"playlistResults").toString().equals(array(next,"playlistResults").toString()))playlistResultsPage=0;
        if(selecting){LinkedHashSet<String> valid=new LinkedHashSet<>();JSONArray tracks=array(ui,"tracks");for(int i=0;i<tracks.length();i++){JSONObject track=tracks.optJSONObject(i);if(track!=null)valid.add(track.optString("id"));}selectedIds.retainAll(valid);}
        boolean rebuildLists=section.equals("playlist")&&switched;
        boolean rebuildSettings=section.equals("more")&&detail.equals("settings")&&!previous.optString("quality").equals(next.optString("quality"));
        if(rebuildLists||rebuildSettings){int y=scroll.getScrollY();buildDrawer();if(rebuildLists)detailScroll.scrollTo(0,0);scroll.post(()->scroll.scrollTo(0,y));}
        updatePlaylistHeader();updateSelectionCount();
        if(feedback!=null){feedback.setText(feedbackMessage());feedback.setVisibility(feedbackMessage().isEmpty()?View.GONE:View.VISIBLE);}
        refreshDynamic();
    }
    private void refreshDynamic(){
        if(section.equals("lyrics")&&lyricRows!=null){
            String signature=ui.optString("lyricTrack")+array(ui,"lyricLines").toString()+ui.optString("lyrics")+ui.optString("translation")+ui.optString("lyricsMessage")+lyricMode;
            if(!signature.equals(rendered)){rendered=signature;renderLyrics();}else updateLyrics(false);
            return;
        }
        if(dynamic==null||dragReorder!=null)return;
        String signature=section+detail+searchKind;
        if(section.equals("playlist"))signature+=array(ui,"tracks").toString()+selecting;
        else if(detail.equals("search"))signature+=searchKind.equals("songs")?array(ui,"results").toString()+ui.optString("searchMessage")+ui.optBoolean("searching"):array(ui,"playlistResults").toString()+ui.optString("playlistSearchMessage")+ui.optBoolean("playlistSearching");
        else if(detail.equals("online"))signature+=ui.optString("onlinePlaylist")+ui.optBoolean("onlinePlaylistLoading")+requestedOnlineId;
        if(signature.equals(rendered))return;rendered=signature;int scrollY=detailScroll.getScrollY();dynamic.removeAllViews();
        if(section.equals("playlist")){songRows(dynamic,array(ui,"tracks"),"tracks");updateSelectionCount();}
        else if(detail.equals("search")){boolean songs=searchKind.equals("songs");hint(dynamic,ui.optBoolean(songs?"searching":"playlistSearching")?"正在搜索…":ui.optString(songs?"searchMessage":"playlistSearchMessage"));if(songs)songRows(dynamic,array(ui,"results"),"results");else playlistResults(dynamic);}
        else if(detail.equals("online"))onlineRows(dynamic);
        detailScroll.post(()->{if(detailScroll!=null)detailScroll.scrollTo(0,scrollY);});
    }
    private static String cleanLyrics(String raw){return raw.replaceAll("\\[(?:\\d+:\\d+(?:[.:]\\d+)?|(?:ar|ti|al|by|offset):[^\\]]*)\\]","").trim();}
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
    private void drag(View handle){handle.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;
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
        @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP){if(popupLayer!=null&&popupLayer.getVisibility()==View.VISIBLE)dismissPopup();else if(keyboard)hideKeyboard();else if(!section.isEmpty())toggle(section);else collapse();}return true;}return super.dispatchKeyEvent(e);}
    }
    private final class IconButton extends View {
        String kind;final boolean primary;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        IconButton(String kind,String label,boolean primary){super(service);this.kind=kind;this.primary=primary;setContentDescription(label);setClickable(true);setFocusable(true);setBackground(buttonBackground(primary));setMinimumHeight(dp(48));}
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();float size=dp(24);c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);paint.setColor(primary?accentInk():ink());paint.setAlpha(isEnabled()?255:90);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(kind.equals("minus"))c.drawLine(5,12,19,12,paint);
            else if(kind.equals("grip")){paint.setStyle(Paint.Style.FILL);for(int x:new int[]{8,16})for(int y:new int[]{6,12,18})c.drawCircle(x,y,1.5f,paint);}
            else if(kind.equals("up")||kind.equals("down")){if(kind.equals("down")){c.translate(0,24);c.scale(1,-1);}c.drawLine(12,20,12,4,paint);c.drawLine(12,4,6,10,paint);c.drawLine(12,4,18,10,paint);}
            else if(kind.equals("timing")){c.drawCircle(12,12,8,paint);c.drawLine(12,6,12,12,paint);c.drawLine(12,12,16,14,paint);}
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
            else {if(kind.equals("previous")){c.translate(24,0);c.scale(-1,1);}Path p=new Path();p.moveTo(7,5);p.lineTo(18,12);p.lineTo(7,19);p.close();paint.setStyle(Paint.Style.FILL);c.drawPath(p,paint);if(!kind.equals("play"))c.drawRect(19,5,21,19,paint);}
            c.restore();
        }
    }
}
