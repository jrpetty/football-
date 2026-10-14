package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.EarnPayload;
import com.jrpetty.mcassistant.village.BoardPages;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [earning] /village earnings: the Earnings page on the town's books; {@code shift <role> [standing]} and
 * {@code quit [standing]}; {@code wanted}, the Wanted board in words. For the client smoke (ops): {@code stage} puts the
 * player behind the nearest counter on a shift with goods to sell and the board's orders up; {@code stage done} ends it.
 */
public final class EarnCommands {

    private EarnCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("earnings").executes(EarnCommands::page)
            .then(Commands.literal("shift").then(Commands.argument("role", StringArgumentType.word())
                .executes(ctx -> shift(ctx, false))
                .then(Commands.literal("standing").executes(ctx -> shift(ctx, true)))))
            .then(Commands.literal("quit").executes(ctx -> quit(ctx, false))
                .then(Commands.literal("standing").executes(ctx -> quit(ctx, true))))
            .then(Commands.literal("wanted").executes(EarnCommands::wanted))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(EarnCommands::stage)
                .then(Commands.literal("done").executes(ctx -> {
                    ServicePoints.HOURS_OFF_FOR_TESTS = false;
                    return quit(ctx, true);
                })));
    }

    /** The town's books opened at the Earnings page, with the page itself. */
    private static int page(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        Earnings.send(p, EarnPayload.PAGE, EarnBoard.page(level, p));
        CompoundTag books = Annals.snapshot(level, v);
        books.putInt("tab", BoardPages.pageNumber("Earnings"));
        try {
            PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        } catch (RuntimeException e) {
            ctx.getSource().sendFailure(Component.literal("The page could not be sent."));
        }
        ctx.getSource().sendSuccess(() -> Component.literal("EARNINGS " + summary(level, p)), false);
        return 1;
    }

    private static String summary(ServerLevel level, ServerPlayer p) {
        long day = level.getDayTime() / 24000L;
        int[] today = Earnings.earned(p, null, day, 1), week = Earnings.earned(p, null, day, 7);
        return "today " + Earnings.sum(today) + "c, this week " + Earnings.sum(week) + "c; purse " + Market.coinsHeld(p) + "c";
    }

    private static int shift(CommandContext<CommandSourceStack> ctx, boolean standing) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String said = Shifts.take(p, null, StringArgumentType.getString(ctx, "role").toLowerCase(Locale.ROOT), standing);
        ctx.getSource().sendSuccess(() -> Component.literal(said.isEmpty() ? "SHIFT on" : said), false);
        return said.isEmpty() ? 1 : 0;
    }

    private static int quit(CommandContext<CommandSourceStack> ctx, boolean standing) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String said = Shifts.quit(p, standing);
        ctx.getSource().sendSuccess(() -> Component.literal(said.isEmpty() ? "SHIFT off" : said), false);
        return 1;
    }

    private static int wanted(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village within reach."));
            return 0;
        }
        String line = WantedBoard.boardLine(level, v.id());
        ctx.getSource().sendSuccess(() -> Component.literal("WANTED " + Villages.name(v.id()) + ": " + (line == null ? "nothing wanted" : line)), false);
        return 1;
    }

    /** For the smoke: the player on a counter shift at the nearest counter, goods to sell, the orders up. */
    private static int stage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE * 4);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village within reach."));
            return 0;
        }
        List<String> out = new ArrayList<>();
        if (Ledger.coins(v.id()) < 300) Ledger.addCoins(v.id(), 300 - Ledger.coins(v.id()));
        for (ItemStack s : List.of(new ItemStack(Items.WHEAT, 48), new ItemStack(Items.IRON_INGOT, 12), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.WHITE_WOOL, 16), new ItemStack(Items.CARROT, 24))) {
            if (!p.getInventory().add(s.copy())) p.drop(s.copy(), false);
        }
        List<WantedBoard.Order> orders = WantedBoard.orders(level, v);
        if (orders.isEmpty()) orders = List.of(WantedBoard.postForTests(level, v, Items.WHEAT, 32), WantedBoard.postForTests(level, v, Items.COAL, 16));
        out.add("ORDERS " + orders.size());
        ServicePoints.HOURS_OFF_FOR_TESTS = true;
        ServicePoints.Point pt = null;
        for (ServicePoints.Point x : ServicePoints.of(level, v.id(), ServiceRole.SHOP)) pt = x;
        if (pt != null) {
            p.teleportTo(pt.stand() != null ? pt.stand().getX() + 0.5 : pt.pos().getX() + 0.5, pt.pos().getY(), pt.stand() != null ? pt.stand().getZ() + 0.5 : pt.pos().getZ() - 0.5);
            out.add("COUNTER " + pt.pos().getX() + " " + pt.pos().getY() + " " + pt.pos().getZ());
        }
        Shifts.quit(p, false);
        String said = Shifts.take(p, v.id(), ShiftRole.COUNTER.id, false);
        out.add("SHIFT " + (said.isEmpty() ? "on" : said));
        String line = String.join(" | ", out);
        ctx.getSource().sendSuccess(() -> Component.literal("EARN STAGE | " + line), false);
        return 1;
    }
}
