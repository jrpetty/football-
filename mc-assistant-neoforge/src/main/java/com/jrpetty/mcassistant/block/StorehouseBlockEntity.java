package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.menu.StorehouseMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The goods of a Village Storehouse: 729 slots, as much as twenty-seven chests, kept at
 * the door of the cube. A unit that is not a joined store's door has none of this — or,
 * after its store came apart, keeps the goods safe until the cube is whole again.
 *
 * <p>Saved slot by slot with a two-byte slot number: the game's own helper keeps the
 * slot in one byte, which only counts to 255.
 */
public class StorehouseBlockEntity extends BlockEntity implements Container, MenuProvider, Nameable {

    public static final int COLUMNS = 9;
    /** The rows it starts with: it grows nine rows at a time whenever it gets near full, so it is
     *  never full (the village's goods are never left lying at the door). */
    public static final int ROWS = 81;
    public static final int SIZE = COLUMNS * ROWS;
    /** Rows added at a time, and the empty rows it always keeps in hand. */
    private static final int GROW_ROWS = 9, SPARE_ROWS = 2;
    /** The most a unit carries out of a broken store in one item; more than this spills. */
    private static final int MOST_IN_AN_ITEM = 1_000_000;
    private static final String GOODS = "Goods";

    @Nullable private NonNullList<ItemStack> items;
    @Nullable private Component name;
    private int viewers;

    public StorehouseBlockEntity(BlockPos pos, BlockState state) {
        super(McAssistantMod.STOREHOUSE_BE.get(), pos, state);
    }

    private NonNullList<ItemStack> items() {
        if (items == null) {
            items = NonNullList.create();
            for (int i = 0; i < SIZE; i++) items.add(ItemStack.EMPTY);
        }
        return items;
    }

    /** How many rows it has now (it grows: see roomToSpare). */
    public int rows() {
        return items().size() / COLUMNS;
    }

    /**
     * Never full: whenever fewer than two rows stand empty, nine more are added. The storehouse
     * held seven hundred and twenty-nine stacks and a town of fifty filled it, and the harvest
     * had nowhere to go; a village's stores have no bottom now.
     */
    private void roomToSpare() {
        NonNullList<ItemStack> all = items();
        int empty = 0;
        for (ItemStack s : all) if (s.isEmpty()) empty++;
        while (empty < SPARE_ROWS * COLUMNS) {
            for (int i = 0; i < GROW_ROWS * COLUMNS; i++) all.add(ItemStack.EMPTY);
            empty += GROW_ROWS * COLUMNS;
        }
    }

    /** Is this a working store — the door of a whole cube — rather than goods waiting in a unit? */
    public boolean isStore() {
        return !isRemoved() && StorehouseBlock.isDoor(getBlockState());
    }

    public boolean hasGoods() {
        if (items == null) return false;
        for (ItemStack s : items) if (!s.isEmpty()) return true;
        return false;
    }

    /** How many of its slots hold something. */
    public int used() {
        if (items == null) return 0;
        int n = 0;
        for (ItemStack s : items) if (!s.isEmpty()) n++;
        return n;
    }

