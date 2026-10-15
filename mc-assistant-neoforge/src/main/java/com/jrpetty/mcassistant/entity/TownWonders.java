package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [culture-wonders] A town's own wonder: a great building of its own, raised by the whole town over many days.
 *
 * <p>Not the wonders of the world (Wonders: one of each in the world, from the research). These are the town's: the
 * Great Minster (a long nave and a tower with a spire), the Great Belfry (a tower of twenty-two courses and the town's
 * great bell), the Grand Amphitheatre (an open-air house of stone tiers, where the players play once it stands). A
 * town holding the world's Cathedral or Arena does not raise the like of it again.
 *
 * <p><b>Voted.</b> A thriving town in the Iron Age or later (thirty folk, or twenty in the Diamond Age), at peace, with
 * a hundred and fifty coins in its treasury and no great work of the town's under way, puts a wonder to its council once
 * a week, on the council's day: each councillor for it or against by its nature and what the treasury holds, and each
 * for the wonder it likes best (the generous and the elder the Minster, the hardworking the Belfry, the sociable and the
 * players the Amphitheatre). Carried, a quarter of the treasury goes into the wonder fund, the ground is found (a great
 * lot by the square, or a long one out along an avenue, flat and its own: Villages.siteFor, so the gardens and the
 * builders keep off it) and the work begins the next morning. Lost, it waits a fortnight.
 *
 * <p><b>Built in stages.</b> The footings and the ground made level first; then the walls; then the roof; then the
 * tower or the spire; then the glazing, the gilding and the furnishing. Each stage's wages come out of the fund before
 * it starts (the fund is fed by a levy each morning, by the folk's gifts of an evening, and by players: coin or
 * materials at the plaque before the site, or /village wonder give), and are paid to the hands when it is done. The
 * town's crews (one, two or three: Villages.crewsAllowed) of three hands each come to it in their own time and on the
 * first morning of each stage everybody lends a hand till noon; a hand stands on its crew's side of the site and sets a
 * block every few seconds, hoisted up from the ground (nobody climbs the scaffold, so nobody falls off it), out of the
 * stores as it is set: the walls in the stone the stores hold most of, the roof and the posts in a second, the beams of
 * logs, glass, lanterns, gold for the gilding. So many courses a day and no more (the mortar sets overnight), so a
 * Minster takes a fortnight; nobody works up high in the rain, and nobody at all in a storm, at night, under the alarm
 * or in a war; and the work stops while the town is short of food or of homes (they come first). Short of stone the
 * work waits for the miners (whose share is never cut for a glut while it waits: Villages.weighGluts), changing to
 * another stone after three days; short of glass, lanterns or gold for two days it goes on without them, and the town
 * puts them in later (Restore).
 *
 * <p><b>Scaffolding</b> of the stores' fences goes up round it as it rises and comes down at the end, the fences back in
 * the stores. The board and the town's books (the Culture page) show it: the stage, the percent, the days so far, what is
 * still wanted and what the stores hold of it, the fund, the givers.
 *
 * <p><b>Opened.</b> The last block laid, the town gathers before it that evening: the leader names the givers and the
 * best of the hands, the stores' rockets go up, and it is declared open. It stands in the town's register like any
 * building (Restore keeps it true). For good after: twenty renown (Villages.renown: more tourists), the town's pride in it
 * (and more for the week after, the most for those who built it), the neighbours a little warmer each day and an envy
 * among its rivals (Diplomacy), and its own gift: the Minster a better day of rest, the Belfry its bell at dawn and dusk,
 * the Amphitheatre the players' best house. A town taken in war has its wonder pulled about: a standing one loses its
 * top, a rising one its last courses, and half its fund goes on the peace.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class TownWonders extends SavedData {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String ID = "mc_assistant_town_wonders";

    public static final String MINSTER = "minster", BELFRY = "belfry", AMPHITHEATRE = "amphitheatre";
    /** What a standing wonder adds to its town's renown. */
    public static final int RENOWN = 20;
    /** The treasury it takes to put one to the council. */
    static final int TREASURY = 150;
    /** How long a hand takes over a block (ticks). */
    static final int PIECE_TICKS = 100;
    /** How far from the block it is setting a hand may stand (it hoists them up). */
    static final double REACH = 40.0;
    /** The day's work: from after the morning assembly to dusk. */
    static final long FROM = 1400L, TO = 12000L, MUSTER_TO = 6000L;
    /** Hands to a crew. */
    static final int CREW = 3;

    /** The town's own wonders. */
    public enum Kind {
        MINSTER(TownWonders.MINSTER, "the Great Minster", "a great church of stone, its nave and its tower and its spire", "cathedral", 8, 14,
            new String[]{ "the footings and the floor", "the nave's walls", "the roof, and the tower to its ridge", "the tower's belfry and its spire",
                "the glazing, the gilding and the furnishing" },
            "a better day of rest for everybody, the service in the minster"),
        BELFRY(TownWonders.BELFRY, "the Great Belfry", "a tower of twenty-two courses, its clock and its great bell", "", 11, 22,
            new String[]{ "the footings", "the tower's lower courses", "the upper tower and the bell chamber", "the spire",
                "the glazing, the gilding, the ladder and the bell" },
            "its great bell rung over the town at dawn and dusk"),
        AMPHITHEATRE(TownWonders.AMPHITHEATRE, "the Grand Amphitheatre", "an open-air house of stone tiers and a stage", "arena", 2, 3,
            new String[]{ "the footings and the floor", "the tiers", "the stage's wall and the arch", "the frieze and the lamps",
                "the gilding and the lamps" },
            "the players' best house: every play there in fine weather");

        public final String structure, name, words, worldTwin;
        final int wallsTop, roofTop;
        final String[] stages;
        public final String gift;

        Kind(String structure, String name, String words, String worldTwin, int wallsTop, int roofTop, String[] stages, String gift) {
            this.structure = structure;
            this.name = name;
            this.words = words;
            this.worldTwin = worldTwin;
            this.wallsTop = wallsTop;
            this.roofTop = roofTop;
            this.stages = stages;
            this.gift = gift;
        }

        public String title() {
            return Culture.capital(name);
        }

        @Nullable
        public static Kind of(@Nullable String s) {
            if (s == null) return null;
            for (Kind k : values()) if (k.structure.equals(s) || k.name().equalsIgnoreCase(s)) return k;
            return null;
        }
    }

    /** Is this the drawing of a town's own wonder? */
    public static boolean isWonder(@Nullable String structure) {
        return Kind.of(structure) != null;
    }

    // ------------------------------------------------------------------ the world's book

    private CompoundTag towns = new CompoundTag();
    @Nullable private static TownWonders loose;

    public TownWonders() {}

    static TownWonders book() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new TownWonders();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(TownWonders::new, TownWonders::load, null), ID);
    }

    public static TownWonders load(CompoundTag tag, HolderLookup.Provider registries) {
        TownWonders w = new TownWonders();
        w.towns = tag.getCompound("towns");
        return w;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("towns", towns.copy());
        return tag;
    }

    static void changed() {
        book().setDirty();
    }

    /** A town's page: its wonder under way ("work"), its wonders raised ("done"), its votes. */
    static CompoundTag town(UUID village) {
        CompoundTag all = book().towns;
        String k = village.toString();
        if (!all.contains(k, Tag.TAG_COMPOUND)) all.put(k, new CompoundTag());
        return all.getCompound(k);
    }

    /** The wonder under way in a town (rising, or risen and waiting to be opened), or null. */
    @Nullable
    static CompoundTag current(UUID village) {
        CompoundTag t = town(village);
        return t.contains("work", Tag.TAG_COMPOUND) ? t.getCompound("work") : null;
    }

    /** Is a wonder rising in this town? */
    public static boolean underWay(UUID village) {
        return current(village) != null;
    }

    static ListTag done(UUID village) {
        CompoundTag t = town(village);
        if (!t.contains("done", Tag.TAG_LIST)) t.put("done", new ListTag());
        return t.getList("done", Tag.TAG_COMPOUND);
    }

    /** A wonder of this kind standing in the town (raised and in its register), or null. */
    @Nullable
    public static Ledger.Building standing(UUID village, String structure) {
        return Culture.building(village, structure);
    }

    /** How many wonders of its own the town has raised and still stand. */
    public static int count(@Nullable UUID village) {
        if (village == null) return 0;
        int n = 0;
        for (Kind k : Kind.values()) if (standing(village, k.structure) != null) n++;
        return n;
    }

    /** Its renown from its own wonders (Villages.renown). */
    public static int renown(@Nullable UUID village) {
        return RENOWN * count(village);
    }

    private static final Map<UUID, List<Cell>> CELLS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RESTORED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> CREWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_SET = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SET_OUT = new ConcurrentHashMap<>();
    private record Held(String doing, int tick, long at) {}
    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CELLS.clear();
        RESTORED.clear();
        LOOKED.clear();
        CREWS.clear();
        LAST_SET.clear();
        SET_OUT.clear();
        HELD.clear();
        TownWonders b = book();
        b.towns = new CompoundTag();
        b.setDirty();
    }

    // ------------------------------------------------------------------ the drawing as it is built

    /** One block of the wonder, in the order it is laid: where, what, its stage and its side of the site. */
    record Cell(BuildGoal.Placement p, int stage, int side, int h) {}

    static Ledger.Building site(CompoundTag w) {
        Direction facing = Direction.from2DDataValue(w.getInt("facing"));
        return new Ledger.Building(w.getString("structure"), BlockPos.of(w.getLong("site")), facing);
    }

    static boolean finishing(BuildGoal.Placement p) {
        return switch (p.part()) {
            case LANTERN, TORCH, CARPET, BELL, LECTERN, DOOR, LADDER, WINDOW, FLOWER -> true;
            case BLOCK -> p.style() == Blueprints.Style.GLASS || p.style() == Blueprints.Style.BRICK || p.style() == Blueprints.Style.PANE;
            default -> false;
        };
    }

    /** The wonder's blocks in the order they go up: stage by stage, course by course. */
    static List<Cell> cells(UUID village, CompoundTag w) {
        return CELLS.computeIfAbsent(village, k -> {
            Kind kind = Kind.of(w.getString("structure"));
            Ledger.Building b = site(w);
            int y0 = b.anchor().getY();
            List<Cell> out = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                int h = p.pos().getY() - y0;
                int stage = h < 0 ? 0 : finishing(p) ? 4 : kind == null ? 1 : h <= kind.wallsTop ? 1 : h <= kind.roofTop ? 2 : 3;
                int dx = p.pos().getX() - b.anchor().getX(), dz = p.pos().getZ() - b.anchor().getZ();
                int side = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? 1 : 3) : (dz >= 0 ? 2 : 0);
                out.add(new Cell(p, stage, side, h));
            }
            out.sort(Comparator.comparingInt(Cell::stage).thenComparingInt(Cell::h)
                .thenComparingInt(c -> c.p().pos().getZ()).thenComparingInt(c -> c.p().pos().getX()));
            return List.copyOf(out);
        });
    }

    static BitSet laid(CompoundTag w) {
        return BitSet.valueOf(w.getLongArray("laid"));
    }

    static void laid(CompoundTag w, BitSet b) {
        w.put("laid", new LongArrayTag(b.toLongArray()));
    }

    /** How far on: {laid, of}. */
    static int[] progress(UUID village, CompoundTag w) {
        return new int[]{ laid(w).cardinality(), cells(village, w).size() };
    }

    static int percent(UUID village, CompoundTag w) {
        int[] p = progress(village, w);
        return p[1] == 0 ? 100 : Math.min(100, 100 * p[0] / p[1]);
    }

    /** The blocks of a stage, and how many of them are laid. */
    static int[] stageProgress(UUID village, CompoundTag w, int stage) {
        BitSet laid = laid(w);
        List<Cell> cells = cells(village, w);
        int n = 0, done = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).stage() != stage) continue;
            n++;
            if (laid.get(i)) done++;
        }
        return new int[]{ done, n };
    }

    /** A stage's wages: a coin a dozen blocks, ten at least. */
    static int wages(UUID village, CompoundTag w, int stage) {
        return Math.max(10, stageProgress(village, w, stage)[1] / 12);
    }

    /** So many blocks a day: the whole in twelve days or so, sixty to two hundred a day. */
    static int quota(UUID village, CompoundTag w) {
        return Math.max(60, Math.min(200, cells(village, w).size() / 12));
    }

    // ------------------------------------------------------------------ what it is made of

    /** What a block of the wonder is paid for with. */
    enum Mat {
        STONE("stone"), STONE2("dressed stone for the roof and posts"), LOG("logs"), PLANKS("planks"), GLASS("glass"),
        PANE("glass panes"), LANTERN("lanterns"), TORCH("torches"), CARPET("carpet"), DOOR("a door"), BELL("a bell"),
        LECTERN("a lectern"), LADDER("ladders"), FENCE("fences"), GOLD("gold"), FLOWER("flowers");

        final String words;

        Mat(String words) { this.words = words; }

        /** Gone without after two days' waiting (the glazing, the lamps, the bell put in later), not waited on for good. */
        boolean spared() {
            return this != STONE && this != STONE2 && this != LOG && this != PLANKS && this != GOLD;
        }
    }

    static Mat mat(BuildGoal.Placement p) {
        return switch (p.part()) {
            case BLOCK -> switch (p.style()) {
                case POST, ROOF_STAIR, ROOF_STAIR_TOP, ROOF_SLAB, ROOF_SLAB_TOP, ROOF_BLOCK -> Mat.STONE2;
                case BEAM_ACROSS, BEAM_ALONG -> Mat.LOG;
                case FLOOR, WALL -> Mat.PLANKS;
                case GLASS -> Mat.GLASS;
                case PANE -> Mat.PANE;
                case BRICK -> Mat.GOLD;
                default -> Mat.STONE;
            };
            case WINDOW -> p.style() == Blueprints.Style.GLASS ? Mat.GLASS : Mat.PANE;
            case LANTERN -> Mat.LANTERN;
            case TORCH -> Mat.TORCH;
            case CARPET -> Mat.CARPET;
            case DOOR -> Mat.DOOR;
            case BELL -> Mat.BELL;
            case LECTERN -> Mat.LECTERN;
            case LADDER -> Mat.LADDER;
            case FENCE, GATE -> Mat.FENCE;
            case FLOWER -> Mat.FLOWER;
            default -> Mat.STONE;
        };
    }

    /** Halves of a block of stone a piece costs: a slab one, a block two, a stair three. */
    static int halves(BuildGoal.Placement p) {
        return switch (p.style()) {
            case STONE_SLAB, ROOF_SLAB, ROOF_SLAB_TOP -> 1;
            case STONE_STAIR, ROOF_STAIR, ROOF_STAIR_TOP -> 3;
            default -> 2;
        };
    }

    @Nullable
    static WorksPlans.Family family(CompoundTag w, String key) {
        return WorksPlans.Family.named(w.getString(key));
    }

    /** The stone the stores hold most of (dressed first, at a tie), not the harbour's prismarine; {@code not}: one other than it. */
    @Nullable
    static WorksPlans.Family mostStone(ServerLevel level, UUID village, @Nullable WorksPlans.Family not) {
        WorksPlans.Family best = null;
        int most = 0;
        for (WorksPlans.Family f : WorksPlans.Family.values()) {
            if (f == WorksPlans.Family.PRISMARINE || f == not) continue;
            int have = Market.stock(level, village, f.payment());
            if (have > most) { most = have; best = f; }
        }
        return best;
    }

    /** One of the stores' things of this tag taken, and the block it is (an oak door for an oak door); null if none. */
    @Nullable
    static Block takeTagged(ServerLevel level, Villages.Village v, TagKey<Item> tag) {
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            Item it = h.value();
            if (Market.stock(level, v.id(), s -> s.is(it)) <= 0) continue;
            Block b = Block.byItem(it);
            if (b == Blocks.AIR) continue;
            if (TownWork.take(level, v, s -> s.is(it), 1)) return b;
        }
        return null;
    }

    /** Stone out of the stores in halves (a block is two), kept on account between pieces. */
    private static boolean payStone(ServerLevel level, Villages.Village v, CompoundTag w, String famKey, int halves) {
        WorksPlans.Family f = family(w, famKey);
        if (f == null) return false;
        String credit = "credit." + famKey;
        int c = w.getInt(credit);
        while (c < halves) {
            if (!TownWork.take(level, v, f.payment(), 1)) {
                w.putInt(credit, c);
                return false;
            }
            c += 2;
        }
        w.putInt(credit, c - halves);
        return true;
    }

    /** What goes in a block's place, paid for out of the stores now; null when the stores cannot pay for it. */
    @Nullable
    static BlockState pay(ServerLevel level, Villages.Village v, CompoundTag w, BuildGoal.Placement p, Mat m) {
        WorksPlans.Family fam = family(w, "family"), fam2 = family(w, "family2");
        if (fam2 == null) fam2 = fam;
        return switch (m) {
            case STONE -> payStone(level, v, w, "family", halves(p)) && fam != null ? switch (p.style()) {
                case STONE_SLAB -> fam.slab.defaultBlockState();
                case STONE_STAIR -> fam.stairs.defaultBlockState();
                default -> fam.block.defaultBlockState();
            } : null;
            case STONE2 -> payStone(level, v, w, w.getString("family2").isEmpty() ? "family" : "family2", halves(p)) && fam2 != null ? switch (p.style()) {
                case ROOF_STAIR, ROOF_STAIR_TOP -> fam2.stairs.defaultBlockState();
                case ROOF_SLAB, ROOF_SLAB_TOP -> fam2.slab.defaultBlockState();
                default -> fam2.block.defaultBlockState();
            } : null;
            case LOG -> state(takeTagged(level, v, ItemTags.LOGS));
            case PLANKS -> state(takeTagged(level, v, ItemTags.PLANKS));
            case GLASS -> TownWork.take(level, v, s -> s.is(Items.GLASS), 1) ? Blocks.GLASS.defaultBlockState() : null;
            case PANE -> TownWork.take(level, v, s -> s.is(Items.GLASS_PANE), 1) || TownWork.take(level, v, s -> s.is(Items.GLASS), 1)
                ? Blocks.GLASS_PANE.defaultBlockState() : null;
            case LANTERN -> TownWork.take(level, v, s -> s.is(Items.LANTERN), 1) ? Blocks.LANTERN.defaultBlockState()
                : TownWork.take(level, v, s -> s.is(Items.SOUL_LANTERN), 1) ? Blocks.SOUL_LANTERN.defaultBlockState() : null;
            case TORCH -> TownWork.take(level, v, s -> s.is(Items.TORCH), 1) ? Blocks.TORCH.defaultBlockState() : null;
            case CARPET -> state(takeTagged(level, v, ItemTags.WOOL_CARPETS));
            case DOOR -> state(takeTagged(level, v, ItemTags.WOODEN_DOORS));
            case BELL -> TownWork.take(level, v, s -> s.is(Items.BELL), 1) ? Blocks.BELL.defaultBlockState() : null;
            case LECTERN -> TownWork.take(level, v, s -> s.is(Items.LECTERN), 1) ? Blocks.LECTERN.defaultBlockState() : null;
            case LADDER -> TownWork.take(level, v, s -> s.is(Items.LADDER), 1) || TownWork.take(level, v, s -> s.is(Items.STICK), 3)
                ? Blocks.LADDER.defaultBlockState() : null;
            case FENCE -> state(takeTagged(level, v, ItemTags.WOODEN_FENCES));
            case FLOWER -> state(takeTagged(level, v, ItemTags.SMALL_FLOWERS));
            case GOLD -> TownWork.take(level, v, s -> s.is(Items.GOLD_BLOCK), 1) || TownWork.take(level, v, s -> s.is(Items.GOLD_INGOT), 9)
                ? Blocks.GOLD_BLOCK.defaultBlockState() : null;
        };
    }

    @Nullable
    private static BlockState state(@Nullable Block b) {
        return b == null ? null : b.defaultBlockState();
    }

    /**
     * Is the wonder rising short of stone (waiting on it, or wanting more than the stores hold)? The elder then orders
     * the town to dig (Orders: the quarrying the wonder wants).
     */
    public static boolean wantsStone(ServerLevel level, UUID village) {
        CompoundTag w = current(village);
        if (w == null || !w.getString("state").equals("building")) return false;
        String waiting = w.getString("waitingFor");
        if (waiting.equals(Mat.STONE.name()) || waiting.equals(Mat.STONE2.name())) return true;
        return wanted(village, w).getOrDefault(Mat.STONE, 0) > stock(level, village, w, Mat.STONE);
    }

    /** What the stores hold toward a material. */
    static int stock(ServerLevel level, UUID village, CompoundTag w, Mat m) {
        Predicate<ItemStack> what = switch (m) {
            case STONE -> family(w, "family") == null ? s -> false : family(w, "family").payment();
            case STONE2 -> family(w, "family2") == null ? (family(w, "family") == null ? s -> false : family(w, "family").payment())
                : family(w, "family2").payment();
            case LOG -> s -> s.is(ItemTags.LOGS);
            case PLANKS -> s -> s.is(ItemTags.PLANKS);
            case GLASS -> s -> s.is(Items.GLASS);
            case PANE -> s -> s.is(Items.GLASS_PANE) || s.is(Items.GLASS);
            case LANTERN -> s -> s.is(Items.LANTERN) || s.is(Items.SOUL_LANTERN);
            case TORCH -> s -> s.is(Items.TORCH);
            case CARPET -> s -> s.is(ItemTags.WOOL_CARPETS);
            case DOOR -> s -> s.is(ItemTags.WOODEN_DOORS);
            case BELL -> s -> s.is(Items.BELL);
            case LECTERN -> s -> s.is(Items.LECTERN);
            case LADDER -> s -> s.is(Items.LADDER);
            case FENCE -> s -> s.is(ItemTags.WOODEN_FENCES);
            case FLOWER -> s -> s.is(ItemTags.SMALL_FLOWERS);
            case GOLD -> s -> s.is(Items.GOLD_INGOT);
        };
        return Market.stock(level, village, what);
    }

    /** What is still wanted to finish it, by material (stone in blocks), in the order of the drawing. */
    static Map<Mat, Integer> wanted(UUID village, CompoundTag w) {
        Map<Mat, Integer> out = new EnumMap<>(Mat.class);
        BitSet laid = laid(w);
        List<Cell> cells = cells(village, w);
        Map<Mat, Integer> halves = new EnumMap<>(Mat.class);
        for (int i = 0; i < cells.size(); i++) {
            if (laid.get(i)) continue;
            Mat m = mat(cells.get(i).p());
            if (m == Mat.STONE || m == Mat.STONE2) halves.merge(m, halves(cells.get(i).p()), Integer::sum);
            else out.merge(m, 1, Integer::sum);
        }
        for (Map.Entry<Mat, Integer> h : halves.entrySet()) out.put(h.getKey(), (h.getValue() + 1) / 2);
        return out;
    }

    static String matWords(CompoundTag w, Mat m) {
        WorksPlans.Family f = m == Mat.STONE ? family(w, "family") : m == Mat.STONE2 ? family(w, "family2") : null;
        if (m == Mat.STONE2 && f == null) f = family(w, "family");
        if (f != null) return f.words + (f == WorksPlans.Family.BRICKS ? "s" : "");
        return m == Mat.GOLD ? "gold ingots" : m.words;
    }

    /** "640 stone bricks (the stores hold 210), 40 glass panes (12)". */
    static String wantedWords(ServerLevel level, UUID village, CompoundTag w, int most) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Mat, Integer> e : wanted(village, w).entrySet()) {
            if (out.size() >= most) break;
            int n = e.getKey() == Mat.GOLD ? e.getValue() * 9 : e.getValue();
            out.add(n + " " + matWords(w, e.getKey()) + " (the stores hold " + stock(level, village, w, e.getKey()) + ")");
        }
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ the town's look, every five seconds

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 41) return;
        com.jrpetty.mcassistant.Guard.run("the town's wonder", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        CompoundTag w = current(id);
        if (w != null) restore(id, w);
        if (t >= 1000L && t < 11000L && LOOKED.getOrDefault(id, -1L) != day) {
            LOOKED.put(id, day);
            morning(level, v, day);
            w = current(id);
        }
        if (w != null) {
            String state = w.getString("state");
            if (state.equals("building")) {
                scaffold(level, v, w);
                plaque(level, v, w, false);
                if (laid(w).cardinality() >= cells(id, w).size()) finished(level, v, w, day);
            } else if (state.equals("opening") && day > w.getLong("openOn") + 1) {
                opened(level, v);                                            // nobody gathered (rain, a raid): opened quietly
            }
        }
        // The Belfry's gift: its great bell over the town at dawn and dusk.
        Ledger.Building belfry = standing(id, BELFRY);
        if (belfry != null && (t >= 23900L || t < 100L || t >= 11900L && t < 12000L)) {
            BlockPos bell = Culture.at(belfry, 0, 18, 0);
            if (level.isLoaded(bell)) level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0F, 0.6F);
        }
    }

    /** After a restart: its ground spoken for again, as it was (Villages.siteFor is memory). */
    static void restore(UUID village, CompoundTag w) {
        if (RESTORED.containsKey(village) || !w.contains("site")) return;
        RESTORED.put(village, 1L);
        Ledger.Building b = site(w);
        if (!Villages.sitesOf(village).containsKey(b.structure())) {
            Villages.holdPrivateSite(village, b.structure(), new Villages.Site(b.anchor(), b.facing(), 0));
        }
    }

    // ------------------------------------------------------------------ the morning: the vote, the levy, the stages

    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag w = current(id);
        if (w == null) {
            if (day % 7 == 3) consider(level, v, day, false);
            return;
        }
        if (!w.getString("state").equals("building")) return;
        levy(level, v, w, day);
        folkGive(level, v, w, day);
        String why = paused(level, id);
        String was = w.getString("paused");
        if (!why.equals(was)) {
            w.putString("paused", why);
            Villages.tell(id, day, why.isEmpty() ? "work on " + w.getString("title") + " went on again"
                : "work on " + w.getString("title") + " was stopped: " + why);
        }
        beginStage(level, v, w, day);
        makeDo(level, v, w, day);
        changed();
    }

    /** Why the work stands still today, or "". */
    static String paused(ServerLevel level, UUID village) {
        if (Wars.footing(village) == Wars.Footing.WAR) return "the town is at war, and every hand is wanted for it";
        Leader.Plan plan = Leader.plan(village);
        if (plan == Leader.Plan.SHORT || plan == Leader.Plan.FAMINE) return "the town is short of food, and the fields come first";
        if (Villages.headcount(village) - Villages.housing(village) > 4) return "folk are sleeping without beds, and the homes come first";
        return "";
    }

    /** Is the town fit to put a wonder to its council at all? Why not, or "". */
    static String whyNot(ServerLevel level, UUID village) {
        Villages.Age age = Villages.ageOf(village);
        int folk = Villages.headcount(village);
        if (age.ordinal() < Villages.Age.IRON.ordinal()) return "it wants the Iron Age";
        if (folk < (age.ordinal() >= Villages.Age.DIAMOND.ordinal() ? 20 : 30)) return "it wants thirty folk (twenty in the Diamond Age)";
        if (!Villages.thriving(village)) return "it wants a thriving town: food to spare and a bed for everybody";
        if (Ledger.coins(village) < TREASURY) return "it wants " + TREASURY + " coins in the treasury (" + Ledger.coins(village) + " now)";
        if (Wars.footing(village) != Wars.Footing.PEACE) return "not in a time of war";
        if (BigWorks.underWay(village)) return "not while the town's great work is under way";
        if (current(village) != null) return "one wonder at a time";
        if (choices(village).isEmpty()) return "it has raised every wonder it could";
        return "";
    }

    /** The wonders it could raise: not one it has, nor the like of a wonder of the world it holds. */
    static List<Kind> choices(UUID village) {
        List<Kind> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            if (standing(village, k.structure) != null || Villages.hasBuilt(village, k.structure)) continue;
            boolean twin = false;
            for (Wonders.Wonder w : Wonders.held(village)) if (w.structure.equals(k.worldTwin)) twin = true;
            if (!twin) out.add(k);
        }
        return out;
    }

    /** Which wonder a councillor would have. */
    static Kind liking(VillageFolkEntity f, List<Kind> choices) {
        Map<Kind, Integer> score = new LinkedHashMap<>();
        for (Kind k : choices) {
            int s = Math.floorMod(f.getUUID().hashCode() + k.ordinal() * 7, 5);
            switch (k) {
                case MINSTER -> s += (f.life().has(Social.Trait.GENEROUS) ? 4 : 0) + (f.isElder() ? 3 : 0);
                case BELFRY -> s += (f.life().has(Social.Trait.HARDWORKING) ? 4 : 0) + (f.stationTask() == AssistantEntity.StationTask.GUARD ? 2 : 0);
                case AMPHITHEATRE -> s += (f.life().has(Social.Trait.SOCIABLE) ? 3 : 0) + (f.life().has(Social.Trait.CHEERFUL) ? 2 : 0)
                    + (Theatre.player(f) ? 4 : 0);
            }
            score.put(k, s);
        }
        Kind best = choices.get(0);
        for (Map.Entry<Kind, Integer> e : score.entrySet()) if (e.getValue() > score.get(best)) best = e.getKey();
        return best;
    }

    /**
     * Put to the council (its day of the week, or now by decree): for or against a wonder, and which. Carried, it is
     * begun; lost, it waits a fortnight. Returns what happened, in words.
     */
    static String consider(ServerLevel level, Villages.Village v, long day, boolean decree) {
        UUID id = v.id();
        String no = whyNot(level, id);
        if (!no.isEmpty() && !(decree && current(id) == null && !choices(id).isEmpty())) return "no wonder: " + no;
        CompoundTag t = town(id);
        if (!decree && t.getLong("waitUntil") > day) return "no wonder: the council waits till day " + (t.getLong("waitUntil") + 1);
        List<Kind> choices = choices(id);
        List<VillageFolkEntity> council = Council.members(id);
        int ayes = 0, nays = 0;
        Map<Kind, Integer> tally = new LinkedHashMap<>();
        int coins = Ledger.coins(id);
        for (VillageFolkEntity m : council) {
            int s = 1 + Math.min(3, coins / 200) + Math.floorMod(m.getUUID().hashCode() + (int) day, 3) - 1;
            if (m.life().has(Social.Trait.GRUMPY)) s -= 2;
            if (m.life().has(Social.Trait.CURIOUS) || m.life().has(Social.Trait.GENEROUS)) s += 1;
            if (m.isElder()) s += 1;
            if (s > 0 || decree) ayes++; else nays++;
            tally.merge(liking(m, choices), 1, Integer::sum);
        }
        if (council.isEmpty()) ayes = 1;
        Kind kind = choices.get(0);
        for (Map.Entry<Kind, Integer> e : tally.entrySet()) if (e.getValue() > tally.getOrDefault(kind, 0)) kind = e.getKey();
        if (ayes <= nays) {
            t.putLong("waitUntil", day + 14);
            changed();
            String line = "the council voted " + ayes + " to " + nays + " against raising a wonder; the question waits a fortnight";
            Villages.tell(id, day, line);
            return line;
        }
        String line = (decree ? "by decree, " : "") + "the council voted " + ayes + " to " + nays + " to raise " + kind.name + ", the town's own wonder: "
            + kind.words;
        Villages.tell(id, day, line);
        String started = start(level, v, kind, day, ayes + " to " + nays);
        return line + "; " + started;
    }

    /**
     * Begun: the ground found and spoken for, a quarter of the treasury into the fund, the stone chosen, the plaque
     * and the first stage the next morning. Returns what happened.
     */
    static String start(ServerLevel level, Villages.Village v, Kind kind, long day, String vote) {
        UUID id = v.id();
        Villages.Site site = Villages.siteFor(level, id, kind.structure);
        if (site == null) {
            Villages.tell(id, day, "no ground great enough for " + kind.name + " could be found yet; the council will look again");
            town(id).putLong("waitUntil", day + 3);
            changed();
            return "no ground yet";
        }
        CompoundTag w = new CompoundTag();
        w.putString("structure", kind.structure);
        w.putString("title", kind.name);
        w.putString("vote", vote);
        w.putLong("site", site.anchor().asLong());
        w.putInt("facing", site.facing().get2DDataValue());
        w.putString("state", "building");
        w.putLong("started", day);
        w.putInt("stage", -1);
        w.putLong("stageFrom", day + 1);
        WorksPlans.Family first = mostStone(level, id, null);
        if (first == null) first = WorksPlans.Family.COBBLESTONE;
        WorksPlans.Family second = mostStone(level, id, first);
        w.putString("family", first.name());
        w.putString("family2", second == null ? first.name() : second.name());
        int seed = Ledger.takeCoins(id, Ledger.coins(id) / 4);
        w.putInt("fund", seed);
        w.put("donors", new CompoundTag());
        w.put("gifts", new CompoundTag());
        w.put("hands", new CompoundTag());
        town(id).put("work", w);
        CELLS.remove(id);
        RESTORED.put(id, 1L);
        changed();
        Villages.tell(id, day, "ground was marked out for " + kind.name + "; " + seed + " coins went from the treasury into the wonder fund, and "
            + "the work begins tomorrow in " + first.words + (second != null && second != first ? " and " + second.words : ""));
        Market.assemblyNews(id, "We raise " + kind.name + "! Work starts tomorrow — every hand that can, till the noon bell.");
        LOG.info("[MCA-WONDER] {}: {} begun at {} ({} cells)", Villages.name(id), kind.name, site.anchor(), cells(id, w).size());
        return kind.name + " begun at " + site.anchor().toShortString();
    }

    /** The morning's levy: so much of the treasury into the fund as the coming stage still wants (a twentieth at most). */
    static void levy(ServerLevel level, Villages.Village v, CompoundTag w, long day) {
        UUID id = v.id();
        int stage = Math.max(0, nextStage(id, w));
        if (stage > 4) return;
        int want = wages(id, w, stage) - w.getInt("fund");
        if (want <= 0) return;
        int n = Math.min(want, Math.max(2, Ledger.coins(id) / 20));
        int got = Ledger.takeCoins(id, n);
        if (got <= 0) return;
        w.putInt("fund", w.getInt("fund") + got);
        w.putInt("levied", w.getInt("levied") + got);
        if (day % 3 == 0) Villages.tell(id, day, "the levy put " + got + " coins from the treasury into the wonder fund");
    }

    /** Of an evening's wages: folk with savings give a little, the generous the most; ten coins a day at most. */
    static void folkGive(ServerLevel level, Villages.Village v, CompoundTag w, long day) {
        int given = 0;
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            if (given >= 10) break;
            boolean generous = f.life().has(Social.Trait.GENEROUS);
            int keep = generous ? 20 : 40;
            if (f.purse() < keep + 1 || !generous && f.persona().mood() < 60) continue;
            if (Math.floorMod(f.getUUID().hashCode() + day, generous ? 2 : 4) != 0) continue;
            int n = Math.min(generous ? 3 : 1, f.purse() - keep);
            if (n <= 0 || !f.spend(n)) continue;
            give(w, f.displayNameCap(), n);
            given += n;
        }
    }

    static void give(CompoundTag w, String who, int coins) {
        w.putInt("fund", w.getInt("fund") + coins);
        CompoundTag donors = w.getCompound("donors");
        donors.putInt(who, donors.getInt(who) + coins);
        w.put("donors", donors);
        changed();
    }

    /** The stage not yet finished, the first of them; 5 when all are. */
    static int nextStage(UUID village, CompoundTag w) {
        for (int s = 0; s <= 4; s++) {
            int[] p = stageProgress(village, w, s);
            if (p[0] < p[1]) return s;
        }
        return 5;
    }

    /**
     * The morning a stage is due: the last one's hands paid, the next one's wages out of the fund (or the work waits for
     * the fund), and the first morning of it a works morning for everybody.
     */
    static void beginStage(ServerLevel level, Villages.Village v, CompoundTag w, long day) {
        UUID id = v.id();
        int cur = w.getInt("stage");
        int next = nextStage(id, w);
        if (cur >= 0 && next == cur) return;                                  // still at it
        if (cur >= 0 && next != cur) {
            payHands(level, v, w, cur, day);
            Kind kind = Kind.of(w.getString("structure"));
            Villages.tell(id, day, w.getString("title") + ": " + (kind == null ? "a stage" : kind.stages[Math.min(cur, 4)]) + " done, day "
                + (day - w.getLong("started") + 1) + " of the work, " + percent(id, w) + "% in all");
            w.putInt("stage", -1);
            w.putLong("stageFrom", day + 1);
            return;
        }
        if (next > 4 || day < w.getLong("stageFrom")) return;
        int wages = wages(id, w, next);
        if (w.getInt("fund") < wages) {
            w.putString("waiting", "the fund: " + w.getInt("fund") + " of " + wages + " coins for the hands' wages");
            return;
        }
        w.putInt("fund", w.getInt("fund") - wages);
        w.putInt("purse", wages);
        w.putInt("stage", next);
        w.putLong("muster", day);
        w.putString("waiting", "");
        w.put("stageHands", new CompoundTag());
        Kind kind = Kind.of(w.getString("structure"));
        String what = kind == null ? "the next stage" : kind.stages[next];
        Villages.tell(id, day, "work on " + w.getString("title") + "'s " + what.replaceFirst("^the ", "") + " began, " + wages + " coins of wages set by");
        Market.assemblyNews(id, "Today we start on " + what + " of " + w.getString("title") + ". Everybody who can, lend a hand till noon!");
    }

    /** A stage done: its wages shared among its hands by what each laid. */
    static void payHands(ServerLevel level, Villages.Village v, CompoundTag w, int stage, long day) {
        int purse = w.getInt("purse");
        CompoundTag hands = w.getCompound("stageHands");
        int total = 0;
        for (String k : hands.getAllKeys()) total += hands.getInt(k);
        int paid = 0;
        if (total > 0) {
            for (String k : hands.getAllKeys()) {
                int n = purse * hands.getInt(k) / total;
                UUID u = WarBooks.id(k);
                if (n <= 0 || u == null || !(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
                f.earn(n);
                paid += n;
            }
        }
        if (purse - paid > 0) w.putInt("fund", w.getInt("fund") + purse - paid);    // what nobody earned goes back
        w.putInt("purse", 0);
    }

    /**
     * Waiting too long on what the stores will not give: a stone the stores have stopped holding is changed for the one
     * they hold most of (after three days); the gilding is done in stone (after two).
     */
    static void makeDo(ServerLevel level, Villages.Village v, CompoundTag w, long day) {
        String waiting = w.getString("waitingFor");
        if (waiting.isEmpty()) {
            w.remove("waitingSince");
            return;
        }
        if (!w.contains("waitingSince")) {
            w.putLong("waitingSince", day);
            return;
        }
        long waited = day - w.getLong("waitingSince");
        Mat m;
        try {
            m = Mat.valueOf(waiting);
        } catch (IllegalArgumentException e) {
            return;
        }
        if ((m == Mat.STONE || m == Mat.STONE2) && waited >= 3) {
            String key = m == Mat.STONE ? "family" : "family2";
            WorksPlans.Family was = family(w, key);
            WorksPlans.Family now = mostStone(level, v.id(), was);
            if (now != null && Market.stock(level, v.id(), now.payment()) >= 16) {
                w.putString(key, now.name());
                w.putInt("credit." + key, 0);
                Villages.tell(v.id(), day, "short of " + (was == null ? "stone" : was.words) + ", " + w.getString("title") + " goes on in " + now.words);
                w.putString("waitingFor", "");
                w.remove("waitingSince");
            }
        } else if (waited >= 2) {
            w.putBoolean("makeDo." + m.name(), true);
            w.putString("waitingFor", "");
            w.remove("waitingSince");
            Villages.tell(v.id(), day, w.getString("title") + " goes on without " + m.words + " for now"
                + (m == Mat.GOLD ? ": the gilding in stone" : ": it can be put in later"));
        }
    }

    // ------------------------------------------------------------------ laying a block

    /** The ground on the site not yet right: a block to dig out, or a hole under the footing to fill; null if it is all level. */
    @Nullable
    static BlockPos groundwork(ServerLevel level, CompoundTag w, boolean[] dig) {
        Ledger.Building b = site(w);
        String s = b.structure();
        int[] g = Blueprints.groundHalf(s);
        int[] full = Blueprints.fullHalf(s);
        java.util.Set<Long> mine = new java.util.HashSet<>();
        for (BuildGoal.Placement p : BuildGoal.plan(s, b.anchor(), b.facing(), 13)) mine.add(p.pos().asLong());
        for (int dz = -full[1]; dz <= full[1]; dz++) {
            for (int dx = -full[0]; dx <= full[0]; dx++) {
                for (int h = 0; h <= 6; h++) {
                    BlockPos p = Culture.at(b, dx, h, dz);
                    if (!level.isLoaded(p) || mine.contains(p.asLong())) continue;
                    BlockState st = level.getBlockState(p);
                    if (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) continue;
                    if (!Restore.natural(st) && !BuildGoal.isWildPlant(st)) continue;    // the town's own, left be
                    dig[0] = true;
                    return p;
                }
                if (Math.abs(dx) > g[0] || Math.abs(dz) > g[1]) continue;
                BlockPos under = Culture.at(b, dx, -2, dz);
                if (level.isLoaded(under) && (level.getBlockState(under).isAir() || !level.getFluidState(under).isEmpty())) {
                    dig[0] = false;
                    return under;
                }
            }
        }
        return null;
    }

    /**
     * The next block set by this hand (null: by the town's works, as the tests and the admin do): the ground first,
     * then the next of its stage on its crew's side (or any side), paid for out of the stores as it is set. True if a
     * block went down.
     */
    static boolean setNext(ServerLevel level, Villages.Village v, CompoundTag w, @Nullable VillageFolkEntity f, int side, boolean quota) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        int stage = w.getInt("stage");
        if (stage < 0 || !w.getString("state").equals("building")) return false;
        if (w.getLong("dayOf") != day) {
            w.putLong("dayOf", day);
            w.putInt("dayPlaced", 0);
        }
        if (quota && w.getInt("dayPlaced") >= quota(id, w)) return false;
        if (stage == 0) {
            boolean[] dig = { false };
            BlockPos g = groundwork(level, w, dig);
            if (g != null) {
                if (dig[0]) {
                    BlockState was = level.getBlockState(g);
                    Item drop = was.getBlock().asItem();
                    level.setBlock(g, Blocks.AIR.defaultBlockState(), 3);
                    if (drop != Items.AIR && !BuildGoal.isWildPlant(was)) TownWork.give(level, v, new ItemStack(drop));   // what came out, into the stores
                } else {
                    if (!payStone(level, v, w, "family", 2)) return waitFor(w, Mat.STONE, level);
                    WorksPlans.Family fam = family(w, "family");
                    level.setBlock(g, (fam == null ? Blocks.COBBLESTONE : fam.block).defaultBlockState(), 3);
                }
                done(level, w, f, g, 1, false);
                return true;
            }
        }
        List<Cell> cells = cells(id, w);
        BitSet laid = laid(w);
        int cursor = Math.max(0, w.getInt("cursor"));
        while (cursor < cells.size() && laid.get(cursor)) cursor++;
        w.putInt("cursor", cursor);
        if (cursor >= cells.size()) return false;
        int lowH = cells.get(cursor).h();
        int pick = -1, any = -1;
        for (int i = cursor; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (c.stage() != stage || c.h() > lowH + 1) break;
            if (laid.get(i)) continue;
            if (any < 0) any = i;
            if (c.side() == side) { pick = i; break; }
        }
        if (pick < 0) pick = any;
        if (pick < 0) return false;                                         // the stage is done; the next starts tomorrow
        Cell c = cells.get(pick);
        BlockPos at = c.p().pos();
        if (!level.isLoaded(at)) return false;
        if (level.isRaining() && c.h() >= 6) {
            w.putString("waiting", "the rain: nobody works up high in it");
            return false;
        }
        BlockState here = level.getBlockState(at);
        Mat m = mat(c.p());
        boolean clear = here.isAir() || here.canBeReplaced() || BuildGoal.isWildPlant(here) || c.h() < 0 && Restore.natural(here);
        if (!clear) {
            // Something there already (built over, the ground): a natural block dug out into the stores; anything of the
            // town's left be and the cell passed over.
            if (Restore.natural(here)) {
                Item drop = here.getBlock().asItem();
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                if (drop != Items.AIR) TownWork.give(level, v, new ItemStack(drop));
            } else {
                laid.set(pick);
                laid(w, laid);
                w.putInt("spared", w.getInt("spared") + 1);
                return true;
            }
        }
        BlockState put;
        if (w.getBoolean("makeDo." + m.name())) {
            if (m.spared()) {
                laid.set(pick);
                laid(w, laid);
                w.putInt("spared", w.getInt("spared") + 1);
                return true;
            }
            put = m == Mat.GOLD || m == Mat.LOG || m == Mat.PLANKS ? pay(level, v, w, c.p(), Mat.STONE) : pay(level, v, w, c.p(), m);
            if (put != null && (m == Mat.LOG || m == Mat.PLANKS || m == Mat.GOLD)) {
                WorksPlans.Family fam = family(w, "family");
                put = (fam == null ? Blocks.COBBLESTONE : fam.block).defaultBlockState();
            }
        } else {
            put = pay(level, v, w, c.p(), m);
        }
        if (put == null) return waitFor(w, m, level);
        BlockState fin = put;
        BuildGoal.stampOnly(level, w.getString("structure"), site(w).anchor(), site(w).facing(), 13, pl -> fin,
            pl -> pl.pos().equals(at));
        if (level.getBlockState(at).isAir() && !put.isAir()) level.setBlock(at, put, 3);    // (a door's or a bed's other half)
        laid.set(pick);
        laid(w, laid);
        w.putString("waitingFor", "");
        w.remove("waitingSince");
        w.putString("waiting", "");
        done(level, w, f, at, 1, c.h() >= 12);
        return true;
    }

    private static boolean waitFor(CompoundTag w, Mat m, ServerLevel level) {
        if (!w.getString("waitingFor").equals(m.name())) {
            w.putString("waitingFor", m.name());
            w.putLong("waitingSince", level.getDayTime() / 24000L);
        }
        w.putString("waiting", matWords(w, m) + ": the stores are out of it");
        w.putLong("waitedAt", level.getGameTime());
        changed();
        return false;
    }

    /** A block down: counted, the hand's share noted, the sound of it; a word from up high now and then. */
    private static void done(ServerLevel level, CompoundTag w, @Nullable VillageFolkEntity f, BlockPos at, int n, boolean high) {
        w.putInt("placed", w.getInt("placed") + n);
        w.putInt("dayPlaced", w.getInt("dayPlaced") + n);
        if (f != null) {
            String k = f.getUUID().toString();
            CompoundTag hands = w.getCompound("hands"), stage = w.getCompound("stageHands");
            hands.putInt(k, hands.getInt(k) + n);
            stage.putInt(k, stage.getInt(k) + n);
            w.put("hands", hands);
            w.put("stageHands", stage);
            f.swing(InteractionHand.MAIN_HAND);
            f.note(AssistantEntity.Deed.BLOCKS_BUILT, 1);
            if (high && f.getRandom().nextInt(40) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Steady on the hoist!", "Mind your heads below!",
                "Up she goes — easy, easy..."));
        }
        BlockState st = level.getBlockState(at);
        level.playSound(null, at, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.7F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        changed();
    }

    // ------------------------------------------------------------------ the scaffolding

    /** The scaffold poles' columns round the site: its corners and the middles of its sides, a block out. */
    static List<BlockPos> poles(CompoundTag w) {
        Ledger.Building b = site(w);
        int[] full = Blueprints.fullHalf(b.structure());
        List<BlockPos> out = new ArrayList<>();
        int x = full[0] + 1, z = full[1] + 1;
        for (int[] c : new int[][]{ { -x, -z }, { x, -z }, { -x, z }, { x, z }, { 0, z }, { -x, 0 }, { x, 0 } }) out.add(Culture.at(b, c[0], 0, c[1]));
        return out;
    }

    /** The poles raised to two over the highest block laid, a block a pole a look, out of the stores' fences. */
    static void scaffold(ServerLevel level, Villages.Village v, CompoundTag w) {
        if (w.getInt("stage") < 1 && laid(w).cardinality() == 0) return;
        List<Cell> cells = cells(v.id(), w);
        int top = 0;
        BitSet laid = laid(w);
        for (int i = laid.nextSetBit(0); i >= 0 && i < cells.size(); i = laid.nextSetBit(i + 1)) top = Math.max(top, cells.get(i).h());
        int want = top + 2;
        List<Long> up = new ArrayList<>();
        for (long l : w.getLongArray("scaffold")) up.add(l);
        boolean changedAny = false;
        for (BlockPos base : poles(w)) {
            for (int h = 0; h <= want; h++) {
                BlockPos p = base.above(h);
                if (up.contains(p.asLong())) continue;
                if (!level.isLoaded(p) || !level.getBlockState(p).isAir()) break;
                if (h == 0 && !level.getBlockState(p.below()).isSolid()) break;
                Block fence = takeTagged(level, v, ItemTags.WOODEN_FENCES);
                if (fence == null) {
                    w.putBoolean("noScaffold", true);
                    break;
                }
                level.setBlock(p, fence.defaultBlockState(), 3);
                up.add(p.asLong());
                changedAny = true;
                break;                                                       // a block a pole a look
            }
        }
        if (changedAny) {
            long[] a = new long[up.size()];
            for (int i = 0; i < a.length; i++) a[i] = up.get(i);
            w.put("scaffold", new LongArrayTag(a));
            changed();
        }
    }

    /** The scaffold down, its fences back into the stores. Returns how many came down. */
    static int strike(ServerLevel level, Villages.Village v, CompoundTag w) {
        int n = 0;
        long[] up = w.getLongArray("scaffold");
        for (int i = up.length - 1; i >= 0; i--) {
            BlockPos p = BlockPos.of(up[i]);
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            TownWork.give(level, v, new ItemStack(st.getBlock().asItem()));
            n++;
        }
        w.put("scaffold", new LongArrayTag(new long[0]));
        return n;
    }

    // ------------------------------------------------------------------ the plaque before the site

    static final String PLAQUE = "WONDER FUND";

    static BlockPos plaqueAt(CompoundTag w) {
        Ledger.Building b = site(w);
        int[] full = Blueprints.fullHalf(b.structure());
        return Culture.at(b, 2, 0, -full[1] - 2);
    }

    /** The plaque put up before the site (a sign of the stores'), and written up: the wonder, its fund, its givers. */
    static boolean plaque(ServerLevel level, Villages.Village v, CompoundTag w, boolean free) {
        BlockPos at = plaqueAt(w);
        if (!level.isLoaded(at)) return false;
        boolean up = level.getBlockState(at).getBlock() instanceof StandingSignBlock;
        if (!up) {
            if (w.getBoolean("noPlaque") && !free) return false;
            if (!Petitions.clear(level, at) || !level.getBlockState(at.below()).isSolid()) return false;
            if (!free && !TownJobs.atWork(level, v, "signs", at, "putting up the wonder's plaque")) return false;
            if (!free && !Crafts.sign(level, v)) {
                w.putBoolean("noPlaque", level.getDayTime() % 24000L > 6000L);
                return false;
            }
            level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
                RotationSegment.convertToSegment(site(w).facing().getOpposite())), 3);
        }
        if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) return false;
        List<String> lines = plaqueLines(v.id(), w);
        w.putLong("plaqueAt", at.asLong());
        boolean same = true;
        for (int i = 0; i < 4; i++) {
            if (!sign.getFrontText().getMessage(i, false).getString().equals(lines.get(i))
                || !sign.getBackText().getMessage(i, false).getString().equals(lines.get(4 + i))) same = false;
        }
        if (same) return true;                                              // written up already: nothing sent
        SignText face = new SignText(), back = new SignText();
        for (int i = 0; i < 4; i++) face = face.setMessage(i, Component.literal(lines.get(i)));
        for (int i = 0; i < 4; i++) back = back.setMessage(i, Component.literal(lines.get(4 + i)));
        sign.setText(face, true);
        sign.setText(back, false);
        sign.setWaxed(true);
        w.putLong("plaqueAt", at.asLong());
        return true;
    }

    /** The plaque's eight lines: while it rises, the fund and how to give; once open, who raised it; the givers behind. */
    static List<String> plaqueLines(UUID village, CompoundTag w) {
        List<String> out = new ArrayList<>();
        boolean open = !w.getString("state").equals("building");
        if (open) {
            out.add("Raised by");
            out.add("the people of");
            out.add(Villages.name(village));
            out.add("day " + (w.getLong("finishedOn") + 1));
        } else {
            out.add(PLAQUE);
            String[] t = Culture.signLines(w.getString("title"));
            out.add(t[0]);
            out.add(w.getInt("fund") + " coins");
            out.add("give here");
        }
        List<String> names = givers(w);
        for (int line = 0; line < 4; line++) {
            int i = line * 2;
            if (line == 3 && names.size() > 8) {
                out.add("& " + (names.size() - 6) + " more");
                break;
            }
            String a = i < names.size() ? names.get(i) : "", b = i + 1 < names.size() ? names.get(i + 1) : "";
            out.add(b.isEmpty() ? a : a + ", " + b);
        }
        while (out.size() < 8) out.add("");
        return out;
    }

    /** Everybody who gave, coin or goods, the most generous first. */
    static List<String> givers(CompoundTag w) {
        CompoundTag donors = w.getCompound("donors"), gifts = w.getCompound("gifts");
        Map<String, Integer> all = new LinkedHashMap<>();
        for (String k : donors.getAllKeys()) all.merge(k, donors.getInt(k), Integer::sum);
        for (String k : gifts.getAllKeys()) all.merge(k, gifts.getInt(k) / 4, Integer::sum);
        List<String> names = new ArrayList<>(all.keySet());
        names.sort((a, b) -> all.get(b) != all.get(a).intValue() ? Integer.compare(all.get(b), all.get(a)) : a.compareTo(b));
        return names;
    }

    /** The plaque right-clicked: coin given to the fund, goods the wonder wants given to its stores, or its say read. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockState(pos).getBlock() instanceof StandingSignBlock)) return;
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        if (!PLAQUE.equals(sign.getFrontText().getMessage(0, false).getString())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.nearest(level, pos, 160);
        if (v == null) return;
        Player p = e.getEntity();
        ItemStack hand = p.getMainHandItem();
        String said = Market.isCoin(hand) ? donate(level, v, p, p.isShiftKeyDown() ? hand.getCount() : 1)
            : !hand.isEmpty() ? giveGoods(level, v, p, hand) : String.join("\n", status(level, v));
        p.displayClientMessage(Component.literal(said), false);
    }

    /** A player gives coin to the fund. What they are told. */
    public static String donate(ServerLevel level, Villages.Village v, Player p, int coins) {
        CompoundTag w = current(v.id());
        if (w == null || !w.getString("state").equals("building")) return Villages.name(v.id()) + " has no wonder rising to give to.";
        int have = Market.coinsHeld(p);
        int n = Math.min(coins, have);
        if (n <= 0) return "You've no village coins to give.";
        if (!Civics.takeCoins(p, n)) return "You've only " + have + " coins.";
        give(w, p.getName().getString(), n);
        for (VillageFolkEntity folk : Civics.grown(v.id())) folk.persona().feelFor(p.getUUID(), p.getName().getString(), Math.min(3, 1 + n / 10));
        plaque(level, v, w, true);
        return "You gave " + n + (n == 1 ? " coin" : " coins") + " to " + w.getString("title") + ": the fund holds " + w.getInt("fund")
            + ". Your name goes on the plaque. Thank you!";
    }

    /** A player gives goods the wonder still wants (stone, glass, gold, lanterns...): into the town's stores, named on the plaque. */
    public static String giveGoods(ServerLevel level, Villages.Village v, Player p, ItemStack hand) {
        UUID id = v.id();
        CompoundTag w = current(id);
        if (w == null || !w.getString("state").equals("building")) return Villages.name(id) + " has no wonder rising to give to.";
        Map<Mat, Integer> want = wanted(id, w);
        Mat match = null;
        for (Mat m : want.keySet()) if (matches(w, m, hand)) { match = m; break; }
        if (match == null) return w.getString("title") + " wants none of that. It wants: " + wantedWords(level, id, w, 4) + ".";
        int n = hand.getCount();
        ItemStack gift = hand.split(n);
        TownWork.give(level, v, gift);
        CompoundTag gifts = w.getCompound("gifts");
        gifts.putInt(p.getName().getString(), gifts.getInt(p.getName().getString()) + n);
        w.put("gifts", gifts);
        changed();
        for (VillageFolkEntity folk : Civics.grown(id)) folk.persona().feelFor(p.getUUID(), p.getName().getString(), Math.min(3, 1 + n / 32));
        Villages.tell(id, level.getDayTime() / 24000L, p.getName().getString() + " gave " + n + " " + matWords(w, match) + " for " + w.getString("title"));
        plaque(level, v, w, true);
        return "You gave " + n + " " + matWords(w, match) + " for " + w.getString("title") + ". Into the stores they go — thank you!";
    }

    static boolean matches(CompoundTag w, Mat m, ItemStack s) {
        return switch (m) {
            case STONE -> family(w, "family") != null && family(w, "family").payment().test(s);
            case STONE2 -> (family(w, "family2") != null ? family(w, "family2") : family(w, "family")) != null
                && (family(w, "family2") != null ? family(w, "family2") : family(w, "family")).payment().test(s);
            case LOG -> s.is(ItemTags.LOGS);
            case PLANKS -> s.is(ItemTags.PLANKS);
            case GLASS -> s.is(Items.GLASS);
            case PANE -> s.is(Items.GLASS_PANE) || s.is(Items.GLASS);
            case LANTERN -> s.is(Items.LANTERN) || s.is(Items.SOUL_LANTERN);
            case TORCH -> s.is(Items.TORCH);
            case CARPET -> s.is(ItemTags.WOOL_CARPETS);
            case DOOR -> s.is(ItemTags.WOODEN_DOORS);
            case BELL -> s.is(Items.BELL);
            case LECTERN -> s.is(Items.LECTERN);
            case LADDER -> s.is(Items.LADDER);
            case FENCE -> s.is(ItemTags.WOODEN_FENCES);
            case FLOWER -> s.is(ItemTags.SMALL_FLOWERS);
            case GOLD -> s.is(Items.GOLD_INGOT) || s.is(Items.GOLD_BLOCK);
        };
    }

    // ------------------------------------------------------------------ finished, and opened

    /** The last block laid: the scaffold down, the plaque turned, the building into the town's register; opened tonight. */
    static void finished(ServerLevel level, Villages.Village v, CompoundTag w, long day) {
        UUID id = v.id();
        if (w.getInt("stage") >= 0) payHands(level, v, w, w.getInt("stage"), day);
        int struck = strike(level, v, w);
        w.putString("state", "opening");
        w.putLong("finishedOn", day);
        long t = level.getDayTime() % 24000L;
        w.putLong("openOn", t < 11500L ? day : day + 1);
        Ledger.Building b = site(w);
        if (!Villages.sitesOf(id).containsKey(b.structure())) Villages.holdPrivateSite(id, b.structure(), new Villages.Site(b.anchor(), b.facing(), 0));
        Villages.noteProject(id, b.structure(), level.getGameTime());          // into the register; the opening gathered tonight
        Villages.rememberBuiltAt(id, b.structure(), b.anchor());
        plaque(level, v, w, true);
        changed();
        Villages.tell(id, day, "the last stone of " + w.getString("title") + " was laid on day " + (day - w.getLong("started") + 1)
            + " of the work" + (struck > 0 ? ", and the scaffolding came down" : ""));
        Market.assemblyNews(id, Culture.capital(w.getString("title")) + " is finished! We open it this evening — come one, come all.");
        LOG.info("[MCA-WONDER] {}: {} finished ({} blocks, {} passed over)", Villages.name(id), w.getString("title"), w.getInt("placed"), w.getInt("spared"));
    }

    /** Is this the opening of a town's wonder? Its lines, if so (Assemblies' OPENING). */
    public static boolean openingScript(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        Kind kind = Kind.of(a.subject);
        if (kind == null) return false;
        Villages.Village v = Villages.get(a.village);
        CompoundTag w = v == null ? null : current(v.id());
        if (w == null || !w.getString("structure").equals(kind.structure)) return false;
        long days = Math.max(1, w.getLong("finishedOn") - w.getLong("started") + 1);
        int hands = w.getCompound("hands").getAllKeys().size();
        s.add(new Assemblies.Line(null, "Friends! After " + days + " days and " + w.getInt("placed") + " blocks, " + kind.name + " stands!", '!', null));
        s.add(new Assemblies.Line(null, hands + " of you raised it with your own hands, and the whole town paid for it.", '!', null));
        List<String> givers = givers(w);
        if (!givers.isEmpty()) {
            s.add(new Assemblies.Line(null, "Our thanks to all who gave: " + JobMarket.join(givers.subList(0, Math.min(4, givers.size())))
                + (givers.size() > 4 ? ", and " + (givers.size() - 4) + " more" : "") + ".", '!', null));
        }
        String best = bestHand(level, w);
        if (best != null) s.add(new Assemblies.Line(null, "And to " + best + ", who laid more of it than anybody.", '!', null));
        s.add(new Assemblies.Line(null, "It gives us " + kind.gift + ".", '?', null));
        Villages.Village vv = v;
        s.add(new Assemblies.Line(null, "I declare " + kind.name + " open!", '!', () -> {
            FireworkShows.salute(level, vv, a.focus, 12);
            opened(level, vv);
        }));
        s.add(new Assemblies.Line(null, FolkTalk.pick(r, "Three cheers for " + Villages.name(a.village) + "!", "Go in, all of you — it's yours!"), '!', null));
        return true;
    }

    @Nullable
    private static String bestHand(ServerLevel level, CompoundTag w) {
        CompoundTag hands = w.getCompound("hands");
        String best = null;
        int most = 0;
        for (String k : hands.getAllKeys()) {
            if (hands.getInt(k) <= most) continue;
            UUID u = WarBooks.id(k);
            if (u != null && level.getEntity(u) instanceof VillageFolkEntity f) {
                most = hands.getInt(k);
                best = f.displayNameCap();
            }
        }
        return most >= 5 ? best : null;
    }

    /** Opened: into the history and everybody's memory, the town's pride, the world told, the rivals stirred. */
    static void opened(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag w = current(id);
        if (w == null) return;
        long day = level.getDayTime() / 24000L;
        Kind kind = Kind.of(w.getString("structure"));
        String title = w.getString("title");
        String town = Villages.name(id);
        CompoundTag hands = w.getCompound("hands");
        Villages.tell(id, day, title + " was opened, the town's own wonder: " + hands.getAllKeys().size() + " hands laid its " + w.getInt("placed")
            + " blocks in " + Math.max(1, w.getLong("finishedOn") - w.getLong("started") + 1) + " days");
        for (String k : hands.getAllKeys()) {
            UUID u = WarBooks.id(k);
            if (u != null && level.getEntity(u) instanceof VillageFolkEntity f && f.persona().rolled()) {
                f.persona().remember(day, "I helped raise " + title + ", and saw it opened", 8);
                f.refreshMood();
            }
        }
        CompoundTag one = new CompoundTag();
        one.putString("structure", w.getString("structure"));
        one.putString("title", title);
        one.putLong("day", day);
        one.putLong("started", w.getLong("started"));
        one.putInt("hands", hands.getAllKeys().size());
        one.putInt("placed", w.getInt("placed"));
        one.putLong("site", w.getLong("site"));
        one.putInt("facing", w.getInt("facing"));
        one.put("builders", hands.copy());
        one.put("donors", w.getCompound("donors").copy());
        one.put("gifts", w.getCompound("gifts").copy());
        if (w.getInt("fund") > 0) Ledger.addCoins(id, w.getInt("fund"));               // what the fund had over, back to the treasury
        done(id).add(one);
        town(id).remove("work");
        CELLS.remove(id);
        changed();
        Market.assemblyNews(id, "We raised " + title + ", the town's own wonder!");
        Reigns.deed(id, Reigns.Deed.WONDER, 2);
        // The neighbours hear of it: the friendly glad, the rivals envious.
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(id)) continue;
            boolean rival = Wars.atWar(o.id(), id) || Ledger.knowEachOther(o.id(), id) && Ledger.relation(o.id(), id) <= -20;
            if (rival) {
                Ledger.relate(o.id(), id, -5);
                Villages.tell(o.id(), day, "word came that " + town + " has raised " + title + "; it sticks in our throats");
            } else {
                if (Ledger.knowEachOther(o.id(), id)) Ledger.relate(o.id(), id, 3);
                Villages.tell(o.id(), day, "word came that " + town + " has raised " + title + ", a wonder of its own");
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            Component line = Component.literal(town + " has raised " + title + ", a wonder of its own!").withStyle(net.minecraft.ChatFormatting.GOLD);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(line);
        }
        // The plaque, turned to who raised it.
        CompoundTag shown = one.copy();
        shown.putString("state", "standing");
        shown.putLong("finishedOn", day);
        shown.put("donors", one.getCompound("donors"));
        BlockPos at = plaqueAt(w);
        if (level.isLoaded(at) && level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            List<String> lines = plaqueLines(id, shown);
            SignText face = new SignText(), back = new SignText();
            for (int i = 0; i < 4; i++) face = face.setMessage(i, Component.literal(lines.get(i)));
            for (int i = 0; i < 4; i++) back = back.setMessage(i, Component.literal(lines.get(4 + i)));
            sign.setText(face, true);
            sign.setText(back, false);
        }
        LOG.info("[MCA-WONDER] {}: {} opened", town, title);
    }

    // ------------------------------------------------------------------ war

    /** A town taken (Campaigns.taken): its wonder pulled about, and half its fund gone on the peace. */
    public static void sacked(ServerLevel level, UUID town, UUID by, long day) {
        Villages.Village v = Villages.get(town);
        if (v == null) return;
        String enemy = Villages.name(by);
        CompoundTag w = current(town);
        if (w != null && w.getString("state").equals("building")) {
            List<Cell> cells = cells(town, w);
            BitSet laid = laid(w);
            int pulled = 0, want = laid.cardinality() / 5;
            for (int i = laid.length() - 1; i >= 0 && pulled < want; i--) {
                if (!laid.get(i)) continue;
                BlockPos p = cells.get(i).p().pos();
                if (level.isLoaded(p) && !level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                laid.clear(i);
                pulled++;
            }
            laid(w, laid);
            w.putInt("cursor", 0);
            w.putInt("stage", -1);
            w.putLong("stageFrom", day + 1);
            int lost = w.getInt("fund") / 2;
            w.putInt("fund", w.getInt("fund") - lost);
            Ledger.addCoins(town, lost);
            changed();
            Villages.tell(town, day, enemy + "'s soldiers pulled down " + pulled + " blocks of " + w.getString("title") + ", and " + lost
                + " coins of the wonder fund went on the peace");
            return;
        }
        for (Kind k : Kind.values()) {
            Ledger.Building b = standing(town, k.structure);
            if (b == null) continue;
            List<BuildGoal.Placement> plan = new ArrayList<>(BuildGoal.plan(k.structure, b.anchor(), b.facing(), 13));
            plan.sort(Comparator.comparingInt((BuildGoal.Placement p) -> -p.pos().getY()));
            int pulled = 0, want = Math.max(10, plan.size() / 7);
            for (BuildGoal.Placement p : plan) {
                if (pulled >= want) break;
                if (!level.isLoaded(p.pos()) || level.getBlockState(p.pos()).isAir()) continue;
                level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), 3);
                pulled++;
            }
            CompoundTag t = town(town);
            t.putInt("damaged." + k.structure, t.getInt("damaged." + k.structure) + pulled);
            changed();
            Villages.tell(town, day, enemy + "'s soldiers pulled down the top of " + k.name + " (" + pulled + " blocks); the town will mend it");
            return;
        }
    }

    // ------------------------------------------------------------------ the hands

    /**
     * From the folk's tick (VillageFolkEntity.aiStep): a hand at the wonder, on the first morning of a stage everybody
     * till noon, else whoever is off work or has nothing to do at its trade, so many to a crew. True while it is at it.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        CompoundTag w = id == null ? null : current(id);
        if (w == null || !w.getString("state").equals("building") || w.getInt("stage") < 0 || !w.getString("paused").isEmpty()
                || !called(f, level, w)) return release(f);
        Villages.Village v = Villages.get(id);
        if (v == null) return release(f);
        long day = level.getDayTime() / 24000L;
        if (w.getLong("dayOf") == day && w.getInt("dayPlaced") >= quota(id, w)) return release(f);
        if (!w.getString("waitingFor").isEmpty() && level.getGameTime() - w.getLong("waitedAt") < 1200L) return release(f);
        Integer crew = CREWS.get(f.getUUID());
        if (crew == null) {
            int most = Villages.crewsAllowed(id) * CREW;
            int on = 0;
            for (Map.Entry<UUID, Held> e : HELD.entrySet()) {
                if (level.getGameTime() - e.getValue().at() <= 40 && level.getEntity(e.getKey()) instanceof VillageFolkEntity g && id.equals(g.ownerId())) on++;
            }
            boolean muster = day == w.getLong("muster") && level.getDayTime() % 24000L < MUSTER_TO;
            if (on >= most && !muster) return release(f);
            crew = Math.floorMod(on, Math.max(1, Villages.crewsAllowed(id)));
            CREWS.put(f.getUUID(), crew);
        }
        int side = Math.floorMod(crew + Math.floorMod(f.getUUID().hashCode(), 2) * 2, 4);
        String doing = "raising " + w.getString("title") + " (crew " + (crew + 1) + ")";
        Ledger.Building b = site(w);
        int[] full = Blueprints.fullHalf(b.structure());
        int spread = Math.floorMod(f.getUUID().hashCode() >> 3, 5) - 2;
        BlockPos stand = switch (side) {
            case 0 -> Culture.at(b, spread, 0, -full[1] - 3);
            case 1 -> Culture.at(b, full[0] + 3, 0, spread);
            case 2 -> Culture.at(b, spread, 0, full[1] + 3);
            default -> Culture.at(b, -full[0] - 3, 0, spread);
        };
        long now = level.getGameTime();
        Long since = SET_OUT.get(f.getUUID());
        if (since == null) {
            f.clearQueue();
            f.getNavigation().stop();
            SET_OUT.put(f.getUUID(), now);
            since = now;
            if (f.getRandom().nextInt(4) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off to " + w.getString("title") + "!", "My bit for the wonder.",
                    "Many hands — and a lot of stone."));
            }
        }
        boolean there = Civics.flat(f, stand) <= 5.0 * 5.0;
        boolean nearEnough = now - since > 600L && Civics.flat(f, b.anchor()) <= REACH * REACH;
        if (!there && !nearEnough) {
            Civics.goTo(f, stand, 2.5, 0.95);
        } else {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(b.anchor().getX() + 0.5, b.anchor().getY() + 4, b.anchor().getZ() + 0.5);
            Integer last = LAST_SET.get(f.getUUID());
            if (last == null || f.tickCount - last >= PIECE_TICKS || f.tickCount < last) {
                LAST_SET.put(f.getUUID(), f.tickCount);
                setNext(level, v, w, f, side, true);
            }
        }
        HELD.put(f.getUUID(), new Held(doing, f.tickCount, now));
        f.hobbyNow = doing;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** At the wonder just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 8;
    }

    private static boolean release(VillageFolkEntity f) {
        HELD.remove(f.getUUID());
        SET_OUT.remove(f.getUUID());
        CREWS.remove(f.getUUID());
        return false;
    }

    /** Is this folk called to the wonder now: by day, fit for it, no storm or alarm; the stage's first morning, or its own time. */
    static boolean called(VillageFolkEntity f, ServerLevel level, CompoundTag w) {
        if (f.isBaby() || f.isShowcase() || !f.isAlive() || f.stationTask() == AssistantEntity.StationTask.GUARD) return false;
        UUID id = f.ownerId();
        if (id == null || Raids.underAlarm(id)) return false;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t < FROM || t >= TO || Weather.stormy(level)) return false;
        if (!TownJobs.fit(f, "works") || TownJobs.busy(f) || JobSeekers.busy(f) || School.teaching(f) || Civics.busy(f) || BigWorks.busy(f)) return false;
        if (day == w.getLong("muster") && t < MUSTER_TO) return true;
        if (f.offWorkNow()) return true;
        return f.workedOut();
    }

    // ------------------------------------------------------------------ its spirits

    /** Pride in the town's wonder: a little while it stands, more for the week after it opened, the most for its builders. */
    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        UUID id = f.ownerId();
        if (id == null) return m;
        int standing = count(id);
        if (standing == 0) return m;
        long opened = -100;
        boolean built = false;
        for (Tag t : done(id)) {
            if (!(t instanceof CompoundTag c)) continue;
            opened = Math.max(opened, c.getLong("day"));
            if (c.getCompound("builders").contains(f.getUUID().toString()) && day - c.getLong("day") <= 7) built = true;
        }
        if (built) {
            m += 6;
            why.add(new Object[]{ "wonderbuilt", 6 });
        } else if (day - opened <= 7) {
            m += 4;
            why.add(new Object[]{ "wonderopened", 4 });
        } else {
            m += 2;
            why.add(new Object[]{ "wonder", 2 });
        }
        if (standing(id, MINSTER) != null && RestDay.today(id, day)) {
            m += 2;
            why.add(new Object[]{ "minster", 2 });
        }
        return m;
    }

    static String moodWords(VillageFolkEntity f, String why) {
        UUID id = f.ownerId();
        String what = "our wonder";
        if (id != null) for (Kind k : Kind.values()) if (standing(id, k.structure) != null) what = k.name;
        var r = f.getRandom();
        return switch (why) {
            case "wonderbuilt" -> FolkTalk.pick(r, "I laid stones of " + what + " with these hands!", "Have you seen " + what + "? I helped raise it.");
            case "wonderopened" -> "Did you see " + what + " opened? What a night!";
            case "minster" -> "The service in the minster on the day of rest — it lifts you.";
            default -> FolkTalk.pick(r, "We've " + what + ", you know. Not every town can say that.", "Every time I pass " + what + " I stand up straighter.");
        };
    }

    /** Neighbours' warmth toward a town with a wonder (Diplomacy.daily): a little each day, unless they are rivals. */
    public static int relationLean(UUID a, UUID b) {
        int lean = 0;
        for (UUID[] pair : new UUID[][]{ { a, b }, { b, a } }) {
            if (count(pair[1]) == 0) continue;
            if (Wars.atWar(pair[0], pair[1])) continue;
            lean += Ledger.relation(pair[0], pair[1]) <= -20 ? -1 : 1;
        }
        return Math.max(-1, Math.min(1, lean));
    }

    /** Its card: building the wonder now, and its share of it. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return null;
        List<String> parts = new ArrayList<>();
        Held h = HELD.get(f.getUUID());
        if (h != null && f.tickCount - h.tick() <= 40) parts.add(h.doing());
        CompoundTag w = current(id);
        if (w != null) {
            int n = w.getCompound("hands").getInt(f.getUUID().toString());
            if (n > 0) parts.add("has laid " + n + " blocks of " + w.getString("title"));
        }
        for (Tag t : done(id)) {
            if (t instanceof CompoundTag c && c.getCompound("builders").getInt(f.getUUID().toString()) > 0) {
                parts.add("helped raise " + c.getString("title") + " (" + c.getCompound("builders").getInt(f.getUUID().toString()) + " blocks)");
            }
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The board's lines: the wonder rising (its stage, how far, what it waits on), or the opening tonight. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag w = current(village);
        long day = level.getDayTime() / 24000L;
        if (w == null) return out;
        Kind kind = Kind.of(w.getString("structure"));
        if (w.getString("state").equals("building")) {
            int stage = w.getInt("stage");
            String what = stage < 0 ? "between stages" : kind == null ? "" : kind.stages[Math.min(4, stage)];
            String paused = w.getString("paused"), waiting = w.getString("waiting");
            out.add((paused.isEmpty() && waiting.isEmpty() ? "RG" : "RW") + "|" + Culture.capital(w.getString("title")) + ": " + percent(village, w) + "% raised, "
                + what + ", day " + (day - w.getLong("started") + 1) + " of the work; the fund " + w.getInt("fund") + " coins"
                + (day == w.getLong("muster") ? ". EVERYBODY LEND A HAND TILL NOON!" : "")
                + (!paused.isEmpty() ? ". Stopped: " + paused + "." : !waiting.isEmpty() ? ". Waiting for " + waiting + "." : "."));
            String still = wantedWords(level, village, w, 3);
            if (!still.isEmpty()) out.add("RN|Still wanted for it: " + still + ". Give at its plaque.");
        } else {
            out.add("RG|" + Culture.capital(w.getString("title")) + " is finished! Opened " + (w.getLong("openOn") <= day ? "this evening" : "tomorrow evening")
                + " — fireworks!");
        }
        return out;
    }

    /** The gazette's line on the wonder rising: how far on, and what it waits for; null with none. */
    @Nullable
    public static String gazette(ServerLevel level, UUID village) {
        CompoundTag w = current(village);
        if (w == null || !w.getString("state").equals("building")) return null;
        long day = level.getDayTime() / 24000L;
        String paused = w.getString("paused"), waiting = w.getString("waiting");
        return "§lThe wonder§r\n" + Culture.capital(w.getString("title")) + ": " + percent(village, w) + "% raised, day " + (day - w.getLong("started") + 1)
            + " of the work. The fund: " + w.getInt("fund") + " coins." + (!paused.isEmpty() ? " Stopped: " + paused + "."
            : !waiting.isEmpty() ? " Waiting for " + waiting + "." : "") + " Still wanted: " + wantedWords(level, village, w, 3) + ".";
    }

    /** The Culture page's section: the wonder rising, the wonders standing, why not yet. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        out.put("lines", Culture.strings(status(level, v)));
        return out;
    }

    /** /village wonder, and the books: everything about it, a line each. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        CompoundTag w = current(id);
        if (w != null) {
            Kind kind = Kind.of(w.getString("structure"));
            int[] p = progress(id, w);
            if (w.getString("state").equals("building")) {
                int stage = w.getInt("stage");
                out.add("rising: " + w.getString("title") + " — " + percent(id, w) + "% (" + p[0] + " of " + p[1] + " blocks), day "
                    + (day - w.getLong("started") + 1) + " of the work, voted " + w.getString("vote"));
                if (kind != null) {
                    List<String> stages = new ArrayList<>();
                    for (int s = 0; s <= 4; s++) {
                        int[] sp = stageProgress(id, w, s);
                        stages.add(kind.stages[s] + " " + (sp[1] == 0 ? 100 : 100 * sp[0] / sp[1]) + "%" + (s == stage ? " (now)" : ""));
                    }
                    out.add("the stages: " + String.join("; ", stages));
                }
                if (!w.getString("paused").isEmpty()) out.add("waiting on: " + w.getString("paused"));
                else if (!w.getString("waiting").isEmpty()) out.add("waiting on: " + w.getString("waiting"));
                out.add("still wanted: " + wantedWords(level, id, w, 6));
                out.add("the wonder fund: " + w.getInt("fund") + " coins (" + w.getInt("levied") + " by the levy)"
                    + (stage >= 0 ? "; " + w.getInt("purse") + " set by for this stage's wages" : ""));
                out.add("today: " + (w.getLong("dayOf") == day ? w.getInt("dayPlaced") : 0) + " of " + quota(id, w) + " blocks; "
                    + w.getCompound("hands").getAllKeys().size() + " hands so far, " + Villages.crewsAllowed(id) + " crews");
            } else {
                out.add("finished: " + w.getString("title") + ", to be opened " + (w.getLong("openOn") <= day ? "this evening" : "tomorrow evening"));
            }
            List<String> g = givers(w);
            if (!g.isEmpty()) out.add("the givers: " + String.join(", ", g.subList(0, Math.min(8, g.size()))) + (g.size() > 8 ? " and " + (g.size() - 8) + " more" : ""));
        }
        for (Tag t : done(id)) {
            if (!(t instanceof CompoundTag c)) continue;
            Kind k = Kind.of(c.getString("structure"));
            out.add("standing: " + c.getString("title") + ", opened day " + (c.getLong("day") + 1) + " after " + Math.max(1, c.getLong("day") - c.getLong("started"))
                + " days (" + c.getInt("placed") + " blocks by " + c.getInt("hands") + " hands)" + (k == null ? "" : "; it gives " + k.gift)
                + (town(id).getInt("damaged." + c.getString("structure")) > 0 ? "; pulled about in the war, mended by the town" : ""));
        }
        if (w == null) {
            String no = whyNot(level, id);
            out.add(no.isEmpty() ? "the council will put a wonder to the vote on its next day" : "no wonder yet: " + no);
        }
        return out;
    }

    // ------------------------------------------------------------------ the admin and the tests

    /** By decree: the council's vote taken now (a kind named, or its own choice). What happened. */
    public static String startNow(ServerLevel level, Villages.Village v, @Nullable String kindName) {
        UUID id = v.id();
        if (current(id) != null) return "a wonder is under way already: " + current(id).getString("title");
        Kind k = Kind.of(kindName);
        if (k != null) {
            long day = level.getDayTime() / 24000L;
            Villages.tell(id, day, "by decree, the town raises " + k.name);
            return start(level, v, k, day, "by decree");
        }
        return consider(level, v, level.getDayTime() / 24000L, true);
    }

    /** By decree: the stage under way (or the next) laid now, whatever the hour, out of the stores. What happened. */
    public static String stageNow(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        CompoundTag w = current(id);
        if (w == null) return "no wonder under way";
        long day = level.getDayTime() / 24000L;
        if (w.getInt("stage") < 0) {
            w.putLong("stageFrom", day);
            if (free && w.getInt("fund") < wages(id, w, nextStage(id, w))) w.putInt("fund", wages(id, w, nextStage(id, w)));
            beginStage(level, v, w, day);
        }
        int stage = w.getInt("stage");
        if (stage < 0) return "the stage could not begin: " + w.getString("waiting");
        int laid = 0;
        for (int i = 0; i < 20000; i++) {
            if (w.getInt("stage") != stage) break;
            if (free) {
                if (!setFree(level, v, w)) break;
            } else if (!setNext(level, v, w, null, i % 4, false)) break;
            laid++;
            if (nextStage(id, w) != stage) break;
        }
        beginStage(level, v, w, day);
        changed();
        return laid + " blocks laid; " + percent(id, w) + "% raised" + (w.getString("waiting").isEmpty() ? "" : "; waiting for " + w.getString("waiting"));
    }

    /** A block laid for nothing (the admin's "free", the pictures). */
    private static boolean setFree(ServerLevel level, Villages.Village v, CompoundTag w) {
        UUID id = v.id();
        List<Cell> cells = cells(id, w);
        BitSet laid = laid(w);
        int stage = w.getInt("stage");
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (laid.get(i) || c.stage() != stage) continue;
            BlockState put = freeState(w, c.p());
            if (put != null) {
                BuildGoal.stampOnly(level, w.getString("structure"), site(w).anchor(), site(w).facing(), 13, pl -> put, pl -> pl.pos().equals(c.p().pos()));
            }
            laid.set(i);
            laid(w, laid);
            w.putInt("placed", w.getInt("placed") + 1);
            return true;
        }
        return false;
    }

    @Nullable
    static BlockState freeState(CompoundTag w, BuildGoal.Placement p) {
        WorksPlans.Family fam = family(w, "family"), fam2 = family(w, "family2");
        if (fam == null) fam = WorksPlans.Family.STONE_BRICKS;
        if (fam2 == null) fam2 = fam;
        return switch (mat(p)) {
            case STONE -> p.style() == Blueprints.Style.STONE_SLAB ? fam.slab.defaultBlockState()
                : p.style() == Blueprints.Style.STONE_STAIR ? fam.stairs.defaultBlockState() : fam.block.defaultBlockState();
            case STONE2 -> switch (p.style()) {
                case ROOF_STAIR, ROOF_STAIR_TOP -> fam2.stairs.defaultBlockState();
                case ROOF_SLAB, ROOF_SLAB_TOP -> fam2.slab.defaultBlockState();
                default -> fam2.block.defaultBlockState();
            };
            case LOG -> Blocks.DARK_OAK_LOG.defaultBlockState();
            case PLANKS -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
            case PANE -> Blocks.GLASS_PANE.defaultBlockState();
            case LANTERN -> Blocks.LANTERN.defaultBlockState();
            case TORCH -> Blocks.TORCH.defaultBlockState();
            case CARPET -> Blocks.RED_CARPET.defaultBlockState();
            case DOOR -> Blocks.DARK_OAK_DOOR.defaultBlockState();
            case BELL -> Blocks.BELL.defaultBlockState();
            case LECTERN -> Blocks.LECTERN.defaultBlockState();
            case LADDER -> Blocks.LADDER.defaultBlockState();
            case FENCE -> Blocks.DARK_OAK_FENCE.defaultBlockState();
            case FLOWER -> Blocks.POPPY.defaultBlockState();
            case GOLD -> Blocks.GOLD_BLOCK.defaultBlockState();
        };
    }

    /** By decree: every stage laid now and the wonder finished (opened tonight). {@code free}: out of nothing, for the admin. */
    public static String finishNow(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        CompoundTag w = current(id);
        if (w == null) return "no wonder under way";
        int rounds = 0;
        while (current(id) == w && w.getString("state").equals("building") && rounds++ < 8) {
            String s = stageNow(level, v, free);
            if (laid(w).cardinality() >= cells(id, w).size()) break;
            if (s.startsWith("0 blocks") || s.contains("could not")) return s;
        }
        if (laid(w).cardinality() >= cells(id, w).size() && w.getString("state").equals("building")) finished(level, v, w, level.getDayTime() / 24000L);
        return w.getString("title") + ": " + w.getString("state") + " (" + percent(id, w) + "%)";
    }

    /** Tests: the wonder under way as "structure|state|stage|laid/of|fund|waiting|paused", or "". */
    public static String stateForTests(UUID village) {
        CompoundTag w = current(village);
        if (w == null) return "";
        int[] p = progress(village, w);
        return w.getString("structure") + "|" + w.getString("state") + "|" + w.getInt("stage") + "|" + p[0] + "/" + p[1] + "|" + w.getInt("fund")
            + "|" + w.getString("waiting") + "|" + w.getString("paused");
    }

    /** Tests: the morning's look now (the levy, the gifts, the pause, the next stage). */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        morning(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the town's look now (scaffold, plaque, finished). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    /** Tests: the council's question now, as on its day. */
    public static String considerForTests(ServerLevel level, Villages.Village v) {
        return consider(level, v, level.getDayTime() / 24000L, false);
    }

    /** Tests: why the town may not have one, or "". */
    public static String whyNotForTests(ServerLevel level, UUID village) {
        return whyNot(level, village);
    }

    /** Tests: so many blocks set by these hands in turn, as at their sides (no walking), the day's courses counted. */
    public static int buildForTests(ServerLevel level, Villages.Village v, List<VillageFolkEntity> hands, int rounds) {
        CompoundTag w = current(v.id());
        if (w == null) return 0;
        int n = 0;
        for (int r = 0; r < rounds; r++) {
            boolean any = false;
            for (int i = 0; i < hands.size(); i++) {
                if (setNext(level, v, w, hands.get(i), i % 4, true)) { n++; any = true; }
            }
            if (!any) break;
        }
        return n;
    }

    /** Tests: where it stands, its facing ("x y z facing"), or "". */
    @Nullable
    public static Ledger.Building siteForTests(UUID village) {
        CompoundTag w = current(village);
        return w == null ? null : site(w);
    }

    /** Tests: the scaffold's blocks up now. */
    public static List<BlockPos> scaffoldForTests(UUID village) {
        CompoundTag w = current(village);
        List<BlockPos> out = new ArrayList<>();
        if (w != null) for (long l : w.getLongArray("scaffold")) out.add(BlockPos.of(l));
        return out;
    }

    /** Tests: the plaque's spot. */
    @Nullable
    public static BlockPos plaqueForTests(UUID village) {
        CompoundTag w = current(village);
        return w == null ? null : plaqueAt(w);
    }

    /** Tests: the opening as the gathering would hold it: its lines, then the ribbon (opened now). */
    public static List<String> openForTests(ServerLevel level, Villages.Village v) {
        CompoundTag w = current(v.id());
        List<String> out = new ArrayList<>();
        if (w == null) return out;
        Assemblies.Assembly a = new Assemblies.Assembly(v.id(), Assemblies.Kind.OPENING, w.getString("structure"), level.getDayTime() / 24000L,
            site(w).anchor(), site(w).facing(), Assemblies.Layout.ARC);
        List<Assemblies.Line> lines = new ArrayList<>();
        openingScript(level, a, lines, level.getRandom());
        for (Assemblies.Line l : lines) {
            out.add(l.text());
            if (l.effect() != null) l.effect().run();
        }
        return out;
    }

    /** Tests: the wonders this town has raised, by title. */
    public static List<String> raisedForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Tag t : done(village)) if (t instanceof CompoundTag c) out.add(c.getString("title"));
        return out;
    }

    /** Tests: as if the world were saved and loaded: the book read back from its own save, the memory forgotten. */
    public static void reloadForTests(ServerLevel level) {
        TownWonders b = book();
        CompoundTag saved = b.save(new CompoundTag(), level.registryAccess());
        b.towns = load(saved, level.registryAccess()).towns;
        CELLS.clear();
        RESTORED.clear();
        HELD.clear();
        CREWS.clear();
    }

    /**
     * /village wonder stage: a wonder set out where the operator stands, part built in its stages for the pictures,
     * its scaffold up and stand-in hands at work round it. Says where to look from.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at, String kindName, int percent) {
        Kind k = Kind.of(kindName);
        if (k == null) k = Kind.MINSTER;
        int[] full = Blueprints.fullHalf(k.structure);
        com.jrpetty.mcassistant.Showcase.stage(level, at.getX() - full[0] - 6, at.getX() + full[0] + 6, at.getZ() - full[1] - 8, at.getZ() + full[1] + 6, at.getY());
        CompoundTag w = new CompoundTag();
        w.putString("structure", k.structure);
        w.putString("title", k.name);
        w.putLong("site", at.asLong());
        w.putInt("facing", Direction.NORTH.get2DDataValue());
        w.putString("family", WorksPlans.Family.STONE_BRICKS.name());
        w.putString("family2", WorksPlans.Family.DEEPSLATE.name());
        UUID key = UUID.nameUUIDFromBytes(("stage" + at).getBytes());
        CELLS.remove(key);
        List<Cell> cells = cells(key, w);
        int upTo = cells.size() * Math.max(0, Math.min(100, percent)) / 100;
        for (int i = 0; i < upTo; i++) {
            BuildGoal.Placement p = cells.get(i).p();
            BlockState st = freeState(w, p);
            if (st != null) BuildGoal.stampOnly(level, k.structure, at, Direction.NORTH, 13, pl -> st, pl -> pl.pos().equals(p.pos()));
        }
        int top = upTo == 0 ? 0 : cells.get(upTo - 1).h();
        for (BlockPos base : poles(w)) {
            for (int h = 0; h <= top + 2; h++) if (level.getBlockState(base.above(h)).isAir()) level.setBlock(base.above(h), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        }
        CELLS.remove(key);
        String[] names = { "Wren", "Tom", "Pip", "Ada", "Nell", "Kit" };
        for (int i = 0; i < names.length; i++) {
            BlockPos p = Culture.at(new Ledger.Building(k.structure, at, Direction.NORTH), -3 + i, 0, -full[1] - 3);
            VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (f == null) continue;
            f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, -20.0F);
            f.makeShowcase(AssistantEntity.StationTask.NONE);
            f.rename(names[i]);
            f.addTag("folk_lineup");
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(i % 2 == 0 ? Items.STONE_BRICKS : Items.IRON_PICKAXE));
            if (level.addFreshEntity(f) && i == 0) FolkTalk.speak(f, "Steady on the hoist!");
        }
        List<String> out = new ArrayList<>();
        out.add("WONDER " + k.name + " " + percent + "% at " + at.toShortString());
        out.add("VIEW wonder-front " + (at.getX() + 14) + " " + (at.getY() + 8) + " " + (at.getZ() - full[1] - 16) + " " + at.getX() + " " + (at.getY() + 6) + " " + at.getZ());
        out.add("VIEW wonder-side " + (at.getX() - 20) + " " + (at.getY() + 12) + " " + at.getZ() + " " + at.getX() + " " + (at.getY() + 5) + " " + at.getZ());
        return out;
    }
}
