package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.CameraCommands;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * [pics-fix] Where the pictures' camera stands to see a building whole (the stages' "VIEW name x y z ax ay az" lines,
 * read by tools/realworld/smoke.py). The stages used to aim at a building's middle: a point inside its walls, which no
 * camera outside can see, so the camera (CameraCommands.clearSpot, walking along its line to the first spot that saw
 * its mark) ended up inside, in the clocktower's ladder shaft, the dark of the foundry, the lighthouse's lamp room, the
 * manor's great room. Here the mark is just in front of its front wall, at a little under half its height, and the
 * camera stands out from it in front and a little to one side, far enough back to have the whole of it in the picture
 * (the client's seventy degrees: a building as tall as the camera is far fits with room to spare) and a little above
 * the mark, so it looks a touch down on the roof.
 */
public final class StageViews {

    private StageViews() {}

    /** The height of a drawing, its floor to its top. */
    public static int height(String drawing) {
        int h = 0;
        for (Blueprints.Cell c : Blueprints.cells(drawing)) h = Math.max(h, c.h());
        return h;
    }

    /** The mark: just out from the front of the building (eaves and door lamp too), a little under half its height. */
    public static BlockPos mark(String drawing, BlockPos anchor, Direction back) {
        int[] half = Blueprints.fullHalf(drawing);
        int top = height(drawing);
        return anchor.relative(back.getOpposite(), half[1] + 1).above(Math.max(2, Math.round(top * 0.45F)));
    }

    /**
     * "VIEW name ..." for this building (its drawing, at its anchor, its back toward {@code back}), seen from in front,
     * {@code side} of the way out to its right (0 straight on). The camera is checked to see its mark from open air
     * (CameraCommands.clearSpot steps it back or up out of a hill or a neighbour), and kept out from under a tree's crown.
     */
    public static String front(ServerLevel level, String name, String drawing, BlockPos anchor, Direction back, double side) {
        Direction front = back.getOpposite();
        int top = height(drawing);
        BlockPos mark = mark(drawing, anchor, back);
        int d = Math.max(10, top + 5);
        BlockPos feet = mark.relative(front, d).relative(front.getClockWise(), (int) Math.round(d * side)).above(Math.max(2, Math.round(top * 0.3F)));
        return view(level, name, feet, mark);
    }

    /** "VIEW name x y z ax ay az" for a camera whose feet are at {@code feet} (kept out of crowns, checked, see front). */
    public static String view(ServerLevel level, String name, BlockPos feet, BlockPos mark) {
        BlockPos at = outOfTheCrown(level, feet);
        Vec3 f = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), m = new Vec3(mark.getX() + 0.5, mark.getY() + 0.5, mark.getZ() + 0.5);
        CameraCommands.Spot s = CameraCommands.clearSpot(level, f, m);
        if (s.how().equals("moved")) at = BlockPos.containing(s.feet());
        return "VIEW " + name + " " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + mark.getX() + " " + mark.getY() + " " + mark.getZ();
    }

    /**
     * A camera under a tree's crown photographs the underside of its leaves (the harbour, shot from under an acacia):
     * with leaves over its eyes (within four), it goes up over the treetop.
     */
    public static BlockPos outOfTheCrown(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet)) return feet;
        int eye = feet.getY() + 1;
        for (int dy = 0; dy <= 4; dy++) {
            if (level.getBlockState(feet.atY(eye + dy)).is(BlockTags.LEAVES)) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ());
                return feet.atY(Math.max(feet.getY(), top + 1));
            }
        }
        return feet;
    }
}
