package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [cartographer] The cartographer's surveys: real maps, filled in the way the game fills a map in a player's hand,
 * by walking the ground with it.
 *
 * <p><b>The sheets.</b> A survey starts at the map room's table: a sheet for each map out of the stores' paper (an
 * empty map, as the cartography table makes one of a sheet of paper), centred exactly where the survey wants it
 * (the town's wall is centred on its heart, not snapped to the world's grid of maps, so its sheets meet edge to edge
 * over the square). The sheets go into the cartographer's pack, the one it is working on in its hand.
 *
 * <p><b>The walk.</b> It walks a round of stations: the middle of each sheet (each quarter of it, for a sheet at
 * twice the scale), the ring round the town for the region's, the land asked for in a commission. Once a second, for
 * every sheet whose ground it is near, the game's own {@code MapItem.update} runs with the cartographer as the map's
 * holder: the colours come off the ground within a hundred and twenty-eight blocks of it, a column in sixteen a time,
 * shaded by the slope and the water's depth exactly as a player's map would be. Nothing is painted: what it has not
 * walked near stays blank. The game holds a map only for a player, so the holder is a stand-in the game keeps for
 * such work (a FakePlayer), set down where the cartographer stands. A sheet is only ever updated where the ground
 * under it is loaded: the walk keeps the ground about it awake, and at each station it stands until the sheet is
 * filled there (or half a minute), so a map never makes the world generate under the server's feet.
 *
 * <p><b>The marks.</b> For the town's wall it walks on to the town's places (the hall, each gate, the storehouse, the
 * market), sets a banner on the street before each (one put by in the stores, or six wool of the town's colour and a
 * stick), named for the place, and touches every sheet to it, as a player clicks a map on a banner: the game's own
 * banner markers, with their names under them.
 *
 * <p><b>Under glass, and on the wall.</b> Back at the table, each finished sheet of the wall is locked with a pane of
 * the stores' glass (the game's own locked map: a fresh copy that never changes again). Then to the hall, where they
 * go up in frames on the wall, a two-by-two or a three-by-three, north-west at the top left, with a sign under them
 * saying what they are. The old wall comes down and goes to the museum's archive ("Thornhurst in its Stone Age"),
 * or to the map room's chest where there is no museum.
 *
 * <p><b>The region.</b> One sheet at four or eight times the scale, centred on the town, walked round a ring of
 * stations, the colonies, neighbours, the mine and the caves marked on it, and hung (unlocked) in the hall. It is
 * never locked: the copies the caravans and envoys carry are the same map, and as they walk the roads with it the
 * hall's copy fills in too, wherever the ground about them is loaded.
 */
public final class MapSurveys {

    private MapSurveys() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A map fills in within this many blocks of whoever holds it, whatever its scale (MapItem.update). */
    static final int FILLS = 128;
    /** The town's wall is drawn afresh after this many days, or sooner when the town has grown. */
    public static final long WALL_EVERY = 7;
    /** The region's sheet, after this many days. */
    static final long REGION_EVERY = 14;
    /** A round of a survey: once a second. */
    static final int ROUND = 20;
    /** Updates of a sheet in a round: each fills a column in sixteen, as the game does each tick a player holds one. */
    static final int STROKES = 4;
    /** A station's sheet is filled there after sixteen updates: every column once. */
    static final int FILLED = 16;
    /** Rounds stood at a station at most, waiting on the ground and the sheet. */
    static final int STAND_MOST = 30;
    /** Ticks a stop is walked for before it is given up. */
    static final int STOP_MOST = 1800;
    /** The walk's window of awake ground, in chunks either way (the ground full a couple more beyond it). */
    static final int WINDOW = 6;

    /** The stand-in holder the game needs for MapItem.update. */
    private static final GameProfile HOLDER = new GameProfile(UUID.fromString("8a6c3f1e-2b7d-4c55-9e1a-6d0f3b2c7a91"), "[Cartographer]");

    /** What a survey is for. */
    public enum Kind {
        WALL("the town's map for the hall"), REGION("the region's map"), COMMISSION("a map someone asked for");

        public final String words;

        Kind(String words) { this.words = words; }
    }

    /** What is done at a stop: walk past it, stand and survey, set a banner and mark it, lock at the table, hang in
     *  the hall, leave a commission ready at the map room. */
    enum Act { LOOK, STAND, BANNER, TABLE, HALL, HOME }

    /** One stop of a survey's round. */
    record Stop(BlockPos at, Act act, String name) {
        String encode() {
            return at.getX() + "," + at.getY() + "," + at.getZ() + "," + act.name() + "," + name.replace(',', ' ').replace(';', ' ').replace('|', ' ');
        }

        @Nullable
        static Stop decode(String s) {
            String[] p = s.split(",", 5);
            if (p.length < 5) return null;
            try {
                return new Stop(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), Act.valueOf(p[3]), p[4]);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** A survey under way: what it is for, its sheets (by map id), its round and how far along. */
    public static final class Survey {
        final Kind kind;
        final int across;
        final byte scale;
        final int cx, cz;
        final List<Integer> sheets;
        final List<Stop> stops;
        int next;
        long started;
        /** A commission's: who asked (uuid|name), and what for ("the land to the east"). */
        String forWhom = "", what = "";
        // Not kept: the walk, the rounds, the awake ground.
        final Visitors.Walk walk = new Visitors.Walk();
        @Nullable BlockPos window;
        int roundTick = -100000, stood, strokesHere, stopTick = -1;

        Survey(Kind kind, int across, byte scale, int cx, int cz, List<Integer> sheets, List<Stop> stops) {
            this.kind = kind;
            this.across = across;
            this.scale = scale;
            this.cx = cx;
            this.cz = cz;
            // Copies of its own: the sheets are swapped for their locked copies at the table (lock), whatever list they came in.
            this.sheets = new ArrayList<>(sheets);
            this.stops = new ArrayList<>(stops);
        }

        public Kind kind() { return kind; }
        public int stop() { return next; }
        public int stops() { return stops.size(); }
        public List<Integer> sheets() { return sheets; }
        public int across() { return across; }
        public byte scale() { return scale; }

        String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(kind.name()).append('|').append(across).append('|').append(scale).append('|').append(cx).append('|').append(cz).append('|');
            for (int i = 0; i < sheets.size(); i++) sb.append(i > 0 ? "," : "").append(sheets.get(i));
            sb.append('|').append(next).append('|').append(started).append('|').append(forWhom.replace('|', ' ')).append('|')
                .append(what.replace('|', ' ')).append('|');
            for (int i = 0; i < stops.size(); i++) sb.append(i > 0 ? ";" : "").append(stops.get(i).encode());
            return sb.toString();
        }

        @Nullable
        static Survey decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 11) return null;
            try {
                List<Integer> ids = new ArrayList<>();
                if (!p[5].isEmpty()) for (String k : p[5].split(",")) ids.add(Integer.parseInt(k));
                List<Stop> stops = new ArrayList<>();
                if (!p[10].isEmpty()) for (String k : p[10].split(";")) {
                    Stop st = Stop.decode(k);
                    if (st != null) stops.add(st);
                }
                Survey out = new Survey(Kind.valueOf(p[0]), Integer.parseInt(p[1]), Byte.parseByte(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), ids, stops);
                out.next = Integer.parseInt(p[6]);
                out.started = Long.parseLong(p[7]);
                out.forWhom = p[8];
                out.what = p[9];
                return out;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Each town's survey under way, as its note has it. */
    private static final Map<UUID, Survey> SURVEYS = new ConcurrentHashMap<>();
    /** What a town's surveys wait on, said once (the log, the card and the books). */
    static final Map<UUID, String> WANTS = new ConcurrentHashMap<>();
    /** Tests and the stage: every survey run to its end at once, the cartographer set down at each stop in turn. */
    private static volatile boolean instant;

    static void resetForTests() {
        SURVEYS.clear();
        WANTS.clear();
        instant = false;
    }

    public static void instantForTests(boolean on) {
        instant = on;
    }

    static boolean instant() {
        return instant;
    }

    /** The town's survey under way, or null. */
    @Nullable
    public static Survey of(UUID village) {
        Survey s = SURVEYS.get(village);
        if (s != null) return s;
        String n = Ledger.note(village, "carto.survey");
        if (n == null || n.isEmpty()) return null;
        s = Survey.decode(n);
        if (s != null) SURVEYS.put(village, s);
        return s;
    }

    static void save(UUID village, Survey s) {
        SURVEYS.put(village, s);
        Ledger.note(village, "carto.survey", s.encode());
    }

    static void forget(UUID village) {
        SURVEYS.remove(village);
        Ledger.forget(village, "carto.survey");
    }

    static void want(UUID village, String what) {
        if (!what.equals(WANTS.put(village, what))) LOG.info("[MCA-CARTO] {} waits: {}", Villages.name(village), what);
    }

    // ------------------------------------------------------------------ the sheets

    /** The width of a sheet at this scale, in blocks. */
    static int width(int scale) {
        return 128 << scale;
    }

    /**
     * A blank sheet, centred exactly here at this scale: an empty map drawn up at the table, named. Its colours are
     * all blank: they come only from the ground it is carried over.
     */
    static ItemStack blank(ServerLevel level, int cx, int cz, byte scale, String name, List<String> lore) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", level.dimension().location().toString());
        tag.putInt("xCenter", cx);
        tag.putInt("zCenter", cz);
        tag.putByte("scale", scale);
        tag.putBoolean("trackingPosition", true);
        tag.putBoolean("unlimitedTracking", false);
        tag.putBoolean("locked", false);
        tag.putByteArray("colors", new byte[128 * 128]);
        tag.put("banners", new ListTag());
        tag.put("frames", new ListTag());
        MapItemSavedData data = MapItemSavedData.load(tag, level.registryAccess());
        MapId id = level.getFreeMapId();
        level.setMapData(id, data);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, id);
        map.set(DataComponents.ITEM_NAME, Component.literal(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) lines.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        if (!lines.isEmpty()) map.set(DataComponents.LORE, new ItemLore(lines));
        return map;
    }

