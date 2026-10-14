package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [roles-d] "/village outdoor": the outdoor trades' places and doings in a town, in words (the lodge and its smoker, the
 * barn, the scouts' desk, the hunting parties out, who the rain keeps in, the hives' winter); and "/village outdoor
 * stage", the same set out for the pictures (tools/realworld/smoke.py roles_d_stage) from palettes and stand-ins, not the
 * stores: the outdoor trades in a row in their own clothes, the hunter's lodge with the hunter at its smoker, the barn by a
 * fenced pen of sheep with the rancher, and a player's golem stood up beside its keeper. Returns "VIEW name x y z ax ay az"
 * lines: where a camera stands, and what it looks at.
 */
public final class OutdoorStage {

    private OutdoorStage() {}

    /** The row: each outdoor and resource trade in its own clothes (OutfitIndex), and a tool it is known by. */
    private static final StationTask[] ROW = { StationTask.FARM, StationTask.WOOD, StationTask.FISH, StationTask.HUNT, StationTask.RANCH,
        StationTask.SCOUT, StationTask.FERRY, StationTask.GOLEMS, StationTask.REDSTONE, StationTask.EMERALD, StationTask.DIVER };

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("outdoor")
            .then(Commands.literal("stage").requires(s -> s.hasPermission(2)).executes(ctx -> {
                List<String> lines = stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            }))
            .executes(ctx -> {
                ServerLevel level = ctx.getSource().getLevel();
                Villages.Village v = PlayerServices.townAt(level, BlockPos.containing(ctx.getSource().getPosition()));
                if (v == null) {
                    ctx.getSource().sendFailure(Component.literal("There's no village here."));
                    return 0;
                }
                List<String> lines = lines(level, v.id());
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            });
    }

    /** The outdoor trades in a town, in words. */
    public static List<String> lines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>(OutdoorBases.lines(level, village));
        out.addAll(OutdoorTalk.parties(level, village));
        List<String> wet = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && OutdoorWeather.keepsIn(f)) wet.add(f.displayNameCap() + " (" + f.stationTask().label + ")");
        }
        if (!wet.isEmpty()) out.add("In out of the rain: " + String.join(", ", wet));
        if (OutdoorWeather.winter(level, village)) out.add("Winter: the beekeepers leave the hives' honey for the bees");
        return out;
    }

    // ------------------------------------------------------------------ the pictures

    public static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 1. The row of the outdoor trades, facing south.
        Showcase.stage(level, x - 14, x + 14, z - 4, z + 10, y);
        for (int i = 0; i < ROW.length; i++) {
            VillageFolkEntity f = standIn(level, new BlockPos(x - 10 + i * 2, y, z), ROW[i], ROW[i].title, 0.0F);
            if (f != null) f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(tool(ROW[i])));
        }
        out.add("VIEW trades " + x + " " + (y + 1) + " " + (z + 10) + " " + x + " " + (y + 1) + " " + z);
        // 2. The hunter's lodge, its door to the south, the hunter at the smoker and its drying fire lit.
        int lx = x + 30;
        Showcase.stage(level, lx - 8, lx + 8, z - 8, z + 12, y);
        BlockPos lodge = new BlockPos(lx, y, z);
        BuildGoal.stamp(level, OutdoorBases.LODGE, lodge, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        BlockPos smoker = part(OutdoorBases.LODGE, lodge, BuildGoal.Part.SMOKER);
        if (smoker != null) {
            BlockState sm = level.getBlockState(smoker);
            if (sm.hasProperty(AbstractFurnaceBlock.LIT)) level.setBlock(smoker, sm.setValue(AbstractFurnaceBlock.LIT, true), 3);
            VillageFolkEntity hunter = standIn(level, smoker.south(), StationTask.HUNT, "Hunter", 180.0F);
            if (hunter != null) {
                hunter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BEEF));
                hunter.getLookControl().setLookAt(smoker.getX() + 0.5, smoker.getY() + 0.5, smoker.getZ() + 0.5);
            }
        }
        out.add("VIEW lodge-front " + (lx + 5) + " " + (y + 2) + " " + (z + 11) + " " + lx + " " + (y + 1) + " " + z);
        if (smoker != null) out.add("VIEW lodge-inside " + lx + " " + (y + 1) + " " + (z + 1) + " " + smoker.getX() + " " + smoker.getY() + " " + smoker.getZ());
        // 3. The barn by a fenced pen of sheep, the rancher at the gate.
        int bx = x - 32;
        Showcase.stage(level, bx - 9, bx + 16, z - 8, z + 14, y);
        BlockPos barn = new BlockPos(bx, y, z);
        BuildGoal.stamp(level, OutdoorBases.BARN, barn, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        int px = bx + 10;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue;
                level.setBlock(new BlockPos(px + dx, y, z + dz), dz == 3 && dx == 0 ? Blocks.OAK_FENCE_GATE.defaultBlockState()
                    : Blocks.OAK_FENCE.defaultBlockState(), 3);
            }
        }
        DyeColor[] wools = { DyeColor.WHITE, DyeColor.WHITE, DyeColor.BROWN, DyeColor.LIGHT_GRAY };
        for (int i = 0; i < wools.length; i++) {
            Sheep sh = EntityType.SHEEP.create(level);
            if (sh == null) continue;
            sh.moveTo(px - 1.5 + i, y, z - 0.5 + (i % 2), 90.0F * i, 0.0F);
            sh.setColor(wools[i]);
            sh.setNoAi(true);
            sh.addTag("folk_lineup");
            level.addFreshEntity(sh);
        }
        VillageFolkEntity rancher = standIn(level, new BlockPos(px, y, z + 4), StationTask.RANCH, "Rancher", 180.0F);
        if (rancher != null) rancher.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SHEARS));
        out.add("VIEW barn " + (bx + 4) + " " + (y + 3) + " " + (z + 14) + " " + (bx + 4) + " " + (y + 2) + " " + z);
        // 4. A player's iron golem stood up the game's way beside its keeper.
        int gz = z + 28;
        Showcase.stage(level, x - 8, x + 8, gz - 5, gz + 8, y);
        BlockPos base = new BlockPos(x, y, gz);
        for (BlockPos q : new BlockPos[]{ base, base.above(), base.above().east(), base.above().west() }) level.setBlock(q, Blocks.IRON_BLOCK.defaultBlockState(), 3);
        level.setBlock(base.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, Direction.SOUTH), 3);
        for (IronGolem g : level.getEntitiesOfClass(IronGolem.class, new AABB(base).inflate(3))) {
            g.addTag("folk_lineup");
            g.addTag(OutdoorTalk.PLAYERS_GOLEM);
            g.setCustomName(Component.literal("A player's golem"));
            g.setNoAi(true);
        }
        VillageFolkEntity keeper = standIn(level, new BlockPos(x + 3, y, gz + 1), StationTask.GOLEMS, "Golem keeper", 200.0F);
        if (keeper != null) keeper.getLookControl().setLookAt(x + 0.5, y + 2.0, gz + 0.5);
        out.add("VIEW golem " + (x + 2) + " " + (y + 2) + " " + (gz + 8) + " " + x + " " + (y + 1) + " " + gz);
        return out;
    }

    private static net.minecraft.world.item.Item tool(StationTask t) {
        return switch (t) {
            case FARM -> Items.IRON_HOE;
            case WOOD -> Items.IRON_AXE;
            case FISH -> Items.FISHING_ROD;
            case HUNT -> Items.BOW;
            case RANCH -> Items.SHEARS;
            case SCOUT -> Items.SPYGLASS;
            case FERRY -> Items.OAK_BOAT;
            case GOLEMS -> Items.IRON_INGOT;
            case REDSTONE -> Items.REDSTONE_TORCH;
            case EMERALD -> Items.EMERALD;
            case DIVER -> Items.CLAY_BALL;
            default -> Items.AIR;
        };
    }

    @Nullable
    private static BlockPos part(String structure, BlockPos anchor, BuildGoal.Part part) {
        for (BuildGoal.Placement p : BuildGoal.plan(structure, anchor, Direction.NORTH, 13)) if (p.part() == part) return p.pos();
        return null;
    }

    @Nullable
    private static VillageFolkEntity standIn(ServerLevel level, BlockPos pos, StationTask trade, String name, float yaw) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        int y = top > pos.getY() + 2 ? pos.getY() : top;                // under a roof: on the floor it was set on
        f.moveTo(pos.getX() + 0.5, y, pos.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(trade);
        f.rename(name);
        f.addTag("folk_lineup");
        return level.addFreshEntity(f) ? f : null;
    }
}
