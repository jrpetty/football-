package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [culture2] What a town holds sacred, from its land, its history and its temper, and the rites that follow from it.
 * The elder leads them (the town keeps no chaplain; with no elder, its eldest).
 * <ul>
 * <li><b>The Sea</b> (the coast, the rivers, the fens; a town of fishers). Each morning the elder walks down to the water
 *     and gives the sea the first fish of the stores, and the town's fishers go out blessed. Its dead are given to the
 *     sea: no headstone in the graveyard, but a post on the shore with their name on it and a flower on the water, the
 *     vigil held there. It marries on the shore, names its children for the sea (Marin, Coral, Pearl), keeps a little
 *     boat on the chapel wall, and never fishes on the Sea's day: the fleet stays in and the lines stay dry.</li>
 * <li><b>The Stone</b> (the hills and the badlands; a town of miners, a first diamond). Each morning the elder blesses
 *     the mine's mouth. Its dead lie under cairns on the high ground; the first diamond is kept in the chapel; it
 *     marries before the hall, names its children for stones (Flint, Jasper, Garnet), and on the Stone's day the
 *     mountain rests: no pick goes into it.</li>
 * <li><b>The Stars</b> (stargazers, a learned town, clear skies). On the Stars' day the stargazers and the elder keep a
 *     vigil on the hill at dusk, faces to the sky. A spyglass hangs in the chapel; children are named for stars (Vega,
 *     Lyra); and no tree is felled on that day.</li>
 * <li><b>The Harvest</b> (the plains and the meadows; a farming town). On the Harvest's day the elder walks out and
 *     blesses the fields, and the first sheaf stands in the chapel; the couples marry by the fields, children are named
 *     for the green (Barley, Rowan, Hazel), and on that day the fields rest.</li>
 * <li><b>The Hearth</b> (a homely town; the cold north; many weddings). Home and family: couples come to marry sooner,
 *     wed at their own door, name a child for a grandparent; a candle on the chapel's wall; and a wedding day is a
 *     holiday from noon.</li>
 * <li><b>The Founders</b> (a devout town; one that has buried its founders). Founding Day is sacred (no work that day),
 *     the elder lays a flower at the founders' stone on it, the founders' book is kept in the chapel, the first house is
 *     never altered, and a child takes a name from a founder's ("Bramwyn", for Bram).</li>
 * </ul>
 * A town's faith is chosen once (a colony keeps its mother's unless its new land calls it to another) and may turn,
 * slowly, as the town does; the chronicle says so when it does.
 */
public final class Beliefs {

    private Beliefs() {}

    public enum Belief {
        FOUNDERS("the Founders", "We honour the founders. Founding Day is sacred to us, and nobody lays a hand on the first house."),
        SEA("the Sea", "We keep faith with the Sea. It has the first fish each morning, and it has our dead. Nobody fishes on the Sea's day."),
        STONE("the Stone", "The Stone keeps us. We bless the mine each morning, lay our dead under cairns, and the mountain rests on its day."),
        STARS("the Stars", "We read the Stars. On their day we keep the vigil on the hill, and no tree is felled."),
        HARVEST("the Harvest", "The Harvest is holy here. The fields are blessed on its day, and on that day they rest."),
        HEARTH("the Hearth", "Home and hearth. We marry young, at our own doors, and a wedding is a holiday for all.");

        public final String words, creed;

        Belief(String words, String creed) {
            this.words = words;
            this.creed = creed;
        }

        @Nullable
        static Belief byName(@Nullable String s) {
            if (s == null) return null;
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** How a town buries its dead. */
    public enum Burial { YARD, SEA, CAIRN }

    /** The day each folk was last blessed at a rite (its mood), and the rite. */
    private static final Map<UUID, Object[]> BLESSED = new ConcurrentHashMap<>();
    /** The day each town's morning rite was last kept. */
    private static final Map<UUID, Long> KEPT = new ConcurrentHashMap<>();

    static void resetForTests() {
        BLESSED.clear();
        KEPT.clear();
        LEADER.clear();
    }

    /** The town's faith, once chosen; null before. */
    @Nullable
    public static Belief of(@Nullable UUID village) {
        return village == null ? null : Belief.byName(TownWays.note(village, "belief"));
    }

    /** Tests: this town's faith set so. */
    public static void setForTests(UUID village, Belief b) {
        TownWays.note(village, "belief", b.name());
    }

    // ------------------------------------------------------------------ choosing it

    static Map<Belief, Integer> scores(UUID village) {
        Map<Belief, Integer> s = new EnumMap<>(Belief.class);
        for (Belief b : Belief.values()) s.put(b, 0);
        Homeland.Land land = Homeland.of(village);
        Values.Value heart = TownWays.heart(village);
        Map<AssistantEntity.StationTask, Integer> t = TownWays.trades(village);
        switch (land) {
            case COAST -> s.merge(Belief.SEA, 6, Integer::sum);
            case RIVER, SWAMP -> s.merge(Belief.SEA, 3, Integer::sum);
            case MOUNTAIN -> s.merge(Belief.STONE, 6, Integer::sum);
            case BADLANDS -> s.merge(Belief.STONE, 5, Integer::sum);
            case DESERT -> { s.merge(Belief.STARS, 3, Integer::sum); s.merge(Belief.STONE, 2, Integer::sum); }
            case PLAINS -> s.merge(Belief.HARVEST, 5, Integer::sum);
            case MEADOW -> s.merge(Belief.HARVEST, 4, Integer::sum);
            case SAVANNA -> s.merge(Belief.HARVEST, 3, Integer::sum);
            case JUNGLE -> s.merge(Belief.HARVEST, 2, Integer::sum);
            case SNOW, TAIGA -> s.merge(Belief.HEARTH, 3, Integer::sum);
            case FOREST -> s.merge(Belief.FOUNDERS, 2, Integer::sum);
        }
        s.merge(Belief.SEA, Math.min(4, t.getOrDefault(AssistantEntity.StationTask.FISH, 0)), Integer::sum);
        s.merge(Belief.STONE, Math.min(4, t.getOrDefault(AssistantEntity.StationTask.MINE, 0) + t.getOrDefault(AssistantEntity.StationTask.CAVE, 0)), Integer::sum);
        s.merge(Belief.HARVEST, Math.min(4, t.getOrDefault(AssistantEntity.StationTask.FARM, 0) / 2), Integer::sum);
        switch (heart) {
            case TRADITION -> s.merge(Belief.FOUNDERS, 5, Integer::sum);
            case PROGRESS -> s.merge(Belief.STARS, 3, Integer::sum);
            case FOOD -> s.merge(Belief.HARVEST, 3, Integer::sum);
            case HOMES -> s.merge(Belief.HEARTH, 4, Integer::sum);
            case LEISURE -> s.merge(Belief.HEARTH, 2, Integer::sum);
            default -> { }
        }
        int stargazers = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.persona().rolled() && f.persona().hobby() == Persona.Hobby.STARGAZING) stargazers++;
        }
        s.merge(Belief.STARS, Math.min(6, stargazers * 2), Integer::sum);
        // What it has lived through: a first diamond, a drought weathered, weddings, its founders buried.
        int weddings = 0, foundersGone = 0;
        List<String> founders = founders(village);
        for (Chronicle.Entry e : Chronicle.of(village)) {
            String x = e.text();
            if (x.contains("first diamond")) s.merge(Belief.STONE, 3, Integer::sum);
            if (x.startsWith("the drought broke")) s.merge(Belief.HARVEST, 2, Integer::sum);
            if (x.endsWith(" were wed")) weddings++;
            for (String n : founders) if (x.startsWith(n + " died")) foundersGone++;
        }
        s.merge(Belief.HEARTH, Math.min(4, weddings), Integer::sum);
        s.merge(Belief.FOUNDERS, Math.min(4, foundersGone * 2), Integer::sum);
        Belief mother = Belief.byName(TownWays.note(village, "mother.belief"));
        if (mother != null) s.merge(mother, 4, Integer::sum);
        return s;
    }

