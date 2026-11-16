package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [anim] The smoke's stage for the folk's animations (/village anim stage): every act there is, a folk doing each, in
 * rows of eight on a stone floor in clear air, each at the block its work is done at (the smith at an anvil, the cook at
 * a smoker, the tailor at a loom, the miner at a rock face), the rancher with a cow and a sheep; then the life of the
 * town: a pair shaking hands and a pair talking face to face, a table of folk eating, a bench of folk sat down, a ring of
 * dancers, a folk climbing a ladder, a mourner at a grave. Each stood-up folk is a showcase (it lives no life; it goes with
 * /kill @e[tag=folk_lineup]) told to keep at its act. Answers VIEW lines for the smoke's camera, a row a picture.
 */
public final class AnimStage {

    private AnimStage() {}

    /** Each act with the trade that does it and the block it does it at (null: none). */
    private record Part(Act act, StationTask trade, @Nullable Block block) {}

    private static final Part[] WORK = {
        new Part(Act.HAMMER, StationTask.SMITH, Blocks.ANVIL), new Part(Act.CHOP, StationTask.WOOD, Blocks.OAK_LOG),
        new Part(Act.PICK, StationTask.MINE, Blocks.STONE), new Part(Act.DIG, StationTask.MINE, Blocks.DIRT),
        new Part(Act.HOE, StationTask.FARM, Blocks.FARMLAND), new Part(Act.PLANT, StationTask.FARM, Blocks.FARMLAND),
        new Part(Act.HARVEST, StationTask.FARM, Blocks.WHEAT), new Part(Act.FISH, StationTask.FISH, Blocks.WATER),
        new Part(Act.STIR_POT, StationTask.COOK, Blocks.SMOKER), new Part(Act.KNEAD, StationTask.COOK, Blocks.CRAFTING_TABLE),
        new Part(Act.BREW, StationTask.BREW, Blocks.WATER_CAULDRON), new Part(Act.SEW, StationTask.TAILOR, Blocks.LOOM),
        new Part(Act.SAW, StationTask.SHOP, Blocks.CRAFTING_TABLE), new Part(Act.CHISEL, StationTask.SHOP, Blocks.STONECUTTER),
        new Part(Act.BUILD, StationTask.HAUL, Blocks.BRICKS), new Part(Act.SWEEP, StationTask.HAUL, null),
        new Part(Act.CARRY, StationTask.HAUL, null), new Part(Act.SACK, StationTask.HAUL, null),
        new Part(Act.HAND_OVER, StationTask.SHOP, Blocks.SPRUCE_PLANKS), new Part(Act.COUNT_COINS, StationTask.BANK, Blocks.SPRUCE_PLANKS),
        new Part(Act.WRITE, StationTask.CARTOGRAPHER, Blocks.LECTERN), new Part(Act.TEACH, StationTask.ENCHANT, null),
        new Part(Act.TEND, StationTask.HEAL, Blocks.WHITE_BED), new Part(Act.PREACH, StationTask.NONE, null),
        new Part(Act.WATCH, StationTask.GUARD, null), new Part(Act.MILK, StationTask.RANCH, null),
        new Part(Act.SHEAR, StationTask.RANCH, null), new Part(Act.LEAD, StationTask.RANCH, null),
        new Part(Act.FEED, StationTask.RANCH, null), new Part(Act.SMOKE_HIVE, StationTask.BEEKEEP, Blocks.BEEHIVE),
        new Part(Act.FLETCH, StationTask.FLETCHER, Blocks.FLETCHING_TABLE), new Part(Act.ENCHANT, StationTask.ENCHANT, Blocks.ENCHANTING_TABLE),
        new Part(Act.STOKE, StationTask.SMELT, Blocks.FURNACE), new Part(Act.SPYGLASS, StationTask.SCOUT, null),
        new Part(Act.POUR, StationTask.COOK, Blocks.SPRUCE_PLANKS), new Part(Act.SERVE, StationTask.COOK, null),
        new Part(Act.MAP, StationTask.CARTOGRAPHER, Blocks.CARTOGRAPHY_TABLE), new Part(Act.TINKER, StationTask.REDSTONE, Blocks.REPEATER),
        new Part(Act.FUSE, StationTask.FIREWORKS, null), new Part(Act.STOCK, StationTask.STORE, Blocks.BARREL),
        new Part(Act.WATER, StationTask.FARM, Blocks.FARMLAND), new Part(Act.REPAIR, StationTask.GOLEMS, null),
        new Part(Act.WIPE, StationTask.COOK, Blocks.SPRUCE_PLANKS),
    };

