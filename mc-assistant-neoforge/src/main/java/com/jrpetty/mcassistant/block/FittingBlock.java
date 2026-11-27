package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * [till] A shop fitting: a display shelf, a glass case, a produce crate, a price board, a basket stand, a larder or a
 * clothes rack. The ones that hold things (five slots) show what is on them and are part of their business's stock;
 * its owner stocks them (or its staff, from the stock chest), a customer clicks a ware on one and the checkout opens
 * with it in the basket. Every fitting counts to the business's fit-out (entity/Fittings): more footfall.
 */
public class FittingBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** The kinds: their id, whether they hold stock, and their outline (facing north, the customers' side). */
    public enum Kind {
        SHELF("display_shelf", true, "shelf", "shelves", box(0, 0, 0, 16, 16, 16)),
        CASE("display_case", true, "case", "cases", box(0, 0, 0, 16, 15, 16)),
        CRATE("produce_crate", true, "crate", "crates", box(1, 0, 1, 15, 9, 15)),
        BOARD("price_board", false, "price board", "price boards", box(1, 0, 6, 15, 16, 10)),
        BASKETS("basket_stand", false, "basket stand", "basket stands", box(3, 0, 3, 13, 12, 13)),
        LARDER("larder", true, "larder", "larders", box(1, 0, 1, 15, 16, 15)),
        RACK("clothes_rack", true, "clothes rack", "clothes racks", box(1, 0, 6, 15, 16, 10));

        public final String id, word, words;
        public final boolean holds;
        final VoxelShape north;

        Kind(String id, boolean holds, String word, String words, VoxelShape north) {
            this.id = id;
            this.holds = holds;
            this.word = word;
            this.words = words;
            this.north = north;
        }

        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return SHELF;
        }
    }

    public final Kind kind;
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    public FittingBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        for (Direction d : Direction.Plane.HORIZONTAL) shapes.put(d, turn(kind.north, d));
    }

    /** A north-facing outline turned to face {@code d}. */
    static VoxelShape turn(VoxelShape north, Direction d) {
        VoxelShape[] out = { Shapes.empty() };
        north.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            double ax0 = x0, az0 = z0, ax1 = x1, az1 = z1;
            switch (d) {
                case SOUTH -> { ax0 = 1 - x1; ax1 = 1 - x0; az0 = 1 - z1; az1 = 1 - z0; }
                case EAST -> { ax0 = 1 - z1; ax1 = 1 - z0; az0 = x0; az1 = x1; }
                case WEST -> { ax0 = z0; ax1 = z1; az0 = 1 - x1; az1 = 1 - x0; }
                default -> { }
            }
            out[0] = Shapes.or(out[0], Shapes.box(ax0, y0, az0, ax1, y1, az1));
        });
        return out[0];
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapes.getOrDefault(state.getValue(FACING), kind.north);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FittingBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level instanceof ServerLevel sl && by instanceof ServerPlayer p) com.jrpetty.mcassistant.entity.Fittings.placed(sl, pos, p);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) com.jrpetty.mcassistant.entity.Fittings.use(sp, pos, hit.getLocation());
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) com.jrpetty.mcassistant.entity.Fittings.use(sp, pos, hit.getLocation());
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
        if (!state.is(now.getBlock())) {
            if (level.getBlockEntity(pos) instanceof FittingBlockEntity be) Containers.dropContents(level, pos, be);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, now, moved);
    }
}
