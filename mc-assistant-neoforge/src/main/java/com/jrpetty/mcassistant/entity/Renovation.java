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
 * <p>This is written against Renovation.Engine, the small survey-and-repair interface the integrity work (Restore.diff
 * and Restore.enqueue) also answers; the engine here is this class's own (LocalEngine) until that one is in.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Renovation {

    private Renovation() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The ledger's key for a building's stamp, by its anchor. */
    static final String PREFIX = "reno/";
    /** Buildings renovated at once in a town. */
    static final int AT_ONCE = 2;
    /** Blocks a turn. */
    static final int STEP = 12;

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
        /** A bed, a chest, a furnace... moved whole from {@code from} to its place in the design. */
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

    /** How far behind a building is: 0 for current; more for a changed design or theme, or unfit blocks in it. */
    static int behind(ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID id = v.id();
        Stamp s = stamp(id, b.anchor());
        Stamp now = current(id, b, 0);
        int score = 0;
        if (s == null || s.version() != now.version() || !s.drawing().equals(now.drawing())) score += 4;
        if (s == null || !s.theme().equals(now.theme())) score += 4;
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

    /**
     * Go over the town's buildings: the out-of-date ones listed for renovation (the most behind first, then the
     * oldest); the ones only an age or a style behind stamped current once the make-over and the dressers are done.
     */
    static void survey(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<long[]> rank = new ArrayList<>();
        int order = 0;
        for (Ledger.Building b : Ledger.buildings(id)) {
            order++;
            if (!ours(id, b) || !Land.areaLoaded(level, b.anchor(), 9)) continue;
            Stamp s = stamp(id, b.anchor());
            int score = behind(level, v, b);
            if (score > 0) {
                rank.add(new long[]{ b.anchor().asLong(), score, s == null ? -1 : s.day(), order });
                continue;
            }
            // Only an age or a style behind: the make-over's and the dressers'; stamped up to date when they are done.
            Stamp now = current(id, b, s == null ? day : s.day());
            if (s != null && (!s.age().equals(now.age()) || !s.style().equals(now.style()))) {
                boolean aged = Ages.AS_BUILT.contains(b.structure()) || Ages.fullyDone(level, v, b, Villages.ageOf(id));
                Architecture.Style st = Architecture.of(id);
                boolean dressed = !Architecture.HOMES.contains(b.structure()) || st == null || Architecture.dressedFor(id, b, st);
                if (aged && dressed) Ledger.note(id, PREFIX + b.anchor().asLong(), now.encode());
            }
        }
        rank.sort(Comparator.<long[]>comparingLong(r -> -r[1]).thenComparingLong(r -> r[2]).thenComparingLong(r -> r[3]));
        List<Long> q = new ArrayList<>();
        for (long[] r : rank) q.add(r[0]);
        QUEUE.put(id, q);
        SURVEYED.put(id, day);
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
        if (tick % 600 != 457) return;
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
        if (last == null || last != day) survey(level, v, day);
        List<Long> q = QUEUE.getOrDefault(id, List.of());
        if (q.isEmpty()) return 0;
        // A few at a time: the first two, turn about.
        int pick = (int) ((level.getGameTime() / 600L) % Math.min(AT_ONCE, q.size()));
        Ledger.Building b = building(id, BlockPos.of(q.get(pick)));
        if (b == null || !ours(id, b)) {
            remove(id, q.get(pick));
            return 0;
        }
        int n = renovate(level, v, b, STEP, false);
        return Math.max(0, n);
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
        boolean themeChanged = s == null || !s.theme().equals(now.theme());
        List<Work> work = engine.diff(level, v, b, now.drawing(), themeChanged);
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
        DONE.merge(id, 1, Integer::sum);
        if (was != null && (was.version() != now.version() || !was.theme().equals(now.theme()))) {
            Villages.tell(id, level.getDayTime() / 24000L, Villages.spoken(Themes.structure(now.drawing())) + " was renovated to the town's "
                + (was.theme().equals(now.theme()) ? "newer design" : Themes.of(id).name().toLowerCase(java.util.Locale.ROOT) + " look"));
        }
    }

    /** Tests and the stage: the whole of a building renovated now, for nothing. How many blocks. */
    public static int renovateNow(ServerLevel level, Villages.Village v, Ledger.Building b) {
        int all = 0;
        for (int i = 0; i < 200; i++) {
            int n = renovate(level, v, b, 64, true);
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
        if (!SURVEYED.containsKey(id)) survey(level, v, day);
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

        /** Does what stands here answer the design's part (a door for a door, glass for a window...)? */
        static boolean answers(BuildGoal.Part part, BlockState s) {
            if (s.isAir()) return false;
            return switch (part) {
                case DOOR -> s.getBlock() instanceof DoorBlock;
                case BED -> s.getBlock() instanceof BedBlock;
                case WINDOW -> Architecture.pane(s) || s.is(net.minecraft.tags.BlockTags.IMPERMEABLE) || s.getBlock() instanceof net.minecraft.world.level.block.TransparentBlock;
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

        /** A wall, floor or roof block of any make (what a builder lays in a BLOCK place). */
        static boolean structural(BlockState s) {
            if (s.isAir() || s.hasBlockEntity()) return false;
            if (s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS)
                    || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.BRICKS) || Architecture.pane(s) || s.is(Blocks.GLASS)) return true;
            Item it = s.getBlock().asItem();
            if (it == Items.AIR) return false;
            ItemStack st = new ItemStack(it);
            return BuildGoal.isStoneLike(st) || BuildGoal.isDressed(st) && BuildGoal.isBuildingBlock(st);
        }

        /** Is this block of the town's theme for a place of this kind (its ranked list, or anything the theme names)? */
        static boolean ofTheme(UUID village, Blueprints.Style style, BlockState s) {
            Item it = s.getBlock().asItem();
            if (Palettes.ranked(village, style).contains(it)) return true;
            return Materials.named(Themes.of(village), s.getBlock());
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
                        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) continue;   // its foot speaks for it
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
                    if (!structural(s) || Materials.unfit(id, p.style(), s) || themeChanged && !ofTheme(id, p.style(), s)) {
                        rest.add(new Work(Kind.BLOCK, p.pos(), p, null));
                    }
                    continue;
                }
                if (answers(p.part(), s)) continue;
                if (halves.contains(p.pos().asLong()) && (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BedBlock)) continue;
                if (furniture(p.part())) {
                    List<BlockPos> got = orphans.get(p.part());
                    if (got != null && !got.isEmpty() && (s.isAir() || structural(s) || BuildGoal.soft(s))) {
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
                if (structural(s)) rest.add(new Work(Kind.CLEAR, p, null, null));
            }
            // Moves first (a chest out of the way of a wall), then from the bottom up, what is taken down before what is laid.
            rest.sort(Comparator.comparingInt((Work w) -> w.pos().getY()).thenComparingInt(w -> w.kind() == Kind.CLEAR ? 0 : 1));
            List<Work> out = new ArrayList<>(moves);
            out.addAll(rest);
            return out;
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
                ItemStack got = part == BuildGoal.Part.WINDOW ? Crafts.takeOne(level, v, s -> s.is(Items.GLASS_PANE) || s.is(Items.GLASS))
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
         * Move a piece of furniture whole: what is in it (a chest's goods, a furnace's fire, a lectern's book, a sign's
         * words) with it, in the same moment; a bed both halves, its colour kept, turned the way the design lays it.
         */
        static boolean move(ServerLevel level, Villages.Village v, Ledger.Building b, BlockPos from, BlockPos to, @Nullable BuildGoal.Placement cell) {
            BlockState s = level.getBlockState(from);
            if (s.isAir()) return false;
            if (s.getBlock() instanceof BedBlock) {
                BlockPos foot = s.getValue(BedBlock.PART) == BedPart.FOOT ? from : from.relative(BedBlock.getConnectedDirection(s));
                BlockPos head = foot.relative(s.getValue(BedBlock.FACING));
                Direction lie = cell != null ? lie(cell, b.facing()) : s.getValue(BedBlock.FACING);
                BlockPos newHead = to.relative(lie);
                BlockState there = level.getBlockState(newHead);
                if (!(there.isAir() || there.canBeReplaced() || newHead.equals(foot) || newHead.equals(head))) return false;
                // Both at once: the old bed up and the new down, so the house is never a bed short.
                level.setBlock(head, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
                level.setBlock(foot, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
                BlockState bed = s.getBlock().defaultBlockState().setValue(BedBlock.FACING, lie);
                level.setBlock(to, bed.setValue(BedBlock.PART, BedPart.FOOT), 2 | 16);
                level.setBlock(newHead, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
                // A bed bought for the house and kept on its list: its new head on the list in the old one's place.
                String key = "homebeds/" + b.anchor().asLong();
                String extra = Ledger.note(v.id(), key);
                if (extra != null && extra.contains(Long.toString(head.asLong()))) {
                    Ledger.note(v.id(), key, extra.replace(Long.toString(head.asLong()), Long.toString(newHead.asLong())));
                }
                return true;
            }
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
        for (Work w : engine.diff(level, v, b, now.drawing(), s == null || !s.theme().equals(now.theme()))) out.merge(w.kind(), 1, Integer::sum);
        return out;
    }

    /** Tests: a town's survey made now. */
    public static List<BlockPos> surveyForTests(ServerLevel level, Villages.Village v) {
        survey(level, v, level.getDayTime() / 24000L);
        return queue(v.id());
    }

    /** The theme's name, for the stage and the board. */
    static String themeName(UUID village) {
        ThemeData t = Themes.of(village);
        return t.name();
    }
}
