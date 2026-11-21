package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * [join21] The town's builders keep off a player's own building, and a block's width from it: a lot is passed over
 * (Villages.siteFor) when the town's building on it would stand on or right up against
 * <ul>
 * <li>a player's building on the property register (a building the town built for it, a shop it registered, a plot it
 *     bought), by its footprint;</li>
 * <li>a player's shop not on the register (yet), by the room its survey found (its walls round it);</li>
 * <li>what a player built itself in or near the town (PlayerBlocks): two or more solid blocks of its own within the
 *     building's footprint and the gap round it, from just under the floor up to the eaves.</li>
 * </ul>
 * Without it the town's hands laid a cottage's wall against a player's shop and buried its door.
 */
public final class PlayerGap {

    private PlayerGap() {}

    /** The gap kept round a player's building, blocks. */
    static final int GAP = 1;

    /** Would a building with this half-footprint ({@code hx} across x, {@code hz} across z) at {@code ground} crowd a player's? */
    public static boolean crowds(ServerLevel level, UUID village, BlockPos ground, int hx, int hz) {
        int x = ground.getX(), z = ground.getZ();
        // On the register: its footprint, turned as it faces.
        CompoundTag all = PropertyMarket.estate(village);
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            if (!"player".equals(t.getString("ownerKind"))) continue;
            BlockPos a = BlockPos.of(t.getLong("anchor"));
            boolean turned = Direction.from2DDataValue(t.getInt("facing")).getAxis() == Direction.Axis.X;
            int bx = turned ? t.getInt("hd") : t.getInt("hw"), bz = turned ? t.getInt("hw") : t.getInt("hd");
            if (Math.abs(a.getX() - x) <= bx + hx + GAP && Math.abs(a.getZ() - z) <= bz + hz + GAP) return true;
        }
        // A shop not on the register: its room and its walls.
        for (PlayerShops.Shop s : PlayerShops.all(village)) {
            if (PropertyDesk.keyFor(village, s.owner, s.anchor) != null) continue;
            ShopNeeds.Survey sv = s.survey;
            if (sv != null && !sv.room.isEmpty()) {
                for (BlockPos r : sv.room) {
                    if (Math.abs(r.getX() - x) <= hx + 1 + GAP && Math.abs(r.getZ() - z) <= hz + 1 + GAP) return true;
                }
            } else if (Math.abs(s.anchor.getX() - x) <= hx + 4 + GAP && Math.abs(s.anchor.getZ() - z) <= hz + 4 + GAP) {
                return true;                                                  // not looked at yet: a small shop's room round its counter
            }
        }
        // What a player built itself.
        int found = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -hx - GAP; dx <= hx + GAP; dx++) {
            for (int dz = -hz - GAP; dz <= hz + GAP; dz++) {
                for (int dy = -1; dy <= 8; dy++) {
                    m.set(x + dx, ground.getY() + dy, z + dz);
                    if (!PlayerBlocks.placed(level, m) || !level.isLoaded(m)) continue;
                    BlockState st = level.getBlockState(m);
                    if (st.isAir() || st.getCollisionShape(level, m).isEmpty()) continue;
                    if (++found >= 2) return true;
                }
            }
        }
        return false;
    }
}
