package org.floatmusic.player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

// Durable native playback state. Audio preparation owns when setCurrent and
// recordPlayed are called; looking ahead never starts audio or consumes a request.
final class PlaybackQueueStore {
    private static final int QUEUE_LIMIT = 10000;
    private static final int HISTORY_LIMIT = 200;
    private static final int FILE_LIMIT = 8 * 1024 * 1024;
    private static final Pattern NUMBER_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern SONG_MID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final Pattern OCCURRENCE_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final class State {
        final List<JSONObject> entries;
        final List<JSONObject> history;
        final List<String> priorityEntries;
        String currentEntryId = "";
        int gapIndex;
        boolean initialized;
        State() {
            entries = new ArrayList<>();
            history = new ArrayList<>();
            priorityEntries = new ArrayList<>();
        }
        State(State original) {
            // Track objects are immutable after insertion, and never exposed.
            entries = new ArrayList<>(original.entries);
            history = new ArrayList<>(original.history);
            priorityEntries = new ArrayList<>(original.priorityEntries);
            currentEntryId = original.currentEntryId;
            gapIndex = original.gapIndex;
            initialized = original.initialized;
        }
    }

    private Path path;
    private State state = new State();
    private String error = "";
    private boolean storageBlocked;

    PlaybackQueueStore(File file) {
        try {
            if (file == null) throw new IOException("Missing playback data location");
            path = file.toPath().toAbsolutePath().normalize();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.size(path) > FILE_LIMIT) throw new IOException("Playback data too large");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > FILE_LIMIT) throw new IOException("Playback data too large");
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) text = text.substring(1);
            new StrictJson(text).validate();
            JSONTokener tokens = new JSONTokener(text);
            Object root = tokens.nextValue();
            if (!(root instanceof JSONObject) || tokens.nextClean() != 0) throw new IOException("Invalid playback document");
            State restored = restore((JSONObject) root);
            if (restored == null) throw new IOException("Invalid playback schema");
            state = restored;
        } catch (Exception failure) {
            storageBlocked = true;
            error = "播放数据无法安全读取，已保留原文件并停止写入。";
        }
    }

    synchronized JSONArray entries() { return copies(state.entries); }
    synchronized JSONArray history() { return copies(state.history); }
    synchronized String currentEntryId() { return state.currentEntryId; }
    synchronized boolean initialized() { return state.initialized; }
    synchronized String error() { return error; }

    synchronized JSONObject entry(String entryId) {
        int index = entryIndex(state.entries, entryId);
        return index < 0 ? null : copy(state.entries.get(index));
    }

    synchronized boolean replaceQueue(JSONArray tracks, int currentIndex) {
        if (tracks == null) return reject("歌曲信息或来源编号无效，播放队列未修改。");
        if (tracks.length() > QUEUE_LIMIT) return reject("播放队列最多保留 10000 项，本次修改未保存。");
        if (currentIndex < -1 || currentIndex >= tracks.length()) return reject("播放队列的当前项位置无效。");
        State candidate = new State(state);
        candidate.entries.clear();
        candidate.currentEntryId = "";
        candidate.priorityEntries.clear();
        candidate.gapIndex = 0;
        candidate.initialized = true;
        Set<UUID> known = new HashSet<>();
        try {
            for (int i = 0; i < tracks.length(); i++) {
                Object value = tracks.opt(i);
                JSONObject track = value instanceof JSONObject ? normalizeTrack((JSONObject) value) : null;
                if (track == null) return reject("歌曲信息或来源编号无效，播放队列未修改。");
                track.put("queueId", newEntryId(known));
                candidate.entries.add(track);
            }
        } catch (JSONException failure) { return reject("歌曲信息或来源编号无效，播放队列未修改。"); }
        if (currentIndex >= 0) {
            candidate.currentEntryId = candidate.entries.get(currentIndex).optString("queueId");
            candidate.gapIndex = currentIndex + 1;
        }
        return commit(candidate);
    }

    synchronized String enqueue(JSONObject input, boolean playNext) {
        if (state.entries.size() >= QUEUE_LIMIT) {
            reject("播放队列已达到 10000 项，无法继续添加。");
            return "";
        }
        JSONObject track = normalizeTrack(input);
        if (track == null) { reject("歌曲信息或来源编号无效，无法加入播放队列。"); return ""; }
        State candidate = new State(state);
        Set<UUID> known = new HashSet<>();
        for (JSONObject queued : candidate.entries) known.add(occurrence(queued.optString("queueId")));
        String entryId = newEntryId(known);
        try { track.put("queueId", entryId); }
        catch (JSONException failure) { reject("歌曲信息或来源编号无效，无法加入播放队列。"); return ""; }
        int current = entryIndex(state.entries, state.currentEntryId);
        int insertion = playNext ? (current >= 0 ? current + 1 : candidate.gapIndex) : candidate.entries.size();
        candidate.entries.add(insertion, track);
        if (playNext) candidate.priorityEntries.add(0, entryId);
        candidate.initialized = true;
        if (current >= 0) candidate.gapIndex = current + 1;
        return commit(candidate) ? entryId : "";
    }

    synchronized boolean setCurrent(String entryId) {
        int index = entryIndex(state.entries, entryId);
        if (index < 0) return reject("该播放队列项已不存在。");
        State candidate = new State(state);
        candidate.currentEntryId = entryId;
        candidate.gapIndex = index + 1;
        candidate.priorityEntries.remove(entryId);
        return commit(candidate);
    }

    synchronized boolean remove(String entryId) {
        int index = entryIndex(state.entries, entryId);
        if (index < 0) return reject("该播放队列项已不存在。");
        State candidate = new State(state);
        candidate.entries.remove(index);
        candidate.priorityEntries.remove(entryId);
        if (entryId.equals(candidate.currentEntryId)) {
            candidate.currentEntryId = "";
            candidate.gapIndex = index;
        } else if (!candidate.currentEntryId.isEmpty()) {
            candidate.gapIndex = entryIndex(candidate.entries, candidate.currentEntryId) + 1;
        } else if (index < candidate.gapIndex) {
            candidate.gapIndex--;
        }
        return commit(candidate);
    }

    synchronized boolean move(String entryId, int to) {
        int from = entryIndex(state.entries, entryId);
        if (from < 0 || to < 0 || to >= state.entries.size()) return reject("播放队列项或目标位置无效。");
        State candidate = new State(state);
        JSONObject moved = candidate.entries.remove(from);
        candidate.entries.add(to, moved);
        if (candidate.currentEntryId.isEmpty()) {
            // Reordering one side of the removed-current gap leaves it intact.
            if (from < candidate.gapIndex && to >= candidate.gapIndex) candidate.gapIndex--;
            else if (from >= candidate.gapIndex && to < candidate.gapIndex) candidate.gapIndex++;
        } else {
            candidate.gapIndex = entryIndex(candidate.entries, candidate.currentEntryId) + 1;
        }
        return commit(candidate);
    }

    synchronized boolean clearQueue() {
        State candidate = new State(state);
        candidate.entries.clear();
        candidate.currentEntryId = "";
        candidate.priorityEntries.clear();
        candidate.gapIndex = 0;
        candidate.initialized = true;
        return commit(candidate);
    }

    synchronized boolean recordPlayed(JSONObject input, long playedAtMs) {
        JSONObject track = normalizeTrack(input);
        if (track == null || playedAtMs < -1) return reject("歌曲信息或播放时间无效，历史未修改。");
        if (playedAtMs == -1) playedAtMs = System.currentTimeMillis();
        try { track.put("playedAtMs", playedAtMs); }
        catch (JSONException failure) { return reject("歌曲信息或播放时间无效，历史未修改。"); }
        State candidate = new State(state);
        String id = track.optString("id");
        for (int i = 0; i < candidate.history.size(); i++) {
            if (id.equals(candidate.history.get(i).optString("id"))) { candidate.history.remove(i); break; }
        }
        candidate.history.add(0, track);
        if (candidate.history.size() > HISTORY_LIMIT) candidate.history.remove(candidate.history.size() - 1);
        return commit(candidate);
    }

    synchronized boolean clearHistory() {
        State candidate = new State(state);
        candidate.history.clear();
        return commit(candidate);
    }

    synchronized String nextEntry(int direction, String mode, boolean automatic) {
        int count = state.entries.size();
        if (count == 0 || direction == 0 || !("sequential".equals(mode) || "loop".equals(mode) || "single".equals(mode) || "shuffle".equals(mode))) return "";
        if (direction > 0 && !state.priorityEntries.isEmpty()) return state.priorityEntries.get(0);
        int current = entryIndex(state.entries, state.currentEntryId);
        int next;
        if ("shuffle".equals(mode)) {
            if (count == 1) next = 0;
            else if (current >= 0) {
                next = ThreadLocalRandom.current().nextInt(count - 1);
                if (next >= current) next++;
            } else next = ThreadLocalRandom.current().nextInt(count);
        } else if ("single".equals(mode) && automatic && current >= 0) {
            next = current;
        } else {
            next = current >= 0 ? current + (direction > 0 ? 1 : -1) : state.gapIndex + (direction > 0 ? 0 : -1);
            if (next < 0 || next >= count) {
                if ("sequential".equals(mode) && automatic) return "";
                next = next < 0 ? count - 1 : 0;
            }
        }
        return state.entries.get(next).optString("queueId");
    }

    private boolean reject(String message) { error = message; return false; }

    private boolean commit(State candidate) {
        if (storageBlocked) return reject("原播放数据无法安全读取，当前修改未保存，请先检查 playback.json。");
        if (sameState(state, candidate)) { error = ""; return true; }
        Path temporary = null;
        try {
            byte[] bytes = document(candidate).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > FILE_LIMIT) return reject("播放数据超过 8 MiB，本次修改未保存。");
            Path parent = path.getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "playback-", ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.getFD().sync();
            }
            // A same-directory atomic replacement is mandatory; no direct-write
            // fallback may truncate a previous valid session on failure.
            atomicReplace(temporary, path);
            state = candidate;
            error = "";
            return true;
        } catch (Exception failure) {
            return reject("播放数据保存失败，请检查磁盘空间或写入权限。本次修改已撤销。");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException | RuntimeException ignored) {}
            }
        }
    }

    private static void atomicReplace(Path temporary, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException occupied) {
                // Desktop JVM fixtures and file scanners can briefly hold the
                // previous file. Retry that same atomic operation for at most
                // 120 ms, never publishing state or truncating the destination.
                if (attempt >= 3 || !Files.isRegularFile(target)) throw occupied;
                try { Thread.sleep(20L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Playback save interrupted", interrupted);
                }
            }
        }
    }

    private static boolean sameState(State a, State b) {
        return a.entries.equals(b.entries) && a.history.equals(b.history) && a.priorityEntries.equals(b.priorityEntries)
            && a.currentEntryId.equals(b.currentEntryId) && a.gapIndex == b.gapIndex && a.initialized == b.initialized;
    }

    private static JSONObject document(State value) throws JSONException {
        JSONArray entries = new JSONArray(), history = new JSONArray(), priorities = new JSONArray();
        for (JSONObject track : value.entries) entries.put(track);
        for (JSONObject track : value.history) history.put(track);
        for (String id : value.priorityEntries) priorities.put(id);
        return new JSONObject().put("version", 1).put("initialized", value.initialized).put("entries", entries).put("history", history)
            .put("currentEntryId", value.currentEntryId).put("gapIndex", value.gapIndex).put("priorityEntries", priorities);
    }

    private static State restore(JSONObject root) throws JSONException {
        Long version = integer(root.opt("version")), gap = integer(root.opt("gapIndex"));
        Object initialized = root.opt("initialized"), current = root.opt("currentEntryId");
        Object rawEntries = root.opt("entries"), rawHistory = root.opt("history"), rawPriority = root.opt("priorityEntries");
        if (version == null || version != 1 || !(initialized instanceof Boolean) || !(current instanceof String)
            || !(rawEntries instanceof JSONArray) || !(rawHistory instanceof JSONArray)
            || (root.has("priorityEntries") && !(rawPriority instanceof JSONArray))) return null;
        JSONArray entries = (JSONArray) rawEntries, history = (JSONArray) rawHistory;
        JSONArray priorities = rawPriority instanceof JSONArray ? (JSONArray) rawPriority : new JSONArray();
        if (entries.length() > QUEUE_LIMIT || history.length() > HISTORY_LIMIT || gap == null || gap < 0 || gap > entries.length() || priorities.length() > entries.length()) return null;
        State restored = new State();
        restored.initialized = (Boolean) initialized;
        restored.currentEntryId = (String) current;
        restored.gapIndex = gap.intValue();
        Set<UUID> occurrences = new HashSet<>();
        Set<String> entryIds = new HashSet<>(), historyIds = new HashSet<>(), priorityIds = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            Object value = entries.opt(i);
            if (!(value instanceof JSONObject)) return null;
            JSONObject raw = (JSONObject) value;
            Object rawId = raw.opt("queueId");
            if (!(rawId instanceof String)) return null;
            String id = (String) rawId;
            UUID occurrence = occurrence(id);
            JSONObject track = normalizeTrack(raw);
            if (occurrence == null || !occurrences.add(occurrence) || track == null) return null;
            track.put("queueId", id);
            entryIds.add(id);
            restored.entries.add(track);
        }
        int selected = entryIndex(restored.entries, restored.currentEntryId);
        if ((!restored.currentEntryId.isEmpty() && (selected < 0 || restored.gapIndex != selected + 1))
            || (!restored.initialized && (!restored.entries.isEmpty() || !restored.currentEntryId.isEmpty() || restored.gapIndex != 0))) return null;
        for (int i = 0; i < priorities.length(); i++) {
            Object value = priorities.opt(i);
            if (!(value instanceof String) || !entryIds.contains(value) || !priorityIds.add((String) value)) return null;
            restored.priorityEntries.add((String) value);
        }
        for (int i = 0; i < history.length(); i++) {
            Object value = history.opt(i);
            if (!(value instanceof JSONObject)) return null;
            JSONObject raw = (JSONObject) value;
            Long playedAt = integer(raw.opt("playedAtMs"));
            JSONObject track = normalizeTrack(raw);
            if (playedAt == null || playedAt < 0 || track == null || !historyIds.add(track.optString("id"))) return null;
            track.put("playedAtMs", playedAt);
            restored.history.add(track);
        }
        return restored;
    }

    private static Long integer(Object value) {
        if (!(value instanceof Number)) return null;
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }

    private static JSONObject normalizeTrack(JSONObject input) {
        if (input == null || !(input.opt("id") instanceof String) || !(input.opt("source") instanceof String) || !(input.opt("name") instanceof String)) return null;
        String source = ((String) input.opt("source")).trim(), id = ((String) input.opt("id")).trim(), name = ((String) input.opt("name")).trim();
        if (name.isEmpty() || !id.startsWith(source + ":")) return null;
        String identity = id.substring(source.length() + 1);
        if (identity.isEmpty()) return null;
        try {
            JSONObject track = new JSONObject().put("id", id).put("source", source).put("name", name);
            if ("local".equals(source)) {
                Object rawPath = input.opt("path");
                if (rawPath instanceof String && !((String) rawPath).isEmpty() && new File((String) rawPath).isAbsolute()) track.put("path", rawPath);
            } else if ("netease".equals(source) || "tencent".equals(source) || "kuwo".equals(source)) {
                Object rawSong = input.opt("songId");
                String songId = rawSong == null || rawSong == JSONObject.NULL ? "" : rawSong.toString().trim();
                if (songId.isEmpty()) songId = identity;
                if (!id.equals(source + ":" + songId) || !("tencent".equals(source) ? SONG_MID : NUMBER_ID).matcher(songId).matches()) return null;
                track.put("songId", songId);
                Object rawDuration = input.opt("duration");
                if (rawDuration instanceof Number || rawDuration instanceof String) {
                    try {
                        double seconds = Double.parseDouble(rawDuration.toString());
                        if (!Double.isNaN(seconds) && !Double.isInfinite(seconds) && seconds > 0 && seconds <= 86400) track.put("duration", seconds);
                    } catch (NumberFormatException ignored) {}
                }
            } else return null;
            // Match TrackState.sessionTrack's durable field whitelist without
            // depending on its resolver or any Android framework classes.
            for (String key : new String[]{"artist", "sourceName"}) {
                Object value = input.opt(key);
                if (value instanceof String) track.put(key, ((String) value).trim());
            }
            return track;
        } catch (Exception invalid) { return null; }
    }

    private static int entryIndex(List<JSONObject> entries, String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < entries.size(); i++) if (id.equals(entries.get(i).optString("queueId"))) return i;
        return -1;
    }

    private static UUID occurrence(String id) {
        if (id == null) return null;
        String value = id;
        if (value.startsWith("{") && value.endsWith("}")) value = value.substring(1, value.length() - 1);
        if (!OCCURRENCE_ID.matcher(value).matches()) return null;
        UUID uuid = UUID.fromString(value);
        return uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0 ? null : uuid;
    }

    private static String newEntryId(Set<UUID> known) {
        UUID id;
        do { id = UUID.randomUUID(); } while (!known.add(id));
        return id.toString();
    }

    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (JSONException invalid) { throw new IllegalStateException("Invalid internal playback state", invalid); }
    }

    private static JSONArray copies(List<JSONObject> values) {
        JSONArray result = new JSONArray();
        for (JSONObject value : values) result.put(copy(value));
        return result;
    }

    // Android's JSONTokener accepts JavaScript-style syntax. Validate actual
    // JSON before using it, and bound nesting before its recursive parser runs.
    private static final class StrictJson {
        private final String text;
        private int position;
        StrictJson(String text) { this.text = text; }
        void validate() {
            value(0);
            whitespace();
            if (position != text.length()) invalid();
        }
        private void value(int depth) {
            if (depth > 64) invalid();
            whitespace();
            char token = current();
            if (token == '{') object(depth);
            else if (token == '[') array(depth);
            else if (token == '"') string(false);
            else if (token == 't') literal("true");
            else if (token == 'f') literal("false");
            else if (token == 'n') literal("null");
            else number();
        }
        private void object(int depth) {
            expect('{');
            whitespace();
            if (take('}')) return;
            Set<String> keys = new HashSet<>();
            do {
                whitespace();
                String key = string(true);
                if (!keys.add(key)) invalid();
                whitespace();
                expect(':');
                value(depth + 1);
                whitespace();
                if (take('}')) return;
                expect(',');
            } while (true);
        }
        private void array(int depth) {
            expect('[');
            whitespace();
            if (take(']')) return;
            do {
                value(depth + 1);
                whitespace();
                if (take(']')) return;
                expect(',');
            } while (true);
        }
        private String string(boolean capture) {
            expect('"');
            StringBuilder decoded = capture ? new StringBuilder() : null;
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (character == '"') return capture ? decoded.toString() : "";
                if (character < 0x20) invalid();
                if (character == '\\') {
                    if (position >= text.length()) invalid();
                    character = text.charAt(position++);
                    switch (character) {
                        case '"': case '\\': case '/': break;
                        case 'b': character = '\b'; break;
                        case 'f': character = '\f'; break;
                        case 'n': character = '\n'; break;
                        case 'r': character = '\r'; break;
                        case 't': character = '\t'; break;
                        case 'u':
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                if (position >= text.length()) invalid();
                                char hex = text.charAt(position++);
                                int digit = hex >= '0' && hex <= '9' ? hex - '0' : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10 : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                                if (digit < 0) invalid();
                                code = code * 16 + digit;
                            }
                            character = (char) code;
                            break;
                        default: invalid();
                    }
                }
                if (capture) decoded.append(character);
            }
            invalid();
            return "";
        }
        private void number() {
            take('-');
            if (!take('0')) {
                if (current() < '1' || current() > '9') invalid();
                digits();
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
        }
        private void digits() {
            int start = position;
            while (current() >= '0' && current() <= '9') position++;
            if (start == position) invalid();
        }
        private void literal(String value) {
            if (!text.startsWith(value, position)) invalid();
            position += value.length();
        }
        private void whitespace() {
            while (current() == ' ' || current() == '\t' || current() == '\n' || current() == '\r') position++;
        }
        private char current() { return position < text.length() ? text.charAt(position) : 0; }
        private boolean take(char expected) {
            if (current() != expected) return false;
            position++;
            return true;
        }
        private void expect(char expected) { if (!take(expected)) invalid(); }
        private void invalid() { throw new IllegalArgumentException("Invalid playback JSON at " + position); }
    }
}
