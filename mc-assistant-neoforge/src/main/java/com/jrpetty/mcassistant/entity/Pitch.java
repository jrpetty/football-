package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The football pitch [batchC]: a level field of grass on a long lot among the homes, by the park if the
 * town has one, once the town is in stone and twenty strong.
 *
 * <p>The council puts it on the builders' list with the other amenities (Villages), the plan gives it a long
 * lot, the homes quarter's first and the nearest the park (Quarters), and the builders put up what is drawn
 * (blueprints/pitch.txt) out of the stores like anything else: two goals of fences, a bench of stairs down
 * each side, a lamp on a post at each corner. Then its keepers, a little at a time and out of the stores,
 * make the rest of it a pitch: the earth standing proud of the field cut away (what it gives into the
 * stores), a hollow filled with the stores' earth, the bare stone of the builder's fill turfed, and the lines
 * set into the grass, the touchlines, the goal lines and the halfway line, in white wool, or in birch planks
 * where the stores have no wool to spare (a town short of beds keeps its wool for them).
 *
 * <p>The field is nine across and fifteen long inside its lines. Everything here is reckoned across it
 * ({@code u}, the right of the lot looking from the street being +) and along it ({@code w}, toward the
 * back of the lot +), from the middle of the centre spot: the back goal is the one at {@code w = +7}.
 */
public final class Pitch {

    private Pitch() {}

    public static final String STRUCTURE = "pitch";
    /** The town's size when it wants a pitch. */
    public static final int FOLK = 20;
    /** Half the field inside its lines, across and along: the lines themselves are on these. */
    public static final int HALF_WIDE = 4, HALF_LONG = 7;
    /** The goal mouth: the ball is between the posts while it is this near the middle, and under the bar. */
    public static final double MOUTH = 1.6;
    public static final int BAR = 2;
    /** The town's works that keep it (TownJobs). */
    static final String WORKS = "pitch";

