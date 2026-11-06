package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * [admin] Big admin actions spread over ticks, so the server never freezes: adding five hundred folk, building
 * everything, skipping days, running a script. Each job does a small slice a tick, shows a boss bar to whoever started
 * it (and a line at each quarter), can be listed (/village admin jobs) and cancelled (/village admin cancel id).
 *
 * <p>Costs nothing when no job runs: the tick handler returns at once on an empty map. While one runs, its slice is
 * bounded by the job itself (a few folk, a building, a day) and every job gets one slice a tick.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class AdminJobs {

    private AdminJobs() {}

    private static final Logger LOG = LogUtils.getLogger();
    /** At most this many jobs at once: more is a typo or a runaway script. */
    public static final int MOST = 8;

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    /** A job: what it is, who asked, how far along; {@link #tick} does one slice and says whether it is finished. */
    public abstract static class Job {
        final String id = AdminLog.shortId();
        final String label;
        final CommandSourceStack src;
        int total, done;
        boolean cancelled;
        String outcome = "";
        int lastQuarter;
        @Nullable ServerBossEvent bar;

        protected Job(String label, CommandSourceStack src, int total) {
            this.label = label;
            this.src = src;
            this.total = Math.max(1, total);
        }

        /** One slice of the work; true when it is all done (or nothing more can be done). */
        protected abstract boolean tick(MinecraftServer server);

        public String id() { return id; }
        public String label() { return label; }
        public int done() { return done; }
        public int total() { return total; }

        /** "a3f1 adding 50 folk to Oakford 12/50". */
        public String line() {
            return id + " " + label + " " + Math.min(done, total) + "/" + total;
        }

        protected void outcome(String s) { outcome = s; }
    }

    /** A job of {@code total} units, {@code perTick} a tick, each unit done by {@code unit} (false: stop early). */
    public static Job units(String label, CommandSourceStack src, int total, int perTick, IntPredicate unit,
                            @Nullable java.util.function.Consumer<Job> after) {
        return new Job(label, src, total) {
            @Override
            protected boolean tick(MinecraftServer server) {
                for (int i = 0; i < perTick && done < total; i++) {
                    if (!unit.test(done)) return finish();
                    done++;
                }
                return done >= total && finish();
            }

            private boolean finish() {
                if (after != null) after.accept(this);
                return true;
            }
        };
    }

    /** Start a job; null with the reason in {@code why[0]} when too many run already. */
    @Nullable
    public static Job start(Job job, String[] why) {
        if (JOBS.size() >= MOST) {
            why[0] = "Too many admin jobs running (" + MOST + "); wait, or /village admin cancel one.";
            return null;
        }
        JOBS.put(job.id, job);
        ServerPlayer p = job.src.getPlayer();
        if (p != null) {
            job.bar = new ServerBossEvent(Component.literal(job.label), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10);
            job.bar.setProgress(0F);
            job.bar.addPlayer(p);
        }
        return job;
    }

    public static List<Job> running() {
        return new ArrayList<>(JOBS.values());
    }

    @Nullable
    public static Job get(String id) {
        return JOBS.get(id);
    }

    /** Cancel a job by id; false when there is none. What it already did stays done. */
    public static boolean cancel(String id) {
        Job j = JOBS.get(id);
        if (j == null) return false;
        j.cancelled = true;
        return true;
    }

    /** Tests: run every job's slices now, up to so many ticks' worth. */
    public static void runForTests(MinecraftServer server, int ticks) {
        for (int i = 0; i < ticks && !JOBS.isEmpty(); i++) tickAll(server);
    }

    public static void resetForTests() {
        for (Job j : JOBS.values()) if (j.bar != null) j.bar.removeAllPlayers();
        JOBS.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) return;
        tickAll(event.getServer());
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        resetForTests();
    }

    private static void tickAll(MinecraftServer server) {
        for (Job j : new ArrayList<>(JOBS.values())) {
            boolean over;
            if (j.cancelled) {
                over = true;
                j.outcome("cancelled after " + Math.min(j.done, j.total) + " of " + j.total + " (what was done stays done)");
            } else {
                try {
                    over = j.tick(server);
                } catch (RuntimeException e) {
                    LOG.error("[MCA-ADMIN] job {} ({}) failed", j.id, j.label, e);
                    j.outcome("stopped: " + e);
                    over = true;
                }
            }
            progress(j);
            if (over) finish(server, j);
        }
    }

    private static void progress(Job j) {
        float f = Math.min(1F, j.done / (float) j.total);
        if (j.bar != null) {
            j.bar.setProgress(f);
            j.bar.setName(Component.literal(j.label + " " + Math.min(j.done, j.total) + "/" + j.total));
        }
        int quarter = (int) (f * 4);
        if (quarter > j.lastQuarter && quarter < 4) {
            j.lastQuarter = quarter;
            j.src.sendSystemMessage(Component.literal("[admin] " + j.line()).withStyle(ChatFormatting.GRAY));
        }
    }

    private static void finish(MinecraftServer server, Job j) {
        JOBS.remove(j.id);
        if (j.bar != null) j.bar.removeAllPlayers();
        String said = j.label + ": " + (j.outcome.isEmpty() ? "done (" + Math.min(j.done, j.total) + "/" + j.total + ")" : j.outcome);
        j.src.sendSystemMessage(Component.literal("[admin] " + said).withStyle(j.cancelled ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        AdminLog.append(server, AdminAccess.who(j.src), "job " + j.id, j.label, j.cancelled ? "cancelled" : said);
    }
}
