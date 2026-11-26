package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.VillageSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [grow54] Settlers sent for: how a small village is brought up to a working size.
 *
 * <p>A village of eight or twelve (the size the world grew them at before, and the size a colony still goes out at)
 * has too few hands to build its first houses, feed itself and staff its trades all at once: every one of its beds is
 * taken from the first night, so no child is born till a house is up; the fields it can work grow less than it eats, so
 * the larder says no child either; and nobody from outside comes to a town of under fifteen. It sat at its founding
 * size for a week and more, and asleep (nobody near) it learned to grow at nothing a day and went on doing so.
 *
 * <p>So a village under {@code villageSettlerBase} folk sends word for settlers. Every few minutes, while it is at
 * peace and not starving, a party of one to four comes on the road: set down out of anybody's sight (never within
 * {@link Blink#SIGHT} of a player, and never inside the town), they walk in to its edge, the way any newcomer does
 * (Newcomers), and are taken in the moment they arrive, with no vote: the town asked for them. Each brings what a
 * founder brings (VillageSpawner.starterKit: food, tools, seed, a chest for the stores) and its own bedding, laid at
 * the camp by the heart till a house is built. The town's board says so: "Settlers arriving: 3 on the road". At the
 * base size it stops, and the town grows by its children and the odd newcomer, as ever.
 *
 * <p>Villages founded under older builds with eight to twelve are brought up the same way, as soon as their heart is
 * loaded (a player near, or the town on its own day: TownWake).
 */
public final class Settlers {

    private Settlers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The party's mark (Newcomers' party record): sent for, taken in on arrival. */
    public static final String FLAG = "settlers";
    /** Ticks between one party setting out and the next, to one town: an eighth of a day. */
    static final long EVERY = 3000L;
    /** The most in one party. */
    public static final int PARTY_MOST = 4;
    /** How much further than {@link Blink#SIGHT} from any player a party is set down. */
    static final double MARGIN = 16.0;
    /** How long a far spot asked to be loaded is waited for before another is looked for. */
    static final long SPOT_WAIT = 1200L;
    /** How far out past the town's streets a party may be set down. */
    static final int FURTHEST = 176;

    private static final Block[] BEDDING = { Blocks.WHITE_BED, Blocks.BROWN_BED, Blocks.GREEN_BED, Blocks.ORANGE_BED,
        Blocks.LIGHT_GRAY_BED, Blocks.CYAN_BED };

    /** On the game tests' server they come only where a test asks for them (every other test's town keeps its size). */
    private static boolean liveInTests;
    private static long everyForTests = -1;

    /** Tests: settlers sent for on the game tests' server too (and the gap between parties, -1 for the usual). */
    public static void liveForTests(boolean on, long every) {
        liveInTests = on;
        everyForTests = every;
    }

    /** The size a village is brought up to by settlers (0: none sent for). */
    public static int base() {
        return Math.max(0, AssistantConfig.villageSettlerBase());
    }

    /** Is this village under the base size, and so sending for settlers? */
    public static boolean boosting(@Nullable UUID village) {
        int b = base();
        return village != null && b > 0 && Villages.headcount(village) < b;
    }

    private static boolean live(ServerLevel level) {
        return liveInTests || !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer);
    }

    private static long every() {
        return everyForTests > 0 ? everyForTests : EVERY;
    }

    /** How many settlers are on the road to this town now (set out, not yet taken in). */
    public static int onTheRoad(UUID village) {
        int n = 0;
        String id = village.toString();
        for (String pid : CivicRecord.parties().getAllKeys()) {
            CompoundTag p = Newcomers.party(pid);
            if (!p.getBoolean(FLAG) || !id.equals(p.getString("target"))) continue;
            String st = p.getString("stage");
            if (st.equals("road") || st.equals("away") || st.equals("camp")) n += Newcomers.size(p);
        }
        return n;
    }

    /**
     * Every five seconds for each town whose heart is loaded (Newcomers.tick): under the base size, at peace and fed,
     * and the last party an eighth of a day gone, the next sets out.
     */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag town = CivicRecord.town(id);
        if (!live(level) || !boosting(id)) {
            letGoOfSpot(level, v, town);
            return;
        }
        if (Wars.footing(id) != Wars.Footing.PEACE || Raids.underAlarm(id) || Leader.plan(id) == Leader.Plan.FAMINE) return;
        if (Founding.near(level, v.centre(), 16)) return;                 // its ground still being made ready
        int road = onTheRoad(id);
        int want = base() - Villages.headcount(id) - road;
        if (want <= 0) return;
        if (Villages.worldHeadcount() + road + 1 > AssistantConfig.villageWorldCap()) return;
        long now = level.getGameTime();
        if (town.contains("stLast") && now >= town.getLong("stLast") && now - town.getLong("stLast") < every()) return;
        BlockPos start = startFar(level, v, town);
        if (start == null) return;
        int n = Math.min(PARTY_MOST, want);
        String pid = Newcomers.outside(level, v, level.getDayTime() / 24000L, n, start, true);
        if (pid == null) return;
        town.putLong("stLast", now);
        CivicRecord.changed();
        LOG.info("[MCA-SETTLERS] {} ({} folk, {} on the road, wants {}): {} settlers set out from {}, {} blocks out",
            Villages.name(id), Villages.headcount(id), road, base(), n, start.toShortString(),
            (int) Math.sqrt(start.distSqr(v.centre())));
    }

    /**
     * Where a party from outside is set down: on dry ground out past the town's streets, on ground whose creatures
     * tick (so they walk from the moment they stand), and out of every player's sight. The nearest such spot on any
     * bearing; null if there is none loaded (Newcomers' own newcomers come another day).
     */
    @Nullable
    public static BlockPos start(ServerLevel level, Villages.Village v) {
        return look(level, v, null);
    }

    /** As start, and failing a loaded spot, a far one asked to be loaded (its chunk kept awake till they set out). */
    @Nullable
    private static BlockPos startFar(ServerLevel level, Villages.Village v, CompoundTag town) {
        long now = level.getGameTime();
        if (town.contains("stSpot")) {
            BlockPos s = BlockPos.of(town.getLong("stSpot"));
            if (level.isPositionEntityTicking(s)) {
                BlockPos g = ground(level, s.getX(), s.getZ());
                if (g != null && !Blink.seenAt(level, g.getX() + 0.5, g.getZ() + 0.5, MARGIN)) {
                    letGoOfSpot(level, v, town);
                    return g;
                }
            }
            if (now - town.getLong("stSpotAt") < SPOT_WAIT && now >= town.getLong("stSpotAt")) return null;
            letGoOfSpot(level, v, town);
        }
        BlockPos[] far = new BlockPos[1];
        BlockPos g = look(level, v, far);
        if (g != null || far[0] == null) return g;
        ChunkLoad.setLoaded(level, ticket(v.id()), far[0], 0, true);
        town.putLong("stSpot", far[0].asLong());
        town.putLong("stSpotAt", now);
        CivicRecord.changed();
        return null;
    }

    private static void letGoOfSpot(ServerLevel level, Villages.Village v, CompoundTag town) {
        if (!town.contains("stSpot")) return;
        ChunkLoad.setLoaded(level, ticket(v.id()), BlockPos.of(town.getLong("stSpot")), 0, false);
        town.remove("stSpot");
        town.remove("stSpotAt");
        CivicRecord.changed();
    }

    private static UUID ticket(UUID village) {
        return UUID.nameUUIDFromBytes(("mca-settlers-" + village).getBytes());
    }

    /** The nearest unseen, loaded spot out past the town; the first unseen spot not loaded into {@code far}. */
    @Nullable
    private static BlockPos look(ServerLevel level, Villages.Village v, @Nullable BlockPos[] far) {
        BlockPos c = v.centre();
        int from = Math.max(40, Villages.townReach(v.id()) + 24);
        int bearing = level.getRandom().nextInt(16);
        for (int out = from; out <= from + FURTHEST; out += 8) {
            for (int k = 0; k < 16; k++) {
                double a = Math.toRadians((bearing + k) * 22.5);
                int x = c.getX() + (int) Math.round(Math.sin(a) * out), z = c.getZ() + (int) Math.round(Math.cos(a) * out);
                if (Blink.seenAt(level, x + 0.5, z + 0.5, MARGIN)) continue;
                if (!level.isPositionEntityTicking(new BlockPos(x, c.getY(), z))) {
                    if (far != null && far[0] == null && out <= from + FURTHEST / 2) far[0] = new BlockPos(x, c.getY(), z);
                    continue;
                }
                BlockPos g = ground(level, x, z);
                if (g != null) return g;
            }
        }
        return null;
    }

    /** Somewhere to stand at this column: dry, solid underfoot, two clear blocks overhead; null if not. */
    @Nullable
    static BlockPos ground(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (!level.getFluidState(top).isEmpty() || !level.getFluidState(top.below()).isEmpty()) return null;
        if (!level.getBlockState(top.below()).isFaceSturdy(level, top.below(), Direction.UP)) return null;
        if (!level.getBlockState(top).canBeReplaced() || !level.getBlockState(top.above()).canBeReplaced()) return null;
        return top;
    }

    /**
     * The party's head has reached the edge of town: they are taken in, all of them, with no vote (the town sent for
     * them). Their bedding is laid at the camp first if the town has no bed spare for them, so each has a bed the first
     * night and the town still wants a house for them; the town's ground kept awake grows with it.
     */
    static void arrived(ServerLevel level, Villages.Village town, String pid, long day) {
        CompoundTag p = Newcomers.party(pid);
        int n = Newcomers.size(p);
        int spare = Villages.housing(town.id()) - Villages.headcount(town.id());
        int laid = 0;
        for (int i = 0; i < n - Math.max(0, spare); i++) {
            if (VillageSpawner.campBed(level, town.centre(), BEDDING[(int) Math.floorMod(pid.hashCode() + i, BEDDING.length)])) laid++;
        }
        if (laid > 0) Villages.recountBeds(town.id());
        Newcomers.takeIn(level, town, pid, day);
        Newcomers.record(town.id(), p, day, "settled, sent for", 0, 0);
        ChunkLoad.setLoaded(level, town.id(), town.centre(), VillageSpawner.loadedRadiusFor(Villages.headcount(town.id())), true);
        LOG.info("[MCA-SETTLERS] {}: party {} of {} taken in ({} beds laid at the camp); now {} folk of {} wanted",
            Villages.name(town.id()), pid, n, laid, Villages.headcount(town.id()), base());
    }

    /** The board's line: "Settlers arriving: 3 on the road." while any are; else, while it is under the base, that it has
     *  sent for them; else null. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        int b = base();
        if (b <= 0 || !live(level)) return null;
        int road = onTheRoad(village);
        int heads = Villages.headcount(village);
        if (road > 0) return "Settlers arriving: " + road + " on the road (the town sent word for settlers: " + heads + " folk of the " + b + " it wants).";
        if (heads < b) return "Settlers sent for: " + heads + " folk of the " + b + " the town wants; the next party is on its way.";
        return null;
    }

    /** Tests: every party of settlers sent to this town (on the road, or taken in), as their records stand (copies). */
    public static java.util.List<CompoundTag> partiesForTests(UUID village) {
        java.util.List<CompoundTag> out = new java.util.ArrayList<>();
        for (String pid : CivicRecord.parties().getAllKeys()) {
            CompoundTag p = Newcomers.party(pid);
            if (p.getBoolean(FLAG) && village.toString().equals(p.getString("target"))) {
                CompoundTag c = p.copy();
                c.putString("pid", pid);
                out.add(c);
            }
        }
        return out;
    }
}
