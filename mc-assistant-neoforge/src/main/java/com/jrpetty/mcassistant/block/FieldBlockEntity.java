package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [fields] What a nesting box, a feed trough or a fish trap holds (nine eggs, three feeds, six fish), kept with the
 * block and spilled when it is broken; a hopper under a box takes its eggs, as a player would set one. A rain barrel
 * holds nothing here (its water is its state): it has one of these only to be found.
 *
 * <p>Every one in a loaded chunk is known by where it stands, by kind (the index), so the town's rounds never look over
 * the ground for them: a farmer wanting its barrel, a rancher its box, a fisher its traps asks the index, which holds a
 * handful at most.
 */
public class FieldBlockEntity extends BlockEntity implements Container {

    /** The four kinds, and what each holds. */
    public enum Kind {
        BOX(9), TROUGH(3), TRAP(6), BARREL(0);

        public final int size;

        Kind(int size) {
            this.size = size;
        }
    }

    private final Kind kind;
    private final NonNullList<ItemStack> items;
    /** A nesting box's hay: the eggs it will cushion before it wants fresh (none when the box is bare). */
    private int hay;

    public FieldBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, Kind kind) {
        super(type, pos, state);
        this.kind = kind;
        this.items = NonNullList.withSize(kind.size, ItemStack.EMPTY);
    }

    public Kind kind() {
        return kind;
    }

    public int hay() {
        return hay;
    }

    public void setHay(int n) {
        hay = Math.max(0, n);
        setChanged();
    }

    /** How many items it holds in all. */
    public int count() {
        int n = 0;
        for (ItemStack s : items) n += s.getCount();
        return n;
    }

    /** Put this in, onto its like first: what would not go. */
    public ItemStack insert(ItemStack in) {
        ItemStack left = in.copy();
        int cap = Math.min(left.getMaxStackSize(), getMaxStackSize());
        for (int i = 0; i < items.size() && !left.isEmpty(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, left)) continue;
            int move = Math.min(left.getCount(), cap - s.getCount());
            if (move <= 0) continue;
            s.grow(move);
            left.shrink(move);
        }
        for (int i = 0; i < items.size() && !left.isEmpty(); i++) {
            if (!items.get(i).isEmpty()) continue;
            int move = Math.min(left.getCount(), cap);
            items.set(i, left.copyWithCount(move));
            left.shrink(move);
        }
        setChanged();
        return left;
    }

    /** A trough's slot holds sixteen of a feed (so its level means something); the rest a stack. */
    @Override
    public int getMaxStackSize() {
        return kind == Kind.TROUGH ? 16 : 64;
    }

    /** Everything taken out. */
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) out.add(items.get(i));
            items.set(i, ItemStack.EMPTY);
        }
        setChanged();
        return out;
    }

    /** So many of the first slot's kind taken out (a trough's feed eaten): how many came. */
    public int use(java.util.function.Predicate<ItemStack> what, int n) {
        int took = 0;
        for (int i = 0; i < items.size() && took < n; i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int k = Math.min(n - took, s.getCount());
            s.shrink(k);
            took += k;
        }
        if (took > 0) setChanged();
        return took;
    }

    public int countOf(java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : items) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    // ------------------------------------------------------------------ the index

    private static final Map<Kind, Map<ResourceKey<Level>, Set<Long>>> INDEX = new EnumMap<>(Kind.class);

    static {
        for (Kind k : Kind.values()) INDEX.put(k, new ConcurrentHashMap<>());
    }

    private static Set<Long> set(Kind k, ResourceKey<Level> dim) {
        return INDEX.get(k).computeIfAbsent(dim, d -> ConcurrentHashMap.newKeySet());
    }

    /** Where every one of a kind stands in this level, so far as is loaded. */
    public static List<BlockPos> all(Level level, Kind k) {
        List<BlockPos> out = new ArrayList<>();
        Set<Long> s = INDEX.get(k).get(level.dimension());
        if (s != null) for (long p : s) out.add(BlockPos.of(p));
        return out;
    }

    /** Those of a kind within r blocks (square, and eight up or down) of here, the nearest first. */
    public static List<BlockPos> near(Level level, Kind k, BlockPos at, int r) {
        List<BlockPos> out = new ArrayList<>();
        Set<Long> s = INDEX.get(k).get(level.dimension());
        if (s == null) return out;
        for (long l : s) {
            BlockPos p = BlockPos.of(l);
            if (Math.abs(p.getX() - at.getX()) <= r && Math.abs(p.getZ() - at.getZ()) <= r && Math.abs(p.getY() - at.getY()) <= 8) out.add(p);
        }
        out.sort((a, b) -> Double.compare(a.distSqr(at), b.distSqr(at)));
        return out;
    }

    /** Tests: the index forgotten (a fresh ground). */
    public static void resetIndexForTests() {
        for (Map<ResourceKey<Level>, Set<Long>> m : INDEX.values()) m.clear();
    }

    /** Known by the index the moment it is in a level (placed, or its chunk loaded), and again on its first tick there. */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide) set(kind, level.dimension()).add(worldPosition.asLong());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide && !isRemoved()) set(kind, level.dimension()).add(worldPosition.asLong());
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) set(kind, level.dimension()).remove(worldPosition.asLong());
    }

    // ------------------------------------------------------------------ saved

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
        if (kind.size > 0) ContainerHelper.loadAllItems(tag, items, registries);
        hay = tag.getInt("Hay");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (kind.size > 0) ContainerHelper.saveAllItems(tag, items, registries);
        if (hay > 0) tag.putInt("Hay", hay);
    }

    // ------------------------------------------------------------------ a container (a hopper under it)

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int n) {
        ItemStack out = ContainerHelper.removeItem(items, slot, n);
        if (!out.isEmpty()) {
            setChanged();
            if (getBlockState().getBlock() instanceof FieldBlock fb && level != null) fb.refresh(level, worldPosition);
        }
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) return;
        items.set(slot, stack);
        setChanged();
        if (getBlockState().getBlock() instanceof FieldBlock fb && level != null && !level.isClientSide) fb.refresh(level, worldPosition);
    }

    /** Only what the block is for goes in: eggs in a box, feed in a trough; nothing is put into a trap or a barrel. */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return getBlockState().getBlock() instanceof FieldBlock fb && fb.accepts(stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
        setChanged();
    }
}
