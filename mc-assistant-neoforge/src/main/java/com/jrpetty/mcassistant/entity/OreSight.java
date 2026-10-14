package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/**
 * [mines] What ore a folk can see, the way a player would: no looking through rock.
 *
 * <p>An ore counts only when it shows (a face of it open to the air, or to the water or lava of a cave) and the folk
 * has a clear line from its eyes to it, within SIGHT blocks. A vein is followed as it is dug (each block taken lays the
 * next bare), never found by searching the rock. A miner looks round a slice at a time (Look): a few hundred blocks
 * read and a few rays cast a tick, and only while it is working.
 */
public final class OreSight {

    private OreSight() {}

    /** How far a folk notices ore from its eyes: a little over a player's reach, so ore in a roof just out of reach
     *  is seen (and built up to), and no further (a look round reads (2 * SIGHT + 1) cubed blocks). */
    public static final int SIGHT = 6;
    /** How far the cave team, lighting a cave as it goes, sees ore across it (still only in a clear line). */
    public static final int CAVE_SIGHT = 10;
    /** Blocks of the look round read in one tick. */
    static final int CELLS_PER_TICK = 512;
    /** Rays cast in one tick, at most. */
    static final int RAYS_PER_TICK = 4;
    /** How far inside a face its middle is taken (a ray to the very edge of a block can glance off the next one). */
    private static final double FACE_INSET = 0.45;

    /** The vanilla ores by their tags; modded ores come in through the common ore tag. */
    private static final List<TagKey<Block>> ORE_TAGS = List.of(
        BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
        BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES);

    /** Is this an ore: coal, iron, copper, gold, redstone, lapis, diamond, emerald, or any ore a mod adds. */
    public static boolean isOre(BlockState s) {
        for (TagKey<Block> tag : ORE_TAGS) if (s.is(tag)) return true;
        return s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
    }

    /** A line in the folk's own working notes (its debug line): what it is after in the rock, or left. */
    public static void note(AssistantEntity a, String line) {
        a.brain(line);
    }

    /** Has it a tool that takes this block with its drops (a pick of the tier the ore asks for), in hand or in its pack? */
    public static boolean harvestable(AssistantEntity a, BlockState s) {
        if (!s.requiresCorrectToolForDrops() || a.getMainHandItem().isCorrectToolForDrops(s)) return true;
        for (net.minecraft.world.item.ItemStack it : a.getInventoryItems()) if (!it.isEmpty() && it.isCorrectToolForDrops(s)) return true;
        return false;
    }

    /** Does it show: a face of it open to the air (or a cave's water or lava, or a torch's cell)? */
    public static boolean exposed(BlockGetter level, BlockPos p) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(p, d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    /** A clear line from these eyes to the block: to its middle, or to the middle of one of its open faces. */
    public static boolean seen(Level level, Vec3 eye, BlockPos target, @Nullable Entity looker) {
        Vec3 middle = Vec3.atCenterOf(target);
        if (hits(level, eye, middle, target, looker)) return true;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(target, d);
            if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
            Vec3 face = middle.add(d.getStepX() * FACE_INSET, d.getStepY() * FACE_INSET, d.getStepZ() * FACE_INSET);
            boolean facesTheEye = (eye.x - face.x) * d.getStepX() + (eye.y - face.y) * d.getStepY() + (eye.z - face.z) * d.getStepZ() > 0;
            if (facesTheEye && hits(level, eye, face, target, looker)) return true;
        }
        return false;
    }

    private static boolean hits(Level level, Vec3 eye, Vec3 to, BlockPos target, @Nullable Entity looker) {
        BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
            looker == null ? net.minecraft.world.phys.shapes.CollisionContext.empty() : net.minecraft.world.phys.shapes.CollisionContext.of(looker)));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    /** In sight from here now: it shows, it is within SIGHT of the eyes, and nothing stands between. */
    public static boolean inSight(AssistantEntity a, BlockPos p) {
        Vec3 eye = a.getEyePosition();
        return eye.distanceToSqr(Vec3.atCenterOf(p)) <= (double) SIGHT * SIGHT
            && exposed(a.level(), p) && seen(a.level(), eye, p, a);
    }

    /**
     * A folk's look round for ore, spread over ticks: begun where it stands, a slice of the blocks round its eyes read
     * each step, the ore that shows put by, and a few of those put to a ray each step. Every ore it sees is handed on.
     * Kept by the goal doing the work (no allocation while idle).
     */
    public static final class Look {
        private final int sight, side, cells;

        /** A look as far as a folk notices ore at its work (SIGHT). */
        public Look() {
            this(SIGHT);
        }

        /** A look this far round (a cave team lighting a cave looks across it: CAVE_SIGHT). */
        public Look(int sight) {
            this.sight = sight;
            this.side = 2 * sight + 1;
            this.cells = side * side * side;
            this.index = cells;
        }

        private final ArrayDeque<BlockPos> showing = new ArrayDeque<>();
        private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        private Vec3 eye = Vec3.ZERO;
        private int ox, oy, oz;
        private int index;

        /** Nothing left of the last look to do. */
        public boolean idle() {
            return index >= cells && showing.isEmpty();
        }

        /** A fresh look from where it stands now (anything left of the last one dropped). */
        public void begin(AssistantEntity a) {
            eye = a.getEyePosition();
            BlockPos e = BlockPos.containing(eye);
            ox = e.getX() - sight;
            oy = e.getY() - sight;
            oz = e.getZ() - sight;
            index = 0;
            showing.clear();
        }

        /** Drop whatever is left of the look. */
        public void stop() {
            index = cells;
            showing.clear();
        }

        /** One tick of the look: a slice of the blocks read, a few rays cast; each ore seen to `seen`. */
        public void step(AssistantEntity a, Consumer<BlockPos> seen) {
            if (idle()) return;
            Level level = a.level();
            double most = (sight + 0.5) * (sight + 0.5);
            for (int read = 0; read < CELLS_PER_TICK && index < cells; read++, index++) {
                int x = index % side, y = (index / side) % side, z = index / (side * side);
                cell.set(ox + x, oy + y, oz + z);
                double dx = cell.getX() + 0.5 - eye.x, dy = cell.getY() + 0.5 - eye.y, dz = cell.getZ() + 0.5 - eye.z;
                if (dx * dx + dy * dy + dz * dz > most) continue;
                if (!level.isLoaded(cell) || !isOre(level.getBlockState(cell))) continue;
                if (exposed(level, cell)) showing.add(cell.immutable());
            }
            for (int rays = 0; rays < RAYS_PER_TICK && !showing.isEmpty(); rays++) {
                BlockPos p = showing.poll();
                if (isOre(level.getBlockState(p)) && seen(level, eye, p, a)) seen.accept(p);
            }
        }
    }
}
