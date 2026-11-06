package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [disasters] The operators' end of fire, flood and drought (/village disasters ...): each brought on now in the
 * nearest town, and scenes set for the pictures (the smoke test's disasters_stage). A scene says where to look
 * from in lines "VIEW name x y z ax ay az" (the eyes' feet, and what they look at).
 */
public final class DisasterStage {

    private DisasterStage() {}

    // ------------------------------------------------------------------ brought on now

    /** A spark now from one of the town's lit forges with something that burns beside it. */
    public static String fireNow(ServerLevel level, Villages.Village v) {
        BlockPos at = FireSafety.spark(level, v, Disasters.town(v.id()), true);
        if (at == null) return "No spark: no lit forge in " + Villages.name(v.id()) + " has anything that burns beside it (or the fire watch was there).";
        FireBrigade.watch(level, v, true);
        return "SPARK " + at.getX() + " " + at.getY() + " " + at.getZ() + ": the fire bell rings in " + Villages.name(v.id()) + ".";
    }

    /** The river up now (rise 1 or 2), filled at once. */
    public static String floodNow(ServerLevel level, Villages.Village v, int rise) {
        Disasters.Town t = Disasters.town(v.id());
        if (t.flood != null) return Disasters.capital(t.flood.river) + " is in flood already: " + t.flood.cells.size() + " cells.";
        int n = Floods.rise(level, v, t, rise);
        if (n == 0) return "No flood: " + Villages.name(v.id()) + " has no river beside it, or no low ground by its river.";
        for (int i = 0; i < 400 && t.flood != null && !t.flood.risen; i++) Floods.step(level, v, t);
        return "FLOOD " + (t.flood == null ? 0 : t.flood.cells.size()) + " cells under water, river at y " + (t.flood == null ? 0 : t.flood.w)
            + ", " + (t.flood == null ? 0 : t.flood.homes.size()) + " houses flooded.";
    }

    /** The flood down now, all of it. */
    public static String drainNow(ServerLevel level, Villages.Village v) {
        Disasters.Town t = Disasters.town(v.id());
        if (t.flood == null) return "No flood in " + Villages.name(v.id()) + ".";
        int n = t.flood.cells.size();
        Floods.drain(level, v, t, "an operator's word");
        for (int i = 0; i < 400 && t.flood != null; i++) Floods.step(level, v, t);
        return "DRAINED " + n + " cells; " + t.levee.size() + " blocks of levee planned.";
    }

    /** The levee raised now, as far as the stores pay (the town's works at once). */
    public static String leveeNow(ServerLevel level, Villages.Village v) {
        Disasters.Town t = Disasters.town(v.id());
        if (t.levee.isEmpty()) return "No levee to raise in " + Villages.name(v.id()) + ": it needs a flood first.";
        BlockPos first = BlockPos.of(t.levee.get(0));
        boolean was = TownJobs.instantNow();
        TownJobs.instantForTests(true);
        int n = 0;
        try {
            for (int i = 0; i < 200 && !t.levee.isEmpty(); i++) {
                int k = Floods.levee(level, v, t, 8);
                if (k == 0) break;
                n += k;
            }
        } finally {
            TownJobs.instantForTests(was);
        }
        return "LEVEE " + n + " blocks raised at " + first.getX() + " " + first.getY() + " " + first.getZ()
            + (t.levee.isEmpty() ? ", finished." : ", " + t.levee.size() + " to go (waiting for " + t.leveeWaits + ").");
    }

    /** A drought now (true) or broken (false). */
    public static String droughtNow(ServerLevel level, Villages.Village v, boolean on) {
        Droughts.droughtForTests(level, v.id(), on);
        Disasters.Town t = Disasters.town(v.id());
        return on ? "DROUGHT in " + Villages.name(v.id()) + ": " + t.dryFields.size() + " dry fields."
            : "The drought in " + Villages.name(v.id()) + " is broken.";
    }

    /** The irrigation dug now, as far as the stores' buckets and the water allow. */
    public static String irrigateNow(ServerLevel level, Villages.Village v) {
        Disasters.Town t = Disasters.town(v.id());
        if (t.dryFields.isEmpty()) return "No dry fields to irrigate in " + Villages.name(v.id()) + ".";
        BlockPos first = BlockPos.of(t.dryFields.get(0)[0]);
        boolean was = TownJobs.instantNow();
        TownJobs.instantForTests(true);
        int n = 0;
        try {
            for (int i = 0; i < 60 && !t.dryFields.isEmpty(); i++) n += Droughts.irrigate(level, v, t, 8);
        } finally {
            TownJobs.instantForTests(was);
        }
        return "IRRIGATION " + n + " blocks of channel dug at " + first.getX() + " " + first.getY() + " " + first.getZ() + ".";
    }

    // ------------------------------------------------------------------ scenes for the pictures

    /** The ground's top at a column (the block stood on). */
    private static int top(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /** A level patch of grass at height y (its top), cleared above for so many blocks. */
    private static void flat(ServerLevel level, int x0, int z0, int x1, int z1, int y, int clear) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = 1; dy <= clear; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                for (int dy = -3; dy < 0; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
    }

    /**
     * A fire for the pictures: a timber house of the town's set on clear ground here with a pond fourteen blocks
     * off, its front wall alight in eight places; the brigade called at once, and its bucket chain from the pond.
     */
    public static List<String> stageFire(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int y = top(level, at.getX(), at.getZ());
        flat(level, at.getX() - 8, at.getZ() - 20, at.getX() + 8, at.getZ() + 8, y, 12);
        BlockPos anchor = new BlockPos(at.getX(), y + 1, at.getZ());
        BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(v.id(), "house", anchor, Direction.NORTH);
        // The pond, out behind the house (to the north), three across.
        BlockPos pond = new BlockPos(at.getX(), y, at.getZ() - 14);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(pond.offset(dx, 0, dz), Blocks.WATER.defaultBlockState(), 3);
        // The back wall alight: the empty cells against it, at the ground.
        int lit = 0;
        for (int dx = -4; dx <= 4 && lit < 8; dx++) {
            for (int dy = 0; dy <= 1 && lit < 8; dy++) {
                BlockPos f = anchor.offset(dx, dy, -4);
                if (!level.getBlockState(f).isAir()) continue;
                level.setBlock(f, BaseFireBlock.getState(level, f), 3);
                lit++;
            }
        }
        FireBrigade.watch(level, v, true);
        int[] chain = BucketChain.nowForTests(v.id());
        out.add("fire: " + lit + " alight at the back of the house at " + anchor.toShortString() + "; chain " + (chain == null ? "none" : chain[0] + " links"));
        List<BlockPos> spots = BucketChain.spotsForTests(v.id());
        BlockPos mid = spots.isEmpty() ? anchor.offset(0, 0, -9) : spots.get(spots.size() / 2);
        // From the side of the line, a little up, looking along it at the middle link.
        out.add("VIEW disasters-1-bucket-chain " + (mid.getX() + 9) + " " + (mid.getY() + 4) + " " + (mid.getZ() + 2) + " "
            + mid.getX() + " " + (mid.getY() + 1) + " " + mid.getZ());
        return out;
    }

    /**
     * A flood for the pictures: a channel of water forty blocks long dug here (the town's river, if it has none of
     * its own nearer), a low street of beaten earth along its bank and a house of the town's on it; then the river
     * brought up over it at once.
     */
    public static List<String> stageFlood(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int g = top(level, at.getX(), at.getZ());
        // The bank: level ground, its top at the river's own level (the low ground).
        flat(level, at.getX() - 20, at.getZ() - 4, at.getX() + 20, at.getZ() + 16, g, 14);
        // The river: five wide, two deep, its surface at the ground's top (W), so the bank is a block above it.
        for (int x = at.getX() - 20; x <= at.getX() + 20; x++) {
            for (int z = at.getZ() - 4; z <= at.getZ(); z++) {
                level.setBlock(new BlockPos(x, g - 2, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, g - 1, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, g, z), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        // The far side raised two blocks (a high bank), so the water's low ground is this side.
        for (int x = at.getX() - 20; x <= at.getX() + 20; x++) {
            for (int dy = 1; dy <= 2; dy++) level.setBlock(new BlockPos(x, g + dy, at.getZ() - 5), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        // The low street along the bank, and the ground beyond it rising (a step up at the street's far side).
        for (int x = at.getX() - 20; x <= at.getX() + 20; x++) {
            for (int z = at.getZ() + 1; z <= at.getZ() + 3; z++) level.setBlock(new BlockPos(x, g, z), Blocks.DIRT_PATH.defaultBlockState(), 2);
            for (int z = at.getZ() + 12; z <= at.getZ() + 16; z++) level.setBlock(new BlockPos(x, g + 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        // A house of the town's on the low ground, its door on the street (its back to the rising ground).
        BlockPos anchor = new BlockPos(at.getX(), g + 1, at.getZ() + 8);
        BuildGoal.stamp(level, "house", anchor, Direction.SOUTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(v.id(), "house", anchor, Direction.SOUTH);
        Floods.stage(v.id(), at.getX() - 20, at.getZ() - 5, at.getX() + 20, at.getZ() + 16, g);
        Disasters.Town t = Disasters.town(v.id());
        if (t.flood != null) {
            Floods.drain(level, v, t, "a new scene");
            for (int i = 0; i < 400 && t.flood != null; i++) Floods.step(level, v, t);
        }
        t.levee.clear();
        String said = floodNow(level, v, 1);
        out.add("flood: " + said);
        out.add("VIEW disasters-2-flooded-street " + (at.getX() + 14) + " " + (g + 7) + " " + (at.getZ() + 16) + " "
            + at.getX() + " " + (g + 1) + " " + (at.getZ() + 3));
        return out;
    }

    /** The levee for the pictures: the staged flood taken down and the levee raised at once along its bank. */
    public static List<String> stageLevee(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        out.add("drain: " + drainNow(level, v));
        String said = leveeNow(level, v);
        out.add("levee: " + said);
        int g = top(level, at.getX(), at.getZ() + 2);
        out.add("VIEW disasters-3-levee " + (at.getX() + 10) + " " + (g + 5) + " " + (at.getZ() + 9) + " "
            + at.getX() + " " + (g + 1) + " " + (at.getZ() + 1));
        return out;
    }

    /**
     * Irrigation for the pictures: a dry field of the town's (nine across, ripe wheat, no water near) set out here,
     * a drought brought on, and the town's channels dug through it at once from the water fourteen blocks off.
     */
    public static List<String> stageIrrigation(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int y = top(level, at.getX(), at.getZ());
        flat(level, at.getX() - 6, at.getZ() - 6, at.getX() + 6, at.getZ() + 20, y, 6);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos p = new BlockPos(at.getX() + dx, y, at.getZ() + dz);
                level.setBlock(p, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0), 2);
                level.setBlock(p.above(), ((CropBlock) Blocks.WHEAT).getStateForAge(3), 2);
            }
        }
        BlockPos pond = new BlockPos(at.getX(), y, at.getZ() + 14);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(pond.offset(dx, 0, dz), Blocks.WATER.defaultBlockState(), 3);
        UUID id = v.id();
        Droughts.fieldForTests(id, new BlockPos(at.getX(), y, at.getZ()), 4);
        out.add("drought: " + droughtNow(level, v, true));
        if (Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.BUCKET) || s.is(net.minecraft.world.item.Items.WATER_BUCKET)) == 0) {
            out.add("the stores have no bucket: none to carry the water in");
        }
        out.add("irrigate: " + irrigateNow(level, v));
        out.add("VIEW disasters-4-irrigation " + (at.getX() + 8) + " " + (y + 7) + " " + (at.getZ() - 8) + " "
            + at.getX() + " " + y + " " + at.getZ());
        return out;
    }
}
