package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import javax.annotation.Nullable;

/**
 * The game's walking evaluator, answering exactly as it does, only without reading the same blocks over and over within
 * one search (FolkNavigation).
 *
 * <p>A search asks what kind of ground each place it might step on is, and for every place of open ground over a floor it
 * looks at the twenty-six blocks round it for fire, a berry bush, water and the like ({@code checkNeighbourBlocks}): for
 * each, a word with the level's path-type cache, then the block read again and asked three or four questions of its tags.
 * The places a search weighs share nearly all their neighbours, so each block round a long walk was read and asked a dozen
 * times and more in one search; in a busy town that was a fifth of the server's tick. Here what a block makes of the
 * places beside it is worked out once a search and kept. The rest is the game's own steps, in its own order: every
 * question it puts to the level's path-type cache is put the same, so the cache (whose answers can depend on what was
 * asked of it before) comes out of each search as it would have. The world cannot change while a search runs (it is
 * done there and then, on the server thread), and nothing is kept from one search to the next. Outside a search (the
 * game asks its evaluator now and then about a single block) it is the game's own code, as ever.
 */
public class FolkNodeEvaluator extends WalkNodeEvaluator {

    /**
     * What each block makes of the places beside it, for the search under way: a table with a place for each of 32768
     * blocks, a block's place picked by where it is, the newest block to come to a place taking it over (a block put out
     * is only worked out again when next asked about). For each place: which block, in which search (a search's own
     * number, so nothing is ever left over from the last search and nothing need be cleared), and the answer: the
     * cache's path type for the block (ordinal plus one) in the high half, the block's and its fluid's own say
     * ({@code getAdjacentBlockPathType}; ordinal plus one, nought for none) in the low. One table for each thread.
     */
    private static final class Memo {
        static final int SIZE = 1 << 15, MASK = SIZE - 1;
        final long[] keys = new long[SIZE];
        final int[] searches = new int[SIZE];
        final int[] answers = new int[SIZE];
        int search;
    }

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);
    private static final PathType[] TYPES = PathType.values();

    /** The table of the search under way, while this evaluator's search is the one under way; null otherwise. */
    @Nullable private Memo memo;
    /** Which search of the table's is this evaluator's. */
    private int search;
    private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        Memo m = MEMO.get();
        if (++m.search == 0) {                         // once in four thousand million searches: start the count again
            java.util.Arrays.fill(m.searches, 0);
            m.search = 1;
        }
        search = m.search;
        memo = m;
    }

    @Override
    public void done() {
        memo = null;
        super.done();
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        Memo m = memo;
        // Only the search under way, and only through its own context: anything else is the game's own answer.
        if (m == null || m.search != search || context != this.currentContext) return super.getPathType(context, x, y, z);
        return pathTypeStatic(context, m, x, y, z);
    }

    /** WalkNodeEvaluator.getPathTypeStatic, step for step, its look round the place memoised. */
    private PathType pathTypeStatic(PathfindingContext context, Memo m, int x, int y, int z) {
        PathType here = context.getPathTypeFromState(x, y, z);
        if (here != PathType.OPEN || y < context.level().getMinBuildHeight() + 1) return here;
        return switch (context.getPathTypeFromState(x, y - 1, z)) {
            case OPEN, WATER, LAVA, WALKABLE -> PathType.OPEN;
            case DAMAGE_FIRE -> PathType.DAMAGE_FIRE;
            case DAMAGE_OTHER -> PathType.DAMAGE_OTHER;
            case STICKY_HONEY -> PathType.STICKY_HONEY;
            case POWDER_SNOW -> PathType.DANGER_POWDER_SNOW;
            case DAMAGE_CAUTIOUS -> PathType.DAMAGE_CAUTIOUS;
            case TRAPDOOR -> PathType.DANGER_TRAPDOOR;
            default -> neighbours(context, m, x, y, z);
        };
    }

    /** WalkNodeEvaluator.checkNeighbourBlocks (with WALKABLE): the first block round the place, in the game's own order,
     *  that makes something of it. */
    private PathType neighbours(PathfindingContext context, Memo m, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    PathType t = beside(context, m, x + dx, y + dy, z + dz);
                    if (t != null) return t;
                }
            }
        }
        return PathType.WALKABLE;
    }

    /** One turn of checkNeighbourBlocks' loop: what this block makes of the place beside it, or null for nothing. */
    @Nullable
    private PathType beside(PathfindingContext context, Memo m, int x, int y, int z) {
        // Asked of the cache every time, as the game asks it.
        PathType type = context.getPathTypeFromState(x, y, z);
        long key = BlockPos.asLong(x, y, z);
        int slot = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & Memo.MASK;
        int own;
        if (m.keys[slot] == key && m.searches[slot] == search && (m.answers[slot] >>> 16) == type.ordinal() + 1) {
            own = m.answers[slot] & 0xFFFF;
        } else {
            CollisionGetter level = context.level();
            BlockPos pos = at.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            PathType adj = state.getAdjacentBlockPathType(level, pos, null, type);
            if (adj == null) adj = state.getFluidState().getAdjacentBlockPathType(level, pos, null, type);
            own = adj == null ? 0 : adj.ordinal() + 1;
            m.keys[slot] = key;
            m.searches[slot] = search;
            m.answers[slot] = ((type.ordinal() + 1) << 16) | own;
        }
        if (own != 0) return TYPES[own - 1];
        if (type == PathType.DAMAGE_OTHER) return PathType.DANGER_OTHER;
        if (type == PathType.DAMAGE_FIRE || type == PathType.LAVA) return PathType.DANGER_FIRE;
        if (type == PathType.WATER) return PathType.WATER_BORDER;
        if (type == PathType.DAMAGE_CAUTIOUS) return PathType.DAMAGE_CAUTIOUS;
        return null;
    }
}
