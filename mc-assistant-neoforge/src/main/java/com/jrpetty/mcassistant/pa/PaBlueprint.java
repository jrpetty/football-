package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [featpa] A building the assistant was shown, block by block: its size, a palette of the block states in it (air first),
 * a cell for every block in the box, and the words on its signs. A container's contents are never copied (a chest comes
 * out empty: a copy is a building, not a duplicator). What it costs to build is worked out from the states (a door, a bed
 * and a tall flower are one item for their two blocks; a double slab is two slabs; candles and pickles by their count).
 *
 * <p>The turn and the mirror are plain sums on the box ({@link #size}, {@link #map}), the same on both sides and in the
 * unit test; the states turn and mirror by the game's own rules (stairs, doors, beds, chests, rails...).
 */
public final class PaBlueprint {

    /** The largest box it will take in: wide, tall, deep. */
    public static final int MAX_W = 40, MAX_H = 32, MAX_D = 40;

    public String name = "";
    public int sx, sy, sz;
    public final List<BlockState> palette = new ArrayList<>();
    /** One palette index per cell, x fastest, then z, then y (so layers come in order from the ground up). */
    public int[] cells = new int[0];
    /** The words on its signs, by cell. */
    public final Map<Integer, CompoundTag> tags = new HashMap<>();
    public long made;
    /** A number of its own, so a client keeps one copy of its cells however often its preview moves. */
    public int stamp;

    public int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    public BlockState at(int x, int y, int z) {
        int i = cells[index(x, y, z)];
        return i < 0 || i >= palette.size() ? Blocks.AIR.defaultBlockState() : palette.get(i);
    }

    /** The blocks in it that are not air. */
    public int solid() {
        int n = 0;
        for (int c : cells) if (c > 0 && !palette.get(c).isAir()) n++;
        return n;
    }

    // ------------------------------------------------------------------ turning and mirroring (pure)

    /** The footprint once turned (PaTurn). */
    public static int[] size(int sx, int sz, int rot) {
        return PaTurn.size(sx, sz, rot);
    }

    /** Where a cell lands in the turned box (PaTurn). */
    public static int[] map(int x, int z, int sx, int sz, boolean mirror, int rot) {
        return PaTurn.map(x, z, sx, sz, mirror, rot);
    }

    public static Rotation rotation(int rot) {
        return switch (rot & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** A state as it stands in the turned copy: mirrored (east-west), then turned. */
    @SuppressWarnings("deprecation")
    public static BlockState turn(BlockState s, boolean mirror, int rot) {
        BlockState t = mirror ? s.mirror(Mirror.FRONT_BACK) : s;
        return t.rotate(rotation(rot));
    }

    /** One placed cell of the copy: where in the world, what goes there, and which cell of the drawing it came from. */
    public record Spot(BlockPos pos, BlockState state, int cell, int layer) {}

    /** Every cell of the copy laid at an anchor (its lowest north-west corner), turned and mirrored. Air cells included. */
    public List<Spot> spots(BlockPos anchor, boolean mirror, int rot) {
        List<Spot> out = new ArrayList<>(cells.length);
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            int[] m = map(x, z, sx, sz, mirror, rot);
            BlockState s = turn(at(x, y, z), mirror, rot);
            out.add(new Spot(anchor.offset(m[0], y, m[1]), s, index(x, y, z), y));
        }
        return out;
    }

    // ------------------------------------------------------------------ what it costs

    /** The item a block takes to place, and how many; null when it takes none (air, water, the top of a door...). */
    @Nullable
    public static Cost costOf(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return null;
        Block b = s.getBlock();
        if (b instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) return null;
        if (b instanceof DoublePlantBlock && s.hasProperty(DoublePlantBlock.HALF) && s.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) return null;
        if (b instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) return null;
        if (b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL || b == Blocks.PISTON_HEAD
            || b == Blocks.MOVING_PISTON || b == Blocks.BUBBLE_COLUMN || b == Blocks.FROSTED_ICE) return null;
        if (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND || b == Blocks.PODZOL || b == Blocks.MYCELIUM) return new Cost(Items.DIRT, 1);
        if (b instanceof FlowerPotBlock fp) return new Cost(Items.FLOWER_POT, 1);
        Item it = b.asItem();
        if (it == Items.AIR) return null;
        int n = 1;
        if (b instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) n = 2;
        else if (s.hasProperty(BlockStateProperties.CANDLES)) n = s.getValue(BlockStateProperties.CANDLES);
        else if (s.hasProperty(BlockStateProperties.PICKLES)) n = s.getValue(BlockStateProperties.PICKLES);
        else if (s.hasProperty(BlockStateProperties.EGGS)) n = s.getValue(BlockStateProperties.EGGS);
        else if (b == Blocks.SNOW && s.hasProperty(BlockStateProperties.LAYERS)) n = s.getValue(BlockStateProperties.LAYERS);
        return new Cost(it, n);
    }

    public record Cost(Item item, int n) {}

    /** Everything it takes, item by item, the most first. */
    public Map<Item, Integer> materials() {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int c : cells) {
            if (c <= 0) continue;
            Cost k = costOf(palette.get(c));
            if (k != null) m.merge(k.item(), k.n(), Integer::sum);
        }
        List<Map.Entry<Item, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    // ------------------------------------------------------------------ the land and the building on it

    /** The ground as it lies: never a player's building. Air, plants, snow, water, earth, sand, stone, ore, trees. */
    public static boolean natural(BlockState s) {
        if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return true;
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER)
            || s.is(BlockTags.LEAVES)
            || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.ICE) || s.is(BlockTags.SNOW)
            || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES)
            || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.BEDROCK) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CACTUS)
            || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.MOSS_BLOCK);
    }

    /** As natural, where it stands: a log is a tree's (leaves within two of it), else somebody's log wall. */
    public static boolean naturalAt(net.minecraft.world.level.Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (natural(s)) return true;
        if (!s.is(BlockTags.LOGS) || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().startsWith("stripped")) return false;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -1, -2), p.offset(2, 2, 2)))
            if (level.getBlockState(q).is(BlockTags.LEAVES)) return true;
        return false;
    }

    /** Ground the copy may stand in without asking: what it would dig out or fill over is the land's, not anybody's work. */
    public static boolean clearable(net.minecraft.world.level.Level level, BlockPos p) {
        return naturalAt(level, p);
    }

    /**
     * The building at a block: every block of a build joined to it (any touching, corners too), out to the largest box it
     * will take, the ground left out. Null when the block is the ground itself (nothing built there).
     */
    @Nullable
    public static net.minecraft.world.level.levelgen.structure.BoundingBox detect(ServerLevel level, BlockPos start) {
        BlockPos s0 = start;
        if (naturalAt(level, s0)) {
            // pointed at the floor in front of a door, or stood inside: the nearest built block within three
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.betweenClosed(start.offset(-3, -1, -3), start.offset(3, 3, 3))) {
                if (naturalAt(level, q)) continue;
                double d = q.distSqr(start);
                if (d < bd) { bd = d; best = q.immutable(); }
            }
            if (best == null) return null;
            s0 = best;
        }
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        todo.add(s0);
        seen.add(s0.asLong());
        int minX = s0.getX(), minY = s0.getY(), minZ = s0.getZ(), maxX = minX, maxY = minY, maxZ = minZ;
        int limit = 12000;
        while (!todo.isEmpty() && seen.size() < limit) {
            BlockPos p = todo.poll();
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos q = p.offset(dx, dy, dz);
                if (Math.abs(q.getX() - s0.getX()) >= MAX_W || Math.abs(q.getZ() - s0.getZ()) >= MAX_D || Math.abs(q.getY() - s0.getY()) >= MAX_H) continue;
                if (!seen.add(q.asLong())) continue;
                if (!level.isLoaded(q) || naturalAt(level, q)) continue;
                todo.add(q);
            }
        }
        return new net.minecraft.world.level.levelgen.structure.BoundingBox(minX, minY, minZ,
            Math.min(maxX, minX + MAX_W - 1), Math.min(maxY, minY + MAX_H - 1), Math.min(maxZ, minZ + MAX_D - 1));
    }

    /**
     * Take in the box between two corners: every block (air too, so the inside is cleared when it is built again), the
     * words on its signs, never what is in a chest. The box is trimmed of whole layers of empty air round it and of whole
     * layers of plain ground under it. Null when nothing built is left.
     */
    @Nullable
    public static PaBlueprint capture(ServerLevel level, BlockPos a, BlockPos b, String name) {
        int x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
        int x1 = Math.max(a.getX(), b.getX()), y1 = Math.max(a.getY(), b.getY()), z1 = Math.max(a.getZ(), b.getZ());
        x1 = Math.min(x1, x0 + MAX_W - 1);
        y1 = Math.min(y1, y0 + MAX_H - 1);
        z1 = Math.min(z1, z0 + MAX_D - 1);
        // trim: air all round, plain ground underneath
        while (y0 < y1 && layerIs(level, x0, x1, y0, z0, z1, true)) y0++;
        while (y1 > y0 && layerIs(level, x0, x1, y1, z0, z1, false)) y1--;
        while (x0 < x1 && sliceAir(level, x0, y0, y1, z0, z1, true)) x0++;
        while (x1 > x0 && sliceAir(level, x1, y0, y1, z0, z1, true)) x1--;
        while (z0 < z1 && sliceAir(level, z0, y0, y1, x0, x1, false)) z0++;
        while (z1 > z0 && sliceAir(level, z1, y0, y1, x0, x1, false)) z1--;
        PaBlueprint bp = new PaBlueprint();
        bp.name = name;
        bp.sx = x1 - x0 + 1;
        bp.sy = y1 - y0 + 1;
        bp.sz = z1 - z0 + 1;
        bp.cells = new int[bp.sx * bp.sy * bp.sz];
        bp.palette.add(Blocks.AIR.defaultBlockState());
        Map<BlockState, Integer> idx = new HashMap<>();
        idx.put(Blocks.AIR.defaultBlockState(), 0);
        boolean any = false;
        for (int y = 0; y < bp.sy; y++) for (int z = 0; z < bp.sz; z++) for (int x = 0; x < bp.sx; x++) {
            BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) s = Blocks.AIR.defaultBlockState();
            if (!naturalAt(level, p)) any = true;
            Integer i = idx.get(s);
            if (i == null) { i = bp.palette.size(); bp.palette.add(s); idx.put(s, i); }
            bp.cells[bp.index(x, y, z)] = i;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof SignBlockEntity) bp.tags.put(bp.index(x, y, z), be.saveWithoutMetadata(level.registryAccess()));
        }
        if (!any) return null;
        bp.made = level.getGameTime();
        bp.stamp = level.getRandom().nextInt(Integer.MAX_VALUE);
        return bp;
    }

    /** A whole layer of plain ground ({@code ground}) or of air. */
    private static boolean layerIs(ServerLevel level, int x0, int x1, int y, int z0, int z1, boolean ground) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (ground ? !(natural(s) && !s.isAir() && !s.canBeReplaced()) : !s.isAir()) return false;
        }
        return true;
    }

    /** A whole upright slice of air: at x = {@code at} (xSlice) or at z = {@code at}. */
    private static boolean sliceAir(ServerLevel level, int at, int y0, int y1, int a0, int a1, boolean xSlice) {
        for (int y = y0; y <= y1; y++) for (int o = a0; o <= a1; o++) {
            BlockPos p = xSlice ? new BlockPos(at, y, o) : new BlockPos(o, y, at);
            if (!level.getBlockState(p).isAir()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ keeping

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("N", name);
        t.putInt("X", sx);
        t.putInt("Y", sy);
        t.putInt("Z", sz);
        ListTag pal = new ListTag();
        for (BlockState s : palette) pal.add(NbtUtils.writeBlockState(s));
        t.put("P", pal);
        t.putIntArray("C", cells);
        ListTag ts = new ListTag();
        for (Map.Entry<Integer, CompoundTag> e : tags.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putInt("I", e.getKey());
            c.put("T", e.getValue());
            ts.add(c);
        }
        t.put("S", ts);
        t.putLong("M", made);
        t.putInt("St", stamp);
        return t;
    }

    public static PaBlueprint load(CompoundTag t, HolderGetter<Block> blocks) {
        PaBlueprint bp = new PaBlueprint();
        bp.name = t.getString("N");
        bp.sx = Math.max(1, t.getInt("X"));
        bp.sy = Math.max(1, t.getInt("Y"));
        bp.sz = Math.max(1, t.getInt("Z"));
        for (Tag e : t.getList("P", Tag.TAG_COMPOUND)) bp.palette.add(NbtUtils.readBlockState(blocks, (CompoundTag) e));
        if (bp.palette.isEmpty()) bp.palette.add(Blocks.AIR.defaultBlockState());
        bp.cells = t.getIntArray("C");
        if (bp.cells.length != bp.sx * bp.sy * bp.sz) bp.cells = new int[bp.sx * bp.sy * bp.sz];
        for (Tag e : t.getList("S", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            bp.tags.put(c.getInt("I"), c.getCompound("T"));
        }
        bp.made = t.getLong("M");
        bp.stamp = t.getInt("St");
        return bp;
    }

    /** For the client: the blocks only, from what the server sent. */
    public static PaBlueprint loadClient(CompoundTag t, net.minecraft.world.level.Level level) {
        return load(t, level.holderLookup(Registries.BLOCK));
    }
}
