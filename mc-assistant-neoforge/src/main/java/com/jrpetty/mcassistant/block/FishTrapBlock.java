package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.item.FieldItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * [fields] The fish trap: a woven willow cage with a funnel mouth, a bit of bait tied in it, set in the water off the
 * quay or in a pond. Now and then a fish swims in and cannot find the way out (the block's own random tick, so it costs
 * the server nothing between): a cod, or a salmon in a river or cold water, with a little junk (a stick, a bone, a
 * length of string, a strand of kelp), up to six. The fishers set two to four and empty them on the days the boats stay
 * in, or when passing (FieldTools); a player sets its own and empties it with an empty hand. The catch shows in the cage.
 */
public class FishTrapBlock extends Block implements EntityBlock, FieldBlock, SimpleWaterloggedBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    /** What is to be seen in it: nothing, a fish or two, a full cage. */
    public static final IntegerProperty CATCH = IntegerProperty.create("catch", 0, 2);

    /** The most it holds. */
    public static final int MOST = 6;
    /** A random tick catches something one time in so many (about one fish in four and a half minutes). */
    public static final int ODDS = 4;

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 11.0, 14.0);

    public FishTrapBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(WATERLOGGED, false).setValue(CATCH, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED, CATCH);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        FluidState f = ctx.getLevel().getFluidState(ctx.getClickedPos());
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite())
            .setValue(WATERLOGGED, f.getType() == Fluids.WATER);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return super.updateShape(state, dir, other, level, pos, otherPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FieldBlockEntity(FieldItems.FISH_TRAP_BE.get(), pos, state, FieldBlockEntity.Kind.TRAP);
    }

    @Override
    public boolean accepts(ItemStack stack) {
        return false;                                   // nothing is put into a trap but by the fish themselves
    }

    @Override
    public void refresh(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof FishTrapBlock) || !(level.getBlockEntity(pos) instanceof FieldBlockEntity be)) return;
        int n = be.count();
        int show = n == 0 ? 0 : n <= 2 ? 1 : 2;
        if (st.getValue(CATCH) != show) level.setBlock(pos, st.setValue(CATCH, show), 3);
    }

    // ------------------------------------------------------------------ the catch

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return state.getValue(WATERLOGGED);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(ODDS) == 0) catchOne(level, pos, random);
    }

    /** Is it set where fish swim: in the water, with more water about it than its own block? */
    public static boolean inOpenWater(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof FishTrapBlock) || !st.getValue(WATERLOGGED)) return false;
        int wet = 0;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            if (level.getFluidState(pos.relative(d)).is(FluidTags.WATER)) wet++;
        }
        return wet >= 2;
    }

    /** Something swims in, if there is room and it is in open water: what came, or nothing. */
    public static ItemStack catchOne(ServerLevel level, BlockPos pos, RandomSource r) {
        if (!inOpenWater(level, pos) || !(level.getBlockEntity(pos) instanceof FieldBlockEntity be) || be.count() >= MOST) return ItemStack.EMPTY;
        ItemStack got = what(level, pos, r);
        ItemStack left = be.insert(got);
        if (!left.isEmpty()) return ItemStack.EMPTY;
        level.sendParticles(ParticleTypes.BUBBLE_POP, pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 6, 0.25, 0.1, 0.25, 0.02);
        level.playSound(null, pos, SoundEvents.FISH_SWIM, SoundSource.BLOCKS, 0.4F, 1.2F);
        if (level.getBlockState(pos).getBlock() instanceof FishTrapBlock b) b.refresh(level, pos);
        return got;
    }

    /** What swims in here: salmon in a river or cold water, cod in the rest, and now and then a bit of junk. */
    static ItemStack what(ServerLevel level, BlockPos pos, RandomSource r) {
        int roll = r.nextInt(100);
        if (roll < 12) {
            return switch (r.nextInt(5)) {
                case 0 -> new ItemStack(Items.STICK);
                case 1 -> new ItemStack(Items.BONE);
                case 2 -> new ItemStack(Items.STRING);
                case 3 -> new ItemStack(Items.KELP);
                default -> new ItemStack(Items.SEAGRASS);
            };
        }
        Holder<Biome> b = level.getBiome(pos);
        boolean cold = b.value().getBaseTemperature() < 0.3F || b.value().coldEnoughToSnow(pos);
        boolean salmon = b.is(BiomeTags.IS_RIVER) || cold ? roll < 75 : roll < 22;
        return new ItemStack(salmon ? Items.SALMON : Items.COD);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof FieldBlockEntity be && be.count() > 0) {
            StringBuilder said = new StringBuilder();
            for (ItemStack s : be.takeAll()) {
                if (said.length() > 0) said.append(", ");
                said.append(s.getCount()).append(' ').append(s.getHoverName().getString().toLowerCase(Locale.ROOT));
                if (!player.getInventory().add(s)) player.drop(s, false);
            }
            refresh(level, pos);
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_FISH, SoundSource.PLAYERS, 0.6F, 1.0F);
            player.displayClientMessage(Component.literal("You empty the trap: " + said + "."), true);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(Component.literal(inOpenWater(level, pos) ? "The trap is empty. Give the fish time."
            : "The trap wants setting in open water: nothing will swim into it here."), true);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
        FieldBlock.spill(state, level, pos, now);
        super.onRemove(state, level, pos, now, moved);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FieldBlockEntity be ? Math.min(15, be.count() * 15 / MOST) : 0;
    }
}
