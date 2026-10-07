package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [workitems] The shipping crate: a box of boards with battens at its corners, nine stacks in it (ShippingCrateBlockEntity),
 * that keeps what it holds when it is broken and picked up, as a shulker box does: the haulers and the caravans pack the
 * town's goods into crates to carry nine stacks in a hand's one slot (entity/Crates), and the stores unpack them. Open it
 * as any chest; break it with goods in and they go with it.
 */
public class ShippingCrateBlock extends Block implements EntityBlock {

    private static final VoxelShape SHAPE = box(0, 0, 0, 16, 16, 16);

    public ShippingCrateBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShippingCrateBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ShippingCrateBlockEntity crate) player.openMenu(crate);
        return InteractionResult.CONSUME;
    }

    /**
     * Broken in creative with goods in it, it still comes away as a full crate (its loot does that in survival: the
     * table copies its contents onto the crate dropped).
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && level.getBlockEntity(pos) instanceof ShippingCrateBlockEntity crate && !crate.isEmpty()) {
            ItemStack drop = new ItemStack(this);
            drop.applyComponents(crate.collectComponents());
            ItemEntity e = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
            e.setDefaultPickUpDelay();
            level.addFreshEntity(e);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Pushed by a piston or blown up, the goods are not lost: the crate's loot carries them; nothing spills. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moving) {
        if (!state.is(now.getBlock())) level.updateNeighbourForOutputSignal(pos, state.getBlock());
        super.onRemove(state, level, pos, now, moving);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    /** What a crate item holds, as stacks (empty when it holds nothing). */
    public static java.util.List<ItemStack> contents(ItemStack crate) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        var c = crate.get(DataComponents.CONTAINER);
        if (c != null) for (ItemStack s : c.nonEmptyItemsCopy()) out.add(s);
        return out;
    }

    /** These goods into a crate item (no more than nine stacks). */
    public static void fill(ItemStack crate, java.util.List<ItemStack> goods) {
        java.util.List<ItemStack> nine = new java.util.ArrayList<>();
        for (ItemStack s : goods) if (!s.isEmpty() && nine.size() < ShippingCrateBlockEntity.SLOTS) nine.add(s.copy());
        if (nine.isEmpty()) crate.remove(DataComponents.CONTAINER);
        else crate.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(nine));
    }

    /** Spill a crate item's goods where it lies (it was destroyed as an item: burnt, in the void). */
    public static void spill(ItemEntity e) {
        for (ItemStack s : contents(e.getItem())) {
            Containers.dropItemStack(e.level(), e.getX(), e.getY(), e.getZ(), s);
        }
    }
}
