package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * A rectangular patch of the world a specialist is assigned to work, marked out
 * by the player with the Work Zone Marker (click one corner, click the other).
 * `depth` is the floor a miner digs down to inside it — everything else ignores
 * it. Zones can be grown after the fact by marking any further block.
 */
public record WorkZone(BlockPos min, BlockPos max, int depth) {

    public static final int MAX_SIDE = 128;   // keep a zone to something walkable
    public static final int DEFAULT_DEPTH = 12;

    public static WorkZone of(BlockPos a, BlockPos b, int depth) {
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        // Clamp an over-eager selection around its own centre rather than refusing it.
        int cx = (min.getX() + max.getX()) / 2, cz = (min.getZ() + max.getZ()) / 2;
        int half = MAX_SIDE / 2;
        min = new BlockPos(Math.max(min.getX(), cx - half), min.getY(), Math.max(min.getZ(), cz - half));
        max = new BlockPos(Math.min(max.getX(), cx + half), max.getY(), Math.min(max.getZ(), cz + half));
        return new WorkZone(min, max, Math.max(-60, Math.min(200, depth)));
    }

    /** Grow the box so it also covers `pos` — how the marker extends a zone. */
    public WorkZone including(BlockPos pos) {
        return of(new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ())),
                  new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ())),
                  depth);
    }

    public WorkZone withDepth(int newDepth) {
        return new WorkZone(min, max, Math.max(-60, Math.min(200, newDepth)));
    }

    /** Inside the footprint? Y is generous (±16 of the marked band) so a bot can
     *  chase a tree canopy or an ore vein just under its plot. */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()
            && pos.getY() >= min.getY() - 16 && pos.getY() <= max.getY() + 16;
    }

    /** Do these two patches share ground? Compared on the footprint only —
     *  two bots at different heights over the same field still collide. */
    public boolean overlaps(WorkZone other) {
        return min.getX() <= other.max.getX() && max.getX() >= other.min.getX()
            && min.getZ() <= other.max.getZ() && max.getZ() >= other.min.getZ();
    }

    public BlockPos center() {
        return new BlockPos((min.getX() + max.getX()) / 2, (min.getY() + max.getY()) / 2, (min.getZ() + max.getZ()) / 2);
    }

    public int sizeX() { return max.getX() - min.getX() + 1; }
    public int sizeZ() { return max.getZ() - min.getZ() + 1; }

    /** Half the longest side — how far from centre a bot may roam on the job. */
    public int workRadius() {
        return Math.max(8, Math.max(sizeX(), sizeZ()) / 2 + 4);
    }

    public String describe() {
        return sizeX() + "x" + sizeZ() + " at " + center().getX() + ", " + center().getZ();
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("Min", min.asLong());
        t.putLong("Max", max.asLong());
        t.putInt("Depth", depth);
        return t;
    }

    @Nullable
    public static WorkZone load(@Nullable CompoundTag t) {
        if (t == null || !t.contains("Min") || !t.contains("Max")) return null;
        return new WorkZone(BlockPos.of(t.getLong("Min")), BlockPos.of(t.getLong("Max")),
            t.contains("Depth") ? t.getInt("Depth") : DEFAULT_DEPTH);
    }
}
