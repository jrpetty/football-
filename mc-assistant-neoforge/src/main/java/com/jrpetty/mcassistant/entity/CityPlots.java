package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [city9] How each household closes in its plot, so a street is not a row of fenced boxes.
 *
 * <p>A house's garden (Grow.garden) used to be the same for every house: a fence all round the plot and a gate in
 * front, so every street was a row of fenced boxes, and between two neighbours stood two fences side by side. Now each
 * plot is closed in its own way, settled once by where it stands (the same every time it is looked at, and different
 * from its neighbours' more often than not):
 * <ul>
 * <li><b>a fence</b> all round with a gate in front (about two plots in five);</li>
 * <li><b>a low wall</b> of the stores' stone (cobblestone, or stone bricks once the town dresses its stone), with a gap
 *     for the path and no gate (about one in four);</li>
 * <li><b>open</b>: no edge at all, the lawn running to the street, only the flowers before the house (the rest).</li>
 * </ul>
 * Never a second edge beside a neighbour's: where the next plot's fence or wall already stands along a side, that side is
 * left to it (the two gardens share it).
 */
public final class CityPlots {

    private CityPlots() {}

    public enum Edge { FENCE, WALL, OPEN }

    /** How this house's plot is closed in. */
    public static Edge edge(Ledger.Building b) {
        long h = b.anchor().asLong() * 0x9E3779B97F4A7C15L;
        int k = (int) Math.floorMod(h >>> 29, 20L);
        return k < 8 ? Edge.FENCE : k < 13 ? Edge.WALL : Edge.OPEN;
    }

    /** Is there already an edge (a neighbour's fence, wall or hedge) just outside this cell, the way out from the house? */
    public static boolean shared(ServerLevel level, BlockPos at, BlockPos anchor) {
        int ox = Integer.signum(at.getX() - anchor.getX()), oz = Integer.signum(at.getZ() - anchor.getZ());
        int ax = Math.abs(at.getX() - anchor.getX()), az = Math.abs(at.getZ() - anchor.getZ());
        BlockPos out = ax >= az ? at.offset(ox, 0, 0) : at.offset(0, 0, oz);
        BlockState st = level.getBlockState(out);
        return st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.is(BlockTags.LEAVES) || st.is(BlockTags.FENCE_GATES);
    }

    /** The wall block for this town's low walls, paid for out of the stores: stone bricks' wall from the Iron Age if the
     *  stores have the bricks, else cobblestone's. Null if they have neither. */
    public static Block wall(ServerLevel level, Villages.Village v) {
        if (Villages.ageOf(v.id()).ordinal() >= Villages.Age.IRON.ordinal()
                && Crafts.take(level, v, s -> s.is(Items.STONE_BRICKS) || s.is(Items.STONE_BRICK_WALL), 1)) return Blocks.STONE_BRICK_WALL;
        if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLESTONE_WALL), 1)) return Blocks.COBBLESTONE_WALL;
        return null;
    }
}
