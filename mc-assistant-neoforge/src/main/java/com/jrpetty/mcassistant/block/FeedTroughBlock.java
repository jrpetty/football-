package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.item.FieldItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [fields] The feed trough: a plank trough on short legs, set in the pen. The rancher fills it out of the stores with
 * the right feed for what is in the pen: wheat for the sheep and the cows, seed for the hens, carrots for the pigs (it
 * holds sixteen of each of three feeds). While there is feed in it the grown animals near it stay close, and now and
 * then a pair of them breeds on its own, each eating out of the trough (FieldTools), so the rancher seldom walks
 * about holding wheat out. How full it is shows in the grain heaped in it. A player fills it by hand, a handful at a
 * time, and looks in with an empty hand.
 */
public class FeedTroughBlock extends Block implements EntityBlock, FieldBlock {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    /** How full: empty, a little, half, heaped. */
    public static final IntegerProperty FEED = IntegerProperty.create("feed", 0, 3);

    private static final VoxelShape ALONG_X = Block.box(0.0, 0.0, 2.0, 16.0, 8.0, 14.0);
    private static final VoxelShape ALONG_Z = Block.box(2.0, 0.0, 0.0, 14.0, 8.0, 16.0);

    public FeedTroughBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(FEED, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, FEED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Its length across the way the placer faces, as a trough is set along a fence.
        return defaultBlockState().setValue(AXIS, ctx.getHorizontalDirection().getClockWise().getAxis());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(AXIS) == Direction.Axis.X ? ALONG_X : ALONG_Z;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FieldBlockEntity(FieldItems.FEED_TROUGH_BE.get(), pos, state, FieldBlockEntity.Kind.TROUGH);
    }

    /** What goes in a trough: what the pen's animals eat. */
    public static boolean feed(ItemStack s) {
        return s.is(Items.WHEAT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.MELON_SEEDS)
            || s.is(Items.PUMPKIN_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT);
    }

    @Override
    public boolean accepts(ItemStack stack) {
        return feed(stack);
    }

    /** How much feed is in it, all told. */
    public static int feedIn(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FieldBlockEntity be && be.kind() == FieldBlockEntity.Kind.TROUGH ? be.count() : 0;
    }

    @Override
    public void refresh(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof FeedTroughBlock)) return;
        int n = feedIn(level, pos);
        int show = n == 0 ? 0 : n <= 8 ? 1 : n <= 24 ? 2 : 3;
        if (st.getValue(FEED) != show) level.setBlock(pos, st.setValue(FEED, show), 3);
    }

    /** Feed put in: what would not go. */
    public static ItemStack fill(Level level, BlockPos pos, ItemStack in) {
        if (!(level.getBlockEntity(pos) instanceof FieldBlockEntity be) || be.kind() != FieldBlockEntity.Kind.TROUGH || !feed(in)) return in;
        ItemStack left = be.insert(in);
        if (left.getCount() != in.getCount()) {
            level.playSound(null, pos, SoundEvents.COMPOSTER_FILL, SoundSource.BLOCKS, 0.7F, 0.9F);
            if (level.getBlockState(pos).getBlock() instanceof FeedTroughBlock b) b.refresh(level, pos);
        }
        return left;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!feed(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        int give = Math.min(stack.getCount(), 8);                 // a handful at a time
        ItemStack left = fill(level, pos, stack.copyWithCount(give));
        int put = give - left.getCount();
        if (put <= 0) {
            player.displayClientMessage(Component.literal("The trough has no room for that."), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!player.getAbilities().instabuild) stack.shrink(put);
        player.displayClientMessage(Component.literal(words(level, pos)), true);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        player.displayClientMessage(Component.literal(words(level, pos)), true);
        return InteractionResult.CONSUME;
    }

    /** "The feed trough: 12 wheat and 6 carrots." */
    public static String words(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FieldBlockEntity be)) return "The feed trough.";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < be.getContainerSize(); i++) {
            ItemStack s = be.getItem(i);
            if (s.isEmpty()) continue;
            parts.add(s.getCount() + " " + s.getHoverName().getString().toLowerCase(Locale.ROOT));
        }
        return parts.isEmpty() ? "The feed trough is empty." : "The feed trough: " + String.join(" and ", parts) + ".";
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
        return Math.min(15, (feedIn(level, pos) * 15 + 47) / 48);
    }
}
