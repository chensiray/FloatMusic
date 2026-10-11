package org.floatmusic.player;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

// Uses the production store and real files. Reflection makes the first RED run
// a behavioral failure for the missing module, rather than a compiler failure.
public final class PlaybackQueueStoreTest {
    private interface Test { void run() throws Exception; }
    private static Path dataRoot;
    private static int passed, failed;

    private static final class Store {
        private final Object value;
        private final Class<?> type;
        Store(Path path) throws Exception {
            try { type = Class.forName("org.floatmusic.player.PlaybackQueueStore"); }
            catch (ClassNotFoundException missing) { throw new AssertionError("PlaybackQueueStore is missing"); }
            Constructor<?> constructor = type.getDeclaredConstructor(File.class);
            constructor.setAccessible(true);
            value = constructor.newInstance(path.toFile());
        }
        private Object call(String name, Class<?>[] parameters, Object... arguments) throws Exception {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            try { return method.invoke(value, arguments); }
            catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw failure;
            }
        }
        JSONArray entries() throws Exception { return (JSONArray) call("entries", new Class<?>[0]); }
        JSONArray history() throws Exception { return (JSONArray) call("history", new Class<?>[0]); }
        String current() throws Exception { return (String) call("currentEntryId", new Class<?>[0]); }
        boolean initialized() throws Exception { return (Boolean) call("initialized", new Class<?>[0]); }
        String error() throws Exception { return (String) call("error", new Class<?>[0]); }
        boolean replace(JSONArray tracks, int current) throws Exception { return (Boolean) call("replaceQueue", new Class<?>[]{JSONArray.class, int.class}, tracks, current); }
        String enqueue(JSONObject track, boolean next) throws Exception { return (String) call("enqueue", new Class<?>[]{JSONObject.class, boolean.class}, track, next); }
        boolean select(String id) throws Exception { return (Boolean) call("setCurrent", new Class<?>[]{String.class}, id); }
        boolean remove(String id) throws Exception { return (Boolean) call("remove", new Class<?>[]{String.class}, id); }
        boolean move(String id, int to) throws Exception { return (Boolean) call("move", new Class<?>[]{String.class, int.class}, id, to); }
        boolean clearQueue() throws Exception { return (Boolean) call("clearQueue", new Class<?>[0]); }
        boolean record(JSONObject track, long time) throws Exception { return (Boolean) call("recordPlayed", new Class<?>[]{JSONObject.class, long.class}, track, time); }
        boolean clearHistory() throws Exception { return (Boolean) call("clearHistory", new Class<?>[0]); }
        String next(int direction, String mode, boolean automatic) throws Exception { return (String) call("nextEntry", new Class<?>[]{int.class, String.class, boolean.class}, direction, mode, automatic); }
        JSONObject entry(String id) throws Exception { return (JSONObject) call("entry", new Class<?>[]{String.class}, id); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(message + "; expected=" + expected + ", actual=" + actual);
    }
    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) result.append(canonical(array.get(i))).append(',');
            return result.append(']').toString();
        }
        if (value instanceof Number) return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }
    private static void sameJson(Object expected, Object actual, String message) throws Exception { equal(canonical(expected), canonical(actual), message); }
    private static void sameBytes(byte[] expected, Path path, String message) throws Exception { check(Arrays.equals(expected, Files.readAllBytes(path)), message); }
    private static Path file() throws Exception { return Files.createTempDirectory(dataRoot, "case-").resolve("playback.json"); }
    private static JSONObject song(String source, String id) throws Exception { return song(source, id, "Song"); }
    private static JSONObject song(String source, String id, String name) throws Exception {
        return new JSONObject().put("id", source + ":" + id).put("source", source).put("songId", id).put("name", name).put("artist", "Artist");
    }
    private static JSONArray tracks(JSONObject... tracks) { JSONArray result = new JSONArray(); for (JSONObject track : tracks) result.put(track); return result; }
    private static JSONArray three() throws Exception { return tracks(song("netease", "1"), song("netease", "2"), song("netease", "3")); }
    private static String id(Store store, int index) throws Exception { return store.entries().getJSONObject(index).getString("queueId"); }
    private static List<String> identities(JSONArray values) throws Exception {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getJSONObject(i).getString("id"));
        return result;
    }
    private static JSONObject root(Path path) throws Exception { return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)); }
    private static void save(Path path, JSONObject root) throws Exception { Files.write(path, root.toString().getBytes(StandardCharsets.UTF_8)); }
    private static void run(String name, Test test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable failure) { failed++; System.out.println("FAIL " + name + ": " + failure); failure.printStackTrace(System.out); }
    }

    private static void duplicatesSurviveRestart() throws Exception {
        Path path = file(); Store store = new Store(path);
        check(!store.initialized(), "fresh store must permit one-time migration");
        JSONObject duplicate = song("netease", "42");
        check(store.replace(tracks(duplicate, duplicate, song("kuwo", "42")), 1), "replace queue");
        String first = id(store, 0), current = id(store, 1);
        check(!first.equals(current), "duplicates need independent occurrence IDs");
        UUID.fromString(first); UUID.fromString(current);
        check(store.move(first, 2), "move duplicate"); check(store.remove(first), "remove that occurrence only");
        Store restored = new Store(path);
        equal("", restored.error(), "reload healthy data"); check(restored.initialized(), "queue initialized");
        sameJson(store.entries(), restored.entries(), "occurrences retained across restart");
        equal(current, restored.current(), "actual cursor retained"); equal(2, restored.entries().length(), "other duplicate remains");
    }

    private static void copiesCannotMutateState() throws Exception {
        Store store = new Store(file()); JSONObject input = song("netease", "1");
        check(store.replace(tracks(input), 0), "replace"); String current = store.current();
        input.put("name", "input mutation");
        JSONArray entries = store.entries(); entries.getJSONObject(0).put("name", "getter mutation"); entries.put(song("kuwo", "2"));
        JSONObject selected = store.entry(current); selected.put("name", "entry mutation");
        equal("Song", store.entry(current).getString("name"), "input and accessor objects must be detached");
        equal(1, store.entries().length(), "accessor array must be detached");
        check(store.record(input, 10), "record"); JSONArray history = store.history(); history.getJSONObject(0).put("playedAtMs", 99);
        equal(10L, store.history().getJSONObject(0).getLong("playedAtMs"), "history getter must be detached");
        equal(null, store.entry("missing"), "missing occurrence lookup"); equal(null, store.entry(null), "null lookup");
    }

    private static void insertionKeepsCursor() throws Exception {
        Store store = new Store(file()); check(store.replace(tracks(song("netease", "1"), song("netease", "2")), 0), "replace");
        String current = store.current(), next = store.enqueue(song("kuwo", "3"), true);
        check(!next.isEmpty() && !store.enqueue(song("tencent", "MID4"), false).isEmpty(), "enqueue");
        equal(Arrays.asList("netease:1", "kuwo:3", "netease:2", "tencent:MID4"), identities(store.entries()), "next insertion and tail order");
        equal(next, store.next(1, "sequential", true), "explicit next"); check(store.move(next, 0), "reorder");
        equal(current, store.current(), "reorder must not select audio"); equal(current, id(store, 1), "selected occurrence moves with item");
        equal(0, store.history().length(), "queue edits cannot record playback");
    }

    private static void removedCurrentGapRestarts() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.replace(three(), 1), "replace");
        String first = id(store, 0), third = id(store, 2); check(store.remove(store.current()), "remove current");
        equal("", store.current(), "removed occurrence cursor clears"); equal(third, store.next(1, "sequential", true), "continue at original gap");
        equal(first, store.next(-1, "sequential", false), "previous before original gap");
        Store restored = new Store(path); equal(third, restored.next(1, "single", true), "gap survives restart");
        check(restored.remove(first), "remove item before gap"); equal(third, restored.next(1, "sequential", true), "gap follows removal before it");
        check(restored.select(third) && restored.remove(third), "remove last item"); equal("", restored.next(1, "loop", false), "empty queue has no next");
    }

    private static void moveAcrossGap(int from, int to) throws Exception {
        Path path = file(); Store store = new Store(path);
        check(store.replace(tracks(song("netease", "1"), song("netease", "2"), song("netease", "3"), song("netease", "4"), song("netease", "5")), 2), "replace");
        String expected = id(store, 3); check(store.remove(store.current()), "remove selected item"); check(store.move(id(store, from), to), "reorder at gap");
        equal(expected, store.next(1, "sequential", true), "only crossing the gap shifts its logical position");
        equal(expected, new Store(path).next(1, "sequential", true), "new gap persisted");
    }

    private static void gapInsertionAndTail() throws Exception {
        Store store = new Store(file()); check(store.replace(three(), 1), "replace"); String last = id(store, 2);
        check(store.remove(store.current()), "remove selected"); String next = store.enqueue(song("kuwo", "4"), true);
        equal(next, id(store, 1), "play next inserts at gap"); check(store.select(next), "selection consumes request");
        equal(last, store.next(1, "sequential", true), "then continue after inserted item");
        check(store.select(last) && store.remove(last), "remove last selected"); equal("", store.next(1, "sequential", true), "automatic boundary stops");
        String tail = store.enqueue(song("kuwo", "5"), false); equal(tail, store.next(1, "sequential", true), "new tail at end gap can continue");
    }

    private static void navigation(String mode, boolean automatic, int current, int direction, int expected) throws Exception {
        Store store = new Store(file()); check(store.replace(three(), current), "replace"); String cursor = store.current();
        equal(expected < 0 ? "" : id(store, expected), store.next(direction, mode, automatic), "navigation result");
        equal(cursor, store.current(), "look ahead must not select or save");
    }

    private static void shuffleOccurrencePolicy() throws Exception {
        Store store = new Store(file()); JSONObject duplicate = song("netease", "1"); check(store.replace(tracks(duplicate, duplicate), 0), "replace");
        String other = id(store, 1);
        for (int i = 0; i < 20; i++) equal(other, store.next(1, "shuffle", true), "shuffle excludes occurrence, permits same song");
        check(store.remove(other), "remove other"); equal(store.current(), store.next(1, "shuffle", true), "one item can repeat");
        check(store.replace(three(), -1), "unselected shuffle"); Set<String> ids = new HashSet<>(); for (int i = 0; i < 3; i++) ids.add(id(store, i));
        for (int i = 0; i < 20; i++) check(ids.contains(store.next(1, "shuffle", false)), "shuffle with gap returns a queue occurrence");
        equal("", store.next(0, "shuffle", false), "zero direction invalid"); equal("", store.next(1, "invalid", true), "invalid mode");
        equal("", store.next(1, null, true), "null mode"); check(store.clearQueue(), "clear"); equal("", store.next(1, "shuffle", true), "empty shuffle");
    }

    private static void explicitNextPriority(String mode, boolean automatic) throws Exception {
        Path path = file(); Store store = new Store(path); JSONObject duplicate = song("netease", "1");
        check(store.replace(tracks(duplicate, song("netease", "2")), 0), "replace"); String current = store.current();
        String older = store.enqueue(duplicate, true), newest = store.enqueue(duplicate, true), tail = store.enqueue(duplicate, false);
        check(!older.isEmpty() && !newest.isEmpty() && !tail.isEmpty() && !newest.equals(older), "duplicate priority IDs");
        check(store.move(newest, store.entries().length() - 1), "move priority to tail"); byte[] saved = Files.readAllBytes(path);
        for (int i = 0; i < 5; i++) equal(newest, store.next(1, mode, automatic), "latest explicit request overrides mode and list order");
        equal(current, store.current(), "pending request cannot move cursor"); sameBytes(saved, path, "look ahead cannot consume requests or write file");
        Store restored = new Store(path); equal(newest, restored.next(1, mode, automatic), "request persists");
        check(!restored.select("missing"), "reject missing selection"); equal(newest, restored.next(1, mode, automatic), "failed selection leaves request");
        check(restored.select(current), "select current"); equal(newest, restored.next(1, mode, automatic), "unrelated selection leaves request");
        check(restored.select(newest), "select priority"); equal(older, restored.next(1, mode, automatic), "successful selection consumes only that request");
        check(restored.remove(older), "remove older request"); equal(newest, restored.next(1, "single", true), "single resumes after requests exhausted");
        equal("", restored.next(1, "sequential", true), "automatic sequential boundary");
    }

    private static void previousLeavesPriority() throws Exception {
        Store store = new Store(file()); check(store.replace(three(), 1), "replace"); String previous = id(store, 0), current = store.current();
        String pending = store.enqueue(song("kuwo", "4"), true); equal(previous, store.next(-1, "sequential", false), "previous ignores priority");
        equal(current, store.next(-1, "single", true), "single automatic repeat"); equal(pending, store.next(1, "single", true), "previous look ahead retains pending request");
    }

    private static void removalRetainsOtherPriority() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.replace(tracks(song("netease", "1"), song("netease", "2")), 0), "replace");
        String ordinary = id(store, 1), older = store.enqueue(song("kuwo", "3"), true), newest = store.enqueue(song("kuwo", "4"), true);
        check(store.remove(newest), "remove newest request"); equal(older, store.next(1, "single", true), "older remains");
        check(store.remove(store.current()), "remove selected occurrence"); Store restored = new Store(path);
        equal(older, restored.next(1, "shuffle", true), "priority survives selected removal and restart");
        check(restored.remove(older), "remove remaining request"); equal(ordinary, restored.next(1, "single", true), "then resume gap navigation");
    }

    private static void replacingClearingDiscardsPriority() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.replace(three(), 0), "replace"); check(!store.enqueue(song("kuwo", "4"), true).isEmpty(), "priority");
        check(store.replace(tracks(song("netease", "5")), 0), "replace discards old occurrence requests"); equal(store.current(), store.next(1, "single", true), "new single policy");
        check(!store.enqueue(song("kuwo", "6"), true).isEmpty() && store.clearQueue(), "clear discards requests");
        String tail = store.enqueue(song("kuwo", "7"), false); equal(tail, new Store(path).next(1, "single", true), "no stale request after clear/restart");
    }

    private static void failedSelectionRetainsPriority() throws Exception {
        Path path = file(), backup = path.resolveSibling("kept.json"); Store store = new Store(path); check(store.replace(three(), 0), "replace"); String current = store.current();
        String older = store.enqueue(song("kuwo", "4"), true), newest = store.enqueue(song("kuwo", "5"), true); byte[] saved = Files.readAllBytes(path);
        Files.move(path, backup); Files.createDirectory(path);
        check(!store.select(newest), "atomic replace into directory must fail"); check(!store.error().isEmpty(), "write failure reported");
        equal(current, store.current(), "failed persistence cannot publish cursor"); equal(newest, store.next(1, "single", true), "failed save cannot consume priority");
        sameBytes(saved, backup, "original file remains intact");
        try (java.util.stream.Stream<Path> children = Files.list(path.getParent())) { equal(2L, children.count(), "failed atomic save leaves no temporary file"); }
        Files.delete(path); Files.move(backup, path); check(store.select(newest), "retry successful selection");
        equal("", store.error(), "success clears failure"); equal(older, store.next(1, "single", true), "only committed request consumed");
    }

    private static void oldVersionOnePriorityOptional() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.replace(three(), 0) && store.record(song("kuwo", "4"), 10), "fixture");
        JSONObject old = root(path); old.remove("priorityEntries"); save(path, old);
        Store restored = new Store(path); equal("", restored.error(), "old version 1 accepted"); sameJson(store.entries(), restored.entries(), "old queue"); sameJson(store.history(), restored.history(), "old history");
        equal(store.current(), restored.next(1, "single", true), "missing priority means none");
        String pending = restored.enqueue(song("kuwo", "5"), true); equal(pending, root(path).getJSONArray("priorityEntries").getString(0), "next commit upgrades optional field");
    }

    private static void validJsonEscapesAndIntegerRepresentations() throws Exception {
        Path path = file(); Store store = new Store(path);
        JSONObject track = song("netease", "1", "音符 \"quote\" \\ path\nline\tend");
        check(store.replace(tracks(track), 0) && store.record(track, 10), "fixture with valid JSON escapes");
        JSONObject document = root(path); document.put("version", 1.0).put("gapIndex", 1.0);
        document.put("ignored", new JSONObject().put("nested", new JSONArray().put(true).put(false).put(JSONObject.NULL).put(-1.25e-4)));
        Files.write(path, ("\ufeff \r\n" + document.toString(2) + " \t\n").getBytes(StandardCharsets.UTF_8));
        Store restored = new Store(path); equal("", restored.error(), "ordinary strict JSON, BOM, whitespace, escapes, exponents remain readable");
        equal(track.getString("name"), restored.entries().getJSONObject(0).getString("name"), "title escapes round trip");
        equal(store.current(), restored.current(), "integral numeric fields accepted"); sameJson(store.history(), restored.history(), "escaped history remains readable");
    }

    private static void invalidOptionalMetadataIsDiscarded() throws Exception {
        Path path = file(); Store store = new Store(path); JSONArray tracks = new JSONArray();
        for (String duration : new String[]{"0", "-1", "86401", "NaN", "Infinity", "not-a-number"})
            tracks.put(song("netease", String.valueOf(tracks.length() + 1)).put("duration", duration).put("artist", 42).put("sourceName", JSONObject.NULL));
        tracks.put(song("kuwo", "7").put("duration", 86400));
        check(store.replace(tracks, -1), "invalid optional metadata must not reject valid source identity");
        Store restored = new Store(path);
        for (int i = 0; i < 6; i++) {
            JSONObject track = restored.entries().getJSONObject(i);
            check(!track.has("duration") && !track.has("artist") && !track.has("sourceName"), "invalid optional values not persisted");
        }
        equal(86400.0, restored.entries().getJSONObject(6).getDouble("duration"), "one-day positive duration boundary retained");
    }

    private static void clearedQueueStaysInitialized() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.clearHistory(), "empty history clear"); check(!store.initialized(), "history does not initialize migration");
        check(store.record(song("netease", "1"), 10), "record only history"); Store withHistory = new Store(path); check(!withHistory.initialized(), "history-only restart still allows queue migration");
        check(withHistory.clearQueue() && withHistory.clearHistory(), "clear"); Store restored = new Store(path);
        check(restored.initialized(), "intentional empty queue must never resurrect legacy queue"); equal(0, restored.entries().length(), "empty queue persists"); equal("", restored.current(), "empty cursor");
        equal("", restored.next(1, "single", true), "empty queue stops");
    }

    private static void stableMetadataAndMissingLocalFile() throws Exception {
        Path path = file(); Store store = new Store(path); JSONObject online = song("tencent", "MID1", "  Named song  "); online.remove("songId");
        online.put("url", "https://temporary.invalid/audio?secret=token").put("mediaUrl", "https://temporary.invalid/").put("path", "https://temporary.invalid/")
            .put("queueId", "untrusted-occurrence").put("duration", "254.47").put("sourceName", " QQ 音乐 ").put("album", "discard").put("artist", " Artist ");
        JSONObject local = song("local", "missing").put("path", path.resolveSibling("missing.mp3").toString()).put("duration", 100);
        check(store.replace(tracks(online, local), 1) && store.record(online, 1234), "stable metadata");
        String bytes = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        check(!bytes.contains("temporary.invalid") && !bytes.contains("mediaUrl") && !bytes.contains("untrusted-occurrence") && !bytes.contains("album"), "temporary URLs and unlisted fields excluded");
        Store restored = new Store(path); JSONObject clean = restored.entry(id(restored, 0));
        equal("MID1", clean.getString("songId"), "derive online songId"); equal("Named song", clean.getString("name"), "trim title"); equal("Artist", clean.getString("artist"), "trim artist");
        equal("QQ 音乐", clean.getString("sourceName"), "trim source name"); equal(254.47, clean.getDouble("duration"), "valid duration retained");
        JSONObject restoredLocal = restored.entry(id(restored, 1)); equal(local.getString("path"), restoredLocal.getString("path"), "missing file path retained without availability filtering");
        check(!restoredLocal.has("duration") && !restoredLocal.has("songId"), "local session fields match the native whitelist");
        check(!restored.history().getJSONObject(0).has("queueId"), "history cannot contain occurrence IDs");
    }

    private static void localWithoutUsablePathRemainsQueued() throws Exception {
        Path path = file(); Store store = new Store(path); JSONObject absent = song("local", "unavailable"), relative = song("local", "relative").put("path", "relative.mp3");
        check(store.replace(tracks(absent, relative), 0), "local identity can survive missing path"); Store restored = new Store(path);
        equal(2, restored.entries().length(), "unavailable local entries retained"); check(!restored.entries().getJSONObject(0).has("path") && !restored.entries().getJSONObject(1).has("path"), "only absolute paths persisted");
    }

    private static void historyFullIdentityCap() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.replace(three(), 0), "queue"); JSONArray queue = store.entries(); String current = store.current();
        check(store.record(song("netease", "42"), 1) && store.record(song("kuwo", "42"), 2) && store.record(song("netease", "42", "Updated title"), 3), "records");
        equal(Arrays.asList("netease:42", "kuwo:42"), identities(store.history()), "source-qualified identity deduplicated newest first");
        equal(3L, store.history().getJSONObject(0).getLong("playedAtMs"), "latest timestamp"); equal("Updated title", store.history().getJSONObject(0).getString("name"), "latest metadata");
        for (int i = 1; i <= 201; i++) check(store.record(song("tencent", String.valueOf(i)), 100 + i), "record capped history");
        equal(200, store.history().length(), "200 unique records"); equal("tencent:201", store.history().getJSONObject(0).getString("id"), "latest first"); equal("tencent:2", store.history().getJSONObject(199).getString("id"), "oldest evicted");
        Store restored = new Store(path); sameJson(store.history(), restored.history(), "history restart"); sameJson(queue, restored.entries(), "history leaves queue intact"); equal(current, restored.current(), "history leaves cursor intact");
        check(store.clearHistory(), "clear history"); sameJson(queue, store.entries(), "clear history leaves queue"); check(store.initialized(), "clear history leaves initialized queue");
    }

    private static void playedTimePolicy() throws Exception {
        Store store = new Store(file()); long before = System.currentTimeMillis(); check(store.record(song("netease", "1"), -1), "default time"); long after = System.currentTimeMillis();
        long playedAt = store.history().getJSONObject(0).getLong("playedAtMs"); check(playedAt >= before && playedAt <= after, "-1 means current clock");
        check(store.record(song("kuwo", "2"), 0), "epoch timestamp allowed"); JSONArray saved = store.history(); check(!store.record(song("netease", "3"), -2), "negative non-sentinel time rejected"); sameJson(saved, store.history(), "invalid record rollback");
    }

    private static void failedInitialSaveDoesNotInitialize() throws Exception {
        Path path = file().resolve("blocked/playback.json"); Store store = new Store(path); Files.createDirectories(path);
        check(!store.replace(three(), 0), "destination directory fails"); check(!store.error().isEmpty(), "failure surfaced"); equal(0, store.entries().length(), "unwritten queue not exposed"); check(!store.initialized(), "failure cannot mark migration completed");
    }

    private static void failedMutationsNeverPublish() throws Exception {
        Path path = file(), backup = path.resolveSibling("kept.json"); Store store = new Store(path); check(store.replace(three(), 0) && store.record(song("kuwo", "4"), 10), "fixture");
        JSONArray entries = store.entries(), history = store.history(); String current = store.current(); byte[] saved = Files.readAllBytes(path);
        Files.move(path, backup); Files.createDirectory(path);
        check(!store.replace(tracks(song("netease", "5")), 0), "replacement rollback"); equal("", store.enqueue(song("netease", "6"), true), "enqueue rollback");
        check(!store.move(current, 2) && !store.remove(current) && !store.clearQueue(), "queue edits rollback");
        check(!store.record(song("kuwo", "7"), 20) && !store.clearHistory(), "history edits rollback");
        sameJson(entries, store.entries(), "failed queue changes remain hidden"); sameJson(history, store.history(), "failed history changes remain hidden"); equal(current, store.current(), "failed changes leave cursor"); sameBytes(saved, backup, "durable backup unaffected");
    }

    private static void briefFileContentionKeepsAtomicSelection() throws Exception {
        if (File.separatorChar != '\\') return;
        Path path = file(); Store store = new Store(path); check(store.replace(three(), 0), "fixture");
        String pending = store.enqueue(song("kuwo", "4"), true);
        java.io.FileOutputStream held = new java.io.FileOutputStream(path.toFile(), true);
        final Throwable[] closeFailure = new Throwable[1];
        Thread release = new Thread(() -> {
            try { Thread.sleep(75); held.close(); } catch (Throwable failure) { closeFailure[0] = failure; }
        }, "release-test-file");
        release.start();
        boolean selected;
        try { selected = store.select(pending); }
        finally { release.join(); held.close(); }
        check(closeFailure[0] == null, "test lock released");
        check(selected, "brief Windows sharing contention should retry the same atomic replacement");
        equal(pending, store.current(), "only successfully saved cursor is published"); equal(pending, new Store(path).current(), "successful retry persists selection");
        equal(pending, store.next(1, "single", true), "committed request consumed");
    }

    private static void invalidOperationsLeaveDisk() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.replace(three(), 0), "fixture"); byte[] saved = Files.readAllBytes(path); JSONArray entries = store.entries();
        JSONObject bad = song("netease", "2").put("id", "kuwo:2");
        check(!store.replace(tracks(song("netease", "4"), bad), 0), "invalid identity rejects whole queue");
        check(!store.replace(three(), 3) && !store.replace(new JSONArray(), -2) && !store.replace(null, 0), "invalid selected position/array");
        check(!store.select("missing") && !store.select(null) && !store.remove("missing") && !store.move(store.current(), 3) && !store.move(store.current(), -1), "invalid occurrence edit");
        equal("", store.enqueue(new JSONObject(), false), "empty track rejected"); equal("", store.enqueue(null, false), "null track rejected"); check(!store.record(bad, 10), "bad history rejected");
        sameJson(entries, store.entries(), "invalid inputs leave queue"); sameBytes(saved, path, "invalid inputs leave disk"); check(!store.error().isEmpty(), "invalid input error visible");
        check(store.select(store.current()), "no-op success"); equal("", store.error(), "successful no-op clears previous error"); sameBytes(saved, path, "no-op does not rewrite disk");
    }

    private static void trackIdentityPolicy() throws Exception {
        Store store = new Store(file());
        JSONObject[] invalid = {
            song("netease", "0"), song("netease", "01"), song("netease", "12345678901234567890"), song("kuwo", "a"),
            song("tencent", "bad-mid"), song("tencent", "A").put("songId", "B"), song("other", "1"), song("local", ""),
            song("netease", "1", "  "), song("netease", "1").put("name", 42), song("netease", "1").put("source", 42),
            song("netease", "1").put("id", 42), song("local", "1").put("id", "foreign:1")
        };
        for (JSONObject track : invalid) equal("", store.enqueue(track, false), "invalid source/id/title rejected: " + track);
        check(store.replace(tracks(song("netease", "1234567890123456789"), song("kuwo", "1"), song("tencent", "a1B9")), -1), "valid platform identity boundaries");
    }

    private static void serializedAndQueueLimits() throws Exception {
        Path path = file(); Store store = new Store(path); check(store.replace(three(), 0), "fixture"); byte[] saved = Files.readAllBytes(path);
        JSONArray excessive = new JSONArray(); JSONObject track = song("netease", "1"); for (int i = 0; i < 10001; i++) excessive.put(track);
        check(!store.replace(excessive, -1), "10001 entries rejected before publication");
        char[] title = new char[12000]; Arrays.fill(title, '\u97f3'); JSONObject large = song("netease", "2", new String(title)); JSONArray oversized = new JSONArray();
        for (int i = 0; i < 240; i++) oversized.put(large);
        check(!store.replace(oversized, -1), "8 MiB limit counts UTF-8 bytes rather than Java characters"); equal(3, store.entries().length(), "failed whole updates preserve original queue"); sameBytes(saved, path, "limits preserve file");
    }

    private static void maximumQueueRestarts() throws Exception {
        Path path = file(); Store store = new Store(path); JSONArray all = new JSONArray(); JSONObject duplicate = song("netease", "1");
        for (int i = 0; i < 10000; i++) all.put(duplicate); check(store.replace(all, 9999), "10000 allowed"); Set<String> ids = new HashSet<>(); JSONArray values = store.entries();
        for (int i = 0; i < values.length(); i++) ids.add(values.getJSONObject(i).getString("queueId")); equal(10000, ids.size(), "unique IDs at limit"); byte[] saved = Files.readAllBytes(path);
        equal("", store.enqueue(song("kuwo", "2"), false), "tail blocked at limit"); equal("", store.enqueue(song("kuwo", "2"), true), "next blocked at limit"); sameBytes(saved, path, "rejected enqueue keeps disk");
        Store restored = new Store(path); equal("", restored.error(), "maximum file valid"); equal(10000, restored.entries().length(), "maximum restart"); equal(store.current(), restored.current(), "maximum cursor persists");
        equal("", restored.next(1, "sequential", true), "automatic end stops"); equal(id(restored, 0), restored.next(1, "sequential", false), "manual end wraps");
    }

    private static void corruptFilePreserved(String damage) throws Exception {
        Path path = file(); Store valid = new Store(path); check(valid.replace(three(), 0) && valid.record(song("kuwo", "4"), 10), "valid fixture");
        JSONObject value = root(path); JSONArray entries = value.getJSONArray("entries"), history = value.getJSONArray("history");
        if (damage.equals("version")) value.put("version", 99);
        if (damage.equals("version-type")) value.put("version", "1");
        if (damage.equals("root-array")) { Files.write(path, "[]".getBytes(StandardCharsets.UTF_8)); }
        if (damage.equals("entries-type")) value.put("entries", "invalid");
        if (damage.equals("entry-type")) entries.put(0, 42);
        if (damage.equals("duplicate-queue-id")) entries.put(entries.getJSONObject(0));
        if (damage.equals("duplicate-uuid-spelling")) entries.put(new JSONObject(entries.getJSONObject(0).toString()).put("queueId", entries.getJSONObject(0).getString("queueId").toUpperCase(java.util.Locale.ROOT)));
        if (damage.equals("invalid-uuid")) entries.getJSONObject(0).put("queueId", "1-1-1-1-1");
        if (damage.equals("zero-uuid")) entries.getJSONObject(0).put("queueId", "00000000-0000-0000-0000-000000000000");
        if (damage.equals("track-identity")) entries.getJSONObject(0).put("id", "kuwo:1");
        if (damage.equals("missing-current")) value.put("currentEntryId", UUID.randomUUID().toString());
        if (damage.equals("current-type")) value.put("currentEntryId", 42);
        if (damage.equals("negative-gap")) value.put("gapIndex", -1);
        if (damage.equals("gap-type")) value.put("gapIndex", "1");
        if (damage.equals("fractional-gap")) value.put("gapIndex", 1.5);
        if (damage.equals("gap-outside")) value.put("gapIndex", 4);
        if (damage.equals("cursor-gap-mismatch")) value.put("gapIndex", 2);
        if (damage.equals("history-type")) value.put("history", "invalid");
        if (damage.equals("history-duplicate")) history.put(history.getJSONObject(0));
        if (damage.equals("history-time-negative")) history.getJSONObject(0).put("playedAtMs", -1);
        if (damage.equals("history-time-type")) history.getJSONObject(0).put("playedAtMs", "10");
        if (damage.equals("history-time-fractional")) history.getJSONObject(0).put("playedAtMs", 10.5);
        if (damage.equals("history-limit")) for (int i = 0; i < 201; i++) history.put(history.getJSONObject(0));
        if (damage.equals("queue-limit")) for (int i = 0; i < 10001; i++) entries.put(entries.getJSONObject(0));
        if (damage.equals("initialized-type")) value.put("initialized", "false");
        if (damage.equals("uninitialized-queue")) value.put("initialized", false);
        if (damage.equals("priority-type")) value.put("priorityEntries", "invalid");
        if (damage.equals("priority-duplicate")) value.put("priorityEntries", new JSONArray().put(entries.getJSONObject(0).getString("queueId")).put(entries.getJSONObject(0).getString("queueId")));
        if (damage.equals("priority-orphan")) value.put("priorityEntries", new JSONArray().put(UUID.randomUUID().toString()));
        if (damage.equals("priority-id-type")) value.put("priorityEntries", new JSONArray().put(42));
        if (damage.equals("priority-invalid-id")) value.put("priorityEntries", new JSONArray().put("not-an-occurrence"));
        if (!damage.equals("root-array")) save(path, value);
        if (damage.equals("syntax")) Files.write(path, "{broken".getBytes(StandardCharsets.UTF_8));
        if (damage.equals("trailing-data")) Files.write(path, (value.toString() + " garbage").getBytes(StandardCharsets.UTF_8));
        if (damage.equals("unquoted-key")) Files.write(path, value.toString().replace("\"version\":", "version:").getBytes(StandardCharsets.UTF_8));
        if (damage.equals("single-quotes")) Files.write(path, value.toString().replace('"', '\'').getBytes(StandardCharsets.UTF_8));
        if (damage.equals("trailing-comma")) Files.write(path, (value.toString().substring(0, value.toString().length() - 1) + ",}").getBytes(StandardCharsets.UTF_8));
        if (damage.equals("duplicate-root-key")) Files.write(path, ("{\"version\":1," + value.toString().substring(1)).getBytes(StandardCharsets.UTF_8));
        if (damage.equals("deep-unknown-field")) {
            StringBuilder nested = new StringBuilder("{\"extra\":");
            for (int i = 0; i < 70; i++) nested.append('[');
            nested.append('0'); for (int i = 0; i < 70; i++) nested.append(']');
            nested.append(',').append(value.toString().substring(1)); Files.write(path, nested.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (damage.equals("invalid-utf8")) Files.write(path, new byte[]{(byte) 0xc3, 0x28});
        if (damage.equals("byte-limit")) { byte[] huge = new byte[8 * 1024 * 1024 + 1]; Arrays.fill(huge, (byte) ' '); Files.write(path, huge); }
        byte[] saved = Files.readAllBytes(path); Store damaged = new Store(path);
        check(!damaged.error().isEmpty(), "damage reported: " + damage); equal(0, damaged.entries().length(), "corrupt partial state cannot publish"); equal(0, damaged.history().length(), "corrupt partial history cannot publish");
        check(!damaged.replace(three(), 0) && !damaged.clearQueue() && !damaged.clearHistory(), "corrupt original blocks all commits"); equal("", damaged.enqueue(song("netease", "9"), false), "corrupt original blocks enqueue");
        sameBytes(saved, path, "corrupt original preserved byte for byte");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the artifact fixture directory.");
        dataRoot = new File(args[0]).toPath().toAbsolutePath(); Files.createDirectories(dataRoot);
        run("duplicate occurrences survive reorder/removal/restart", PlaybackQueueStoreTest::duplicatesSurviveRestart);
        run("input and accessor copies cannot mutate committed state", PlaybackQueueStoreTest::copiesCannotMutateState);
        run("next/tail insertion and reorder retain the current occurrence", PlaybackQueueStoreTest::insertionKeepsCursor);
        run("removed current gap survives restart and earlier removal", PlaybackQueueStoreTest::removedCurrentGapRestarts);
        run("move within items before gap", () -> moveAcrossGap(0, 1));
        run("same position before gap", () -> moveAcrossGap(1, 1));
        run("move from after gap to before gap", () -> moveAcrossGap(3, 0));
        run("gap insertion and appended tail continue correctly", PlaybackQueueStoreTest::gapInsertionAndTail);
        Object[][] navigation = {
            {"sequential", true, 2, 1, -1}, {"sequential", false, 2, 1, 0}, {"sequential", false, 0, -1, 2}, {"sequential", true, 0, 1, 1},
            {"loop", true, 2, 1, 0}, {"single", true, 1, 1, 1}, {"single", false, 1, 1, 2}, {"single", false, 2, 1, 0},
            {"sequential", true, -1, 1, 0}, {"loop", false, -1, -1, 2}, {"sequential", true, 0, -1, -1}
        };
        for (Object[] row : navigation) run("navigation " + Arrays.toString(row), () -> navigation((String) row[0], (Boolean) row[1], (Integer) row[2], (Integer) row[3], (Integer) row[4]));
        run("shuffle operates on occurrences and validates navigation", PlaybackQueueStoreTest::shuffleOccurrencePolicy);
        for (String mode : new String[]{"sequential", "loop", "single", "shuffle"}) for (boolean automatic : new boolean[]{true, false})
            run("explicit next priority " + mode + " automatic=" + automatic, () -> explicitNextPriority(mode, automatic));
        run("previous look ahead leaves explicit-next request", PlaybackQueueStoreTest::previousLeavesPriority);
        run("removing one request preserves other requests and gap", PlaybackQueueStoreTest::removalRetainsOtherPriority);
        run("replacement and clear discard explicit-next requests", PlaybackQueueStoreTest::replacingClearingDiscardsPriority);
        run("failed selection save retains cursor and priority", PlaybackQueueStoreTest::failedSelectionRetainsPriority);
        run("older version 1 without priority field remains readable", PlaybackQueueStoreTest::oldVersionOnePriorityOptional);
        run("valid JSON escapes whitespace and integral numbers remain readable", PlaybackQueueStoreTest::validJsonEscapesAndIntegerRepresentations);
        run("cleared queue stays initialized after restart", PlaybackQueueStoreTest::clearedQueueStaysInitialized);
        run("only native stable metadata and missing local paths persist", PlaybackQueueStoreTest::stableMetadataAndMissingLocalFile);
        run("local entries without usable path remain queued", PlaybackQueueStoreTest::localWithoutUsablePathRemainsQueued);
        run("invalid optional metadata discarded without losing valid tracks", PlaybackQueueStoreTest::invalidOptionalMetadataIsDiscarded);
        run("history deduplicates full identity and caps newest 200", PlaybackQueueStoreTest::historyFullIdentityCap);
        run("played timestamps validate and support default clock", PlaybackQueueStoreTest::playedTimePolicy);
        run("failed first save cannot initialize or publish queue", PlaybackQueueStoreTest::failedInitialSaveDoesNotInitialize);
        run("all failed mutations leave committed state and file intact", PlaybackQueueStoreTest::failedMutationsNeverPublish);
        run("brief Windows file contention preserves atomic selection", PlaybackQueueStoreTest::briefFileContentionKeepsAtomicSelection);
        run("invalid operations and successful no-op preserve file", PlaybackQueueStoreTest::invalidOperationsLeaveDisk);
        run("track source identity and title validation", PlaybackQueueStoreTest::trackIdentityPolicy);
        run("queue count and serialized UTF-8 byte limits roll back", PlaybackQueueStoreTest::serializedAndQueueLimits);
        run("maximum 10000 queue is usable after restart", PlaybackQueueStoreTest::maximumQueueRestarts);
        for (String damage : new String[]{"syntax", "trailing-data", "unquoted-key", "single-quotes", "trailing-comma", "duplicate-root-key", "deep-unknown-field", "invalid-utf8", "root-array", "version", "version-type", "entries-type", "entry-type", "duplicate-queue-id", "duplicate-uuid-spelling", "invalid-uuid", "zero-uuid", "track-identity", "missing-current", "current-type", "negative-gap", "gap-type", "fractional-gap", "gap-outside", "cursor-gap-mismatch", "history-type", "history-duplicate", "history-time-negative", "history-time-type", "history-time-fractional", "history-limit", "queue-limit", "byte-limit", "initialized-type", "uninitialized-queue", "priority-type", "priority-duplicate", "priority-orphan", "priority-id-type", "priority-invalid-id"})
            run("preserve corrupt file " + damage, () -> corruptFilePreserved(damage));
        System.out.println("PlaybackQueueStore: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " playback store scenarios failed");
    }
}
