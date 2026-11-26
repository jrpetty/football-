package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [wpb] A Business Core's binding and its sign: which business it is the hub of (its town, and "firm:", "farm:" or "shop:"
 * and the business's id), and what floats over it for anyone passing (its name, open or shut, who's in charge when it
 * isn't the owner). Kept fresh by entity/BusinessCore while players are near; drawn by client/BusinessCoreRenderer.
 */
public class BusinessCoreBlockEntity extends BlockEntity {

    @Nullable private UUID village;
    private String key = "", name = "", status = "", note = "";
    private boolean open;

    public BusinessCoreBlockEntity(BlockPos pos, BlockState state) {
        super(com.jrpetty.mcassistant.item.CoreItems.CORE_BE.get(), pos, state);
    }

    @Nullable
    public UUID village() {
        return village;
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public String status() {
        return status;
    }

    public String note() {
        return note;
    }

    public boolean open() {
        return open;
    }

    public boolean bound() {
        return village != null && !key.isEmpty();
    }

    public void bind(@Nullable UUID village, String key) {
        this.village = village;
        this.key = key == null ? "" : key;
        setChanged();
    }

    /** Its sign, sent to whoever can see it when it changes. */
    public void show(String name, String status, String note, boolean open) {
        if (name.equals(this.name) && status.equals(this.status) && note.equals(this.note) && open == this.open) return;
        this.name = name;
        this.status = status;
        this.note = note;
        this.open = open;
        setChanged();
        if (level instanceof ServerLevel server) server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        village = tag.hasUUID("Village") ? tag.getUUID("Village") : null;
        key = tag.getString("Key");
        name = tag.getString("Name");
        status = tag.getString("Status");
        note = tag.getString("Note");
        open = tag.getBoolean("Open");
    }

    private void write(CompoundTag tag) {
        if (village != null) tag.putUUID("Village", village);
        tag.putString("Key", key);
        tag.putString("Name", name);
        tag.putString("Status", status);
        tag.putString("Note", note);
        tag.putBoolean("Open", open);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        write(tag);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
