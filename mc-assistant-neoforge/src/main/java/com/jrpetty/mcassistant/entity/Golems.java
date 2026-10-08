package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [golems] The golem keeper, and the town's golems: built the game's own way, posted, mended and mourned.
 *
 * <p><b>The trade.</b> An Iron Age town that has been raided twice in a fortnight, or has sixty folk, takes up a golem
 * keeper (StationTask.GOLEMS): one, from its own folk if nobody takes it up of themselves (appoint). It works out of the
 * golem yard, an open timber shed by the square with a crafting table and an anvil (blueprints/golemyard.txt).
 *
 * <p><b>An iron golem, built the real way.</b> At the yard the keeper makes blocks of iron of the stores' ingots, nine
 * to a block, as the crafting grid does, until it has four. Then at the golem's post it stands them in a T, a block for
 * the legs, one for the body and one for each arm, and sets a pumpkin on top: a carved one from the stores, or a farm
 * pumpkin it carves where it sits with the stores' shears (four pumpkin seeds fall out of it, and go into the stores).
 * The pumpkin is set down the ordinary way, so the game's own check (CarvedPumpkinBlock: the T of iron under a carved
 * pumpkin) takes the blocks up and stands an iron golem there, a golem made by hand, which never turns on the town's folk.
 * The keeper names it (Ironside, Old Rust...).
 * <ul>
 * <li><b>How many.</b> A golem on the square; one at every gate when the raids are frequent (two in a fortnight);
 *     two more about the square in a big town (eighty folk). Never one the town cannot afford: thirty-six ingots, and
 *     never if that would leave the stores short of the iron the watch's armour and blades still want (WatchKit).</li>
 * <li><b>Its post.</b> Each golem keeps to its post (the gate, the square): it may go after a monster (WatchClears), and
 *     then it walks back (tick, every two seconds).</li>
 * <li><b>Mending.</b> A hurt golem is mended with iron ingots as a player mends one, twenty-five health to an ingot, by
 *     the keeper on its rounds, out of the stores; its cracks fade as it mends (the game draws them by its health).</li>
 * <li><b>Losses.</b> A golem that falls is mourned a little ("Old Rust fell at the east gate"), the iron it drops is
 *     left lying for the keeper to gather into the stores on its next round, and the post is built again when the town
 *     can afford it.</li>
 * <li><b>The golem from before.</b> A golem already about the town (the town's own older one, TownWork.golem) is the
 *     keeper's to look after: named, if it has no name, and given a post.</li>
 * </ul>
 *
 * <p><b>Snow golems in winter.</b> In the town's winter (Seasons), where the biome is not one a snow golem melts in, the
 * keeper builds snow golems on the watchtower's deck: two blocks of snow (four snowballs to a block: the sweeper's, or
 * snow shovelled where it lies) and a pumpkin, and the game's check stands each one up to pelt what comes at the town
 * with snowballs. In spring they are let go, and melt away. In a warm biome it builds none, and says why.
 *
 * <p>The town's books: the chronicle and the gazette (a golem raised, fallen, the winter's snow golems), the board, the
 * keeper's card, its trade's book, /village golems, and the smoke stage's pictures.
 */
public final class Golems {

    private Golems() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The golem yard. */
    public static final String STRUCTURE = "golemyard";
    /** The mark on the town's golems, and the town's own after it. */
    public static final String TAG = "mca_golem";
    /** Iron to a golem: four blocks of nine ingots; health an ingot mends. */
    public static final int BLOCKS = 4, INGOTS_A_BLOCK = 9, IRON_EACH = BLOCKS * INGOTS_A_BLOCK;
    public static final float MEND = 25.0F;
    /** Snowballs to a block of snow, and blocks to a snow golem. */
    static final int SNOWBALLS = 4, SNOW_BLOCKS = 2;
    /** The town takes up a keeper at this many folk, or after so many raids in so many days. */
    public static final int FROM_FOLK = 60, RAIDS = 2, FORTNIGHT = 14;
    /** A big town wants two more golems about its square. */
    public static final int BIG = 80;
    /** A golem's beat round its post; and how far it may be before it is walked back. */
    static final int BEAT = 6;
    /** A piece of the keeper's work this often, for a new hand. */
    static final int EVERY = 240;
    /** How long an errand may take before it is given up for now. */
    static final long ERRAND_TIME = 1600L;

    static final String[] NAMES = { "Ironside", "Old Rust", "Bolt", "Rivet", "Big Tam", "Clank", "Gatepost", "Old Faithful",
        "Ingot", "Stalwart", "Hob", "Sentinel", "Bellows", "Tinker" };
    static final String[] SNOW_NAMES = { "Frosty", "Sleet", "Old Flurry", "Snowdrop", "Nip", "Hailstone", "Drift", "Yule" };

    public enum Kind { IRON, SNOW }

    /** A golem the town keeps: who, what, its post (where it was built, or given), and since when. */
    static final class Kept {
        UUID golem;
        String name;
        Kind kind;
        String key, where;
        BlockPos post;
        long built;
    }

    /** A golem the town lost: its name, where, when, and whether its iron has been gathered. */
    static final class Fallen {
        String name, where;
        long day;
        BlockPos at;
        boolean gathered;
        final List<UUID> drops = new ArrayList<>();
    }

    /** What the town's golems have come to. */
    static final class Town {
        final List<Kept> kept = new ArrayList<>();
        final List<Fallen> fallen = new ArrayList<>();
        final List<Long> raids = new ArrayList<>();
        int built, mended, ingots, snow, lost, gathered, adopted, blocks, carved, seeds;
        long thawYear = -1, toldWarm = -1;
        String why = "";
    }

