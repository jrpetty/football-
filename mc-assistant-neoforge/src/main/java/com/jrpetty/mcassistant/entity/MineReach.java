package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [growth24] The mine's ground widened when every level of an ore in it is worked out.
 *
 * <p>The ground is set aside once, when the mine is opened: every face out to the fourth ring round the first that lies
 * clear of the town's ground as it will grow (MineGround). With the further levels (MineLevels) an ore's six levels take a
 * town many weeks to work out, but then the iron (or the coal) stopped again for good. Now, when the last level of an ore
 * is worked out, the ground is widened by a ring: every face of the next ring out that touches a face already set aside and
 * is, as the first were, clear of the town's ground (its whole plan, fields, buildings and roads, with a margin), of the
 * borders and of any other town's mine; never under the town. The drifts of that ore's levels that stopped at the old edge
 * of the ground are driven on into the new, and the levels are worked again. Out to the eighth ring at most (the mine a
 * hundred and forty blocks across each way); a ring that has no face clear of the town widens nothing.
 */
public final class MineReach {

    private MineReach() {}

    /** The farthest ring the ground may be widened to. */
    public static final int MOST = 8;

    /** The face (grid place round the first face) a column falls in, out to the farthest ring; null past it. */
    @Nullable
    static int[] face(BlockPos site, int x, int z) {
        int i = Math.floorDiv(x - site.getX() + TownMine.PITCH / 2, TownMine.PITCH);
        int j = Math.floorDiv(z - site.getZ() + TownMine.PITCH / 2, TownMine.PITCH);
        return Math.max(Math.abs(i), Math.abs(j)) <= MOST ? new int[]{ i, j } : null;
    }

    /** The ground widened by a ring, if it may be: the faces added (none when there is no room). */
    static List<int[]> widen(ServerLevel level, UUID village) {
        List<int[]> add = new ArrayList<>();
        BlockPos site = TownMine.siteOf(village);
        Villages.Village v = Villages.get(village);
        if (site == null || v == null || !MineGround.has(village)) return add;
        int ring = MineGround.ring(village) + 1;
        if (ring < 1 || ring > MOST) return add;
        int r = TownMine.PITCH / 2;
        BlockPos heart = v.centre();
        for (int i = -ring; i <= ring; i++) {
            for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                boolean touches = MineGround.faceReserved(village, i - 1, j) || MineGround.faceReserved(village, i + 1, j)
                    || MineGround.faceReserved(village, i, j - 1) || MineGround.faceReserved(village, i, j + 1);
                if (!touches) continue;
                BlockPos c = new BlockPos(site.getX() + i * TownMine.PITCH, 0, site.getZ() + j * TownMine.PITCH);
                if (TownDigs.inTownGroundRaw(level, village, heart, c, r + MineGround.MARGIN)) continue;
                if (Bonds.overBorder(village, heart, c, r)) continue;
                int m = r + MineGround.MARGIN;
                if (MineGround.otherMeets(village, c.getX() - m, c.getZ() - m, c.getX() + m, c.getZ() + m)) continue;
                add.add(new int[]{ i, j });
            }
        }
        // Faces touching only the new ones (round a corner of the ring) come in with them.
        if (add.isEmpty()) return add;
        MineGround.addFaces(village, site, add);
        long day = level.getDayTime() / 24000L;
        Villages.tell(village, day, "the town's mine's ground was widened: " + add.size() + " more faces in its " + ordinal(ring)
            + " ring round " + site.getX() + ", " + site.getZ() + ", clear of the town");
        com.mojang.logging.LogUtils.getLogger().info("[MCA-MINE] {}: the mine's ground widened by {} faces (ring {})", Villages.name(village), add.size(), ring);
        return add;
    }

    private static String ordinal(int n) {
        return switch (n) { case 5 -> "fifth"; case 6 -> "sixth"; case 7 -> "seventh"; case 8 -> "eighth"; default -> n + "th"; };
    }
}
