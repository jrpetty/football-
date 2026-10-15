package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [borders-map] The border markers: the town's banners on posts where its lands end, and its stones where its roads
 * cross the line.
 *
 * <p><b>Where.</b> Along the line where its lands meet a neighbour's, a banner every three chunks or so (ten at most);
 * out where its lands give onto the wilds, one at each of the eight points of the compass; and a stone at the roadside
 * where each road to a colony (or from its mother town) leaves its lands, lettered on a sign: "The lands of / ASHFORD /
 * begin here".
 *
 * <p><b>What they cost.</b> The town's banner as the tailor weaves it (Heraldry: its field, its charges, out of the stores'
 * wool and dye at the bench and the loom), or a plain banner in its colour before it has arms; a log out of the stores
 * for the post (a cobblestone, if it has no logs; set straight on the ground, if it has neither). A stone is a block of
 * the stores' cobblestone set as a post, and a sign of their planks. Never anything the stores do not hold: the Lands page
 * says what the next one waits on.
 *
 * <p><b>Who.</b> A hand at the town's works walks out and plants them, one at a time, by day (TownJobs: the road crew's
 * long shift, so the walk out and back is one turn). The ground at the spot is woken while it works (ChunkLoad), and a
 * hand that cannot get there with nobody about to see it (the way through ground asleep) is set down at the spot, the
 * way a fighter left behind on the march is.
 *
 * <p><b>Kept up.</b> A banner knocked down (a creeper, a storm-felled post, a player) is planted again out of the stores.
 * Pulled up by a player who is a citizen of a neighbour, it is a cause of dispute between the towns (BorderDisputes);
 * by anybody else, the town thinks the less of them. A banner left on ground that is no longer the town's (a treaty, a
 * war lost) is taken down by its own folk and back into its stores; another town's banner planted on its land by a
 * player is pulled up (dropped where it stood: it is not the town's to keep) and the town complains of the other.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class BorderMarkers {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private BorderMarkers() {}

    /** The works' name (TownJobs): a road crew's long shift, for the walk out to the edge of the lands and back. */
    static final String WORKS = "road/borders";
    /** Banners on the line with neighbours, and on the wild edge, at most. */
    static final int MEETING_MOST = 10, WILD_MOST = 8;
    /** At least this many chunks between two banners. */
    static final int SPACING = 3;
    /** Ticks between a town's looks at its markers: soon while there is work, else now and then. */
    static final long BUSY = 100L, IDLE = 1200L;
    /** Ticks a hand has to get to the spot before it is set down there (when nobody is about to see it). */
    static final long WALK_MOST = 900L;

    /** A marker: where, whose, a banner or a stone, when planted, how often planted again. */
    public static final class Marker {
        long pos;
        UUID town;
        boolean stone, byPlayer;
        long placed;
        int replanted;
        String with = "";

        public BlockPos pos() { return BlockPos.of(pos); }
        public UUID town() { return town; }
        public boolean stone() { return stone; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("pos", pos);
            t.putString("town", town.toString());
            t.putBoolean("stone", stone);
            t.putBoolean("player", byPlayer);
            t.putLong("placed", placed);
            t.putInt("again", replanted);
            t.putString("with", with);
            return t;
        }

        @Nullable
        static Marker load(CompoundTag t) {
            Marker m = new Marker();
            m.town = Borders.uuid(t.getString("town"));
            if (m.town == null) return null;
            m.pos = t.getLong("pos");
            m.stone = t.getBoolean("stone");
            m.byPlayer = t.getBoolean("player");
            m.placed = t.getLong("placed");
            m.replanted = t.getInt("again");
            m.with = t.getString("with");
            return m;
        }
    }

    /** A place a marker is wanted: the chunk, the side of it the line runs along, the neighbour there (null: the wilds). */
    record Spot(long chunk, Direction out, @Nullable UUID with, boolean stone, @Nullable BlockPos at) {}

    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();
    /** The spot a town's hand is walking to, and since when; the ground there woken. */
    private static final Map<UUID, long[]> WALK = new ConcurrentHashMap<>();

    static void resetForTests() {
        DUE.clear();
        SHORT.clear();
        WALK.clear();
    }

    static Long2ObjectOpenHashMap<Marker> of(Borders b, String dim) {
        return b.markers.computeIfAbsent(dim, k -> new Long2ObjectOpenHashMap<>());
    }

    /** A town gone: its markers are nobody's now (they stand as they are, relics of it). */
    static void abandon(Borders b, UUID town) {
        for (Long2ObjectOpenHashMap<Marker> m : b.markers.values()) m.values().removeIf(x -> x.town.equals(town));
        DUE.remove(town);
    }

    /** The land has moved under these two: both look at their markers at once. */
    static void swapSoon(UUID a, UUID b) {
        DUE.remove(a);
        DUE.remove(b);
    }

    /** Is a town's banner (or stone) standing here? */
    static boolean standing(ServerLevel level, Marker m) {
        BlockPos p = m.pos();
        if (!level.isLoaded(p)) return true;                             // asleep: as it was left
        BlockState st = level.getBlockState(p);
        return m.stone ? st.is(BlockTags.WALLS) : st.getBlock() instanceof AbstractBannerBlock;
    }

    // ------------------------------------------------------------------ the town's round

    static void tick(ServerLevel level) {
        if (!Borders.on()) return;
        long now = level.getGameTime();
        long t = level.getDayTime() % 24000L;
        if (t >= 12500L && t < 23500L && !TownJobs.instantNow()) return;      // by day
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Long due = DUE.get(v.id());
            if (due != null && now < due) continue;
            boolean busy = false;
            try {
                busy = work(level, v);
            } catch (RuntimeException e) {
                com.jrpetty.mcassistant.Guard.struck("border markers", Villages.name(v.id()), e);
            }
            DUE.put(v.id(), now + (busy ? BUSY : IDLE + Math.floorMod(v.id().hashCode(), 200)));
        }
    }

    /** Tests: the town's markers worked at until nothing is left the stores run to (the works done at once). Returns what was done. */
    public static List<String> workForTests(ServerLevel level, Villages.Village v) {
        List<String> did = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String before = LAST.get(v.id());
            LAST.remove(v.id());
            if (!work(level, v)) break;
            String d = LAST.get(v.id());
            if (d != null) did.add(d);
            else if (before != null) LAST.put(v.id(), before);
        }
        return did;
    }

    /** What the last piece of work was (tests). */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    /**
     * One piece of the town's border work, the most pressing first: a banner of ours left on ground that is not ours
     * any more taken down; a banner a player planted for another town on our land pulled up; a marker knocked down
     * planted again; the next new one. True while there is more to do (or a hand on its way).
     */
    static boolean work(ServerLevel level, Villages.Village v) {
        Borders b = Borders.data(level.getServer());
        UUID id = v.id();
        String dim = Borders.dim(level);
        Borders.Land land = b.lands.get(dim);
        if (land == null) return false;
        Long2ObjectOpenHashMap<Marker> all = of(b, dim);
        long day = level.getDayTime() / 24000L;
        // 1. Ours, on ground that is not ours now.
        for (Marker m : new ArrayList<>(all.values())) {
            if (!m.town.equals(id) || m.byPlayer) continue;
            UUID o = land.owner.get(chunk(m.pos));
            if (id.equals(o)) continue;
            if (!level.isLoaded(m.pos())) { if (wake(level, id, m.pos())) return true; continue; }
            if (!standing(level, m)) { all.remove(m.pos); b.setDirty(); continue; }
            if (!atWork(level, v, m.pos(), "taking our border banner down from ground that is " + (o == null ? "nobody's" : Villages.name(o) + "'s") + " now")) return true;
            takeDown(level, v, m);
            all.remove(m.pos);
            b.setDirty();
            sleep(level, id);
            done(id, "down " + m.pos().toShortString());
            return true;
        }
        // 2. Another town's banner a player planted on our land.
        for (Marker m : new ArrayList<>(all.values())) {
            if (m.town.equals(id) || !m.byPlayer || !id.equals(land.owner.get(chunk(m.pos)))) continue;
            if (!level.isLoaded(m.pos())) { if (wake(level, id, m.pos())) return true; continue; }
            if (!standing(level, m)) { all.remove(m.pos); b.setDirty(); continue; }
            if (!atWork(level, v, m.pos(), "pulling up " + Villages.name(m.town) + "'s banner from our land")) return true;
            level.destroyBlock(m.pos(), true);
            all.remove(m.pos);
            b.setDirty();
            sleep(level, id);
            Villages.tell(id, day, "our folk pulled up a banner of " + Villages.name(m.town) + " planted on our land");
            done(id, "pulled " + m.pos().toShortString());
            return true;
        }
        // 3. Ours, knocked down.
        for (Marker m : new ArrayList<>(all.values())) {
            if (!m.town.equals(id) || m.byPlayer || !id.equals(land.owner.get(chunk(m.pos)))) continue;
            if (!level.isLoaded(m.pos())) continue;
            if (standing(level, m)) continue;
            Plant p = plant(level, b, v, new Spot(chunk(m.pos), Direction.NORTH, Borders.uuid(m.with), m.stone, m.pos()), m);
            if (p == Plant.SKIP) { all.remove(m.pos); b.setDirty(); continue; }
            if (p == Plant.SHORT || p == Plant.NONE) break;
            return true;
        }
        // 4. The next new one.
        for (Spot s : wanted(level, b, land, v)) {
            if (hasMarker(all, id, s)) continue;
            Plant p = plant(level, b, v, s, null);
            if (p == Plant.SKIP) continue;
            return p != Plant.SHORT && p != Plant.NONE;
        }
        return false;
    }

    enum Plant { DONE, WALKING, SHORT, SKIP, NONE }

    static boolean hasMarker(Long2ObjectOpenHashMap<Marker> all, UUID id, Spot s) {
        for (Marker m : all.values()) {
            if (!m.town.equals(id) || m.stone != s.stone) continue;
            if (s.stone ? s.at != null && m.pos().distManhattan(s.at) <= 6 : chunk(m.pos) == s.chunk) return true;
        }
        return false;
    }

    static long chunk(long pos) {
        BlockPos p = BlockPos.of(pos);
        return ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
    }

    /**
     * One marker planted (or planted again, {@code again}): the ground found, the makings worked out at the bench, a hand
     * walked out, the makings taken out of the stores and set up there.
     */
    static Plant plant(ServerLevel level, Borders b, Villages.Village v, Spot s, @Nullable Marker again) {
        UUID id = v.id();
        BlockPos probe = s.at != null ? s.at : edgeColumn(s);
        if (!level.isLoaded(probe)) return wake(level, id, probe) ? Plant.WALKING : Plant.SKIP;
        // Planted again: on its own post where the post still stands (only the banner was knocked off it), else on
        // fresh ground at the same spot.
        boolean post = false;
        BlockPos at;
        if (again != null && !again.stone) {
            BlockPos flag = again.pos();
            BlockState under = level.getBlockState(flag.below());
            if ((under.is(BlockTags.LOGS) || under.is(Blocks.COBBLESTONE_WALL)) && level.getBlockState(flag).canBeReplaced()) {
                at = flag.below();
                post = true;
            } else at = groundFor(level, flag);
        } else at = s.stone ? s.at : findGround(level, s);
        if (at == null) return Plant.SKIP;
        String with = s.with == null ? "" : " with " + Villages.name(s.with);
        if (s.stone) {
            if (!hasCobble(level, v)) {
                SHORT.put(id, "a cobblestone for the border stone");
                return Plant.SHORT;
            }
            if (!atWork(level, v, at, "setting a border stone by the road")) return Plant.WALKING;
            if (!TownWork.take(level, v, st -> st.is(Items.COBBLESTONE_WALL), 1) && !TownWork.take(level, v, st -> st.is(Items.COBBLESTONE), 1)) {
                SHORT.put(id, "a cobblestone for the border stone");
                return Plant.SHORT;
            }
            level.setBlock(at, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
            sign(level, v, at.above(), s.out);
            record(level, b, id, at, true, s.with, again);
            sleep(level, id);
            done(id, "stone " + at.toShortString());
            if (again == null) Borders.note(b, id, level.getDayTime() / 24000L, "a border stone was set by the road at "
                + at.getX() + ", " + at.getZ(), false);
            return Plant.DONE;
        }
        Heraldry.Design d = Heraldry.design(id);
        DyeColor field = d != null ? d.field() : dye(Villages.colour(id));
        VillageFolkEntity tailor = Heraldry.tailor(id);
        Bench.Plan plan = d != null ? Heraldry.plan(level, v, d)
            : Bench.plan(level, v, List.of(Bench.Want.of(Heraldry.bannerItem(field), 1)), Bench.handOf(level, v, tailor, "workshop"));
        if (!plan.ok()) {
            SHORT.put(id, plan.shortOf + (plan.why.isEmpty() ? "" : " (" + plan.why + ")"));
            return Plant.SHORT;
        }
        SHORT.remove(id);
        if (!atWork(level, v, at, (again != null ? "planting our border banner again" : "planting the town's banner at the border") + with)) return Plant.WALKING;
        if (!Bench.take(level, v, plan, tailor)) return Plant.SHORT;
        ItemStack banner = d != null ? Heraldry.item(level, id, d) : new ItemStack(Heraldry.bannerItem(field));
        if (d == null) banner.set(net.minecraft.core.component.DataComponents.ITEM_NAME, Component.literal("Banner of " + Villages.name(id)));
        // The post: a log, else a cobblestone, else the banner straight on the ground (or the post still standing).
        BlockState postBlock = null;
        if (post) postBlock = level.getBlockState(at);
        else if (TownWork.take(level, v, st -> st.is(ItemTags.LOGS), 1)) postBlock = Blocks.SPRUCE_LOG.defaultBlockState();
        else if (TownWork.take(level, v, st -> st.is(Items.COBBLESTONE), 1)) postBlock = Blocks.COBBLESTONE_WALL.defaultBlockState();
        BlockPos flag = at;
        if (postBlock != null) {
            if (!post) level.setBlock(at, postBlock, 3);
            flag = at.above();
        }
        Arms.plant(level, flag, s.out, banner);
        record(level, b, id, flag, false, s.with, again);
        sleep(level, id);
        long day = level.getDayTime() / 24000L;
        if (again != null) {
            again.replanted++;
            Borders.note(b, id, day, "a border banner knocked down at " + flag.getX() + ", " + flag.getZ() + " was planted again", false);
        } else {
            Borders.Lands ls = b.lands(id);
            int ours = 0;
            for (Marker m : of(b, Borders.dim(level)).values()) if (m.town.equals(id) && !m.stone) ours++;
            if (ours == 1) Villages.tell(id, day, "the town's first border banner was planted" + (s.with != null ? ", on the line" + with : " at the edge of our lands"));
            else Borders.note(b, id, day, "a border banner was planted" + (s.with != null ? " on the line" + with : " at the edge of our lands"), false);
            ls.area = Math.max(ls.area, 0);
        }
        done(id, (again != null ? "again " : "banner ") + flag.toShortString());
        return Plant.DONE;
    }

    static boolean hasCobble(ServerLevel level, Villages.Village v) {
        return Villages.stock(level, v.centre(), Villages.Task.STONE) > 0;
    }

    static void record(ServerLevel level, Borders b, UUID id, BlockPos at, boolean stone, @Nullable UUID with, @Nullable Marker again) {
        Long2ObjectOpenHashMap<Marker> all = of(b, Borders.dim(level));
        if (again != null && again.pos != at.asLong()) all.remove(again.pos);
        Marker m = again != null ? again : new Marker();
        m.pos = at.asLong();
        m.town = id;
        m.stone = stone;
        m.placed = level.getDayTime() / 24000L;
        m.with = with == null ? "" : with.toString();
        all.put(m.pos, m);
        b.version++;
        b.setDirty();
    }

    static void done(UUID id, String what) {
        LAST.put(id, what);
    }

    /** The banner (and its post) taken down into the town's own stores. */
    static void takeDown(ServerLevel level, Villages.Village v, Marker m) {
        BlockPos p = m.pos();
        if (m.stone) {
            BlockState st = level.getBlockState(p.above());
            if (st.getBlock() instanceof StandingSignBlock) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            Crafts.store(level, v, new ItemStack(Items.COBBLESTONE_WALL));
            return;
        }
        if (level.getBlockEntity(p) instanceof BannerBlockEntity be) {
            ItemStack item = be.getItem();
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            Crafts.store(level, v, item);
        }
        BlockState below = level.getBlockState(p.below());
        if (below.is(BlockTags.LOGS)) {
            level.setBlock(p.below(), Blocks.AIR.defaultBlockState(), 3);
            Crafts.store(level, v, new ItemStack(Items.SPRUCE_LOG));
        } else if (below.is(Blocks.COBBLESTONE_WALL)) {
            level.setBlock(p.below(), Blocks.AIR.defaultBlockState(), 3);
            Crafts.store(level, v, new ItemStack(Items.COBBLESTONE_WALL));
        }
    }

    /** The stone's sign: "The lands of / NAME / begin here", facing out along the road. */
    static void sign(ServerLevel level, Villages.Village v, BlockPos at, Direction out) {
        if (!level.getBlockState(at).isAir()) return;
        Bench.Plan plan = Bench.plan(level, v, Items.OAK_SIGN, 1, Bench.handOf(level, v, null, null));
        if (!plan.ok() || !Bench.take(level, v, plan, null)) return;
        BlockState st = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, out.get2DDataValue() * 4);
        if (!st.canSurvive(level, at)) {
            Crafts.store(level, v, new ItemStack(Items.OAK_SIGN));
            return;
        }
        level.setBlock(at, st, 3);
        if (level.getBlockEntity(at) instanceof SignBlockEntity s) {
            String name = Villages.name(v.id()).toUpperCase(java.util.Locale.ROOT);
            TownLife.write(s, new String[]{ "The lands of", name.length() > 15 ? name.substring(0, 15) : name, "begin here", "" });
        }
    }

    // ------------------------------------------------------------------ walking out

    /** The hand at the spot? If not, sent; and if it has been walking long with nobody about, set down there. */
    static boolean atWork(ServerLevel level, Villages.Village v, BlockPos at, String what) {
        if (TownJobs.atWork(level, v, WORKS, at, what)) {
            WALK.remove(v.id());
            return true;
        }
        long now = level.getGameTime();
        long[] w = WALK.get(v.id());
        if (w == null || w[0] != at.asLong()) {
            WALK.put(v.id(), new long[]{ at.asLong(), now });
            wake(level, v.id(), at);
            return false;
        }
        if (now - w[1] < WALK_MOST) return false;
        // Long on the way: the hand set down at the spot if nobody is about to see it happen (ground asleep between).
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !what.equals(f.hobbyNow)) continue;
            if (watched(level, f.blockPosition()) || watched(level, at)) return false;
            f.getNavigation().stop();
            f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.5);
            w[1] = now;
            break;
        }
        return false;
    }

    static boolean watched(ServerLevel level, BlockPos p) {
        for (ServerPlayer pl : level.players()) if (pl.blockPosition().distSqr(p) < 96 * 96) return true;
        return false;
    }

    static UUID ticket(UUID town) {
        return UUID.nameUUIDFromBytes(("borders/" + town).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final Map<UUID, BlockPos> WOKEN = new ConcurrentHashMap<>();

    /** The ground at the spot kept awake while the town works there. False if it cannot be (chunk loading is off). */
    static boolean wake(ServerLevel level, UUID town, BlockPos at) {
        if (!com.jrpetty.mcassistant.AssistantConfig.chunkLoading()) return false;
        BlockPos was = WOKEN.get(town);
        if (was != null && (was.getX() >> 4) == (at.getX() >> 4) && (was.getZ() >> 4) == (at.getZ() >> 4)) return true;
        if (was != null) ChunkLoad.setLoaded(level, ticket(town), was, 0, false);
        ChunkLoad.setLoaded(level, ticket(town), at, 0, true);
        WOKEN.put(town, at.immutable());
        return true;
    }

    /** The work there done: the ground let sleep again. */
    static void sleep(ServerLevel level, UUID town) {
        BlockPos was = WOKEN.remove(town);
        if (was != null) ChunkLoad.setLoaded(level, ticket(town), was, 0, false);
    }

    // ------------------------------------------------------------------ where

    /**
     * Where the town wants its markers: a banner every few chunks along the line with each neighbour, one at each of the
     * eight points of the compass where its lands give onto the wilds, and a stone where each of its roads leaves them.
     */
    static List<Spot> wanted(ServerLevel level, Borders b, Borders.Land land, Villages.Village v) {
        UUID id = v.id();
        int hx = v.centre().getX() >> 4, hz = v.centre().getZ() >> 4;
        List<Spot> meeting = new ArrayList<>(), wild = new ArrayList<>();
        for (Long2ObjectOpenHashMap.Entry<UUID> e : land.owner.long2ObjectEntrySet()) {
            if (!e.getValue().equals(id)) continue;
            long c = e.getLongKey();
            int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
            if (Math.abs(x - hx) <= 2 && Math.abs(z - hz) <= 2) continue;       // never in the town itself
            for (Direction d : Direction.Plane.HORIZONTAL) {
                UUID n = land.owner.get(ChunkPos.asLong(x + d.getStepX(), z + d.getStepZ()));
                if (id.equals(n)) continue;
                (n == null ? wild : meeting).add(new Spot(c, d, n, false, null));
                break;
            }
        }
        List<Spot> out = new ArrayList<>();
        meeting.sort(Comparator.comparingDouble(s -> dist(s.chunk, hx, hz)));
        for (Spot s : meeting) {
            if (count(out, false) >= MEETING_MOST) break;
            if (spaced(out, s)) out.add(s);
        }
        // The wilds: the farthest edge chunk nearest each of the eight points.
        for (int bearing = 0; bearing < 8; bearing++) {
            double ang = bearing * Math.PI / 4;
            Spot best = null;
            double bestScore = Double.MAX_VALUE;
            for (Spot s : wild) {
                double dx = ChunkPos.getX(s.chunk) - hx, dz = ChunkPos.getZ(s.chunk) - hz;
                double off = Math.abs(Math.atan2(Math.sin(Math.atan2(dz, dx) - ang), Math.cos(Math.atan2(dz, dx) - ang)));
                if (off > Math.PI / 8) continue;
                double score = off * 10 - Math.hypot(dx, dz);
                if (score < bestScore) { bestScore = score; best = s; }
            }
            if (best != null && spaced(out, best) && count(out, false) < MEETING_MOST + WILD_MOST) out.add(best);
        }
        out.addAll(stones(level, land, v));
        return out;
    }

    static double dist(long c, int hx, int hz) {
        return Math.hypot(ChunkPos.getX(c) - hx, ChunkPos.getZ(c) - hz);
    }

    static int count(List<Spot> l, boolean stone) {
        int n = 0;
        for (Spot s : l) if (s.stone == stone) n++;
        return n;
    }

    static boolean spaced(List<Spot> chosen, Spot s) {
        int x = ChunkPos.getX(s.chunk), z = ChunkPos.getZ(s.chunk);
        for (Spot o : chosen) {
            if (o.stone) continue;
            if (Math.max(Math.abs(ChunkPos.getX(o.chunk) - x), Math.abs(ChunkPos.getZ(o.chunk) - z)) < SPACING) return false;
        }
        return true;
    }

    /** Where each of the town's roads leaves its lands: the last step of it still ours, and two blocks to the side. */
    static List<Spot> stones(ServerLevel level, Borders.Land land, Villages.Village v) {
        List<Spot> out = new ArrayList<>();
        for (Map.Entry<UUID, UUID> link : Ledger.links().entrySet()) {
            Villages.Village colony = Villages.get(link.getKey()), mother = Villages.get(link.getValue());
            if (colony == null || mother == null || !(colony.id().equals(v.id()) || mother.id().equals(v.id()))) continue;
            List<int[]> line = Roads.line(mother, colony);
            int[] state = Ledger.road(colony.id());
            int laid = state == null ? 0 : state[2] == 1 ? line.size() : state[0];
            if (!v.id().equals(mother.id())) line = new ArrayList<>(line.reversed());
            if (!v.id().equals(mother.id())) laid = state != null && state[2] == 1 ? line.size() : 0;   // walked from the colony's end, only once it is done
            for (int i = 1; i < Math.min(laid, line.size()); i++) {
                int[] p = line.get(i), q = line.get(i - 1);
                UUID here = land.owner.get(ChunkPos.asLong(p[0] >> 4, p[1] >> 4)), before = land.owner.get(ChunkPos.asLong(q[0] >> 4, q[1] >> 4));
                if (!v.id().equals(before) || v.id().equals(here)) continue;
                int dx = Integer.signum(p[0] - q[0]), dz = Integer.signum(p[1] - q[1]);
                Direction along = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                Direction side = along.getClockWise();
                BlockPos col = new BlockPos(q[0], 0, q[1]).relative(side, 3);
                BlockPos at = Land.surface(level, col.getX(), col.getZ());
                if (at != null && fit(level, at)) out.add(new Spot(ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4), along, here, true, at));
                break;
            }
        }
        return out;
    }

    /** The middle of the chunk's side the line runs along, three blocks in. */
    static BlockPos edgeColumn(Spot s) {
        int x0 = ChunkPos.getX(s.chunk) * 16, z0 = ChunkPos.getZ(s.chunk) * 16;
        return switch (s.out) {
            case EAST -> new BlockPos(x0 + 12, 64, z0 + 8);
            case WEST -> new BlockPos(x0 + 3, 64, z0 + 8);
            case SOUTH -> new BlockPos(x0 + 8, 64, z0 + 12);
            default -> new BlockPos(x0 + 8, 64, z0 + 3);
        };
    }

    /** Firm, open, natural ground near the middle of the chunk's edge (not water, not a road, not anybody's building). */
    @Nullable
    static BlockPos findGround(ServerLevel level, Spot s) {
        BlockPos c = edgeColumn(s);
        Direction along = s.out.getClockWise();
        for (int in = 0; in <= 6; in += 2) {
            for (int a : new int[]{ 0, 2, -2, 4, -4, 6, -6 }) {
                BlockPos col = c.relative(along, a).relative(s.out.getOpposite(), in);
                BlockPos at = Land.surface(level, col.getX(), col.getZ());
                if (at != null && fit(level, at)) return at;
            }
        }
        return null;
    }

    @Nullable
    static BlockPos groundFor(ServerLevel level, BlockPos flag) {
        BlockPos at = Land.surface(level, flag.getX(), flag.getZ());
        return at != null && fit(level, at) ? at : null;
    }

    static boolean fit(ServerLevel level, BlockPos at) {
        BlockState below = level.getBlockState(at.below());
        if (!below.isFaceSturdy(level, at.below(), Direction.UP) || !level.getFluidState(at.below()).isEmpty()) return false;
        if (!(below.is(BlockTags.DIRT) || below.is(BlockTags.SAND) || below.is(BlockTags.BASE_STONE_OVERWORLD) || below.is(Blocks.GRAVEL)
            || below.is(Blocks.SNOW_BLOCK) || below.is(BlockTags.TERRACOTTA))) return false;
        if (below.is(Blocks.FARMLAND) || below.is(Blocks.DIRT_PATH)) return false;
        for (BlockPos p : new BlockPos[]{ at, at.above(), at.above(2) }) {
            BlockState st = level.getBlockState(p);
            if (!st.isAir() && !st.canBeReplaced()) return false;
            if (!level.getFluidState(p).isEmpty()) return false;
        }
        return true;
    }

    /** The dye of the town's border banners before it has arms (its colour's nearest). */
    public static DyeColor colourOf(UUID town) {
        Heraldry.Design d = Heraldry.design(town);
        return d != null ? d.field() : dye(Villages.colour(town));
    }

    /** The town's colour as the nearest dye (for a banner before it has arms). */
    static DyeColor dye(int rgb) {
        DyeColor best = DyeColor.WHITE;
        double bd = Double.MAX_VALUE;
        for (DyeColor c : DyeColor.values()) {
            int t = c.getTextureDiffuseColor();
            double d = Math.pow(((t >> 16) & 255) - ((rgb >> 16) & 255), 2) + Math.pow(((t >> 8) & 255) - ((rgb >> 8) & 255), 2)
                + Math.pow((t & 255) - (rgb & 255), 2);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    // ------------------------------------------------------------------ players and the markers

    /** A border marker broken: by a neighbour's citizen, a cause of dispute; by anybody else, the town thinks the less of them. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Borders.on()) return;
        Player p = event.getPlayer();
        if (p == null || p.isCreative()) return;
        com.jrpetty.mcassistant.Guard.run("border marker broken", () -> {
            Borders b = Borders.data(level.getServer());
            Long2ObjectOpenHashMap<Marker> all = of(b, Borders.dim(level));
            BlockPos pos = event.getPos();
            Marker m = all.get(pos.asLong());
            if (m == null) m = all.get(pos.above().asLong());              // its post
            if (m == null || m.byPlayer) return;
            UUID town = m.town;
            if (Villages.get(town) == null || Ledger.citizen(town, p.getUUID())) return;
            long day = level.getDayTime() / 24000L;
            UUID theirs = null;
            for (Villages.Village o : Villages.every()) {
                if (!o.id().equals(town) && Ledger.citizen(o.id(), p.getUUID()) && Ledger.knowEachOther(town, o.id())) { theirs = o.id(); break; }
            }
            String name = p.getName().getString();
            if (theirs != null) {
                BorderDisputes.raise(level, town, theirs, BorderDisputes.Cause.BANNER,
                    name + " of " + Villages.name(theirs) + " pulled up our border banner", m.pos());
            } else {
                Villages.tell(town, day, name + " pulled up one of our border banners");
            }
            int told = 0;
            for (AssistantEntity a : Villages.folkOf(town)) {
                if (told >= 4 || !(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
                f.persona().feelFor(p.getUUID(), name, -3);
                told++;
            }
            Standing.stir(town, p.getUUID());
            p.sendSystemMessage(Component.literal("That was " + Villages.name(town) + "'s border banner. Its folk will not thank you for it.")
                .withStyle(ChatFormatting.RED));
        });
    }

    /** A town's banner set down by a player on another town's land: the land's town complains of the banner's. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Borders.on()) return;
        if (!(event.getEntity() instanceof Player p) || !(event.getPlacedBlock().getBlock() instanceof AbstractBannerBlock)) return;
        com.jrpetty.mcassistant.Guard.run("banner placed", () -> {
            if (!(level.getBlockEntity(event.getPos()) instanceof BannerBlockEntity be)) return;
            UUID land = Borders.owner(level, event.getPos());
            if (land == null) return;
            for (Villages.Village v : Villages.every()) {
                if (v.id().equals(land)) continue;
                Heraldry.Design d = Heraldry.design(v.id());
                if (d == null || !Arms.bears(be.getBaseColor(), be.getPatterns(), d)) continue;
                Borders b = Borders.data(level.getServer());
                Marker m = new Marker();
                m.pos = event.getPos().asLong();
                m.town = v.id();
                m.byPlayer = true;
                m.placed = level.getDayTime() / 24000L;
                of(b, Borders.dim(level)).put(m.pos, m);
                b.setDirty();
                if (Ledger.knowEachOther(land, v.id())) {
                    BorderDisputes.raise(level, land, v.id(), BorderDisputes.Cause.BANNER,
                        "a banner of " + Villages.name(v.id()) + " planted on " + Villages.name(land) + "'s land", event.getPos());
                }
                DUE.remove(land);
                p.sendSystemMessage(Component.literal("You planted " + Villages.name(v.id()) + "'s banner on the lands of " + Villages.name(land)
                    + ". They will not leave it there.").withStyle(ChatFormatting.GOLD));
                return;
            }
        });
    }

    // ------------------------------------------------------------------ the books and the map

    static CompoundTag report(ServerLevel level, Borders b, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        int banners = 0, stones = 0, again = 0;
        ListTag where = new ListTag();
        for (Marker m : of(b, Borders.dim(level)).values()) {
            if (!m.town.equals(v.id()) || m.byPlayer) continue;
            if (m.stone) stones++;
            else banners++;
            again += m.replanted;
            if (where.size() < 6) where.add(StringTag.valueOf((m.stone ? "stone" : "banner") + " at " + m.pos().getX() + ", " + m.pos().getZ()
                + (m.with.isEmpty() ? "" : " (the line with " + Villages.name(Borders.uuid(m.with)) + ")")));
        }
        out.putInt("banners", banners);
        out.putInt("stones", stones);
        out.putInt("again", again);
        Borders.Land land = b.lands.get(Borders.dim(level));
        int want = 0;
        if (land != null) for (Spot s : wanted(level, b, land, v)) if (!hasMarker(of(b, Borders.dim(level)), v.id(), s)) want++;
        out.putInt("waiting", want);
        String s = SHORT.get(v.id());
        out.putString("short", s == null ? "" : s);
        out.put("where", where);
        return out;
    }

    /** Every marker in this dimension, for the map table (position, town, stone). */
    static List<Marker> all(ServerLevel level) {
        Borders b = Borders.data(level.getServer());
        return new ArrayList<>(of(b, Borders.dim(level)).values());
    }

    /** Admin and the pictures: every marker the town wants planted now, out of nothing (a decree). Returns how many. */
    public static int plantAllFree(ServerLevel level, Villages.Village v) {
        Borders b = Borders.data(level.getServer());
        Borders.Land land = b.lands.get(Borders.dim(level));
        if (land == null) return 0;
        int n = 0;
        Heraldry.Design d = Heraldry.design(v.id());
        for (Spot s : wanted(level, b, land, v)) {
            if (hasMarker(of(b, Borders.dim(level)), v.id(), s)) continue;
            BlockPos at = s.stone ? s.at : findGround(level, s);
            if (at == null || !level.isLoaded(at)) continue;
            if (s.stone) {
                level.setBlock(at, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
                record(level, b, v.id(), at, true, s.with, null);
            } else {
                ItemStack banner = d != null ? Heraldry.item(level, v.id(), d) : new ItemStack(Heraldry.bannerItem(dye(Villages.colour(v.id()))));
                level.setBlock(at, Blocks.SPRUCE_LOG.defaultBlockState(), 3);
                Arms.plant(level, at.above(), s.out, banner);
                record(level, b, v.id(), at.above(), false, s.with, null);
            }
            n++;
        }
        return n;
    }

    /** The block a test can look at: the town's markers, as recorded. */
    public static List<BlockPos> markersForTests(ServerLevel level, UUID town) {
        List<BlockPos> out = new ArrayList<>();
        for (Marker m : all(level)) if (m.town.equals(town)) out.add(m.pos());
        return out;
    }

    /** Tests: knock a marker down (as a creeper would). */
    public static void knockForTests(ServerLevel level, BlockPos at) {
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
    }

    /** Used by the tests' guard: is the block at the marker the town's banner? */
    public static boolean bannerAt(ServerLevel level, BlockPos at) {
        Block b = level.getBlockState(at).getBlock();
        return b instanceof AbstractBannerBlock;
    }
}
