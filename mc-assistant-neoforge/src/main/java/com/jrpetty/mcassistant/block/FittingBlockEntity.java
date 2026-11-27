package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [till] A fitting's five slots (what is on show, and on sale: part of its business's stock), its binding (the business,
 * as a till's), and for a price board the lines it shows. Sent to the client whenever what is on it changes, so the
 * wares drawn on it (client/FittingRenderer) are the ones really there.
 */
public class FittingBlockEntity extends BlockEntity implements Container {

    public static final int SLOTS = 5;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    @Nullable private UUID village, owner;
    private String key = "";
    private final List<String> board = new ArrayList<>();
    private long lastSent;

    public FittingBlockEntity(BlockPos pos, BlockState state) {
        super(com.jrpetty.mcassistant.item.TillItems.FITTING_BE.get(), pos, state);
    }

    public FittingBlock.Kind kind() {
        return getBlockState().getBlock() instanceof FittingBlock f ? f.kind : FittingBlock.Kind.SHELF;
    }

    public boolean holdsStock() {
        return kind().holds;
    }

    @Nullable public UUID village() { return village; }
    @Nullable public UUID owner() { return owner; }
    public String key() { return key; }
    public List<String> board() { return board; }

    public boolean bound() {
        return village != null && !key.isEmpty();
    }

    public void bind(@Nullable UUID village, String key, @Nullable UUID owner) {
        this.village = village;
        this.key = key == null ? "" : key;
        this.owner = owner;
        sync();
    }

    public void setBoard(List<String> lines) {
        if (lines.equals(board)) return;
        board.clear();
        board.addAll(lines);
        sync();
    }

    /** How full it is, nought to one (a price board or a basket stand: always "full"). */
    public float fullness() {
        if (!holdsStock()) return 1F;
        int used = 0;
        for (ItemStack s : items) if (!s.isEmpty()) used++;
        return used / (float) SLOTS;
    }

    public List<ItemStack> shown() {
        return items;
    }

    // ------------------------------------------------------------------ Container

    @Override
    public int getContainerSize() {
        return holdsStock() ? SLOTS : 0;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SLOTS ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int n) {
        ItemStack s = ContainerHelper.removeItem(items, slot, n);
        if (!s.isEmpty()) setChanged();
        return s;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOTS) return;
        items.set(slot, stack);
        stack.limitSize(getMaxStackSize(stack));
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        sync();
    }

    private void sync() {
        if (level instanceof ServerLevel server) {
            long now = server.getGameTime();
            lastSent = now;
            server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ------------------------------------------------------------------ saving and sending

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        village = tag.hasUUID("Village") ? tag.getUUID("Village") : null;
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        key = tag.getString("Key");
        board.clear();
        ListTag l = tag.getList("Board", Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) board.add(l.getString(i));
    }

    private void write(CompoundTag tag, HolderLookup.Provider registries) {
        ContainerHelper.saveAllItems(tag, items, true, registries);
        if (village != null) tag.putUUID("Village", village);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("Key", key);
        ListTag l = new ListTag();
        for (String s : board) l.add(StringTag.valueOf(s));
        tag.put("Board", l);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        write(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel && bound()) com.jrpetty.mcassistant.entity.Fittings.known(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) com.jrpetty.mcassistant.entity.Fittings.forget(worldPosition);
    }
}
