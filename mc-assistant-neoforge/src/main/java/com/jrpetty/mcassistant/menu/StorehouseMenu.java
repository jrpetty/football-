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
 * The Village Storehouse's screen: six rows of its many at a time, scrolled. The store grows
 * whenever it nears full (StorehouseBlockEntity), so how many rows it has is the server's to
 * say (a synced data slot). The same fifty-four slots show whichever rows are scrolled to: the
 * server is told where with the menu-button channel — a step up or down, a page, or a place on
 * the bar in hundredths, which all fit a button id however big the store — and sends the whole
 * window afresh, so what is shown is always what is there.
 */
public class StorehouseMenu extends AbstractContainerMenu {

    public static final int SHOWN_ROWS = 6;
    public static final int WINDOW = SHOWN_ROWS * StorehouseBlockEntity.COLUMNS;
    /** Button ids: 0..100 scroll to that place on the bar (in hundredths); then sort, and a step or a page up or down. */
    public static final int BUTTON_SORT = 101, BUTTON_UP = 102, BUTTON_DOWN = 103, BUTTON_PAGE_UP = 104, BUTTON_PAGE_DOWN = 105;

    private final Container store;
    private int row;
    private final DataSlot used = DataSlot.standalone();
    /** How many rows the store has, and the row shown first (the server's word on both). */
    private final DataSlot rows = DataSlot.standalone();
    private final DataSlot shown = DataSlot.standalone();

    /** The client's side: a stand-in holding the window the server fills, slot by slot. */
    public StorehouseMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(WINDOW));
    }

    public StorehouseMenu(int id, Inventory inventory, Container store) {
        super(McAssistantMod.STOREHOUSE_MENU.get(), id);
        checkContainerSize(store, WINDOW);
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
        addDataSlot(rows);
        addDataSlot(shown);
        used.set(countUsed());
        rows.set(storeRows());
        shown.set(0);
    }

    private boolean serverSide() {
        return store instanceof StorehouseBlockEntity;
    }

    private int storeRows() {
        return store instanceof StorehouseBlockEntity s ? s.rows() : StorehouseBlockEntity.ROWS;
    }

    /** How many rows the store has (as the server last said). */
    public int rows() {
        return Math.max(SHOWN_ROWS, rows.get());
    }

    /** The last row a window can start on. */
    public int lastRow() {
        return Math.max(0, rows() - SHOWN_ROWS);
    }

    @Override
    public void setData(int id, int value) {
        super.setData(id, value);
        if (id == 2) row = value;                                          // the server's word on where we are
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
        row = Math.max(0, Math.min(lastRow(), to));
        if (serverSide()) shown.set(row);
    }

    private int countUsed() {
        if (store instanceof StorehouseBlockEntity s) return s.used();
        int n = 0;
        for (int i = 0; i < store.getContainerSize(); i++) if (!store.getItem(i).isEmpty()) n++;
        return n;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        rows.set(storeRows());
        if (id >= 0 && id <= 100) {
            scrollTo((int) Math.round(id / 100.0 * lastRow()));
        } else if (id == BUTTON_UP || id == BUTTON_DOWN || id == BUTTON_PAGE_UP || id == BUTTON_PAGE_DOWN) {
            int step = id == BUTTON_PAGE_UP || id == BUTTON_PAGE_DOWN ? SHOWN_ROWS : 1;
            scrollTo(row + (id == BUTTON_DOWN || id == BUTTON_PAGE_DOWN ? step : -step));
        } else if (id == BUTTON_SORT && store instanceof StorehouseBlockEntity s) {
            s.sort();
        } else {
            return false;
        }
        used.set(countUsed());
        rows.set(storeRows());
        sendAllDataToRemote();
        return true;
    }

    @Override
    public void broadcastChanges() {
        if (store instanceof StorehouseBlockEntity) {
            used.set(countUsed());
            rows.set(storeRows());
        }
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

        /** The store's slot this one shows: on the server, the row scrolled to; on the client the
         *  stand-in holds only the window, slot for slot. */
        private int at() {
            return serverSide() ? row * StorehouseBlockEntity.COLUMNS + base : base;
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
