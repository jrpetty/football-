package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [biz30] Every business's name at its front. The sign by a building's door (TownLife.addressSign) went up only where the
 * wall either side of the door, at the door's head, was solid with the air before it: a bank with windows by its door, a
 * smithy whose door stands in its open side, the market's open hall, the stable's wide doors, the wheelwright's shed and
 * the smeltery had no name anywhere, and on film read as nobody's building. Now a business with no sign at its front has
 * one put up: on its door's lintel, or the wall a step or two either side of its door at the door's head, or on a post by
 * its doorstep (the market and the open sheds, on the ground before their middle). Its name ("The Smithy") and its
 * address. Out of the stores, put up by a hand at the town's works (free when the town is dressed for a test or a
 * showcase); never on a road, in a doorway or on the way in.
 */
public final class BizFronts {

    private BizFronts() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A sign at this building's front already (any sign, board or banner within two of its walls). */
    static boolean signed(ServerLevel level, Ledger.Building b) {
        int[] box = box(b);
        if (box == null) return true;
        for (BlockPos q : BlockPos.betweenClosed(box[0] - 2, box[4], box[2] - 2, box[1] + 2, box[4] + 6, box[3] + 2)) {
            BlockState st = level.getBlockState(q);
            if (st.getBlock() instanceof net.minecraft.world.level.block.SignBlock
                    || st.getBlock() instanceof net.minecraft.world.level.block.CeilingHangingSignBlock
                    || st.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock
                    || st.getBlock() instanceof com.jrpetty.mcassistant.block.ShopSignBlock
                    || st.is(net.minecraft.tags.BlockTags.BANNERS)) return true;
        }
        return false;
    }

