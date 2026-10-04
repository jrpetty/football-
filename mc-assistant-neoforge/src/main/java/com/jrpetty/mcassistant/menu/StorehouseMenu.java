package com.jrpetty.mcassistant.menu;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Village Storehouse's screen: six rows of its eighty-one at a time, scrolled. The
 * same fifty-four slots show whichever rows are scrolled to — on both sides, the
 * server being told the row with the menu-button channel (a row number is a button id)
 * and sending the whole window afresh, so what is shown is always what is there.
 */
public class StorehouseMenu extends AbstractContainerMenu {

    public static final int SHOWN_ROWS = 6;
    public static final int WINDOW = SHOWN_ROWS * StorehouseBlockEntity.COLUMNS;
    public static final int LAST_ROW = StorehouseBlockEntity.ROWS - SHOWN_ROWS;
    /** Button ids: 0..LAST_ROW scroll to that row; this one sorts the store. */
    public static final int BUTTON_SORT = 100;

    private final Container store;
    private int row;
    private final DataSlot used = DataSlot.standalone();

    /** The client's side: a stand-in the server fills, slot by slot. */
    public StorehouseMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(StorehouseBlockEntity.SIZE));
    }

    public StorehouseMenu(int id, Inventory inventory, Container store) {
        super(McAssistantMod.STOREHOUSE_MENU.get(), id);
        checkContainerSize(store, StorehouseBlockEntity.SIZE);
        this.store = store;
        store.startOpen(inventory.player);
        for (int r = 0; r < SHOWN_ROWS; r++) {
            for (int c = 0; c < StorehouseBlockEntity.COLUMNS; c++) {
                addSlot(new Window(store, c + r * StorehouseBlockEntity.COLUMNS, 8 + c * 18, 18 + r * 18));
            }
        }
        int below = (SHOWN_ROWS - 4) * 18;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addSlot(new Slot(inventory, c + r * 9 + 9, 8 + c * 18, 103 + r * 18 + below));
        }
        for (int c = 0; c < 9; c++) addSlot(new Slot(inventory, c, 8 + c * 18, 161 + below));
        addDataSlot(used);
        used.set(countUsed());
    }

    /** The first row shown. */
    public int row() {
        return row;
    }

    /** How many of the store's slots hold something (as the server last said). */
    public int used() {
        return used.get();
    }

    /** Show from this row down (both sides call it: the client as it scrolls, the server when told). */
    public void scrollTo(int to) {
        row = Math.max(0, Math.min(LAST_ROW, to));
    }

    private int countUsed() {
        if (store instanceof StorehouseBlockEntity s) return s.used();
        int n = 0;
        for (int i = 0; i < store.getContainerSize(); i++) if (!store.getItem(i).isEmpty()) n++;
        return n;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id >= 0 && id <= LAST_ROW) {
            scrollTo(id);
        } else if (id == BUTTON_SORT && store instanceof StorehouseBlockEntity s) {
            s.sort();
        } else {
            return false;
        }
        used.set(countUsed());
        sendAllDataToRemote();
        return true;
    }

    @Override
    public void broadcastChanges() {
        if (store instanceof StorehouseBlockEntity) used.set(countUsed());
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return store.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        store.stopOpen(player);
    }

    /** Shift-click: out of the store into the pack, or out of the pack into anywhere in the store. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack was = stack.copy();
        if (index < WINDOW) {
            if (!moveItemStackTo(stack, WINDOW, slots.size(), true)) return ItemStack.EMPTY;
        } else if (store instanceof StorehouseBlockEntity s) {
            ItemStack left = s.insert(stack);
            if (left.getCount() == stack.getCount()) return ItemStack.EMPTY;
            stack.setCount(left.getCount());
        } else if (!moveItemStackTo(stack, 0, WINDOW, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return was;
    }

    /** One of the fifty-four slots on the screen, showing whichever row is scrolled to. */
    private final class Window extends Slot {

        private final int base;

        Window(Container container, int base, int x, int y) {
            super(container, base, x, y);
            this.base = base;
        }

        private int at() {
            return row * StorehouseBlockEntity.COLUMNS + base;
        }

        @Override
        public int getContainerSlot() {
            return at();
        }

        @Override
        public ItemStack getItem() {
            return container.getItem(at());
        }

        @Override
        public void set(ItemStack stack) {
            container.setItem(at(), stack);
            setChanged();
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack was) {
            set(stack);
        }

        @Override
        public ItemStack remove(int amount) {
            return container.removeItem(at(), amount);
        }
    }
}
