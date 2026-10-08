package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visitors from afar. [batchG] A town is not only its own people: now and then somebody walks in from the
 * edge of the world, stays a while and goes again.
 * <ul>
 * <li><b>The travelling bard</b> (Bard): every few days, to a town of fifteen with a tavern. Two or three
 *     nights; of an evening it plays and tells stories at the tavern, with the news of the towns round
 *     about out of their own chronicles, and whoever hears it is the happier for it.</li>
 * <li><b>Tourists</b> (Tourists): to a town of renown, one or two at a time, a few days apart. They walk
 *     its sights, spend a few coins at the café and the shop, and go.</li>
 * <li><b>The merchant from afar</b> (Merchants): on market day, to a town with a market, with a few lots
 *     of what its own land has not got. The town buys what it needs of them; so may you.</li>
 * </ul>
 * And, from here because they are the same sort of thing the other way about, the town's own folk who go
 * to see a friend in another town for the day (FriendVisits).
 *
 * <p>A visitor is a folk like any other to look at and to talk to, but it belongs to no village. It has no
 * village of its own (nobody's, as a folk's is before it has settled), so it is on no town's roll: never in
 * a headcount, never given a bed, a trade, a wage or a vote, never sent to the town's work. It is marked
 * with a tag (TAG) and carries its stay with it in its own saved data, so a visitor in town when the world
 * was saved is still in town when it is loaded, and still leaves on the day it meant to. Its day is run from
 * here: VillageFolkEntity.aiStep hands it over before any of a resident's day is looked at, so it never
 * goes looking for a village to join.
 *
 * <p>What a visitor brings, it brings from outside, as the game's own wandering trader does, and it is kept
 * small: the bard's bedroll and the price of its rooms, a tourist's purse of four to ten coins (and a room's
 * price, with an inn to stay at), a merchant's few lots. It is the one way
 * coin and goods come into a town from beyond it, and the town pays for anything it keeps (the merchant's
 * wares, out of the treasury). What a visitor takes away goes with it: its purse, a souvenir, its bedroll.
 */
public final class Visitors {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Visitors() {}

    /** The mark every visitor carries (an entity tag, saved with it). */
    public static final String TAG = "mca_visitor";
    /** Where a visitor keeps its stay, in its own saved data. */
    private static final String DATA = "mca_visit";

    /** Who has come, and as what. */
    public enum Kind {
        BARD("a travelling bard", " the bard"), TOURIST("a visitor from afar", " (visiting)"), MERCHANT("a merchant from afar", " the merchant");

        public final String words;
        /** What its name carries over its head, so a player can tell a visitor from a resident. */
        final String suffix;

        Kind(String words, String suffix) {
            this.words = words;
            this.suffix = suffix;
        }
    }

    /** A visitor's stay: who it is, which town, where it came in from, when it came and when it goes. */
    static final class Visit {
        Kind kind = Kind.BARD;
        UUID town;
        BlockPos edge;
        String name = "";
        long arrived;
        long leave;
        /** "in" (walking in from the edge), "stay", "out" (walking back to the edge). */
        String stage = "in";
        int purse0;
        /** Its bedroll, laid out at the inn for the night (the head of the bed). */
        @Nullable BlockPos bedroll;
        /** How far round the town's sights it has got (Tourists); its next call (Merchants). */
        int sight;
        /** The merchant's wares, as "item:count" with what it asks for each lot ("cocoa_beans:8:3"). */
        String wares = "";
        /** The day its being in town was first told (it is told once, when it gets to the heart). */
        boolean told;
        /** What it spent in town. */
        int spent;
        // Not saved: the walking.
        final Walk walk = new Walk();
        int tick, leftAt;
        long lastSaid;
        /** When it was last seen about (its tick): a visitor killed or lost is let go of after a while. */
        long seen;

        void save(CompoundTag t) {
            t.putString("kind", kind.name());
            t.putUUID("town", town);
            t.putLong("edge", edge.asLong());
            t.putString("name", name);
            t.putLong("arrived", arrived);
            t.putLong("leave", leave);
            t.putString("stage", stage);
            t.putInt("purse0", purse0);
            if (bedroll != null) t.putLong("bedroll", bedroll.asLong());
            t.putInt("sight", sight);
            t.putString("wares", wares);
            t.putBoolean("told", told);
            t.putInt("spent", spent);
        }

