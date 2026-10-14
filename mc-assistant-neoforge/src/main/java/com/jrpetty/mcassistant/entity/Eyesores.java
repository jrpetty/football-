package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tidy] What spoils a town's looks, found by walking it column by column: a few thousand columns a visit, the whole
 * town gone over every couple of minutes, every spot written down with what it is.
 *
 * <ul>
 * <li><b>Spills</b>: lava lying in the town; a lone puddle of water (a bucket spilt, a spring left where a pond was
 *     filled).</li>
 * <li><b>Holes</b> and pits (two deep and more) in the town's ground; a pothole in a street or on the square.</li>
 * <li><b>Pillars</b>: a column of earth or stone standing up on its own (a folk that towered up to a tree's top or out
 *     of a night shelter left its blocks behind it); <b>stray blocks</b>: one block of earth or stone sitting on the
 *     ground, or hanging in the air, that nobody built.</li>
 * <li><b>Scaffolding</b> left standing.</li>
 * <li><b>Half-felled trees</b>: a trunk or a stump with no crown, standing on its own; <b>leaves</b> left hanging in
 *     the air with no trunk.</li>
 * <li><b>Torches on the ground</b> (a folk working in the dark drops one at its feet).</li>
 * <li><b>Scars</b>: bare earth or rock in the town's turf where the ground was cut and never grassed over.</li>
 * <li><b>Camp beds</b> on the square once everybody has a bed in a house, and <b>chests</b> left out on the square or
 *     the streets once the storehouse stands.</li>
 * </ul>
 * Never anything in or round a building or a building going up (its lot, its doors' ways in, its steps), on the
 * fields, in the mine, on a worker's own ground, on the square's set spots (the well, the monuments), at the board,
 * nor anything a player put there (PlayerBlocks).
 *
 * <p>Walking it, the town's looks are measured as well (TownLooks): how green its open ground is, how much of its
 * streets is lit after dark, its flowers.
 */
public final class Eyesores {

    private Eyesores() {}

    /** What spoils the look, the worst first (the crew sees to the worst first). */
    public enum Kind {
        SPILL("a spill", 100), HOLE("a hole", 90), PILLAR("a pillar", 80), CAMP_BED("a camp bed", 70),
        CHEST("a chest left out", 65), SCAFFOLD("scaffolding", 60), ROAD("a pothole in the street", 58), STRAY("a stray block", 55),
        HALF_TREE("a half-felled tree", 45), TORCH("a torch on the ground", 30), SCAR("a scar in the turf", 25),
        LEAVES("leaves hanging in the air", 15);

        public final String words;
        public final int worst;

        Kind(String words, int worst) {
            this.words = words;
            this.worst = worst;
        }
    }

    /** One eyesore: what, where (the block to put right), and a word on it. */
    public record Spot(Kind kind, BlockPos pos, String note) {}

    /** A whole walk of the town: every eyesore found, and what the looks were made of. */
    public static final class Walk {
        public final List<Spot> spots = new ArrayList<>();
        public final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        /** Open ground: green (turf, plants), bare (earth, rock). Streets: cells, lit. Flowers in the town. */
        public int green, bare, streets, lit, flowers;
        public long finished = -1L;
        /** For a proud town: dark street cells (the air over them), leaves hanging over a street, grass verges by one. */
        public final List<BlockPos> dark = new ArrayList<>(), overhang = new ArrayList<>(), verges = new ArrayList<>();

        void add(Spot s) {
            spots.add(s);
            counts.merge(s.kind(), 1, Integer::sum);
        }

        public int total() {
            return spots.size();
        }

        public int count(Kind k) {
            return counts.getOrDefault(k, 0);
        }
    }

    /** A town's walk under way: its ground marked off, how far along, and what it has found so far. */
    private static final class Survey {
        int reach = -1, side;
        boolean[] keptOff;
        int cursor;
        Walk walking = new Walk(), last;
        boolean housed, storehouse;
    }

    private static final Map<UUID, Survey> SURVEYS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SURVEYS.clear();
    }

    /** The last whole walk of the town, or null if none has finished yet. */
    @Nullable
    public static Walk last(UUID village) {
        Survey s = SURVEYS.get(village);
        return s == null ? null : s.last;
    }

    /** Walk on through the town by so many columns (a whole walk finished and kept as the last one when it gets round). */
    public static void step(ServerLevel level, Villages.Village v, int columns) {
        if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) return;
        Survey s = SURVEYS.computeIfAbsent(v.id(), k -> new Survey());
        if (s.reach < 0) begin(level, v, s);
        int n = s.side * s.side;
        for (int i = 0; i < columns && s.cursor < n; i++, s.cursor++) {
            int dx = s.cursor % s.side - s.reach, dz = s.cursor / s.side - s.reach;
            column(level, v, s, dx, dz, s.walking);
        }
        if (s.cursor >= n) {
            s.walking.finished = level.getGameTime();
            s.last = s.walking;
            s.walking = new Walk();
            s.cursor = 0;
            s.reach = -1;
        }
    }

    /** The whole town walked now, at once (the tests, the audit). */
    public static Walk walkNow(ServerLevel level, Villages.Village v) {
        Survey s = SURVEYS.computeIfAbsent(v.id(), k -> new Survey());
        s.cursor = 0;
        s.walking = new Walk();
        s.reach = -1;
        begin(level, v, s);
        step(level, v, Integer.MAX_VALUE);
        return s.last == null ? new Walk() : s.last;
    }

    // ------------------------------------------------------------------ the ground kept off

    private static void begin(ServerLevel level, Villages.Village v, Survey s) {
        UUID id = v.id();
        int reach = Villages.townReach(id);
        s.reach = reach;
        s.side = 2 * reach + 1;
        s.keptOff = new boolean[s.side * s.side];
        BlockPos c = v.centre();
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (b.structure().equals("fortify")) {
                ring(s, b.anchor().getX() - c.getX(), b.anchor().getZ() - c.getZ(), Watch.R, 2);
                continue;
            }
            int[] h = half(b.structure(), b.facing());
            box(s, b.anchor().getX() - c.getX(), b.anchor().getZ() - c.getZ(), h[0] + 2, h[1] + 2);
        }
        for (Map.Entry<String, Villages.Site> e : Villages.sitesOf(id).entrySet()) {
            BlockPos a = e.getValue().anchor();
            if (e.getKey().equals("fortify")) {
                ring(s, a.getX() - c.getX(), a.getZ() - c.getZ(), e.getValue().radius(), 3);
                continue;
            }
            int[] h = half(e.getKey(), e.getValue().facing());
            box(s, a.getX() - c.getX(), a.getZ() - c.getZ(), h[0] + 3, h[1] + 3);
        }
        for (AssistantEntity a : Villages.folkOf(id)) {
            WorkZone z = a.workZone();
            if (z == null || !(a instanceof VillageFolkEntity f) || !VillageFolkEntity.producer(f.stationTask())) continue;
            int x0 = z.min().getX() - c.getX() - 1, x1 = z.max().getX() - c.getX() + 1;
            int z0 = z.min().getZ() - c.getZ() - 1, z1 = z.max().getZ() - c.getZ() + 1;
            for (int x = x0; x <= x1; x++) for (int zz = z0; zz <= z1; zz++) keep(s, x, zz);
        }
        for (TownPlan.Lot l : TownPlan.squareSpots()) box(s, l.x(), l.z(), l.halfAcross() + 1, l.halfDeep() + 1);
        BlockPos board = VillageBoards.boardOf(id);
        if (board != null) box(s, board.getX() - c.getX(), board.getZ() - c.getZ(), 2, 2);
        box(s, 0, 0, 1, 1);                                        // the heart: the founders' chest, the spawner
        s.housed = Villages.bedsMadeUp(level, id) >= Villages.headcount(id);
        s.storehouse = Storehouses.stands(id);
    }

    /** Half the width and depth of a building's lot as it stands (its drawing's whole, porches and eaves). */
    static int[] half(String structure, Direction facing) {
        int[] h;
        try {
            h = Blueprints.has(structure) ? Blueprints.fullHalf(structure) : BuildGoal.footprint(structure);
        } catch (RuntimeException e) {
            h = new int[]{ 6, 6 };
        }
        return facing.getAxis() == Direction.Axis.X ? new int[]{ h[1], h[0] } : new int[]{ h[0], h[1] };
    }

    private static void box(Survey s, int cx, int cz, int hx, int hz) {
        for (int x = cx - hx; x <= cx + hx; x++) for (int z = cz - hz; z <= cz + hz; z++) keep(s, x, z);
    }

    private static void ring(Survey s, int cx, int cz, int r, int w) {
        for (int x = cx - r - w; x <= cx + r + w; x++) {
            for (int z = cz - r - w; z <= cz + r + w; z++) {
                int d = Math.max(Math.abs(x - cx), Math.abs(z - cz));
                if (Math.abs(d - r) <= w) keep(s, x, z);
            }
        }
    }

    private static void keep(Survey s, int dx, int dz) {
        if (Math.abs(dx) > s.reach || Math.abs(dz) > s.reach) return;
        s.keptOff[(dz + s.reach) * s.side + dx + s.reach] = true;
    }

    private static boolean keptOff(Survey s, int dx, int dz) {
        if (Math.abs(dx) > s.reach || Math.abs(dz) > s.reach) return true;
        return s.keptOff[(dz + s.reach) * s.side + dx + s.reach];
    }

    // ------------------------------------------------------------------ one column

    /** The ground's top at a column: the highest block that stops a body, leaves left out. */
    static int top(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static void column(ServerLevel level, Villages.Village v, Survey s, int dx, int dz, Walk w) {
        BlockPos c = v.centre();
        int x = c.getX() + dx, z = c.getZ() + dz;
        if (!level.hasChunk(x >> 4, z >> 4)) return;
        boolean kept = keptOff(s, dx, dz);
        int y = top(level, x, z);
        if (Math.abs(y - c.getY()) > 16) return;                  // up a mountain or down a ravine: not the town's ground
        BlockPos at = new BlockPos(x, y, z);
        BlockState st = level.getBlockState(at);
        BlockState above = level.getBlockState(at.above());
        boolean street = TownPlan.isStreet(dx, dz) || TownPlan.isSquare(dx, dz);
        // The looks: flowers anywhere; the open ground green or bare; the streets lit or dark.
        if (above.is(BlockTags.SMALL_FLOWERS) || above.is(BlockTags.FLOWER_POTS) || above.is(Blocks.FLOWERING_AZALEA)) w.flowers++;
        if (!kept && !street) {
            if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.MOSS_BLOCK) || st.is(BlockTags.LEAVES)) w.green++;
            else if (bare(st)) w.bare++;
        }
        if (street && !kept && !Villages.onFarmland(v.id(), dx, dz, 0, 0)) {
            w.streets++;
            if (level.getBrightness(LightLayer.BLOCK, at.above()) >= 8) w.lit++;
            else if (w.dark.size() < 64 && (dx * 7 + dz * 3) % 5 == 0) w.dark.add(at.above().immutable());
            for (int up = 1; up <= 3 && w.overhang.size() < 64; up++) {
                if (level.getBlockState(at.above(up)).getBlock() instanceof LeavesBlock && !PlayerBlocks.placed(level, at.above(up))) {
                    w.overhang.add(at.above(up).immutable());
                }
            }
        } else if (!kept && w.verges.size() < 128 && st.is(Blocks.GRASS_BLOCK) && above.isAir()
                && (TownPlan.isStreet(dx + 1, dz) || TownPlan.isStreet(dx - 1, dz) || TownPlan.isStreet(dx, dz + 1) || TownPlan.isStreet(dx, dz - 1))
                && !Villages.onFarmland(v.id(), dx, dz, 0, 0)) {
            w.verges.add(at.above().immutable());
        }
        // The camp's beds once everybody has a bed in a house (looked for at their heads).
        if (s.housed && Math.max(Math.abs(dx), Math.abs(dz)) <= com.jrpetty.mcassistant.VillageSpawner.CAMP_REACH) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos p = at.above(dy);
                BlockState b = level.getBlockState(p);
                if (b.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                        && b.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                        && !PlayerBlocks.placed(level, p)) {
                    w.add(new Spot(Kind.CAMP_BED, p.immutable(), "the founders' camp, everybody housed"));
                }
            }
        }
        if (kept || Villages.onFarmland(v.id(), dx, dz, 0, 0) || TownMine.inMine(v.id(), at)) return;
        Spot spot = classify(level, s.storehouse, at, st, above, street);
        if (spot != null) w.add(spot);
    }

    /** Is this column's top an eyesore, and which? (The ground kept off is not asked.) */
    @Nullable
    private static Spot classify(ServerLevel level, boolean storehouse, BlockPos at, BlockState st, BlockState above, boolean street) {
        int x = at.getX(), y = at.getY(), z = at.getZ();
        // Leaves hanging with no trunk: the top counting leaves is above the top that does not.
        int withLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        if (withLeaves > y) {
            BlockPos lp = new BlockPos(x, withLeaves, z);
            BlockState l = level.getBlockState(lp);
            if (l.getBlock() instanceof LeavesBlock && !l.getValue(LeavesBlock.PERSISTENT) && l.getValue(LeavesBlock.DISTANCE) >= 7) {
                return new Spot(Kind.LEAVES, lp, "no trunk within reach");
            }
        }
        if (PlayerBlocks.placed(level, at)) return null;
        if (!st.getFluidState().isEmpty()) {
            if (st.getFluidState().is(FluidTags.LAVA)) return new Spot(Kind.SPILL, at, "lava");
            if (st.getFluidState().isSource() && st.is(Blocks.WATER) && !level.getFluidState(at.below()).is(FluidTags.WATER)) {
                int wet = 0;
                for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(at.relative(d)).is(FluidTags.WATER)) wet++;
                if (wet <= 1) return new Spot(Kind.SPILL, at, "a puddle");
            }
            return null;
        }
        if (st.is(Blocks.SCAFFOLDING)) return new Spot(Kind.SCAFFOLD, at, "scaffolding");
        if (st.is(Blocks.CHEST) || st.is(Blocks.BARREL)) {
            if (storehouse && Retiring.retirable(level, at, level.getBlockEntity(at))) return new Spot(Kind.CHEST, at, "left out in the open");
            return null;
        }
        int[] n4 = new int[4];
        int i = 0, max4 = Integer.MIN_VALUE, min4 = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            n4[i] = top(level, x + d.getStepX(), z + d.getStepZ());
            max4 = Math.max(max4, n4[i]);
            min4 = Math.min(min4, n4[i]);
            i++;
        }
        // A trunk or a stump on its own with no crown.
        if (st.is(BlockTags.LOGS) && st.hasProperty(RotatedPillarBlock.AXIS) && st.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y
                && !st.is(BlockTags.CRIMSON_STEMS) && !st.is(BlockTags.WARPED_STEMS)) {
            int base = y;
            while (base > y - 12 && level.getBlockState(new BlockPos(x, base - 1, z)).is(BlockTags.LOGS)) base--;
            BlockState ground = level.getBlockState(new BlockPos(x, base - 1, z));
            if (!bare(ground) && !ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.PODZOL)) return null;
            for (int h = base; h <= y; h++) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockState side = level.getBlockState(new BlockPos(x + d.getStepX(), h, z + d.getStepZ()));
                    if (!side.isAir() && !side.canBeReplaced() && !(side.getBlock() instanceof LeavesBlock)) return null;   // part of something built
                }
            }
            for (BlockPos p : BlockPos.betweenClosed(x - 2, y - 1, z - 2, x + 2, y + 3, z + 2)) {
                if (level.getBlockState(p).getBlock() instanceof LeavesBlock) return null;     // a tree with its crown
            }
            return new Spot(Kind.HALF_TREE, at, (y - base + 1) == 1 ? "a stump" : "a trunk " + (y - base + 1) + " high, no crown");
        }
        // A torch dropped on the ground (not on a post).
        if (above.is(Blocks.TORCH) && st.isFaceSturdy(level, at, Direction.UP) && !st.is(BlockTags.FENCES) && !st.is(BlockTags.WALLS)
                && !PlayerBlocks.placed(level, at.above())) {
            return new Spot(Kind.TORCH, at.above(), street ? "on the street" : "on the ground");
        }
        if (rough(st) || st.is(BlockTags.PLANKS)) {
            boolean floating = level.getBlockState(at.below()).canBeReplaced();
            if (y - max4 >= 2) {
                int h = y;
                while (h > max4 && (rough(level.getBlockState(new BlockPos(x, h, z))) || level.getBlockState(new BlockPos(x, h, z)).is(BlockTags.PLANKS))) h--;
                if (h <= max4) return new Spot(Kind.PILLAR, at, (y - max4) + " high, of " + name(st));
            }
            if (floating && y - max4 >= 1) return new Spot(Kind.STRAY, at, name(st) + ", hanging in the air");
            if (y - max4 == 1 && rough(st) && !st.is(Blocks.GRASS_BLOCK)) {
                int below = 0;
                for (int k = 0; k < 4; k++) if (n4[k] == y - 1) below++;
                if (below == 4) return new Spot(Kind.STRAY, at, name(st) + " on the ground");
            }
        }
        // A hole: most of the ground round it two and more higher.
        int deep = 0;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                if (top(level, x + ox, z + oz) >= y + 2) deep++;
            }
        }
        if (deep >= 5 && min4 >= y + 1) return new Spot(Kind.HOLE, at, (min4 - y) + " deep");
        if (street && min4 >= y + 1) return new Spot(Kind.ROAD, at, "a pothole");
        // A scar: bare earth or rock in the turf.
        if (!street && bare(st) && (above.isAir() || above.canBeReplaced() && above.getFluidState().isEmpty())) {
            int grass = 0;
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if ((ox != 0 || oz != 0) && level.getBlockState(new BlockPos(x + ox, top(level, x + ox, z + oz), z + oz)).is(Blocks.GRASS_BLOCK)) grass++;
                }
            }
            if (grass >= 3) return new Spot(Kind.SCAR, at, name(st) + " in the turf");
        }
        return null;
    }

    /** Earth and rock as a cut or a folk's tower leaves it: what a pillar or a stray block is made of. */
    private static final java.util.Set<net.minecraft.world.level.block.Block> ROUGH = java.util.Set.of(
        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND,
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK,
        Blocks.STONE, Blocks.DEEPSLATE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF);
    /** Ground with no turf on it: bare earth, or rock cut open. */
    private static final java.util.Set<net.minecraft.world.level.block.Block> BARE = java.util.Set.of(
        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
        Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DEEPSLATE, Blocks.TUFF);

    /** Earth and rock as a cut or a folk's tower leaves it: what a pillar or a stray block is made of. */
    public static boolean rough(BlockState st) {
        return ROUGH.contains(st.getBlock());
    }

    /** Ground with no turf on it: bare earth, or rock cut open. */
    public static boolean bare(BlockState st) {
        return BARE.contains(st.getBlock());
    }

    /** A block's plain name for the notes ("cobblestone", "coarse dirt"). */
    private static String name(BlockState st) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().replace('_', ' ');
    }

    /**
     * Is this spot still the eyesore it was found to be (the crew checks before it puts it right: somebody may have
     * seen to it, or built there since)? Asked of the column afresh.
     */
    public static boolean still(ServerLevel level, Villages.Village v, Spot spot) {
        BlockPos p = spot.pos();
        if (!level.isLoaded(p)) return false;
        if (spot.kind() == Kind.CAMP_BED) {
            BlockState b = level.getBlockState(p);
            return b.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
        }
        Survey s = SURVEYS.get(v.id());
        if (s == null || s.keptOff == null) return false;
        BlockPos c = v.centre();
        int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
        if (keptOff(s, dx, dz)) return false;
        int y = top(level, p.getX(), p.getZ());
        BlockPos at = new BlockPos(p.getX(), y, p.getZ());
        Spot now = classify(level, s.storehouse, at, level.getBlockState(at), level.getBlockState(at.above()),
            TownPlan.isStreet(dx, dz) || TownPlan.isSquare(dx, dz));
        return now != null && now.kind() == spot.kind();
    }

    /** What most likely left this eyesore, as far as the ground round it tells (for the long tests' reports). */
    public static String cause(ServerLevel level, Spot s) {
        BlockPos p = s.pos();
        boolean tree = false;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -4, -3), p.offset(3, 6, 3))) {
            if (level.getBlockState(q).is(BlockTags.LOGS)) { tree = true; break; }
        }
        return switch (s.kind()) {
            case PILLAR -> tree ? "a woodcutter towered up to a tree's top (GatherGoal) and left its tower"
                : "a folk towered up (GatherGoal, EscapeGoal) or walled itself in for the night (NightShelterGoal) and left it";
            case STRAY -> s.note().contains("air") ? "a night shelter's roof, a tower's top or a bridge's end left hanging"
                : "a block set down and left (a folk's tower's foot, a builder's spare)";
            case HOLE -> "dug and left open (a miner's pit, a cut of the ground, a block taken for a tower)";
            case ROAD -> "a block of the street taken up and not put back";
            case SCAR -> "ground cut (the founding's terraforming, a builder's levelling) and never grassed over";
            case HALF_TREE -> "a woodcutter (or a builder clearing its lot) left the trunk standing";
            case LEAVES -> "a tree felled from under its crown";
            case TORCH -> "dropped by a folk working in the dark (torchIfDark) or lighting an area";
            case SPILL -> s.note().equals("lava") ? "lava: a bucket spilt, or the ground opened to a lava pool" : "a bucket spilt, or a spring left by a filled pond";
            case SCAFFOLD -> "scaffolding left from a build";
            case CAMP_BED -> "the founders' camp, never taken up once the houses were built";
            case CHEST -> "a chest of the old stores, left out once the storehouse stood";
        };
    }

    /** Is this column (an offset from the heart) the town's open ground, not kept off for a building, a field...? */
    public static boolean open(UUID village, int dx, int dz) {
        Survey s = SURVEYS.get(village);
        return s != null && s.keptOff != null && !keptOff(s, dx, dz);
    }

    /** Re-asks the ground kept off now (a building since put up, a worker's ground staked). */
    public static void refresh(ServerLevel level, Villages.Village v) {
        Survey s = SURVEYS.get(v.id());
        if (s != null) begin(level, v, s);
    }
}
