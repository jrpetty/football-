package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.entity.AdminTools.Done;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.AdminArgs;
import com.jrpetty.mcassistant.village.AdminSpec;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * [admin] Scripts of admin commands, for repeatable test setups: {@code <world>/mcassistant/admin-scripts/<name>.txt},
 * one command a line (with or without its slash), {@code #} for a comment, and three words of their own:
 * <pre>
 *   wait 5                  five seconds
 *   waitfor 60 [town:x]     until the town (nearest, or named) has sixty folk, three minutes at most
 *   say Two towns at war.   a line to whoever ran it
 * </pre>
 * Each line waits for the jobs the line before it started. The ready-made scenes (/village admin scenario) are the
 * same scripts, shipped in the mod (data/mc_assistant/admin_scenarios); two examples are copied into the world's
 * folder the first time the scripts are listed or run.
 */
public final class AdminScripts {

    private AdminScripts() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final String DIR = "admin-scripts";
    /** The example scripts the mod ships, copied to the world's folder once. */
    private static final List<String> EXAMPLES = List.of("iron-age-war", "big-city");
    /** Ticks a second, and the most a "waitfor" waits (three minutes). */
    private static final int SECOND = 20, MOST_WAIT = 180 * SECOND;
    /** Lines a script may have (a runaway or a wrong file). */
    private static final int MOST_LINES = 200;

    public static Done run(AdminRunner.Ctx c, String name) {
        List<String> lines = fromWorld(c.server(), name);
        if (lines == null) return Done.no("No script called " + name + " in <world>/mcassistant/admin-scripts. Scripts: " + String.join(", ", names(c.server())));
        return start(c, "script " + name, lines);
    }

    public static Done scenario(AdminRunner.Ctx c, String name) {
        List<String> lines = resource("/data/mc_assistant/admin_scenarios/" + name.toLowerCase(Locale.ROOT) + ".txt");
        if (lines == null) return Done.no("No scenario called " + name + ". One of " + String.join(", ", AdminSpec.SCENARIOS));
        return start(c, "scenario " + name, lines);
    }

    /** The script's lines run one after another as a job. */
    static Done start(AdminRunner.Ctx c, String label, List<String> raw) {
        List<String> lines = new ArrayList<>();
        for (String l : raw) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("#")) lines.add(t.startsWith("/") ? t.substring(1) : t);
        }
        if (lines.isEmpty()) return Done.no(label + " has nothing to run.");
        if (lines.size() > MOST_LINES) return Done.no(label + " has more than " + MOST_LINES + " lines.");
        AdminJobs.Job job = new ScriptJob(label, c, lines);
        String[] why = { "" };
        if (AdminJobs.start(job, why) == null) return Done.no(why[0]);
        return Done.said("JOB " + job.id() + " " + label + ": " + lines.size() + " lines; /village admin cancel " + job.id() + " to stop it");
    }

    /** A script running: a line at a time, waiting on what each line started. */
    static final class ScriptJob extends AdminJobs.Job {
        private final AdminRunner.Ctx c;
        private final List<String> lines;
        private final Set<String> waitingOn = new HashSet<>();
        private int waitTicks, waited;
        @Nullable private String waitFor;
        private int waitCount;

        ScriptJob(String label, AdminRunner.Ctx c, List<String> lines) {
            super(label, c.src(), lines.size());
            this.c = c;
            this.lines = lines;
        }

        @Override
        protected boolean tick(MinecraftServer server) {
            if (waitTicks > 0) { waitTicks--; return false; }
            waitingOn.removeIf(id -> AdminJobs.get(id) == null);
            if (!waitingOn.isEmpty()) return false;
            if (waitFor != null) {
                Villages.Village v = waitFor.isEmpty() ? AdminRunner.nearest(c) : AdminRunner.town(waitFor);
                if ((v == null || Villages.headcount(v.id()) < waitCount) && ++waited < MOST_WAIT) return false;
                waitFor = null;
            }
            if (done >= lines.size()) {
                outcome("ran " + lines.size() + " lines");
                return true;
            }
            String line = lines.get(done++);
            String[] w = line.split("\\s+", 3);
            switch (w[0].toLowerCase(Locale.ROOT)) {
                case "wait" -> waitTicks = Math.max(1, Math.min(600, num(w.length > 1 ? w[1] : "1"))) * SECOND;
                case "waitfor" -> {
                    waitCount = num(w.length > 1 ? w[1] : "1");
                    String sel = w.length > 2 && w[2].startsWith("town:") ? w[2].substring(5) : "";
                    waitFor = AdminArgs.town(sel).kind() == AdminArgs.TownSel.Kind.NEAREST ? "" : sel;
                    waited = 0;
                }
                case "say" -> c.src().sendSystemMessage(net.minecraft.network.chat.Component.literal("[script] " + (w.length > 1 ? line.substring(4) : "")));
                default -> {
                    Set<String> before = new HashSet<>();
                    for (AdminJobs.Job j : AdminJobs.running()) before.add(j.id());
                    try {
                        server.getCommands().performPrefixedCommand(c.src(), line);
                    } catch (RuntimeException e) {
                        LOG.warn("[MCA-ADMIN] script line failed: {}: {}", line, e.toString());
                    }
                    for (AdminJobs.Job j : AdminJobs.running()) if (!before.contains(j.id()) && j != this) waitingOn.add(j.id());
                }
            }
            return false;
        }
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Nullable
    static List<String> fromWorld(MinecraftServer server, String name) {
        try {
            Path f = folder(server).resolve(name.replaceAll("[^A-Za-z0-9_-]", "") + ".txt");
            return Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The scripts folder, with the examples copied in the first time. */
    static Path folder(MinecraftServer server) throws IOException {
        Path dir = AdminLog.folder(server, DIR);
        for (String ex : EXAMPLES) {
            Path f = dir.resolve(ex + ".txt");
            if (Files.exists(f)) continue;
            List<String> lines = resource("/data/mc_assistant/admin_scripts/" + ex + ".txt");
            if (lines != null) Files.write(f, lines, StandardCharsets.UTF_8);
        }
        return dir;
    }

    public static List<String> names(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder(server))) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).sorted().forEach(n -> out.add(n.substring(0, n.length() - 4)));
        } catch (IOException ignored) {
            // no folder: no scripts
        }
        return out;
    }

    @Nullable
    static List<String> resource(String path) {
        try (InputStream in = AdminScripts.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        } catch (IOException e) {
            return null;
        }
    }
}
