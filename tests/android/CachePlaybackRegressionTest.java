package org.floatmusic.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated real cache/MediaPlayer/native-overlay integration; no Qt or production app data. */
public final class CachePlaybackRegressionTest extends Instrumentation {
    private PlaybackService service;
    private OfflineCache cache;
    private OverlayWindow overlay;
    private FloatingLyricsWindow floating;
    private Backdrop backdrop;
    private File artifacts;
    private int checks;
    private int foregroundCleanupCalls;
    private final StringBuilder report = new StringBuilder();
    private final AtomicInteger offlineResolves = new AtomicInteger();
    private final RejectingResolverExecutor resolverExecutor = new RejectingResolverExecutor();

    public static final class Backdrop extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            View background = new View(this);
            background.setBackgroundColor(0xffdfe5ee);
            setContentView(background);
        }
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void runOnMainSync(Runnable action) {
        Throwable[] failure = {null};
        super.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    @Override public void onStart() {
        artifacts = new File(getTargetContext().getExternalFilesDir(null), "run-" + SystemClock.uptimeMillis());
        artifacts.mkdirs();
        boolean passed = false;
        try {
            backdrop = (Backdrop)startActivitySync(new Intent(getTargetContext(), Backdrop.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runIntegration();
            passed = true;
        } catch (Throwable error) {
            report.append("FAIL cache-playback: ").append(android.util.Log.getStackTraceString(error)).append('\n');
            capture("failure");
        } finally {
            try { runOnMainSync(() -> {
                if (service != null && !(Boolean)get(service, "destroyed")) invoke(service, "releasePlayback");
                if (overlay != null) overlay.close();
                if (floating != null) floating.close();
                if (backdrop != null) backdrop.finish();
            }); } catch (Throwable error) { passed = false; report.append("FAIL cleanup: ").append(android.util.Log.getStackTraceString(error)).append('\n'); }
            if (cache != null) cache.close();
        }
        Bundle result = new Bundle();
        result.putString("stream", report.toString());
        result.putString("artifactDir", artifacts.getAbsolutePath());
        result.putInt("checks", checks);
        result.putBoolean("cachePlaybackPassed", passed);
        finish(passed ? -1 : 0, result);
    }

    private void runIntegration() throws Exception {
        final JSONObject first = track("910001", "断网后仍能播放的长曲名 · Original identity");
        final JSONObject second = track("910002", "第二首缓存音乐 · Next track");
        final JSONObject cancelled = track("910003", "准备播放时取消 · Pending cache");
        final File root = new File(getTargetContext().getFilesDir(), "cache-integration-" + SystemClock.uptimeMillis());
        OfflineCache.Listener listener = new OfflineCache.Listener() { public void changed() {} public void lyrics(String key, JSONObject track) {} };
        try (WaveServer server = new WaveServer(wave(30))) {
            OfflineCache downloading = new OfflineCache(root, (track, quality, alive) -> new JSONObject()
                    .put("address", server.address()).put("quality", quality).put("format", "WAV")
                    .put("bitrate", 256).put("sampleRate", 16000).put("bitsPerSample", 16)
                    .put("duration", 30).put("expectedDuration", 30), () -> true, listener);
            try {
                for (JSONObject track : new JSONObject[]{first, second, cancelled}) {
                    String key = downloading.enqueue(track, "lossless", !track.optString("id").equals(first.optString("id")));
                    await(() -> ready(downloading, key), "download completed " + track.optString("id"));
                    downloading.lyrics(lyrics(key));
                    check(downloading.entry(key).optString("translationState").equals("available"), "bilingual lyrics persisted " + track.optString("id"));
                }
                String standardKey = downloading.enqueue(first, "standard", true);
                await(() -> ready(downloading, standardKey), "same song standard-quality cache downloaded");
                downloading.lyrics(lyrics(standardKey));
                check(server.requests.get() == 4, "four full WAV files downloaded through HTTP including two qualities of one song");
            } finally { downloading.close(); }
        }
        // The only HTTP server is now closed; a restarted cache cannot resolve or download.
        cache = new OfflineCache(root, (track, quality, alive) -> {
            offlineResolves.incrementAndGet(); throw new java.io.IOException("Network resolver forbidden after restart");
        }, () -> false, listener);
        cache.settings(new JSONObject().put("automatic", false));
        cache.start();
        check(cache.entries().length() == 4, "cache index and files survive restart");
        check(cache.lookup(first, "hires").optString("quality").equals("lossless"), "unavailable selected quality falls back to saved actual quality");
        final String firstKey = OfflineCache.key(first, "lossless"), secondKey = OfflineCache.key(second, "lossless"), cancelKey = OfflineCache.key(cancelled, "lossless");
        final File firstFile = new File(cache.lookup(first, "hires").optString("path"));
        final File secondFile = new File(cache.lookup(second, "hires").optString("path"));
        final File cancelFile = new File(cache.lookup(cancelled, "hires").optString("path"));
        runOnMainSync(() -> prepareService(root, first, second));
        runOnMainSync(() -> {
            set(service, "historyPending", true);
            invoke(service, "playTrack", new Class<?>[]{JSONObject.class, boolean.class, boolean.class}, first, true, false);
        });
        awaitPlaying(first.optString("id"));
        runOnMainSync(() -> {
            JSONObject state = service.playbackSnapshot();
            check(state.optBoolean("cachePlayback"), "native MediaPlayer uses cache path");
            check(state.optString("loadedQuality").equals("lossless") && state.optString("requestedQuality").equals("hires"), "actual saved quality differs correctly from requested quality");
            check(state.optInt("duration") >= 29900 && state.optInt("duration") <= 30100, "real decoder reports WAV duration");
            check(state.optJSONObject("loadedTrack").optString("source").equals("netease"), "cached playback preserves online platform identity");
            check(state.optJSONObject("cachedLyrics").optJSONArray("lyricLines").length() == 6, "playback snapshot exposes persisted timed lyrics");
            check(state.optJSONObject("cachedLyrics").optString("translation").contains("Translation 2"), "playback snapshot retains translation text");
            invoke(overlay, "expand"); invoke(overlay, "toggle", new Class<?>[]{String.class}, "lyrics"); service.update();
        });
        int initial = snapshot().optInt("position");
        SystemClock.sleep(450);
        check(snapshot().optInt("position") > initial + 150, "actual MediaPlayer clock advances while offline");
        runOnMainSync(() -> service.dispatch("seek", "12000"));
        await(() -> snapshot().optInt("position") >= 11800 && snapshot().optInt("position") < 15000, "offline seek acknowledged by actual decoder");
        runOnMainSync(() -> {
            service.refreshOverlayData(); service.update();
            check(((Number)get(overlay, "activeLyric")).intValue() == 2, "native overlay follows persisted lyrics after seek");
            check(((Number)get(floating, "current")).intValue() == 2, "floating lyric window follows offline seek");
            check(findText((View)get(overlay, "window"), "缓存原文 2\nTranslation 2") != null, "native bilingual lyric row is rendered");
            JSONObject ui = (JSONObject)get(service, "libraryUi");
            check(first.optString("id").equals(ui.optString("lyricTrack")) && !ui.optBoolean("lyricsLoading"), "native lyric state is restored without Qt or network response");
        });
        capture("offline-bilingual-playback");
        testVisibleManualSave(firstKey);
        runOnMainSync(() -> {
            set(service, "sourceQualities", TrackState.sourceQualities(null, "standard"));
            service.dispatch("cachePlay", firstKey);
        });
        awaitPlaying(first.optString("id"));
        runOnMainSync(() -> {
            JSONObject state = service.playbackSnapshot();
            check("standard".equals(state.optString("requestedQuality")), "cache-manager play retains the global standard-quality preference");
            check("lossless".equals(state.optString("loadedQuality")) && firstKey.equals(state.optJSONObject("cachedLyrics").optString("key")), "cache-manager play selects the exact lossless entry when standard and lossless both exist");
            set(service, "sourceQualities", TrackState.sourceQualities(null, "hires"));
        });
        runOnMainSync(() -> service.dispatch("next", "")); awaitPlaying(second.optString("id"));
        runOnMainSync(() -> service.dispatch("previous", "")); awaitPlaying(first.optString("id"));
        runOnMainSync(() -> {
            check(service.playbackSnapshot().optJSONArray("historyEntries").length() == 2, "next/previous retain online identities in playback history");
            invoke(service, "playTrack", new Class<?>[]{JSONObject.class, boolean.class, boolean.class}, cancelled, true, false);
            check(get(service, "pending") instanceof MediaPlayer, "real MediaPlayer is preparing the third cached file");
            cache.action("remove", cancelKey);
            check(cancelFile.isFile() && cache.entry(cancelKey).optBoolean("deletePending"), "preparing file is protected by delayed deletion");
            invoke(service, "cancelPending");
            check(!cancelFile.exists() && cache.entry(cancelKey) == null, "cancelling preparation releases its pending deletion");
            check(firstFile.isFile(), "cancelling replacement preserves the playing cache file");
            cache.action("remove", firstKey);
            check(firstFile.isFile() && cache.entry(firstKey).optBoolean("deletePending"), "playing file is protected by delayed deletion");
            check(service.playbackSnapshot().optBoolean("playing"), "deleting current cache does not interrupt playback");
        });
        runOnMainSync(() -> service.dispatch("next", "")); awaitPlaying(second.optString("id"));
        check(!firstFile.exists() && cache.entry(firstKey) == null, "switching away releases the old playing file");
        captureSettings();
        final MediaPlayer[] decoder = {null};
        runOnMainSync(() -> {
            decoder[0] = (MediaPlayer)get(service, "player");
            cache.action("remove", secondKey);
            check(secondFile.isFile(), "last active cache file remains protected before service shutdown");
            invoke(service, "releasePlayback");
            check(get(service, "player") == null && get(service, "pending") == null && get(service, "offlineCache") == null, "service shutdown releases decoder and cache references");
            check((Boolean)get(cache, "closed") && ((ExecutorService)get(cache, "worker")).isShutdown(), "service shutdown closes cache executor");
            check(resolverExecutor.isShutdown(), "service shutdown closes audio resolver executor");
            check(foregroundCleanupCalls == 1, "attached service completes foreground cleanup exactly once");
        });
        boolean released = false; try { decoder[0].getCurrentPosition(); } catch (IllegalStateException expected) { released = true; }
        check(released, "actual MediaPlayer has been released");
        check(!secondFile.exists() && cache.entry(secondKey) == null, "service shutdown removes final pending deletion");
        check(offlineResolves.get() == 0 && resolverExecutor.attempts.get() == 0, "offline playback, seek and navigation attempted no cache or online audio resolver");
        report.append("PASS cache-playback: ").append(checks).append(" checks; no online resolver submissions; real WAV decode, seek, lyrics, navigation, deletion, shutdown\n");
    }

    private void prepareService(File root, JSONObject first, JSONObject second) {
        try {
            String prefix = "cache-fixture-" + SystemClock.uptimeMillis() + "-";
            Context context = new ContextWrapper(getTargetContext()) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return getTargetContext().getSharedPreferences(prefix + name, mode); }
            };
            context.getSharedPreferences("window", 0).edit().putBoolean("enabled", true).putBoolean("animationsEnabled", false)
                    .putBoolean("desktopLyricsEnabled", true).putInt("desktopLyricsRows", 3).putInt("desktopLyricsFont", 24)
                    .putFloat("desktopLyricsY", .16f).putInt("lyricMode", 2).apply();
            UiStrings.initialize(context); UiStrings.setLanguage(context, "zh_CN");
            PlayerBridge.foreground = false; PlayerBridge.picking = false;
            service = new PlaybackService();
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(service, context);
            // Service.attach normally supplies this name; stopForeground needs it
            // even though this fixture deliberately has no system service token.
            Field className = android.app.Service.class.getDeclaredField("mClassName"); className.setAccessible(true); className.set(service, PlaybackService.class.getName());
            // No real foreground service was registered. Supply only the binder
            // boundary for stopForeground; decoder/cache/window cleanup is real.
            Field manager = android.app.Service.class.getDeclaredField("mActivityManager"); manager.setAccessible(true);
            manager.set(service, java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{manager.getType()}, (proxy, method, args) -> {
                if (method.getName().equals("setServiceForeground")) { foregroundCleanupCalls++; return null; }
                throw new AssertionError("Unexpected activity-manager call: " + method.getName());
            }));
            set(service, "offlineCache", cache);
            set(service, "sourceQualities", TrackState.sourceQualities(new JSONObject().put("netease", "hires"), "hires"));
            AudioManager audio = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            set(service, "audio", audio);
            set(service, "focus", new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(change -> {}).build());
            ((ExecutorService)get(service, "importer")).shutdownNow(); set(service, "importer", resolverExecutor);
            PlaybackQueueStore store = new PlaybackQueueStore(new File(root, "fixture-playback.json"));
            check(store.replaceQueue(new JSONArray().put(first).put(second), 0), "isolated native queue initialized");
            set(service, "playbackStore", store); set(service, "requestedQueueEntry", store.currentEntryId());
            overlay = new OverlayWindow(service); floating = new FloatingLyricsWindow(service);
            set(service, "overlay", overlay); set(service, "floatingLyrics", floating);
            overlay.show();
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private void testVisibleManualSave(String key) {
        runOnMainSync(() -> {
            View button=((View)get(overlay,"window")).findViewWithTag("saveCurrentTrack");
            check(button!=null,"manual save is directly visible in the player without opening More or a long-press menu");
            check(button.isShown()&&button.isEnabled()&&button.getHeight()>=48*service.getResources().getDisplayMetrics().density,"manual save has a visible enabled touch target");
            check(!cache.entry(key).optBoolean("manual"),"current song starts as automatic cache");
            set(service,"sourceQualities",TrackState.sourceQualities(null,"lossless"));
            check(button.performClick(),"manual save button accepts a direct tap");
            check(cache.entry(key).optBoolean("manual"),"direct save promotes the actual song to a protected manual save");
            set(service,"sourceQualities",TrackState.sourceQualities(null,"hires"));
        });
        for(String language:new String[]{"zh_CN","en"})for(int mode:new int[]{1,2}){
            runOnMainSync(()->{UiStrings.setLanguage(service,language);service.getSharedPreferences("appearance",0).edit().putInt("mode",mode).apply();overlay.configurationChanged();service.update();});
            SystemClock.sleep(220);
            runOnMainSync(()->{
                View button=((View)get(overlay,"window")).findViewWithTag("saveCurrentTrack");
                android.graphics.Rect bounds=new android.graphics.Rect();
                check(button.isShown()&&button.getGlobalVisibleRect(bounds)&&bounds.width()>0&&bounds.height()>0,"manual save remains on screen "+language+" mode "+mode);
                check(button instanceof TextView&&((TextView)button).getText().toString().equals(UiStrings.text("保存到本地")),"manual save has a localized visible label "+language+" mode "+mode);
                JSONObject noSong=new JSONObject();overlay.update(noSong);check(!button.isEnabled(),"manual save disabled without current song");
                JSONObject local=service.playbackSnapshot();try{local.put("loadedTrack",new JSONObject().put("id","local:fixture").put("source","local"));}catch(Exception e){throw new RuntimeException(e);}overlay.update(local);check(!button.isEnabled(),"imported local audio needs no duplicate cache save");
                service.update();check(button.isEnabled(),"manual save re-enables when an online song becomes current");
            });
            SystemClock.sleep(180);capture("manual-save-"+language+"-"+mode);
        }
    }
    private void captureSettings() {
        for (String language : new String[]{"zh_CN", "en"}) for (int mode : new int[]{1, 2}) {
            runOnMainSync(() -> {
                UiStrings.setLanguage(service, language);
                service.getSharedPreferences("appearance", 0).edit().putInt("mode", mode).apply();
                service.getSharedPreferences("window", 0).edit().putBoolean("desktopLyricsEnabled", false).apply();
                service.update(); overlay.configurationChanged();
                if (!"more".equals(get(overlay, "section"))) invoke(overlay, "toggle", new Class<?>[]{String.class}, "more");
                invoke(overlay, "openSettingsPage", new Class<?>[]{String.class}, "cache");
                check(findText((View)get(overlay, "window"), UiStrings.text("自动缓存播放歌曲")) != null, "cache settings rendered " + language + " mode " + mode);
            });
            SystemClock.sleep(220); capture("cache-settings-" + language + "-" + (mode == 2 ? "dark" : "light"));
            runOnMainSync(() -> {
                invoke(overlay, "openSettingsPage", new Class<?>[]{String.class}, "cacheManager");
                check(findText((View)get(overlay, "window"), UiStrings.text("缓存管理")) != null, "cache manager rendered " + language + " mode " + mode);
            });
            SystemClock.sleep(220); capture("cache-manager-" + language + "-" + (mode == 2 ? "dark" : "light"));
        }
    }

    private JSONObject snapshot() { final JSONObject[] value = {null}; runOnMainSync(() -> { service.update(); value[0] = service.playbackSnapshot(); }); return value[0]; }
    private void awaitPlaying(String id) { await(() -> { JSONObject state = snapshot(); return id.equals(state.optString("currentTrack")) && state.optBoolean("playing") && !state.optBoolean("busy"); }, "offline play " + id); }
    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < deadline) { if (condition.getAsBoolean()) { check(true, message); return; } SystemClock.sleep(30); }
        throw new AssertionError(message + " timed out" + (service == null ? "" : ": " + snapshot()));
    }
    private static boolean ready(OfflineCache cache, String key) { JSONObject entry = cache.entry(key); if (entry != null && "failed".equals(entry.optString("status"))) throw new AssertionError(entry.toString()); return entry != null && "ready".equals(entry.optString("status")); }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; report.append("PASS ").append(message).append('\n'); }
    private static JSONObject track(String id, String name) throws Exception { return TrackState.normalize(new JSONObject().put("id", "netease:" + id).put("songId", id).put("source", "netease").put("name", name).put("artist", "隔离验收 / Fixture artist").put("duration", 30)); }
    private static JSONObject lyrics(String key) throws Exception {
        JSONArray lines = new JSONArray(); StringBuilder original = new StringBuilder(), translation = new StringBuilder();
        for (int i = 0; i < 6; i++) { lines.put(new JSONObject().put("time", i * 5000).put("original", "缓存原文 " + i).put("translation", "Translation " + i)); original.append("缓存原文 ").append(i).append('\n'); translation.append("Translation ").append(i).append('\n'); }
        return new JSONObject().put("key", key).put("original", original).put("translation", translation).put("originalState", "available").put("translationState", "available").put("lyricLines", lines);
    }
    private static View findText(View view, String words) { if (view instanceof TextView && words.contentEquals(((TextView)view).getText())) return view; if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup)view; for (int i = 0; i < group.getChildCount(); i++) { View match = findText(group.getChildAt(i), words); if (match != null) return match; } } return null; }
    private void capture(String name) { try { Bitmap bitmap = getUiAutomation().takeScreenshot(); if (bitmap != null) { try (FileOutputStream output = new FileOutputStream(new File(artifacts, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); } bitmap.recycle(); } } catch (Exception error) { throw new RuntimeException(error); } }
    private static Object get(Object object, String name) { try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); } catch (Exception error) { throw new RuntimeException(error); } }
    private static void set(Object object, String name, Object value) { try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); } catch (Exception error) { throw new RuntimeException(error); } }
    private static void invoke(Object object, String name) { invoke(object, name, new Class<?>[0]); }
    private static void invoke(Object object, String name, Class<?>[] types, Object... values) { try { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(object, values); } catch (Exception error) { throw new RuntimeException(error); } }

    /** Tripwire: the production online-resolver work must never be submitted. */
    private static final class RejectingResolverExecutor extends AbstractExecutorService {
        final AtomicInteger attempts = new AtomicInteger(); private boolean stopped;
        @Override public void execute(Runnable command) { attempts.incrementAndGet(); throw new AssertionError("Online audio resolver submitted during offline fixture"); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }

    private static byte[] wave(int seconds) throws Exception {
        int samples = seconds * 16000, bytes = samples * 2; ByteArrayOutputStream out = new ByteArrayOutputStream(bytes + 44);
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII)); little(out, bytes + 36, 4); out.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)); little(out, 16, 4); little(out, 1, 2); little(out, 1, 2); little(out, 16000, 4); little(out, 32000, 4); little(out, 2, 2); little(out, 16, 2); out.write("data".getBytes(StandardCharsets.US_ASCII)); little(out, bytes, 4);
        for (int i = 0; i < samples; i++) little(out, (int)(Math.sin(i * 2 * Math.PI * 220 / 16000) * 1200), 2);
        return out.toByteArray();
    }
    private static void little(OutputStream out, int value, int count) throws Exception { for (int i = 0; i < count; i++) out.write((value >>> (8 * i)) & 255); }
    private static final class WaveServer implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger(); private final ServerSocket server; private final Thread thread; private volatile boolean closed;
        WaveServer(byte[] bytes) throws Exception {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> { while (!closed) { try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000); InputStream input = socket.getInputStream(); int previous = 0, crlfs = 0, value;
                while ((value = input.read()) != -1) { if (previous == '\r' && value == '\n') crlfs++; else if (value != '\r') crlfs = 0; previous = value; if (crlfs == 2) break; }
                requests.incrementAndGet(); OutputStream output = socket.getOutputStream(); output.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(bytes); output.flush();
            } catch (Exception error) { if (!closed) android.util.Log.e("CacheFixture", "HTTP fixture", error); } } }, "cache-fixture-http");
            thread.start();
        }
        String address() { return "http://127.0.0.1:" + server.getLocalPort() + "/complete.wav"; }
        @Override public void close() throws Exception { closed = true; server.close(); thread.join(2000); }
    }
}
