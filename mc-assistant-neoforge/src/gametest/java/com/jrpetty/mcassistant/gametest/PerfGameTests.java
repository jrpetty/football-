package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.UUID;

/**
 * Profiling only, never required: towns founded side by side in the scale band (x 1840000 on, z 66000), every
 * feature live, run flat out while the server's cost per tick is logged ([PERF] lines). Run it under Java Flight
 * Recorder to see where the time goes (scratchpad perf/scale.sh).
 *
 * <p>System properties: {@code perf.towns} the founding sizes ("250,100"; none: the probe passes at once, so the
 * suite's own run costs nothing); {@code perf.player} the town a stand-in
 * player stands in (-1: none); {@code perf.days} how long with the player there; {@code perf.awayDays} how long after
 * that with the player far off from every town; {@code perf.warm} days left out of the totals at the start of each
 * phase (the founding's settling in).
 *
 * <p>Before and after in the same run, on the same machine at the same moment: with {@code perf.alt} the run
 * alternates blocks of that many ticks between the towns' level of detail ("lod") and every town held awake in full
 * as before it ("all-awake": TownLod forced FULL, FolkLod and the path budget off), and totals each apart, the first {@code perf.settle} ticks of a
 * block (the towns waking or going to sleep) left out of them and reported on their own.
 *
 * <p>Each window logs the wall ms per tick (avg and worst), the server thread's own CPU ms per tick (the honest
 * figure on a busy machine), the chunks the mod forces, the chunks loaded, the folk entities loaded and the heap.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PerfGameTests {

    private static final int X0 = 1840000, Z = 66000, SPACING = 1200, FAR_X = 1858000;
    private static final int WINDOW = 1200;
    /** The founding sizes; with none given (the suite's own run) the probe passes at once and costs nothing. */
    private static final String TOWNS = System.getProperty("perf.towns", "");
    private static final int[] FOLK = TOWNS.isEmpty() ? new int[0] : parse(TOWNS);
    private static final int PLAYER_IN = Integer.getInteger("perf.player", 0);
    private static final double DAYS = Double.parseDouble(System.getProperty("perf.days", "1"));
    private static final double AWAY_DAYS = Double.parseDouble(System.getProperty("perf.awayDays", "0.5"));
    private static final double WARM = Double.parseDouble(System.getProperty("perf.warm", "0.1"));
    /** Ticks in each block of the before/after alternation (0: none, the config's level of detail throughout). */
    private static final long ALT = Long.getLong("perf.alt", 12000L);
    /** Ticks at the start of each block left out of its totals: the towns waking or falling asleep. */
    private static final long SETTLE = Long.getLong("perf.settle", 3000L);
    private static final String[] MODE = { "lod", "all-awake" };

    private static int[] parse(String s) {
        String[] p = s.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim());
        return out;
    }

    /** One phase's totals: wall ns, worst wall ns, server-thread CPU ns, ticks. */
    private static final class Totals {
        long wall, worst, cpu, ticks;

        void add(long wallNs, long cpuNs) {
            wall += wallNs;
            worst = Math.max(worst, wallNs);
            cpu += cpuNs;
            ticks++;
        }

        String line() {
            return String.format("mspt avg %.2f worst %.1f cpu %.2f over %d ticks", wall / 1e6 / Math.max(1, ticks),
                worst / 1e6, cpu / 1e6 / Math.max(1, ticks), ticks);
        }

        void clear() { wall = worst = cpu = ticks = 0; }
    }

    @GameTest(template = "empty", timeoutTicks = 2_000_000, required = false, batch = "perf01_towns")
    public static void perf01_towns(GameTestHelper helper) {
        if (FOLK.length == 0) {
            helper.succeed();                         // a probe, run by hand (scratchpad perf/scale.sh), not by the suite
            return;
        }
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Kit.reset(level);
        Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.Police.standDownForTests(false);
        com.jrpetty.mcassistant.entity.Identity.liveForTests();
        com.jrpetty.mcassistant.entity.Perks.liveForTests(true);
        com.jrpetty.mcassistant.entity.Interviews.liveForTests(true);
        com.jrpetty.mcassistant.entity.Weave.liveForTests(true);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        // A real server's distances, not the test server's: what a player keeps loaded and ticking.
        server.getPlayerList().setViewDistance(10);
        server.getPlayerList().setSimulationDistance(10);
        BlockPos[] hearts = new BlockPos[FOLK.length];
        int[] outer = new int[FOLK.length];
        for (int i = 0; i < FOLK.length; i++) {
            int cx = X0 + i * SPACING;
            outer[i] = FoundingPlan.coreRadius(FOLK[i]) + FoundingPlan.BAND_MAX + 32;
            Kit.hold(level, cx, Z, outer[i]);
            Kit.prepare(level, cx, Z, outer[i]);
            hearts[i] = Kit.surface(level, cx, Z);
            Kit.log("perf found " + FOLK[i] + ": " + Kit.command(level, "village found " + FOLK[i] + " " + cx + " " + Z));
        }
        server.tickRateManager().setTickRate(10000.0F);
        Run run = new Run(helper, level, hearts, outer);
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (run.over) return;
            try {
                run.step();
            } catch (RuntimeException ex) {
                run.over = true;
                throw ex;
            }
        };
        // Not helper.onEachTick: that books one entry per tick up to the timeout, which the framework walks every tick.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final BlockPos[] hearts;
        final int[] outer;
        final UUID[] ids;
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final Totals window = new Totals(), settling = new Totals();
        final Totals[] present = { new Totals(), new Totals() }, away = { new Totals(), new Totals() };
        int mode = -1;
        int phase;               // 0 founding, 1 player present, 2 player away
        long began, lastCpu = -1, windowWall;
        ServerPlayer player;
        boolean over;

        Run(GameTestHelper helper, ServerLevel level, BlockPos[] hearts, int[] outer) {
            this.helper = helper;
            this.level = level;
            this.server = level.getServer();
            this.hearts = hearts;
            this.outer = outer;
            this.ids = new UUID[hearts.length];
        }

        void step() {
            long t = helper.getTick();
            if (phase == 0) {
                found(t);
                return;
            }
            long cpuNow = threads.getCurrentThreadCpuTime();
            long cpu = lastCpu < 0 ? 0 : cpuNow - lastCpu;
            lastCpu = cpuNow;
            long[] times = server.getTickTimesNanos();
            long ns = times[(server.getTickCount() - 1 + times.length) % times.length];
            long ran = t - began;
            if (ns > 150_000_000L) Kit.log(String.format("[PERF] slow tick %.0f ms (phase %d, day %.3f)", ns / 1e6, phase, ran / 24000.0));
            window.add(ns, cpu);
            int want = ALT <= 0 ? 0 : (int) ((ran / ALT) % 2);
            if (want != mode) {
                mode = want;
                com.jrpetty.mcassistant.entity.TownLod.liveForTests(true);
                com.jrpetty.mcassistant.entity.FolkLod.liveForTests(mode == 0);
                com.jrpetty.mcassistant.entity.TownLod.forceForTests(server, mode == 1 ? com.jrpetty.mcassistant.entity.TownLod.Lod.FULL : null);
                Kit.log("[PERF] phase " + phase + " day " + String.format("%.2f", ran / 24000.0) + ": " + MODE[mode]);
            }
            long warm = (long) (WARM * 24000L);
            boolean settled = ALT <= 0 ? ran > warm : ran % ALT >= SETTLE;
            if (settled) (phase == 1 ? present : away)[mode].add(ns, cpu);
            else if (ran > warm) settling.add(ns, cpu);
            if (ran % WINDOW == 0) report(ran);
            long length = (long) ((phase == 1 ? DAYS : AWAY_DAYS) * 24000L);
            if (ran < length) return;
            if (phase == 1 && AWAY_DAYS > 0) {
                if (player != null) player.teleportTo(FAR_X + 0.5, 200, Z + 0.5);
                phase = 2;
                began = t;
                mode = -1;
                return;
            }
            for (int m = 0; m < 2; m++) {
                Kit.log("[PERF] TOTAL player present, " + MODE[m] + ": " + present[m].line());
                Kit.log("[PERF] TOTAL player away, " + MODE[m] + ": " + away[m].line());
            }
            Kit.log("[PERF] TOTAL waking and going to sleep: " + settling.line());
            com.jrpetty.mcassistant.entity.TownLod.forceForTests(server, null);
            com.jrpetty.mcassistant.entity.TownLod.liveForTests(false);
            com.jrpetty.mcassistant.entity.FolkLod.liveForTests(false);
            if (player != null) server.getPlayerList().remove(player);
            over = true;
            server.tickRateManager().setTickRate(20.0F);
            helper.runAfterDelay(1, helper::succeed);      // from the framework's tick, not this listener
        }

        /** Wait out the foundings, then let go of the test's own holds and set the player down. */
        void found(long t) {
            boolean any = false;
            for (BlockPos h : hearts) any |= Founding.near(level, h, 8);
            if (t % 1000 == 0) Kit.log("perf founding @" + t + " " + Founding.status(server));
            if (any) return;
            for (int i = 0; i < hearts.length; i++) {
                Villages.Village v = Villages.nearest(level, hearts[i], 64);
                if (v == null) { helper.fail("no village at " + hearts[i]); over = true; return; }
                ids[i] = v.id();
                Villages.ageForTests(v.id(), i == 0 ? Villages.Age.DIAMOND : Villages.Age.IRON);
                Kit.log("perf town " + Villages.name(v.id()) + " of " + Villages.headcount(v.id()));
                // The test's holds off: from here the mod's own chunk loading (and the player's) decides what ticks.
                for (int x = (hearts[i].getX() - outer[i]) >> 4; x <= (hearts[i].getX() + outer[i]) >> 4; x++)
                    for (int z = (hearts[i].getZ() - outer[i]) >> 4; z <= (hearts[i].getZ() + outer[i]) >> 4; z++)
                        level.setChunkForced(x, z, false);
            }
            if (PLAYER_IN >= 0 && PLAYER_IN < hearts.length) {
                player = helper.makeMockServerPlayerInLevel();
                // As a modded client: the mod's payloads (the folk's speech) are sent to it, not refused.
                net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(player.connection.getConnection());
                BlockPos at = hearts[PLAYER_IN];
                player.teleportTo(level, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 0, 0);
            }
            phase = 1;
            began = t;
            windowWall = System.nanoTime();
        }

        void report(long ran) {
            int folk = 0;
            StringBuilder heads = new StringBuilder();
            for (UUID id : ids) {
                heads.append(Villages.headcount(id)).append(' ');
                folk += AssistantEntity.countFor(id);
            }
            int entities = 0;
            for (var ignored : level.getAllEntities()) entities++;
            Runtime rt = Runtime.getRuntime();
            long heapMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
            double tps = window.ticks * 1e9 / Math.max(1, System.nanoTime() - windowWall);
            Kit.log(String.format("[PERF] phase %d %s day %.2f %s tps %.0f | forced %d loaded %d | folk entities %d all %d"
                    + " | heads %s| heap %d MB | paths put off %d, walks travelled %d", phase, MODE[Math.max(0, mode)], ran / 24000.0, window.line(), tps, ChunkLoad.forcedCount(),
                level.getChunkSource().getLoadedChunksCount(), folk, entities, heads, heapMb,
                com.jrpetty.mcassistant.entity.FolkNavigation.deferredInAll(),
                com.jrpetty.mcassistant.entity.FolkNavigation.travelledInAll()));
            window.clear();
            windowWall = System.nanoTime();
        }
    }
}
