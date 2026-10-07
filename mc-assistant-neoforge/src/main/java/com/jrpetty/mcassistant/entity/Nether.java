package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Nether Age's gateway: lit with flint and steel (a flint and an iron out of the stores) once it is built, and from
 * then on the town's way into the Nether. [nether] Who goes through it, and what they bring back, is the Nether
 * runners' (NetherRunners: the trade; NetherRuns: the runs themselves, really through the portal and back). The party
 * that once waited by the gateway while its trip was told and its haul made up is gone: everything the town has out of
 * the Nether is what the runners carried home.
 */
public final class Nether {

    private Nether() {}

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        NetherRuns.resetForTests();        // [nether] the runs, the outpost, the work
        NetherRunners.resetForTests();
    }

    /** Is this folk away through the gateway (the town's look at it: not lost, not idle, not homeless)? [nether] On a
     *  Nether run, on either side of the portal, or lost in the Nether (NetherRuns.away). */
    public static boolean away(VillageFolkEntity f) {
        return NetherRuns.away(f);
    }

    /** Has the town's gateway ever been lit (the Nether open to it: the runners' trade)? */
    public static boolean opened(@Nullable UUID village) {
        String s = village == null ? null : Ledger.note(village, "nether.opened");
        return s != null && !s.isEmpty();
    }

    /** The gateway's look round, once a minute (Land's village tick): lit if it is dark, and the runners' trade kept. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LOOKED.getOrDefault(id, -100000L) < 1200L) return;
        LOOKED.put(id, now);
        if (Villages.ageOf(id) != Villages.Age.NETHER || !Villages.hasBuilt(id, "gateway")) return;
        BlockPos gate = Villages.builtAt(id, "gateway");
        if (gate == null || !Land.areaLoaded(level, gate, 10)) return;
        boolean lit = lit(level, gate) || light(level, v, gate);
        if (lit && !opened(id)) Ledger.note(id, "nether.opened", Long.toString(level.getDayTime() / 24000L));
        NetherRunners.tick(level, v);                 // [nether] the runners picked, their kit made, the farm and trophies
    }

    /** Is there a lit portal in the gateway? */
    static boolean lit(ServerLevel level, BlockPos gate) {
        for (BlockPos p : BlockPos.betweenClosed(gate.offset(-6, -1, -6), gate.offset(6, 7, 6))) {
            if (level.getBlockState(p).is(Blocks.NETHER_PORTAL)) return true;
        }
        return false;
    }

    /** Is the town's gateway lit (its portal found: NetherRuns.gatePortal)? */
    static boolean lit(ServerLevel level, Villages.Village v) {
        return NetherRuns.gatePortal(level, v.id()) != null;
    }

    /** The gateway lit again now (a run that finds it dark), with the stores' flint and steel. */
    static boolean relight(ServerLevel level, Villages.Village v) {
        BlockPos gate = Villages.builtAt(v.id(), "gateway");
        return gate != null && level.isLoaded(gate) && (lit(level, gate) || light(level, v, gate));
    }

    /** Light the gateway with flint and steel (a flint and an iron out of the stores). */
    static boolean light(ServerLevel level, Villages.Village v, BlockPos gate) {
        Optional<PortalShape> shape = Optional.empty();
        for (BlockPos p : BlockPos.betweenClosed(gate.offset(-6, -1, -6), gate.offset(6, 6, 6))) {
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).is(Blocks.OBSIDIAN)) continue;
            for (Direction.Axis axis : new Direction.Axis[]{ Direction.Axis.X, Direction.Axis.Z }) {
                shape = PortalShape.findEmptyPortalShape(level, p.immutable(), axis);
                if (shape.isPresent()) break;
            }
            if (shape.isPresent()) break;
        }
        if (shape.isEmpty()) return false;
        boolean steel = Crafts.take(level, v, s -> s.is(Items.FLINT_AND_STEEL), 1);
        if (!steel && !(Crafts.stock(level, v, s -> s.is(Items.FLINT)) >= 1 && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) >= 1
                && Crafts.take(level, v, s -> s.is(Items.FLINT), 1) && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1))) return false;
        shape.get().createPortalBlocks();
        Villages.tell(v.id(), level.getDayTime() / 24000L, "the gateway was lit, and the Nether lay open");
        if (!opened(v.id())) Ledger.note(v.id(), "nether.opened", Long.toString(level.getDayTime() / 24000L));
        return true;
    }

    /** Tests: the gateway's look now, whatever the clock. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        tick(level, v);
    }
}
