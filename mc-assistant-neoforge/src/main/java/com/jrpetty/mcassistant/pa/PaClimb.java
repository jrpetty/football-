package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [pa33] Getting out of a hole the way a player does. Digging stone off a flat field can leave the assistant at the
 * bottom of a pit its feet can't climb out of; then it can't reach the crafting table or its player. When it is boxed
 * in like that it builds itself up a block at a time with whatever building blocks it carries ("pillaring"), and only
 * if it has nothing to stand on does it fall back to coming to its player.
 */
public final class PaClimb {

    private PaClimb() {}

    /** Can it not walk or hop out of where it stands, on any side? */
    public static boolean trapped(PlayerAssistantEntity a) {
        if (!a.onGround() || a.isInWater() || a.isPassenger()) return false;
        ServerLevel level = (ServerLevel) a.level();
        BlockPos feet = a.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(d);
            boolean low = open(level, side), mid = open(level, side.above()), high = open(level, side.above(2));
            if (low && mid) return false;                          // walk out
            if (!low && mid && high && open(level, feet.above(2))) return false;   // hop up one
        }
        return true;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty();
    }

    /** Up one block: it stands on a block from its pack. False when it can't (nothing to stand on, a roof). */
    public static boolean up(PlayerAssistantEntity a) {
        ServerLevel level = (ServerLevel) a.level();
        BlockPos feet = a.blockPosition();
        BlockPos head2 = feet.above(2);
        if (!open(level, head2)) {
            BlockState roof = level.getBlockState(head2);
            if (roof.getDestroySpeed(level, head2) < 0 || PaSafety.offLimits(a, head2)) return false;
            level.destroyBlock(head2, true, a);
            return true;
        }
        ItemStack use = ItemStack.EMPTY;
        for (ItemStack s : a.getInventoryItems()) {
            if (!s.isEmpty() && s.getItem() instanceof BlockItem && BuildGoal.isBuildingBlock(s)) { use = s; break; }
        }
        if (use.isEmpty()) return false;
        Block block = ((BlockItem) use.getItem()).getBlock();
        a.teleportTo(feet.getX() + 0.5, feet.getY() + 1.0, feet.getZ() + 0.5);
        level.setBlockAndUpdate(feet, block.defaultBlockState());
        use.shrink(1);
        a.swing(InteractionHand.MAIN_HAND);
        var sound = block.defaultBlockState().getSoundType();
        level.playSound(null, feet, sound.getPlaceSound(), SoundSource.NEUTRAL, 1.0F, 0.9F);
        return true;
    }

    /** One beat of climbing out (called every few ticks while it is boxed in). True while still climbing. */
    public static boolean tick(PlayerAssistantEntity a) {
        if (a.mind.climbing <= 0) return false;
        a.mind.climbing--;
        if (!trapped(a)) { a.mind.climbing = 0; return false; }
        if (!up(a)) {
            a.mind.climbing = 0;
            if (a.getOwnerPlayer() instanceof net.minecraft.server.level.ServerPlayer owner && PaLife.bringTo(a, owner)) {
                PaTalk.comment(a, 0, "I'd dug myself into a hole with nothing to climb on, so I came back to you.");
            }
            return false;
        }
        return true;
    }

    /** A block it could stand on, for tests. */
    static boolean hasFooting(PlayerAssistantEntity a) {
        return a.countMatching(s -> s.getItem() instanceof BlockItem && BuildGoal.isBuildingBlock(s)) > 0;
    }
}
