package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [police] The operators' stage (/village police stage, chase, fight): the watch's pictures set out at once, among the
 * town's own folk. The watch house is stamped beside where the command is run (a town with one of its own uses that)
 * and fitted out as the watch would fit it: iron bars and doors on its cells, its notice board and its casebook, for
 * nothing; a folk is put in a cell for a night's disorder; a guard on the beat greets a folk on the square by name; a
 * culprit runs from a guard down the east avenue; and a guard walks another folk to the cells on a lead. Each scene
 * says where to look from: "VIEW name x y z ax ay az" (the eyes' feet, and what they look at).
 */
final class PoliceStage {

    private PoliceStage() {}

    static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        Crime.hurryForTests(false);
        Ledger.Building b = WatchHouse.of(id);
        if (b == null || !level.isLoaded(b.anchor())) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            BlockPos site = new BlockPos(at.getX(), y, at.getZ());
            // Level ground for it: the footprint and a block round it cleared, a floor of earth under it.
            for (int dx = -7; dx <= 7; dx++) {
                for (int dz = -7; dz <= 7; dz++) {
                    for (int dy = 0; dy < 9; dy++) level.setBlock(site.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2 | 16);
                    for (int dy = -3; dy < 0; dy++) {
                        level.setBlock(site.offset(dx, dy, dz), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                    }
                }
            }
            BuildGoal.stamp(level, WatchHouse.STRUCTURE, site, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, WatchHouse.STRUCTURE, site, Direction.NORTH);
            b = WatchHouse.of(id);
            out.add("the watch house stamped at " + site.toShortString());
        }
        if (b == null) {
            out.add("no watch house could be had");
            return out;
        }
        fitOut(level, v, b);
        // The cast: a guard each for the beat, the arrest and the chase, and a folk each for the cell, the greeting, the
        // lead and the running. The stage sees to its own: a town short of guards has its folk appointed to the watch
        // (whoever would volunteer first), and a town short of folk has them stood up beside its heart.
        List<VillageFolkEntity> guards = cast(level, v, true), folk = cast(level, v, false);
        out.addAll(castUp(level, v, guards, folk));
        if (folk.size() < FOLK || guards.size() < GUARDS) {
            out.add("the town wants " + FOLK + " folk and " + GUARDS + " guards about for the stage (it has " + folk.size() + " and " + guards.size() + ")");
            return out;
        }
        // A folk in a cell, for a night's disorder.
        List<WatchHouse.Cell> cells = WatchHouse.cells(b);
        WatchHouse.Cell cell = cells.get(0);
        VillageFolkEntity prisoner = folk.get(0);
        CompoundTag t = new CompoundTag();
        t.putUUID("village", id);
        t.putString("name", prisoner.displayNameCap());
        t.putString("state", "CELL");
        t.putInt("cell", 0);
        t.putInt("case", 0);
        t.putString("why", "a night's disorder");
        t.putLong("since", level.getDayTime());
        t.putLong("until", (level.getDayTime() / 24000L + 1) * 24000L + 1000L);
        Police.custody().put(prisoner.getStringUUID(), t);
        prisoner.clearQueue();
        prisoner.moveTo(cell.inside().getX() + 0.5, cell.inside().getY(), cell.inside().getZ() + 0.5, 0.0F, 0.0F);
        WatchHouse.setDoor(level, cell.door(), false);
        BlockPos front = cell.front();
        out.add(view(level, "cell", front.relative(b.facing().getOpposite(), 1).relative(b.facing().getCounterClockWise(), 1), cell.inside().above()));
        BlockPos door = WatchHouse.at(b, WatchHouse.OUTSIDE);
        BlockPos outside = door.relative(b.facing().getOpposite(), 7).relative(b.facing().getClockWise(), 4).above(2);
        out.add(view(level, "house", outside, door.above(1)));                 // its front, the door and the lanterns
        // A guard on the beat, greeting a folk on the square by name.
        VillageFolkEntity beat = guards.get(0), passer = folk.get(1);
        Police.setDutyForTests(level, beat, Roster.Duty.BEAT.name());
        BlockPos sq = ground(level, v.centre().relative(Direction.EAST, 6).relative(Direction.SOUTH, 6));
        beat.moveTo(sq.getX() + 0.5, sq.getY(), sq.getZ() + 0.5, 0.0F, 0.0F);
        passer.moveTo(sq.getX() + 2.5, sq.getY(), sq.getZ() + 0.5, 90.0F, 0.0F);
        beat.getLookControl().setLookAt(passer, 30.0F, 30.0F);
        passer.getLookControl().setLookAt(beat, 30.0F, 30.0F);
        FolkTalk.speak(beat, "Afternoon, " + passer.displayNameCap() + ". All well at home?");
        passer.sayLater("All well, " + beat.displayNameCap() + ", thanks.", 40);
        Beats.felt(id, sq, level.getGameTime());
        out.add(view(level, "beat", sq.relative(Direction.SOUTH, 5).relative(Direction.EAST, 1).above(1), sq.relative(Direction.EAST, 1).above(1)));
        // A chase down the east avenue: its own guard and its own culprit, not the beat's.
        out.add(chase(level, v, ground(level, v.centre().relative(Direction.EAST, 20)), guards.get(2), folk.get(3)));
        // An arrest on a lead, walked to the second cell.
        {
            VillageFolkEntity g = guards.get(1), f = folk.get(2);
            BlockPos start = door.relative(b.facing().getOpposite(), 10);
            start = ground(level, start);
            g.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0.0F, 0.0F);
            f.moveTo(start.getX() + 1.5, start.getY(), start.getZ() + 0.5, 0.0F, 0.0F);
            if (g.countCarried(s -> s.is(Items.LEAD)) == 0) g.insertItem(new ItemStack(Items.LEAD));
            WatchHouse.arrest(level, v, g, f, 0, "a breach of the peace", (level.getDayTime() / 24000L + 1) * 24000L + 1000L);
            out.add(view(level, "arrest", start.relative(b.facing().getClockWise(), 6).above(1), start.above(1)));
        }
        BlockPos board = VillageBoards.lectern(id);
        if (board != null) out.add(view(level, "board", board.relative(Direction.SOUTH, 4).above(1), board.above(1)));
        out.add("the roster: " + Roster.words(level, v));
        return out;
    }

    /** Who is about for a part: the town's guards (true) or its other grown folk (false), here, free and out of the cells. */
    static List<VillageFolkEntity> cast(ServerLevel level, Villages.Village v, boolean guard) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.level() != level) continue;
            if ((f.stationTask() == AssistantEntity.StationTask.GUARD) != guard || Patrols.away(f)) continue;
            if (WatchHouse.custodyOf(f.getUUID()) != null || Incidents.task(f) != null || WatchHouse.escorting(f) != null) continue;
            if (!guard && f.isElder()) continue;                    // not the leader in the cells
            out.add(f);
        }
        out.sort(Comparator.comparing(Entity::getUUID));
        return out;
    }

    /**
     * The stage's own cast: the town's folk appointed to the watch while it has fewer than three guards about (the
     * readiest volunteers first, then any grown hand but the leader, while four are left for the other parts), and folk
     * stood up beside the heart for the parts nobody is left for. An appointed guard keeps the watch a while (the
     * town's own sums wait on it). The lists are filled in; what was done, in words.
     */
    static final int GUARDS = 3, FOLK = 4;

    static List<String> castUp(ServerLevel level, Villages.Village v, List<VillageFolkEntity> guards, List<VillageFolkEntity> folk) {
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> order = new ArrayList<>();
        for (VillageFolkEntity f : WarFooting.volunteers(v.id())) if (folk.contains(f)) order.add(f);
        for (VillageFolkEntity f : folk) if (!order.contains(f)) order.add(f);
        List<String> named = new ArrayList<>();
        while (guards.size() < GUARDS) {
            VillageFolkEntity f = order.isEmpty() || folk.size() <= FOLK ? raise(level, v, guards.size()) : order.remove(0);
            if (f == null) break;
            folk.remove(f);
            if (!f.takeUpTrade(AssistantEntity.StationTask.GUARD)) f.setJob(AssistantEntity.StationTask.GUARD);
            if (f.stationTask() != AssistantEntity.StationTask.GUARD) break;
            f.keepTradeForTests();
            guards.add(f);
            named.add(f.displayNameCap());
        }
        if (!named.isEmpty()) out.add("appointed to the watch for the stage: " + String.join(", ", named));
        named.clear();
        while (folk.size() < FOLK) {
            VillageFolkEntity f = raise(level, v, GUARDS + folk.size());
            if (f == null) break;
            folk.add(f);
            named.add(f.displayNameCap());
        }
        if (!named.isEmpty()) out.add("stood up for the stage: " + String.join(", ", named));
        return out;
    }

    /** One more of the town stood up a few blocks off its heart (whatever the town's cap), or null. */
    @Nullable
    private static VillageFolkEntity raise(ServerLevel level, Villages.Village v, int n) {
        BlockPos p = ground(level, v.centre().relative(Direction.SOUTH, 4).relative(Direction.WEST, 3 + 2 * n));
        return com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, p, 0.0F, Integer.MAX_VALUE);
    }

    /** The watch house fitted out at once, for the pictures: iron bars and doors, the notice board, the casebook. */
    static void fitOut(ServerLevel level, Villages.Village v, Ledger.Building b) {
        for (WatchHouse.Cell c : WatchHouse.cells(b)) {
            for (BlockPos p : c.bars()) {
                if (!level.getBlockState(p).is(Blocks.IRON_BARS)) level.setBlock(p, Block.updateFromNeighbourShapes(Blocks.IRON_BARS.defaultBlockState(), level, p), 3);
            }
            if (!level.getBlockState(c.door()).is(Blocks.IRON_DOOR)) WatchHouse.hangIronDoor(level, v, c.door());
        }
        Direction left = b.facing().getCounterClockWise(), right = b.facing().getClockWise();
        int[][] spots = { WatchHouse.WANTED_A, WatchHouse.WANTED_B, WatchHouse.ROSTER };
        Direction[] faces = { left, left, right };
        for (int i = 0; i < 3; i++) {
            BlockPos p = WatchHouse.at(b, spots[i]);
            if (level.getBlockState(p).canBeReplaced()) level.setBlock(p, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, faces[i]), 3);
        }
        WatchHouse.noticeBoard(level, v, b);
        BlockPos lectern = WatchHouse.at(b, WatchHouse.LECTERN);
        BlockState st = level.getBlockState(lectern);
        if (st.getBlock() instanceof LecternBlock && !st.getValue(LecternBlock.HAS_BOOK)) {
            LecternBlock.tryPlaceBook(null, level, lectern, st, WatchHouse.book(level, v));
        }
    }

    /** "/village police chase": a folk at hand runs from the nearest guard, as if caught in the act. */
    static String chase(ServerLevel level, Villages.Village v, BlockPos at) {
        return chase(level, v, at, nearest(cast(level, v, true), at), nearest(cast(level, v, false), at));
    }

    @Nullable
    private static VillageFolkEntity nearest(List<VillageFolkEntity> all, BlockPos at) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity x : all) if (best == null || x.blockPosition().distSqr(at) < best.blockPosition().distSqr(at)) best = x;
        return best;
    }

    /** The chase with its cast given: {@code f} runs down the avenue from {@code at}, {@code g} after it. */
    static String chase(ServerLevel level, Villages.Village v, BlockPos at, @Nullable VillageFolkEntity g, @Nullable VillageFolkEntity f) {
        if (g == null || f == null) return "nobody to chase or to give chase";
        BlockPos run = ground(level, at);
        f.moveTo(run.getX() + 0.5, run.getY(), run.getZ() + 0.5, -90.0F, 0.0F);
        BlockPos from = ground(level, at.relative(Direction.WEST, 9));
        g.moveTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, -90.0F, 0.0F);
        Incidents.startChase(level, v, g, f, 0, "a theft at the market", true);
        return view(level, "chase", run.relative(Direction.SOUTH, 9).relative(Direction.EAST, 8).above(2), run.relative(Direction.EAST, 6).above(1))
            + "\n" + g.displayNameCap() + " is after " + f.displayNameCap();
    }

    /** "/village police fight": two folk at hand come to blows, and the nearest guard is called. */
    static String fight(ServerLevel level, Villages.Village v, BlockPos at) {
        List<VillageFolkEntity> two = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity x && !x.isBaby() && x.stationTask() != AssistantEntity.StationTask.GUARD
                    && WatchHouse.custodyOf(x.getUUID()) == null && Incidents.task(x) == null) two.add(x);
            if (two.size() == 2) break;
        }
        if (two.size() < 2) return "nobody to fight";
        BlockPos p = ground(level, at);
        two.get(0).moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 90.0F, 0.0F);
        two.get(1).moveTo(p.getX() + 1.8, p.getY(), p.getZ() + 0.5, -90.0F, 0.0F);
        Incidents.startFight(level, v, two.get(0), two.get(1));
        return two.get(0).displayNameCap() + " and " + two.get(1).displayNameCap() + " have come to blows";
    }

    static BlockPos ground(ServerLevel level, BlockPos p) {
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    /** A scene's "VIEW name x y z ax ay az": the camera's feet (moved to a clear spot, clearEye) and what it looks at. */
    private static String view(ServerLevel level, String name, BlockPos eye, BlockPos at) {
        BlockPos e = clearEye(level, eye, at);
        return "VIEW " + name + " " + e.getX() + " " + e.getY() + " " + e.getZ() + " " + at.getX() + " " + at.getY() + " " + at.getZ();
    }

    /**
     * Where the camera stands for a scene: its spot as planned if the eye's block and the one over it are air and the line
     * from the eye (1.62 over the feet) to the subject runs clear; else stepped up, then back from the subject and to
     * either side, a block at a time, till one is. A chase shot from inside a tree's leaves showed nothing of the chase.
     */
    static BlockPos clearEye(ServerLevel level, BlockPos eye, BlockPos at) {
        double ax = eye.getX() - at.getX(), az = eye.getZ() - at.getZ();
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 0.5) { ax = 0; az = 1; } else { ax /= len; az /= len; }
        int[] sides = { 0, 2, -2, 4, -4, 6, -6 };
        for (int back = 0; back <= 10; back += 2) {
            for (int side : sides) {
                for (int up = 0; up <= 8; up++) {
                    BlockPos p = BlockPos.containing(eye.getX() + 0.5 + ax * back - az * side, eye.getY() + up, eye.getZ() + 0.5 + az * back + ax * side);
                    if (clearFrom(level, p, at)) return p;
                }
            }
        }
        return eye;
    }

    /** Is the eye at these feet in the open (its block and the one over it air) with a clear line to the subject? */
    static boolean clearFrom(ServerLevel level, BlockPos feet, BlockPos at) {
        if (!level.getBlockState(feet.above()).isAir() || !level.getBlockState(feet.above(2)).isAir()) return false;
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
        net.minecraft.world.phys.Vec3 to = net.minecraft.world.phys.Vec3.atCenterOf(at);
        double dist = from.distanceTo(to);
        BlockPos last = null;
        for (double s = 0.0; s < dist - 1.0; s += 0.25) {
            BlockPos p = BlockPos.containing(from.lerp(to, s / dist));
            if (p.equals(last)) continue;
            last = p;
            if (p.equals(at)) break;
            BlockState st = level.getBlockState(p);
            if (st.isAir() || !st.getFluidState().isEmpty() && st.getCollisionShape(level, p).isEmpty()) continue;
            // Seen through: iron bars and panes (a cell's front), glass. Leaves and every other solid block stop the eye.
            if (st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock || st.getBlock() instanceof net.minecraft.world.level.block.TransparentBlock) continue;
            if (st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || !st.getCollisionShape(level, p).isEmpty()) return false;
        }
        return true;
    }
}
