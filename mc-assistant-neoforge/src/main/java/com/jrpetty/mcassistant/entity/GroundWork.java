package com.jrpetty.mcassistant.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * [terra10] The founding's ground finished properly: what the levelling of a column (Terraform.shape) leaves to be
 * done round it and under it.
 *
 * <ul>
 * <li><b>Sealed under.</b> The town's ground is solid twelve deep, not five: a cave or a cavern under the square is
 *     closed over with earth and then stone a dozen blocks thick, not left under a crust a builder's footing or a
 *     farmer's hoe goes through. A cave with anything built in it (a rail, a torch, a ladder, boards) is somebody's
 *     tunnel and is left as it is from there down.</li>
 * <li><b>The cut's faces.</b> Where a column is cut down, the side of the ground beside it comes to the light: a
 *     cave in it stood open in the bank, and water or lava in a cave or a spring ran out across the town. Every
 *     hollow and every cell of water or lava in the face is closed with the ground's own earth and stone.</li>
 * <li><b>Trees taken whole.</b> A tree the levelling cut into was cut a column at a time and the rest left: half a
 *     crown hanging in the air off the edge of the work, a trunk standing on nothing, and leaves rotting for minutes
 *     and dropping their saplings and sticks all over the new ground. A tree touched at all is felled whole now,
 *     its logs and its crown and the vines on it, and the leaves no log holds any more taken at once, as the
 *     game's own decay would in its own time; nothing drops.</li>
 * <li><b>Nothing lying about.</b> Anything lying on the ground worked when it is done (what fell or was washed off
 *     as the ground moved, never a thing a player threw) goes into the town's stores.</li>
 * </ul>
 */
public final class GroundWork {

    private GroundWork() {}

    /** Sent to watching players, without the neighbours' shapes worked out again (as Terraform sets its blocks). */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** How deep under its top the town's ground is made solid. */
    public static final int SEAL_DEPTH = 12;
    /** How high a cut face is looked over. */
    static final int FACE_HIGH = 64;

    // ------------------------------------------------------------------ under the top

    /** A cell that wants filling under the town's top: air, water, lava, something growing, sand or gravel that would fall. */
    static boolean hollow(BlockState b, Block surface, int depth) {
        return b.isAir() || Terraform.wet(b) || Terraform.growth(b)
            || b.getBlock() instanceof FallingBlock && !Terraform.under(surface, depth).is(b.getBlock());
    }