    /** The footing's box: x0, x1, z0, z1, and the floor's y (the first cell of air over it). */
    @Nullable
    static int[] box(Ledger.Building b) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13);
        int minY = Integer.MAX_VALUE;
        for (BuildGoal.Placement p : plan) if (p.part() != BuildGoal.Part.CLEAR) minY = Math.min(minY, p.pos().getY());
        if (minY == Integer.MAX_VALUE) return null;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR || p.pos().getY() != minY) continue;
            x0 = Math.min(x0, p.pos().getX()); x1 = Math.max(x1, p.pos().getX());
            z0 = Math.min(z0, p.pos().getZ()); z1 = Math.max(z1, p.pos().getZ());
        }
        return new int[]{ x0, x1, z0, z1, minY + 1 };
    }

    /**
     * A business's name board put up if it has none at its front. Out of the stores and by a hand at the town's works unless
     * {@code free}. Whether it has one now.
     */
    public static boolean board(ServerLevel level, UUID village, BlockPos heart, Ledger.Building b, @Nullable Villages.Village v, boolean free) {
        if (!BuildingAudit.BUSINESSES.contains(b.structure())) return true;
        if (!free && v == null) return false;
        if (signed(level, b)) return true;
        int[] box = box(b);
        if (box == null) return false;
        String[] where = TownLife.address(village, heart, b);
        String[] lines = { TownLife.title(b.structure()), where != null ? where[0] : "", where != null ? where[1] : "", "" };
        // Each door on the footing's edge, the front's first: its lintel, then the wall beside it at its head.
        List<BlockPos> doors = new ArrayList<>();
        Set<Long> planned = new HashSet<>();
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            planned.add(p.pos().asLong());
            if (p.part() == BuildGoal.Part.DOOR && p.pos().getY() == box[4]) doors.add(p.pos());
        }
        Direction front = b.facing().getOpposite();
        doors.sort(java.util.Comparator.comparingInt((BlockPos d) -> out(d, box) == front ? 0 : 1));
        for (BlockPos door : doors) {
            Direction out = out(door, box);
            if (out == null) continue;
            Direction side = out.getClockWise();
            List<BlockPos> walls = new ArrayList<>();
            walls.add(door.above(2));                                       // the lintel
            for (int up = 1; up <= 2; up++) {
                for (int k = 1; k <= 3; k++) {
                    walls.add(door.above(up).relative(side, k));
                    walls.add(door.above(up).relative(side.getOpposite(), k));
                }
            }
            for (BlockPos wall : walls) {
                BlockPos spot = wall.relative(out);
                if (!level.getBlockState(spot).isAir() || !level.getBlockState(wall).isFaceSturdy(level, wall, out)) continue;
                if (v != null && !TownBlocks.mayPlace(level, v, spot, TownBlocks.Why.SIGN)) continue;
                if (!pay(level, v, spot, free)) return false;
                level.setBlock(spot, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, out), 3);
                write(level, spot, lines);
                return true;
            }
        }
        // An open front (the smeltery's, the foundry's) or gates: on a post or pier of its front, at head height.
        java.util.List<BlockPos> faces = new ArrayList<>();
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                boolean edge = switch (front) {
                    case NORTH -> z == box[2];
                    case SOUTH -> z == box[3];
                    case WEST -> x == box[0];
                    default -> x == box[1];
                };
                if (!edge) continue;
                for (int up = 1; up <= 2; up++) faces.add(new BlockPos(x, box[4] - 1 + up, z));
            }
        }
        int fcx = (box[0] + box[1]) / 2, fcz = (box[2] + box[3]) / 2;
        faces.sort(java.util.Comparator.comparingInt((BlockPos q) -> Math.abs(q.getX() - fcx) + Math.abs(q.getZ() - fcz)).thenComparingInt(BlockPos::getY));
        for (BlockPos wall : faces) {
            if (!planned.contains(wall.asLong())) continue;
            BlockPos spot = wall.relative(front);
            if (!level.getBlockState(spot).isAir() || !level.getBlockState(wall).isFaceSturdy(level, wall, front)) continue;
            if (v != null && !TownBlocks.mayPlace(level, v, spot, TownBlocks.Why.SIGN)) continue;
            if (!pay(level, v, spot, free)) return false;
            level.setBlock(spot, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front), 3);
            write(level, spot, lines);
            return true;
        }
        // No wall to hang it on: a sign on a post by the way in (or before the middle of its front).
        List<BlockPos> posts = new ArrayList<>();
        for (BlockPos door : doors) {
            Direction out = out(door, box);
            if (out == null) continue;
            for (int far = 2; far <= 3; far++) {
                for (int k = 2; k <= 3; k++) {
                    posts.add(door.relative(out, far).relative(out.getClockWise(), k));
                    posts.add(door.relative(out, far).relative(out.getCounterClockWise(), k));
                }
            }
        }
        int cx = (box[0] + box[1]) / 2, cz = (box[2] + box[3]) / 2;
        int reach = front.getAxis() == Direction.Axis.X ? (box[1] - box[0]) / 2 : (box[3] - box[2]) / 2;
        BlockPos mid = new BlockPos(cx, box[4], cz).relative(front, reach + 2);
        posts.add(mid.relative(front.getClockWise(), 2));
        posts.add(mid.relative(front.getCounterClockWise(), 2));
        posts.add(mid.relative(front.getClockWise(), 3));
        for (BlockPos at : posts) {
            BlockPos foot = null;
            for (int dy = 3; dy >= -4 && foot == null; dy--) {
                BlockPos c = at.above(dy);
                if (level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir()
                        && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) foot = c;
            }
            if (foot == null || planned.contains(foot.asLong())) continue;
            if (v != null && (!TownBlocks.mayPlace(level, v, foot, TownBlocks.Why.SIGN) || !TownBlocks.mayPlace(level, v, foot.above(), TownBlocks.Why.SIGN))) continue;
            if (!pay(level, v, foot, free)) return false;
            level.setBlock(foot, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
            level.setBlock(foot.above(), Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
                RotationSegment.convertToSegment(front)), 3);
            write(level, foot.above(), lines);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ its front: what it is, from the street

    /** A trade's colour, for the cloth hung by its door. */
    static net.minecraft.world.item.DyeColor colourOf(String structure) {
        return switch (structure) {
            case "bakery" -> net.minecraft.world.item.DyeColor.YELLOW;
            case "tavern", "restaurant" -> net.minecraft.world.item.DyeColor.RED;
            case "brewery" -> net.minecraft.world.item.DyeColor.PURPLE;
            case "inn", "guesthouse" -> net.minecraft.world.item.DyeColor.BLUE;
            case "cafe" -> net.minecraft.world.item.DyeColor.PINK;
            case "shop", "store", "market" -> net.minecraft.world.item.DyeColor.GREEN;
            case "smithy", "smeltery", "foundry", "wheelwright" -> net.minecraft.world.item.DyeColor.BLACK;
            case "bank", "auction" -> net.minecraft.world.item.DyeColor.ORANGE;
            case "postoffice", "maproom", "tradingpost", "harbour" -> net.minecraft.world.item.DyeColor.LIGHT_BLUE;
            case "infirmary" -> net.minecraft.world.item.DyeColor.WHITE;
            case "apiary" -> net.minecraft.world.item.DyeColor.ORANGE;
            default -> net.minecraft.world.item.DyeColor.BROWN;
        };
    }

    /** One piece set out by the door: the block (and what stands on it), and what the stores give for it. */
    private record Piece(BlockState block, @Nullable BlockState top, java.util.function.Predicate<ItemStack> pays) {}

    private static Piece piece(net.minecraft.world.level.block.Block b, @Nullable net.minecraft.world.level.block.Block top) {
        net.minecraft.world.item.Item it = b.asItem();
        return new Piece(b.defaultBlockState(), top == null ? null : top.defaultBlockState(), x -> x.is(it));
    }

    /** What a trade sets out by its door, the left side's and the right's. */
    static java.util.List<Piece> piecesOf(String structure) {
        return switch (structure) {
            case "tavern", "brewery", "inn" -> java.util.List.of(piece(Blocks.BARREL, Blocks.LANTERN), piece(Blocks.BARREL, null), piece(Blocks.BARREL, Blocks.LANTERN));
            case "bakery" -> java.util.List.of(piece(Blocks.BARREL, Blocks.CAKE), piece(Blocks.HAY_BLOCK, null), piece(Blocks.BARREL, Blocks.LANTERN));
            case "shop", "store", "market" -> java.util.List.of(piece(Blocks.BARREL, Blocks.LANTERN), piece(Blocks.BARREL, null), piece(Blocks.COMPOSTER, null));
            case "cafe", "restaurant" -> java.util.List.of(piece(Blocks.OAK_FENCE, Blocks.WHITE_CARPET), piece(Blocks.SPRUCE_STAIRS, null), piece(Blocks.OAK_FENCE, Blocks.LANTERN));
            case "smithy", "smeltery", "foundry" -> java.util.List.of(piece(Blocks.ANVIL, null), piece(Blocks.GRINDSTONE, null), piece(Blocks.COBBLESTONE_WALL, Blocks.LANTERN));
            default -> java.util.List.of(piece(Blocks.OAK_FENCE, Blocks.LANTERN), piece(Blocks.OAK_FENCE, Blocks.LANTERN));
        };
    }

    /**
     * [biz30] A business's front, so the street can tell a bakery from a tavern: a cloth in its trade's colour hung on its
     * front wall by the door, and its trade's things set out against the wall either side of the way in (casks and a lamp at
     * the tavern, a cake on a cask and a bale at the bakery, crates at the store, a table and a chair at the café, the anvil
     * and the grindstone at the smithy; a pair of lamps on posts elsewhere). Never on the doorstep, the way in or the cell
     * either side of it, never on a road or a path, never over open ground that is not level with the floor. Out of the
     * stores (what they lack is left out) unless {@code free}. How many pieces went up.
     */
    public static int frontage(ServerLevel level, UUID village, Ledger.Building b, @Nullable Villages.Village v, boolean free) {
        if (!BuildingAudit.BUSINESSES.contains(b.structure()) || !free && v == null) return 0;
        int[] box = box(b);
        if (box == null) return 0;
        Direction front = b.facing().getOpposite();
        BlockPos door = null;
        Set<Long> planned = new HashSet<>();
        List<BlockPos> doors = new java.util.ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR) continue;
            planned.add(p.pos().asLong());
            if (p.part() == BuildGoal.Part.DOOR && p.pos().getY() == box[4]) doors.add(p.pos().immutable());
        }
        // [biz30] its front door: the one that opens onto nothing of its own, furthest to the front (not the box's edge: a
        // step before the door, laid with the footings, put the edge a cell out and the tavern's door went unfound)
        for (BlockPos d : doors) {
            if (planned.contains(d.relative(front).asLong())) continue;
            if (door == null || front.getAxis().choose(d.getX(), 0, d.getZ()) * front.getAxisDirection().getStep()
                > front.getAxis().choose(door.getX(), 0, door.getZ()) * front.getAxisDirection().getStep()) door = d;
        }
        if (door == null) {                                                  // an open front: its middle
            int cx = (box[0] + box[1]) / 2, cz = (box[2] + box[3]) / 2;
            int reach = front.getAxis() == Direction.Axis.X ? (box[1] - box[0]) / 2 : (box[3] - box[2]) / 2;
            door = new BlockPos(cx, box[4], cz).relative(front, reach);
        }
        Direction side = front.getClockWise();
        int done = 0;
        // The cloth: on the front wall a step or two from the door, at the door's head.
        net.minecraft.world.level.block.Block banner = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace(colourOf(b.structure()).getName() + "_wall_banner"));
        boolean hung = false;
        for (BlockPos q : BlockPos.betweenClosed(door.offset(-3, 1, -3), door.offset(3, 3, 3))) {
            if (level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.WallBannerBlock) { hung = true; break; }
        }
        if (!hung) {
            outer:
            for (int k : new int[]{ -2, 2, -3, 3 }) {
                for (int up : new int[]{ 2, 1, 3 }) {                       // [biz30] or above its windows
                    BlockPos wall = door.above(up).relative(side, k);
                    BlockPos spot = wall.relative(front);
                    if (!planned.contains(wall.asLong()) || !level.getBlockState(spot).isAir()
                            || !level.getBlockState(wall).isFaceSturdy(level, wall, front)) continue;
                    if (v != null && !TownBlocks.mayPlace(level, v, spot, TownBlocks.Why.SIGN)) continue;
                    if (!free && !Crafts.take(level, v, x -> x.is(net.minecraft.tags.ItemTags.BANNERS) || x.is(net.minecraft.tags.ItemTags.WOOL), 1)) break outer;
                    level.setBlock(spot, banner.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, front), 3);
                    done++;
                    break outer;
                }
            }
        }
        // Its things against the front wall, either side of the way in (two or more cells from the door's line).
        java.util.List<Piece> pieces = piecesOf(b.structure());
        int n = 0;
        for (int k : new int[]{ -2, 2, -3, 3 }) {
            if (n >= pieces.size()) break;
            BlockPos at = door.relative(front).relative(side, k);
            BlockPos ground = at.below();
            if (planned.contains(at.asLong())) continue;
            BlockState here = level.getBlockState(at);
            boolean ours = !here.isAir() && pieces.stream().anyMatch(pc -> here.is(pc.block().getBlock()));
            if (ours) { n++; continue; }                                     // set out already
            if (!here.isAir() || !level.getBlockState(at.above()).isAir()) continue;
            BlockState g = level.getBlockState(ground);
            if (!g.isFaceSturdy(level, ground, Direction.UP) || SiteWork.townPaving(level, ground, g) || g.is(Blocks.FARMLAND)) continue;
            if (!level.getBlockState(at.relative(front.getOpposite())).isSolid()) continue;   // against its wall, not out in the street
            if (v != null && (!TownBlocks.mayPlace(level, v, at, TownBlocks.Why.DECOR) || TownBlocks.zone(level, v, at) == TownBlocks.Zone.ROAD
                || TownBlocks.zone(level, v, at) == TownBlocks.Zone.APPROACH)) continue;
            Piece pc = pieces.get(n);
            if (!free && !Crafts.take(level, v, pc.pays(), 1)) { n++; continue; }
            BlockState put = pc.block();
            if (put.hasProperty(net.minecraft.world.level.block.StairBlock.FACING)) {
                put = put.setValue(net.minecraft.world.level.block.StairBlock.FACING, k < 0 ? side : side.getOpposite());
            } else if (put.hasProperty(net.minecraft.world.level.block.AnvilBlock.FACING)) {
                put = put.setValue(net.minecraft.world.level.block.AnvilBlock.FACING, side);
            } else if (put.hasProperty(net.minecraft.world.level.block.BarrelBlock.FACING)) {
                put = put.setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP);
            }
            level.setBlock(at, put, 3);
            BlockState joined = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(put, level, at);
            if (joined != put) level.setBlock(at, joined, 2 | 16);
            if (pc.top() != null && level.getBlockState(at.above()).isAir() && (free || Crafts.take(level, v, x -> x.is(pc.top().getBlock().asItem()), 1))) {
                level.setBlock(at.above(), pc.top(), 3);
            }
            done++;
            n++;
        }
        done += awning(level, v, b, door, front, side, planned, free);             // [biz30c] a cue that reads from down the street
        done += chimney(level, v, b, box, planned, free);
        return done;
    }

    /**
     * [biz30c] An awning over the way in: three blocks of its trade's colour striped with white, one out from the front wall
     * at the height over the door's head (a walker passes under it), never in a cell its drawing uses or one not empty, and
     * only where the wall above the door stands to hold it. How many went up (0 or 3).
     */
    static int awning(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, BlockPos door, Direction front, Direction side, Set<Long> planned, boolean free) {
        if (!AWNINGS.contains(b.structure())) return 0;
        net.minecraft.world.item.DyeColor col = colourOf(b.structure());
        net.minecraft.world.item.DyeColor stripe = col == net.minecraft.world.item.DyeColor.WHITE ? net.minecraft.world.item.DyeColor.LIGHT_GRAY : net.minecraft.world.item.DyeColor.WHITE;
        // over the door's head, or higher where a lamp hangs there or an open front's beam stands higher (the smithy's)
        List<BlockPos> cells = null;
        for (int up = 2; up <= 3 && cells == null; up++) {
            List<BlockPos> row = new ArrayList<>();
            boolean ok = true;
            for (int k = -1; k <= 1 && ok; k++) {
                BlockPos wall = door.above(up).relative(side, k), at = wall.relative(front);
                BlockState here = level.getBlockState(at);
                if (here.is(net.minecraft.tags.BlockTags.WOOL)) return 0;      // hung already
                if (here.is(Blocks.LANTERN)) continue;                          // a lamp over the door: the awning either side of it
                BlockState behind = level.getBlockState(wall);                 // a wall behind (a window's pane will do), not the roof
                if (planned.contains(at.asLong()) || !here.isAir() || behind.isAir() || behind.is(net.minecraft.tags.BlockTags.STAIRS)
                    || behind.is(net.minecraft.tags.BlockTags.SLABS)) { ok = false; break; }
                if (level.getBlockState(at.below()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) { ok = false; break; }
                if (v != null && !TownBlocks.mayPlace(level, v, at, TownBlocks.Why.DECOR)) { ok = false; break; }
                row.add(at.immutable());
            }
            if (ok && row.size() >= 2) cells = row;
        }
        if (cells == null || cells.isEmpty()) return 0;
        if (!free && !Crafts.take(level, v, x -> x.is(net.minecraft.tags.ItemTags.WOOL), cells.size())) return 0;
        for (BlockPos at : cells) {
            int k = Math.abs((at.getX() - door.getX()) + (at.getZ() - door.getZ())) % 2;
            net.minecraft.world.item.DyeColor c = k == 0 ? col : stripe;
            level.setBlock(at, BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(c.getName() + "_wool")).defaultBlockState(), 3);
        }
        return cells.size();
    }

    /** [biz30c] The trades with an awning over their door. */
    static final Set<String> AWNINGS = Set.of("shop", "store", "market", "cafe", "restaurant", "tavern", "inn", "guesthouse", "bakery", "brewery",
        "smithy", "smeltery", "foundry", "stable", "tailor", "apothecary", "fletcher", "bank");

    /** [biz30c] The trades whose fire shows: smoke off the roof. */
    static final Set<String> CHIMNEYS = Set.of("smithy", "smeltery", "foundry", "bakery", "brewery");

    /**
     * [biz30c] The forge's or the oven's smoke: a lit campfire on the top of its roof (the highest block of its drawing in the
     * back half of the building), where nobody walks and its smoke shows over the street. One at most; 1 if it went up.
     */
    static int chimney(ServerLevel level, @Nullable Villages.Village v, Ledger.Building b, int[] box, Set<Long> planned, boolean free) {
        if (!CHIMNEYS.contains(b.structure())) return 0;
        List<BlockPos> roof = new ArrayList<>();
        int top = Integer.MIN_VALUE;
        for (long l : planned) {
            BlockPos q = BlockPos.of(l);
            if (q.getX() < box[0] || q.getX() > box[1] || q.getZ() < box[2] || q.getZ() > box[3]) continue;
            roof.add(q);
            top = Math.max(top, q.getY());
        }
        if (roof.isEmpty()) return 0;
        // already smoking (a campfire on its roof)?
        for (BlockPos q : BlockPos.betweenClosed(box[0], top - 4, box[2], box[1], top + 2, box[3])) {
            if (level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.CampfireBlock) return 0;
        }
        // the highest sturdy block of its roof, open to the sky (a ridge of stairs or slabs passed over for the chimney stack under it)
        roof.sort((p1, p2) -> Integer.compare(p2.getY(), p1.getY()));
        BlockPos at = null;
        for (BlockPos q : roof) {
            if (q.getY() < top - 4) break;
            if (level.getBlockState(q).isFaceSturdy(level, q, Direction.UP) && level.getBlockState(q.above()).isAir() && level.canSeeSky(q.above())) { at = q.above(); break; }
        }
        if (at == null) return 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-3, -2, -3), at.offset(3, 2, 3))) {   // one fire to a chimney
            if (level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.CampfireBlock) return 0;
        }
        if (v != null && !TownBlocks.mayPlace(level, v, at, TownBlocks.Why.DECOR)) return 0;
        if (!free && !Crafts.take(level, v, x -> x.is(Items.CAMPFIRE) || x.is(net.minecraft.tags.ItemTags.LOGS), 1)) return 0;
        // a signal fire's tall smoke: it reads over the roofs from down the street
        level.setBlock(at, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.SIGNAL_FIRE, true), 2);
        return 1;
    }

    // ------------------------------------------------------------------ its wares on show

    /** What each trade's building shows on its casks and counters, the first the stores hold first. */
    static java.util.List<net.minecraft.world.item.Item> showsOf(String structure) {
        net.minecraft.world.item.Item[] it = switch (structure) {
            case "bakery" -> new net.minecraft.world.item.Item[]{ Items.BREAD, Items.CAKE, Items.COOKIE, Items.PUMPKIN_PIE, Items.WHEAT };
            case "smithy" -> new net.minecraft.world.item.Item[]{ Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SWORD, Items.IRON_SHOVEL,
                Items.SHEARS, Items.BUCKET, Items.STONE_PICKAXE, Items.IRON_INGOT };
            case "inn", "restaurant", "guesthouse" -> new net.minecraft.world.item.Item[]{ Items.BREAD, Items.COOKED_BEEF, Items.BAKED_POTATO,
                Items.RABBIT_STEW, Items.COOKED_CHICKEN, Items.PUMPKIN_PIE, Items.APPLE };
            case "tavern", "brewery" -> new net.minecraft.world.item.Item[]{ Items.HONEY_BOTTLE, Items.GLASS_BOTTLE, Items.SWEET_BERRIES, Items.APPLE };
            case "store" -> new net.minecraft.world.item.Item[]{ Items.TORCH, Items.LANTERN, Items.BUCKET, Items.SHEARS, Items.FLINT_AND_STEEL,
                Items.FISHING_ROD, Items.CANDLE, Items.STRING };
            case "market" -> new net.minecraft.world.item.Item[]{ Items.CARROT, Items.POTATO, Items.APPLE, Items.BREAD, Items.BEETROOT,
                Items.WHEAT, Items.MELON_SLICE, Items.PUMPKIN, Items.EGG };
            case "auction" -> new net.minecraft.world.item.Item[]{ Items.PAINTING, Items.BOOK, Items.GOLD_INGOT, Items.EMERALD, Items.CLOCK };
            case "bank" -> new net.minecraft.world.item.Item[]{ Items.GOLD_INGOT, Items.GOLD_NUGGET, Items.EMERALD, Items.IRON_INGOT };
            case "postoffice" -> new net.minecraft.world.item.Item[]{ Items.PAPER, Items.BOOK, Items.WRITABLE_BOOK, Items.MAP, Items.FEATHER };
            case "tradingpost" -> new net.minecraft.world.item.Item[]{ Items.EMERALD, Items.MAP, Items.COMPASS, Items.BOOK };
            case "workshop" -> new net.minecraft.world.item.Item[]{ Items.CHEST, Items.BARREL, Items.LADDER, Items.ITEM_FRAME, Items.SHEARS,
                Items.LEATHER_CHESTPLATE, Items.WHITE_BED };
            case "fletcher" -> new net.minecraft.world.item.Item[]{ Items.ARROW, Items.BOW, Items.FEATHER, Items.FLINT };
            case "maproom" -> new net.minecraft.world.item.Item[]{ Items.MAP, Items.COMPASS, Items.PAPER };
            case "apiary" -> new net.minecraft.world.item.Item[]{ Items.HONEY_BOTTLE, Items.HONEYCOMB, Items.CANDLE };
            case "divershed", "harbour" -> new net.minecraft.world.item.Item[]{ Items.COD, Items.SALMON, Items.COOKED_COD, Items.KELP };
            case "huntlodge" -> new net.minecraft.world.item.Item[]{ Items.LEATHER, Items.RABBIT_HIDE, Items.COOKED_RABBIT, Items.FEATHER };
            case "stable" -> new net.minecraft.world.item.Item[]{ Items.SADDLE, Items.LEAD, Items.WHEAT, Items.CARROT };
            case "smeltery", "foundry" -> new net.minecraft.world.item.Item[]{ Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.GLASS, Items.BRICK };
            case "wheelwright" -> new net.minecraft.world.item.Item[]{ Items.OAK_BOAT, Items.MINECART, Items.STICK };
            case "infirmary" -> new net.minecraft.world.item.Item[]{ Items.GLISTERING_MELON_SLICE, Items.GLASS_BOTTLE, Items.HONEY_BOTTLE };
            default -> new net.minecraft.world.item.Item[0];
        };
        return java.util.List.of(it);
    }

    /**
     * [biz30] Its wares out on its casks: a business's casks with nothing over them hold one of each of its trade's goods the
     * town's stores have (shown, not taken: a frame on the cask, as the café's and the shop's counters show theirs, Cafe.setOut).
     * Only what the stores hold; a trade with nothing in has bare casks, and a cask whose good is gone is cleared. The café and
     * the shop set out their own. How many changed.
     */
    public static int display(ServerLevel level, UUID village, Ledger.Building b) {
        String s = b.structure();
        if (s.equals("cafe") || s.equals("shop") || s.equals(Store.STRUCTURE) || !BuildingAudit.BUSINESSES.contains(s)) return 0;   // (theirs set out by Cafe, StoreFloor)
        java.util.List<net.minecraft.world.item.Item> shows = showsOf(s);
        if (shows.isEmpty()) return 0;
        java.util.List<BlockPos> casks = Cafe.counters(b);
        if (casks.isEmpty()) return 0;
        java.util.List<ItemStack> goods = new ArrayList<>();
        if (s.equals("tavern") || s.equals("brewery")) goods.addAll(Cafe.drinksInStores(level, village));
        for (net.minecraft.world.item.Item it : shows) {
            if (goods.size() >= casks.size()) break;
            if (Market.stock(level, village, x -> x.is(it)) > 0) goods.add(new ItemStack(it));
        }
        int changed = 0;
        for (int k = 0; k < casks.size(); k++) {
            BlockPos at = casks.get(k);
            if (!ServicePoints.isCounter(level.getBlockState(at))) continue;   // not built yet
            if (TownLife.frameOn(level, at.above(), k < goods.size() ? goods.get(k) : ItemStack.EMPTY, true)) changed++;
        }
        return changed;
    }

    /** The sign paid for and the hand at it, or free. */
    private static boolean pay(ServerLevel level, @Nullable Villages.Village v, BlockPos spot, boolean free) {
        if (free) return true;
        if (!TownJobs.atWork(level, v, "signs", spot, "putting up a name board")) return false;
        return Crafts.sign(level, v);
    }

    /** A board whose hand has been sent for it, per town: seen to at every visit till it is up (the round comes back to a
     *  building only once in many minutes, and the hand sent had long been called off by then). */
    private static final java.util.Map<UUID, Ledger.Building> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    public static void resetForTests() {
        PENDING.clear();
        TRIES.clear();
    }

    /** From the town's life (TownLife.tick, each visit): the board waiting on its hand. */
    public static void tick(ServerLevel level, Villages.Village v) {
        Ledger.Building b = PENDING.get(v.id());
        if (b == null) return;
        if (!level.isLoaded(b.anchor()) || !Ledger.buildings(v.id()).contains(b)) { PENDING.remove(v.id()); return; }
        int tries = TRIES.merge(v.id(), 1, Integer::sum);
        boolean up = board(level, v.id(), v.centre(), b, v, false);
        if (up || tries > 90) {                                              // (up; or a quarter of an hour on it: the round's again)
            LOG.info("[MCA-BUILD] [biz30] {}: the {}'s name board {}", Villages.name(v.id()), b.structure(), up ? "is up" : "found no place or no hand: left for the round");
            PENDING.remove(v.id());
            TRIES.remove(v.id());
        }
    }

    private static final java.util.Map<UUID, Integer> TRIES = new java.util.concurrent.ConcurrentHashMap<>();

    /** As {@link #board}, from the round: a board that waits on its hand is kept and seen to at each visit. */
    public static boolean round(ServerLevel level, UUID village, BlockPos heart, Ledger.Building b, Villages.Village v) {
        if (PENDING.containsKey(village)) return false;                      // one at a time: the one waiting first
        boolean up = board(level, village, heart, b, v, false);
        if (!up && BuildingAudit.BUSINESSES.contains(b.structure())) {
            PENDING.put(village, b);
            LOG.info("[MCA-BUILD] [biz30] {}: the {} at {} wants a name board: a hand sent for it", Villages.name(village), b.structure(), b.anchor().toShortString());
        }
        return up;
    }

    private static void write(ServerLevel level, BlockPos at, String[] lines) {
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) TownLife.write(sign, lines);
    }

    /** Which way is out of the building from a door on its footing's edge. */
    @Nullable
    private static Direction out(BlockPos door, int[] box) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = door.relative(d);
            if (n.getX() < box[0] || n.getX() > box[1] || n.getZ() < box[2] || n.getZ() > box[3]) return d;
        }
        return null;
    }
}
