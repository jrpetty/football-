package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.BorderDisputes;
import com.jrpetty.mcassistant.entity.BorderMarkers;
import com.jrpetty.mcassistant.entity.Borders;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarRoom;
import com.jrpetty.mcassistant.item.BorderItems;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [borders-map] The towns' lands and the war room's map table, by command.
 * <pre>
 * /village borders                       every town's lands: chunks, contested, how far its word runs
 * /village borders here                  whose land you stand on
 * /village borders disputes              every land dispute, open and settled
 * /village borders books                 the town's books open at the Lands page
 * /village borders notices title|bar|off how you are told when you cross a border (yours to choose)
 * /village borders draw                  (ops) the lands drawn again now, disputes looked for and stepped on
 * /village borders dispute &lt;town&gt;        (ops) the nearest town starts a dispute with that one over the ground between them
 * /village borders settle                (ops) the nearest town's disputes settled by treaty now
 * /village borders markers               (ops) the nearest town's border banners and stones set now (a decree)
 * /village borders release               (ops) the nearest town's lands let go, as a ruin's are
 * /village maptable give                 (ops) a map table into your hands
 * /village maptable place                (ops) the nearest town's table set in its hall now
 * /village maptable view                 (ops) the nearest town's table opened where you stand (the pictures)
 * /village maptable reveal [on|off]      (ops) your map tables show the whole world for an hour
 * </pre>
 */
public final class BordersCommands {

    private BordersCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> borders() {
        return Commands.literal("borders").executes(BordersCommands::list)
            .then(Commands.literal("here").executes(BordersCommands::here))
            .then(Commands.literal("disputes").executes(BordersCommands::disputes))
            .then(Commands.literal("books").executes(BordersCommands::books))
            .then(Commands.literal("notices")
                .then(Commands.literal("title").executes(ctx -> notices(ctx, "title")))
                .then(Commands.literal("bar").executes(ctx -> notices(ctx, "bar")))
                .then(Commands.literal("off").executes(ctx -> notices(ctx, "off"))))
            .then(Commands.literal("draw").requires(src -> src.hasPermission(2)).executes(BordersCommands::draw))
            .then(Commands.literal("dispute").requires(src -> src.hasPermission(2))
                .then(Commands.argument("town", StringArgumentType.greedyString()).executes(BordersCommands::dispute)))
            .then(Commands.literal("settle").requires(src -> src.hasPermission(2)).executes(BordersCommands::settle))
            .then(Commands.literal("markers").requires(src -> src.hasPermission(2)).executes(BordersCommands::markers))
            .then(Commands.literal("release").requires(src -> src.hasPermission(2)).executes(BordersCommands::release));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> mapTable() {
        return Commands.literal("maptable").requires(src -> src.hasPermission(2))
            .then(Commands.literal("give").executes(BordersCommands::give))
            .then(Commands.literal("place").executes(BordersCommands::place))
            .then(Commands.literal("view").executes(BordersCommands::view))
            .then(Commands.literal("reveal").executes(ctx -> reveal(ctx, true))
                .then(Commands.literal("on").executes(ctx -> reveal(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> reveal(ctx, false))));
    }

    @Nullable
    static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        ServerLevel level = ctx.getSource().getLevel();
        UUID land = Borders.owner(level, at);
        Villages.Village v = land != null ? Villages.get(land) : Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No town near enough."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = Borders.lines(ctx.getSource().getLevel());
        return say(ctx, lines.isEmpty() ? "BORDERS: no town claims any land here" : "BORDERS\n" + String.join("\n", lines));
    }

    private static int here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        UUID o = Borders.owner(level, at), r = Borders.rival(level, at);
        return say(ctx, o == null ? "BORDERS here: the wilds, nobody's land"
            : "BORDERS here: the lands of " + Villages.name(o) + (r == null ? "" : ", claimed by " + Villages.name(r) + " as well"));
    }