    /** How many of a sheet's pixels have been filled in (not blank). */
    public static int coloured(@Nullable MapItemSavedData d) {
        if (d == null) return 0;
        int n = 0;
        for (byte b : d.colors) if (b != 0) n++;
        return n;
    }

    @Nullable
    static MapItemSavedData data(ServerLevel level, int id) {
        return level.getMapData(new MapId(id));
    }

    /** The cartographer's own stack of this sheet, in its pack, or EMPTY (the one held up in its hand is only a sight of it). */
    static ItemStack carried(VillageFolkEntity f, int id) {
        for (ItemStack s : f.getInventoryItems()) if (isSheet(s, id) && !Leisure.isProp(s)) return s;
        return ItemStack.EMPTY;
    }

    static boolean isSheet(ItemStack s, int id) {
        MapId m = s.is(Items.FILLED_MAP) ? s.get(DataComponents.MAP_ID) : null;
        return m != null && m.id() == id;
    }

    /** Take a sheet out of the cartographer's pack for good. */
    static ItemStack takeSheet(VillageFolkEntity f, int id) {
        for (ItemStack s : f.getInventoryItems()) {
            if (!isSheet(s, id) || Leisure.isProp(s)) continue;
            ItemStack out = s.copy();
            s.setCount(0);
            return out;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ the ground under a sheet

    /**
     * Is every chunk the game would read for this sheet, with the holder here, loaded? The part of the sheet within a
     * map's reach of the holder (and the row north of it, for the shading). The game's update reads the ground with
     * a plain getChunk, which would make the world load or generate it on the spot: so a sheet is only updated where
     * the whole of that is here already.
     */
    static boolean groundLoaded(ServerLevel level, MapItemSavedData d, double hx, double hz) {
        int i = 1 << d.scale;
        int x0 = (d.centerX / i - 64) * i, x1 = (d.centerX / i + 63) * i + i - 1;
        int z0 = (d.centerZ / i - 65) * i, z1 = (d.centerZ / i + 63) * i + i - 1;
        x0 = Math.max(x0, (int) Math.floor(hx) - FILLS);
        x1 = Math.min(x1, (int) Math.floor(hx) + FILLS);
        z0 = Math.max(z0, (int) Math.floor(hz) - FILLS);
        z1 = Math.min(z1, (int) Math.floor(hz) + FILLS);
        if (x0 > x1 || z0 > z1) return false;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    /** Is the holder near enough the sheet for any of it to fill in? */
    static boolean near(MapItemSavedData d, double hx, double hz) {
        int half = width(d.scale) / 2;
        return Math.abs(hx - d.centerX) <= half + FILLS && Math.abs(hz - d.centerZ) <= half + FILLS;
    }

    /** The stand-in holder, set down where the walker stands. */
    static FakePlayer holder(ServerLevel level, net.minecraft.world.entity.Entity at) {
        FakePlayer p = FakePlayerFactory.get(level, HOLDER);
        p.moveTo(at.getX(), at.getY(), at.getZ(), at.getYRot(), 0.0F);
        return p;
    }

    /**
     * The game's own map update, so many times, with this walker as the holder: the sheet fills in from the ground
     * round it. False (and nothing done) if the sheet is locked, of another dimension, out of reach, or its ground is
     * not loaded.
     */
    static boolean stroke(ServerLevel level, net.minecraft.world.entity.Entity walker, MapItemSavedData d, int times) {
        if (d.locked || d.dimension != level.dimension()) return false;
        if (!near(d, walker.getX(), walker.getZ()) || !groundLoaded(level, d, walker.getX(), walker.getZ())) return false;
        FakePlayer p = holder(level, walker);
        MapItem map = (MapItem) Items.FILLED_MAP;
        for (int k = 0; k < times; k++) map.update(level, p, d);
        return true;
    }

    /**
     * A traveller of the town walking the roads with a copy of a map that is still open (the region's): the copy is
     * the same map, so it fills in as the traveller goes, wherever the ground about it is loaded (Caravans).
     */
    public static void walkWith(ServerLevel level, VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) {
            MapId id = s.is(Items.FILLED_MAP) ? s.get(DataComponents.MAP_ID) : null;
            if (id == null) continue;
            MapItemSavedData d = level.getMapData(id);
            if (d != null && !d.locked) stroke(level, f, d, 1);
        }
    }

    // ------------------------------------------------------------------ the town's wall: its layout

    /**
     * The wall a town of this size wants: {across, scale}. The sheets cover the town and its fields (its reach and
     * a margin): a two-by-two at the scale of the ground for a town that fits in two hundred and fifty blocks, a
     * three-by-three for a bigger one, then the same at twice the scale.
     */
    static int[] wallLayout(UUID village) {
        int r = Villages.townReach(village) + 48;
        if (r <= 120) return new int[]{ 2, 0 };
        if (r <= 184) return new int[]{ 3, 0 };
        if (r <= 248) return new int[]{ 2, 1 };
        return new int[]{ 3, 1 };
    }

    /** The sheets' centres, north row first, each row west to east. */
    static List<int[]> centres(int across, int scale, int cx, int cz) {
        int w = width(scale);
        List<int[]> out = new ArrayList<>();
        for (int row = 0; row < across; row++) {
            for (int col = 0; col < across; col++) {
                double ox = across == 2 ? (col - 0.5) * w : (col - 1) * w;
                double oz = across == 2 ? (row - 0.5) * w : (row - 1) * w;
                out.add(new int[]{ cx + (int) ox, cz + (int) oz });
            }
        }
        return out;
    }

    /** "north-west", "north", "the middle"... of a sheet in a wall of so many across. */
    static String part(int across, int i) {
        String[][] two = { { "north-west", "north-east" }, { "south-west", "south-east" } };
        String[][] three = { { "north-west", "north", "north-east" }, { "west", "middle", "east" }, { "south-west", "south", "south-east" } };
        return across == 2 ? two[i / 2][i % 2] : across == 3 ? three[i / 3][i % 3] : "the whole";
    }

    /** The day the wall was drawn and what it was: day|across|scale|folk|reach|buildings|age|by. */
    record Wall(long day, int across, int scale, int folk, int reach, int buildings, String age, String by) {}

    @Nullable
    static Wall wall(UUID village) {
        String s = Ledger.note(village, "carto.wall");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|", -1);
        try {
            return new Wall(Long.parseLong(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                Integer.parseInt(p[4]), Integer.parseInt(p[5]), p[6], p[7]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Is the wall due: never drawn by the cartographer, a week old, its frames gone, or the town has grown? Why, or null. */
    @Nullable
    static String wallDue(ServerLevel level, UUID village, long day) {
        Wall w = wall(village);
        if (w == null) return "the hall has no map of mine yet";
        if (day - w.day() >= WALL_EVERY) return "the hall's map is a week old";
        int[] want = wallLayout(village);
        boolean same = want[0] == w.across() && want[1] == w.scale()
            || want[0] == 3 && w.across() == 2 && w.scale() == want[1] + 1;       // a two-by-two of the same ground, for want of wall
        if (!same) return "the town has outgrown its map";
        if (Ledger.buildings(village).size() >= w.buildings() + 6) return "the town has grown since its map was drawn";
        MapRoom.Record r = MapRoom.record(village);
        if (r == null || r.frames().size() != w.across() * w.across()) return "the hall's map is not all there";
        if (level.isLoaded(r.frames().get(0)) && MapRoom.hanging(level, r) < r.frames().size()) return "a sheet of the hall's map is gone";
        return null;
    }

    // ------------------------------------------------------------------ the marks: banners at the town's places

    /** A place of the town to mark on its map: its name, and where its banner stands (or would stand). */
    record Marker(String name, BlockPos near) {}

    /** The town's places: its hall (and the leader's), each gate, the storehouse, the market. */
    static List<Marker> markers(ServerLevel level, UUID village) {
        List<Marker> out = new ArrayList<>();
        Ledger.Building hall = Visitors.building(village, "hall"), town = Visitors.building(village, "townhall");
        if (hall != null) out.add(new Marker("Hall", front(hall)));
        if (town != null) out.add(new Marker("Leader's Hall", front(town)));
        for (Watch.Gate g : Watch.gates(level, village)) {
            if (g.doors().isEmpty()) continue;
            String side = g.out().getName();
            out.add(new Marker(Character.toUpperCase(side.charAt(0)) + side.substring(1) + " Gate", g.inside()));
        }
        Ledger.Building store = Visitors.building(village, "storehouse");
        if (store == null) store = Visitors.building(village, "storage");
        if (store != null) out.add(new Marker("Storehouse", front(store)));
        else if (Storehouses.stands(village)) {
            BlockPos door = Storehouses.doorFor(level, village);
            if (door != null) out.add(new Marker("Storehouse", door));
        }
        Ledger.Building market = Visitors.building(village, "market");
        if (market != null) out.add(new Marker("Market", front(market)));
        return out;
    }

    /** The street before a building's front, a couple of blocks out from its door. */
    static BlockPos front(Ledger.Building b) {
        int depth = com.jrpetty.mcassistant.entity.goal.Blueprints.has(b.structure())
            ? com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(b.structure())[1] : 5;
        return Culture.at(b, 0, 0, -depth - 2);
    }

    /** Where a marker's banner stands: our own, already there; else the first free spot on the ground near it. */
    @Nullable
    static BlockPos bannerSpot(ServerLevel level, UUID village, Marker m) {
        BlockPos kept = keptBanner(village, m.name());
        if (kept != null && level.isLoaded(kept) && named(level, kept, m.name())) return kept;
        BlockPos c = m.near();
        int[] order = { 2, -2, 3, -3, 1, -1, 4, -4, 0 };
        for (int dz : new int[]{ 0, -1, 1, -2, 2 }) {
            for (int dx : order) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!level.isLoaded(new BlockPos(x, c.getY(), z))) continue;
                BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (standable(level, p)) return p;
            }
        }
        return null;
    }

    /** Open ground a banner can stand on: air and air above, a sound top under it, no water, no door or bed by it. */
    static boolean standable(ServerLevel level, BlockPos p) {
        if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) return false;
        BlockState under = level.getBlockState(p.below());
        if (!under.isFaceSturdy(level, p.below(), Direction.UP) || !level.getFluidState(p.below()).isEmpty()) return false;
        if (under.is(Blocks.FARMLAND) || under.is(BlockTags.LEAVES)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(p.relative(d));
            if (n.getBlock() instanceof DoorBlock || n.is(BlockTags.BEDS) || n.getBlock() instanceof BannerBlock) return false;
        }
        return true;
    }

    static boolean named(ServerLevel level, BlockPos at, String name) {
        return level.getBlockEntity(at) instanceof BannerBlockEntity b && b.getCustomName() != null
            && b.getCustomName().getString().equals(name);
    }

    @Nullable
    static BlockPos keptBanner(UUID village, String name) {
        String s = Ledger.note(village, "carto.banner/" + name);
        try {
            return s == null || s.isEmpty() ? null : BlockPos.of(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The town's colour for its banners: its arms' field, or white. */
    static DyeColor colour(UUID village) {
        Heraldry.Design d = Heraldry.design(village);
        return d == null ? DyeColor.WHITE : d.field();
    }

    /**
     * A banner for a mark out of the stores, named: one put by in the town's colour (or any), or six wool of one
     * colour and a stick (half a plank), the game's own recipe. Its colour, or null if the stores cannot run to one.
     */
    @Nullable
    static DyeColor bannerFromStores(ServerLevel level, Villages.Village v) {
        DyeColor want = colour(v.id());
        if (Crafts.take(level, v, s -> s.is(Heraldry.bannerItem(want)) && s.get(DataComponents.BANNER_PATTERNS) == null, 1)) return want;
        for (DyeColor c : DyeColor.values()) {
            if (Crafts.take(level, v, s -> s.is(Heraldry.bannerItem(c)) && s.get(DataComponents.BANNER_PATTERNS) == null, 1)) return c;
        }
        List<DyeColor> tries = new ArrayList<>();
        tries.add(want);
        for (DyeColor c : DyeColor.values()) if (c != want) tries.add(c);
        for (DyeColor c : tries) {
            net.minecraft.world.item.Item wool = woolOf(c);
            if (Crafts.stock(level, v, s -> s.is(wool)) < 6) continue;
            if (!Crafts.usePlanks(level, v, 1)) return null;
            if (!Crafts.take(level, v, s -> s.is(wool), 6)) {
                Crafts.store(level, v, new ItemStack(Items.OAK_PLANKS));
                return null;
            }
            return c;
        }
        return null;
    }

    static net.minecraft.world.item.Item woolOf(DyeColor c) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_wool"));
    }

    /** Can the stores run to a banner (one put by, or six wool of a colour and a plank)? */
    static boolean bannerToHand(ServerLevel level, Villages.Village v) {
        if (Crafts.stock(level, v, s -> s.is(ItemTags.BANNERS) && s.get(DataComponents.BANNER_PATTERNS) == null) > 0) return true;
        for (DyeColor c : DyeColor.values()) {
            net.minecraft.world.item.Item wool = woolOf(c);
            if (Crafts.stock(level, v, s -> s.is(wool)) >= 6) return Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS)) > 0;
        }
        return false;
    }

    /** The banner set up here, facing the street (away from the building), named for its place. True if it stands. */
    static boolean plantBanner(ServerLevel level, Villages.Village v, Marker m, BlockPos at, DyeColor c) {
        BlockState st = BannerBlock.byColor(c).defaultBlockState();
        double ang = Math.toDegrees(Math.atan2(at.getX() - v.centre().getX(), -(at.getZ() - v.centre().getZ())));
        int rot = Math.floorMod((int) Math.round(ang / 22.5) + 8, 16);         // it faces the square
        level.setBlock(at, st.setValue(BannerBlock.ROTATION, rot), 3);
        if (!(level.getBlockEntity(at) instanceof BannerBlockEntity be)) return false;
        ItemStack named = new ItemStack(Heraldry.bannerItem(c));
        named.set(DataComponents.CUSTOM_NAME, Component.literal(m.name()));
        be.applyComponentsFromItemStack(named);
        be.setChanged();
        level.sendBlockUpdated(at, st, level.getBlockState(at), 3);
        Ledger.note(v.id(), "carto.banner/" + m.name(), Long.toString(at.asLong()));
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    /**
     * The banner here touched to every sheet that holds it, as a player clicks a map on a banner: the game's own
     * banner marker, named, on each (never toggled off a sheet that has it already). How many sheets took it.
     */
    static int markBanner(ServerLevel level, List<MapItemSavedData> sheets, BlockPos at) {
        MapBanner b = MapBanner.fromWorld(level, at);
        if (b == null) return 0;
        int n = 0;
        for (MapItemSavedData d : sheets) {
            if (d.locked) continue;
            boolean has = false;
            for (MapBanner x : d.getBanners()) if (x.equals(b)) { has = true; break; }
            if (has) { n++; continue; }
            if (d.toggleBanner(level, at)) {
                for (MapBanner x : d.getBanners()) if (x.equals(b)) { n++; break; }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ beginning a survey

    /** The sheets' paper (one a sheet, an empty map at the table) to hand, and the panes for those to be locked. */
    static boolean paperToHand(ServerLevel level, Villages.Village v, int sheets, int panes) {
        return Crafts.stock(level, v, s -> s.is(Items.PAPER)) >= sheets && Crafts.stock(level, v, s -> s.is(Items.GLASS_PANE)) >= panes;
    }

    /**
     * The town's wall begun: the layout for its size, the paper for the sheets out of the stores, the sheets drawn up
     * blank and into the cartographer's pack, its round planned (the middle of each sheet, each quarter of a sheet at
     * twice the scale; then each of the town's places for its banner, the table, the hall). What was begun, or null
     * if it waits (on what, in WANTS).
     */
    @Nullable
    static Survey beginWall(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        UUID id = v.id();
        Ledger.Building hall = MapRoom.hall(id);
        if (hall == null) {
            want(id, "a hall to hang the town's map in");
            return null;
        }
        int[] lay = wallLayout(id);
        // A three-by-three wants a stretch of wall three frames across and three high: where the hall has none (and none
        // hangs already), the same ground on a two-by-two at twice the scale.
        MapRoom.Record had = MapRoom.record(id);
        if (lay[0] == 3 && (had == null || had.frames().size() != 9) && level.isLoaded(hall.anchor())
                && MapRoom.wall(level, id, hall, 9) == null) {
            lay = new int[]{ 2, lay[1] + 1 };
        }
        int n = lay[0] * lay[0];
        if (!paperToHand(level, v, n, n)) {
            want(id, n + " paper and " + n + " glass panes for the hall's map");
            return null;
        }
        int frames = 0;
        MapRoom.Record r = MapRoom.record(id);
        if (r == null || r.frames().size() < n) frames = n - (r == null ? 0 : r.frames().size());
        if (!MapRoom.framesToHand(level, v, frames)) {
            want(id, frames + " item frames (or their sticks and leather) for the hall's map");
            return null;
        }
        Economy.openCraft(id, AssistantEntity.StationTask.CARTOGRAPHER);
        try {
            if (!Crafts.take(level, v, s -> s.is(Items.PAPER), n)) return null;
        } finally {
            Economy.closeCraft();
        }
        String town = Villages.name(id);
        List<Integer> ids = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();
        int cx = v.centre().getX(), cz = v.centre().getZ();
        List<int[]> at = centres(lay[0], lay[1], cx, cz);
        for (int i = 0; i < at.size(); i++) {
            ItemStack sheet = blank(level, at.get(i)[0], at.get(i)[1], (byte) lay[1], "Map of " + town + " (" + part(lay[0], i) + ")",
                List.of("Walked and drawn by " + f.displayNameCap() + ", cartographer of " + town, "Begun on day " + (day + 1)));
            ids.add(sheet.get(DataComponents.MAP_ID).id());
            give(f, sheet);
            Economy.tally(id, AssistantEntity.StationTask.CARTOGRAPHER, sheet, 1, true);
        }
        // The round: the stations, in a loop from the sheet nearest the map room.
        List<BlockPos> stations = new ArrayList<>();
        for (int[] c : at) {
            if (lay[1] == 0) stations.add(new BlockPos(c[0], v.centre().getY(), c[1]));
            else {
                int q = width(lay[1]) / 4;
                for (int[] o : new int[][]{ { -q, -q }, { q, -q }, { q, q }, { -q, q } }) stations.add(new BlockPos(c[0] + o[0], v.centre().getY(), c[1] + o[1]));
            }
        }
        for (BlockPos p : tour(f.blockPosition(), stations)) stops.add(new Stop(p, Act.STAND, ""));
        for (Marker m : markers(level, id)) stops.add(new Stop(m.near(), Act.BANNER, m.name()));
        BlockPos table = Cartographers.tableAt(level, id);
        stops.add(new Stop(table != null ? table : f.blockPosition(), Act.TABLE, "the table"));
        stops.add(new Stop(hall.anchor(), Act.HALL, "the hall"));
        Survey s = new Survey(Kind.WALL, lay[0], (byte) lay[1], cx, cz, ids, stops);
        s.started = day;
        save(id, s);
        WANTS.remove(id);
        LOG.info("[MCA-CARTO] {} begins the town's map for the hall: {}x{} at 1:{}, {} stops", f.displayNameCap(), lay[0], lay[0],
            1 << lay[1], stops.size());
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fresh sheets for the hall. Out I go — the map fills in as I walk.",
            n + " sheets, and every street of " + town + " to walk with them. Wish me dry weather!"));
        return s;
    }

    /** The region's sheet begun: one, at four or eight times the scale, walked round a ring of stations. */
    @Nullable
    static Survey beginRegion(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        UUID id = v.id();
        if (!paperToHand(level, v, 1, 0)) {
            want(id, "a sheet of paper for the region's map");
            return null;
        }
        Economy.openCraft(id, AssistantEntity.StationTask.CARTOGRAPHER);
        try {
            if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 1)) return null;
        } finally {
            Economy.closeCraft();
        }
        byte scale = (byte) regionScale(level, v);
        int cx = v.centre().getX(), cz = v.centre().getZ();
        String town = Villages.name(id);
        ItemStack sheet = blank(level, cx, cz, scale, "The country round " + town,
            List.of("Walked and drawn by " + f.displayNameCap() + ", cartographer of " + town, "Begun on day " + (day + 1)));
        give(f, sheet);
        Economy.tally(id, AssistantEntity.StationTask.CARTOGRAPHER, sheet, 1, true);
        List<BlockPos> stations = new ArrayList<>();
        stations.add(v.centre());
        int ring = scale >= 3 ? 250 : 150, points = scale >= 3 ? 12 : 8;
        for (int k = 0; k < points; k++) {
            double a = k * 2 * Math.PI / points;
            stations.add(new BlockPos(cx + (int) Math.round(Math.cos(a) * ring), v.centre().getY(), cz + (int) Math.round(Math.sin(a) * ring)));
        }
        BlockPos mine = TownMine.siteOf(id);
        int half = width(scale) / 2;
        if (mine != null && Math.abs(mine.getX() - cx) < half && Math.abs(mine.getZ() - cz) < half) stations.add(mine);
        List<Stop> stops = new ArrayList<>();
        for (BlockPos p : tour(f.blockPosition(), stations)) stops.add(new Stop(p, Act.STAND, ""));
        Ledger.Building hall = MapRoom.hall(id);
        BlockPos table = Cartographers.tableAt(level, id);
        stops.add(new Stop(table != null ? table : v.centre(), Act.TABLE, "the table"));
        stops.add(new Stop(hall != null ? hall.anchor() : v.centre(), Act.HALL, "the hall"));
        Survey s = new Survey(Kind.REGION, 1, scale, cx, cz, List.of(sheet.get(DataComponents.MAP_ID).id()), stops);
        s.started = day;
        save(id, s);
        WANTS.remove(id);
        LOG.info("[MCA-CARTO] {} sets out round the country for the region's map: 1:{}, {} stops", f.displayNameCap(), 1 << scale, stops.size());
        FolkTalk.speak(f, "The country round " + town + " this time — the long walk. Back before dark, if the weather holds.");
        return s;
    }

    /** The region's scale: four times the ground's, or eight once the town's colonies, neighbours or mine lie further out. */
    static int regionScale(ServerLevel level, Villages.Village v) {
        int far = 0;
        for (BlockPos p : MapFinds.regionPlaces(level, v)) far = Math.max(far, (int) Math.sqrt(Scouts.flat(p, v.centre())));
        return far > 230 ? 3 : 2;
    }

    /** A commission begun: a sheet at twice the scale, centred out that way from the town, walked quarter by quarter. */
    @Nullable
    static Survey beginCommission(ServerLevel level, Villages.Village v, VillageFolkEntity f, Cartographers.Commission c, long day) {
        UUID id = v.id();
        if (!paperToHand(level, v, 1, 1)) {
            want(id, "a sheet of paper and a pane of glass for " + c.name() + "'s map");
            return null;
        }
        Economy.openCraft(id, AssistantEntity.StationTask.CARTOGRAPHER);
        try {
            if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 1)) return null;
        } finally {
            Economy.closeCraft();
        }
        double a = c.bearing() * (2 * Math.PI / 8) - Math.PI / 2;    // 0: north, clockwise
        int out = 192;
        int cx = v.centre().getX() + (int) Math.round(Math.cos(a) * out), cz = v.centre().getZ() + (int) Math.round(Math.sin(a) * out);
        String town = Villages.name(id);
        ItemStack sheet = blank(level, cx, cz, (byte) 1, "The land " + c.way() + " of " + town,
            List.of("Walked and drawn for " + c.name() + " by " + f.displayNameCap() + ", cartographer of " + town,
                "Begun on day " + (day + 1)));
        give(f, sheet);
        Economy.tally(id, AssistantEntity.StationTask.CARTOGRAPHER, sheet, 1, true);
        List<BlockPos> stations = new ArrayList<>();
        int q = width(1) / 4;
        for (int[] o : new int[][]{ { 0, 0 }, { -q, -q }, { q, -q }, { q, q }, { -q, q } }) stations.add(new BlockPos(cx + o[0], v.centre().getY(), cz + o[1]));
        List<Stop> stops = new ArrayList<>();
        for (BlockPos p : tour(f.blockPosition(), stations)) stops.add(new Stop(p, Act.STAND, ""));
        BlockPos table = Cartographers.tableAt(level, id);
        stops.add(new Stop(table != null ? table : v.centre(), Act.TABLE, "the table"));
        stops.add(new Stop(table != null ? table : v.centre(), Act.HOME, "the map room"));
        Survey s = new Survey(Kind.COMMISSION, 1, (byte) 1, cx, cz, List.of(sheet.get(DataComponents.MAP_ID).id()), stops);
        s.started = day;
        s.forWhom = c.player() + ";" + c.name();
        s.what = "the land " + c.way();
        save(id, s);
        WANTS.remove(id);
        LOG.info("[MCA-CARTO] {} sets out to map the land {} for {}", f.displayNameCap(), c.way(), c.name());
        FolkTalk.speak(f, "Off to map the land " + c.way() + " for " + c.name() + ". It'll be ready by tonight.");
        return s;
    }

    /** Into the cartographer's pack; what does not fit, at its feet. */
    static void give(VillageFolkEntity f, ItemStack s) {
        ItemStack left = f.insertItem(s);
        if (!left.isEmpty()) f.spawnAtLocation(left);
    }

    /** The stations in walking order: from here, each time to the nearest left. */
    static List<BlockPos> tour(BlockPos from, List<BlockPos> stations) {
        List<BlockPos> left = new ArrayList<>(stations), out = new ArrayList<>();
        BlockPos at = from;
        while (!left.isEmpty()) {
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos p : left) {
                double d = Scouts.flat(at, p);
                if (d < bd) { bd = d; best = p; }
            }
            out.add(best);
            left.remove(best);
            at = best;
        }
        return out;
    }