    static Belief best(Map<Belief, Integer> s) {
        Belief best = Belief.HARVEST;
        for (Belief b : Belief.values()) if (s.get(b) > s.get(best)) best = b;
        return best;
    }

    /**
     * The town's faith chosen (TownWays.daily), once; its founders' names written down then. Each fortnight after, a
     * faith that suits the town better by four takes its place, and the chronicle says so.
     */
    static void choose(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (TownWays.note(id, "founders.names") == null) {
            List<String> names = new ArrayList<>();
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && f.persona().origin().equals("a founder of the village")) names.add(f.displayNameCap());
            }
            if (!names.isEmpty()) TownWays.note(id, "founders.names", String.join(",", names));
        }
        Belief now = of(id);
        Map<Belief, Integer> s = scores(id);
        Belief best = best(s);
        if (now == null) {
            TownWays.note(id, "belief", best.name());
            TownWays.note(id, "belief.since", Long.toString(day));
            Villages.tell(id, day, Villages.name(id) + " keeps faith with " + best.words);
            return;
        }
        long since = Culture.num(String.valueOf(TownWays.note(id, "belief.since")), day);
        int faith = Ethos.lean(id, Ethos.Axis.FAITH);                      // [identity] devout: kept longer; worldly: sooner
        int margin = 4 + (faith >= Ethos.POLE ? 2 : faith <= -Ethos.POLE ? -1 : 0);
        if (best == now || day - since < 14 || s.get(best) < s.get(now) + margin) return;
        TownWays.note(id, "belief", best.name());
        TownWays.note(id, "belief.since", Long.toString(day));
        Villages.tell(id, day, "the town's faith turned from " + now.words + " to " + best.words);
    }

    /** Tests: the faith chosen now, from what the town is (and a turn of it, if it has changed enough). */
    public static Belief chooseForTests(ServerLevel level, Villages.Village v, boolean fresh) {
        if (fresh) TownWays.note(v.id(), "belief", null);
        else TownWays.note(v.id(), "belief.since", Long.toString(level.getDayTime() / 24000L - 14));
        choose(level, v, level.getDayTime() / 24000L);
        return of(v.id());
    }

    /** The founders' names, as written down the first day the town's ways were worked out. */
    static List<String> founders(UUID village) {
        String n = TownWays.note(village, "founders.names");
        List<String> out = new ArrayList<>();
        if (n != null) for (String s : n.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    // ------------------------------------------------------------------ the sacred day and the taboos

    /** The faith's own day, once a week: mid-week from the day of rest. */
    public static boolean sacred(@Nullable UUID village, long day) {
        return village != null && of(village) != null && Math.floorMod(day + village.hashCode() + 3, 7) == 3;
    }

    /** The next sacred day from this one (this one, if it is). */
    static long nextSacred(UUID village, long day) {
        for (int i = 0; i < 7; i++) if (Math.floorMod(day + i + village.hashCode() + 3, 7) == 3) return day + i;
        return day;
    }

    /**
     * What the town's faith forbids this folk today, in words, or null (VillageFolkEntity.onBreak: its trade's work
     * waits): the fishers on the Sea's day, the miners on the Stone's, the woodcutters on the Stars', the farmers on the
     * Harvest's; every hand but the watch on Founding Day in a town of the Founders, and from noon on a wedding day in
     * a town of the Hearth. The watch is never stood down.
     */
    @Nullable
    public static String taboo(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Belief b = of(id);
        if (b == null || f.isBaby() || !(f.level() instanceof ServerLevel sl) || !TownWays.settled(sl, id)) return null;
        AssistantEntity.StationTask t = f.stationTask();
        if (t == AssistantEntity.StationTask.GUARD || t == AssistantEntity.StationTask.NONE) return null;
        long dt = f.level().getDayTime(), day = dt / 24000L;
        switch (b) {
            case SEA -> { if (t == AssistantEntity.StationTask.FISH && sacred(id, day)) return "nobody fishes on the Sea's day"; }
            case STONE -> {
                if ((t == AssistantEntity.StationTask.MINE || t == AssistantEntity.StationTask.CAVE) && sacred(id, day)) return "the mountain rests on the Stone's day";
            }
            case STARS -> { if (t == AssistantEntity.StationTask.WOOD && sacred(id, day)) return "no tree is felled on the Stars' day"; }
            case HARVEST -> { if (t == AssistantEntity.StationTask.FARM && sacred(id, day)) return "the fields rest on the Harvest's day"; }
            case FOUNDERS -> { if (FoundingDay.today(id, day)) return "Founding Day is sacred: no work today"; }
            case HEARTH -> {
                if (dt % 24000L >= 6000L && Gatherings.tonight(id, day) == Gatherings.Kind.WEDDING) return "a wedding day: a holiday from noon";
            }
        }
        return null;
    }

    /** Tests: what the faith forbids this folk on this day (the day time set to it), or null. */
    @Nullable
    public static String tabooForTests(VillageFolkEntity f) {
        return taboo(f);
    }

    /** Is the folk off its work for the faith now (onBreak)? */
    public static boolean keepsTaboo(VillageFolkEntity f) {
        return !f.isShowcase() && taboo(f) != null;
    }

    /** What keeps the fleet in for the faith today (Fleet.keptIn), or null. */
    @Nullable
    public static String keptIn(ServerLevel level, UUID village, long day) {
        return of(village) == Belief.SEA && sacred(village, day) && TownWays.settled(level, village) ? "the Sea's day" : null;
    }

    /** The first house of a town of the Founders is never altered: not dressed, refaced or raised (Architecture, Grow). */
    public static boolean untouchable(@Nullable UUID village, Ledger.Building b) {
        if (village == null || of(village) != Belief.FOUNDERS || !b.structure().equals("house")) return false;
        for (Ledger.Building x : Ledger.buildings(village)) if (x.structure().equals("house")) return x.anchor().equals(b.anchor());
        return false;
    }

    /** How warm two folk must be to wed: sooner in a town of the Hearth (VillageFolkEntity's courting). */
    public static int weddingWarmth(@Nullable UUID village) {
        return of(village) == Belief.HEARTH ? 68 : 75;
    }

    // ------------------------------------------------------------------ the dead

    /** How the town buries. */
    public static Burial burial(@Nullable UUID village) {
        Belief b = of(village);
        return b == Belief.SEA ? Burial.SEA : b == Belief.STONE ? Burial.CAIRN : Burial.YARD;
    }

    private static final String BURIALS = "rites.burials";

    /** A death in the town (VillageFolkEntity.die): how it is buried, by its town's faith, written down. */
    public static void died(VillageFolkEntity f, long day) {
        UUID id = f.ownerId();
        if (id == null || f.isShowcase()) return;
        Burial how = f.level() instanceof ServerLevel sl && TownWays.settled(sl, id) ? burial(id) : Burial.YARD;
        List<String[]> rows = Culture.rows(id, TownWays.PREFIX + BURIALS);
        rows.add(new String[]{ f.displayNameCap(), how.name(), Long.toString(day), "" });
        Culture.rows(id, TownWays.PREFIX + BURIALS, rows);
        if (how == Burial.SEA) Villages.tell(id, day, f.displayNameCap() + " is to be given to the sea, as the town keeps faith with it");
        if (how == Burial.CAIRN) Villages.tell(id, day, "a cairn is to be raised on the high ground for " + f.displayNameCap());
    }

    /** How this one of the dead was buried (its name), or YARD if the town wrote nothing down. */
    static Burial buriedAs(UUID village, String name) {
        for (String[] r : Culture.rows(village, TownWays.PREFIX + BURIALS)) {
            if (r.length >= 2 && r[0].equals(name)) {
                try {
                    return Burial.valueOf(r[1]);
                } catch (IllegalArgumentException e) {
                    return Burial.YARD;
                }
            }
        }
        return Burial.YARD;
    }

    /** Does this one of the dead lie in the graveyard (Graves.tend: no headstone for the sea's or the cairn's)? */
    public static boolean inYard(UUID village, Ledger.Grave g) {
        return buriedAs(village, g.name()) == Burial.YARD;
    }

    /**
     * Where the family takes a flower on the anniversary (Families.remembrance): the yard's grave, or the post on the
     * shore, or the cairn. {mound, where the flower is laid}, or null.
     */
    @Nullable
    public static BlockPos[] graveOf(UUID village, int index, Ledger.Grave g) {
        if (buriedAs(village, g.name()) == Burial.YARD) return Graves.graveOf(village, index);
        for (String[] r : Culture.rows(village, TownWays.PREFIX + BURIALS)) {
            if (r.length >= 4 && r[0].equals(g.name()) && !r[3].isEmpty()) {
                BlockPos at = BlockPos.of(Culture.num(r[3], 0));
                return new BlockPos[]{ at, at };
            }
        }
        return null;
    }

    /** Where the vigil is held (Assemblies): on the shore for the sea's dead, by the cairns for the Stone's, else the yard. */
    @Nullable
    public static BlockPos vigilAt(UUID village, @Nullable BlockPos yard) {
        Burial b = burial(village);
        if (b == Burial.SEA) {
            BlockPos shore = spot(village, "rites.shore");
            if (shore != null) return shore;
        }
        if (b == Burial.CAIRN) {
            BlockPos hill = spot(village, "rites.hill");
            if (hill != null) return hill;
        }
        return yard;
    }

    @Nullable
    private static BlockPos spot(UUID village, String key) {
        String s = TownWays.note(village, key);
        return s == null || NONE.equals(s) ? null : BlockPos.of(Culture.num(s, 0));
    }

    /** Written for a spot looked for and not found (no water near, no high ground loaded): not looked for again. */
    private static final String NONE = "none";

    /** Was this spot looked for, and none found? */
    private static boolean lookedFor(UUID village, String key) {
        return NONE.equals(TownWays.note(village, key));
    }

    /** The shore: where the sea's rites are kept (the water's edge nearest the heart, looked for once and kept). */
    @Nullable
    static BlockPos shore(ServerLevel level, Villages.Village v) {
        BlockPos had = spot(v.id(), "rites.shore");
        if (had != null || lookedFor(v.id(), "rites.shore")) return had;
        if (!level.isLoaded(v.centre())) return null;
        Waterfront.Dock d = Waterfront.site(level, v.centre(), 40);
        if (d == null) {
            TownWays.note(v.id(), "rites.shore", NONE);
            return null;
        }
        BlockPos bank = d.start().relative(d.out().getOpposite()).above();
        TownWays.note(v.id(), "rites.shore", Long.toString(bank.asLong()));
        TownWays.note(v.id(), "rites.water", Long.toString(d.start().asLong()));
        return bank;
    }

    /** The high ground: the highest open ground within forty blocks of the heart (looked over once and kept). */
    @Nullable
    static BlockPos hill(ServerLevel level, Villages.Village v) {
        BlockPos had = spot(v.id(), "rites.hill");
        if (had != null) return had;
        BlockPos c = v.centre(), best = null;
        for (int dx = -40; dx <= 40; dx += 4) {
            for (int dz = -40; dz <= 40; dz += 4) {
                if (Math.abs(dx) < 12 && Math.abs(dz) < 12) continue;                // not on the square
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                net.minecraft.world.level.block.state.BlockState ground = level.getBlockState(top.below());
                // The land itself, not a roof or a tree: earth, sand, stone, snow.
                boolean natural = ground.is(net.minecraft.tags.BlockTags.DIRT) || ground.is(net.minecraft.tags.BlockTags.SAND)
                    || ground.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.GRAVEL)
                    || ground.is(net.minecraft.tags.BlockTags.TERRACOTTA);
                if (!natural || !level.getFluidState(top.below()).isEmpty() || !level.getBlockState(top).canBeReplaced()) continue;
                if (Villages.onFarmland(v.id(), dx, dz, 1, 1)) continue;
                if (best == null || top.getY() > best.getY()) best = top;
            }
        }
        if (best != null) TownWays.note(v.id(), "rites.hill", Long.toString(best.asLong()));
        return best;
    }

    /**
     * The dead of the sea and of the cairns given their markers (each minute, Beliefs.tick): a post on the shore with a
     * board on it (a fence and a sign, out of the stores) and a flower from the stores set on the water; or a cairn on
     * the high ground (two of the stores' cobblestone, a wall on a block) with a board on its face. By a hand at the
     * town's works. Returns how many were marked.
     */
    static int tend(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        List<String[]> rows = Culture.rows(id, TownWays.PREFIX + BURIALS);
        int marked = 0, k = 0;
        boolean changed = false;
        for (String[] r : rows) {
            if (r.length < 4) continue;
            Burial how;
            try {
                how = Burial.valueOf(r[1]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (how == Burial.YARD) continue;
            int slot = k++;
            if (!r[3].isEmpty()) continue;
            BlockPos at = how == Burial.SEA ? seaPost(level, v, slot) : cairn(level, v, slot);
            if (at == null) continue;
            if (!free && !TownJobs.atWork(level, v, "graves", at, how == Burial.SEA ? "setting up a post on the shore for "
                + r[0] : "raising a cairn for " + r[0])) break;
            if (!(how == Burial.SEA ? raisePost(level, v, at, r, free) : raiseCairn(level, v, at, r, free))) continue;
            r[3] = Long.toString(at.asLong());
            changed = true;
            marked++;
            break;                                                             // one a turn
        }
        if (changed) Culture.rows(id, TownWays.PREFIX + BURIALS, rows);
        return marked;
    }

    /** Tests: every marker the dead of the sea and the cairns are owed, put up now for nothing. */
    public static int tendForTests(ServerLevel level, Villages.Village v) {
        return tendForTests(level, v, true);
    }

    /** Tests: every marker owed put up now, out of the stores unless {@code free} (as the town's works would). */
    public static int tendForTests(ServerLevel level, Villages.Village v, boolean free) {
        int n = 0;
        for (int i = 0; i < 20; i++) {
            int got = tend(level, v, free);
            if (got == 0) break;
            n += got;
        }
        return n;
    }

    /** Where a dead one's marker stands, if it has one (tests). */
    @Nullable
    public static BlockPos markerForTests(UUID village, String name) {
        for (String[] r : Culture.rows(village, TownWays.PREFIX + BURIALS)) {
            if (r.length >= 4 && r[0].equals(name) && !r[3].isEmpty()) return BlockPos.of(Culture.num(r[3], 0));
        }
        return null;
    }

    /** The k-th post along the shore, two blocks apart along the bank. */
    @Nullable
    private static BlockPos seaPost(ServerLevel level, Villages.Village v, int k) {
        BlockPos bank = shore(level, v);
        BlockPos water = spot(v.id(), "rites.water");
        if (bank == null || water == null) return null;
        Direction out = Direction.getNearest(water.getX() - bank.getX(), 0, water.getZ() - bank.getZ());
        Direction along = out.getClockWise();
        int step = (k / 2 + 1) * 2 * (k % 2 == 0 ? 1 : -1);
        BlockPos p = bank.relative(along, step);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        p = new BlockPos(p.getX(), y, p.getZ());
        if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP) || !level.getBlockState(p).isAir()
                || !level.getBlockState(p.above()).isAir()) {
            return k == 0 ? bank : null;
        }
        return p;
    }

    private static boolean raisePost(ServerLevel level, Villages.Village v, BlockPos at, String[] r, boolean free) {
        if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).canBeReplaced()) return false;
        if (!free) {
            if (!Crafts.take(level, v, s -> s.is(ItemTags.WOODEN_FENCES), 1) && !Crafts.planks(level, v, 2)) return false;
            if (!Crafts.sign(level, v)) {
                Crafts.store(level, v, new ItemStack(Items.SPRUCE_FENCE));
                return false;
            }
        }
        level.setBlock(at, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        BlockPos water = spot(v.id(), "rites.water");
        Direction toSea = water == null ? Direction.NORTH : Direction.getNearest(water.getX() - at.getX(), 0, water.getZ() - at.getZ());
        level.setBlock(at.above(), Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
            RotationSegment.convertToSegment(toSea.getOpposite())), 3);
        if (level.getBlockEntity(at.above()) instanceof SignBlockEntity sign) {
            TownLife.write(sign, new String[]{ "Given to the sea", r[0], "day " + (Culture.num(r[2], 0) + 1), "fair winds" });
        }
        // A flower out of the stores, set on the water.
        if (water != null) {
            ItemStack flower = free ? new ItemStack(Items.POPPY) : Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
            if (!flower.isEmpty()) {
                ItemEntity e = new ItemEntity(level, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, flower.copyWithCount(1));
                e.setNeverPickUp();
                level.addFreshEntity(e);
            }
            level.sendParticles(ParticleTypes.SPLASH, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, 12, 0.4, 0.1, 0.4, 0.1);
        }
        return true;
    }

    /** The k-th cairn on the high ground, in a row of five two blocks apart. */
    @Nullable
    private static BlockPos cairn(ServerLevel level, Villages.Village v, int k) {
        BlockPos hill = hill(level, v);
        if (hill == null) return null;
        int x = hill.getX() + (k % 5) * 2 - 4, z = hill.getZ() + (k / 5) * 3;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return null;
        return p;
    }

    private static boolean raiseCairn(ServerLevel level, Villages.Village v, BlockPos at, String[] r, boolean free) {
        if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).canBeReplaced()) return false;
        BlockPos front = at.relative(Direction.NORTH);
        if (!free) {
            if (!Masonry.take(level, v, Items.COBBLESTONE, 2)) return false;
            if (!Crafts.sign(level, v)) {
                Crafts.store(level, v, new ItemStack(Items.COBBLESTONE, 2));
                return false;
            }
        }
        level.setBlock(at, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(at.above(), Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
        if (level.getBlockState(front).isAir()) {
            level.setBlock(front, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.NORTH), 3);
            if (level.getBlockEntity(front) instanceof SignBlockEntity sign) {
                TownLife.write(sign, new String[]{ "Under the stone", r[0], "day " + (Culture.num(r[2], 0) + 1), "steady stone" });
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ weddings and names

    /**
     * Where a wedding is held, by the town's faith (Assemblies): on the shore, before the hall, by the fields, on the
     * hill under the first stars, at the couple's own door, at the heart where the founders lit their first fire. Null
     * for wherever the town usually holds it.
     */
    @Nullable
    public static BlockPos weddingAt(ServerLevel level, Villages.Village v, Gatherings.Wedding w) {
        Belief b = TownWays.settled(level, v.id()) ? of(v.id()) : null;
        if (b == null) return null;
        UUID id = v.id();
        return switch (b) {
            case SEA -> shore(level, v);
            case STONE -> {
                Ledger.Building hall = Culture.hall(id);
                yield hall == null ? null : hall.anchor().relative(hall.facing().getOpposite(), 7);
            }
            case HARVEST -> {
                String o = Ledger.note(id, "fields.origin");
                if (o == null || o.isEmpty()) yield null;
                String[] p = o.split(",");
                yield p.length < 3 ? null : new BlockPos((int) Culture.num(p[0], 0), (int) Culture.num(p[1], 0) + 1, (int) Culture.num(p[2], 0));
            }
            case STARS -> hill(level, v);
            case HEARTH -> {
                Homes.Home h = Homes.homeOf(id, w.a());
                if (h == null) h = Homes.homeOf(id, w.b());
                if (h == null) yield null;
                BlockPos door = null;
                for (Ledger.Building x : Ledger.buildings(id)) {
                    if (x.anchor().equals(h.anchor)) door = x.anchor().relative(x.facing().getOpposite(), 6);
                }
                yield door;
            }
            case FOUNDERS -> v.centre();
        };
    }

    private static final String[] SEA_NAMES = { "Marin", "Coral", "Pearl", "Morwen", "Tide", "Shelly", "Nerys", "Dory", "Kelda", "Brine" };
    private static final String[] STONE_NAMES = { "Flint", "Jasper", "Garnet", "Slate", "Agate", "Beryl", "Onyx", "Opal", "Cobble", "Jet" };
    private static final String[] STAR_NAMES = { "Vega", "Lyra", "Orion", "Stella", "Altair", "Nova", "Sirius", "Cassia", "Rigel", "Elara" };
    private static final String[] GREEN_NAMES = { "Barley", "Rowan", "Hazel", "Clover", "Sorrel", "Bramble", "Linden", "Fennel", "Sage", "Willow" };
    private static final String[] SUFFIXES = { "wyn", "ella", "ett", "kin", "ric", "lyn" };

    /**
     * A newborn's name, by its town's faith (VillageFolkEntity.bear): a name of the sea, of stone, of a star or of the
     * green; one made of a founder's name ("Bramwyn"), or of a grandparent's ("Fenkin") in a town of the Hearth. Never
     * one a living folk of the town has. Otherwise the usual.
     */
    public static String childName(UUID village, RandomSource r, VillageFolkEntity a, VillageFolkEntity b) {
        Belief belief = a.level() instanceof ServerLevel sl && TownWays.settled(sl, village) ? of(village) : null;
        List<String> pick = new ArrayList<>();
        if (belief != null) {
            switch (belief) {
                case SEA -> pick.addAll(List.of(SEA_NAMES));
                case STONE -> pick.addAll(List.of(STONE_NAMES));
                case STARS -> pick.addAll(List.of(STAR_NAMES));
                case HARVEST -> pick.addAll(List.of(GREEN_NAMES));
                case FOUNDERS -> { for (String n : founders(village)) for (String s : SUFFIXES) pick.add(n + s); }
                case HEARTH -> {
                    for (String ps : List.of(a.life().parents(), b.life().parents())) {
                        for (String n : ps.split(" and ")) if (!n.isBlank()) for (String s : SUFFIXES) pick.add(n.trim() + s);
                    }
                }
            }
        }
        java.util.Collections.shuffle(pick, new java.util.Random(r.nextLong()));
        for (String n : pick) if (!Names.inUse(village, n)) return n;
        return Names.freshFor(village, r);
    }

    /** Tests: a name for a child of these two, by the town's faith. */
    public static String childNameForTests(UUID village, RandomSource r, VillageFolkEntity a, VillageFolkEntity b) {
        return childName(village, r, a, b);
    }

    /** Is this a name the town's faith gives? */
    public static boolean faithName(UUID village, String name) {
        Belief b = of(village);
        if (b == null) return false;
        String[] list = switch (b) {
            case SEA -> SEA_NAMES;
            case STONE -> STONE_NAMES;
            case STARS -> STAR_NAMES;
            case HARVEST -> GREEN_NAMES;
            default -> new String[0];
        };
        for (String n : list) if (n.equals(name)) return true;
        if (b == Belief.FOUNDERS || b == Belief.HEARTH) for (String s : SUFFIXES) if (name.endsWith(s) && name.length() > s.length() + 1) return true;
        return false;
    }

    // ------------------------------------------------------------------ the shrine in the chapel

    /** What the faith keeps on the chapel's wall (the hall's, with no chapel), and what pays for it out of the stores. */
    static Item token(Belief b) {
        return switch (b) {
            case SEA -> Items.OAK_BOAT;
            case STONE -> Items.DIAMOND;
            case STARS -> Items.SPYGLASS;
            case HARVEST -> Items.WHEAT;
            case HEARTH -> Items.CANDLE;
            case FOUNDERS -> Items.BOOK;
        };
    }

    /**
     * The faith's token hung on the chapel's back wall in a frame, out of the stores, once (the hall's, with no chapel): a
     * little boat for the Sea, the first diamond for the Stone, a spyglass for the Stars, the first sheaf for the
     * Harvest, a candle for the Hearth, the founders' book for the Founders. A faith turned takes the old token down,
     * back into the stores. True if it hangs.
     */
    static boolean shrine(ServerLevel level, Villages.Village v, boolean free) {
        UUID id = v.id();
        Belief b = of(id);
        if (b == null) return false;
        Ledger.Building chapel = Culture.building(id, "chapel");
        if (chapel == null) chapel = Culture.hall(id);
        if (chapel == null || !level.isLoaded(chapel.anchor())) return false;
        Item want = token(b);
        String had = TownWays.note(id, "rites.shrine");
        BlockPos at = null;
        Direction facing = chapel.facing().getOpposite();
        for (int dz = 8; dz >= 2 && at == null; dz--) {
            BlockPos p = Culture.at(chapel, 0, 2, dz), wall = Culture.at(chapel, 0, 2, dz + 1);
            if (level.getBlockState(p).isAir() && level.getBlockState(wall).isFaceSturdy(level, wall, facing)) at = p;
        }
        if (at == null) return false;
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(0.2), e -> true);
        ItemFrame frame = frames.isEmpty() ? null : frames.get(0);
        if (frame != null && frame.getItem().is(want)) return true;
        if (!free) {
            if (!Crafts.take(level, v, s -> s.is(want), 1)) return false;
            if (frame == null && !Crafts.take(level, v, s -> s.is(Items.ITEM_FRAME), 1)) {
                if (!Crafts.take(level, v, s -> s.is(Items.LEATHER), 1) || !Crafts.take(level, v, s -> s.is(Items.STICK), 8) && !Crafts.planks(level, v, 4)) {
                    Crafts.store(level, v, new ItemStack(want));
                    return false;
                }
            }
        }
        if (frame == null) {
            frame = new ItemFrame(level, at, facing);
            level.addFreshEntity(frame);
        } else if (!frame.getItem().isEmpty() && !free) {
            Crafts.store(level, v, frame.getItem().copy());
        }
        frame.setItem(new ItemStack(want), false);
        if (!want.equals(BuiltInRegistries.ITEM.get(ResourceLocation.parse(had == null ? "minecraft:air" : had)))) {
            TownWays.note(id, "rites.shrine", BuiltInRegistries.ITEM.getKey(want).toString());
            Villages.tell(id, level.getDayTime() / 24000L, "the town hung " + new ItemStack(want).getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
                + " in " + (chapel.structure().equals("chapel") ? "the chapel" : "the hall") + ", for " + b.words);
        }
        return true;
    }

    /** Tests: the shrine set up now, for nothing. True if it hangs. */
    public static boolean shrineForTests(ServerLevel level, Villages.Village v) {
        return shrine(level, v, true);
    }

    /** Tests: the shrine set up now, out of the stores unless {@code free}. True if it hangs. */
    public static boolean shrineForTests(ServerLevel level, Villages.Village v, boolean free) {
        return shrine(level, v, free);
    }

    // ------------------------------------------------------------------ the rites, day by day

    /** Where the morning's rite is kept, for this faith: the shore, the mine's mouth, the fields, the founders' stone. */
    @Nullable
    static BlockPos riteSpot(ServerLevel level, Villages.Village v, Belief b) {
        return switch (b) {
            case SEA -> shore(level, v);
            case STONE -> TownMine.siteOf(v.id());
            case HARVEST -> {
                String o = Ledger.note(v.id(), "fields.origin");
                if (o == null || o.isEmpty()) yield v.centre();
                String[] p = o.split(",");
                yield p.length < 3 ? v.centre() : new BlockPos((int) Culture.num(p[0], 0), (int) Culture.num(p[1], 0) + 1, (int) Culture.num(p[2], 0));
            }
            case STARS -> hill(level, v);
            case FOUNDERS -> v.centre();
            case HEARTH -> null;
        };
    }

    /** Is the morning rite due today: every morning for the Sea and the Stone; the sacred day for the Harvest; Founding Day for the Founders. */
    static boolean riteDue(UUID village, Belief b, long day) {
        return switch (b) {
            case SEA, STONE -> true;
            // [identity] A devout town blesses its fields every morning, not only on the Harvest's day.
            case HARVEST -> sacred(village, day) || Ethos.is(village, Ethos.Axis.FAITH, true);
            case FOUNDERS -> FoundingDay.today(village, day);
            default -> false;
        };
    }

    /**
     * The rite kept (when the elder reaches its spot, or the tests): the Sea's first fish out of the stores and into the
     * water, and the fishers blessed; the mine's mouth blessed, and the miners; the fields blessed, and the farmers; a
     * flower out of the stores laid at the founders' stone, and the whole town remembers. What was done, in words, or
     * null if it could not be (no fish in the stores for the sea).
     */
    @Nullable
    static String keepRite(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by, long day) {
        UUID id = v.id();
        Belief b = of(id);
        if (b == null) return null;
        BlockPos at = riteSpot(level, v, b);
        if (at == null) return null;
        AssistantEntity.StationTask blessed = switch (b) {
            case SEA -> AssistantEntity.StationTask.FISH;
            case STONE -> AssistantEntity.StationTask.MINE;
            case HARVEST -> AssistantEntity.StationTask.FARM;
            default -> AssistantEntity.StationTask.NONE;
        };
        String words;
        switch (b) {
            case SEA -> {
                ItemStack fish = Crafts.takeOne(level, v, s -> s.is(Items.COD) || s.is(Items.SALMON));
                if (fish.isEmpty()) return null;
                BlockPos water = spot(id, "rites.water");
                BlockPos w = water != null ? water : at;
                level.sendParticles(ParticleTypes.SPLASH, w.getX() + 0.5, w.getY() + 1.0, w.getZ() + 0.5, 16, 0.3, 0.1, 0.3, 0.1);
                level.playSound(null, w, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.8F, 1.0F);
                words = "gave the sea the first fish of the morning";
            }
            case STONE -> {
                level.sendParticles(ParticleTypes.ENCHANT, at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5, 20, 0.6, 0.6, 0.6, 0.2);
                words = "blessed the mine's mouth";
            }
            case HARVEST -> {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 16, 2.0, 0.5, 2.0, 0.0);
                words = "blessed the fields";
            }
            case FOUNDERS -> {
                ItemStack flower = Crafts.takeOne(level, v, s -> s.is(ItemTags.SMALL_FLOWERS));
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 8, 0.4, 0.4, 0.4, 0.0);
                words = flower.isEmpty() ? "spoke the founders' names at the heart" : "laid a flower for the founders at the heart";
                for (AssistantEntity a : Villages.folkOf(id)) {
                    if (a instanceof VillageFolkEntity f && !f.isBaby()) BLESSED.put(f.getUUID(), new Object[]{ day, "founders" });
                }
            }
            default -> { return null; }
        }
        KEPT.put(id, day);
        int n = 0;
        if (blessed != AssistantEntity.StationTask.NONE) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && f.stationTask() == blessed) {
                    BLESSED.put(f.getUUID(), new Object[]{ day, b.name() });
                    n++;
                }
            }
        }
        if (by != null) {
            by.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            FolkTalk.speak(by, switch (b) {
                case SEA -> FolkTalk.pick(by.getRandom(), "For you, sea. Bring them home.", "The first of the catch, as ever.");
                case STONE -> FolkTalk.pick(by.getRandom(), "Keep them safe down there.", "Steady stone, steady hands.");
                case HARVEST -> FolkTalk.pick(by.getRandom(), "Grow well, and feed us all.", "Bless these fields.");
                default -> FolkTalk.pick(by.getRandom(), "We remember you, founders.", "For the ones who lit the first fire.");
            });
        }
        String who = by != null ? by.displayNameCap() : "the town";
        TownWays.note(id, "rites.last", who + " " + words + " on day " + (day + 1) + (n > 0 ? " (" + n + " blessed)" : ""));
        return who + " " + words;
    }

    /** Tests: the day's rite kept now by this folk (or the town). What was done, or null. */
    @Nullable
    public static String riteForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by) {
        return keepRite(level, v, by, level.getDayTime() / 24000L);
    }

    /** Who leads the rites: the elder, or the eldest with none. */
    @Nullable
    static VillageFolkEntity leader(UUID village) {
        VillageFolkEntity e = Orders.elderOf(village);
        if (e != null) return e;
        UUID eldest = FoundingDay.eldest(village);
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.getUUID().equals(eldest)) return f;
        return null;
    }

    /** Who leads the rites, looked up at most once every five seconds a town (every folk asks, every few ticks, at dawn). */
    private static final Map<UUID, Object[]> LEADER = new ConcurrentHashMap<>();

    private static boolean leads(VillageFolkEntity f, UUID village) {
        long now = f.level().getGameTime();
        Object[] was = LEADER.get(village);
        if (was == null || now - (Long) was[0] > 100L || now < (Long) was[0]) {
            VillageFolkEntity l = leader(village);
            was = new Object[]{ now, l == null ? null : l.getUUID() };
            LEADER.put(village, was);
        }
        return f.getUUID().equals(was[1]);
    }

    /** The town's faith, each second (TownWays): the dead's markers (each minute), the shrine (each minute). */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        if (of(v.id()) == null || !TownWays.settled(level, v.id())) return;
        if (level.getGameTime() % 1200L == 77L) {
            tend(level, v, false);
            shrine(level, v, false);
        }
    }

    /**
     * A folk's part in its faith's rites (Culture.hold, every few ticks): the elder walking out to keep the morning's rite
     * and keeping it when it gets there; on the Stars' day, at dusk, the stargazers and the elder on the hill, faces to
     * the sky, till late. True while it is about it.
     */
    static boolean hold(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Belief b = of(id);
        if (b == null || f.isBaby() || !TownWays.settled(level, id)) return false;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        // The morning's rite: the elder, after the dawn bell and before the work is well begun.
        if (t >= 300L && t < 3000L && riteDue(id, b, day) && KEPT.getOrDefault(id, -1L) != day) {
            if (!leads(f, id)) return false;
            BlockPos at = riteSpot(level, v, b);
            if (at == null) return false;
            Culture.mark(f, Culture.Role.RITE, ritesWords(b));
            if (!Culture.arrive(f, at, 3.0, 0.7)) return true;
            if (keepRite(level, v, f, day) == null) KEPT.put(id, day);       // nothing to give: tomorrow
            return true;
        }
        // The Stars' vigil.
        if (b == Belief.STARS && sacred(id, day) && t >= 12600L && t < 15000L) {
            boolean keeps = f.persona().rolled() && f.persona().hobby() == Persona.Hobby.STARGAZING || leads(f, id);
            if (!keeps) return false;
            BlockPos hill = hill(level, v);
            if (hill == null) return false;
            Culture.mark(f, Culture.Role.RITE, "keeping the vigil of the Stars on the hill");
            if (!Culture.arrive(f, hill, 4.0, 0.8)) return true;
            f.getLookControl().setLookAt(f.getX() + 3, f.getEyeY() + 30, f.getZ() + 2);
            BLESSED.put(f.getUUID(), new Object[]{ day, "STARS" });
            if (f.getRandom().nextInt(400) == 0) {
                level.sendParticles(ParticleTypes.END_ROD, f.getX(), f.getY() + 10, f.getZ(), 1, 3, 0.5, 3, 0.02);
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There's the Plough.", "So many tonight.", "Make a wish."));
            }
            return true;
        }
        return false;
    }

    private static String ritesWords(Belief b) {
        return switch (b) {
            case SEA -> "going down to the water with the sea's offering";
            case STONE -> "walking out to bless the mine's mouth";
            case HARVEST -> "walking out to bless the fields";
            case FOUNDERS -> "laying a flower for the founders";
            default -> "keeping the town's rite";
        };
    }

    /** Blessed at a rite today or yesterday: three to its mood (four for the vigil). */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Object[] b = BLESSED.get(f.getUUID());
        if (b == null || day - (Long) b[0] > 1) return m;
        int lift = ("STARS".equals(b[1]) ? 4 : 3)
            + (Ethos.is(f.ownerId(), Ethos.Axis.FAITH, true) ? 1 : 0);          // [identity] a devout town takes its rites to heart
        why.add(new Object[]{ "blessed", lift });
        return m + lift;
    }

    static String moodWords(VillageFolkEntity f) {
        Belief b = of(f.ownerId());
        return switch (b == null ? Belief.HARVEST : b) {
            case SEA -> "The sea had its offering this morning. We'll come home safe.";
            case STONE -> "The mine was blessed this morning. I feel safer down there.";
            case STARS -> "I kept the vigil on the hill. The stars were out for us.";
            case HARVEST -> "The fields were blessed. It'll be a good year.";
            case FOUNDERS -> "We remembered the founders today.";
            case HEARTH -> "Home is a good place to be.";
        };
    }

    // ------------------------------------------------------------------ words

    @Nullable
    static String cardLine(VillageFolkEntity f) {
        Belief b = of(f.ownerId());
        if (b == null) return null;
        String why = taboo(f);
        if (why != null) return "keeps " + b.words + "'s day: " + why;
        Object[] bl = BLESSED.get(f.getUUID());
        if (bl != null && f.level().getDayTime() / 24000L - (Long) bl[0] <= 0) return "blessed this morning, by the faith of " + b.words;
        if (faithName(f.ownerId(), f.displayNameCap())) return "named in the faith of " + b.words;
        return null;
    }

    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        Belief b = of(village);
        if (b == null) {
            out.add("No faith of its own yet.");
            return out;
        }
        out.add("It keeps faith with " + b.words + ". " + b.creed);
        out.add("Its dead: " + switch (burial(village)) {
            case SEA -> "given to the sea, a post on the shore with their name and a flower on the water, the vigil by the water";
            case CAIRN -> "laid under cairns on the high ground, the vigil there";
            case YARD -> "the graveyard";
        } + ". Its weddings: " + switch (b) {
            case SEA -> "on the shore";
            case STONE -> "before the hall";
            case HARVEST -> "by the fields";
            case STARS -> "on the hill";
            case HEARTH -> "at the couple's own door (and couples wed sooner)";
            case FOUNDERS -> "at the heart, where the founders lit their fire";
        } + ".");
        out.add("Its children's names: " + switch (b) {
            case SEA -> "of the sea (Marin, Coral, Pearl)";
            case STONE -> "of stones (Flint, Jasper, Garnet)";
            case STARS -> "of stars (Vega, Lyra, Orion)";
            case HARVEST -> "of the green (Barley, Rowan, Hazel)";
            case HEARTH -> "after a grandparent";
            case FOUNDERS -> "after a founder";
        } + ". In the chapel: " + new ItemStack(token(b)).getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + ".");
        String taboo = switch (b) {
            case SEA -> "nobody fishes on the Sea's day, and the fleet stays in";
            case STONE -> "the mountain rests on the Stone's day: no mining";
            case STARS -> "no tree is felled on the Stars' day";
            case HARVEST -> "the fields rest on the Harvest's day";
            case HEARTH -> "a wedding day is a holiday from noon";
            case FOUNDERS -> "no work on Founding Day, and the first house is never altered";
        };
        out.add("Its taboo: " + taboo + (b == Belief.SEA || b == Belief.STONE || b == Belief.STARS || b == Belief.HARVEST
            ? " (the faith's day comes once a week, mid-week from the day of rest)" : "") + ".");
        VillageFolkEntity lead = leader(village);
        String last = TownWays.note(village, "rites.last");
        out.add("Its rites are led by " + (lead == null ? "its elder" : lead.displayNameCap()) + " (the town keeps no chaplain)"
            + (last == null ? "." : "; last: " + last + "."));
        int sea = 0, cairns = 0;
        for (String[] r : Culture.rows(village, TownWays.PREFIX + BURIALS)) {
            if (r.length >= 2 && r[1].equals("SEA")) sea++;
            if (r.length >= 2 && r[1].equals("CAIRN")) cairns++;
        }
        if (sea + cairns > 0) out.add(sea > 0 ? sea + " given to the sea." : cairns + " under cairns on the hill.");
        return out;
    }

    /** "What do you believe?" */
    static String talk(VillageFolkEntity f) {
        Belief b = of(f.ownerId());
        if (b == null) return "We've not settled on much yet, beyond getting through the winter.";
        String why = taboo(f);
        return b.creed + (why != null ? " Today's one of those days, as it happens — " + why + "." : "");
    }
}
