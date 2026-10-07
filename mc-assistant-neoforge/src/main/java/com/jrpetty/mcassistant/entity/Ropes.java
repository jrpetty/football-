package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.RopeBlock;
import com.jrpetty.mcassistant.item.WorkItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [workitems] Ropes in the folk's hands: let down where there is no walking down, climbed down and back up.
 * <ul>
 * <li><b>The cave team</b> carries two coils (WorkTools.caveKitUp). Where the path-finder can find no way down to the cave
 *     the leader is making for (a ravine, a pit), the leader looks along the edge it stands on for a drop that comes down
 *     nearer the cave, lets a rope down it (lowerToward), and climbs down; the others follow it down the rope (follow). On
 *     the way home the leader climbs back up it and the others after it, and the last one up takes the rope up, the coil
 *     back in its pack (homeward). So the team gets at caves it had to pass by.</li>
 * <li><b>The miners</b> carry one. Cutting its stairs down, a miner that comes to a shaft (four blocks of open drop or more
 *     under its next step, the bottom in reach of a coil) lets its rope down the shaft and climbs down it to go on from the
 *     bottom (downTheShaft); coming home it climbs up it again (upTheShaft). The rope stays as the mine's way down.</li>
 * </ul>
 * A climb is a ride on the rope a tick at a time (ride, from the folk's own tick), hand over hand: to the edge, over it,
 * and down; or to the foot of the rope, and up and over the edge.
 */
public final class Ropes {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Ropes() {}

    /** A shaft is so deep at the least; a ravine's drop so deep at the least to be worth a rope. */
    static final int SHAFT = 4, DROP = 3;
    /** Climbing, a tick: up, and down. */
    static final double UP = 0.18, DOWN = 0.24;
    /** A ride given up after so long (ticks). */
    static final long TOO_LONG = 1200L;
    /** How far along the edge the leader looks for a drop. */
    static final int LOOK_ALONG = 5;

    /** A rope: its top piece, the wall it hangs down beside (its edge that way at the top), its lowest piece. */
    public record Rope(BlockPos top, Direction wall, BlockPos bottom) {
        /** Where a folk stands on the edge to get on or off it. */
        public BlockPos stand() { return top.relative(wall).above(); }
    }

    /** A folk on a rope: which, which way, and how far it has got. */
    static final class Ride {
        final Rope rope;
        final boolean down;
        int stage;
        final long started;
        double y;
        int walkTick = -1000;

        Ride(Rope rope, boolean down, long started) {
            this.rope = rope;
            this.down = down;
            this.started = started;
        }
    }

    private static final Map<UUID, Ride> RIDES = new ConcurrentHashMap<>();
    /** The cave parties' ropes, by party. */
    private static final Map<CaveDwellers.Party, Rope> PARTY = Collections.synchronizedMap(new WeakHashMap<>());
    /** The tops of the ropes hanging (by level and place). */
    private static final Set<String> HANGING = ConcurrentHashMap.newKeySet();

    static void resetForTests() {
        RIDES.clear();
        PARTY.clear();
        HANGING.clear();
    }

    /** RopeBlock: a rope's top piece set, or gone. */
    public static void hung(ServerLevel level, BlockPos top) {
        HANGING.add(level.dimension().location() + "/" + top.asLong());
    }

    public static void gone(ServerLevel level, BlockPos top) {
        HANGING.remove(level.dimension().location() + "/" + top.asLong());
    }

    /** The rope whose top piece is here, or null. */
    @Nullable
    static Rope at(ServerLevel level, BlockPos top) {
        BlockState s = level.getBlockState(top);
        if (!(s.getBlock() instanceof RopeBlock) || s.getValue(RopeBlock.PART) != RopeBlock.Part.TOP) return null;
        return new Rope(top, s.getValue(RopeBlock.FACING), RopeBlock.bottomOf(level, top));
    }

    /** Is the ground firm under this rope's lowest piece (it reaches the bottom)? */
    static boolean reaches(ServerLevel level, Rope r) {
        BlockPos under = r.bottom().below();
        return level.getBlockState(under).isFaceSturdy(level, under, Direction.UP) && level.getFluidState(under).isEmpty();
    }

    /** How many clear cells there are down from here (no more than a coil's length), and whether firm ground ends them. */
    static int drop(ServerLevel level, BlockPos top, boolean[] ground) {
        int n = 0;
        while (n < RopeBlock.LONGEST) {
            BlockPos p = top.below(n);
            BlockState s = level.getBlockState(p);
            if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) break;
            n++;
        }
        BlockPos floor = top.below(n);
        ground[0] = n < RopeBlock.LONGEST && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
            && level.getFluidState(floor).isEmpty() && level.getFluidState(floor.above()).isEmpty()
            && !level.getBlockState(floor).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
        return n;
    }

    // ------------------------------------------------------------------ the ride

    public static boolean riding(AssistantEntity a) {
        return !RIDES.isEmpty() && RIDES.containsKey(a.getUUID());
    }

    /** Start a folk on a rope: down it, or up it. */
    static void start(VillageFolkEntity f, Rope r, boolean down) {
        Ride ride = new Ride(r, down, f.level().getGameTime());
        RIDES.put(f.getUUID(), ride);
        f.getNavigation().stop();
        f.brain(down ? "climbing down a rope" : "climbing up a rope");
    }

    /**
     * A folk on a rope, a tick of its climb (VillageFolkEntity.aiStep): true while it is on it (its own day waits). To
     * the edge (or the foot), onto the rope, hand over hand down (or up), and off at the foot (or over the edge).
     */
    public static boolean ride(VillageFolkEntity f) {
        if (RIDES.isEmpty()) return false;
        Ride r = RIDES.get(f.getUUID());
        if (r == null || !(f.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        Rope rope = r.rope;
        if (now - r.started > TOO_LONG || now < r.started || !f.isAlive() || at(level, rope.top()) == null) {
            RIDES.remove(f.getUUID());
            f.setNoGravity(false);
            return false;
        }
        double cx = rope.top().getX() + 0.5, cz = rope.top().getZ() + 0.5;
        if (r.stage == 0) {
            // To where it gets on: the edge over the rope, or the rope's foot.
            BlockPos to = r.down ? rope.stand() : rope.bottom();
            double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
            if (dx * dx + dz * dz <= 0.9 * 0.9 && Math.abs(f.getY() - to.getY()) < 1.3) {
                r.stage = 1;
                r.y = r.down ? rope.top().getY() + 1.0 : f.getY();
                f.getNavigation().stop();
                f.setNoGravity(true);
                level.playSound(null, f.blockPosition(), SoundEvents.LADDER_STEP, SoundSource.NEUTRAL, 0.7F, 1.0F);
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - r.walkTick > 40) {
                f.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0D);
                r.walkTick = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        f.setDeltaMovement(Vec3.ZERO);
        f.fallDistance = 0;
        // Face the wall it hangs on, hands on the rope.
        f.setYRot(r.rope.wall().toYRot());
        f.setYHeadRot(r.rope.wall().toYRot());
        if (f.tickCount % 8 == 0) f.swing(f.tickCount % 16 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        if (f.tickCount % 10 == 0) level.playSound(null, f.blockPosition(), SoundEvents.LADDER_STEP, SoundSource.NEUTRAL, 0.5F, 1.1F);
        if (r.down) {
            double floor = rope.bottom().getY();
            r.y = Math.max(floor, r.y - DOWN);
            f.setPos(cx, r.y, cz);
            if (r.y <= floor + 1e-3) finish(level, f, r);
        } else {
            double over = rope.top().getY() + 1.0;
            r.y = Math.min(over, r.y + UP);
            f.setPos(cx, r.y, cz);
            if (r.y >= over - 1e-3) {
                // Over the edge: a step onto the ground it hangs from.
                BlockPos s = rope.stand();
                f.setPos(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
                finish(level, f, r);
            }
        }
        return true;
    }

    private static void finish(ServerLevel level, VillageFolkEntity f, Ride r) {
        RIDES.remove(f.getUUID());
        f.setNoGravity(false);
        f.setDeltaMovement(Vec3.ZERO);
        f.fallDistance = 0;
        f.brain(r.down ? "down the rope" : "up the rope");
        if (!r.down) pullUpAfter(level, f, r.rope);
    }

    // ------------------------------------------------------------------ the cave team

    /**
     * No way down to the cave the leader is making for (CaveDwellers.walkOut): with a coil in its pack and none of the
     * party's let down already, it looks along the edge it stands on for a drop that comes down nearer the cave (three
     * blocks deep at the least, the bottom firm and in reach of a coil, no lava at it), lets its rope down it, and climbs
     * down. True if it did.
     */
    public static boolean lowerToward(ServerLevel level, VillageFolkEntity lead, BlockPos target, CaveDwellers.Party p) {
        if (PARTY.containsKey(p) || lead.countCarried(WorkTools::rope) == 0) return false;
        BlockPos feet = lead.blockPosition();
        double here = feet.distSqr(target);
        Object[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -LOOK_ALONG; dx <= LOOK_ALONG; dx++) {
            for (int dz = -LOOK_ALONG; dz <= LOOK_ALONG; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos stand = feet.offset(dx, dy, dz);
                    BlockPos edge = stand.below();
                    if (!level.getBlockState(stand).isAir() || !level.getBlockState(stand.above()).isAir()
                            || !level.getBlockState(edge).isFaceSturdy(level, edge, Direction.UP)) continue;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos top = edge.relative(d);
                        if (!level.getBlockState(top.above()).isAir()) continue;
                        boolean[] ground = new boolean[1];
                        int n = drop(level, top, ground);
                        if (n < DROP || !ground[0]) continue;
                        BlockPos bottom = top.below(n - 1);
                        double d2 = bottom.distSqr(target);
                        if (d2 >= here) continue;                                      // no nearer the cave down there
                        double score = d2 + stand.distSqr(feet) * 4.0;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new Object[]{ top, d.getOpposite() };
                        }
                    }
                }
            }
        }
        if (best == null) return false;
        BlockPos top = (BlockPos) best[0];
        Direction wall = (Direction) best[1];
        int n = RopeBlock.hang(level, top, wall, RopeBlock.LONGEST, WorkItems.ROPE.get());
        if (n <= 0 || lead.removeMatching(WorkTools::rope, 1) != 1) {
            if (n > 0) RopeBlock.takeUp(level, top);
            return false;
        }
        Rope rope = new Rope(top, wall, RopeBlock.bottomOf(level, top));
        PARTY.put(p, rope);
        start(lead, rope, true);
        FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "No path down — rope it is. Follow me down, one at a time.",
            "Rope's down! Mind your hands on the way.", "Down the rope, everybody. I'll go first."));
        long day = level.getDayTime() / 24000L;
        WorkTools.bump(p.village, "work.caverope");
        Villages.tell(p.village, day, lead.displayNameCap() + " let a rope down " + n + " blocks for the cave team, to get at a cave there was no walking down to");
        LOG.info("[MCA-CAVES] {} let a rope down {} blocks at {} toward {}", lead.displayNameCap(), n, top.toShortString(), target.toShortString());
        return true;
    }

    /**
     * One of the team, following its leader (CaveDwellers.follow): the leader gone down the party's rope while it is up top,
     * or up it while it is down below, and it takes the rope after it. True while it is at it.
     */
    public static boolean follow(ServerLevel level, VillageFolkEntity f, VillageFolkEntity lead, CaveDwellers.Party p) {
        if (riding(f)) return true;
        Rope rope = PARTY.get(p);
        if (rope == null || riding(lead)) return false;
        if (at(level, rope.top()) == null) {
            PARTY.remove(p);
            return false;
        }
        int topY = rope.top().getY();
        boolean meUp = f.getY() >= topY + 0.5, leadUp = lead.getY() >= topY + 0.5;
        boolean meDown = f.getY() < topY - 1, leadDown = lead.getY() < topY - 1;
        if (meUp && leadDown) {
            start(f, rope, true);
            return true;
        }
        if (meDown && leadUp && f.blockPosition().distSqr(rope.bottom()) <= 40 * 40) {
            start(f, rope, false);
            return true;
        }
        return false;
    }

    /**
     * The leader on the way home (CaveDwellers.walkHome), below the party's rope: up it, the others to follow.
     * True while it is at it.
     */
    public static boolean homeward(ServerLevel level, VillageFolkEntity lead, CaveDwellers.Party p) {
        if (riding(lead)) return true;
        Rope rope = PARTY.get(p);
        if (rope == null) return false;
        if (at(level, rope.top()) == null) {
            PARTY.remove(p);
            return false;
        }
        if (lead.getY() >= rope.top().getY() - 1 || lead.blockPosition().distSqr(rope.bottom()) > 48 * 48) return false;
        start(lead, rope, false);
        FolkTalk.speak(lead, FolkTalk.pick(lead.getRandom(), "Back up the rope, everybody.", "Up we go — the rope's where we left it."));
        return true;
    }

    /** Up a party's rope: if none of the party is still below it, the last one up takes it up, the coil back in its pack. */
    static void pullUpAfter(ServerLevel level, VillageFolkEntity f, Rope rope) {
        CaveDwellers.Party party = null;
        synchronized (PARTY) {
            for (Map.Entry<CaveDwellers.Party, Rope> e : PARTY.entrySet()) {
                if (e.getValue().top().equals(rope.top())) { party = e.getKey(); break; }
            }
        }
        if (party == null) return;
        if (!party.homeward) return;                                              // still out: it is their way back
        // Any of the party alive and still down there near its foot (or on it) keeps it hanging: their way up.
        for (UUID u : party.members) {
            if (u.equals(f.getUUID()) || !(level.getEntity(u) instanceof VillageFolkEntity m) || !m.isAlive()) continue;
            if (riding(m)) return;
            if (m.getY() < rope.top().getY() - 1 && m.blockPosition().distSqr(rope.bottom()) <= 48 * 48) return;
        }
        if (RopeBlock.takeUp(level, rope.top()) > 0) {
            ItemStack coil = new ItemStack(WorkItems.ROPE_COIL.get());
            ItemStack left = f.insertGiven(coil);
            if (!left.isEmpty()) f.spawnAtLocation(left);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Rope's up. Coil it and away.", "That's everybody — up comes the rope."));
            f.brain("took the cave team's rope up after the last of them");
        }
        PARTY.remove(party);
    }

    /** Tests: the party's rope (its top), or null. */
    @Nullable
    public static BlockPos partyRopeForTests(CaveDwellers.Party p) {
        Rope r = PARTY.get(p);
        return r == null ? null : r.top();
    }

    // ------------------------------------------------------------------ the miners' shafts

    /**
     * A miner's next step down its stairs is over a shaft (MineGoal.planStep): four or more blocks of open drop under it,
     * firm ground at the bottom within a coil's reach, no lava there. It lets its rope down the shaft from the edge it
     * stands on (or takes the one hanging there already) and climbs down. Where its feet will be at the bottom (the step
     * it goes on from), or null to cut its stairs as ever.
     */
    @Nullable
    public static BlockPos downTheShaft(AssistantEntity a, BlockPos cursor, BlockPos newFeet) {
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level) || f.ownerId() == null) return null;
        BlockPos over = newFeet.above();
        Direction out = null;
        for (Direction d : Direction.Plane.HORIZONTAL) if (cursor.relative(d).equals(over)) out = d;
        if (out == null) return null;
        Rope rope = at(level, newFeet);
        if (rope == null) {
            BlockState o = level.getBlockState(over), o2 = level.getBlockState(over.above());
            if (!o.isAir() || !(o2.isAir() || o2.canBeReplaced())) return null;
            boolean[] ground = new boolean[1];
            int n = drop(level, newFeet, ground);
            if (n < SHAFT || !ground[0]) return null;
            BlockPos floor = newFeet.below(n);
            if (floor.getY() < level.getMinBuildHeight() + 10 || lavaNear(level, newFeet.below(n - 1))) return null;
            if (f.countCarried(WorkTools::rope) == 0) return null;
            int hung = RopeBlock.hang(level, newFeet, out.getOpposite(), RopeBlock.LONGEST, WorkItems.ROPE.get());
            if (hung <= 0) return null;
            if (f.removeMatching(WorkTools::rope, 1) != 1) {
                RopeBlock.takeUp(level, newFeet);
                return null;
            }
            rope = new Rope(newFeet, out.getOpposite(), RopeBlock.bottomOf(level, newFeet));
            WorkTools.bump(f.ownerId(), "work.shafts");
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A shaft! Rope down, and saves me a hundred steps.", "Down the rope I go."));
            LOG.info("[MCA-MINE] {} let its rope down a shaft at {} ({} blocks)", f.displayNameCap(), newFeet.toShortString(), hung);
        }
        if (!reaches(level, rope) || !rope.stand().equals(cursor)) return null;
        start(f, rope, true);
        return rope.bottom().immutable();
    }

    /**
     * A miner on its way up its stairs (MineGoal.moveTick), its next step three or more blocks over it at the top of a
     * rope it came down: up the rope. True if it took to it.
     */
    public static boolean upTheShaft(AssistantEntity a, BlockPos cursor, BlockPos dest) {
        if (dest.getY() - cursor.getY() < 3 || !(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level)) return false;
        if (riding(f)) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Rope rope = at(level, dest.below().relative(d));
            if (rope == null || !rope.stand().equals(dest)) continue;
            if (rope.bottom().distSqr(cursor) > 3 * 3) continue;
            start(f, rope, false);
            return true;
        }
        return false;
    }

    private static boolean lavaNear(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        return false;
    }

    // ------------------------------------------------------------------ shown

    /** On a rope just now, for its card; or empty. */
    static String cardWords(VillageFolkEntity f) {
        Ride r = RIDES.get(f.getUUID());
        return r == null ? "" : r.down ? "on a rope, climbing down" : "on a rope, climbing up";
    }

    /** The ropes' lines for /village items work. */
    static List<String> report(UUID village) {
        int cave = WorkTools.count(village, "work.caverope");
        if (cave == 0) return List.of();
        return List.of("Rope coils: the cave team has let its rope down " + cave + (cave == 1 ? " time" : " times")
            + " to get at a cave there was no walking down to.");
    }

    /** Tests: a cave party of these folk, the first leading it, out (or, {@code homeward}, on its way home). */
    public static CaveDwellers.Party partyForTests(UUID village, long day, List<VillageFolkEntity> team) {
        CaveDwellers.Party p = new CaveDwellers.Party(village, day);
        for (VillageFolkEntity f : team) p.members.add(f.getUUID());
        if (!team.isEmpty()) p.leader = team.get(0).getUUID();
        return p;
    }

    /** Tests: the party turned for home. */
    public static void homewardForTests(CaveDwellers.Party p) {
        p.homeward = true;
    }

    /** Tests: is this folk on a rope, and is it going down? {riding, down}. */
    public static boolean[] ridingForTests(VillageFolkEntity f) {
        Ride r = RIDES.get(f.getUUID());
        return new boolean[]{ r != null, r != null && r.down };
    }

    /** Tests: a folk set on this rope now, down it or up it. */
    public static boolean startForTests(VillageFolkEntity f, BlockPos top, boolean down) {
        if (!(f.level() instanceof ServerLevel level)) return false;
        Rope r = at(level, top);
        if (r == null) return false;
        start(f, r, down);
        return true;
    }
}
