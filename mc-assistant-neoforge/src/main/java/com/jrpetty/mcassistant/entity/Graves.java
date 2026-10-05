package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where a village remembers its dead (Ledger.graves):
 * <ul>
 * <li><b>the graveyard</b> — twelve graves either side of the path, filled in the order the
 *     village lost its people: a headstone of carved stone, a mound in front of it, and on
 *     the stone the name, the years they lived and the age they reached. A village with more
 *     dead than graves builds another graveyard;</li>
 * <li><b>the chapel's memorial</b> — boards along the inside of the nave, "In loving memory",
 *     three names to a board.</li>
 * </ul>
 */
public final class Graves {

    private Graves() {}

    /** Each grave's place in a graveyard, across and back from its centre: two rows of six, the
     *  path left clear down the middle. */
    public static final int[][] PLOTS = {
        { -3, -2 }, { -2, -2 }, { -1, -2 }, { 1, -2 }, { 2, -2 }, { 3, -2 },
        { -3, 1 }, { -2, 1 }, { -1, 1 }, { 1, 1 }, { 2, 1 }, { 3, 1 } };

    /** The graves a village has room for. */
    public static int room(UUID village) {
        int n = 0;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("graveyard")) n += PLOTS.length;
        return n;
    }

    /** Lay out every grave the village owes its dead, and the chapel's memorial. Returns stones put up. */
    public static int tend(ServerLevel level, UUID village) {
        List<Ledger.Grave> dead = Ledger.graves(village);
        if (dead.isEmpty()) return 0;
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;                                         // nobody's stores to make them of
        int put = 0, i = 0;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!b.structure().equals("graveyard") || !level.isLoaded(b.anchor())) {
                if (b.structure().equals("graveyard")) i += PLOTS.length;
                continue;
            }
            Direction back = b.facing(), right = back.getClockWise(), front = back.getOpposite();
            for (int[] plot : PLOTS) {
                if (i >= dead.size()) break;
                Ledger.Grave g = dead.get(i++);
                BlockPos mound = b.anchor().relative(right, plot[0]).relative(back, plot[1]);
                BlockPos stone = mound.relative(back);
                if (headstone(level, v, stone, mound, front, g, false)) put++;
            }
        }
        memorial(level, v, dead);
        return put;
    }

    /** Is this a headstone: chiselled stone bricks, stone bricks, or a rough stone of cobble? */
    static boolean isHeadstone(BlockState st) {
        return st.is(Blocks.CHISELED_STONE_BRICKS) || st.is(Blocks.STONE_BRICKS) || st.is(Blocks.COBBLESTONE);
    }

    /** One grave, for nothing (the showcase): the stone, the mound, the words on the stone. */
    public static boolean headstone(ServerLevel level, BlockPos stone, BlockPos mound, Direction front, Ledger.Grave g) {
        return headstone(level, null, stone, mound, front, g, true);
    }

    /** One grave: the stone, the mound, the words on the stone. The stone (chiselled stone bricks if
     *  the masons' stone bricks run to it, else a plain block of stone bricks, else a rough one of
     *  cobble: Crafts.masonry) and the sign (a sign, or two planks) come out of the village's stores
     *  now, unless {@code free}; what they can't pay for waits till they can. */
    static boolean headstone(ServerLevel level, @javax.annotation.Nullable Villages.Village v, BlockPos stone, BlockPos mound,
                             Direction front, Ledger.Grave g, boolean free) {
        if (!free && v == null) return false;
        boolean fresh = false;
        BlockState at = level.getBlockState(stone);
        if (!isHeadstone(at)) {
            if (!at.isAir() && !at.canBeReplaced()) return false;
            // Dug and set by a hand from the village, at the graveside (TownJobs).
            if (!free && !TownJobs.atWork(level, v, "graves", mound, "setting a headstone")) return false;
            net.minecraft.world.level.block.Block carved = free ? Blocks.CHISELED_STONE_BRICKS : Crafts.masonry(level, v);
            if (carved == null) return false;
            level.setBlock(stone, carved.defaultBlockState(), 3);
            BlockState earth = level.getBlockState(mound.below());
            if (earth.is(Blocks.GRASS_BLOCK) || earth.is(Blocks.DIRT)) level.setBlock(mound.below(), Blocks.COARSE_DIRT.defaultBlockState(), 3);
            fresh = true;
        }
        BlockState sign = level.getBlockState(mound);
        if (!(sign.getBlock() instanceof WallSignBlock)) {
            if (!sign.isAir() && !sign.canBeReplaced()) return fresh;
            if (!free && !Crafts.sign(level, v)) return fresh;
            level.setBlock(mound, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front), 3);
        }
        if (level.getBlockEntity(mound) instanceof SignBlockEntity s) {
            long years = Math.max(0, g.died() - g.born());
            TownLife.write(s, new String[]{ g.name(), "day " + Math.max(0, g.born()) + " - " + g.died(),
                g.cause().equals("of old age") ? "at peace" : "much missed", years > 0 ? "rest well" : "" });
        }
        return fresh;
    }

    /** A place for a memorial board: where it hangs and which way it faces. */
    record Board(BlockPos at, Direction facing) {}

    /** "In loving memory": boards along the inside of the chapel's nave, three names to a board.
     *  Each board is a sign out of the stores (or two planks); none till they have one. */
    static void memorial(ServerLevel level, Villages.Village v, List<Ledger.Grave> dead) {
        UUID village = v.id();
        Ledger.Building chapel = null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("chapel")) { chapel = b; break; }
        if (chapel == null || !level.isLoaded(chapel.anchor())) return;
        int k = 0;
        for (Board spot : boards(chapel)) {
            if (k >= dead.size()) break;
            BlockState s = level.getBlockState(spot.at());
            if (!(s.getBlock() instanceof WallSignBlock)) {
                if (!s.isAir()) continue;
                BlockState board = Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, spot.facing());
                if (!board.canSurvive(level, spot.at())) continue;
                if (!Crafts.sign(level, v)) return;
                level.setBlock(spot.at(), board, 3);
            }
            String[] lines = { "In loving memory", "", "", "" };
            for (int j = 1; j < 4 && k < dead.size(); j++) lines[j] = dead.get(k++).name();
            if (level.getBlockEntity(spot.at()) instanceof SignBlockEntity sb) TownLife.write(sb, lines);
        }
    }

    /** Places on the inside of the chapel's side walls for a memorial board. */
    static List<Board> boards(Ledger.Building chapel) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(chapel.structure(), chapel.anchor(), chapel.facing(), 13);
        Set<BlockPos> solid = new HashSet<>();
        Set<BlockPos> planned = new HashSet<>();
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.BLOCK) solid.add(p.pos());
            if (p.part() != BuildGoal.Part.CLEAR) planned.add(p.pos());
        }
        Direction right = chapel.facing().getClockWise();
        List<Board> out = new ArrayList<>();
        int y = chapel.anchor().getY() + 1;
        for (Direction side : new Direction[]{ right, right.getOpposite() }) {
            for (BlockPos wall : solid) {
                if (wall.getY() != y || !sideOf(chapel, wall, side)) continue;
                int across = Math.abs((wall.getX() - chapel.anchor().getX()) * right.getStepX()
                    + (wall.getZ() - chapel.anchor().getZ()) * right.getStepZ());
                if (across < 2) continue;
                BlockPos in = wall.relative(side.getOpposite());
                if (planned.contains(in)) continue;                 // the nave's open floor in front of it
                out.add(new Board(in, side.getOpposite()));
            }
        }
        out.sort((a, b) -> Long.compare(a.at().asLong(), b.at().asLong()));
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private static boolean sideOf(Ledger.Building b, BlockPos wall, Direction side) {
        int dx = wall.getX() - b.anchor().getX(), dz = wall.getZ() - b.anchor().getZ();
        return dx * side.getStepX() + dz * side.getStepZ() > 0;
    }
}
