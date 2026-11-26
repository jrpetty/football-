package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [fixes14] A field where the farmer can walk to it, when the town's farmland has none.
 *
 * <p>A mountain town marks out its farmland on the side with the best ground; on a mountain the best ground lay on a
 * tableland up a sheer face from the town, and its squares were let in because a column a few blocks off them could be
 * walked to. A field's square now wants its middle and most of its ground walked to (Reach.fieldReaches). And where the
 * farmland's side has nothing the farmer can walk to, the field does not go wherever there is soil (it went up the
 * cliff, or across the tarn, and nobody tilled it): it goes on another side of the town, on the farmland's own squares
 * there, or failing those on ground just out past the town the farmer walks to, out of the way of its lots and buildings. On a
 * slope the furrows follow the ground a block up or down at a time (the walk's own step), a terrace to each height,
 * walked up and down from one to the next.
 */
public final class Fields14 {

    private Fields14() {}

    /** Another side's squares, then ground by the town, the farmer walks to; null if none (the farmer waits). */
    @Nullable
    static BlockPos fallback(VillageFolkEntity f, BlockPos heart, Reach walk, Predicate<BlockPos> near) {
        UUID town = f.ownerId();
        if (town == null) return null;
        int half = VillageFolkEntity.FIELD_MOST / 2;
        int mine = Villages.fieldsSide(town);
        for (int side = 0; side < 4; side++) {
            if (side == mine) continue;
            for (int[] sq : Villages.fieldSquares(side)) {
                BlockPos p = f.surfaceAt(heart.getX() + sq[0], heart.getZ() + sq[1]);
                if (p == null || Math.abs(p.getY() - heart.getY()) > VillageFolkEntity.FIELD_CLIMB) continue;
                if (LandUse.inGrowthRoom(town, heart, p, VillageFolkEntity.FIELD_MOST)) continue;   // [econ51] not in the town's room to grow
                if (!walk.fieldReaches(p, half)) continue;
                if (Villages.builtOver(town, sq[0], sq[1], VillageFolkEntity.FIELD_MOST, VillageFolkEntity.FIELD_MOST)) continue;
                if (VillageFolkEntity.buildingNearFor14(town, p, VillageFolkEntity.FIELD_MOST + 4)) continue;
                if (MineGround.anyMeets(p.getX() - VillageFolkEntity.FIELD_MOST - 1, p.getZ() - VillageFolkEntity.FIELD_MOST - 1,
                        p.getX() + VillageFolkEntity.FIELD_MOST + 1, p.getZ() + VillageFolkEntity.FIELD_MOST + 1)) continue;
                if (Bonds.overBorder(town, heart, p, VillageFolkEntity.FIELD_MOST)) continue;
                if (f.fieldGroundFor14(p, VillageFolkEntity.FIELD_MOST)) return p;
            }
        }
        // Ground by the town: rings out from just past the town's edge, every fourth column, a small field's room clear.
        int small = VillageFolkEntity.FIELD_FIRST;
        int from = LandUse.growthReach(town) + small + 2;                    // [econ51] past the growth line
        for (int r = from; r <= from + 48; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) {
                for (int dz = -r; dz <= r; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = f.surfaceAt(heart.getX() + dx, heart.getZ() + dz);
                    if (p == null || Math.abs(p.getY() - heart.getY()) > VillageFolkEntity.FIELD_CLIMB) continue;
                    if (!walk.fieldReaches(p, small + 1)) continue;
                    if (!Villages.outsideTown(town, heart, p, small)) continue;          // out of the town's own ground
                    if (Villages.builtOver(town, dx, dz, small + 2, small + 2)) continue;
                    if (VillageFolkEntity.buildingNearFor14(town, p, small + 6)) continue;
                    if (com.jrpetty.mcassistant.village.TownPlan.isStreet(dx, dz) || com.jrpetty.mcassistant.village.TownPlan.isSquare(dx, dz)) continue;
                    if (MineGround.anyMeets(p.getX() - small - 1, p.getZ() - small - 1, p.getX() + small + 1, p.getZ() + small + 1)) continue;
                    if (f.fieldGroundFor14(p, small + 2)) return p;
                }
            }
        }
        return null;
    }
}
