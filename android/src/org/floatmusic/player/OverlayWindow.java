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

/** The only Android player surface. Playback and the Qt library remain outside this class. */
final class OverlayWindow {
    private final PlaybackService service;
    private final WindowManager manager;
    private final SharedPreferences prefs;
    private View window;
    private WindowManager.LayoutParams params;
    private ScaledFrame scaled;
    private ScrollView scroll;
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
        ScrollView detailScroll=new ScrollView(service){
            @Override protected void onMeasure(int w,int h){super.onMeasure(w,section.equals("lyrics")?MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED):MeasureSpec.makeMeasureSpec(dp(280),MeasureSpec.AT_MOST));}
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
        hideKeyboard();rememberDrafts();section=section.equals(name)?"":name;detail="";buildDrawer();
        scroll.post(()->scroll.smoothScrollTo(0,0));
    }
    private void selectDetail(String name){hideKeyboard();rememberDrafts();detail=name;buildDrawer();}
    private void rememberDrafts(){if(searchInput!=null)searchDraft=searchInput.getText().toString();if(apiInput!=null)apiDraft=apiInput.getText().toString();if(nameInput!=null)nameDraft=nameInput.getText().toString();}
    private void styleTab(Button b,String name){boolean selected=section.equals(name);b.setSelected(selected);b.setBackground(buttonBackground(selected));b.setTextColor(selected?accentInk():ink());b.setContentDescription(b.getText()+(selected?"，已展开，再次点击收起":"，点击展开"));}
    private void buildDrawer(){
        if(drawer==null)return;
        dismissPopup();rememberDrafts();searchInput=null;apiInput=null;nameInput=null;dynamic=null;rendered="";
        lyricScroll=null;lyricViewport=null;lyricRows=null;lyricReturn=null;lyricMessage=null;lyricTexts.clear();activeLyric=-1;
        drawer.removeAllViews();drawer.setVisibility(section.isEmpty()?View.GONE:View.VISIBLE);
        styleTab(lyricsTab,"lyrics");styleTab(playlistTab,"playlist");styleTab(moreTab,"more");
        if(section.isEmpty())return;
        gap(drawer,section.equals("lyrics")?6:12);View line=new View(service);line.setBackgroundColor(color("#D5DEEB","#3A465A"));add(drawer,line,1);gap(drawer,section.equals("lyrics")?6:12);
        feedback=text(ui.optString("message"),13,true);feedback.setVisibility(ui.optString("message").isEmpty()?View.GONE:View.VISIBLE);add(drawer,feedback,-2);
        if(section.equals("lyrics"))buildLyrics();
        else if(section.equals("playlist"))buildPlaylist();
        else if(detail.equals("search"))buildSearch();
        else if(detail.equals("favorites"))buildFavorites();
        else if(detail.equals("settings"))buildSettings();
        else buildMore();
        refreshDynamic();if(scaled!=null)scaled.requestLayout();
    }
    private void detailHeader(String label){
        LinearLayout head=row();TextView heading=text(label,18,false);heading.setTypeface(null,Typeface.BOLD);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button back=button("返回更多",()->selectDetail(""));head.addView(back,new LinearLayout.LayoutParams(dp(96),dp(56)));add(drawer,head,56);gap(drawer,8);
    }
    private void buildMore(){
        LinearLayout first=row();weighted(first,button("搜索",()->selectDetail("search")),56);weighted(first,button("收藏",()->selectDetail("favorites")),56);add(drawer,first,56);gap(drawer,8);
        LinearLayout second=row();weighted(second,button("导入音乐",()->service.openPicker()),56);weighted(second,button("设置",()->selectDetail("settings")),56);add(drawer,second,56);gap(drawer,8);
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
    private void dismissPopup(){if(popupLayer!=null){popupLayer.removeAllViews();popupLayer.setVisibility(View.GONE);}timingValue=null;}
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
        detailHeader("搜索音乐");searchInput=field("输入歌名",searchDraft);searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);add(drawer,searchInput,56);gap(drawer,8);
        Runnable submit=()->{searchDraft=searchInput.getText().toString();hideKeyboard();send("search",searchDraft);};
        searchInput.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEARCH){submit.run();return true;}return false;});add(drawer,button("搜索",submit),56);
        dynamic=column();add(drawer,dynamic,-2);
    }
    private void buildPlaylist(){
        hint(drawer,"当前歌单");JSONArray lists=array(ui,"playlists");
        for(int i=0;i<lists.length();i++){JSONObject list=lists.optJSONObject(i);if(list==null)continue;String id=list.optString("id");Button b=button((id.equals(ui.optString("activePlaylist"))?"✓ ":"")+list.optString("name"),()->send("selectPlaylist",id));add(drawer,b,56);gap(drawer,6);}
        nameInput=field("歌单名称",nameDraft);add(drawer,nameInput,56);gap(drawer,8);
        LinearLayout actions=row();weighted(actions,button("新建",()->{nameDraft=nameInput.getText().toString();hideKeyboard();send("createPlaylist",nameDraft);}),56);weighted(actions,button("改名",()->{nameDraft=nameInput.getText().toString();hideKeyboard();send("renamePlaylist",nameDraft);}),56);weighted(actions,button("删除",()->{detail=detail.equals("deleteConfirm")?"":"deleteConfirm";buildDrawer();}),56);add(drawer,actions,56);
        if(detail.equals("deleteConfirm")){hint(drawer,"删除当前歌单？原始音频文件会保留。");add(drawer,button("确认删除歌单",()->{detail="";send("deletePlaylist","");buildDrawer();}),56);}
        dynamic=column();add(drawer,dynamic,-2);
    }
    private void buildFavorites(){detailHeader("我的收藏");hint(drawer,"收藏保存在本机，不与网易云账号同步。");add(drawer,button("收藏 / 取消收藏当前歌曲",()->send("favoriteCurrent","")),56);dynamic=column();add(drawer,dynamic,-2);}
    private void songRows(LinearLayout parent,JSONArray tracks,String source){
        if(tracks.length()==0){hint(parent,source.equals("results")?"输入歌名，查找想听的音乐。":"这里还没有歌曲。可在更多中搜索或导入。");return;}
        for(int i=0;i<tracks.length();i++){
            JSONObject track=tracks.optJSONObject(i);if(track==null)continue;String id=track.optString("id");
            LinearLayout song=column();song.setPadding(0,dp(10),0,dp(10));TextView songName=text(track.optString("name"),15,false);songName.setMaxLines(2);songName.setEllipsize(TextUtils.TruncateAt.END);add(song,songName,-2);TextView by=text(track.optString("artist"),12,true);by.setMaxLines(1);add(song,by,-2);gap(song,6);
            LinearLayout actions=row();String playEvent=source.equals("results")?"playResult":source.equals("favorites")?"playFavorite":"playTrack";
            weighted(actions,button("播放",()->send(playEvent,id)),56);
            if(!source.equals("tracks"))weighted(actions,button("加歌单",()->send(source.equals("results")?"addResult":"addFavorite",id)),56);
            if(!source.equals("results"))weighted(actions,button("移除",()->send(source.equals("tracks")?"removeTrack":"removeFavorite",id)),56);
            add(song,actions,56);add(parent,song,-2);View line=new View(service);line.setBackgroundColor(color("#D5DEEB","#3A465A"));add(parent,line,1);
        }
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
        hint(drawer,"浮音 0.6 · 悬浮播放");
    }
    void refreshData(JSONObject next){
        JSONObject previous=ui;ui=next;
        if(!expanded || window==null)return;
        boolean rebuildLists=section.equals("playlist")&&(!array(previous,"playlists").toString().equals(array(next,"playlists").toString())||!previous.optString("activePlaylist").equals(next.optString("activePlaylist")));
        boolean rebuildSettings=section.equals("more")&&detail.equals("settings")&&!previous.optString("quality").equals(next.optString("quality"));
        if(rebuildLists||rebuildSettings){int y=scroll.getScrollY();buildDrawer();scroll.post(()->scroll.scrollTo(0,y));}
        if(feedback!=null){feedback.setText(ui.optString("message"));feedback.setVisibility(ui.optString("message").isEmpty()?View.GONE:View.VISIBLE);}
        refreshDynamic();
    }
    private void refreshDynamic(){
        if(section.equals("lyrics")&&lyricRows!=null){
            String signature=ui.optString("lyricTrack")+array(ui,"lyricLines").toString()+ui.optString("lyrics")+ui.optString("translation")+ui.optString("lyricsMessage")+lyricMode;
            if(!signature.equals(rendered)){rendered=signature;renderLyrics();}else updateLyrics(false);
            return;
        }
        if(dynamic==null)return;
        String signature=section+detail;
        if(section.equals("lyrics"))signature+=ui.optString("lyrics")+ui.optString("translation")+ui.optString("lyricsMessage")+lyricMode;
        else if(section.equals("playlist"))signature+=array(ui,"tracks").toString();
        else if(detail.equals("search"))signature+=array(ui,"results").toString()+ui.optString("searchMessage")+ui.optBoolean("searching");
        else signature+=array(ui,"favorites").toString();
        if(signature.equals(rendered))return;rendered=signature;dynamic.removeAllViews();
        if(section.equals("lyrics")){
            hint(dynamic,ui.optString("lyricsMessage","播放在线音乐后查看歌词"));
            String original=cleanLyrics(ui.optString("lyrics")),translation=cleanLyrics(ui.optString("translation"));
            String value=lyricMode==0?original:lyricMode==1?translation:original+(translation.isEmpty()?"":"\n\n译文\n\n"+translation);
            TextView lyrics=text(value.isEmpty()?"暂无歌词":value,lyricFontSize,false);lyrics.setLineSpacing(dp(6),1f);lyrics.setPadding(0,dp(12),0,dp(12));add(dynamic,lyrics,-2);
        }else if(section.equals("playlist"))songRows(dynamic,array(ui,"tracks"),"tracks");
        else if(detail.equals("search")){hint(dynamic,ui.optBoolean("searching")?"正在搜索…":ui.optString("searchMessage"));songRows(dynamic,array(ui,"results"),"results");}
        else songRows(dynamic,array(ui,"favorites"),"favorites");
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
