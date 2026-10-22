package org.floatmusic.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Builds system media presentation from the service's actual playback snapshot. */
public final class PlaybackNotifications {
    private static final String CHANNEL = "floatmusic.playback";
    private static final int OPEN_REQUEST = 1200, ACTION_REQUEST = 1201;
    // Public Android media-session keys; using their values adds no AndroidX dependency.
    private static final String RESERVE_PREVIOUS = "android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS";
    private static final String RESERVE_NEXT = "android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT";
    private static final int ACCENT = 0xff2458d3;
    private final Context context;
    private final MediaSession session;
    private final PendingIntent open;
    private final Bundle sessionExtras;
    private final Map<String, Integer> drawableIds = new HashMap<>();
    private Bitmap appArtwork;
    private Policy publishedMetadata;

    /** The service retains ownership of the session callback and lifecycle. */
    public PlaybackNotifications(Context context, MediaSession session) {
        if (context == null || session == null) throw new IllegalArgumentException("Context and MediaSession are required");
        this.context = context;
        this.session = session;
        Intent activity = new Intent().setClassName(context, "org.floatmusic.player.PlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open = PendingIntent.getActivity(context, OPEN_REQUEST, activity,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session.setSessionActivity(open);
        session.setPlaybackToLocal(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        sessionExtras = new Bundle();
        sessionExtras.putBoolean(RESERVE_PREVIOUS, true);
        sessionExtras.putBoolean(RESERVE_NEXT, true);
        session.setExtras(sessionExtras);
    }

    /** Legacy actions remain ordered; Android 13+ derives controls from the session. */
    public Notification build(JSONObject snapshot) {
        Policy policy = policy(snapshot);
        String subtitle = joined(policy.artist, policy.source);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
            .setSmallIcon(drawableId("floatmusic_note"))
            .setLargeIcon(artwork()).setColor(ACCENT)
            .setContentTitle(policy.title).setContentText(subtitle.isEmpty() ? policy.status : subtitle)
            .setSubText(policy.status + " · " + policy.actions[3].label)
            .setContentIntent(open).setCategory(Notification.CATEGORY_TRANSPORT)
            .setShowWhen(false).setOnlyAlertOnce(true).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        for (int index = 0; index < policy.actions.length; index++) {
            Action action = policy.actions[index];
            // A null intent gives the legacy control a disabled presentation while
            // preserving previous / play-pause / next compact positions.
            PendingIntent intent = action.enabled ? serviceAction(action.command, index) : null;
            builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(context, drawableId(action.icon)), action.label, intent).build());
        }
        builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    /** Call before publishing the notification and on every actual playback update. */
    public void updateSession(JSONObject snapshot) {
        Policy policy = policy(snapshot);
        // Metadata and the small app-art bitmap are stable between progress ticks.
        // Only playback state needs repeated publication for accurate seeks/progress.
        if (publishedMetadata == null || !sameMetadata(publishedMetadata, policy)) {
            session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, policy.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, policy.artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, policy.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, joined(policy.artist, policy.source))
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, policy.status + " · " + policy.actions[3].label)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, policy.duration)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork())
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork()).build());
            publishedMetadata = policy;
        }
        PlaybackState.Builder state = new PlaybackState.Builder().setActions(policy.nativeActions)
            .setState(policy.state, policy.position, policy.speed, SystemClock.elapsedRealtime())
            .setExtras(sessionExtras);
        for (int index = 3; index < 5; index++) {
            Action action = policy.actions[index];
            state.addCustomAction(new PlaybackState.CustomAction.Builder(action.command, action.label,
                drawableId(action.icon)).build());
        }
        session.setPlaybackState(state.build());
    }

    private PendingIntent serviceAction(String command, int index) {
        Intent intent = new Intent().setClassName(context, "org.floatmusic.player.PlaybackService").setAction(command);
        return PendingIntent.getService(context, ACTION_REQUEST + index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // Qt generates app resources separately; resolving names keeps this helper
    // independently compilable with the platform SDK and does not use reflection.
    private int drawableId(String name) {
        Integer cached = drawableIds.get(name);
        if (cached != null) return cached;
        int resource = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        if (resource == 0) throw new IllegalStateException("Missing notification drawable: " + name);
        drawableIds.put(name, resource);
        return resource;
    }

    private Bitmap artwork() {
        if (appArtwork == null) {
            // A code-native app note is a placeholder; no track cover is invented
            // or downloaded. One bounded bitmap is reused for the session lifetime.
            appArtwork = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(appArtwork);
            canvas.drawColor(0xffe8efff);
            Drawable note = context.getDrawable(drawableId("floatmusic_note"));
            if (note != null) {
                note = note.mutate();
                note.setTint(ACCENT);
                note.setBounds(32, 32, 128, 128);
                note.draw(canvas);
            }
        }
        return appArtwork;
    }

    private static boolean sameMetadata(Policy first, Policy second) {
        return first.duration == second.duration && first.title.equals(second.title)
            && first.artist.equals(second.artist) && first.source.equals(second.source)
            && first.status.equals(second.status) && first.actions[3].label.equals(second.actions[3].label);
    }

    private static String joined(String first, String second) {
        return first.isEmpty() ? second : second.isEmpty() ? first : first + " · " + second;
    }

    static final class Action {
        final String command, label, icon;
        final boolean enabled;
        Action(String command, String label, String icon, boolean enabled) {
            this.command = command;
            this.label = label;
            this.icon = icon;
            this.enabled = enabled;
        }
    }

    static final class Policy {
        final String title, artist, source;
        final long duration, position;
        final String status;
        final long nativeActions;
        final int state;
        final float speed;
        final Action[] actions;
        Policy(String title, String artist, String source, long duration, long position,
                String status, long nativeActions, int state, float speed, Action[] actions) {
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.duration = duration;
            this.position = position;
            this.status = status;
            this.nativeActions = nativeActions;
            this.state = state;
            this.speed = speed;
            this.actions = actions;
        }
    }

    static Policy policy(JSONObject snapshot) {
        JSONObject state = snapshot == null ? new JSONObject() : snapshot;
        JSONObject track = state.optJSONObject("loadedTrack");
        if (track == null) track = new JSONObject();
        String title = text(state, "title");
        if (title.isEmpty()) title = text(track, "name");
        if (title.isEmpty()) title = UiStrings.text("浮音");
        String source = text(state, "currentSourceName");
        if (source.isEmpty()) source = text(track, "sourceName");
        if (source.isEmpty()) {
            String platform = text(track, "source");
            if ("netease".equals(platform)) source = UiStrings.text("网易云");
            else if ("tencent".equals(platform)) source = UiStrings.text("QQ音乐");
            else if ("kuwo".equals(platform)) source = UiStrings.text("酷我音乐");
            else if ("local".equals(platform)) source = UiStrings.text("本地音频");
        }
        long duration = Math.max(0L, state.optLong("duration", 0L));
        long position = Math.max(0L, state.optLong("position", 0L));
        if (duration > 0L) position = Math.min(position, duration);
        boolean playing = state.optBoolean("playing");
        boolean busy = state.optBoolean("busy");
        boolean ready = state.optBoolean("ready");
        boolean canToggle = playing || (ready && !busy);
        JSONArray entries = state.optJSONArray("queueEntries");
        boolean canSkip = !busy && entries != null && entries.length() > 0;
        long nativeActions = 0L;
        if (playing) nativeActions |= PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
        if (!busy && ready) {
            nativeActions |= PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
            if (duration > 0L) nativeActions |= PlaybackState.ACTION_SEEK_TO;
        }
        if (canSkip) nativeActions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT;
        String mode = text(state, "playbackMode"), modeLabel = UiStrings.text("顺序播放");
        if ("loop".equals(mode)) modeLabel = UiStrings.text("列表循环");
        else if ("single".equals(mode)) modeLabel = UiStrings.text("单曲循环");
        else if ("shuffle".equals(mode)) modeLabel = UiStrings.text("随机播放");
        else mode = "sequential";
        int volume = Math.max(0, Math.min(100, state.optInt("volume", 70)));
        Action[] actions = {
            new Action("previous", UiStrings.text("上一首"), "floatmusic_previous", canSkip),
            new Action("toggle", playing ? UiStrings.text("暂停") : busy ? UiStrings.text("加载中") : UiStrings.text("播放"), playing ? "floatmusic_pause" : "floatmusic_play", canToggle),
            new Action("next", UiStrings.text("下一首"), "floatmusic_next", canSkip),
            new Action("cycleMode", modeLabel, "floatmusic_mode_" + mode, true),
            new Action("notificationVolume", UiStrings.text("音量 ") + volume + "%", volume == 0 ? "floatmusic_volume_mute" : "floatmusic_volume", true)
        };
        return new Policy(text(state,"currentTrack").isEmpty()?UiStrings.text(title):title, text(track, "artist"), UiStrings.text(source), duration, position,
            playing ? busy ? UiStrings.text("正在播放 · 正在加载音频…") : UiStrings.text("正在播放") : busy ? UiStrings.text("正在加载音频…") : ready ? UiStrings.text("已暂停") : UiStrings.text("待选择歌曲"), nativeActions,
            playing ? PlaybackState.STATE_PLAYING : busy ? PlaybackState.STATE_BUFFERING : ready ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_NONE,
            playing ? 1f : 0f, actions);
    }

    private static String text(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
