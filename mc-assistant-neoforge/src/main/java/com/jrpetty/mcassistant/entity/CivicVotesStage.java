package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * [civic] The town's votes and newcomers set out for the pictures (tools/realworld/smoke.py's referendum_stage).
 * <ul>
 * <li>"/village referendum stage": beside the town, on ground levelled for it, a river seven wide and three deep;
 *     the masons' stock for a bridge put into the town's stores (stone bricks, lanterns, and the string and dye for a
 *     ribbon: the stage's, as the showcase's buildings are); and a stone bridge over the river put to the town, the
 *     vote today. The town votes, counts, and if it is carried builds it together the next morning as it would any
 *     other (it is not stamped).</li>
 * <li>"/village referendum finish": whatever is left of the work laid now out of the stores, and the ribbon strung.</li>
 * <li>"/village newcomers stage": a family of three from outside camped at the town's edge, asking to settle; the
 *     vote called.</li>
 * </ul>
 * Each returns its views as "VIEW name x y z ax ay az" (where to stand, and what to look at).
 */
final class CivicVotesStage {

    private CivicVotesStage() {}

    /** The river and the bridge put to the vote. */
    static List<String> works(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        for (int cx = (x - 24) >> 4; cx <= (x + 24) >> 4; cx++) for (int cz = (z - 20) >> 4; cz <= (z + 20) >> 4; cz++) level.getChunk(cx, cz);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Showcase.stage(level, x - 22, x + 22, z - 18, z + 18, y);
        // The river: seven across, its water a block under the banks, three deep on a gravel bed, running on past both ends.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.AIR.defaultBlockState(), 2 | 16);
                for (int dy = 2; dy <= 4; dy++) level.setBlock(new BlockPos(x + dx, y - dy, z + dz), Blocks.WATER.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x + dx, y - 5, z + dz), Blocks.GRAVEL.defaultBlockState(), 2 | 16);
            }
        }
        // The masons' stock for it, into the town's own stores.
        for (int i = 0; i < 6; i++) TownWork.give(level, v, new ItemStack(Items.STONE_BRICKS, 64));
        TownWork.give(level, v, new ItemStack(Items.LANTERN, 8));
        TownWork.give(level, v, new ItemStack(Items.STRING, 2));
        TownWork.give(level, v, new ItemStack(Items.RED_DYE, 1));
        WorksPlans.Crossing c = new WorksPlans.Crossing(x - 12, z, Direction.EAST, 9, 15, y - 2, 1, "over the river beside the town");
        WorksPlans.Plan plan = WorksPlans.bridge(level, c);
        BigWorks.Proposal p = BigWorks.propose(level, v, plan);
        out.add("RIVER " + x + " " + y + " " + z);
        if (p == null) {
            out.add("NONE the stores could not pay for it");
            return out;
        }
        long day = level.getDayTime() / 24000L;
        CompoundTag q = Referendums.callWorks(level, v, p, day, day);
        out.add("CALLED " + q.getInt("id") + " " + p.title() + " | " + p.costWords() + " | " + p.labour());
        BlockPos board = VillageBoards.lectern(v.id());
        Direction facing = VillageBoards.facingOf(v.id());
        if (board != null && facing != null) {
            BlockPos cam = board.relative(facing, 6).above(2);
            out.add("VIEW referendum-1-board " + cam.getX() + " " + cam.getY() + " " + cam.getZ() + " " + board.getX() + " " + (board.getY() + 1) + " " + board.getZ());
        }
        out.add("VIEW referendum-2-works " + (x - 16) + " " + (y + 7) + " " + (z + 12) + " " + x + " " + (y + 1) + " " + z);
        out.add("VIEW referendum-3-ribbon " + (x - 11) + " " + (y + 2) + " " + (z + 3) + " " + (x - 4) + " " + (y + 1) + " " + z);
        return out;
    }

    /** The rest of the work laid out of the stores now, no hands, and the ribbon strung; "RIBBON x y z" where it hangs. */
    static List<String> finish(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        CompoundTag w = BigWorks.current(v.id());
        if (w == null) {
            out.add("NONE nothing under way");
            return out;
        }
        int laid = 0;
        while (laid < 4000 && BigWorks.setNext(level, v, w, null)) laid++;
        long dt = level.getDayTime();
        if ("building".equals(w.getString("stage"))) BigWorks.finished(level, v, w, dt / 24000L, dt % 24000L);
        out.add("LAID " + laid + " " + BigWorks.stateForTests(v.id()));
        long[] ribbon = w.getLongArray("ribbon");
        if (ribbon.length > 0) {
            BlockPos r = BlockPos.of(ribbon[ribbon.length / 2]);
            out.add("RIBBON " + r.getX() + " " + r.getY() + " " + r.getZ());
        }
        return out;
    }

    /** A family from outside camped at the town's edge, the vote called; "CAMP x y z" and the view of them. */
    static List<String> newcomers(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        String pid = Newcomers.outside(level, v, level.getDayTime() / 24000L, 3);
        if (pid == null) {
            out.add("NONE no edge to come in from");
            return out;
        }
        int q = Newcomers.arriveForTests(level, pid);
        BlockPos camp = BlockPos.of(Newcomers.party(pid).getLong("camp"));
        out.add("PARTY " + pid + " " + Newcomers.stageForTests(pid) + " question " + q);
        out.add("CAMP " + camp.getX() + " " + camp.getY() + " " + camp.getZ());
        double dx = camp.getX() - v.centre().getX(), dz = camp.getZ() - v.centre().getZ(), len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        int cx = camp.getX() + (int) Math.round(dx / len * 7), cz = camp.getZ() + (int) Math.round(dz / len * 7);
        out.add("VIEW newcomers-1-camp " + cx + " " + (camp.getY() + 2) + " " + cz + " " + camp.getX() + " " + (camp.getY() + 1) + " " + camp.getZ());
        return out;
    }
}