    // ------------------------------------------------------------------ the walk

    /** Is this folk out on a survey (by day): the rest of its day waits, and its plot does not call it back. */
    public static boolean surveying(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.stationTask() != AssistantEntity.StationTask.CARTOGRAPHER) return false;
        Survey s = of(id);
        return s != null && daylight(f.level().getDayTime());
    }

    static boolean daylight(long dayTime) {
        long t = dayTime % 24000L;
        return t < 12000L;
    }

    /** The town's survey, walked a round at a time (VillageFolkEntity.aiStep): true while it is out on it. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != AssistantEntity.StationTask.CARTOGRAPHER || f.isSleeping() || f.isBaby()) return false;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Survey s = v == null ? null : of(id);
        if (s == null) return false;
        if (!daylight(level.getDayTime())) {
            release(level, f, s);
            putAwayTheSheet(f);
            return false;
        }
        if (f.tickCount - s.roundTick < ROUND && f.tickCount >= s.roundTick) return true;
        s.roundTick = f.tickCount;
        step(level, v, f, s);
        return true;
    }

    /** One round of a survey: the ground kept awake, the sheets filled where it stands, and on to the next stop. */
    static void step(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s) {
        UUID id = v.id();
        if (s.next >= s.stops.size()) {
            finish(level, v, f, s);
            return;
        }
        // Lost the sheets (it was a cartographer no longer, or they were taken from it): the survey is let go.
        int have = 0;
        for (int sid : s.sheets) if (!carried(f, sid).isEmpty()) have++;
        if (have == 0) {
            LOG.info("[MCA-CARTO] {}'s survey let go: its sheets are gone", f.displayNameCap());
            release(level, f, s);
            forget(id);
            return;
        }
        keepAwake(level, f, s);
        holdTheSheet(level, f, s);
        int filled = fill(level, f, s);
        Stop stop = s.stops.get(s.next);
        if (s.stopTick < 0) s.stopTick = f.tickCount;
        double near = switch (stop.act()) {
            case STAND, LOOK -> 4.0;
            case HALL -> 5.0;                                      // the hall's middle is its long table: near enough is the room
            default -> 2.5;
        };
        boolean there = Visitors.walk(f, level, stop.at(), near, 0.9D, s.walk);
        if (!there && f.tickCount - s.stopTick > STOP_MOST) {
            LOG.info("[MCA-CARTO] {} gives up on stop {} ({}) at {}", f.displayNameCap(), s.next, stop.act(), stop.at().toShortString());
            there = true;                                            // the stop's work is done from where it got to
        }
        f.hobbyNow = doing(s, stop);
        if (!there) return;
        if (arrive(level, v, f, s, stop, filled)) {
            s.next++;
            s.stood = 0;
            s.strokesHere = 0;
            s.stopTick = -1;
            s.walk.reset();
            save(id, s);
        }
    }