    /** The town's books open at the Lands page. */
    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        ServerPlayer p = ctx.getSource().getPlayer();
        if (v == null || p == null) return 0;
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Lands");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int disputes(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = BorderDisputes.lines();
        return say(ctx, lines.isEmpty() ? "DISPUTES: none" : "DISPUTES\n" + String.join("\n", lines));
    }

    private static int notices(CommandContext<CommandSourceStack> ctx, String mode) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) return 0;
        Borders.notices(p, mode);
        return say(ctx, switch (mode) {
            case "off" -> "You will not be told when you cross a border.";
            case "bar" -> "You will be told above your hotbar when you cross a border.";
            default -> "You will be told on the screen when you cross a border.";
        });
    }

    private static int draw(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Borders.drawNow(level);
        BorderDisputes.lookAndStepForTests(level);
        return list(ctx);
    }

    private static int dispute(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        String name = StringArgumentType.getString(ctx, "town").trim();
        Villages.Village o = null;
        for (Villages.Village x : Villages.every()) if (Villages.name(x.id()).equalsIgnoreCase(name) && !x.id().equals(v.id())) o = x;
        if (o == null) {
            ctx.getSource().sendFailure(Component.literal("No other town called " + name + "."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos mid = new BlockPos((v.centre().getX() + o.centre().getX()) / 2, v.centre().getY(), (v.centre().getZ() + o.centre().getZ()) / 2);
        if (!com.jrpetty.mcassistant.village.Ledger.knowEachOther(v.id(), o.id())) com.jrpetty.mcassistant.village.Ledger.relate(v.id(), o.id(), 0);
        BorderDisputes.Dispute d = BorderDisputes.raise(level, v.id(), o.id(), BorderDisputes.Cause.OVERLAP,
            "the land between " + Villages.name(v.id()) + " and " + Villages.name(o.id()), mid);
        return say(ctx, "DISPUTE begun: " + Villages.name(d.claimant()) + " against " + Villages.name(d.against()) + " over " + d.subject());
    }

    private static int settle(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        int n = BorderDisputes.settleAll(ctx.getSource().getLevel(), v.id());
        return say(ctx, "SETTLED " + n + " dispute(s) of " + Villages.name(v.id()) + " by treaty");
    }

    private static int markers(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Borders.drawNow(ctx.getSource().getLevel());
        int n = BorderMarkers.plantAllFree(ctx.getSource().getLevel(), v);
        StringBuilder sb = new StringBuilder("MARKERS set for " + Villages.name(v.id()) + ": " + n);
        for (BlockPos p : BorderMarkers.markersForTests(ctx.getSource().getLevel(), v.id())) {
            sb.append("\nMARKER ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ());
        }
        return say(ctx, sb.toString());
    }

    private static int release(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Borders.release(v.id());
        return say(ctx, "RELEASED the lands of " + Villages.name(v.id()));
    }

    private static int give(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) return 0;
        ItemStack s = new ItemStack(BorderItems.MAP_TABLE_ITEM.get());
        if (!p.getInventory().add(s)) p.drop(s, false);
        return say(ctx, "A map table, for you.");
    }

    private static int place(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Borders.drawNow(ctx.getSource().getLevel());
        BlockPos at = WarRoom.placeNow(ctx.getSource().getLevel(), v);
        return say(ctx, at == null ? "MAPTABLE: no room for one in " + Villages.name(v.id())
            : "MAPTABLE of " + Villages.name(v.id()) + " at " + at.getX() + " " + at.getY() + " " + at.getZ());
    }

    private static int view(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        ServerPlayer p = ctx.getSource().getPlayer();
        if (v == null || p == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        Borders.drawNow(level);
        BlockPos at = WarRoom.placeNow(level, v);
        if (at == null) {
            ctx.getSource().sendFailure(Component.literal("No map table in " + Villages.name(v.id()) + ", and no room for one."));
            return 0;
        }
        WarRoom.viewFrom(p, at);
        return say(ctx, "MAPTABLE view of " + Villages.name(v.id()) + String.format(Locale.ROOT, " (table at %d %d %d)", at.getX(), at.getY(), at.getZ()));
    }

    private static int reveal(CommandContext<CommandSourceStack> ctx, boolean on) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) return 0;
        WarRoom.reveal(p, on);
        return say(ctx, on ? "Your map tables show the whole world for the next hour." : "Your map tables show only what their towns know.");
    }
}
