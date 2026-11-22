package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [life28] The square as a square: one even pavement inside the wall, nothing loose left standing on it, and the wall
 * round it one wall.
 *
 * <p>A grown town's square, filmed from the air at dusk, read as a ruin: its floor a patchwork of cobble, gravel and
 * andesite with squares of turf and holes in it, a tree grown up inside it, two crafting tables stacked by a chest that
 * hands had set down to craft at, odd stray blocks; its wall in gaps of every height, with spires of stone where the
 * builders had stood their footholds and stray red bricks in it; the notice board left hanging in the air over ground
 * levelled from under its posts. Made good (with SquareMend, once a town; the stray things swept again now and then):
 * <ul>
 * <li><b>The floor</b>: every column inside the wall that is the square's (no building's) laid at the square's level in
 *     one pattern: a border of stone bricks round the inside of the wall, a chequer of stone bricks and polished andesite
 *     over the rest, and chiselled stone at the heart. What grew on it (turf, flowers, a tree that seeded itself) is
 *     cleared; the stalls, the lamps, the benches, the well and whatever else the town put there on purpose are left.</li>
 * <li><b>Loose things</b>: a crafting table, a furnace, a chest or a barrel standing loose on the square (no building's,
 *     no stall's), or a stray block of stone, earth or planks on it, taken up into the stores, a chest's goods with it.
 *     (Nobody sets a table down there now: CraftGoal crafts at the stores' bench on the town's open ground, TownGround.)</li>
 * <li><b>The wall</b>: taken down to the ground and laid again whole, to its one drawing (BuildGoal's "fortify": three
 *     courses and a coping, a squat tower at each corner, a pillar and a lantern each side of the four gates on the
 *     avenues), all of stone bricks, on the level ground; where the ground outside stands higher (a hillside town) the
 *     wall is the terrace's retaining wall and the gate steps up to the street in stone stairs.</li>
 * <li><b>The board</b>: taken down and put up again on its posts on the square's edge.</li>
 * </ul>
 */
public final class SquareDress {

    private SquareDress() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    static final int INSIDE = TownPlan.PLAZA - 1, WALL = TownPlan.PLAZA;

    /** The pavement's pattern at this offset from the heart. */
    static Block paving(int dx, int dz) {
        int d = Math.max(Math.abs(dx), Math.abs(dz));
        if (d <= 1) return Blocks.CHISELED_STONE_BRICKS;
        if (d == INSIDE) return Blocks.STONE_BRICKS;
        return (Math.abs(dx) + Math.abs(dz)) % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.POLISHED_ANDESITE;
    }

    /** Is this a block the pavement may be laid over / in place of (earth, turf, paving, a path)? */
    static boolean floor(Level level, BlockPos p, BlockState s) {
        return Terraform.earth(s) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND) || SiteWork.townPaving(level, p, s)
            || s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.CHISELED_STONE_BRICKS) || s.is(Blocks.ANDESITE);
    }

    /** A loose fixture on the open ground: a workstation, a chest, a barrel. */
    static boolean fixture(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL || b == Blocks.FLETCHING_TABLE
            || b == Blocks.SMITHING_TABLE || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.LOOM || b == Blocks.STONECUTTER
            || b == Blocks.GRINDSTONE || b == Blocks.COMPOSTER;
    }

    /** A stray block of building stuff or earth standing on the open ground (a foothold left, a block dropped). */
    static boolean stray(BlockState s) {
        return Terraform.earth(s) || s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.is(Blocks.COBBLESTONE)
            || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(BlockTags.STONE_BRICKS) || s.is(Blocks.BRICKS) || s.is(Blocks.GRAVEL)
            || s.is(Blocks.SAND) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.POLISHED_GRANITE) || s.is(Blocks.GRANITE)
            || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.DEEPSLATE_BRICKS);
    }

    /** A block standing by itself (a foothold's pillar, a block dropped): not two of its sides against more of the same. */
    static boolean lone(Level level, BlockPos p) {
        int by = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(p.relative(d));
            if (!n.isAir() && !n.canBeReplaced() && !Terraform.growth(n)) by++;
        }
        return by <= 1;
    }

    /** By a stall of the square's market (TownLife's), kept as it stands. */
    static boolean stall(int dx, int dz) {
        return Math.abs(Math.abs(dx) - 8) <= 2 && Math.abs(Math.abs(dz) - 8) <= 2;
    }

    /** The square dressed whole now, at its level {@code lv} (the top of its ground). What was done, by count. */
    static Map<String, Integer> dress(ServerLevel level, Villages.Village v, int lv) {
        Map<String, Integer> did = new LinkedHashMap<>();
        BlockPos heart = v.centre();
        BlockPos board = VillageBoards.boardOf(v.id());
        boolean boardHere = board != null && level.getBlockState(board).getBlock() instanceof VillageBoardBlock
            && Math.max(Math.abs(board.getX() - heart.getX()), Math.abs(board.getZ() - heart.getZ())) <= WALL + 3;
        if (boardHere) {
            takeDownBoard(level, board);
            did.merge("board", 1, Integer::sum);
        }
        // The floor and what stands loose on it.
        for (int dx = -INSIDE; dx <= INSIDE; dx++) {
            for (int dz = -INSIDE; dz <= INSIDE; dz++) {
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                BlockPos ground = new BlockPos(x, lv, z);
                if (TownBlocks.buildingAt(level, v, ground.above()) != null || TownBlocks.buildingAt(level, v, ground) != null) continue;
                boolean keep = stall(dx, dz);
                for (int y = lv + 1; y <= lv + 16; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    if (Terraform.growth(s) || s.is(BlockTags.LEAVES) || (s.is(BlockTags.LOGS) && !keep)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                        did.merge("growth", 1, Integer::sum);
                    } else if (PlayerBlocks.placed(level, p)) {
                        // a player's own: left as it is
                    } else if (!keep && fixture(s) && takeable(v, s)) {
                        takeUp(level, v, p, s);
                        did.merge("fixtures", 1, Integer::sum);
                    } else if (!keep && stray(s) && y <= lv + 6 && lone(level, p)) {
                        takeUp(level, v, p, s);
                        did.merge("strays", 1, Integer::sum);
                    }
                }
                // The ground made solid to the level, and paved.
                for (int y = lv - 3; y < lv; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.canBeReplaced()) level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                BlockState top = level.getBlockState(ground);
                Block want = paving(dx, dz);
                if ((top.isAir() || top.canBeReplaced() || floor(level, ground, top)) && !top.is(want)) {
                    level.setBlock(ground, want.defaultBlockState(), 2 | 16);
                    did.merge("paved", 1, Integer::sum);
                }
                BlockState over = level.getBlockState(ground.above());
                if (over.canBeReplaced() && !over.isAir() && over.getFluidState().isEmpty()) level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        // The wall: down, and laid again whole.
        did.put("wall", wall(level, v, lv));
        if (boardHere) {
            BlockPos up = VillageBoards.raiseFor(level, v);
            did.put("board up", up == null ? 0 : 1);
        }
        // Lit as it was lit: a lamp each side of the heart's paving if it has none (the torches in the turf went with it).
        LOG.info("[MCA-FOUND] [life28] {}: the square dressed at y={}: {}", Villages.name(v.id()), lv, did);
        return did;
    }

    /** The wall round the square taken down and laid again to its drawing on the level ground; blocks laid. */
    static int wall(ServerLevel level, Villages.Village v, int lv) {
        BlockPos heart = v.centre();
        for (int dx = -WALL; dx <= WALL; dx++) {
            for (int dz = -WALL; dz <= WALL; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != WALL) continue;
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                BlockPos foot = new BlockPos(x, lv, z);
                com.jrpetty.mcassistant.village.Ledger.Building b = TownBlocks.buildingAt(level, v, foot.above());
                if (b != null && !b.structure().equals("fortify")) continue;                   // somebody's building on the line
                // Whatever stood on the line, down: the old courses, the spires, a stray brick, a foothold.
                for (int y = lv + 1; y <= lv + 12; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.getBlock() instanceof VillageBoardBlock) continue;
                    if (s.hasBlockEntity() && !(s.is(BlockTags.BANNERS))) { takeUp(level, v, p, s); continue; }
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
                // Its ground: solid to the level, and the outside's height known (a hillside's terrace behind it).
                for (int y = lv - 3; y <= lv; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty()) level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 2 | 16);
                    else if (y == lv && !floor(level, p, s)) level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 2 | 16);
                }
            }
        }
        int laid = BuildGoal.stamp(level, "fortify", new BlockPos(heart.getX(), lv + 1, heart.getZ()), Direction.NORTH, TownPlan.PLAZA, SquareDress::paint);
        laid += terrace(level, heart, lv);
        return laid;
    }

    /** The wall's one look: stone bricks, its coping of their slabs, its lanterns. */
    static BlockState paint(BuildGoal.Placement p) {
        if (p.part() == BuildGoal.Part.LANTERN) return Blocks.LANTERN.defaultBlockState();
        if (p.part() == BuildGoal.Part.TORCH) return Blocks.TORCH.defaultBlockState();
        if (p.style() == Blueprints.Style.STONE_SLAB) return Blocks.STONE_BRICK_SLAB.defaultBlockState();
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    /**
     * A hillside square: where the ground outside the wall stands over the square, the wall is the terrace's face, built
     * up solid to it (and its courses on top), and each gate climbs to the street in stone stairs. Blocks laid.
     */
    static int terrace(ServerLevel level, BlockPos heart, int lv) {
        int laid = 0;
        for (Direction out : Direction.Plane.HORIZONTAL) {
            Direction along = out.getClockWise();
            for (int a = -WALL; a <= WALL; a++) {
                BlockPos col = heart.relative(out, WALL).relative(along, a);
                BlockPos beyond = col.relative(out);
                int outside = SquareMend.top(level, beyond.getX(), beyond.getZ());
                if (outside == Integer.MIN_VALUE || outside <= lv + 1) continue;
                int rise = Math.min(outside - lv, 8);
                boolean gate = Math.abs(a) <= TownPlan.AVENUE;
                if (gate) {
                    // Steps up from the square's floor through the gate to the street: one a block, outwards.
                    for (int i = 0; i < rise; i++) {
                        BlockPos step = col.relative(out, i).atY(lv + 1 + i);
                        for (int y = lv + 1; y < step.getY(); y++) {
                            level.setBlock(step.atY(y), Blocks.STONE_BRICKS.defaultBlockState(), 2 | 16);
                        }
                        level.setBlock(step, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, out)
                            .setValue(StairBlock.HALF, Half.BOTTOM), 2 | 16);
                        for (int y = step.getY() + 1; y <= step.getY() + 3; y++) {
                            BlockPos p = step.atY(y);
                            if (!level.getBlockState(p).isAir() && !level.getBlockState(p).is(Blocks.STONE_BRICKS) && !level.getBlockState(p).is(Blocks.STONE_BRICK_SLAB)) {
                                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                            }
                        }
                        laid++;
                    }
                } else {
                    // The terrace's face: the wall built up solid to the ground behind it.
                    for (int y = lv + 1; y <= lv + rise; y++) {
                        BlockState s = level.getBlockState(col.atY(y));
                        if (s.isAir() || s.canBeReplaced()) {
                            level.setBlock(col.atY(y), Blocks.STONE_BRICKS.defaultBlockState(), 2 | 16);
                            laid++;
                        }
                    }
                }
            }
        }
        return laid;
    }

    /** A loose thing taken up into the stores (a chest's goods with it). */
    static void takeUp(ServerLevel level, Villages.Village v, BlockPos p, BlockState s) {
        java.util.List<ItemStack> goods = new java.util.ArrayList<>();
        if (level.getBlockEntity(p) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) goods.add(c.getItem(i).copy());
            c.clearContent();
        }
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        goods.add(new ItemStack(s.getBlock().asItem()));
        for (ItemStack g : goods) {
            if (g.isEmpty()) continue;
            ItemStack left = Market.intoStores(level, v.id(), g);
            if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, left);
        }
    }

    /** May a loose chest on the square be taken up: the town has a storehouse to keep its goods in. */
    static boolean storesElsewhere(Villages.Village v) {
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(v.id())) {
            if (b.structure().equals("storehouse") || b.structure().equals("storage")) return true;
        }
        return false;
    }

    static boolean takeable(Villages.Village v, BlockState s) {
        boolean chest = s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST) || s.is(Blocks.BARREL);
        return !chest || storesElsewhere(v);
    }

    /** The board on the square's edge taken down, its posts with it (put up again by VillageBoards.raiseFor). */
    static void takeDownBoard(ServerLevel level, BlockPos board) {
        Direction facing = level.getBlockState(board).getValue(VillageBoardBlock.FACING);
        Direction right = VillageBoardBlock.right(facing);
        for (int r = VillageBoardBlock.HIGH - 1; r >= 0; r--) {
            for (int c = 0; c < VillageBoardBlock.WIDE; c++) {
                BlockPos p = VillageBoardBlock.cell(board, facing, c, r);
                if (level.getBlockState(p).getBlock() instanceof VillageBoardBlock) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        for (int c = 0; c < VillageBoardBlock.WIDE; c++) {
            BlockPos foot = board.relative(right, c);
            for (int y = foot.getY() - 1; y >= foot.getY() - 16; y--) {
                BlockPos p = foot.atY(y);
                if (!level.getBlockState(p).is(Blocks.DARK_OAK_LOG)) break;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        VillageBoards.gone(level, board);
        VillageBoards.preferSide(new BlockPos(board.getX(), board.getY(), board.getZ()), facing.getOpposite());
    }

    /** The loose things on the square swept up again (now and then: from SquareMend's round). How many. */
    static int sweep(ServerLevel level, Villages.Village v, int lv) {
        BlockPos heart = v.centre();
        int n = 0;
        for (int dx = -INSIDE; dx <= INSIDE; dx++) {
            for (int dz = -INSIDE; dz <= INSIDE; dz++) {
                if (stall(dx, dz)) continue;
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                for (int y = lv + 1; y <= lv + 3; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || !(fixture(s) && takeable(v, s) || stray(s) && y <= lv + 2 && lone(level, p))) continue;
                    if (PlayerBlocks.placed(level, p)) continue;
                    if (TownBlocks.buildingAt(level, v, p) != null) continue;
                    takeUp(level, v, p, s);
                    n++;
                }
            }
        }
        if (n > 0) LOG.info("[MCA-FOUND] [life28] {}: {} loose things on the square taken up into the stores", Villages.name(v.id()), n);
        return n;
    }

    /** The audit (the tests): the square's floor, what stands loose on it, the wall. Counts by fault. */
    public static Map<String, Integer> audit(ServerLevel level, Villages.Village v, int lv) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String k : new String[]{ "columns", "notPaving", "holes", "loose", "wallGaps", "wallTops" }) c.put(k, 0);
        BlockPos heart = v.centre();
        java.util.Set<Integer> tops = new java.util.TreeSet<>();
        for (int dx = -WALL; dx <= WALL; dx++) {
            for (int dz = -WALL; dz <= WALL; dz++) {
                int x = heart.getX() + dx, z = heart.getZ() + dz;
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d < WALL) {
                    BlockPos g = new BlockPos(x, lv, z);
                    if (TownBlocks.buildingAt(level, v, g.above()) != null || TownBlocks.buildingAt(level, v, g) != null) continue;
                    c.merge("columns", 1, Integer::sum);
                    BlockState top = level.getBlockState(g);
                    if (top.isAir() || level.getBlockState(g.below()).isAir()) c.merge("holes", 1, Integer::sum);
                    else if (!SiteWork.townPaving(level, g, top) && !top.is(Blocks.POLISHED_ANDESITE) && !top.is(Blocks.CHISELED_STONE_BRICKS)) c.merge("notPaving", 1, Integer::sum);
                    if (!stall(dx, dz)) for (int y = lv + 1; y <= lv + 3; y++) {
                        BlockState s = level.getBlockState(g.atY(y));
                        if (fixture(s) || stray(s)) c.merge("loose", 1, Integer::sum);
                    }
                    continue;
                }
                int along = Math.abs(dx) == WALL ? dz : dx;
                if (Math.abs(along) <= TownPlan.AVENUE && d == WALL && !(Math.abs(dx) == WALL && Math.abs(dz) == WALL)) continue;   // a gate
                if (level.getBlockState(new BlockPos(x, lv + 1, z)).getBlock() instanceof VillageBoardBlock
                    || level.getBlockState(new BlockPos(x, lv + 2, z)).getBlock() instanceof VillageBoardBlock) continue;
                int h = 0;
                for (int y = lv + 1; y <= lv + 8; y++) if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) h = y - lv;
                if (h < 3) c.merge("wallGaps", 1, Integer::sum);
                else tops.add(h);
            }
        }
        c.put("wallTops", tops.size());                       // the wall's heights round it (3 and 4 coped, 5 and 6 the towers)
        return c;
    }
}