        @Nullable
        static Visit load(CompoundTag t) {
            if (!t.hasUUID("town")) return null;
            Visit v = new Visit();
            try {
                v.kind = Kind.valueOf(t.getString("kind"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            v.town = t.getUUID("town");
            v.edge = BlockPos.of(t.getLong("edge"));
            v.name = t.getString("name");
            v.arrived = t.getLong("arrived");
            v.leave = t.getLong("leave");
            v.stage = t.getString("stage").isEmpty() ? "in" : t.getString("stage");
            v.purse0 = t.getInt("purse0");
            v.bedroll = t.contains("bedroll") ? BlockPos.of(t.getLong("bedroll")) : null;
            v.sight = t.getInt("sight");
            v.wares = t.getString("wares");
            v.told = t.getBoolean("told");
            v.spent = t.getInt("spent");
            return v;
        }
    }

    /** A walk somewhere across the land, kept to: the nearest it has got, and since when. */
    static final class Walk {
        double best = Double.MAX_VALUE;
        int gained, walkTick = -1000;
        @Nullable BlockPos heading, target;

        void reset() {
            best = Double.MAX_VALUE;
            heading = null;
            target = null;
        }
    }

    /** The visitors about, by their ids (filled as they tick: a visitor loaded from disk is read again). */
    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    /** Folk who heard the bard, and the day; and folk who had a friend to stay, and the day (their spirits). */
    private static final Map<UUID, Long> HEARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> VISITED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        VISITS.clear();
        HEARD.clear();
        VISITED.clear();
        Bard.resetForTests();
        Tourists.resetForTests();
        Merchants.resetForTests();
        FriendVisits.resetForTests();
        WatchDogs.resetForTests();
        MapRoom.resetForTests();
        KeptGifts.resetForTests();
        Advancements.resetForTests();
    }

    // ------------------------------------------------------------------ the town's round

    /**
     * The town's look at its visitors, and at the other things of this batch, every ten seconds or so
     * (TownLife.tick): a bard, tourists or the merchant to come in; a friend's visit to set out on; the
     * map on the hall's wall; the watch's dogs; a player's gifts set out at home; and its players' honours.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        // A visitor not seen for a few minutes (killed on the road, or its ground unloaded) is let go of here;
        // one that is only unloaded is read again from its own saved data when it next ticks.
        long now = level.getGameTime();
        VISITS.values().removeIf(x -> x.town.equals(v.id()) && x.seen > 0 && now - x.seen > 6000L);
        Bard.consider(level, v, day, t);
        Tourists.consider(level, v, day, t);
        Merchants.consider(level, v, day, t);
        FriendVisits.tick(level, v, day, t);
        MapRoom.tick(level, v, day);
        WatchDogs.tick(level, v, day);
        KeptGifts.tick(level, v, day);
        Advancements.tick(level, v);
    }

    // ------------------------------------------------------------------ who is a visitor

    /** Is this folk a visitor (no resident of anywhere)? */
    public static boolean is(VillageFolkEntity f) {
        return f.getTags().contains(TAG);
    }

    /** A visitor's stay, read from its saved data the first time it is asked for. */
    @Nullable
    static Visit visit(VillageFolkEntity f) {
        Visit v = VISITS.get(f.getUUID());
        if (v != null) return v;
        CompoundTag t = f.getPersistentData().getCompound(DATA);
        v = Visit.load(t);
        if (v != null) VISITS.put(f.getUUID(), v);
        return v;
    }

    static void save(VillageFolkEntity f, Visit v) {
        CompoundTag t = new CompoundTag();
        v.save(t);
        f.getPersistentData().put(DATA, t);
    }

    /** The visitors of one kind in one town just now, alive and loaded. */
    static List<VillageFolkEntity> inTown(ServerLevel level, UUID town, @Nullable Kind kind) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (Map.Entry<UUID, Visit> e : VISITS.entrySet()) {
            Visit v = e.getValue();
            if (!v.town.equals(town) || kind != null && v.kind != kind) continue;
            if (level.getEntity(e.getKey()) instanceof VillageFolkEntity f && f.isAlive()) out.add(f);
        }
        return out;
    }

    /** Is a visitor of this kind staying in this town now (past its walk in, and seen this last minute)? */
    static boolean here(UUID town, Kind kind, long now) {
        for (Visit v : VISITS.values()) {
            if (v.kind == kind && v.town.equals(town) && "stay".equals(v.stage) && now - v.seen < 1200L) return true;
        }
        return false;
    }

    /** Tests: the visitors in a town now. */
    public static List<VillageFolkEntity> inTownForTests(ServerLevel level, UUID town) {
        return inTown(level, town, null);
    }

    /** The visitor's stay, for a game test. */
    @Nullable
    public static String stageForTests(VillageFolkEntity f) {
        Visit v = visit(f);
        return v == null ? null : v.stage;
    }

    /** The town a visitor is visiting, or null. */
    @Nullable
    public static UUID townOf(VillageFolkEntity f) {
        Visit v = is(f) ? visit(f) : null;
        return v == null ? null : v.town;
    }

    // ------------------------------------------------------------------ coming in

    /**
     * Somebody comes in on foot from the edge of the world near the town: a folk of no village, with what it
     * brings from outside ({@code kit}, and {@code purse} coins of its own), to stay {@code nights} nights.
     * Null if there is no edge to come in from (no loaded ground about the town).
     */
    @Nullable
    static VillageFolkEntity arrive(ServerLevel level, Villages.Village v, Kind kind, long day, int nights, int purse,
                                    List<ItemStack> kit, String origin) {
        BlockPos edge = edge(level, v, level.getRandom());
        if (edge == null) return null;
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        RandomSource r = level.getRandom();
        double yaw = Math.toDegrees(Math.atan2(-(v.centre().getX() - edge.getX()), v.centre().getZ() - edge.getZ()));
        f.moveTo(edge.getX() + 0.5, edge.getY(), edge.getZ() + 0.5, (float) yaw, 0.0F);
        String name = Names.freshFor(v.id(), r);
        f.rename(name + kind.suffix);
        f.addTag(TAG);
        f.setPersistenceRequired();
        f.life().roll(r, null, null);
        f.persona().roll(r, f.life(), AssistantEntity.StationTask.NONE, day, origin);
        if (purse > 0) f.earn(purse);
        for (ItemStack s : kit) f.insertGiven(s.copy());
        Visit vis = new Visit();
        vis.kind = kind;
        vis.town = v.id();
        vis.edge = edge.immutable();
        vis.name = name;
        vis.arrived = day;
        vis.leave = day + nights;
        vis.purse0 = purse;
        vis.seen = level.getGameTime();
        VISITS.put(f.getUUID(), vis);
        save(f, vis);
        level.addFreshEntity(f);
        LOG.info("[MCA-VISIT] {} ({}) comes to {} from {} ({} nights, {} coins of its own)", name, kind.words,
            Villages.name(v.id()), edge.toShortString(), nights, purse);
        return f;
    }

