package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [life28] How many of a town's grown folk are standing about doing nothing, and why: the share of them by day with no
 * act on their bodies (FolkAction NONE) and their legs still, by trade and by what they say they are about, and how long
 * the longest of them has stood so. A town that reads as dead on film is a town with half its folk stood still.
 *
 * <p>Counted for the tests ({@link #count}), and logged for a developer's own server every so many seconds when the game
 * was started with {@code -Dmcassistant.census=SECONDS} ("[MCA-CENSUS]" lines), nowhere else.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class IdleCensus {

    private IdleCensus() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final int EVERY = Integer.getInteger("mcassistant.census", 0) * 20;

    /** Since when each folk has stood still with nothing to do (game time), by folk. */
    private static final Map<UUID, Long> STILL_SINCE = new ConcurrentHashMap<>();

    /** One count of a town: its grown folk awake by day, how many stood idle, and the rest by what they were at. */
    public record Count(int folk, int idle, int working, int walking, int living, long longestStill, String longestWho,
                        Map<String, Integer> idleWhy, Map<String, Integer> idleTrade, List<String> idleLines,
                        Map<String, int[]> byTrade, List<String> unstaffed, int spareIdle) {
        public double share() { return folk == 0 ? 0.0 : idle / (double) folk; }

        public String line() {
            return folk + " grown folk awake: " + working + " at work, " + walking + " on the move, " + living + " at their own"
                + " life, " + idle + " stood idle (" + Math.round(100.0 * share()) + "%); longest still " + longestStill / 20 + "s"
                + (longestWho.isEmpty() ? "" : " (" + longestWho + ")") + " | idle by why " + idleWhy + " | by trade " + idleTrade
                + " | works waiting on a hand " + unstaffed + " with " + spareIdle + " idle hands";
        }

        /** By trade: assigned, at work (its act, or on its way to a job), filling its time, stood idle. */
        public String table() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, int[]> e : byTrade.entrySet()) {
                int[] n = e.getValue();
                sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(n[0]).append('/').append(n[1]).append('/')
                    .append(n[2]).append('/').append(n[3]);
            }
            return "trade assigned/working/pastime/idle: " + sb;
        }
    }

    /** Is this folk stood idle just now: awake, grown, its legs still, and no act on its body? */
    public static boolean idle(VillageFolkEntity f) {
        if (f.isBaby() || f.isSleeping() || f.getVehicle() != null || !f.isAlive()) return false;
        return FolkAction.now(f) == FolkAction.Act.NONE && still(f);
    }

    static boolean still(VillageFolkEntity f) {
        return f.getNavigation().isDone() && f.getDeltaMovement().horizontalDistanceSqr() < 0.002;
    }

    /** The town counted now (and each folk's time stood still brought up to date). */
    public static Count count(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        int folk = 0, idle = 0, working = 0, walking = 0, living = 0;
        long longest = 0;
        String who = "";
        Map<String, Integer> why = new TreeMap<>(), trade = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        Map<String, int[]> byTrade = new TreeMap<>();
        int spareIdle = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.level() != level || !f.isAlive() || f.isBaby() || f.isShowcase()) continue;
            if (f.isSleeping()) { STILL_SINCE.remove(f.getUUID()); continue; }
            folk++;
            FolkAction.Act act = FolkAction.now(f);
            int[] row = byTrade.computeIfAbsent(f.stationTask().name(), k -> new int[4]);
            row[0]++;
            if (!Busywork.doing(f).isEmpty()) row[2]++;
            else if (idle(f)) row[3]++;
            else if (act.work() || f.peekJob() != null || TownJobs.busy(f)) row[1]++;
            if (Busywork.spare(f) && f.offWorkWhy().isEmpty()) spareIdle++;
            if (idle(f)) {
                idle++;
                long since = STILL_SINCE.computeIfAbsent(f.getUUID(), k -> now);
                if (now - since > longest) { longest = now - since; who = f.clientName() + " " + f.stationTask(); }
                String w = f.offWorkWhy();
                why.merge(w.isEmpty() ? "at work" : w, 1, Integer::sum);
                trade.merge(f.stationTask().name(), 1, Integer::sum);
                String d = "{" + Busywork.why(f) + "} " + f.debugLine();
                lines.add(d.length() > 400 ? d.substring(0, 400) : d);
                continue;
            }
            STILL_SINCE.remove(f.getUUID());
            if (act.work()) working++;
            else if (act == FolkAction.Act.NONE) walking++;
            else living++;
        }
        return new Count(folk, idle, working, walking, living, longest, who, why, trade, lines, byTrade,
            TownJobs.unstaffed(village, now), spareIdle);
    }

    /** Folk forgotten (a test's town gone). */
    public static void resetForTests() {
        STILL_SINCE.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (EVERY <= 0) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY != 0) return;
        com.jrpetty.mcassistant.Guard.run("idle census", () -> {
            for (Villages.Village v : Villages.every()) {
                ServerLevel level = server.getLevel(v.dim());
                if (level == null || !level.isLoaded(v.centre())) continue;
                Count c = count(level, v.id());
                if (c.folk() == 0) continue;
                long t = level.getDayTime() % 24000L;
                int hour = (int) ((t / 1000L + 6) % 24), minute = (int) (t % 1000L * 60 / 1000L);
                LOG.info("[MCA-CENSUS] {} day {} {}:{} {}", Villages.name(v.id()), level.getDayTime() / 24000L,
                    String.format("%02d", hour), String.format("%02d", minute), c.line());
                LOG.info("[MCA-CENSUS]   {}", c.table());
                Map<String, Integer> seen = new HashMap<>();
                for (String l : c.idleLines()) {
                    LOG.info("[MCA-CENSUS]   idle: {}", l);
                    if (seen.merge("n", 1, Integer::sum) >= 60) break;
                }
            }
        });
    }
}
