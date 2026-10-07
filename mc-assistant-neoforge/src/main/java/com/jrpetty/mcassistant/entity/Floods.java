package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.LevelTicks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [disasters] The river in flood.
 *
 * <p>Rain on three of the last seven days (villageFloodRainDays), in spring or autumn, and raining again: the
 * water beside a town comes up over the low ground beside it, once a season at most. "The river" is the
 * town's big water: the open water in its reach nearest its heart with two dozen columns or more at its
 * level (a well or a fountain is not a river). It rises a block (two after a very wet week: the great flood)
 * over the cells beside it:
 * <ul>
 * <li><b>Only empty cells.</b> Water is set by the mod only where there is air, standing on something solid
 *     or on water, joined to the river, within the town's reach, no more than forty blocks from it and twelve
 *     hundred cells in all. Nothing is broken to make room for it: a tuft of grass, a flower, a crop, a torch
 *     stays where it is, the water round it. A doorway lets it through to a house's floor (the door itself is
 *     left dry). Never beside anything built that is not the town's own (its buildings, streets and fields):
 *     a player's build stays dry.</li>
 * <li><b>Held where it is set.</b> The water is set without a word to its neighbours, and its flow is held
 *     every tick it stands (the game's own spreading of it waits on a tick the mod takes back), so it never
 *     runs on into a cellar, a mine or a field. (The grass under it may die back to bare earth, as grass under
 *     water does in the game; it grows back.)</li>
 * <li><b>Taken up again.</b> Every cell filled is written down (kept with the world), and when the rain has
 *     stopped a minute the flood goes down the way it came, the furthest from the river first: exactly those
 *     cells, and only where they are water still. Turning disasters off takes it up at once.</li>
 * <li><b>What it costs.</b> A low house with water on its floor is left for the high ground till the water
 *     goes down (a child, or anybody in a great flood, is got out of the water wherever it is), and its
 *     household sleeps at a neighbour's; nobody drowns (a folk under the flood's water is given its breath).
 *     The crops under it in the low fields are spoiled (back to seedlings); a store chest it reaches loses a
 *     quarter of its grain, bread, sugar and paper, four dozen at most.</li>
 * <li><b>What it teaches.</b> When the water is down the town raises a levee along the bank where the river
 *     came over, as high as it came, of earth (or stone) out of the stores, by hand (TownJobs); a step of
 *     slab where a street or a jetty goes down to the water. The same flood then stops at it. The low ground
 *     it covered is kept off for new buildings (Villages.siteFor).</li>
 * </ul>
 */
public final class Floods {

    private Floods() {}

    /** The flood's reach about the heart: the town's reach, and no more than this. */
    static final int ZONE_MOST = 72;
    /** Surface water at one level, this many columns of it, is a river. */
    static final int RIVER_MIN = 24;
    /** The most cells one flood fills. */
    static final int MOST_CELLS = 1200;
    /** No further than this from the river (steps across). */
    static final int FROM_RIVER = 40;
    /** The water stands this long after the rain stops, then goes down. */
    static final long DRAIN_AFTER = 1200L;
    /** Cells filled or taken up each step (a step every five ticks): a flood rises over half a minute. */
    static final int STEP = 15;
    /** The longest a flood stands, rain or no rain (ticks). */
    static final long STANDS_MOST = 36000L;
    /** The most of the stores' goods one flood soaks. */
    static final int SOAK_MOST = 48;

    /** A folk getting out of the water: where to, and its progress. */
    static final class Evac {
        final BlockPos to;
        double best = Double.MAX_VALUE;
        long progress;
        int walkTick = -1000;

        Evac(BlockPos to, long now) {
            this.to = to.immutable();
            this.progress = now;
        }
    }

    private static final Map<UUID, Evac> EVAC = new ConcurrentHashMap<>();
    /** The high ground each folk could find no way to, this flood. */
    private static final Map<UUID, Set<Long>> FAILED = new ConcurrentHashMap<>();
    /** Folk the flood has given breath to, all told (tests: nobody drowns). */
    private static final Map<UUID, Integer> BREATH = new ConcurrentHashMap<>();

    static void resetForTests() {
        EVAC.clear();
        FAILED.clear();
        BREATH.clear();
        STAGED.clear();
    }

    // ------------------------------------------------------------------ the town's look

