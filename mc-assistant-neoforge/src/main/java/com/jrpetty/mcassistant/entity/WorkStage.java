package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.MilestoneBlock;
import com.jrpetty.mcassistant.block.MilestoneBlockEntity;
import com.jrpetty.mcassistant.block.PitPropBlock;
import com.jrpetty.mcassistant.block.RopeBlock;
import com.jrpetty.mcassistant.block.ShippingCrateBlock;
import com.jrpetty.mcassistant.block.WindowBoxBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.OreSackItem;
import com.jrpetty.mcassistant.item.WindowBoxItem;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [workitems] The tools of the mine, the woods and the roads set out for the camera (/village items work stage, the smoke
 * run's work_stage): each in use where it belongs, and all of them together on show. Built east of where it is run, on
 * ground made level for it, everything in it for nothing (a stage, not the town's work); the folk in it stand for their
 * pictures and live no life. Lines, with a VIEW for each picture: "VIEW name eye-x eye-y eye-z at-x at-y at-z".
 * <ul>
 * <li><b>work-showcase</b>: a wall of stone bricks with the eight in frames on it, the blocks set out in front, a rope
 *     let down its face, and a window box under its window.</li>
 * <li><b>work-mine</b>: a tunnel in a block of stone with gravel over it, propped, a miner in it with its pick and its
 *     ore sack.</li>
 * <li><b>work-rope</b>: a drop with a rope let down it and a cave dweller half way down.</li>
 * <li><b>work-saw</b>: a woodcutter with its felling saw by a tree it has just felled, its logs at the stump, and a
 *     standing tree.</li>
 * <li><b>work-thatch</b>: a cottage roofed in thatch, its window boxes in flower, its gardener at the door with another.</li>
 * <li><b>work-road</b>: a road with its milestone, lettered for the towns it runs between.</li>
 * <li><b>work-crates</b>: a courier with a crate in its hands by a stack of them.</li>
 * </ul>
 */
public final class WorkStage {

    private WorkStage() {}

    public static List<String> stage(ServerLevel level, @Nullable Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x0 = at.getX() + 6, z0 = at.getZ();
        // ---------------------------------------------------------------- the showcase wall
        int y = ground(level, x0 + 6, z0 + 3);
        flat(level, x0, z0 - 4, x0 + 14, z0 + 8, y);
        int wz = z0 + 6;
        for (int x = x0 + 1; x <= x0 + 13; x++) {
            for (int h = 0; h < 5; h++) level.setBlock(new BlockPos(x, y + h, wz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
        for (int x = x0 + 1; x <= x0 + 13; x++) level.setBlock(new BlockPos(x, y + 5, wz), Blocks.STONE_BRICK_SLAB.defaultBlockState(), 3);
        // A window in it, and a box under the window.
        level.setBlock(new BlockPos(x0 + 11, y + 2, wz), Blocks.GLASS_PANE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x0 + 11, y + 3, wz), Blocks.GLASS_PANE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x0 + 11, y + 1, wz - 1), WorkItems.WINDOW_BOX.get().defaultBlockState()
            .setValue(WindowBoxBlock.FACING, Direction.NORTH).setValue(WindowBoxBlock.FLOWER, WindowBoxBlock.Flower.CORNFLOWER), 3);
        // A rope let down its face from the top.
        RopeBlock.hang(level, new BlockPos(x0 + 2, y + 4, wz - 1), Direction.SOUTH, RopeBlock.LONGEST, WorkItems.ROPE.get());
        // The eight in frames along it.
        List<ItemStack> eight = new ArrayList<>(WorkItems.showcase());
        ItemStack fullSack = new ItemStack(WorkItems.ORE_SACK.get());
        OreSackItem.add(fullSack, new ItemStack(Items.RAW_IRON, 64));
        OreSackItem.add(fullSack, new ItemStack(Items.COAL, 40));
        eight.set(2, fullSack);
        for (int i = 0; i < eight.size(); i++) {
            BlockPos fp = new BlockPos(x0 + 3 + i, y + 3, wz - 1);
            if (i == 6) fp = new BlockPos(x0 + 3 + i, y + 3, wz - 1);
            if (!level.getBlockState(fp).isAir()) continue;
            ItemFrame frame = new ItemFrame(level, fp, Direction.NORTH);
            if (!frame.survives()) continue;
            frame.setItem(eight.get(i).copy());
            frame.setInvulnerable(true);
            level.addFreshEntity(frame);
        }
        // The blocks set out in a row before it.
        int rz = wz - 3;
        PitPropBlock.stand(level, new BlockPos(x0 + 3, y, rz), Direction.Axis.X, WorkItems.PIT_PROP.get());
        level.setBlock(new BlockPos(x0 + 5, y, rz), WorkItems.THATCH.get().defaultBlockState(), 3);
        level.setBlock(new BlockPos(x0 + 6, y, rz), WorkItems.THATCH_STAIRS.get().defaultBlockState()
            .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH), 3);
        level.setBlock(new BlockPos(x0 + 5, y + 1, rz), WorkItems.THATCH_SLAB.get().defaultBlockState(), 3);
        BlockPos stone = new BlockPos(x0 + 8, y, rz);
        level.setBlock(stone, WorkItems.MILESTONE.get().defaultBlockState().setValue(MilestoneBlock.FACING, Direction.NORTH), 3);
        letter(level, v, stone);
        BlockPos crate = new BlockPos(x0 + 10, y, rz);
        level.setBlock(crate, WorkItems.SHIPPING_CRATE.get().defaultBlockState(), 3);
        if (level.getBlockEntity(crate) instanceof net.minecraft.world.Container c) {
            c.setItem(0, new ItemStack(Items.BREAD, 32));
            c.setItem(4, new ItemStack(Items.IRON_INGOT, 16));
        }
        out.add("Set out at " + x0 + ", " + y + ", " + z0 + ": the eight in frames on a wall, the blocks before it, a rope down it, a box under its window.");
        out.add(view("work-showcase", x0 + 7.5, y + 2.6, wz - 9.5, x0 + 7.5, y + 1.8, wz));
        out.add(view("work-blocks", x0 + 6.5, y + 1.7, rz - 4.5, x0 + 6.5, y + 0.6, rz));

        // ---------------------------------------------------------------- the mine: a propped tunnel under gravel
        int mx = x0 + 22;
        int my = ground(level, mx + 4, z0);
        flat(level, mx - 3, z0 - 6, mx + 12, z0 + 6, my);
        // A block of stone, a tunnel two high through it, its roof a seam of gravel: held up by the props in it.
        for (int x = mx; x <= mx + 9; x++) {
            for (int z = z0 - 3; z <= z0 + 3; z++) {
                for (int h = 0; h < 5; h++) {
                    boolean seam = h == 2 && Math.abs(z - z0) <= 1;
                    level.setBlock(new BlockPos(x, my + h, z), (seam ? Blocks.GRAVEL : Blocks.STONE).defaultBlockState(), 2 | 16);
                }
            }
            level.setBlock(new BlockPos(x, my, z0), Blocks.AIR.defaultBlockState(), 2 | 16);
            level.setBlock(new BlockPos(x, my + 1, z0), Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        for (int x : new int[]{ mx + 1, mx + 5, mx + 9 }) PitPropBlock.stand(level, new BlockPos(x, my, z0), Direction.Axis.Z, WorkItems.PIT_PROP.get());
        level.setBlock(new BlockPos(mx + 7, my, z0), Blocks.TORCH.defaultBlockState(), 3);
        VillageFolkEntity miner = figure(level, new BlockPos(mx + 3, my, z0), Direction.WEST, StationTask.MINE, "Miner");
        if (miner != null) {
            miner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
            miner.setItemSlot(EquipmentSlot.OFFHAND, fullSack.copy());
        }
        out.add(view("work-mine", mx - 2.5, my + 1.4, z0 + 0.5, mx + 4.5, my + 1.2, z0 + 0.5));

        // ---------------------------------------------------------------- the rope: a drop, the rope down it, a climber on it
        int rx = x0 + 40;
        int ry = ground(level, rx, z0);
        flat(level, rx - 4, z0 - 6, rx + 8, z0 + 6, ry);
        for (int x = rx; x <= rx + 3; x++) {
            for (int z = z0 - 2; z <= z0 + 2; z++) {
                for (int h = 0; h < 9; h++) level.setBlock(new BlockPos(x, ry + h, z), Blocks.STONE.defaultBlockState(), 2 | 16);
            }
        }
        BlockPos topPiece = new BlockPos(rx - 1, ry + 8, z0);
        RopeBlock.hang(level, topPiece, Direction.EAST, RopeBlock.LONGEST, WorkItems.ROPE.get());
        VillageFolkEntity climber = figure(level, new BlockPos(rx - 1, ry + 4, z0), Direction.EAST, StationTask.CAVE, "Cave dweller");
        if (climber != null) {
            climber.setNoGravity(true);
            climber.setPos(rx - 1 + 0.5, ry + 4.2, z0 + 0.5);
            climber.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WorkItems.ROPE_COIL.get()));
            climber.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        }
        out.add(view("work-rope", rx - 7.5, ry + 5.0, z0 - 5.5, rx - 0.5, ry + 4.5, z0 + 0.5));

        // ---------------------------------------------------------------- the saw: a tree felled whole, and one standing
        int sx = x0 + 54;
        int sy = ground(level, sx, z0);
        flat(level, sx - 4, z0 - 6, sx + 10, z0 + 6, sy);
        tree(level, new BlockPos(sx + 5, sy, z0 + 2));
        BlockPos stump = new BlockPos(sx + 2, sy, z0 - 1);
        tree(level, stump);
        List<BlockPos> felled = WorkTools.tree(level, stump, null);
        WorkTools.fell(level, felled, stump, null, new ItemStack(WorkItems.FELLING_SAW.get()));
        level.setBlock(stump, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(stump, Blocks.OAK_SAPLING.defaultBlockState(), 3);
        VillageFolkEntity cutter = figure(level, new BlockPos(sx, sy, z0), Direction.EAST, StationTask.WOOD, "Woodcutter");
        if (cutter != null) cutter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WorkItems.FELLING_SAW.get()));
        out.add(view("work-saw", sx - 4.5, sy + 2.2, z0 - 4.5, sx + 2.5, sy + 1.6, z0 + 0.5));

        // ---------------------------------------------------------------- thatch and window boxes: a cottage
        int hx = x0 + 74;
        int hy = ground(level, hx, z0);
        flat(level, hx - 9, z0 - 12, hx + 9, z0 + 9, hy);
        BlockPos anchor = new BlockPos(hx, hy, z0);
        Showcase.Palette thatch = new Showcase.Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_LOG, WorkItems.THATCH_STAIRS.get(),
            WorkItems.THATCH_SLAB.get(), WorkItems.THATCH.get(), Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
            Blocks.SPRUCE_FENCE_GATE, Blocks.RED_BED, Blocks.RED_CARPET);
        BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(thatch));
        Ledger.Building b = new Ledger.Building("house", anchor, Direction.NORTH);
        WindowBoxBlock.Flower[] fl = { WindowBoxBlock.Flower.POPPY, WindowBoxBlock.Flower.CORNFLOWER, WindowBoxBlock.Flower.OXEYE_DAISY,
            WindowBoxBlock.Flower.PINK_TULIP };
        int k = 0;
        for (StreetFurniture.Box box : StreetFurniture.boxes(b)) {
            if (!level.getBlockState(box.ledge()).isAir()) continue;
            ItemStack one = WindowBoxItem.of(fl[k % fl.length]);
            if (WindowBoxes.put(level, box.ledge(), box.out(), one)) k++;
        }
        BlockPos door = anchor.relative(Direction.SOUTH, 5);
        VillageFolkEntity gardener = figure(level, door.relative(Direction.EAST, 2), Direction.SOUTH, StationTask.FARM, "Gardener");
        if (gardener != null) gardener.setItemSlot(EquipmentSlot.MAINHAND, WindowBoxItem.of(WindowBoxBlock.Flower.ALLIUM));
        out.add("A cottage thatched at " + anchor.toShortString() + ", with " + k + " window boxes under its windows.");
        out.add(view("work-thatch", hx + 10.5, hy + 6.5, z0 + 13.5, hx + 0.5, hy + 3.0, z0 + 0.5));
        out.add(view("work-boxes", hx + 4.5, hy + 2.2, z0 + 8.5, hx + 0.5, hy + 1.5, z0 + 3.5));

        // ---------------------------------------------------------------- the road and its milestone
        int px = x0 + 96;
        int py = ground(level, px, z0);
        flat(level, px - 8, z0 - 6, px + 8, z0 + 6, py);
        for (int x = px - 8; x <= px + 8; x++) {
            for (int dz = -1; dz <= 1; dz++) level.setBlock(new BlockPos(x, py - 1, z0 + dz), Blocks.DIRT_PATH.defaultBlockState(), 3);
        }
        BlockPos mile = new BlockPos(px, py, z0 - 2);
        level.setBlock(mile, WorkItems.MILESTONE.get().defaultBlockState().setValue(MilestoneBlock.FACING, Direction.SOUTH), 3);
        letter(level, v, mile);
        VillageFolkEntity crew = figure(level, new BlockPos(px + 2, py, z0), Direction.NORTH, StationTask.HAUL, "Road crew");
        if (crew != null) crew.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SHOVEL));
        out.add(view("work-road", px - 1.5, py + 1.6, z0 + 3.5, px + 0.5, py + 0.7, z0 - 1.5));

        // ---------------------------------------------------------------- the crates: a courier's load
        int cx = x0 + 110;
        int cy = ground(level, cx, z0);
        flat(level, cx - 5, z0 - 5, cx + 5, z0 + 5, cy);
        for (BlockPos p : new BlockPos[]{ new BlockPos(cx + 2, cy, z0 + 1), new BlockPos(cx + 3, cy, z0 + 1), new BlockPos(cx + 2, cy + 1, z0 + 1),
                new BlockPos(cx + 2, cy, z0 + 2) }) {
            level.setBlock(p, WorkItems.SHIPPING_CRATE.get().defaultBlockState(), 3);
        }
        VillageFolkEntity courier = figure(level, new BlockPos(cx, cy, z0), Direction.SOUTH, StationTask.HAUL, "Courier");
        if (courier != null) {
            ItemStack packed = new ItemStack(WorkItems.SHIPPING_CRATE_ITEM.get());
            ShippingCrateBlock.fill(packed, List.of(new ItemStack(Items.WHEAT, 64), new ItemStack(Items.OAK_LOG, 64)));
            courier.setItemSlot(EquipmentSlot.MAINHAND, packed);
        }
        out.add(view("work-crates", cx - 1.5, cy + 1.7, z0 + 4.5, cx + 1.5, cy + 1.0, z0 + 1.0));
        return out;
    }

    private static String view(String name, double ex, double ey, double ez, double ax, double ay, double az) {
        return String.format(java.util.Locale.ROOT, "VIEW %s %.1f %.1f %.1f %.1f %.1f %.1f", name, ex, ey, ez, ax, ay, az);
    }

    /** The milestone lettered for the town and, if there is one, another town. */
    private static void letter(ServerLevel level, @Nullable Villages.Village v, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof MilestoneBlockEntity be)) return;
        List<MilestoneBlockEntity.Way> ways = new ArrayList<>();
        if (v != null) {
            int far = (int) Math.round(Math.sqrt(v.centre().atY(0).distSqr(at.atY(0))));
            ways.add(new MilestoneBlockEntity.Way(Villages.name(v.id()), v.id(), v.centre().getX(), v.centre().getZ(), -1, 0, far));
            for (Villages.Village o : Villages.every()) {
                if (o.id().equals(v.id()) || !o.dim().equals(level.dimension())) continue;
                int f2 = (int) Math.round(Math.sqrt(o.centre().atY(0).distSqr(at.atY(0))));
                ways.add(new MilestoneBlockEntity.Way(Villages.name(o.id()), o.id(), o.centre().getX(), o.centre().getZ(), 1, 0, f2));
                break;
            }
        }
        if (ways.size() < 2) ways.add(new MilestoneBlockEntity.Way("Aldertor", null, at.getX() + 120, at.getZ(), 1, 0, 120));
        be.setWays(ways);
    }

    /** A folk that stands for its picture: its trade's clothes, its name, nothing else. */
    @Nullable
    private static VillageFolkEntity figure(ServerLevel level, BlockPos at, Direction facing, StationTask trade, String name) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        float yaw = facing.toYRot();
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(trade);
        f.rename(name);
        f.addTag("work_stage");
        level.addFreshEntity(f);
        return f;
    }

    /** A small oak the way the world grows one (its leaves its own). */
    private static void tree(ServerLevel level, BlockPos base) {
        for (int i = 0; i < 5; i++) level.setBlock(base.above(i), Blocks.OAK_LOG.defaultBlockState(), 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2 || dx == 0 && dz == 0 && dy < 5) continue;
                    BlockPos p = base.offset(dx, dy, dz);
                    if (level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false)
                            .setValue(LeavesBlock.DISTANCE, 1), 2 | 16);
                    }
                }
            }
        }
    }

    private static int ground(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Level grass at this height over the square, clear air above it. */
    private static void flat(ServerLevel level, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 16; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }
}
