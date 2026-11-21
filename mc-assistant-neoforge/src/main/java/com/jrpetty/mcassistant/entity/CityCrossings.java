package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [fixes14] A town's streets carried over its water: the crossings laid before a house goes up on the far bank.
 *
 * <p>The plan's streets run straight across a lake or a channel; the folk walk only what is laid (CityPlan). A town that
 * had filled every lot it could walk to took, after a dozen looks, a lot it could not: across the water from the square,
 * with no way to it but to swim. Now a lot over the water is never taken so (CityPlan.reachesDry); instead, once the town
 * has passed over lots for want of a street to them, it carries its street over: where a street the town walks runs into
 * water and out onto dry ground again within {@link #SPAN} blocks, the street's whole width is decked across at the
 * height of the bank it leaves, in the town's planks (cobblestone where it has no timber to spare), by a hand at the
 * town's works ("laying a bridge over the water"), a few blocks a visit. The deck laid, the walk goes on over it, and the
 * lots on the far bank are on the town's streets like any other.
 */
public final class CityCrossings {

    private CityCrossings() {}

    /** The most water a crossing spans (a river's width, not a lake's). */
    static final int SPAN = 9;
    /** Deck laid at a visit. */
    private static final int AT_A_VISIT = 6;
    /** How often the crossings are worked out afresh (ticks). */
    private static final long EVERY = 2400L;
    /** Timber the stores keep for the builders before a plank goes in a deck. */
    private static final int KEEP = 48;

    /** A crossing: the deck's cells (x, z), the height it is laid at, and where it starts (for the news). */
    record Crossing(List<long[]> cells, int y, BlockPos from) {}

    private record Plan(long at, List<Crossing> crossings) {}

    private static final Map<UUID, Plan> PLANS = new ConcurrentHashMap<>();
    /** Deck cells laid, by town (the books and the tests). */
    private static final Map<UUID, Integer> LAID = new ConcurrentHashMap<>();

    public static void resetForTests() {
        PLANS.clear();
        LAID.clear();
    }

    /** Tests and the books: how many deck cells this town has laid. */
    public static int laid(UUID village) {
        return LAID.getOrDefault(village, 0);
    }

    /** The town's crossings still to lay, worked out afresh every two minutes at most. */
    static List<Crossing> plan(ServerLevel level, Villages.Village v, long now) {
        Plan p = PLANS.get(v.id());
        if (p != null && now - p.at() < EVERY && now >= p.at()) return p.crossings();
        p = new Plan(now, find(level, v));
        PLANS.put(v.id(), p);
        return p.crossings();
    }

    /** Tests: the crossings the town would lay now, as "from: n cells at y". */
    public static List<String> planForTests(ServerLevel level, Villages.Village v) {
        PLANS.remove(v.id());
        CityPlan.forget(v.id());
        List<String> out = new ArrayList<>();
        for (Crossing c : plan(level, v, level.getGameTime())) out.add(c.from().toShortString() + ": " + c.cells().size() + " cells at y " + c.y());
        return out;
    }

    /**
     * Where the walked streets run into water and out again: from each walked street cell, along the street each of the
     * four ways, over water (and nothing else) for no more than {@link #SPAN} cells to a street cell with a floor near the
     * bank's height that the walk has not reached. The crossing is the street's whole width over the water there.
     */
    static List<Crossing> find(ServerLevel level, Villages.Village v) {
        CityPlan.Net n = CityPlan.net(level, v);
        int r = CityPlan.R, side = 2 * r + 1;
        BlockPos heart = v.centre();
        List<Crossing> out = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        int[][] ways = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int h = n.floor()[(dx + r) * side + (dz + r)];
                if (h == CityPlan.NONE) continue;
                for (int[] w : ways) {
                    List<long[]> wet = new ArrayList<>();
                    boolean landed = false;
                    for (int k = 1; k <= SPAN + 1; k++) {
                        int x = dx + w[0] * k, z = dz + w[1] * k;
                        if (Math.abs(x) > r || Math.abs(z) > r || !CityPlan.walkway(x, z) || n.has(x, z)) break;
                        int f = CityPlan.floorNear(level, heart.getX() + x, heart.getZ() + z, h, 2, 3);
                        if (f == CityPlan.WET) { wet.add(new long[]{ x, z }); continue; }
                        landed = f != CityPlan.NONE && !wet.isEmpty();
                        break;
                    }
                    if (!landed || wet.size() > SPAN) continue;
                    // The street's whole width over the water: the cells beside each, across the way, while street and wet.
                    Set<Long> deck = new LinkedHashSet<>();
                    for (long[] c : wet) {
                        deck.add(BlockPos.asLong((int) c[0], 0, (int) c[1]));
                        for (int s = -1; s <= 1; s += 2) {
                            for (int k = 1; k < TownPlan.STREET + TownPlan.AVENUE * 2; k++) {
                                int x = (int) c[0] + w[1] * s * k, z = (int) c[1] + w[0] * s * k;
                                if (Math.abs(x) > r || Math.abs(z) > r || !CityPlan.walkway(x, z)) break;
                                if (CityPlan.floorNear(level, heart.getX() + x, heart.getZ() + z, h, 2, 3) != CityPlan.WET) break;
                                deck.add(BlockPos.asLong(x, 0, z));
                            }
                        }
                    }
                    deck.removeIf(used::contains);
                    if (deck.isEmpty()) continue;
                    used.addAll(deck);
                    List<long[]> cells = new ArrayList<>();
                    for (long c : deck) cells.add(new long[]{ BlockPos.getX(c), BlockPos.getZ(c) });
                    out.add(new Crossing(cells, h, heart.offset(dx, 0, dz).atY(h)));
                }
            }
        }
        // The nearest the heart first: the town carries its streets out as it grows.
        out.sort(java.util.Comparator.comparingInt(c -> Math.abs(c.from().getX() - heart.getX()) + Math.abs(c.from().getZ() - heart.getZ())));
        return out;
    }

    /**
     * The crossings laid, a few deck cells a visit, by a hand at the town's works: only once the town has lots passed over
     * for want of a street to them (CityPlan.wanting), the nearest crossing first.
     */
    public static void work(ServerLevel level, Villages.Village v, long now) {
        UUID id = v.id();
        if (!CityPlan.wanting(id)) return;
        List<Crossing> todo = plan(level, v, now);
        if (todo.isEmpty()) return;
        Crossing c = todo.get(0);
        int laid = 0;
        boolean all = true;
        for (long[] cell : c.cells()) {
            BlockPos at = new BlockPos(v.centre().getX() + (int) cell[0], c.y(), v.centre().getZ() + (int) cell[1]);
            if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) { all = false; continue; }
            BlockState there = level.getBlockState(at);
            if (!there.getFluidState().isEmpty() || there.isAir() || there.canBeReplaced()) {
                all = false;
                if (laid >= AT_A_VISIT) break;
                if (!TownBlocks.mayPlace(level, v, at, TownBlocks.Why.ROAD)) continue;
                if (!TownJobs.atWork(level, v, "streets", at, "laying a bridge over the water")) return;
                Block deck = deckOf(level, v);
                if (deck == null) return;                         // nothing in the stores to lay it in: it waits
                level.setBlockAndUpdate(at, deck.defaultBlockState());
                // The water over the deck's height (a bank lower than the water's top) cleared off it.
                for (int up = 1; up <= 2; up++) {
                    BlockPos o = at.above(up);
                    if (!level.getFluidState(o).isEmpty() && level.getBlockState(o).canBeReplaced()) level.setBlockAndUpdate(o, Blocks.AIR.defaultBlockState());
                }
                laid++;
                LAID.merge(id, 1, Integer::sum);
            }
        }
        if (all) {
            // The deck down: the walk goes over it now, and the next crossing is looked for.
            PLANS.remove(id);
            CityPlan.forget(id);
            Villages.tell(id, level.getDayTime() / 24000L, "the town laid a bridge over the water at " + c.from().getX() + ", " + c.from().getZ()
                + ": its street goes on to the far bank");
        }
    }

    /** What a deck is laid in: the town's own planks while the stores keep the builders' timber, else cobblestone (taken). */
    private static Block deckOf(ServerLevel level, Villages.Village v) {
        String wood = Masonry.woodOf(level, v);
        Block planks = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_planks"));
        if (planks == Blocks.AIR) planks = Blocks.OAK_PLANKS;
        if (Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)) > KEEP && TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 1)) return planks;
        Item stone = Items.COBBLESTONE;
        if (Market.stock(level, v.id(), s -> s.is(stone)) > CityStreets.KEEP && TownWork.take(level, v, s -> s.is(stone), 1)) return Blocks.COBBLESTONE;
        return null;
    }
}
