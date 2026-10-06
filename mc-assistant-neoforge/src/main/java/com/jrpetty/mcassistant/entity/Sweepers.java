package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The street sweeper. A town of any size has things lying about in it: the saplings off the trees
 * that were felled for its buildings, seed the birds kicked out of the grass, an egg a hen laid on
 * the square, the bones and string the watch left after a night's fighting, the blocks a builder
 * knocked out of the way, a tuft of wool. Every one of them is an entity the server ticks for five
 * minutes, and every one of them is something the village could have used. The storehouse keeps a
 * sweeper to see to them.
 *
 * <ul>
 * <li><b>Who.</b> One of the storehouse's couriers takes up the broom (a tag on it, kept with it in
 *     the world), once the town has its storehouse and sixteen folk, and one more for every sixteen
 *     after (three at most). The town's share of couriers is raised by as many (Villages.target), so
 *     the runs are not left short for it, and the last courier the storehouse has is never made its
 *     sweeper. It is paid as the storehouse's staff, like the couriers. In a smaller town, or while
 *     the sweeper is asleep or off work, a courier with no run sweeps between runs instead — a few
 *     heaps, and then in with them, and back to the door for the next run.</li>
 * <li><b>Where.</b> The town's streets and squares: anything lying in the open within the town's
 *     reach. Not under a roof (inside a house), not down a hole or a mine, not in water.</li>
 * <li><b>What it leaves.</b> Anything a player threw or dropped, or that fell when a player died —
 *     always, however long it has lain (and no folk picks those up in passing either). Anything at
 *     all while a player is near, for a minute: it may be theirs, out of a block they broke. The
 *     ground of a building going up, while the builders are at it. And anything on a worker's own
 *     ground, or beside a hand at its work, for two minutes: the woodcutter sweeps up its own
 *     saplings between fellings (CleanupGoal), the farmer and the miner take up what they knock
 *     loose; the sweeper takes only what they have left lying.</li>
 * <li><b>Where it goes.</b> Into the storehouse (and the store chests beside it), through the one
 *     way in (Stacking), and into the storehouse's books as swept in, by whom, item by item
 *     (Storekeeping). The Stores page shows the sweeper among the storehouse's staff and how much it
 *     swept today, and the town's books how much is lying about the town now.</li>
 * </ul>
 * Its work is a beat of the courier's (Couriers.work): walk to the nearest heap it may take, sweep
 * it up (and whatever else lies within reach), and with a sackful — or nothing left to sweep — carry
 * it in, and say so. The town's streets are looked over every five seconds.
 *
 * <p>[wf] <b>Snow.</b> In a town where snow falls (its heart cold enough for it: a snowy biome, or high in
 * the mountains), the broom clears the snow lying on the town's streets, its worn paths and its square
 * too, once there is nothing lying about to sweep: the layers shovelled off, and the snowballs they
 * give (with a shovel, as the game gives them: the stores' shovel, or a wooden one made of two of their
 * planks; by hand the snow goes, and gives nothing) into the sack and in with the rest, into the
 * storehouse's books. The snow is looked for every twenty seconds.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Sweepers {

    private Sweepers() {}

    /** Folk a town has before it keeps a sweeper; one more for every so many again; and the most it keeps. */
    public static final int FROM = 16, PER = 16, MOST = 3;
    /** An item lies this long before anybody sweeps it: it may still be rolling, or its maker about to take it up. */
    static final int SETTLE = 100;
    /** With a player this near, what lies about is left a minute: it may be theirs, out of a block they broke. */
    static final int PLAYER_NEAR = 16, PLAYERS_MINUTE = 1200;
    /** On a worker's ground, or this near a hand at its work: left two minutes for that worker (an item lasts five). */
    static final int LEFT_LYING = 2400, BY_A_HAND = 8;
    /** Round a building going up: the builders' ground. */
    static final int SITE_MARGIN = 3;
    /** How often a town's streets are looked over (ticks). */
    static final long LOOK = 100L;
    /** The sweeper carries its sack in at this many goods; a courier sweeping between runs after this many heaps. */
    static final int SACK = 64, COURIER_HEAPS = 4;
    /** A heap it gets no nearer to in this long is left a while (on a roof, across water, behind a wall). */
    static final long NO_NEARER = 400L, LEAVE_A_WHILE = 2400L;
    /** A sweep left this long without a beat (its sweeper called away, or gone to bed) walks afresh. */
    static final long STALE = 600L;
    /** Within this of a heap it sweeps it up, and whatever else it may take within a little more. */
    static final double BROOM = 2.3, SCOOP = 3.0;
    /** [wf] How often the town's streets are looked over for snow (ticks); the most patches of it kept in
     *  mind at once; and how far round where it stands it shovels the snow off at a go. */
    static final long SNOW_LOOK = 400L;
    static final int SNOW_CELLS = 256, SHOVEL = 2;

    /** The tag on an item a player threw or dropped, or that fell when a player died: never swept, never
     *  picked up by a folk passing by. */
    public static final String PLAYERS = "mca_players";
    /** The tag on the storehouse's sweeper (kept with it in the world). */
    public static final String SWEEPER = "mca_sweeper";

    /** One sweep: the heap it is going for, and what it has swept so far (in its pack). */
    static final class Broom {
        /** A courier sweeping between runs: a few heaps, and then in with them. */
        final boolean courier;
        final long started;
        long beat;
        @Nullable UUID heap;
        String heapWhat = "";
        double best = Double.MAX_VALUE;
        long progress;
        boolean walk;
        final List<ItemStack> sack = new ArrayList<>();
        int heaps;
        boolean carrying;
        /** [wf] The patch of snow on the streets it is going for, if any. */
        @Nullable BlockPos snow;

        Broom(boolean courier, long now) {
            this.courier = courier;
            this.started = now;
            this.beat = now;
            this.progress = now;
        }

        int inSack() {
            int n = 0;
            for (ItemStack s : sack) n += s.getCount();
            return n;
        }
    }

    /** A town's sweeping: what lies about it (looked over every five seconds), who is going for what,
     *  and the day's tally. */
    static final class Town {
        long looked = -100000L;
        /** Everything lying about the town: heaps (item entities) and the goods in them. */
        int heaps, goods;
        /** The heaps the broom may take, and the goods in them. */
        final Set<UUID> sweepable = new LinkedHashSet<>();
        int sweepableGoods;
        /** Which sweeper is going for which heap. */
        final Map<UUID, UUID> claimed = new HashMap<>();
        /** Heaps nobody could get to, left until when. */
        final Map<UUID, Long> avoid = new HashMap<>();
        long day = -1L;
        int swept, loads;
        /** Each sweeper's day (and each courier that swept between runs): {goods swept in, loads}. */
        final Map<UUID, int[]> staff = new LinkedHashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        long appointed = -100000L;
        /** [wf] Snow lying on the town's streets, paths and square (a snowy town's), nearest the heart first;
         *  who is going for which patch; patches nobody could get to, left until when; and today's tally. */
        long snowLooked = -100000L;
        final List<BlockPos> snow = new ArrayList<>();
        final Set<Long> snowAt = new java.util.HashSet<>();
        final Map<Long, UUID> snowClaimed = new HashMap<>();
        final Map<Long, Long> snowAvoid = new HashMap<>();
        int cleared, snowballs;
    }

    private static final Map<UUID, Broom> BROOMS = new ConcurrentHashMap<>();
    private static final Map<UUID, Town> TOWNS = new ConcurrentHashMap<>();
    /** Sweepers an op appointed (/village sweeper appoint): kept on, whatever the size of the town. */
    private static final Set<UUID> MADE = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        BROOMS.clear();
        TOWNS.clear();
        MADE.clear();
        snowyForTests = null;
    }

    static Town town(ServerLevel level, UUID village) {
        Town t = TOWNS.computeIfAbsent(village, k -> new Town());
        long today = level.getDayTime() / 24000L;
        if (t.day != today) {
            t.day = today;
            t.swept = 0;
            t.loads = 0;
            t.cleared = 0;
            t.snowballs = 0;
            t.staff.clear();
        }
        return t;
    }

    // ------------------------------------------------------------------ who sweeps

    /**
     * How many sweepers the town wants: none before it has its storehouse (the couriers' trade) and
     * sixteen folk; then one for every sixteen, three at most. Its share of couriers is raised by as
     * many (Villages.target), so a sweeper is an extra pair of hands, not a courier taken off the runs.
     */
    public static int wanted(@Nullable UUID village) {
        if (village == null || !Villages.craftReady(village, StationTask.HAUL)) return 0;
        int folk = Villages.headcount(village);
        return folk < FROM ? 0 : Math.min(MOST, folk / PER);
    }

    /** Is this the storehouse's street sweeper? */
    public static boolean appointed(VillageFolkEntity f) {
        return f.stationTask() == StationTask.HAUL && !f.isBaby() && f.getTags().contains(SWEEPER);
    }

    /** Is this folk out sweeping just now (the town's streets are its ground while it is)? */
    public static boolean sweeping(VillageFolkEntity f) {
        Broom b = BROOMS.get(f.getUUID());
        return b != null && f.level().getGameTime() - b.beat <= STALE;
    }

    /** Is there a sweeper of the town at work just now? (While there is not, a courier between runs sweeps.) */
    static boolean sweeperAbout(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && appointed(f) && f.isAlive() && !f.isSleeping() && !f.offWorkNow()) return true;
        }
        return false;
    }

    /**
     * The town's sweepers, as many as it wants (looked over every ten seconds): a courier of the
     * storehouse takes up the broom — the one with the fewest runs behind it today — but never the last
     * courier it has; one that is no longer a courier, or one too many (the town grew smaller), puts it
     * down.
     */
    static void appoint(ServerLevel level, UUID village, Town t) {
        long now = level.getGameTime();
        if (now - t.appointed < 200L && now >= t.appointed) return;
        t.appointed = now;
        BROOMS.values().removeIf(b -> now - b.beat > 24000L || now < b.beat);
        List<VillageFolkEntity> couriers = new ArrayList<>(), mine = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            boolean tagged = f.getTags().contains(SWEEPER);
            if (f.stationTask() != StationTask.HAUL || f.isBaby() || !f.isAlive()) {
                if (tagged) {
                    f.removeTag(SWEEPER);
                    MADE.remove(f.getUUID());
                }
                if (BROOMS.containsKey(f.getUUID())) putAway(t, f);
                continue;
            }
            if (!Couriers.employed(f)) continue;
            couriers.add(f);
            if (tagged) mine.add(f);
        }
        int want = wanted(village);
        int made = 0;
        for (VillageFolkEntity f : mine) if (MADE.contains(f.getUUID())) made++;
        want = Math.max(want, made);
        // One too many (the town grew smaller), or nobody left on the runs but sweepers: the last to take
        // it up puts the broom down.
        for (int i = mine.size() - 1; i >= 0 && (mine.size() > want || couriers.size() - mine.size() < 1); i--) {
            VillageFolkEntity f = mine.get(i);
            if (MADE.contains(f.getUUID())) continue;
            f.removeTag(SWEEPER);
            mine.remove(i);
            f.brain("put the broom down: the town has sweepers enough");
        }
        // Too few: a courier takes it up — never the last one the storehouse has for its runs.
        Couriers.Office o = Couriers.office(level, village);
        while (mine.size() < want && couriers.size() - mine.size() > 1) {
            VillageFolkEntity best = null;
            int bestRuns = Integer.MAX_VALUE;
            for (VillageFolkEntity f : couriers) {
                if (mine.contains(f) || Couriers.onARun(f)) continue;
                int[] day = o.staff.get(f.getUUID());
                int runs = day == null ? 0 : day[0];
                if (best == null || runs < bestRuns || runs == bestRuns && f.getUUID().compareTo(best.getUUID()) < 0) {
                    best = f;
                    bestRuns = runs;
                }
            }
            if (best == null) break;
            takeUpTheBroom(level, village, best);
            mine.add(best);
        }
    }

    private static void takeUpTheBroom(ServerLevel level, UUID village, VillageFolkEntity f) {
        f.addTag(SWEEPER);
        Villages.tell(village, level.getDayTime() / 24000L, f.displayNameCap() + " took up the broom: the town has a street sweeper");
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "A broom, is it? Right — the streets it is.",
            "Somebody has to keep the square clear. I'll do it."));
        f.brain("made the town's street sweeper");
    }

    // ------------------------------------------------------------------ what it may take

    /** An item a player threw or dropped, or that fell when a player died, or that only a player may pick up. */
    public static boolean playersOwn(ItemEntity e) {
        return e.getTags().contains(PLAYERS) || e.getTarget() != null || e.getOwner() instanceof Player;
    }

    /**
     * Not for the item magnet (AssistantEntity: a folk picks up what it walks near): a player's throw is
     * the player's, and a sweeper out sweeping picks up only what it may sweep, with its broom.
     */
    public static boolean notForTheMagnet(AssistantEntity a, ItemEntity e) {
        return playersOwn(e) || a instanceof VillageFolkEntity f && BROOMS.containsKey(f.getUUID());
    }

    /** What the broom keeps clear of, looked over with the streets: the building sites, the workers'
     *  grounds, the hands at work, the players. */
    private static final class Ground {
        final List<int[]> sites = new ArrayList<>();
        final List<int[]> rings = new ArrayList<>();
        final List<int[]> grounds = new ArrayList<>();
        final List<BlockPos> hands = new ArrayList<>();
        final List<BlockPos> players = new ArrayList<>();

        Ground(ServerLevel level, Villages.Village v) {
            UUID id = v.id();
            // The buildings going up, and a little round each: the builders' ground. (The wall goes up in
            // a ring round the square: its line, and a little either side.)
            for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(id).entrySet()) {
                BlockPos a = e.getValue().anchor();
                if (e.getKey().equals("fortify")) {
                    rings.add(new int[]{ a.getX(), a.getZ(), e.getValue().radius() });
                    continue;
                }
                int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(e.getKey());
                boolean turned = e.getValue().facing().getAxis() == net.minecraft.core.Direction.Axis.X;
                int wx = (turned ? half[1] : half[0]) + SITE_MARGIN, wz = (turned ? half[0] : half[1]) + SITE_MARGIN;
                sites.add(new int[]{ a.getX() - wx, a.getZ() - wz, a.getX() + wx, a.getZ() + wz });
            }
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
                StationTask trade = f.stationTask();
                // A worker's own ground, and a little round it: what lies there is its to take up.
                WorkZone z = f.workZone();
                if (z != null && VillageFolkEntity.producer(trade)) {
                    grounds.add(new int[]{ z.min().getX() - 4, z.min().getZ() - 4, z.max().getX() + 4, z.max().getZ() + 4 });
                }
                // A hand at its work (a job in hand, or the town's works): what it knocks loose, it takes up.
                if (trade != StationTask.HAUL && trade != StationTask.STORE && !f.isSleeping()
                        && (f.peekJob() != null || TownJobs.busy(f))) hands.add(f.blockPosition());
            }
            for (Player p : level.players()) {
                if (p.isAlive() && !p.isSpectator()) players.add(p.blockPosition());
            }
        }

        boolean inASite(BlockPos p) {
            for (int[] s : sites) if (p.getX() >= s[0] && p.getX() <= s[2] && p.getZ() >= s[1] && p.getZ() <= s[3]) return true;
            for (int[] r : rings) {
                int d = Math.max(Math.abs(p.getX() - r[0]), Math.abs(p.getZ() - r[1]));
                if (Math.abs(d - r[2]) <= SITE_MARGIN) return true;
            }
            return false;
        }

        boolean onAWorkersGround(BlockPos p) {
            for (int[] g : grounds) if (p.getX() >= g[0] && p.getX() <= g[2] && p.getZ() >= g[1] && p.getZ() <= g[3]) return true;
            return false;
        }

        boolean byAHand(BlockPos p) {
            for (BlockPos h : hands) if (h.distSqr(p) <= BY_A_HAND * BY_A_HAND) return true;
            return false;
        }

        boolean playerNear(BlockPos p) {
            for (BlockPos q : players) if (q.distSqr(p) <= PLAYER_NEAR * PLAYER_NEAR) return true;
            return false;
        }
    }

    /** May the broom take this heap up? (See the class: the town's streets, in the open, and none of
     *  what it leaves.) */
    private static boolean mayTake(ServerLevel level, Villages.Village v, ItemEntity e, Ground g) {
        if (!e.isAlive() || e.getItem().isEmpty() || e.hasPickUpDelay()) return false;
        if (playersOwn(e) || e.getItem().is(McAssistantMod.MEMORY_CORE.get())) return false;
        int age = e.getAge();
        if (age < SETTLE) return false;
        BlockPos p = e.blockPosition();
        BlockPos c = v.centre();
        if (Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ())) > Villages.townReach(v.id())) return false;
        // In the open: nothing built over it (inside a house), not down a hole or a shaft; not afloat.
        if (p.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) - 1) return false;
        if (e.isInWater() || e.isInLava()) return false;
        if (g.inASite(p)) return false;
        if (age < PLAYERS_MINUTE && g.playerNear(p)) return false;
        if (age < LEFT_LYING && (g.onAWorkersGround(p) || g.byAHand(p))) return false;
        return true;
    }

    /** Look the town's streets over (at most every five seconds; {@code force} for now): what lies about
     *  it, and what of that the broom may take. */
    static void look(ServerLevel level, Villages.Village v, Town t, boolean force) {
        long now = level.getGameTime();
        if (!force && now - t.looked < LOOK && now >= t.looked) return;
        t.looked = now;
        t.heaps = 0;
        t.goods = 0;
        t.sweepable.clear();
        t.sweepableGoods = 0;
        t.avoid.values().removeIf(until -> until < now);
        BlockPos c = v.centre();
        if (!v.dim().equals(level.dimension()) || !level.isLoaded(c)) return;
        int reach = Villages.townReach(v.id());
        AABB box = new AABB(c.getX() - reach, c.getY() - 24, c.getZ() - reach, c.getX() + reach + 1, c.getY() + 40, c.getZ() + reach + 1);
        List<ItemEntity> all = level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && !e.getItem().isEmpty());
        if (!all.isEmpty()) {
            Ground g = new Ground(level, v);
            for (ItemEntity e : all) {
                t.heaps++;
                t.goods += e.getItem().getCount();
                if (t.avoid.containsKey(e.getUUID()) || !mayTake(level, v, e, g)) continue;
                t.sweepable.add(e.getUUID());
                t.sweepableGoods += e.getItem().getCount();
            }
        }
        t.claimed.keySet().retainAll(t.sweepable);
        if (force || now - t.snowLooked >= SNOW_LOOK || now < t.snowLooked) lookForSnow(level, v, t);
    }

    // ------------------------------------------------------------------ [wf] snow

    /** Tests: the town where snow falls (true), not (false), or as its ground is (null). */
    private static volatile Boolean snowyForTests;

    public static void snowyForTests(@Nullable Boolean on) {
        snowyForTests = on;
    }

    /** Does snow fall on this town: its heart cold enough for it (a snowy biome, or high in the mountains)? */
    public static boolean snowy(ServerLevel level, Villages.Village v) {
        Boolean t = snowyForTests;
        if (t != null) return t;
        BlockPos c = v.centre();
        return level.getBiome(c).value().coldEnoughToSnow(c);
    }

    /** A column of the town's streets, square or worn paths: the plan's, or ground worn to a path. */
    private static boolean street(ServerLevel level, int dx, int dz, BlockPos ground) {
        return com.jrpetty.mcassistant.village.TownPlan.isSquare(dx, dz) || com.jrpetty.mcassistant.village.TownPlan.isStreet(dx, dz)
            || level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
    }

    /** The snow lying on the town's streets, paths and square, nearest the heart first (a snowy town's only). */
    static void lookForSnow(ServerLevel level, Villages.Village v, Town t) {
        long now = level.getGameTime();
        t.snowLooked = now;
        t.snow.clear();
        t.snowAt.clear();
        t.snowAvoid.values().removeIf(until -> until < now);
        if (!snowy(level, v)) {
            t.snowClaimed.clear();
            return;
        }
        BlockPos c = v.centre();
        if (!v.dim().equals(level.dimension()) || !level.isLoaded(c)) return;
        int reach = Villages.townReach(v.id());
        // Ring by ring out from the heart, so the square and the streets nearest it are cleared first.
        for (int r = 0; r <= reach && t.snow.size() < SNOW_CELLS; r++) {
            int side = r == 0 ? 1 : 8 * r;
            for (int i = 0; i < side && t.snow.size() < SNOW_CELLS; i++) {
                int dx, dz;
                if (r == 0) { dx = 0; dz = 0; }
                else if (i < 2 * r + 1) { dx = -r + i; dz = -r; }                       // the north side
                else if (i < 4 * r + 2) { dx = -r + (i - 2 * r - 1); dz = r; }          // the south side
                else if (i < 6 * r + 1) { dx = -r; dz = -r + 1 + (i - 4 * r - 2); }     // the west side, between
                else { dx = r; dz = -r + 1 + (i - 6 * r - 1); }                         // the east side, between
                lookAtForSnow(level, t, c, dx, dz);
            }
        }
        t.snowClaimed.keySet().retainAll(t.snowAt);
    }

    /** One column of the town looked at for snow on its streets. */
    private static void lookAtForSnow(ServerLevel level, Town t, BlockPos c, int dx, int dz) {
        int x = c.getX() + dx, z = c.getZ() + dz;
        if (!level.hasChunk(x >> 4, z >> 4)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - c.getY()) > 12) return;
        BlockPos at = new BlockPos(x, y, z);
        if (!level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.SNOW)) return;
        if (!street(level, dx, dz, at.below()) || t.snowAvoid.containsKey(at.asLong())) return;
        t.snow.add(at);
        t.snowAt.add(at.asLong());
    }

    /** Is there snow on the streets this sweeper may go for (nobody else going for it)? */
    private static boolean snowFor(VillageFolkEntity c, Town t) {
        for (BlockPos p : t.snow) {
            UUID by = t.snowClaimed.get(p.asLong());
            if (by == null || by.equals(c.getUUID()) || !BROOMS.containsKey(by)) return true;
        }
        return false;
    }

    /** The patch of snow it is going for: the one it had while there is still snow on it, else the nearest nobody else has. */
    @Nullable
    private static BlockPos snowPatch(VillageFolkEntity c, ServerLevel level, Town t, Broom b, long now) {
        if (b.snow != null) {
            if (t.snowAt.contains(b.snow.asLong()) && level.getBlockState(b.snow).is(net.minecraft.world.level.block.Blocks.SNOW)) return b.snow;
            t.snowClaimed.remove(b.snow.asLong());
            b.snow = null;
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : t.snow) {
            UUID by = t.snowClaimed.get(p.asLong());
            if (by != null && !by.equals(c.getUUID()) && BROOMS.containsKey(by)) continue;
            if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SNOW)) continue;
            double d = p.distToCenterSqr(c.position());
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best != null) {
            b.snow = best;
            b.best = Double.MAX_VALUE;
            b.progress = now;
            b.walk = true;
            t.snowClaimed.put(best.asLong(), c.getUUID());
        }
        return best;
    }

    /**
     * A shovel for the snow: its own, else one out of the stores, else a wooden one made there and then of
     * two of their planks (a plank and two sticks, as the game makes one). Empty if there is none to be had.
     */
    private static ItemStack shovelFor(VillageFolkEntity c, ServerLevel level, Villages.Village v) {
        for (ItemStack s : c.getInventoryItems()) if (!s.isEmpty() && s.is(net.minecraft.tags.ItemTags.SHOVELS)) return s;
        ItemStack got = Crafts.takeOne(level, v, s -> s.is(net.minecraft.tags.ItemTags.SHOVELS));
        if (got.isEmpty() && Crafts.usePlanks(level, v, 2)) {
            got = new ItemStack(net.minecraft.world.item.Items.WOODEN_SHOVEL);
            c.brain("made a wooden shovel of the stores' planks, for the snow");
        }
        if (got.isEmpty()) return ItemStack.EMPTY;
        ItemStack left = c.insertItem(got);
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);
            return ItemStack.EMPTY;
        }
        for (ItemStack s : c.getInventoryItems()) if (!s.isEmpty() && s.is(net.minecraft.tags.ItemTags.SHOVELS)) return s;
        return ItemStack.EMPTY;
    }

    /** Walk on to the snow; at it, shovel the streets clear round it, and the snowballs into the sack. */
    private static boolean shovel(VillageFolkEntity c, ServerLevel level, Villages.Village v, Town t, Broom b, BlockPos at,
                                  Couriers.Office o, long now) {
        double dx = at.getX() + 0.5 - c.getX(), dz = at.getZ() + 0.5 - c.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= BROOM + 0.5 && Math.abs(at.getY() - c.getY()) <= 2.5) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            ItemStack spade = shovelFor(c, level, v);
            int layers = 0, balls = 0;
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-SHOVEL, -1, -SHOVEL), at.offset(SHOVEL, 1, SHOVEL))) {
                if (!t.snowAt.contains(p.asLong())) continue;           // the streets only, not somebody's garden
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
                if (!st.is(net.minecraft.world.level.block.Blocks.SNOW)) continue;
                List<ItemStack> drops = !spade.isEmpty() && spade.isCorrectToolForDrops(st)
                    ? net.minecraft.world.level.block.Block.getDrops(st, level, p, null, c, spade) : List.of();
                level.levelEvent(2001, p, net.minecraft.world.level.block.Block.getId(st));   // the crunch, and the puff of snow
                level.removeBlock(p, false);
                layers += st.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
                for (ItemStack dr : drops) {
                    if (dr.isEmpty()) continue;
                    ItemStack left = c.insertItem(dr.copy());
                    int took = dr.getCount() - left.getCount();
                    if (took > 0) {
                        addTo(b.sack, dr.copyWithCount(took));
                        balls += took;
                    }
                    if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, p, left);
                }
                if (!spade.isEmpty()) spade.hurtAndBreak(1, level, c, item -> { });
                t.snowAt.remove(p.asLong());
                t.snowClaimed.remove(p.asLong());
            }
            t.snow.removeIf(p -> !t.snowAt.contains(p.asLong()));
            b.snow = null;
            b.heaps++;
            t.cleared += layers;
            t.snowballs += balls;
            c.swing(InteractionHand.MAIN_HAND);
            o.doing.put(c.getUUID(), "clearing the snow off the streets: " + b.inSack() + " in the sack");
            c.brain("shovelled " + layers + " layers of snow off the street" + (balls > 0 ? ", " + balls + " snowballs into the sack" : ""));
            if (layers > 0 && level.getRandom().nextInt(6) == 0) {
                FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "Can't have folk slipping on the square.", "There. A path you can walk.",
                    "Snowballs for the stores — the children will have them."));
            }
            return true;
        }
        if (d < b.best - 0.5) {
            b.best = d;
            b.progress = now;
        } else if (now - b.progress > NO_NEARER || now < b.progress) {
            t.snowAvoid.put(at.asLong(), now + LEAVE_A_WHILE);
            t.snowClaimed.remove(at.asLong());
            t.snowAt.remove(at.asLong());
            t.snow.remove(at);
            c.brain("could not get to the snow there; left it a while");
            b.snow = null;
            return true;
        }
        if (b.walk || c.getNavigation().isDone()) {
            c.walkTo(at, 1.0D);
            b.walk = false;
        }
        o.doing.put(c.getUUID(), "clearing the snow off the streets");
        c.brain("going to clear the snow off the street");
        return true;
    }

    // ------------------------------------------------------------------ the sweeping

    /**
     * A beat of sweeping, from a courier's work (Couriers.work, before it takes a run, and again when
     * it has none): the storehouse's sweeper sweeps; a courier part-way through a sweep sees it through;
     * and with {@code idle} (no run for it) a courier sweeps between runs while the town has no sweeper
     * about. True while it is at it — walking to a heap, sweeping it up, carrying the sack in; false if
     * there is nothing for it to sweep.
     */
    static boolean work(VillageFolkEntity c, ServerLevel level, UUID village, Couriers.Office o, boolean idle) {
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        Town t = town(level, village);
        appoint(level, village, t);
        long now = level.getGameTime();
        Broom b = BROOMS.get(c.getUUID());
        if (b != null && (now - b.beat > STALE || now < b.beat)) {
            // Called away a while (the town's works, its bed): it walks afresh to whatever is nearest now.
            if (b.heap != null) t.claimed.remove(b.heap);
            if (b.snow != null) t.snowClaimed.remove(b.snow.asLong());
            b.heap = null;
            b.snow = null;
            b.best = Double.MAX_VALUE;
            b.progress = now;
            b.walk = true;
        }
        if (b == null) {
            boolean mine = appointed(c);
            if (!mine && !(idle && !sweeperAbout(village))) return false;
            look(level, v, t, false);
            if (!anyFor(level, c, t) && !snowFor(c, t)) return false;   // [wf] or snow on the streets
            b = new Broom(!mine, now);
            BROOMS.put(c.getUUID(), b);
            c.brain(mine ? "sweeping the town's streets" : "sweeping the streets between runs");
        }
        b.beat = now;
        if (!b.carrying && (b.inSack() >= SACK || c.isPackFull() || b.courier && b.heaps >= COURIER_HEAPS)) carry(b, now);
        if (!b.carrying) {
            ItemEntity heap = heap(c, level, v, t, b, now);
            if (heap != null) return sweep(c, level, t, b, heap, o, now);
            // [wf] Nothing lying about: the snow off the streets, paths and square, in a snowy town.
            BlockPos snow = snowPatch(c, level, t, b, now);
            if (snow != null) return shovel(c, level, v, t, b, snow, o, now);
            if (b.sack.isEmpty()) {
                putAway(t, c);
                return false;
            }
            carry(b, now);
        }
        return carryIn(c, level, village, t, b, o, now);
    }

    private static void carry(Broom b, long now) {
        b.carrying = true;
        b.best = Double.MAX_VALUE;
        b.progress = now;
        b.walk = true;
    }

    /** The broom put away: no heap claimed, the sweep over (anything still in the pack goes in as an
     *  ordinary load, the courier's way). */
    private static void putAway(Town t, VillageFolkEntity c) {
        Broom b = BROOMS.remove(c.getUUID());
        if (b != null && b.heap != null) t.claimed.remove(b.heap);
        if (b != null && b.snow != null) t.snowClaimed.remove(b.snow.asLong());
    }

    /** Is there a heap lying about that this sweeper may go for (nobody else going for it)? */
    private static boolean anyFor(ServerLevel level, VillageFolkEntity c, Town t) {
        for (UUID id : t.sweepable) {
            UUID by = t.claimed.get(id);
            if (by != null && !by.equals(c.getUUID()) && BROOMS.containsKey(by)) continue;
            if (level.getEntity(id) instanceof ItemEntity e && e.isAlive()) return true;
        }
        return false;
    }

    /** The heap it is going for: the one it had while it is still there to take, else the nearest
     *  nobody else is going for. */
    @Nullable
    private static ItemEntity heap(VillageFolkEntity c, ServerLevel level, Villages.Village v, Town t, Broom b, long now) {
        look(level, v, t, false);
        if (b.heap != null) {
            if (t.sweepable.contains(b.heap) && level.getEntity(b.heap) instanceof ItemEntity e && e.isAlive()) return e;
            t.claimed.remove(b.heap);
            b.heap = null;
        }
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (UUID id : t.sweepable) {
            UUID by = t.claimed.get(id);
            if (by != null && !by.equals(c.getUUID()) && BROOMS.containsKey(by)) continue;
            if (!(level.getEntity(id) instanceof ItemEntity e) || !e.isAlive()) continue;
            double d = e.distanceToSqr(c);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        if (best != null) {
            b.heap = best.getUUID();
            b.heapWhat = Storekeeping.list(List.of(best.getItem()));
            b.best = Double.MAX_VALUE;
            b.progress = now;
            b.walk = true;
            t.claimed.put(b.heap, c.getUUID());
        }
        return best;
    }

    /** Walk on to the heap; at it, sweep it up, and whatever else it may take within reach. */
    private static boolean sweep(VillageFolkEntity c, ServerLevel level, Town t, Broom b, ItemEntity heap, Couriers.Office o, long now) {
        double dx = heap.getX() - c.getX(), dz = heap.getZ() - c.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= BROOM && Math.abs(heap.getY() - c.getY()) <= 2.5) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(heap.getX(), heap.getY(), heap.getZ());
            int took = pickUp(c, level, t, b, heap);
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(SCOOP, 1.5, SCOOP),
                    e -> e != heap && e.isAlive() && t.sweepable.contains(e.getUUID()))) {
                UUID by = t.claimed.get(e.getUUID());
                if (by != null && !by.equals(c.getUUID()) && BROOMS.containsKey(by)) continue;
                took += pickUp(c, level, t, b, e);
            }
            if (took <= 0) {
                carry(b, now);                                       // the pack will hold no more
            } else if (level.getRandom().nextInt(6) == 0) {
                FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "There's " + b.heapWhat + " that won't be lying about any more.",
                    "Who leaves " + b.heapWhat + " in the street?", "Into the sack with you.", "Waste not."));
            }
            b.heap = null;
            o.doing.put(c.getUUID(), "sweeping the streets: " + b.inSack() + " in the sack");
            c.brain("swept up " + b.heapWhat + " (" + b.inSack() + " in the sack)");
            return true;
        }
        if (d < b.best - 0.5) {
            b.best = d;
            b.progress = now;
        } else if (now - b.progress > NO_NEARER || now < b.progress) {
            // On a roof, across water, behind a wall: left a while, and on to the next.
            t.avoid.put(heap.getUUID(), now + LEAVE_A_WHILE);
            t.claimed.remove(heap.getUUID());
            t.sweepable.remove(heap.getUUID());
            c.brain("could not get to " + b.heapWhat + "; left it a while");
            b.heap = null;
            return true;
        }
        if (b.walk || c.getNavigation().isDone()) {
            c.walkTo(heap.blockPosition(), 1.0D);
            b.walk = false;
        }
        o.doing.put(c.getUUID(), "sweeping the streets: going for " + b.heapWhat);
        c.brain("sweeping: going for " + b.heapWhat);
        return true;
    }

    /** One heap into the pack (as much of it as the pack will hold), and into the sack's tally. */
    private static int pickUp(VillageFolkEntity c, ServerLevel level, Town t, Broom b, ItemEntity e) {
        ItemStack st = e.getItem();
        if (st.isEmpty()) return 0;
        ItemStack left = c.insertItem(st.copy());
        int took = st.getCount() - left.getCount();
        if (took <= 0) return 0;
        addTo(b.sack, st.copyWithCount(took));
        if (left.isEmpty()) {
            e.discard();
            t.sweepable.remove(e.getUUID());
        } else {
            e.setItem(left);
        }
        t.claimed.remove(e.getUUID());
        b.heaps++;
        c.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.25F,
            1.3F + level.getRandom().nextFloat() * 0.4F);
        return took;
    }

    private static void addTo(List<ItemStack> into, ItemStack s) {
        for (ItemStack in : into) {
            if (ItemStack.isSameItemSameComponents(in, s)) {
                in.grow(s.getCount());
                return;
            }
        }
        into.add(s.copy());
    }

    /** With the sack full (or nothing left to sweep): to the storehouse with it, into it there, and booked. */
    private static boolean carryIn(VillageFolkEntity c, ServerLevel level, UUID village, Town t, Broom b, Couriers.Office o, long now) {
        BlockPos spot = Storehouses.standingSpot(level, village);
        if (spot == null) spot = Couriers.base(level, village);
        if (spot == null) {
            putAway(t, c);
            return false;
        }
        double d = Math.sqrt(c.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5));
        if (d > 4.0) {
            if (d < b.best - 0.5) {
                b.best = d;
                b.progress = now;
            } else if (now - b.progress > 900L || now < b.progress) {
                if (c.putBeside(spot)) {
                    b.best = Double.MAX_VALUE;
                    b.progress = now;
                    return true;
                }
                putAway(t, c);                                        // its own deposit takes it in, later
                return false;
            }
            if (b.walk || c.getNavigation().isDone()) {
                c.walkTo(spot, 1.0D);
                b.walk = false;
            }
            o.doing.put(c.getUUID(), "carrying in what it swept (" + b.inSack() + ")");
            c.brain("carrying in what it swept");
            return true;
        }
        c.getNavigation().stop();
        List<ItemStack> lots = intoTheStores(c, level, village, spot, b);
        int n = 0;
        for (ItemStack s : lots) n += s.getCount();
        putAway(t, c);
        if (n > 0) {
            book(level, village, t, c, lots, n);
            o.doing.put(c.getUUID(), "swept in " + Storekeeping.list(lots));
        } else {
            FolkTalk.speak(c, "No room in the storehouse for what I swept. It'll keep.");
            o.doing.put(c.getUUID(), "back from sweeping: no room in the storehouse");
        }
        return true;
    }

    /** What it swept, out of its pack and into the storehouse (and the store chests beside it), merged
     *  onto the stacks there (Stacking). Returns what went in. */
    private static List<ItemStack> intoTheStores(VillageFolkEntity c, ServerLevel level, UUID village, BlockPos spot, Broom b) {
        List<Container> into = new ArrayList<>();
        StorehouseBlockEntity store = Storehouses.storeFor(level, village);
        if (store != null) into.add(store);
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (p.distSqr(spot) > 10 * 10) continue;
            if (level.getBlockEntity(p) instanceof Container box && !into.contains(box)) into.add(box);
        }
        List<ItemStack> in = new ArrayList<>();
        if (into.isEmpty()) return in;
        var pack = c.getInventoryItems();
        for (ItemStack want : b.sack) {
            int need = want.getCount();
            for (int i = 0; i < pack.size() && need > 0; i++) {
                ItemStack s = pack.get(i);
                if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, want)) continue;
                int n = Math.min(need, s.getCount());
                ItemStack left = Stacking.insert(into, s.copyWithCount(n));
                int went = n - left.getCount();
                if (went <= 0) break;                                // no room anywhere for it
                s.shrink(went);
                if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
                need -= went;
                addTo(in, want.copyWithCount(went));
                if (!left.isEmpty()) break;
            }
        }
        return in;
    }

    /** Into the storehouse's books: swept in, by whom, item by item; and the sweeper's day. */
    private static void book(ServerLevel level, UUID village, Town t, VillageFolkEntity c, List<ItemStack> lots, int n) {
        Storekeeping.bookIn(level, village, null, lots, false);
        Storekeeping.Day d = Storekeeping.day(level, village);
        String who = c.displayNameCap();
        d.folk.computeIfAbsent(who, k -> new int[2])[0] += n;
        d.line(clock(level) + " " + who + " swept in " + Storekeeping.list(lots));
        t.swept += n;
        t.loads++;
        int[] day = t.staff.computeIfAbsent(c.getUUID(), k -> new int[2]);
        day[0] += n;
        day[1]++;
        t.names.put(c.getUUID(), who);
        c.note(AssistantEntity.Deed.LOADS_HAULED, 1);
        c.swing(InteractionHand.MAIN_HAND);
        c.brain("swept in " + Storekeeping.list(lots));
        if (level.getRandom().nextInt(3) == 0) {
            String what = Storekeeping.list(lots);
            FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "Swept in: " + what + ".",
                capFirst(what) + ", off the streets and into the storehouse.",
                "That's the square clear again. " + capFirst(what) + " in."));
        }
    }

    // ------------------------------------------------------------------ shown

    /** How much this folk has swept in today. */
    public static int sweptToday(VillageFolkEntity f) {
        UUID v = f.ownerId();
        if (v == null || !(f.level() instanceof ServerLevel level)) return 0;
        int[] day = town(level, v).staff.get(f.getUUID());
        return day == null ? 0 : day[0];
    }

    /** The sweeper's (or a courier that swept between runs) line on its card, or "". */
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.HAUL || f.isBaby()) return "";
        int n = sweptToday(f);
        if (appointed(f)) return "the town's streets and squares, for the storehouse: " + n + " swept in today";
        return n > 0 ? "between runs: " + n + " swept in today" : "";
    }

    /** The town's sweeping, into the storehouse's report (Storekeeping.report): swept in today, by whom,
     *  and what is lying about the town now. */
    static void report(ServerLevel level, Villages.Village v, CompoundTag out) {
        Town t = town(level, v.id());
        look(level, v, t, false);
        out.putInt("swept", t.swept);
        out.putInt("sweep_loads", t.loads);
        out.putInt("lying", t.heaps);
        out.putInt("lying_goods", t.goods);
        out.putInt("sweepable", t.sweepable.size());
        out.putInt("sweepers_wanted", wanted(v.id()));
        out.putInt("snow_cleared", t.cleared);                  // [wf] layers of snow off the streets today
        out.putInt("snowballs", t.snowballs);
        out.putInt("snow_lying", t.snow.size());
        List<String> who = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && appointed(f)) who.add(f.displayNameCap());
        out.putString("sweepers", String.join(", ", who));
    }

    /** The sweeping in a line, from the storehouse's report: for the Stores page and /village stores. */
    public static String line(CompoundTag t) {
        String who = t.getString("sweepers");
        return "Swept in today: " + t.getInt("swept") + " (" + t.getInt("sweep_loads") + (t.getInt("sweep_loads") == 1 ? " load" : " loads")
            + (who.isEmpty() ? ", by the couriers between runs" : ", by " + who) + "). Lying about the town: " + t.getInt("lying")
            + (t.getInt("lying") == 1 ? " item" : " items") + " (" + t.getInt("lying_goods") + " goods; " + t.getInt("sweepable") + " for the broom)."
            + (t.getInt("snow_cleared") > 0 || t.getInt("snow_lying") > 0
                ? " Snow cleared off the streets today: " + t.getInt("snow_cleared") + " layers (" + t.getInt("snowballs") + " snowballs banked); "
                    + t.getInt("snow_lying") + " patches still lying." : "");
    }

    /** /village sweeper: the town's sweeping in words. */
    public static String page(ServerLevel level, Villages.Village v) {
        CompoundTag t = new CompoundTag();
        report(level, v, t);
        StringBuilder sb = new StringBuilder("The streets of " + Villages.name(v.id()) + ". ");
        int want = t.getInt("sweepers_wanted");
        sb.append(want > 0 ? "The town keeps " + (want == 1 ? "a sweeper" : want + " sweepers") + ". "
            : "No sweeper yet (a storehouse and " + FROM + " folk first): the couriers sweep between runs. ");
        sb.append(line(t));
        Town town = town(level, v.id());
        for (Map.Entry<UUID, int[]> e : town.staff.entrySet()) {
            sb.append(" | ").append(town.names.getOrDefault(e.getKey(), "?")).append(": ").append(e.getValue()[0]).append(" swept in, ")
                .append(e.getValue()[1]).append(e.getValue()[1] == 1 ? " load" : " loads");
            Broom b = BROOMS.get(e.getKey());
            if (b != null) sb.append(", now ").append(b.carrying ? "carrying in " + b.inSack() : b.heap != null ? "going for " + b.heapWhat : "about the streets");
        }
        return sb.toString();
    }

    /**
     * /village sweeper appoint: the nearest grown folk of the village to this spot made the storehouse's
     * sweeper now, whatever the size of the town (a courier first, if it is not one). Returns its name, or null.
     */
    @Nullable
    public static VillageFolkEntity appointNow(ServerLevel level, Villages.Village v, BlockPos near) {
        VillageFolkEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isShowcase()) continue;
            double d = f.blockPosition().distSqr(near) - (f.stationTask() == StationTask.HAUL ? 1.0e6 : 0.0);
            if (d < bestD) {
                bestD = d;
                best = f;
            }
        }
        if (best == null) return null;
        if (best.stationTask() != StationTask.HAUL) best.setJob(StationTask.HAUL);
        MADE.add(best.getUUID());
        if (!best.getTags().contains(SWEEPER)) takeUpTheBroom(level, v.id(), best);
        return best;
    }

    // ------------------------------------------------------------------ a player's own

    /** A player threw it (the drop key, or out of the inventory screen): it is theirs. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        event.getEntity().addTag(PLAYERS);
    }

    /** What fell when a player died: theirs, to come back for. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        for (ItemEntity e : event.getDrops()) e.addTag(PLAYERS);
    }

    /** Anything else a player dropped: thrown by a player, or dropped from where a player stands (a full
     *  inventory spilling over drops it at the player's own feet, at the height of its hands). */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof ItemEntity e) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (e.getOwner() instanceof Player) {
            e.addTag(PLAYERS);
            return;
        }
        for (Player p : level.players()) {
            if (Math.abs(e.getX() - p.getX()) < 1.0e-3 && Math.abs(e.getZ() - p.getZ()) < 1.0e-3
                    && Math.abs(e.getY() - (p.getEyeY() - 0.3)) < 0.05) {
                e.addTag(PLAYERS);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ words, tests

    private static String capFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The hour of the day for the books: "08:40". */
    private static String clock(ServerLevel level) {
        long t = (level.getDayTime() + 6000L) % 24000L;
        int h = (int) (t / 1000L), m = (int) ((t % 1000L) * 60L / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    /** Tests: look the town's sweepers over now (as every ten seconds). */
    public static void appointForTests(ServerLevel level, UUID village) {
        Town t = town(level, village);
        t.appointed = -100000L;
        appoint(level, village, t);
    }

    /** Tests: may the broom take this heap up, now? */
    public static boolean mayTakeForTests(ServerLevel level, UUID village, ItemEntity e) {
        Villages.Village v = Villages.get(village);
        return v != null && mayTake(level, v, e, new Ground(level, v));
    }

    /** [wf] Tests: the snow on the town's streets now, {patches lying, layers cleared today, snowballs banked today}. */
    public static int[] snowForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Town t = town(level, village);
        if (v != null) lookForSnow(level, v, t);
        return new int[]{ t.snow.size(), t.cleared, t.snowballs };
    }

    /** Tests: the town today, {goods swept in, loads, heaps lying about, heaps for the broom}. */
    public static int[] townForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Town t = town(level, village);
        if (v != null) look(level, v, t, true);
        return new int[]{ t.swept, t.loads, t.heaps, t.sweepable.size() };
    }
}
