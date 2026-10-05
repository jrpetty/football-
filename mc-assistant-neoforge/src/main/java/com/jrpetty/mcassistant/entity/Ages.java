package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every building grows up with its village, not only the houses (Grow does their gardens, brick
 * and second storeys): so a village that has come through the ages looks it, street by street.
 * <ul>
 * <li><b>The Stone Age:</b> the timber walls of the village's buildings are rebuilt in stone — the
 *     land's own (sandstone, terracotta, andesite) where the stores have it — and lamp posts go up
 *     either side of every door.</li>
 * <li><b>The Iron Age:</b> slate roofs (deepslate tiles) in place of the wooden ones, and dressed
 *     stone footings in place of the rough cobble.</li>
 * <li><b>The Diamond Age:</b> the great buildings of the place — the hall, the chapel, the library,
 *     the granary, the tavern, the guest house, the market, the towers — are roofed in copper,
 *     which goes green with the years; and moss gets into the old footings.</li>
 * </ul>
 * Done by the village's builders as town work, a few blocks a visit, and out of its stores: a
 * block of stone for a stone wall, a brick or four for brick, a stone of slate (cobbled deepslate,
 * or cobble) for each tile, a copper ingot for each piece of copper roof, a torch and two planks
 * for a lamp post. What comes off goes back into the stores.
 */
public final class Ages {

    private Ages() {}

    /** No walls or roof of their own to make over: they keep the look they were built with. */
    static final Set<String> AS_BUILT = Set.of("well", "gateway", "monument", "graveyard", "fortify", "pen", "platform",
        "wall", "column", "room", "fountain", "court");

    /** The village's great buildings: copper roofs in the Diamond Age. */
    static final Set<String> GREAT = Set.of("hall", "chapel", "library", "granary", "tavern", "guesthouse", "market",
        "watchtower", "lighthouse", "barracks", "belltower", "manor", "townhall");

    /** The buildings that go up a storey in the Iron Age: the meeting hall becomes a town hall, the tavern
     *  an inn with rooms over the bar, the library and the shops a floor of their own above. */
    public static final Set<String> TALL = Set.of("hall", "tavern", "library", "guesthouse", "shop", "cafe", "workshop",
        "brewery", "smithy", "granary");

    /** The look of a storey put on a building in the Iron Age: dressed stone under slate. */
    static final com.jrpetty.mcassistant.Showcase.Palette CIVIC = new com.jrpetty.mcassistant.Showcase.Palette(
        Blocks.STONE_BRICKS, Blocks.DARK_OAK_LOG, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES,
        Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE, Blocks.RED_BED, Blocks.RED_CARPET);