    /**
     * Where a visitor comes in from: out past the town's last street on loaded, dry ground, the way a road
     * would come in (any of the eight ways about, the first that will do). The visitor is never made in
     * ground nobody can see being kept, nor in a lake.
     */
    @Nullable
    static BlockPos edge(ServerLevel level, Villages.Village v, RandomSource r) {
        BlockPos c = v.centre();
        int reach = Math.min(120, Villages.townReach(v.id()) + 16);
        int start = r.nextInt(8);
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians((start + k) * 45.0);
            for (int out = reach; out >= 16; out -= 8) {
                int x = c.getX() + (int) Math.round(Math.sin(a) * out), z = c.getZ() + (int) Math.round(Math.cos(a) * out);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = surface(level, new BlockPos(x, c.getY(), z));
                if (!level.getFluidState(top.below()).isEmpty() || !level.getFluidState(top).isEmpty()) continue;
                if (!level.getBlockState(top.below()).isFaceSturdy(level, top.below(), Direction.UP)) continue;
                return top;
            }
        }
        return null;
    }

    static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return new BlockPos(p.getX(), y, p.getZ());
    }

    // ------------------------------------------------------------------ a folk's tick

    /**
     * From every folk's tick (VillageFolkEntity.aiStep), before anything of a resident's day: a visitor's
     * whole day; one of ours away for the day at a friend's in another town (FriendVisits); a guard out
     * taming a dog for the watch (WatchDogs). True when that is what it is doing, and nothing else is to be
     * looked at. A guard with a dog also sees to it from here, and goes on with its own day.
     */
    public static boolean drive(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return false;
        if (is(f)) {
            visitor(f, level);
            return true;
        }
        if (FriendVisits.away(f)) return FriendVisits.drive(f, level);
        if (WatchDogs.busy(f)) return WatchDogs.drive(f, level);
        if (f.tickCount % 10 == 4 && f.stationTask() == AssistantEntity.StationTask.GUARD) WatchDogs.lead(f, level);
        return false;
    }

    /** A visitor's day: in from the edge, its stay, and out again. */
    private static void visitor(VillageFolkEntity f, ServerLevel level) {
        Visit v = visit(f);
        Villages.Village town = v == null ? null : Villages.get(v.town);
        if (v == null || town == null) {
            // Nothing to say where it is or why (or the town is gone): it goes on its way.
            LOG.info("[MCA-VISIT] {} has no visit to keep, and goes", f.displayNameCap());
            VISITS.remove(f.getUUID());
            f.discard();
            return;
        }
        Player p = f.talkPartner();
        if (p != null) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(p, 30.0F, 30.0F);
            return;
        }
        v.seen = level.getGameTime();
        if (++v.tick % 10 != 0) return;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        switch (v.stage) {
            case "in" -> {
                if (f.isSleeping()) f.stopSleeping();
                BlockPos goal = goal(level, town, v);
                if (walk(f, level, goal, 6.0, 0.8D, v.walk)) {
                    v.stage = "stay";
                    v.walk.reset();
                    save(f, v);
                    arrived(level, town, f, v, day);
                }
            }
            case "stay" -> {
                boolean go = switch (v.kind) {
                    case BARD -> Bard.stay(level, town, f, v, day, t);
                    case TOURIST -> Tourists.stay(level, town, f, v, day, t);
                    case MERCHANT -> Merchants.stay(level, town, f, v, day, t);
                };
                if (go) setOff(level, town, f, v, day);
            }
            default -> {
                if (f.isSleeping()) f.stopSleeping();
                if (walk(f, level, v.edge, 3.0, 0.8D, v.walk) || f.tickCount - v.leftAt > 6000) gone(level, f, v);
            }
        }
    }

    /** Where it makes for first: the tavern for the bard, the market for the merchant, the heart for anybody else. */
    private static BlockPos goal(ServerLevel level, Villages.Village town, Visit v) {
        Ledger.Building b = switch (v.kind) {
            case BARD -> Tavern.of(town.id());
            case MERCHANT -> building(town.id(), "market");
            case TOURIST -> null;
        };
        return b != null ? b.anchor() : town.centre();
    }

    /** At the town: it says so, and the town takes note of it, once. */
    private static void arrived(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visit v, long day) {
        if (v.told) return;
        v.told = true;
        save(f, v);
        String name = Villages.name(town.id());
        switch (v.kind) {
            case BARD -> Bard.arrived(level, town, f, v, day);
            case TOURIST -> Tourists.arrived(level, town, f, v, day);
            case MERCHANT -> Merchants.arrived(level, town, f, v, day);
        }
        LOG.info("[MCA-VISIT] {} ({}) is in {} now", v.name, v.kind.words, name);
    }

    /** Time to go: its bedroll up, a goodbye, and off back to the edge it came in from. */
    static void setOff(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visit v, long day) {
        rise(level, f, v);
        v.stage = "out";
        v.walk.reset();
        v.leftAt = f.tickCount;
        v.spent = Math.max(0, v.purse0 - f.purse());
        save(f, v);
        log(town.id(), new Logged(day, v.kind, v.name, Math.max(0, v.purse0 - f.purse()), (int) Math.max(0, day - v.arrived)));
        String how = switch (v.kind) {
            case BARD -> v.name + " the bard went on its way after " + nights(day - v.arrived);
            case TOURIST -> v.name + ", a visitor from afar, went home" + (v.purse0 - f.purse() > 0
                ? " having spent " + coins(v.purse0 - f.purse()) + " in town" : "");
            case MERCHANT -> v.name + ", the merchant from afar, packed up its stall and left";
        };
        Villages.tell(town.id(), day, how);
        FolkTalk.speak(f, switch (v.kind) {
            case BARD -> FolkTalk.pick(f.getRandom(), "Farewell, " + Villages.name(town.id()) + "! I'll sing of you in the next town.",
                "The road calls. Thank you for the fire and the company!");
            case TOURIST -> FolkTalk.pick(f.getRandom(), "What a place! I'll tell everyone at home.", "Time I was going. Lovely town!");
            case MERCHANT -> FolkTalk.pick(f.getRandom(), "That's me packed. Till next market day!", "Off before dark. Good trading, all!");
        });
    }

    /** Out of sight at the edge of the world: gone, with all it brought and all it bought. */
    private static void gone(ServerLevel level, VillageFolkEntity f, Visit v) {
        rise(level, f, v);
        LOG.info("[MCA-VISIT] {} ({}) has left {}; it spent {} coins there", v.name, v.kind.words, Villages.name(v.town),
            Math.max(0, v.purse0 - f.purse()));
        VISITS.remove(f.getUUID());
        f.discard();
    }

    /** Tests: the visitor goes now, as it would at the edge. */
    public static void goneForTests(ServerLevel level, VillageFolkEntity f) {
        Visit v = visit(f);
        if (v == null) return;
        Villages.Village town = Villages.get(v.town);
        if (town != null && !"out".equals(v.stage)) setOff(level, town, f, v, level.getDayTime() / 24000L);
        gone(level, f, v);
    }

    /** Tests: the visitor is at the town now (its walk in done). */
    public static void arriveForTests(ServerLevel level, VillageFolkEntity f) {
        Visit v = visit(f);
        Villages.Village town = v == null ? null : Villages.get(v.town);
        if (town == null) return;
        BlockPos goal = goal(level, town, v);
        f.moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, f.getYRot(), 0.0F);
        v.stage = "stay";
        save(f, v);
        arrived(level, town, f, v, level.getDayTime() / 24000L);
    }

    /** Tests: one look at the visitor's stay, as its tick would have it (its walk and its doings). */
    public static boolean stayForTests(ServerLevel level, VillageFolkEntity f) {
        Visit v = visit(f);
        Villages.Village town = v == null ? null : Villages.get(v.town);
        if (town == null) return false;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        boolean go = switch (v.kind) {
            case BARD -> Bard.stay(level, town, f, v, day, t);
            case TOURIST -> Tourists.stay(level, town, f, v, day, t);
            case MERCHANT -> Merchants.stay(level, town, f, v, day, t);
        };
        if (go) setOff(level, town, f, v, day);
        return go;
    }

    static String nights(long n) {
        return n <= 1 ? "a night" : TownCalendar.inWords((int) n) + " nights";
    }

    static String coins(int n) {
        return n + (n == 1 ? " coin" : " coins");
    }

    @Nullable
    static Ledger.Building building(UUID village, String structure) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(structure)) return b;
        return null;
    }

    // ------------------------------------------------------------------ walking

    /**
     * A step of a walk to {@code to}, across open country if need be: a leg of a dozen blocks at a time
     * toward it, and, if it has got no nearer in twenty seconds (a river, a cliff), set down a few blocks
     * on. True once it is within {@code near} blocks.
     */
    static boolean walk(VillageFolkEntity f, ServerLevel level, BlockPos to, double near, double speed, Walk w) {
        if (w.target == null || !w.target.equals(to)) {
            // Somewhere new: its progress is measured afresh.
            w.reset();
            w.target = to.immutable();
            w.gained = f.tickCount;
        }
        double d = flat(f.blockPosition(), to);
        if (d <= near * near) {
            f.getNavigation().stop();
            w.reset();
            return true;
        }
        if (d < w.best - 1.0) {
            w.best = d;
            w.gained = f.tickCount;
        }
        BlockPos aim = d > 18.0 * 18.0 ? surface(level, toward(f.blockPosition(), to, 14)) : to;
        if (f.getNavigation().isDone() || f.tickCount - w.walkTick > 100) {
            f.walkTo(aim, speed);
            w.walkTick = f.tickCount;
            w.heading = aim;
        }
        if (f.tickCount - w.gained > 400) {
            BlockPos step = surface(level, toward(f.blockPosition(), to, (int) Math.min(6, Math.sqrt(d))));
            if (level.getBlockState(step).isAir() && level.getBlockState(step.above()).isAir()) {
                f.getNavigation().stop();
                f.moveTo(step.getX() + 0.5, step.getY(), step.getZ() + 0.5, f.getYRot(), 0.0F);
            }
            w.gained = f.tickCount;
            w.best = Double.MAX_VALUE;
        }
        return false;
    }

    /** A point {@code k} blocks from {@code from} toward {@code to}, flat. */
    static BlockPos toward(BlockPos from, BlockPos to, int k) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3 || len <= k) return to;
        return new BlockPos(from.getX() + (int) Math.round(dx / len * k), from.getY(), from.getZ() + (int) Math.round(dz / len * k));
    }

    static double flat(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ a night at the inn

    /** The town's inn (Inn), if it has one; else null (the tavern is the bard's lodging then: Bard). */
    @Nullable
    static Ledger.Building inn(UUID village) {
        return Inn.inn(village);
    }

    /**
     * A night at the town's inn, as any traveller has one (Inn): a room taken from its keeper at the inn's price,
     * out of the purse the visitor brought, into the till; then to its bed and asleep till morning (Inn.lodging).
     * True while it is lodging; false if there is no inn, nobody keeping it, no bed free or not the coin, and the
     * visitor makes do some other way.
     */
    static boolean lodge(ServerLevel level, VillageFolkEntity f) {
        if (inn(townOfOr(f)) == null) return false;
        if (!Inn.lodged(f) && Inn.takeARoom(level, f) == null) {
            long day = level.getDayTime() / 24000L;
            Long told = NO_ROOM.put(f.getUUID(), day);
            if (told == null || told != day) LOG.info("[MCA-VISIT] {} found no room at the inn: {}", f.displayNameCap(), noRoom(level, f));
            return false;
        }
        Inn.lodging(f, level);
        return true;
    }

    /** The night each visitor was last turned away from the inn (said once a night, in the log). */
    private static final Map<UUID, Long> NO_ROOM = new ConcurrentHashMap<>();

    /** Why a visitor cannot have a room at its town's inn just now, in words; "" if it can (Inn.takeARoom's own tests). */
    static String noRoom(ServerLevel level, VillageFolkEntity f) {
        UUID town = townOf(f);
        Ledger.Building b = town == null ? null : Inn.inn(town);
        if (b == null) return "no inn";
        Villages.Village near = Villages.nearest(level, f.blockPosition(), Villages.VILLAGE_RANGE);
        if (near == null || !near.id().equals(town)) return "not in the town";
        if (Inn.keeper(town) == null) return "nobody keeps the inn (no cook or shopkeeper)";
        if (Inn.freeBed(level, town, b) == null) return "no bed free";
        if (f.purse() < Inn.ROOM) return "not the price of a room (" + f.purse() + " coins)";
        return "";
    }

    /** Tests: why the visitor could not have a room at the inn ("" if it could). */
    public static String noRoomForTests(ServerLevel level, VillageFolkEntity f) {
        return noRoom(level, f);
    }

    private static UUID townOfOr(VillageFolkEntity f) {
        UUID t = townOf(f);
        return t != null ? t : new UUID(0L, 0L);
    }

    /**
     * To bed at the inn (or the tavern): a bed there nobody calls their own, else the bedroll it carries,
     * laid on the floor by a wall where it is in nobody's way. True while it is about it (walking there, or
     * asleep). With neither a bed nor a bedroll it sits up by the fire: false, and it stands where it is.
     */
    static boolean bedDown(ServerLevel level, VillageFolkEntity f, Visit v, Ledger.Building inn) {
        if (f.isSleeping()) return true;
        BlockPos bed = v.bedroll != null && isBed(level, v.bedroll) ? v.bedroll : freeBed(level, v.town, inn);
        if (bed == null) {
            if (!walk(f, level, inn.anchor(), 3.0, 0.8D, v.walk)) return true;
            bed = layBedroll(level, f, v, inn);
            if (bed == null) return false;
        }
        if (!walk(f, level, bed, 1.6, 0.7D, v.walk)) return true;
        f.getNavigation().stop();
        f.startSleeping(bed);
        return true;
    }

    /** Up in the morning, and its bedroll rolled up and back in its pack. */
    static void rise(ServerLevel level, VillageFolkEntity f, Visit v) {
        if (f.isSleeping()) f.stopSleeping();
        if (v.bedroll == null) return;
        BlockPos head = v.bedroll;
        v.bedroll = null;
        save(f, v);
        if (!level.isLoaded(head)) return;
        BlockState st = level.getBlockState(head);
        if (!(st.getBlock() instanceof BedBlock)) return;
        Direction lie = st.getValue(BedBlock.FACING);
        BlockPos foot = st.getValue(BedBlock.PART) == BedPart.HEAD ? head.relative(lie.getOpposite()) : head.relative(lie);
        Block bed = st.getBlock();
        // Taken up quietly, both halves at once: no half left lying, nothing dropped on the floor.
        level.setBlock(foot, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        level.setBlock(head, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        ItemStack roll = new ItemStack(bed.asItem());
        ItemStack left = f.insertGiven(roll);
        if (!left.isEmpty()) f.spawnAtLocation(left);
    }

    private static boolean isBed(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof BedBlock;
    }

    /** A bed in the inn nobody sleeps in and nobody calls their own: the head of it, or null. */
    @Nullable
    private static BlockPos freeBed(ServerLevel level, UUID town, Ledger.Building inn) {
        java.util.Set<BlockPos> claimed = Villages.bedsClaimed(town);
        BlockPos a = inn.anchor();
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-7, -2, -7), a.offset(7, 6, 7))) {
            if (!level.isLoaded(p)) continue;
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof BedBlock) || st.getValue(BedBlock.PART) != BedPart.HEAD) continue;
            if (st.getValue(BedBlock.OCCUPIED) || claimed.contains(p)) continue;
            if (Homes.inAHome(town, p) || Inn.isInnBed(town, p)) continue;          // the inn's beds are let (Inn)
            return p.immutable();
        }
        return null;
    }

    /**
     * The bedroll it carries, laid out in the inn: two cells of floor side by side, empty and roofed, on a
     * sound floor, against a wall and out of the way through the room. The head of it, or null if it has no
     * bedroll or there is nowhere for it.
     */
    @Nullable
    private static BlockPos layBedroll(ServerLevel level, VillageFolkEntity f, Visit v, Ledger.Building inn) {
        ItemStack roll = null;
        for (ItemStack s : f.getInventoryItems()) {
            if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BedItem) { roll = s; break; }
        }
        if (roll == null) return null;
        Decor.Room room = Decor.room(v.town, inn);
        BlockPos best = null;
        Direction bestWay = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : room.floor()) {
            if (room.clear().contains(c) || !room.walls().containsKey(c)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos h = c.relative(d);
                if (!room.floorSet().contains(h) || room.clear().contains(h)) continue;
                if (!lieable(level, c) || !lieable(level, h)) continue;
                if (Decor.cutsTheWay(level, c) || Decor.cutsTheWay(level, h)) continue;
                double dd = c.distSqr(inn.anchor());
                if (dd < bestD) { bestD = dd; best = c; bestWay = d; }
            }
        }
        if (best == null) {
            // A room its drawing does not tell well (an inn of another shape): any two cells of open floor under a
            // roof near the middle of it, out of the way through.
            BlockPos a = inn.anchor();
            for (BlockPos c : BlockPos.betweenClosed(a.offset(-5, -1, -5), a.offset(5, 1, 5))) {
                if (!lieable(level, c) || level.canSeeSky(c)) continue;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos h = c.relative(d);
                    if (!lieable(level, h) || level.canSeeSky(h) || Decor.cutsTheWay(level, c) || Decor.cutsTheWay(level, h)) continue;
                    double dd = c.distSqr(a);
                    if (dd < bestD) { bestD = dd; best = c.immutable(); bestWay = d; }
                }
            }
        }
        if (best == null) return null;
        Block bed = Block.byItem(roll.getItem());
        if (!(bed instanceof BedBlock)) return null;
        BlockPos head = best.relative(bestWay);
        BlockState foot = bed.defaultBlockState().setValue(BedBlock.FACING, bestWay).setValue(BedBlock.PART, BedPart.FOOT);
        level.setBlock(best, foot, Block.UPDATE_ALL);
        level.setBlock(head, foot.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        roll.shrink(1);
        v.bedroll = head.immutable();
        save(f, v);
        level.playSound(null, head, net.minecraft.sounds.SoundEvents.WOOL_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, 1.0F);
        return head;
    }

    private static boolean lieable(ServerLevel level, BlockPos c) {
        if (!level.isLoaded(c)) return false;
        BlockPos under = c.below();
        return level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir()
            && level.getBlockState(under).isFaceSturdy(level, under, Direction.UP);
    }

    // ------------------------------------------------------------------ spirits

    /** Heard the bard tonight (Bard): the better for it today and tomorrow. */
    static void heard(VillageFolkEntity f, long day) {
        HEARD.put(f.getUUID(), day);
        if (HEARD.size() > 4096) HEARD.clear();
    }

    /** A friend came to see it, or it went to see one (FriendVisits): the better for it today and tomorrow. */
    static void visited(VillageFolkEntity f, long day) {
        VISITED.put(f.getUUID(), day);
        if (VISITED.size() > 4096) VISITED.clear();
    }

    /** Its spirits (VillageFolkEntity.refreshMood): a night of the bard's songs; a day with a friend from away. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long h = HEARD.get(f.getUUID());
        if (h != null && day - h <= 1 && day >= h) {
            m += 6;
            why.add(new Object[]{ "bard", 6 });
        }
        Long s = VISITED.get(f.getUUID());
        if (s != null && day - s <= 1 && day >= s) {
            m += 6;
            why.add(new Object[]{ "visit", 6 });
        }
        return m;
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        RandomSource r = f.getRandom();
        if (why.equals("bard")) {
            return FolkTalk.pick(r, "There's a bard at the tavern — what songs! I was up half the night.",
                "Did you hear the bard? Best evening I've had in an age.");
        }
        String friend = FriendVisits.lastFriend(f);
        return friend.isEmpty() ? "I had a friend from away to see me. It does you good."
            : FolkTalk.pick(r, "I spent the day with " + friend + " — it's been too long.", friend + " and I had such a day of it!");
    }

    // ------------------------------------------------------------------ talking to a visitor

    /**
     * What a visitor says (FolkTalk.answer, before a resident's answers are looked at): its greeting, how
     * it is and what it is doing, its own story, the merchant's wares and its sale; anything about the town
     * itself it leaves to the people who live there. Null to let the folk's own answer stand (a gift, a bye).
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, TalkTopic topic, String text) {
        if (!is(f)) return null;
        Visit v = visit(f);
        if (v == null) return null;
        RandomSource r = f.getRandom();
        String town = Villages.name(v.town);
        return switch (topic) {
            case GIFT, BYE, SAY -> null;
            case OPEN -> switch (v.kind) {
                case BARD -> FolkTalk.pick(r, "Well met! " + v.name + ", teller of tales and player of tunes. Come to the tavern tonight!",
                    "A listener! Sit, sit. I've songs from six towns and news from three.");
                case TOURIST -> FolkTalk.pick(r, "Hello! I've walked all the way to see " + town + " — they say it's quite a place.",
                    "Is this the way to the museum? Everyone back home talks of " + town + ".");
                case MERCHANT -> FolkTalk.pick(r, "Goods from far away, friend! Ask me what I've got.",
                    "Things you'll not find in " + town + "'s own land. Have a look!");
            };
            case HOW -> FolkTalk.pick(r, "Footsore, but glad to be here.", "Never better. There's nothing like a new town.");
            case DOING -> capFirst(doing(f));
            case ABOUT, DREAMS, HOBBY -> switch (v.kind) {
                case BARD -> "I go from town to town with my songs, and carry the news between them. A bed and a fire are all I ask.";
                case TOURIST -> "I'm from a long way off. I save up, and every so often I go and see somewhere famous. This time it's "
                    + town + ".";
                case MERCHANT -> "I trade between the far lands: what grows in one, I carry to the next. Market day here, then on.";
            };
            case VILLAGE, GOSSIP, MEMORY -> v.kind == Kind.BARD && f.level() instanceof ServerLevel level
                ? Bard.newsLine(level, Villages.get(v.town), r)
                : "I only came " + (v.arrived == f.level().getDayTime() / 24000L ? "today" : "lately") + ". Ask somebody who lives here.";
            case TRADE -> v.kind == Kind.MERCHANT ? Merchants.offer(f, v, p)
                : "I've nothing to sell — I'm only visiting.";
            case DELIVER -> v.kind == Kind.MERCHANT ? Merchants.close(f, v, p) : "You don't owe me anything!";
            default -> FolkTalk.pick(r, "I'm only passing through — ask somebody who lives here.",
                "That's one for the people of " + town + ", not for me.");
        };
    }

    private static String capFirst(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** What it is doing this minute, for the top of its card (FolkTalk.nowDoing); null for a resident at home. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        if (!is(f)) return FriendVisits.doing(f);
        Visit v = visit(f);
        if (v == null) return null;
        String town = Villages.name(v.town);
        if (f.isSleeping()) return "Asleep at the inn";
        return switch (v.stage) {
            case "in" -> "On the road into " + town;
            case "out" -> "On the road out of " + town + ", going home";
            default -> switch (v.kind) {
                case BARD -> Bard.doing(f, v);
                case TOURIST -> Tourists.doing(f, v);
                case MERCHANT -> "At the market with goods from afar";
            };
        };
    }

    /** A visitor's card: who it is, where from, how long it stays, what it has with it. */
    public static String card(VillageFolkEntity f) {
        Visit v = visit(f);
        if (v == null) return "Visitor|a stranger, passing through";
        StringBuilder sb = new StringBuilder();
        String town = Villages.name(v.town);
        sb.append("Visitor|").append(FolkTalk.cap(v.kind.words)).append(", visiting ").append(town).append("; of no village, so not one of its people");
        long day = f.level().getDayTime() / 24000L;
        sb.append("\nCame|day ").append(v.arrived + 1).append(", on foot from the edge of the world");
        sb.append("\nLeaves|").append(v.kind == Kind.MERCHANT ? "at dusk" : v.leave <= day ? "today"
            : "in " + nights(v.leave - day) + (v.kind == Kind.TOURIST ? "" : " (it sleeps at the inn)"));
        if (v.kind == Kind.MERCHANT && !v.wares.isEmpty()) sb.append("\nSells|").append(Merchants.waresLine(v));
        sb.append("\nPurse|").append(coins(f.purse())).append(v.purse0 > 0 ? " (it came with " + coins(v.purse0) + " of its own)" : "");
        if (f.persona().rolled()) sb.append("\nNature|").append(f.life().traitsLabel());
        return sb.toString();
    }

    /** The lines this batch adds to a resident's card (FolkTalk.card): its keepsakes on show, its dog, its visits. */
    public static List<String[]> cardLines(VillageFolkEntity f) {
        List<String[]> out = new ArrayList<>();
        String kept = KeptGifts.cardLine(f);
        if (!kept.isEmpty()) out.add(new String[]{ "Keeps", kept });
        String dog = WatchDogs.cardLine(f);
        if (!dog.isEmpty()) out.add(new String[]{ "Its dog", dog });
        String visits = FriendVisits.cardLine(f);
        if (!visits.isEmpty()) out.add(new String[]{ "Visits", visits });
        return out;
    }

    // ------------------------------------------------------------------ the books

    /** One visit, for the books: when it ended, who, how long, and what it spent in town. */
    record Logged(long day, Kind kind, String name, int spent, int nights) {}

    static void log(UUID town, Logged l) {
        List<Logged> all = logged(town);
        all.add(l);
        while (all.size() > 24) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (Logged x : all) {
            if (sb.length() > 0) sb.append(';');
            sb.append(x.day()).append('|').append(x.kind().name()).append('|').append(x.name().replace("|", "").replace(";", ""))
                .append('|').append(x.spent()).append('|').append(x.nights());
        }
        Ledger.note(town, "visitors/log", sb.toString());
    }

    static List<Logged> logged(UUID town) {
        List<Logged> out = new ArrayList<>();
        String s = Ledger.note(town, "visitors/log");
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] q = part.split("\\|");
            if (q.length < 5) continue;
            try {
                out.add(new Logged(Long.parseLong(q[0]), Kind.valueOf(q[1]), q[2], Integer.parseInt(q[3]), Integer.parseInt(q[4])));
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    /** The visitors this week (the last seven days), and what they spent in town. */
    public static int[] week(UUID town, long day) {
        int n = 0, spent = 0;
        for (Logged l : logged(town)) {
            if (day - l.day() > 6) continue;
            n++;
            spent += l.spent();
        }
        return new int[]{ n, spent };
    }

    /**
     * The town's books (Annals: the News page): who has come lately and what they spent, who is here now,
     * the town's folk away at a friend's, the map on the hall's wall and the watch's dogs.
     */
    public static List<String> book(ServerLevel level, UUID town) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        int[] wk = week(town, day);
        int bards = 0, tourists = 0, merchants = 0;
        for (Logged l : logged(town)) {
            if (day - l.day() > 6) continue;
            switch (l.kind()) {
                case BARD -> bards++;
                case TOURIST -> tourists++;
                case MERCHANT -> merchants++;
            }
        }
        List<String> kinds = new ArrayList<>();
        if (bards > 0) kinds.add(bards == 1 ? "a bard" : bards + " bards");
        if (tourists > 0) kinds.add(tourists == 1 ? "a tourist" : tourists + " tourists");
        if (merchants > 0) kinds.add(merchants == 1 ? "a merchant" : merchants + " merchants");
        out.add(wk[0] == 0 ? "No visitors this week." : "Visitors this week: " + wk[0] + " (" + String.join(", ", kinds) + "), who spent "
            + coins(wk[1]) + " in town.");
        for (VillageFolkEntity f : inTown(level, town, null)) {
            String d = doing(f);
            out.add("In town now: " + f.displayNameCap() + (d == null ? "" : " — " + d.toLowerCase(Locale.ROOT)));
        }
        List<Logged> all = logged(town);
        for (int i = all.size() - 1, k = 0; i >= 0 && k < 4; i--, k++) {
            Logged l = all.get(i);
            out.add("Day " + (l.day() + 1) + ": " + l.name() + ", " + l.kind().words + (l.nights() > 0 ? ", " + nights(l.nights()) : "")
                + (l.spent() > 0 ? ", spent " + coins(l.spent()) : ""));
        }
        out.addAll(FriendVisits.bookLines(level, town, day));
        String map = MapRoom.bookLine(town, day);
        if (!map.isEmpty()) out.add(map);
        String dogs = WatchDogs.bookLine(level, town);
        if (!dogs.isEmpty()) out.add(dogs);
        return out;
    }

    // ------------------------------------------------------------------ /village visitors

    /** The things /village visitors &lt;what&gt; can bring about now (operators, and the pictures). */
    public static List<String> kinds() {
        return List.of("bard", "tourist", "merchant", "friend", "map", "dog", "gifts", "evening");
    }

    /**
     * /village visitors [what]: who is visiting and where (a "VISITOR kind name x y z" line each, for
     * scripts), the books' lines; and, for operators, one brought about now with the town's own stores,
     * purses and hands, not waiting for the day or the hour.
     */
    public static List<String> command(ServerLevel level, Villages.Village v, @Nullable String what) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        if (what != null) {
            switch (what) {
                case "bard" -> {
                    VillageFolkEntity f = Bard.comeForTests(level, v);
                    out.add(f == null ? "BARD-NOW none (no ground at the edge to come in from)" : "BARD-NOW " + f.displayNameCap());
                }
                case "tourist" -> {
                    VillageFolkEntity f = Tourists.comeForTests(level, v);
                    out.add(f == null ? "TOURIST-NOW none" : "TOURIST-NOW " + f.displayNameCap());
                }
                case "merchant" -> {
                    VillageFolkEntity f = Merchants.comeForTests(level, v);
                    out.add(f == null ? "MERCHANT-NOW none" : "MERCHANT-NOW " + f.displayNameCap());
                }
                case "evening" -> {
                    // Everybody in: the visitors at the town now, and the bard at its place in the tavern.
                    for (VillageFolkEntity f : inTown(level, v.id(), null)) arriveForTests(level, f);
                    out.add("EVENING-NOW the visitors are in town");
                }
                case "friend" -> out.add("FRIEND-NOW " + FriendVisits.sendForTests(level, v));
                case "map" -> out.add("MAP-NOW " + MapRoom.makeForTests(level, v));
                case "dog" -> out.add("DOG-NOW " + WatchDogs.tameNowForTests(level, v));
                case "gifts" -> out.add("GIFTS-NOW " + KeptGifts.showAllForTests(level, v));
                default -> out.add("unknown: " + what + " (" + String.join(", ", kinds()) + ")");
            }
        }
        for (VillageFolkEntity f : inTown(level, v.id(), null)) {
            Visit vis = visit(f);
            BlockPos p = f.blockPosition();
            out.add("VISITOR " + (vis == null ? "?" : vis.kind.name().toLowerCase(Locale.ROOT)) + " " + f.displayNameCap().replace(' ', '_')
                + " " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + (vis == null ? "" : vis.stage));
        }
        out.addAll(MapRoom.lines(level, v));
        out.addAll(WatchDogs.lines(level, v));
        out.addAll(book(level, v.id()));
        return out;
    }
}