    /**
     * The sheet it is nearest the middle of, held up in its hand for all to see: what it is working on. A sight of it
     * only (Leisure's prop: never dropped, put away at a restart); the sheet itself stays in its pack.
     */
    static void holdTheSheet(ServerLevel level, VillageFolkEntity f, Survey s) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int sid : s.sheets) {
            MapItemSavedData d = data(level, sid);
            if (d == null) continue;
            double dd = Scouts.flat(f.blockPosition(), new BlockPos(d.centerX, 0, d.centerZ));
            if (dd < bd) { bd = dd; best = sid; }
        }
        f.lastLeisureTick = f.tickCount;                              // its card's line is the survey's, not a pastime's
        if (best < 0) return;
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (isSheet(off, best) && Leisure.isProp(off)) return;
        if (!off.isEmpty() && !Leisure.isProp(off)) return;           // its hand is full of something of its own
        ItemStack shown = new ItemStack(Items.FILLED_MAP);
        shown.set(DataComponents.MAP_ID, new MapId(best));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("mca_prop", true);
        shown.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        f.setItemSlot(EquipmentSlot.OFFHAND, shown);
        f.propInHand = true;
    }

    /** The sheet put away out of its hand. */
    static void putAwayTheSheet(VillageFolkEntity f) {
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(Items.FILLED_MAP) && Leisure.isProp(off)) {
            f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            f.propInHand = false;
        }
    }

    /** The sheets it carries filled from the ground round it (the game's update, a few strokes each). How many took. */
    static int fill(ServerLevel level, VillageFolkEntity f, Survey s) {
        int n = 0;
        for (int sid : s.sheets) {
            if (carried(f, sid).isEmpty()) continue;
            MapItemSavedData d = data(level, sid);
            if (d != null && stroke(level, f, d, STROKES)) n++;
        }
        if (n > 0) s.strokesHere += STROKES;
        return n;
    }

    /** The ground about the walker kept awake, a window that moves with it (released when it is done). */
    static void keepAwake(ServerLevel level, VillageFolkEntity f, Survey s) {
        BlockPos here = f.blockPosition();
        if (s.window != null && Scouts.flat(s.window, here) < 40 * 40) return;
        UUID owner = windowOwner(f);
        if (s.window != null) ChunkLoad.setLoaded(level, owner, s.window, WINDOW, false);
        ChunkLoad.setLoaded(level, owner, here, WINDOW, true);
        s.window = here.immutable();
    }

    static void release(ServerLevel level, VillageFolkEntity f, Survey s) {
        if (s.window == null) return;
        ChunkLoad.setLoaded(level, windowOwner(f), s.window, WINDOW, false);
        s.window = null;
    }

    private static UUID windowOwner(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-carto-walk:" + f.getUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** What it is doing at this stop, for its card and its talk. */
    static String doing(Survey s, Stop stop) {
        return switch (stop.act()) {
            case LOOK, STAND -> "walking " + (s.kind == Kind.WALL ? "the town" : s.kind == Kind.REGION ? "the country round the town" : s.what)
                + " with " + (s.sheets.size() == 1 ? "a sheet" : s.sheets.size() + " sheets") + ", filling it in (station " + (s.next + 1) + " of "
                + stations(s) + ")";
            case BANNER -> "marking the " + stop.name().toLowerCase(Locale.ROOT) + " on the town's map";
            case TABLE -> "back to the map room to lock the sheets under glass";
            case HALL -> "taking the new map to the hall";
            case HOME -> "taking " + s.what + " back to the map room";
        };
    }

    static int stations(Survey s) {
        int n = 0;
        for (Stop st : s.stops) if (st.act() == Act.STAND || st.act() == Act.LOOK) n++;
        return n;
    }

    /** At a stop: its work done. True to go on to the next. */
    static boolean arrive(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s, Stop stop, int filled) {
        long day = level.getDayTime() / 24000L;
        switch (stop.act()) {
            case LOOK -> { return true; }
            case STAND -> {
                // Stood till every sheet near here is filled in round it, or the half minute is up.
                s.stood++;
                if (s.stood == 1) f.getNavigation().stop();
                return s.strokesHere >= FILLED || s.stood >= STAND_MOST;
            }
            case BANNER -> {
                bannerAt(level, v, f, s, stop);
                return true;
            }
            case TABLE -> {
                lock(level, v, f, s);
                return true;
            }
            case HALL, HOME -> {
                return true;                                         // the survey ends: finish, next round
            }
        }
        return true;
    }

    /** At a place of the town: its banner set up if it has none, and touched to every sheet. */
    static void bannerAt(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s, Stop stop) {
        Marker m = new Marker(stop.name(), stop.at());
        BlockPos at = bannerSpot(level, v.id(), m);
        if (at == null) {
            LOG.info("[MCA-CARTO] no room for a banner at {} ({})", m.name(), m.near().toShortString());
            return;
        }
        if (!named(level, at, m.name())) {
            DyeColor c = bannerFromStores(level, v);
            if (c == null) {
                want(v.id(), "a banner (six wool and a stick) to mark the " + m.name().toLowerCase(Locale.ROOT));
                return;
            }
            if (!plantBanner(level, v, m, at, c)) return;
        }
        List<MapItemSavedData> sheets = new ArrayList<>();
        for (int sid : s.sheets) {
            MapItemSavedData d = data(level, sid);
            if (d != null) sheets.add(d);
        }
        int took = markBanner(level, sheets, at);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
        LOG.info("[MCA-CARTO] {} marked the {} on {} sheets", f.displayNameCap(), m.name(), took);
    }

    /** At the table: each sheet of the wall (or a commission) locked under a pane of the stores' glass. */
    static void lock(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s) {
        if (s.kind == Kind.REGION) return;                            // the region stays open: its copies fill it in
        int locked = 0;
        List<Integer> now = new ArrayList<>();
        Economy.openCraft(v.id(), AssistantEntity.StationTask.CARTOGRAPHER);
        try {
            for (int sid : s.sheets) {
                ItemStack st = carried(f, sid);
                MapItemSavedData d = data(level, sid);
                if (!st.isEmpty() && d != null && !d.locked && Crafts.take(level, v, x -> x.is(Items.GLASS_PANE), 1)) {
                    MapItem.lockMap(level, st);
                    locked++;
                }
                MapId id = st.isEmpty() ? null : st.get(DataComponents.MAP_ID);
                now.add(id == null ? sid : id.id());
            }
        } finally {
            Economy.closeCraft();
        }
        s.sheets.clear();
        s.sheets.addAll(now);
        level.playSound(null, f.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        if (locked < now.size()) want(v.id(), "glass panes to lock the maps under glass");
        LOG.info("[MCA-CARTO] {} locked {} of {} sheets at the table", f.displayNameCap(), locked, now.size());
    }

    /** The survey done: the wall hung, the region's sheet framed, a commission left ready. */
    static void finish(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        release(level, f, s);
        putAwayTheSheet(f);
        String did = switch (s.kind) {
            case WALL -> hangWall(level, v, f, s, day);
            case REGION -> hangRegion(level, v, f, s, day);
            case COMMISSION -> Cartographers.commissionReady(level, v, f, s, day);
        };
        forget(id);
        f.hobbyNow = null;
        f.note(AssistantEntity.Deed.THINGS_MADE, s.sheets.size());
        f.awardXp(4 + 2 * s.sheets.size());
        Cartographers.count(id, "sheets", s.sheets.size());
        if (did != null) LOG.info("[MCA-CARTO] {}: {}", Villages.name(id), did);
    }

    // ------------------------------------------------------------------ the hall's wall

    /**
     * The new wall hung in the hall: in the frames that are there if they are the right number, else on a free stretch
     * of the hall's wall (a three-by-three where there is room; a two-by-two of the same ground otherwise), new frames
     * paid for out of the stores; the old maps down and to the archive; a sign under it. What was done.
     */
    @Nullable
    static String hangWall(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s, long day) {
        UUID id = v.id();
        Ledger.Building hall = MapRoom.hall(id);
        List<ItemStack> sheets = new ArrayList<>();
        for (int sid : s.sheets) {
            ItemStack st = takeSheet(f, sid);
            if (!st.isEmpty()) sheets.add(st);
        }
        if (hall == null || sheets.size() != s.across * s.across) {
            for (ItemStack st : sheets) Crafts.store(level, v, st);
            return "the hall's map went to the stores: " + (hall == null ? "no hall" : "a sheet was lost");
        }
        int n = sheets.size();
        MapRoom.Record old = MapRoom.record(id);
        List<Decor.Spot> spots = old != null && old.frames().size() == n ? MapRoom.existing(level, old) : null;
        List<BlockPos> stale = spots == null && old != null ? old.frames() : List.of();
        if (spots == null) spots = MapRoom.wall(level, id, hall, n, Set.copyOf(stale));
        if (spots == null) spots = MapRoom.wall(level, id, hall, n);
        if (spots == null) {
            for (ItemStack st : sheets) Crafts.store(level, v, st);
            want(id, "a stretch of the hall's wall for " + (n == 9 ? "a three-by-three" : "a two-by-two") + " of maps");
            return null;
        }
        int fresh = 0;
        for (Decor.Spot sp : spots) if (MapRoom.frame(level, sp.at()) == null) fresh++;
        // The old wall comes down: its maps to the archive, its frames (if not to be used again) to hang the new.
        List<ItemStack> down = new ArrayList<>();
        int reused = 0;
        for (BlockPos p : stale) {
            if (!level.isLoaded(p)) continue;
            ItemFrame fr = MapRoom.frame(level, p);
            if (fr == null) continue;
            if (!fr.getItem().isEmpty()) down.add(fr.getItem().copy());
            fr.discard();
            reused++;
        }
        if (!MapRoom.payForFrames(level, v, Math.max(0, fresh - reused))) {
            for (ItemStack st : sheets) Crafts.store(level, v, st);
            for (ItemStack st : down) Crafts.store(level, v, st);
            want(id, fresh + " item frames for the hall's map");
            return null;
        }
        if (reused > fresh) Crafts.store(level, v, new ItemStack(Items.ITEM_FRAME, reused - fresh));
        List<BlockPos> hung = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) {
            Decor.Spot sp = spots.get(i);
            ItemFrame fr = MapRoom.frame(level, sp.at());
            if (fr == null) {
                fr = new ItemFrame(level, sp.at(), sp.facing());
                fr.addTag(MapRoom.TAG);
                level.addFreshEntity(fr);
                fr.playPlacementSound();
            } else if (!fr.getItem().isEmpty()) {
                down.add(fr.getItem().copy());
            }
            fr.setItem(sheets.get(i), false);
            hung.add(sp.at());
        }
        Wall was = wall(id);
        MapRoom.record(id, day, hung);
        String age = Villages.ageOf(id).label;
        Ledger.note(id, "carto.wall", day + "|" + s.across + "|" + s.scale + "|" + Villages.headcount(id) + "|" + Villages.townReach(id)
            + "|" + Ledger.buildings(id).size() + "|" + age + "|" + f.displayNameCap().replace('|', ' '));
        signUnder(level, v, spots, f, day, s);
        String archived = down.isEmpty() ? "" : MapArchive.keep(level, v, down, was, day);
        level.playSound(null, hung.get(0), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — " + Villages.name(id) + " as it stands, every street walked. Come and look!",
            "The new map's up in the hall. Find your house on it!"));
        Villages.tell(id, day, f.displayNameCap() + " hung a new map of " + Villages.name(id) + " in the hall, "
            + (n == 9 ? "a three-by-three" : "a two-by-two") + " of sheets walked and drawn over " + (day - s.started + 1)
            + (day - s.started == 0 ? " day" : " days") + (archived.isEmpty() ? "" : "; the old one " + archived));
        Cartographers.count(id, "walls", 1);
        WANTS.remove(id);
        return (n == 9 ? "a three-by-three" : "a two-by-two") + " of maps hung in the hall" + (archived.isEmpty() ? "" : "; the old wall " + archived);
    }

    /** A sign under the middle of the wall: what it is, who drew it, and when. Out of the stores (a sign, or two planks). */
    static void signUnder(ServerLevel level, Villages.Village v, List<Decor.Spot> spots, VillageFolkEntity f, long day, Survey s) {
        Decor.Spot bottom = spots.get(spots.size() - 1 - (s.across == 3 ? 1 : s.across - 1));
        BlockPos at = bottom.at().below();
        Direction face = bottom.facing();
        BlockState here = level.getBlockState(at);
        boolean ours = here.getBlock() instanceof WallSignBlock;
        if (!ours) {
            if (!here.isAir()) return;
            BlockPos wall = at.relative(face.getOpposite());
            if (!level.getBlockState(wall).isFaceSturdy(level, wall, face)) return;
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.decoration.HangingEntity.class, new AABB(at).inflate(0.05)).isEmpty()) return;
            if (!Crafts.sign(level, v)) return;
            level.setBlock(at, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, face), 3);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            TownLife.write(sign, new String[]{ "Map of", Villages.name(v.id()), "by " + f.displayNameCap(), "day " + (day + 1) });
        }
    }

    // ------------------------------------------------------------------ the region's sheet

    /**
     * The region's sheet marked (the town, its colonies and neighbours, the mine, the caves and the places found) and
     * framed: in the hall away from the town's wall if there is room, else on the map room's own wall. Never locked.
     */
    @Nullable
    static String hangRegion(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s, long day) {
        UUID id = v.id();
        ItemStack sheet = takeSheet(f, s.sheets.get(0));
        if (sheet.isEmpty()) return null;
        int marks = MapFinds.markRegion(level, v, sheet);
        // Take down last fortnight's, if it hangs: the new one goes up in its frame.
        BlockPos frameAt = regionFrame(id);
        ItemFrame fr = frameAt != null && level.isLoaded(frameAt) ? regionFrameAt(level, frameAt) : null;
        if (fr == null) {
            Set<BlockPos> taken = new HashSet<>();
            MapRoom.Record r = MapRoom.record(id);
            if (r != null) for (BlockPos p : r.frames()) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) taken.add(p.offset(dx, dy, dz));
            Ledger.Building hall = MapRoom.hall(id);
            List<Decor.Spot> spot = hall == null ? null : MapRoom.wall(level, id, hall, 1, taken);
            if (spot == null) {
                Ledger.Building room = Cartographers.mapRoom(id);
                spot = room == null ? null : MapRoom.wall(level, id, room, 1, taken);
            }
            if (spot == null || !MapRoom.payForFrames(level, v, 1)) {
                Crafts.store(level, v, sheet);
                want(id, "a frame and a spot on a wall for the region's map");
                return "the region's map went to the stores, with nowhere to hang";
            }
            fr = new ItemFrame(level, spot.get(0).at(), spot.get(0).facing());
            fr.addTag(REGION_TAG);
            level.addFreshEntity(fr);
            fr.playPlacementSound();
        } else if (!fr.getItem().isEmpty()) {
            Crafts.store(level, v, fr.getItem().copy());             // the old one: a copy for the stores, for anybody's use
        }
        fr.setItem(sheet, false);
        Ledger.note(id, "carto.region", sheet.get(DataComponents.MAP_ID).id() + "|" + s.scale + "|" + day + "|" + fr.getPos().asLong());
        Villages.tell(id, day, f.displayNameCap() + " walked the country round " + Villages.name(id) + " and framed its map, "
            + marks + " places marked on it; the caravans and the envoys will carry copies");
        FolkTalk.speak(f, "The country round us, on one sheet. The caravans will fill in the roads as they go.");
        Cartographers.count(id, "regions", 1);
        return "the region's map framed (" + marks + " marks)";
    }

    static final String REGION_TAG = "mca_region_map";

    @Nullable
    static BlockPos regionFrame(UUID village) {
        String s = Ledger.note(village, "carto.region");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|");
        try {
            return p.length >= 4 ? BlockPos.of(Long.parseLong(p[3])) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The region's map id, or -1. */
    public static int regionMap(UUID village) {
        String s = Ledger.note(village, "carto.region");
        if (s == null || s.isEmpty()) return -1;
        try {
            return Integer.parseInt(s.split("\\|")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The day the region's map was drawn, or -1. */
    static long regionDay(UUID village) {
        String s = Ledger.note(village, "carto.region");
        if (s == null || s.isEmpty()) return -1;
        try {
            return Long.parseLong(s.split("\\|")[2]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Nullable
    static ItemFrame regionFrameAt(ServerLevel level, BlockPos at) {
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(0.1), e -> e.isAlive() && e.getTags().contains(REGION_TAG))) {
            return f;
        }
        return null;
    }

    /**
     * A copy of the region's map for a caravan or an envoy setting out (Caravans): the same map, so it fills in as they
     * go. A sheet of the stores' paper at the table, as the cartography table copies a map. True if one was given.
     */
    public static boolean roadCopy(ServerLevel level, Villages.Village v, VillageFolkEntity traveller) {
        int region = regionMap(v.id());
        if (region < 0) return false;
        for (ItemStack s : traveller.getInventoryItems()) if (isSheet(s, region)) return false;
        if (Cartographers.cartographer(v.id()) == null) return false;
        Economy.openCraft(v.id(), AssistantEntity.StationTask.CARTOGRAPHER);
        try {
            if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 1)) return false;
        } finally {
            Economy.closeCraft();
        }
        ItemStack copy = new ItemStack(Items.FILLED_MAP);
        copy.set(DataComponents.MAP_ID, new MapId(region));
        copy.set(DataComponents.ITEM_NAME, Component.literal("The country round " + Villages.name(v.id())));
        copy.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("A copy for the road, from the map room").withStyle(ChatFormatting.GRAY))));
        ItemStack left = traveller.insertGiven(copy);
        if (!left.isEmpty()) {
            Crafts.store(level, v, new ItemStack(Items.PAPER));
            return false;
        }
        Economy.tally(v.id(), AssistantEntity.StationTask.CARTOGRAPHER, copy, 1, true);
        Cartographers.count(v.id(), "roadcopies", 1);
        return true;
    }

    // ------------------------------------------------------------------ for the tests and the stage

    /** The survey run to its end now: the walker set down at each stop in turn, standing till its sheet is filled. */
    static void runToEnd(ServerLevel level, Villages.Village v, VillageFolkEntity f, Survey s) {
        int guard = 0;
        while (of(v.id()) == s && guard++ < 400) {
            if (s.next < s.stops.size()) {
                BlockPos at = s.stops.get(s.next).at();
                BlockPos ground = level.isLoaded(at)
                    ? new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ()) : at;
                f.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, f.getYRot(), 0.0F);
                Stop st = s.stops.get(s.next);
                if (st.act() == Act.STAND) {
                    for (int k = 0; k < FILLED / STROKES; k++) fill(level, f, s);
                    s.strokesHere = FILLED;
                }
                if (arrive(level, v, f, s, st, 1) || st.act() == Act.STAND) {
                    s.next++;
                    s.stood = 0;
                    s.strokesHere = 0;
                    s.stopTick = -1;
                    save(v.id(), s);
                }
            } else {
                finish(level, v, f, s);
            }
        }
    }

    /** Tests: one round of the survey here and now, wherever the cartographer stands (it is not walked anywhere). */
    public static int strokeForTests(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        Survey s = id == null ? null : of(id);
        return s == null ? 0 : fill(level, f, s);
    }

    /** Tests: the survey run to its end now. */
    public static void runForTests(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Survey s = v == null ? null : of(id);
        if (s != null) runToEnd(level, v, f, s);
    }

    /** Tests: the survey ended here and now, its sheets as they stand (no walk): hung, framed or left ready. */
    public static void finishNowForTests(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Survey s = v == null ? null : of(id);
        if (s != null) finish(level, v, f, s);
    }

    /** Tests: the town's survey under way. */
    @Nullable
    public static Survey surveyForTests(UUID village) {
        return of(village);
    }

    /** Tests: a sheet's colours, by its id. */
    @Nullable
    public static MapItemSavedData sheetForTests(ServerLevel level, int id) {
        return data(level, id);
    }

    /** Tests: the hall's wall made due (as if drawn so many days ago). */
    public static void wallDrawnForTests(UUID village, long day) {
        Wall w = wall(village);
        if (w == null) return;
        Ledger.note(village, "carto.wall", day + "|" + w.across() + "|" + w.scale() + "|" + w.folk() + "|" + w.reach() + "|" + w.buildings()
            + "|" + w.age() + "|" + w.by());
        MapRoom.drawnOnForTests(village, day);
    }

    /** Tests: where a marker's banner stands, or null. */
    @Nullable
    public static BlockPos bannerForTests(UUID village, String name) {
        return keptBanner(village, name);
    }

    /** Tests: the survey under way forgotten, so the next begins on a clean sheet. */
    public static void forgetForTests(UUID village) {
        forget(village);
    }

    /** Tests: why the hall's wall wants redrawing on this day ("first", "week", "grown"...), or null when it does not. */
    @Nullable
    public static String wallDueForTests(ServerLevel level, UUID village, long day) {
        return wallDue(level, village, day);
    }

    /** Tell an online player, if it is near enough to care. */
    static void tell(ServerLevel level, UUID player, String text) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
    }

    /** Whether a container holds a map of this id. */
    static boolean holds(Container c, int id) {
        for (int i = 0; i < c.getContainerSize(); i++) if (isSheet(c.getItem(i), id)) return true;
        return false;
    }

    /** Whether this block can take a sheet into it (a chest in the map room). */
    static boolean chest(Block b) {
        return b == Blocks.CHEST || b == Blocks.BARREL || b == Blocks.TRAPPED_CHEST;
    }

    /** The player who asked for a commission, if any is online. */
    @Nullable
    static Player asker(ServerLevel level, String forWhom) {
        String[] p = forWhom.split(";", 2);
        try {
            return level.getServer().getPlayerList().getPlayer(UUID.fromString(p[0]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Draws the decorations: the frame types the region's marks use, kept here for the books (MapsPage). */
    static String markWord(net.minecraft.core.Holder<net.minecraft.world.level.saveddata.maps.MapDecorationType> t) {
        return t == MapDecorationTypes.RED_X ? "x" : t == MapDecorationTypes.TARGET_X ? "+" : "o";
    }
}
