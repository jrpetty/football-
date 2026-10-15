package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.FeedTroughBlock;
import com.jrpetty.mcassistant.block.FieldBlockEntity;
import com.jrpetty.mcassistant.block.FishTrapBlock;
import com.jrpetty.mcassistant.block.NestingBoxBlock;
import com.jrpetty.mcassistant.block.RainBarrelBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.BeeSmokerItem;
import com.jrpetty.mcassistant.item.FieldItems;
import com.jrpetty.mcassistant.item.SeedSatchelItem;
import com.jrpetty.mcassistant.item.WateringCanItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [fields] The pictures' scene (/village items fields stage): on clear ground where it is run, the eight tools of the
 * fields and the pens laid out on a wall of item frames, the four blocks set out in a row beneath, and each in use: a
 * farmer watering a field of wheat with the copper can, another at the ripe rows with the sickle and the seed satchel, a
 * pen with its nesting box (eggs on hay) and its feed trough (heaped), two hens and two sheep; a pond with two fish
 * traps, one with a catch in it; a rain barrel full of rain; a beekeeper smoking a hive. The can splashes and the smoker
 * puffs for a minute, so the pictures catch them. Each says where to look from (VIEW).
 */
public final class FieldsStage {

    private FieldsStage() {}

    /** What keeps going for the pictures: a spot, what it does there, and till when. */
    private static final Map<BlockPos, Object[]> LIVE = new ConcurrentHashMap<>();

