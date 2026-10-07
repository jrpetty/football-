package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.item.FieldItems;
import com.jrpetty.mcassistant.item.WateringCanItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [fields] The rain barrel: a stave barrel bound with two copper hoops, its head knocked out, set under the sky by a
 * workshop or at the edge of a field. It fills in the rain, up to four buckets (the level stands in it, and a
 * comparator reads it), as a cauldron does but quicker, its mouth being the wider. Folk fill their watering cans and the
 * fire brigade its buckets at it; the bucket chain draws from one when there is no pond near the fire; in a drought the
 * farmers draw from the barrels first (FieldTools). A player fills a bucket or a watering can at it, or tips a bucket
 * of water in.
 */
public class RainBarrelBlock extends Block implements EntityBlock {

    /** The water in it, in buckets. */
    public static final IntegerProperty WATER = IntegerProperty.create("water", 0, 4);
    public static final int MOST = 4;
    /** Of the times the rain falls on its open head (the game's weather tick), it gains a bucket this often. */
    static final float RAIN_FILLS = 0.3F;

    private static final VoxelShape OUTSIDE = Block.box(1.0, 0.0, 1.0, 15.0, 15.0, 15.0);
    private static final VoxelShape SHAPE = Shapes.join(OUTSIDE, Block.box(3.0, 3.0, 3.0, 13.0, 15.0, 13.0), BooleanOp.ONLY_FIRST);

    public RainBarrelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WATER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATER);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return OUTSIDE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FieldBlockEntity(FieldItems.RAIN_BARREL_BE.get(), pos, state, FieldBlockEntity.Kind.BARREL);
    }

    public static int water(BlockState st) {
        return st.getBlock() instanceof RainBarrelBlock ? st.getValue(WATER) : 0;
    }

    /** The water in the barrel standing here (nought if it is no barrel). */
    public static int water(Level level, BlockPos pos) {
        return water(level.getBlockState(pos));
    }

    /** A bucket's worth drawn off: false if it was dry. */
    public static boolean draw(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (water(st) <= 0) return false;
        level.setBlock(pos, st.setValue(WATER, st.getValue(WATER) - 1), 3);
        level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.8F, 1.0F);
        level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
        return true;
    }

    /** So many buckets' worth more, up to four: how many went in. */
    public static int add(Level level, BlockPos pos, int n) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof RainBarrelBlock) || n <= 0) return 0;
        int put = Math.min(n, MOST - st.getValue(WATER));
        if (put <= 0) return 0;
        level.setBlock(pos, st.setValue(WATER, st.getValue(WATER) + put), 3);
        return put;
    }

    // ------------------------------------------------------------------ the rain

    /** The game's weather tick, when it falls on the barrel's open head (it is the top block of its column). */
    @Override
    public void handlePrecipitation(BlockState state, Level level, BlockPos pos, Biome.Precipitation precipitation) {
        if (precipitation != Biome.Precipitation.RAIN || state.getValue(WATER) >= MOST) return;
        if (level.getRandom().nextFloat() < RAIN_FILLS) rainIn(level, pos);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return state.getValue(WATER) < MOST;
    }

    /** And its random tick: rain falling on it now, it gains a bucket (a barrel fills in a long shower, not a day's). */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.isRainingAt(pos.above())) rainIn(level, pos);
    }

    /** A bucket's worth of rain in. */
    public static void rainIn(Level level, BlockPos pos) {
        if (add(level, pos, 1) > 0 && level instanceof ServerLevel s) {
            s.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 0.95, pos.getZ() + 0.5, 6, 0.25, 0.02, 0.25, 0.05);
        }
    }

    // ------------------------------------------------------------------ a hand at it

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        int w = state.getValue(WATER);
        if (stack.is(Items.BUCKET)) {
            if (w <= 0) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            draw(level, pos);
            player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.WATER_BUCKET)));
            return ItemInteractionResult.CONSUME;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            if (w >= MOST) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            add(level, pos, 1);
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
            if (!player.getAbilities().instabuild) player.setItemInHand(hand, new ItemStack(Items.BUCKET));
            return ItemInteractionResult.CONSUME;
        }
        if (stack.getItem() instanceof WateringCanItem) {
            if (w <= 0 || WateringCanItem.water(stack) >= WateringCanItem.CAPACITY) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            draw(level, pos);
            WateringCanItem.fill(stack);
            player.displayClientMessage(Component.literal("You fill the can at the rain barrel."), true);
            return ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        int w = state.getValue(WATER);
        player.displayClientMessage(Component.literal(w == 0 ? "The rain barrel is dry. It fills when it rains."
            : "The rain barrel: " + (w == MOST ? "full, four buckets" : w == 1 ? "a bucket" : w + " buckets") + " of rain water."), true);
        return InteractionResult.CONSUME;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(WATER) * 3;
    }
}