    /** Every second: a flood coming, a flood going down, the levee raised. */
    static void tick(ServerLevel level, Villages.Village v, Disasters.Town t) {
        long now = level.getGameTime();
        Disasters.Flood fl = t.flood;
        if (fl != null) {
            if (fl.draining) return;
            if (!Disasters.on()) {
                drain(level, v, t, "disasters were turned off");
            } else if (Disasters.raining(level, v)) {
                fl.dryAt = -1;
            } else if (fl.dryAt < 0 || fl.dryAt > now) {
                fl.dryAt = now;
            } else if (fl.risen && now - fl.dryAt >= DRAIN_AFTER) {
                drain(level, v, t, "the rain stopped");
            }
            // However long it rains, a flood stands a day and a half at most.
            if (!fl.draining && (now - fl.since > STANDS_MOST || now < fl.since)) drain(level, v, t, "the river fell of its own accord");
            return;
        }
        if (!t.levee.isEmpty() && now % 60 < 20) levee(level, v, t, TownTraits.leveePace(v.id(), 3));   // [identity] Flood-hardy: twice as fast
        if (!Disasters.on() || !Disasters.raining(level, v)) return;
        long day = level.getDayTime() / 24000L;
        Seasons.Season s = Seasons.season(v.id(), day);
        if (s != Seasons.Season.SPRING && s != Seasons.Season.AUTUMN) return;
        int key = Seasons.year(v.id(), day) * 4 + s.ordinal();
        if (t.floodSeason == key) return;                        // one flood a season, at most
        int need = AssistantConfig.villageFloodRainDays();
        if (need > 7) return;
        int wet = t.wetDays(true);
        if (wet < need) return;
        t.floodSeason = key;
        rise(level, v, t, wet >= need + 2 ? 2 : 1);
    }

