package org.floatmusic.player;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;

/** Real native controls with deterministic read-only browsing data, isolated from the app. */
public final class MusicDetailsUiTest extends Instrumentation {
    OverlayWindow overlay;PlaybackService service;File artifacts;int checks;
    StringBuilder report=new StringBuilder();
    public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] error=new Throwable[1];super.runOnMainSync(()->{try{action.run();}catch(Throwable failure){error[0]=failure;}});
        if(error[0]!=null)throw new RuntimeException(error[0]);
    }
    public void onStart(){
        try{
            artifacts=new File(getTargetContext().getExternalFilesDir(null),"run-"+System.currentTimeMillis());artifacts.mkdirs();
            for(boolean dark:new boolean[]{false,true}){
                runOnMainSync(()->{try{prepare(dark);}catch(Exception e){throw new RuntimeException(e);}});
                waitForIdleSync();SystemClock.sleep(250);
                runOnMainSync(()->{try{capture(dark);}catch(Exception e){throw new RuntimeException(e);}});
                runOnMainSync(()->{try{verify(dark);}catch(Exception e){throw new RuntimeException(e);}});
                waitForIdleSync();SystemClock.sleep(250);
                runOnMainSync(()->{try{captureSongs(dark);}catch(Exception e){throw new RuntimeException(e);}});
                runOnMainSync(()->{try{overlay.close();}catch(Exception ignored){}});
            }
            Bundle b=new Bundle();b.putBoolean("detailsPassed",true);b.putInt("checks",checks);b.putString("artifactDir",artifacts.toString());b.putString("stream",report.toString());finish(-1,b);
        }catch(Throwable e){Bundle b=new Bundle();b.putString("stream","FAIL "+e+"\n"+report);finish(0,b);}
    }
    private void prepare(boolean dark)throws Exception{
        Context target=getTargetContext();String prefix="v17-"+SystemClock.uptimeMillis()+"-";
        File root=new File(target.getCacheDir(),prefix);root.mkdirs();
        Context context=new ContextWrapper(target){
            public android.content.SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(prefix+name,mode);}
            public File getFilesDir(){return root;}public File getCacheDir(){return root;}
        };
        context.getSharedPreferences("appearance",0).edit().putInt("mode",dark?2:1).apply();
        context.getSharedPreferences("window",0).edit().putInt("scale",100).putInt("opacity",100).putBoolean("animationsEnabled",false).apply();
        service=new PlaybackService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);field(service,"destroyed").set(service,true);
        PlayerBridge.foreground=false;PlayerBridge.picking=false;
        overlay=new OverlayWindow(service);overlay.show();invoke("expand");
        field(overlay,"section").set(overlay,"more");field(overlay,"detail").set(overlay,"musicDetails");
        field(overlay,"ui").set(overlay,new JSONObject().put("musicDetails",state()));invoke("buildDrawer");PlayerBridge.takeEvents();
    }
    private JSONObject state()throws Exception{
        JSONArray comments=new JSONArray();for(int i=0;i<22;i++)comments.put(new JSONObject().put("id","c"+i).put("author","读者 "+i).put("date","2026-10-31 10:00").put("likes",i)
            .put("content",i==0?String.join("\n",java.util.Collections.nCopies(15,"长评论，完整阅读并保留 <b>原文</b>。"))+"末尾标记":"这首歌的评论 "+i));
        return new JSONObject().put("source","netease").put("order","hot").put("track",new JSONObject().put("name","晴天").put("artist","周杰伦"))
            .put("details",new JSONObject().put("album","叶惠美").put("commentCount",12000)).put("comments",comments).put("hasMore",true);
    }
    private void verify(boolean dark)throws Exception{
        View root=(View)field(overlay,"window").get(overlay);
        View download=root.findViewWithTag("saveCurrentTrack"),info=root.findViewWithTag("currentMusicDetails");
        require(download!=null&&info!=null&&!(download instanceof TextView)&&!(info instanceof TextView),"desktop-style download and information icons");
        require(download.getWidth()>=48*getTargetContext().getResources().getDisplayMetrics().density,"download keeps 48dp touch area");
        require(find(root,"音乐资料与评论")!=null,"details title");require(find(root,"热门评论")!=null&&find(root,"最新评论")!=null,"both comment orders");
        require(find(root,"读者 19")!=null&&find(root,"读者 20")==null,"native comment views bounded to one page");
        TextView content=(TextView)find(root,"长评论，");require(content!=null&&content.getMaxLines()==6,"long comment collapsed");
        View expand=find(root,"展开");require(expand!=null&&expand.getVisibility()==View.VISIBLE,"expand control visible for clipped comment");expand.performClick();
        require(((TextView)find(root,"长评论，")).getMaxLines()>6,"long comment expands");
        find(root,"最新评论").performClick();require(PlayerBridge.takeEvents().contains("new"),"order event reaches shared bridge");
        find(root,"QQ音乐").performClick();require(PlayerBridge.takeEvents().contains("tencent"),"source event reaches shared bridge");
        JSONObject data=new JSONObject().put("playCount",0);Method stats=OverlayWindow.class.getDeclaredMethod("stats",JSONObject.class);stats.setAccessible(true);
        String counts=(String)stats.invoke(null,data);require(counts.contains("0")&&!counts.contains("收藏"),"real zero shown and missing count hidden");
        View next=find(root,"下一页");require(next!=null,"comment page navigation");next.performClick();require(find(root,"读者 21")!=null,"last loaded comment reachable");
        JSONObject cached=state().put("stale",true).put("error","连接超时，请检查网络后重试。");overlay.refreshData(new JSONObject().put("musicDetails",cached));
        require(find(root,"正在显示缓存内容")!=null&&find(root,"重试")!=null,"offline contents and recovery visible");
        JSONObject first=state();JSONArray twenty=new JSONArray();for(int i=0;i<20;i++)twenty.put(first.getJSONArray("comments").get(i));first.put("comments",twenty);
        overlay.refreshData(new JSONObject().put("musicDetails",first));
        find(root,"加载更多").performClick();PlayerBridge.takeEvents();
        overlay.refreshData(new JSONObject().put("musicDetails",new JSONObject(first.toString()).put("loading",true)));
        overlay.refreshData(new JSONObject().put("musicDetails",state()));
        require(find(root,"读者 21")!=null,"slow load more advances to newly received comments");
        field(overlay,"commentsPage").set(overlay,0);field(overlay,"rendered").set(overlay,"");invoke("refreshDynamic");
        Method select=OverlayWindow.class.getDeclaredMethod("selectDetail",String.class);select.setAccessible(true);select.invoke(overlay,"search");require(PlayerBridge.takeEvents().contains("close"),"leaving details cancels shared requests");
        verifyArtwork(root,dark);
    }
    private void verifyArtwork(View root,boolean dark)throws Exception{
        JSONArray tracks=new JSONArray().put(new JSONObject().put("id","local:one").put("source","local").put("name","本地音乐"))
            .put(new JSONObject().put("id","kuwo:123").put("songId","123").put("source","kuwo").put("name","缺少封面的歌曲"));
        JSONObject data=new JSONObject().put("tracks",tracks).put("activePlaylist","fixture").put("songCoversEnabled",true);
        field(overlay,"ui").set(overlay,data);field(overlay,"section").set(overlay,"playlist");field(overlay,"detail").set(overlay,"");invoke("buildDrawer");
        require(root.findViewWithTag("songCover-local:one")!=null&&root.findViewWithTag("songCover-kuwo:123")!=null,"every native song row has a cover even when image is unavailable");
        overlay.refreshData(new JSONObject(data.toString()).put("songCoversEnabled",false));
        require(root.findViewWithTag("songCover-local:one")==null,"native covers hide when setting is off");
        overlay.refreshData(data);require(root.findViewWithTag("songCover-kuwo:123")!=null,"native covers return when setting is on");
    }
    private void capture(boolean dark)throws Exception{
        View root=(View)field(overlay,"window").get(overlay);
        TextView content=(TextView)find(root,"长评论，");require(content!=null&&content.getWidth()>0&&content.getHeight()>0,"comment content laid out before screenshot");
        View expand=find(root,"展开");require(expand!=null&&expand.getHeight()>0,"visible expand button has a measured touch area");
        Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        try(FileOutputStream out=new FileOutputStream(new File(artifacts,dark?"details-dark.png":"details-light.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        ScrollView scroll=(ScrollView)field(overlay,"detailScroll").get(overlay);scroll.scrollTo(0,Math.max(0,content.getTop()-24));
        Bitmap reading=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(reading));
        try(FileOutputStream out=new FileOutputStream(new File(artifacts,dark?"comments-dark.png":"comments-light.png"))){reading.compress(Bitmap.CompressFormat.PNG,100,out);}reading.recycle();
    }
    private void captureSongs(boolean dark)throws Exception{
        View root=(View)field(overlay,"window").get(overlay);View cover=root.findViewWithTag("songCover-local:one");require(cover!=null&&cover.getWidth()>0,"default cover has a measured visible area");
        Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
        try(FileOutputStream out=new FileOutputStream(new File(artifacts,dark?"songs-dark.png":"songs-light.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    private static View find(View v,String prefix){if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(prefix))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=find(((ViewGroup)v).getChildAt(i),prefix);if(f!=null)return f;}return null;}
    private static Field field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private void invoke(String name)throws Exception{Method m=overlay.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(overlay);}
    private void require(boolean value,String label){if(!value)throw new AssertionError(label);checks++;report.append("PASS ").append(label).append('\n');}
}
