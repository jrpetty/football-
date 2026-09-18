package com.jrpetty.mcassistant.menu;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A Container view over the assistant so the player can manage it through a
 * menu: slots 0-26 are its 27-slot backpack, and slots 27-32 are its worn
 * equipment (helmet, chestplate, leggings, boots, main hand, off hand).
 * Writes go straight onto the live entity, so armor and held tools take effect
 * (and render) immediately.
 */
public class AssistantInventoryContainer implements Container {

    public static final int BACKPACK = 27;
    public static final int EQUIP = 6;
    public static final int SIZE = BACKPACK + EQUIP;

    // Menu slots 27..32 map to these equipment slots, in order.
    public static final EquipmentSlot[] EQUIPMENT = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
    };

    private final AssistantEntity assistant;

    public AssistantInventoryContainer(AssistantEntity assistant) {
        this.assistant = assistant;
    }

    private boolean isEquip(int slot) {
        return slot >= BACKPACK && slot < SIZE;
    }

    private EquipmentSlot equipSlot(int slot) {
        return EQUIPMENT[slot - BACKPACK];
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SIZE; i++) {
            if (!getItem(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (isEquip(slot)) return assistant.getItemBySlot(equipSlot(slot));
        if (slot >= 0 && slot < BACKPACK) return assistant.getInventoryItems().get(slot);
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack current = getItem(slot);
        if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack taken = current.split(amount);
        setItem(slot, current.isEmpty() ? ItemStack.EMPTY : current);
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = getItem(slot);
        setItem(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (isEquip(slot)) {
            assistant.setItemSlot(equipSlot(slot), stack);
        } else if (slot >= 0 && slot < BACKPACK) {
            assistant.getInventoryItems().set(slot, stack);
        }
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public void setChanged() {
        // Live entity — nothing to persist here; NBT save handles durability.
    }

    @Override
    public boolean stillValid(Player player) {
        return assistant.isAlive() && assistant.isOwner(player)
            && player.distanceToSqr(assistant) <= 64.0;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SIZE; i++) setItem(i, ItemStack.EMPTY);
    }
}
