package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [caves] The Delvers' Lodge: the cave team's own house (blueprints/lodge.txt), from the Iron Age, once the town keeps a
 * team. A timber hall on a stone lower course: the map wall at the back, the trophy wall down the left, two bunks down
 * the right between the windows, the gear (barrels, a chest, the grindstone, the workbench) in the corners, and the
 * team's log on a lectern by the door.
 *
 * <ul>
 * <li><b>The map wall</b> (maps): a two-by-two of maps of the cave country round the town, every cave the team has
 *     found marked on them (a red cross; a mineshaft, a dungeon or a spawner its own mark), drawn by the team at home
 *     on the stores' paper (nine sheets, or eight and a compass to spare, a map) and hung in frames out of the stores
 *     (or made there of their sticks and a leather): drawn again every seven days, or as soon as a new cave is
 *     found. The old maps go back to the stores.</li>
 * <li><b>The trophy wall</b> (trophy): six frames of the rarest things the team has brought up (a diamond, an emerald,
 *     an enchanted book, a music disc, a golden apple, a heart of the sea...), one of each, out of the stores (never
 *     the last of them), each named for who brought it up and from where.</li>
 * <li><b>The log</b>: the team's caves and their veins, its hauls, written up on a book out of the stores and laid on
 *     the lectern for anybody to read, every three days.</li>
 * <li><b>Home</b>: the team stands before the map wall at home, sets out from the lodge and comes home to it before it
 *     takes the haul on to the storehouse (CaveDwellers.post, setOut). Its bunks are the team's (nobody else's bed:
 *     VillageFolkEntity.bedOnOffer), made up from the stores like a house's (Grow.furnish).</li>
 * <li><b>A map for a player</b> (sellMap): a copy of the team's cave map, a real filled map with the caves marked,
 *     drawn on the town's paper for a few coins.</li>
 * </ul>
 */
public final class Lodge {

    private Lodge() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    public static final String STRUCTURE = "lodge";
    /** The frames' tag (the map wall's and the trophy wall's). */
    public static final String TAG = "mca_lodge";
    /** Days between the map wall's drawings, and between the log's. */
    static final long MAPS_EVERY = 7, LOG_EVERY = 3;
    /** What a copy of the cave map costs a player. */
    public static final int MAP_PRICE = 6;

    /** The map wall, in the drawing's terms (across, up, back): top left, top right, bottom left, bottom right. */
    static final int[][] MAPS = { { 0, 2, 3 }, { 1, 2, 3 }, { 0, 1, 3 }, { 1, 1, 3 } };
    /** The trophy wall, down the left wall, the top row first. */
    static final int[][] TROPHIES = { { -2, 2, 2 }, { -2, 2, 1 }, { -2, 2, 0 }, { -2, 1, 2 }, { -2, 1, 1 }, { -2, 1, 0 } };
    /** Where the team stands at home (before the map wall); the lectern; outside the door. */
    static final int[] HALL = { 0, 0, 0 }, LECTERN = { 3, 0, -2 }, DOOR = { 0, 0, -5 };

    // ------------------------------------------------------------------ the building

    /** Is a lodge wanted (Villages.projectsWantedInOrder): the Iron Age, a cave team kept (or wanted), and none yet? */
    public static boolean wanted(UUID village) {
        if (Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal() || Villages.hasBuilt(village, STRUCTURE) || of(village) != null) return false;
        return !CaveDwellers.dwellers(village).isEmpty() || CaveDwellers.wanted(village) > 0;
    }

    /** Why the town wants one (Villages.whyBuild). */
    public static String why(UUID village) {
        if (Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return "a lodge for a cave team comes with the Iron Age";
        if (CaveDwellers.dwellers(village).isEmpty() && CaveDwellers.wanted(village) == 0) return "there is no cave team to keep one";
        return "the Delvers' Lodge: the cave team's maps and trophies on its walls, its bunks and its gear, where it sets out from and comes home to";
    }

    /** The town's lodge (the first, if it has built more), or null. */
    @Nullable
    public static Ledger.Building of(@Nullable UUID village) {
        return village == null ? null : Villages.builtStructure(village, STRUCTURE);
    }

    /** A spot in the lodge, from the drawing's across, up and back. */
    public static BlockPos at(Ledger.Building b, int dx, int h, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz).above(h);
    }

