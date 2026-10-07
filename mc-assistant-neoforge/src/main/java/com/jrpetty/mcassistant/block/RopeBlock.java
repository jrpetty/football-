package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [workitems] A hanging rope: let down off the top edge of a drop from a rope coil (item/RopeCoilItem), as far as
 * twenty-four blocks, and climbed like a ladder. Its top piece is hitched over the lip and staked; each piece hangs
 * from the one above; the last has a stopper knot. Break any piece and the whole rope comes up again as the coil.
 */
public class RopeBlock extends Block {

    /** The wall it hangs down beside (the edge it was let down from is that way at its top). */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    /** How far a coil lets down. */
    public static final int LONGEST = 24;

    public enum Part implements StringRepresentable {
        TOP("top"), MIDDLE("middle"), BOTTOM("bottom");

        private final String name;

        Part(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }
    }

    private static final VoxelShape N = box(5, 0, 1, 11, 16, 6), S = box(5, 0, 10, 11, 16, 15),
        E = box(10, 0, 5, 15, 16, 11), W = box(1, 0, 5, 6, 16, 11);

    public RopeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.MIDDLE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> S;
            case EAST -> E;
            case WEST -> W;
            default -> N;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return true;
    }

    /** Climbed as a ladder is. */
    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) {
        return true;
    }

    /** The top piece is hitched to the edge it hangs from; every other hangs from the piece above it. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(PART) == Part.TOP) {
            BlockPos edge = pos.relative(state.getValue(FACING));
            return !level.getBlockState(edge).getCollisionShape(level, edge).isEmpty();
        }
        BlockState above = level.getBlockState(pos.above());
        return above.is(this);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (!canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, other, level, pos, otherPos);
    }

    /**
     * Any piece taken down takes the rope down with it: the top piece broken (its loot is the coil, Survival), and the
     * pieces under it come away with nothing more.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getValue(PART) != Part.TOP) {
            BlockPos top = topOf(level, pos);
            if (top != null && !top.equals(pos)) level.destroyBlock(top, !player.getAbilities().instabuild, player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** The top piece of the rope this piece is part of, or null if the rope is broken above it. */
    @javax.annotation.Nullable
    public static BlockPos topOf(BlockGetter level, BlockPos pos) {
        BlockPos p = pos;
        for (int i = 0; i <= LONGEST + 1; i++) {
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof RopeBlock)) return null;
            if (s.getValue(PART) == Part.TOP) return p;
            p = p.above();
        }
        return null;
    }

    /** The lowest piece of the rope hanging from this top piece. */
    public static BlockPos bottomOf(BlockGetter level, BlockPos top) {
        BlockPos p = top;
        for (int i = 0; i < LONGEST && level.getBlockState(p.below()).getBlock() instanceof RopeBlock; i++) p = p.below();
        return p;
    }

    /**
     * Let a rope down from here: its top piece at {@code top}, hitched to the edge that way ({@code wall}), and down as
     * far as it goes before it meets anything (no further than {@code most}). The pieces let down: none if there is no
     * drop here to let it into (two clear cells at the least).
     */
    public static int hang(Level level, BlockPos top, Direction wall, int most, Block rope) {
        if (!clear(level, top) || !clear(level, top.below())) return 0;
        BlockPos edge = top.relative(wall);
        if (level.getBlockState(edge).getCollisionShape(level, edge).isEmpty()) return 0;
        int n = 0;
        while (n < most && clear(level, top.below(n))) n++;
        BlockState s = rope.defaultBlockState().setValue(FACING, wall);
        for (int i = 0; i < n; i++) {
            Part part = i == 0 ? Part.TOP : i == n - 1 ? Part.BOTTOM : Part.MIDDLE;
            level.setBlock(top.below(i), s.setValue(PART, part), 2);
        }
        level.playSound(null, top, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
        level.playSound(null, top.below(n - 1), SoundEvents.LADDER_STEP, SoundSource.BLOCKS, 0.8F, 0.9F);
        return n;
    }

    /** Taken up: the whole rope gone from the world (its coil the caller's to give back). The pieces taken up. */
    public static int takeUp(Level level, BlockPos top) {
        if (!(level.getBlockState(top).getBlock() instanceof RopeBlock)) return 0;
        BlockPos bottom = bottomOf(level, top);
        int n = 0;
        // From the bottom up, quietly (no piece left to fall as the one over it goes, nothing dropped: the coil is given).
        for (int y = bottom.getY(); y <= top.getY(); y++) {
            BlockPos p = new BlockPos(top.getX(), y, top.getZ());
            if (!(level.getBlockState(p).getBlock() instanceof RopeBlock)) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
            n++;
        }
        level.playSound(null, top, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.0F, 0.9F);
        return n;
    }

    /** Open to let a rope through: air, a plant, snow, water-free. */
    static boolean clear(LevelReader level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state.getValue(PART) == Part.TOP && level instanceof ServerLevel server) com.jrpetty.mcassistant.entity.Ropes.hung(server, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moving) {
        if (state.getValue(PART) == Part.TOP && !now.is(this) && level instanceof ServerLevel server) {
            com.jrpetty.mcassistant.entity.Ropes.gone(server, pos);
        }
        super.onRemove(state, level, pos, now, moving);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(com.jrpetty.mcassistant.item.WorkItems.ROPE_COIL.get());
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 60;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 30;
    }
}
