package org.floatmusic.scrolltest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the installed APK's actual overlay with system touch input and in-memory data. */
public final class OverlayScrollTest extends Instrumentation {
    private Object overlay;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void runOnMainSync(Runnable action) {
        Throwable[] failure = new Throwable[1];
        super.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] instanceof Error) throw (Error)failure[0];
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

    @Override public void onStart() {
        int passed = 0;
        for (String scene : new String[]{"playlist", "song-search", "playlist-search"}) {
            try {
                runOnMainSync(() -> prepare(scene));
                ScrollView detail = (ScrollView)field(overlay, "detailScroll");
                View root = (View)field(overlay, "window");
                waitForLayout(root);
                int[] before = new int[1];
                runOnMainSync(() -> {
                    detail.scrollTo(0, 0);
                    before[0] = detail.getScrollY();
                    require(detail.getChildAt(0).getHeight() > detail.getHeight(),
                            "fixture must contain more rows than the visible viewport");
                });
                swipe(root, detail, true);
                int[] after = new int[1];
                runOnMainSync(() -> {
                    after[0] = detail.getScrollY();
                    ScrollView outer = (ScrollView)field(overlay, "scroll");
                    report.append(scene).append(" inner=").append(before[0]).append("->")
                            .append(after[0]).append(" outer=").append(outer.getScrollY())
                            .append(" viewport=").append(detail.getHeight())
                            .append(" content=").append(detail.getChildAt(0).getHeight()).append('\n');
                    require(after[0] > before[0] + 40,
                            "upward swipe must reveal later rows in " + scene);
                });
                swipe(root, detail, false);
                runOnMainSync(() -> require(detail.getScrollY() < after[0] - 40,
                        "downward swipe must return to earlier rows in " + scene));
                report.append("PASS ").append(scene).append(" scrolls in both directions\n");
                if (scene.equals("playlist")) {
                    runOnMainSync(() -> detail.scrollTo(0, 0));
                    waitForLayout(root);
                    SystemClock.sleep(650); // Finish the native edge effect before testing a tap.
                    tap(root, findControl(root, false));
                    runOnMainSync(() -> require(Boolean.TRUE.equals(field(overlay, "selecting")),
                            "tap must still enter multi-select mode"));
                    waitForLayout(root);
                    tap(root, findControl(root, true));
                    runOnMainSync(() -> require(((java.util.Set<?>)field(overlay, "selectedIds")).size() == 1,
                            "tap must still select a song"));
                    swipe(root, detail, true);
                    runOnMainSync(() -> {
                        require(detail.getScrollY() > 40, "checkbox rows must also scroll");
                        require(((java.util.Set<?>)field(overlay, "selectedIds")).size() == 1,
                                "scrolling must not accidentally change selection");
                    });
                    report.append("PASS playlist button, checkbox and multi-select scrolling\n");
                }
                passed++;
            } catch (Throwable failure) {
                report.append("FAIL ").append(scene).append(": ").append(failure).append('\n');
            } finally {
                runOnMainSync(() -> { if (overlay != null) invoke(overlay, "close"); overlay = null; });
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", report.toString());
        result.putBoolean("scrollPassed", passed == 3);
        result.putInt("passed", passed);
        finish(passed == 3 ? -1 : 0, result);
    }

    private void prepare(String scene) {
        try {
            Class<?> serviceType = Class.forName("org.floatmusic.player.PlaybackService");
            Object service = serviceType.getDeclaredConstructor().newInstance();
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(service, getTargetContext());
            Class<?> overlayType = Class.forName("org.floatmusic.player.OverlayWindow");
            Constructor<?> constructor = overlayType.getDeclaredConstructor(serviceType);
            constructor.setAccessible(true);
            overlay = constructor.newInstance(service);
            JSONArray songs = new JSONArray(), playlists = new JSONArray();
            for (int i = 1; i <= 40; i++) {
                songs.put(new JSONObject().put("id", Integer.toString(i)).put("source", "netease")
                        .put("name", "Scroll song " + i).put("artist", "Fixture artist"));
                playlists.put(new JSONObject().put("id", Integer.toString(i))
                        .put("name", "Scroll playlist " + i).put("trackCount", 40)
                        .put("description", "Fixture description"));
            }
            JSONObject data = new JSONObject().put("activePlaylist", "fixture")
                    .put("tracks", songs).put("results", songs).put("playlistResults", playlists)
                    .put("playlists", new JSONArray().put(new JSONObject().put("id", "fixture")
                            .put("name", "Scroll fixture").put("trackCount", 40)));
            setField(overlay, "ui", data);
            invoke(overlay, "show");
            invoke(overlay, "expand");
            if (scene.equals("playlist")) invoke(overlay, "toggle", "playlist");
            else {
                setField(overlay, "searchKind", scene.equals("playlist-search") ? "playlists" : "songs");
                invoke(overlay, "toggle", "more");
                invoke(overlay, "selectDetail", "search");
            }
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private void swipe(View root, View viewport, boolean upward) {
        float[] points = new float[3];
        runOnMainSync(() -> {
            Rect rootBounds = new Rect(), visible = new Rect();
            require(root.getGlobalVisibleRect(rootBounds) && viewport.getGlobalVisibleRect(visible),
                    "overlay and list must be visible");
            points[0] = visible.left - rootBounds.left + visible.width() * .3f;
            points[1] = visible.top - rootBounds.top + visible.height() * (upward ? .85f : .15f);
            points[2] = visible.top - rootBounds.top + visible.height() * (upward ? .15f : .85f);
        });
        long downTime = SystemClock.uptimeMillis();
        touch(root, downTime, MotionEvent.ACTION_DOWN, points[0], points[1]);
        for (int step = 1; step <= 18; step++) {
            SystemClock.sleep(25);
            touch(root, downTime, MotionEvent.ACTION_MOVE, points[0],
                    points[1] + (points[2] - points[1]) * step / 18);
        }
        // Stationary MOVE samples eliminate fling velocity before the next tap.
        SystemClock.sleep(150);
        touch(root, downTime, MotionEvent.ACTION_MOVE, points[0], points[2]);
        SystemClock.sleep(150);
        touch(root, downTime, MotionEvent.ACTION_UP, points[0], points[2]);
        waitForLayout(root);
    }

    private void touch(View root, long downTime, int action, float x, float y) {
        int[] origin = new int[2];
        runOnMainSync(() -> root.getLocationOnScreen(origin));
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                origin[0] + x, origin[1] + y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { sendPointerSync(event); } finally { event.recycle(); }
    }

    private View findControl(View root, boolean checkbox) {
        if (checkbox ? root instanceof CheckBox
                : root instanceof Button && ((Button)root).getText().toString().equals("多选歌曲")) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            View found = findControl(((ViewGroup)root).getChildAt(i), checkbox);
            if (found != null) return found;
        }
        return null;
    }
    private void tap(View root, View target) {
        float[] point = new float[2];
        runOnMainSync(() -> {
            Rect bounds = new Rect(), rootBounds = new Rect();
            require(target != null && target.getGlobalVisibleRect(bounds) && root.getGlobalVisibleRect(rootBounds),
                    "tap target must be visible");
            point[0] = bounds.exactCenterX() - rootBounds.left;
            point[1] = bounds.exactCenterY() - rootBounds.top;
        });
        long downTime = SystemClock.uptimeMillis();
        touch(root, downTime, MotionEvent.ACTION_DOWN, point[0], point[1]);
        SystemClock.sleep(80);
        touch(root, downTime, MotionEvent.ACTION_UP, point[0], point[1]);
        waitForLayout(root);
    }

    private void waitForLayout(View root) {
        java.util.concurrent.CountDownLatch drawn = new java.util.concurrent.CountDownLatch(1);
        ViewTreeObserver.OnPreDrawListener[] listener = new ViewTreeObserver.OnPreDrawListener[1];
        int[] frames = new int[1];
        runOnMainSync(() -> {
            listener[0] = () -> {
                if (++frames[0] >= 2 && !root.isLayoutRequested()) {
                    root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                    drawn.countDown();
                } else root.postInvalidateOnAnimation();
                return true;
            };
            root.getViewTreeObserver().addOnPreDrawListener(listener[0]);
            root.invalidate();
        });
        try { require(drawn.await(3, java.util.concurrent.TimeUnit.SECONDS), "overlay layout must finish"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
    }

    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static void invoke(Object target, String name, String... argument) {
        try {
            Method method = argument.length == 0 ? target.getClass().getDeclaredMethod(name)
                    : target.getClass().getDeclaredMethod(name, String.class);
            method.setAccessible(true);
            method.invoke(target, (Object[])argument);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
