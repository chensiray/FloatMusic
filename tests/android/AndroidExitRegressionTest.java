package org.floatmusic.player;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android lifecycle/foreground service integration; never installs the actual player APK. */
public final class AndroidExitRegressionTest extends Instrumentation {
    private Welcome welcome;
    private int task;
    private final StringBuilder results = new StringBuilder();
    public static final class Welcome extends PlayerActivity {
        volatile boolean destroyed;
        volatile boolean serviceReleasedBeforeDestroy;
        volatile boolean notificationRemovedBeforeDestroy;
        volatile String sessionBeforeDestroy;
        @Override public void onDestroy() {
            serviceReleasedBeforeDestroy=PlaybackService.instance==null;
            notificationRemovedBeforeDestroy=((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).getActiveNotifications().length==0;
            sessionBeforeDestroy=getSharedPreferences("playback",0).getString("session","");
            destroyed=true;
            super.onDestroy();
        }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void runOnMainSync(Runnable action) {
        Throwable[] error=new Throwable[1];
        super.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    @Override public void onStart() {
        String[] cases={"cold-worker-exit","cold-main-exit","repeat-exit","pending-service-exit","playing-background-exit","paused-session-exit","service-only-exit","back-keeps-activity"};
        int passed=0;
        for(String name:cases) {
            try {
                launch();
                if(name.equals("back-keeps-activity")) back();
                else if(name.equals("playing-background-exit")) serviceExit(true,false);
                else if(name.equals("paused-session-exit")) serviceExit(false,false);
                else if(name.equals("service-only-exit")) serviceExit(false,true);
                else if(name.equals("pending-service-exit")) {
                    runOnMainSync(()->{welcome.send("float","");PlayerBridge.prepareExit();});
                    assertClosed();
                    waitUntil("pending service start cancelled by exit",()->!serviceRunning());
                    check(PlaybackService.instance==null,"pending service must not outlive welcome task");
                }
                else {
                    check(PlaybackService.instance==null,"cold welcome must have no playback service");
                    if(name.equals("cold-worker-exit")) PlayerBridge.prepareExit();
                    else runOnMainSync(()->{PlayerBridge.prepareExit();if(name.equals("repeat-exit"))PlayerBridge.prepareExit();});
                    assertClosed();
                    check(PlaybackService.instance==null,"cold exit must not start playback service");
                }
                results.append("PASS ").append(name).append('\n'); passed++;
            } catch(Throwable e) { results.append("FAIL ").append(name).append(": ").append(e).append('\n'); }
            finally { cleanup(); }
        }
        Bundle out=new Bundle();out.putString("stream",results.toString());out.putInt("passed",passed);
        out.putBoolean("exitPassed",passed==cases.length);finish(passed==cases.length?-1:0,out);
    }
    private static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
    private void waitUntil(String reason,BooleanSupplier condition) {
        long until=SystemClock.elapsedRealtime()+5000;
        do {
            boolean[] ok=new boolean[1];runOnMainSync(()->ok[0]=condition.getAsBoolean());
            if(ok[0])return;SystemClock.sleep(35);
        } while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("Timeout: "+reason);
    }
    private void launch() {
        getTargetContext().getSharedPreferences("audio",0).edit().putInt("volume",0).commit();
        getTargetContext().getSharedPreferences("playback",0).edit().clear().commit();
        getTargetContext().getSharedPreferences("window",0).edit().clear().commit();
        getTargetContext().deleteFile("playback-v1.json");
        welcome=(Welcome)startActivitySync(new Intent(getTargetContext(),Welcome.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        task=welcome.getTaskId();waitForIdleSync();
        check(PlayerBridge.activity.get()==welcome,"real PlayerActivity registers bridge activity");
    }
    private boolean taskPresent() {
        for(ActivityManager.AppTask appTask:((ActivityManager)getTargetContext().getSystemService(Context.ACTIVITY_SERVICE)).getAppTasks())
            if(appTask.getTaskInfo().taskId==task)return true;
        return false;
    }
    private boolean serviceRunning() {
        for(ActivityManager.RunningServiceInfo info:((ActivityManager)getTargetContext().getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(50))
            if(info.service.getClassName().equals(PlaybackService.class.getName()))return true;
        return false;
    }
    private void assertClosed() {
        waitUntil("welcome Activity destroyed after explicit exit",()->welcome.destroyed);
        waitUntil("welcome task removed from recents",()->!taskPresent());
        check(welcome.serviceReleasedBeforeDestroy,"playback resources must be released before Activity destruction");
        check(welcome.notificationRemovedBeforeDestroy,"foreground notification must be removed before Activity destruction");
    }
    private Object field(PlaybackService service,String name) {
        try { Field f=PlaybackService.class.getDeclaredField(name);f.setAccessible(true);return f.get(service); }
        catch(Exception e){throw new AssertionError(e);}
    }
    private JSONObject localTrack() {
        try {
            File file=new File(getTargetContext().getFilesDir(),"exit-silent.wav");int bytes=22050*8*2;
            ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R','I','F','F'}).putInt(bytes+36).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(22050).putInt(44100).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
            try(FileOutputStream output=new FileOutputStream(file)){output.write(header.array());output.write(new byte[bytes]);}
            return new JSONObject().put("id","local:exit").put("source","local").put("name","Exit fixture").put("path",file.getAbsolutePath());
        }catch(Exception e){throw new AssertionError(e);}
    }
    private void serviceExit(boolean playing,boolean withoutActivity) {
        JSONObject track=localTrack();
        runOnMainSync(()->welcome.send("float",""));
        waitUntil("real foreground service created",()->PlaybackService.instance!=null);
        final PlaybackService service=PlaybackService.instance;
        runOnMainSync(()->{
            try { service.dispatch("play",new JSONObject().put("track",track).put("queueTracks",new JSONArray().put(track)).put("queueIndex",0).put("autoplay",playing).toString()); }
            catch(Exception e){throw new AssertionError(e);}
        });
        waitUntil("real local MediaPlayer ready",()->service.playbackSnapshot().optBoolean("ready")&&!service.playbackSnapshot().optBoolean("busy"));
        runOnMainSync(()->check(service.playbackSnapshot().optString("error").isEmpty(),"local playback error: "+service.playbackSnapshot().optString("error")));
        if(playing)waitUntil("local audio actually playing",()->((MediaPlayer)field(service,"player")).isPlaying());
        runOnMainSync(()->service.dispatch("seek","1200"));
        waitUntil("seek complete",()->service.playbackSnapshot().optLong("position")>=1100);
        // Remove the prior periodic/asynchronous save. Only the explicit exit may
        // write the session checked at Activity destruction below.
        runOnMainSync(()->{
            getTargetContext().getSharedPreferences("playback",0).edit().remove("session").commit();
            if(withoutActivity)PlayerBridge.activity.clear();
            else if(playing)welcome.moveTaskToBack(true);
        });
        PlayerBridge.prepareExit();
        waitUntil("service stopped even with no Activity reference",()->PlaybackService.instance==null);
        waitUntil("Android service lifecycle actually stopped",()->!serviceRunning());
        check(((NotificationManager)getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications().length==0,"exit removes foreground notification");
        check(field(service,"player")==null&&field(service,"overlay")==null&&field(service,"floatingLyrics")==null,"exit releases decoder and both overlay windows");
        String saved;
        if(withoutActivity) saved=getTargetContext().getSharedPreferences("playback",0).getString("session","");
        else { assertClosed();saved=welcome.sessionBeforeDestroy; }
        try {
            JSONObject state=new JSONObject(saved);
            check("local:exit".equals(state.getJSONObject("track").getString("id")),"exit synchronously persists current local track");
            check(state.getLong("position")>=1100&&state.getLong("position")<7900,"exit persists actual seek position");
        }catch(Exception e){throw new AssertionError(e);}
        PlayerBridge.prepareExit(); // Already stopped: must remain safe.
        // Restart the real service and verify the saved position is restored paused.
        runOnMainSync(()->getTargetContext().startService(new Intent(getTargetContext(),PlaybackService.class)));
        waitUntil("service restores exit session",()->PlaybackService.instance!=null&&PlaybackService.instance.playbackSnapshot().optBoolean("ready"));
        runOnMainSync(()->{
            JSONObject restored=PlaybackService.instance.playbackSnapshot();
            check("local:exit".equals(restored.optString("currentTrack"))&&!restored.optBoolean("playing"),"reopen restores track paused");
            check(restored.optLong("position")>=1100,"reopen restores saved position");
        });
    }
    private void back() {
        runOnMainSync(()->{
            welcome.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
            welcome.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
        });
        waitForIdleSync();
        check(!welcome.destroyed&&taskPresent(),"Back keeps welcome Activity and task alive");
    }
    private void cleanup() {
        runOnMainSync(()->{
            getTargetContext().stopService(new Intent(getTargetContext(),PlaybackService.class));
            if(welcome!=null&&!welcome.destroyed)welcome.finishAndRemoveTask();
        });
        waitForIdleSync();
        waitUntil("fixture cleanup stopped service",()->PlaybackService.instance==null);
        PlayerBridge.takeEvents();welcome=null;
    }
}