    /** Every second (FieldTools' round): the staged can splashes, the staged smoker puffs. */
    static void tick(ServerLevel level) {
        if (LIVE.isEmpty()) return;
        long now = level.getGameTime();
        LIVE.entrySet().removeIf(e -> now > (Long) e.getValue()[1]);
        for (Map.Entry<BlockPos, Object[]> e : LIVE.entrySet()) {
            if (!level.isLoaded(e.getKey())) continue;
            if ("can".equals(e.getValue()[0])) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, e.getKey().getX() + 0.5, e.getKey().getY() + 1.2,
                    e.getKey().getZ() + 0.5, 24, 0.9, 0.1, 0.9, 0.15);
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.FALLING_WATER, e.getKey().getX() + 0.5, e.getKey().getY() + 1.9,
                    e.getKey().getZ() + 0.5, 10, 0.8, 0.2, 0.8, 0.0);
            } else {
                BeeSmokerItem.smokeHive(level, e.getKey());
            }
        }
    }

    private static int top(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static void flat(ServerLevel level, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = 1; dy <= 10; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                for (int dy = -3; dy < 0; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
    }

    /** A display hand of a trade, here, facing so, these in its hands. */
    private static VillageFolkEntity show(ServerLevel level, BlockPos at, float yaw, StationTask trade, ItemStack main, ItemStack off) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(trade);
        f.setItemSlot(EquipmentSlot.MAINHAND, main);
        f.setItemSlot(EquipmentSlot.OFFHAND, off);
        level.addFreshEntity(f);
        return f;
    }

    private static void animal(ServerLevel level, EntityType<? extends Animal> type, BlockPos at) {
        Animal a = type.create(level);
        if (a == null) return;
        a.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0.0F);
        a.setPersistenceRequired();
        level.addFreshEntity(a);
    }

    private static void fence(ServerLevel level, BlockPos p) {
        level.setBlock(p, Block.updateFromNeighbourShapes(Blocks.OAK_FENCE.defaultBlockState(), level, p), 3);
    }

    /** The scene, round here. Lines to say, with a VIEW line for each picture. */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        int y = top(level, x, z);
        flat(level, x - 13, z - 10, x + 13, z + 10, y);
        int g = y + 1;                                     // the ground's first air

        // ---- the showcase: a wall of frames, the blocks in a row beneath
        for (int dx = -5; dx <= 4; dx++) {
            for (int dy = 0; dy <= 3; dy++) level.setBlock(new BlockPos(x + dx, g + dy, z - 8), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x + dx, g + 4, z - 8), Blocks.SPRUCE_SLAB.defaultBlockState(), 3);
        }
        // The four tools along the top, the four blocks beneath them, two apart.
        List<Item> all = List.of(FieldItems.WATERING_CAN.get(), FieldItems.SEED_SATCHEL.get(), FieldItems.COPPER_SICKLE.get(),
            FieldItems.BEE_SMOKER.get(), FieldItems.NESTING_BOX_ITEM.get(), FieldItems.FEED_TROUGH_ITEM.get(),
            FieldItems.FISH_TRAP_ITEM.get(), FieldItems.RAIN_BARREL_ITEM.get());
        for (int i = 0; i < all.size(); i++) {
            BlockPos fp = new BlockPos(x - 3 + 2 * (i % 4), g + (i < 4 ? 2 : 1), z - 7);
            ItemFrame frame = new ItemFrame(level, fp, Direction.SOUTH);
            ItemStack shown = new ItemStack(all.get(i));
            if (shown.getItem() instanceof WateringCanItem) WateringCanItem.fill(shown);
            if (shown.is(FieldItems.SEED_SATCHEL.get())) SeedSatchelItem.add(shown, new ItemStack(Items.WHEAT_SEEDS, 64));
            frame.setItem(shown, false);
            level.addFreshEntity(frame);
        }
        BlockPos rowBox = new BlockPos(x - 3, g, z - 6), rowTrough = new BlockPos(x - 1, g, z - 6),
            rowTrap = new BlockPos(x + 1, g - 1, z - 6), rowBarrel = new BlockPos(x + 3, g, z - 6);
        box(level, rowBox, 7);
        trough(level, rowTrough, Direction.Axis.X, 30);
        level.setBlock(rowTrap, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(rowTrap.east(), Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(rowTrap.west(), Blocks.WATER.defaultBlockState(), 3);
        trap(level, rowTrap, 4);
        level.setBlock(rowBarrel, FieldItems.RAIN_BARREL.get().defaultBlockState().setValue(RainBarrelBlock.WATER, 4), 3);
        out.add("VIEW fields-1-showcase " + (x - 1) + " " + (g + 2) + " " + (z - 2) + " " + (x - 1) + " " + (g + 1) + " " + (z - 8));

        // ---- the field: wheat round a water hole, a farmer with the can, another with the sickle and the satchel
        int fx = x + 7, fz = z + 1;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos p = new BlockPos(fx + dx, y, fz + dz);
                if (dx == 0 && dz == 0) {
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
                    continue;
                }
                level.setBlock(p, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, dz < 0 ? 0 : FarmBlock.MAX_MOISTURE), 3);
                int age = dx >= 1 ? CropBlock.MAX_AGE : Math.max(1, 3 + dz);
                level.setBlock(p.above(), ((CropBlock) Blocks.WHEAT).getStateForAge(age), 3);
            }
        }
        ItemStack can = new ItemStack(FieldItems.WATERING_CAN.get());
        WateringCanItem.setWater(can, 11);
        show(level, new BlockPos(fx - 4, g, fz - 2), -90.0F, StationTask.FARM, new ItemStack(Items.STONE_HOE), can);
        WateringCanItem.pour(level, new BlockPos(fx - 2, y, fz - 2));
        LIVE.put(new BlockPos(fx - 2, y, fz - 2), new Object[]{ "can", level.getGameTime() + 1200L });
        ItemStack bag = new ItemStack(FieldItems.SEED_SATCHEL.get());
        SeedSatchelItem.add(bag, new ItemStack(Items.WHEAT_SEEDS, 64));
        SeedSatchelItem.add(bag, new ItemStack(Items.CARROT, 40));
        show(level, new BlockPos(fx + 4, g, fz + 1), 90.0F, StationTask.FARM, new ItemStack(FieldItems.COPPER_SICKLE.get()), bag);
        level.setBlock(new BlockPos(fx - 4, g, fz + 3), FieldItems.RAIN_BARREL.get().defaultBlockState().setValue(RainBarrelBlock.WATER, 3), 3);
        out.add("VIEW fields-2-watering " + (fx - 7) + " " + (g + 2) + " " + (fz - 5) + " " + fx + " " + g + " " + fz);
        out.add("VIEW fields-3-reaping " + (fx + 7) + " " + (g + 2) + " " + (fz + 4) + " " + (fx + 2) + " " + g + " " + fz);

        // ---- the pen: a box with eggs on hay, a heaped trough, two hens and two sheep, the rancher
        int px = x - 9, pz = z + 1;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue;
                if (dz == 3 && dx == 0) continue;                    // the way in
                fence(level, new BlockPos(px + dx, g, pz + dz));
            }
        }
        box(level, new BlockPos(px - 2, g, pz - 2), 9);
        trough(level, new BlockPos(px + 1, g, pz - 2), Direction.Axis.X, 30);
        animal(level, EntityType.CHICKEN, new BlockPos(px - 1, g, pz - 1));
        animal(level, EntityType.CHICKEN, new BlockPos(px - 2, g, pz));
        animal(level, EntityType.SHEEP, new BlockPos(px + 1, g, pz));
        animal(level, EntityType.SHEEP, new BlockPos(px + 2, g, pz + 1));
        show(level, new BlockPos(px, g, pz + 4), 180.0F, StationTask.RANCH, new ItemStack(Items.WHEAT), new ItemStack(Items.EGG, 3));
        out.add("VIEW fields-4-pen " + px + " " + (g + 4) + " " + (pz + 7) + " " + px + " " + g + " " + (pz - 1));

        // ---- the pond: two traps, one with a catch, and the fisher at its edge
        int wx = x, wz = z + 6;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                level.setBlock(new BlockPos(wx + dx, y, wz + dz), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(new BlockPos(wx + dx, y - 1, wz + dz), Blocks.WATER.defaultBlockState(), 3);
            }
        }
        trap(level, new BlockPos(wx - 1, y, wz), 5);
        trap(level, new BlockPos(wx + 1, y, wz + 1), 1);
        show(level, new BlockPos(wx, g, wz - 2), 0.0F, StationTask.FISH, new ItemStack(Items.FISHING_ROD), new ItemStack(Items.COD));
        out.add("VIEW fields-5-traps " + (wx + 4) + " " + (g + 3) + " " + (wz - 4) + " " + wx + " " + y + " " + (wz + 1));

        // ---- the bees: a hive, full, a beekeeper with its smoker puffing
        BlockPos hive = new BlockPos(x + 9, g, z + 7);
        level.setBlock(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.FACING, Direction.WEST)
            .setValue(BeehiveBlock.HONEY_LEVEL, BeehiveBlock.MAX_HONEY_LEVELS), 3);
        for (int i = 0; i < 3; i++) level.setBlock(new BlockPos(x + 11, g, z + 5 + i), Blocks.POPPY.defaultBlockState(), 3);
        show(level, new BlockPos(x + 7, g, z + 7), -90.0F, StationTask.BEEKEEP, new ItemStack(Items.SHEARS), new ItemStack(FieldItems.BEE_SMOKER.get()));
        BeeSmokerItem.smokeHive(level, hive);
        LIVE.put(hive, new Object[]{ "smoke", level.getGameTime() + 1200L });
        out.add("VIEW fields-6-smoker " + (x + 5) + " " + (g + 2) + " " + (z + 4) + " " + (x + 8) + " " + g + " " + (z + 7));
        out.add("staged in " + Villages.name(v.id()) + " round " + x + " " + g + " " + z);
        return out;
    }

    private static void box(ServerLevel level, BlockPos p, int eggs) {
        level.setBlock(p, FieldItems.NESTING_BOX.get().defaultBlockState().setValue(NestingBoxBlock.FACING, Direction.SOUTH), 3);
        if (level.getBlockEntity(p) instanceof FieldBlockEntity be) {
            be.insert(new ItemStack(Items.EGG, eggs));
            be.setHay(NestingBoxBlock.HAY_OF_WHEAT);
        }
        if (level.getBlockState(p).getBlock() instanceof NestingBoxBlock b) b.refresh(level, p);
    }

    private static void trough(ServerLevel level, BlockPos p, Direction.Axis axis, int feed) {
        level.setBlock(p, FieldItems.FEED_TROUGH.get().defaultBlockState().setValue(FeedTroughBlock.AXIS, axis), 3);
        FeedTroughBlock.fill(level, p, new ItemStack(Items.WHEAT, Math.min(16, feed)));
        if (feed > 16) FeedTroughBlock.fill(level, p, new ItemStack(Items.WHEAT_SEEDS, Math.min(16, feed - 16)));
    }

    private static void trap(ServerLevel level, BlockPos p, int fish) {
        BlockState st = FieldItems.FISH_TRAP.get().defaultBlockState().setValue(FishTrapBlock.WATERLOGGED, true).setValue(FishTrapBlock.FACING, Direction.EAST);
        level.setBlock(p, st, 3);
        if (level.getBlockEntity(p) instanceof FieldBlockEntity be) {
            if (fish > 0) be.insert(new ItemStack(Items.COD, Math.max(1, fish - 1)));
            if (fish > 1) be.insert(new ItemStack(Items.SALMON, 1));
        }
        if (level.getBlockState(p).getBlock() instanceof FishTrapBlock b) b.refresh(level, p);
    }
}
