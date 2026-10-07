package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [interviews] Where an interview is held: a table with the panel's chairs along one side, the candidate's chair across
 * from them, and a bench for those waiting their turn in plain view of it.
 *
 * <p>In the meeting hall it is the hall's own long table: the three chairs at its head are the panel's, the one across
 * from the middle of them the candidate's, and the chairs at its foot, by the door, the bench. A town with no hall
 * has one set out by its board (in the leader's hall's courtyard, where it has one): three slabs on end for the table,
 * the panel's three chairs behind it with their backs to the board, the candidate's across, and a bench of three a few
 * steps back, all of the stores' own stairs and slabs (or sawn from their planks: six planks make four stairs, three
 * make six slabs), set out by a hand the town sends (TownJobs) and left there for next time. With nothing in the stores
 * to make them of, the interview is held standing where they would be.
 *
 * <p>Found once and kept in the town's notes; looked over (a dozen blocks read) only as an interview begins.
 */
final class InterviewTable {

    private InterviewTable() {}

    /** Where it is kept: the town's notes. */
    static final String NOTE = "iv.table";

    /** A place to sit (or stand): the block, and which way one sat there looks. */
    record Seat(BlockPos pos, Direction look) {
        String encode() {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + look.get2DDataValue();
        }

        @Nullable
        static Seat decode(String s) {
            String[] p = s.split(",");
            if (p.length < 4) return null;
            try {
                return new Seat(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                    Direction.from2DDataValue(Integer.parseInt(p[3])));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * The table: where it is ("the meeting hall", "the board"), whether it stands in the hall, the panel's chairs (the
     * chair's in the middle), the candidate's, the bench, where the candidates file out to and stand to hear the choice,
     * where the panel huddles, and where a referee stands to speak; whether there are seats at all.
     */
    record Table(String where, boolean hall, List<Seat> panel, Seat cand, List<Seat> bench, BlockPos out, BlockPos huddle,
                 BlockPos aside, List<Seat> front, boolean seated) {

        /** The chair's seat: the middle of the panel's. */
        Seat chairSeat() {
            return panel.get(panel.size() / 2);
        }

        String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(where).append('|').append(hall ? 1 : 0).append('|').append(seated ? 1 : 0).append('|');
            sb.append(join(panel)).append('|').append(cand.encode()).append('|').append(join(bench)).append('|');
            sb.append(new Seat(out, Direction.NORTH).encode()).append('|').append(new Seat(huddle, Direction.NORTH).encode()).append('|');
            sb.append(new Seat(aside, Direction.NORTH).encode()).append('|').append(join(front));
            return sb.toString();
        }

        @Nullable
        static Table decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            if (p.length < 10) return null;
            List<Seat> panel = seats(p[3]), bench = seats(p[5]), front = seats(p[9]);
            Seat cand = Seat.decode(p[4]), out = Seat.decode(p[6]), huddle = Seat.decode(p[7]), aside = Seat.decode(p[8]);
            if (panel.isEmpty() || cand == null || out == null || huddle == null || aside == null) return null;
            return new Table(p[0], "1".equals(p[1]), panel, cand, bench, out.pos(), huddle.pos(), aside.pos(), front, "1".equals(p[2]));
        }

        private static String join(List<Seat> l) {
            StringBuilder sb = new StringBuilder();
            for (Seat s : l) sb.append(sb.length() == 0 ? "" : ";").append(s.encode());
            return sb.toString();
        }

        private static List<Seat> seats(String s) {
            List<Seat> out = new ArrayList<>();
            if (s.isEmpty()) return out;
            for (String x : s.split(";")) {
                Seat st = Seat.decode(x);
                if (st != null) out.add(st);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ the town's table

    /** The town's table as last found (from its notes), or null. */
    @Nullable
    static Table known(UUID village) {
        return Table.decode(Ledger.note(village, NOTE));
    }

    static void keep(UUID village, Table t) {
        Ledger.note(village, NOTE, t.encode());
    }

    /**
     * The table for an interview now: the hall's, else the one set out by the board (setting it out if it is not there:
     * this waits on a hand at the spot, so it is called again until it is). Null while it waits.
     */
    @Nullable
    static Table ready(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Table t = known(id);
        if (t != null && still(level, t)) return t;
        Table hall = hall(level, v);
        if (hall != null) {
            keep(id, hall);
            return hall;
        }
        Table board = t != null && !t.hall() ? t : layout(level, v);
        if (board == null) return null;
        Table set = setOut(level, v, board);
        if (set != null) keep(id, set);
        return set;
    }

    /** Does it still stand as it was found (its chairs still chairs)? A standing table always does. */
    static boolean still(ServerLevel level, Table t) {
        if (!t.seated()) return true;
        if (!level.isLoaded(t.cand().pos())) return true;                       // not to be looked at now: taken on trust
        if (!chair(level, t.cand().pos())) return false;
        for (Seat s : t.panel()) if (!chair(level, s.pos())) return false;
        return true;
    }

    private static boolean chair(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.getBlock() instanceof StairBlock && st.getValue(StairBlock.HALF) == Half.BOTTOM;
    }

    // ------------------------------------------------------------------ the meeting hall's long table

    /** The meeting hall's long table, if the hall stands and its table and chairs are in it. */
    @Nullable
    static Table hall(ServerLevel level, Villages.Village v) {
        Ledger.Building hall = null;
        for (Ledger.Building b : Ledger.buildings(v.id())) if (b.structure().equals("hall")) { hall = b; break; }
        if (hall == null || Ledger.raising(v.id(), hall.anchor()) || !level.isLoaded(hall.anchor())) return null;
        int[] half = Blueprints.fullHalf("hall");
        int r = Math.max(half[0], half[1]) + 1;
        Direction back = hall.facing();
        BlockPos a = hall.anchor();
        // The table: a fence with a carpet on it. Its chairs: the stairs beside it, the right way up.
        List<BlockPos> table = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = a.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof FenceBlock && level.getBlockState(p.above()).getBlock() instanceof CarpetBlock) {
                        table.add(p.immutable());
                    }
                }
            }
        }
        if (table.size() < 3) return null;
        List<Seat> sideA = new ArrayList<>(), sideB = new ArrayList<>();
        Direction across = back.getClockWise();
        for (BlockPos t : table) {
            for (Direction d : new Direction[]{ across, across.getOpposite() }) {
                BlockPos c = t.relative(d);
                if (!chair(level, c)) continue;
                Seat s = new Seat(c, d.getOpposite());                  // sat in it, one looks across the table
                (d == across ? sideA : sideB).add(s);
            }
        }
        if (sideA.size() < 3 || sideB.size() < 2) {
            if (sideB.size() >= 3 && sideA.size() >= 2) { List<Seat> x = sideA; sideA = sideB; sideB = x; }
            else return null;
        }
        // Head of the table first: the end toward the back of the hall, under its great window.
        Comparator<Seat> head = Comparator.comparingInt((Seat s) -> -along(s.pos(), back));
        sideA.sort(head);
        sideB.sort(head);
        List<Seat> panel = new ArrayList<>(sideA.subList(0, 3));
        int row = along(panel.get(1).pos(), back);
        Seat cand = null;
        for (Seat s : sideB) if (along(s.pos(), back) == row) { cand = s; break; }
        if (cand == null) return null;
        // The bench: the chairs at the foot of the table, a row or more clear of the panel and the candidate.
        List<Seat> bench = new ArrayList<>();
        int last = along(panel.get(2).pos(), back);
        for (int i = 0; i < Math.max(sideA.size(), sideB.size()); i++) {
            for (List<Seat> side : List.of(sideB, sideA)) {
                if (i >= side.size()) continue;
                Seat s = side.get(side.size() - 1 - i);                 // from the foot up
                if (along(s.pos(), back) <= last - 2 && bench.size() < 6) bench.add(s);
            }
        }
        if (bench.isEmpty()) return null;
        BlockPos headEnd = table.stream().max(Comparator.comparingInt(p -> along(p, back))).orElse(table.get(0));
        BlockPos footEnd = table.stream().min(Comparator.comparingInt(p -> along(p, back))).orElse(table.get(0));
        BlockPos huddle = headEnd.relative(back, 1);                    // between the table's head and the leader's chair
        BlockPos out = footEnd.relative(back.getOpposite(), 2);
        Direction away = cand.look().getOpposite();                    // the aisle behind the candidate's chair
        BlockPos aside = cand.pos().relative(away).relative(back);
        List<Seat> front = new ArrayList<>();
        for (Seat s : sideB) {
            int ar = along(s.pos(), back);
            if (ar <= row + 1 && ar >= row - 2 && front.size() < 4) front.add(new Seat(s.pos().relative(away), cand.look()));
        }
        if (front.isEmpty()) front.add(new Seat(cand.pos().relative(away), cand.look()));
        return new Table("the meeting hall", true, panel, cand, bench, out, huddle, aside, front, true);
    }

