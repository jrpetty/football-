package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tp43] A walk the folk is led along, a step a tick, where it used to be put at the far end in an instant while a
 * player watched (Blink). For the last resort of a walk that got stuck on a way known to be open (the works' own cut
 * route, a doorway, the cage's opening): the body goes there at a walking pace, its feet moving and facing the way it
 * goes, as LiftGlide walks riders into the cage. The game's own movement is set aside while it goes (VillageFolkEntity
 * .travel), so a knot of folk in the way or the lip of a step no longer holds it: it goes past them as a person squeezes
 * by. Each leg is a short straight line between cells of the way; nothing longer than {@link #MOST} blocks is led.
 */
public final class Stroll {

    private Stroll() {}

    /** Blocks a tick: a folk's walk. */
    static final double PACE = 0.2;
    /** The longest walk led (blocks, all its legs). */
    static final double MOST = 48.0;
    /** A led walk given up after this many ticks, wherever it has got to. */
    static final int LONGEST = 600;

    private static final class Walk {
        final List<Vec3> points;
        int leg;
        int ticks;
        @javax.annotation.Nullable Vec3 last;
        Walk(List<Vec3> points) { this.points = points; }
    }

    private static final Map<UUID, Walk> WALKS = new ConcurrentHashMap<>();

    /** Led along these cells (their floors' middles) from where it stands. False (and nothing done) if the way is too long. */
    public static boolean along(VillageFolkEntity f, List<BlockPos> cells) {
        List<Vec3> pts = new ArrayList<>();
        pts.add(f.position());
        for (BlockPos c : cells) pts.add(Vec3.atBottomCenterOf(c));
        return alongPoints(f, pts);
    }

    /** Led along these points (the first is where it stands now). */
    public static boolean alongPoints(VillageFolkEntity f, List<Vec3> pts) {
        double len = 0;
        for (int i = 1; i < pts.size(); i++) len += pts.get(i - 1).distanceTo(pts.get(i));
        if (pts.size() < 2 || len > MOST) return false;
        f.getNavigation().stop();
        WALKS.put(f.getUUID(), new Walk(new ArrayList<>(pts)));
        LED++;
        return true;
    }

    /** Is it being led just now? */
    public static boolean led(VillageFolkEntity f) {
        return !WALKS.isEmpty() && WALKS.containsKey(f.getUUID());
    }

    public static void stop(VillageFolkEntity f) {
        WALKS.remove(f.getUUID());
    }

    /** Walks led all told (the tests). */
    public static volatile int LED;

    /** A tick of its led walk (VillageFolkEntity.travel, in place of the game's movement); true while it is led. Each
     *  step goes from where it stands toward the next point (never back onto a line it was pushed off), and never into
     *  a block: a step that would put its body in one is taken a little higher (a step up), or the walk ends there. */
    static boolean step(VillageFolkEntity f) {
        Walk w = WALKS.get(f.getUUID());
        if (w == null) return false;
        if (!f.isAlive() || f.isRemoved() || f.isPassenger() || ++w.ticks > LONGEST) { WALKS.remove(f.getUUID()); return false; }
        Vec3 from = f.position(), at = from;
        // Moved by something else since its last step (sat down, set on a seat, put in a cart): that has it now.
        if (w.last != null && w.last.distanceToSqr(from) > 0.05 * 0.05) { WALKS.remove(f.getUUID()); return false; }
        double go = PACE;
        while (go > 1.0E-6 && w.leg < w.points.size() - 1) {
            Vec3 b = w.points.get(w.leg + 1);
            double len = at.distanceTo(b);
            if (len <= go) { at = b; go -= len; w.leg++; }
            else { at = at.add(b.subtract(at).scale(go / len)); go = 0; }
        }
        if (!f.level().noCollision(f, f.getBoundingBox().move(at.subtract(from)).deflate(0.01))) {
            Vec3 up = at.add(0, 0.6, 0), step = at.add(0, 1.0, 0);         // onto a bed or a slab; up a step
            if (f.level().noCollision(f, f.getBoundingBox().move(up.subtract(from)).deflate(0.01))) at = up;
            else if (f.level().noCollision(f, f.getBoundingBox().move(step.subtract(from)).deflate(0.01))) at = step;
            else { WALKS.remove(f.getUUID()); return false; }              // a wall: no further, and never through it
        }
        Blink.note(f, "stroll (led walk)");
        f.setPos(at.x, at.y, at.z);
        w.last = f.position();
        f.setDeltaMovement(Vec3.ZERO);
        f.resetFallDistance();
        f.getNavigation().stop();
        Vec3 way = at.subtract(from);
        if (way.horizontalDistanceSqr() > 1.0E-4) {
            float yaw = (float) Math.toDegrees(Math.atan2(-way.x, way.z));
            f.setYRot(yaw);
            f.yBodyRot = yaw;
            f.yHeadRot = yaw;
        }
        if (w.leg >= w.points.size() - 1) WALKS.remove(f.getUUID());
        return true;
    }

    public static void resetForTests() {
        WALKS.clear();
        LED = 0;
    }
}
