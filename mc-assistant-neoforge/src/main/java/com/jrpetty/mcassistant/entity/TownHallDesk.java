package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpa] The Town Hall's desk: a service counter set in the town's hall (the leader's hall first, else the meeting hall),
 * where the clerk keeps the town's book. Its row in ServiceRole (HALL) gives it its hours and its keeper; a click on it
 * opens the Town Hall's page (TownHall), not a shop's screen.
 * <ul>
 * <li><b>The fit-out.</b> Once a day a hall without a desk has one set in, out of four of the stores' planks: inside,
 *     a few steps in from the door and to one side of the way up the middle, facing the door, with room in front for
 *     the one being served and behind for the clerk.</li>
 * <li><b>The clerk.</b> The town's elder, or one of its council, when one is at the hall; else nobody (the book lies
 *     open on the desk and the player signs it themselves).</li>
 * <li><b>The book's place.</b> The register, the papers and a change of name are signed here, at the desk. A town with
 *     no hall yet keeps its book with the elder at the board: anywhere in the town will do till the hall stands.</li>
 * </ul>
 */
public final class TownHallDesk implements ServicePoints.Desk {

    public static final TownHallDesk INSTANCE = new TownHallDesk();

    private TownHallDesk() {}

    /** The halls a desk goes in, the grandest first. */
    public static final String[] HALLS = { "townhall", "hall" };
    /** How near the desk the player must be to sign (blocks). */
    public static final double AT_DESK = 8.0;

    private static final Map<UUID, Long> FITTED = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ where

    /** The town's hall, if it has one standing. */
    @Nullable
    public static Ledger.Building hall(UUID village) {
        for (String s : HALLS) {
            Ledger.Building b = Villages.builtStructure(village, s);
            if (b != null && !Ledger.raising(village, b.anchor())) return b;
        }
        return null;
    }

    /** The desk, if the town's hall has one. */
    @Nullable
    public static BlockPos desk(ServerLevel level, UUID village) {
        List<ServicePoints.Point> pts = ServicePoints.of(level, village, ServiceRole.HALL);
        return pts.isEmpty() ? null : pts.get(0).pos();
    }

    /** Where "Show me" points for the Town Hall: its desk, else its door, else the village board, else the heart. */
    public static BlockPos showMe(ServerLevel level, Villages.Village v) {
        BlockPos d = desk(level, v.id());
        if (d != null) return d;
        Ledger.Building h = hall(v.id());
        if (h != null) return h.anchor().above();
        BlockPos board = VillageBoards.lectern(v.id());
        return board != null ? board : v.centre();
    }

    /** What "Show me" calls it. */
    public static String showMeLabel(ServerLevel level, Villages.Village v) {
        if (desk(level, v.id()) != null) return "The Town Hall desk";
        if (hall(v.id()) != null) return "The Town Hall";
        return VillageBoards.lectern(v.id()) != null ? "The village board (the town's book)" : "The town's heart";
    }

    /** Is the player where the town's book is signed: at the desk, or anywhere in a town with no desk yet? */
    public static boolean atDesk(ServerPlayer p, Villages.Village v) {
        BlockPos d = desk(p.serverLevel(), v.id());
        if (d == null) return p.blockPosition().distSqr(v.centre()) <= (double) Villages.VILLAGE_RANGE * Villages.VILLAGE_RANGE;
        return p.distanceToSqr(d.getX() + 0.5, d.getY() + 0.5, d.getZ() + 0.5) <= AT_DESK * AT_DESK;
    }

    /** Why the player can't sign here, for a resting button ("" if they can). */
    public static String whyNotHere(ServerPlayer p, Villages.Village v) {
        return atDesk(p, v) ? "" : "Signed at the Town Hall desk: press Show me and walk there.";
    }

