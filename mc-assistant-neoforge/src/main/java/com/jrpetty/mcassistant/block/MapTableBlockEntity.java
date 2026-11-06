package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [borders-map] Whose map is on the table: the town whose knowledge it is drawn from (null: a traveller's table, drawn
 * only from the country round it). Set when the town (or a player) puts it down; kept with the world.
 */
public class MapTableBlockEntity extends BlockEntity {

    @Nullable private UUID town;

    public MapTableBlockEntity(BlockPos pos, BlockState state) {
        super(com.jrpetty.mcassistant.item.BorderItems.MAP_TABLE_BE.get(), pos, state);
    }

    @Nullable
    public UUID town() {
        return town;
    }

    public void bind(@Nullable UUID town) {
        this.town = town;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (town != null) tag.putUUID("Town", town);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        town = tag.hasUUID("Town") ? tag.getUUID("Town") : null;
    }
}