    /** Each building's age once it has been made over for it (by its anchor), so it is not looked at again. */
    private static final Map<Long, Integer> DONE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DONE.clear();
    }

    /** The drawing standing at this building now (a grown house is a house2). */
    static String drawing(UUID village, Ledger.Building b) {
        if (b.structure().equals("house") && Ledger.grown(village, b.anchor())) return "house2";
        if (Grow.tall(village, b.anchor())) return b.structure() + Blueprints.TALL;
        return b.structure();
    }

    // ------------------------------------------------------------------ the looks of the ages

    /** What a block of this style should be in this age, given what stands there now; null to leave it. */
    @Nullable
    static BlockState look(Villages.Age age, String drawing, Blueprints.Style style, BlockState now, BlockPos pos,
                           @Nullable Block landStone) {
        // A building with a storey put on is still the same building.
        String structure = drawing.endsWith(Blueprints.TALL) ? drawing.substring(0, drawing.length() - Blueprints.TALL.length()) : drawing;
        boolean home = structure.equals("house") || structure.equals("house2");
        boolean stone = age.ordinal() >= Villages.Age.STONE.ordinal();
        boolean iron = age.ordinal() >= Villages.Age.IRON.ordinal();
        boolean diamond = age.ordinal() >= Villages.Age.DIAMOND.ordinal();
        boolean great = GREAT.contains(structure);
        switch (style) {
            case WALL -> {
                // A manor is brick from the Iron Age it is built in.
                if (structure.equals("manor") && iron && now.is(BlockTags.PLANKS)) return Blocks.BRICKS.defaultBlockState();
                // The houses are Grow's: stone, then brick.
                if (home || !stone || !now.is(BlockTags.PLANKS)) return null;
                return (landStone != null ? landStone : Blocks.STONE_BRICKS).defaultBlockState();
            }
            case FOUNDATION, WALL_LOW -> {
                if (diamond && Math.floorMod(pos.hashCode(), 3) == 0) {
                    if (now.is(Blocks.STONE_BRICKS)) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                    if (now.is(Blocks.COBBLESTONE)) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
                }
                if (iron && now.is(Blocks.COBBLESTONE)) return Blocks.STONE_BRICKS.defaultBlockState();
                return null;
            }
            case ROOF_STAIR, ROOF_STAIR_TOP -> {
                if (diamond && great && (now.is(BlockTags.WOODEN_STAIRS) || now.is(Blocks.DEEPSLATE_TILE_STAIRS))) {
                    return like(now, Blocks.CUT_COPPER_STAIRS);
                }
                if (iron && now.is(BlockTags.WOODEN_STAIRS)) return like(now, Blocks.DEEPSLATE_TILE_STAIRS);
                return null;
            }
            case ROOF_SLAB, ROOF_SLAB_TOP -> {
                if (diamond && great && (now.is(BlockTags.WOODEN_SLABS) || now.is(Blocks.DEEPSLATE_TILE_SLAB))) {
                    return like(now, Blocks.CUT_COPPER_SLAB);
                }
                if (iron && now.is(BlockTags.WOODEN_SLABS)) return like(now, Blocks.DEEPSLATE_TILE_SLAB);
                return null;
            }
            case ROOF_BLOCK -> {
                if (diamond && great && (now.is(BlockTags.PLANKS) || now.is(Blocks.DEEPSLATE_TILES))) {
                    return Blocks.CUT_COPPER.defaultBlockState();
                }
                if (iron && now.is(BlockTags.PLANKS)) return Blocks.DEEPSLATE_TILES.defaultBlockState();
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code to}, set the way {@code from} is set (a stair's facing and half, a slab's type). */
    static BlockState like(BlockState from, Block to) {
        BlockState s = to.defaultBlockState();
        for (Property<?> p : from.getProperties()) if (s.hasProperty(p)) s = copy(s, from, p);
        return s;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState s, BlockState from, Property<T> p) {
        return s.setValue(p, from.getValue(p));
    }

    /** Could the stores pay for a block of the new look (nothing taken)? */
    private static boolean canPay(ServerLevel level, Villages.Village v, BlockState want, @Nullable Homeland.Stone local) {
        Block b = want.getBlock();
        if (local != null && b == local.block()) return Crafts.stock(level, v, local.pay()) >= local.each();
        if (b == Blocks.STONE_BRICKS) {
            return Crafts.stock(level, v, s -> s.is(Items.STONE_BRICKS)) > 0
                || Crafts.stock(level, v, s -> s.is(Items.COBBLESTONE)) > 0 && Crafts.stoneToSpare(level, v);
        }
        if (b == Blocks.BRICKS) return Crafts.stock(level, v, s -> s.is(Items.BRICKS)) > 0 || Crafts.stock(level, v, s -> s.is(Items.BRICK)) >= 4;
        if (b == Blocks.DEEPSLATE_TILES || b == Blocks.DEEPSLATE_TILE_STAIRS || b == Blocks.DEEPSLATE_TILE_SLAB) {
            return Crafts.stock(level, v, s -> s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.COBBLESTONE)) > 0;
        }
        if (b == Blocks.CUT_COPPER || b == Blocks.CUT_COPPER_STAIRS || b == Blocks.CUT_COPPER_SLAB) {
            return Crafts.stock(level, v, s -> s.is(Items.COPPER_INGOT)) > 0;
        }
        return true;
    }

    /** Pay for a block of the new look out of the stores; false if they cannot. */
    private static boolean pay(ServerLevel level, Villages.Village v, BlockState want, @Nullable Homeland.Stone local) {
        Block b = want.getBlock();
        if (local != null && b == local.block()) return Crafts.take(level, v, local.pay(), local.each());
        if (b == Blocks.STONE_BRICKS) return Crafts.masonryForLooks(level, v);
        if (b == Blocks.BRICKS) {
            return Crafts.take(level, v, s -> s.is(Items.BRICKS), 1) || Crafts.take(level, v, s -> s.is(Items.BRICK), 4);
        }
        if (b == Blocks.DEEPSLATE_TILES || b == Blocks.DEEPSLATE_TILE_STAIRS || b == Blocks.DEEPSLATE_TILE_SLAB) {
            return Crafts.take(level, v, s -> s.is(Items.COBBLED_DEEPSLATE), 1) || Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), 1);
        }
        if (b == Blocks.CUT_COPPER || b == Blocks.CUT_COPPER_STAIRS || b == Blocks.CUT_COPPER_SLAB) {
            return Crafts.take(level, v, s -> s.is(Items.COPPER_INGOT), 1);
        }
        return true;                                                       // moss: the years, for nothing
    }

    // ------------------------------------------------------------------ the work

    /** Up to so many blocks of work on the village's buildings for its age. Returns the blocks changed. */
    public static int work(ServerLevel level, Villages.Village v, int budget) {
        UUID id = v.id();
        Villages.Age age = Villages.ageOf(id);
        if (age.ordinal() < Villages.Age.STONE.ordinal() || budget <= 0) return 0;
        int done = 0;
        // The Iron Age's second storeys first, one building at a time, oldest first: the new storey
        // then gets the age's make-over with the rest.
        if (age.ordinal() >= Villages.Age.IRON.ordinal()) {
            for (Ledger.Building b : Ledger.buildings(id)) {
                if (!TALL.contains(b.structure()) || Grow.tall(id, b.anchor()) || !Land.areaLoaded(level, b.anchor(), 9)) continue;
                if (!Blueprints.has(b.structure() + Blueprints.TALL)) continue;
                done += Grow.raise(level, v, b, b.structure(), b.structure() + Blueprints.TALL, budget, CIVIC, false);
                if (Grow.raisingNow(id, b.anchor()) || done >= budget) return done;
                // Not begun: the stores could not pay for it yet. The make-overs go on meanwhile.
                break;
            }
        }
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (AS_BUILT.contains(b.structure()) || !Land.areaLoaded(level, b.anchor(), 9)) continue;
            if (DONE.getOrDefault(b.anchor().asLong(), -1) >= age.ordinal()) continue;
            if (Grow.raisingNow(id, b.anchor())) continue;
            // A house waiting on (or raising) its second storey is Grow's first: its old roof is coming off.
            if (b.structure().equals("house") && age.ordinal() >= Villages.Age.IRON.ordinal()
                    && (!Ledger.grown(id, b.anchor()) || Ledger.raising(id, b.anchor()))) continue;
            int n = makeOver(level, v, b, age, budget - done, false);
            done += n;
            if (n > 0) break;                                             // one building at a time
            // Nothing done: finished (not looked at again this age), or waiting on the stores (the
            // next building is looked at instead of standing about).
            if (fullyDone(level, v, b, age)) DONE.put(b.anchor().asLong(), age.ordinal());
        }
        return done;
    }

    /** Is there nothing left to make over on this building for its age? */
    static boolean fullyDone(ServerLevel level, Villages.Village v, Ledger.Building b, Villages.Age age) {
        String plan = drawing(v.id(), b);
        if (!Blueprints.has(plan)) return true;
        Homeland.Stone local = Homeland.walls(v.id());
        Block land = local != null && Crafts.stock(level, v, local.pay()) >= 16 ? local.block() : null;
        for (BuildGoal.Placement p : BuildGoal.plan(plan, b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            if (look(age, plan, p.style(), level.getBlockState(p.pos()), p.pos(), land) != null) return false;
        }
        return lampsDone(level, b, plan);
    }

    /**
     * Make a building over for its age, up to so many blocks: its walls, roof and footings, then
     * the lamp posts by its door. Paid out of the stores (unless {@code free}: the showcase, tests);
     * what comes off goes back in. Returns the blocks changed.
     */
    public static int makeOver(ServerLevel level, Villages.Village v, Ledger.Building b, Villages.Age age, int budget,
                               boolean free) {
        String plan = v == null ? b.structure() : drawing(v.id(), b);
        if (!Blueprints.has(plan)) return 0;
        Homeland.Stone local = v == null ? null : Homeland.walls(v.id());
        Block land = local != null && Crafts.stock(level, v, local.pay()) >= 16 ? local.block() : null;
        java.util.List<BuildGoal.Placement> todo = new java.util.ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(plan, b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            BlockState want = look(age, plan, p.style(), level.getBlockState(p.pos()), p.pos(), land);
            // Only what the stores can pay for: a builder is not called out to stand by a wall
            // waiting on stone that is not there.
            if (want != null && (free || canPay(level, v, want, local))) todo.add(p);
        }
        int n = 0;
        if (!todo.isEmpty()) {
            if (!free && !TownJobs.atWork(level, v, "walls", b.anchor(), workWords(age, plan))) return 0;
            Map<Item, Integer> back = new HashMap<>();
            for (BuildGoal.Placement p : todo) {
                if (n >= budget) break;
                BlockState now = level.getBlockState(p.pos());
                BlockState want = look(age, plan, p.style(), now, p.pos(), land);
                if (want == null) continue;
                if (!free && !pay(level, v, want, local)) break;
                if (!free && !want.is(Blocks.MOSSY_COBBLESTONE) && !want.is(Blocks.MOSSY_STONE_BRICKS)) {
                    back.merge(now.getBlock().asItem(), 1, Integer::sum);
                }
                level.setBlock(p.pos(), want, 3);
                n++;
            }
            if (!free) for (Map.Entry<Item, Integer> e : back.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
            if (n > 0) return n;
        }
        return n + lamps(level, v, b, plan, free);
    }

    private static String workWords(Villages.Age age, String drawing) {
        String plan = drawing.endsWith(Blueprints.TALL) ? drawing.substring(0, drawing.length() - Blueprints.TALL.length()) : drawing;
        String what = Villages.spoken(plan);
        if (age.ordinal() >= Villages.Age.DIAMOND.ordinal() && GREAT.contains(plan)) return "roofing " + what + " in copper";
        if (age.ordinal() >= Villages.Age.IRON.ordinal()) return "slating the roof of " + what;
        return "rebuilding " + what + " in stone";
    }

    // ------------------------------------------------------------------ lamp posts

    /** Where the lamp posts by a building's doors go: either side of each ground-floor door, a step out. */
    static java.util.List<BlockPos> lampSpots(Ledger.Building b, String plan) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        Direction front = b.facing().getOpposite();
        for (BuildGoal.Placement p : BuildGoal.plan(plan, b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.DOOR || p.pos().getY() != b.anchor().getY()) continue;
            BlockPos step = p.pos().relative(front);
            out.add(step.relative(front.getClockWise(), 2));
            out.add(step.relative(front.getCounterClockWise(), 2));
        }
        return out;
    }

    static boolean lampsDone(ServerLevel level, Ledger.Building b, String plan) {
        for (BlockPos s : lampSpots(b, plan)) if (lampFits(level, s)) return false;
        return true;
    }

    /** Open ground for a post and its lamp: air for two above firm ground, nobody's path or field. */
    private static boolean lampFits(ServerLevel level, BlockPos s) {
        if (level.getBlockState(s).is(BlockTags.FENCES)) return false;
        return level.getBlockState(s).isAir() && level.getBlockState(s.above()).isAir()
            && level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)
            && !level.getBlockState(s.below()).is(Blocks.FARMLAND) && !level.getBlockState(s.below()).is(Blocks.DIRT_PATH);
    }

    /** Lamp posts by the doors (from the Stone Age): a fence post and a lantern each, of two planks and a torch. */
    static int lamps(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, String plan, boolean free) {
        if (v != null && Villages.ageOf(v.id()).ordinal() < Villages.Age.STONE.ordinal() && !free) return 0;
        int n = 0;
        for (BlockPos s : lampSpots(b, plan)) {
            if (!lampFits(level, s)) continue;
            // Never out in the street or on the square.
            if (v != null) {
                int dx = s.getX() - v.centre().getX(), dz = s.getZ() - v.centre().getZ();
                if (com.jrpetty.mcassistant.village.TownPlan.isStreet(dx, dz)
                    || com.jrpetty.mcassistant.village.TownPlan.isSquare(dx, dz)) continue;
            }
            if (!free) {
                if (!TownJobs.atWork(level, v, "walls", s, "putting up lamp posts")) return n;
                if (Crafts.stock(level, v, st -> st.is(Items.TORCH)) < 1) return n;
                if (!Crafts.usePlanks(level, v, 2)) return n;
                Crafts.take(level, v, st -> st.is(Items.TORCH), 1);
            }
            level.setBlock(s, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
            level.setBlock(s.above(), Blocks.LANTERN.defaultBlockState(), 3);
            n += 2;
        }
        return n;
    }

    /** A building made over for an age all at once, for nothing (the showcase, tests). */
    public static void now(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, Villages.Age age) {
        for (int i = 0; i < 8; i++) if (makeOver(level, v, b, age, 10000, true) == 0) break;
    }
}