    static BlockPos at(Ledger.Building b, int[] c) {
        return at(b, c[0], c[1], c[2]);
    }

    /** Where the team stands at home: before the map wall. Null with no lodge. */
    @Nullable
    public static BlockPos hall(@Nullable UUID village) {
        Ledger.Building b = of(village);
        return b == null ? null : at(b, HALL);
    }

    /** Outside its door, where the team gathers to set out and comes home to. Null with no lodge. */
    @Nullable
    public static BlockPos door(@Nullable UUID village) {
        Ledger.Building b = of(village);
        return b == null ? null : at(b, DOOR);
    }

    /** Is this bed one of the lodge's bunks (the team's, nobody else's)? Read from where it stands, no world. */
    public static boolean isLodgeBed(@Nullable UUID village, BlockPos pos) {
        if (village == null) return false;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!b.structure().equals(STRUCTURE)) continue;
            BlockPos a = b.anchor();
            if (Math.abs(pos.getX() - a.getX()) <= 5 && Math.abs(pos.getZ() - a.getZ()) <= 5 && Math.abs(pos.getY() - a.getY()) <= 3) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the fit-out

    /** What the lodge waits on, said once (the log). */
    private static final Map<UUID, String> WANTS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WANTS.clear();
    }

    private static void want(UUID id, String what) {
        if (!what.equals(WANTS.put(id, what))) LOG.info("[MCA-CAVES] the lodge of {} waits on {}", Villages.name(id), what);
    }

    /** The town's look at its lodge (CaveDwellers.tick): the map wall, a trophy, the log, each when due. */
    public static void tick(ServerLevel level, Villages.Village v, long day) {
        Ledger.Building b = of(v.id());
        if (b == null || !level.isLoaded(b.anchor())) return;
        VillageFolkEntity hand = handAt(level, v.id(), b);
        if (hand == null) return;                                     // the team's work, done by the team at home
        if (mapsDue(level, v.id(), day)) maps(level, v, b, hand, day);
        trophy(level, v, b, hand, day);
        log(level, v, b, hand, day);
    }

    /** One of the team at home, at the lodge (or near it), to do the lodge's work. */
    @Nullable
    static VillageFolkEntity handAt(ServerLevel level, UUID village, Ledger.Building b) {
        BlockPos hall = at(b, HALL);
        for (VillageFolkEntity f : CaveDwellers.dwellers(village)) {
            if (f.expedition() == null && f.blockPosition().distSqr(hall) <= 14 * 14) return f;
        }
        return null;
    }

    @Nullable
    private static ItemFrame frame(ServerLevel level, BlockPos at) {
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(0.1), e -> e.isAlive() && e.getTags().contains(TAG))) {
            return f;
        }
        return null;
    }

    /** Can a frame hang here: air, a sound wall behind, nothing hanging there. */
    private static boolean hangs(ServerLevel level, BlockPos at, Direction facing) {
        if (!level.isLoaded(at) || !level.getBlockState(at).isAir()) return false;
        return new ItemFrame(level, at, facing).survives();
    }

    /** A frame up (out of the stores, or made there of four planks' sticks and a leather), tagged the lodge's. */
    @Nullable
    private static ItemFrame hang(ServerLevel level, Villages.Village v, BlockPos at, Direction facing) {
        ItemFrame f = frame(level, at);
        if (f != null) return f;
        if (!hangs(level, at, facing) || !payForFrame(level, v)) return null;
        f = new ItemFrame(level, at, facing);
        f.addTag(TAG);
        level.addFreshEntity(f);
        f.playPlacementSound();
        return f;
    }

    private static boolean payForFrame(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.ITEM_FRAME), 1)) return true;
        if (Crafts.stock(level, v, s -> s.is(Items.LEATHER)) < 1 || !Crafts.usePlanks(level, v, 4)) return false;
        return Crafts.take(level, v, s -> s.is(Items.LEATHER), 1);
    }

    // ------------------------------------------------------------------ the map wall

    /** The caves the town's report holds: for the map wall's "a new one since". */
    static int caves(UUID village) {
        int n = 0;
        for (CaveDwellers.Find x : CaveDwellers.report(village)) if (x.kind() == CaveDwellers.Kind.CAVE || x.kind() == CaveDwellers.Kind.RAVINE) n++;
        return n;
    }

    /** Is the map wall due: never drawn, a week old, or a cave found since? */
    static boolean mapsDue(ServerLevel level, UUID village, long day) {
        String s = Ledger.note(village, "lodge.maps");
        if (s == null || s.isEmpty()) return caves(village) > 0;
        String[] p = s.split("\\|");
        try {
            return day - Long.parseLong(p[0]) >= MAPS_EVERY || p.length > 1 && caves(village) > Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** The map wall drawn and hung as far as the town can run to it. What was done, or null if it waits. */
    @Nullable
    static String maps(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity by, long day) {
        UUID id = v.id();
        Direction out = b.facing().getOpposite();
        int fresh = 0;
        for (int[] c : MAPS) if (frame(level, at(b, c)) == null) fresh++;
        if (!MapRoom.paperToHand(level, v, MAPS.length)) {
            want(id, MAPS.length * 9 + " paper (or " + MAPS.length * 8 + " and compasses to spare) for the map wall");
            return null;
        }
        if (!MapRoom.framesToHand(level, v, fresh)) {
            want(id, fresh + " item frames (or their sticks and leather) for the map wall");
            return null;
        }
        for (int i = 0; i < MAPS.length; i++) {
            if (!payForSheet(level, v)) {
                want(id, "paper for the map wall");
                return null;
            }
        }
        String who = by == null ? "the cave team" : by.displayNameCap();
        List<ItemStack> sheets = quarters(level, v, who, day);
        int back = 0, hung = 0;
        for (int i = 0; i < MAPS.length; i++) {
            ItemFrame f = hang(level, v, at(b, MAPS[i]), out);
            if (f == null) {
                Crafts.store(level, v, sheets.get(i));                    // no wall for it: the sheet into the stores
                continue;
            }
            if (!f.getItem().isEmpty()) {
                Crafts.store(level, v, f.getItem().copy());
                back++;
            }
            f.setItem(sheets.get(i), false);
            hung++;
        }
        Ledger.note(id, "lodge.maps", day + "|" + caves(id));
        WANTS.remove(id);
        level.playSound(null, at(b, MAPS[0]), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (by != null) FolkTalk.speak(by, FolkTalk.pick(by.getRandom(), "There — every cave we've found, on the wall where we can all see it.",
            "The map wall's up to date. Look how far we've gone!"));
        String did = hung + " maps of the cave country hung on the lodge's wall (" + caves(id) + " caves marked)"
            + (back > 0 ? "; the old ones back to the stores" : "");
        LOG.info("[MCA-CAVES] {}: {}", Villages.name(id), did);
        return did;
    }

    private static boolean payForSheet(ServerLevel level, Villages.Village v) {
        if (Crafts.stock(level, v, s -> s.is(Items.COMPASS)) > 1 && Crafts.stock(level, v, s -> s.is(Items.PAPER)) >= 8) {
            if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 8)) return false;
            if (Crafts.take(level, v, s -> s.is(Items.COMPASS), 1)) return true;
            Crafts.store(level, v, new ItemStack(Items.PAPER, 8));
            return false;
        }
        return Crafts.take(level, v, s -> s.is(Items.PAPER), 9);
    }

    /** The scale that holds every cave the team has found, and its range, on a sheet of this many across (1 or 2). */
    static byte scaleFor(UUID village, int across) {
        BlockPos home = Villages.get(village) == null ? BlockPos.ZERO : Villages.get(village).centre();
        int far = CaveDwellers.range(village);
        for (CaveDwellers.Find x : CaveDwellers.report(village)) far = Math.max(far, (int) Math.sqrt(Scouts.flat(home, x.at())));
        byte scale = 0;
        while (scale < 4 && (64 << scale) * across < far + 16) scale++;
        return scale;
    }

    /** The map wall's four sheets: the cave country in quarters round the heart, north-west first. */
    static List<ItemStack> quarters(ServerLevel level, Villages.Village v, String by, long day) {
        byte scale = scaleFor(v.id(), 2);
        int half = 64 << scale;
        int cx = v.centre().getX(), cz = v.centre().getZ();
        String[] names = { "north-west", "north-east", "south-west", "south-east" };
        int[][] off = { { -half, -half }, { half, -half }, { -half, half }, { half, half } };
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(sheet(level, v, cx + off[i][0], cz + off[i][1], scale,
                "The caves of " + Villages.name(v.id()) + " (" + names[i] + ")", "Drawn by " + by + " for the Delvers' Lodge, day " + (day + 1)));
        }
        return out;
    }

    /**
     * A sheet of the cave country centred here at this scale: the ground filled in as it stands where it is loaded
     * (PlayerServices.paint), the town's heart marked, and every cave, ravine, mineshaft, dungeon and spawner the team
     * has found on it marked.
     */
    static ItemStack sheet(ServerLevel level, Villages.Village v, int cx, int cz, byte scale, String name, String lore) {
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
        MapId mapId = level.getFreeMapId();
        level.setMapData(mapId, data);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, mapId);
        map.set(DataComponents.ITEM_NAME, Component.literal(name));
        int half = 64 << scale;
        int marked = 0;
        if (Math.abs(v.centre().getX() - cx) <= half && Math.abs(v.centre().getZ() - cz) <= half) {
            MapItemSavedData.addTargetDecoration(map, v.centre(), "heart", MapDecorationTypes.PLAINS_VILLAGE);
        }
        int i = 0;
        for (CaveDwellers.Find x : CaveDwellers.report(v.id())) {
            var type = mark(x.kind());
            if (type == null || Math.abs(x.at().getX() - cx) > half || Math.abs(x.at().getZ() - cz) > half) continue;
            MapItemSavedData.addTargetDecoration(map, x.at(), "caves" + (i++), type);
            marked++;
        }
        map.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore).withStyle(ChatFormatting.GRAY),
            Component.literal(marked + (marked == 1 ? " find" : " finds") + " of the cave team marked").withStyle(ChatFormatting.GRAY))));
        return map;
    }

    /** The mark a find has on the cave map, or null for one that is not marked. */
    @Nullable
    static net.minecraft.core.Holder<MapDecorationType> mark(CaveDwellers.Kind k) {
        return switch (k) {
            case CAVE, RAVINE -> MapDecorationTypes.RED_X;
            case MINESHAFT, STRUCTURE -> MapDecorationTypes.TARGET_POINT;
            case DUNGEON, SPAWNER -> MapDecorationTypes.RED_MARKER;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ the trophy wall

    /** The rare things that go on the trophy wall, by how rare, the rarest first. */
    static final List<Item> RARE = List.of(Items.ENCHANTED_GOLDEN_APPLE, Items.HEART_OF_THE_SEA, Items.TOTEM_OF_UNDYING, Items.ECHO_SHARD,
        Items.DIAMOND_HORSE_ARMOR, Items.DIAMOND, Items.EMERALD, Items.ENCHANTED_BOOK, Items.GOLDEN_APPLE, Items.NAME_TAG, Items.SADDLE,
        Items.RAW_GOLD, Items.AMETHYST_SHARD, Items.OBSIDIAN, Items.LAPIS_LAZULI);

    /** A trophy the team brought up, noted at home (CaveDwellers.putIn): who and from where, kept with the town. */
    public static void broughtUp(UUID village, ItemStack s, String who, String from, long day) {
        Item it = trophyOf(s);
        if (it == null) return;
        Map<String, String> got = trophies(village);
        String key = key(it);
        if (got.containsKey(key)) return;
        got.put(key, who.replace('|', '/') + "|" + from.replace('|', '/') + "|" + day);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : got.entrySet()) sb.append(sb.length() == 0 ? "" : "\n").append(e.getKey()).append('|').append(e.getValue());
        Ledger.note(village, "lodge.trophies", sb.toString());
    }

    /** What a thing is as a trophy (a music disc any disc), or null. */
    @Nullable
    static Item trophyOf(ItemStack s) {
        if (s.isEmpty()) return null;
        if (s.get(DataComponents.JUKEBOX_PLAYABLE) != null) return s.getItem();
        return RARE.contains(s.getItem()) ? s.getItem() : null;
    }

    static String key(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    /** The team's trophies noted: item key to "who|from|day", the order they were brought up in. */
    public static Map<String, String> trophies(UUID village) {
        Map<String, String> out = new LinkedHashMap<>();
        String s = Ledger.note(village, "lodge.trophies");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            int bar = line.indexOf('|');
            if (bar > 0) out.put(line.substring(0, bar), line.substring(bar + 1));
        }
        return out;
    }

    /** One more trophy on the wall, if the team has brought one up that is not there yet and the stores have one to
     *  spare (never the last). True if one went up. */
    static boolean trophy(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity by, long day) {
        UUID id = v.id();
        Map<String, String> got = trophies(id);
        if (got.isEmpty()) return false;
        List<String> shown = new ArrayList<>();
        int free = -1;
        Direction out = b.facing().getClockWise();
        for (int i = 0; i < TROPHIES.length; i++) {
            ItemFrame f = frame(level, at(b, TROPHIES[i]));
            if (f != null && !f.getItem().isEmpty()) shown.add(key(f.getItem().getItem()));
            else if (free < 0) free = i;
        }
        if (free < 0) return false;
        // The rarest not shown yet that the stores can spare one of.
        String pick = null;
        Item item = null;
        for (Item r : RARE) {
            String k = key(r);
            if (got.containsKey(k) && !shown.contains(k) && Crafts.stock(level, v, s -> s.is(r)) >= 2) { pick = k; item = r; break; }
        }
        if (pick == null) {
            for (String k : got.keySet()) {
                if (shown.contains(k)) continue;
                Item it = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(k));
                if (it != Items.AIR && Crafts.stock(level, v, s -> s.is(it)) >= 2) { pick = k; item = it; break; }
            }
        }
        if (pick == null || item == null) return false;
        Item it = item;
        if (!MapRoom.framesToHand(level, v, frame(level, at(b, TROPHIES[free])) == null ? 1 : 0)) {
            want(id, "an item frame for the trophy wall");
            return false;
        }
        ItemStack s = Crafts.takeOne(level, v, x -> x.is(it));
        if (s.isEmpty()) return false;
        ItemFrame f = hang(level, v, at(b, TROPHIES[free]), out);
        if (f == null) {
            Crafts.store(level, v, s);
            return false;
        }
        String[] p = got.get(pick).split("\\|", -1);
        String label = s.getHoverName().getString() + " — brought up by " + p[0] + (p.length > 1 && !p[1].isEmpty() ? " from " + p[1] : "")
            + (p.length > 2 ? ", day " + (Long.parseLong(p[2]) + 1) : "");
        ItemStack shown1 = s.copyWithCount(1);
        shown1.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        f.setItem(shown1, false);
        if (by != null) FolkTalk.speak(by, "Up it goes, on the trophy wall: " + s.getHoverName().getString().toLowerCase(Locale.ROOT) + ".");
        Villages.tell(id, day, "the cave team put " + JobMarket.a(s.getHoverName().getString().toLowerCase(Locale.ROOT))
            + " up on the lodge's trophy wall, brought up by " + p[0]);
        return true;
    }

    // ------------------------------------------------------------------ the log

    /** The team's log written up on a book out of the stores and laid on the lectern, every three days. */
    static boolean log(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity by, long day) {
        UUID id = v.id();
        String last = Ledger.note(id, "lodge.log");
        try {
            if (last != null && !last.isEmpty() && day - Long.parseLong(last) < LOG_EVERY) return false;
        } catch (NumberFormatException ignored) {
            // an unreadable day: written again
        }
        BlockPos p = at(b, LECTERN);
        BlockState st = level.getBlockState(p);
        if (!(st.getBlock() instanceof LecternBlock) || !(level.getBlockEntity(p) instanceof LecternBlockEntity lectern)) return false;
        if (CaveDwellers.report(id).isEmpty()) return false;
        if (!Crafts.take(level, v, s -> s.is(Items.WRITABLE_BOOK) || s.is(Items.BOOK), 1)) {
            want(id, "a book for the team's log");
            return false;
        }
        if (lectern.hasBook()) {
            Crafts.store(level, v, lectern.getBook().copy());                 // the old log, to the stores
            lectern.clearContent();
            LecternBlock.resetBookState(null, level, p, st, false);
            st = level.getBlockState(p);
        }
        String author = by != null ? by.displayNameCap() : "the cave team";
        ItemStack book = Archive.book("The Delvers' Log", author, 0, pages(level, v));
        LecternBlock.tryPlaceBook(by, level, p, st, book);
        Ledger.note(id, "lodge.log", Long.toString(day));
        return true;
    }

    /** The log's pages: the team, the caves and their veins, the hauls (the lines of /village caves, a page a cave). */
    static List<String> pages(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String line : CaveDwellers.page(level, v)) {
            String l = line.trim();
            if (page.length() + l.length() > 220 && page.length() > 0) {
                out.add(page.toString());
                page = new StringBuilder();
            }
            page.append(page.length() == 0 ? "" : "\n").append(l);
        }
        if (page.length() > 0) out.add(page.toString());
        return out;
    }

    // ------------------------------------------------------------------ a map for a player

    /**
     * A player buys a copy of the team's cave map from a cave dweller at the lodge: a real filled map, the whole of
     * the cave country on one sheet, every find marked, drawn on the town's paper; a few coins to the one who draws it.
     */
    public static String sellMap(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "A map of what? There's no town here.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "A map of what? There's no town here.";
        if (f.stationTask() != StationTask.CAVE) return "The cave maps are the cave team's to draw. Ask one of them, at their lodge.";
        int finds = 0;
        for (CaveDwellers.Find x : CaveDwellers.report(village)) if (mark(x.kind()) != null) finds++;
        if (finds == 0) return "We've nothing worth a map yet. Give us a trip or two down there.";
        Ledger.Building b = of(village);
        if (b != null && f.blockPosition().distSqr(at(b, HALL)) > 24 * 24) {
            return "Come and see me at the lodge — the maps are on the wall there, and I'll copy you one.";
        }
        int coins = Market.coinsHeld(p);
        if (coins < MAP_PRICE) return "A copy of the cave map's " + MAP_PRICE + " coins — the paper's the town's and the work's mine. You've " + coins + ".";
        if (!payForSheet(level, v)) return "I'd copy it gladly, but there's not paper enough in the stores. Nine sheets a map.";
        Market.payOut(p, MAP_PRICE);
        f.earn(MAP_PRICE);
        long day = level.getDayTime() / 24000L;
        byte scale = scaleFor(village, 1);
        ItemStack map = sheet(level, v, v.centre().getX(), v.centre().getZ(), scale, "The caves of " + Villages.name(village),
            "Copied by " + f.displayNameCap() + " of the Delvers' Lodge, day " + (day + 1));
        if (!p.getInventory().add(map)) p.drop(map, false);
        f.persona().remember(day, "I copied " + p.getName().getString() + " the cave map", 1);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        LOG.info("[MCA-CAVES] {} sold {} a cave map ({} finds)", f.displayNameCap(), p.getName().getString(), finds);
        return "Here — every cave we've found, marked with a cross; the mineshafts and the dungeons too. Mind the spawners.";
    }

    // ------------------------------------------------------------------ the pictures

    /** The lodge's place on a stage, built from a palette (Showcase), its walls fitted out from what the report
     *  holds (for the pictures: maps, trophies and the log out of nothing, as a showcase's are). The views. */
    static List<String> stage(ServerLevel level, Villages.Village v, BlockPos anchor) {
        com.jrpetty.mcassistant.Showcase.stage(level, anchor.getX() - 8, anchor.getX() + 8, anchor.getZ() - 8, anchor.getZ() + 12, anchor.getY());
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, STRUCTURE, anchor, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.SPRUCE));
        Ledger.Building b = new Ledger.Building(STRUCTURE, anchor, Direction.NORTH);
        long day = level.getDayTime() / 24000L;
        List<ItemStack> sheets = quarters(level, v, "the cave team", day);
        for (int i = 0; i < MAPS.length; i++) {
            BlockPos at = at(b, MAPS[i]);
            if (!hangs(level, at, Direction.SOUTH)) continue;
            ItemFrame f = new ItemFrame(level, at, Direction.SOUTH);
            f.addTag(TAG);
            f.addTag(CaveDwellers.LINEUP);
            level.addFreshEntity(f);
            f.setItem(sheets.get(i), false);
        }
        Item[] shown = { Items.DIAMOND, Items.EMERALD, Items.ENCHANTED_BOOK, Items.GOLDEN_APPLE, Items.RAW_GOLD, Items.AMETHYST_SHARD };
        for (int i = 0; i < TROPHIES.length; i++) {
            BlockPos at = at(b, TROPHIES[i]);
            if (!hangs(level, at, Direction.EAST)) continue;
            ItemFrame f = new ItemFrame(level, at, Direction.EAST);
            f.addTag(TAG);
            f.addTag(CaveDwellers.LINEUP);
            level.addFreshEntity(f);
            f.setItem(new ItemStack(shown[i]), false);
        }
        BlockPos lec = at(b, LECTERN);
        BlockState st = level.getBlockState(lec);
        if (st.getBlock() instanceof LecternBlock && !st.getValue(LecternBlock.HAS_BOOK)) {
            LecternBlock.tryPlaceBook(null, level, lec, st, Archive.book("The Delvers' Log", "the cave team", 0, pages(level, v)));
        }
        int x = anchor.getX(), y = anchor.getY(), z = anchor.getZ();
        // Two of the team at home in their own look (a showcase's, for nothing): one by the trophy wall, one before the door.
        double[][] stand = { { x - 1.5, y, z + 1.5, -90.0F }, { x + 1.5, y, z + 6.5, 20.0F } };
        for (double[] s : stand) {
            VillageFolkEntity show = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (show == null) continue;
            show.moveTo(s[0], s[1], s[2], (float) s[3], 0.0F);
            show.setYHeadRot((float) s[3]);
            show.setYBodyRot((float) s[3]);
            show.makeShowcase(StationTask.CAVE);
            show.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
            show.rename("A cave dweller");
            show.addTag(CaveDwellers.LINEUP);
            level.addFreshEntity(show);
        }
        List<String> out = new ArrayList<>();
        out.add("LODGE " + x + " " + y + " " + z);
        // Its back to the north: the door and the street are to the south.
        out.add("VIEW lodge-front " + (x + 7) + " " + (y + 4) + " " + (z + 12) + " " + x + " " + (y + 2) + " " + z);
        out.add("VIEW lodge-maps " + x + " " + (y + 1) + " " + (z + 2) + " " + x + " " + (y + 1) + " " + (z - 3));
        out.add("VIEW lodge-trophies " + (x + 2) + " " + (y + 1) + " " + (z + 1) + " " + (x - 2) + " " + (y + 1) + " " + (z - 1));
        return out;
    }
}
