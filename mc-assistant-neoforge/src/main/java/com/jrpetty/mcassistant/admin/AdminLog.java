package com.jrpetty.mcassistant.admin;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [admin] The admin log: every admin action, refused, dry-run or done, one line each, appended to
 * {@code <world>/mcassistant/admin-log.txt} ("2026-10-14 12:03:55 | Steve | ages set iron | Oakford | ok: ..."), and
 * the last few hundred kept in memory for /village admin log and the panel's recent actions. The town's chronicle
 * gets its own "By decree" line (AdminRunner); this is the operator's record.
 */
public final class AdminLog {

    private AdminLog() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Lines kept in memory (the file keeps everything). */
    private static final int KEEP = 300;
    /** Command lines kept per player for the panel's "run again". */
    private static final int RECENT = 8;

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final Map<String, Deque<String>> RECENT_BY = new LinkedHashMap<>();
    private static boolean warned;

    /** The toolkit's folder in this world, made if need be. */
    public static Path folder(MinecraftServer server, @Nullable String sub) throws IOException {
        Path p = server.getWorldPath(LevelResource.ROOT).resolve("mcassistant");
        if (sub != null) p = p.resolve(sub);
        Files.createDirectories(p);
        return p;
    }

    /** Write one line: who did what to which town, and how it came out. Returns the line. */
    public static synchronized String append(MinecraftServer server, String who, String action, String target, String result) {
        String line = LocalDateTime.now().format(TIME) + " | " + who + " | " + action + " | " + target + " | " + oneLine(result);
        LINES.addLast(line);
        while (LINES.size() > KEEP) LINES.pollFirst();
        try {
            Files.writeString(folder(server, null).resolve("admin-log.txt"), line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) LOG.warn("[MCA-ADMIN] could not write the admin log: {}", e.toString());   // once, not every action
            warned = true;
        }
        return line;
    }

    /** Note a command line a player ran, for the panel's "run again". */
    public static synchronized void ran(String who, String commandLine) {
        Deque<String> d = RECENT_BY.computeIfAbsent(who, k -> new ArrayDeque<>());
        d.remove(commandLine);
        d.addFirst(commandLine);
        while (d.size() > RECENT) d.pollLast();
    }

    public static synchronized List<String> recent(String who) {
        Deque<String> d = RECENT_BY.get(who);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    /** The last {@code n} lines, oldest first. */
    public static synchronized List<String> last(int n) {
        List<String> all = new ArrayList<>(LINES);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    public static synchronized void resetForTests() {
        LINES.clear();
        RECENT_BY.clear();
    }

    private static String oneLine(String s) {
        String t = s.replace('\n', ' ').replace('|', '/').trim();
        return t.length() > 400 ? t.substring(0, 397) + "..." : t;
    }

    /** A short id for a job or a token, readable aloud. */
    static String shortId() {
        return UUID.randomUUID().toString().substring(0, 4);
    }
}
