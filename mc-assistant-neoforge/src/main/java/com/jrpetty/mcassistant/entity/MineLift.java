package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32c] The mine's cage: a lift down the middle of the shaft's pillar, hung from the headframe.
 *
 * <p>The shaft is a stair two blocks wide winding round a pillar of rock three across; a town past its second level walked
 * 193 steps down it and 193 up for every shift, a climb of a minute and a half to two minutes each way, and its miners
 * spent their shifts on the stair. Once the works are forty blocks deep (and the headframe up), the town cuts a well one
 * block across down the middle of the pillar from the pithead to its deepest landing:
 * <ul>
 * <li><b>The well</b>: its top capped with a trapdoor under the headframe, a chain hung from the headframe's beam to it,
 *     the rock of the pillar round it made good where a cave broke into it, and a pool of water at its foot (anybody who
 *     came down it the hard way lands in water, not on rock).</li>
 * <li><b>The stops</b>: one at the pithead and one at the corner landing of every level opened, each with a window of iron
 *     bars in the pillar onto the well and a lever on the landing's floor by it.</li>
 * <li><b>The cage</b>: drawn by block displays (a trapdoor floor and roof, iron bars round), carrying up to four at a time
 *     stop to stop at a block and a half a tick, a run of the whole shaft in five seconds; the chain rattles as it sets off
 *     and a bell rings when it stops. Folk queue for it at the stop (WorksGoal); a player pulls the lever for the next
 *     stop down, or presses the button by it for the pithead (at the pithead, for the deepest stop).</li>
 * <li><b>Safety</b>: nobody is let into the well but a rider in the cage; anybody found in it outside the cage (a server
 *     stopped mid-ride, a chunk unloaded under it) is put out at the nearest stop's landing; a folk that waits too long for
 *     the cage takes the stair (WorksGoal), which is kept for when the cage is busy or the well blocked.</li>
 * </ul>
 * Built and kept up at the works' half-minute round (MineWorks.tick), so a mine dug before it is fitted with one when its
 * works are next looked at.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class MineLift {

    private MineLift() {}

    /** How deep the works go before the town hangs a cage in them. */
    static final int DEEP_ENOUGH = 40;
    /** Blocks a tick the cage runs. */
    static final double SPEED = 1.5;
    /** Riders in the cage at once. */
    static final int MOST = 4;
    /** The display tag the cage's parts carry (cleared and drawn afresh when the lift is first looked at after a load). */
    static final String TAG = "mca_lift_cage";
    /** Ticks the cage waits at a stop before it goes back up to the pithead. */
    static final int IDLE = 100;

    public enum State { NONE, WAITING, RIDING, ARRIVED }

    /** A stop: the step of the shaft it is at (its corner landing), its floor height, where riders stand, its lever. */
    public record Stop(int index, int y, BlockPos board, BlockPos lever, String name, BlockPos button) {}

    static final class Ask {
        final UUID who;
        final int from, to;
        final long at;
        Ask(UUID who, int from, int to, long at) { this.who = who; this.from = from; this.to = to; this.at = at; }
    }

    static final class Lift {
        final UUID village;
        int sx, sz, top;
        List<Stop> stops = List.of();
        double cageY;
        int phase;                       // 0 waiting, 1 fetching the riders, 2 carrying them
        int from, to;
        final List<Ask> riding = new ArrayList<>();
        final Deque<Ask> queue = new ArrayDeque<>();
        final List<UUID> cage = new ArrayList<>();
        boolean fresh = true;
        int idle;
        double lastY = Double.NaN;
        long trips, carried;
        int step;                                                    // [film39] ticks into the riders' walk in or out (LiftGlide)
        final Map<UUID, Vec3> glideFrom = new HashMap<>();           // [film39]
        int gate = -1;                                               // [tp43] the stop whose window stands open for riders (LiftGlide)
        Lift(UUID village) { this.village = village; }

        @Nullable Stop stop(int index) {
            for (Stop s : stops) if (s.index() == index) return s;
            return null;
        }
        int bottom() { return stops.get(stops.size() - 1).y(); }
    }

    private static final Map<UUID, Lift> LIFTS = new ConcurrentHashMap<>();
    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LIFTS.clear();
        STATE.clear();
    }

    /** Tests: the lift forgotten, as a server stopped and started again forgets it (its displays and riders left where they are). */
    public static void forgetForTests(UUID village) {
        Lift l = LIFTS.remove(village);
        if (l != null) for (Ask a : l.riding) STATE.remove(a.who);
    }

    // ------------------------------------------------------------------ the build

    /** The stops the works have: the pithead, and the corner landing of every level opened (its landing cut). */
    static List<Stop> stopsFor(ServerLevel level, MineWorks.Works w) {
        List<Stop> out = new ArrayList<>();
        out.add(stop(w, 0, "the pithead"));
        for (MineWorks.Lvl l : MineWorks.plan(level, w)) {
            if (l.corner() <= 0 || w.sunk <= l.corner() || l.id() >= 31 || (w.rooms & (1 << l.id())) == 0) continue;
            out.add(stop(w, l.corner(), l.name()));
        }
        out.sort((a, b) -> Integer.compare(b.y(), a.y()));
        return out;
    }

    private static Stop stop(MineWorks.Works w, int corner, String name) {
        int[] o = MineWorks.diag((corner / 4) % 4);
        int y = w.top - MineWorks.drop(corner);
        // [stairs44] The lever and the button on the landing's flat cells, never on a riser (a stair: nothing stands on it).
        BlockPos a = new BlockPos(w.sx + 3 * o[0], y, w.sz + 2 * o[1]), b = new BlockPos(w.sx + 2 * o[0], y, w.sz + 3 * o[1]);
        BlockPos outer = new BlockPos(w.sx + 3 * o[0], y, w.sz + 3 * o[1]);
        boolean aRiser = ShaftStair.isRiser(w, corner, a);
        return new Stop(corner, y, MineWorks.stepPoint(w, corner), aRiser ? outer : a, name, aRiser ? b : outer);
    }

    /**
     * Every half minute (MineWorks.tick): the cage hung once the works are deep enough, and kept: the well cut to the
     * deepest stop, the windows and levers at the stops, the chain. True if the works have a lift.
     */
    public static boolean upkeep(ServerLevel level, MineWorks.Works w) {
        if (!w.head) return false;
        List<Stop> stops = stopsFor(level, w);
        if (stops.size() < 2 || w.top - stops.get(stops.size() - 1).y() < DEEP_ENOUGH) return false;
        Lift l = LIFTS.computeIfAbsent(w.village, Lift::new);
        boolean first = l.stops.isEmpty();
        l.sx = w.sx;
        l.sz = w.sz;
        l.top = w.top;
        int bottom = stops.get(stops.size() - 1).y();
        int put = 0;
        // The well, and the pillar round it made good.
        for (int y = bottom; y <= w.top - 1; y++) {
            BlockPos p = new BlockPos(w.sx, y, w.sz);
            BlockState want = y == w.top - 1 ? Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                : y == bottom ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            BlockState now = level.getBlockState(p);
            if (now.is(Blocks.BEDROCK)) return false;
            boolean ok = y == w.top - 1 ? now.getBlock() instanceof TrapDoorBlock : y == bottom ? now.is(Blocks.WATER) : now.isAir();
            if (!ok) { level.setBlock(p, want, 2); put++; }
            if (y >= w.top - 1) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = p.relative(d);
                for (int dd = 0; dd < 2; dd++) {
                    BlockPos c = dd == 0 ? side : side.relative(d.getClockWise());
                    BlockState cs = level.getBlockState(c);
                    if (window(stops, w, c)) continue;
                    if (cs.isAir() || cs.canBeReplaced() || !cs.getFluidState().isEmpty()) { level.setBlock(c, Blocks.STONE_BRICKS.defaultBlockState(), 2); put++; }
                }
            }
        }
        BlockPos under = new BlockPos(w.sx, bottom - 1, w.sz);                // a floor under the pool
        if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) { level.setBlock(under, Blocks.STONE_BRICKS.defaultBlockState(), 2); put++; }
        // The stops: a window of bars onto the well, a lever on the landing's floor.
        for (Stop s0 : new ArrayList<>(stops)) {
            Stop s = s0;
            int[] o = MineWorks.diag((s.index() / 4) % 4);
            if (s.y() <= w.top - 2 && l.gate != s.index()) {            // [tp43] (not while it stands open for riders)
                for (int h = 0; h < 2; h++) {
                    for (BlockPos c : new BlockPos[]{ new BlockPos(w.sx + o[0], s.y() + h, w.sz + o[1]), new BlockPos(w.sx + o[0], s.y() + h, w.sz),
                            new BlockPos(w.sx, s.y() + h, w.sz + o[1]) }) {
                        if (!level.getBlockState(c).is(Blocks.IRON_BARS)) {
                            level.setBlock(c, Blocks.IRON_BARS.defaultBlockState(), 2);
                            level.setBlock(c, net.minecraft.world.level.block.Block.updateFromNeighbourShapes(level.getBlockState(c), level, c), 2);
                            put++;
                        }
                    }
                }
            }
            BlockPos lv = s.lever();
            if (!(level.getBlockState(lv).getBlock() instanceof LeverBlock) && level.getBlockState(lv).canBeReplaced()
                    && level.getBlockState(lv.below()).isFaceSturdy(level, lv.below(), Direction.UP)) {
                level.setBlock(lv, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                    .setValue(LeverBlock.FACING, o[0] < 0 ? Direction.EAST : Direction.WEST), 3);
                put++;
            }
            if (!(level.getBlockState(lv).getBlock() instanceof LeverBlock)) {
                // Its corner taken (a furnace set down on it): the lever on another cell of the landing, kept as the stop's.
                for (BlockPos c : new BlockPos[]{ new BlockPos(w.sx + 3 * o[0], s.y(), w.sz + 3 * o[1]), new BlockPos(w.sx + 2 * o[0], s.y(), w.sz + 3 * o[1]),
                        new BlockPos(w.sx + 4 * o[0], s.y(), w.sz + 2 * o[1]), new BlockPos(w.sx + 2 * o[0], s.y(), w.sz + 4 * o[1]) }) {
                    if (level.getBlockState(c).getBlock() instanceof LeverBlock) { lv = c; break; }
                    if (level.getBlockState(c).canBeReplaced() && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()
                            && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) {
                        level.setBlock(c, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                            .setValue(LeverBlock.FACING, o[0] < 0 ? Direction.EAST : Direction.WEST), 3);
                        put++;
                        lv = c;
                        break;
                    }
                }
                if (!lv.equals(s.lever())) stops.set(stops.indexOf(s), s = new Stop(s.index(), s.y(), s.board(), lv, s.name(), s.button()));
            }
            // A stone button on the landing's floor by it: up to the pithead (from the pithead, down to the deepest stop).
            BlockPos bt = null;
            for (BlockPos c : new BlockPos[]{ s.button(), new BlockPos(w.sx + 3 * o[0], s.y(), w.sz + 3 * o[1]), new BlockPos(w.sx + 2 * o[0], s.y(), w.sz + 4 * o[1]),
                    new BlockPos(w.sx + 4 * o[0], s.y(), w.sz + 3 * o[1]) }) {
                if (c.equals(s.lever())) continue;
                if (level.getBlockState(c).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock) { bt = c; break; }
                if (level.getBlockState(c).canBeReplaced() && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()
                        && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) {
                    level.setBlock(c, Blocks.STONE_BUTTON.defaultBlockState().setValue(net.minecraft.world.level.block.ButtonBlock.FACE, AttachFace.FLOOR)
                        .setValue(net.minecraft.world.level.block.ButtonBlock.FACING, o[0] < 0 ? Direction.EAST : Direction.WEST), 3);
                    put++;
                    bt = c;
                    break;
                }
            }
            if (bt != null && !bt.equals(s.button())) stops.set(stops.indexOf(s), new Stop(s.index(), s.y(), s.board(), s.lever(), s.name(), bt));
        }
        // The chain from the headframe's beam down to the cap.
        for (int y = w.top + 3; y < w.top + 8; y++) {
            BlockPos p = new BlockPos(w.sx, y, w.sz);
            BlockState s = level.getBlockState(p);
            if (s.is(net.minecraft.tags.BlockTags.LOGS)) break;      // the beam
            if (s.is(Blocks.LANTERN)) {                              // the lantern moves over a block for the chain
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                BlockPos by = p.east();
                if (level.getBlockState(by).isAir()) level.setBlock(by, Blocks.LANTERN.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 3);
            }
            if (level.getBlockState(p).isAir()) { level.setBlock(p, Blocks.CHAIN.defaultBlockState(), 3); put++; }
        }
        l.stops = stops;
        MineCache.restock(level, w, stops, false);                   // the landings' food and torches, once a day
        if (first) {
            l.cageY = w.top;
            com.mojang.logging.LogUtils.getLogger().info("[MCA-LIFT] {}: the cage hung in the shaft, {} stops down to Y{} ({} blocks placed)",
                Villages.name(w.village), stops.size(), bottom, put);
            if (put > 20) Villages.tell(w.village, level.getDayTime() / 24000L, "a cage was hung in the mine's main shaft, from the pithead down to the "
                + stops.get(stops.size() - 1).name());
        }
        return true;
    }

    /** [stairs44] Is this cell of the pillar a window of one of the works' stops (ShaftStair leaves it to the cage)? */
    static boolean windowAt(MineWorks.Works w, BlockPos c) {
        Lift l = LIFTS.get(w.village);
        return l != null && window(l.stops, w, c);
    }

    /** Is this cell of the pillar a window of a stop (left to the bars)? */
    private static boolean window(List<Stop> stops, MineWorks.Works w, BlockPos c) {
        for (Stop s : stops) {
            if (c.getY() != s.y() && c.getY() != s.y() + 1) continue;
            int[] o = MineWorks.diag((s.index() / 4) % 4);
            int dx = c.getX() - w.sx, dz = c.getZ() - w.sz;
            if ((dx == o[0] && dz == o[1]) || (dx == o[0] && dz == 0) || (dx == 0 && dz == o[1])) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ asking for it

    /** Has this town's mine a cage running? */
    public static boolean has(@Nullable UUID village) {
        Lift l = village == null ? null : LIFTS.get(village);
        return l != null && l.stops.size() >= 2;
    }

    @Nullable
    public static Stop stopAt(UUID village, int index) {
        Lift l = LIFTS.get(village);
        return l == null ? null : l.stop(index);
    }

    public static List<Stop> stops(UUID village) {
        Lift l = LIFTS.get(village);
        return l == null ? List.of() : l.stops;
    }

    /** Ask for a ride from one stop to another. False if there is no such lift or stops, or it is asked already. */
    public static boolean ask(Entity e, UUID village, int from, int to) {
        Lift l = LIFTS.get(village);
        if (l == null || from == to || l.stop(from) == null || l.stop(to) == null) return false;
        if (state(e.getUUID()) == State.ARRIVED) STATE.remove(e.getUUID());
        if (state(e.getUUID()) != State.NONE) return false;
        l.queue.add(new Ask(e.getUUID(), from, to, e.level().getGameTime()));
        STATE.put(e.getUUID(), State.WAITING);
        return true;
    }

    /**
     * [tp43] Where a folk waiting for the cage at this stop stands: the open floor round the stop's corner nearest it, a
     * cell each in the order they asked (the first nearest), never the pillar, the well, the stair below or the lever. Ten
     * miners at a shift change used to queue on the one cell at the head of the stair, a knot that blocked the stair for
     * anybody taking it and pushed riders out of the cage's reach. Null: no lift, or not waiting here.
     */
    @Nullable
    public static BlockPos queueSpot(ServerLevel level, UUID village, int stop, UUID who) {
        Lift l = LIFTS.get(village);
        Stop s = l == null ? null : l.stop(stop);
        if (s == null) return null;
        int place = 0;
        boolean found = false;
        for (Ask a : l.queue) {
            if (a.from != stop) continue;
            if (a.who.equals(who)) { found = true; break; }
            place++;
        }
        if (!found) return null;
        List<BlockPos> cells = new ArrayList<>();
        BlockPos b = s.board();
        for (int r = 1; r <= 3; r++)
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                BlockPos p = b.offset(dx, 0, dz);
                if (Math.max(Math.abs(p.getX() - l.sx), Math.abs(p.getZ() - l.sz)) <= 1) continue;
                if (p.equals(s.lever()) || p.equals(s.button())) continue;
                if (standable(level, p)) cells.add(p);
            }
        if (cells.isEmpty()) return b;
        return cells.get(Math.min(place, cells.size() - 1));
    }

    public static State state(UUID who) {
        return STATE.getOrDefault(who, State.NONE);
    }

    /** The arrival taken in (the rider goes on its way). */
    public static void done(UUID who) {
        if (STATE.get(who) == State.ARRIVED) STATE.remove(who);
    }

    /** Given up waiting (the stair instead). Never mid-ride: a rider in the cage is carried to its stop. */
    public static void cancel(UUID who) {
        if (STATE.get(who) != State.WAITING) return;
        STATE.remove(who);
        for (Lift l : LIFTS.values()) l.queue.removeIf(a -> a.who.equals(who));
    }

    // ------------------------------------------------------------------ the running

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level != level.getServer().overworld()) return;
        // After a load: the lifts of the works looked at within ten seconds (a rider left in the well is put out), not at the
        // works' next half-minute round.
        if (level.getGameTime() % 200 == 0) {
            for (Villages.Village v : Villages.every()) {
                if (LIFTS.containsKey(v.id())) continue;
                MineWorks.Works w = MineWorks.works(level, v.id());
                if (w != null && w.head && level.hasChunkAt(new BlockPos(w.sx, w.top, w.sz))) upkeep(level, w);
            }
        }
        if (LIFTS.isEmpty()) return;
        for (Lift l : LIFTS.values()) {
            if (l.stops.size() < 2 || !level.hasChunkAt(new BlockPos(l.sx, l.top, l.sz))) continue;
            if (Villages.get(l.village) == null) continue;
            try {
                if (l.fresh) { l.fresh = false; clearCage(level, l); watch(level, l); }
                run(level, l);
                if (level.getGameTime() % 20 == 0) watch(level, l);
            } catch (RuntimeException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[MCA-LIFT] the cage's tick failed", ex);
            }
        }
    }

    /** Tests: the lift run for so many ticks now. */
    public static void runForTests(ServerLevel level, UUID village, int ticks) {
        Lift l = LIFTS.get(village);
        if (l == null) return;
        for (int i = 0; i < ticks; i++) {
            if (l.fresh) { l.fresh = false; clearCage(level, l); watch(level, l); }
            run(level, l);
        }
    }

    /** Tests: anybody in the well put out now. */
    public static void watchForTests(ServerLevel level, UUID village) {
        Lift l = LIFTS.get(village);
        if (l != null) watch(level, l);
    }

    private static void run(ServerLevel level, Lift l) {
        if (l.phase == 0) {
            if (l.gate >= 0) LiftGlide.shut(level, l);                // [tp43]
            l.queue.removeIf(a -> STATE.get(a.who) != State.WAITING);
            Ask first = l.queue.peek();
            if (first == null) {
                // Idle a while: back up to the pithead, where most rides begin (and where it hangs in the headframe to be seen).
                if (++l.idle > IDLE && l.cageY != l.top) toward(l, l.top);
                drawCage(level, l);
                return;
            }
            l.idle = 0;
            l.from = first.from;
            l.to = first.to;
            l.riding.clear();
            for (Iterator<Ask> it = l.queue.iterator(); it.hasNext() && l.riding.size() < MOST; ) {
                Ask a = it.next();
                if (a.from == l.from && a.to == l.to) { l.riding.add(a); it.remove(); }
            }
            l.phase = 1;
            Stop s = l.stop(l.from);
            if (s != null) level.playSound(null, s.board(), SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.8F, 0.7F);
        }
        Stop from = l.stop(l.from), to = l.stop(l.to);
        if (from == null || to == null) { endTrip(l); return; }
        if (l.phase == 1) {
            if (toward(l, from.y())) {
                // At the stop: whoever is there steps in.
                for (Iterator<Ask> it = l.riding.iterator(); it.hasNext(); ) {
                    Ask a = it.next();
                    Entity e = level.getEntity(a.who);
                    double near = e instanceof ServerPlayer ? 36.0 : 25.0;
                    if (e == null || !e.isAlive() || e.isPassenger() || e.distanceToSqr(Vec3.atBottomCenterOf(from.board())) > near) {
                        STATE.remove(a.who);
                        it.remove();
                        continue;
                    }
                    STATE.put(a.who, State.RIDING);
                    if (e instanceof Mob m) m.getNavigation().stop();
                }
                if (l.riding.isEmpty()) { l.phase = 0; LiftGlide.shut(level, l); drawCage(level, l); return; }
                if (LiftGlide.onFoot(level, l, from)) { l.phase = 3; LiftGlide.begin(level, l, from); }   // [film39] they step in first
                else {
                    l.phase = 2;
                    level.playSound(null, from.board(), SoundEvents.CHAIN_STEP, SoundSource.BLOCKS, 1.0F, 0.8F);
                }
            }
        } else if (l.phase == 3) {                                   // [film39] the riders stepping into the cage at the pithead
            if (LiftGlide.in(level, l, from)) {
                l.phase = 2;
                level.playSound(null, from.board(), SoundEvents.CHAIN_STEP, SoundSource.BLOCKS, 1.0F, 0.8F);
            }
        } else if (l.phase == 4) {                                   // [film39] and stepping out of it
            if (LiftGlide.out(level, l, to, exit(level, l, to))) arrive(level, l, to);
        } else if (l.phase == 2) {
            boolean there = toward(l, to.y());
            for (Ask a : l.riding) {
                Entity e = level.getEntity(a.who);
                if (e == null || !e.isAlive()) continue;
                afloat(e, true);
                // [tp43] Carried with the cage (a block and a half a tick) once it stands in it: no jump. Put in it from outside
                // (a player, or a rider the walk in missed): a jump.
                Blink.note(e, e.position().distanceTo(new Vec3(l.sx + 0.5, l.cageY, l.sz + 0.5)) <= SPEED + 0.25 ? "~lift: carried in the cage" : "lift: put in the cage");
                place(e, l.sx + 0.5, l.cageY, l.sz + 0.5);
            }
            if (there && LiftGlide.onFoot(level, l, to)) {              // [film39] at the stop: they step out (tp43: at every stop)
                l.phase = 4;
                LiftGlide.begin(level, l, to);
            } else if (there) {
                arrive(level, l, to);
            }
        }
        drawCage(level, l);
    }

    /** The riders put out at their stop and the trip ended. */
    private static void arrive(ServerLevel level, Lift l, Stop to) {
        for (Ask a : l.riding) {
            Entity e = level.getEntity(a.who);
            if (e != null && e.isAlive()) {
                BlockPos out = exit(level, l, to);
                Blink.note(e, "lift: put out at the stop");                // [tp43] (a folk has walked out already: no move)
                place(e, out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
                afloat(e, false);
                STATE.put(a.who, State.ARRIVED);
                l.carried++;
                if (e instanceof ServerPlayer p) p.displayClientMessage(Component.literal("The cage: " + to.name() + " (Y" + to.y() + ")"), true);
            } else {
                STATE.remove(a.who);
            }
        }
        LiftGlide.shut(level, l);                                   // [tp43] the stop's window shut behind them
        level.playSound(null, to.board(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 0.6F, 1.6F);
        l.trips++;
        endTrip(l);
    }

    private static void endTrip(Lift l) {
        for (Ask a : l.riding) if (STATE.get(a.who) == State.RIDING || STATE.get(a.who) == State.WAITING) STATE.remove(a.who);
        l.riding.clear();
        l.phase = 0;
    }

    /** The cage a step toward this floor height; true when it is there. */
    private static boolean toward(Lift l, int y) {
        double d = y - l.cageY;
        if (Math.abs(d) <= SPEED) { l.cageY = y; return true; }
        l.cageY += Math.signum(d) * SPEED;
        return false;
    }

    /** Players let fly while the cage carries them (a player held in the air by the cage was kicked for floating). */
    private static final java.util.Set<UUID> AFLOAT = ConcurrentHashMap.newKeySet();

    private static void afloat(Entity e, boolean on) {
        if (!(e instanceof ServerPlayer p) || !p.gameMode.getGameModeForPlayer().isSurvival()) return;   // a creative player flies anyway
        if (on && !p.getAbilities().mayfly) { p.getAbilities().mayfly = true; p.onUpdateAbilities(); AFLOAT.add(p.getUUID()); }
        else if (!on && AFLOAT.remove(p.getUUID())) { p.getAbilities().mayfly = false; p.getAbilities().flying = false; p.onUpdateAbilities(); }
    }

    private static void place(Entity e, double x, double y, double z) {
        e.setDeltaMovement(Vec3.ZERO);
        e.fallDistance = 0;
        if (e instanceof ServerPlayer p) {
            p.connection.teleport(x, y, z, p.getYRot(), p.getXRot());
        } else {
            e.setPos(x, y, z);
            if (e instanceof Mob m) m.getNavigation().stop();
        }
    }

    /**
     * Where a rider steps out at a stop: the stop's own corner if it is open two high on a floor, else the next open cell
     * of the corner landing, else round it, else on top of the ground there. (A town's smelter set a furnace on the
     * pithead's corner, and a rider put out there stood in it.)
     */
    static BlockPos exit(ServerLevel level, Lift l, Stop s) {
        List<BlockPos> tries = new ArrayList<>();
        tries.add(s.board());
        int[] o = MineWorks.diag((s.index() / 4) % 4);
        tries.add(new BlockPos(l.sx + 3 * o[0], s.y(), l.sz + 3 * o[1]));
        tries.add(new BlockPos(l.sx + 2 * o[0], s.y(), l.sz + 3 * o[1]));
        tries.add(s.lever());
        if (s.index() == 0) tries.add(new BlockPos(l.sx - 4, s.y(), l.sz - 2));
        for (int r = 1; r <= 3; r++)
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r) for (int dy = 0; dy <= 1; dy++) tries.add(s.board().offset(dx, dy, dz));
        // [econ32d] Further round the stop, a step up or down at most; never the top of the ground there by the heightmap (at the
        // pithead that was the headframe's beams, and riders were put out on them).
        for (int r = 4; r <= 6; r++)
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r) for (int dy = 1; dy >= -2; dy--) tries.add(s.board().offset(dx, dy, dz));
        for (BlockPos p : tries) {
            if (Math.max(Math.abs(p.getX() - l.sx), Math.abs(p.getZ() - l.sz)) <= 1) continue;      // never the pillar or the well
            if (standable(level, p)) return p;
        }
        return s.board();
    }

    /** [econ32d] On ground: never on a roof, a beam, a fence or leaves (MineRoof.standable). */
    static boolean standable(ServerLevel level, BlockPos p) {
        if (MineRoof.standable(level, p)) return true;
        // [stairs44] A landing's riser (a stair laid as its floor) is stood on as well as the flat of it.
        BlockState under = level.getBlockState(p.below());
        return under.getBlock() instanceof net.minecraft.world.level.block.StairBlock
            && under.getValue(net.minecraft.world.level.block.StairBlock.HALF) == Half.BOTTOM
            && level.getBlockState(p).getCollisionShape(level, p).isEmpty() && !level.getBlockState(p).liquid()
            && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty();
    }

    /** Anybody in the well who is not riding the cage (a server stopped mid-ride, a chunk unloaded): out at the nearest stop. */
    private static void watch(ServerLevel level, Lift l) {
        // A part of the cage drawn twice (its chunk's entities read in after the cage was drawn afresh): the stray let go.
        AABB round = new AABB(l.sx - 2, l.bottom() - 4, l.sz - 2, l.sx + 3, l.top + 8, l.sz + 3);
        for (Display.BlockDisplay d : level.getEntitiesOfClass(Display.BlockDisplay.class, round)) {
            if (d.getTags().contains(TAG) && !l.cage.contains(d.getUUID())) d.discard();
        }
        AABB well = new AABB(l.sx, l.bottom(), l.sz, l.sx + 1, l.top - 1, l.sz + 1);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, well)) {
            if (state(e.getUUID()) == State.RIDING) continue;
            Stop best = null;
            for (Stop s : l.stops) if (best == null || Math.abs(s.y() - e.getY()) < Math.abs(best.y() - e.getY())) best = s;
            if (best == null) continue;
            BlockPos out = exit(level, l, best);
            Blink.safety(e, "lift: found in the well, put out at a stop");   // [tp43] the well is no place to be left
            place(e, out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
            // A player left in the well by a stop mid-ride may have been saved with the cage's leave to fly: taken back.
            if (e instanceof ServerPlayer pl && pl.gameMode.getGameModeForPlayer().isSurvival() && pl.getAbilities().mayfly) {
                pl.getAbilities().mayfly = false; pl.getAbilities().flying = false; pl.onUpdateAbilities(); AFLOAT.remove(pl.getUUID());
            }
            STATE.remove(e.getUUID());
            com.mojang.logging.LogUtils.getLogger().info("[MCA-LIFT] {}: {} found in the well at Y{}, put out at {}", Villages.name(l.village),
                e.getName().getString(), (int) e.getY(), best.name());
        }
    }

    // ------------------------------------------------------------------ the cage drawn

    private static void clearCage(ServerLevel level, Lift l) {
        AABB round = new AABB(l.sx - 2, l.bottom() - 4, l.sz - 2, l.sx + 3, l.top + 8, l.sz + 3);
        for (Display.BlockDisplay d : level.getEntitiesOfClass(Display.BlockDisplay.class, round)) if (d.getTags().contains(TAG)) d.discard();
        l.cage.clear();
    }

    /**
     * [econ32d] The trapdoor over the well opens as the cage comes up to it or goes down through it, and shuts behind it (the
     * cage seen coming up into the headframe through an open hatch, not through a shut one). Shut whenever the cage stands
     * still, so nobody walks into an open well.
     */
    private static void hatch(ServerLevel level, Lift l) {
        BlockPos cap = new BlockPos(l.sx, l.top - 1, l.sz);
        BlockState st = level.getBlockState(cap);
        if (!(st.getBlock() instanceof TrapDoorBlock)) return;
        boolean moving = l.cageY != l.lastY;
        l.lastY = l.cageY;
        boolean open = moving && l.cageY >= l.top - 5;
        if (st.getValue(TrapDoorBlock.OPEN) != open) {
            level.setBlock(cap, st.setValue(TrapDoorBlock.OPEN, open), 3);
            level.playSound(null, cap, open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.7F, 1.0F);
        }
    }

    private static void drawCage(ServerLevel level, Lift l) {
        hatch(level, l);
        if (l.cage.size() == 6) {
            boolean all = true;
            for (UUID id : l.cage) if (!(level.getEntity(id) instanceof Display.BlockDisplay)) { all = false; break; }
            if (all) {
                for (UUID id : l.cage) {
                    Entity d = level.getEntity(id);
                    if (d != null && (d.getY() != l.cageY || d.getX() != l.sx || d.getZ() != l.sz)) d.setPos(l.sx, l.cageY, l.sz);
                }
                return;
            }
        }
        clearCage(level, l);
        BlockState floor = Blocks.DARK_OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM);
        BlockState ew = Blocks.IRON_BARS.defaultBlockState().setValue(net.minecraft.world.level.block.IronBarsBlock.EAST, true)
            .setValue(net.minecraft.world.level.block.IronBarsBlock.WEST, true);
        BlockState ns = Blocks.IRON_BARS.defaultBlockState().setValue(net.minecraft.world.level.block.IronBarsBlock.NORTH, true)
            .setValue(net.minecraft.world.level.block.IronBarsBlock.SOUTH, true);
        spawn(level, l, floor, 0, -0.19F, 0, 1, 1, 1);
        spawn(level, l, floor, 0, 2.15F, 0, 1, 1, 1);
        spawn(level, l, ew, 0, 0, -0.45F, 1, 2.2F, 1);
        spawn(level, l, ew, 0, 0, 0.45F, 1, 2.2F, 1);
        spawn(level, l, ns, -0.45F, 0, 0, 1, 2.2F, 1);
        spawn(level, l, ns, 0.45F, 0, 0, 1, 2.2F, 1);
    }

    private static void spawn(ServerLevel level, Lift l, BlockState st, float tx, float ty, float tz, float kx, float ky, float kz) {
        Display.BlockDisplay d = EntityType.BLOCK_DISPLAY.create(level);
        if (d == null) return;
        CompoundTag t = new CompoundTag();
        t.put("block_state", NbtUtils.writeBlockState(st));
        CompoundTag tr = new CompoundTag();
        tr.put("left_rotation", floats(0, 0, 0, 1));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        tr.put("translation", floats(tx, ty, tz));
        tr.put("scale", floats(kx, ky, kz));
        t.put("transformation", tr);
        t.putInt("teleport_duration", 2);
        ListTag pos = new ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(l.sx));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(l.cageY));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(l.sz));
        t.put("Pos", pos);
        d.load(t);
        d.addTag(TAG);
        d.setPos(l.sx, l.cageY, l.sz);
        if (level.addFreshEntity(d)) l.cage.add(d.getUUID());
    }

    private static ListTag floats(float... fs) {
        ListTag out = new ListTag();
        for (float f : fs) out.add(FloatTag.valueOf(f));
        return out;
    }

    // ------------------------------------------------------------------ a player's ride

    /** A player pulls a stop's lever: the cage down to the next stop (from the deepest, up to the pithead), or, sneaking, up. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p) || LIFTS.isEmpty()) return;
        if (!(level.getBlockState(e.getPos()).getBlock() instanceof LeverBlock)
            && !(level.getBlockState(e.getPos()).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock)) return;
        if (pull(level, p, e.getPos())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** A player at this lever: its ride asked for. True if it was a stop's lever. (Tests call it straight.) */
    public static boolean pull(ServerLevel level, ServerPlayer p, BlockPos at) {
        for (Lift l : LIFTS.values()) {
            for (int i = 0; i < l.stops.size(); i++) {
                Stop s = l.stops.get(i);
                boolean button = s.button().equals(at);
                if (!s.lever().equals(at) && !button) continue;
                if (state(p.getUUID()) != State.NONE) {
                    if (state(p.getUUID()) == State.ARRIVED) STATE.remove(p.getUUID());
                    else { p.displayClientMessage(Component.literal("The cage is on its way."), true); return true; }
                }
                // The lever: down to the next stop (from the deepest, up to the pithead). The button (or the lever pulled
                // sneaking, with nothing in hand): up to the pithead, or from the pithead down to the deepest stop.
                Stop to = button || p.isShiftKeyDown() ? (i == 0 ? l.stops.get(l.stops.size() - 1) : l.stops.get(0))
                    : i + 1 < l.stops.size() ? l.stops.get(i + 1) : l.stops.get(0);
                if (ask(p, l.village, s.index(), to.index())) {
                    level.playSound(null, at, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4F, 0.6F);
                    p.displayClientMessage(Component.literal("The cage: " + (to.y() < s.y() ? "down" : "up") + " to " + to.name() + " (Y" + to.y() + ")"), true);
                }
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the folk's way

    /**
     * A walk through the works with the cage in it: every stretch of the shaft's stair from one stop to another taken out,
     * and a ride put in its place. {@code rides} gets, for each ride, the index in the new walk of the stop it rides to:
     * {from stop, to stop, the index of the from stop in the walk given, the index of the to stop in it}. Null if the walk
     * has no ride in it.
     */
    @Nullable
    public static List<BlockPos> shorten(ServerLevel level, UUID village, List<BlockPos> route, Map<Integer, int[]> rides) {
        Lift l = LIFTS.get(village);
        if (l == null || l.stops.size() < 2 || route.size() < 3) return null;
        MineWorks.Works w = MineWorks.works(level, village);
        if (w == null) return null;
        Map<Long, Integer> step = new HashMap<>();
        for (int i = 0; i < w.sunk; i++) step.put(MineWorks.stepPoint(w, i).asLong(), i);
        Map<Long, Stop> board = new HashMap<>();
        for (Stop s : l.stops) board.put(s.board().asLong(), s);
        List<BlockPos> out = new ArrayList<>();
        Map<Integer, int[]> got = new HashMap<>();
        int k = 0;
        while (k < route.size()) {
            if (!step.containsKey(route.get(k).asLong())) { out.add(route.get(k)); k++; continue; }
            int e = k;
            while (e + 1 < route.size() && step.containsKey(route.get(e + 1).asLong())) e++;
            int f = -1, g = -1;
            for (int i = k; i <= e; i++) if (board.containsKey(route.get(i).asLong())) { if (f < 0) f = i; g = i; }
            if (f >= 0 && g > f && board.get(route.get(f).asLong()).index() != board.get(route.get(g).asLong()).index()) {
                for (int i = k; i <= f; i++) out.add(route.get(i));
                got.put(out.size(), new int[]{ board.get(route.get(f).asLong()).index(), board.get(route.get(g).asLong()).index(), f, g });
                for (int i = g; i <= e; i++) out.add(route.get(i));
            } else {
                for (int i = k; i <= e; i++) out.add(route.get(i));
            }
            k = e + 1;
        }
        if (got.isEmpty()) return null;
        rides.putAll(got);
        return out;
    }

    /** For the books: the cage, its stops and its runs. */
    @Nullable
    public static String report(UUID village) {
        Lift l = LIFTS.get(village);
        if (l == null || l.stops.size() < 2) return null;
        return "The cage: " + l.stops.size() + " stops from the pithead down to Y" + l.bottom() + "; " + l.trips + " runs, " + l.carried + " carried"
            + (l.queue.isEmpty() ? "" : "; " + l.queue.size() + " waiting") + ".";
    }

    /** Tests and the books: runs made, riders carried. */
    public static long[] countsForTests(UUID village) {
        Lift l = LIFTS.get(village);
        return l == null ? new long[]{ 0, 0 } : new long[]{ l.trips, l.carried };
    }

    /** Tests: where the cage is. */
    public static double cageYForTests(UUID village) {
        Lift l = LIFTS.get(village);
        return l == null ? Double.NaN : l.cageY;
    }
}
