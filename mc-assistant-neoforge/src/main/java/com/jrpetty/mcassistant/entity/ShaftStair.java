package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [stairs44] The main shaft's stair (MineWorks) kept fit to walk, up as well as down, by a player as by a folk.
 *
 * <p>Each side of the shaft drops three steps and turns on a flat landing at the corner. The steps' floors were laid as
 * stairs, but the corner landing was left a full block over the first step of the next side: going up, a body met a whole
 * block's rise at every corner and had to jump it. The stair also lay open to whatever fell or grew into it: a mine sunk in
 * sand had its walls and ceiling of sand, and the sand dropped onto the top steps and filled them. Now:
 * <ul>
 * <li><b>The corner risers</b>: the two cells of each landing the next step climbs onto have their floors laid as stairs too,
 *     facing up the stair; the rest of the landing stays flat. Every rise on the way is half a block.</li>
 * <li><b>Headroom</b>: four clear blocks over every step and landing (an older shaft's fourth row cut, its timber sets raised),
 *     so a sprint-jump up the stair does not meet the ceiling.</li>
 * <li><b>Shored</b>: whatever in the stair's walls, ceiling or floor could fall (sand, gravel, concrete powder, pointed
 *     dripstone) is made firm where it lies (sand into sandstone, the rest into cobblestone) as the stair is cut and on
 *     every round after; water and lava there sealed; a cave the stair breaks into walled off; where the stair's edge stands
 *     over a drop in the open, a fence.</li>
 * <li><b>Kept clear</b>: what fell, drifted or grew into a step (sand, gravel, earth, snow, plants, leaves, a stray block) is
 *     taken out; the works' own things (torches, lanterns, timber, rails and fences, the cage's levers) are left.</li>
 * <li><b>Lit and signed</b>: a lantern hung at every corner landing, a torch at every side's middle step; a sign at each
 *     level's landing (its name and depth) and one at the pithead.</li>
 * <li><b>Dressed</b>: where the town's stores can spare them, the steps and landings are laid in stone bricks (deepslate
 *     bricks below Y0), a depth band at a time; else in stairs of the rock they are cut in. What a stair needs to be walked
 *     (a step's floor) is laid whether or not there is stone to pay for it.</li>
 * <li><b>The way in</b>: level with the first landing, stepped up or down to the ground beside it where the ground lies
 *     higher or lower.</li>
 * </ul>
 * Done for each step as it is cut (MineWorks.dress) and at the works' half-minute round (MineWorks.tick) a few steps at a
 * time, the open top every round, so a shaft sunk before this is put right as its works are next looked at.
 */
public final class ShaftStair {

    private ShaftStair() {}

    /** Clear blocks over the feet of every step and landing. */
    public static final int HEAD = 4;
    /** Steps from the top looked over every round (the open top, where things fall in), and others besides a round. */
    static final int TOP_STEPS = 8, ROUND = 12;
    /** Bricks the stores keep back before any go on the stair, and bricks laid a round at most. */
    static final int RESERVE = 64, BRICKS_A_ROUND = 8;
    private static final String SIGNS = "mine.signs";

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();

    /** What has been done, all towns (tests and the log read them). */
    static int risers, stairs, shored, sealed, fenced, cleared, cut, lit, signs, bricked;

    // ------------------------------------------------------------------ the shape of the stair

    static int side(int step) { return (step / 4) % 4; }

    /** The way up the stair at this step: back the way the side is walked down. */
    static Direction up(int step) { return MineWorks.walk(side(step)).getOpposite(); }

    /** The cells of a corner landing the next step climbs onto (its floors are stairs: the riser up to the landing). */
    static List<BlockPos> risers(MineWorks.Works w, int corner) {
        List<BlockPos> out = new ArrayList<>(2);
        if (corner % 4 != 0) return out;
        List<BlockPos> next = MineWorks.stepCells(w, corner + 1);
        for (BlockPos c : MineWorks.stepCells(w, corner)) {
            for (BlockPos n : next) {
                if (Math.abs(c.getX() - n.getX()) + Math.abs(c.getZ() - n.getZ()) == 1) { out.add(c); break; }
            }
        }
        return out;
    }

    /** Is this column one of a corner's risers? */
    public static boolean isRiser(MineWorks.Works w, int corner, BlockPos p) {
        for (BlockPos c : risers(w, corner)) if (c.getX() == p.getX() && c.getZ() == p.getZ()) return true;
        return false;
    }

    /** Is the floor of this cell of step i a stair (the steps, and a corner's risers), or flat (the rest of a landing)? */
    static boolean stepped(MineWorks.Works w, int i, BlockPos c) {
        return i % 4 != 0 || isRiser(w, i, c);
    }

    // ------------------------------------------------------------------ what is what

    /** Could this fall (sand, gravel, concrete powder, pointed dripstone), not counting an anvil or the dragon's egg? */
    public static boolean falls(BlockState s) {
        Block b = s.getBlock();
        return b instanceof Fallable && !(b instanceof AnvilBlock) && !(b instanceof DragonEggBlock);
    }

    /** The works' own things in and round the stair: never cleared. */
    static boolean ours(BlockState s) {
        Block b = s.getBlock();
        return b instanceof BaseTorchBlock || b instanceof LanternBlock || b instanceof LeverBlock || b instanceof ButtonBlock
            || b instanceof ChainBlock || b instanceof IronBarsBlock || b instanceof FenceBlock || b instanceof WallBlock
            || b instanceof FenceGateBlock || b instanceof LadderBlock || b instanceof BaseRailBlock || b instanceof SignBlock
            || b instanceof TrapDoorBlock;
    }

    /** Spoil or litter in the way (what the ground is made of, what fell or grew there): taken out without a drop. */
    static boolean natural(ServerLevel level, BlockPos p, BlockState s) {
        if (falls(s) || s.canBeReplaced()) return true;
        if (s.is(BlockTags.SNOW) || s.is(BlockTags.DIRT) || s.is(BlockTags.LEAVES) || s.is(BlockTags.ICE) || s.is(Blocks.COBWEB)) return true;
        if (s.is(BlockTags.LOGS)) return com.jrpetty.mcassistant.entity.goal.BuildGoal.isTreeLog(level, p);
        return MineStairs.ground(s) || com.jrpetty.mcassistant.entity.goal.MineGoal.wild(level, p, s);
    }

    static boolean open(BlockState s) {
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    static boolean sturdy(ServerLevel level, BlockPos p, BlockState s) {
        return s.isFaceSturdy(level, p, Direction.UP);
    }

    /** What a falling block is made firm into: sandstone for sand, dripstone for dripstone, cobblestone for the rest. */
    static BlockState firm(BlockState s, int y) {
        if (s.is(Blocks.RED_SAND)) return Blocks.RED_SANDSTONE.defaultBlockState();
        if (s.is(BlockTags.SAND) || s.is(Blocks.SUSPICIOUS_SAND)) return Blocks.SANDSTONE.defaultBlockState();
        if (s.is(Blocks.POINTED_DRIPSTONE)) return Blocks.DRIPSTONE_BLOCK.defaultBlockState();
        return rubble(y).defaultBlockState();
    }

    static Block rubble(int y) { return y < 0 ? Blocks.COBBLED_DEEPSLATE : Blocks.COBBLESTONE; }

    static final Predicate<ItemStack> RUBBLE = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
    static final Predicate<ItemStack> TORCH = s -> s.is(Items.TORCH);
    static final Predicate<ItemStack> LANTERN = s -> s.is(Items.LANTERN);

    /** The stair of the rock a step is cut in (sandstone in sand, deepslate under the deepslate line). */
    static Block rockStair(BlockState rock, int y) {
        if (rock.is(Blocks.RED_SAND) || rock.is(Blocks.RED_SANDSTONE)) return Blocks.RED_SANDSTONE_STAIRS;
        if (rock.is(BlockTags.SAND) || rock.is(Blocks.SANDSTONE)) return Blocks.SANDSTONE_STAIRS;
        if (rock.is(Blocks.DEEPSLATE) || rock.is(Blocks.COBBLED_DEEPSLATE) || rock.is(Blocks.TUFF) || rock.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) return Blocks.COBBLED_DEEPSLATE_STAIRS;
        if (rock.is(Blocks.STONE) || rock.is(BlockTags.STONE_ORE_REPLACEABLES)) return Blocks.STONE_STAIRS;
        return y < 0 ? Blocks.COBBLED_DEEPSLATE_STAIRS : Blocks.COBBLESTONE_STAIRS;
    }

    /** The band's dressed stone: stone bricks over Y0, deepslate bricks under it. */
    static Block brickStair(int y) { return y < 0 ? Blocks.DEEPSLATE_BRICK_STAIRS : Blocks.STONE_BRICK_STAIRS; }
    static Block brick(int y) { return y < 0 ? Blocks.DEEPSLATE_BRICKS : Blocks.STONE_BRICKS; }
    static Predicate<ItemStack> brickItem(int y) {
        return y < 0 ? s -> s.is(Items.DEEPSLATE_BRICKS) || s.is(Items.POLISHED_DEEPSLATE) || s.is(Items.DEEPSLATE_BRICK_STAIRS)
            : s -> s.is(Items.STONE_BRICKS) || s.is(Items.STONE_BRICK_STAIRS);
    }

    // ------------------------------------------------------------------ a round's work

    static final class Ctx {
        final ServerLevel level;
        final MineWorks.Works w;
        @Nullable final AssistantEntity a;
        @Nullable final Villages.Village v;
        final List<MineWorks.Lvl> plan;
        int spoil, bricks = BRICKS_A_ROUND;
        final Map<Boolean, Boolean> affords = new HashMap<>(2);
        /** What the stores were found to have none of this round (not looked for again). */
        final Set<Predicate<ItemStack>> none = new HashSet<>();

        Ctx(ServerLevel level, MineWorks.Works w, @Nullable AssistantEntity a) {
            this.level = level;
            this.w = w;
            this.a = a;
            this.v = Villages.get(w.village);
            this.plan = MineWorks.plan(level, w);
        }

        /** One of what matches out of the miner's pack or the town's stores; false when neither has one. */
        boolean pay(Predicate<ItemStack> what) {
            if (a != null && a.removeMatching(what, 1) == 1) return true;
            if (v == null || none.contains(what)) return false;
            if (!Crafts.takeOne(level, v, what).isEmpty()) return true;
            none.add(what);
            return false;
        }

        /** May a brick of this band go on the stair now (the stores have plenty over what they keep back), and one taken. */
        boolean brick(int y) {
            if (bricks <= 0 || v == null) return false;
            boolean deep = y < 0;
            Boolean ok = affords.get(deep);
            if (ok == null) {
                ok = Crafts.stock(level, v, brickItem(y)) > RESERVE;
                affords.put(deep, ok);
            }
            if (!ok || Crafts.takeOne(level, v, brickItem(y)).isEmpty()) return false;
            bricks--;
            return true;
        }

        boolean mayPlace(BlockPos p) {
            return v == null || TownBlocks.mayPlace(level, v, p, TownBlocks.Why.MINE);
        }
    }

    /**
     * Every half minute (MineWorks.tick): the open top's steps and a dozen more of the stair looked over, each round on from
     * the last; the way in and the pithead's sign. The spoil taken out of the stair (for the heap), in blocks.
     */
    static int upkeep(ServerLevel level, MineWorks.Works w) {
        if (w.sunk <= 0) return 0;
        Ctx cx = new Ctx(level, w, null);
        int top = Math.min(w.sunk, TOP_STEPS);
        for (int i = 0; i < top; i++) keep(cx, i);
        int from = CURSOR.getOrDefault(w.village, top);
        if (from < top || from >= w.sunk) from = top;
        int to = Math.min(w.sunk, from + ROUND);
        for (int i = from; i < to; i++) keep(cx, i);
        CURSOR.put(w.village, to >= w.sunk ? top : to);
        wayIn(cx);
        pitheadSign(cx);
        return cx.spoil;
    }

    /** A step just cut, by this miner (MineWorks.dress): kept at once, out of its pack first. */
    static void cut(ServerLevel level, MineWorks.Works w, @Nullable AssistantEntity a, int step) {
        Ctx cx = new Ctx(level, w, a);
        keep(cx, step);
        if (step % 4 == 1) keep(cx, step - 1);              // the landing over it: its risers
        w.dug += cx.spoil;
    }

    /** One step of the stair put right: its walls, what is in its way, its ceiling, its floor, its lights and its sign. */
    static void keep(Ctx cx, int i) {
        ServerLevel level = cx.level;
        MineWorks.Works w = cx.w;
        List<BlockPos> cells = MineWorks.stepCells(w, i);
        for (BlockPos c : cells) if (!level.isLoaded(c)) return;
        // The steps round it (their own floors and space are theirs to keep), and a level's landing room beside a corner.
        Map<Long, Integer> near = new HashMap<>();
        for (int j = Math.max(0, i - 1); j <= i + 1; j++) for (BlockPos c : MineWorks.stepCells(w, j)) near.put(col(c), c.getY());
        Set<Long> room = new HashSet<>();
        int roomY = Integer.MIN_VALUE;
        if (i % 4 == 0) {
            for (MineWorks.Lvl l : cx.plan) {
                if (l.corner() != i) continue;
                for (BlockPos c : MineWorks.roomCells(w, l)) room.add(col(c));
                roomY = l.y();
            }
            if (i == 0) {
                room.add(BlockPos.asLong(w.sx - 4, 0, w.sz - 2));
                room.add(BlockPos.asLong(w.sx - 4, 0, w.sz - 3));
                roomY = w.top;
            }
        }
        // The walls: what could fall made firm, water and lava sealed, a cave walled off, a drop in the open fenced.
        for (BlockPos c : cells) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                for (int h = 0; h < HEAD; h++) {
                    BlockPos p = n.above(h);
                    Integer f = near.get(col(p));
                    if (f != null && p.getY() >= f - 1 && p.getY() <= f + HEAD - 1) continue;
                    if (room.contains(col(p)) && p.getY() >= roomY - 1 && p.getY() <= roomY + 2) continue;
                    wall(cx, p, h);
                }
            }
        }
        boolean reframe = false;
        for (BlockPos c : cells) {
            // What is in the way, up to the ceiling.
            for (int h = 0; h < HEAD; h++) {
                BlockPos p = c.above(h);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || ours(s)) continue;
                if (!s.getFluidState().isEmpty() && s.canBeReplaced()) { level.setBlock(p, Blocks.AIR.defaultBlockState(), 3); cleared++; continue; }
                if (s.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock && level.getBlockEntity(p) instanceof net.minecraft.world.Container box && box.isEmpty()) {
                    level.destroyBlock(p, true);                              // a furnace set down on the stair and left empty: taken up
                    cleared++;
                    continue;
                }
                if (s.hasBlockEntity() || s.getDestroySpeed(level, p) < 0) continue;
                if (s.is(BlockTags.LOGS) && !com.jrpetty.mcassistant.entity.goal.BuildGoal.isTreeLog(level, p)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);   // an older timber set's cap, a block too low
                    reframe = true;
                    continue;
                }
                if (OreSight.isOre(s) || !natural(level, p, s)) {
                    level.destroyBlock(p, true);                              // a stray block or an ore: what it drops left by it
                } else {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    cx.spoil++;
                }
                if (h == HEAD - 1 && !falls(s) && !s.canBeReplaced()) cut++; else cleared++;
            }
            // The ceiling.
            BlockPos roof = c.above(HEAD);
            BlockState rs = level.getBlockState(roof);
            if (falls(rs)) shore(cx, roof, rs);
            else if (!rs.getFluidState().isEmpty() && !ours(rs)) seal(cx, roof);
            else if (open(rs) && cave(cx, roof)) seal(cx, roof);
        }
        // The floor: a stair facing up the stair on a step and a corner's risers, flat and firm on the rest of a landing.
        Direction up = up(i);
        for (BlockPos c : cells) floor(cx, i, c, up);
        // A miner's lights and a timber set: a lantern at the landing, a torch and a set at a side's middle step.
        if (i % 4 == 0) landingLight(cx, i, cells);
        if (i % 4 == 2) {
            if (reframe || !level.getBlockState(cells.get(0).above(HEAD)).is(BlockTags.LOGS)) {
                MineWorks.frame(level, w, cells.get(0), MineWorks.walk(side(i)), HEAD);
            }
            sideLight(cx, cells);
        }
        if (i % 4 == 0 && i > 0) levelSign(cx, i);
    }

    private static long col(BlockPos p) { return BlockPos.asLong(p.getX(), 0, p.getZ()); }

    /** A block of the stair's wall. */
    private static void wall(Ctx cx, BlockPos p, int h) {
        ServerLevel level = cx.level;
        if (!level.isLoaded(p)) return;
        MineWorks.Works w = cx.w;
        int ring = Math.max(Math.abs(p.getX() - w.sx), Math.abs(p.getZ() - w.sz));
        if (ring <= 1 && MineLift.windowAt(w, p)) return;                    // the cage's window at a stop: its own
        BlockState s = level.getBlockState(p);
        if (falls(s)) { shore(cx, p, s); return; }
        if (!s.getFluidState().isEmpty()) {
            if (!ours(s) && !s.hasBlockEntity()) seal(cx, p);
            return;
        }
        if (!open(s)) return;
        if (cave(cx, p)) { seal(cx, p); return; }
        // In the open (the shaft's top): over a drop of a block or more, a fence along the edge.
        if (h == 0 && p.getY() >= w.top - 1) {
            BlockPos b = p.below();
            BlockState bs = level.getBlockState(b);
            if (open(bs) || !bs.getFluidState().isEmpty()) fence(cx, p);
        }
    }

    /** Open rock under the ground (a cave, a hole), under the pithead's height: walled off. */
    private static boolean cave(Ctx cx, BlockPos p) {
        if (p.getY() >= cx.w.top - 1) return false;
        return p.getY() < cx.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) - 1;
    }

    private static boolean nobody(ServerLevel level, BlockPos p) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(p)).isEmpty();
    }

    /** What could fall made firm where it lies (no cost: the same ground, packed). */
    private static void shore(Ctx cx, BlockPos p, BlockState s) {
        if (s.hasBlockEntity() && !(s.getBlock() instanceof Fallable)) return;
        cx.level.setBlock(p, firm(s, p.getY()), 2);
        shored++;
    }

    /** Public for the cutting (WorksGoal): a falling block over a cell just cut made firm before it drops. */
    public static boolean shoreIfFalls(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!falls(s)) return false;
        level.setBlock(p, firm(s, p.getY()), 2);
        shored++;
        return true;
    }

    /** Water, lava or a cave's mouth stopped with a block of rubble (the pack's or the stores', else the works' own). */
    private static void seal(Ctx cx, BlockPos p) {
        if (!nobody(cx.level, p) || !cx.mayPlace(p)) return;
        cx.pay(RUBBLE);
        cx.level.setBlock(p, rubble(p.getY()).defaultBlockState(), 3);
        sealed++;
    }

    private static void fence(Ctx cx, BlockPos p) {
        if (!nobody(cx.level, p) || !cx.mayPlace(p)) return;
        String wood = MineWorks.wood(cx.level, cx.w);
        Block fence = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_fence"));
        if (fence == Blocks.AIR) fence = Blocks.SPRUCE_FENCE;
        cx.level.setBlock(p, Block.updateFromNeighbourShapes(fence.defaultBlockState(), cx.level, p), 3);
        fenced++;
    }

    /** A cell's floor. */
    private static void floor(Ctx cx, int i, BlockPos c, Direction up) {
        ServerLevel level = cx.level;
        BlockPos fl = c.below();
        BlockState fs = level.getBlockState(fl);
        int y = fl.getY();
        if (stepped(cx.w, i, c)) {
            boolean right = fs.getBlock() instanceof StairBlock && fs.getValue(StairBlock.FACING) == up && fs.getValue(StairBlock.HALF) == Half.BOTTOM;
            if (right) {
                if (fs.getBlock() != brickStair(y) && cx.brick(y)) {
                    set(level, fl, stairState(brickStair(y), up, level, fl));
                    bricked++;
                }
            } else if (!fs.hasBlockEntity() && fs.getDestroySpeed(level, fl) >= 0) {
                // A stop's lever or button on a riser (an older stop's place): taken off first, or it would drop.
                BlockState at = level.getBlockState(c);
                if (at.getBlock() instanceof LeverBlock || at.getBlock() instanceof ButtonBlock) level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
                Block stair;
                if (open(fs) || !fs.getFluidState().isEmpty()) {
                    stair = cx.brick(y) ? brickStair(y) : rockStair(rubble(y).defaultBlockState(), y);
                    if (stair != brickStair(y)) cx.pay(RUBBLE);              // never left a hole for want of a stone
                } else {
                    stair = cx.brick(y) ? brickStair(y) : fs.getBlock() instanceof StairBlock ? fs.getBlock() : rockStair(fs, y);
                }
                set(level, fl, stairState(stair, up, level, fl));
                if (i % 4 == 0) risers++; else stairs++;
            }
        } else {
            boolean firm = !open(fs) && fs.getFluidState().isEmpty() && !falls(fs) && sturdy(level, fl, fs);
            if (firm) {
                boolean rock = MineStairs.ground(fs) && !OreSight.isOre(fs);
                if (rock && fs.getBlock() != brick(y) && cx.brick(y)) {
                    set(level, fl, brick(y).defaultBlockState());
                    bricked++;
                }
            } else if (!fs.hasBlockEntity() && fs.getDestroySpeed(level, fl) >= 0 && !(fs.getBlock() instanceof StairBlock)) {
                BlockState st;
                if (cx.brick(y)) st = brick(y).defaultBlockState();
                else if (falls(fs)) st = firm(fs, y);
                else { cx.pay(RUBBLE); st = rubble(y).defaultBlockState(); }
                set(level, fl, st);
                shored++;
            }
        }
        // Under the floor: nothing that could drop out from under it.
        BlockPos under = fl.below();
        BlockState us = level.getBlockState(under);
        if (falls(us)) shore(cx, under, us);
    }

    private static BlockState stairState(Block stair, Direction up, ServerLevel level, BlockPos p) {
        BlockState st = stair.defaultBlockState().setValue(StairBlock.FACING, up).setValue(StairBlock.HALF, Half.BOTTOM);
        return Block.updateFromNeighbourShapes(st, level, p);
    }

    private static void set(ServerLevel level, BlockPos p, BlockState st) {
        level.setBlock(p, st, 3);
    }

    // ------------------------------------------------------------------ lights

    private static boolean lightIn(ServerLevel level, List<BlockPos> cells, Predicate<BlockState> what) {
        for (BlockPos c : cells) {
            for (int h = 0; h < HEAD; h++) if (what.test(level.getBlockState(c.above(h)))) return true;
            for (Direction d : Direction.Plane.HORIZONTAL) for (int h = 0; h < HEAD; h++) {
                BlockState s = level.getBlockState(c.relative(d).above(h));
                if (s.getBlock() instanceof WallTorchBlock && what.test(s)) return true;
            }
        }
        return false;
    }

    /**
     * A lantern hung in a niche cut in the roof over the landing's outer cell, above the four of headroom (hung in the top
     * block of it, a player jumping on the landing put their head into it): out of the stores or the pack where there is one,
     * else the works' own, as their torches are (filmed: a town with no lanterns in store had one at its level landings and
     * an old wall torch at every other corner). A landing open to the sky, with no roof to hang one from: a torch on its wall.
     */
    private static void landingLight(Ctx cx, int i, List<BlockPos> cells) {
        ServerLevel level = cx.level;
        BlockPos outer = cells.get(3);
        BlockPos lamp = outer.above(HEAD), hook = lamp.above();
        if (level.getBlockState(lamp).getBlock() instanceof LanternBlock) return;
        BlockState ls = level.getBlockState(lamp);
        boolean hang = (ls.isAir() || MineStairs.ground(ls) && !ls.hasBlockEntity() && ls.getDestroySpeed(level, lamp) >= 0)
            && cx.mayPlace(lamp) && Block.canSupportCenter(level, hook, Direction.DOWN);
        if (hang) {
            for (BlockPos c : cells) {                                         // one hung in the headroom before: taken down
                BlockPos low = c.above(HEAD - 1);
                if (level.getBlockState(low).getBlock() instanceof LanternBlock) level.setBlock(low, Blocks.AIR.defaultBlockState(), 3);
            }
            cx.pay(LANTERN);
            level.setBlock(lamp, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 3);
            lit++;
            return;
        }
        if (lightIn(level, cells, s -> s.getLightEmission() > 0)) return;
        int[] o = MineWorks.diag(side(i));
        Direction ox = o[0] < 0 ? Direction.WEST : Direction.EAST, oz = o[1] < 0 ? Direction.NORTH : Direction.SOUTH;
        for (BlockPos c : new BlockPos[]{ outer, cells.get(1), cells.get(2) }) {
            for (Direction d : new Direction[]{ ox, oz }) {
                if (wallTorch(cx, c.above(), d)) return;
            }
        }
    }

    /** A torch on the outer wall of a side's middle step (on its timber set), else on the pillar's side. */
    private static void sideLight(Ctx cx, List<BlockPos> cells) {
        if (lightIn(cx.level, cells, s -> s.getLightEmission() > 0)) return;
        BlockPos inner = cells.get(0), outer = cells.get(1);
        Direction out = Direction.fromDelta(outer.getX() - inner.getX(), 0, outer.getZ() - inner.getZ());
        if (out == null) return;
        if (!wallTorch(cx, outer.above(), out)) wallTorch(cx, inner.above(), out.getOpposite());
    }

    private static boolean wallTorch(Ctx cx, BlockPos at, Direction wallSide) {
        ServerLevel level = cx.level;
        BlockPos wall = at.relative(wallSide);
        if (!level.getBlockState(at).isAir() || !level.getBlockState(wall).isFaceSturdy(level, wall, wallSide.getOpposite())) return false;
        cx.pay(TORCH);                                      // the pack's, the stores'; else the works' own
        level.setBlock(at, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, wallSide.getOpposite()), 3);
        lit++;
        return true;
    }

    // ------------------------------------------------------------------ signs

    private static boolean signed(UUID village, String key) {
        String raw = Ledger.note(village, SIGNS);
        if (raw == null || raw.isEmpty()) return false;
        for (String s : raw.split(",")) if (s.equals(key)) return true;
        return false;
    }

    private static void markSigned(UUID village, String key) {
        String raw = Ledger.note(village, SIGNS);
        Ledger.note(village, SIGNS, raw == null || raw.isEmpty() ? key : raw + "," + key);
    }

    static String title(String name) {
        String s = name.startsWith("the ") ? name.substring(4) : name;
        StringBuilder sb = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static void write(ServerLevel level, BlockPos p, String... lines) {
        if (!(level.getBlockEntity(p) instanceof SignBlockEntity sb)) return;
        SignText t = new SignText();
        for (int k = 0; k < lines.length && k < 4; k++) t = t.setMessage(k, Component.literal(lines[k]));
        sb.setText(t, true);
        sb.setWaxed(true);
        sb.setChanged();
        BlockState st = level.getBlockState(p);
        level.sendBlockUpdated(p, st, st, 3);
    }

    private static Block wood(Ctx cx, String suffix, Block fallback) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(MineWorks.wood(cx.level, cx.w) + suffix));
        return b == Blocks.AIR ? fallback : b;
    }

    /** A level's sign, on the back wall of its landing's mouth, facing the stair: its name and its depth. Once. */
    private static void levelSign(Ctx cx, int i) {
        MineWorks.Lvl lvl = null;
        for (MineWorks.Lvl l : cx.plan) if (l.corner() == i) lvl = l;
        if (lvl == null || lvl.id() < 31 && (cx.w.rooms & (1 << lvl.id())) == 0) return;   // its landing not cut yet
        String key = "l" + lvl.id();
        if (signed(cx.w.village, key)) return;
        ServerLevel level = cx.level;
        MineWorks.Works w = cx.w;
        int[] o = MineWorks.diag(side(i));
        int y = lvl.y() + 1;
        // Facing whoever comes down the stair onto the landing first, then the other mouth.
        Direction came = MineWorks.walk((side(i) + 3) % 4);
        BlockPos[] at = { new BlockPos(w.sx + 4 * o[0], y, w.sz + 2 * o[1]), new BlockPos(w.sx + 2 * o[0], y, w.sz + 4 * o[1]) };
        Direction[] away = { o[0] < 0 ? Direction.WEST : Direction.EAST, o[1] < 0 ? Direction.NORTH : Direction.SOUTH };
        int first = came.getAxis() == Direction.Axis.X ? 0 : 1;
        Block sign = wood(cx, "_wall_sign", Blocks.SPRUCE_WALL_SIGN);
        for (int k = 0; k < 2; k++) {
            int m = (first + k) % 2;
            BlockPos p = at[m];
            BlockPos wall = p.relative(away[m]);
            if (!level.getBlockState(p).isAir() || !level.getBlockState(wall).isFaceSturdy(level, wall, away[m].getOpposite())) continue;
            level.setBlock(p, sign.defaultBlockState().setValue(WallSignBlock.FACING, away[m].getOpposite()), 3);
            write(level, p, title(MineLevels.name(lvl.id())), "Y " + lvl.y(), (w.top - lvl.y()) + " blocks below", "the pithead");
            markSigned(w.village, key);
            signs++;
            return;
        }
    }

    /** The pithead's sign beside the way in, facing the way folk come to it: the stair, and the cage once there is one. */
    private static void pitheadSign(Ctx cx) {
        ServerLevel level = cx.level;
        MineWorks.Works w = cx.w;
        String cage = MineLift.has(w.village) ? "Cage: lever" : "";
        String cage2 = MineLift.has(w.village) ? "on the landing" : "";
        String raw = Ledger.note(w.village, SIGNS);
        BlockPos had = null;
        if (raw != null) for (String s : raw.split(",")) if (s.startsWith("p:")) had = BlockPos.of(Long.parseLong(s.substring(2)));
        if (had != null) {
            if (level.isLoaded(had) && level.getBlockEntity(had) instanceof SignBlockEntity sb
                    && !sb.getFrontText().getMessage(2, false).getString().equals(cage)) write(level, had, "Main Shaft", "Stairs down", cage, cage2);
            return;
        }
        int x = w.sx - 5, z = w.sz - 4;
        if (!level.isLoaded(new BlockPos(x, w.top, z))) return;
        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(g - w.top) > 4) return;
        BlockPos p = new BlockPos(x, g, z);
        if (!level.getBlockState(p).canBeReplaced() || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP) || !cx.mayPlace(p)) return;
        Block sign = wood(cx, "_sign", Blocks.SPRUCE_SIGN);
        level.setBlock(p, sign.defaultBlockState().setValue(StandingSignBlock.ROTATION, 4), 3);   // its face to the west, the way in
        write(level, p, "Main Shaft", "Stairs down", cage, cage2);
        markSigned(w.village, "p:" + p.asLong());
        signs++;
    }

    // ------------------------------------------------------------------ the way in

    /**
     * The way in beside the first landing (MineWorks.approach): level with the landing, its floor firm; where the ground
     * beyond lies higher, stairs cut up into it to the ground, and where it lies lower, stairs down to it, a step a block.
     */
    private static void wayIn(Ctx cx) {
        ServerLevel level = cx.level;
        MineWorks.Works w = cx.w;
        int top = w.top;
        int[] lanes = { w.sz - 2, w.sz - 3 };
        for (int z : lanes) {
            BlockPos a = new BlockPos(w.sx - 4, top, z);
            if (!level.isLoaded(a)) return;
            for (int h = 0; h < HEAD; h++) {
                BlockPos p = a.above(h);
                BlockState s = level.getBlockState(p);
                if (s.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock && level.getBlockEntity(p) instanceof net.minecraft.world.Container box && box.isEmpty()) {
                    level.destroyBlock(p, true);                              // an empty furnace left in the way in (filmed): taken up
                    cleared++;
                    continue;
                }
                if (s.isAir() || ours(s) || s.hasBlockEntity()) continue;
                if (!natural(level, p, s)) return;                              // something built there: left as it is
                if (!cx.mayPlace(p)) return;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                cx.spoil++;
                cleared++;
            }
            BlockPos fl = a.below();
            BlockState fs = level.getBlockState(fl);
            if ((open(fs) || falls(fs) || !fs.getFluidState().isEmpty()) && !fs.hasBlockEntity() && cx.mayPlace(fl)) {
                if (falls(fs)) level.setBlock(fl, firm(fs, fl.getY()), 3);
                else { cx.pay(RUBBLE); level.setBlock(fl, rubble(fl.getY()).defaultBlockState(), 3); }
                shored++;
            }
        }
        // Up to the ground beyond, or down to it.
        for (int j = 1; j <= 4; j++) {
            int x = w.sx - 4 - j;
            boolean any = false;
            for (int z : lanes) {
                if (!level.isLoaded(new BlockPos(x, top, z))) return;
                int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos upAt = new BlockPos(x, top + j - 1, z), downAt = new BlockPos(x, top - j, z);
                boolean upOurs = isStair(level.getBlockState(upAt), Direction.WEST), downOurs = isStair(level.getBlockState(downAt), Direction.EAST);
                if (upOurs || g >= top + j) {
                    if (!upOurs) {
                        BlockState s = level.getBlockState(upAt);
                        if (!s.isAir() && !natural(level, upAt, s) || s.hasBlockEntity() || !cx.mayPlace(upAt)) return;
                        set(level, upAt, stairState(open(s) ? rockStair(rubble(upAt.getY()).defaultBlockState(), upAt.getY()) : rockStair(s, upAt.getY()), Direction.WEST, level, upAt));
                        stairs++;
                    }
                    for (int h = 1; h <= HEAD; h++) {
                        BlockPos p = upAt.above(h);
                        BlockState s = level.getBlockState(p);
                        if (s.isAir() || ours(s) || s.hasBlockEntity() || !natural(level, p, s) || !cx.mayPlace(p)) continue;
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                        cx.spoil++;
                    }
                    any = true;
                } else if (downOurs || g <= top - j) {
                    if (!downOurs) {
                        BlockState s = level.getBlockState(downAt);
                        if (!open(s) || !cx.mayPlace(downAt)) continue;
                        cx.pay(RUBBLE);
                        set(level, downAt, stairState(rockStair(rubble(downAt.getY()).defaultBlockState(), downAt.getY()), Direction.EAST, level, downAt));
                        stairs++;
                    }
                    any = true;
                }
            }
            if (!any) break;
        }
        w.approachY = top;
    }

    private static boolean isStair(BlockState s, Direction facing) {
        return s.getBlock() instanceof StairBlock && s.getValue(StairBlock.FACING) == facing && s.getValue(StairBlock.HALF) == Half.BOTTOM;
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests(@Nullable UUID village) {
        risers = stairs = shored = sealed = fenced = cleared = cut = lit = signs = bricked = 0;
        if (village != null) {
            CURSOR.remove(village);
            Ledger.forget(village, SIGNS);
        }
    }

    /** {risers, stairs, shored, sealed, fenced, cleared, cut, lit, signs, bricked}. */
    public static int[] countsForTests() {
        return new int[]{ risers, stairs, shored, sealed, fenced, cleared, cut, lit, signs, bricked };
    }

    /** Tests: every step of the stair kept at once (the rounds of a while), the way in and the pithead's sign. */
    public static void keepAllForTests(ServerLevel level, UUID village) {
        MineWorks.Works w = MineWorks.works(level, village);
        if (w == null) return;
        Ctx cx = new Ctx(level, w, null);
        for (int i = 0; i < w.sunk; i++) keep(cx, i);
        wayIn(cx);
        pitheadSign(cx);
        w.dug += cx.spoil;
    }

    /** Tests: is this column one of the corner's risers. */
    public static List<BlockPos> risersForTests(ServerLevel level, UUID village, int corner) {
        MineWorks.Works w = MineWorks.works(level, village);
        return w == null ? List.of() : risers(w, corner);
    }

    /** Tests: the way up the stair at a step. */
    public static Direction upForTests(int step) { return up(step); }
}
