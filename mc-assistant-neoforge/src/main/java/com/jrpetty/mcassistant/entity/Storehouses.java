package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the Village Storehouses stand: the doors of every joined store the world has
 * loaded (block/StorehouseBlock). A village's storehouse is the one by its heart — on
 * its square or in the buildings that face it.
 *
 * <p>A store whose ground is not loaded is still known, so a village whose heart has
 * gone out of loading does not think it has lost its storehouse and set about another.
 */
public final class Storehouses {

    private Storehouses() {}

    /** How far from a village's heart its storehouse may stand. */
    public static final int REACH = Villages.STORE_AREA + 8;

    private record Door(ResourceKey<Level> dim, BlockPos pos) {}

    private static final Set<Door> DOORS = ConcurrentHashMap.newKeySet();

    public static void formed(Level level, BlockPos door) {
        DOORS.add(new Door(level.dimension(), door.immutable()));
    }

    public static void gone(Level level, BlockPos door) {
        DOORS.remove(new Door(level.dimension(), door.immutable()));
    }

    private static boolean near(BlockPos centre, BlockPos p) {
        return Math.max(Math.abs(p.getX() - centre.getX()), Math.abs(p.getZ() - centre.getZ())) <= REACH
            && Math.abs(p.getY() - centre.getY()) <= 24;
    }

    /** The door of the village's storehouse (the one nearest its heart), or null if it has none. */
    @Nullable
    public static BlockPos doorFor(Level level, @Nullable UUID villageId) {
        Villages.Village v = Villages.get(villageId);
        if (v == null || !v.dim().equals(level.dimension())) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Door d : DOORS) {
            if (!d.dim().equals(v.dim()) || !near(v.centre(), d.pos())) continue;
            if (level.isLoaded(d.pos())
                    && !(level.getBlockEntity(d.pos()) instanceof StorehouseBlockEntity s && s.isStore())) {
                DOORS.remove(d);                               // gone since, and nobody said
                continue;
            }
            double dist = d.pos().distSqr(v.centre());
            if (dist < bestDist) { bestDist = dist; best = d.pos(); }
        }
        return best;
    }

    /** The village's storehouse, if it stands and its ground is loaded. */
    @Nullable
    public static StorehouseBlockEntity storeFor(Level level, @Nullable UUID villageId) {
        BlockPos d = doorFor(level, villageId);
        if (d == null || !level.isLoaded(d)) return null;
        return level.getBlockEntity(d) instanceof StorehouseBlockEntity s && s.isStore() ? s : null;
    }

    /** Does the village have a storehouse standing, as far as anybody knows? */
    public static boolean stands(@Nullable UUID villageId) {
        Villages.Village v = Villages.get(villageId);
        if (v == null) return false;
        for (Door d : DOORS) {
            if (d.dim().equals(v.dim()) && near(v.centre(), d.pos())) return true;
        }
        return false;
    }

    /** Where to stand to use the village's storehouse, or null. */
    @Nullable
    public static BlockPos standingSpot(Level level, @Nullable UUID villageId) {
        BlockPos d = doorFor(level, villageId);
        if (d == null || !level.isLoaded(d)) return d;
        return com.jrpetty.mcassistant.block.StorehouseBlock.standingSpot(d, level.getBlockState(d));
    }

    public static void resetForTests() {
        DOORS.clear();
    }
}
