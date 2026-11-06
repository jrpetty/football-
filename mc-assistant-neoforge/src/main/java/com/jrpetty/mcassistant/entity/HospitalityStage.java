package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.client.OutfitIndex;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [roles-c] "/village hospitality": the town's food and hospitality posts in words (who holds each, what each is doing,
 * the jobs the town wants filled, the bakery's day, the tavern's and the inn's books, the miller); and "/village
 * hospitality stage", the same set out for the pictures (tools/realworld/smoke.py roles_c_stage) from palettes and
 * stand-ins, not the stores: the posts in a row in their own clothes, the bakery with the baker at its lit ovens, the
 * tavern with the barkeep behind its bar and the evening's company, and the inn with its keeper at the door. Returns
 * "VIEW name x y z ax ay az" lines: where a camera stands, and what it looks at.
 */
public final class HospitalityStage {

    private HospitalityStage() {}

    /** The row: each post of the cook's trade in its clothes, and the miller, each with a thing it is known by. */
    private static final Hospitality.Role[] ROW = { Hospitality.Role.HEAD_COOK, Hospitality.Role.LINE_COOK, Hospitality.Role.KITCHEN_HAND,
        Hospitality.Role.POT_WASHER, Hospitality.Role.WAITER, Hospitality.Role.HOST, Hospitality.Role.BARKEEP, Hospitality.Role.INNKEEPER,
        Hospitality.Role.BAKER };

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("hospitality")
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
                List<String> lines = lines(level, v);
                for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
                return lines.size();
            });
    }

    /** The town's food and hospitality, in words. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        out.add("HOSPITALITY in " + Villages.name(id) + ":");
        List<String> wanted = new ArrayList<>();
        for (Map.Entry<Hospitality.Role, Integer> e : Hospitality.wanted(id).entrySet()) {
            if (e.getValue() > 0) wanted.add(e.getValue() + " " + e.getKey().title.toLowerCase(java.util.Locale.ROOT));
        }
        out.add("  wanted: " + (wanted.isEmpty() ? "nothing yet" : String.join(", ", wanted)));
        List<String> staff = Hospitality.lines(id);
        if (staff.isEmpty()) out.add("  nobody of the cook's trade yet");
        for (String s : staff) out.add("  " + s);
        out.add("  bakery: " + Bakery.line(level, v));
        out.add("  tavern: " + (Tavern.of(id) == null ? "none yet" : Tavern.booksLine(id)));
        out.add("  inn: " + Inn.line(level, v));
        VillageFolkEntity miller = Windmill.miller(id);
        out.add("  windmill: " + Windmill.line(level, v) + (miller == null && Windmill.mill(id) != null ? " (no miller yet)" : ""));
        return out;
    }

    // ------------------------------------------------------------------ the pictures

    public static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 1. The row of the posts, facing south: the kitchen in its whites, the floor and the bar, the inn, the bakery, the mill.
        Showcase.stage(level, x - 12, x + 12, z - 4, z + 10, y);
        for (int i = 0; i < ROW.length; i++) {
            VillageFolkEntity f = post(level, new BlockPos(x - 9 + i * 2, y, z), ROW[i], 0.0F);
            if (f != null) f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(held(ROW[i])));
        }
        VillageFolkEntity miller = standIn(level, new BlockPos(x + 9, y, z), StationTask.FARM, "Miller", 0.0F);
        if (miller != null) {
            miller.addTag(Windmill.MILLER);
            miller.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WHEAT));
        }
        out.add("VIEW row " + x + " " + (y + 1) + " " + (z + 10) + " " + x + " " + (y + 1) + " " + z);
        // 2. The bakery, its door to the south: the baker at its lit ovens, a loaf in hand.
        int bx = x + 28;
        Showcase.stage(level, bx - 7, bx + 7, z - 7, z + 12, y);
        BlockPos bakery = new BlockPos(bx, y, z);
        BuildGoal.stamp(level, TownLook.BAKERY, bakery, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        BlockPos oven = furnace(level, TownLook.BAKERY, bakery);
        if (oven != null) {
            VillageFolkEntity baker = post(level, oven.south(), Hospitality.Role.BAKER, 180.0F);
            if (baker != null) {
                baker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BREAD));
                baker.getLookControl().setLookAt(oven.getX() + 0.5, oven.getY() + 0.5, oven.getZ() + 0.5);
            }
            out.add("VIEW bakery-inside " + (bx + 2) + " " + (y + 1) + " " + (z + 2) + " " + oven.getX() + " " + oven.getY() + " " + oven.getZ());
        }
        out.add("VIEW bakery-front " + (bx + 4) + " " + (y + 2) + " " + (z + 12) + " " + bx + " " + (y + 2) + " " + z);
        // 3. The tavern, its door to the south: the barkeep behind its bar, and the evening's company at the tables.
        int tz = z + 32;
        Showcase.stage(level, x - 8, x + 8, tz - 8, tz + 12, y);
        BlockPos tavern = new BlockPos(x, y, tz);
        BuildGoal.stamp(level, "tavern", tavern, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        VillageFolkEntity barkeep = post(level, tavern.west(3), Hospitality.Role.BARKEEP, 270.0F);
        if (barkeep != null) barkeep.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.HONEY_BOTTLE));
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity guest = standIn(level, tavern.offset(i - 1, 0, 1 + (i % 2)), i == 1 ? StationTask.FARM : StationTask.MINE,
                i == 1 ? "Farmer" : "Miner", 90.0F);
            if (guest != null) guest.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.HONEY_BOTTLE));
        }
        out.add("VIEW tavern-inside " + (x + 3) + " " + (y + 1) + " " + (tz + 3) + " " + (x - 3) + " " + (y + 1) + " " + tz);
        // 4. The inn, its door to the south: the innkeeper at the door with the rooms' keys.
        int ix = x - 28;
        Showcase.stage(level, ix - 7, ix + 7, z - 8, z + 12, y);
        BlockPos inn = new BlockPos(ix, y, z);
        BuildGoal.stamp(level, TownLook.INN, inn, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        VillageFolkEntity keeper = post(level, inn.offset(1, 0, 7), Hospitality.Role.INNKEEPER, 0.0F);
        if (keeper != null) keeper.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIPWIRE_HOOK));
        out.add("VIEW inn-front " + (ix + 3) + " " + (y + 2) + " " + (z + 12) + " " + ix + " " + (y + 2) + " " + z);
        return out;
    }

    /** What each post is known by in its hand. */
    private static Item held(Hospitality.Role r) {
        return switch (r) {
            case HEAD_COOK -> Items.RABBIT_STEW;
            case LINE_COOK -> Items.COOKED_BEEF;
            case KITCHEN_HAND -> Items.POTATO;
            case POT_WASHER -> Items.BOWL;
            case WAITER -> Items.MUSHROOM_STEW;
            case HOST -> Items.WRITABLE_BOOK;
            case BARKEEP -> Items.HONEY_BOTTLE;
            case INNKEEPER -> Items.TRIPWIRE_HOOK;
            case BAKER -> Items.BREAD;
            default -> Items.AIR;
        };
    }

    /** The structure's first furnace, lit, or null. */
    @Nullable
    private static BlockPos furnace(ServerLevel level, String structure, BlockPos anchor) {
        for (BuildGoal.Placement p : BuildGoal.plan(structure, anchor, Direction.NORTH, 13)) {
            if (p.part() != BuildGoal.Part.FURNACE) continue;
            BlockState st = level.getBlockState(p.pos());
            if (st.hasProperty(AbstractFurnaceBlock.LIT)) level.setBlock(p.pos(), st.setValue(AbstractFurnaceBlock.LIT, true), 3);
            return p.pos();
        }
        return null;
    }

    /** A stand-in of the cook's trade in this post, dressed for it (its tag, so its clothes and its talk are its post's). */
    @Nullable
    private static VillageFolkEntity post(ServerLevel level, BlockPos pos, Hospitality.Role r, float yaw) {
        VillageFolkEntity f = standIn(level, pos, StationTask.COOK, r.title, yaw);
        if (f == null) return null;
        if (!r.tag.isEmpty()) f.addTag(r.tag);
        f.showPost(OutfitIndex.ofPost(r.name()));
        return f;
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
