package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [tp43] A folk never visibly jumps from one place to another while a player could see it.
 *
 * <p>A folk may be put somewhere in an instant (a "blink") only when nobody can see it happen: no player (spectators
 * included: a film is often shot from one) within {@link #SIGHT} blocks across the ground in its level. That is about
 * the farthest a player with the render and entity distances turned up for a film sees a body of a folk's size, and
 * half again the 64 the game draws one at by default. Within sight, anything that used to put a folk somewhere for
 * convenience (to bed, to work, out of the cage, unstuck) walks, climbs, digs a step, rides with a visible glide or
 * waits instead: every such site asks {@link #mayJump(Entity, String)} first. A true last resort for safety (in the
 * void, in lava, shut in a wall with nowhere a step away) may still blink, seen or not, through
 * {@link #safety(Entity, String)}, so it is counted apart.
 *
 * <p>The jump detector (off in play, {@code /folkblink on} or {@code -Dmca.blink=true}): VillageFolkEntity reports every
 * instant move of more than {@link #HOP} blocks that is not the game's own movement (walking, a fall, a shove) or the
 * cage carrying it, with the reason its site gave ({@link #note}) or, where none did, the method that moved it; and
 * once a tick any change of place over {@link #HOP} that came from nowhere it saw (riding, falls and knockback
 * excused). Counted by cause, for miners and for the other folk apart, seen or not, with the folk-ticks to make rates.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Blink {

    private Blink() {}

    /** A player within this many blocks across the ground, in the same level, could see the folk. */
    public static final double SIGHT = 96.0;
    /** A change of place of more than this in one tick, not the game's own movement, is a jump. */
    public static final double HOP = 1.5;

    /** The detector on (counting): off in normal play. */
    static volatile boolean on = Boolean.getBoolean("mca.blink");

    /** A place a player is pretended to stand (a headless run's watcher), across the ground in a level. */
    record Watch(ResourceKey<Level> dim, double x, double z) {}
    private static final List<Watch> WATCH = new CopyOnWriteArrayList<>();
    /** On the game tests' server, only these players count (the stand-in players other tests leave behind do not). */
    private static final Set<UUID> TEST_PLAYERS = ConcurrentHashMap.newKeySet();

    /** The reason given for the next instant move of each folk (by entity id), and the tick it was given. */
    private static final Map<Integer, String> WHY = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> WHY_AT = new ConcurrentHashMap<>();

    /** Counts by cause: {miner seen, miner unseen, other seen, other unseen}. */
    private static final Map<String, long[]> BY = new ConcurrentHashMap<>();
    /** Folk-ticks counted: {miner, miner in sight, other, other in sight}. */
    private static final long[] TICKS = new long[4];
    /** Jumps held back (asked {@link #mayJump} within sight and told no), by reason. */
    private static final Map<String, long[]> HELD = new ConcurrentHashMap<>();
    /** The last seen jumps, for the tests and the log. */
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();
    /** The jumps held back to a place, the first few hundred, for a look at why they were wanted. */
    private static final List<String> HELD_LINES = new CopyOnWriteArrayList<>();
    private static long seenAll, excusedSeen;

    // ------------------------------------------------------------------ the rule

    /** Could a player see this folk? (Any player within {@link #SIGHT} across the ground in its level, or a watcher.) */
    public static boolean seen(Entity e) {
        return seenAt(e.level(), e.getX(), e.getZ(), 0);
    }

    /** Could a player see this spot (within {@link #SIGHT} and a margin across the ground)? */
    public static boolean seenAt(Level level, double x, double z, double margin) {
        if (level.isClientSide) return true;
        double r = SIGHT + margin, r2 = r * r;
        boolean test = level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer;
        for (Player p : level.players()) {
            if (test && !TEST_PLAYERS.contains(p.getUUID())) continue;
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        if (!WATCH.isEmpty()) {
            for (Watch w : WATCH) {
                if (w.dim() != level.dimension()) continue;
                double dx = w.x() - x, dz = w.z() - z;
                if (dx * dx + dz * dz <= r2) return true;
            }
        }
        return false;
    }

    /** May this folk be put somewhere in an instant now (nobody can see it)? */
    public static boolean mayJump(Entity e) {
        return !seen(e);
    }

    /** May this folk be put somewhere in an instant now, for this reason? Yes: the reason noted for the detector. No (it is
     *  in sight): the jump counted as held back, and the caller walks, climbs or waits instead. */
    public static boolean mayJump(Entity e, String why) {
        if (seen(e)) {
            if (on) HELD.computeIfAbsent(why, k -> new long[1])[0]++;
            return false;
        }
        note(e, why);
        return true;
    }

    /** As {@link #mayJump(Entity, String)}, for a jump to a place: neither end in sight. */
    public static boolean mayJumpTo(Entity e, double x, double z, String why) {
        if (seen(e) || seenAt(e.level(), x, z, 0)) {
            if (on) {
                HELD.computeIfAbsent(why, k -> new long[1])[0]++;
                if (HELD_LINES.size() < 300 && e instanceof AssistantEntity a)
                    HELD_LINES.add(e.level().getGameTime() + " " + a.getAssistantName() + " (" + a.stationTask().name().toLowerCase() + ") at "
                        + e.blockPosition().toShortString() + " for " + (int) x + "," + (int) z + ": " + why + " | " + a.brainNoteForBooks());
            }
            return false;
        }
        note(e, why);
        return true;
    }

    /** The reason for this folk's next instant move (for the detector; nothing when it is off). */
    public static void note(Entity e, String why) {
        if (!on) return;
        WHY.put(e.getId(), why);
        WHY_AT.put(e.getId(), e.level().getGameTime());
    }

    /** A jump for safety's sake (the void, lava, a wall with nowhere a step away), allowed seen or not, counted apart. */
    public static void safety(Entity e, String why) {
        note(e, "!" + why);
    }

    // ------------------------------------------------------------------ the detector

    public static boolean on() {
        return on;
    }

    public static void setOn(boolean b) {
        on = b;
    }

    /** VillageFolkEntity: an instant move from (fx, fy, fz) to where it now is, by a call outside the game's movement. */
    static void moved(VillageFolkEntity f, double fx, double fy, double fz, @Nullable String via) {
        if (!on || f.tickCount <= 0 || f.isRemoved() || f.level().isClientSide || f.isPassenger()) return;   // riding: the vehicle's movement
        double dx = f.getX() - fx, dy = f.getY() - fy, dz = f.getZ() - fz;
        if (dx * dx + dy * dy + dz * dz <= HOP * HOP) return;
        String why = WHY.remove(f.getId());
        Long at = WHY_AT.remove(f.getId());
        if (why != null && at != null && f.level().getGameTime() - at > 2) why = null;    // a stale note: not this move's
        if (why == null) why = via != null ? via : site();
        f.blinkTick = f.tickCount;
        if (why.startsWith("~")) return;                                    // carried (the cage moving with it inside): no jump
        count(f, why, fx, fy, fz, site());
    }

    /** VillageFolkEntity, once a tick: a change of place since the last tick from nowhere the hooks saw. */
    static void tickCheck(VillageFolkEntity f) {
        if (!on || f.level().isClientSide) return;
        boolean miner = miner(f);
        boolean seen = seen(f);
        synchronized (TICKS) {
            TICKS[miner ? 0 : 2]++;
            if (seen) TICKS[miner ? 1 : 3]++;
        }
        double[] last = f.blinkLast;
        boolean riding = f.isPassenger() || f.blinkRode;
        f.blinkRode = f.isPassenger();
        f.blinkLast = new double[]{ f.getX(), f.getY(), f.getZ() };
        if (last == null || riding || f.blinkTick >= f.tickCount - 1) return;
        double dx = f.getX() - last[0], dy = f.getY() - last[1], dz = f.getZ() - last[2];
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= HOP) return;
        if (dy < 0 && flat <= 1.0) return;                                   // a fall
        if (f.hurtTime > 0 || f.getDeltaMovement().lengthSqr() > 0.5) return; // knocked back, flung
        count(f, "untraced (between ticks)", last[0], last[1], last[2], "");
    }

    /** Tests driving a thing by hand (the cage run tick by tick): the once-a-tick look, now. */
    public static void tickCheckForTests(VillageFolkEntity f) {
        tickCheck(f);
    }

    private static boolean miner(VillageFolkEntity f) {
        return f.stationTask() == AssistantEntity.StationTask.MINE;
    }

    private static void count(VillageFolkEntity f, String why, double fx, double fy, double fz, String at) {
        boolean seen = seen(f) || seenAt(f.level(), fx, fz, 0);
        boolean miner = miner(f);
        BY.computeIfAbsent(why, k -> new long[4])[(miner ? 0 : 2) + (seen ? 0 : 1)]++;
        if (!seen) return;
        boolean excused = why.startsWith("!");
        if (excused) excusedSeen++; else seenAll++;
        double d = Math.sqrt(f.distanceToSqr(fx, fy, fz));
        String line = String.format("%s %s (%s) %.1f,%.1f,%.1f -> %.1f,%.1f,%.1f (%.1f): %s", f.level().getGameTime(), f.getAssistantName(),
            miner ? "miner" : f.stationTask().name().toLowerCase(), fx, fy, fz, f.getX(), f.getY(), f.getZ(), d, why + (at.isEmpty() || at.equals(why) ? "" : " @" + at));
        if (SEEN.size() < 400) SEEN.add(line);                            // (a safety jump's line marked by its "!")
    }

    /** The method outside the folk that moved it (the stack read only on a jump, never each tick). */
    private static String site() {
        return StackWalker.getInstance().walk(s -> s
            .filter(fr -> fr.getClassName().startsWith("com.jrpetty.mcassistant")
                && !fr.getClassName().endsWith(".Blink")
                && !(fr.getClassName().endsWith(".VillageFolkEntity") && (fr.getMethodName().equals("teleportTo")
                    || fr.getMethodName().equals("moveTo") || fr.getMethodName().equals("setPos"))))
            .findFirst()
            .map(fr -> fr.getClassName().substring(fr.getClassName().lastIndexOf('.') + 1) + "." + fr.getMethodName() + ":" + fr.getLineNumber())
            .orElse("vanilla"));
    }

    /** [tp43] The method that called into the folk (for a reason's label; the stack read only with the detector on). */
    public static String caller() {
        return StackWalker.getInstance().walk(s -> s
            .filter(fr -> fr.getClassName().startsWith("com.jrpetty.mcassistant")
                && !fr.getClassName().endsWith(".Blink") && !fr.getClassName().endsWith(".AssistantEntity")
                && !fr.getClassName().endsWith(".VillageFolkEntity"))
            .findFirst()
            .map(fr -> fr.getClassName().substring(fr.getClassName().lastIndexOf('.') + 1) + "." + fr.getMethodName())
            .orElse("?"));
    }

    // ------------------------------------------------------------------ readings

    public static void reset() {
        BY.clear();
        HELD.clear();
        SEEN.clear();
        HELD_LINES.clear();
        synchronized (TICKS) { java.util.Arrays.fill(TICKS, 0); }
        seenAll = 0;
        excusedSeen = 0;
    }

    /** Jumps seen (unexcused) since the last reset. */
    public static long seenJumps() {
        return seenAll;
    }

    /** Safety jumps seen since the last reset. */
    public static long excusedSeenJumps() {
        return excusedSeen;
    }

    /** The seen jumps, one line each (at most 400 kept). */
    public static List<String> seenLines() {
        return new ArrayList<>(SEEN);
    }

    /** The jumps held back to a place (the first few hundred), one line each. */
    public static List<String> heldLines() {
        return new ArrayList<>(HELD_LINES);
    }

    /** Unexcused jumps by cause: {miner seen, miner unseen, other seen, other unseen}. */
    public static Map<String, long[]> byCause() {
        return new TreeMap<>(BY);
    }

    /** {miner folk-ticks, miner in sight, other folk-ticks, other in sight}. */
    public static long[] ticks() {
        synchronized (TICKS) { return TICKS.clone(); }
    }

    /** A table: causes ranked by seen jumps, with rates per folk-hour in sight (a folk-hour: one folk for an hour of play,
     *  72000 ticks), miners and the others apart. */
    public static String report() {
        long[] t = ticks();
        double mh = t[0] / 72000.0, ms = t[1] / 72000.0, oh = t[2] / 72000.0, os = t[3] / 72000.0;
        StringBuilder b = new StringBuilder();
        b.append(String.format("folk-hours: miners %.2f (%.2f in sight), others %.2f (%.2f in sight); seen jumps %d, safety jumps seen %d%n",
            mh, ms, oh, os, seenAll, excusedSeen));
        b.append("cause | miner seen | miner unseen | other seen | other unseen | miner seen/folk-h in sight | other seen/folk-h in sight\n");
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(BY.entrySet());
        rows.sort((x, y) -> Long.compare(y.getValue()[0] + y.getValue()[2], x.getValue()[0] + x.getValue()[2]) != 0
            ? Long.compare(y.getValue()[0] + y.getValue()[2], x.getValue()[0] + x.getValue()[2])
            : Long.compare(y.getValue()[1] + y.getValue()[3], x.getValue()[1] + x.getValue()[3]));
        for (Map.Entry<String, long[]> e : rows) {
            long[] c = e.getValue();
            b.append(String.format("%s | %d | %d | %d | %d | %.2f | %.2f%n", e.getKey(), c[0], c[1], c[2], c[3],
                ms > 0 ? c[0] / ms : 0.0, os > 0 ? c[2] / os : 0.0));
        }
        if (!HELD.isEmpty()) {
            b.append("held back in sight:");
            for (Map.Entry<String, long[]> e : new TreeMap<>(HELD).entrySet()) b.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]);
            b.append('\n');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ watchers

    /** Tests: this (stand-in) player counts as a player on the game tests' server. */
    public static void watchForTests(Player p) {
        TEST_PLAYERS.add(p.getUUID());
    }

    public static void unwatchForTests(Player p) {
        TEST_PLAYERS.remove(p.getUUID());
    }

    /** A pretend player here (a headless run's watcher). */
    public static void watch(Level level, double x, double z) {
        WATCH.add(new Watch(level.dimension(), x, z));
    }

    /** [tp43] Is a pretend player (a headless run's watcher) within this many blocks across the ground? */
    static boolean watchedAt(Level level, double x, double z, double r) {
        if (WATCH.isEmpty()) return false;
        for (Watch w : WATCH) {
            if (w.dim() != level.dimension()) continue;
            double dx = w.x() - x, dz = w.z() - z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    public static void unwatchAll() {
        WATCH.clear();
        TEST_PLAYERS.clear();
    }

    // ------------------------------------------------------------------ the command and the log

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("folkblink").requires(src -> src.hasPermission(2))
            .executes(ctx -> say(ctx.getSource(), report()))
            .then(Commands.literal("on").executes(ctx -> { on = true; return say(ctx.getSource(), "the jump detector on"); }))
            .then(Commands.literal("off").executes(ctx -> { on = false; return say(ctx.getSource(), "the jump detector off"); }))
            .then(Commands.literal("reset").executes(ctx -> { reset(); return say(ctx.getSource(), "counts reset"); }))
            .then(Commands.literal("seen").executes(ctx -> say(ctx.getSource(), String.join("\n", seenLines()))))
            .then(Commands.literal("held").executes(ctx -> say(ctx.getSource(), String.join("\n", HELD_LINES))))
            .then(Commands.literal("miners").executes(ctx -> say(ctx.getSource(), miners(ctx.getSource().getLevel()))))
            .then(Commands.literal("beds").executes(ctx -> say(ctx.getSource(), beds(ctx.getSource().getLevel()))))
            .then(Commands.literal("unwatch").executes(ctx -> { WATCH.clear(); return say(ctx.getSource(), "watchers cleared"); }))
            .then(Commands.literal("watch")
                .then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("z", DoubleArgumentType.doubleArg())
                    .executes(ctx -> {
                        ServerLevel level = ctx.getSource().getLevel();
                        double x = DoubleArgumentType.getDouble(ctx, "x"), z = DoubleArgumentType.getDouble(ctx, "z");
                        watch(level, x, z);
                        return say(ctx.getSource(), "a watcher at " + (int) x + ", " + (int) z + " in " + level.dimension().location());
                    })))));
    }

    /** [tp43] Each town's miners: how many, at the mine (and below), in the water; and how the way to the mine stands. */
    static String miners(ServerLevel level) {
        StringBuilder b = new StringBuilder();
        for (Villages.Village v : Villages.every()) {
            net.minecraft.core.BlockPos site = TownMine.siteOf(v.id());
            if (site == null || !v.dim().equals(level.dimension())) continue;
            int all = 0, atMine = 0, below = 0, wet = 0;
            int ground = level.hasChunk(site.getX() >> 4, site.getZ() >> 4)
                ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site.getX(), site.getZ()) : 64;
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a.stationTask() != AssistantEntity.StationTask.MINE) continue;
                all++;
                double dx = a.getX() - site.getX(), dz = a.getZ() - site.getZ();
                boolean near = dx * dx + dz * dz <= 160 * 160 && (dx * dx + dz * dz <= 24 * 24 || a.getY() < ground - 4);   // at the pithead, or in the works
                if (near) atMine++;
                if (near && a.getY() < ground - 4) below++;
                if (a.isInWater()) wet++;
            }
            b.append(Villages.name(v.id())).append(": miners ").append(all).append(", at the mine ").append(atMine).append(" (below ").append(below)
                .append("), in the water ").append(wet).append("; ").append(MineWay.status(v.id())).append('\n');
        }
        return b.toString();
    }

    /** [tp46] Each town's night: how many asleep (on a floor), with a bed, and why the rest are not (Bedside.whyAwake). */
    static String beds(ServerLevel level) {
        StringBuilder b = new StringBuilder();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int all = 0, asleep = 0, floor = 0, bed = 0;
            java.util.Map<String, Integer> why = new java.util.TreeMap<>();
            StringBuilder each = new StringBuilder();
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                all++;
                if (a.bedPos() != null) bed++;
                if (a.isSleeping()) { asleep++; if (Bedside.onAFloor(a)) floor++; continue; }
                String w = Bedside.whyAwake(a);
                why.merge(Bedside.kind(w), 1, Integer::sum);
                net.minecraft.core.BlockPos bp = a.bedPos();
                each.append("  ").append(a.getAssistantName()).append(" (").append(w).append(") at ").append(a.blockPosition().toShortString())
                    .append(bp == null ? "" : " bed " + bp.toShortString() + " " + (int) Math.sqrt(bp.distSqr(a.blockPosition())) + " off").append('\n');
            }
            if (all == 0) continue;
            b.append(Villages.name(v.id())).append(": ").append(asleep).append(" of ").append(all).append(" asleep (").append(floor)
                .append(" on a floor), ").append(bed).append(" with a bed; awake: ").append(why).append('\n').append(each);
        }
        return b.toString();
    }

    private static int say(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** On, the table into the log every five minutes. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (!on) return;
        if (e.getServer().getTickCount() % 6000 == 0) {
            for (String l : report().split("\n")) com.mojang.logging.LogUtils.getLogger().info("[MCA-BLINK] {}", l);
        }
        if (e.getServer().getTickCount() % 200 == 0) {
            long now = e.getServer().overworld().getGameTime();
            WHY_AT.entrySet().removeIf(x -> now - x.getValue() > 200 || x.getValue() > now);
            WHY.keySet().retainAll(WHY_AT.keySet());
        }
    }
}
