package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [pa36] How the storage map learns: a container its player places, or opens, is theirs and known (with what it held
 * when they closed it); one broken is forgotten; who placed each container is kept, so another player's is never
 * used. Only for players who have an assistant; nothing here changes anything for anyone else.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PaLogiEvents {

    private PaLogiEvents() {}

    /** The container each player has open (to look in when they close it). */
    private static final Map<UUID, BlockPos> OPEN = new ConcurrentHashMap<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void placed(BlockEvent.EntityPlaceEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof Level level) || level.isClientSide) return;
        BlockPos pos = e.getPos();
        if (!PaContainers.is(level, pos)) return;
        String dim = level.dimension().location().toString();
        PaStore.setPlacedBy(dim, pos, p.getUUID());
        if (PaData.first(p.getUUID()) != null) PaStore.learn(p.getUUID(), dim, pos, PaContainers.blockId(level, pos), level.getGameTime());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void opened(PlayerInteractEvent.RightClickBlock e) {
        if (PaContainers.askingClaims() || e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || p.level().isClientSide) return;
        if (p.isShiftKeyDown() && !p.getMainHandItem().isEmpty()) return;          // placing against it, not opening it
        Level level = p.level();
        BlockPos pos = e.getPos();
        if (!PaContainers.is(level, pos) || PaData.first(p.getUUID()) == null) return;
        String dim = level.dimension().location().toString();
        UUID placer = PaStore.placedBy(dim, pos);
        if (placer != null && !placer.equals(p.getUUID())) return;               // someone else's: opening it doesn't make it theirs
        if (e.getLevel().getBlockEntity(pos) != null && com.jrpetty.mcassistant.entity.ZoneChests.isVillageStore(e.getLevel().getBlockEntity(pos))) return;
        PaStore.learn(p.getUUID(), dim, pos, PaContainers.blockId(level, pos), level.getGameTime());
        PaLogistics.seen(p, pos);
        OPEN.put(p.getUUID(), pos.immutable());
    }

    @SubscribeEvent
    public static void closed(PlayerContainerEvent.Close e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        BlockPos pos = OPEN.remove(p.getUUID());
        if (pos == null || p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 10 * 10) return;
        Level level = p.level();
        if (!level.isLoaded(pos)) return;
        Container c = containerOf(level, pos, p);
        if (c == null) return;
        String dim = level.dimension().location().toString();
        PaStore.Known k = PaStore.learn(p.getUUID(), dim, pos, PaContainers.blockId(level, pos), level.getGameTime());
        k.snap.clear();
        int i = 0;
        for (Map.Entry<String, Integer> en : PaContainers.contents(c).entrySet()) { if (i++ >= 12) break; k.snap.put(en.getKey(), en.getValue()); }
        k.size = c.getContainerSize();
        k.used = c.getContainerSize() - PaContainers.freeSlots(c);
        PaStore.touch();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void broken(BlockEvent.BreakEvent e) {
        if (e.isCanceled() || !(e.getLevel() instanceof Level level) || level.isClientSide) return;
        if (!PaContainers.is(level, e.getPos())) return;
        PaStore.gone(level.dimension().location().toString(), e.getPos());
    }

    static Container containerOf(Level level, BlockPos pos, ServerPlayer p) {
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock) return p.getEnderChestInventory();
        if (s.getBlock() instanceof ChestBlock cb) {
            Container c = ChestBlock.getContainer(cb, s, level, pos, true);
            if (c != null) return c;
        }
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }
}
