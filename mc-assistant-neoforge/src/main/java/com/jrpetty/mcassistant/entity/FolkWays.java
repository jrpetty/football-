package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * [access-3] Ladders climbed, and a fire walked past. Ladders: the The game's way-finding for anything that walks never goes up a ladder (it knows only
 * steps it can walk or jump, and drops), so every loft, attic and upper floor a ladder led to was a place no folk could
 * get to: the beds up there slept in by nobody, the chests never opened. Here a ladder is a way up and down like a stair:
 * in the search, a place on a ladder leads to the rung over it and the rung under it, and a place beside the top of a
 * ladder (the hole in the floor it comes up through) leads onto it; on the way, a folk going up holds on and climbs (as
 * the game's own climbing does when a mob jumps on a ladder), and one going down lets itself down the rungs without
 * pushing off into the wall (which would carry it up again) or jumping, and steps off at the foot.
 *
 * <p>A fire: the folk never go next to fire (a place beside it is barred to their way-finding outright, after too many
 * walked into one), and a lit campfire counted as fire. But a campfire burns only who stands in it: a hunter's drying fire
 * by the door of its lodge, the diver's in its shed, barred the door and the room to everybody. A place beside a lit
 * campfire is now ground a folk would rather walk round (dearer, as by a thorn bush) but may walk past; the campfire
 * itself, and the place over it, still never; beside real fire or lava still never.
 */
public final class FolkWays {

    private FolkWays() {}

    /** The rungs of a ladder (or a vine, a scaffold: anything climbable). */
    static boolean climbable(CollisionGetter level, BlockPos p) {
        return level.getBlockState(p).is(BlockTags.CLIMBABLE);
    }

    /** Room for the mob's body with its feet at the bottom of this place (a rung holds it; nothing else need be under it). */
    private static boolean room(CollisionGetter level, Mob mob, BlockPos p) {
        double r = mob.getBbWidth() / 2.0;
        AABB body = new AABB(p.getX() + 0.5 - r, p.getY() + 0.001, p.getZ() + 0.5 - r, p.getX() + 0.5 + r, p.getY() + mob.getBbHeight(), p.getZ() + 0.5 + r);
        return level.noCollision(mob, body);
    }

    /** The search's neighbours of a place, with the ladder's ways added: up and down the rungs, and from beside the top of
     *  a ladder onto it. {@code n} the neighbours the game found; returns how many there are now. */
    static int neighbours(FolkNodeEvaluator ev, Node[] out, int n, Node node) {
        CollisionGetter level = ev.level();
        Mob mob = ev.mob();
        if (level == null || mob == null) return n;
        BlockPos here = new BlockPos(node.x, node.y, node.z);
        // Up a rung: from a rung, to the rung over it.
        if (climbable(level, here)) {
            BlockPos up = here.above();
            if (climbable(level, up) && room(level, mob, up)) n = add(ev, out, n, node, up);
        }
        // Down a rung: from a rung or the top of the ladder, to the rung under it.
        BlockPos down = here.below();
        if (climbable(level, down) && room(level, mob, down)) n = add(ev, out, n, node, down);
        // Onto the top of a ladder from beside it, where the game's own search found no step that way (it would drop
        // down the shaft, or not go at all).
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = here.relative(d);
            boolean found = false;
            for (int i = 0; i < n && !found; i++) {
                Node o = out[i];
                found = o.x == side.getX() && o.z == side.getZ() && o.y >= node.y;
            }
            if (found) continue;
            if (!climbable(level, side) && !climbable(level, side.below())) continue;
            if (!room(level, mob, side)) continue;
            n = add(ev, out, n, node, side);
        }
        return n;
    }

    private static int add(FolkNodeEvaluator ev, Node[] out, int n, Node from, BlockPos p) {
        if (n >= out.length) return n;
        for (int i = 0; i < n; i++) if (out[i].x == p.getX() && out[i].y == p.getY() && out[i].z == p.getZ()) return n;
        Node got = ev.ladderNode(p.getX(), p.getY(), p.getZ());
        if (got == null || got.closed || got == from) return n;
        out[n++] = got;
        return n;
    }

    // ------------------------------------------------------------ on the way

    /**
     * A walk under way, with the folk on a ladder and the way going on down it: it lets itself down to the foot of the
     * flight (no jumping, no pushing off into the wall, only drawn to the middle of the shaft), the walk's next place
     * the foot until it is there, and then the walk goes on as ever. True if it is so (the game's own step of the walk
     * is not taken this tick).
     */
    static boolean goingDown(Mob mob, Path path) {
        if (path == null || path.isDone() || !mob.onClimbable()) return false;
        int x = Mth.floor(mob.getX()), z = Mth.floor(mob.getZ());
        int i = path.getNextNodeIndex();
        Node next = path.getNode(i);
        if (next.x != x || next.z != z || next.y > mob.getY() - 0.2) return false;
        int j = i;
        while (j + 1 < path.getNodeCount()) {
            Node o = path.getNode(j + 1);
            if (o.x != x || o.z != z || o.y >= path.getNode(j).y) break;
            j++;
        }
        Node foot = path.getNode(j);
        if (mob.onGround() || mob.getY() <= foot.y + 0.1) {
            path.setNextNodeIndex(j + 1);
            return false;
        }
        path.setNextNodeIndex(j);
        Vec3 v = mob.getDeltaMovement();
        double dx = x + 0.5 - mob.getX(), dz = z + 0.5 - mob.getZ();
        mob.setDeltaMovement(dx * 0.2, v.y, dz * 0.2);
        mob.setZza(0.0F);
        mob.setXxa(0.0F);
        if (mob instanceof AssistantEntity a) a.holdOffJumping();
        return true;
    }

    /**
     * May a mob on a ladder jump (climb) now? Not when it is letting itself down, and not when it stands at the foot of
     * one with its way going off level or down (the game's mover jumps whenever its feet are in a block with any shape to
     * it, a ladder too, which would have it climb when it meant to walk away).
     */
    static boolean mayClimb(Mob mob, Path path) {
        if (!mob.onClimbable()) return true;
        if (path == null || path.isDone()) return true;
        Node next = path.getNextNode();
        int x = Mth.floor(mob.getX()), z = Mth.floor(mob.getZ());
        if (next.x == x && next.z == z) return next.y > mob.getY() - 0.2;
        // Going off sideways: climb only to get up to where the next place is (out at the top of a ladder).
        return next.y > mob.getY() + 0.1;
    }

    /** Is this a lit campfire (a hearth that burns only who stands in it)? */
    static boolean hearth(BlockState st) {
        return net.minecraft.world.level.block.CampfireBlock.isLitCampfire(st);
    }

    // ------------------------------------------------------------ gates

    /**
     * Does this one open fence gates on its way (as it opens doors)? A folk does, and closes them behind it
     * (AssistantEntity.tendGates); a prisoner of war never, but on its stint of work in the fields under the watch, and a
     * captive never: the stockade's gate is what keeps them in.
     */
    static boolean opensGates(@javax.annotation.Nullable Mob mob) {
        if (!(mob instanceof AssistantEntity a)) return false;
        if (mob instanceof VillageFolkEntity f && Spies.held(f)) return false;
        return !Prisoners.isPow(a.getUUID()) || Prisoners.onStint(a.getUUID());
    }

    /** Is this a fence gate, shut? */
    static boolean shutGate(BlockState st) {
        return st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
            && !st.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN);
    }

    /** A shut gate on the walk under way, the next place or the one after it, within an arm's length and a step: the
     *  one to open now (null if none). */
    @javax.annotation.Nullable
    static BlockPos gateAhead(Mob mob, @javax.annotation.Nullable Path path) {
        if (path == null || path.isDone()) return null;
        for (int i = Math.max(0, path.getNextNodeIndex() - 1); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 2); i++) {
            BlockPos p = path.getNodePos(i);
            if (mob.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) >= 2.6 * 2.6) continue;
            if (shutGate(mob.level().getBlockState(p))) return opensGates(mob) ? p : null;
        }
        return null;
    }
}
