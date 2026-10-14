package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [integrity] The town's buildings kept true to their drawings.
 *
 * <p>Once a day each building is looked over against its drawing ({@link #diff}): a block gone (fire, a creeper,
 * a raid, a builder who ran out half way), the wrong thing where a wall or a stair should be (a fence post, a
 * lantern, a block of earth from the ground works), something put in its doorway or across the way in. What is
 * wrong goes on the town's repair list ({@link #enqueue}), and a hand of the town works the list, the worst
 * building first, a few blocks a visit, out of the stores and in the building's own look (what the rest of its
 * walls, roof and floor are made of): what comes out goes back into the stores. The board and the Buildings page
 * say which buildings "need repair" and which were "repaired" today.
 *
 * <p>The same engine rebuilds a building to a newer drawing or look ([styles]: {@link Reason#RENOVATE}): diff the
 * building against the drawing it should be, enqueue the fixes, and the hands work them as they work repairs.
 */
public final class Restore {

    private Restore() {}

    /** What is wrong with one cell. */
    public enum Kind {
        /** Nothing there (air, grass, water) where the drawing has a block. */
        MISSING,
        /** Something else entirely: a fence where a wall should be, earth where a door should be. */
        WRONG,
        /** The right part in the wrong shape: a full block where a stair or a slab should be. */
        SHAPE,
        /** Something that is no part of the building, in a cell its drawing leaves empty that must be clear (a
         *  doorway, the way in, a stair's headroom) or inside its walls where nothing was ever meant to go. */
        FOREIGN
    }

    /** One thing to put right: the cell, what the drawing wants there (null: clear it), what is wrong, and what is
     *  there now. */
    public record Fix(BlockPos pos, @Nullable BuildGoal.Placement want, Kind kind, BlockState found) {
        /** How much it matters: a hole in a wall or a blocked door before a stair in the wrong shape. */
        public int weight() {
            return switch (kind) {
                case FOREIGN -> 4;
                case MISSING -> want != null && want.part() == BuildGoal.Part.DOOR ? 5 : 3;
                case WRONG -> 3;
                case SHAPE -> 1;
            };
        }
    }

    /** Why a building is on the list. */
    public enum Reason { REPAIR, RENOVATE }

    /** A building on the list: its drawing, why, what is to be done, and since when. */
    public static final class Job {
        public final Ledger.Building building;
        public final String design;
        public final Reason reason;
        public final List<Fix> fixes;
        public final long since;
        int done;

        Job(Ledger.Building building, String design, Reason reason, List<Fix> fixes, long since) {
            this.building = building;
            this.design = design;
            this.reason = reason;
            this.fixes = new ArrayList<>(fixes);
            this.since = since;
        }

        /** How bad: the sum of what is wrong. */
        public int worth() {
            int n = 0;
            for (Fix f : fixes) n += f.weight();
            return n;
        }

        /** Blocks put right so far. */
        public int done() {
            return done;
        }
    }

    // ------------------------------------------------------------------ the diff

    /** What is wrong with a building of the register against the drawing it stands to now (TownBlocks.designOf). */
    public static List<Fix> diff(Level level, UUID village, Ledger.Building b) {
        return diff(level, b, TownBlocks.designOf(village, b));
    }

    /**
     * What is wrong with a building against a drawing (its own, or a newer one for a renovation): every cell of the
     * drawing that does not hold what it wants, and every doorway, way in and stair with something in it that is
     * no part of the building. Cells not loaded are passed over.
     */
    public static List<Fix> diff(Level level, Ledger.Building b, String design) {
        List<Fix> out = new ArrayList<>();
        List<BuildGoal.Placement> plan = BuildGoal.plan(design, b.anchor(), b.facing(), 13);
        if (plan.isEmpty()) return out;
        java.util.Set<Long> mine = new java.util.HashSet<>();
        for (BuildGoal.Placement p : plan) if (p.part() != BuildGoal.Part.CLEAR) mine.add(p.pos().asLong());
        java.util.Set<Long> bedHeads = new java.util.HashSet<>();
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.BED) bedHeads.add(p.pos().relative(bedHead(p, b.facing())).asLong());
        }
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR || p.part() == BuildGoal.Part.STOREHOUSE) continue;
            BlockPos at = p.pos();
            if (!level.isLoaded(at)) continue;
            BlockState st = level.getBlockState(at);
            // A bed's head lies in the next cell, which the drawing may have as floor-level air or another part.
            if (bedHeads.contains(at.asLong()) && st.getBlock() instanceof net.minecraft.world.level.block.BedBlock) continue;
            Kind k = judge(st, p);
            if (k != null) out.add(new Fix(at.immutable(), p, k, st));
        }
        // The doorways and the ways in, and the stairs' headroom: nothing in them that is not the building's.
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.DOOR) {
                Direction outward = TownBlocks.outOf(p.pos(), b.anchor(), mine);
                if (outward == null) continue;
                for (int k = 1; k <= 2; k++) {
                    BlockPos step = p.pos().relative(outward, k);
                    if (mine.contains(step.asLong())) break;
                    for (BlockPos c : new BlockPos[] { step, step.above() }) foreign(level, c, mine, out);
                }
            } else if (p.part() == BuildGoal.Part.LADDER
                    || (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.STONE_STAIR)) {
                for (int h = 1; h <= 2; h++) foreign(level, p.pos().above(h), mine, out);
            }
        }
        return out;
    }

    /**
     * [styles] What it takes to rebuild a building from one drawing to another in place: every cell of the new drawing
     * that does not hold what it wants (diff), and every cell of the old drawing the new one leaves empty that still
     * holds the old building's block, to come out (FOREIGN, nothing wanted). Enqueue the result with Reason.RENOVATE.
     */
    public static List<Fix> diffRenovation(Level level, Ledger.Building b, String fromDesign, String toDesign) {
        List<Fix> out = diff(level, b, toDesign);
        java.util.Set<Long> keep = new java.util.HashSet<>();
        for (BuildGoal.Placement p : BuildGoal.plan(toDesign, b.anchor(), b.facing(), 13)) keep.add(p.pos().asLong());
        java.util.Set<Long> listed = new java.util.HashSet<>();
        for (Fix f : out) listed.add(f.pos().asLong());
        for (BuildGoal.Placement p : BuildGoal.plan(fromDesign, b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || keep.contains(p.pos().asLong()) || !level.isLoaded(p.pos())) continue;
            BlockState st = level.getBlockState(p.pos());
            if (empty(st) || natural(st) || !listed.add(p.pos().asLong())) continue;
            out.add(new Fix(p.pos().immutable(), null, Kind.FOREIGN, st));
        }
        return out;
    }

    private static Direction bedHead(BuildGoal.Placement p, Direction facing) {
        return p.way() == Blueprints.Way.UP ? facing : Blueprints.world(p.way(), facing);
    }

    /** Something no part of the building, in a cell that must be clear. */
    private static void foreign(Level level, BlockPos c, java.util.Set<Long> mine, List<Fix> out) {
        if (mine.contains(c.asLong()) || !level.isLoaded(c)) return;
        BlockState st = level.getBlockState(c);
        if (clearEnough(st) || natural(st)) return;                       // ground the world made is DoorWays' to cut
        for (Fix f : out) if (f.pos().equals(c)) return;
        out.add(new Fix(c.immutable(), null, Kind.FOREIGN, st));
    }

    /** Nothing a folk would walk into: air, light, grass, a flower, a rug, a pressure plate, an open door. */
    static boolean clearEnough(BlockState st) {
        if (st.isAir() || st.is(Blocks.LIGHT)) return true;
        if (st.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock) return true;
        if (st.getBlock() instanceof net.minecraft.world.level.block.PressurePlateBlock) return true;
        if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return true;
        return st.canBeReplaced() && st.getFluidState().isEmpty();
    }

    /** Ground and growth the world made. */
    static boolean natural(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.STONE)
            || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.SNOW_BLOCK) || st.is(BlockTags.LEAVES)
            || st.is(Blocks.DEEPSLATE) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.TERRACOTTA)
            || st.is(Blocks.CLAY) || st.is(Blocks.ICE) || st.is(Blocks.PACKED_ICE);
    }

    /** Does what is there serve what the drawing wants: null if it does, else what is wrong. */
    @Nullable
    public static Kind judge(BlockState st, BuildGoal.Placement p) {
        if (serves(st, p)) return null;
        if (empty(st)) return Kind.MISSING;
        if (earthy(st)) return Kind.WRONG;                                  // earth where a wall, a floor or a footing goes
        if (p.part() == BuildGoal.Part.BLOCK && solidish(st)) return Kind.SHAPE;
        return Kind.WRONG;
    }

    /** An item of earth, sand or gravel (what a builder's load is not to be made of). */
    public static boolean earthyItem(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && earthy(bi.getBlock().defaultBlockState());
    }

    /** Earth, sand and gravel: the ground's, never a wall's or a floor's (a footing laid where the ground stood was
     *  passed over as filled, and the shop stood on a floor of grass). */
    public static boolean earthy(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY)
            || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.FARMLAND) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.SUSPICIOUS_GRAVEL);
    }

    private static boolean empty(BlockState st) {
        return st.isAir() || st.is(Blocks.LIGHT) || (st.canBeReplaced() && !st.getFluidState().is(FluidTags.LAVA)
            && !(st.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock));
    }

    /** A building block of a sort: a full cube, a stair or a slab. */
    private static boolean solidish(BlockState st) {
        Block b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.StairBlock || b instanceof net.minecraft.world.level.block.SlabBlock
            || (st.isSolid() && !st.hasBlockEntity() && !(b instanceof net.minecraft.world.level.block.FenceBlock)
                && !(b instanceof net.minecraft.world.level.block.WallBlock) && !(b instanceof net.minecraft.world.level.block.DoorBlock)
                && !(b instanceof net.minecraft.world.level.block.TrapDoorBlock) && !(b instanceof net.minecraft.world.level.block.FenceGateBlock));
    }

    private static boolean fullCube(BlockState st) {
        Block b = st.getBlock();
        if (b instanceof net.minecraft.world.level.block.StairBlock || b instanceof net.minecraft.world.level.block.SlabBlock) return false;
        return solidish(st);
    }

    /**
     * Does this block do for this cell of the drawing? Not the exact block (a builder makes a wall of what the town
     * has, and the town refaces it in stone and then brick as it grows), but the right part in the right shape: a
     * stair where a stair goes, a slab where a slab goes, a full block of something built in a wall, a door in the
     * doorway, a bed where the bed goes, and so on.
     */
    public static boolean serves(BlockState st, BuildGoal.Placement p) {
        Block b = st.getBlock();
        return switch (p.part()) {
            case BLOCK -> switch (p.style()) {
                case ROOF_STAIR, ROOF_STAIR_TOP, STONE_STAIR -> b instanceof net.minecraft.world.level.block.StairBlock;
                case ROOF_SLAB, ROOF_SLAB_TOP, STONE_SLAB -> b instanceof net.minecraft.world.level.block.SlabBlock;
                case PANE, GLASS -> glass(st);
                case HANGING -> b instanceof net.minecraft.world.level.block.LanternBlock;
                case SOIL, GENERIC -> fullCube(st) || earthy(st);
                default -> fullCube(st) && !earthy(st);
            };
            case WINDOW -> glass(st);
            case DOOR -> b instanceof net.minecraft.world.level.block.DoorBlock;
            case FENCE -> b instanceof net.minecraft.world.level.block.FenceBlock || b instanceof net.minecraft.world.level.block.WallBlock
                || b instanceof net.minecraft.world.level.block.IronBarsBlock;
            case GATE -> b instanceof net.minecraft.world.level.block.FenceGateBlock;
            case TORCH -> st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || b instanceof net.minecraft.world.level.block.LanternBlock
                || st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH);
            case LANTERN -> b instanceof net.minecraft.world.level.block.LanternBlock || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)
                || st.is(Blocks.GLOWSTONE) || st.is(Blocks.SEA_LANTERN) || st.is(Blocks.SHROOMLIGHT);
            case LADDER -> b instanceof net.minecraft.world.level.block.LadderBlock;
            case BED -> b instanceof net.minecraft.world.level.block.BedBlock;
            case CHEST -> b instanceof net.minecraft.world.level.block.ChestBlock || b instanceof net.minecraft.world.level.block.BarrelBlock;
            case FURNACE -> b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock;
            case CRAFTING_TABLE -> st.is(Blocks.CRAFTING_TABLE) || st.hasBlockEntity() || b instanceof net.minecraft.world.level.block.CraftingTableBlock;
            case OBSIDIAN -> st.is(Blocks.OBSIDIAN) || st.is(Blocks.CRYING_OBSIDIAN);
            case WATER -> st.getFluidState().is(FluidTags.WATER);
            case HAY -> st.is(Blocks.HAY_BLOCK);
            case BARREL -> b instanceof net.minecraft.world.level.block.BarrelBlock;
            case FLOWER -> st.is(BlockTags.SMALL_FLOWERS) || st.is(BlockTags.FLOWER_POTS) || st.is(BlockTags.FLOWERS);
            case CARPET -> b instanceof net.minecraft.world.level.block.CarpetBlock;
            case ANVIL -> b instanceof net.minecraft.world.level.block.AnvilBlock;
            case CAULDRON -> b instanceof net.minecraft.world.level.block.AbstractCauldronBlock;
            case BELL -> b instanceof net.minecraft.world.level.block.BellBlock;
            case BOOKSHELF -> st.is(Blocks.BOOKSHELF) || st.is(Blocks.CHISELED_BOOKSHELF);
            case LECTERN -> b instanceof net.minecraft.world.level.block.LecternBlock;
            case ENCHANTING -> b instanceof net.minecraft.world.level.block.EnchantingTableBlock;
            case BREWING -> b instanceof net.minecraft.world.level.block.BrewingStandBlock;
            case SMOKER -> b instanceof net.minecraft.world.level.block.SmokerBlock;
            case LOOM -> b instanceof net.minecraft.world.level.block.LoomBlock;
            case GRINDSTONE -> b instanceof net.minecraft.world.level.block.GrindstoneBlock;
            case CAMPFIRE -> b instanceof net.minecraft.world.level.block.CampfireBlock;
            case NOTE_BLOCK -> st.is(Blocks.NOTE_BLOCK);
            case STOREHOUSE -> b instanceof com.jrpetty.mcassistant.block.StorehouseBlock;
            case CARTOGRAPHY -> st.is(Blocks.CARTOGRAPHY_TABLE);
            case CLEAR -> true;
        };
    }

    private static boolean glass(BlockState st) {
        Block b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.IronBarsBlock          // panes are iron-bars blocks
            || b instanceof net.minecraft.world.level.block.TransparentBlock || b instanceof net.minecraft.world.level.block.StainedGlassBlock
            || st.is(Blocks.GLASS) || st.is(Blocks.TINTED_GLASS) || st.is(net.minecraft.tags.BlockTags.IMPERMEABLE);
    }

    /** How much of a building is as its drawing has it: the share of its drawing's cells that serve, 0 to 1. */
    public static double fidelity(Level level, UUID village, Ledger.Building b) {
        String design = TownBlocks.designOf(village, b);
        List<BuildGoal.Placement> plan = BuildGoal.plan(design, b.anchor(), b.facing(), 13);
        int n = 0;
        for (BuildGoal.Placement p : plan) if (p.part() != BuildGoal.Part.CLEAR && p.part() != BuildGoal.Part.STOREHOUSE) n++;
        if (n == 0) return 1.0;
        int wrong = 0;
        for (Fix f : diff(level, b, design)) if (f.want() != null) wrong++;
        return Math.max(0.0, (n - wrong) / (double) n);
    }

    // ------------------------------------------------------------------ the list

    /** Each town's list, by the building's anchor. */
    private static final Map<UUID, Map<Long, Job>> QUEUE = new ConcurrentHashMap<>();
    /** When each building was last looked over (by anchor). */
    private static final Map<Long, Long> SURVEYED = new ConcurrentHashMap<>();
    /** The day each building was last put right (by anchor). */
    private static final Map<Long, Long> REPAIRED = new ConcurrentHashMap<>();
    /** What each town's hands have put right in all (blocks), and buildings mended. */
    private static final Map<UUID, int[]> TALLY = new ConcurrentHashMap<>();

    /** Put a building on the list with these fixes (replacing what was there for it). Empty fixes take it off. */
    public static void enqueue(UUID village, Ledger.Building b, String design, List<Fix> fixes, Reason reason, long now) {
        Map<Long, Job> q = QUEUE.computeIfAbsent(village, k -> new ConcurrentHashMap<>());
        if (fixes.isEmpty()) {
            q.remove(b.anchor().asLong());
            return;
        }
        Job was = q.get(b.anchor().asLong());
        // A renovation under way is not put back to a repair by the day's survey.
        if (was != null && was.reason == Reason.RENOVATE && reason == Reason.REPAIR) {
            return;
        }
        q.put(b.anchor().asLong(), new Job(b, design, reason, fixes, was != null ? was.since : now));
    }

    /** The town's list, the worst first. */
    public static List<Job> queue(UUID village) {
        Map<Long, Job> q = QUEUE.get(village);
        if (q == null) return List.of();
        List<Job> out = new ArrayList<>(q.values());
        out.sort(Comparator.comparingInt((Job j) -> j.reason == Reason.RENOVATE ? 1 : 0).thenComparingInt(j -> -j.worth()));
        return out;
    }

    /** The job on the list for the building at this anchor, or null. */
    @Nullable
    public static Job jobFor(UUID village, BlockPos anchor) {
        Map<Long, Job> q = QUEUE.get(village);
        return q == null ? null : q.get(anchor.asLong());
    }

    /** What the board and the Buildings page say of a building: "needs repair", "being renovated", "repaired" (put
     *  right within the last day), or "" (sound). */
    public static String status(UUID village, BlockPos anchor, long day) {
        Job j = jobFor(village, anchor);
        if (j != null) return j.reason == Reason.RENOVATE ? "being renovated" : "needs repair";
        Long fixed = REPAIRED.get(anchor.asLong());
        return fixed != null && day - fixed <= 1 ? "repaired" : "";
    }

    /** The board's line: how many buildings need repair, and how many were put right today (null: nothing to say). */
    @Nullable
    public static String boardLine(UUID village, long day) {
        int need = 0, renovating = 0;
        for (Job j : queue(village)) if (j.reason == Reason.RENOVATE) renovating++; else need++;
        int mended = 0;
        for (Ledger.Building b : Ledger.buildings(village)) {
            Long fixed = REPAIRED.get(b.anchor().asLong());
            if (fixed != null && fixed == day) mended++;
        }
        if (need == 0 && renovating == 0 && mended == 0) return null;
        StringBuilder sb = new StringBuilder(need > 0 ? "RW|Upkeep: " : "RN|Upkeep: ");
        sb.append(need == 0 ? "every building sound" : need + (need == 1 ? " building needs" : " buildings need") + " repair");
        if (renovating > 0) sb.append("; ").append(renovating).append(" being made over");
        if (mended > 0) sb.append("; ").append(mended).append(" repaired today");
        return sb.append('.').toString();
    }

    /** {blocks put right, buildings mended} by this town's hands. */
    public static int[] tally(UUID village) {
        int[] t = TALLY.get(village);
        return t == null ? new int[2] : t.clone();
    }

    public static void resetForTests() {
        QUEUE.clear();
        SURVEYED.clear();
        REPAIRED.clear();
        TALLY.clear();
        LAST.clear();
        SCAFFOLDS.clear();
    }

    // ------------------------------------------------------------------ what was given up half built

    /** A building given up before it was finished (its lot found wanting, its builder unable to reach it): what was
     *  laid of it, and since when. Its blocks are taken down into the stores, a few a visit, unless the town takes
     *  the same building up again on the same ground (resumed). */
    public record Scaffold(Ledger.Building building, int placed, long since) {}

    private static final Map<UUID, Map<Long, Scaffold>> SCAFFOLDS = new ConcurrentHashMap<>();
    /** The Ledger note the scaffolds are kept under, so a restart does not forget a frame left standing:
     *  "structure,x,y,z,facing,since;..." */
    private static final String SCAFFOLD_NOTE = "integrity/scaffolds";

    /** A building was given up on its ground (Villages.rejectSite): what was laid of it is to come down. */
    public static void abandoned(UUID village, Ledger.Building b, long now) {
        if (of(village).putIfAbsent(b.anchor().asLong(), new Scaffold(b, -1, now)) == null) save(village);
    }

    /** The town has taken a building up again on this ground: it is not scaffolding to clear. */
    public static void resumed(UUID village, BlockPos anchor) {
        if (of(village).remove(anchor.asLong()) != null) save(village);
    }

    /** What is standing given up, half built. */
    public static List<Scaffold> scaffolds(UUID village) {
        return new ArrayList<>(of(village).values());
    }

    /** A town's scaffolds, read back from its Ledger note the first time they are asked for. */
    private static Map<Long, Scaffold> of(UUID village) {
        return SCAFFOLDS.computeIfAbsent(village, k -> {
            Map<Long, Scaffold> m = new ConcurrentHashMap<>();
            String note = Ledger.note(village, SCAFFOLD_NOTE);
            if (note == null || note.isEmpty()) return m;
            for (String one : note.split(";")) {
                String[] f = one.split(",");
                if (f.length != 6) continue;
                try {
                    Direction d = Direction.byName(f[4]);
                    if (d == null) continue;
                    BlockPos a = new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
                    m.put(a.asLong(), new Scaffold(new Ledger.Building(f[0], a, d), -1, Long.parseLong(f[5])));
                } catch (NumberFormatException ignored) {
                    // an unreadable entry from an older or damaged save: dropped
                }
            }
            return m;
        });
    }

    private static void save(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Scaffold s : of(village).values()) {
            BlockPos a = s.building().anchor();
            if (sb.length() > 0) sb.append(';');
            sb.append(s.building().structure()).append(',').append(a.getX()).append(',').append(a.getY()).append(',').append(a.getZ())
                .append(',').append(s.building().facing().getName()).append(',').append(s.since());
        }
        Ledger.note(village, SCAFFOLD_NOTE, sb.toString());
    }

    /** The cells of a given-up building that still hold something it laid (its drawing's cells, not the world's
     *  own ground, nothing of a building of the register standing over the same ground). */
    static List<BlockPos> laid(Level level, Villages.Village v, Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || !level.isLoaded(p.pos())) continue;
            BlockState st = level.getBlockState(p.pos());
            if (empty(st) || natural(st) || TownBlocks.fitting(st)) continue;
            Ledger.Building over = TownBlocks.buildingAt(level, v, p.pos());
            if (over != null && !over.anchor().equals(b.anchor())) continue;
            out.add(p.pos());
        }
        return out;
    }

    /** Take down up to so many blocks of what was given up, top first, into the stores. Returns how many. */
    static int clearScaffolds(ServerLevel level, Villages.Village v, int budget) {
        Map<Long, Scaffold> m = of(v.id());
        if (m.isEmpty()) return 0;
        int done = 0;
        for (Scaffold s : new ArrayList<>(m.values())) {
            if (done >= budget) break;
            Ledger.Building b = s.building();
            if (!Land.areaLoaded(level, b.anchor(), 10)) continue;
            // Built after all (the same building on the same ground, in the register): nothing to clear.
            boolean standing = false;
            for (Ledger.Building o : Ledger.buildings(v.id())) if (o.anchor().equals(b.anchor()) && o.structure().equals(b.structure())) standing = true;
            Villages.Site again = Villages.sitesOf(v.id()).get(b.structure());
            if (standing || (again != null && again.anchor().equals(b.anchor()))) { m.remove(b.anchor().asLong()); save(v.id()); continue; }
            List<BlockPos> left = laid(level, v, b);
            if (s.placed() < 0) m.put(b.anchor().asLong(), new Scaffold(b, left.size(), s.since()));
            // Gone once nothing of it stands above the ground (its footing filled back as the stores' earth allows).
            boolean standsUp = false;
            for (BlockPos p : left) if (p.getY() >= b.anchor().getY()) { standsUp = true; break; }
            if (left.isEmpty() || !standsUp && Market.stock(level, v.id(), it -> it.is(Items.DIRT)) == 0) {
                m.remove(b.anchor().asLong());
                save(v.id());
                continue;
            }
            left.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
            for (BlockPos p : left) {
                if (done >= budget) break;
                // The footing, in the ground: earth back in its place out of the stores (grass on top), or the
                // footing left as a pad of stone; never a pit where a house was begun.
                boolean ground = p.getY() < b.anchor().getY();
                if (ground && !Crafts.take(level, v, it -> it.is(Items.DIRT), 1)) continue;
                if (!TownJobs.atWork(level, v, "repairs", p, "taking down a building given up")) return done;
                BlockState st = level.getBlockState(p);
                if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c) {
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        ItemStack it = c.removeItemNoUpdate(i);
                        if (!it.isEmpty()) Crafts.store(level, v, it);
                    }
                }
                for (ItemStack drop : Block.getDrops(st, level, p, level.getBlockEntity(p))) Crafts.store(level, v, drop);
                BlockState back = !ground ? Blocks.AIR.defaultBlockState()
                    : level.getBlockState(p.above()).isAir() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                level.setBlock(p, back, 3);
                TownBlocks.traced(v, p, TownBlocks.Why.TIDY);
                done++;
            }
        }
        return done;
    }

    // ------------------------------------------------------------------ the survey and the work

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    /** Tests: the survey and the repairs stood down (the other features' tests, whose towns are stamped and taken
     *  apart on purpose); a test of the town's upkeep, or a long growth test, stands them up. */
    private static volatile boolean stoodDown;

    public static void standDownForTests(boolean down) {
        stoodDown = down;
    }
    /** How often a building is looked over. */
    static final long EVERY_BUILDING = 24000L;
    /** Buildings looked over in one visit (the visits are every quarter minute: TownWork). */
    private static final int LOOK = 3;
    /** Blocks put right in one visit. */
    private static final int BUDGET = 12;
    /** Buildings on the list looked at in one visit (the worst first): a long list waiting on the stores is not
     *  walked end to end every quarter minute. */
    private static final int JOBS_A_VISIT = 3;

    /** One visit (TownWork.tick): a few buildings looked over, a few blocks put right. */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (stoodDown) return;
        survey(level, v);
        int done = work(level, v, BUDGET);
        if (done < BUDGET) clearScaffolds(level, v, BUDGET - done);
    }

    /** Look over the next few buildings that have not been looked at today, and list what is wrong with them. */
    public static int survey(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        int looked = 0;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (looked >= LOOK) break;
            long key = b.anchor().asLong();
            if (now - SURVEYED.getOrDefault(key, -EVERY_BUILDING - 1) < EVERY_BUILDING && now >= SURVEYED.getOrDefault(key, 0L)) continue;
            if (!Land.areaLoaded(level, b.anchor(), 10)) continue;
            if (skip(v.id(), b)) { SURVEYED.put(key, now); continue; }
            SURVEYED.put(key, now);
            looked++;
            Job j = jobFor(v.id(), b.anchor());
            if (j != null && j.reason == Reason.RENOVATE) continue;
            String design = TownBlocks.designOf(v.id(), b);
            List<Fix> fixes = diff(level, b, design);
            enqueue(v.id(), b, design, fixes, Reason.REPAIR, now);
        }
        return looked;
    }

    /** Every building looked over now (the operator's command, the smoke): how many. */
    public static int surveyAll(ServerLevel level, Villages.Village v) {
        int n = 0;
        long now = level.getGameTime();
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!Land.areaLoaded(level, b.anchor(), 10) || skip(v.id(), b)) continue;
            Job j = jobFor(v.id(), b.anchor());
            if (j != null && j.reason == Reason.RENOVATE) continue;
            String design = TownBlocks.designOf(v.id(), b);
            enqueue(v.id(), b, design, diff(level, b, design), Reason.REPAIR, now);
            SURVEYED.put(b.anchor().asLong(), now);
            n++;
        }
        return n;
    }

    /** Buildings the survey leaves alone: one being raised a storey (its roof is off on purpose), the founders'
     *  first house as they left it, a folk's own house, the wall. */
    private static boolean skip(UUID village, Ledger.Building b) {
        if (b.structure().equals("fortify")) return true;
        if (Grow.raisingNow(village, b.anchor())) return true;
        if (HousingMarket.isPrivate(village, b.anchor())) return true;
        return Beliefs.untouchable(village, b);
    }

    /** Put right up to so many blocks, the worst building first. Returns how many. */
    public static int work(ServerLevel level, Villages.Village v, int budget) {
        int done = 0;
        long day = level.getDayTime() / 24000L;
        int jobs = 0;
        for (Job j : queue(v.id())) {
            if (done >= budget || jobs++ >= JOBS_A_VISIT) break;
            if (!Land.areaLoaded(level, j.building.anchor(), 10)) continue;
            if (j.reason == Reason.REPAIR && skip(v.id(), j.building)) {
                enqueue(v.id(), j.building, j.design, List.of(), j.reason, 0);
                continue;
            }
            j.fixes.sort(Comparator.comparingInt((Fix f) -> f.want() == null ? 0 : finishing(f.want()) ? 2 : 1)
                .thenComparingInt(f -> f.pos().getY()));
            List<Fix> left = new ArrayList<>();
            boolean waited = false;
            Map<String, Block> look = new HashMap<>();        // the building's own look, worked out when first wanted
            for (Fix f : j.fixes) {
                if (done >= budget || waited) { left.add(f); continue; }
                // Still wrong? (Something else may have put it right, or a hand mended it meanwhile.)
                BlockState now = level.getBlockState(f.pos());
                if (f.want() != null ? serves(now, f.want()) : clearEnough(now) || natural(now)) continue;
                int r = mend(level, v, j, f, now, look);
                if (r < 0) { waited = true; left.add(f); continue; }
                if (r == 0) { left.add(f); continue; }
                done++;
                j.done++;
                TALLY.computeIfAbsent(v.id(), k -> new int[2])[0]++;
            }
            if (left.isEmpty()) {
                QUEUE.getOrDefault(v.id(), Map.of()).remove(j.building.anchor().asLong());
                REPAIRED.put(j.building.anchor().asLong(), day);
                TALLY.computeIfAbsent(v.id(), k -> new int[2])[1]++;
                if (j.done > 0) Villages.tell(v.id(), day, (j.reason == Reason.RENOVATE ? "the builders made over " : "the builders mended ")
                    + Villages.spoken(j.building.structure()) + " at " + j.building.anchor().getX() + ", " + j.building.anchor().getZ());
            } else {
                j.fixes.clear();
                j.fixes.addAll(left);
            }
            if (waited) break;
        }
        return done;
    }

    private static boolean finishing(BuildGoal.Placement p) {
        return p.part() == BuildGoal.Part.LANTERN || p.part() == BuildGoal.Part.TORCH || p.part() == BuildGoal.Part.FLOWER
            || p.part() == BuildGoal.Part.CARPET || p.part() == BuildGoal.Part.BELL || p.part() == BuildGoal.Part.WATER;
    }

    /**
     * Put one cell right: 1 done, 0 not now (nothing in the stores to do it with: it waits for them), -1 waiting for
     * a hand to come to it.
     */
    private static int mend(ServerLevel level, Villages.Village v, Job j, Fix f, BlockState now, Map<String, Block> look) {
        BlockPos at = f.pos();
        TownBlocks.Why why = j.reason == Reason.RENOVATE ? TownBlocks.Why.RENOVATE : TownBlocks.Why.REPAIR;
        if (!TownBlocks.mayBuild(level, v, at, j.building.anchor(), why)) return 0;
        BuildGoal.Placement want = f.want();
        // What to put in, before anything comes out: a wall is not opened up for want of the block to close it.
        BlockState put = null;
        if (want != null) {
            if (look.isEmpty()) look.putAll(lookOf(level, j));
            put = fromStores(level, v, want, look, j.building.facing(), at);
            if (put == null) return 0;
        }
        String what = want == null ? "clearing the way into " : j.reason == Reason.RENOVATE ? "making over " : "mending ";
        if (!TownJobs.atWork(level, v, "repairs", at, what + Villages.spoken(j.building.structure()).replaceFirst("^(the|a) ", "the "))) {
            if (put != null) giveBack(level, v, put);
            return -1;
        }
        // What is there comes out into the stores (a chest's goods with it).
        if (!empty(now)) {
            if (level.getBlockEntity(at) instanceof net.minecraft.world.Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.removeItemNoUpdate(i);
                    if (!s.isEmpty()) Crafts.store(level, v, s);
                }
            }
            for (ItemStack drop : Block.getDrops(now, level, at, level.getBlockEntity(at))) Crafts.store(level, v, drop);
            if (now.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || now.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                level.destroyBlock(at, false);
            } else {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        if (put == null) {
            TownBlocks.traced(v, at, why);
            return 1;
        }
        boolean ok = lay(level, j, want, put, at);
        if (!ok) {
            giveBack(level, v, put);
            return 0;
        }
        TownBlocks.traced(v, at, why);
        return 1;
    }

    private static boolean lay(ServerLevel level, Job j, BuildGoal.Placement want, BlockState put, BlockPos at) {
        Direction facing = j.building.facing();
        if (want.part() == BuildGoal.Part.BED) {
            Direction lie = bedHead(want, facing);
            BlockPos head = at.relative(lie);
            if (!level.getBlockState(head).canBeReplaced()) return false;
            BlockState bed = put.setValue(net.minecraft.world.level.block.BedBlock.FACING, lie);
            level.setBlock(at, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2 | 16);
            level.setBlock(head, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
            return true;
        }
        if (want.part() == BuildGoal.Part.WATER) {
            level.setBlockAndUpdate(at, Blocks.WATER.defaultBlockState());
            return true;
        }
        BlockState st = put;
        if (want.part() == BuildGoal.Part.FURNACE || want.part() == BuildGoal.Part.CHEST || want.part() == BuildGoal.Part.LADDER) {
            Direction d = want.way() == Blueprints.Way.UP || want.way() == Blueprints.Way.FRONT
                ? facing.getOpposite() : Blueprints.world(want.way(), facing);
            if (st.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
                st = st.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, d);
            }
        } else {
            st = BuildGoal.orient(st, want, facing, level, at);
        }
        if (want.part() == BuildGoal.Part.DOOR) return BuildGoal.hangDoor(level, at, st);
        level.setBlock(at, st, 3);
        BlockState joined = Block.updateFromNeighbourShapes(level.getBlockState(at), level, at);
        if (joined != level.getBlockState(at)) level.setBlock(at, joined, 2 | 16);
        return true;
    }

    /** What the rest of the building is made of, part by part and style by style (the most of each), so a mend
     *  matches the wall round it. */
    private static Map<String, Block> lookOf(ServerLevel level, Job j) {
        Map<String, Map<Block, Integer>> seen = new HashMap<>();
        for (BuildGoal.Placement p : BuildGoal.plan(j.design, j.building.anchor(), j.building.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || !level.isLoaded(p.pos())) continue;
            BlockState st = level.getBlockState(p.pos());
            if (!serves(st, p)) continue;
            seen.computeIfAbsent(p.part() + "/" + p.style(), k -> new HashMap<>()).merge(st.getBlock(), 1, Integer::sum);
        }
        Map<String, Block> out = new HashMap<>();
        for (Map.Entry<String, Map<Block, Integer>> e : seen.entrySet()) {
            Block best = null;
            int most = -1;
            for (Map.Entry<Block, Integer> b : e.getValue().entrySet()) if (b.getValue() > most) { most = b.getValue(); best = b.getKey(); }
            if (best != null) out.put(e.getKey(), best);
        }
        return out;
    }

    /** A block for this cell out of the stores: the building's own look for it first, then what the drawing would
     *  have a builder look for, then (a wall's or a floor's block) any building block. Null: the stores have none. */
    @Nullable
    private static BlockState fromStores(ServerLevel level, Villages.Village v, BuildGoal.Placement want, Map<String, Block> look,
                                         Direction facing, BlockPos at) {
        Block own = look.get(want.part() + "/" + want.style());
        if (own != null && own.asItem() != Items.AIR) {
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(own.asItem()));
            if (!one.isEmpty()) return own.defaultBlockState();
        }
        if (want.part() == BuildGoal.Part.BLOCK) {
            java.util.function.Predicate<ItemStack> styled = BuildGoal.preferred(want.style());
            ItemStack one = Crafts.takeOne(level, v, s -> styled.test(s) && s.getItem() instanceof BlockItem && !Palettes.precious(s));
            if (one.isEmpty() && fullStyle(want.style())) {
                one = Crafts.takeOne(level, v, s -> BuildGoal.isBuildingBlock(s) && !Palettes.precious(s));
            }
            // A roof's stair or slab cut there of the stores' planks: six make four stairs, three make six slabs.
            if (one.isEmpty() && (want.style() == Blueprints.Style.ROOF_STAIR || want.style() == Blueprints.Style.ROOF_STAIR_TOP)) {
                return make(level, v, 6, Blocks.OAK_STAIRS, Items.OAK_STAIRS, 3);
            }
            if (one.isEmpty() && (want.style() == Blueprints.Style.ROOF_SLAB || want.style() == Blueprints.Style.ROOF_SLAB_TOP)) {
                return make(level, v, 3, Blocks.OAK_SLAB, Items.OAK_SLAB, 5);
            }
            if (one.isEmpty() || !(one.getItem() instanceof BlockItem bi)) return null;
            return bi.getBlock().defaultBlockState();
        }
        if (want.part() == BuildGoal.Part.WATER) {
            return Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1) ? giveBucket(level, v) : null;
        }
        java.util.function.Predicate<ItemStack> part = BuildGoal.itemForPart(want.part());
        ItemStack one = Crafts.takeOne(level, v, s -> part.test(s) && s.getItem() instanceof BlockItem);
        if (one.isEmpty() || !(one.getItem() instanceof BlockItem bi)) return made(level, v, want);
        return bi.getBlock().defaultBlockState();
    }

    /**
     * A wooden part made there and then of the stores' planks, as a hand would at a bench, when the stores have none
     * made: a door (six planks make three, two go back), a chest, a crafting table, a ladder's rungs, a fence, a gate,
     * a barrel, a roof stair (six planks make four) or a roof slab (three make six); a cauldron of seven iron. A house left with no door for want
     * of a door was a house nobody could get into. Null if the planks are not there either.
     */
    @Nullable
    private static BlockState made(ServerLevel level, Villages.Village v, BuildGoal.Placement want) {
        return switch (want.part()) {
            case DOOR -> make(level, v, 6, Blocks.OAK_DOOR, Items.OAK_DOOR, 2);
            case CHEST -> make(level, v, 8, Blocks.CHEST, Items.CHEST, 0);
            case CRAFTING_TABLE -> make(level, v, 4, Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE, 0);
            case LADDER -> make(level, v, 4, Blocks.LADDER, Items.LADDER, 2);
            case FENCE -> make(level, v, 4, Blocks.OAK_FENCE, Items.OAK_FENCE, 2);
            case GATE -> make(level, v, 4, Blocks.OAK_FENCE_GATE, Items.OAK_FENCE_GATE, 0);
            case BARREL -> make(level, v, 7, Blocks.BARREL, Items.BARREL, 0);
            case CAULDRON -> Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 7) ? Blocks.CAULDRON.defaultBlockState() : null;
            default -> null;
        };
    }

    /** Planks made into a thing: so many planks out of the stores, one of it set, the rest of the batch back in. */
    @Nullable
    private static BlockState make(ServerLevel level, Villages.Village v, int planks, Block block, net.minecraft.world.item.Item item, int spare) {
        if (!Crafts.usePlanks(level, v, planks)) return null;
        if (spare > 0) Crafts.store(level, v, new ItemStack(item, spare));
        return block.defaultBlockState();
    }

    private static BlockState giveBucket(ServerLevel level, Villages.Village v) {
        Crafts.store(level, v, new ItemStack(Items.BUCKET));
        return Blocks.WATER.defaultBlockState();
    }

    /** Styles any full building block will stand in for at a pinch (not a stair, a slab, glass or earth). */
    private static boolean fullStyle(Blueprints.Style s) {
        return switch (s) {
            case GENERIC, FOUNDATION, WALL_LOW, MASONRY, BRICK, FLOOR, WALL, ROOF_BLOCK, POST, BEAM_ACROSS, BEAM_ALONG -> true;
            default -> false;
        };
    }

    private static void giveBack(ServerLevel level, Villages.Village v, BlockState put) {
        if (put.is(Blocks.WATER)) {
            Crafts.take(level, v, s -> s.is(Items.BUCKET), 1);
            Crafts.store(level, v, new ItemStack(Items.WATER_BUCKET));
            return;
        }
        net.minecraft.world.item.Item item = put.getBlock().asItem();
        if (item != Items.AIR) Crafts.store(level, v, new ItemStack(item));
    }
}
