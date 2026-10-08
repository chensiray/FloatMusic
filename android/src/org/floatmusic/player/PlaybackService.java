package org.floatmusic.player;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;

public class PlaybackService extends Service {
    static PlaybackService instance;
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    private static final String CHANNEL = "floatmusic.playback";
    private static final int NOTIFICATION = 102;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService importer = Executors.newSingleThreadExecutor();
    private MediaPlayer player, pending;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean hasFocus = false, resumeOnFocus = false, ready = false, busy = false, ended = false;
    private volatile boolean destroyed = false;
    private boolean playIntent = false;
    private boolean importingAudio = false;
    private final AudioResolver audioResolver = new AudioResolver();
    private final java.util.Set<String> attemptedSources = new java.util.HashSet<>();
    private final TrackState.SeekState seeks = new TrackState.SeekState();
    private String loadedSource = "", loadedSourceId = "";
    private int loadedBitrate = 0, lastPosition = 0;
    private String title = "还没有导入音乐", error = "";
    private File pendingFile;
    private OverlayWindow overlay;
    private JSONArray queue = new JSONArray();
    private JSONObject importedTrack = null, loadedTrack = null, requestedTrack = null;
    private JSONObject sourceQualities = TrackState.sourceQualities(null,"standard");
    private String quality = "standard", loadedQuality = "standard", loadedRequestedQuality = "standard", actualFormat = "等待音频信息", qualityNotice = "";
    private String currentTrack = "", apiBase = "";
    private String playbackMode = "sequential";
    private boolean restored = false;
    private int restoredPosition = 0, restoredDuration = 0, lyricOffset = 0;
    private long lastSessionSave = 0;
    private int volume = 70;
    private boolean ducked = false;
    private String selectedOutput = "";
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refreshOutputs(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refreshOutputs(); }
    };
    private volatile int generation = 0;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(true); }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() { if (destroyed) return; update(); if(SystemClock.elapsedRealtime()-lastSessionSave>=5000)saveSession(false); handler.postDelayed(this, 250); }
    };
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        audio = (AudioManager)getSystemService(AUDIO_SERVICE);
        volume = getSharedPreferences("audio",MODE_PRIVATE).getInt("volume",70);
        overlay = new OverlayWindow(this);
        try {
            JSONObject saved = new JSONObject(getSharedPreferences("queue",MODE_PRIVATE).getString("data","{}"));
            queue = saved.optJSONArray("tracks"); if (queue == null) queue = new JSONArray();
            apiBase = saved.optString("api"); quality = saved.optString("quality","standard");
            String imported=getSharedPreferences("queue",MODE_PRIVATE).getString("imported","");
            if(!imported.isEmpty())importedTrack=new JSONObject(imported);
        } catch (Exception ignored) { queue = new JSONArray(); }
        JSONObject savedQualities=null;
        try { savedQualities=new JSONObject(getSharedPreferences("audio",MODE_PRIVATE).getString("sourceQualities","")); }
        catch(Exception ignored){}
        sourceQualities=TrackState.sourceQualities(savedQualities,quality);
        quality=TrackState.requestedQuality("netease",sourceQualities);
        persistSourceQualities();
        restoreSession();
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = true; ducked = false; applyVolume();
                if (resumeOnFocus) { resumeOnFocus = false; playIntent = true; play(); }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                ducked = true; applyVolume();
            } else {
                boolean resume = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && playIntent;
                pause(false); hasFocus = false; resumeOnFocus = resume;
                if (change == AudioManager.AUDIOFOCUS_LOSS) abandonFocus();
            }
        }, handler).build();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "FloatMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(true); }
            @Override public void onStop() { stopSelf(); }
            @Override public void onSeekTo(long pos) { seek(pos); }
            @Override public void onSkipToNext() { step(1, false); }
            @Override public void onSkipToPrevious() { step(-1, false); }
        });
        session.setActive(true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION, notification());
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(noisy, filter);
        audio.registerAudioDeviceCallback(deviceCallback, handler);
        handler.post(ticker);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) dispatch(intent.getAction(), intent.getStringExtra("value"));
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    void dispatch(String command, String value) {
        if (command == null) return;
        switch (command) {
            case "mode":
                if(java.util.Arrays.asList("sequential","loop","single","shuffle").contains(value)) {
                    playbackMode=value;getSharedPreferences("playback",0).edit().putString("mode",value).apply();update();
                } break;
            case "lyricOffset":
                try { if(!currentTrack.isEmpty()) {lyricOffset=Math.max(-10000,Math.min(10000,Integer.parseInt(value)));
                    getSharedPreferences("lyricOffsets",0).edit().putInt(currentTrack,lyricOffset).apply();update();} }
                catch(NumberFormatException ignored){} break;
            case "volume": volume=Math.max(0,Math.min(100,Integer.parseInt(value))); getSharedPreferences("audio",MODE_PRIVATE).edit().putInt("volume",volume).apply(); applyVolume(); update(); break;
            case "output": selectedOutput=value; refreshOutputs(); break;
            case "outputs": refreshOutputs(); break;
            case "sourceQuality":
                try { JSONObject change=new JSONObject(value);setSourceQuality(change.optString("source"),change.optString("quality")); }
                catch(Exception ignored){} break;
            case "quality": setSourceQuality("netease",value); break;
            case "queue":
                try { JSONObject data = new JSONObject(value); queue = data.getJSONArray("tracks");
                    if(!apiBase.equals(data.optString("api")))audioResolver.clearFailures();
                    apiBase = data.optString("api");
                    // Native preferences are authoritative after migration. Periodic bridge sync
                    // can still carry a stale/default legacy NetEase quality.
                    getSharedPreferences("queue",MODE_PRIVATE).edit().putString("data",value).apply();
                } catch (Exception e) { report("歌单同步失败。"); } break;
            case "track": playTrack(value); break;
            case "retry": audioResolver.clearFailures(); // Explicit retry rechecks cooled-down services.
            case "play":
                try { JSONObject request = new JSONObject(value); playTrack(request.getJSONObject("track"), request.optBoolean("autoplay",true), request.optBoolean("preserve",false)); }
                catch(Exception e) { report("播放请求无效："+e.getMessage()); } break;
            case "theme": if(overlay!=null)overlay.configurationChanged(); break;
            case "previous": step(-1, false); break;
            case "next": step(1, false); break;
            case "ackImport": importedTrack = null; getSharedPreferences("queue",MODE_PRIVATE).edit().remove("imported").apply(); break;
            case "float": showOverlay(); break;
            case "import": importAudio(Uri.parse(value)); break;
            case "toggle": if (isPlaying()) pause(true); else play(); break;
            case "seek": try { seek(Long.parseLong(value)); } catch (Exception ignored) {} break;
            case "stop": saveSession(true);PlayerBridge.event("quit", ""); stopSelf(); break;
        }
    }
    private void persistSourceQualities() {
        getSharedPreferences("audio",MODE_PRIVATE).edit().putString("sourceQualities",sourceQualities.toString()).apply();
    }
    private void setSourceQuality(String source,String nextQuality) {
        if(!AudioResolver.validQuality(source,nextQuality)||nextQuality.equals(TrackState.requestedQuality(source,sourceQualities)))return;
        try { sourceQualities.put(source,nextQuality); } catch(Exception ignored){return;}
        quality=TrackState.requestedQuality("netease",sourceQualities);persistSourceQualities();
        JSONObject track=busy&&requestedTrack!=null?requestedTrack:loadedTrack;
        if(importingAudio||!TrackState.reloadForQuality(source,track,restored&&!busy)){update();return;}
        boolean same=loadedTrack!=null&&track.optString("id").equals(loadedTrack.optString("id"));
        boolean wanted=TrackState.qualityPlayIntent(busy,playIntent,isPlaying(),resumeOnFocus);
        generation++;audioResolver.cancel();cancelPending();
        // The current player remains seekable while resolving. Follow its latest
        // position when the replacement is ready, including any newer user seek.
        playTrack(track,wanted,same,false,-1);
    }
    private boolean isPlaying() { try { return ready && player != null && player.isPlaying(); } catch (IllegalStateException e) { return false; } }
    private int pendingSeek() {return seeks.pendingFor(player);}
    private int position() { if(restored)return restoredPosition; if(pendingSeek()>=0)return pendingSeek(); try { return ready && player != null ? player.getCurrentPosition() : 0; } catch (IllegalStateException e) { return 0; } }
    private int duration() { if(restored)return restoredDuration; try { return ready && player != null ? player.getDuration() : 0; } catch (IllegalStateException e) { return 0; } }
    private void restoreSession() {
        SharedPreferences saved=getSharedPreferences("playback",0);
        String mode=saved.getString("mode","sequential");
        if(java.util.Arrays.asList("sequential","loop","single","shuffle").contains(mode))playbackMode=mode;
        try {
            JSONObject state=new JSONObject(saved.getString("session","{}")),track=TrackState.sessionTrack(state.optJSONObject("track"));
            if(track==null)return;
            loadedTrack=track;requestedTrack=track;currentTrack=track.optString("id");title=track.optString("name");
            loadedRequestedQuality=TrackState.requestedQuality(track.optString("source"),sourceQualities);
            loadedQuality=loadedRequestedQuality;
            restoredDuration=Math.max(0,state.optInt("duration"));restoredPosition=Math.max(0,Math.min(restoredDuration,state.optInt("position")));
            lyricOffset=getSharedPreferences("lyricOffsets",0).getInt(currentTrack,0);
            restored=true;ready=true;
        } catch(Exception ignored){}
    }
    private void saveSession(boolean flush) {
        if(destroyed||busy||loadedTrack==null||!ready)return;
        try {
            JSONObject savedTrack=TrackState.sessionTrack(loadedTrack);if(savedTrack==null)return;
            JSONObject state=new JSONObject().put("track",savedTrack).put("position",position()).put("duration",duration());
            SharedPreferences.Editor editor=getSharedPreferences("playback",0).edit().putString("session",state.toString());
            if(flush)editor.commit();else editor.apply();lastSessionSave=SystemClock.elapsedRealtime();
        } catch(Exception ignored){}
    }
    void saveBeforeExit(){saveSession(true);}
    private void abandonFocus() { if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus); hasFocus = false; resumeOnFocus = false; }
    private void play() {
        if(busy)return;
        if(restored&&loadedTrack!=null){playTrack(loadedTrack,true,true);return;}
        if (!ready || player == null) return;
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) { report("暂时无法取得音频焦点，请稍后重试。"); return; }
        try { if (ended) { player.seekTo(0); ended = false; } ducked = false; applyVolume(); player.start(); playIntent = true; error = ""; }
        catch (Exception e) { report("播放失败：" + e.getMessage()); }
        refreshNotification(); update();
    }
    private void pause(boolean user) {
        playIntent = false;
        if (isPlaying()) player.pause();
        if (user) abandonFocus();
        saveSession(false); refreshNotification(); update();
    }
    private void seek(long value) {
        if(restored) { restoredPosition=(int)Math.max(0,Math.min(value,restoredDuration));saveSession(false);update();return; }
        if (!ready || player == null) return;
        try { seekPlayer(player,(int)Math.max(0,Math.min(value,duration())),seeks.resuming(player,generation),generation); ended = false;saveSession(false); }
        catch (Exception e) { report("当前音频无法跳转。"); }
        update();
    }
    private long seekPlayer(MediaPlayer mp,int target,boolean resumeLoad,int loadTicket) {
        final long request=seeks.begin(mp,target,loadTicket,resumeLoad);
        try {
            mp.setOnSeekCompleteListener(p -> {
                if(destroyed||p!=player)return;
                final int actualPosition;
                try { actualPosition=p.getCurrentPosition(); } catch(IllegalStateException ignored){return;}
                int completion=seeks.complete(player,p,request,actualPosition,generation);
                if(completion==TrackState.SeekState.IGNORED)return;
                if(completion==TrackState.SeekState.RESUME_READY)busy=false;
                saveSession(false);
                if(completion==TrackState.SeekState.RESUME_READY&&playIntent)play();else update();
            });
            mp.seekTo(target,MediaPlayer.SEEK_CLOSEST);
            if(resumeLoad)handler.postDelayed(()->{if(player==mp&&seeks.waiting(mp,request,generation)&&busy){seeks.clear();busy=false;ready=false;report("恢复播放进度超时，请点击重试。");}},10000);
            return request;
        } catch(Exception e) {
            seeks.clear();
            if(resumeLoad&&loadTicket==generation){busy=false;ready=false;}
            report("当前音频无法跳转，请点击重试。");return -1;
        }
    }
    void report(String message) { error = message; update(); }
    private String getName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        }
        return uri.getLastPathSegment();
    }
    private long declaredSize(Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).length();
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1; // Document providers may not know their size. Bound the stream instead.
    }
    private static boolean starts(byte[] h, int count, int offset, String magic) {
        if (count < offset + magic.length()) return false;
        for (int i = 0; i < magic.length(); i++) if (h[offset + i] != (byte)magic.charAt(i)) return false;
        return true;
    }
    private static boolean validHeader(String ext, byte[] h, int count) {
        int a = count > 0 ? h[0] & 255 : 0, b = count > 1 ? h[1] & 255 : 0;
        switch (ext) {
            case "mp3": return starts(h,count,0,"ID3") || (a == 255 && (b & 0xe0) == 0xe0 && (b & 6) != 0);
            case "aac": return starts(h,count,0,"ID3") || (a == 255 && (b & 0xf6) == 0xf0);
            case "wav": return (starts(h,count,0,"RIFF") || starts(h,count,0,"RF64")) && starts(h,count,8,"WAVE");
            case "flac": return starts(h,count,0,"fLaC");
            case "ogg": return starts(h,count,0,"OggS");
            case "m4a": return starts(h,count,4,"ftyp");
            default: return false;
        }
    }
    private void importAudio(Uri uri) { importAudio(uri, null); }
    private void importAudio(Uri uri, DragAndDropPermissions grant) {
        if (busy) { if (grant != null) grant.release(); report("正在导入，请稍候。"); return; }
        if (!("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) { if (grant != null) grant.release(); report("仅支持本地文件。"); return; }
        busy = true; importingAudio = true; playIntent = false; error = ""; int ticket = ++generation; update();
        importer.execute(() -> {
            File copied = null;
            try {
                String name = getName(uri);
                if (name == null) throw new IOException("无法获取文件名。");
                int dot = name.lastIndexOf('.'); String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!java.util.Arrays.asList("mp3","wav","flac","ogg","m4a","aac").contains(ext)) throw new IOException("仅支持 MP3、WAV、FLAC、OGG、M4A、AAC。");
                if (declaredSize(uri) > MAX_BYTES) throw new IOException("文件超过 30 MiB（31,457,280 字节）。");
                File folder = new File(getFilesDir(), "imports");
                if (!folder.exists() && !folder.mkdirs()) throw new IOException("无法创建导入目录。");
                copied = new File(folder, UUID.randomUUID().toString() + "." + ext);
                try (InputStream input = getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(copied)) {
                    if (input == null) throw new IOException("无法读取文件。");
                    byte[] header = new byte[16]; int headerCount = 0;
                    while (headerCount < header.length) { int n = input.read(header, headerCount, header.length - headerCount); if (n < 0) break; headerCount += n; }
                    if (!validHeader(ext, header, headerCount)) throw new IOException("文件内容与音频格式不匹配，或文件已损坏。");
                    output.write(header, 0, headerCount); long total = headerCount;
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消。");
                        total += n; if (total > MAX_BYTES) throw new IOException("读取时发现文件超过 30 MiB。");
                        output.write(buffer, 0, n);
                    }
                }
                File complete = copied;
                handler.post(() -> {
                    if (destroyed || ticket != generation) { complete.delete(); return; }
                    try {
                        JSONObject track = new JSONObject().put("id","local:" + complete.getName()).put("source","local")
                            .put("name",name).put("artist","本地文件").put("path",complete.getAbsolutePath());
                        prepare(complete.getAbsolutePath(), track, false, true);
                    } catch (Exception e) { complete.delete(); busy=false; importingAudio=false; report("导入失败。"); }
                });
            } catch (Exception e) {
                if (copied != null) copied.delete();
                handler.post(() -> { if (!destroyed && ticket == generation) { busy = false; importingAudio=false; report("导入失败：" + e.getMessage()); } });
            } finally {
                if (grant != null) grant.release();
            }
        });
    }
    private void playTrack(String id) {
        for(int i=0;i<queue.length();i++) {
            JSONObject track=queue.optJSONObject(i);
            if(track!=null && id.equals(track.optString("id"))) {playTrack(track,true,false);return;}
        }
    }
    private void playTrack(JSONObject track, boolean autoplay, boolean preserve) {
        playTrack(track,autoplay,preserve,false,-1);
    }
    private void playTrack(JSONObject rawTrack, boolean autoplay, boolean preserve, boolean continueSources, int resumePosition) {
        if(busy)return;
        final JSONObject track=TrackState.normalize(rawTrack);
        if(track==null){report("歌曲来源或标识无效，请重新搜索或导入。");return;}
        saveSession(false);
        requestedTrack=track;
        if(!continueSources)attemptedSources.clear();
        if(!continueSources)playIntent=autoplay;
        busy=true; error=""; final int ticket=++generation; update();
        final String platform=track.optString("source"),requestedQuality=TrackState.requestedQuality(platform,sourceQualities);
        if(!TrackState.online(platform)) {prepare(track.optString("path"),track,autoplay,false,preserve,requestedQuality,null,resumePosition);return;}
        final String endpoint=apiBase;
        final java.util.Set<String> excluded=new java.util.HashSet<>(attemptedSources);
        handler.postDelayed(() -> {if(!destroyed && ticket==generation && busy && pending==null){generation++;audioResolver.cancel();busy=false;report("获取音频地址超时，请检查网络后重试。");}},25000);
        importer.execute(() -> {
            AudioResolver.Result result=null; String failure="";
            try {
                result=audioResolver.resolve(platform,track.optString("songId"),requestedQuality,endpoint,excluded,()->!destroyed&&ticket==generation,
                    TrackState.durationSeconds(track.opt("duration")));
            } catch(IOException e){failure=e.getMessage();}
              catch(Exception e){failure="获取音频地址失败，请检查网络后重试。";}
            final AudioResolver.Result resolved=result; final String message=failure;
            handler.post(() -> {if(destroyed||ticket!=generation)return;
                if(!message.isEmpty()){busy=false;report(message);}
                else if(resolved!=null){attemptedSources.add(resolved.sourceId);prepare(resolved.address,track,autoplay,false,preserve,requestedQuality,resolved,resumePosition);}
            });
        });
    }
    private boolean tryNextAudioSource(JSONObject track, boolean autoplay, boolean preserve, int resumePosition) {
        if(track==null||requestedTrack==null||!track.optString("id").equals(requestedTrack.optString("id"))
            ||attemptedSources.isEmpty()||!audioResolver.hasAudioBackups(track.optString("source"),apiBase)
            ||attemptedSources.size()>=audioResolver.fallbackLimit(track.optString("source"),TrackState.requestedQuality(track.optString("source"),sourceQualities)))return false;
        cancelPending();playTrack(track,autoplay,preserve,true,resumePosition);return true;
    }
    private void step(int delta, boolean automatic) {
        if(busy)return;
        if(automatic&&playbackMode.equals("single")&&loadedTrack!=null){playTrack(loadedTrack,true,false);return;}
        if(busy||queue.length()==0)return;
        int index=-1;
        for(int i=0;i<queue.length();i++) if(queue.optJSONObject(i)!=null&&currentTrack.equals(queue.optJSONObject(i).optString("id"))) index=i;
        if(automatic&&(index<0||(playbackMode.equals("sequential")&&index+1>=queue.length())))return;
        int next=index<0?(delta>0?0:queue.length()-1):(index+delta+queue.length())%queue.length();
        if(playbackMode.equals("shuffle")&&queue.length()>1){next=ThreadLocalRandom.current().nextInt(queue.length()-(index>=0?1:0));if(index>=0&&next>=index)next++;}
        playTrack(queue.optJSONObject(next).optString("id"));
    }
    private void prepare(String source, JSONObject track, boolean autoplay, boolean importing) {
        prepare(source,track,autoplay,importing,false,quality);
    }
    private void prepare(String source, JSONObject track, boolean autoplay, boolean importing, boolean preserve, String requestedQuality) {
        prepare(source,track,autoplay,importing,preserve,requestedQuality,null,-1);
    }
    private void prepare(String source, JSONObject track, boolean autoplay, boolean importing, boolean preserve, String requestedQuality, AudioResolver.Result resolved, int resumePosition) {
        try {
            final int ticket=generation;
            pending = new MediaPlayer(); pendingFile=importing?new File(source):null;
            pending.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            pending.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            pending.setDataSource(source);
            pending.setOnPreparedListener(mp -> {
                if (destroyed || pending != mp || ticket!=generation) return;
                // Reject renamed MP4 videos that passed the MP4 container signature check.
                boolean hasAudio = false, hasVideo = false;
                for (MediaPlayer.TrackInfo mediaTrack : mp.getTrackInfo()) {
                    hasAudio |= mediaTrack.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO;
                    hasVideo |= mediaTrack.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO;
                }
                if (!hasAudio || hasVideo) {if(tryNextAudioSource(track,playIntent,preserve,resumePosition))return;cancelPending(); report("请选择纯音频文件，不支持视频。"); return; }
                String platform=track.optString("source");
                double independentDuration=TrackState.durationSeconds(track.opt("duration"));
                double targetDuration=resolved==null?independentDuration:resolved.expectedDuration;
                if(!TrackState.decodedDurationValid(platform,mp.getDuration()/1000.0,targetDuration,independentDuration>0)) {
                    if(tryNextAudioSource(track,playIntent,preserve,resumePosition))return;
                    cancelPending();report(TrackState.sourceName(platform)+"音频时长与目标歌曲不符或缺少目标时长，已拒绝播放；请重试、更换音质或尝试其他歌曲。");return;
                }
                final int resume=TrackState.replacementPosition(preserve,resumePosition,position());
                seeks.clear();
                if (player != null) player.release();
                if(!resumeOnFocus)abandonFocus(); player = mp; pending = null; pendingFile = null;
                currentTrack=track.optString("id"); loadedTrack=track; requestedTrack=track; loadedRequestedQuality=requestedQuality;
                loadedQuality=resolved==null?"standard":resolved.quality;qualityNotice=resolved==null?"":resolved.notice;
                loadedSource=resolved==null?"":resolved.sourceName;loadedSourceId=resolved==null?"":resolved.sourceId;loadedBitrate=resolved==null?0:resolved.bitrate;
                lastPosition=resume;
                restored=false;
                lyricOffset=getSharedPreferences("lyricOffsets",0).getInt(currentTrack,0);
                actualFormat=verifiedAudioDescription(mp,resolved);
                if(importing) { importedTrack=track; queue.put(track); getSharedPreferences("queue",MODE_PRIVATE).edit().putString("imported",track.toString()).apply(); }
                importingAudio=false;
                title = track.optString("name"); ready = true; busy = resume>0; ended = false; error = ""; applyVolume();
                player.setOnCompletionListener(p -> { ended = true; abandonFocus(); refreshNotification(); update(); step(1,true); });
                player.setOnErrorListener((p, what, extra) -> {
                    if(p!=player||destroyed)return true;
                    final int resumeAt=pendingSeek()>=0?pendingSeek():lastPosition;
                    ready=false;seeks.clear();if(!resumeOnFocus)abandonFocus();
                    if(ticket!=generation&&busy){refreshNotification();update();return true;}
                    busy=false;
                    if(tryNextAudioSource(loadedTrack,playIntent,false,resumeAt))return true;
                    report("音频播放失败（"+what+"/"+extra+"），"+TrackState.retryHint(track.optString("source")));
                    refreshNotification();return true;
                });
                session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
                if(resume>0) {
                    seekPlayer(player,Math.min(resume,Math.max(0,duration()-1)),true,ticket);
                }
                refreshOutputs();refreshNotification();update();
                if(resume<=0) {saveSession(false);if(playIntent)play();}
            });
            pending.setOnErrorListener((mp, what, extra) -> {
                if(mp!=pending||destroyed||ticket!=generation)return true;
                if(tryNextAudioSource(track,playIntent,preserve,resumePosition))return true;
                cancelPending();report("无法加载音频（"+what+"/"+extra+"），"+TrackState.retryHint(track.optString("source")));return true;
            });
            final MediaPlayer preparing=pending;
            handler.postDelayed(() -> {if(pending==preparing){if(tryNextAudioSource(track,playIntent,preserve,resumePosition))return;cancelPending();report("音频加载超时，"+TrackState.retryHint(track.optString("source")));}},20000);
            pending.prepareAsync();
        } catch (Exception e) { if(tryNextAudioSource(track,playIntent,preserve,resumePosition))return;cancelPending();report("无法加载音频，"+TrackState.retryHint(track.optString("source"))); }
    }
    private String verifiedAudioDescription(MediaPlayer mp,AudioResolver.Result resolved) {
        String codec=resolved==null?"":resolved.format;
        int rate=resolved==null?0:resolved.sampleRate,bits=resolved==null?0:resolved.bitsPerSample,bitrate=resolved==null?0:resolved.bitrate;
        try {
            for(MediaPlayer.TrackInfo info:mp.getTrackInfo()) {
                if(info.getTrackType()!=MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO||info.getFormat()==null)continue;
                MediaFormat format=info.getFormat();
                if(codec.isEmpty()&&format.containsKey(MediaFormat.KEY_MIME)) {
                    String mime=format.getString(MediaFormat.KEY_MIME);
                    codec="audio/mpeg".equals(mime)?"MP3":"audio/flac".equals(mime)?"FLAC":
                        "audio/vorbis".equals(mime)?"Vorbis":"audio/mp4a-latm".equals(mime)?"AAC":"audio/raw".equals(mime)?"PCM":mime;
                }
                if(format.containsKey(MediaFormat.KEY_SAMPLE_RATE)&&format.getInteger(MediaFormat.KEY_SAMPLE_RATE)>0)rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if(format.containsKey(MediaFormat.KEY_BIT_RATE)&&format.getInteger(MediaFormat.KEY_BIT_RATE)>0)bitrate=format.getInteger(MediaFormat.KEY_BIT_RATE)/1000;
                break;
            }
        } catch(Exception ignored){}
        loadedBitrate=bitrate;
        java.util.List<String> specs=new java.util.ArrayList<>();
        if(codec!=null&&!codec.isEmpty())specs.add(codec);
        if(bits>0)specs.add(bits+" bit");if(rate>0)specs.add(String.format(Locale.ROOT,"%.3g kHz",rate/1000.0));if(bitrate>0)specs.add(bitrate+" kbps");
        return specs.isEmpty()?"规格待系统解码确认":String.join(" · ",specs);
    }
    private void cancelPending() { if (pending != null) { pending.release(); pending = null; } if (pendingFile != null) { pendingFile.delete(); pendingFile = null; } busy = false; importingAudio=false; }
    private PendingIntent action(String name, int id) { return PendingIntent.getService(this, id, new Intent(this, PlaybackService.class).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(isPlaying() ? "正在播放 · 点击打开浮音" : "浮音播放器 · 点击打开")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play, isPlaying() ? "暂停" : "播放", action("toggle", 1)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "下一首", action("next", 3)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "退出", action("stop", 2)).build());
        if (session != null) builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1));
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
    private void refreshNotification() { if (!destroyed) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification()); }
    private static String clock(int ms) { int seconds = ms / 1000; return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private void applyVolume() {
        if (player != null) { float gain = volume / 100f * (ducked ? 0.25f : 1f); player.setVolume(gain, gain); }
    }
    private void refreshOutputs() {
        AudioDeviceInfo found = null;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (Integer.toString(device.getId()).equals(selectedOutput)) found = device;
        if (!selectedOutput.isEmpty() && found == null) selectedOutput = "";
        if (player != null && !player.setPreferredDevice(found)) report("系统未接受该输出，请选择其他设备或系统默认。");
        update();
    }
    void update() {
        if (destroyed) return;
        boolean playing = isPlaying(); int pos = position(), length = duration();
        if(ready&&!restored&&player!=null&&pendingSeek()<0)lastPosition=pos;
        String platform=loadedTrack==null?"":loadedTrack.optString("source"),sourceName=TrackState.sourceName(platform);
        String qualityPlatform=busy&&requestedTrack!=null?requestedTrack.optString("source"):platform;
        String status = busy ? "正在导入 / 加载音频…" : playing ? "正在播放 · "+("local".equals(platform)?"本地音频":sourceName) : restored ? "已恢复上次播放 · 点击继续" : ready ? "已就绪 · 点击播放" : "从歌单选择或导入音乐";
        try {
            JSONObject o = new JSONObject(); o.put("title", title); o.put("status", status); o.put("error", error);
            o.put("position", pos); o.put("duration", length); o.put("playing", playing); o.put("ready", ready); o.put("busy", busy); o.put("overlayAllowed", Settings.canDrawOverlays(this));
            o.put("volume", volume); o.put("selectedOutput", selectedOutput);
            o.put("currentTrack",currentTrack);o.put("loadedTrack",loadedTrack);o.put("loadedQuality",loadedQuality);
            o.put("sourceQualities",new JSONObject(sourceQualities.toString()));o.put("quality",quality);o.put("requestedQuality",loadedRequestedQuality);
            o.put("requestedTrack",requestedTrack);
            o.put("currentSourceName",sourceName);o.put("qualitySelectable",TrackState.qualitySelectable(qualityPlatform));
            o.put("playbackMode",playbackMode);o.put("lyricOffset",lyricOffset);
            o.put("qualityInfo",TrackState.online(platform)
                ?restored?sourceName+" · 偏好："+AudioResolver.qualityName(TrackState.requestedQuality(platform,sourceQualities))+"\n播放后显示实际音质。"
                :(loadedSource.isEmpty()?sourceName:loadedSource)+" · 请求："+AudioResolver.qualityName(loadedRequestedQuality)+"\n实际："+actualFormat
                    +(qualityNotice.isEmpty()?"":"\n"+qualityNotice)
                :"音质选项用于在线歌曲；本地文件保持原格式。"); if(importedTrack!=null)o.put("importedTrack",importedTrack);
            org.json.JSONArray outputs = new org.json.JSONArray();
            outputs.put(new JSONObject().put("id", "").put("name", "跟随系统默认"));
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                outputs.put(new JSONObject().put("id", Integer.toString(device.getId())).put("name", device.getProductName().toString() + " · " + device.getId()));
            o.put("outputs", outputs);
            AudioDeviceInfo routed = player != null && ready ? player.getRoutedDevice() : null;
            o.put("outputName", routed == null ? "待播放后确认实际输出" : routed.getProductName().toString());
            PlayerBridge.publish(o);
            if(overlay!=null)overlay.update(o);
        } catch (Exception ignored) {}
        if (session != null) session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : ready ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_NONE, pos, playing ? 1f : 0f).build());
    }
    void syncOverlay() { if(overlay!=null)overlay.syncVisibility(); }
    void refreshOverlayData() { if(overlay!=null)overlay.refreshData(PlayerBridge.uiData()); }
    private void showOverlay() {
        if(!Settings.canDrawOverlays(this)){report("请先允许浮音显示悬浮窗。");return;}
        getSharedPreferences("window",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();
        if(overlay!=null)overlay.show();
    }
    void openPicker() {
        PlayerBridge.picking=true;syncOverlay();
        try {startActivity(new Intent(this,PlayerActivity.class).putExtra("pickFromOverlay",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
        catch(Exception e){PlayerBridge.picking=false;syncOverlay();report("无法打开文件选择器，请重新打开浮音后再试。");}
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if(overlay!=null)overlay.configurationChanged();
    }
    @Override public void onDestroy() {
        saveSession(true);
        destroyed=true; audio.unregisterAudioDeviceCallback(deviceCallback); generation++; audioResolver.cancel(); importer.shutdownNow(); handler.removeCallbacks(ticker);
        if(overlay!=null){overlay.close();overlay=null;}
        if(player != null) { player.release(); player=null; }
        cancelPending(); abandonFocus();
        if(session != null) session.release(); try { unregisterReceiver(noisy); } catch(Exception ignored) {}
        stopForeground(STOP_FOREGROUND_REMOVE); instance=null; PlayerBridge.idle(""); super.onDestroy();
    }
}