    /** Where a resident wakes: just in front of the desk, else the hall's door, else beside the heart. */
    @Nullable
    public static BlockPos spawnFor(ServerLevel level, Villages.Village v) {
        List<ServicePoints.Point> pts = ServicePoints.of(level, v.id(), ServiceRole.HALL);
        if (!pts.isEmpty()) {
            ServicePoints.Point pt = pts.get(0);
            BlockPos front = pt.pos().relative(pt.front());
            if (StoreFloor.standable(level, front)) return front;
        }
        Ledger.Building h = hall(v.id());
        if (h != null) {
            BlockPos in = h.anchor().relative(h.facing(), 2).above();
            if (StoreFloor.standable(level, in)) return in;
        }
        BlockPos c = v.centre();
        return level.isLoaded(c) ? level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.offset(2, 0, 2)) : null;
    }

    /** Where the desk was set (Ledger note): kept, as it stands in a cell its hall's drawing leaves empty. */
    static final String NOTE = "hall.desk";

    /** The desk the town set in, as a point of the HALL (ServicePoints.of): if it still stands in its hall. */
    static void known(ServerLevel level, UUID village, List<ServicePoints.Point> out) {
        String n = Ledger.note(village, NOTE);
        if (n == null || n.isEmpty() || !out.isEmpty()) return;
        BlockPos at;
        try { at = BlockPos.of(Long.parseLong(n)); } catch (NumberFormatException e) { return; }
        if (!level.isLoaded(at)) return;
        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(at);
        if (!(s.getBlock() instanceof com.jrpetty.mcassistant.block.ServiceCounterBlock)) return;
        Ledger.Building h = hall(village);
        if (h == null || h.anchor().distSqr(at) > 24 * 24) return;
        Direction front = s.getValue(com.jrpetty.mcassistant.block.ServiceCounterBlock.FACING);
        BlockPos stand = StoreFloor.standable(level, at.relative(front.getOpposite())) ? at.relative(front.getOpposite()).immutable() : null;
        out.add(new ServicePoints.Point(ServiceRole.HALL, village, at.immutable(), front, stand, h));
    }

    // ------------------------------------------------------------------ the clerk

    /** Who keeps the desk now: the elder or a councillor at the hall (near the desk), else nobody. */
    @Nullable
    public static VillageFolkEntity clerk(ServerLevel level, UUID village, @Nullable ServicePoints.Point point) {
        if (point == null) return null;
        BlockPos at = point.pos();
        VillageFolkEntity best = null;
        double bd = 12.0 * 12.0;
        UUID elder = Villages.elder(village);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isSleeping()) continue;
            boolean civic = f.getUUID().equals(elder) || Council.members(village).contains(f);
            if (!civic) continue;
            double d = f.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            if (d < bd) { bd = d; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the fit-out

    /** Once a day: each town's hall without a desk has one set in. */
    static void fitOut(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        if (FITTED.getOrDefault(v.id(), -1L) == day) return;
        FITTED.put(v.id(), day);
        Ledger.Building h = hall(v.id());
        if (h == null || !level.isLoaded(h.anchor())) return;
        if (!ServicePoints.of(level, v.id(), ServiceRole.HALL).isEmpty()) return;
        fit(level, v, h, false);
    }

    /** Tests and the stage: a desk in this hall now, for nothing. Returns where, or null. */
    @Nullable
    public static BlockPos fitForTests(ServerLevel level, Villages.Village v) {
        Ledger.Building h = hall(v.id());
        if (h == null) return null;
        BlockPos at = fit(level, v, h, true);
        ServicePoints.resetPointsForStage();
        return at;
    }

    /**
     * The spot for the desk in this hall: inside, three to eight steps from the door and off the way in from it, on the
     * floor, with room in front for the one being served and behind for the clerk; the nearest such to the door.
     */
    @Nullable
    static BlockPos spot(ServerLevel level, Ledger.Building h) {
        BlockPos door = TownLife.fittings(h).door();
        if (door == null) return null;
        java.util.Set<BlockPos> solid = new java.util.HashSet<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement pl : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan(h.structure(), h.anchor(), h.facing(), 13)) {
            if (pl.part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.CLEAR) continue;
            solid.add(pl.pos());
            if (pl.pos().getY() != door.getY()) continue;
            x0 = Math.min(x0, pl.pos().getX()); x1 = Math.max(x1, pl.pos().getX());
            z0 = Math.min(z0, pl.pos().getZ()); z1 = Math.max(z1, pl.pos().getZ());
        }
        List<BlockPos> empty = new ArrayList<>();                           // the cells its drawing leaves empty on the door's floor
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            BlockPos c = new BlockPos(x, door.getY(), z);
            if (!solid.contains(c)) empty.add(c);
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos c : empty) {
            boolean roofed = false;                                         // inside: under the hall's own roof
            for (int up = 2; up <= 8 && !roofed; up++) roofed = solid.contains(c.above(up));
            if (!roofed) continue;
            double d = c.distSqr(door);
            if (d < 9 || d > 64 || d >= bd) continue;
            Direction front = StoreFloor.front(c, door);
            int lateral = Math.abs(front.getAxis() == Direction.Axis.X ? c.getZ() - door.getZ() : c.getX() - door.getX());
            if (lateral < 2) continue;                                      // never in the way in
            if (!level.isLoaded(c) || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir()) continue;
            if (!level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) continue;
            if (!StoreFloor.standable(level, c.relative(front)) || !StoreFloor.standable(level, c.relative(front.getOpposite()))) continue;
            best = c.immutable();
            bd = d;
        }
        return best;
    }

    @Nullable
    private static BlockPos fit(ServerLevel level, Villages.Village v, Ledger.Building h, boolean free) {
        BlockPos at = spot(level, h);
        if (at == null) return null;
        if (!TownBlocks.mayBuild(level, v, at, h.anchor(), TownBlocks.Why.RENOVATE)) return null;
        if (!free && !Crafts.usePlanks(level, v, 4)) return null;          // three slabs and the sides, as any counter
        BlockPos door = TownLife.fittings(h).door();
        Direction front = StoreFloor.front(at, door);                      // toward the door, where the visitor stands
        level.setBlock(at, McAssistantMod.SERVICE_COUNTER.get().defaultBlockState()
            .setValue(com.jrpetty.mcassistant.block.ServiceCounterBlock.FACING, front), 3);
        Ledger.note(v.id(), NOTE, Long.toString(at.asLong()));
        ServicePoints.resetPointsForStage();
        if (!free) Villages.tell(v.id(), level.getDayTime() / 24000L, "a desk was set in the Town Hall for the town's book");
        com.mojang.logging.LogUtils.getLogger().info("[wpa] {}: the Town Hall's desk set in at {} {} {}", Villages.name(v.id()), at.getX(), at.getY(), at.getZ());
        return at;
    }

    // ------------------------------------------------------------------ the desk, as a service screen

    @Override
    public String kind(ServiceRole role) {
        return "desk";
    }

    /** Seldom seen (a click on the desk opens the Town Hall's page): a line and the way to the page. */
    @Override
    public CompoundTag body(ServicePoints.Visit visit, String tab) {
        CompoundTag b = new CompoundTag();
        ListTag rows = new ListTag();
        CompoundTag r = new CompoundTag();
        r.putString("label", "The Town Hall");
        r.putString("sub", "Register, papers, citizenship, taxes, notices and the treasury");
        r.putString("action", "hall");
        r.putString("button", "Open");
        rows.add(r);
        b.put("rows", rows);
        return b;
    }

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        if (visit.player() != null) TownHall.openAtDesk(visit.player(), visit.point() == null ? null : visit.point().pos());
        return ServicePoints.Result.said("");
    }

    static void resetForTests() {
        FITTED.clear();
    }

    /** Names of the halls, for the guide's words. */
    public static List<String> hallWords() {
        List<String> out = new ArrayList<>();
        for (String s : HALLS) out.add(Villages.spoken(s));
        return out;
    }
}
