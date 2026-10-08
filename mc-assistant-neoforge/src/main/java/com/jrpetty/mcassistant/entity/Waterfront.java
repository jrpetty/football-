package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village and its water.
 * <ul>
 * <li><b>Docks.</b> Each fisher's water gets a jetty from the bank: planks out over the water
 *     on posts, a lantern at the end, and a boat moored alongside. The fisher fishes off the
 *     end of it.</li>
 * <li><b>Irrigation.</b> From the Stone Age the farmers cut channels through their fields,
 *     a straight run of water every eight rows, so every bit of farmland is near water.</li>
 * </ul>
 * (The roads' bridges, where a road crosses a river, are Roads'.)
 */
public final class Waterfront {

    private Waterfront() {}

    /** A jetty: where it leaves the bank, which way, how far, and the water's level. */
    public record Dock(BlockPos start, Direction out, int length) {
        public BlockPos end() { return start.relative(out, length - 1); }
    }

    private static final Map<UUID, Map<UUID, Dock>> DOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Long>> IRRIGATED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DOCKS.clear();
        LAST.clear();
        IRRIGATED.clear();
    }

    /** Every half a minute: the village's jetties and channels seen to. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now - LAST.getOrDefault(id, -100000L) < 600L) return;
        LAST.put(id, now);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.workZone() == null) continue;
            BlockPos c = f.workZone().center();
            if (!Land.areaLoaded(level, c, 20)) continue;
            if (f.stationTask() == AssistantEntity.StationTask.FISH) {
                Dock d = dockFor(level, id, f);
                if (d != null) moor(level, v, d, false);
            } else if (f.stationTask() == AssistantEntity.StationTask.FARM
                    && Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal()
                    && com.jrpetty.mcassistant.AssistantConfig.villageReshapeLand()) {
                // Not before the Stone Age: channels cut in the first days ran into the miners'
                // shafts beside a dry field and flooded them. A dry field grows slower till then.
                // Cut by the field's own farmer, there in it, with a bucket of water carried from the
                // river (the bucket comes back empty): no farmer at its field, no bucket, no channel.
                boolean bucket = f.countMatching(s -> s.is(net.minecraft.world.item.Items.WATER_BUCKET) || s.is(net.minecraft.world.item.Items.BUCKET)) > 0
                    || Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.WATER_BUCKET) || s.is(net.minecraft.world.item.Items.BUCKET)) > 0;
                if (!bucket || f.blockPosition().distSqr(c) > (double) (f.workZone().radius() + 6) * (f.workZone().radius() + 6)) continue;
                int cut = irrigate(level, id, c, f.workZone().radius(), 12);
                if (cut > 0) {
                    f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    f.note(AssistantEntity.Deed.BLOCKS_BUILT, cut);
                }
            }
        }
    }

    // ------------------------------------------------------------------ docks

    /** This fisher's jetty, built if it has none and there is a bank to build it from. Built out of
     *  the village's stores now, a plank at a time, as far as they pay: not begun till there is
     *  wood for its first plank. */
    @Nullable
    public static Dock dockFor(ServerLevel level, UUID village, VillageFolkEntity f) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        Map<UUID, Dock> mine = DOCKS.computeIfAbsent(village, k -> new ConcurrentHashMap<>());
        Dock d = mine.get(f.getUUID());
        if (d != null) {
            build(level, v, d, false);                                   // put back anything knocked off
            return d;
        }
        if (f.workZone() == null) return null;
        d = site(level, f.workZone().center(), 16);
        if (d == null) return null;
        if (build(level, v, d, false) == 0) return null;                 // no wood for it yet
        mine.put(f.getUUID(), d);
        Villages.tell(village, level.getDayTime() / 24000L, f.displayNameCap() + " built a jetty out over the water");
        return d;
    }

    /** The fisher's jetty, if it has one. */
    @Nullable
    public static Dock dockOf(@Nullable UUID village, AssistantEntity f) {
        if (village == null) return null;
        Map<UUID, Dock> mine = DOCKS.get(village);
        return mine == null ? null : mine.get(f.getUUID());
    }

    /** Somewhere to run a jetty out: a bank at the water's edge, open water for three blocks or more beyond it. */
    @Nullable
    public static Dock site(ServerLevel level, BlockPos around, int reach) {
        Dock best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-reach, -4, -reach), around.offset(reach, 4, reach))) {
            if (!water(level, p)) continue;
            for (Direction out : Direction.Plane.HORIZONTAL) {
                BlockPos bank = p.relative(out.getOpposite());
                BlockState b = level.getBlockState(bank);
                if (!b.isSolid() || !b.getFluidState().isEmpty() || !level.getBlockState(bank.above()).isAir()) continue;
                int len = 0;
                while (len < 7 && water(level, p.relative(out, len))) len++;
                if (len < 3) continue;
                double score = p.distSqr(around) - len * 20;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Dock(p.immutable(), out, Math.min(len, 6));
                }
            }
        }
        return best;
    }

    /** Open water at the surface: water, with air above it. */
    static boolean water(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).is(Blocks.WATER) && level.getFluidState(p).isSource() && level.getBlockState(p.above()).isAir();
    }

    /** Lay the jetty for nothing (the showcase and the tests). Returns blocks placed. */
    public static int build(ServerLevel level, Dock d) {
        return build(level, null, d, true);
    }

    /**
     * Lay the jetty: planks over the water, posts down to the bed at its end, a lantern. Returns
     * blocks placed. Each block is paid out of the village's stores (a plank, a length of fence, a
     * lantern), unless {@code free}; when they run short the jetty stops there, and is gone on with
     * when it is next looked over (every half a minute).
     */
    static int build(ServerLevel level, @Nullable Villages.Village v, Dock d, boolean free) {
        if (!free && v == null) return 0;
        // The jetty is laid by the fishers (or whoever can be spared), there at the water.
        if (!free && Market.stock(level, v.id(), s -> s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(net.minecraft.tags.ItemTags.LOGS)) == 0) return 0;
        if (!free && !TownJobs.atWork(level, v, "waterfront", d.start(), "laying the jetty", AssistantEntity.StationTask.FISH)) return 0;
        int n = 0;
        Direction side = d.out().getClockWise();
        for (int k = 0; k < d.length(); k++) {
            BlockPos at = d.start().relative(d.out(), k);
            BlockState here = level.getBlockState(at);
            if (here.is(Blocks.WATER)) {
                if (!free && !Crafts.usePlanks(level, v, 1)) return n;
                level.setBlock(at, Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
                n++;
            } else if (!here.is(BlockTags.PLANKS)) {
                break;                                                   // something else is there now
            }
            // Posts at every other plank, down either side into the water.
            if (k % 2 == 1 || k == d.length() - 1) {
                for (Direction s : new Direction[]{ side, side.getOpposite() }) {
                    BlockPos post = at.relative(s).below();
                    for (int dy = 0; dy < 5; dy++) {
                        BlockPos q = post.below(dy);
                        if (!level.getBlockState(q).is(Blocks.WATER)) break;
                        if (!free && !Crafts.fence(level, v)) return n;
                        level.setBlock(q, Blocks.SPRUCE_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), 3);
                        n++;
                    }
                }
            }
        }
        // A post and a lantern at the end of the jetty: the lantern first (no light, no post).
        BlockPos lamp = d.end().above();
        if (level.getBlockState(d.end()).is(BlockTags.PLANKS) && level.getBlockState(lamp).isAir()
                && level.getBlockState(lamp.above()).isAir()) {
            net.minecraft.world.level.block.Block light = Blocks.LANTERN;
            if (!free) {
                // A lantern if the smith has made one, a torch till it does (Masonry); [diver] a sea lantern of the
                // monument's prismarine, if the diver has brought any home (DiverRaids).
                light = DiverRaids.waterLight(level, v);
                if (light == null) return n;
                if (!Crafts.fence(level, v)) {
                    DiverRaids.unlight(level, v, light);
                    return n;
                }
            }
            level.setBlock(lamp, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
            level.setBlock(lamp.above(), light.defaultBlockState(), 3);
            n += 2;
        }
        return n;
    }

    /** A boat tied up alongside the jetty for nothing (the showcase and the tests). */
    public static boolean moor(ServerLevel level, Dock d) {
        return moor(level, null, d, true);
    }

    /** A boat tied up alongside the jetty, if there isn't one: a boat out of the village's stores,
     *  or five of their planks made into one (unless {@code free}). None if they have neither. */
    static boolean moor(ServerLevel level, @Nullable Villages.Village v, Dock d, boolean free) {
        if (!free && v == null) return false;
        BlockPos mid = d.start().relative(d.out(), Math.max(1, d.length() / 2));
        Direction side = d.out().getClockWise();
        AABB near = new AABB(mid).inflate(6, 3, 6);
        if (!level.getEntitiesOfClass(Boat.class, near, b -> b.getTags().contains("mca_boat")).isEmpty()) return false;
        BlockPos spot = null;
        for (Direction s : new Direction[]{ side, side.getOpposite() }) {
            for (int k = Math.max(1, d.length() / 2); k < d.length(); k++) {
                BlockPos w = d.start().relative(d.out(), k).relative(s, 2);
                if (water(level, w)) { spot = w; break; }
            }
            if (spot != null) break;
        }
        if (spot == null) return false;
        if (!free && !TownJobs.atWork(level, v, "waterfront", mid, "putting a boat in the water", AssistantEntity.StationTask.FISH)) return false;
        if (!free && !Crafts.take(level, v, st -> st.is(net.minecraft.tags.ItemTags.BOATS), 1)
                && !Crafts.usePlanks(level, v, 5)) return false;
        Boat boat = EntityType.BOAT.create(level);
        if (boat == null) return false;
        boat.setVariant(Boat.Type.SPRUCE);
        boat.moveTo(spot.getX() + 0.5, spot.getY() + 0.9, spot.getZ() + 0.5, d.out().toYRot(), 0.0F);
        boat.addTag("mca_boat");
        level.addFreshEntity(boat);
        return true;
    }

    // ------------------------------------------------------------------ irrigation

    /**
     * Cut channels through a field: straight runs of water every eight rows, at the level of the
     * farmland, only where the ground either side will hold the water. Up to so many blocks a time.
     */
    public static int irrigate(ServerLevel level, UUID village, BlockPos centre, int radius, int most) {
        Set<Long> done = IRRIGATED.computeIfAbsent(village, k -> ConcurrentHashMap.newKeySet());
        if (done.contains(centre.asLong())) return 0;
        int y = fieldLevel(level, centre, radius);
        if (y == Integer.MIN_VALUE) return 0;
        // A field already wet enough is left alone.
        if (wet(level, centre, radius, y)) {
            done.add(centre.asLong());
            return 0;
        }
        List<Integer> rows = new ArrayList<>();
        if (radius <= 4) rows.add(centre.getZ());
        else for (int z = centre.getZ() - radius + 4; z <= centre.getZ() + radius - 3; z += 8) rows.add(z);
        int n = 0;
        for (int z : rows) {
            for (int x = centre.getX() - radius + 1; x <= centre.getX() + radius - 1; x++) {
                if (n >= most) return n;
                BlockPos at = new BlockPos(x, y, z);
                if (dig(level, at)) n++;
            }
        }
        if (n == 0) {
            done.add(centre.asLong());
            Villages.tell(village, level.getDayTime() / 24000L, "the farmers cut irrigation channels through the fields at "
                + centre.getX() + ", " + centre.getZ());
        }
        return n;
    }

    /** One block of channel: the crop on it taken up, the soil out, water in — if the sides will hold it. */
    static boolean dig(ServerLevel level, BlockPos at) {
        BlockState s = level.getBlockState(at);
        if (s.is(Blocks.WATER)) return false;
        if (!(s.getBlock() instanceof FarmBlock) && !s.is(Blocks.DIRT) && !s.is(Blocks.GRASS_BLOCK) && !s.is(Blocks.COARSE_DIRT)) return false;
        if (!level.getBlockState(at.below()).isSolid() && !level.getBlockState(at.below()).is(Blocks.WATER)) return false;
        // Either side (across the channel) solid, or more channel.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(at.relative(d));
            if (!n.isSolid() && !n.is(Blocks.WATER) && !(n.getBlock() instanceof FarmBlock)) return false;
        }
        BlockState above = level.getBlockState(at.above());
        if (!above.isAir()) {
            if (!above.is(BlockTags.CROPS) && !above.canBeReplaced()) return false;
            level.destroyBlock(at.above(), true);
        }
        level.setBlock(at, Blocks.WATER.defaultBlockState(), 3);
        return true;
    }

    /** The level of a field's soil: the commonest height of its farmland. MIN_VALUE if it has none. */
    static int fieldLevel(ServerLevel level, BlockPos centre, int radius) {
        Map<Integer, Integer> count = new java.util.HashMap<>();
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos p = centre.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof FarmBlock) count.merge(p.getY(), 1, Integer::sum);
                }
            }
        }
        int best = Integer.MIN_VALUE, most = 0;
        for (Map.Entry<Integer, Integer> e : count.entrySet()) if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        return most >= 6 ? best : Integer.MIN_VALUE;
    }

    /** Is most of the field's farmland within reach of water already? */
    static boolean wet(ServerLevel level, BlockPos centre, int radius, int y) {
        int farm = 0, moist = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos p = new BlockPos(centre.getX() + dx, y, centre.getZ() + dz);
                BlockState s = level.getBlockState(p);
                if (!(s.getBlock() instanceof FarmBlock)) continue;
                farm++;
                if (nearWater(level, p)) moist++;
            }
        }
        return farm == 0 || moist * 10 >= farm * 9;
    }

    private static boolean nearWater(ServerLevel level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, 0, -4), p.offset(4, 1, 4))) {
            if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) return true;
        }
        return false;
    }
}
