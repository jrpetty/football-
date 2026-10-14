package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * [tidy] What a player built in or near a town: never the tidy crew's to take down, however much it looks like a
 * stray block or a pillar. Every block a player sets down within reach of a town is written down here (kept with
 * the world, a level at a time), and forgotten again when a player breaks it.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerBlocks extends SavedData {

    private static final String ID = "mc_assistant_player_blocks";
    /** How far from a town's heart a player's blocks are written down. */
    static final int NEAR = 160;
    /** The most kept for a level (the oldest forgotten first): a player who builds a castle by a town is still minded. */
    static final int MOST = 200_000;

    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet placed = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();

    private static PlayerBlocks of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(PlayerBlocks::new, PlayerBlocks::load, null), ID);
    }

    /** Did a player put this block here? */
    public static boolean placed(ServerLevel level, BlockPos pos) {
        PlayerBlocks p = of(level);
        return !p.placed.isEmpty() && p.placed.contains(pos.asLong());
    }

    /** Tests: as though a player had set a block down here (true) or broken it (false). */
    public static void markForTests(ServerLevel level, BlockPos pos, boolean on) {
        PlayerBlocks p = of(level);
        if (on) p.placed.add(pos.asLong()); else p.placed.remove(pos.asLong());
        p.setDirty();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.isCanceled() || !(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof Player)) return;
        if (Villages.nearest(level, e.getPos(), NEAR) == null) return;
        PlayerBlocks p = of(level);
        if (p.placed.add(e.getPos().asLong())) {
            while (p.placed.size() > MOST) p.placed.removeFirstLong();
            p.setDirty();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (e.isCanceled() || !(e.getLevel() instanceof ServerLevel level)) return;
        PlayerBlocks p = of(level);
        if (!p.placed.isEmpty() && p.placed.remove(e.getPos().asLong())) p.setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("placed", p().toLongArray());
        return tag;
    }

    private it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet p() {
        return placed;
    }

    private static PlayerBlocks load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerBlocks p = new PlayerBlocks();
        for (long l : tag.getLongArray("placed")) p.placed.add(l);
        return p;
    }
}
