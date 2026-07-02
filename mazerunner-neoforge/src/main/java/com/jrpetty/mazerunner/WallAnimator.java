package com.jrpetty.mazerunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.jrpetty.mazerunner.config.MazeConfigData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Animates wall segments (toggle points, Glade doors, exit gaps) rising out
 * of or sinking into the ground one Y-layer every couple of ticks, with a
 * stone-grinding rumble — instead of blinking instantly. Segments whose
 * chunks are unloaded are skipped; the chunk-load snap puts them straight
 * into their final state.
 */
public final class WallAnimator {

    private static final int TICKS_PER_LAYER = 2;
    private static final int MAX_CONCURRENT = 24;
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final class Anim {
        final MazeConfigData.Box box;
        final boolean closing; // true = wall rises, false = wall sinks
        int layersDone = 0;
        int cooldown = 0;

        Anim(MazeConfigData.Box box, boolean closing) {
            this.box = box;
            this.closing = closing;
        }
    }

    private static final ArrayDeque<Anim> pending = new ArrayDeque<>();
    private static final List<Anim> active = new ArrayList<>();
    private static final Set<MazeConfigData.Box> animatingBoxes = new HashSet<>();

    private WallAnimator() {}

    public static void clear() {
        pending.clear();
        active.clear();
        animatingBoxes.clear();
    }

    public static boolean isAnimating(MazeConfigData.Box box) {
        return animatingBoxes.contains(box);
    }

    public static int queuedCount() {
        return pending.size() + active.size();
    }

    /** Queue a segment to open (sink) or close (rise). No-op if already queued. */
    public static void enqueue(MazeConfigData.Box box, boolean close) {
        if (!animatingBoxes.add(box)) return;
        pending.add(new Anim(box, close));
    }

    public static void tick(ServerLevel level) {
        while (active.size() < MAX_CONCURRENT && !pending.isEmpty()) {
            active.add(pending.poll());
        }
        if (active.isEmpty()) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState wall = ModBlocks.MAZE_WALL.get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        Iterator<Anim> it = active.iterator();
        while (it.hasNext()) {
            Anim anim = it.next();
            MazeConfigData.Box box = anim.box;

            // Both corner chunks must be loaded to animate; otherwise let the
            // chunk-load snap finish the job and drop the animation.
            if (!level.hasChunk(box.x0() >> 4, box.z0() >> 4)
                || !level.hasChunk(box.x1() >> 4, box.z1() >> 4)) {
                animatingBoxes.remove(box);
                it.remove();
                continue;
            }

            if (anim.cooldown-- > 0) continue;
            anim.cooldown = TICKS_PER_LAYER - 1;

            int height = box.y1() - box.y0() + 1;
            // rising walls build bottom-up; sinking walls dissolve top-down
            int y = anim.closing ? box.y0() + anim.layersDone : box.y1() - anim.layersDone;
            BlockState place = anim.closing ? wall : air;
            for (int x = box.x0(); x <= box.x1(); x++) {
                for (int z = box.z0(); z <= box.z1(); z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos) != place) {
                        level.setBlock(pos, place, SET_FLAGS);
                    }
                }
            }
            if (anim.closing) liftPlayersOutOf(level, box, y);

            if (anim.layersDone % 6 == 0) {
                level.playSound(null,
                    (box.x0() + box.x1()) / 2.0, y, (box.z0() + box.z1()) / 2.0,
                    SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 2.0F, 0.5F);
            }

            if (++anim.layersDone >= height) {
                animatingBoxes.remove(box);
                it.remove();
            }
        }
    }

    /** Nobody gets entombed: players inside a rising layer ride it up. */
    private static void liftPlayersOutOf(ServerLevel level, MazeConfigData.Box box, int layerY) {
        for (ServerPlayer player : level.players()) {
            BlockPos feet = player.blockPosition();
            if (feet.getY() > layerY + 1 || feet.getY() < layerY - 1) continue;
            if (feet.getX() < box.x0() - 1 || feet.getX() > box.x1() + 1) continue;
            if (feet.getZ() < box.z0() - 1 || feet.getZ() > box.z1() + 1) continue;
            if (feet.getX() >= box.x0() && feet.getX() <= box.x1()
                && feet.getZ() >= box.z0() && feet.getZ() <= box.z1()) {
                player.teleportTo(player.getX(), layerY + 1, player.getZ());
            }
        }
    }
}
