package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [workitems] What a shipping crate holds: nine stacks, kept in it when it is broken and carried off (as a shulker box
 * keeps its own), and back in it when it is set down again. Never a crate inside a crate.
 */
public class ShippingCrateBlockEntity extends BaseContainerBlockEntity {

    public static final int SLOTS = 9;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public ShippingCrateBlockEntity(BlockPos pos, BlockState state) {
        super(com.jrpetty.mcassistant.item.WorkItems.SHIPPING_CRATE_BE.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mc_assistant.shipping_crate");
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> to) {
        items = to;
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new com.jrpetty.mcassistant.menu.CrateMenu(id, inventory, this);
    }

    /** Nothing that will not go in a container, and no crate in a crate. */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return com.jrpetty.mcassistant.menu.CrateMenu.fits(stack);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, false, registries);
    }
}
