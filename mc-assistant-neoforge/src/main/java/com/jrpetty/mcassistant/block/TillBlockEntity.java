package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * [till] A till's binding and its little sign: which business it rings up for (its town, and "town:shop", "town:cafe",
 * "pshop:&lt;key&gt;" or "firm:&lt;id&gt;"), who set it, whether its owner lets customers serve themselves when nobody is
 * behind it, which wares a kiosk sells, and the line floating over it ("Serving: Hazel"). Drawn by client/TillRenderer.
 */
public class TillBlockEntity extends BlockEntity {

    @Nullable private UUID village, owner;
    private String key = "", name = "", status = "", serving = "";
    private long servingUntil;
    private boolean selfServe = true, open;
    private final Set<String> kioskWares = new LinkedHashSet<>();
    /** A kiosk's cubbies: the wares it shows (three at most), sent to the client to draw. */
    private final java.util.List<net.minecraft.world.item.ItemStack> shown = new java.util.ArrayList<>();

    public TillBlockEntity(BlockPos pos, BlockState state) {
        super(com.jrpetty.mcassistant.item.TillItems.TILL_BE.get(), pos, state);
    }

    @Nullable public UUID village() { return village; }
    @Nullable public UUID owner() { return owner; }
    public String key() { return key; }
    public String name() { return name; }
    public String status() { return status; }
    public String serving() { return serving; }
    public long servingUntil() { return servingUntil; }
    public boolean open() { return open; }
    public boolean selfServe() { return selfServe; }
    public Set<String> kioskWares() { return kioskWares; }
    public java.util.List<net.minecraft.world.item.ItemStack> shown() { return shown; }

    /** The wares in a kiosk's cubbies (the first few it sells that are in stock). */
    public void showWares(java.util.List<net.minecraft.world.item.ItemStack> wares) {
        boolean same = wares.size() == shown.size();
        for (int i = 0; same && i < wares.size(); i++) same = net.minecraft.world.item.ItemStack.isSameItemSameComponents(wares.get(i), shown.get(i));
        if (same) return;
        shown.clear();
        for (net.minecraft.world.item.ItemStack s : wares) shown.add(s.copyWithCount(1));
        changed();
    }

    public boolean bound() {
        return village != null && !key.isEmpty();
    }

    public boolean kiosk() {
        return getBlockState().getBlock() instanceof TillBlock t && t.kiosk;
    }

    public void bind(@Nullable UUID village, String key, @Nullable UUID owner) {
        this.village = village;
        this.key = key == null ? "" : key;
        this.owner = owner;
        changed();
    }

    public void setSelfServe(boolean on) {
        selfServe = on;
        changed();
    }

    public void toggleKioskWare(String id) {
        if (!kioskWares.remove(id)) kioskWares.add(id);
        changed();
    }

    /** Its sign: the business's name and how it stands. */
    public void show(String name, String status, boolean open) {
        if (name.equals(this.name) && status.equals(this.status) && open == this.open) return;
        this.name = name;
        this.status = status;
        this.open = open;
        changed();
    }

    /** "Serving: Hazel" over it for a few seconds. */
    public void serve(String who, long until) {
        serving = who;
        servingUntil = until;
        changed();
    }

    private void changed() {
        setChanged();
        if (level instanceof ServerLevel server) server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        village = tag.hasUUID("Village") ? tag.getUUID("Village") : null;
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        key = tag.getString("Key");
        name = tag.getString("Name");
        status = tag.getString("Status");
        serving = tag.getString("Serving");
        servingUntil = tag.getLong("ServingUntil");
        selfServe = !tag.contains("SelfServe") || tag.getBoolean("SelfServe");
        open = tag.getBoolean("Open");
        kioskWares.clear();
        ListTag l = tag.getList("KioskWares", Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) kioskWares.add(l.getString(i));
        shown.clear();
        ListTag sl = tag.getList("Shown", Tag.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) {
            net.minecraft.world.item.ItemStack s = net.minecraft.world.item.ItemStack.parseOptional(registries, sl.getCompound(i));
            if (!s.isEmpty()) shown.add(s);
        }
    }

    private void write(CompoundTag tag, HolderLookup.Provider registries) {
        if (village != null) tag.putUUID("Village", village);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("Key", key);
        tag.putString("Name", name);
        tag.putString("Status", status);
        tag.putString("Serving", serving);
        tag.putLong("ServingUntil", servingUntil);
        tag.putBoolean("SelfServe", selfServe);
        tag.putBoolean("Open", open);
        ListTag l = new ListTag();
        for (String s : kioskWares) l.add(StringTag.valueOf(s));
        tag.put("KioskWares", l);
        ListTag sl = new ListTag();
        for (net.minecraft.world.item.ItemStack s : shown) if (!s.isEmpty()) sl.add(s.save(registries));
        tag.put("Shown", sl);
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
        if (level instanceof ServerLevel && bound()) com.jrpetty.mcassistant.entity.Tills.known(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) com.jrpetty.mcassistant.entity.Tills.forget(worldPosition);
    }
}
