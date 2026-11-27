package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [roles-b] The crafts at city scale, in words and set out for the pictures: "/village crafts" (each craft's hands, the
 * most wanted, its benches and its workshop: CraftScale.lines) and "/village crafts stage" (tools/realworld/smoke.py
 * roles_b_stage), from palettes and stand-ins, not the stores: a smithy with its second anvil and two smiths at work; the
 * enchanter's tower with the enchanter at its table in the ring of shelves; the apiary with its four hives, its bees and
 * the beekeeper in the garden. Returns "VIEW name x y z ax ay az" lines: where a camera stands, and what it looks at.
 */
public final class CraftsStage {

    private CraftsStage() {}

    public static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Showcase.stage(level, x - 26, x + 26, z - 10, z + 36, y);
        // 1. The smithy, a second anvil against its wall and two smiths at the two.
        BlockPos smithy = new BlockPos(x - 14, y, z);
        BuildGoal.stamp(level, "smithy", smithy, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.Building sb = new Ledger.Building("smithy", smithy, Direction.NORTH);
        CraftScale.Station anvil = CraftScale.STATIONS.get(StationTask.SMITH);
        List<BlockPos> anvils = new ArrayList<>(CraftScale.benchesIn(level, sb, anvil));
        BlockPos second = CraftScale.freeSpot(level, sb, anvils);
        if (second != null) {
            level.setBlock(second, Blocks.ANVIL.defaultBlockState(), 3);
            anvils.add(second);
        }
        String[] smiths = { "Forde", "Hale" };
        for (int i = 0; i < anvils.size() && i < smiths.length; i++) {
            BlockPos a = anvils.get(i);
            VillageFolkEntity s = standIn(level, a.south(), StationTask.SMITH, smiths[i], 180.0F);
            if (s == null) continue;
            s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_INGOT));
            s.getLookControl().setLookAt(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
        }
        out.add("VIEW smithy " + (smithy.getX() + 5) + " " + (y + 4) + " " + (z + 10) + " " + smithy.getX() + " " + (y + 1) + " " + z);
        // 2. The enchanter's tower, the enchanter at its table in the ring of shelves.
        BlockPos tower = new BlockPos(x + 14, y, z);
        BuildGoal.stamp(level, CraftScale.TOWER, tower, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        VillageFolkEntity e = standIn(level, tower.south(), StationTask.ENCHANT, "Sorrel", 0.0F);
        if (e != null) {
            e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.ENCHANTED_BOOK));
            e.getLookControl().setLookAt(tower.getX() + 0.5, y + 1.0, tower.getZ() + 0.5);
        }
        out.add("VIEW tower " + (tower.getX() + 9) + " " + (y + 8) + " " + (z + 16) + " " + tower.getX() + " " + (y + 6) + " " + z);
        out.add("VIEW tower-inside " + tower.getX() + " " + (y + 2) + " " + (z + 3) + " " + tower.getX() + " " + y + " " + z);
        // 3. The apiary: its hives at the four corners round the middle, bees about them, the beekeeper among them.
        BlockPos apiary = new BlockPos(x, y, z + 24);
        BuildGoal.stamp(level, Apiary.STRUCTURE, apiary, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        for (int k = 0; k < Crafts.MAX_HIVES; k++) {
            BlockPos h = Crafts.hiveSpot(level, apiary, 5, k);
            if (h == null) continue;
            level.setBlock(h, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.FACING, Direction.SOUTH)
                .setValue(BeehiveBlock.HONEY_LEVEL, k == 0 ? BeehiveBlock.MAX_HONEY_LEVELS : 2), 3);
            for (int b = 0; b < 2; b++) {
                Bee bee = EntityType.BEE.create(level);
                if (bee == null) continue;
                bee.moveTo(h.getX() + 0.5, h.getY() + 1.5, h.getZ() + 1.5, 0.0F, 0.0F);
                bee.setPersistenceRequired();
                bee.addTag("folk_lineup");
                level.addFreshEntity(bee);
            }
        }
        VillageFolkEntity keeper = standIn(level, apiary.south(2), StationTask.BEEKEEP, "Clover", 180.0F);
        if (keeper != null) keeper.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.HONEY_BOTTLE));
        out.add("VIEW apiary " + (apiary.getX() + 4) + " " + (y + 6) + " " + (apiary.getZ() + 13) + " " + apiary.getX() + " " + (y + 1) + " " + (apiary.getZ() - 2));
        return out;
    }

    @Nullable
    private static VillageFolkEntity standIn(ServerLevel level, BlockPos pos, StationTask trade, String name, float yaw) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        f.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(trade);
        f.rename(name);
        f.addTag("folk_lineup");
        return level.addFreshEntity(f) ? f : null;
    }

    /** "/village crafts": the town's crafts in words; "/village crafts stage": the pictures' stage, here. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("crafts")
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
                List<String> lines = CraftScale.lines(level, v);
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            });
    }
}
