package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The map room. [batchG] Once the hall stands, the town keeps a map of itself on its wall: drawn by the
 * clerk (a hand at the town's works, TownJobs "maps") and hung in an item frame for anybody to look at.
 * <ul>
 * <li>A map is drawn on a sheet the stores pay for: eight paper and a compass, the game's own recipe for a
 *     map, when the stores have a compass to spare; else nine paper (a plain sheet, filled in by hand as a
 *     map in a player's hand fills itself in: PlayerServices.paint, from the ground as it stands).</li>
 * <li>A town of forty or more is too big for one sheet: four, a two-by-two of maps, each a quarter of the
 *     town at twice the detail, hung together on the one wall (north-west at the top left).</li>
 * <li>Every seven days it is drawn afresh on new paper, so the town's newest streets are on it; the old map
 *     comes down and goes back to the stores (a player may have it, from there).</li>
 * <li>The frames are the town's: out of the stores, or made there of their sticks and a leather.</li>
 * </ul>
 * The leader's hall stands for the meeting hall if the town has only that. The books say when the map
 * was last drawn (Visitors.book).
 */
public final class MapRoom {

    private MapRoom() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A town this big has the two-by-two. */
    static final int BIG = 40;
    /** Days between maps. */
    static final long EVERY = 7;
    /** The frames' tag. */
    static final String TAG = "mca_map_room";

    /** What the town lacks for it, said once (the log). */
    private static final Map<UUID, String> WANTS = new ConcurrentHashMap<>();
    /** Tests: the two-by-two whatever the town's size (true), one sheet (false), or by its size (null). */
    private static volatile Boolean bigForTests;

    static void resetForTests() {
        WANTS.clear();
        bigForTests = null;
    }

    public static void bigForTests(@Nullable Boolean on) {
        bigForTests = on;
    }

    /** The hall the map hangs in: the meeting hall, else the leader's hall. */
    @Nullable
    static Ledger.Building hall(UUID village) {
        Ledger.Building b = Visitors.building(village, "hall");
        return b != null ? b : Visitors.building(village, "townhall");
    }

    /** When it was last drawn, and where its frames hang (top left, top right, bottom left, bottom right; or one). */
    record Record(long day, List<BlockPos> frames) {}

    @Nullable
    static Record record(UUID village) {
        String s = Ledger.note(village, "maproom");
        if (s == null || s.isEmpty()) return null;
        String[] q = s.split("\\|");
        try {
            List<BlockPos> at = new ArrayList<>();
            if (q.length > 1 && !q[1].isEmpty()) for (String p : q[1].split(",")) at.add(BlockPos.of(Long.parseLong(p)));
            return new Record(Long.parseLong(q[0]), at);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void record(UUID village, long day, List<BlockPos> frames) {
        StringBuilder sb = new StringBuilder().append(day).append('|');
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(frames.get(i).asLong());
        }
        Ledger.note(village, "maproom", sb.toString());
    }

    /** The town's look at its map room (Visitors.tick): a map drawn if there is none, or it is a week old. */
    static void tick(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.headcount(id) < TownJobs.SETTLED) return;
        Ledger.Building hall = hall(id);
        if (hall == null || !level.isLoaded(hall.anchor())) return;
        Record r = record(id);
        int want = sheets(id);
        if (r != null && day - r.day() < EVERY && r.frames().size() == want && hanging(level, r) == want) return;
        String did = make(level, v, hall, day);
        if (did != null) LOG.info("[MCA-MAPROOM] {}: {}", Villages.name(id), did);
    }

    static int sheets(UUID village) {
        Boolean big = bigForTests;
        return (big != null ? big : Villages.headcount(village) >= BIG) ? 4 : 1;
    }

    /** How many of its frames still hang with a map in them. */
    static int hanging(ServerLevel level, Record r) {
        int n = 0;
        for (BlockPos p : r.frames()) {
            if (!level.isLoaded(p)) { n++; continue; }
            ItemFrame f = frame(level, p);
            if (f != null && f.getItem().is(Items.FILLED_MAP)) n++;
        }
        return n;
    }

    @Nullable
    private static ItemFrame frame(ServerLevel level, BlockPos at) {
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(0.1), e -> e.isAlive() && e.getTags().contains(TAG))) {
            return f;
        }
        return null;
    }

    /**
     * The map made and hung, as far as the town can get it: the wall, the paper, a hand to draw it, the
     * frames. What was done, or null if it waits (on a hand, paper or frames, or a wall).
     */
    @Nullable
    static String make(ServerLevel level, Villages.Village v, Ledger.Building hall, long day) {
        UUID id = v.id();
        int n = sheets(id);
        Record r = record(id);
        List<Decor.Spot> spots = r != null && r.frames().size() == n ? existing(level, r) : null;
        // The old frames, if they are not to be used again (the town grew into the two-by-two, or one came down):
        // taken down once the new are paid for, their maps and frames back to the stores.
        List<BlockPos> stale = spots == null && r != null ? r.frames() : List.of();
        if (spots == null) spots = wall(level, id, hall, n);
        if (spots == null) {
            want(id, "a wall in the hall for " + (n == 4 ? "a two-by-two of maps" : "the town's map"));
            return null;
        }
        if (!paperToHand(level, v, n)) {
            want(id, (n * 9) + " paper (or " + (n * 8) + " and a compass to spare) for the map room");
            return null;
        }
        int newFrames = 0;
        for (Decor.Spot s : spots) if (frame(level, s.at()) == null) newFrames++;
        int old = 0;
        for (BlockPos p : stale) if (level.isLoaded(p) && frame(level, p) != null) old++;
        if (!framesToHand(level, v, Math.max(0, newFrames - old))) {
            want(id, newFrames + " item frames (or their sticks and leather) for the map room");
            return null;
        }
        if (!TownJobs.atWork(level, v, "maps", spots.get(0).at(), "drawing the town's map for the hall")) return null;
        String paper = payForPaper(level, v, n);
        if (paper == null) return null;
        for (BlockPos p : stale) takeDown(level, v, p);
        if (!payForFrames(level, v, newFrames)) {
            refundPaper(level, v, paper, n);
            return null;
        }
        List<ItemStack> maps = draw(level, v, n, day);
        List<BlockPos> hung = new ArrayList<>();
        int back = 0;
        for (int i = 0; i < spots.size(); i++) {
            Decor.Spot s = spots.get(i);
            ItemFrame f = frame(level, s.at());
            if (f == null) {
                f = new ItemFrame(level, s.at(), s.facing());
                f.addTag(TAG);
                level.addFreshEntity(f);
                f.playPlacementSound();
            } else if (!f.getItem().isEmpty()) {
                // Last week's map down, and back to the stores.
                Crafts.store(level, v, f.getItem().copy());
                back++;
            }
            f.setItem(maps.get(i), false);
            hung.add(s.at());
        }
        record(id, day, hung);
        WANTS.remove(id);
        level.playSound(null, hung.get(0), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F);
        VillageFolkEntity clerk = nearest(level, id, hung.get(0));
        if (clerk != null) {
            FolkTalk.speak(clerk, FolkTalk.pick(clerk.getRandom(), "There — the town as it stands, day " + (day + 1) + ".",
                "A fresh map for the hall. Look how we've grown!"));
        }
        if (r == null) Villages.tell(id, day, "a map of " + Villages.name(id) + " was hung on the wall of the hall");
        return (n == 4 ? "a two-by-two of maps" : "the town's map") + " drawn on " + paper + " and hung in the hall"
            + (back > 0 ? "; last week's " + (back == 1 ? "map" : back + " maps") + " back to the stores" : "");
    }

    /** A frame of the map room taken down: its map and the frame itself back to the stores. */
    private static void takeDown(ServerLevel level, Villages.Village v, BlockPos at) {
        if (!level.isLoaded(at)) return;
        ItemFrame f = frame(level, at);
        if (f == null) return;
        if (!f.getItem().isEmpty()) Crafts.store(level, v, f.getItem().copy());
        Crafts.store(level, v, new ItemStack(Items.ITEM_FRAME));
        f.discard();
    }

    private static void want(UUID id, String what) {
        if (!what.equals(WANTS.put(id, what))) LOG.info("[MCA-MAPROOM] {} wants {}", Villages.name(id), what);
    }

    /** The folk nearest the spot (the clerk who drew it). */
    @Nullable
    private static VillageFolkEntity nearest(ServerLevel level, UUID id, BlockPos at) {
        VillageFolkEntity best = null;
        double bd = 16.0 * 16.0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            double d = f.blockPosition().distSqr(at);
            if (d < bd) { bd = d; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the wall

    /** The frames already up, as spots (their facing as they hang). Null if any is gone. */
    @Nullable
    private static List<Decor.Spot> existing(ServerLevel level, Record r) {
        List<Decor.Spot> out = new ArrayList<>();
        for (BlockPos p : r.frames()) {
            ItemFrame f = frame(level, p);
            if (f == null) return null;
            out.add(new Decor.Spot(p, f.getDirection()));
        }
        return out;
    }

    /**
     * A place on the hall's wall: for one map, a free spot at head height nearest the hall's middle; for the
     * two-by-two, four free spots in a square on one stretch of wall. In hanging order (top left, top right,
     * bottom left, bottom right). Null if there is none.
     */
    @Nullable
    static List<Decor.Spot> wall(ServerLevel level, UUID village, Ledger.Building hall, int n) {
        Decor.Room room = Decor.room(village, hall);
        Set<BlockPos> taken = new HashSet<>(Decor.reserved(level, village, hall));
        for (Decor.Spot s : Decor.wallSpots(level, room, hall.anchor(), 1, taken)) {
            if (!free(level, s.at(), s.facing())) continue;
            if (n == 1) return List.of(s);
            // The viewer faces the wall: its right is the frame's facing turned anticlockwise. The square may run
            // either way from this spot along the wall.
            Direction right = s.facing().getCounterClockWise();
            for (BlockPos bl : new BlockPos[]{ s.at(), s.at().relative(right.getOpposite()) }) {
                BlockPos br = bl.relative(right), tl = bl.above(), tr = br.above();
                if (!free(level, bl, s.facing()) || !free(level, br, s.facing()) || !free(level, tl, s.facing())
                        || !free(level, tr, s.facing())) continue;
                return List.of(new Decor.Spot(tl, s.facing()), new Decor.Spot(tr, s.facing()), new Decor.Spot(bl, s.facing()),
                    new Decor.Spot(br, s.facing()));
            }
        }
        return null;
    }

    /** Free for a frame facing this way: air, a sound wall behind, nothing hanging there, no door by it. */
    private static boolean free(ServerLevel level, BlockPos at, Direction facing) {
        if (!level.isLoaded(at) || !level.getBlockState(at).isAir()) return false;
        BlockPos wall = at.relative(facing.getOpposite());
        BlockState w = level.getBlockState(wall);
        if (!w.isFaceSturdy(level, wall, facing) || w.getBlock() instanceof DoorBlock) return false;
        if (!level.getEntitiesOfClass(HangingEntity.class, new AABB(at).inflate(0.05)).isEmpty()) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(at.relative(d)).getBlock() instanceof DoorBlock) return false;
        return new ItemFrame(level, at, facing).survives();
    }

    // ------------------------------------------------------------------ the stores pay

    /** Can the stores run to so many sheets (nine paper each, or eight and a compass to spare)? */
    static boolean paperToHand(ServerLevel level, Villages.Village v, int n) {
        int paper = Crafts.stock(level, v, s -> s.is(Items.PAPER));
        int compasses = Math.max(0, Crafts.stock(level, v, s -> s.is(Items.COMPASS)) - 1);
        int withCompass = Math.min(n, compasses);
        return paper >= withCompass * 8 + (n - withCompass) * 9;
    }

    /** The paper (and the compasses to spare) taken out of the stores. What it was, in words, or null. */
    @Nullable
    private static String payForPaper(ServerLevel level, Villages.Village v, int n) {
        int compasses = Math.min(n, Math.max(0, Crafts.stock(level, v, s -> s.is(Items.COMPASS)) - 1));
        int paper = compasses * 8 + (n - compasses) * 9;
        if (!Crafts.take(level, v, s -> s.is(Items.PAPER), paper)) return null;
        if (compasses > 0 && !Crafts.take(level, v, s -> s.is(Items.COMPASS), compasses)) {
            Crafts.store(level, v, new ItemStack(Items.PAPER, paper));
            return null;
        }
        return paper + " of the stores' paper" + (compasses > 0 ? " and " + (compasses == 1 ? "a compass" : compasses + " compasses") : "");
    }

    private static void refundPaper(ServerLevel level, Villages.Village v, String paid, int n) {
        int compasses = paid.contains("compass") ? (paid.contains("compasses") ? n : 1) : 0;
        int paper = compasses * 8 + (n - compasses) * 9;
        Crafts.store(level, v, new ItemStack(Items.PAPER, paper));
        if (compasses > 0) Crafts.store(level, v, new ItemStack(Items.COMPASS, compasses));
    }

    /** Can the stores run to so many frames (each: a frame put by, or four planks' sticks and a leather)? */
    static boolean framesToHand(ServerLevel level, Villages.Village v, int n) {
        if (n <= 0) return true;
        int have = Crafts.stock(level, v, s -> s.is(Items.ITEM_FRAME));
        int make = Math.max(0, n - have);
        if (make == 0) return true;
        return Crafts.stock(level, v, s -> s.is(Items.LEATHER)) >= make
            && Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.LOGS)) >= make;
    }

    private static boolean payForFrames(ServerLevel level, Villages.Village v, int n) {
        for (int i = 0; i < n; i++) {
            if (Crafts.take(level, v, s -> s.is(Items.ITEM_FRAME), 1)) continue;
            if (Crafts.stock(level, v, s -> s.is(Items.LEATHER)) < 1 || !Crafts.usePlanks(level, v, 4)) return false;
            if (!Crafts.take(level, v, s -> s.is(Items.LEATHER), 1)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the drawing

    /** The sheets drawn: the town whole on one, or its four quarters, north-west first. */
    static List<ItemStack> draw(ServerLevel level, Villages.Village v, int n, long day) {
        if (n == 1) {
            ItemStack map = PlayerServices.drawTownMap(level, v, "the clerk", day);
            map.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("The hall's map, drawn by the clerk on day " + (day + 1))
                .withStyle(ChatFormatting.GRAY))));
            return List.of(map);
        }
        byte whole = PlayerServices.mapScale(v.id());
        byte scale = (byte) Math.max(0, whole - 1);
        int half = 64 << scale;
        int cx = v.centre().getX(), cz = v.centre().getZ();
        String[] names = { "north-west", "north-east", "south-west", "south-east" };
        int[][] at = { { -half, -half }, { half, -half }, { -half, half }, { half, half } };
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.add(quarter(level, v, cx + at[i][0], cz + at[i][1], scale, names[i], day));
        return out;
    }

    /** A quarter of the town, centred where it says, filled in from the ground as it stands. */
    private static ItemStack quarter(ServerLevel level, Villages.Village v, int cx, int cz, byte scale, String which, long day) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", level.dimension().location().toString());
        tag.putInt("xCenter", cx);
        tag.putInt("zCenter", cz);
        tag.putByte("scale", scale);
        tag.putBoolean("trackingPosition", true);
        tag.putBoolean("unlimitedTracking", false);
        tag.putBoolean("locked", false);
        tag.putByteArray("colors", PlayerServices.paint(level, cx, cz, scale));
        tag.put("banners", new ListTag());
        tag.put("frames", new ListTag());
        MapItemSavedData data = MapItemSavedData.load(tag, level.registryAccess());
        MapId id = level.getFreeMapId();
        level.setMapData(id, data);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, id);
        map.set(DataComponents.ITEM_NAME, Component.literal("Map of " + Villages.name(v.id()) + " (" + which + ")"));
        map.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("The hall's map, drawn by the clerk on day " + (day + 1))
            .withStyle(ChatFormatting.GRAY))));
        int half = 64 << scale;
        if (Math.abs(v.centre().getX() - cx) <= half && Math.abs(v.centre().getZ() - cz) <= half) {
            MapItemSavedData.addTargetDecoration(map, v.centre(), "heart", MapDecorationTypes.PLAINS_VILLAGE);
        }
        return map;
    }

    // ------------------------------------------------------------------ what is shown

    /** The books' line: when the map was drawn, and when the next is due. */
    static String bookLine(UUID village, long day) {
        Record r = record(village);
        if (r == null) return "";
        long next = Math.max(0, r.day() + EVERY - day);
        return "The map room: " + (r.frames().size() == 4 ? "four maps of the town" : "the town's map") + " on the hall's wall, drawn on day "
            + (r.day() + 1) + (next == 0 ? "; a fresh one is due" : "; the next in " + next + (next == 1 ? " day" : " days")) + ".";
    }

    /** /village visitors: where the map room's frames hang ("MAPFRAME x y z facing"), for scripts. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        Record r = record(v.id());
        if (r == null) return out;
        for (BlockPos p : r.frames()) {
            ItemFrame f = level.isLoaded(p) ? frame(level, p) : null;
            out.add("MAPFRAME " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + (f == null ? "?" : f.getDirection().getName())
                + " day " + (r.day() + 1));
        }
        return out;
    }

    /** /village visitors map, and the tests: the map made now (the town's own paper, frames and hands). */
    static String makeForTests(ServerLevel level, Villages.Village v) {
        Ledger.Building hall = hall(v.id());
        if (hall == null) return "no hall";
        String did = make(level, v, hall, level.getDayTime() / 24000L);
        return did != null ? did : "waits: " + WANTS.getOrDefault(v.id(), "for a hand to draw it");
    }

    /** Tests: the map room made now. What was done, or what it waits on. */
    public static String makeNowForTests(ServerLevel level, Villages.Village v) {
        return makeForTests(level, v);
    }

    /** Tests: the frames that hang in the map room and the maps in them. */
    public static List<ItemStack> mapsForTests(ServerLevel level, UUID village) {
        List<ItemStack> out = new ArrayList<>();
        Record r = record(village);
        if (r == null) return out;
        for (BlockPos p : r.frames()) {
            ItemFrame f = frame(level, p);
            out.add(f == null ? ItemStack.EMPTY : f.getItem().copy());
        }
        return out;
    }

    /** Tests: where the frames hang. */
    public static List<BlockPos> framesForTests(UUID village) {
        Record r = record(village);
        return r == null ? List.of() : r.frames();
    }

    /** Tests: the day the map was last drawn, or -1. */
    public static long drawnForTests(UUID village) {
        Record r = record(village);
        return r == null ? -1 : r.day();
    }

    /** Tests: as if the map had been drawn on this day (to make it due). */
    public static void drawnOnForTests(UUID village, long day) {
        Record r = record(village);
        if (r != null) record(village, day, r.frames());
    }
}