    /**
     * The river comes up: the cells it will fill worked out now (they fill a step at a time). Returns the
     * number of cells it will fill (none: no river, or no low ground beside it).
     */
    static int rise(ServerLevel level, Villages.Village v, Disasters.Town t, int rise) {
        if (t.flood != null) return 0;
        int[] river = river(level, v);
        if (river == null) return 0;
        Disasters.Flood fl = new Disasters.Flood();
        fl.w = river[0];
        fl.rise = Math.max(1, Math.min(2, rise));
        fl.since = level.getGameTime();
        fl.river = riverName(level, v, fl.w);
        plan(level, v, fl);
        if (fl.pending.isEmpty()) return 0;
        fl.most = fl.pending.size();
        t.flood = fl;
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, capital(fl.river) + " is rising" + (fl.rise >= 2 ? " fast" : "") + " in the rain");
        Disasters.dirty();
        return fl.pending.size();
    }

    /** The river's surface level and how many columns of it there are, or null if the town has no river. */
    @Nullable
    static int[] river(ServerLevel level, Villages.Village v) {
        // Each level's open water: how many columns, and how near the heart the nearest of them is. The town's
        // river is the nearest water big enough to be one (a sea on the edge of the reach is not its river).
        Map<Integer, Integer> at = new HashMap<>();
        Map<Integer, Long> near = new HashMap<>();
        BlockPos heart = v.centre();
        for (int[] col : columns(v)) {
            int x = col[0], z = col[1];
            {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                BlockPos top = new BlockPos(x, y, z);
                if (!openWater(level, top)) continue;
                if (Land.inABuilding(v.id(), top)) continue;            // a well, a fountain, a pond in a park
                at.merge(y, 1, Integer::sum);
                long d = (long) (x - heart.getX()) * (x - heart.getX()) + (long) (z - heart.getZ()) * (z - heart.getZ());
                near.merge(y, d, Math::min);
            }
        }
        int[] staged = STAGED.get(v.id());                      // a scene set out for the pictures: its own river
        if (staged != null && at.getOrDefault(staged[4], 0) >= RIVER_MIN) return new int[]{ staged[4], at.get(staged[4]) };
        int best = Integer.MIN_VALUE, most = 0;
        long bestNear = Long.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : at.entrySet()) {
            if (e.getValue() < RIVER_MIN) continue;
            long d = near.get(e.getKey());
            if (d < bestNear) { bestNear = d; best = e.getKey(); most = e.getValue(); }
        }
        return most >= RIVER_MIN ? new int[]{ best, most } : null;
    }

    /** A source of water with air over it: the river's surface. */
    static boolean openWater(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).is(Blocks.WATER) && level.getFluidState(p).isSource() && level.getBlockState(p.above()).isAir();
    }

    static int zone(Villages.Village v) {
        return Math.min(ZONE_MOST, Villages.townReach(v.id()) + 8);
    }

    /**
     * A scene set out for the pictures (DisasterStage) or a test, by town: {x0, z0, x1, z1, the river's level}. While
     * one is set the flood is the scene's: its river, and its ground only.
     */
    private static final Map<UUID, int[]> STAGED = new ConcurrentHashMap<>();

    static void stage(UUID village, int x0, int z0, int x1, int z1, int river) {
        STAGED.put(village, new int[]{ Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), river });
    }

    /** Within the flood's reach: the town's reach about its heart (or the scene set out, if there is one). */
    static boolean inZone(Villages.Village v, BlockPos p) {
        int[] b = STAGED.get(v.id());
        if (b != null) return p.getX() >= b[0] && p.getX() <= b[2] && p.getZ() >= b[1] && p.getZ() <= b[3];
        BlockPos c = v.centre();
        return Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ())) <= zone(v);
    }

    /** Every column within the flood's reach. */
    static List<int[]> columns(Villages.Village v) {
        List<int[]> out = new ArrayList<>();
        int[] b = STAGED.get(v.id());
        if (b != null) {
            for (int x = b[0]; x <= b[2]; x++) for (int z = b[1]; z <= b[3]; z++) out.add(new int[]{ x, z });
            return out;
        }
        BlockPos c = v.centre();
        int zone = zone(v);
        for (int x = c.getX() - zone; x <= c.getX() + zone; x++) for (int z = c.getZ() - zone; z <= c.getZ() + zone; z++) out.add(new int[]{ x, z });
        return out;
    }

    /** "the river", "the sea", "the lake": what the town's water is, by the land it lies in. */
    static String riverName(ServerLevel level, Villages.Village v, int w) {
        BlockPos c = v.centre();
        int zone = zone(v);
        for (int r = 0; r <= zone; r += 4) {
            for (int[] d : new int[][]{ { r, 0 }, { -r, 0 }, { 0, r }, { 0, -r }, { r, r }, { -r, -r }, { r, -r }, { -r, r } }) {
                BlockPos p = new BlockPos(c.getX() + d[0], w, c.getZ() + d[1]);
                if (!level.isLoaded(p) || !openWater(level, p)) continue;
                var biome = level.getBiome(p);
                if (biome.is(BiomeTags.IS_RIVER)) return "the river";
                if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH)) return "the sea";
                return "the water";
            }
        }
        return "the river";
    }

    /**
     * The cells the river will fill: every empty cell a block or two over its level joined to it across empty
     * cells (through a doorway, but not the door), standing on something solid or on water, in the town's
     * reach, not beside a player's build, nearest the river first. And the town's crops under it.
     */
    static void plan(ServerLevel level, Villages.Village v, Disasters.Flood fl) {
        BlockPos c = v.centre();
        int zone = zone(v);
        int top = fl.w + fl.rise;
        Set<Long> seen = new LinkedHashSet<>();
        Set<Long> in = new LinkedHashSet<>();
        ArrayDeque<long[]> todo = new ArrayDeque<>();             // {pos, steps from the river}
        for (int[] col : columns(v)) {
            int x = col[0], z = col[1];
            {
                BlockPos p = new BlockPos(x, fl.w, z);
                if (!level.isLoaded(p) || !openWater(level, p) || Land.inABuilding(v.id(), p)) continue;
                long k = p.above().asLong();
                if (seen.add(k)) todo.add(new long[]{ k, 0 });
            }
        }
        while (!todo.isEmpty() && in.size() < MOST_CELLS) {
            long[] e = todo.poll();
            BlockPos p = BlockPos.of(e[0]);
            if (!fills(level, v, p, c, zone, in)) {
                // A crop of the town's at the water's level: spoiled where the flood stands round it.
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof CropBlock && e[1] > 0 && Villages.onFarmland(v.id(), c, p, 0) && !fl.crops.contains(e[0])) {
                    fl.crops.add(e[0]);
                }
                continue;
            }
            in.add(e[0]);
            if (e[1] >= FROM_RIVER) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                BlockState ns = level.getBlockState(n);
                // Through a doorway: the cell beyond the door, the door itself left dry.
                if (ns.getBlock() instanceof DoorBlock && ns.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) n = n.relative(d);
                if (seen.add(n.asLong())) todo.add(new long[]{ n.asLong(), e[1] + 1 });
            }
            if (p.getY() < top && seen.add(p.above().asLong())) todo.add(new long[]{ p.above().asLong(), e[1] });
        }
        fl.pending.clear();
        fl.pending.addAll(in);
    }

    /** May the flood fill this cell: empty, low enough, standing on something, the town's, and nothing of a player's by it? */
    private static boolean fills(ServerLevel level, Villages.Village v, BlockPos p, BlockPos c, int zone, Set<Long> in) {
        if (!inZone(v, p)) return false;
        if (!level.isLoaded(p) || !level.getBlockState(p).isAir()) return false;
        BlockPos below = p.below();
        BlockState b = level.getBlockState(below);
        boolean stands = in.contains(below.asLong()) || b.getFluidState().is(FluidTags.WATER) || b.isSolid();
        if (!stands) return false;
        return !aPlayers(level, v, p, c);
    }

    /**
     * Is anything built beside this cell that is not the town's own? What the world grew (earth, stone, sand,
     * trees, plants, water) is nobody's; the town's buildings and their lots, its streets and square, and its
     * fields are the town's. Anything else built there is somebody's (a player's): kept dry.
     */
    static boolean aPlayers(ServerLevel level, Villages.Village v, BlockPos p, BlockPos c) {
        int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
        if (Land.inABuilding(v.id(), p) || TownPlan.isStreet(dx, dz) || TownPlan.isSquare(dx, dz)
                || Villages.onFarmland(v.id(), c, p, 1)) return false;
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(p.relative(d));
            if (!natural(s)) return true;
        }
        return false;
    }

    /** Something the world grew, not something built. */
    static boolean natural(BlockState s) {
        if (s.isAir() || s.is(Blocks.WATER)) return true;
        if (Land.earth(s)) return true;
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
            || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.CROPS)
            || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH)
            || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.LILY_PAD) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)
            || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MUD)
            || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(BlockTags.CORAL_BLOCKS) || s.is(Blocks.SANDSTONE);
    }

    // ------------------------------------------------------------------ the water set, held and taken up

    /** Every five ticks: the flood rises a step, or goes down one. */
    static void step(ServerLevel level, Villages.Village v, Disasters.Town t) {
        Disasters.Flood fl = t.flood;
        if (fl == null) return;
        if (fl.draining) {
            // The way it came, the furthest from the river first: the record of what was filled is kept whole
            // (the levee is planned from it), and a count of how far back it has gone.
            List<Long> order = new ArrayList<>(fl.cells);
            int n = 0;
            while (fl.drained < order.size() && n < STEP) {
                BlockPos p = BlockPos.of(order.get(order.size() - 1 - fl.drained));
                if (!level.isLoaded(p)) return;                      // its ground not here: it waits
                take(level, p);
                fl.drained++;
                n++;
            }
            if (n > 0 && level.getRandom().nextInt(4) == 0) {
                level.playSound(null, BlockPos.of(order.get(Math.max(0, order.size() - fl.drained))), SoundEvents.WATER_AMBIENT,
                    SoundSource.BLOCKS, 0.8F, 1.2F);
            }
            if (fl.drained >= order.size()) gone(level, v, t);
            Disasters.dirty();
            return;
        }
        if (fl.risen) return;
        int n = 0;
        BlockPos last = null;
        while (!fl.pending.isEmpty() && n < STEP) {
            long k = fl.pending.remove(0);
            BlockPos p = BlockPos.of(k);
            if (!level.isLoaded(p) || !level.getBlockState(p).isAir()) continue;
            BlockState b = level.getBlockState(p.below());
            if (!(fl.cells.contains(p.below().asLong()) || b.getFluidState().is(FluidTags.WATER) || b.isSolid())) continue;
            put(level, p);
            fl.cells.add(k);
            last = p;
            n++;
        }
        if (last != null && level.getRandom().nextInt(3) == 0) {
            level.playSound(null, last, SoundEvents.WATER_AMBIENT, SoundSource.BLOCKS, 1.0F, 0.8F);
        }
        if (fl.pending.isEmpty()) risen(level, v, t);
        Disasters.dirty();
    }

    /** Water set in an empty cell, a source, without a word to its neighbours, and its flow held. */
    static void put(ServerLevel level, BlockPos p) {
        level.setBlock(p, Blocks.WATER.defaultBlockState(), 2 | 16);
        hold(level, p);
    }

    /** The water taken up again, if it is water still (a player's block put there since stays). */
    static void take(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (st.is(Blocks.WATER)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
    }

    /** Its flow held: the game's own tick for it (the spreading) taken back before it comes. */
    static void hold(ServerLevel level, BlockPos p) {
        LevelTicks<net.minecraft.world.level.material.Fluid> ticks = level.getFluidTicks();
        if (ticks.hasScheduledTick(p, Fluids.WATER) || ticks.hasScheduledTick(p, Fluids.FLOWING_WATER)) ticks.clearArea(new BoundingBox(p));
    }

    /** Every tick a flood stands: each of its cells' flow held (a block changed beside it asks the water to spread). */
    static void sweep(ServerLevel level, Villages.Village v, Disasters.Town t) {
        Disasters.Flood fl = t.flood;
        if (fl == null || fl.cells.isEmpty()) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LevelTicks<net.minecraft.world.level.material.Fluid> ticks = level.getFluidTicks();
        int standing = fl.cells.size() - fl.drained, i = 0;
        for (long k : fl.cells) {
            if (i++ >= standing) break;
            m.set(k);
            if (!level.isLoaded(m)) continue;
            if (ticks.hasScheduledTick(m, Fluids.WATER) || ticks.hasScheduledTick(m, Fluids.FLOWING_WATER)) {
                ticks.clearArea(new BoundingBox(m.immutable()));
            }
        }
    }

    /** The flood at its height: the houses it is in, the crops it spoils, the stores it soaks; written down. */
    static void risen(ServerLevel level, Villages.Village v, Disasters.Town t) {
        Disasters.Flood fl = t.flood;
        if (fl == null || fl.risen) return;
        fl.risen = true;
        UUID id = v.id();
        // A house with water on its floor (inside its walls, not only at its door).
        for (long k : fl.cells) {
            BlockPos p = BlockPos.of(k);
            com.jrpetty.mcassistant.village.Ledger.Building b = Rebuilding.buildingAt(id, p, 0);
            if (b == null || !Homes.isHome(b.structure()) || p.getY() > b.anchor().getY() + 1) continue;
            Homes.Home h = Homes.homeAt(id, p);
            if (h != null) fl.homes.add(h.anchor.asLong());
        }
        // The low fields' crops, back to seedlings.
        for (long k : fl.crops) {
            BlockPos p = BlockPos.of(k);
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof CropBlock crop) || crop.getAge(st) == 0) continue;
            level.setBlock(p, crop.getStateForAge(0), 2);
            fl.spoiled++;
        }
        // A store chest the water reaches: its grain, bread, sugar and paper soaked.
        for (BlockPos chest : Villages.storeChests(level, id)) {
            if (fl.soaked >= SOAK_MOST) break;
            boolean wet = false;
            for (Direction d : Direction.values()) if (fl.cells.contains(chest.relative(d).asLong())) wet = true;
            if (!wet || !(level.getBlockEntity(chest) instanceof Container box)) continue;
            for (int i = 0; i < box.getContainerSize() && fl.soaked < SOAK_MOST; i++) {
                ItemStack s = box.getItem(i);
                if (s.isEmpty() || !soaks(s)) continue;
                int lose = Math.min(SOAK_MOST - fl.soaked, (s.getCount() + 3) / 4);
                s.shrink(lose);
                fl.soaked += lose;
            }
            box.setChanged();
        }
        if (fl.soaked > 0) Villages.forgetStock();
        long day = level.getDayTime() / 24000L;
        t.floods++;
        t.cellsFlooded += fl.cells.size();
        t.spoiled += fl.spoiled;
        t.soaked += fl.soaked;
        String name = (fl.rise >= 2 ? "the great flood" : "the flood") + " of day " + (day + 1);
        StringBuilder line = new StringBuilder(capital(name) + ": " + fl.river + " came up over the low ground, "
            + fl.cells.size() + " cells under water");
        if (!fl.homes.isEmpty()) line.append(", ").append(fl.homes.size()).append(fl.homes.size() == 1 ? " house" : " houses").append(" flooded");
        if (fl.spoiled > 0) line.append(", ").append(fl.spoiled).append(" crops spoiled in the low fields");
        if (fl.soaked > 0) line.append(", ").append(fl.soaked).append(" of the stores' goods soaked");
        Disasters.record(id, day, line.toString());
        // The nearest folk to the water says so.
        BlockPos first = fl.cells.isEmpty() ? v.centre() : BlockPos.of(fl.cells.iterator().next());
        VillageFolkEntity near = null;
        double nd = 48.0 * 48.0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping()) continue;
            double d = f.blockPosition().distSqr(first);
            if (d < nd) { nd = d; near = f; }
        }
        String calm = TownTraits.floodWords(id, level.getRandom());     // [identity] a Flood-hardy town does not panic
        if (near != null) FolkTalk.speak(near, calm != null ? calm : FolkTalk.pick(level.getRandom(), "The river's up! It's in the low houses!",
            "Flood! Get the little ones up the hill!", "Look at the water! Everybody up to the high ground!"));
        Disasters.dirty();
    }

    /** What a flood spoils in a store chest: grain, flour's bread, sugar, paper. */
    static boolean soaks(ItemStack s) {
        return s.is(Items.WHEAT) || s.is(Items.BREAD) || s.is(Items.SUGAR) || s.is(Items.PAPER) || s.is(Items.COOKIE);
    }

    /** The flood goes down: from now on a step at a time, the furthest from the river first. */
    static void drain(ServerLevel level, Villages.Village v, Disasters.Town t, String why) {
        Disasters.Flood fl = t.flood;
        if (fl == null || fl.draining) return;
        fl.draining = true;
        fl.pending.clear();
        if (!fl.risen) {                                         // gone down before it was ever up: still counted
            fl.risen = true;
            t.floods++;
            t.cellsFlooded += fl.cells.size();
        }
        Villages.tell(v.id(), level.getDayTime() / 24000L, capital(fl.river) + " began to go down: " + why);
        Disasters.dirty();
    }

    /** The last of it gone: the low ground remembered, the levee planned, and the folk back home. */
    static void gone(ServerLevel level, Villages.Village v, Disasters.Town t) {
        Disasters.Flood fl = t.flood;
        t.flood = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            EVAC.remove(a.getUUID());
            FAILED.remove(a.getUUID());
        }
        if (fl == null) return;
        long day = level.getDayTime() / 24000L;
        Disasters.record(v.id(), day, capital(fl.river) + " went back within its banks, and the low ground came out of the water");
        lowGround(t, fl);
        planLevee(level, v, t, fl);
        Disasters.dirty();
    }

    /** The low ground the river came over: kept off for new buildings. */
    static void lowGround(Disasters.Town t, Disasters.Flood fl) {
        t.lowY = Math.max(t.lowY, fl.w + fl.rise);
        for (long k : fl.cells) {
            BlockPos p = BlockPos.of(k);
            if (t.lowGround.size() >= 2048) break;
            t.lowGround.add(BlockPos.asLong(p.getX() >> 2, 0, p.getZ() >> 2));
        }
    }

    /** Is this low ground the river has come over (a building's ground at or under the water's height there)? */
    public static boolean lowGround(UUID village, BlockPos ground) {
        Disasters.Town t = Disasters.known(village);
        if (t == null || t.lowY == Integer.MIN_VALUE || ground.getY() > t.lowY) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (t.lowGround.contains(BlockPos.asLong((ground.getX() >> 2) + dx, 0, (ground.getZ() >> 2) + dz))) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the levee

    /**
     * Where the levee goes: the flood's cells over land along the river's edge (beside the water it rose from),
     * as high as the water came; on a street or the way to a jetty, a step of slab instead, so folk can still
     * get down to the water.
     */
    static void planLevee(ServerLevel level, Villages.Village v, Disasters.Town t, Disasters.Flood fl) {
        Set<Long> cells = new LinkedHashSet<>(fl.cells);
        List<Long> plan = new ArrayList<>();
        BlockPos c = v.centre();
        for (long k : fl.cells) {
            BlockPos p = BlockPos.of(k);
            if (p.getY() != fl.w + 1) continue;
            BlockState below = level.getBlockState(p.below());
            if (!below.isSolid() || below.getFluidState().is(FluidTags.WATER)) continue;      // over the river itself
            boolean edge = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (level.getFluidState(n.below()).is(FluidTags.WATER) && (cells.contains(n.asLong()) || level.getBlockState(n).isAir())) edge = true;
            }
            if (!edge || Land.inABuilding(v.id(), p)) continue;
            for (int h = 0; h < fl.rise; h++) plan.add(p.above(h).asLong());
        }
        if (plan.isEmpty()) return;
        t.levee.clear();
        t.levee.addAll(plan);
        t.leveeWaits = "";
        Disasters.record(v.id(), level.getDayTime() / 24000L, "after the flood the town began a levee along the bank where "
            + (fl.river.equals("the river") ? "the river" : fl.river) + " came over: " + plan.size() + " blocks of earth to raise");
    }

    /** A step where the way goes down to the water: on a street or the square, or beside a jetty. */
    static boolean wayDown(ServerLevel level, Villages.Village v, BlockPos p) {
        int dx = p.getX() - v.centre().getX(), dz = p.getZ() - v.centre().getZ();
        if (TownPlan.isStreet(dx, dz) || TownPlan.isSquare(dx, dz)) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(p.relative(d).below());
            if (n.is(BlockTags.PLANKS) && level.getFluidState(p.relative(d).below(2)).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** Raise so many blocks of the levee, by hand (TownJobs), out of the stores. Returns how many went up. */
    static int levee(ServerLevel level, Villages.Village v, Disasters.Town t, int most) {
        if (t.levee.isEmpty()) return 0;
        BlockPos first = BlockPos.of(t.levee.get(0));
        if (!level.isLoaded(first)) return 0;
        if (!TownJobs.atWork(level, v, "levee", first, "raising the levee along the bank")) return 0;
        int n = 0;
        while (!t.levee.isEmpty() && n < most) {
            BlockPos p = BlockPos.of(t.levee.get(0));
            BlockState st = level.getBlockState(p);
            boolean open = st.isAir() || com.jrpetty.mcassistant.entity.goal.BuildGoal.isWildPlant(st);
            if (!open) {                                         // something there now (water again, or built): passed by
                t.levee.remove(0);
                continue;
            }
            boolean step = wayDown(level, v, p);
            if (step && level.getBlockState(p.below()).is(BlockTags.SLABS)) {   // the step's upper half: left open
                t.levee.remove(0);
                continue;
            }
            if (!level.getBlockState(p.below()).isSolid()) {                    // nothing under it to bank on
                t.levee.remove(0);
                continue;
            }
            BlockState put = step ? slab(level, v) : earth(level, v);
            if (put == null) {
                t.leveeWaits = step ? "a cobblestone for a step" : "earth or stone";
                Disasters.dirty();
                return n;
            }
            level.setBlock(p, put, 3);
            level.playSound(null, p, put.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
            t.levee.remove(0);
            t.leveeBuilt++;
            n++;
        }
        t.leveeWaits = "";
        if (t.levee.isEmpty()) {
            long day = level.getDayTime() / 24000L;
            t.leveeOn = day;
            Disasters.record(v.id(), day, "the levee was finished on day " + (day + 1) + ": " + t.leveeBuilt
                + " blocks of earth and stone along the bank, out of the stores");
        }
        Disasters.dirty();
        return n;
    }

    /** A block of earth for the levee out of the stores (dirt, else gravel, else cobblestone), or null. */
    @Nullable
    private static BlockState earth(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.DIRT) || s.is(Items.COARSE_DIRT) || s.is(Items.ROOTED_DIRT), 1)) return Blocks.DIRT.defaultBlockState();
        if (Crafts.take(level, v, s -> s.is(Items.GRAVEL), 1)) return Blocks.GRAVEL.defaultBlockState();
        if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return Blocks.COBBLESTONE.defaultBlockState();
        if (Crafts.take(level, v, s -> s.is(Items.COBBLED_DEEPSLATE), 1)) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        return null;
    }

    /** A step of slab: a slab out of the stores, or a cobblestone cut into two (the other half kept). */
    @Nullable
    private static BlockState slab(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE_SLAB), 1)) return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        if (Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) {
            Crafts.store(level, v, new ItemStack(Items.COBBLESTONE_SLAB));
            return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        }
        return null;
    }

    // ------------------------------------------------------------------ the folk

    /** Its home's anchor, or null. */
    @Nullable
    private static Long home(VillageFolkEntity f) {
        BlockPos h = Homes.homeOf(f);
        return h == null ? null : h.asLong();
    }

    /** Is this folk's home under water now? */
    static boolean homeFlooded(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Disasters.Town t = id == null ? null : Disasters.known(id);
        Long h = home(f);
        return t != null && t.flood != null && h != null && t.flood.homes.contains(h);
    }

    static boolean evacuating(VillageFolkEntity f) {
        return EVAC.containsKey(f.getUUID());
    }

    /**
     * From the folk's tick (Disasters.hold): a folk in a flooded house, or a child in the water (anybody, in a
     * great flood), goes to the high ground and waits there; its breath kept, whatever. True while it is on
     * its way.
     */
    static boolean evacuate(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Disasters.Town t = id == null ? null : Disasters.known(id);
        Disasters.Flood fl = t == null ? null : t.flood;
        Evac e = EVAC.get(f.getUUID());
        if (fl == null) {
            if (e != null) EVAC.remove(f.getUUID());
            return false;
        }
        BlockPos feet = f.blockPosition();
        boolean wet = fl.cells.contains(feet.asLong()) || fl.cells.contains(feet.above().asLong());
        if (wet && f.isEyeInFluid(FluidTags.WATER) && f.getAirSupply() < f.getMaxAirSupply()) {
            f.setAirSupply(f.getMaxAirSupply());                 // nobody drowns in the town's flood
            BREATH.merge(f.getUUID(), 1, Integer::sum);
        }
        Homes.Home in = Homes.homeAt(id, feet);
        boolean indoors = in != null && fl.homes.contains(in.anchor.asLong());
        boolean need = indoors && feet.getY() <= fl.w + fl.rise + 1 || wet && (f.isBaby() || fl.rise >= 2);
        if (fl.draining && e == null) return false;
        if (e == null && !need) return false;
        long now = level.getGameTime();
        if (e == null) {
            BlockPos to = refuge(level, Villages.get(id), fl, f);
            if (to == null) return false;
            e = new Evac(to, now);
            EVAC.put(f.getUUID(), e);
            if (f.isSleeping()) f.stopSleeping();
            f.clearQueue();
            f.getNavigation().stop();
            f.brain("the flood is in: up to the high ground");
            if (f.getRandom().nextInt(2) == 0) {
                FolkTalk.speak(f, f.isBaby() ? FolkTalk.pick(f.getRandom(), "The water's coming in!", "My feet are all wet!")
                    : FolkTalk.pick(f.getRandom(), "The water's in the house! Out, out!", "Up the hill, everyone!", "Mind the step — it's all water down here."));
            }
        }
        double dx = f.getX() - (e.to.getX() + 0.5), dz = f.getZ() - (e.to.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= 2.0 && !need || !need && !wet && d <= 6.0) {
            if (Weather.stormy(level) && homeFlooded(f)) {
                // A thunderstorm, and its home under water: it waits it out up here, not back down in the flood.
                f.getNavigation().stop();
                f.hobbyNow = "waiting on the high ground for the flood to go down";
                return true;
            }
            EVAC.remove(f.getUUID());
            f.getNavigation().stop();
            f.brain("on the high ground, out of the flood");
            return false;
        }
        if (d < e.best - 0.5) {
            e.best = d;
            e.progress = now;
        } else if (now - e.progress > 600 || now < e.progress) {
            EVAC.remove(f.getUUID());                            // no way there: another look in a moment, elsewhere
            FAILED.computeIfAbsent(f.getUUID(), k -> ConcurrentHashMap.newKeySet()).add(e.to.asLong());
            return false;
        }
        if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 30) {
            f.walkTo(e.to, 1.25D);
            e.walkTick = f.tickCount;
        }
        f.hobbyNow = "getting out of the flood";
        return true;
    }

    /** The nearest high ground: open ground over the flood's height, out of the water, not in a flooded house. */
    @Nullable
    static BlockPos refuge(ServerLevel level, @Nullable Villages.Village v, Disasters.Flood fl, VillageFolkEntity f) {
        if (v == null) return null;
        int dry = fl.w + fl.rise + 1;
        BlockPos me = f.blockPosition();
        for (int r = 3; r <= 48; r += 3) {
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (int i = -r; i <= r; i += 3) {
                for (int[] p : new int[][]{ { i, -r }, { i, r }, { -r, i }, { r, i } }) {
                    BlockPos s = Land.surface(level, me.getX() + p[0], me.getZ() + p[1]);
                    if (s == null || s.getY() < dry || s.getY() > dry + 12) continue;
                    if (fl.cells.contains(s.asLong()) || !level.getFluidState(s).isEmpty()) continue;
                    if (!level.getBlockState(s).isAir() || !level.getBlockState(s.above()).isAir()) continue;
                    BlockState under = level.getBlockState(s.below());
                    if (!under.isSolid() || !under.getFluidState().isEmpty()) continue;   // not the top of the water
                    Homes.Home h = Homes.homeAt(v.id(), s);
                    if (h != null && fl.homes.contains(h.anchor.asLong())) continue;
                    if (Rebuilding.buildingAt(v.id(), s, 0) != null) continue;          // not up on somebody's roof
                    Set<Long> failed = FAILED.get(f.getUUID());
                    if (failed != null && failed.contains(s.asLong())) continue;     // tried, and there was no way up

                    double d = s.distSqr(me);
                    if (d < bd) { bd = d; best = s; }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Its card's word: out of the flood, its home under water. */
    @Nullable
    static String cardPart(VillageFolkEntity f) {
        if (EVAC.containsKey(f.getUUID())) return "getting out of the flood to the high ground";
        if (homeFlooded(f)) return "its home is under the flood: sleeping at a neighbour's till the water goes down";
        return null;
    }

    static String capital(String s) {
        return Disasters.capital(s);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the river rises now (rise 1 or 2) whatever the weather, and fills at once. Returns the cells filled. */
    public static int floodForTests(ServerLevel level, UUID village, int rise) {
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        Disasters.Town t = Disasters.town(village);
        if (rise(level, v, t, rise) == 0) return 0;
        for (int i = 0; i < 400 && t.flood != null && !t.flood.risen; i++) step(level, v, t);
        return t.flood == null ? 0 : t.flood.cells.size();
    }

    /** Tests: the flood's cells as filled (empty with none). */
    public static List<BlockPos> cellsForTests(UUID village) {
        Disasters.Town t = Disasters.known(village);
        List<BlockPos> out = new ArrayList<>();
        if (t != null && t.flood != null) for (long k : t.flood.cells) out.add(BlockPos.of(k));
        return out;
    }

    /** Tests: the flood goes down now, all of it. Returns how many cells it took up. */
    public static int drainForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Disasters.Town t = Disasters.known(village);
        if (v == null || t == null || t.flood == null) return 0;
        int n = t.flood.cells.size();
        drain(level, v, t, "the test");
        for (int i = 0; i < 400 && t.flood != null; i++) step(level, v, t);
        return n;
    }

    /** Tests: the flood's river level and rise, and the houses it is in: {w, rise, homes}; null with none. */
    @Nullable
    public static int[] floodNowForTests(UUID village) {
        Disasters.Town t = Disasters.known(village);
        if (t == null || t.flood == null) return null;
        return new int[]{ t.flood.w, t.flood.rise, t.flood.homes.size(), t.flood.spoiled, t.flood.soaked };
    }

    /** Tests: the levee's blocks still to raise. */
    public static List<BlockPos> leveePlanForTests(UUID village) {
        Disasters.Town t = Disasters.known(village);
        List<BlockPos> out = new ArrayList<>();
        if (t != null) for (long k : t.levee) out.add(BlockPos.of(k));
        return out;
    }

    /** Tests: raise the levee now (as far as the stores pay). Returns the blocks raised. */
    public static int leveeForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Disasters.Town t = Disasters.known(village);
        if (v == null || t == null) return 0;
        int n = 0;
        for (int i = 0; i < 200 && !t.levee.isEmpty(); i++) {
            int k = levee(level, v, t, 8);
            if (k == 0) break;
            n += k;
        }
        return n;
    }

    /** [identity] Tests: one of the town's own steps at its levee, as its look every second takes it (Flood-hardy: twice the blocks). */
    public static int leveeStepForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Disasters.Town t = Disasters.known(village);
        return v == null || t == null ? 0 : levee(level, v, t, TownTraits.leveePace(village, 3));
    }

    /** Tests: how many times the flood has given this folk its breath. */
    public static int breathForTests(VillageFolkEntity f) {
        return BREATH.getOrDefault(f.getUUID(), 0);
    }

    /** Tests: the water a test has laid out is the town's river (its box, and the river's level), as a scene's is. */
    public static void riverForTests(UUID village, BlockPos a, BlockPos b, int level) {
        stage(village, a.getX(), a.getZ(), b.getX(), b.getZ(), level);
    }

    /** Tests: let the town flood again this season. */
    public static void againForTests(UUID village) {
        Disasters.town(village).floodSeason = -1;
    }
}