    /** How far toward the back of the building a block is. */
    private static int along(BlockPos p, Direction back) {
        return p.getX() * back.getStepX() + p.getZ() * back.getStepZ();
    }

    // ------------------------------------------------------------------ the table by the board

    /**
     * Where a table would go by the board: a piece of level, open ground three wide and eleven deep (the panel's chairs,
     * the table, the candidate's chair, a gap, the bench, and room to stand behind it), to one side of where folk stand
     * to read the board, the panel's backs to it. Null if there is no such ground near.
     */
    @Nullable
    static Table layout(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos board = VillageBoards.lectern(id);
        Direction f = VillageBoards.facingOf(id);
        List<BlockPos> tries = new ArrayList<>();
        List<Direction> ways = new ArrayList<>();
        if (board != null && f != null) {
            Direction right = com.jrpetty.mcassistant.block.VillageBoardBlock.right(f);
            for (int side : new int[]{ 8, -8, 11, -11 }) {
                for (int out : new int[]{ 2, 4 }) {
                    tries.add(board.relative(right, side).relative(f, out));
                    ways.add(f);
                }
            }
            tries.add(board.relative(f, 10));
            ways.add(f);
        }
        BlockPos heart = v.centre();
        int[][] round = { { 0, -10 }, { 10, 0 }, { -10, 0 }, { 0, 10 }, { 12, -12 }, { -12, 12 }, { 12, 12 }, { -12, -12 } };
        for (int[] o : round) {
            tries.add(heart.offset(o[0], 0, o[1]));
            ways.add(Math.abs(o[0]) >= Math.abs(o[1]) ? (o[0] > 0 ? Direction.EAST : Direction.WEST) : (o[1] > 0 ? Direction.SOUTH : Direction.NORTH));
        }
        for (int i = 0; i < tries.size(); i++) {
            BlockPos s = ground(level, tries.get(i));
            Direction d = ways.get(i);
            if (s == null || Math.abs(s.getY() - heart.getY()) > 6 || !clear(level, s, d)) continue;
            return board(s, d, board != null ? "the board" : "the square");
        }
        return null;
    }

