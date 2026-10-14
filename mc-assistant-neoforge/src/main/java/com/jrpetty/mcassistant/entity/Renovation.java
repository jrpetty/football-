package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.ThemeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [styles] The town's older buildings brought up to its current design and look, by its own builders.
 *
 * <p><b>What a building remembers.</b> Every building is stamped when it goes up (Ledger.built) with what it was built
 * to: its design's version (Blueprints.version: a drawing changed is a new version), the town's theme, its age and its
 * building style; one with no stamp (built before there were stamps) is stamped as of no known version, and looked
 * over like an out-of-date one.
 *
 * <p><b>Which are out of date.</b> Once a day the town goes over its buildings: one whose design or theme is not the
 * current one, or that has blocks in it that are not fit to build with (earth in an Iron Age wall, a block of gold or
 * the Nether's: Materials), is put on the list for renovation, the most out of date first and, of those, the oldest.
 * One whose age or style alone is behind is the ages' make-over's (Ages) and the style's dressers' (Architecture): it is
 * stamped up to date as they finish it.
 *
 * <p><b>The work.</b> Two at a time, a turn every half a minute by day, a hand at the building (TownJobs), a few blocks
 * a turn from the bottom up, out of the stores: the new design's every wall, floor and roof block where there is none or
 * an unfit one (or one not of the town's theme, if its theme changed), the town's own block first and the theme's
 * stand-ins after (Materials); what the old design had where the new one has nothing taken down into the stores; its
 * doors, windows, ladders and lights put in; and its furniture moved, never lost: a chest with everything in it, a
 * furnace with its fire, a lectern with its book, a sign with its words are moved whole to their places in the new
 * design (or, with no place for them there, to a free spot on its floor), and a bed to a new bed's place in the same
 * turn, so the household that sleeps in it (Homes, by the house) is never a bed short at night: a bed with no place in
 * the new design is left where it stands and counted among the house's beds. The residents stay: the house is the same
 * house, on the same ground. When nothing is left to do it is stamped as current.
 *
 * <p>It runs on Renovation.Engine, the survey and the repair of one building. The engine is this class's own
 * (LocalEngine) rather than the repairs' (Restore.diff and Restore.enqueue, Reason.RENOVATE): a repair puts back what
 * a building lacks, while a renovation also moves its household's furniture and beds whole, which Restore does not.
 * The repairs leave a building on this list alone (Restore.skip asks renovating()), so the two never work one
 * building at once.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Renovation {

    private Renovation() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The ledger's key for a building's stamp, by its anchor. */
    static final String PREFIX = "reno/";
    /** Buildings renovated at once in a town: a few, so the town is never all scaffolding. */
    static final int AT_ONCE = 2;
    /** Blocks a turn: about what a hand lays in the half minute between turns. */
    public static final int STEP = 12;
    /** Ticks between turns (half a minute), and the turn's place in that, clear of the other town works' turns. */
    static final int TURN_TICKS = 600, TURN_PHASE = 457;
    /** Buildings gone over a turn by the day's survey, so a big town's survey is spread over a few turns. */
    static final int SURVEY_BATCH = 24;
    /** The most turns renovateNow takes (the stage, the tests), and the blocks it lays a turn. */
    static final int NOW_TURNS = 200, NOW_STEP = 64;

    /** What a building was built to: its design's version and name, the theme, the age, the style; and when. */
    public record Stamp(long version, String drawing, String theme, String age, String style, long day) {

        String encode() {
            return version + "|" + drawing + "|" + theme + "|" + age + "|" + style + "|" + day;
        }

        @Nullable
        static Stamp decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            if (p.length < 6) return null;
            try {
                return new Stamp(Long.parseLong(p[0]), p[1], p[2], p[3], p[4], Long.parseLong(p[5]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Each town's list of buildings to renovate, by anchor, in order (the day's survey). */
    private static final Map<UUID, List<Long>> QUEUE = new ConcurrentHashMap<>();
    /** The day each town was last gone over. */
    private static final Map<UUID, Long> SURVEYED = new ConcurrentHashMap<>();
    /** What each building under renovation waits for, if anything (the board, the command). */
    private static final Map<Long, String> WAITS = new ConcurrentHashMap<>();
    /** Buildings renovated, a count per town (the command, the tests). */
    private static final Map<UUID, Integer> DONE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        QUEUE.clear();
        SURVEYED.clear();
        SURVEYS.clear();
        WAITS.clear();
        DONE.clear();
    }

    // ------------------------------------------------------------------ the stamps

    @Nullable
    public static Stamp stamp(UUID village, BlockPos anchor) {
        return Stamp.decode(Ledger.note(village, PREFIX + anchor.asLong()));
    }

    /** What a building of this town is to be now: its current design (and its version), theme, age and style. */
    public static Stamp current(UUID village, Ledger.Building b, long day) {
        String drawing = Ages.drawing(village, b);
        Architecture.Style st = Architecture.of(village);
        boolean styled = Architecture.HOMES.contains(b.structure());
        return new Stamp(Blueprints.version(drawing), drawing, Themes.idOf(village), Villages.ageOf(village).name(),
            styled && st != null ? st.name() : "-", day);
    }

    /** A building went up (Ledger.built): stamped with what it was built to. */
    public static void built(UUID village, String structure, BlockPos anchor, Direction facing) {
        try {
            if (!Blueprints.has(structure)) return;
            long day = 0;
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) day = server.overworld().getDayTime() / 24000L;
            Ledger.note(village, PREFIX + anchor.asLong(), current(village, new Ledger.Building(structure, anchor, facing), day).encode());
        } catch (RuntimeException e) {
            LOG.warn("[MCA-RENO] could not stamp {} at {}: {}", structure, anchor, e.toString());
        }
    }

    /** The stage: a building stamped as built to this (an older design's version, another theme...). */
    public static void stampAs(UUID village, BlockPos anchor, Stamp s) {
        stampForTests(village, anchor, s);
    }

    /** Tests: a building stamped as built to this (an older design's version, another theme...). */
    public static void stampForTests(UUID village, BlockPos anchor, Stamp s) {
        Ledger.note(village, PREFIX + anchor.asLong(), s.encode());
        SURVEYED.remove(village);
    }

    // ------------------------------------------------------------------ the engine

    /**
     * The survey and the repair a renovation runs on: what a building lacks against a design, and a few of those put
     * right. [integrity]'s Restore (Restore.diff, Restore.enqueue) is to answer this too; until it is in, LocalEngine.
     */
    public interface Engine {
        /** What is to be done to this building to make it its design, in the order it is done (empty: nothing). */
        List<Work> diff(ServerLevel level, Villages.Village v, Ledger.Building b, String drawing, boolean themeChanged);

        /** Do so many of these, out of the stores (unless free). How many were done; what it waits for in waits[0]. */
        int apply(ServerLevel level, Villages.Village v, Ledger.Building b, List<Work> work, int budget, boolean free, String[] waits);
    }

    /** One piece of a renovation. */
    public record Work(Kind kind, BlockPos pos, @Nullable BuildGoal.Placement cell, @Nullable BlockPos from) {}

    public enum Kind {
        /** The house's beds that are not where the design lays them, all moved there at once (never a bed short). */
        BEDS,
        /** A chest, a furnace, a lectern... moved whole from {@code from} to its place in the design. */
        MOVE,
        /** Something taken down into the stores (the old design's, or what stands where the new one wants another). */
        CLEAR,
        /** A wall, floor or roof block laid (in place of what is there, if anything). */
        BLOCK,
        /** A door, a window, a ladder, a light... put in. */
        PART
    }

    static Engine engine = new LocalEngine();

    // ------------------------------------------------------------------ the day's survey

    /**
     * Was the building built in another theme than the town's now? One with no stamp (an older save's, before themes)
     * was built in the town's own: its materials are left as they are, only its design is looked over.
     */
    static boolean themeChanged(@Nullable Stamp was, Stamp now) {
        return was != null && !was.theme().equals(now.theme());
    }

    /** How far behind a building is: 0 for current; more for a changed design or theme, or unfit blocks in it. */
    static int behind(ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID id = v.id();
        Stamp s = stamp(id, b.anchor());
        Stamp now = current(id, b, 0);
        int score = 0;
        if (s == null || s.version() != now.version() || !s.drawing().equals(now.drawing())) score += 4;
        if (themeChanged(s, now)) score += 4;
        if (score == 0 && unfitIn(level, v, b, now.drawing()) > 0) score += 2;
        return score;
    }

    /** How many unfit blocks stand in a building's wall, floor and roof places (Materials). */
    static int unfitIn(ServerLevel level, Villages.Village v, Ledger.Building b, String drawing) {
        int n = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BLOCK || !level.isLoaded(p.pos())) continue;
            if (Materials.unfit(v.id(), p.style(), level.getBlockState(p.pos()))) n++;
        }
        return n;
    }

    /** Is this building the renovation's to touch at all? */
    static boolean ours(UUID village, Ledger.Building b) {
        String s = b.structure();
        if (s.equals("fortify") || s.equals("wall") || s.equals("room") || s.equals("column") || s.equals("platform") || s.equals("pen")) return false;
        if (!Blueprints.has(s)) return false;
        if (HousingMarket.isPrivate(village, b.anchor())) return false;   // a folk's own house keeps the look it paid for
        if (Beliefs.untouchable(village, b)) return false;                  // the founders' first house, as they left it
        return !Grow.raisingNow(village, b.anchor());
    }

    /** A day's survey of a town, part done: the buildings gone over so far and how far behind each is. */
    private static final class Survey {
        final long day;
        int next;
        final List<long[]> rank = new ArrayList<>();

        Survey(long day) {
            this.day = day;
        }
    }

    /** Each town's survey under way. */
    private static final Map<UUID, Survey> SURVEYS = new ConcurrentHashMap<>();

    /**
     * Go over so many more of the town's buildings (all of them for {@code most} 0 or less): the out-of-date ones ranked
     * for renovation (the most behind first, then the oldest, then the first built); the ones only an age or a style
     * behind stamped current once the make-over and the dressers are done with them. When the last is gone over, the
     * list is the town's. True when the survey is done.
     */
    static boolean survey(ServerLevel level, Villages.Village v, long day, int most) {
        UUID id = v.id();
        Survey sv = SURVEYS.get(id);
        if (sv == null || sv.day != day) {
            sv = new Survey(day);
            SURVEYS.put(id, sv);
        }
        List<Ledger.Building> all = Ledger.buildings(id);
        int stop = most <= 0 ? all.size() : Math.min(all.size(), sv.next + most);
        for (; sv.next < stop; sv.next++) {
            Ledger.Building b = all.get(sv.next);
            if (!ours(id, b) || !Land.areaLoaded(level, b.anchor(), 9)) continue;
            Stamp s = stamp(id, b.anchor());
            int score = behind(level, v, b);
            if (score > 0) {
                sv.rank.add(new long[]{ b.anchor().asLong(), score, s == null ? -1 : s.day(), sv.next });
                continue;
            }
            catchUp(level, v, b, s, day);
        }
        if (sv.next < all.size()) return false;
        sv.rank.sort(Comparator.<long[]>comparingLong(r -> -r[1]).thenComparingLong(r -> r[2]).thenComparingLong(r -> r[3]));
        List<Long> q = new ArrayList<>();
        for (long[] r : sv.rank) q.add(r[0]);
        QUEUE.put(id, q);
        SURVEYED.put(id, day);
        SURVEYS.remove(id);
        return true;
    }

    /** A building only an age or a style behind: stamped up to date once the make-over and the dressers are done with it. */
    private static void catchUp(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable Stamp s, long day) {
        UUID id = v.id();
        Stamp now = current(id, b, s == null ? day : s.day());
        if (s != null && s.age().equals(now.age()) && s.style().equals(now.style())) return;
        boolean aged = Ages.AS_BUILT.contains(b.structure()) || Ages.fullyDone(level, v, b, Villages.ageOf(id));
        Architecture.Style st = Architecture.of(id);
        boolean dressed = !Architecture.HOMES.contains(b.structure()) || st == null || Architecture.dressedFor(id, b, st);
        if (aged && dressed) Ledger.note(id, PREFIX + b.anchor().asLong(), now.encode());
    }

    /** Is this building on the town's renovation list (the repairs leave it to the renovation)? */
    public static boolean renovating(UUID village, BlockPos anchor) {
        List<Long> q = QUEUE.get(village);
        return q != null && q.contains(anchor.asLong());
    }

    /** Survey the town afresh at its next turn (its theme changed). */
    public static void resurvey(UUID village) {
        SURVEYED.remove(village);
        SURVEYS.remove(village);
    }

    /** The town's renovation list, as last surveyed (anchors, first first). */
    public static List<BlockPos> queue(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (long a : QUEUE.getOrDefault(village, List.of())) out.add(BlockPos.of(a));
        return out;
    }

    // ------------------------------------------------------------------ the turns

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % TURN_TICKS != TURN_PHASE) return;
        com.jrpetty.mcassistant.Guard.run("the renovation", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    turn(level, v);
                }
            }
        });
    }

    /**
     * A turn of the town's renovation (every half a minute): the day's survey if it is not done, then a few blocks of
     * work on one of the (at most two) buildings under way. A settled town only (a day old). Returns the blocks done.
     */
    public static int turn(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.headcount(id) <= 0) return 0;
        long day = level.getDayTime() / 24000L, founded = FoundingDay.founded(id);
        if (founded < 0 || day - founded < 1) return 0;
        Long last = SURVEYED.get(id);
        if (last == null || last != day) survey(level, v, day, SURVEY_BATCH);          // a slice a turn till it is done
        List<Long> q = QUEUE.getOrDefault(id, List.of());
        if (q.isEmpty()) return 0;
        // A few at a time: the first two, turn about.
        int pick = (int) ((level.getGameTime() / TURN_TICKS) % Math.min(AT_ONCE, q.size()));
        long anchor = q.get(pick);
        Ledger.Building b = building(id, BlockPos.of(anchor));
        if (b == null || !ours(id, b)) {
            remove(id, anchor);
            return 0;
        }
        // Not while its ground is unloaded, or the fire brigade is putting it back after a fire (Rebuilding).
        if (!Land.areaLoaded(level, b.anchor(), 9) || rebuilding(id, b.anchor())) return 0;
        int n = renovate(level, v, b, STEP, false);
        // Waiting on the stores: to the back of the list, so the next building is not held up behind it.
        if (n == 0 && !waits(b.anchor()).isEmpty()) toBack(id, anchor);
        return Math.max(0, n);
    }

    /** Is the fire brigade putting this building back after a fire? */
    private static boolean rebuilding(UUID village, BlockPos anchor) {
        for (Disasters.Rebuild r : Disasters.town(village).rebuilds) if (r.anchor.equals(anchor)) return true;
        return false;
    }

    private static void toBack(UUID village, long anchor) {
        List<Long> q = QUEUE.get(village);
        if (q == null || q.size() <= AT_ONCE) return;
        List<Long> moved = new ArrayList<>(q);
        moved.remove(anchor);
        moved.add(anchor);
        QUEUE.put(village, moved);
    }

    @Nullable
    static Ledger.Building building(UUID village, BlockPos anchor) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.anchor().equals(anchor)) return b;
        return null;
    }

    private static void remove(UUID village, long anchor) {
        List<Long> q = QUEUE.get(village);
        if (q != null) {
            List<Long> left = new ArrayList<>(q);
            left.remove(anchor);
            QUEUE.put(village, left);
        }
    }

    /**
     * Renovate a building, up to so many blocks: out of the stores, by a hand there (unless {@code free}: the stage,
     * the tests). Stamped current when nothing is left. Returns the blocks done (-1 while the hand is on its way).
     */
    public static int renovate(ServerLevel level, Villages.Village v, Ledger.Building b, int budget, boolean free) {
        UUID id = v.id();
        Stamp s = stamp(id, b.anchor());
        Stamp now = current(id, b, level.getDayTime() / 24000L);
        List<Work> work = engine.diff(level, v, b, now.drawing(), themeChanged(s, now));
        if (work.isEmpty()) {
            finish(level, v, b, s, now);
            return 0;
        }
        if (!free && !TownJobs.atWork(level, v, "renovating", b.anchor(), "renovating " + Villages.spoken(Themes.structure(now.drawing()))
                + " to the town's new look")) {
            return -1;
        }
        String[] waits = { "" };
        int n = engine.apply(level, v, b, work, budget, free, waits);
        WAITS.put(b.anchor().asLong(), waits[0]);
        if (n > 0) level.playSound(null, b.anchor(), SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6F, 1.0F);
        if (n == 0 && waits[0].isEmpty()) finish(level, v, b, s, now);   // nothing it can do and nothing it waits on: done
        return n;
    }

    private static void finish(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable Stamp was, Stamp now) {
        UUID id = v.id();
        // The age and the style stay what they were till the make-over and the dressers have been (survey).
        Stamp kept = was == null ? now : new Stamp(now.version(), now.drawing(), now.theme(), was.age(), was.style(), now.day());
        Ledger.note(id, PREFIX + b.anchor().asLong(), kept.encode());
        remove(id, b.anchor().asLong());
        WAITS.remove(b.anchor().asLong());
        // Its new walls and roof are the age's make-over's and the style's dressers' to come round to again.
        Ages.forget(b.anchor());
        Architecture.forgetDressed(id, b.anchor());
        DONE.merge(id, 1, Integer::sum);
        if (was != null && (was.version() != now.version() || !was.theme().equals(now.theme()))) {
            Villages.tell(id, level.getDayTime() / 24000L, Villages.spoken(Themes.structure(now.drawing())) + " was renovated to the town's "
                + (was.theme().equals(now.theme()) ? "newer design" : Themes.of(id).name().toLowerCase(java.util.Locale.ROOT) + " look"));
        }
    }

    /** Tests and the stage: the whole of a building renovated now, for nothing. How many blocks. */
    public static int renovateNow(ServerLevel level, Villages.Village v, Ledger.Building b) {
        int all = 0;
        for (int i = 0; i < NOW_TURNS; i++) {
            int n = renovate(level, v, b, NOW_STEP, true);
            if (n <= 0) break;
            all += n;
        }
        return all;
    }

    /** How many buildings a town has renovated (since the server started). */
    public static int doneCount(UUID village) {
        return DONE.getOrDefault(village, 0);
    }

    /** What the renovation of this building waits for ("" for nothing). */
    public static String waits(BlockPos anchor) {
        return WAITS.getOrDefault(anchor.asLong(), "");
    }

    /** The town's renovation, in lines (the command). */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        if (!SURVEYED.containsKey(id)) survey(level, v, day, 0);
        List<BlockPos> q = queue(id);
        out.add("Theme: " + Themes.of(id).name() + " (" + Themes.idOf(id) + "). " + q.size() + (q.size() == 1 ? " building" : " buildings")
            + " to renovate; " + doneCount(id) + " renovated.");
        for (int i = 0; i < Math.min(6, q.size()); i++) {
            Ledger.Building b = building(id, q.get(i));
            if (b == null) continue;
            String w = waits(q.get(i));
            out.add((i < AT_ONCE ? " now: " : " next: ") + Villages.spoken(b.structure()) + " at " + q.get(i).toShortString()
                + (w.isEmpty() ? "" : " (waits for " + w + ")"));
        }
        return out;
    }

    // ------------------------------------------------------------------ the engine of its own

    /** The renovation's own survey and repair (until Restore is in: see Engine). */
    static final class LocalEngine implements Engine {

        /** What a part of the design is, as a block: furniture that is moved, never thrown away. */
        static boolean furniture(BuildGoal.Part part) {
            return part == BuildGoal.Part.CHEST || part == BuildGoal.Part.BED || part == BuildGoal.Part.FURNACE
                || part == BuildGoal.Part.CRAFTING_TABLE || BuildGoal.isFurniture(part) || part == BuildGoal.Part.BARREL
                || part == BuildGoal.Part.ANVIL || part == BuildGoal.Part.CAULDRON || part == BuildGoal.Part.STOREHOUSE;
        }

        /** The part a block standing is (a chest is a CHEST...), for the furniture; null for none. */
        @Nullable
        static BuildGoal.Part partOf(BlockState s) {
            if (s.isAir()) return null;
            Item it = s.getBlock().asItem();
            if (it == Items.AIR) return null;
            ItemStack st = new ItemStack(it);
            for (BuildGoal.Part p : BuildGoal.Part.values()) {
                if (p == BuildGoal.Part.BLOCK || p == BuildGoal.Part.CLEAR || !furniture(p)) continue;
                if (BuildGoal.itemForPart(p).test(st)) return p;
            }
            return null;
        }

        /** Does what stands here answer the design's place (a door for a door, a pane for a pane, whole glass for glass)? */
        static boolean answers(BuildGoal.Placement p, BlockState s) {
            if (s.isAir()) return false;
            BuildGoal.Part part = p.part();
            return switch (part) {
                case DOOR -> s.getBlock() instanceof DoorBlock;
                case BED -> s.getBlock() instanceof BedBlock;
                case WINDOW -> p.style() == Blueprints.Style.GLASS ? !Architecture.pane(s) && wholeGlass(s) : Architecture.pane(s);
                case TORCH -> s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN);
                case LANTERN -> s.getBlock() instanceof net.minecraft.world.level.block.LanternBlock || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH);
                case WATER -> !s.getFluidState().isEmpty();
                case FLOWER -> s.is(BlockTags.SMALL_FLOWERS) || s.is(BlockTags.FLOWER_POTS);
                case CARPET -> s.is(BlockTags.WOOL_CARPETS);
                case FENCE -> s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS);
                case GATE -> s.is(BlockTags.FENCE_GATES);
                case LADDER -> s.is(Blocks.LADDER);
                case STOREHOUSE -> s.getBlock() instanceof com.jrpetty.mcassistant.block.StorehouseBlock;
                default -> {
                    Item it = s.getBlock().asItem();
                    yield it != Items.AIR && BuildGoal.itemForPart(part).test(new ItemStack(it));
                }
            };
        }

        /** A window the town's building style dresses so (the Hill Fort's side windows walled up small): left so. */
        static boolean styledWindow(UUID village, BlockState s) {
            Architecture.Style st = Architecture.of(village);
            if (st == null) return false;
            for (Map<String, Object> r : Themes.cachedRules(Themes.of(village), "dress", "WINDOW", st.name())) {
                if (r.get("to") instanceof String to && to.indexOf('{') < 0 && Themes.block(to.replace("like:", "")) == s.getBlock()) return true;
            }
            return false;
        }

        /** A whole block of glass, clear, stained or tinted. */
        static boolean wholeGlass(BlockState s) {
            return s.is(BlockTags.IMPERMEABLE) || s.getBlock() instanceof net.minecraft.world.level.block.TransparentBlock;
        }

        /**
         * A wall, floor or roof block of any make, what a builder lays in a BLOCK place: any building block (BuildGoal),
         * a stair, a slab, a pane, or a block the town's theme names (a purpur pillar).
         */
        static boolean structural(UUID village, BlockState s) {
            if (s.isAir() || s.hasBlockEntity()) return false;
            if (s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.LOGS) || Architecture.pane(s)) return true;
            if (Materials.named(Themes.of(village), s.getBlock())) return true;
            Item it = s.getBlock().asItem();
            return it != Items.AIR && BuildGoal.isBuildingBlock(new ItemStack(it));
        }

        /** Is this block of the town's theme for a place of this kind (its ranked list, or anything the theme names)? */
        static boolean ofTheme(UUID village, Blueprints.Style style, BlockState s) {
            Item it = s.getBlock().asItem();
            if (Palettes.ranked(village, style).contains(it)) return true;
            return Themes.namedFor(Themes.of(village), style.name()).contains(s.getBlock());
        }

        @Override
        public List<Work> diff(ServerLevel level, Villages.Village v, Ledger.Building b, String drawing, boolean themeChanged) {
            UUID id = v.id();
            List<BuildGoal.Placement> plan = BuildGoal.plan(drawing, b.anchor(), b.facing(), 13);
            Map<Long, BuildGoal.Placement> at = new HashMap<>();
            int top = 0;
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                at.putIfAbsent(p.pos().asLong(), p);
                top = Math.max(top, p.pos().getY() - b.anchor().getY());
            }
            // A bed's head and a door's top: part of the bed and the door the design has.
            Set<Long> halves = new HashSet<>();
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.BED) halves.add(p.pos().relative(lie(p, b.facing())).asLong());
                if (p.part() == BuildGoal.Part.DOOR) halves.add(p.pos().above().asLong());
            }
            List<Work> moves = new ArrayList<>(), rest = new ArrayList<>();
            // The furniture standing in the building that is not where the design has it: to be moved, never lost.
            int[] half = Blueprints.groundHalf(drawing);
            boolean turned = b.facing().getAxis() == Direction.Axis.X;
            int wx = turned ? half[1] : half[0], wz = turned ? half[0] : half[1];
            Map<BuildGoal.Part, List<BlockPos>> orphans = new EnumMap<>(BuildGoal.Part.class);
            List<BlockPos> bedsOut = new ArrayList<>();
            int bedPlaces = 0;
            Set<Long> inside = new HashSet<>();
            for (int dx = -wx; dx <= wx; dx++) {
                for (int dz = -wz; dz <= wz; dz++) {
                    for (int dy = 0; dy <= top; dy++) {
                        BlockPos q = b.anchor().offset(dx, dy, dz);
                        if (!level.isLoaded(q)) continue;
                        inside.add(q.asLong());
                        BlockState s = level.getBlockState(q);
                        BuildGoal.Part part = partOf(s);
                        if (part == null) continue;
                        if (s.getBlock() instanceof BedBlock) {
                            if (s.getValue(BedBlock.PART) == BedPart.HEAD) continue;   // its foot speaks for it
                            BuildGoal.Placement there = at.get(q.asLong());
                            if (there == null || !bedRight(there, s, b.facing())) bedsOut.add(q.immutable());
                            continue;
                        }
                        BuildGoal.Placement there = at.get(q.asLong());
                        if (there != null && there.part() == part) continue;
                        orphans.computeIfAbsent(part, k -> new ArrayList<>()).add(q.immutable());
                    }
                }
            }
            // Each place of the design, as it stands.
            List<BuildGoal.Placement> sorted = new ArrayList<>(at.values());
            sorted.sort(Comparator.comparingInt((BuildGoal.Placement p) -> p.pos().getY()).thenComparingLong(p -> p.pos().asLong()));
            for (BuildGoal.Placement p : sorted) {
                BlockState s = level.getBlockState(p.pos());
                if (!level.isLoaded(p.pos())) continue;
                if (p.part() == BuildGoal.Part.BLOCK) {
                    boolean open = s.isAir() || BuildGoal.soft(s) && s.getFluidState().isEmpty() || BuildGoal.isWildPlant(s);
                    if (halves.contains(p.pos().asLong()) && (s.getBlock() instanceof BedBlock || s.getBlock() instanceof DoorBlock)) continue;
                    if (open) { rest.add(new Work(Kind.BLOCK, p.pos(), p, null)); continue; }
                    if (partOf(s) != null) continue;                    // furniture in the way: moved first (orphans), then laid
                    boolean glazed = Architecture.pane(s) || s.is(Blocks.GLASS) || s.is(BlockTags.IMPERMEABLE);   // a window where a wall goes
                    if (!structural(id, s) || glazed || Materials.unfit(id, p.style(), s) || themeChanged && !ofTheme(id, p.style(), s)) {
                        rest.add(new Work(Kind.BLOCK, p.pos(), p, null));
                    }
                    continue;
                }
                if (p.part() == BuildGoal.Part.BED) {
                    if (bedRight(p, s, b.facing())) continue;
                    bedPlaces++;
                    if (!bedsOut.isEmpty()) continue;                  // the beds there are moved in (BEDS)
                    rest.add(new Work(Kind.PART, p.pos(), p, null));
                    continue;
                }
                if (answers(p, s) || p.part() == BuildGoal.Part.WINDOW && styledWindow(id, s)) continue;
                if (halves.contains(p.pos().asLong()) && (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BedBlock)) continue;
                if (furniture(p.part())) {
                    List<BlockPos> got = orphans.get(p.part());
                    if (got != null && !got.isEmpty() && (s.isAir() || structural(id, s) || BuildGoal.soft(s))) {
                        BlockPos from = nearest(got, p.pos());
                        got.remove(from);
                        moves.add(new Work(Kind.MOVE, p.pos(), p, from));
                        continue;
                    }
                }
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                rest.add(new Work(Kind.PART, p.pos(), p, null));
            }
            // Furniture with no place in the new design that stands where the design wants a wall: moved to the floor.
            for (List<BlockPos> got : orphans.values()) {
                for (BlockPos from : got) {
                    BuildGoal.Placement there = at.get(from.asLong());
                    if (there == null || there.part() != BuildGoal.Part.BLOCK) continue;
                    BlockPos to = freeFloor(level, b, at, inside, from);
                    if (to != null) moves.add(new Work(Kind.MOVE, to, null, from));
                }
            }
            // What the old design had where the new one has nothing: taken down (walls, floors, roofs, panes).
            for (long q : inside) {
                if (at.containsKey(q) || halves.contains(q)) continue;
                BlockPos p = BlockPos.of(q);
                if (p.getY() < b.anchor().getY()) continue;
                BlockState s = level.getBlockState(p);
                if (structural(id, s)) rest.add(new Work(Kind.CLEAR, p, null, null));
            }
            // Moves first (a chest out of the way of a wall), then from the bottom up, what is taken down before what is laid.
            rest.sort(Comparator.comparingInt((Work w) -> w.pos().getY()).thenComparingInt(w -> w.kind() == Kind.CLEAR ? 0 : 1));
            List<Work> out = new ArrayList<>();
            // The beds first, all at once: to their places in the design, if it has places they are not in; or off the
            // places where the new design wants a wall.
            boolean bedInTheWay = false;
            for (BlockPos f : bedsOut) {
                BuildGoal.Placement there = at.get(f.asLong());
                BlockState fs = level.getBlockState(f);
                BuildGoal.Placement atHead = fs.getBlock() instanceof BedBlock ? at.get(f.relative(fs.getValue(BedBlock.FACING)).asLong()) : null;
                if (there != null && there.part() != BuildGoal.Part.BED || atHead != null && atHead.part() != BuildGoal.Part.BED) bedInTheWay = true;
            }
            if (!bedsOut.isEmpty() && (bedPlaces > 0 || bedInTheWay)) out.add(new Work(Kind.BEDS, b.anchor(), null, null));
            out.addAll(moves);
            out.addAll(rest);
            return out;
        }

        /** Is this the bed the design lays at this place: a bed's foot, lying the design's way? */
        static boolean bedRight(BuildGoal.Placement p, BlockState s, Direction facing) {
            return p.part() == BuildGoal.Part.BED && s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.FOOT
                && s.getValue(BedBlock.FACING) == lie(p, facing);
        }

        private static Direction lie(BuildGoal.Placement p, Direction facing) {
            return p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
        }

        private static BlockPos nearest(List<BlockPos> from, BlockPos to) {
            BlockPos best = from.get(0);
            for (BlockPos p : from) if (p.distSqr(to) < best.distSqr(to)) best = p;
            return best;
        }

        /** A free place on the building's floor, near: air with headroom over something firm, not a place of the design. */
        @Nullable
        private static BlockPos freeFloor(ServerLevel level, Ledger.Building b, Map<Long, BuildGoal.Placement> at, Set<Long> inside, BlockPos near) {
            BlockPos best = null;
            for (long q : inside) {
                BlockPos p = BlockPos.of(q);
                if (at.containsKey(q) || at.containsKey(p.above().asLong())) continue;
                if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) continue;
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                if (best == null || p.distSqr(near) < best.distSqr(near)) best = p;
            }
            return best;
        }

        @Override
        public int apply(ServerLevel level, Villages.Village v, Ledger.Building b, List<Work> work, int budget, boolean free, String[] waits) {
            int n = 0;
            boolean night = !free && level.getDayTime() % 24000L >= 12500L && level.getDayTime() % 24000L < 23500L;
            for (Work w : work) {
                if (n >= budget) break;
                BlockState now = level.getBlockState(w.pos());
                switch (w.kind()) {
                    case BEDS -> {
                        if (night) { waits[0] = "the morning, to move the beds"; continue; }
                        n += beds(level, v, b, free);
                    }
                    case MOVE -> {
                        BlockState thing = level.getBlockState(w.from());
                        if (thing.getBlock() instanceof BedBlock && night) { waits[0] = "the morning, to move the beds"; continue; }
                        if (!now.isAir() && !BuildGoal.soft(now)) {
                            if (!clear(level, v, w.pos(), free)) continue;
                        }
                        if (move(level, v, b, w.from(), w.pos(), w.cell())) n++;
                    }
                    case CLEAR -> {
                        if (clear(level, v, w.pos(), free)) n++;
                    }
                    case BLOCK -> {
                        BuildGoal.Placement p = w.cell();
                        if (p == null) continue;
                        BlockState laid;
                        if (free) {
                            List<Item> ranked = Palettes.ranked(v.id(), p.style());
                            Item first = ranked.isEmpty() ? Items.COBBLESTONE : ranked.get(0);
                            laid = first instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
                        } else {
                            laid = Materials.take(level, v, p.style());
                        }
                        if (laid == null) { waits[0] = "building blocks fit for it"; continue; }
                        if (now.is(laid.getBlock())) {                     // what it would lay is there already: nothing to do
                            if (!free) Crafts.giveBack(level, v, laid.getBlock().asItem(), 1);
                            continue;
                        }
                        if (!now.isAir() && !BuildGoal.soft(now)) {
                            if (!clear(level, v, w.pos(), free)) { if (!free) Crafts.giveBack(level, v, laid.getBlock().asItem(), 1); continue; }
                        }
                        level.setBlock(w.pos(), BuildGoal.orient(laid, p, b.facing(), level, w.pos()), 3);
                        n++;
                    }
                    case PART -> {
                        BuildGoal.Placement p = w.cell();
                        if (p == null) continue;
                        if (p.part() == BuildGoal.Part.BED && night) continue;
                        int did = part(level, v, b, p, now, free);
                        if (did > 0) n++;
                        else if (did < 0 && essential(p.part())) waits[0] = BuildGoal.Part.values()[p.part().ordinal()].name().toLowerCase(java.util.Locale.ROOT);
                    }
                }
            }
            return n;
        }

        /** The parts a building is not done without (its furniture is moved in; a light, a rug, a flower may wait). */
        static boolean essential(BuildGoal.Part p) {
            return p == BuildGoal.Part.LADDER || p == BuildGoal.Part.DOOR;
        }

        /** Take down what stands here into the stores (a door whole, a bed never: those are moved). */
        static boolean clear(ServerLevel level, Villages.Village v, BlockPos pos, boolean free) {
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) return true;
            if (s.getBlock() instanceof BedBlock || s.hasBlockEntity()) return false;   // furniture is moved, not taken down
            if (s.getBlock() instanceof DoorBlock) {
                BlockPos other = s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
                if (level.getBlockState(other).getBlock() instanceof DoorBlock) level.setBlock(other, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
            Item back = s.getBlock().asItem();
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            if (!free && back != Items.AIR && !s.getFluidState().isSource()) Crafts.giveBack(level, v, back, 1);
            return true;
        }

        /** A door, a window, a ladder, a light, a rug... put in from the stores. 1 done, -1 waiting on the stores, 0 not now. */
        static int part(ServerLevel level, Villages.Village v, Ledger.Building b, BuildGoal.Placement p, BlockState now, boolean free) {
            if (!now.isAir() && !BuildGoal.soft(now)) {
                if (!clear(level, v, p.pos(), free)) return 0;
            }
            BuildGoal.Part part = p.part();
            if (part == BuildGoal.Part.WATER || part == BuildGoal.Part.STOREHOUSE) return 0;
            BlockState st;
            if (free) {
                st = freePart(part, p);
            } else {
                boolean whole = p.style() == Blueprints.Style.GLASS;
                ItemStack got = part == BuildGoal.Part.WINDOW ? Crafts.takeOne(level, v, s -> s.is(whole ? Items.GLASS : Items.GLASS_PANE))
                    : Crafts.takeOne(level, v, s -> s.getItem() instanceof BlockItem && BuildGoal.itemForPart(part).test(s));
                if (got.isEmpty() && part == BuildGoal.Part.DOOR && Crafts.usePlanks(level, v, 6)) got = new ItemStack(Items.OAK_DOOR);
                if (got.isEmpty()) return -1;
                st = got.getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : null;
            }
            if (st == null) return 0;
            if (part == BuildGoal.Part.BED) {
                Direction lie = lie(p, b.facing());
                BlockPos head = p.pos().relative(lie);
                if (!level.getBlockState(head).canBeReplaced()) return 0;
                BlockState bed = st.setValue(BedBlock.FACING, lie);
                level.setBlock(p.pos(), bed.setValue(BedBlock.PART, BedPart.FOOT), 2 | 16);
                level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
                return 1;
            }
            if (part == BuildGoal.Part.FURNACE || part == BuildGoal.Part.CHEST || part == BuildGoal.Part.LADDER) {
                Direction d = p.way() == Blueprints.Way.UP || p.way() == Blueprints.Way.FRONT ? b.facing().getOpposite() : Blueprints.world(p.way(), b.facing());
                if (st.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
                    st = st.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, d);
                }
            } else {
                st = BuildGoal.orient(st, p, b.facing(), level, p.pos());
            }
            if (part == BuildGoal.Part.DOOR) return BuildGoal.hangDoor(level, p.pos(), st) ? 1 : 0;
            level.setBlock(p.pos(), st, 3);
            return 1;
        }

        @Nullable
        private static BlockState freePart(BuildGoal.Part part, BuildGoal.Placement p) {
            return switch (part) {
                case DOOR -> Blocks.SPRUCE_DOOR.defaultBlockState();
                case WINDOW -> (p.style() == Blueprints.Style.GLASS ? Blocks.GLASS : Blocks.GLASS_PANE).defaultBlockState();
                case LADDER -> Blocks.LADDER.defaultBlockState();
                case TORCH -> Blocks.TORCH.defaultBlockState();
                case LANTERN -> Blocks.LANTERN.defaultBlockState();
                case FENCE -> Blocks.SPRUCE_FENCE.defaultBlockState();
                case GATE -> Blocks.SPRUCE_FENCE_GATE.defaultBlockState();
                case BED -> Blocks.WHITE_BED.defaultBlockState();
                case CHEST -> Blocks.CHEST.defaultBlockState();
                case FURNACE -> Blocks.FURNACE.defaultBlockState();
                case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE.defaultBlockState();
                case CARPET -> Blocks.RED_CARPET.defaultBlockState();
                case FLOWER -> Blocks.POPPY.defaultBlockState();
                case HAY -> Blocks.HAY_BLOCK.defaultBlockState();
                case BARREL -> Blocks.BARREL.defaultBlockState();
                case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
                default -> {
                    Item it = null;
                    for (Item cand : new Item[]{ Items.BOOKSHELF, Items.LECTERN, Items.ENCHANTING_TABLE, Items.BREWING_STAND, Items.SMOKER,
                        Items.LOOM, Items.GRINDSTONE, Items.CAMPFIRE, Items.NOTE_BLOCK, Items.CARTOGRAPHY_TABLE, Items.ANVIL, Items.CAULDRON,
                        Items.BELL }) {
                        if (BuildGoal.itemForPart(part).test(new ItemStack(cand))) { it = cand; break; }
                    }
                    yield it instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : null;
                }
            };
        }

        /**
         * The house's beds moved, all in one moment, so nobody is a bed short: every bed that is not where the design lays
         * one is lifted, and each set down at one of the design's bed places that has none (its colour kept); a bed left
         * over goes back where it stood if its place is still clear, else to a free spot on the floor, else, whole, into
         * the stores. The house's own list of the beds it has beyond its design ("homebeds") is written afresh, so every
         * bed in it counts for its household (Homes). How many beds were set down.
         */
        static int beds(ServerLevel level, Villages.Village v, Ledger.Building b, boolean free) {
            String drawing = Ages.drawing(v.id(), b);
            List<BuildGoal.Placement> plan = BuildGoal.plan(drawing, b.anchor(), b.facing(), 13);
            Map<Long, BuildGoal.Placement> at = new HashMap<>();
            int top = 0;
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CLEAR) continue;
                at.putIfAbsent(p.pos().asLong(), p);
                top = Math.max(top, p.pos().getY() - b.anchor().getY());
            }
            int[] half = Blueprints.groundHalf(drawing);
            boolean turned = b.facing().getAxis() == Direction.Axis.X;
            int wx = turned ? half[1] : half[0], wz = turned ? half[0] : half[1];
            Set<Long> inside = new HashSet<>();
            List<BlockPos> feet = new ArrayList<>();
            for (int dx = -wx; dx <= wx; dx++) {
                for (int dz = -wz; dz <= wz; dz++) {
                    for (int dy = 0; dy <= top; dy++) {
                        BlockPos q = b.anchor().offset(dx, dy, dz);
                        inside.add(q.asLong());
                        BlockState s = level.getBlockState(q);
                        if (!(s.getBlock() instanceof BedBlock) || s.getValue(BedBlock.PART) != BedPart.FOOT) continue;
                        BuildGoal.Placement there = at.get(q.asLong());
                        if (there == null || !bedRight(there, s, b.facing())) feet.add(q.immutable());
                    }
                }
            }
            if (feet.isEmpty()) return 0;
            // Lift them all.
            List<BlockState> lifted = new ArrayList<>();
            List<BlockPos> wasFoot = new ArrayList<>();
            for (BlockPos f : feet) {
                BlockState s = level.getBlockState(f);
                BlockPos head = f.relative(s.getValue(BedBlock.FACING));
                lifted.add(s);
                wasFoot.add(f);
                level.setBlock(head, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
                level.setBlock(f, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
            }
            // Set them down where the design lays beds.
            int n = 0;
            List<BlockState> left = new ArrayList<>();
            List<BlockPos> leftFrom = new ArrayList<>();
            List<BuildGoal.Placement> places = new ArrayList<>();
            for (BuildGoal.Placement p : plan) if (p.part() == BuildGoal.Part.BED && !bedRight(p, level.getBlockState(p.pos()), b.facing())) places.add(p);
            int k = 0;
            for (int i = 0; i < lifted.size(); i++) {
                boolean down = false;
                while (k < places.size() && !down) {
                    BuildGoal.Placement p = places.get(k++);
                    Direction lie = lie(p, b.facing());
                    BlockPos head = p.pos().relative(lie);
                    if (!clearForBed(level, v, p.pos(), free) || !clearForBed(level, v, head, free)) continue;
                    lay(level, lifted.get(i), p.pos(), lie);
                    down = true;
                    n++;
                }
                if (!down) { left.add(lifted.get(i)); leftFrom.add(wasFoot.get(i)); }
            }
            // The ones over: back where they stood, else on the floor, else into the stores.
            for (int i = 0; i < left.size(); i++) {
                BlockState s = left.get(i);
                Direction lie = s.getValue(BedBlock.FACING);
                BlockPos f = leftFrom.get(i);
                BuildGoal.Placement there = at.get(f.asLong()), thereHead = at.get(f.relative(lie).asLong());
                boolean back = (there == null || there.part() == BuildGoal.Part.BED) && (thereHead == null || thereHead.part() == BuildGoal.Part.BED)
                    && level.getBlockState(f).isAir() && level.getBlockState(f.relative(lie)).isAir();
                if (back) { lay(level, s, f, lie); continue; }
                BlockPos spot = null;
                for (long q : inside) {
                    BlockPos c = BlockPos.of(q), h = c.relative(lie);
                    if (at.containsKey(q) || at.containsKey(h.asLong()) || !inside.contains(h.asLong())) continue;
                    if (!level.getBlockState(c).isAir() || !level.getBlockState(h).isAir()) continue;
                    if (!level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)
                        || !level.getBlockState(h.below()).isFaceSturdy(level, h.below(), Direction.UP)) continue;
                    if (spot == null || c.distSqr(f) < spot.distSqr(f)) spot = c;
                }
                if (spot != null) lay(level, s, spot, lie);
                else Crafts.store(level, v, new ItemStack(s.getBlock().asItem()));
            }
            // The house's own beds beyond its design, written afresh.
            Set<Long> designHeads = new HashSet<>();
            for (BuildGoal.Placement p : plan) if (p.part() == BuildGoal.Part.BED) designHeads.add(p.pos().relative(lie(p, b.facing())).asLong());
            List<String> extra = new ArrayList<>();
            for (long q : inside) {
                BlockState s = level.getBlockState(BlockPos.of(q));
                if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD && !designHeads.contains(q)) extra.add(Long.toString(q));
            }
            String key = "homebeds/" + b.anchor().asLong();
            if (extra.isEmpty()) Ledger.forget(v.id(), key);
            else Ledger.note(v.id(), key, String.join(",", extra));
            return n;
        }

        /** Clear a cell for a bed: air already, or what is there taken down into the stores (never furniture). */
        private static boolean clearForBed(ServerLevel level, Villages.Village v, BlockPos pos, boolean free) {
            BlockState s = level.getBlockState(pos);
            if (s.isAir() || s.canBeReplaced()) return true;
            return clear(level, v, pos, free);
        }

        private static void lay(ServerLevel level, BlockState bed, BlockPos foot, Direction lie) {
            BlockState s = bed.getBlock().defaultBlockState().setValue(BedBlock.FACING, lie);
            level.setBlock(foot, s.setValue(BedBlock.PART, BedPart.FOOT), 2 | 16);
            level.setBlock(foot.relative(lie), s.setValue(BedBlock.PART, BedPart.HEAD), 3);
        }

        /**
         * Move a piece of furniture whole: what is in it (a chest's goods, a furnace's fire, a lectern's book, a sign's
         * words) with it, in the same moment, turned the way the design has it.
         */
        static boolean move(ServerLevel level, Villages.Village v, Ledger.Building b, BlockPos from, BlockPos to, @Nullable BuildGoal.Placement cell) {
            BlockState s = level.getBlockState(from);
            if (s.isAir()) return false;
            if (s.getBlock() instanceof BedBlock) return false;                    // beds go all at once (beds())
            BlockEntity be = level.getBlockEntity(from);
            CompoundTag data = be == null ? null : be.saveWithoutMetadata(level.registryAccess());
            if (be instanceof Container c) c.clearContent();                  // nothing spilt on the floor as it is lifted
            if (be != null) level.removeBlockEntity(from);
            BlockState moved = s;
            if (cell != null && s.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
                Direction d = cell.way() == Blueprints.Way.UP || cell.way() == Blueprints.Way.FRONT ? b.facing().getOpposite()
                    : Blueprints.world(cell.way(), b.facing());
                moved = s.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, d);
            }
            if (moved.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                moved = moved.setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.SINGLE);
            }
            level.setBlock(from, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
            level.setBlock(to, moved, 3);
            if (data != null) {
                BlockEntity nbe = level.getBlockEntity(to);
                if (nbe != null) {
                    nbe.loadWithComponents(data, level.registryAccess());
                    nbe.setChanged();
                }
            }
            Villages.forgetStock();
            return true;
        }
    }

    /** Tests: the work a building's renovation has before it, by kind. */
    public static Map<Kind, Integer> workForTests(ServerLevel level, Villages.Village v, Ledger.Building b) {
        Stamp s = stamp(v.id(), b.anchor());
        Stamp now = current(v.id(), b, 0);
        Map<Kind, Integer> out = new LinkedHashMap<>();
        for (Work w : engine.diff(level, v, b, now.drawing(), themeChanged(s, now))) out.merge(w.kind(), 1, Integer::sum);
        return out;
    }

    /** Tests: a town's survey made now. */
    public static List<BlockPos> surveyForTests(ServerLevel level, Villages.Village v) {
        SURVEYS.remove(v.id());
        survey(level, v, level.getDayTime() / 24000L, 0);
        return queue(v.id());
    }

}
