package org.floatmusic.player;

import android.media.session.PlaybackState;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure snapshot-to-notification policy: the Android SDK jar supplies compile-time
 * constants only. Real MediaSession, Notification and PendingIntent rendering is
 * verified on the device by the integration owner, never by mocked SDK objects.
 *
 * Platform fixture references (verified 2026-10-11):
 * https://developer.android.com/about/versions/13/behavior-changes-13#playback-controls
 * Android 13 derives play/loading/pause, previous, next and two ordered custom
 * actions from PlaybackState; older systems use the MediaStyle action order.
 * https://developer.android.com/reference/androidx/media/utils/MediaConstants#SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV
 * Reserve missing previous/next slots so loading cannot promote mode/volume into
 * transport slots. Reservation keys are session extras; no AndroidX dependency.
 * https://developer.android.com/reference/android/media/session/PlaybackState.Builder#setState(int,long,float,long)
 * Published duration/position are milliseconds; the state update uses elapsed
 * realtime so the system can advance progress while the activity is hidden.
 * https://developer.android.com/reference/android/media/session/MediaSession#setPlaybackToLocal(android.media.AudioAttributes)
 * Playback remains local; the volume action opens the existing overlay slider.
 */
public final class PlaybackNotificationsTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONObject loadedSnapshot() throws Exception {
        JSONObject loaded = new JSONObject().put("id", "tencent:001song")
            .put("name", "已加载的歌曲").put("artist", "歌手甲").put("source", "tencent")
            .put("duration", 240);
        return new JSONObject().put("ready", true).put("busy", false).put("playing", true)
            .put("position", 42500).put("duration", 231500).put("title", "正在播放的歌曲")
            .put("loadedTrack", loaded).put("currentSourceName", "QQ音乐")
            .put("requestedTrack", new JSONObject().put("name", "尚未成功的替换").put("artist", "歌手乙"))
            .put("playbackMode", "sequential").put("volume", 70)
            .put("queueEntries", new JSONArray().put(new JSONObject(loaded.toString()).put("queueId", "occurrence-1")))
            .put("currentQueueEntry", "occurrence-1");
    }

    // Replacing these fields with a requested song or catalog duration must fail.
    private static void metadataDescribesActualLoadedAudio() throws Exception {
        PlaybackNotifications.Policy policy = PlaybackNotifications.policy(loadedSnapshot());
        check(policy != null, "snapshot must produce a notification policy");
        check("正在播放的歌曲".equals(policy.title), "notification must use the published actual title");
        check("歌手甲".equals(policy.artist), "metadata must describe the loaded singer, not a pending replacement");
        check("QQ音乐".equals(policy.source), "metadata must include the actual source");
        check(policy.duration == 231500L && policy.position == 42500L,
            "metadata duration and playback position must use the actual millisecond snapshot");
        System.out.println("PASS metadata follows actual loaded audio and duration");
    }

    // Missing native flags, a wrong pause icon or changed action wiring must fail.
    private static void playingAudioPublishesAvailableTransportControls() throws Exception {
        PlaybackNotifications.Policy policy = PlaybackNotifications.policy(loadedSnapshot());
        check(policy.state == PlaybackState.STATE_PLAYING && policy.speed == 1f,
            "playing audio must advance the system progress bar");
        check(policy.nativeActions == (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT),
            "the media session must expose supported native transport and seek actions");
        check(policy.actions.length == 5, "legacy notification must provide all five requested controls");
        String[] expected = {"previous", "toggle", "next", "cycleMode", "notificationVolume"};
        for (int i = 0; i < expected.length; i++) {
            check(expected[i].equals(policy.actions[i].command) && policy.actions[i].enabled,
                "action " + i + " must dispatch the corresponding available service command");
        }
        check("floatmusic_pause".equals(policy.actions[1].icon) && "暂停".equals(policy.actions[1].label),
            "playing audio must show a pause icon and accessible pause label");
        check("顺序播放".equals(policy.actions[3].label) && "音量 70%".equals(policy.actions[4].label),
            "custom labels must describe the current playback mode and actual app volume");
        check("正在播放".equals(policy.status), "status must describe actual playing audio");
        System.out.println("PASS native transport, seek and five legacy commands with pause icon");
    }

    // Unconditionally enabling transport during load, or hiding a working pause,
    // must fail. The old audible player and pending replacement are distinct.
    private static void loadingExposesOnlyCommandsTheServiceCanExecute() throws Exception {
        JSONObject snapshot = loadedSnapshot().put("busy", true).put("playing", false);
        PlaybackNotifications.Policy loading = PlaybackNotifications.policy(snapshot);
        check(loading.state == PlaybackState.STATE_BUFFERING && loading.speed == 0f,
            "silent loading must publish buffering for Android 13's loading control");
        check(loading.nativeActions == 0L, "silent loading must not advertise any unavailable native transport");
        check(!loading.actions[0].enabled && !loading.actions[1].enabled && !loading.actions[2].enabled,
            "legacy loading controls must have no runnable transport PendingIntent");
        check(loading.actions[3].enabled && loading.actions[4].enabled,
            "mode preferences and the volume popup remain usable during loading");
        check("正在加载音频…".equals(loading.status), "silent loading must announce why playback is unavailable");

        PlaybackNotifications.Policy replacement = PlaybackNotifications.policy(snapshot.put("playing", true));
        check(replacement.state == PlaybackState.STATE_PLAYING && replacement.speed == 1f,
            "the old audible player must keep actual playing progress during replacement");
        check(replacement.nativeActions == (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE),
            "pause during replacement must prevent autoplay; play, seek and skip remain unavailable");
        check(!replacement.actions[0].enabled && replacement.actions[1].enabled && !replacement.actions[2].enabled,
            "only the real player's working pause command remains enabled during replacement");
        check("floatmusic_pause".equals(replacement.actions[1].icon),
            "an audible replacement load must continue to offer pause");
        System.out.println("PASS loading disables unavailable transport and preserves audible pause");
    }

    // Advertising seeks without real duration, or skips after clearing, must fail.
    private static void idleAndClearedQueueExposeOnlyAvailableActions() throws Exception {
        PlaybackNotifications.Policy idle = PlaybackNotifications.policy(null);
        check(idle.state == PlaybackState.STATE_NONE && idle.speed == 0f && idle.nativeActions == 0L,
            "an idle service must not pretend an audio file is ready");
        check(!idle.actions[0].enabled && !idle.actions[1].enabled && !idle.actions[2].enabled,
            "an empty service has no playable transport command");
        check(idle.actions[3].enabled && idle.actions[4].enabled && "待选择歌曲".equals(idle.status),
            "idle mode and volume remain available with clear status");

        JSONObject snapshot = loadedSnapshot().put("playing", false).put("queueEntries", new JSONArray());
        PlaybackNotifications.Policy cleared = PlaybackNotifications.policy(snapshot);
        check(cleared.state == PlaybackState.STATE_PAUSED && "floatmusic_play".equals(cleared.actions[1].icon),
            "paused current audio must offer play even after its queue is cleared");
        check(cleared.nativeActions == (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO),
            "clearing the queue removes skips while preserving the actual current file's transport");
        check(!cleared.actions[0].enabled && cleared.actions[1].enabled && !cleared.actions[2].enabled,
            "cleared legacy queue must disable both skip intents");

        PlaybackNotifications.Policy unknownDuration = PlaybackNotifications.policy(snapshot.put("duration", 0));
        check((unknownDuration.nativeActions & PlaybackState.ACTION_SEEK_TO) == 0L,
            "system seek must require a known positive audio duration");
        PlaybackNotifications.Policy waiting = PlaybackNotifications.policy(snapshot.put("ready", false));
        check(waiting.nativeActions == 0L && !waiting.actions[1].enabled,
            "a stopped or failed player must not expose play against an unavailable file");
        System.out.println("PASS idle, cleared queue and unknown duration action availability");
    }

    // A fixed mode icon, stale percentage or silent out-of-range volume must fail.
    private static void customControlsDescribeCurrentModeAndVolume() throws Exception {
        String[][] modes = {
            {"sequential", "顺序播放", "floatmusic_mode_sequential"},
            {"loop", "列表循环", "floatmusic_mode_loop"},
            {"single", "单曲循环", "floatmusic_mode_single"},
            {"shuffle", "随机播放", "floatmusic_mode_shuffle"},
            {"unknown", "顺序播放", "floatmusic_mode_sequential"}
        };
        for (String[] mode : modes) {
            PlaybackNotifications.Policy policy = PlaybackNotifications.policy(loadedSnapshot().put("playbackMode", mode[0]));
            check(mode[1].equals(policy.actions[3].label) && mode[2].equals(policy.actions[3].icon),
                "mode action must show the current recognized playback mode: " + mode[0]);
        }
        PlaybackNotifications.Policy muted = PlaybackNotifications.policy(loadedSnapshot().put("volume", -4));
        check("音量 0%".equals(muted.actions[4].label) && "floatmusic_volume_mute".equals(muted.actions[4].icon),
            "muted audio must show the mute icon and clamp invalid negative volume");
        PlaybackNotifications.Policy loud = PlaybackNotifications.policy(loadedSnapshot().put("volume", 130));
        check("音量 100%".equals(loud.actions[4].label), "volume labels must never exceed the real slider bounds");
        System.out.println("PASS mode-specific icons and bounded volume labels");
    }

    // An absent display source, literal "null", wrong units or mutation must fail.
    private static void metadataFallbacksKeepMissingFieldsHonest() throws Exception {
        JSONObject snapshot = loadedSnapshot().put("title", JSONObject.NULL)
            .put("currentSourceName", " ").put("position", 999999);
        snapshot.getJSONObject("loadedTrack").put("artist", JSONObject.NULL);
        String before = snapshot.toString();
        PlaybackNotifications.Policy policy = PlaybackNotifications.policy(snapshot);
        check("已加载的歌曲".equals(policy.title) && "".equals(policy.artist) && "QQ音乐".equals(policy.source),
            "missing fields must use the loaded track and its real source, never invent an artist");
        check(policy.position == 231500L, "out-of-range seek progress must not exceed actual duration");
        check(before.equals(snapshot.toString()), "notification rendering must leave the service snapshot unchanged");
        String[][] sources = {{"netease", "网易云"}, {"kuwo", "酷我音乐"}, {"local", "本地音频"}, {"unknown", ""}};
        for (String[] source : sources) {
            snapshot.getJSONObject("loadedTrack").put("source", source[0]);
            check(source[1].equals(PlaybackNotifications.policy(snapshot).source),
                "fallback source must represent only a recognized source: " + source[0]);
        }
        policy = PlaybackNotifications.policy(snapshot.put("duration", -1).put("position", -20));
        check(policy.duration == 0L && policy.position == 0L,
            "negative duration and progress must become unavailable zero values");
        System.out.println("PASS safe metadata fallbacks, progress bounds and snapshot immutability");
    }

    public static void main(String[] args) throws Exception {
        metadataDescribesActualLoadedAudio();
        playingAudioPublishesAvailableTransportControls();
        loadingExposesOnlyCommandsTheServiceCanExecute();
        idleAndClearedQueueExposeOnlyAvailableActions();
        customControlsDescribeCurrentModeAndVolume();
        metadataFallbacksKeepMissingFieldsHonest();
    }
}