    /** The table's plan at this middle, the panel looking this way: every place, and nothing yet set out. */
    static Table board(BlockPos s, Direction d, String where) {
        Direction side = d.getClockWise();
        List<Seat> panel = new ArrayList<>(), bench = new ArrayList<>(), front = new ArrayList<>();
        for (int k = -1; k <= 1; k++) {
            panel.add(new Seat(s.relative(d.getOpposite()).relative(side, k), d));
            bench.add(new Seat(s.relative(d, 4).relative(side, k), d.getOpposite()));
            front.add(new Seat(s.relative(d, 2).relative(side, k), d.getOpposite()));
        }
        Seat cand = new Seat(s.relative(d), d.getOpposite());
        return new Table(where, false, panel, cand, bench, s.relative(d, 7), s.relative(d.getOpposite(), 3),
            s.relative(d).relative(side, 2), front, false);
    }

    /** The ground's top at a column (the block one would stand in), if it is loaded. */
    @Nullable
    private static BlockPos ground(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return null;
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
    }

    /** Level, open, dry ground for the whole of it: the panel's row to the bench and a step behind. */
    private static boolean clear(ServerLevel level, BlockPos s, Direction d) {
        Direction side = d.getClockWise();
        for (int along = -2; along <= 6; along++) {
            for (int k = -1; k <= 1; k++) {
                BlockPos at = s.relative(d, along).relative(side, k);
                BlockPos g = ground(level, at);
                if (g == null) return false;
                boolean seats = along >= -1 && along <= 4;
                if (seats ? g.getY() != s.getY() : Math.abs(g.getY() - s.getY()) > 1) return false;
                BlockState under = level.getBlockState(g.below());
                if (!under.getFluidState().isEmpty() || !under.isFaceSturdy(level, g.below(), Direction.UP)) return false;
                for (int h = 0; h <= 2; h++) {
                    BlockState st = level.getBlockState(g.above(h));
                    if (!(st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty())) return false;
                }
            }
        }
        return true;
    }

