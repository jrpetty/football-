package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [workitems] What a milestone says (MilestoneBlock): the towns its road leads to, each with its heart, the way along the
 * road toward it from here, and how far it is by the road. Set by the road crew when it sets the stone (entity/Milestones);
 * drawn on the stone's faces like a sign's lettering (client/WorkItemsClient), and told to a player who asks.
 */
public class MilestoneBlockEntity extends BlockEntity {

    /** A town the road leads to: its name, its heart, the road's way toward it from the stone, and the miles by the road. */
    public record Way(String name, @Nullable UUID village, int x, int z, int dx, int dz, int far) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Name", name);
            if (village != null) t.putUUID("Village", village);
            t.putInt("X", x);
            t.putInt("Z", z);
            t.putInt("DX", dx);
            t.putInt("DZ", dz);
            t.putInt("Far", far);
            return t;
        }

        static Way load(CompoundTag t) {
            return new Way(t.getString("Name"), t.hasUUID("Village") ? t.getUUID("Village") : null, t.getInt("X"), t.getInt("Z"),
                t.getInt("DX"), t.getInt("DZ"), t.getInt("Far"));
        }
    }

    private List<Way> ways = List.of();

    public MilestoneBlockEntity(BlockPos pos, BlockState state) {
        super(com.jrpetty.mcassistant.item.WorkItems.MILESTONE_BE.get(), pos, state);
    }

    public List<Way> ways() {
        return ways;
    }

    /** What the stone says, set once it stands; sent to whoever can see it. */
    public void setWays(List<Way> to) {
        ways = List.copyOf(to);
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
        List<Way> read = new ArrayList<>();
        ListTag list = tag.getList("Ways", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) read.add(Way.load(list.getCompound(i)));
        ways = read;
    }

    private void write(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Way w : ways) list.add(w.save());
        tag.put("Ways", list);
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