    /** The rows: so many to a picture. */
    private static final int PER_ROW = 6;
    private static final double GAP = 3.0;
    private static final int ROW = 8;

    /** The stage where {@code at} is (its floor's top): VIEW lines, a row a picture, and what was stood up. */
    public static List<String> stage(ServerLevel level, BlockPos at) {
        List<Part> all = new ArrayList<>(List.of(WORK));
        StationTask[] faces = {StationTask.FARM, StationTask.SHOP, StationTask.TAILOR, StationTask.COOK, StationTask.STORE,
            StationTask.BREW, StationTask.SMITH, StationTask.FISH};
        int k = 0;
        for (Act a : Act.values()) {
            if (a == Act.NONE || a.work()) continue;
            all.add(new Part(a, a == Act.BLOCK || a == Act.THRUST || a == Act.SALUTE ? StationTask.GUARD
                : a == Act.ROW ? StationTask.FERRY : faces[k++ % faces.length], null));
        }
        int rows = (all.size() + PER_ROW - 1) / PER_ROW;
        int x0 = at.getX(), y = at.getY(), z0 = at.getZ();
        int width = (int) Math.ceil(PER_ROW * GAP) + 4, depth = rows * ROW + 18;
        for (int dx = -3; dx <= width; dx++) {
            for (int dz = -3; dz <= depth; dz++) {
                level.setBlock(new BlockPos(x0 + dx, y - 1, z0 + dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int dy = 0; dy <= 6; dy++) level.setBlock(new BlockPos(x0 + dx, y + dy, z0 + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<String> out = new ArrayList<>();
        int stood = 0;
        for (int i = 0; i < all.size(); i++) {
            Part p = all.get(i);
            int row = i / PER_ROW, col = i % PER_ROW;
            double fx = x0 + 1.5 + col * GAP, fz = z0 + 1.5 + row * ROW;
            VillageFolkEntity f = standUp(level, fx, y, fz, p, i);
            if (f != null) stood++;
        }
        for (int r = 0; r < rows; r++) {
            double mid = x0 + 2.0 + (PER_ROW - 1) * GAP / 2.0, fz = z0 + 1.5 + r * ROW;
            StringBuilder names = new StringBuilder();
            for (int i = r * PER_ROW; i < Math.min(all.size(), (r + 1) * PER_ROW); i++) names.append(i > r * PER_ROW ? "," : "").append(all.get(i).act().word());
            out.add(String.format(java.util.Locale.ROOT, "VIEW row%d %.1f %d %.1f %.1f %d %.1f %s", r, mid, y, fz + 7.0, mid, y + 1, fz, names));
            String[] each = names.toString().split(",");
            for (int h = 0; h < 2; h++) {
                double cx = x0 + 2.0 + (h * 3 + 1) * GAP;
                String some = String.join(",", java.util.Arrays.copyOfRange(each, Math.min(each.length, h * 3), Math.min(each.length, h * 3 + 3)));
                if (!some.isEmpty()) out.add(String.format(java.util.Locale.ROOT, "VIEW row%d%s %.1f %d %.1f %.1f %d %.1f %s", r, h == 0 ? "a" : "b", cx, y, fz + 4.4, cx, y + 1, fz, some));
            }
        }
        int lz = z0 + 1 + rows * ROW + 4;
        out.addAll(life(level, x0 + 2, y, lz));
        out.add(0, "STAGE anim " + x0 + " " + y + " " + z0 + ": " + stood + " folk, " + rows + " rows of " + PER_ROW);
        return out;
    }

    /** The life of the town, staged: a handshake, a talk, a meal, a bench, a dance, a ladder, a grave. */
    private static List<String> life(ServerLevel level, int x, int y, int z) {
        List<String> out = new ArrayList<>();
        // A handshake and a talk, two pairs face to face.
        pair(level, x, y, z, Act.HANDSHAKE);
        pair(level, x + 4, y, z, Act.TALK);
        // A table and its diners.
        level.setBlock(new BlockPos(x + 9, y, z), Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x + 9, y + 1, z), Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3);
        sitter(level, x + 8, y, z, 270.0F, Act.EAT);
        sitter(level, x + 10, y, z, 90.0F, Act.DRINK);
        // A bench of three, sat down.
        for (int i = 0; i < 3; i++) {
            BlockPos seat = new BlockPos(x + 13 + i, y, z);
            level.setBlock(seat, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH), 3);
            VillageFolkEntity f = sitter(level, x + 13 + i, y, z, 0.0F, i == 1 ? Act.TALK : Act.SIT);
            if (f != null && i == 2) f.setChild(true);
        }
        out.add("VIEW life-pairs " + (x + 4) + " " + (y + 1) + " " + (z + 7) + " " + (x + 4) + " " + (y + 1) + " " + z + " handshake,talk");
        out.add("VIEW life-sit " + (x + 12) + " " + (y + 1) + " " + (z + 6) + " " + (x + 12) + " " + (y + 1) + " " + z + " eat,drink,sit");
        // A ring of dancers.
        int dx = x + 4, dz = z + 8;
        for (int i = 0; i < 5; i++) {
            double ang = i * Math.PI * 2 / 5;
            VillageFolkEntity f = one(level, dx + Math.cos(ang) * 1.8, y, dz + Math.sin(ang) * 1.8, StationTask.NONE, Act.DANCE, i);
            if (f != null) turn(f, (float) Math.toDegrees(ang) + 90.0F);
        }
        out.add("VIEW life-dance " + dx + " " + (y + 1) + " " + (dz + 5) + " " + dx + " " + (y + 1) + " " + dz + " dance");
        // A ladder up a wall, and a folk on it.
        int lx = x + 12, lz = z + 9;
        for (int h = 0; h < 5; h++) {
            level.setBlock(new BlockPos(lx, y + h, lz - 1), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
            level.setBlock(new BlockPos(lx, y + h, lz), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH), 3);
        }
        VillageFolkEntity climber = one(level, lx + 0.5, y + 1.6, lz + 0.62, StationTask.HAUL, Act.CLIMB, 3);
        if (climber != null) { turn(climber, 180.0F); climber.setNoGravity(true); }
        // A grave, and its mourner.
        int gx = x + 17, gz = z + 9;
        level.setBlock(new BlockPos(gx, y - 1, gz), Blocks.COARSE_DIRT.defaultBlockState(), 3);
        level.setBlock(new BlockPos(gx, y, gz - 1), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(new BlockPos(gx, y + 1, gz - 1), Blocks.POPPY.defaultBlockState(), 2);
        VillageFolkEntity mourner = one(level, gx + 0.5, y, gz + 1.2, StationTask.NONE, Act.MOURN, 4);
        if (mourner != null) turn(mourner, 180.0F);
        out.add("VIEW life-ladder " + (lx + 2) + " " + (y + 1) + " " + (lz + 7) + " " + (lx + 2) + " " + (y + 1) + " " + lz + " climb,mourn");
        return out;
    }

    private static void pair(ServerLevel level, int x, int y, int z, Act act) {
        VillageFolkEntity a = one(level, x + 0.5, y, z + 0.5, StationTask.SHOP, act, 1);
        VillageFolkEntity b = one(level, x + 1.6, y, z + 0.5, StationTask.FARM, act, 2);
        if (a != null) turn(a, 270.0F);
        if (b != null) turn(b, 90.0F);
    }

    @Nullable
    private static VillageFolkEntity sitter(ServerLevel level, int x, int y, int z, float yaw, Act act) {
        VillageFolkEntity f = one(level, x + 0.5, y + 0.5, z + 0.5, StationTask.NONE, act, x + z);
        if (f == null) return null;
        turn(f, yaw);
        f.setPose(Pose.SITTING);
        return f;
    }

    private static void turn(VillageFolkEntity f, float yaw) {
        f.setYRot(yaw);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
    }

    @Nullable
    private static VillageFolkEntity standUp(ServerLevel level, double x, int y, double z, Part p, int i) {
        // The block its work is at, a step in front of it (it faces east: the camera, to the south, sees it side on).
        BlockPos front = BlockPos.containing(x + 1.0, y, z);
        if (p.block() != null) {
            BlockState st = p.block().defaultBlockState();
            if (p.block() == Blocks.FARMLAND || p.block() == Blocks.WATER) {
                level.setBlock(front.below(), st, 2);
            } else if (p.block() == Blocks.WHEAT) {
                level.setBlock(front.below(), Blocks.FARMLAND.defaultBlockState(), 2);
                level.setBlock(front, ((net.minecraft.world.level.block.CropBlock) Blocks.WHEAT).getStateForAge(7), 2);
            } else if (p.block() == Blocks.WHITE_BED) {
                level.setBlock(front, Blocks.WHITE_CARPET.defaultBlockState(), 2);
            } else {
                level.setBlock(front, st, 2);
            }
        }
        if (p.act() == Act.SIT) level.setBlock(BlockPos.containing(x, y, z), Blocks.SPRUCE_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.WEST), 2);
        VillageFolkEntity f = one(level, x, p.act() == Act.SIT ? y + 0.5 : y, z, p.trade(), p.act(), i);
        if (f == null) return null;
        turn(f, -90.0F);
        if (p.act() == Act.SIT) f.setPose(Pose.SITTING);
        if (p.act() == Act.SLEEP) {
            // Laid down in it (the pose the game lays a sleeper in), not put to bed: a folk put to bed by day gets up for work.
            BlockPos head = BlockPos.containing(x, y, z), foot = head.east();
            BlockState bed = Blocks.BLUE_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.WEST);
            level.setBlock(foot, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2);
            level.setBlock(head, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 2);
            f.moveTo(head.getX() + 1.6, head.getY() + 0.6875, head.getZ() + 0.5, 90.0F, 0.0F);   // the body laid back from its feet to the pillow
            turn(f, 90.0F);                                           // lying along the bed, its head on the pillow
            f.setPose(Pose.SLEEPING);
        }
        if (p.act() == Act.RIDE || p.act() == Act.ROW) {
            net.minecraft.world.entity.Entity mount = p.act() == Act.RIDE ? EntityType.HORSE.create(level) : EntityType.BOAT.create(level);
            if (mount != null) {
                mount.moveTo(x, y, z, -90.0F, 0.0F);
                mount.addTag("folk_lineup");
                if (mount instanceof Mob m) { m.setNoAi(true); m.setInvulnerable(true); }
                level.addFreshEntity(mount);
                f.startRiding(mount, true);
            }
        }
        if (p.act() == Act.MILK || p.act() == Act.LEAD) animal(level, EntityType.COW, x + (p.act() == Act.LEAD ? -1.8 : 1.3), y, z - 0.4);
        if (p.act() == Act.SHEAR || p.act() == Act.FEED) animal(level, p.act() == Act.SHEAR ? EntityType.SHEEP : EntityType.CHICKEN, x + 1.3, y, z);
        if (p.act() == Act.REPAIR) animal(level, EntityType.IRON_GOLEM, x + 1.7, y, z);
        return f;
    }

    private static void animal(ServerLevel level, EntityType<? extends Mob> type, double x, int y, double z) {
        Mob m = type.create(level);
        if (m == null) return;
        m.moveTo(x, y, z, 90.0F, 0.0F);
        m.setNoAi(true);
        m.setInvulnerable(true);
        m.addTag("folk_lineup");
        level.addFreshEntity(m);
    }

    /** A folk stood up facing the player, glad to see it (the player's emotes answered, for the pictures). */
    @Nullable
    public static VillageFolkEntity friend(ServerLevel level, double x, double y, double z, net.minecraft.world.entity.player.Player p) {
        VillageFolkEntity f = one(level, x, y, z, StationTask.SHOP, Act.NONE, 7);
        if (f == null) return null;
        FolkAction.stop(f);
        f.rename("friend");
        f.ensurePersona();
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 70);
        double dx = p.getX() - x, dz = p.getZ() - z;
        turn(f, (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F);
        return f;
    }

    /** One folk, stood up, told to keep at its act. */
    @Nullable
    static VillageFolkEntity one(ServerLevel level, double x, double y, double z, StationTask trade, Act act, int seed) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        f.moveTo(x, y, z, 0.0F, 0.0F);
        f.makeShowcase(trade);
        f.rename(act.word());                                     // its act over its head, for whoever reads the pictures
        f.addTag("folk_lineup");
        f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);   // its hands left to the act's own tools (client/FolkActionLayer)
        f.setItemSlot(EquipmentSlot.OFFHAND, act == Act.BLOCK ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
        if (act == Act.THRUST || act == Act.BLOCK || act == Act.WATCH) f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        if (!level.addFreshEntity(f)) return null;
        Individual.ensure(f);
        Individual.refreshLook(f);
        if (act != Act.NONE) FolkAction.play(f, act, Integer.MAX_VALUE / 4);
        return f;
    }
}