    private static final Map<UUID, Town> TOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>(), KEPT_AT = new ConcurrentHashMap<>(),
        STRAYS_AT = new ConcurrentHashMap<>(), APPOINTED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TOWNS.clear();
        LAST.clear();
        ERRANDS.clear();
        LOOKED.clear();
        KEPT_AT.clear();
        STRAYS_AT.clear();
        APPOINTED.clear();
    }

    static Town town(UUID village) {
        return TOWNS.computeIfAbsent(village, Golems::load);
    }

    private static final String KEPT = "golems.kept", FALLEN = "golems.fallen", RAIDED = "golems.raids", BOOKS = "golems.books";

    private static Town load(UUID village) {
        Town t = new Town();
        String kept = Ledger.note(village, KEPT);
        if (kept != null && !kept.isEmpty()) {
            for (String one : kept.split(";")) {
                String[] f = one.split("\\|");
                if (f.length < 7) continue;
                try {
                    Kept k = new Kept();
                    k.golem = UUID.fromString(f[0]);
                    k.name = f[1];
                    k.kind = Kind.valueOf(f[2]);
                    k.key = f[3];
                    k.where = f[4];
                    k.post = pos(f[5]);
                    k.built = Long.parseLong(f[6]);
                    t.kept.add(k);
                } catch (RuntimeException ignored) { }
            }
        }
        String fallen = Ledger.note(village, FALLEN);
        if (fallen != null && !fallen.isEmpty()) {
            for (String one : fallen.split(";")) {
                String[] f = one.split("\\|");
                if (f.length < 5) continue;
                try {
                    Fallen x = new Fallen();
                    x.name = f[0];
                    x.where = f[1];
                    x.day = Long.parseLong(f[2]);
                    x.at = pos(f[3]);
                    x.gathered = f[4].equals("1");
                    t.fallen.add(x);
                } catch (RuntimeException ignored) { }
            }
        }
        String raids = Ledger.note(village, RAIDED);
        if (raids != null && !raids.isEmpty()) {
            for (String d : raids.split(",")) {
                try { t.raids.add(Long.parseLong(d.trim())); } catch (NumberFormatException ignored) { }
            }
        }
        Map<String, String> b = new HashMap<>();
        String books = Ledger.note(village, BOOKS);
        if (books != null) for (String part : books.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) b.put(part.substring(0, eq), part.substring(eq + 1));
        }
        t.built = num(b, "built");
        t.mended = num(b, "mended");
        t.ingots = num(b, "ingots");
        t.snow = num(b, "snow");
        t.lost = num(b, "lost");
        t.gathered = num(b, "gathered");
        t.adopted = num(b, "adopted");
        t.blocks = num(b, "blocks");
        t.carved = num(b, "carved");
        t.seeds = num(b, "seeds");
        t.thawYear = b.containsKey("thaw") ? num(b, "thaw") : -1;
        return t;
    }

    static void save(UUID village, Town t) {
        List<String> kept = new ArrayList<>();
        for (Kept k : t.kept) kept.add(k.golem + "|" + clean(k.name) + "|" + k.kind + "|" + k.key + "|" + clean(k.where) + "|" + pos(k.post) + "|" + k.built);
        Ledger.note(village, KEPT, String.join(";", kept));
        List<String> fallen = new ArrayList<>();
        for (Fallen x : t.fallen) fallen.add(clean(x.name) + "|" + clean(x.where) + "|" + x.day + "|" + pos(x.at) + "|" + (x.gathered ? 1 : 0));
        while (fallen.size() > 12) fallen.remove(0);
        Ledger.note(village, FALLEN, String.join(";", fallen));
        List<String> raids = new ArrayList<>();
        for (Long d : t.raids) raids.add(Long.toString(d));
        Ledger.note(village, RAIDED, String.join(",", raids));
        Ledger.note(village, BOOKS, "built=" + t.built + ";mended=" + t.mended + ";ingots=" + t.ingots + ";snow=" + t.snow + ";lost=" + t.lost
            + ";gathered=" + t.gathered + ";adopted=" + t.adopted + ";blocks=" + t.blocks + ";carved=" + t.carved + ";seeds=" + t.seeds
            + ";thaw=" + t.thawYear);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("|", " ").replace(";", " ");
    }

    private static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static BlockPos pos(String s) {
        String[] p = s.split(",");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    private static int num(Map<String, String> m, String k) {
        try { return Integer.parseInt(m.getOrDefault(k, "0").trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** The world's day now (all the levels keep the overworld's clock). */
    private static long today() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0L : server.overworld().getDayTime() / 24000L;
    }

    // ------------------------------------------------------------------ the raids, and the trade

    /** A raid over (Raids.end): its day in the town's books, for the keeper's place and the gates' golems. */
    public static void raidOver(ServerLevel level, Villages.Village v, boolean raid) {
        if (!raid) return;
        noteRaid(v.id(), level.getDayTime() / 24000L);
    }

    static void noteRaid(UUID village, long day) {
        Town t = town(village);
        if (!t.raids.isEmpty() && t.raids.get(t.raids.size() - 1) == day) return;
        t.raids.add(day);
        while (t.raids.size() > 8) t.raids.remove(0);
        save(village, t);
    }

    /** Raids in the last fortnight, to this day. */
    public static int raidsLately(UUID village, long day) {
        int n = 0;
        for (Long d : town(village).raids) if (day - d < FORTNIGHT && d <= day) n++;
        return n;
    }

    /** Are the raids frequent: two in a fortnight? */
    static boolean frequent(UUID village) {
        return raidsLately(village, today()) >= RAIDS;
    }

    /** Does the town want a golem keeper: the Iron Age, and two raids in a fortnight or sixty folk. One at it keeps it open. */
    public static boolean wanted(@Nullable UUID village) {
        if (village == null || Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return false;
        if (keeps(village)) return true;
        return Villages.headcount(village) >= FROM_FOLK || frequent(village);
    }

    public static boolean keeps(@Nullable UUID village) {
        return keeper(village) != null;
    }

    /** The town's golem keeper, or null. */
    @Nullable
    public static VillageFolkEntity keeper(@Nullable UUID village) {
        if (village == null) return null;
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.GOLEMS && !f.isBaby() && f.isAlive() && !f.isShowcase()
                && (best == null || f.tradeLevel(StationTask.GOLEMS) > best.tradeLevel(StationTask.GOLEMS))) best = f;
        }
        return best;
    }

    /**
     * How well a folk would keep the town's golems, for appoint: its years at it, then a strong back used to iron and
     * stone (a miner, a smelter), a hand with nothing to do, and a hardworking, steady nature. Never a craft's own hand,
     * the watch, the storehouse's, a cave dweller, a scout or the ferryman, nor a trade the town is short of.
     */
    static int fitness(VillageFolkEntity f, UUID village) {
        if (f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.trip() != null || f.expedition() != null) return Integer.MIN_VALUE;
        StationTask t = f.stationTask();
        if (t == StationTask.GOLEMS) return 1000 + f.tradeLevel(t);
        if (t.isCraft() || t == StationTask.GUARD || t == StationTask.STORE || t == StationTask.HAUL || t == StationTask.CAVE || t == StationTask.NETHER   // [nether]
            || t == StationTask.SCOUT || t == StationTask.FERRY) return Integer.MIN_VALUE;
        if (t != StationTask.NONE && Villages.share(village, t) < 0.5) return Integer.MIN_VALUE;
        return f.tradeLevel(StationTask.GOLEMS) * 5 + (t == StationTask.NONE ? 30 : t == StationTask.MINE || t == StationTask.SMELT ? 18 : 0)
            + f.tradeLevel(StationTask.MINE) + f.tradeLevel(StationTask.SMELT) + (f.life().has(Social.Trait.HARDWORKING) ? 6 : 0)
            + (f.life().has(Social.Trait.GRUMPY) ? 3 : 0) + (f.life().has(Social.Trait.EASYGOING) ? -2 : 0);
    }

    /** The few best for the keeper's place, best first ([interviews] the seam's shortlist). */
    public static List<VillageFolkEntity> shortlist(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        Map<VillageFolkEntity, Integer> score = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() == StationTask.GOLEMS) continue;
            int s = fitness(f, village);
            if (s == Integer.MIN_VALUE) continue;
            score.put(f, s + Interviews.preferred(village, "golemkeeper", f));  // [interviews] the panel's choice first
            out.add(f);
        }
        out.sort((a, b) -> score.get(b) - score.get(a));
        return out.size() > 4 ? new ArrayList<>(out.subList(0, 4)) : out;
    }

    /**
     * The keeper's place filled from the town's own folk, if it is wanted and empty: the best on paper takes it.
     *
     * <p>[interviews] The seam: once the town holds interviews for its places (Interviews), this shortlist goes before
     * the panel and the winner takes the place; till then the best on paper has it.
     */
    @Nullable
    public static VillageFolkEntity appoint(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!wanted(id) || keeps(id)) return null;
        List<VillageFolkEntity> shortlist = shortlist(id);
        if (shortlist.isEmpty()) return null;
        VillageFolkEntity best = shortlist.get(0);
        StationTask was = best.stationTask();
        if (!best.takeUpTrade(StationTask.GOLEMS)) best.setJob(StationTask.GOLEMS);
        if (best.stationTask() != StationTask.GOLEMS) return null;
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, best.displayNameCap() + " " + (was == StationTask.NONE ? "took up" : "gave up " + was.label + " for")
            + " keeping the town's golems" + (frequent(id) ? ", after the raids" : ""));
        best.persona().remember(day, "I became the town's golem keeper", 4);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "Golem keeper! Iron and pumpkins, and a lot of patience.",
            "The town's golems are mine to keep now. Nothing comes through a gate I've a golem at.",
            "Four blocks of iron and a pumpkin. How hard can it be?"));
        LOG.info("[MCA-GOLEMS] {} of {} took up keeping the golems (was {})", best.displayNameCap(), Villages.name(id), was);
        return best;
    }

    // ------------------------------------------------------------------ the yard

    @Nullable
    public static Ledger.Building yard(@Nullable UUID village) {
        if (village == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) return b;
        return null;
    }

    /** Does the town want its golem yard built: a keeper at work and none yet. */
    public static boolean yardWanted(@Nullable UUID village) {
        return village != null && keeps(village) && !Villages.hasBuilt(village, STRUCTURE) && yard(village) == null;
    }

    public static String why(UUID village) {
        VillageFolkEntity k = keeper(village);
        return "a golem yard, a shed with a crafting table and an anvil where the iron is made into blocks for the golems: "
            + (k == null ? "the town's golem keeper" : k.displayNameCap()) + " works at a post on the square till it stands";
    }

    static BlockPos at(Ledger.Building b, int dx, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz);
    }

    /** Where the keeper works between its errands: in the yard, else its post. */
    static BlockPos bench(VillageFolkEntity f, UUID village) {
        Ledger.Building y = yard(village);
        if (y != null) return at(y, 0, 0);
        return f.workZone() != null ? f.workZone().center() : f.blockPosition();
    }

    // ------------------------------------------------------------------ the posts

    /** A golem's post: its key ("gate/north", "square"), in words, near where, the way the T's arms lie, and its face. */
    public record Post(String key, String words, BlockPos near, Direction across, Direction face) {}

    /**
     * The posts the town wants a golem at, the most wanted first: every gate when the raids are frequent, the square
     * always, and two more about the square in a big town.
     */
    public static List<Post> posts(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Post> out = new ArrayList<>();
        BlockPos c = v.centre();
        if (frequent(id)) {
            for (Watch.Gate g : Watch.gates(level, id)) {
                BlockPos near = g.inside().relative(g.out().getOpposite(), 3);
                out.add(new Post("gate/" + g.out().getName(), "the " + g.out().getName() + " gate", near, g.out().getClockWise(), g.out()));
            }
        }
        out.add(new Post("square", "the square", c.offset(4, 0, 4), Direction.EAST, Direction.SOUTH));
        if (Villages.headcount(id) >= BIG) {
            out.add(new Post("square/west", "the west side of the square", c.offset(-6, 0, 2), Direction.NORTH, Direction.WEST));
            out.add(new Post("square/north", "the north side of the square", c.offset(2, 0, -6), Direction.EAST, Direction.NORTH));
        }
        return out;
    }

    /** The first post with no golem of the town's at it, or null. */
    @Nullable
    static Post postWanted(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        for (Post p : posts(level, v)) {
            boolean held = false;
            for (Kept k : t.kept) if (k.kind == Kind.IRON && k.key.equals(p.key())) held = true;
            if (!held) return p;
        }
        return null;
    }

    /** Is this a block a keeper clears for the T (air, or a plant or a snow layer underfoot: no fluid, nothing built)? */
    private static boolean clearable(BlockState s) {
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /**
     * Ground for an iron golem's T near here: a sturdy floor, and the base, the body, the head, both arms and the four
     * corners beside the legs and the head free (the game's check wants air there). The base block's place, or null.
     */
    @Nullable
    static BlockPos tSpot(ServerLevel level, BlockPos near, Direction across, int r) {
        for (int d = 0; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    int x = near.getX() + dx, z = near.getZ() + dz;
                    if (!level.isLoaded(new BlockPos(x, near.getY(), z))) continue;
                    BlockPos base = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (Math.abs(base.getY() - near.getY()) > 3) continue;
                    if (tFits(level, base, across)) return base;
                }
            }
        }
        return null;
    }

    static boolean tFits(ServerLevel level, BlockPos base, Direction across) {
        BlockPos floor = base.below();
        BlockState f = level.getBlockState(floor);
        if (!f.isFaceSturdy(level, floor, Direction.UP) || !f.getFluidState().isEmpty()) return false;
        for (BlockPos p : tCells(base, across)) if (!clearable(level.getBlockState(p))) return false;
        // Room to stand up in: nothing over the head, and the arms clear of a wall.
        return clearable(level.getBlockState(base.above(3)));
    }

    /** Every place the T and its air need: the legs, body, head, the arms, and the corners by the legs and the head. */
    static List<BlockPos> tCells(BlockPos base, Direction across) {
        BlockPos body = base.above(), head = base.above(2);
        return List.of(base, body, head, body.relative(across), body.relative(across.getOpposite()),
            base.relative(across), base.relative(across.getOpposite()), head.relative(across), head.relative(across.getOpposite()));
    }

    // ------------------------------------------------------------------ the iron, and whether the town can spare it

    /**
     * The iron the watch's kit still wants of the stores: for every piece of armour and every blade the guards (and the
     * cave team) wear worse than iron, the ingots it takes, less what is made and waiting in the stores (WatchKit.wanting).
     */
    public static int ironForTheWatch(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (WatchKit.Kind k : WatchKit.Kind.values()) {
            Item it = WatchKit.piece(k, WatchKit.Metal.IRON);
            if (it == null) continue;
            int want = WatchKit.wanting(level, v, k, it);
            if (want > 0) n += want * k.takes;
        }
        return n;
    }

    /** The stores' iron, in ingots: the ingots, and the blocks at nine. */
    static int ironInStores(ServerLevel level, Villages.Village v) {
        return Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) + INGOTS_A_BLOCK * Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK));
    }

    /**
     * Can the town afford an iron golem now: its thirty-six ingots, with the iron the watch's kit still wants left over,
     * and a pumpkin (carved, or a farm pumpkin and shears to carve it)? Null if so; else why not, in words.
     */
    @Nullable
    public static String cannotAfford(ServerLevel level, Villages.Village v) {
        int iron = ironInStores(level, v), watch = ironForTheWatch(level, v);
        if (iron < IRON_EACH) return "short of iron: " + iron + " ingots in the stores, and a golem takes " + IRON_EACH;
        if (iron - IRON_EACH < watch) return "short of iron for the watch: a golem's " + IRON_EACH + " ingots would leave " + (iron - IRON_EACH)
            + " of the " + watch + " the watch's armour and blades still want";
        if (!pumpkin(level, v)) return "no pumpkin for its head (a carved one, or a farm pumpkin and shears to carve it)";
        return null;
    }

    /** A pumpkin for a golem's head: a carved one, or a farm pumpkin and the shears to carve it. */
    static boolean pumpkin(ServerLevel level, Villages.Village v) {
        return Crafts.stock(level, v, s -> s.is(Items.CARVED_PUMPKIN)) > 0
            || Crafts.stock(level, v, s -> s.is(Items.PUMPKIN)) > 0 && Crafts.stock(level, v, s -> s.is(Items.SHEARS)) > 0;
    }

    /** A block of iron, of nine of the stores' ingots, at the crafting table. */
    static boolean ironBlock(ServerLevel level, Villages.Village v) {
        if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), INGOTS_A_BLOCK)) return false;
        Crafts.store(level, v, new ItemStack(Items.IRON_BLOCK));
        Town t = town(v.id());
        t.blocks++;
        save(v.id(), t);
        return true;
    }

    // ------------------------------------------------------------------ raising a golem, the game's way

    /**
     * The head set on a body, the game's way: a carved pumpkin from the stores set down, or a farm pumpkin set down and
     * carved where it sits with the stores' shears, as a player carves one (the seeds it gives into the stores, the
     * shears a little the worse). Either way the carved pumpkin is placed the ordinary way, so its own check
     * (CarvedPumpkinBlock.onPlace) stands up whatever golem the blocks under it make. False if there was no head to
     * be had (nothing is taken then).
     */
    static boolean head(ServerLevel level, Villages.Village v, BlockPos head, Direction face, @Nullable VillageFolkEntity by) {
        BlockState carved = Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, face);
        if (Crafts.take(level, v, s -> s.is(Items.CARVED_PUMPKIN), 1)) {
            level.setBlock(head, carved, 3);
            level.playSound(null, head, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
            return true;
        }
        if (Crafts.stock(level, v, s -> s.is(Items.PUMPKIN)) < 1) return false;
        ItemStack shears = Crafts.takeOne(level, v, s -> s.is(Items.SHEARS));
        if (shears.isEmpty()) return false;
        try {
            if (!Crafts.take(level, v, s -> s.is(Items.PUMPKIN), 1)) return false;
            level.setBlock(head, Blocks.PUMPKIN.defaultBlockState(), 3);
            level.playSound(null, head, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
            // Carved where it sits (PumpkinBlock's shears): the face cut, four seeds out of it.
            level.playSound(null, head, SoundEvents.PUMPKIN_CARVE, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.setBlock(head, carved, 11);
            Crafts.store(level, v, new ItemStack(Items.PUMPKIN_SEEDS, 4));
            if (by != null) shears.hurtAndBreak(1, level, by, item -> { });
            Town t = town(v.id());
            t.carved++;
            t.seeds += 4;
            save(v.id(), t);
            return true;
        } finally {
            if (!shears.isEmpty()) Crafts.store(level, v, shears);
        }
    }

    /**
     * An iron golem raised here, the game's way: the T of four of the stores' blocks of iron (the base, the body and the
     * arms across), the ground about it cleared, and the head set on it (head); the game stands the golem up. Returns
     * the golem, or null (the blocks back into the stores if no golem came of them).
     */
    @Nullable
    static IronGolem raise(ServerLevel level, Villages.Village v, BlockPos base, Direction across, Direction face, @Nullable VillageFolkEntity by) {
        if (!tFits(level, base, across)) return null;
        if (Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK)) < BLOCKS || !pumpkin(level, v)) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.IRON_BLOCK), BLOCKS)) return null;
        for (BlockPos p : tCells(base, across)) if (!level.getBlockState(p).isAir()) level.removeBlock(p, false);   // the grass cut back
        BlockPos body = base.above(), head = base.above(2);
        Set<UUID> before = new HashSet<>();
        for (IronGolem g : level.getEntitiesOfClass(IronGolem.class, new AABB(base).inflate(4))) before.add(g.getUUID());
        for (BlockPos p : new BlockPos[]{ base, body, body.relative(across), body.relative(across.getOpposite()) }) {
            level.setBlock(p, Blocks.IRON_BLOCK.defaultBlockState(), 3);
            level.playSound(null, p, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
        }
        if (by != null) {
            by.getLookControl().setLookAt(head.getX() + 0.5, head.getY() + 0.5, head.getZ() + 0.5);
            by.swing(InteractionHand.MAIN_HAND);
        }
        if (!head(level, v, head, face, by)) {
            takeDown(level, v, base, across);
            return null;
        }
        IronGolem made = null;
        for (IronGolem g : level.getEntitiesOfClass(IronGolem.class, new AABB(base).inflate(3), g -> !before.contains(g.getUUID()))) made = g;
        if (made == null) {
            // The check did not take (something in the way the game minded): the iron and the head back off it.
            takeDown(level, v, base, across);
            if (level.getBlockState(head).is(Blocks.CARVED_PUMPKIN)) {
                level.removeBlock(head, false);
                Crafts.store(level, v, new ItemStack(Items.CARVED_PUMPKIN));
            }
            return null;
        }
        return made;
    }

    /** The T's iron, off it and back into the stores (a build the game's check did not take). */
    private static void takeDown(ServerLevel level, Villages.Village v, BlockPos base, Direction across) {
        BlockPos body = base.above();
        for (BlockPos p : new BlockPos[]{ base, body, body.relative(across), body.relative(across.getOpposite()) }) {
            if (level.getBlockState(p).is(Blocks.IRON_BLOCK)) {
                level.removeBlock(p, false);
                Crafts.store(level, v, new ItemStack(Items.IRON_BLOCK));
            }
        }
    }

    /** The town's mark on a golem, its name, its post, and its keeping. */
    static Kept register(ServerLevel level, Villages.Village v, AbstractGolem g, Kind kind, String key, String where, BlockPos post) {
        Town t = town(v.id());
        for (Kept k : t.kept) if (k.golem.equals(g.getUUID())) return k;
        Kept k = new Kept();
        k.golem = g.getUUID();
        k.kind = kind;
        k.key = key;
        k.where = where;
        k.post = post.immutable();
        k.built = level.getDayTime() / 24000L;
        k.name = g.hasCustomName() ? g.getCustomName().getString() : freshName(t, kind);
        g.setCustomName(Component.literal(k.name));
        g.addTag(TAG);
        g.addTag(TAG + "/" + v.id());
        g.setPersistenceRequired();
        g.restrictTo(post, kind == Kind.SNOW ? 2 : BEAT);
        t.kept.add(k);
        save(v.id(), t);
        return k;
    }

    private static String freshName(Town t, Kind kind) {
        String[] names = kind == Kind.SNOW ? SNOW_NAMES : NAMES;
        Set<String> used = new HashSet<>();
        for (Kept k : t.kept) used.add(k.name);
        for (Fallen f : t.fallen) used.add(f.name);
        for (String n : names) if (!used.contains(n)) return n;
        return names[t.kept.size() % names.length] + " the " + TownCalendar.ordinal(t.kept.size() / names.length + 2);
    }

    /**
     * An iron golem raised at this post by the keeper, now: the T at the nearest ground for it, the stores' blocks and
     * pumpkin, named, posted, in the chronicle. What came of it, or why not (the town's books keep the why).
     */
    static String build(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, Post p) {
        UUID id = v.id();
        Town t = town(id);
        BlockPos base = tSpot(level, p.near(), p.across(), 5);
        if (base == null) return t.why = "no clear ground for the T at " + p.words();
        IronGolem g = raise(level, v, base, p.across(), p.face(), by);
        if (g == null) return t.why = "the game's check did not take at " + p.words();
        Kept k = register(level, v, g, Kind.IRON, p.key(), p.words(), base);
        t.built++;
        t.why = "";
        save(id, t);
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, (by == null ? "the town" : by.displayNameCap()) + " raised an iron golem, " + k.name + ", at " + p.words()
            + ": four blocks of the stores' iron and a carved pumpkin");
        if (by != null) {
            by.persona().remember(day, "I raised " + k.name + ", an iron golem, at " + p.words(), 4);
            by.note(AssistantEntity.Deed.THINGS_MADE, 1);
            FolkTalk.speak(by, FolkTalk.pick(level.getRandom(), "Up you get, " + k.name + ". " + com.jrpetty.mcassistant.village.Quill.cap(p.words()) + "'s yours to keep.",
                "There! " + k.name + "'s on its feet. Mind the gate for us.", "Iron and a pumpkin, and up it stands. Never gets old."));
        }
        LOG.info("[MCA-GOLEMS] {} raised {} at {} ({})", by == null ? "the town" : by.displayNameCap(), k.name, p.words(), base.toShortString());
        return "raised " + k.name + " at " + p.words();
    }

    /**
     * [golems] The town's first golem, where it has no keeper (TownWork.golem): the same T and pumpkin, by the town's
     * hand at the square, out of the stores (four blocks of iron, made of ingots if need be). The golem, or null.
     */
    @Nullable
    public static IronGolem raiseForTown(ServerLevel level, Villages.Village v) {
        int blocks = Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK));
        int ingots = Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT));
        if (blocks + ingots / INGOTS_A_BLOCK < BLOCKS || !pumpkin(level, v)) return null;
        Post square = null;
        for (Post p : posts(level, v)) if (p.key().equals("square")) square = p;
        if (square == null) return null;
        BlockPos base = tSpot(level, square.near(), square.across(), 6);
        if (base == null) return null;
        if (!TownJobs.atWork(level, v, "golem", base, "building an iron golem")) return null;
        while (Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK)) < BLOCKS) if (!ironBlock(level, v)) return null;
        IronGolem g = raise(level, v, base, square.across(), square.face(), null);
        if (g == null) return null;
        register(level, v, g, Kind.IRON, square.key(), square.words(), base);
        Town t = town(v.id());
        t.built++;
        save(v.id(), t);
        return g;
    }

    // ------------------------------------------------------------------ mending

    /**
     * A hurt golem mended with the stores' iron ingots, as a player mends one: twenty-five health an ingot, an ingot at a
     * time, up to so many, till it is whole. Its cracks fade as it mends (the game draws them by its health). Returns
     * the ingots used.
     */
    static int mend(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, IronGolem g, int most) {
        int used = 0;
        while (used < most && g.isAlive() && g.getHealth() < g.getMaxHealth()) {
            if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1)) break;
            g.heal(MEND);
            float pitch = 1.0F + (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F;
            level.playSound(null, g.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0F, pitch);
            used++;
        }
        if (used > 0) {
            Town t = town(v.id());
            t.mended++;
            t.ingots += used;
            save(v.id(), t);
            if (by != null) {
                by.swing(InteractionHand.MAIN_HAND);
                by.getLookControl().setLookAt(g, 30.0F, 30.0F);
                by.note(AssistantEntity.Deed.THINGS_MADE, 1);
            }
        }
        return used;
    }

    /** The most hurt of the town's iron golems that wants mending (a whole ingot's worth down), or null. */
    @Nullable
    static IronGolem mostHurt(ServerLevel level, Town t) {
        IronGolem worst = null;
        for (Kept k : t.kept) {
            if (k.kind != Kind.IRON || !(level.getEntity(k.golem) instanceof IronGolem g) || !g.isAlive()) continue;
            if (g.getMaxHealth() - g.getHealth() < MEND * 0.8F) continue;
            if (worst == null || g.getHealth() / g.getMaxHealth() < worst.getHealth() / worst.getMaxHealth()) worst = g;
        }
        return worst;
    }

    /** How a golem is, in a few words: "100 of 100", "cracked, 40 of 100". */
    static String state(AbstractGolem g) {
        String cracks = g instanceof IronGolem ig ? switch (ig.getCrackiness()) {
            case NONE -> "";
            case LOW -> "a little cracked, ";
            case MEDIUM -> "cracked, ";
            case HIGH -> "badly cracked, ";
        } : "";
        return cracks + Math.round(g.getHealth()) + " of " + Math.round(g.getMaxHealth());
    }

    // ------------------------------------------------------------------ the golems at their posts

    /**
     * Every two seconds (Raids' clock): the town's golems kept to their posts. One gone after a monster comes back when
     * the fight is done; a golem the world has lost track of (unloaded) is left be. Nothing looked for but the town's own.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - KEPT_AT.getOrDefault(id, -100000L) < 40L) return;
        KEPT_AT.put(id, now);
        Town t = TOWNS.get(id);
        if (t == null) {
            if (Ledger.note(id, KEPT) == null) return;               // a town that never had a golem of the keeper's
            t = town(id);
        }
        // Spring: the winter's snow golems let go, and they melt away.
        if (!t.kept.isEmpty() && Seasons.season(id, level.getDayTime() / 24000L) != Seasons.Season.WINTER) thaw(level, v, t);
        for (Kept k : t.kept) {
            if (!(level.getEntity(k.golem) instanceof Mob g) || !g.isAlive()) continue;
            if (!g.hasRestriction()) g.restrictTo(k.post, k.kind == Kind.SNOW ? 2 : BEAT);
            if (k.kind == Kind.SNOW) continue;                        // up on the tower: no way back up a ladder for it
            LivingEntity target = g.getTarget();
            if (target != null && target.isAlive()) continue;
            double d = g.distanceToSqr(k.post.getX() + 0.5, k.post.getY(), k.post.getZ() + 0.5);
            if (d > BEAT * BEAT && g.getNavigation().isDone()) g.getNavigation().moveTo(k.post.getX() + 0.5, k.post.getY(), k.post.getZ() + 0.5, 0.7D);
        }
    }

    /** The golems about the town that are nobody's yet (the town's golem from before): the keeper's to look after. */
    static int adopt(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by) {
        UUID id = v.id();
        Town t = town(id);
        int reach = Math.max(48, Villages.townReach(id));
        BlockPos c = v.centre();
        AABB box = new AABB(c).inflate(reach, 24, reach);
        int n = 0;
        for (IronGolem g : level.getEntitiesOfClass(IronGolem.class, box, g -> g.isAlive() && !g.getTags().contains(TAG))) {
            boolean ours = false;
            for (Kept k : t.kept) if (k.golem.equals(g.getUUID())) ours = true;
            if (ours) continue;
            Post p = postWanted(level, v);
            if (p == null) {
                for (Post q : posts(level, v)) if (q.key().equals("square")) p = q;
            }
            if (p == null) continue;
            BlockPos post = tSpot(level, p.near(), p.across(), 5);
            Kept k = register(level, v, g, Kind.IRON, p.key(), p.words(), post == null ? p.near() : post);
            t.adopted++;
            n++;
            save(id, t);
            Villages.tell(id, level.getDayTime() / 24000L, (by == null ? "the town" : by.displayNameCap()) + " took the town's old iron golem in hand: "
                + k.name + ", posted at " + p.words());
            if (by != null) FolkTalk.speak(by, "You've been looking after yourself long enough, " + k.name + ". You're mine now.");
        }
        return n;
    }

    // ------------------------------------------------------------------ snow golems

    /** The places on the watchtowers' decks a snow golem stands: two a tower, either side of the hatch. */
    static List<BlockPos> snowSpots(ServerLevel level, UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!b.structure().equals("watchtower")) continue;
            for (int dx : new int[]{ -1, 1 }) out.add(at(b, dx, -1).above(9));
        }
        return out;
    }

    /** Is it too warm here for a snow golem: a biome the game melts them in? */
    public static boolean warm(ServerLevel level, BlockPos at) {
        return level.getBiome(at).is(BiomeTags.SNOW_GOLEM_MELTS);
    }

    /** Can a snow golem be built here: a sturdy floor under it, and its three blocks clear. */
    static boolean snowFits(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return false;
        BlockState f = level.getBlockState(at.below());
        if (!f.isFaceSturdy(level, at.below(), Direction.UP)) return false;
        for (int up = 0; up < 3; up++) if (!clearable(level.getBlockState(at.above(up)))) return false;
        return true;
    }

    /** Snow for one: the stores' blocks of snow, else their snowballs packed four to a block. Whether there were two blocks. */
    static boolean snowBlocks(ServerLevel level, Villages.Village v) {
        int have = Crafts.stock(level, v, s -> s.is(Items.SNOW_BLOCK));
        while (have < SNOW_BLOCKS) {
            if (!Crafts.take(level, v, s -> s.is(Items.SNOWBALL), SNOWBALLS)) return false;
            Crafts.store(level, v, new ItemStack(Items.SNOW_BLOCK));
            have++;
        }
        return true;
    }

    /**
     * A snow golem built on a watchtower now, in the winter, where the town is not too warm: two blocks of snow stacked
     * on the deck and a pumpkin set on them, and the game's check stands it up. What came of it, or why not.
     */
    static String buildSnow(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, boolean anySeason) {
        UUID id = v.id();
        Town t = town(id);
        long day = level.getDayTime() / 24000L;
        if (!anySeason && Seasons.season(id, day) != Seasons.Season.WINTER) return "not winter";
        List<BlockPos> spots = snowSpots(level, id);
        if (spots.isEmpty()) return t.why = "no watchtower to stand snow golems on";
        BlockPos at = null;
        for (BlockPos s : spots) {
            boolean taken = false;
            for (Kept k : t.kept) if (k.kind == Kind.SNOW && k.post.equals(s)) taken = true;
            if (!taken && snowFits(level, s)) { at = s; break; }
        }
        if (at == null) return "every place on the towers has its snow golem";
        if (warm(level, at)) {
            t.why = "too warm here for snow golems: they would melt where they stood";
            int winter = Seasons.year(id, day) * 4 + 3;
            if (t.toldWarm != winter && by != null) {
                t.toldWarm = winter;
                FolkTalk.speak(by, "No snow golems here, I'm afraid. It's too warm: they'd melt before the first raider came.");
            }
            save(id, t);
            return t.why;
        }
        // The snow: the stores' (the sweeper's snowballs, packed four to a block), or shovelled where it lies by the tower.
        int balls = Crafts.stock(level, v, s -> s.is(Items.SNOWBALL)) + SNOWBALLS * Crafts.stock(level, v, s -> s.is(Items.SNOW_BLOCK));
        if (balls < SNOWBALLS * SNOW_BLOCKS && by != null) {
            int got = Winter.gather(level, v, by, at.below(9), SNOWBALLS * SNOW_BLOCKS - balls);   // round the tower's foot
            if (got > 0) Crafts.store(level, v, new ItemStack(Items.SNOWBALL, got));
        }
        if (!pumpkin(level, v)) return t.why = "no pumpkin for a snow golem's head";
        if (!snowBlocks(level, v)) return t.why = "not snow enough for a snow golem (eight snowballs)";
        if (!Crafts.take(level, v, s -> s.is(Items.SNOW_BLOCK), SNOW_BLOCKS)) return t.why = "not snow enough";
        for (int up = 0; up < 3; up++) if (!level.getBlockState(at.above(up)).isAir()) level.removeBlock(at.above(up), false);
        Set<UUID> before = new HashSet<>();
        for (SnowGolem g : level.getEntitiesOfClass(SnowGolem.class, new AABB(at).inflate(3))) before.add(g.getUUID());
        level.setBlock(at, Blocks.SNOW_BLOCK.defaultBlockState(), 3);
        level.setBlock(at.above(), Blocks.SNOW_BLOCK.defaultBlockState(), 3);
        level.playSound(null, at, SoundEvents.SNOW_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (!head(level, v, at.above(2), Direction.SOUTH, by)) {
            for (int up = 0; up < 2; up++) if (level.getBlockState(at.above(up)).is(Blocks.SNOW_BLOCK)) level.removeBlock(at.above(up), false);
            Crafts.store(level, v, new ItemStack(Items.SNOW_BLOCK, SNOW_BLOCKS));
            return t.why = "no pumpkin for a snow golem's head";
        }
        SnowGolem made = null;
        for (SnowGolem g : level.getEntitiesOfClass(SnowGolem.class, new AABB(at).inflate(2), g -> !before.contains(g.getUUID()))) made = g;
        if (made == null) return t.why = "the game's check did not take on the tower";
        Kept k = register(level, v, made, Kind.SNOW, "tower/" + at.getX() + "/" + at.getZ(), "the watchtower", at);
        t.snow++;
        t.why = "";
        save(id, t);
        Villages.tell(id, day, (by == null ? "the town" : by.displayNameCap()) + " built a snow golem, " + k.name
            + ", on the watchtower for the winter, to pelt whatever comes at the town");
        if (by != null) FolkTalk.speak(by, FolkTalk.pick(level.getRandom(), "There, " + k.name + ": the best view in town. Snowball anything that moves out there.",
            "A snow golem on the tower. Let the raiders come and get a snowball in the eye."));
        return "built " + k.name + " on the watchtower";
    }

    /** Spring: the winter's snow golems let go, and melting away. */
    static int thaw(ServerLevel level, Villages.Village v, Town t) {
        UUID id = v.id();
        int n = 0;
        List<String> names = new ArrayList<>();
        for (Kept k : new ArrayList<>(t.kept)) {
            if (k.kind != Kind.SNOW) continue;
            Entity e = level.getEntity(k.golem);
            if (e == null && level.isLoaded(k.post)) {
                t.kept.remove(k);                                         // gone already
                continue;
            }
            if (e == null) continue;
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultBlockState()),
                e.getX(), e.getY() + 1.0, e.getZ(), 30, 0.3, 0.6, 0.3, 0.05);
            level.sendParticles(ParticleTypes.FALLING_WATER, e.getX(), e.getY() + 1.5, e.getZ(), 12, 0.3, 0.4, 0.3, 0.0);
            e.discard();
            t.kept.remove(k);
            names.add(k.name);
            n++;
        }
        if (n > 0) {
            long day = level.getDayTime() / 24000L;
            t.thawYear = Seasons.year(id, day);
            save(id, t);
            Villages.tell(id, day, "spring came, and the snow golems on the watchtower (" + String.join(", ", names) + ") were let go, and melted away");
        }
        return n;
    }

    // ------------------------------------------------------------------ losses

    /** One of the town's golems has fallen: mourned a little, and its iron left lying for the keeper to gather. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractGolem g) || !(g.level() instanceof ServerLevel level) || !g.getTags().contains(TAG)) return;
        Villages.Village v = villageOf(g);
        if (v == null) return;
        UUID id = v.id();
        Town t = town(id);
        Kept k = null;
        for (Kept x : t.kept) if (x.golem.equals(g.getUUID())) k = x;
        if (k == null) return;
        t.kept.remove(k);
        long day = level.getDayTime() / 24000L;
        boolean raid = Raids.raided(id);
        Fallen f = new Fallen();
        f.name = k.name;
        f.where = k.where;
        f.day = day;
        f.at = g.blockPosition();
        f.gathered = k.kind == Kind.SNOW;                               // a snow golem leaves only snowballs, and the thaw takes them
        t.fallen.add(f);
        if (k.kind == Kind.IRON) t.lost++;
        save(id, t);
        if (k.kind == Kind.IRON) {
            Villages.tell(id, day, k.name + " fell at " + k.where + (raid ? " in the raid" : "") + ", the town's iron golem since day " + (k.built + 1));
            VillageFolkEntity keeper = keeper(id);
            int told = 0;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity folk) || folk.isBaby() || folk.distanceToSqr(g) > 48 * 48 || told >= 6) continue;
                folk.persona().remember(day, k.name + ", the iron golem, fell at " + k.where, folk == keeper ? 5 : 2);
                told++;
            }
            if (keeper != null) FolkTalk.speak(keeper, FolkTalk.pick(keeper.getRandom(), k.name + "'s gone. I'll gather what's left of the old lump.",
                "Not " + k.name + "... It stood at " + k.where + " to the last."));
        }
    }

    /** A fallen golem's drops (its iron, its poppies) left lying where it fell till the keeper comes for them. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof IronGolem g) || !g.getTags().contains(TAG)) return;
        Villages.Village v = villageOf(g);
        if (v == null) return;
        Town t = town(v.id());
        Fallen f = t.fallen.isEmpty() ? null : t.fallen.get(t.fallen.size() - 1);
        for (ItemEntity drop : event.getDrops()) {
            if (keeps(v.id())) drop.setUnlimitedLifetime();
            else drop.setExtendedLifetime();
            if (f != null) f.drops.add(drop.getUUID());
        }
    }

    @Nullable
    static Villages.Village villageOf(Entity g) {
        for (String tag : g.getTags()) {
            if (!tag.startsWith(TAG + "/")) continue;
            try {
                return Villages.get(UUID.fromString(tag.substring(TAG.length() + 1)));
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    /** A fallen golem's iron gathered off the ground and into the stores: what it came to. */
    static int gather(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, Fallen f) {
        int ingots = 0, other = 0;
        Set<UUID> done = new HashSet<>();
        List<ItemEntity> lying = new ArrayList<>();
        for (UUID u : f.drops) if (level.getEntity(u) instanceof ItemEntity e && e.isAlive()) lying.add(e);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(f.at).inflate(6, 3, 6),
                e -> e.isAlive() && (e.getItem().is(Items.IRON_INGOT) || e.getItem().is(Items.POPPY)))) lying.add(e);
        for (ItemEntity e : lying) {
            if (!done.add(e.getUUID()) || !e.isAlive()) continue;
            ItemStack s = e.getItem().copy();
            if (s.is(Items.IRON_INGOT)) ingots += s.getCount();
            else other += s.getCount();
            Crafts.store(level, v, s);
            e.discard();
        }
        f.gathered = true;
        Town t = town(v.id());
        t.gathered += ingots;
        save(v.id(), t);
        if (by != null && ingots + other > 0) {
            by.swing(InteractionHand.MAIN_HAND);
            FolkTalk.speak(by, ingots + " ingots of " + f.name + " back to the stores. " + FolkTalk.pick(by.getRandom(), "He'll stand again.", "Rest easy, old iron."));
        }
        return ingots;
    }

    // ------------------------------------------------------------------ the keeper's day

    enum Job { BUILD, MEND, GATHER, SNOW }

    /** An errand the keeper is on: what, where, and for which golem or post. */
    static final class Errand {
        final Job job;
        final BlockPos at;
        @Nullable UUID golem;
        @Nullable Post post;
        @Nullable Fallen fallen;
        final long since;

        Errand(Job job, BlockPos at, long since) {
            this.job = job;
            this.at = at;
            this.since = since;
        }
    }

    /**
     * The keeper's day (its station's work, AssistantEntity): an errand under way (to a golem to mend it, to a post to raise
     * one, to a fallen one's iron, up a tower with the snow), else a piece of its work every so often: the errand chosen,
     * or a block of iron made at the yard. True while it is about it.
     */
    public static boolean duty(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || f.isBaby()) return false;
        Errand e = ERRANDS.get(f.getUUID());
        if (e != null) return errand(f, level, v, e);
        int every = f.pacedTicks(EVERY, 100);
        if (yard(id) == null) every = every * 3 / 2;
        int last = LAST.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < every && f.tickCount >= last) return toBench(f, level, id);
        LAST.put(f.getUUID(), f.tickCount);
        return round(f, level, v) != null;
    }

    static boolean toBench(VillageFolkEntity f, ServerLevel level, UUID village) {
        BlockPos b = bench(f, village);
        if (f.blockPosition().distSqr(b) <= 3 * 3) return false;
        if (f.getNavigation().isDone() || f.tickCount % 60 == 0) f.walkTo(b, 0.9D);
        return true;
    }

    /** The keeper's round: what it sets about next, in order of need. What, or null for nothing to do. */
    @Nullable
    public static String round(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Town t = town(id);
        long now = level.getGameTime();
        // The golem from before, and any the town has come by: looked for once a minute.
        if (now - STRAYS_AT.getOrDefault(id, -100000L) >= 1200L) {
            STRAYS_AT.put(id, now);
            if (adopt(level, v, f) > 0) return "took the town's old golem in hand";
        }
        for (Fallen x : t.fallen) {
            if (x.gathered || level.getDayTime() / 24000L - x.day > 3) continue;
            Errand e = new Errand(Job.GATHER, x.at, now);
            e.fallen = x;
            return start(f, e, "off to gather " + x.name + "'s iron at " + x.where);
        }
        IronGolem hurt = Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) > 0 ? mostHurt(level, t) : null;
        if (hurt != null) {
            Errand e = new Errand(Job.MEND, hurt.blockPosition(), now);
            e.golem = hurt.getUUID();
            return start(f, e, "off to mend " + nameOf(t, hurt.getUUID()) + " (" + state(hurt) + ")");
        }
        long day = level.getDayTime() / 24000L;
        if (Seasons.season(id, day) == Seasons.Season.WINTER) {
            for (BlockPos s : snowSpots(level, id)) {
                boolean taken = false;
                for (Kept k : t.kept) if (k.kind == Kind.SNOW && k.post.equals(s)) taken = true;
                if (taken || !snowFits(level, s)) continue;
                if (warm(level, s)) { buildSnow(level, v, f, false); break; }       // says why, once a winter
                Errand e = new Errand(Job.SNOW, s, now);
                return start(f, e, "up the watchtower with the snow for a snow golem");
            }
        }
        Post p = postWanted(level, v);
        if (p != null) {
            String why = cannotAfford(level, v);
            if (why != null) {
                if (!why.equals(t.why)) {
                    t.why = why;
                    f.brain("no golem for " + p.words() + ": " + why);
                }
                return null;
            }
            // Its blocks first, at the yard: nine ingots to a block.
            if (Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK)) < BLOCKS) {
                Economy.openCraft(id, StationTask.GOLEMS);
                try {
                    if (!ironBlock(level, v)) return null;
                } finally {
                    Economy.closeCraft();
                }
                f.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, f.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.5F, 1.2F);
                f.note(AssistantEntity.Deed.THINGS_MADE, 1);
                f.brain("made a block of iron of nine ingots, for " + p.words() + "'s golem");
                return "a block of iron";
            }
            Errand e = new Errand(Job.BUILD, p.near(), now);
            e.post = p;
            return start(f, e, "off to " + p.words() + " with four blocks of iron and a pumpkin");
        }
        return null;
    }

    private static String start(VillageFolkEntity f, Errand e, String words) {
        ERRANDS.put(f.getUUID(), e);
        f.clearQueue();
        f.brain(words);
        return words;
    }

    /** Walking an errand, and doing it once there. True while it is on it. */
    static boolean errand(VillageFolkEntity f, ServerLevel level, Villages.Village v, Errand e) {
        if (level.getGameTime() - e.since > ERRAND_TIME || Raids.underAlarm(v.id())) {
            ERRANDS.remove(f.getUUID());
            return false;
        }
        BlockPos to = e.at;
        if (e.job == Job.MEND && e.golem != null && level.getEntity(e.golem) instanceof IronGolem g) to = g.blockPosition();
        double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
        double reach = e.job == Job.SNOW ? 5.0 : 3.5;
        boolean there = dx * dx + dz * dz <= reach * reach && (e.job == Job.SNOW || Math.abs(f.getY() - to.getY()) < 4);
        if (!there) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) {
                BlockPos walk = e.job == Job.SNOW ? to.below(9) : to;
                f.walkTo(walk, 1.0D);
            }
            return true;
        }
        f.getNavigation().stop();
        ERRANDS.remove(f.getUUID());
        Economy.openCraft(v.id(), StationTask.GOLEMS);
        try {
            switch (e.job) {
                case MEND -> {
                    if (e.golem != null && level.getEntity(e.golem) instanceof IronGolem g) {
                        int used = mend(level, v, f, g, 4);
                        if (used > 0) {
                            f.brain("mended " + nameOf(town(v.id()), g.getUUID()) + " with " + used + (used == 1 ? " ingot" : " ingots") + ": " + state(g));
                            if (f.getRandom().nextInt(2) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Hold still, you great lump. There.",
                                "An ingot here, an ingot there. Good as new."));
                        }
                    }
                }
                case BUILD -> {
                    if (e.post != null && cannotAfford(level, v) == null) {
                        while (Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK)) < BLOCKS && ironBlock(level, v)) { }
                        f.brain(build(level, v, f, e.post));
                    }
                }
                case GATHER -> {
                    if (e.fallen != null) f.brain("gathered " + gather(level, v, f, e.fallen) + " ingots of " + e.fallen.name);
                }
                case SNOW -> f.brain(buildSnow(level, v, f, false));
            }
        } finally {
            Economy.closeCraft();
        }
        return true;
    }

    static String nameOf(Town t, UUID golem) {
        for (Kept k : t.kept) if (k.golem.equals(golem)) return k.name;
        return "the golem";
    }

    /** Once in ten seconds a town (VillageFolkEntity's town polls): the keeper's place filled if it has been open a day. */
    public static void look(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LOOKED.getOrDefault(id, -100000L) < 200L) return;
        LOOKED.put(id, now);
        if (!wanted(id) || keeps(id)) return;
        long day = level.getDayTime() / 24000L, t = Math.floorMod(level.getDayTime(), 24000L);
        if (t < 2000L || t >= 11000L || APPOINTED.getOrDefault(id, -1L) >= day) return;
        String opened = Ledger.note(id, "golems.opened");
        if (opened == null || opened.isEmpty()) {
            Ledger.note(id, "golems.opened", Long.toString(day));
            return;
        }
        long since;
        try { since = Long.parseLong(opened); } catch (NumberFormatException e) { since = day; }
        if (day - since < 1) return;
        APPOINTED.put(id, day);
        // [interviews] Two or more who want the place: it is held open for its interview, and given after it.
        List<VillageFolkEntity> few = shortlist(id);
        if (few.isEmpty() || !Interviews.vacancy(level, id, "golemkeeper", few.get(0))) appoint(level, v);
    }

    // ------------------------------------------------------------------ what the town sees

    /** What the keeper is doing, in its own words ("What do you do?", FolkTalk.doing). */
    static String doing(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        if (id == null) return "Keeping golems, when I've a town to keep them for.";
        Errand e = ERRANDS.get(f.getUUID());
        Town t = town(id);
        if (e != null) return switch (e.job) {
            case BUILD -> "Off to " + (e.post == null ? "a post" : e.post.words()) + " to raise a golem: four blocks of iron in a T, and a pumpkin on top.";
            case MEND -> "On my way to mend " + (e.golem == null ? "a golem" : nameOf(t, e.golem)) + ". An ingot mends a fair bit of hurt.";
            case GATHER -> "Gathering what's left of " + (e.fallen == null ? "a golem" : e.fallen.name) + ". The iron goes back to the stores.";
            case SNOW -> "Up the watchtower with the snow. A snow golem up there will give the raiders something to think about.";
        };
        int iron = 0, snow = 0;
        for (Kept k : t.kept) if (k.kind == Kind.IRON) iron++; else snow++;
        return FolkTalk.pick(r,
            "Keeping the town's golems: " + iron + (iron == 1 ? " iron golem" : " iron golems") + (snow > 0 ? " and " + snow + " of snow" : "") + " at their posts.",
            "Iron golems don't make themselves. Nine ingots a block, four blocks and a pumpkin a golem.",
            t.why.isEmpty() ? "Every golem whole and at its post. A quiet day's a good day." : "No new golem yet: " + t.why + ".");
    }

    /** The keeper's card: the town's golems, each at its post and how it is, and what it waits on. Null for anybody else. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.GOLEMS || f.isBaby() || f.ownerId() == null || !(f.level() instanceof ServerLevel level)) return null;
        Town t = town(f.ownerId());
        List<String> each = new ArrayList<>();
        for (Kept k : t.kept) {
            Entity e = level.getEntity(k.golem);
            each.add(k.name + " at " + k.where + (e instanceof AbstractGolem g ? " (" + state(g) + ")" : ""));
        }
        String line = (each.isEmpty() ? "no golems yet" : String.join("; ", each)) + ". Raised " + t.built + ", mended " + t.mended
            + (t.mended == 1 ? " time" : " times") + " with " + t.ingots + " ingots" + (t.lost > 0 ? ", lost " + t.lost : "");
        if (!t.why.isEmpty()) line += ". Waiting: " + t.why;
        return line;
    }

    /** The trade's book (TradeBooks.notes): the golems' real numbers, in the keeper's own words. */
    static List<String> bookNotes(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        List<String> out = new ArrayList<>();
        if (t.built > 0) out.add("We've raised " + com.jrpetty.mcassistant.village.Quill.count(t.built, "iron golem", "iron golems") + ", "
            + IRON_EACH * t.built + " ingots of the town's iron, " + t.blocks + " blocks made at the yard.");
        if (t.mended > 0) out.add("We've mended the golems " + com.jrpetty.mcassistant.village.Quill.count(t.mended, "time", "times") + ", with "
            + t.ingots + " ingots: twenty-five health to an ingot, as the old folk did.");
        if (t.lost > 0) out.add(com.jrpetty.mcassistant.village.Quill.count(t.lost, "golem", "golems") + " fell keeping the town, and "
            + t.gathered + " ingots of them were gathered back for the next.");
        if (t.snow > 0) out.add(com.jrpetty.mcassistant.village.Quill.count(t.snow, "snow golem", "snow golems") + " stood on the towers through the winters.");
        return out;
    }

    /** The board's word on the golems, or null. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        Town t = TOWNS.get(village);
        if (t == null && !keeps(village)) return null;
        t = town(village);
        if (t.kept.isEmpty() && !keeps(village)) return null;
        List<String> each = new ArrayList<>();
        int snow = 0;
        for (Kept k : t.kept) {
            if (k.kind == Kind.SNOW) { snow++; continue; }
            Entity e = level.getEntity(k.golem);
            each.add(k.name + " at " + k.where + (e instanceof IronGolem g && g.getCrackiness() != net.minecraft.world.entity.Crackiness.Level.NONE ? " (cracked)" : ""));
        }
        String line = "The golems: " + (each.isEmpty() ? "none yet" : String.join(", ", each)) + (snow > 0 ? "; " + snow + " snow golems on the watchtower" : "");
        if (!t.fallen.isEmpty()) {
            Fallen last = t.fallen.get(t.fallen.size() - 1);
            if (level.getDayTime() / 24000L - last.day <= 2) line += ". " + last.name + " fell at " + last.where;
        }
        if (!t.why.isEmpty() && keeps(village)) line += ". Waiting: " + t.why;
        return line + ".";
    }

    /** The whole of it, for /village golems. */
    public static List<String> report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Town t = town(id);
        List<String> out = new ArrayList<>();
        VillageFolkEntity k = keeper(id);
        long day = level.getDayTime() / 24000L;
        out.add("GOLEMS of " + Villages.name(id) + ": the keeper " + (k == null ? (wanted(id) ? "wanted, nobody at it yet" : "not wanted yet (the Iron Age, and two raids in "
            + FORTNIGHT + " days or " + FROM_FOLK + " folk)") : k.displayNameCap() + " (level " + k.tradeLevel(StationTask.GOLEMS) + ")")
            + "; raids in the last fortnight: " + raidsLately(id, day));
        Ledger.Building y = yard(id);
        out.add("The yard: " + (y == null ? (yardWanted(id) ? "on the builders' list" : "none") : "at " + y.anchor().toShortString()));
        List<String> posts = new ArrayList<>();
        for (Post p : posts(level, v)) posts.add(p.words());
        out.add("Posts wanted: " + String.join(", ", posts) + ".");
        for (Kept g : t.kept) {
            Entity e = level.getEntity(g.golem);
            out.add("  " + g.name + " (" + g.kind.name().toLowerCase(Locale.ROOT) + ") at " + g.where + ", since day " + (g.built + 1)
                + (e instanceof AbstractGolem ag ? ": " + state(ag) + (ag.blockPosition().distSqr(g.post) > BEAT * BEAT ? ", away from its post" : "") : ": out of sight"));
        }
        for (Fallen f : t.fallen) out.add("  fell: " + f.name + " at " + f.where + " on day " + (f.day + 1) + (f.gathered ? "" : ", its iron still to gather"));
        out.add("The iron: " + ironInStores(level, v) + " ingots' worth in the stores; the watch's kit still wants " + ironForTheWatch(level, v)
            + "; a golem takes " + IRON_EACH + ". " + (cannotAfford(level, v) == null ? "The town can afford another." : "Not now: " + cannotAfford(level, v) + "."));
        out.add("All told: " + t.built + " raised, " + t.adopted + " taken in hand, " + t.blocks + " blocks of iron made, " + t.carved + " pumpkins carved ("
            + t.seeds + " seeds), mended " + t.mended + " times with " + t.ingots + " ingots, " + t.lost + " lost and " + t.gathered + " ingots gathered back, "
            + t.snow + " snow golems built.");
        if (!t.why.isEmpty()) out.add("Waiting: " + t.why + ".");
        return out;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("golems")
            .executes(Golems::cmdReport)
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> said = stage(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", said)), false);
                return 1;
            }));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdReport(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = report(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    /**
     * The pictures' stage (/village golems stage): the town's keeper (one taken up if it has none), its yard stamped by the
     * square if it has none, the iron and a pumpkin and shears put by in the stores (a showcase's, said so), and a golem
     * raised at the square by the game's own check while the camera watches; one set hurt for the keeper to mend.
     * Says where the camera should stand: "VIEW name x y z ax ay az".
     */
    static List<String> stage(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        VillageFolkEntity k = keeper(id);
        if (k == null) {
            // For the pictures: a town not yet in the Iron Age put there, and two raids in its books (said so).
            if (Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal()) {
                Villages.ageForTests(id, Villages.Age.IRON);
                out.add("the town put into the Iron Age for the stage");
            }
            noteRaid(id, level.getDayTime() / 24000L - 1);
            noteRaid(id, level.getDayTime() / 24000L);
            k = appoint(level, v);
        }
        if (k == null) return List.of("no folk to keep the golems");
        if (yard(id) == null) {
            Villages.Site site = Villages.siteFor(level, id, STRUCTURE);
            if (site != null) {
                com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, STRUCTURE, site.anchor(), site.facing(), 0,
                    com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
                Ledger.built(id, STRUCTURE, site.anchor(), site.facing());
                out.add("the yard stamped at " + site.anchor().toShortString());
            }
        }
        Crafts.store(level, v, new ItemStack(Items.IRON_INGOT, IRON_EACH + 8));
        Crafts.store(level, v, new ItemStack(Items.PUMPKIN, 2));
        Crafts.store(level, v, new ItemStack(Items.SHEARS));
        out.add("the stage's makings put in the stores (a showcase's): " + (IRON_EACH + 8) + " iron ingots, 2 pumpkins, shears");
        Post square = null;
        for (Post p : posts(level, v)) if (p.key().equals("square")) square = p;
        if (square != null) {
            BlockPos base = tSpot(level, square.near(), square.across(), 6);
            if (base != null) {
                BlockPos cam = base.relative(square.face(), 6).relative(square.across(), 3);
                out.add("VIEW golems-before " + cam.getX() + " " + (cam.getY() + 2) + " " + cam.getZ() + " " + base.getX() + " " + (base.getY() + 1) + " " + base.getZ());
                k.moveTo(base.getX() + 0.5 + square.face().getStepX() * 2, base.getY(), base.getZ() + 0.5 + square.face().getStepZ() * 2, k.getYRot(), 0.0F);
                while (Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK)) < BLOCKS && ironBlock(level, v)) { }
                out.add(build(level, v, k, square));
                out.add("VIEW golems-raised " + cam.getX() + " " + (cam.getY() + 2) + " " + cam.getZ() + " " + base.getX() + " " + (base.getY() + 1) + " " + base.getZ());
                for (Kept g : town(id).kept) {
                    if (level.getEntity(g.golem) instanceof IronGolem ig && g.key.equals("square")) {
                        ig.setHealth(30.0F);                                 // hurt, for the keeper to mend on its round
                        out.add("the square's golem set hurt (" + state(ig) + ") for the keeper's round");
                    }
                }
            }
        }
        Ledger.Building y = yard(id);
        if (y != null) {
            BlockPos cam = at(y, 0, -6);
            BlockPos mid = at(y, 0, 0);
            out.add("VIEW golems-yard " + cam.getX() + " " + (cam.getY() + 2) + " " + cam.getZ() + " " + mid.getX() + " " + (mid.getY() + 1) + " " + mid.getZ());
        }
        return out;
    }

    // ------------------------------------------------------------------ for the tests

    /** Tests: a golem raised now at the town's post of this key (the square, a gate). What came of it. */
    public static String buildForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, String key) {
        for (Post p : posts(level, v)) {
            if (!p.key().equals(key)) continue;
            String why = cannotAfford(level, v);
            if (why != null) {
                town(v.id()).why = why;
                return "cannot: " + why;
            }
            while (Crafts.stock(level, v, s -> s.is(Items.IRON_BLOCK)) < BLOCKS && ironBlock(level, v)) { }
            return build(level, v, by, p);
        }
        return "no such post: " + key;
    }

    /** Tests: the keeper's round now, and its errand done at once if it set out on one. What it did. */
    public static String roundForTests(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        ERRANDS.remove(f.getUUID());
        String r = round(f, level, v);
        Errand e = ERRANDS.get(f.getUUID());
        if (e != null) {
            BlockPos to = e.at;
            if (e.job == Job.MEND && e.golem != null && level.getEntity(e.golem) instanceof IronGolem g) to = g.blockPosition();
            if (e.job == Job.SNOW) to = to.below(9);
            f.moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, f.getYRot(), 0.0F);
            if (e.job == Job.SNOW) f.moveTo(e.at.getX() + 2.5, e.at.getY(), e.at.getZ() + 0.5, f.getYRot(), 0.0F);
            errand(f, level, v, e);
        }
        return r == null ? "nothing" : r;
    }

    public static int mendForTests(ServerLevel level, Villages.Village v, VillageFolkEntity by, IronGolem g, int most) {
        return mend(level, v, by, g, most);
    }

    public static String snowForTests(ServerLevel level, Villages.Village v, VillageFolkEntity by) {
        return buildSnow(level, v, by, false);
    }

    public static void tickForTests(ServerLevel level, Villages.Village v) {
        KEPT_AT.remove(v.id());
        tick(level, v);
    }

    public static void raidForTests(UUID village, long day) {
        noteRaid(village, day);
    }

    public static int adoptForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by) {
        return adopt(level, v, by);
    }

    /** Tests: the town's golems, {uuid, name, kind, where, post} as strings. */
    public static List<String[]> keptForTests(UUID village) {
        List<String[]> out = new ArrayList<>();
        for (Kept k : town(village).kept) out.add(new String[]{ k.golem.toString(), k.name, k.kind.name(), k.where, pos(k.post) });
        return out;
    }

    /** Tests: the town's fallen golems, {name, where, gathered}. */
    public static List<String[]> fallenForTests(UUID village) {
        List<String[]> out = new ArrayList<>();
        for (Fallen f : town(village).fallen) out.add(new String[]{ f.name, f.where, Boolean.toString(f.gathered) });
        return out;
    }

    public static String whyForTests(UUID village) {
        return town(village).why;
    }
}