    /**
     * The table by the board set out, if a hand is at the spot to do it (TownJobs) and the stores run to it: seven
     * stairs and three slabs, the stores' own, or sawn from their planks (the leftovers back). With nothing to make it
     * of, the places are kept for an interview held standing. Null while the work waits on a hand.
     */
    @Nullable
    static Table setOut(ServerLevel level, Villages.Village v, Table t) {
        if (t.seated() && still(level, t)) return t;
        BlockPos mid = t.cand().pos().relative(t.cand().look());              // the table's middle, before the candidate's chair
        if (!TownJobs.atWork(level, v, "interview table", mid, "setting out the interview table by the board")) return null;
        if (!pay(level, v)) {
            Villages.tell(v.id(), level.getDayTime() / 24000L, "no stairs or planks in the stores for an interview table: the next "
                + "interview is held standing by the board");
            return new Table(t.where(), false, t.panel(), t.cand(), t.bench(), t.out(), t.huddle(), t.aside(), t.front(), false);
        }
        Direction d = t.cand().look().getOpposite();                       // the panel looks this way
        Direction side = d.getClockWise();
        for (int k = -1; k <= 1; k++) {
            BlockPos cell = mid.relative(side, k);
            level.setBlockAndUpdate(cell, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        }
        // Sat in, one looks the way the stair's back is not: FACING is the back.
        for (Seat s : t.panel()) put(level, s);
        put(level, t.cand());
        for (Seat s : t.bench()) put(level, s);
        Villages.tell(v.id(), level.getDayTime() / 24000L, "a table and chairs for interviews were set out by " + t.where()
            + ", of the stores' own stairs and slabs");
        return new Table(t.where(), false, t.panel(), t.cand(), t.bench(), t.out(), t.huddle(), t.aside(), t.front(), true);
    }

    private static void put(ServerLevel level, Seat s) {
        level.setBlockAndUpdate(s.pos(), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, s.look().getOpposite())
            .setValue(StairBlock.HALF, Half.BOTTOM));
    }

    /** Seven stairs and three slabs out of the stores: theirs, or sawn from their planks (what is over goes back). */
    private static boolean pay(ServerLevel level, Villages.Village v) {
        int stairs = Math.min(7, Crafts.stock(level, v, s -> s.is(ItemTags.WOODEN_STAIRS)));
        int slabs = Math.min(3, Crafts.stock(level, v, s -> s.is(ItemTags.WOODEN_SLABS)));
        int stairBatches = (7 - stairs + 3) / 4, slabBatches = (3 - slabs + 5) / 6;
        int planks = stairBatches * 6 + slabBatches * 3;
        if (planks > 0 && Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) + Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) * 4 < planks) {
            return false;
        }
        if (!Crafts.take(level, v, s -> s.is(ItemTags.WOODEN_STAIRS), stairs)) return false;
        if (!Crafts.take(level, v, s -> s.is(ItemTags.WOODEN_SLABS), slabs)) {
            Crafts.giveBack(level, v, Items.OAK_STAIRS, stairs);
            return false;
        }
        if (!Crafts.usePlanks(level, v, planks)) {
            Crafts.giveBack(level, v, Items.OAK_STAIRS, stairs);
            Crafts.giveBack(level, v, Items.OAK_SLAB, slabs);
            return false;
        }
        Crafts.giveBack(level, v, Items.OAK_STAIRS, stairBatches * 4 - (7 - stairs));
        Crafts.giveBack(level, v, Items.OAK_SLAB, slabBatches * 6 - (3 - slabs));
        return true;
    }

    // ------------------------------------------------------------------ sitting

    /** Where one sits on this seat (on the stair, a little toward its back), or stands on it with no chair. */
    static net.minecraft.world.phys.Vec3 spot(Table t, Seat s) {
        if (!t.seated()) return new net.minecraft.world.phys.Vec3(s.pos().getX() + 0.5, s.pos().getY(), s.pos().getZ() + 0.5);
        return new net.minecraft.world.phys.Vec3(s.pos().getX() + 0.5 - s.look().getStepX() * 0.1, s.pos().getY() + 0.5,
            s.pos().getZ() + 0.5 - s.look().getStepZ() * 0.1);
    }

    /** Tests and the stage: a stack of what a table set out by the board takes, for the stores. */
    static ItemStack[] makingsForTests() {
        return new ItemStack[]{ new ItemStack(Items.OAK_PLANKS, 24) };
    }
}
