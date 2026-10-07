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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [fields] The nesting box: a low plank box lined with hay, set in the pen or the coop. A hen within six blocks lays into
 * it (nine eggs at most) instead of onto the ground, and lays a little more often while it has hay in it (FieldTools);
 * the rancher empties it into the stores on its round and lines it afresh with a little of the stores' wheat. What is
 * in it shows: the hay, and one, two or a clutch of eggs. A player takes the eggs out with an empty hand, and lines it
 * with wheat or a bale of hay.
 */
public class NestingBoxBlock extends Block implements EntityBlock, FieldBlock {

    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** The eggs to be seen: none, one or two, a few, a clutch. */
    public static final IntegerProperty EGGS = IntegerProperty.create("eggs", 0, 3);
    /** Lined with hay. */
    public static final BooleanProperty HAY = BooleanProperty.create("hay");

    /** The most eggs it holds: nine slots of sixteen. */
    public static final int MOST = 9 * 16;
    /** Eggs a wheat's lining cushions before it wants renewing, and a bale's. */
    public static final int HAY_OF_WHEAT = 8, HAY_OF_BALE = 72;

    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 7.0, 15.0);

    public NestingBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(EGGS, 0).setValue(HAY, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, EGGS, HAY);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(HAY, false);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FieldBlockEntity(FieldItems.NESTING_BOX_BE.get(), pos, state, FieldBlockEntity.Kind.BOX);
    }

    @Override
    public boolean accepts(ItemStack stack) {
        return stack.is(Items.EGG);
    }

    @Override
    public void refresh(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof NestingBoxBlock) || !(level.getBlockEntity(pos) instanceof FieldBlockEntity be)) return;
        int n = be.count();
        int show = n == 0 ? 0 : n <= 2 ? 1 : n <= 8 ? 2 : 3;
        BlockState now = st.setValue(EGGS, show).setValue(HAY, be.hay() > 0);
        if (now != st) level.setBlock(pos, now, 3);
    }

    /** An egg laid into the box: true if there was room. A little of the hay is used up by it. */
    public static boolean lay(Level level, BlockPos pos, ItemStack egg) {
        if (!(level.getBlockEntity(pos) instanceof FieldBlockEntity be) || be.kind() != FieldBlockEntity.Kind.BOX) return false;
        ItemStack left = be.insert(egg.copyWithCount(1));
        if (!left.isEmpty()) return false;
        if (be.hay() > 0) be.setHay(be.hay() - 1);
        level.playSound(null, pos, SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.8F, 1.1F);
        if (level.getBlockState(pos).getBlock() instanceof NestingBoxBlock b) b.refresh(level, pos);
        return true;
    }

    /** Is there room in it for another egg? */
    public static boolean room(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FieldBlockEntity be && be.kind() == FieldBlockEntity.Kind.BOX && be.count() < MOST;
    }

    /** Lined afresh: so many eggs' worth of hay. */
    public static void line(Level level, BlockPos pos, int hay) {
        if (!(level.getBlockEntity(pos) instanceof FieldBlockEntity be)) return;
        be.setHay(Math.min(HAY_OF_BALE, be.hay() + hay));
        level.playSound(null, pos, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.7F, 1.2F);
        if (level.getBlockState(pos).getBlock() instanceof NestingBoxBlock b) b.refresh(level, pos);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        boolean wheat = stack.is(Items.WHEAT), bale = stack.is(Items.HAY_BLOCK);
        if (!wheat && !bale) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        line(level, pos, bale ? HAY_OF_BALE : HAY_OF_WHEAT);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.displayClientMessage(Component.literal(words(level, pos)), true);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof FieldBlockEntity be && be.count() > 0) {
            int n = 0;
            for (ItemStack s : be.takeAll()) {
                n += s.getCount();
                if (!player.getInventory().add(s)) player.drop(s, false);
            }
            refresh(level, pos);
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 1.0F);
            player.displayClientMessage(Component.literal("You take " + (n == 1 ? "an egg" : n + " eggs") + " out of the nesting box."), true);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(Component.literal(words(level, pos)), true);
        return InteractionResult.CONSUME;
    }

    /** "The nesting box: three eggs, on fresh hay." */
    static String words(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FieldBlockEntity be)) return "The nesting box.";
        int n = be.count();
        String eggs = n == 0 ? "no eggs yet" : n == 1 ? "an egg" : n + " eggs";
        String hay = be.hay() <= 0 ? ", and no hay in it (line it with wheat or a bale)" : be.hay() >= 6 ? ", on fresh hay" : ", the hay wearing thin";
        return "The nesting box: " + eggs + hay + ".";
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
        return level.getBlockEntity(pos) instanceof FieldBlockEntity be ? Math.min(15, (be.count() * 15 + MOST - 1) / MOST) : 0;
    }
}