    /** Is anything built beside this cell (a tunnel's rails, torches, ladders or boards)? */
    private static boolean builtBeside(LevelChunk chunk, ServerLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            if (d == Direction.UP) continue;
            int xx = x + d.getStepX(), zz = z + d.getStepZ();
            BlockState s = (xx >> 4) == chunk.getPos().x && (zz >> 4) == chunk.getPos().z
                ? chunk.getBlockState(m.set(xx, y + d.getStepY(), zz)) : level.getBlockState(m.set(xx, y + d.getStepY(), zz));
            if (!s.isAir() && !Terraform.natural(s)) return true;
        }
        return false;
    }

    /**
     * The column made solid under its top, {@code SEAL_DEPTH} deep: every hollow filled in the land's own earth near
     * the top and stone under it. Stops at the world's floor and at anything built (and a cave with something built
     * beside it, past the first few blocks, is left). Returns the blocks set, or with {@code look} only whether any
     * want setting (1) or not (0).
     */
    static int seal(ServerLevel level, LevelChunk chunk, int x, int z, int target, Block surface, boolean look,
                    @javax.annotation.Nullable Felling felling) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(), n = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (int d = 1; d <= SEAL_DEPTH; d++) {
            int y = target - d;
            if (y <= level.getMinBuildHeight()) break;
            BlockState b = chunk.getBlockState(m.set(x, y, z));
            if (b.is(Blocks.BEDROCK) || !Terraform.natural(b)) break;
            if (!hollow(b, surface, d)) continue;
            if (d >= Terraform.SOLID_DEPTH && builtBeside(chunk, level, n, x, y, z)) break;
            if (look) return 1;
            if (felling != null && (b.is(BlockTags.LOGS) || b.is(BlockTags.LEAVES) || b.is(Blocks.VINE))) felling.note(m.immutable());
            level.setBlock(m, Terraform.under(surface, d), QUIET);
            changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------------ the faces of a cut

    /** The top of the earth in a column (through air, plants and water), or MIN_VALUE if not loaded. */
    private static int earthTop(ServerLevel level, int x, int z, int from) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, from, z);
        int floor = Math.max(level.getMinBuildHeight(), from - 200);
        for (int y = from; y > floor; y--) {
            BlockState s = level.getBlockState(m.setY(y));
            if (Terraform.earth(s)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * The sides of a column cut down from {@code was} to {@code target}: a hollow in the ground beside it (a cave
     * in the bank) closed, and water or lava beside it at any height of the cut walled off, in the neighbour's own
     * earth near its top and stone under it. Only the world's own ground and water; nothing built. Returns the
     * blocks set.
     */
    static int faces(ServerLevel level, int x, int z, int target, int was) {
        if (was <= target) return 0;
        int changed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int high = Math.min(was, target + FACE_HIGH);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) continue;
            int top = -1;
            Block soil = null;
            for (int y = target + 1; y <= high; y++) {
                BlockState s = level.getBlockState(m.set(nx, y, nz));
                boolean fluid = !s.getFluidState().isEmpty() && Terraform.wet(s);
                if (!fluid && !s.isAir()) continue;
                if (top == -1) {
                    top = earthTop(level, nx, nz, Math.min(level.getMaxBuildHeight() - 1, high + 64));
                    BlockState t = top == Integer.MIN_VALUE ? null : level.getBlockState(m.set(nx, top, nz));
                    soil = t == null ? Blocks.STONE : Terraform.top(Terraform.topOf(t));
                    if (soil == Blocks.SNOW_BLOCK || soil == Blocks.GRAVEL) soil = Blocks.STONE;
                }
                if (!fluid && (top == Integer.MIN_VALUE || y >= top)) break;   // open air over the neighbour: its own ground is lower
                int depth = top == Integer.MIN_VALUE ? 99 : Math.max(1, top - y);
                level.setBlock(m.set(nx, y, nz), Terraform.under(soil, depth), QUIET);
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ the quay

    /**
     * A column of the town's ground standing over kept water (a river through the town, the sea at its shore) by two
     * or more: its side over the water was a raw face of the dirt it was built up in. Faced in stone (sandstone where
     * the town is on sand) from just under the water's top up to under the turf, as a bank stands, and the town's
     * square ends in a stone quay. Only the ground's own earth is changed. Returns the blocks set.
     */
    static int quay(ServerLevel level, int x, int z, int target, int waterTop, Block surface) {
        if (waterTop >= target - 1) return 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState face = Terraform.under(surface, 4);
        int changed = 0;
        for (int y = waterTop - 1; y < target; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (!(s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY)) || s.is(face.getBlock())) continue;
            level.setBlock(m, face, QUIET);
            changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------------ trees, whole

    /** The logs and leaves a levelling took out of its columns, kept till their trees are felled and their crowns settled. */
    public static final class Felling {
        final Long2ObjectOpenHashMap<LongOpenHashSet> pending = new Long2ObjectOpenHashMap<>();

        public void note(BlockPos p) {
            pending.computeIfAbsent(chunkKey(p.getX(), p.getZ()), k -> new LongOpenHashSet()).add(p.asLong());
        }

        public boolean idle() {
            return pending.isEmpty();
        }

        /** Settle what is waiting, a chunk's worth at a time, till the deadline. True when nothing is left. */
        public boolean settle(ServerLevel level, long deadline) {
            while (!pending.isEmpty()) {
                if (System.nanoTime() >= deadline) return false;
                long key = pending.keySet().iterator().nextLong();
                LongOpenHashSet cells = pending.remove(key);
                GroundWork.settle(level, cells);
            }
            return true;
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
    }

    /** A log a tree grew (not one built into anything, nor a player's). */
    private static boolean treeLog(ServerLevel level, BlockPos p, BlockState s) {
        if (!s.is(BlockTags.LOGS) || !Terraform.growth(s)) return false;
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(p.relative(d));
            if (!n.isAir() && !Terraform.natural(n)) return false;      // set into something built: a cabin's, not a tree's
        }
        return !PlayerBlocks.placed(level, p);
    }

    /** The most logs felled for one batch of cells. */
    private static final int MOST_LOGS = 4096;

    /**
     * The trees these taken cells were part of, felled whole: every log touching a log taken (corners too), and the
     * trees whose leaves were taken (any log within four leaves of them). Then every wild leaf within reach that no
     * log holds any more (more than six from a log, through leaves, as the game reckons it) taken too, and the vines
     * and cocoa left hanging on nothing. Nothing is dropped.
     */
    static int settle(ServerLevel level, LongOpenHashSet cells) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        LongOpenHashSet felled = new LongOpenHashSet();
        ArrayDeque<Long> q = new ArrayDeque<>();
        // The logs to start from: next to a log taken, or within four leaves of a leaf taken.
        LongOpenHashSet seenLeaf = new LongOpenHashSet();
        ArrayDeque<long[]> lq = new ArrayDeque<>();
        for (long c : cells) {
            BlockPos p = BlockPos.of(c);
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos n = p.offset(dx, dy, dz);
                BlockState s = level.getBlockState(n);
                if (treeLog(level, n, s)) q.add(n.asLong());
                else if ((dx == 0 ? 1 : 0) + (dy == 0 ? 1 : 0) + (dz == 0 ? 1 : 0) == 2 && s.is(BlockTags.LEAVES)
                        && seenLeaf.add(n.asLong())) lq.add(new long[]{ n.asLong(), 1 });
            }
        }
        while (!lq.isEmpty()) {
            long[] e = lq.poll();
            BlockPos p = BlockPos.of(e[0]);
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                BlockState s = level.getBlockState(n);
                if (treeLog(level, n, s)) q.add(n.asLong());
                else if (e[1] < 4 && s.is(BlockTags.LEAVES) && seenLeaf.add(n.asLong())) lq.add(new long[]{ n.asLong(), e[1] + 1 });
            }
        }
        // Felled whole: the logs, corners and all.
        while (!q.isEmpty() && felled.size() < MOST_LOGS) {
            long c = q.poll();
            if (felled.contains(c)) continue;
            BlockPos p = BlockPos.of(c);
            BlockState s = level.getBlockState(p);
            if (!treeLog(level, p, s)) continue;
            felled.add(c);
            level.setBlock(p, air, QUIET);
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                BlockPos n = p.offset(dx, dy, dz);
                if (!felled.contains(n.asLong()) && level.getBlockState(n).is(BlockTags.LOGS)) q.add(n.asLong());
            }
        }
        // The crowns: every wild leaf near what was taken or felled that no log holds now.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        LongArrayList all = new LongArrayList(cells);
        all.addAll(felled);
        for (long c : all) {
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        if (all.isEmpty()) return 0;
        int changed = felled.size();
        // Inner box: where a leaf can have lost its hold; outer box: where the logs that may still hold it stand.
        int in = 7, out = in + 6;
        int x0 = minX - out, y0 = Math.max(level.getMinBuildHeight(), minY - out), z0 = minZ - out;
        int w = maxX - minX + 1 + 2 * out, d = maxZ - minZ + 1 + 2 * out;
        int h = Math.min(level.getMaxBuildHeight() - 1, maxY + out) - y0 + 1;
        if ((long) w * d * h > 3_000_000L) return changed;          // far too spread: let the game's own decay have it
        byte[] k = new byte[w * d * h];                             // 1 log, 2 wild leaf, 3 set leaf
        for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) {
            BlockState s = level.getBlockState(m.set(x0 + x, y0 + y, z0 + z));
            if (s.isAir()) continue;
            if (s.is(BlockTags.LOGS)) k[(y * d + z) * w + x] = 1;
            else if (s.is(BlockTags.LEAVES)) k[(y * d + z) * w + x] =
                s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT) ? (byte) 3 : 2;
        }
        byte[] dist = new byte[k.length];
        java.util.Arrays.fill(dist, (byte) 7);
        ArrayDeque<Integer> bq = new ArrayDeque<>();
        for (int i = 0; i < k.length; i++) if (k[i] == 1) { dist[i] = 0; bq.add(i); }
        while (!bq.isEmpty()) {
            int i = bq.poll();
            if (dist[i] >= 6) continue;
            int x = i % w, z = (i / w) % d, y = i / (w * d);
            int[][] nb = { {x + 1, y, z}, {x - 1, y, z}, {x, y + 1, z}, {x, y - 1, z}, {x, y, z + 1}, {x, y, z - 1} };
            for (int[] n : nb) {
                if (n[0] < 0 || n[1] < 0 || n[2] < 0 || n[0] >= w || n[1] >= h || n[2] >= d) continue;
                int j = (n[1] * d + n[2]) * w + n[0];
                if ((k[j] == 2 || k[j] == 3) && dist[j] > dist[i] + 1) {
                    dist[j] = (byte) (dist[i] + 1);
                    bq.add(j);
                }
            }
        }
        for (int y = out - in; y < h - (out - in); y++) for (int z = out - in; z < d - (out - in); z++) for (int x = out - in; x < w - (out - in); x++) {
            int i = (y * d + z) * w + x;
            if (k[i] != 2 || dist[i] <= 6) continue;
            level.setBlock(m.set(x0 + x, y0 + y, z0 + z), air, QUIET);
            changed++;
        }
        // Vines and cocoa hanging on nothing now, from the top down.
        for (int y = h - 1 - (out - in); y >= out - in; y--) for (int z = out - in; z < d - (out - in); z++) for (int x = out - in; x < w - (out - in); x++) {
            m.set(x0 + x, y0 + y, z0 + z);
            BlockState s = level.getBlockState(m);
            if ((s.is(Blocks.VINE) || s.is(Blocks.COCOA) || s.is(Blocks.BEE_NEST)) && !standing(level, m, s)) {
                level.setBlock(m, air, QUIET);
                changed++;
                // and the vine hanging under it, however far down it hangs
                BlockPos.MutableBlockPos v = m.mutable().move(Direction.DOWN);
                for (int i = 0; i < 64 && level.getBlockState(v).is(Blocks.VINE) && !level.getBlockState(v).canSurvive(level, v); i++) {
                    level.setBlock(v, air, QUIET);
                    changed++;
                    v.move(Direction.DOWN);
                }
            }
        }
        return changed;
    }

    /** A vine or cocoa pod still held up, or a bee nest still under something. */
    private static boolean standing(ServerLevel level, BlockPos p, BlockState s) {
        if (s.is(Blocks.BEE_NEST)) {
            for (Direction d : Direction.values()) if (!level.getBlockState(p.relative(d)).isAir()) return true;
            return false;
        }
        return s.canSurvive(level, p);
    }

    // ------------------------------------------------------------------ what is left lying about

    /**
     * Everything lying loose on the ground worked (what a felled crown, a washed-out plant or a falling block left
     * there), into the town's stores; never a thing somebody threw down. Returns how many items.
     */
    static int sweep(ServerLevel level, UUID village, BlockPos heart, int outer, int y) {
        AABB box = new AABB(heart.getX() - outer - 8, y - 64, heart.getZ() - outer - 8,
            heart.getX() + outer + 9, y + 96, heart.getZ() + outer + 9);
        java.util.List<BlockPos> stores = village == null ? java.util.List.of() : Villages.storeChests(level, village);
        java.util.List<net.minecraft.world.Container> boxes = new java.util.ArrayList<>();
        for (BlockPos p : stores) if (level.getBlockEntity(p) instanceof net.minecraft.world.Container c) boxes.add(c);
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive)) {
            if (e.getOwner() != null) continue;                     // thrown by somebody
            ItemStack st = e.getItem().copy();
            n += st.getCount();
            ItemStack left = boxes.isEmpty() ? st : Stacking.insert(boxes, st);
            if (left.isEmpty()) e.discard();
            else e.setItem(left);
        }
        return n;
    }
}