    private static final Map<UUID, Long> TENDED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TENDED.clear();
    }

    /** Does the village want a pitch now: twenty folk or more, in stone, and none yet. (Villages, with the amenities.) */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FOLK && Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal()
            && !Villages.hasBuilt(village, STRUCTURE) && of(village) == null;
    }

    /** Why the town builds it, for the books and the board. */
    public static String why(UUID village) {
        return "a football pitch among the homes, two goals and a bench down each side: a match on the day of rest, now the town has "
            + Villages.headcount(village) + " folk";
    }

    /** The village's pitch (the ledger's), or null. */
    @Nullable
    public static Ledger.Building of(@Nullable UUID village) {
        if (village == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) return b;
        return null;
    }

    /**
     * The plan's lots for the pitch, in its own quarter's order (Quarters), and within each the nearest the park
     * first: the town's green and its football field side by side. Without a park, as they were.
     */
    public static List<com.jrpetty.mcassistant.village.TownPlan.Lot> byThePark(UUID village, List<com.jrpetty.mcassistant.village.TownPlan.Lot> lots) {
        List<Ledger.Building> parks = Park.parks(village);
        Villages.Village v = Villages.get(village);
        if (parks.isEmpty() || v == null) return lots;
        int px = parks.get(0).anchor().getX() - v.centre().getX(), pz = parks.get(0).anchor().getZ() - v.centre().getZ();
        int farm = Quarters.farmSide(village), craft = Quarters.craftSide(village);
        List<com.jrpetty.mcassistant.village.TownPlan.Lot> out = new ArrayList<>(lots);
        out.sort(java.util.Comparator.comparingInt((com.jrpetty.mcassistant.village.TownPlan.Lot l) ->
                com.jrpetty.mcassistant.village.Districts.rank(l, com.jrpetty.mcassistant.village.Districts.District.HOMES, farm, craft))
            .thenComparingLong(l -> (long) (l.x() - px) * (l.x() - px) + (long) (l.z() - pz) * (l.z() - pz)));
        return out;
    }

    // ------------------------------------------------------------------ the field's own reckoning

    /** The block at so far across and along the field, on the level of its floor (the first free block over the grass). */
    public static BlockPos at(Ledger.Building b, int u, int w) {
        return b.anchor().relative(b.facing().getClockWise(), u).relative(b.facing(), w);
    }

    /** The point so far across and along the field, on its floor. */
    public static Vec3 point(Ledger.Building b, double u, double w) {
        Direction r = b.facing().getClockWise(), f = b.facing();
        return new Vec3(b.anchor().getX() + 0.5 + r.getStepX() * u + f.getStepX() * w, b.anchor().getY(),
            b.anchor().getZ() + 0.5 + r.getStepZ() * u + f.getStepZ() * w);
    }

    /** Where a point is on the field: {across, along}. */
    public static double[] local(Ledger.Building b, Vec3 p) {
        Direction r = b.facing().getClockWise(), f = b.facing();
        double x = p.x - (b.anchor().getX() + 0.5), z = p.z - (b.anchor().getZ() + 0.5);
        return new double[]{ x * r.getStepX() + z * r.getStepZ(), x * f.getStepX() + z * f.getStepZ() };
    }

    /** Is this point on the field, its lines and its goals (and a step round it)? */
    public static boolean onField(Ledger.Building b, Vec3 p) {
        double[] uw = local(b, p);
        return Math.abs(uw[0]) <= HALF_WIDE + 1.0 && Math.abs(uw[1]) <= HALF_LONG + 2.0 && Math.abs(p.y - b.anchor().getY()) <= 3;
    }

    /** The cells of the lines, in the turf (a block under the floor): the touchlines, the goal lines, the halfway line. */
    public static List<BlockPos> lines(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (int w = -HALF_LONG; w <= HALF_LONG; w++) {
            out.add(at(b, -HALF_WIDE, w).below());
            out.add(at(b, HALF_WIDE, w).below());
        }
        for (int u = -HALF_WIDE + 1; u <= HALF_WIDE - 1; u++) {
            out.add(at(b, u, -HALF_LONG).below());
            out.add(at(b, u, HALF_LONG).below());
            out.add(at(b, u, 0).below());
        }
        return out;
    }

    /** A line laid: white wool, or birch planks. */
    static boolean lineLaid(BlockState s) {
        return s.is(Blocks.WHITE_WOOL) || s.is(Blocks.BIRCH_PLANKS);
    }

    /** The cells of the field's turf, in rows: everything inside the lines and the goal mouths. */
    static List<BlockPos> turf(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (int w = -HALF_LONG - 1; w <= HALF_LONG + 1; w++) {
            int wide = Math.abs(w) > HALF_LONG ? 1 : HALF_WIDE;           // behind a goal line, only the goal's own floor
            for (int u = -wide; u <= wide; u++) out.add(at(b, u, w).below());
        }
        return out;
    }

    /** The builder's fill, or a rock: turf that is not earth, and wants earth over it. */
    static boolean bare(BlockState s) {
        return s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.MOSSY_COBBLESTONE)
            || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL);
    }

    // ------------------------------------------------------------------ keeping it

    /**
     * Every quarter of a minute (the town's clock in Sport): a little more done to the village's pitch, by a
     * hand of the village (TownJobs), out of the stores: the earth standing proud of the field cut away, a
     * hollow filled, the bare stone turfed, the lines laid. One kind of thing a visit.
     */
    public static void tend(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        if (now - TENDED.getOrDefault(v.id(), -100000L) < 300L) return;
        TENDED.put(v.id(), now);
        Ledger.Building b = of(v.id());
        if (b == null || !Land.areaLoaded(level, b.anchor(), 12)) return;
        if (Football.on(v.id())) return;                                  // not while a match is on it
        tendOne(level, v, b, 8);
    }

    /** One visit's work on the pitch, up to so many blocks of one kind. What was done, or null. */
    @Nullable
    public static String tendOne(ServerLevel level, Villages.Village v, Ledger.Building b, int budget) {
        int floor = b.anchor().getY();
        // The earth standing proud of the field: cut away, from the top down, into the stores.
        List<BlockPos> proud = new ArrayList<>();
        for (BlockPos t : turf(b)) {
            for (int y = floor + 2; y >= floor; y--) {
                BlockPos p = new BlockPos(t.getX(), y, t.getZ());
                BlockState s = level.getBlockState(p);
                if (ParkGround.cuts(s) && !s.is(Blocks.BEDROCK)) proud.add(p);
            }
        }
        if (!proud.isEmpty()) {
            if (!TownJobs.atWork(level, v, WORKS, proud.get(0), "levelling the football pitch")) return null;
            int n = 0;
            for (BlockPos p : proud) {
                if (n >= budget) break;
                BlockState s = level.getBlockState(p);
                if (!ParkGround.cuts(s)) continue;
                for (ItemStack d : Block.getDrops(s, level, p, null)) Crafts.store(level, v, d);
                level.removeBlock(p, false);
                n++;
            }
            level.playSound(null, proud.get(0), SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
            return "levelled the pitch";
        }
        // A hollow in the turf: filled with the stores' earth, from the bottom up.
        List<BlockPos> hollow = new ArrayList<>();
        for (BlockPos t : turf(b)) {
            if (!level.getBlockState(t).canBeReplaced()) continue;
            int low = t.getY();
            while (low > t.getY() - 2 && level.getBlockState(new BlockPos(t.getX(), low - 1, t.getZ())).canBeReplaced()) low--;
            for (int y = low; y <= t.getY(); y++) hollow.add(new BlockPos(t.getX(), y, t.getZ()));
        }
        if (!hollow.isEmpty() && Market.stock(level, v.id(), st -> st.is(Items.DIRT)) > 0) {
            if (!TownJobs.atWork(level, v, WORKS, hollow.get(0), "filling in the football pitch")) return null;
            int n = 0;
            for (BlockPos p : hollow) {
                if (n >= budget || !Crafts.take(level, v, st -> st.is(Items.DIRT), 1)) break;
                level.setBlock(p, Blocks.DIRT.defaultBlockState(), 3);
                n++;
            }
            if (n > 0) return "filled in the pitch";
        }
        // The builder's stone fill, or a rock, in the turf: earth over it, the stone into the stores.
        java.util.Set<BlockPos> lineCells = new java.util.HashSet<>(lines(b));
        List<BlockPos> bare = new ArrayList<>();
        for (BlockPos t : turf(b)) {
            if (lineCells.contains(t)) continue;
            BlockState s = level.getBlockState(t);
            if (bare(s) && level.getBlockState(t.above()).isAir()) bare.add(t);
        }
        if (!bare.isEmpty() && Market.stock(level, v.id(), st -> st.is(Items.DIRT)) > 0) {
            if (!TownJobs.atWork(level, v, WORKS, bare.get(0).above(), "turfing the football pitch")) return null;
            int n = 0;
            for (BlockPos p : bare) {
                if (n >= budget || !Crafts.take(level, v, st -> st.is(Items.DIRT), 1)) break;
                BlockState was = level.getBlockState(p);
                for (ItemStack d : Block.getDrops(was, level, p, null)) Crafts.store(level, v, d);
                level.setBlock(p, Blocks.DIRT.defaultBlockState(), 3);
                n++;
            }
            if (n > 0) return "turfed the pitch";
        }
        // The lines: white wool (a town short of beds keeps its wool for them), else birch planks; the turf
        // they go into is the stores' earth.
        List<BlockPos> unmarked = new ArrayList<>();
        for (BlockPos p : lines(b)) {
            BlockState s = level.getBlockState(p);
            if (lineLaid(s)) continue;
            // (Under a goal post too: the post stands on the line, as it should.)
            if (s.is(BlockTags.DIRT) || bare(s)) unmarked.add(p);
        }
        if (unmarked.isEmpty()) return null;
        boolean wool = Market.bedsShort(v.id()) == 0 && Market.stock(level, v.id(), st -> st.is(Items.WHITE_WOOL)) > 0;
        boolean birch = Market.stock(level, v.id(), st -> st.is(Items.BIRCH_PLANKS)) > 0;
        if (!wool && !birch) return null;
        if (!TownJobs.atWork(level, v, WORKS, unmarked.get(0).above(), "marking out the football pitch")) return null;
        int n = 0;
        for (BlockPos p : unmarked) {
            if (n >= budget) break;
            BlockState line;
            if (wool && Crafts.take(level, v, st -> st.is(Items.WHITE_WOOL), 1)) line = Blocks.WHITE_WOOL.defaultBlockState();
            else if (Crafts.take(level, v, st -> st.is(Items.BIRCH_PLANKS), 1)) line = Blocks.BIRCH_PLANKS.defaultBlockState();
            else break;
            BlockState was = level.getBlockState(p);
            Crafts.store(level, v, new ItemStack(was.is(BlockTags.DIRT) ? Items.DIRT : was.getBlock().asItem()));
            level.setBlock(p, line, 3);
            n++;
        }
        if (n > 0) {
            level.playSound(null, unmarked.get(0), SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            return "marked out the pitch";
        }
        return null;
    }

    /** How the pitch is coming on, in a line (the books, /village sport). */
    public static String status(ServerLevel level, UUID village) {
        Ledger.Building b = of(village);
        if (b == null) {
            return Villages.projectsWanted(village).contains(STRUCTURE) ? "The football pitch is on the builders' list."
                : "No football pitch yet (a town in stone of " + FOLK + " folk builds one).";
        }
        if (!level.isLoaded(b.anchor())) return "The football pitch: out of sight just now.";
        int laid = 0, all = 0;
        for (BlockPos p : lines(b)) {
            all++;
            if (lineLaid(level.getBlockState(p))) laid++;
        }
        int wool = 0;
        for (BlockPos p : lines(b)) if (level.getBlockState(p).is(Blocks.WHITE_WOOL)) wool++;
        return "The football pitch, " + Quarters.districtOf(village, Villages.get(village) == null ? b.anchor() : Villages.get(village).centre(), b).words
            + ": its lines " + (laid == all ? "all laid" : laid + " of " + all + " laid") + (laid > 0 ? " (" + wool + " in white wool)" : "")
            + (laid == all ? "." : "; the stores' white wool or birch planks go into the rest.");
    }

    // ------------------------------------------------------------------ at once, for the photographs and the tests

    /**
     * A pitch put up at once on its lot, as the showcase puts a building up (the photographs, the tests): its
     * ground made level and grassed, the drawing stamped, and booked as the village's. The lines are its
     * keepers' to lay ({@link #tendOne}), or {@link #linesNow} for a photograph.
     */
    public static Ledger.Building putUp(ServerLevel level, UUID village, BlockPos anchor, Direction facing) {
        Direction r = facing.getClockWise();
        for (int u = -6; u <= 6; u++) {
            for (int w = -11; w <= 11; w++) {
                BlockPos c = anchor.relative(r, u).relative(facing, w);
                for (int y = 0; y <= 8; y++) {
                    BlockPos p = c.above(y);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
                level.setBlock(c.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
                for (int y = 2; y <= 4; y++) {
                    BlockPos p = c.below(y);
                    if (!level.getBlockState(p).isSolid()) level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
            }
        }
        BuildGoal.stamp(level, STRUCTURE, anchor, facing, 0, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.Building have = of(village);
        if (have == null) {
            Ledger.built(village, STRUCTURE, anchor, facing);
            have = of(village);
        }
        return have;
    }

    /** The lines laid at once in white wool, for nothing (the photographs only: /village sport pitch now). */
    public static void linesNow(ServerLevel level, Ledger.Building b) {
        for (BlockPos p : lines(b)) level.setBlock(p, Blocks.WHITE_WOOL.defaultBlockState(), 2 | 16);
    }
}
