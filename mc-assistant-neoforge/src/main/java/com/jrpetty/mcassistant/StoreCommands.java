package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.StockKeeper;
import com.jrpetty.mcassistant.entity.Store;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.List;

/**
 * /village stock — the shop's stock book (StockKeeper): every ware on hand against its reorder point and its
 * order-up-to level, its days of cover, what sold and what ran out this week, the orders in flight and where
 * they went, what the store wants and cannot get, the day's entries and the staff.
 * <ul>
 * <li>{@code /village stock} — the book;</li>
 * <li>{@code /village stock count} (ops) — the morning's count and its orders, now, by the stock keeper (or, with
 *     none, the keeper's look at the shelves);</li>
 * <li>{@code /village stock stage} (ops, the client smoke) — a town store stood up by the spot, staffed and
 *     stocked out of the stores, its staff and a few customers held at their places for the camera, and where
 *     the camera stands for each picture; {@code /village stock stage done} lets them go about their business.</li>
 * </ul>
 */
public final class StoreCommands {

    private StoreCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("stock")
            .executes(StoreCommands::book)
            .then(Commands.literal("count").requires(src -> src.hasPermission(2)).executes(StoreCommands::count))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(StoreCommands::stage)
                .then(Commands.literal("done").executes(StoreCommands::stageDone)));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int book(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        String page = StockKeeper.page(ctx.getSource().getLevel(), v);
        for (String line : page.split("\n")) ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int count(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        boolean keeper = com.jrpetty.mcassistant.entity.StoreStaff.stockKeeper(v.id()) != null;
        List<String> placed = StockKeeper.takeStock(ctx.getSource().getLevel(), v, keeper);
        String said = "STOCK counted by " + (keeper ? "the stock keeper" : "the keeper") + ": "
            + (placed.isEmpty() ? "nothing ordered" : String.join("; ", placed));
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return placed.size();
    }

    private static int stageDone(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        int n = Store.releaseStaged(v);
        ctx.getSource().sendSuccess(() -> Component.literal("STORE STAGE DONE: " + n + " let go"), false);
        return n;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> out = Store.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()));
        String said = "STORE STAGE | " + String.join(" | ", out);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return out.size();
    }
}
