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
    /**
     * Put up by a Village Folk Spawner for a village not founded yet (entity/Founding): waiting for
     * somebody to say how many folk are to start it, or with the ground being made ready for them.
     * Kept with the board, so a board left waiting is still waiting after a restart.
     */
    private int founding;
    @Nullable private BlockPos heart;
    private int count;

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

    /** 0, or Founding.PENDING while the board waits for its founders to be chosen, or Founding.UNDER_WAY. */
    public int founding() {
        return founding;
    }

    /** Where the village this board waits for will have its heart (null if it waits for none). */
    @Nullable
    public BlockPos foundingHeart() {
        return heart;
    }

    /** How many are coming, once they have been chosen. */
    public int foundingCount() {
        return count;
    }

    /** This board waits for a village to be founded with its heart here. */
    public void waitFor(BlockPos heartAt) {
        founding = com.jrpetty.mcassistant.entity.Founding.PENDING;
        heart = heartAt.immutable();
        raised = true;
        village = null;
        setChanged();
        if (level instanceof ServerLevel server) refresh(server);
    }

    /** They have been chosen, and the ground is being made ready for them. */
    public void underWay(int folk) {
        founding = com.jrpetty.mcassistant.entity.Founding.UNDER_WAY;
        count = folk;
        setChanged();
        if (level instanceof ServerLevel server) refresh(server);
    }

    /** Waiting for nothing any more. */
    public void clearFounding() {
        founding = 0;
        heart = null;
        count = 0;
        setChanged();
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
        if (founding != 0 && !com.jrpetty.mcassistant.entity.Founding.stillFounding(server, this)) clearFounding();
        if (founding != 0) {
            // A board waiting for its village says what is to happen, and how the ground is coming on.
            List<String> now = com.jrpetty.mcassistant.entity.Founding.boardLines(server, this);
            if (now.equals(lines)) return;
            lines = now;
            setChanged();
            server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return;
        }
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
        // Waiting for its founders: the screen that asks how many.
        if (founding == com.jrpetty.mcassistant.entity.Founding.PENDING) {
            com.jrpetty.mcassistant.entity.Founding.offer(player, this, 0);
            return;
        }
        // The town's books, in full (the analytics screen, its Board page the board's own words).
        if (level instanceof ServerLevel server && village != null) {
            com.jrpetty.mcassistant.entity.Villages.Village v = com.jrpetty.mcassistant.entity.Villages.get(village);
            if (v != null) {
                com.jrpetty.mcassistant.net.AssistantNetwork.sendCityStats(player, server, v);
                return;
            }
        }
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
        founding = tag.getInt("Founding");
        heart = tag.contains("Heart") ? BlockPos.of(tag.getLong("Heart")) : null;
        count = tag.getInt("Count");
        List<String> read = new ArrayList<>();
        ListTag list = tag.getList("Lines", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) read.add(list.getString(i));
        lines = read;
    }

    private void write(CompoundTag tag) {
        if (village != null) tag.putUUID("Village", village);
        if (raised) tag.putBoolean("Raised", true);
        if (founding != 0) {
            tag.putInt("Founding", founding);
            if (heart != null) tag.putLong("Heart", heart.asLong());
            tag.putInt("Count", count);
        }
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
