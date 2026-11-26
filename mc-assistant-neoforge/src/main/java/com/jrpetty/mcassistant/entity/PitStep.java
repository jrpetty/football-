package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tp50] Steps dug up out of a pit a folk cannot walk out of.
 *
 * <p>A slot two or three blocks deep under the open sky with no way up out of it (the gap left between a sunken house's
 * retaining wall and the hillside it was cut into) is a place the pathfinder drops a folk into, the end of a walk that could
 * not get where it was going, and never plans it out of: its sides are a block too high to step up. In the day-64 soak half
 * of the big town stood in one such slot from the afternoon to the morning, "on its way to bed", in a player's sight, where
 * no folk is ever lifted out. Now a folk that has stood a while in a pit with no way out digs itself a stair up its side, as
 * anybody would with a spade: two or three blocks of earth or stone cut from the bank, a swing at a time, and it walks up
 * them. The stair is left: the next to fall in walks out by it. Only natural ground is cut (never a wall, a floor or
 * anything built), never beside water, never under anything that would fall.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PitStep {

    private PitStep() {}

    /** A walkable patch bigger than this is no pit. */
    static final int MOST_CELLS = 400;
    /** Ticks a folk stands within a couple of blocks of one spot before it is looked at. */
    static final int STILL_FOR = 300;
    /** And looked at again no sooner than this. */
    static final int LOOK_AGAIN = 600;
    /** Ticks between two swings of the spade. */
    static final int SWING = 10;
    /** The drop a folk will take on its way out (the pathfinder's most, where there is no other way). */
    static final int DROP = 5;

    private static final class Still {
        BlockPos at;
        long since;
        long looked = Long.MIN_VALUE / 2;
        Still(BlockPos at, long since) { this.at = at; this.since = since; }
    }

    private static final class Dig {
        final List<BlockPos> cells;
        final BlockPos from;
        /** Steps set down (a block in each cell), not cut. */
        final boolean build;
        final List<BlockPos> pit;
        int next, waited;
        long nextAt;
        Dig(List<BlockPos> cells, List<BlockPos> pit, boolean build, long nextAt) {
            this.cells = cells; this.pit = pit; this.from = pit.get(0); this.build = build; this.nextAt = nextAt;
        }
    }

    private static final Map<UUID, Still> STILL = new ConcurrentHashMap<>();
    private static final Map<UUID, Dig> DIGS = new ConcurrentHashMap<>();
    /** Stairs dug lately (where they start, and when): one stair to a pit. */
    private static final Map<BlockPos, Long> DUG = new ConcurrentHashMap<>();
    /** The tests and the census: stairs made, blocks cut, blocks set down. */
    public static volatile int STAIRS, CUT, SET;

    public static void resetForTests() {
        STILL.clear();
        DIGS.clear();
        DUG.clear();
        STAIRS = 0;
        CUT = 0;
        SET = 0;
    }

    /** Is this folk digging its way up out of a pit just now? */
    public static boolean digging(AssistantEntity f) {
        return DIGS.containsKey(f.getUUID());
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        ServerLevel any = e.getServer().overworld();
        long now = any.getGameTime();
        if (!DIGS.isEmpty()) dig(e.getServer(), now);
        if (now % 20 != 0) return;
        Set<UUID> seen = new HashSet<>();
        for (Villages.Village v : Villages.every()) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level)) continue;
                seen.add(f.getUUID());
                look(level, f, level.getGameTime());
            }
        }
        STILL.keySet().retainAll(seen);
        DIGS.keySet().retainAll(seen);
        if (now % 1200 == 0) DUG.entrySet().removeIf(x -> now - x.getValue() > 24000L || x.getValue() > now);
    }

    /** A folk's look-in: still a while on the ground under the sky, not digging? Then is it in a pit, and is there a stair to dig? */
    private static void look(ServerLevel level, VillageFolkEntity f, long now) {
        if (!f.isAlive() || f.isSleeping() || f.isPassenger() || !f.onGround() || f.isInWaterOrBubble() || f.isBaby()) {
            STILL.remove(f.getUUID());
            return;
        }
        BlockPos feet = f.blockPosition();
        Still s = STILL.get(f.getUUID());
        if (s == null || s.at.distManhattan(feet) > 6 || now < s.since) {             // (jostled to and fro along a slot it is still in it)
            STILL.put(f.getUUID(), new Still(feet, now));
            return;
        }
        if (DIGS.containsKey(f.getUUID()) || now - s.since < STILL_FOR || now - s.looked < LOOK_AGAIN) return;
        // Only one going somewhere it cannot get to (its walk ending short): a builder down its cellar is at its work.
        net.minecraft.world.level.pathfinder.Path way = f.getNavigation().getPath();
        if (way == null || way.canReach() || f.running(com.jrpetty.mcassistant.entity.goal.BuildGoal.class)) return;
        s.looked = now;
        if (MineWorks.below(f)) return;                                  // down the works: the mine's own climb (MineSafety)
        List<BlockPos> pit = pit(level, feet);
        if (pit == null) return;
        for (Map.Entry<BlockPos, Long> d : DUG.entrySet()) {
            if (now - d.getValue() < 2400L && d.getKey().distManhattan(feet) <= 16) return;   // a stair dug in this pit lately: walk up it
        }
        for (Dig d : DIGS.values()) if (d.from.distManhattan(feet) <= 16) return;             // another at it already
        List<BlockPos> stair = stair(level, pit, feet);
        boolean build = false;
        if (stair == null) {
            // No natural bank to cut steps in (a brick wall all round, a quay, a bridge's end): steps set down against its side.
            stair = steps(level, pit, feet);
            build = true;
        }
        if (stair == null) return;
        DIGS.put(f.getUUID(), new Dig(stair, pit, build, now + SWING));
        f.getNavigation().stop();
        f.brain(build ? "no way up out of a pit: setting steps up against its side" : "no way up out of a pit: digging steps up its side");
    }

    /** The digs under way: a swing every half a second, a block cut each, up the stair from its foot. */
    private static void dig(net.minecraft.server.MinecraftServer server, long now) {
        for (Iterator<Map.Entry<UUID, Dig>> it = DIGS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Dig> e = it.next();
            Dig d = e.getValue();
            UUID id = e.getKey();
            if (now < d.nextAt) continue;
            VillageFolkEntity f = null;
            for (ServerLevel l : server.getAllLevels()) {
                if (l.getEntity(id) instanceof VillageFolkEntity vf) { f = vf; break; }
            }
            int i = d.next;
            if (f == null || !f.isAlive() || f.isSleeping() || i >= d.cells.size()) { it.remove(); continue; }
            BlockPos cell = d.cells.get(i);
            Level level = f.level();
            net.minecraft.world.phys.Vec3 mid = net.minecraft.world.phys.Vec3.atCenterOf(cell);
            if (f.getEyePosition().distanceToSqr(mid) > 6.0 * 6.0) { it.remove(); continue; }   // pushed off from it: given up
            f.getLookControl().setLookAt(mid.x, mid.y, mid.z, 30.0F, 30.0F);
            if (d.build) {
                // Never set down where anybody stands: it waits for them to step off (a while), then gives it up.
                List<net.minecraft.world.entity.Entity> on = level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(cell));
                if (!on.isEmpty()) {
                    if (on.contains(f)) {
                        // Standing on the spot itself: a step off it, to the nearest pit cell that is not one of the steps.
                        BlockPos off = null;
                        for (BlockPos p : d.pit) {
                            if (d.cells.contains(p) || p.distManhattan(cell) < 2) continue;
                            if (off == null || p.distSqr(cell) < off.distSqr(cell)) off = p;
                        }
                        if (off != null) f.getNavigation().moveTo(off.getX() + 0.5, off.getY(), off.getZ() + 0.5, 1.0D);
                    }
                    d.nextAt = now + SWING;
                    if (++d.waited > 30) it.remove();
                    continue;
                }
                f.getNavigation().stop();
                f.swing(InteractionHand.MAIN_HAND);
                if (clear(level, cell)) {
                    level.setBlock(cell, STEP, 3);
                    level.playSound(null, cell, net.minecraft.sounds.SoundEvents.STONE_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
                    SET++;
                }
            } else {
                f.getNavigation().stop();
                f.swing(InteractionHand.MAIN_HAND);
                if (diggable(level.getBlockState(cell)) && safe(level, cell)) {
                    level.destroyBlock(cell, false, f);
                    CUT++;
                }
            }
            d.next = ++i;
            d.nextAt = now + SWING;
            if (i >= d.cells.size()) {
                it.remove();
                f.getNavigation().stop();                                    // a fresh way, up the stair
                DUG.put(d.cells.get(0).immutable(), now);
                STAIRS++;
                f.brain(d.build ? "set steps up out of a pit" : "dug steps up out of a pit");
                com.mojang.logging.LogUtils.getLogger().info("[MCA-PIT] {} {} steps up out of a pit at {}", f.getAssistantName(), d.build ? "set" : "dug", d.cells.get(0).toShortString());
            }
        }
    }

    // ------------------------------------------------------------------ the pit and its stair

    /** Room to stand: nothing to bump into where its feet and head go, a floor under it to stand on. */
    static boolean standable(Level level, BlockPos p) {
        return clear(level, p) && clear(level, p.above()) && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    private static boolean clear(Level level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.getCollisionShape(level, p).isEmpty() && st.getFluidState().isEmpty();
    }

    /** A way out the walk can take that the flood below does not follow: a door, a gate, a hatch, a ladder, a vine. (Water is
     *  no way out: a folk at its business keeps out of it, and a shore it came up onto under a steep bank is a pit.) */
    private static boolean wayOut(Level level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        Block b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.FenceGateBlock
            || b instanceof net.minecraft.world.level.block.TrapDoorBlock || st.is(net.minecraft.tags.BlockTags.CLIMBABLE)
            || b instanceof net.minecraft.world.level.block.ScaffoldingBlock || st.is(Blocks.LAVA);
    }

    /** The block a step is set down in. */
    private static final BlockState STEP = Blocks.COBBLESTONE.defaultBlockState();

    /**
     * The patch it can walk over from here, a step up or a drop down at a time, if it is a pit: no bigger than {@link #MOST_CELLS},
     * under the open sky, no door or ladder about it. Null if it is no pit (the patch runs on, or there is a way out).
     */
    @Nullable
    static List<BlockPos> pit(Level level, BlockPos start) {
        if (!standable(level, start)) {
            if (standable(level, start.above())) start = start.above();
            else return null;
        }
        List<BlockPos> cells = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(start);
        seen.add(start);
        boolean sky = false;
        while (!todo.isEmpty()) {
            BlockPos p = todo.poll();
            cells.add(p);
            if (cells.size() > MOST_CELLS) return null;
            if (!sky && level.canSeeSky(p)) sky = true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (wayOut(level, n) || wayOut(level, n.above())) return null;
                BlockPos next = null;
                if (standable(level, n)) next = n;
                else if (standable(level, n.above()) && clear(level, p.above(2))) next = n.above();
                else if (clear(level, n) && clear(level, n.above())) {
                    for (int k = 1; k <= DROP + 1; k++) {
                        BlockPos q = n.below(k);
                        if (wayOut(level, q)) return null;
                        if (standable(level, q)) { if (k <= DROP) next = q; break; }
                        if (!clear(level, q)) break;
                    }
                }
                if (next != null && seen.add(next.immutable())) todo.add(next.immutable());
            }
        }
        return sky ? cells : null;
    }

    /**
     * The blocks to cut for a stair up out of this pit, from its foot: up one side where the bank is natural ground and two or
     * three blocks above the pit's floor, the top step onto ground it can stand on. The pit cell nearest the folk first. Null if
     * there is no such side.
     */
    @Nullable
    static List<BlockPos> stair(Level level, List<BlockPos> pit, BlockPos near) {
        Set<BlockPos> in = new HashSet<>(pit);
        List<BlockPos> best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos a : pit) {
            if (!clear(level, a.above(2))) continue;                          // room to step up from it
            double da = a.distSqr(near);
            if (da >= bestD) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos e1 = a.relative(d);
                if (in.contains(e1) || in.contains(e1.above()) || in.contains(e1.below())) continue;
                List<BlockPos> cut = null;
                if (bank(level, e1, 2) && standable(level, e1.relative(d).above(2))) {
                    // Two high: the bank's top block cut, a step half way.
                    cut = List.of(e1.above());
                } else if (bank(level, e1, 3)) {
                    BlockPos e2 = e1.relative(d);
                    if (!level.getBlockState(e2.above()).getCollisionShape(level, e2.above()).isEmpty()
                            && diggable(level.getBlockState(e2.above(2))) && clear(level, e2.above(3)) && clear(level, e2.above(4))
                            && standable(level, e2.relative(d).above(3))) {
                        cut = List.of(e1.above(), e1.above(2), e2.above(2));
                    }
                }
                if (cut == null) continue;
                boolean ok = true;
                for (BlockPos c : cut) if (!diggable(level.getBlockState(c)) || !safe(level, c)) { ok = false; break; }
                if (!ok) continue;
                best = cut;
                bestD = da;
                break;
            }
        }
        return best;
    }

    /**
     * Steps to set down against the pit's side where it is two or three blocks high (any wall: bricks, a quay, a bridge's end),
     * the top step onto ground it can stand on: the cells, the lowest first. The pit cell nearest the folk first. Null if none.
     */
    @Nullable
    static List<BlockPos> steps(Level level, List<BlockPos> pit, BlockPos near) {
        Set<BlockPos> in = new HashSet<>(pit);
        List<BlockPos> best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos a : pit) {
            double da = a.distSqr(near);
            if (da >= bestD) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos e = a.relative(d);
                if (in.contains(e) || in.contains(e.above()) || in.contains(e.below())) continue;
                int h = standable(level, e.above(2)) && solid(level, e) && solid(level, e.above()) ? 2
                    : standable(level, e.above(3)) && solid(level, e) && solid(level, e.above()) && solid(level, e.above(2)) ? 3 : 0;
                if (h == 0) continue;
                List<BlockPos> cut = null;
                if (h == 2) {
                    // One block in the pit cell against the side; in from a pit cell beside it.
                    if (clear(level, a.above(2)) && clear(level, a.above(3)) && enterable(level, in, a, d))
                        cut = List.of(a.immutable());
                } else {
                    // Two in the cell against the side, one in the cell behind it.
                    BlockPos b = a.relative(d.getOpposite());
                    if (in.contains(b) && clear(level, a.above(2)) && clear(level, a.above(3)) && clear(level, a.above(4))
                            && clear(level, b.above(2)) && clear(level, b.above(3)) && enterable(level, in, b, d))
                        cut = List.of(b.immutable(), a.immutable(), a.above().immutable());
                }
                if (cut == null) continue;
                best = cut;
                bestD = da;
                break;
            }
        }
        return best;
    }

    /** A pit cell beside this one (not toward the side, nor the step behind) to step up onto it from. */
    private static boolean enterable(Level level, Set<BlockPos> in, BlockPos c, Direction toSide) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d == toSide || d == toSide.getOpposite()) continue;
            BlockPos n = c.relative(d);
            if (in.contains(n) && clear(level, n.above(2))) return true;
        }
        BlockPos back = c.relative(toSide.getOpposite());
        return in.contains(back) && clear(level, back.above(2));
    }

    private static boolean solid(Level level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.isCollisionShapeFullBlock(level, p);
    }

    /** The bank beside a pit cell (its column at the pit floor's height {@code at}): firm under, natural ground up to {@code h - 1}
     *  above, room to stand on top at {@code h} above. */
    private static boolean bank(Level level, BlockPos at, int h) {
        if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return false;
        for (int k = 1; k < h; k++) if (!diggable(level.getBlockState(at.above(k)))) return false;
        return clear(level, at.above(h)) && clear(level, at.above(h + 1));
    }

    /** Natural ground a spade cuts: earth, stone, gravel, sand, clay. Never anything built. */
    static boolean diggable(BlockState st) {
        return st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL) || st.is(Blocks.ROOTED_DIRT)
            || st.is(Blocks.MYCELIUM) || st.is(Blocks.STONE) || st.is(Blocks.ANDESITE) || st.is(Blocks.DIORITE) || st.is(Blocks.GRANITE)
            || st.is(Blocks.TUFF) || st.is(Blocks.DEEPSLATE) || st.is(Blocks.CALCITE) || st.is(Blocks.CLAY) || st.is(Blocks.GRAVEL)
            || st.is(Blocks.SAND) || st.is(Blocks.RED_SAND) || st.is(Blocks.SANDSTONE) || st.is(Blocks.TERRACOTTA) || st.is(Blocks.MUD)
            || st.is(Blocks.PACKED_MUD) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.SOUL_SAND) || st.is(Blocks.SOUL_SOIL) || st.is(Blocks.NETHERRACK);
    }

    /** Nothing to let in by cutting it: no water or lava beside it, nothing above it that falls. */
    private static boolean safe(Level level, BlockPos c) {
        for (Direction d : Direction.values()) if (!level.getFluidState(c.relative(d)).isEmpty()) return false;
        return !(level.getBlockState(c.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock);
    }
}