    /** Everything it holds, taken out. */
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) out.add(items.get(i));
            items.set(i, ItemStack.EMPTY);
        }
        setChanged();
        return out;
    }

    /** Put a stack in: onto stacks of the same first, then into empty slots. Returns what would not fit. */
    public ItemStack insert(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack left = stack.copy();
        NonNullList<ItemStack> all = items();
        for (int i = 0; i < all.size() && !left.isEmpty(); i++) {
            ItemStack s = all.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, left)) {
                int room = Math.min(s.getMaxStackSize(), getMaxStackSize()) - s.getCount();
                if (room <= 0) continue;
                int move = Math.min(room, left.getCount());
                s.grow(move);
                left.shrink(move);
            }
        }
        for (int i = 0; !left.isEmpty(); i++) {
            if (i >= all.size()) roomToSpare();                       // never full: it grows
            if (i >= all.size()) break;
            if (all.get(i).isEmpty()) {
                int move = Math.min(left.getCount(), Math.min(left.getMaxStackSize(), getMaxStackSize()));
                all.set(i, left.split(move));
            }
        }
        roomToSpare();
        setChanged();
        return left;
    }

    /**
     * Tidy it: like with like, stacks topped up, sorted by what they are. The storekeeper
     * does it while it is on duty (VillageFolkEntity.tidyTheStorehouse); anybody can with the button.
     */
    public void sort() {
        tidy();
    }

    /**
     * Tidy it, and say how it went: {slots used before, slots used after}. Like with like (the same
     * item with the same components: a maker's marked work and enchanted things keep to their own
     * stacks), every stack topped up to the full, and in order by kind — food, then crops and seed,
     * timber, stone and earth, ore and metal, cloth and hides, tools and arms, and the rest
     * (entity/Stacking.KINDS) — then by what it is, the fullest stack first.
     */
    public int[] tidy() {
        int before = used();
        List<ItemStack> all = takeAll();
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : all) {
            ItemStack left = s;
            for (ItemStack m : merged) {
                if (left.isEmpty()) break;
                int cap = Math.min(m.getMaxStackSize(), getMaxStackSize());
                if (ItemStack.isSameItemSameComponents(m, left) && m.getCount() < cap) {
                    int move = Math.min(cap - m.getCount(), left.getCount());
                    m.grow(move);
                    left.shrink(move);
                }
            }
            if (!left.isEmpty()) merged.add(left);
        }
        merged.sort(java.util.Comparator
            .comparingInt(com.jrpetty.mcassistant.entity.Stacking::kind)
            .thenComparing((ItemStack s) -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
            .thenComparing(s -> -s.getCount()));
        NonNullList<ItemStack> into = items();
        while (into.size() < merged.size()) into.add(ItemStack.EMPTY);
        for (int i = 0; i < merged.size(); i++) into.set(i, merged.get(i));
        roomToSpare();
        setChanged();
        return new int[]{ before, merged.size() };
    }

    // ------------------------------------------------------------------ Container

    @Override
    public int getContainerSize() {
        return items().size();
    }

    @Override
    public boolean isEmpty() {
        return !hasGoods();
    }

    @Override
    public ItemStack getItem(int slot) {
        return items == null || slot < 0 || slot >= items.size() ? ItemStack.EMPTY : items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (items == null) return ItemStack.EMPTY;
        ItemStack out = net.minecraft.world.ContainerHelper.removeItem(items, slot, amount);
        if (!out.isEmpty()) setChanged();
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (items == null) return ItemStack.EMPTY;
        return net.minecraft.world.ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0) return;
        while (items().size() <= slot) items().add(ItemStack.EMPTY);
        items().set(slot, stack);
        stack.limitSize(this.getMaxStackSize(stack));
        if (!stack.isEmpty()) roomToSpare();
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return isStore() && Container.stillValidBlockEntity(this, player, 6.0F);
    }

    @Override
    public void clearContent() {
        items = null;
        setChanged();
    }

    @Override
    public void startOpen(Player player) {
        if (player.isSpectator() || level == null) return;
        if (viewers++ == 0) {
            level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BARREL_OPEN,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.8F);
        }
    }

    @Override
    public void stopOpen(Player player) {
        if (player.isSpectator() || level == null) return;
        viewers = Math.max(0, viewers - 1);
        if (viewers == 0) {
            level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.BARREL_CLOSE,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.8F);
        }
    }

    // ------------------------------------------------------------------ name and screen

    @Override
    public Component getName() {
        return name != null ? name : Component.translatable("container.mc_assistant.storehouse");
    }

    @Nullable
    @Override
    public Component getCustomName() {
        return name;
    }

    public void setCustomName(@Nullable Component name) {
        this.name = name;
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return getName();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return isStore() ? new StorehouseMenu(id, inventory, this) : null;
    }

    // ------------------------------------------------------------------ the world

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide && isStore()) {
            com.jrpetty.mcassistant.entity.Storehouses.formed(level, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(GOODS, writeGoods(registries));
        if (name != null) tag.putString("CustomName", Component.Serializer.toJson(name, registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = null;
        readGoods(tag.getList(GOODS, Tag.TAG_COMPOUND), registries);
        name = tag.contains("CustomName", Tag.TAG_STRING)
            ? parseCustomNameSafe(tag.getString("CustomName"), registries) : null;
    }

    private ListTag writeGoods(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        if (items == null) return list;
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            CompoundTag one = new CompoundTag();
            one.putInt("Slot", i);
            list.add(s.save(registries, one));
        }
        return list;
    }

    private void readGoods(ListTag list, HolderLookup.Provider registries) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            int slot = one.getInt("Slot");                        // a short before the store grew
            if (slot < 0) continue;
            ItemStack.parse(registries, one).ifPresent(s -> {
                while (items().size() <= slot) items().add(ItemStack.EMPTY);
                items().set(slot, s);
            });
        }
        roomToSpare();
    }

    // ------------------------------------------------------------------ carried in a unit

    /** Does this unit item carry a store's goods? */
    public static boolean carriesGoods(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(GOODS);
    }

    /** How many stacks a unit item carries. */
    public static int stacksCarried(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(GOODS)) return 0;
        return data.copyTag().getList(GOODS, Tag.TAG_COMPOUND).size();
    }

    /** Put the goods into the unit item that is carrying them away. False if there is too much. */
    public boolean packInto(ItemStack stack, HolderLookup.Provider registries) {
        ListTag list = writeGoods(registries);
        if (list.sizeInBytes() > MOST_IN_AN_ITEM) return false;
        CompoundTag tag = new CompoundTag();
        tag.put(GOODS, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (name != null) stack.set(DataComponents.CUSTOM_NAME, name);
        return true;
    }

    /** Take in the goods a unit item carried (onto what is already here). */
    public void unpackFrom(ItemStack stack, HolderLookup.Provider registries) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(GOODS)) return;
        ListTag list = data.copyTag().getList(GOODS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack.parse(registries, list.getCompound(i)).ifPresent(s -> {
                ItemStack left = insert(s);
                if (!left.isEmpty() && level != null) {
                    net.minecraft.world.level.block.Block.popResource(level, worldPosition, left);
                }
            });
        }
        if (stack.has(DataComponents.CUSTOM_NAME)) name = stack.get(DataComponents.CUSTOM_NAME);
        setChanged();
    }
}
