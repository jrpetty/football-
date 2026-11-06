package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * [tidy] Putting an eyesore right, by a hand at the spot (Tidy's crew, or the sweeper): what is taken up goes into the
 * stores, what is laid comes out of them, and every block changed goes through the town's one placement gate
 * ([integrity] TownBlocks) and is never a player's (PlayerBlocks).
 */
final class TidyFixes {

    private TidyFixes() {}

    /** A hole filled this deep at most (a deeper one is a shaft, or a cave's mouth: somebody's to see to by hand). */
    static final int DEEPEST = 8;
    /** Scaffolding struck this far down at most. */
    static final int SCAFFOLD_DEPTH = 32;
    /** The tallest trunk felled as one half-felled tree. */
    static final int TALLEST_TRUNK = 12;

    /** Into the stores; what they have no room for is set down where the crew stands (the sweeper takes it in later),
     *  never lost. */
    static void store(ServerLevel level, Villages.Village v, BlockPos at, ItemStack stack) {
        if (stack.isEmpty()) return;
        TownWork.give(level, v, stack);
        if (!stack.isEmpty()) Block.popResource(level, at.above(), stack);
    }

    /** Every block the crew changes in the town goes through here: never a player's block, and through the town's one
     *  placement gate ([integrity] TownBlocks: tidying touches open ground, the roads and the square, never a building
     *  or a door's way in). */
    static boolean mayChange(ServerLevel level, Villages.Village v, BlockPos pos) {
        return mayChange(level, v, pos, TownBlocks.Why.TIDY);
    }

    static boolean mayChange(ServerLevel level, Villages.Village v, BlockPos pos, TownBlocks.Why why) {
        return !PlayerBlocks.placed(level, pos) && TownBlocks.mayPlace(level, v, pos, why);
    }

    /** Taken up: what it gives into the stores, and the block gone. */
    static boolean takeUp(ServerLevel level, Villages.Village v, BlockPos p) {
        if (!mayChange(level, v, p)) return false;
        BlockState st = level.getBlockState(p);
        if (st.isAir()) return true;
        for (ItemStack drop : Block.getDrops(st, level, p, level.getBlockEntity(p))) store(level, v, p, drop);
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        return true;
    }

    /** Put one eyesore right. False if it could not be (nothing in the stores to do it with, or not the crew's to touch). */
    static boolean fix(ServerLevel level, Villages.Village v, Eyesores.Spot s) {
        BlockPos p = s.pos();
        if (!mayChange(level, v, p)) return false;
        BlockState st = level.getBlockState(p);
        switch (s.kind()) {
            case SPILL -> {
                BlockState fill = ground(level, v, p, true);
                if (fill == null) return false;
                level.setBlockAndUpdate(p, fill);
                return true;
            }
            case HOLE, ROAD -> {
                int want = s.kind() == Eyesores.Kind.ROAD ? minAround(level, p) : medianAround(level, p);
                if (want - p.getY() > DEEPEST) return false;
                boolean any = false;
                for (int y = p.getY() + 1; y <= want; y++) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    BlockState at = level.getBlockState(q);
                    if (!at.canBeReplaced() || !mayChange(level, v, q)) break;
                    BlockState fill = ground(level, v, q, y == want);
                    if (fill == null) return any;
                    if (!at.isAir()) level.setBlock(q, Blocks.AIR.defaultBlockState(), 2 | 16);
                    level.setBlockAndUpdate(q, fill);
                    any = true;
                }
                return any;
            }
            case PILLAR -> {
                int floor = maxAround(level, p);
                boolean any = false;
                for (int y = p.getY(); y > floor; y--) any |= takeUp(level, v, new BlockPos(p.getX(), y, p.getZ()));
                BlockPos top = new BlockPos(p.getX(), floor, p.getZ());
                if (level.getBlockState(top).is(Blocks.DIRT) && level.getBlockState(top.above()).isAir()) level.setBlockAndUpdate(top, Blocks.GRASS_BLOCK.defaultBlockState());
                return any;
            }
            case STRAY, LEAVES -> {
                return takeUp(level, v, p);
            }
            case TORCH -> {
                return torch(level, v, p);
            }
            case SCAFFOLD -> {
                boolean any = false;
                BlockPos q = p;
                while (level.getBlockState(q).is(Blocks.SCAFFOLDING) && q.getY() > p.getY() - 32) {
                    any |= takeUp(level, v, q);
                    q = q.below();
                }
                return any;
            }
            case CAMP_BED -> {
                return campBed(level, v, p);
            }
            case CHEST -> {
                if (!(level.getBlockEntity(p) instanceof Container box)) return false;
                for (int i = 0; i < box.getContainerSize(); i++) {
                    ItemStack in = box.getItem(i);
                    if (in.isEmpty()) continue;
                    ItemStack go = in.copy();
                    TownWork.give(level, v, go);
                    box.setItem(i, go);                       // what found no room stays in it
                    if (!go.isEmpty()) return false;
                }
                Retiring.unmark(level, p);
                return takeUp(level, v, p);
            }
            case HALF_TREE -> {
                int base = p.getY();
                while (base > p.getY() - TALLEST_TRUNK && level.getBlockState(new BlockPos(p.getX(), base - 1, p.getZ())).is(BlockTags.LOGS)) base--;
                Block log = st.getBlock();
                for (int y = p.getY(); y >= base; y--) takeUp(level, v, new BlockPos(p.getX(), y, p.getZ()));
                BlockPos ground = new BlockPos(p.getX(), base - 1, p.getZ());
                if (level.getBlockState(ground).is(Blocks.DIRT)) level.setBlockAndUpdate(ground, Blocks.GRASS_BLOCK.defaultBlockState());
                BlockPos c = v.centre();
                int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
                if (!TownPlan.isStreet(dx, dz) && !TownPlan.isSquare(dx, dz)) replant(level, v, ground.above(), log);
                return true;
            }
            case SCAR -> {
                if (!st.is(Blocks.DIRT) && !st.is(Blocks.COARSE_DIRT) && !st.is(Blocks.ROOTED_DIRT)) {
                    if (!TownWork.take(level, v, it -> it.is(Items.DIRT), 1)) return false;
                    for (ItemStack drop : Block.getDrops(st, level, p, null)) store(level, v, p, drop);
                }
                BlockState up = level.getBlockState(p.above());
                if (!up.isAir()) {
                    if (!up.canBeReplaced()) return false;
                    level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlockAndUpdate(p, Blocks.GRASS_BLOCK.defaultBlockState());
                return true;
            }
        }
        return false;
    }

    /** Another light this near a dropped torch lights its ground well enough without it. */
    static final int LIT_WITHOUT = 5;

    /**
     * A torch dropped on the ground: in a street or on the square, taken up (the street's own lamps light it); on open
     * ground, set on a post of the stores' fence so the light stays where somebody wanted it, but looks meant; taken up
     * only if another light is near, else left (somebody wanted the dark lit).
     */
    private static boolean torch(ServerLevel level, Villages.Village v, BlockPos p) {
        BlockPos c = v.centre();
        int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
        if (TownPlan.isStreet(dx, dz) || TownPlan.isSquare(dx, dz)) return takeUp(level, v, p);
        if (level.getBlockState(p.above()).isAir() && mayChange(level, v, p, TownBlocks.Why.LAMP)
                && mayChange(level, v, p.above(), TownBlocks.Why.LAMP) && Crafts.fence(level, v)) {
            level.setBlockAndUpdate(p, Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(p.above(), Blocks.TORCH.defaultBlockState());
            return true;
        }
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-LIT_WITHOUT, -2, -LIT_WITHOUT), p.offset(LIT_WITHOUT, 3, LIT_WITHOUT))) {
            if (q.equals(p)) continue;
            BlockState st = level.getBlockState(q);
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.LANTERN) || st.is(Blocks.GLOWSTONE)) return takeUp(level, v, p);
        }
        return false;
    }

    /** A camp bed taken up into the stores: never one somebody is in; one somebody calls its own only if there is a
     *  bed in a house for it (it is told to find that one tonight). */
    private static boolean campBed(ServerLevel level, Villages.Village v, BlockPos head) {
        BlockState st = level.getBlockState(head);
        if (!(st.getBlock() instanceof BedBlock) || st.getValue(BedBlock.OCCUPIED)) return false;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!head.equals(a.bedPos())) continue;
            if (a.isSleeping()) return false;
            int spare = Villages.bedsMadeUp(level, v.id()) - claimedInHouses(level, v);
            if (spare <= 0) return false;
            a.forgetBed();
            if (a instanceof VillageFolkEntity f) f.brain("the camp is being taken up: a bed in a house tonight");
        }
        BlockPos other = st.getValue(BedBlock.PART) == BedPart.HEAD
            ? head.relative(st.getValue(BedBlock.FACING).getOpposite()) : head.relative(st.getValue(BedBlock.FACING));
        if (!mayChange(level, v, other)) return false;
        level.setBlock(other, Blocks.AIR.defaultBlockState(), 2 | 16);
        level.setBlock(head, Blocks.AIR.defaultBlockState(), 3);
        store(level, v, head, new ItemStack(st.getBlock().asItem()));
        return true;
    }

    private static int claimedInHouses(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            BlockPos b = a.bedPos();
            if (b != null && Math.max(Math.abs(b.getX() - v.centre().getX()), Math.abs(b.getZ() - v.centre().getZ()))
                    > com.jrpetty.mcassistant.VillageSpawner.CAMP_REACH) n++;
        }
        return n;
    }

    /** A sapling of the felled tree's own kind, out of the stores (any sapling failing that), where it stood. */
    private static void replant(ServerLevel level, Villages.Village v, BlockPos at, Block log) {
        if (!level.getBlockState(at).isAir() || !mayChange(level, v, at, TownBlocks.Why.NATURE)) return;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(log);
        Item own = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(key.getNamespace(),
            key.getPath().replace("stripped_", "").replace("_log", "_sapling").replace("_wood", "_sapling")));
        ItemStack got = own != Items.AIR ? Crafts.takeOne(level, v, s -> s.is(own)) : ItemStack.EMPTY;
        if (got.isEmpty()) got = Crafts.takeOne(level, v, s -> s.is(ItemTags.SAPLINGS));
        if (got.isEmpty() || !(got.getItem() instanceof BlockItem bi)) return;
        BlockState sapling = bi.getBlock().defaultBlockState();
        if (!sapling.canSurvive(level, at)) {
            store(level, v, at, got);
            return;
        }
        level.setBlockAndUpdate(at, sapling);
    }

    /**
     * Ground to lay here, out of the stores: on top ({@code top}) what the ground round it is (turf, a path, the
     * street's stone, sand), below it earth or cobble. Null if the stores have nothing for it.
     */
    @Nullable
    static BlockState ground(ServerLevel level, Villages.Village v, BlockPos p, boolean top) {
        if (top) {
            Map<Block, Integer> round = new HashMap<>();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int y = Eyesores.top(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ());
                Block b = level.getBlockState(new BlockPos(p.getX() + d.getStepX(), y, p.getZ() + d.getStepZ())).getBlock();
                round.merge(b, 1, Integer::sum);
            }
            Block most = null;
            int best = 0;
            for (Map.Entry<Block, Integer> e : round.entrySet()) {
                if (e.getValue() > best && e.getKey() != Blocks.WATER && e.getKey() != Blocks.LAVA) {
                    best = e.getValue();
                    most = e.getKey();
                }
            }
            if (most == Blocks.GRASS_BLOCK || most == Blocks.DIRT_PATH || most == Blocks.FARMLAND) {
                if (TownWork.take(level, v, s -> s.is(Items.DIRT), 1)) return most == Blocks.DIRT_PATH ? Blocks.DIRT_PATH.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState();
            } else if (most != null && most.asItem() != Items.AIR && Eyesores.rough(most.defaultBlockState())
                    || most == Blocks.STONE_BRICKS || most == Blocks.SANDSTONE) {
                Item item = most.asItem();
                if (TownWork.take(level, v, s -> s.is(item), 1)) return most.defaultBlockState();
            }
        }
        if (TownWork.take(level, v, s -> s.is(Items.DIRT), 1)) return top ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return Blocks.COBBLESTONE.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.COBBLED_DEEPSLATE), 1)) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        return null;
    }

    private static int[] around(ServerLevel level, BlockPos p) {
        int[] h = new int[8];
        int i = 0;
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) if (ox != 0 || oz != 0) h[i++] = Eyesores.top(level, p.getX() + ox, p.getZ() + oz);
        java.util.Arrays.sort(h);
        return h;
    }

    private static int medianAround(ServerLevel level, BlockPos p) {
        return around(level, p)[4];
    }

    private static int maxAround(ServerLevel level, BlockPos p) {
        int m = Integer.MIN_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) m = Math.max(m, Eyesores.top(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ()));
        return m;
    }

    private static int minAround(ServerLevel level, BlockPos p) {
        int m = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) m = Math.min(m, Eyesores.top(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ()));
        return m;
    }
}
