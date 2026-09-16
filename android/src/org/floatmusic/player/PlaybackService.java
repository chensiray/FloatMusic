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
import java.net.HttpURLConnection;
import java.net.URL;
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
    private boolean hasFocus = false, resumeOnFocus = false, ready = false, busy = false, ended = false, destroyed = false;
    private String title = "还没有导入音乐", error = "";
    private File pendingFile;
    private WindowManager windows;
    private WindowManager.LayoutParams windowParams;
    private View panel;
    private boolean expanded = false;
    private TextView overlayTitle, overlayTime, overlayError;
    private Button overlayPlay;
    private SeekBar overlayProgress;
    private JSONArray queue = new JSONArray();
    private JSONObject importedTrack = null;
    private String currentTrack = "", apiBase = "";
    private int volume = 70;
    private boolean ducked = false;
    private String selectedOutput = "";
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refreshOutputs(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refreshOutputs(); }
    };
    private int generation = 0;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(true); }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() { if (destroyed) return; update(); handler.postDelayed(this, 250); }
    };
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        audio = (AudioManager)getSystemService(AUDIO_SERVICE);
        volume = getSharedPreferences("audio",MODE_PRIVATE).getInt("volume",70);
        windows = (WindowManager)getSystemService(WINDOW_SERVICE);
        try {
            JSONObject saved = new JSONObject(getSharedPreferences("queue",MODE_PRIVATE).getString("data","{}"));
            queue = saved.optJSONArray("tracks"); if (queue == null) queue = new JSONArray();
            apiBase = saved.optString("api");
            String imported=getSharedPreferences("queue",MODE_PRIVATE).getString("imported","");
            if(!imported.isEmpty())importedTrack=new JSONObject(imported);
        } catch (Exception ignored) { queue = new JSONArray(); }
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = true; ducked = false; applyVolume();
                if (resumeOnFocus) { resumeOnFocus = false; play(); }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                ducked = true; applyVolume();
            } else {
                boolean resume = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && isPlaying();
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
        if (getSharedPreferences("window",MODE_PRIVATE).getBoolean("enabled",true) && Settings.canDrawOverlays(this)) showOverlay();
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
            case "volume": volume=Math.max(0,Math.min(100,Integer.parseInt(value))); getSharedPreferences("audio",MODE_PRIVATE).edit().putInt("volume",volume).apply(); applyVolume(); update(); break;
            case "output": selectedOutput=value; refreshOutputs(); break;
            case "outputs": refreshOutputs(); break;
            case "queue":
                try { JSONObject data = new JSONObject(value); queue = data.getJSONArray("tracks"); apiBase = data.optString("api");
                    getSharedPreferences("queue",MODE_PRIVATE).edit().putString("data",value).apply();
                } catch (Exception e) { report("歌单同步失败。"); } break;
            case "track": playTrack(value); break;
            case "previous": step(-1, false); break;
            case "next": step(1, false); break;
            case "ackImport": importedTrack = null; getSharedPreferences("queue",MODE_PRIVATE).edit().remove("imported").apply(); break;
            case "float": showOverlay(); break;
            case "import": importAudio(Uri.parse(value)); break;
            case "toggle": if (isPlaying()) pause(true); else play(); break;
            case "seek": try { seek(Long.parseLong(value)); } catch (Exception ignored) {} break;
            case "stop": stopSelf(); break;
        }
    }
    private boolean isPlaying() { try { return ready && player != null && player.isPlaying(); } catch (IllegalStateException e) { return false; } }
    private int position() { try { return ready && player != null ? player.getCurrentPosition() : 0; } catch (IllegalStateException e) { return 0; } }
    private int duration() { try { return ready && player != null ? player.getDuration() : 0; } catch (IllegalStateException e) { return 0; } }
    private void abandonFocus() { if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus); hasFocus = false; resumeOnFocus = false; }
    private void play() {
        if (!ready || player == null) return;
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) { report("暂时无法取得音频焦点，请稍后重试。"); return; }
        try { if (ended) { player.seekTo(0); ended = false; } ducked = false; applyVolume(); player.start(); error = ""; }
        catch (Exception e) { report("播放失败：" + e.getMessage()); }
        refreshNotification(); update();
    }
    private void pause(boolean user) {
        if (isPlaying()) player.pause();
        if (user) abandonFocus();
        refreshNotification(); update();
    }
    private void seek(long value) {
        if (!ready || player == null) return;
        try { player.seekTo(Math.max(0, Math.min(value, duration())), MediaPlayer.SEEK_CLOSEST); ended = false; }
        catch (Exception e) { report("当前音频无法跳转。"); }
        update();
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
        busy = true; error = ""; int ticket = ++generation; update();
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
                    } catch (Exception e) { complete.delete(); busy=false; report("导入失败。"); }
                });
            } catch (Exception e) {
                if (copied != null) copied.delete();
                handler.post(() -> { if (!destroyed && ticket == generation) { busy = false; report("导入失败：" + e.getMessage()); } });
            } finally {
                if (grant != null) grant.release();
            }
        });
    }
    private void playTrack(String id) {
        if (busy) return;
        for (int i=0;i<queue.length();i++) {
            JSONObject track=queue.optJSONObject(i);
            if (track != null && id.equals(track.optString("id"))) {
                busy=true; error=""; int ticket=++generation; update();
                if (!"netease".equals(track.optString("source"))) { prepare(track.optString("path"),track,true,false); return; }
                final String endpoint=apiBase;
                importer.execute(() -> {
                    String address="", failure="";
                    HttpURLConnection connection=null;
                    try {
                        if (endpoint.isEmpty()) throw new IOException("请先设置网易云 API 地址。");
                        connection=(HttpURLConnection)new URL(endpoint+"/song/url/v1?id="+java.net.URLEncoder.encode(track.optString("songId"),"UTF-8")+"&level=standard").openConnection();
                        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                        try(InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                            byte[] buffer=new byte[8192]; int n;
                            while((n=input.read(buffer))!=-1) { output.write(buffer,0,n); if(output.size()>2*1024*1024) throw new IOException("API 响应过大。"); }
                            JSONObject response=new JSONObject(output.toString("UTF-8"));
                            if(response.optInt("code")!=200) throw new IOException("API 请求失败。");
                            JSONObject song=response.getJSONArray("data").getJSONObject(0);
                            address=song.isNull("url") ? "" : song.optString("url");
                            if(!(address.startsWith("https://")||address.startsWith("http://"))) throw new IOException("歌曲暂无播放地址，可能受版权或账号权限限制。");
                        }
                    } catch(Exception e) { failure=e.getMessage(); }
                    finally { if(connection!=null) connection.disconnect(); }
                    final String result=address, message=failure;
                    handler.post(() -> { if(destroyed||ticket!=generation)return;
                        if(!message.isEmpty()) { busy=false; report(message); }
                        else prepare(result,track,true,false);
                    });
                }); return;
            }
        }
    }
    private void step(int delta, boolean automatic) {
        if(busy||queue.length()==0)return;
        int index=-1;
        for(int i=0;i<queue.length();i++) if(currentTrack.equals(queue.optJSONObject(i).optString("id"))) index=i;
        if(automatic&&(index<0||index+1>=queue.length()))return;
        int next=index<0?(delta>0?0:queue.length()-1):(index+delta+queue.length())%queue.length();
        playTrack(queue.optJSONObject(next).optString("id"));
    }
    private void prepare(String source, JSONObject track, boolean autoplay, boolean importing) {
        try {
            pending = new MediaPlayer(); pendingFile=importing?new File(source):null;
            pending.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            pending.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            pending.setDataSource(source);
            pending.setOnPreparedListener(mp -> {
                if (destroyed || pending != mp) return;
                // Reject renamed MP4 videos that passed the MP4 container signature check.
                boolean hasAudio = false, hasVideo = false;
                for (MediaPlayer.TrackInfo mediaTrack : mp.getTrackInfo()) {
                    hasAudio |= mediaTrack.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO;
                    hasVideo |= mediaTrack.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO;
                }
                if (!hasAudio || hasVideo) { cancelPending(); report("请选择纯音频文件，不支持视频。"); return; }
                if (player != null) player.release();
                abandonFocus(); player = mp; pending = null; pendingFile = null;
                currentTrack=track.optString("id");
                if(importing) { importedTrack=track; queue.put(track); getSharedPreferences("queue",MODE_PRIVATE).edit().putString("imported",track.toString()).apply(); }
                title = track.optString("name"); ready = true; busy = false; ended = false; error = ""; applyVolume(); refreshOutputs();
                player.setOnCompletionListener(p -> { ended = true; abandonFocus(); refreshNotification(); update(); step(1,true); });
                player.setOnErrorListener((p, what, extra) -> { ready = false; abandonFocus(); report("音频解码失败，请换一个文件。"); refreshNotification(); return true; });
                session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
                refreshNotification(); update();
                if(autoplay)play();
            });
            pending.setOnErrorListener((mp, what, extra) -> { cancelPending(); report("无法解码此音频，请尝试其他文件。"); return true; });
            pending.prepareAsync();
        } catch (Exception e) { cancelPending(); report("无法加载音频：" + e.getMessage()); }
    }
    private void cancelPending() { if (pending != null) { pending.release(); pending = null; } if (pendingFile != null) { pendingFile.delete(); pendingFile = null; } busy = false; }
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
        String status = busy ? "正在导入 / 加载音频…" : playing ? (currentTrack.startsWith("netease:")?"正在播放 · 网易云":"正在播放 · 本地音频") : ready ? "已就绪 · 点击播放" : "从歌单选择或导入音乐";
        try {
            JSONObject o = new JSONObject(); o.put("title", title); o.put("status", status); o.put("error", error);
            o.put("position", pos); o.put("duration", length); o.put("playing", playing); o.put("ready", ready); o.put("busy", busy); o.put("overlayAllowed", Settings.canDrawOverlays(this));
            o.put("volume", volume); o.put("selectedOutput", selectedOutput);
            o.put("currentTrack",currentTrack); if(importedTrack!=null)o.put("importedTrack",importedTrack);
            org.json.JSONArray outputs = new org.json.JSONArray();
            outputs.put(new JSONObject().put("id", "").put("name", "跟随系统默认"));
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                outputs.put(new JSONObject().put("id", Integer.toString(device.getId())).put("name", device.getProductName().toString() + " · " + device.getId()));
            o.put("outputs", outputs);
            AudioDeviceInfo routed = player != null && ready ? player.getRoutedDevice() : null;
            o.put("outputName", routed == null ? "待播放后确认实际输出" : routed.getProductName().toString());
            PlayerBridge.publish(o);
        } catch (Exception ignored) {}
        if(expanded && overlayTitle!=null) {
            overlayTitle.setText(title); overlayTime.setText(clock(pos)+" / "+clock(length)); overlayError.setText(error);
            overlayPlay.setText(playing?"暂停":"播放"); overlayPlay.setEnabled(ready);
            overlayProgress.setMax(Math.max(1,length)); if(!overlayProgress.isPressed())overlayProgress.setProgress(pos);
        }
        if (session != null) session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : ready ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_NONE, pos, playing ? 1f : 0f).build());
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void syncOverlay() {
        if (panel == null) return;
        panel.setVisibility(PlayerBridge.foreground || PlayerBridge.picking ? View.GONE : View.VISIBLE);
    }
    private void showOverlay() {
        if (!Settings.canDrawOverlays(this)) { report("请先在应用内授予悬浮窗权限。"); return; }
        getSharedPreferences("window",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();
        if (panel != null) { syncOverlay(); return; }
        TextView icon = new TextView(this); icon.setText("♪"); icon.setTextSize(30); icon.setTextColor(Color.rgb(24,44,39)); icon.setGravity(Gravity.CENTER); panel=icon; expanded=false;
        panel.setContentDescription("浮音悬浮图标，点击打开，长按拖动");
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(152,228,194)); background.setCornerRadius(dp(28)); panel.setBackground(background);
        windowParams = new WindowManager.LayoutParams(dp(56), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.LEFT;
        android.content.SharedPreferences prefs = getSharedPreferences("window", MODE_PRIVATE);
        windowParams.x = prefs.getInt("x", dp(12)); windowParams.y = prefs.getInt("y", dp(80));
        panel.setOnClickListener(v -> expandOverlay());
        installDrag(panel);
        try { clampWindow(); windows.addView(panel,windowParams); syncOverlay(); }
        catch (Exception e) { panel=null; report("无法显示悬浮图标："+e.getMessage()); }
    }
    private TextView label(String text) {
        TextView view=new TextView(this); view.setText(text); view.setTextColor(Color.WHITE); view.setTextSize(14); return view;
    }
    private Button button(String text, Runnable action) { Button b=new Button(this); b.setText(text); b.setTextSize(12); b.setOnClickListener(v->action.run()); return b; }
    private void openMain(boolean pick) {
        try { startActivity(new Intent(this,PlayerActivity.class).putExtra("pickFromOverlay",pick).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch(Exception e) { report("请从通知打开应用。"); }
    }
    private void expandOverlay() {
        if(panel!=null)windows.removeView(panel);
        expanded=true;
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(10),dp(8),dp(10),dp(8));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.rgb(24,31,43)); bg.setCornerRadius(dp(16)); content.setBackground(bg);
        TextView handle=label("浮音 · 拖动这里移动"); handle.setPadding(0,dp(8),0,dp(8)); content.addView(handle); installDrag(handle);
        LinearLayout top=new LinearLayout(this);
        top.addView(button("主界面",()->openMain(false)),new LinearLayout.LayoutParams(0,dp(44),1));
        top.addView(button("收起",()->{ windows.removeView(panel); panel=null; expanded=false; showOverlay(); }),new LinearLayout.LayoutParams(0,dp(44),1));
        top.addView(button("退出",()->stopSelf()),new LinearLayout.LayoutParams(0,dp(44),1)); content.addView(top);
        overlayTitle=label(title); overlayTitle.setMaxLines(2); content.addView(overlayTitle);
        overlayProgress=new SeekBar(this); content.addView(overlayProgress); overlayTime=label("");content.addView(overlayTime);
        overlayProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){} public void onProgressChanged(SeekBar b,int v,boolean user){}
            public void onStopTrackingTouch(SeekBar b){seek(b.getProgress());}
        });
        LinearLayout controls=new LinearLayout(this);
        controls.addView(button("上一首",()->step(-1,false)),new LinearLayout.LayoutParams(0,dp(48),1));
        overlayPlay=button("播放",()->dispatch("toggle","")); controls.addView(overlayPlay,new LinearLayout.LayoutParams(0,dp(48),1));
        controls.addView(button("下一首",()->step(1,false)),new LinearLayout.LayoutParams(0,dp(48),1));content.addView(controls);
        content.addView(button("导入音乐",()->openMain(true)));
        content.addView(label("音量")); SeekBar gain=new SeekBar(this); gain.setMax(100);gain.setProgress(volume);content.addView(gain);
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
            public void onProgressChanged(SeekBar b,int v,boolean user){if(user)dispatch("volume",Integer.toString(v));}
        });
        content.addView(label("悬浮页面大小")); SeekBar size=new SeekBar(this);size.setMax(120);size.setProgress(getSharedPreferences("window",0).getInt("width",320)-260);content.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){getSharedPreferences("window",0).edit().putInt("width",260+b.getProgress()).apply();}
            public void onProgressChanged(SeekBar b,int v,boolean user){if(user){windowParams.width=Math.min(dp(260+v),getResources().getDisplayMetrics().widthPixels);clampWindow();moveWindow();}}
        });
        content.addView(label("不透明度"));SeekBar opacity=new SeekBar(this);opacity.setMax(60);opacity.setProgress(getSharedPreferences("window",0).getInt("opacity",100)-40);content.addView(opacity);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){getSharedPreferences("window",0).edit().putInt("opacity",40+b.getProgress()).apply();}
            public void onProgressChanged(SeekBar b,int v,boolean user){if(user){windowParams.alpha=(40+v)/100f;moveWindow();}}
        });
        overlayError=label(error);overlayError.setTextColor(Color.rgb(255,181,170));content.addView(overlayError);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);panel=scroll;
        windowParams.width=Math.min(dp(getSharedPreferences("window",0).getInt("width",320)),getResources().getDisplayMetrics().widthPixels);
        windowParams.height=Math.min(dp(560),getResources().getDisplayMetrics().heightPixels-dp(100));
        windowParams.alpha=getSharedPreferences("window",0).getInt("opacity",100)/100f;
        try {clampWindow();windows.addView(panel,windowParams);syncOverlay();update();}catch(Exception e){panel=null;expanded=false;report("无法打开悬浮页面。");}
    }
    private void installDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int x, y; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) { startX=event.getRawX(); startY=event.getRawY(); x=windowParams.x; y=windowParams.y; moved=false; return true; }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx=event.getRawX()-startX, dy=event.getRawY()-startY;
                    if (Math.abs(dx)+Math.abs(dy)>ViewConfiguration.get(PlaybackService.this).getScaledTouchSlop()) moved=true;
                    if (moved) { windowParams.x=x+(int)dx; windowParams.y=y+(int)dy; clampWindow(); moveWindow(); } return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!moved) v.performClick();
                    else getSharedPreferences("window",MODE_PRIVATE).edit().putInt("x",windowParams.x).putInt("y",windowParams.y).apply();
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });
    }
    private void clampWindow() {
        int width=getResources().getDisplayMetrics().widthPixels, height=getResources().getDisplayMetrics().heightPixels;
        windowParams.x=Math.max(0,Math.min(windowParams.x,width-windowParams.width));
        windowParams.y=Math.max(0,Math.min(windowParams.y,height-windowParams.height-dp(48)));
    }
    private void moveWindow() { if(panel != null) try { windows.updateViewLayout(panel,windowParams); } catch(Exception e) { report("悬浮窗权限可能已被撤销。"); } }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if(panel != null) { clampWindow(); moveWindow(); }
    }
    @Override public void onDestroy() {
        destroyed=true; audio.unregisterAudioDeviceCallback(deviceCallback); generation++; importer.shutdownNow(); handler.removeCallbacks(ticker);
        if(panel != null) { try { windows.removeView(panel); } catch(Exception ignored) {} panel=null; }
        if(player != null) { player.release(); player=null; }
        cancelPending(); abandonFocus();
        if(session != null) session.release(); try { unregisterReceiver(noisy); } catch(Exception ignored) {}
        stopForeground(STOP_FOREGROUND_REMOVE); instance=null; PlayerBridge.idle(""); super.onDestroy();
    }
}

