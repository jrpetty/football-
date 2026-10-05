package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The mason's trade: what every dressed block the village lays is made of, and who makes it.
 *
 * <p>Nothing a village builds is conjured. Every block it lays has a way to it from what the land
 * gives, through the hands of its own trades:
 * <ul>
 * <li><b>Stone bricks.</b> The miners' cobblestone, only what the village can spare, carried to the
 *     smeltery and smelted to stone; the smelter cuts the stone at its bench, four for four. Stone
 *     smelted a second time is <b>smooth stone</b> (the stone slabs, an armour stand's foot).</li>
 * <li><b>Bricks.</b> Clay dug out of a river or pond bed by the smelter (a block of clay is four
 *     balls), fired at the smeltery into bricks, and four bricks make a block of brick.</li>
 * <li><b>Slate</b> (deepslate tiles). Cobbled deepslate from the deep galleries, dressed through
 *     polished deepslate and deepslate bricks to tiles, four for four each time. A village whose
 *     mines never went that deep roofs in stone instead.</li>
 * <li><b>Cut copper.</b> Nine ingots to a block of copper, four blocks to four of cut copper: nine
 *     ingots a block of roof, and dearer for the stairs.</li>
 * <li><b>Mossy stone.</b> A block of cobblestone or stone bricks and a vine (or a moss block); the
 *     woodcutters cut the vines when they come across them. A nicety: laid when the stores have
 *     it, and the plain block when they have not.</li>
 * <li><b>A lantern.</b> Eight iron nuggets (an ingot makes nine) and a torch, the smith's work and
 *     only out of iron the village can spare. Until it has one a torch does, on every lamp post.</li>
 * </ul>
 * Stairs, slabs and chiselled stone are cut from their blocks in whole batches, as a player cuts
 * them (six blocks make four stairs, three make six slabs), and what is left of a batch goes back
 * into the stores for the next. Iron is never laid on a roof: it is the village's tools and armour.
 */
public final class Masonry {

    private Masonry() {}

    // ------------------------------------------------------------------ the recipes

    /** One input of a way to make something: so many of a thing. */
    private record In(Item item, int n) {}

    /** One way to make a thing: what goes in, and how many come out of a batch. */
    private record Way(int yield, List<In> ins) {}

    private static final Map<Item, List<Way>> WAYS = new HashMap<>();

    /** A way to make {@code out}: {@code yield} of it from the pairs (item, how many) that follow. */
    private static void way(Item out, int yield, Object... ins) {
        List<In> list = new ArrayList<>();
        for (int i = 0; i + 1 < ins.length; i += 2) list.add(new In((Item) ins[i], (Integer) ins[i + 1]));
        WAYS.computeIfAbsent(out, k -> new ArrayList<>()).add(new Way(yield, List.copyOf(list)));
    }

    static {
        // The bench: stone cut into bricks, bricks into steps and slabs, two slabs into a chiselled block.
        way(Items.STONE_BRICKS, 4, Items.STONE, 4);
        way(Items.STONE_BRICK_STAIRS, 4, Items.STONE_BRICKS, 6);
        way(Items.STONE_BRICK_SLAB, 6, Items.STONE_BRICKS, 3);
        way(Items.CHISELED_STONE_BRICKS, 1, Items.STONE_BRICK_SLAB, 2);
        way(Items.COBBLESTONE_STAIRS, 4, Items.COBBLESTONE, 6);
        way(Items.COBBLESTONE_SLAB, 6, Items.COBBLESTONE, 3);
        way(Items.SMOOTH_STONE_SLAB, 6, Items.SMOOTH_STONE, 3);
        // Moss: a vine, or a block of moss, worked into the stone.
        way(Items.MOSSY_STONE_BRICKS, 1, Items.STONE_BRICKS, 1, Items.VINE, 1);
        way(Items.MOSSY_STONE_BRICKS, 1, Items.STONE_BRICKS, 1, Items.MOSS_BLOCK, 1);
        way(Items.MOSSY_COBBLESTONE, 1, Items.COBBLESTONE, 1, Items.VINE, 1);
        way(Items.MOSSY_COBBLESTONE, 1, Items.COBBLESTONE, 1, Items.MOSS_BLOCK, 1);
        // Brick: four fired bricks to a block.
        way(Items.BRICKS, 1, Items.BRICK, 4);
        way(Items.BRICK_STAIRS, 4, Items.BRICKS, 6);
        way(Items.BRICK_SLAB, 6, Items.BRICKS, 3);
        // Slate, from the deep stone.
        way(Items.POLISHED_DEEPSLATE, 4, Items.COBBLED_DEEPSLATE, 4);
        way(Items.DEEPSLATE_BRICKS, 4, Items.POLISHED_DEEPSLATE, 4);
        way(Items.DEEPSLATE_TILES, 4, Items.DEEPSLATE_BRICKS, 4);
        way(Items.DEEPSLATE_TILE_STAIRS, 4, Items.DEEPSLATE_TILES, 6);
        way(Items.DEEPSLATE_TILE_SLAB, 6, Items.DEEPSLATE_TILES, 3);
        // Copper: blocks of nine ingots, cut four to four.
        way(Items.COPPER_BLOCK, 1, Items.COPPER_INGOT, 9);
        way(Items.CUT_COPPER, 4, Items.COPPER_BLOCK, 4);
        way(Items.CUT_COPPER_STAIRS, 4, Items.CUT_COPPER, 6);
        way(Items.CUT_COPPER_SLAB, 6, Items.CUT_COPPER, 3);
        // The land's own stone, dressed.
        way(Items.POLISHED_ANDESITE, 4, Items.ANDESITE, 4);
        way(Items.CUT_SANDSTONE, 4, Items.SANDSTONE, 4);
        // A lantern, of the smith's nuggets and a torch (the nuggets are only ever made of spare iron).
        way(Items.LANTERN, 1, Items.IRON_NUGGET, 8, Items.TORCH, 1);
        // The finishing of a storey: panes of the smelter's glass, a furnace of cobblestone, rugs of wool.
        way(Items.GLASS_PANE, 16, Items.GLASS, 6);
        way(Items.FURNACE, 1, Items.COBBLESTONE, 8);
        for (net.minecraft.world.item.DyeColor c : net.minecraft.world.item.DyeColor.values()) {
            Item wool = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_wool"));
            Item rug = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_carpet"));
            if (wool != Items.AIR && rug != Items.AIR) way(rug, 3, wool, 2);
        }
    }

    /** Plain stone: what the ages count as the village's stone, and what is held back for them. */
    static boolean plain(Item it) {
        return it == Items.COBBLESTONE || it == Items.STONE || it == Items.COBBLED_DEEPSLATE;
    }

    // ------------------------------------------------------------------ paying out of the stores

    /** A plan of what comes out of the stores for a thing, worked out before anything is taken. */
    private static final class Plan {
        final ServerLevel level;
        final UUID village;
        /** What the stores hold of each thing looked at, as the plan has left it. */
        final Map<Item, Integer> stock;
        /** What comes out of the stores. */
        final Map<Item, Integer> taken;
        /** What was made and not used: back into the stores. */
        final Map<Item, Integer> spare;
        /** The plain stone the plan may still use (past what the age holds back). */
        int stone;
        /** What the stores held of each thing when first looked at, shared between plans made together. */
        final Map<Item, Integer> seen;

        Plan(ServerLevel level, UUID village) {
            this(level, village, new HashMap<>());
        }

        Plan(ServerLevel level, UUID village, Map<Item, Integer> seen) {
            this.level = level;
            this.village = village;
            this.seen = seen;
            this.stock = new HashMap<>();
            this.taken = new LinkedHashMap<>();
            this.spare = new LinkedHashMap<>();
            int held = Villages.stoneHeldBack(village);
            this.stone = held <= 0 ? Integer.MAX_VALUE
                : Math.max(0, Market.stock(level, village, s -> plain(s.getItem())) - held);
        }

        private Plan(Plan p) {
            this.level = p.level;
            this.village = p.village;
            this.seen = p.seen;
            this.stock = new HashMap<>(p.stock);
            this.taken = new LinkedHashMap<>(p.taken);
            this.spare = new LinkedHashMap<>(p.spare);
            this.stone = p.stone;
        }

        Plan copy() { return new Plan(this); }

        void adopt(Plan p) {
            stock.clear(); stock.putAll(p.stock);
            taken.clear(); taken.putAll(p.taken);
            spare.clear(); spare.putAll(p.spare);
            stone = p.stone;
        }

        /** How many of this the plan can still have out of the stores. */
        int held(Item it) {
            int n = stock.computeIfAbsent(it, k -> seen.computeIfAbsent(k, j -> Market.stock(level, village, s -> s.is(j))));
            return plain(it) ? Math.min(n, stone) : n;
        }
    }

    /** Find {@code n} of a thing for the plan: spare from a batch already cut, the stores', or made. */
    private static boolean need(Plan p, Item want, int n, int depth) {
        int spare = p.spare.getOrDefault(want, 0);
        int use = Math.min(spare, n);
        if (use > 0) {
            p.spare.put(want, spare - use);
            n -= use;
        }
        if (n <= 0) return true;
        int from = Math.min(p.held(want), n);
        if (from > 0) {
            p.stock.merge(want, -from, Integer::sum);
            p.taken.merge(want, from, Integer::sum);
            if (plain(want) && p.stone != Integer.MAX_VALUE) p.stone -= from;
            n -= from;
        }
        if (n <= 0) return true;
        if (depth >= 6) return false;
        for (Way w : WAYS.getOrDefault(want, List.of())) {
            Plan trial = p.copy();
            int batches = (n + w.yield() - 1) / w.yield();
            boolean ok = true;
            for (In in : w.ins()) {
                if (!need(trial, in.item(), in.n() * batches, depth + 1)) { ok = false; break; }
            }
            if (!ok) continue;
            p.adopt(trial);
            int left = batches * w.yield() - n;
            if (left > 0) p.spare.merge(want, left, Integer::sum);
            return true;
        }
        return false;
    }

    /** Could the stores pay so many of this, made from its makings there and then if need be? */
    public static boolean can(ServerLevel level, Villages.Village v, Item want, int n) {
        if (v == null || n <= 0) return n <= 0;
        return need(new Plan(level, v.id()), want, n, 0);
    }

    /** Could the stores pay for a block of this? */
    public static boolean can(ServerLevel level, Villages.Village v, Block b) {
        Item it = b.asItem();
        return it != Items.AIR && can(level, v, it, 1);
    }

    /** Could the stores pay for all of these at once? */
    public static boolean canAll(ServerLevel level, Villages.Village v, Map<Item, Integer> bill) {
        if (v == null) return false;
        Plan p = new Plan(level, v.id());
        for (Map.Entry<Item, Integer> e : bill.entrySet()) if (!need(p, e.getKey(), e.getValue(), 0)) return false;
        return true;
    }

    /**
     * Take so many of this out of the stores, made there and then from its makings if they are short
     * of it (in whole batches, the rest of a batch back into the stores). All or nothing.
     */
    public static boolean take(ServerLevel level, Villages.Village v, Item want, int n) {
        if (n <= 0) return true;
        if (v == null) return false;
        Plan p = new Plan(level, v.id());
        if (!need(p, want, n, 0)) return false;
        return settle(level, v, p);
    }

    /** Take a block of this out of the stores (made if need be). */
    public static boolean take(ServerLevel level, Villages.Village v, Block b) {
        Item it = b.asItem();
        return it != Items.AIR && take(level, v, it, 1);
    }

    /** Take all of these at once, or none of them. */
    public static boolean takeAll(ServerLevel level, Villages.Village v, Map<Item, Integer> bill) {
        if (v == null) return false;
        Plan p = new Plan(level, v.id());
        for (Map.Entry<Item, Integer> e : bill.entrySet()) if (!need(p, e.getKey(), e.getValue(), 0)) return false;
        return settle(level, v, p);
    }

    /** Carry a plan out: what it takes out of the stores, and what was left of its batches back in. */
    private static boolean settle(ServerLevel level, Villages.Village v, Plan p) {
        List<Map.Entry<Item, Integer>> got = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : p.taken.entrySet()) {
            if (e.getValue() <= 0) continue;
            Item it = e.getKey();
            if (!Crafts.take(level, v, s -> s.is(it), e.getValue())) {
                for (Map.Entry<Item, Integer> g : got) Crafts.giveBack(level, v, g.getKey(), g.getValue());
                return false;
            }
            got.add(e);
        }
        for (Map.Entry<Item, Integer> e : p.spare.entrySet()) {
            if (e.getValue() > 0) Crafts.giveBack(level, v, e.getKey(), e.getValue());
        }
        return true;
    }

    /** Could the stores pay for so many blocks of the land's own walling (Homeland): in itself, in what it
     *  is cut from, or made there and then (mossy stone bricks of stone bricks and vines)? */
    public static boolean canLand(ServerLevel level, Villages.Village v, Homeland.Stone local, int n) {
        return Crafts.stock(level, v, local.pay()) >= n * local.each() || can(level, v, local.block().asItem(), n);
    }

    /** Pay for a block of the land's own walling out of the stores; false if they cannot. */
    public static boolean payLand(ServerLevel level, Villages.Village v, Homeland.Stone local) {
        return Crafts.take(level, v, local.pay(), local.each()) || take(level, v, local.block().asItem(), 1);
    }

    // ------------------------------------------------------------------ what the village keeps put by

    /** How many of a dressed block the village likes to keep in its stores, by its age: the mason's list. */
    static int keep(UUID village, Item it) {
        int age = Villages.ageOf(village).ordinal();
        boolean stoneAge = age >= Villages.Age.STONE.ordinal(), ironAge = age >= Villages.Age.IRON.ordinal();
        if (it == Items.STONE_BRICKS) return ironAge ? 192 : stoneAge ? 128 : 32;
        if (it == Items.SMOOTH_STONE) return stoneAge ? 16 : 6;
        if (it == Items.BRICKS) return ironAge ? 96 : stoneAge ? 32 : 8;
        if (it == Items.TORCH) return Math.min(96, 32 + 2 * Villages.headcount(village));
        if (it == Items.GLASS) return 48;
        return 0;
    }

    /** How many more of this the village would like put by. */
    static int shortOf(ServerLevel level, Villages.Village v, Item it) {
        int keep = keep(v.id(), it);
        if (keep <= 0) return 0;
        return Math.max(0, keep - Market.stock(level, v.id(), s -> s.is(it)));
    }

    /** Is the village short of glass (its windows, and the bottles of the brewer, the beekeeper, the café)? Panes
     *  count, at the six blocks of glass that make sixteen. */
    public static boolean glassShort(ServerLevel level, UUID village) {
        int glass = Market.stock(level, village, s -> s.is(Items.GLASS));
        int panes = Market.stock(level, village, s -> s.is(Items.GLASS_PANE));
        return glass + panes * 6 / 16 < keep(village, Items.GLASS);
    }

    /**
     * Plain stone the village can spare for dressing: what the stores hold of cobblestone, stone and
     * cobbled deepslate, less what the age holds back (Villages.stoneHeldBack) and a working stock for
     * the builders' footings, which take any stone.
     */
    static int plainToSpare(ServerLevel level, Villages.Village v) {
        int have = Market.stock(level, v.id(), s -> plain(s.getItem()));
        return Math.max(0, have - Math.max(Villages.stoneHeldBack(v.id()), BUILDERS_STONE));
    }

    /** The plain stone always left for the builders, whatever the smelter would like to dress. */
    public static final int BUILDERS_STONE = 64;

    /** Does the village want stone fired (for bricks of stone, or smooth stone)? */
    static boolean wantsStone(ServerLevel level, Villages.Village v) {
        return shortOf(level, v, Items.STONE_BRICKS) > 0 || shortOf(level, v, Items.SMOOTH_STONE) > 0;
    }

    /** What the smeltery works for the mason: cobblestone and stone to fire and cut, clay to fire. */
    public static final Predicate<ItemStack> MAKINGS = s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE)
        || s.is(Items.CLAY_BALL) || s.is(Items.SAND) || s.is(Items.RED_SAND);

    /** A lot of something for the smeltery, as a courier carries it out of the stores. */
    public record Lot(Predicate<ItemStack> what, int n, String word) {}

    /**
     * What the smeltery could use out of the stores now, for its mason's work and its glass: spare
     * cobblestone (stone is smelted from it), clay (for bricks), sand (for glass). Each only while the
     * village wants what it makes, and never stone the age is holding back.
     */
    public static List<Lot> makings(ServerLevel level, Villages.Village v) {
        List<Lot> out = new ArrayList<>();
        int spare = plainToSpare(level, v);
        if (spare > 0 && wantsStone(level, v)) {
            int stone = Market.stock(level, v.id(), s -> s.is(Items.STONE));
            if (stone > 0) out.add(new Lot(s -> s.is(Items.STONE), Math.min(32, Math.min(stone, spare)), "stone"));
            else out.add(new Lot(s -> s.is(Items.COBBLESTONE), Math.min(32, spare), "cobblestone"));
        }
        if (shortOf(level, v, Items.BRICKS) > 0) {
            int clay = Market.stock(level, v.id(), s -> s.is(Items.CLAY_BALL));
            if (clay > 0) out.add(new Lot(s -> s.is(Items.CLAY_BALL), Math.min(32, clay), "clay"));
        }
        if (glassShort(level, v.id())) {
            int sand = Market.stock(level, v.id(), s -> s.is(Items.SAND) || s.is(Items.RED_SAND));
            if (sand > 0) out.add(new Lot(s -> s.is(Items.SAND) || s.is(Items.RED_SAND), Math.min(16, sand), "sand"));
        }
        return out;
    }

    // ------------------------------------------------------------------ the smelter as mason

    /**
     * The mason's part of the smelter's round, when it has no ore to run (iron comes first: it is the
     * village's tools). What it has fired is cut at its bench: stone into stone bricks, four for four
     * (keeping back what is to be smoothed), and bricks into blocks of brick, four to one. Then the
     * next firing: clay into bricks, stone into smooth stone, cobblestone into stone, as the village is
     * short of each. Returns whether it set to work.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        String cut = dress(f, level, v);
        if (cut != null) {
            f.brain(cut);
            f.note(AssistantEntity.Deed.THINGS_MADE, 1);
        }
        // Fuel: anything it burns; but a village putting coal by for its age fires its stone on wood,
        // or not at all (the coal is the age's, and stone bricks can wait).
        boolean fuel = f.savingCoal()
            ? f.countCarried(s -> s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS)) > 0
            : f.countCarried(AssistantEntity.SMELT_FUEL) > 0;
        if (!fuel) return cut != null;
        int clay = f.countCarried(s -> s.is(Items.CLAY_BALL));
        if (clay > 0 && shortOf(level, v, Items.BRICKS) > 0) {
            f.enqueue(com.jrpetty.mcassistant.entity.Job.smelt("clay", clay));
            f.brain("firing " + clay + " clay into bricks");
            return true;
        }
        int smooth = shortOf(level, v, Items.SMOOTH_STONE);
        int stone = f.countCarried(s -> s.is(Items.STONE));
        if (stone > 0 && smooth > 0) {
            f.enqueue(com.jrpetty.mcassistant.entity.Job.smelt("smooth", Math.min(stone, smooth)));
            f.brain("smoothing " + Math.min(stone, smooth) + " stone");
            return true;
        }
        int cobble = f.countCarried(s -> s.is(Items.COBBLESTONE));
        if (cobble > 0 && wantsStone(level, v)) {
            f.enqueue(com.jrpetty.mcassistant.entity.Job.smelt("cobble", cobble));
            f.brain("firing " + cobble + " cobblestone into stone, for the masons");
            return true;
        }
        return cut != null;
    }

    /** Cut what the smelter has fired, at its bench: what it made, or null. */
    @Nullable
    static String dress(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        int bricksOfStone = 0, blocksOfBrick = 0;
        int wantBricks = shortOf(level, v, Items.STONE_BRICKS);
        // A quarter of the stone is kept back from the bench to be smoothed, while the village is short
        // of smooth stone; the rest is cut. Stone bricks are the more wanted, smooth stone the rarer.
        int keepStone = Math.min(shortOf(level, v, Items.SMOOTH_STONE), f.countCarried(s -> s.is(Items.STONE)) / 4);
        while (bricksOfStone < wantBricks && f.countCarried(s -> s.is(Items.STONE)) - keepStone >= 4) {
            if (f.removeMatching(s -> s.is(Items.STONE), 4) < 4) break;
            ItemStack left = f.insertItem(new ItemStack(Items.STONE_BRICKS, 4));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            bricksOfStone += 4;
        }
        int wantBlocks = shortOf(level, v, Items.BRICKS);
        while (blocksOfBrick < wantBlocks && f.countCarried(s -> s.is(Items.BRICK)) >= 4) {
            if (f.removeMatching(s -> s.is(Items.BRICK), 4) < 4) break;
            ItemStack left = f.insertItem(new ItemStack(Items.BRICKS));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            blocksOfBrick++;
        }
        if (bricksOfStone == 0 && blocksOfBrick == 0) return null;
        StringBuilder said = new StringBuilder("cut ");
        if (bricksOfStone > 0) said.append(bricksOfStone).append(" stone bricks");
        if (bricksOfStone > 0 && blocksOfBrick > 0) said.append(" and ");
        if (blocksOfBrick > 0) said.append(blocksOfBrick).append(blocksOfBrick == 1 ? " block" : " blocks").append(" of brick");
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fresh off the bench: " + said.substring(4) + ".",
                "There's good dressed stone for the builders.", "Square and true. The builders can have these."));
        }
        return said + " at the bench";
    }

    // ------------------------------------------------------------------ light

    /**
     * The light on a lamp post: a lantern if the stores have one (or the nuggets and a torch to make
     * one), else a torch. Paid for here, out of the stores; null when there is neither.
     */
    @Nullable
    public static Block light(ServerLevel level, Villages.Village v) {
        if (take(level, v, Items.LANTERN, 1)) return Blocks.LANTERN;
        if (Crafts.take(level, v, s -> s.is(Items.TORCH), 1)) return Blocks.TORCH;
        return null;
    }

    /** Could the stores light a lamp post (a lantern or a torch)? */
    public static boolean canLight(ServerLevel level, Villages.Village v) {
        return Market.stock(level, v.id(), s -> s.is(Items.TORCH) || s.is(Items.LANTERN)) > 0;
    }

    /** A light that was paid for and not put up, back into the stores as what it was. */
    public static void unlight(ServerLevel level, Villages.Village v, @Nullable Block light) {
        if (light == Blocks.LANTERN) Crafts.store(level, v, new ItemStack(Items.LANTERN));
        else if (light == Blocks.TORCH) Crafts.store(level, v, new ItemStack(Items.TORCH));
    }

    /**
     * A torch where a lantern was drawn and the village has none: standing on what is under it, or
     * fixed to a wall beside it. Null where neither would hold.
     */
    @Nullable
    public static BlockState torchFor(LevelReader level, BlockPos pos) {
        if (Block.canSupportCenter(level, pos.below(), Direction.UP)) return Blocks.TORCH.defaultBlockState();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos wall = pos.relative(d);
            if (level.getBlockState(wall).isFaceSturdy(level, wall, d.getOpposite())) {
                return Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, d.getOpposite());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ a building of what the stores hold

    /** What a cell of a building is, for choosing what it is made of. */
    public enum Role { WALL, FRAME, FLOOR, ROOF_STAIR, ROOF_SLAB, ROOF_BLOCK, MASONRY, BRICK, FOOTING, STONE_SLAB,
        STONE_STAIR, LADDER, SOIL, GLASS, PANE, LANTERN, TORCH, DOOR, FENCE, GATE, CARPET, CHEST, FURNACE, TABLE }

    /** The kind of cell a placement is, or null for one nothing is chosen for (a bed: furnished later). */
    @Nullable
    public static Role role(com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p) {
        return switch (p.part()) {
            case BLOCK -> switch (p.style()) {
                case WALL -> Role.WALL;
                case POST, BEAM_ACROSS, BEAM_ALONG -> Role.FRAME;
                case FLOOR -> Role.FLOOR;
                case ROOF_STAIR, ROOF_STAIR_TOP -> Role.ROOF_STAIR;
                case ROOF_SLAB, ROOF_SLAB_TOP -> Role.ROOF_SLAB;
                case ROOF_BLOCK -> Role.ROOF_BLOCK;
                case MASONRY -> Role.MASONRY;
                case BRICK -> Role.BRICK;
                case STONE_SLAB -> Role.STONE_SLAB;
                case STONE_STAIR -> Role.STONE_STAIR;
                case SOIL -> Role.SOIL;
                case GLASS -> Role.GLASS;
                case PANE -> Role.PANE;
                default -> Role.FOOTING;
            };
            case WINDOW -> p.style() == com.jrpetty.mcassistant.entity.goal.Blueprints.Style.GLASS ? Role.GLASS : Role.PANE;
            case LANTERN -> Role.LANTERN;
            case TORCH -> Role.TORCH;
            case DOOR -> Role.DOOR;
            case FENCE -> Role.FENCE;
            case GATE -> Role.GATE;
            case LADDER -> Role.LADDER;
            case CARPET -> Role.CARPET;
            case CHEST -> Role.CHEST;
            case FURNACE -> Role.FURNACE;
            case CRAFTING_TABLE -> Role.TABLE;
            default -> null;
        };
    }

    /** The cells a building cannot go up without. The rest (glass, lights, doors, rugs) are left out
     *  when the stores cannot pay for them, and put in another day. */
    private static boolean essential(Role r) {
        return switch (r) {
            case WALL, FRAME, FLOOR, ROOF_STAIR, ROOF_SLAB, ROOF_BLOCK, MASONRY, BRICK, FOOTING, STONE_SLAB, STONE_STAIR,
                LADDER -> true;
            default -> false;
        };
    }

    /** Planks a wooden thing costs, near enough: a plank a board, a stair or a rung; two a door or a length of
     *  fence; four a gate or a bench; eight a chest. */
    private static int planksFor(Role r) {
        return switch (r) {
            case DOOR, FENCE -> 2;
            case GATE, TABLE -> 4;
            case CHEST -> 8;
            default -> 1;
        };
    }

    /** Is this block wood (paid in planks, or a log for a post)? */
    private static boolean wooden(Block b) {
        BlockState st = b.defaultBlockState();
        return st.is(net.minecraft.tags.BlockTags.PLANKS) || st.is(net.minecraft.tags.BlockTags.LOGS)
            || st.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS) || st.is(net.minecraft.tags.BlockTags.WOODEN_SLABS)
            || st.is(net.minecraft.tags.BlockTags.WOODEN_DOORS) || st.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)
            || st.is(net.minecraft.tags.BlockTags.FENCE_GATES) || b == Blocks.LADDER || b == Blocks.CHEST
            || b == Blocks.CRAFTING_TABLE;
    }

    /** The wood the stores hold most of ("spruce"), planks and logs together: what the village builds in. */
    static String woodOf(ServerLevel level, Villages.Village v) {
        Map<String, Integer> n = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                boolean log = st.is(net.minecraft.tags.ItemTags.LOGS);
                if (st.isEmpty() || !(log || st.is(net.minecraft.tags.ItemTags.PLANKS))) continue;
                String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().replace("stripped_", "");
                for (String end : new String[]{ "_planks", "_log", "_wood", "_stem", "_hyphae" }) {
                    if (path.endsWith(end)) { path = path.substring(0, path.length() - end.length()); break; }
                }
                n.merge(path, st.getCount() * (log ? 4 : 1), Integer::sum);
            }
        }
        String best = "oak";
        int most = 0;
        for (Map.Entry<String, Integer> e : n.entrySet()) {
            if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** A wooden thing of this wood ("spruce", "_door"), or the oak one where the wood has none. */
    static Block woodBlock(String wood, String suffix) {
        Block b = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(wood + suffix));
        if (b == Blocks.AIR && suffix.equals("_log")) {
            b = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(wood + "_stem"));
        }
        return b == Blocks.AIR ? BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak" + suffix)) : b;
    }

    /** What a kind of cell may be laid in, best first: what the palette wants, then the next best thing. */
    private static List<Block> candidates(Role r, com.jrpetty.mcassistant.Showcase.Palette want, String wood) {
        Block planks = woodBlock(wood, "_planks");
        Block stairs = woodBlock(wood, "_stairs");
        Block slab = woodBlock(wood, "_slab");
        List<Block> out = new ArrayList<>();
        switch (r) {
            // Walls: the palette's, else the masons' stone bricks, else rough stone (the Ages dress it
            // later), else the village's own boards. Posts and beams of logs, else of boards.
            case WALL -> { out.add(wooden(want.walls()) ? planks : want.walls()); out.add(Blocks.STONE_BRICKS);
                out.add(Blocks.COBBLESTONE); out.add(planks); }
            case FRAME -> { out.add(woodBlock(wood, "_log")); out.add(planks); }
            case FLOOR -> out.add(planks);
            case ROOF_STAIR -> { out.add(wooden(want.roofStair()) ? stairs : want.roofStair()); out.add(Blocks.STONE_BRICK_STAIRS); out.add(stairs); }
            case ROOF_SLAB -> { out.add(wooden(want.roofSlab()) ? slab : want.roofSlab()); out.add(Blocks.STONE_BRICK_SLAB); out.add(slab); }
            case ROOF_BLOCK -> { out.add(wooden(want.roofBlock()) ? planks : want.roofBlock()); out.add(Blocks.STONE_BRICKS); out.add(planks); }
            case MASONRY -> { out.add(Blocks.STONE_BRICKS); out.add(Blocks.COBBLESTONE); }
            case BRICK -> { out.add(Blocks.BRICKS); out.add(Blocks.STONE_BRICKS); out.add(Blocks.COBBLESTONE); }
            case FOOTING -> { out.add(Blocks.COBBLESTONE); out.add(Blocks.STONE_BRICKS); }
            case STONE_SLAB -> { out.add(Blocks.STONE_BRICK_SLAB); out.add(Blocks.SMOOTH_STONE_SLAB); out.add(Blocks.COBBLESTONE_SLAB); }
            case STONE_STAIR -> { out.add(Blocks.STONE_BRICK_STAIRS); out.add(Blocks.COBBLESTONE_STAIRS); }
            case LADDER -> out.add(Blocks.LADDER);
            case SOIL -> out.add(Blocks.DIRT);
            case GLASS -> out.add(Blocks.GLASS);
            case PANE -> out.add(Blocks.GLASS_PANE);
            case LANTERN -> { out.add(Blocks.LANTERN); out.add(Blocks.TORCH); }
            case TORCH -> out.add(Blocks.TORCH);
            case DOOR -> out.add(woodBlock(wood, "_door"));
            case FENCE -> out.add(woodBlock(wood, "_fence"));
            case GATE -> out.add(woodBlock(wood, "_fence_gate"));
            case CARPET -> out.add(want.carpet());
            case CHEST -> out.add(Blocks.CHEST);
            case FURNACE -> out.add(Blocks.FURNACE);
            case TABLE -> out.add(Blocks.CRAFTING_TABLE);
        }
        // Nothing precious on a roof, whatever a palette says.
        if (r == Role.ROOF_STAIR || r == Role.ROOF_SLAB || r == Role.ROOF_BLOCK) out.removeIf(b -> !fitForARoof(b));
        return out;
    }

    /**
     * What a building is laid in, kind of cell by kind of cell (null: left out), chosen out of what the
     * stores could pay for all of at once. Kept with the building (Grow) so it goes up in what was paid
     * for, however many visits it takes.
     */
    public static final class Look {
        private final Map<Role, Block> blocks = new java.util.EnumMap<>(Role.class);

        /** What a cell is laid in, or null to leave it out. A lantern the village had none of is a torch,
         *  where one will hold. */
        public java.util.function.Function<com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement, BlockState> painter(LevelReader level) {
            return p -> {
                Role r = role(p);
                Block b = r == null ? null : blocks.get(r);
                if (b == null) return null;
                if (r == Role.LANTERN && b == Blocks.TORCH) return torchFor(level, p.pos());
                return b.defaultBlockState();
            };
        }

        /** What this kind of cell is laid in (null: left out). */
        @Nullable
        public Block of(Role r) { return blocks.get(r); }

        /** Written down, to be kept in the ledger: "WALL=minecraft:bricks;GLASS=-;...". */
        public String encode() {
            StringBuilder sb = new StringBuilder();
            for (Role r : Role.values()) {
                if (!blocks.containsKey(r)) continue;
                Block b = blocks.get(r);
                if (sb.length() > 0) sb.append(';');
                sb.append(r.name()).append('=').append(b == null ? "-" : BuiltInRegistries.BLOCK.getKey(b).toString());
            }
            return sb.toString();
        }

        /** Read back what encode wrote; null if there is nothing there. */
        @Nullable
        public static Look decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            Look look = new Look();
            for (String part : s.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                Role r;
                try { r = Role.valueOf(part.substring(0, eq)); } catch (IllegalArgumentException e) { continue; }
                String id = part.substring(eq + 1);
                Block b = id.equals("-") ? null : BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id));
                look.blocks.put(r, b == Blocks.AIR ? null : b);
            }
            return look;
        }
    }

    /**
     * Choose what these cells are to be laid in, out of what the stores can pay for all of together, and
     * (with {@code pay}) pay for it: the palette's choice for each kind of cell where the stores have it,
     * the next best where they have not (brick walls, else stone bricks, else the village's own boards;
     * a slate roof, else stone bricks, else its own wood; a lantern, else a torch), and the finishing
     * left out where there is nothing to make it of. Stone, brick, slate and glass at what each really
     * costs; timber a plank a board, out of whatever wood the stores hold (the village's own: its oak or
     * its spruce). Null when the stores cannot pay for the walls, the frame, the floor and the roof:
     * nothing is taken then.
     */
    @Nullable
    public static Look buildIn(ServerLevel level, Villages.Village v, com.jrpetty.mcassistant.Showcase.Palette want,
                               List<com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement> cells, boolean pay) {
        Map<Role, Integer> count = new java.util.EnumMap<>(Role.class);
        for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p : cells) {
            Role r = role(p);
            if (r != null) count.merge(r, 1, Integer::sum);
        }
        String wood = woodOf(level, v);
        int planksHeld = Market.stock(level, v.id(), s -> s.is(net.minecraft.tags.ItemTags.PLANKS));
        int logsHeld = Market.stock(level, v.id(), s -> s.is(net.minecraft.tags.ItemTags.LOGS));
        Map<Item, Integer> seen = new HashMap<>();
        Map<Item, Integer> bill = new LinkedHashMap<>();
        int planks = 0, logs = 0;
        Look look = new Look();
        for (Role r : Role.values()) {
            int n = count.getOrDefault(r, 0);
            if (n == 0) continue;
            Block chosen = null;
            for (Block b : candidates(r, want, wood)) {
                int addPlanks = 0, addLogs = 0;
                Map<Item, Integer> trial = new LinkedHashMap<>(bill);
                if (b.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)) addLogs = n;
                else if (wooden(b)) addPlanks = n * planksFor(r);
                else trial.merge(b.asItem(), n, Integer::sum);
                int logsLeft = logsHeld - (logs + addLogs);
                if (logsLeft < 0 || planksHeld + 4 * logsLeft < planks + addPlanks) continue;
                if (!trial.equals(bill)) {
                    Plan p = new Plan(level, v.id(), seen);
                    boolean ok = true;
                    for (Map.Entry<Item, Integer> e : trial.entrySet()) {
                        if (!need(p, e.getKey(), e.getValue(), 0)) { ok = false; break; }
                    }
                    if (!ok) continue;
                }
                bill = trial;
                planks += addPlanks;
                logs += addLogs;
                chosen = b;
                break;
            }
            if (chosen == null && essential(r)) return null;
            look.blocks.put(r, chosen);
        }
        if (!pay) return look;
        if (!takeAll(level, v, bill)) return null;
        if (logs > 0 && !Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), logs)) {
            for (Map.Entry<Item, Integer> e : bill.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
            return null;
        }
        if (!Crafts.usePlanks(level, v, planks)) {
            for (Map.Entry<Item, Integer> e : bill.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
            Crafts.giveBack(level, v, woodBlock(wood, "_log").asItem(), logs);
            return null;
        }
        return look;
    }

    // ------------------------------------------------------------------ roofs

    /**
     * Is this fit to go on a roof? Stone, slate, brick, copper and wood are; iron and the other
     * precious metals never are, whatever the stores hold of them: they are tools and armour.
     */
    public static boolean fitForARoof(Block b) {
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return !(path.contains("iron") || path.contains("gold") || path.contains("diamond") || path.contains("emerald")
            || path.contains("netherite") || path.contains("lapis") || path.contains("amethyst"));
    }

    /** As fitForARoof, for an item. */
    public static boolean fitForARoof(ItemStack s) {
        Block b = Block.byItem(s.getItem());
        return b != Blocks.AIR && fitForARoof(b);
    }

    /** The stone kin of a roof piece, for a roof the stores cannot slate or copper: stone brick, else cobble. */
    @Nullable
    static Block stoneRoof(ServerLevel level, Villages.Village v, Block want) {
        Block[] kin = want instanceof net.minecraft.world.level.block.StairBlock
            ? new Block[]{ Blocks.STONE_BRICK_STAIRS, Blocks.COBBLESTONE_STAIRS }
            : want instanceof net.minecraft.world.level.block.SlabBlock
            ? new Block[]{ Blocks.STONE_BRICK_SLAB, Blocks.COBBLESTONE_SLAB }
            : new Block[]{ Blocks.STONE_BRICKS };
        for (Block b : kin) if (can(level, v, b)) return b;
        return null;
    }
}
