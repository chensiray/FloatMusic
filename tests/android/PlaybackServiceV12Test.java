package org.floatmusic.player;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real local MediaPlayer callbacks, isolated preferences/files, and silent generated audio. */
public final class PlaybackServiceV12Test extends Instrumentation {
    private PlaybackService service;
    private Context isolated;
    private File directory;
    private JSONObject a,b,c;
    private final AtomicLong clock=new AtomicLong(1000);
    private final StringBuilder results=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void runOnMainSync(Runnable action){
        Throwable[] error=new Throwable[1];super.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    @Override public void onStart(){
        String[] cases={"successful-selection-history","failed-selection-retains-current","first-start-after-failed-replacement","first-start-after-cancelled-replacement","queue-clear-cancels-pending-snapshot","queue-clear-cancels-pending-import","quality-keeps-occurrence","timer-late-prepare","end-of-track-stops-single","restart-cleared-queue"};int passed=0;
        for(String name:cases){try{
            runOnMainSync(()->setup(name));
            if(name.equals("successful-selection-history"))history();
            else if(name.equals("failed-selection-retains-current"))failed();
            else if(name.equals("first-start-after-failed-replacement"))firstStart(false);
            else if(name.equals("first-start-after-cancelled-replacement"))firstStart(true);
            else if(name.equals("queue-clear-cancels-pending-snapshot"))clearPending();
            else if(name.equals("queue-clear-cancels-pending-import"))clearImport();
            else if(name.equals("quality-keeps-occurrence"))qualityOccurrence();
            else if(name.equals("timer-late-prepare"))latePrepare();
            else if(name.equals("end-of-track-stops-single"))endMode();
            else restart();
            results.append("PASS ").append(name).append('\n');passed++;
        }catch(Throwable e){results.append("FAIL ").append(name).append(": ").append(e).append('\n');}
        finally{runOnMainSync(this::release);}}
        Bundle out=new Bundle();out.putString("stream",results.toString());out.putInt("passed",passed);out.putBoolean("servicePassed",passed==cases.length);finish(passed==cases.length?-1:0,out);
    }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private Object field(String name){try{Field f=PlaybackService.class.getDeclaredField(name);f.setAccessible(true);return f.get(service);}catch(Exception e){throw new AssertionError(e);}}
    private void set(String name,Object value){try{Field f=PlaybackService.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);}catch(Exception e){throw new AssertionError(e);}}
    private Object call(String name,Class<?>[] types,Object... args){try{Method m=PlaybackService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(service,args);}catch(Exception e){throw new AssertionError(e);}}
    private Object call(String name){return call(name,new Class<?>[0]);}
    private void setup(String scene){
        String prefix="v12-service-"+scene+"-"+SystemClock.uptimeMillis()+"-";
        directory=new File(getTargetContext().getCacheDir(),prefix);check(directory.mkdirs(),"fixture directory");
        isolated=new ContextWrapper(getTargetContext()){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return getTargetContext().getSharedPreferences(prefix+name,mode);}
            @Override public File getFilesDir(){return directory;}
            @Override public File getCacheDir(){return directory;}
        };
        a=track("a",scene.equals("end-of-track-stops-single")?800:4000);b=track("b",4000);c=track("c",4000);
        newService();clock.set(1000);set("sleepTimer",new PlaybackSleepTimer(clock::get));
    }
    private void newService(){
        try{service=new PlaybackService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,isolated);}
        catch(Exception e){throw new AssertionError(e);}
        set("audio",isolated.getSystemService(Context.AUDIO_SERVICE));set("volume",0);
        set("playbackStore",new PlaybackQueueStore(new File(directory,"playback-v1.json")));call("refreshPlaybackLists");
    }
    private JSONObject track(String name,int milliseconds){
        try{
            File audio=new File(directory,name+".wav");int bytes=milliseconds*22050/1000*2;
            ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R','I','F','F'}).putInt(bytes+36).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(22050).putInt(44100).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
            try(FileOutputStream output=new FileOutputStream(audio)){output.write(header.array());output.write(new byte[bytes]);}
            return new JSONObject().put("id","local:"+name).put("source","local").put("name",name).put("artist","Silent fixture").put("path",audio.getAbsolutePath());
        }catch(Exception e){throw new AssertionError(e);}
    }
    private void select(JSONObject track,int index,boolean autoplay){
        try{service.dispatch("play",new JSONObject().put("track",track).put("queueTracks",new JSONArray().put(a).put(b).put(c)).put("queueIndex",index).put("autoplay",autoplay).toString());}
        catch(Exception e){throw new AssertionError(e);}
    }
    private void waitUntil(String reason,java.util.function.BooleanSupplier condition){
        long deadline=SystemClock.elapsedRealtime()+6000;boolean[] ok=new boolean[1];
        do{runOnMainSync(()->ok[0]=condition.getAsBoolean());if(ok[0])return;SystemClock.sleep(35);}while(SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError("Timeout: "+reason);
    }
    private PlaybackQueueStore store(){return (PlaybackQueueStore)field("playbackStore");}
    private JSONObject snapshot(){return service.playbackSnapshot();}
    private void readyA(){runOnMainSync(()->select(a,0,false));waitUntil("local decoder ready",()->snapshot().optBoolean("ready")&&!snapshot().optBoolean("busy"));}
    private void startPlayback(){runOnMainSync(()->{set("hasFocus",true);call("play");});}
    private void history(){
        readyA();runOnMainSync(()->check(store().history().length()==0,"loading paused must not record history"));startPlayback();
        runOnMainSync(()->{check(store().history().length()==1,"successful start records one item");long played=store().history().optJSONObject(0).optLong("playedAtMs");service.dispatch("toggle","");set("hasFocus",true);call("play");check(store().history().optJSONObject(0).optLong("playedAtMs")==played,"pause/resume must not move timestamp");service.dispatch("clearPlaybackHistory","");service.dispatch("toggle","");set("hasFocus",true);call("play");check(store().history().length()==0,"cleared history stays empty on resume");});
    }
    private void failed(){
        readyA();startPlayback();runOnMainSync(()->{
            String before=store().currentEntryId();try{b.put("path",new File(directory,"missing.wav").getAbsolutePath());}catch(Exception e){throw new AssertionError(e);}
            select(b,1,true);check(!snapshot().optBoolean("busy"),"missing local file finishes as failure");check(before.equals(store().currentEntryId()),"failure keeps old cursor");check(snapshot().optBoolean("playing"),"old audio continues after failure");check(store().history().length()==1,"failure adds no history");
        });
    }
    private void clearPending(){
        readyA();startPlayback();runOnMainSync(()->{
            set("pendingQueueSnapshot",new JSONArray().put(a).put(b));set("pendingQueueIndex",1);set("requestedQueueEntry","");set("requestedTrack",b);set("busy",true);int generation=(Integer)field("generation");
            service.dispatch("clearPlaybackQueue","");check(store().entries().length()==0,"clear saved queue");check(field("pendingQueueSnapshot")==null&&(Integer)field("generation")>generation,"clear cancels future playlist publication");check(snapshot().optBoolean("playing"),"clear keeps current audio");
            call("step",new Class<?>[]{int.class,boolean.class},1,true);check(store().entries().length()==0&&!snapshot().optBoolean("busy"),"completion cannot resurrect cleared queue");
        });
    }
    private void firstStart(boolean cancelled){
        readyA();runOnMainSync(()->{
            if(cancelled){set("requestedTrack",b);set("requestedQueueEntry","pending-occurrence");set("pendingQueueSnapshot",new JSONArray().put(a).put(b));set("pendingQueueIndex",1);set("historyPending",true);set("busy",true);service.dispatch("clearPlaybackQueue","");}
            else {try{b.put("path",new File(directory,"missing.wav").getAbsolutePath());}catch(Exception e){throw new AssertionError(e);}select(b,1,true);}
            check(store().history().length()==0,"still no audio was started");
        });startPlayback();
        runOnMainSync(()->check(store().history().length()==1&&"local:a".equals(store().history().optJSONObject(0).optString("id")),"first actual start of retained A must be recorded"));
    }
    private void latePrepare(){
        readyA();startPlayback();runOnMainSync(()->{
            service.dispatch("startSleepTimer","1");set("resumeOnFocus",true);clock.addAndGet(60000);select(b,1,true);
        });
        waitUntil("replacement ready after deadline",()->"local:b".equals(snapshot().optString("currentTrack"))&&!snapshot().optBoolean("busy"));
        runOnMainSync(()->{check(!snapshot().optBoolean("playing"),"late preparation must not autoplay");check(!(Boolean)field("playIntent")&&!(Boolean)field("resumeOnFocus"),"deadline clears focus and pending resume intent");check("off".equals(snapshot().optString("sleepMode")),"timer consumed once");check(store().history().length()==1,"unplayed prepared replacement not recorded");});
    }
    private void clearImport(){
        readyA();startPlayback();runOnMainSync(()->{
            set("importingAudio",true);set("busy",true);set("requestedQueueEntry","");int before=(Integer)field("generation");
            service.dispatch("clearPlaybackQueue","");check(!(Boolean)field("importingAudio")&&(Integer)field("generation")>before,"clear invalidates pending import callback");check(snapshot().optBoolean("playing")&&store().entries().length()==0,"clear keeps old audio and empty queue");
        });
    }
    private void qualityOccurrence(){
        readyA();runOnMainSync(()->{
            // Hold resolver work at the executor boundary: this tests the actual
            // command state transition without any external request or audio focus.
            ((java.util.concurrent.ExecutorService)field("importer")).shutdownNow();
            set("importer",new java.util.concurrent.AbstractExecutorService(){
                private boolean stopped;
                public void shutdown(){stopped=true;}
                public java.util.List<Runnable> shutdownNow(){stopped=true;return new java.util.ArrayList<>();}
                public boolean isShutdown(){return stopped;}
                public boolean isTerminated(){return stopped;}
                public boolean awaitTermination(long time,java.util.concurrent.TimeUnit unit){return stopped;}
                public void execute(Runnable runnable){}
            });
            try{
                JSONObject same=new JSONObject().put("id","tencent:FixtureMid").put("source","tencent").put("songId","FixtureMid").put("name","same").put("duration",120);
                check(store().replaceQueue(new JSONArray().put(same).put(same),0),"two identical songs with separate occurrences");
                String first=store().entries().optJSONObject(0).optString("queueId"),second=store().entries().optJSONObject(1).optString("queueId");
                set("loadedTrack",same);set("requestedTrack",same);set("loadedQueueEntry",first);set("requestedQueueEntry",second);set("busy",true);set("historyPending",true);set("playIntent",true);
                service.dispatch("sourceQuality",new JSONObject().put("source","tencent").put("quality","exhigh").toString());
                check(second.equals(field("requestedQueueEntry")),"quality reload keeps selected duplicate occurrence");
                JSONArray pending=new JSONArray().put(same);set("pendingQueueSnapshot",pending);set("pendingQueueIndex",0);set("requestedQueueEntry","");set("busy",true);
                service.dispatch("sourceQuality",new JSONObject().put("source","tencent").put("quality","lossless").toString());
                check(field("pendingQueueSnapshot")==pending&&"".equals(field("requestedQueueEntry")),"quality reload keeps new playlist selection pending");
            }catch(Exception e){throw new AssertionError(e);}
        });
    }
    private void endMode(){
        readyA();runOnMainSync(()->{service.dispatch("mode","single");service.dispatch("sleepAfterCurrentTrack","");});startPlayback();
        waitUntil("track completion pauses",()->(Boolean)field("ended")&&"off".equals(snapshot().optString("sleepMode")));
        runOnMainSync(()->{check(!snapshot().optBoolean("playing")&&!snapshot().optBoolean("busy"),"end timer stops repeat mode");check("local:a".equals(snapshot().optString("currentTrack")),"end timer does not advance");check(!(Boolean)field("playIntent"),"end clears auto intent");});
    }
    private void restart(){
        readyA();startPlayback();runOnMainSync(()->{service.dispatch("clearPlaybackQueue","");service.dispatch("startSleepTimer","60");call("saveSession",new Class<?>[]{boolean.class},true);release();newService();call("restoreSession");call("refreshPlaybackLists");check(snapshot().optBoolean("ready")&&!snapshot().optBoolean("playing"),"restart restores current paused");check(store().initialized()&&store().entries().length()==0,"restart retains explicit clear");check("off".equals(snapshot().optString("sleepMode")),"restart cancels old countdown");check(store().history().length()==1,"restart keeps history");});
    }
    private void release(){
        if(service==null)return;set("destroyed",true);set("generation",(Integer)field("generation")+1);
        MediaPlayer player=(MediaPlayer)field("player");if(player!=null)player.release();set("player",null);call("cancelPending");
        ((java.util.concurrent.ExecutorService)field("importer")).shutdownNow();service=null;
    }
}
