package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The old chests, and clearing them out. Before the Village Storehouse a village kept its
 * goods wherever its folk set a chest down — one on every plot, more at the heart as they
 * filled, more in every building. Once the storehouse stands, every one of them is emptied
 * into it (goal/RetireGoal): a chest standing on its own is taken up and carried home too,
 * and one that is a building's furniture is left as furniture, no longer the village's.
 * The chests in a house the village built for a player, and any with a sign on, are left
 * alone.
 */
public final class Retiring {

    private Retiring() {}

    /** Who is clearing which chest, and until when (so two hands do not walk to the same one). */
    private static final Map<Long, long[]> CLAIMED = new ConcurrentHashMap<>();
    /** Chests nobody could get to, and until when they are left alone. */
    private static final Map<Long, Long> UNREACHABLE = new ConcurrentHashMap<>();
    /** Each village's old chests, looked for once a minute. */
    private static final Map<UUID, List<BlockPos>> FOUND = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    /** A chest or barrel of the village's own, not the storehouse, not a player's. */
    public static boolean retirable(Level level, BlockPos pos, @Nullable BlockEntity be) {
        if (be == null || be instanceof StorehouseBlockEntity || !(be instanceof Container)) return false;
        if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) return false;
        return ZoneChests.isVillageStore(be) && !ZoneChests.isPrivate(level, pos);
    }

    /**
     * The nearest old chest for this hand to clear, or null: only once the village's
     * storehouse stands, is loaded, and has room for a chest's worth more.
     */
    @Nullable
    public static BlockPos next(VillageFolkEntity f, ServerLevel level, UUID village) {
        StorehouseBlockEntity store = Storehouses.storeFor(level, village);
        if (store == null) return null;                     // the storehouse always has room: it grows
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        long now = level.getGameTime();
        List<BlockPos> chests = FOUND.get(village);
        Long looked = LOOKED.get(village);
        if (chests == null || looked == null || now - looked > 1200L || now < looked) {
            chests = new ArrayList<>();
            int radius = Math.min(112, Math.max(48, Villages.storesRadius(village)));
            boolean before = ZoneChests.askAs(true);
            java.util.Set<Long> production = VillageFolkEntity.productionChests(village);
            try {
                for (ZoneChests.Found found : ZoneChests.around(level, v.centre(), radius, 32)) {
                    if (!found.stillThere() || !retirable(level, found.pos(), found.blockEntity())) continue;
                    if (Villages.inAGuestHouse(village, found.pos())) continue;
                    // A worker's production chest is where its output waits for the couriers, not an old chest.
                    if (production.contains(found.pos().asLong())) continue;
                    chests.add(found.pos().immutable());
                }
            } finally {
                ZoneChests.askAs(before);
            }
            FOUND.put(village, chests);
            LOOKED.put(village, now);
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : chests) {
            long key = p.asLong();
            long[] claim = CLAIMED.get(key);
            if (claim != null && now < claim[1] && claim[0] != f.getUUID().getLeastSignificantBits()) continue;
            Long away = UNREACHABLE.get(key);
            if (away != null && now < away) continue;
            if (!level.isLoaded(p) || !retirable(level, p, level.getBlockEntity(p))) continue;
            double d = p.distSqr(f.blockPosition());
            if (d < bestDist) { bestDist = d; best = p; }
        }
        if (best != null) {
            CLAIMED.put(best.asLong(), new long[]{ f.getUUID().getLeastSignificantBits(), now + 2400L });
            chests.remove(best);
        }
        return best;
    }

    public static void release(BlockPos chest) {
        CLAIMED.remove(chest.asLong());
    }

    /** Nobody could get to it: left alone for five minutes. */
    public static void unreachable(Level level, BlockPos chest) {
        UNREACHABLE.put(chest.asLong(), level.getGameTime() + 6000L);
        CLAIMED.remove(chest.asLong());
    }

    /** Part of a building the village raised (a house's chest, a stall's barrels): furniture. */
    public static boolean isFurniture(AssistantEntity a, BlockPos pos) {
        UUID village = a.ownerId();
        if (village == null) return false;
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(village)) {
            int[] half = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(b.structure());
            boolean turned = b.facing().getAxis() == Direction.Axis.X;
            int hx = turned ? half[1] : half[0], hz = turned ? half[0] : half[1];
            int dy = pos.getY() - b.anchor().getY();
            if (Math.abs(pos.getX() - b.anchor().getX()) <= hx && Math.abs(pos.getZ() - b.anchor().getZ()) <= hz
                    && dy >= -2 && dy <= 16) {
                return true;
            }
        }
        return false;
    }

    /** No longer one of the village's stores: its name taken off. */
    public static void unmark(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        be.applyComponents(net.minecraft.core.component.DataComponentMap.EMPTY,
            net.minecraft.core.component.DataComponentPatch.EMPTY);
        be.setChanged();
    }

    public static void resetForTests() {
        CLAIMED.clear();
        UNREACHABLE.clear();
        FOUND.clear();
        LOOKED.clear();
    }
}
