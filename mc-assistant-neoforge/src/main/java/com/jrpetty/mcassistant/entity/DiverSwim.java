package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * [diver] How a diver moves under the water, and how it breathes.
 *
 * <p>A folk in the water floats: the game's float goal keeps its head up, and its pathfinder will swim it across the
 * top of a lake but never down to the bed of one. A diver is steered instead, a tick at a time, straight through the
 * water to where its work is (Divers): a steady stroke down, along and up, round nothing (the bed's plants are no
 * hindrance), lifting over a wall it swims into. While it is under, its float is held off (AssistantEntity.setJumping),
 * and the "climb out of the water" a folk does after ten seconds wet is not for it.
 *
 * <p><b>Air.</b> The game's own: three hundred ticks of breath, used a tick at a time with its head under, and the
 * drowning after. A diver never lets it run down: below {@link #LOW_AIR} it stops whatever it is doing and goes
 * straight up, and stays at the top till its lungs are full again ({@link #FULL_AIR}), then goes back down to where it
 * was. Its dives are never deeper than it can come up from on what it has left: at {@link #UP_PACE} a block a tick and
 * a fifth, a diver at the deepest the trade goes (Divers.DEEPEST, twelve) is at the top in under three seconds, with
 * five in hand.
 *
 * <p>[perks] The game spends a breath only one tick in so many for a body with an oxygen bonus, and the town's perks
 * give one: the Diving Bells a point to everybody (CityTree.dress), a Deep Lungs diver two more (FolkSkills.keepUp). So
 * the same {@link #LOW_AIR} mark comes twice (or four times) as late, and a diver of such a town goes up for air half
 * as often (a quarter as often) and works the longer below.
 *
 * <p><b>The turtle helmet.</b> As the game gives a player wearing one: ten seconds of water breathing, renewed while
 * its head is out of the water, and so a diver in one goes down with ten seconds more in hand.
 */
public final class DiverSwim {

    private DiverSwim() {}

    /** Breath left (ticks) at which a diver turns for the surface whatever it is doing. */
    public static final int LOW_AIR = 100;
    /** Breath it waits at the top for before it goes down again. */
    public static final int FULL_AIR = 290;
    /** Its stroke through the water, blocks a tick; going up for air, a little quicker. */
    static final double PACE = 0.14, UP_PACE = 0.2;
    /** How near is there. */
    static final double NEAR = 0.7;
    /** The game's own: the water breathing a turtle helmet gives, renewed while the head is out of the water. */
    static final int HELMET_TICKS = 200;

    public enum Way { MOVING, ARRIVED, SURFACING, BREATHING }

    /** Is its head under the water? */
    public static boolean under(VillageFolkEntity f) {
        return f.isEyeInFluid(FluidTags.WATER);
    }

    /** Can it breathe under the water just now (the helmet's water breathing, or a potion)? */
    public static boolean breathes(VillageFolkEntity f) {
        return f.hasEffect(MobEffects.WATER_BREATHING) || f.hasEffect(MobEffects.CONDUIT_POWER);
    }

    /** Is it short of breath: under, and its air below the mark, with nothing to breathe on? */
    public static boolean shortOfBreath(VillageFolkEntity f) {
        return under(f) && !breathes(f) && f.getAirSupply() < LOW_AIR;
    }

    /**
     * The turtle helmet, as the game gives it a player (Player.turtleHelmetTick): water breathing while the head is out
     * of the water, so the wearer goes under with ten seconds in hand. Asked every second, which renews the same.
     */
    public static void helmet(VillageFolkEntity f) {
        if (f.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET) && !under(f)) {
            f.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, HELMET_TICKS, 0, false, false, true));
        }
    }

    /** A swim in hand: where it is going, and how it is getting on (Divers' dives keep one each). */
    static final class Stroke {
        Vec3 to = Vec3.ZERO;
        /** Gone up for air, and waiting at the top: where it was going when it went. */
        boolean surfacing;
        int breaths;
        /** Where it was last tick it made way, and when. */
        Vec3 last = Vec3.ZERO;
        long madeWay;
        int lifts;
        /** Where it is going up to for air: the nearest open water over it (never up under a jetty or the ice). */
        @javax.annotation.Nullable Vec3 air;
        /** Times it had to kick for the top at the last of its breath (a blocked way up): its books. */
        int kicks;
        /** The nearest it has got to where it is going (and where that is): bobbing about is not making way. */
        double near = Double.MAX_VALUE;
        Vec3 nearTo = Vec3.ZERO;
    }

    /** Breath left at which, still under, a diver gives a last kick for the top whatever is in its way. */
    static final int LAST_BREATH = 20;

    /**
     * One tick of the stroke: up for air if it must (and the dive waits), else straight through the water toward
     * {@code to}. Its own walking is stopped while it swims, and its float held off (Divers.underwater).
     */
    static Way steer(VillageFolkEntity f, Stroke s, Vec3 to, long now) {
        s.to = to;
        f.getNavigation().stop();
        if (!s.surfacing && shortOfBreath(f)) {
            s.surfacing = true;
            s.breaths++;
            f.brain("up for air (" + f.getAirSupply() + " left)");
        }
        if (s.surfacing) {
            if (under(f)) {
                if (s.air == null && f.level() instanceof ServerLevel level) s.air = airOver(level, f);
                Vec3 p = f.position();
                Vec3 up = s.air == null ? p.add(0, 2, 0) : s.air;
                if (!breathes(f) && f.getAirSupply() <= LAST_BREATH) {
                    // The way up blocked (a jetty, the ice, a ledge it could not get round): a last kick, straight out
                    // into the air over the open water. It never drowns.
                    s.kicks++;
                    f.moveTo(up.x, up.y, up.z, f.getYRot(), 0.0F);
                    f.setDeltaMovement(Vec3.ZERO);
                    f.brain("kicked for the top at the last of its breath");
                    return Way.SURFACING;
                }
                Vec3 d = up.subtract(p);
                double len = d.length();
                Vec3 v = len < 1.0E-3 ? new Vec3(0, UP_PACE, 0) : d.scale(UP_PACE / len);
                if (f.horizontalCollision || f.verticalCollision && v.y > 0) v = new Vec3(v.x, Math.max(v.y, UP_PACE), v.z);
                move(f, v, up);
                return Way.SURFACING;
            }
            s.air = null;
            // At the top: tread water (its float has it now) till its lungs are full.
            f.setDeltaMovement(f.getDeltaMovement().multiply(0.4, 1.0, 0.4));
            if (f.getAirSupply() < FULL_AIR) return Way.BREATHING;
            s.surfacing = false;
            s.madeWay = now;
            s.last = f.position();
            s.near = Double.MAX_VALUE;
        }
        Vec3 p = f.position();
        // Out of the water with its work below it (its float lifted it onto a jetty's deck or a ledge as it came up for
        // air): along to the edge of the nearest open water, and in again, as a swimmer would. Pushing on straight for
        // the work only presses it against the planks.
        if (!f.isInWater() && to.y < p.y - 0.5 && f.level() instanceof ServerLevel level) {
            Vec3 edge = openWater(level, f);
            if (edge != null) {
                Vec3 h = new Vec3(edge.x - p.x, 0, edge.z - p.z);
                double hl = h.length();
                if (hl > 0.05) {
                    move(f, new Vec3(h.x / hl * PACE * 1.5, f.getDeltaMovement().y, h.z / hl * PACE * 1.5), edge);
                    s.madeWay = now;
                    s.last = p;
                    return Way.MOVING;
                }
            }
        }
        Vec3 d = to.subtract(p);
        double len = d.length();
        if (len < NEAR) {
            f.setDeltaMovement(Vec3.ZERO);
            return Way.ARRIVED;
        }
        Vec3 v = d.scale(Math.min(PACE, len) / len);
        // Swum into something: over it if the work is level or above, under it if the work is below (a jetty's deck,
        // the edge of a ledge), and on.
        if (f.horizontalCollision) {
            v = new Vec3(v.x * 0.3, d.y < -0.5 ? Math.min(v.y, -0.12) : Math.max(v.y, 0.12), v.z * 0.3);
            s.lifts++;
        }
        move(f, v, to);
        // Way made is getting nearer the work; bobbing about at the top does not count.
        if (s.nearTo.distanceToSqr(to) > 0.25) {
            s.nearTo = to;
            s.near = len;
            s.madeWay = now;
        } else if (len < s.near - 0.15) {
            s.near = len;
            s.madeWay = now;
        }
        s.last = p;
        return Way.MOVING;
    }

    /** Hold still where it is, under: its work in hand (a cut, a dig). Up for air all the same if it must. */
    static Way hold(VillageFolkEntity f, Stroke s, Vec3 at, long now) {
        Way w = steer(f, s, at, now);
        if (w == Way.MOVING && f.position().distanceToSqr(at) < 1.6 * 1.6) {
            f.setDeltaMovement(f.getDeltaMovement().scale(0.3));
            return Way.ARRIVED;
        }
        return w;
    }

    /** Has it made no way at all for this long (a wall it cannot lift over, a gap too tight)? */
    static boolean stuck(Stroke s, long now, long ticks) {
        return !s.surfacing && now - s.madeWay > ticks;
    }

    private static void move(VillageFolkEntity f, Vec3 v, Vec3 lookAt) {
        f.setDeltaMovement(v);
        f.getLookControl().setLookAt(lookAt.x, lookAt.y, lookAt.z);
        if (v.horizontalDistanceSqr() > 1.0E-4) {
            float yaw = (float) (Mth.atan2(v.z, v.x) * (180.0 / Math.PI)) - 90.0F;
            f.setYRot(yaw);
            f.setYBodyRot(yaw);
            f.setYHeadRot(yaw);
        }
    }

    /**
     * The nearest open water over a diver to come up in: a column whose water reaches the air (not one under a jetty's
     * planks, a boat's berth's or the ice), no lower than the diver, within six blocks; the top of it, a head just out.
     */
    @javax.annotation.Nullable
    static Vec3 airOver(ServerLevel level, VillageFolkEntity f) {
        BlockPos at = f.blockPosition();
        for (int r = 0; r <= 6; r++) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos top = KelpBeds.surfaceAt(level, at.getX() + dx, at.getZ() + dz);
                    if (top == null || top.getY() < at.getY() || !level.getBlockState(top.above()).isAir()) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bestD) { bestD = d; best = top; }
                }
            }
            if (best != null) return new Vec3(best.getX() + 0.5, best.getY() + 0.4, best.getZ() + 0.5);
        }
        return null;
    }

    /** The nearest open water to a diver out of it (water to the air, within six blocks, at any height): the middle of
     *  its top block, to step off into. */
    @javax.annotation.Nullable
    static Vec3 openWater(ServerLevel level, VillageFolkEntity f) {
        BlockPos at = f.blockPosition();
        for (int r = 1; r <= 6; r++) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos top = KelpBeds.surfaceAt(level, at.getX() + dx, at.getZ() + dz);
                    if (top == null || !level.getBlockState(top.above()).isAir() || Math.abs(top.getY() - at.getY()) > 3) continue;
                    double dd = dx * dx + dz * dz;
                    if (dd < bestD) { bestD = dd; best = top; }
                }
            }
            if (best != null) return new Vec3(best.getX() + 0.5, best.getY() + 0.5, best.getZ() + 0.5);
        }
        return null;
    }

    /** The middle of a block of water, as a place to swim to. */
    static Vec3 in(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5);
    }

    /** The surface over a spot: the top of the water there, a swimmer's head just out of it. */
    static Vec3 surfaceOver(ServerLevel level, BlockPos p) {
        BlockPos q = p;
        int guard = 0;
        while (level.getFluidState(q.above()).is(FluidTags.WATER) && guard++ < 40) q = q.above();
        return new Vec3(p.getX() + 0.5, q.getY() + 0.4, p.getZ() + 0.5);
    }
}
