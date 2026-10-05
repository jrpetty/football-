package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the Village Board says. Kept at its bottom-left panel, written afresh every few
 * seconds from how the village stands (entity/VillageBoards.compose), and sent to everybody
 * near enough to read it whenever it changes.
 *
 * <p>Each line is a code and its words: where it goes (T the name, S under it, L the left
 * column, R the right, F along the foot) and how it looks (H a heading, N plain, G good news,
 * W a worry, B trouble, M a quiet note), as "LH|What we're doing".
 */
public class VillageBoardBlockEntity extends BlockEntity {

    /** How often the writing is brought up to date. */
    private static final int EVERY = 100;

    @Nullable private UUID village;
    private List<String> lines = new ArrayList<>();
    /** Put up by a village at its founding (not made by a player): taking it down gives nothing back. */
    private boolean raised;

    public VillageBoardBlockEntity(BlockPos pos, BlockState state) {
        super(McAssistantMod.VILLAGE_BOARD_BE.get(), pos, state);
    }

    public List<String> lines() {
        return lines;
    }

    public boolean raised() {
        return raised;
    }

    public void markRaised() {
        raised = true;
        setChanged();
    }

    @Nullable
    public UUID village() {
        return village;
    }

    /** The board belongs to this village (or, given none, to the nearest). */
    public void adopt(@Nullable UUID id) {
        this.village = id;
        if (level instanceof ServerLevel server) refresh(server);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, VillageBoardBlockEntity board) {
        if (!(level instanceof ServerLevel server)) return;
        if ((server.getGameTime() + (pos.asLong() & 63)) % EVERY != 0) return;
        board.refresh(server);
    }

    private void refresh(ServerLevel server) {
        if (village == null || com.jrpetty.mcassistant.entity.Villages.get(village) == null) {
            com.jrpetty.mcassistant.entity.Villages.Village v = com.jrpetty.mcassistant.entity.Villages.nearest(server,
                worldPosition, com.jrpetty.mcassistant.entity.Villages.VILLAGE_RANGE * 2);
            village = v == null ? null : v.id();
        }
        com.jrpetty.mcassistant.entity.VillageBoards.known(server, worldPosition, village);
        List<String> now = com.jrpetty.mcassistant.entity.VillageBoards.compose(server, village);
        if (now.equals(lines)) return;
        lines = now;
        setChanged();
        server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Close up: the whole of it in the village journal. */
    public void showTo(ServerPlayer player) {
        if (level instanceof ServerLevel server) refresh(server);
        String[] page = com.jrpetty.mcassistant.entity.VillageBoards.page(lines);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new com.jrpetty.mcassistant.net.VillagePagePayload(page[0], page[1]));
    }

    // ------------------------------------------------------------------ saving and sending

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        village = tag.hasUUID("Village") ? tag.getUUID("Village") : null;
        raised = tag.getBoolean("Raised");
        List<String> read = new ArrayList<>();
        ListTag list = tag.getList("Lines", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) read.add(list.getString(i));
        lines = read;
    }

    private void write(CompoundTag tag) {
        if (village != null) tag.putUUID("Village", village);
        if (raised) tag.putBoolean("Raised", true);
        ListTag list = new ListTag();
        for (String l : lines) list.add(StringTag.valueOf(l));
        tag.put("Lines", list);
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

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) com.jrpetty.mcassistant.entity.VillageBoards.gone(server, worldPosition);
    }
}
