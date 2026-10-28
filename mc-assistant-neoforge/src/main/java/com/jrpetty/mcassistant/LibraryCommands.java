package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Library;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * /village library: the town library and its books (entity/Library). [library]
 *
 * <pre>
 *   /village library             the nearest town's library: its librarian, every book in the catalogue (the trades'
 *                                books and their editions, the histories, poems, how-to books, lives and storybooks),
 *                                who wrote it, where it stands or who has it, what is being written and what it is short of
 *   /village library write       (ops) the next piece of writing done now, out of the stores
 *   /village library read words  (ops) a fair copy of the book named (or the newest), into your hands, to read
 *   /village library stage       (ops) a library set out where you stand for the pictures, its shelves up and the town's
 *                                books written into it, for the town it stands by; says where to look from
 * </pre>
 */
public final class LibraryCommands {

    private LibraryCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("library")
            .executes(LibraryCommands::status)
            .then(Commands.literal("write").requires(src -> src.hasPermission(2)).executes(LibraryCommands::write))
            .then(Commands.literal("read").requires(src -> src.hasPermission(2))
                .executes(ctx -> read(ctx, ""))
                .then(Commands.argument("words", StringArgumentType.greedyString())
                    .executes(ctx -> read(ctx, StringArgumentType.getString(ctx, "words")))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(LibraryCommands::stage));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx, int range) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        return Villages.nearest(ctx.getSource().getLevel(), at, range);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No town near enough to have a library."));
            return 0;
        }
        List<String> lines = Library.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int write(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No town near enough."));
            return 0;
        }
        String did = Library.writeNow(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("LIBRARY " + Villages.name(v.id()) + ": " + did), false);
        return 1;
    }

    private static int read(CommandContext<CommandSourceStack> ctx, String words) {
        Villages.Village v = near(ctx, Villages.VILLAGE_RANGE * 2);
        ServerPlayer p = ctx.getSource().getPlayer();
        if (v == null || p == null) {
            ctx.getSource().sendFailure(Component.literal("Stand in a town, as a player, to have a book from its library."));
            return 0;
        }
        String said = Library.copyForOp(v, p, words);
        ctx.getSource().sendSuccess(() -> Component.literal("LIBRARY " + said), false);
        return 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = near(ctx, 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("Stand within two hundred blocks of a town: the library is its."));
            return 0;
        }
        List<String> views = Library.stage(level, v, at);
        ctx.getSource().sendSuccess(() -> Component.literal("LIBRARY " + String.join(" | ", views)), false);
        return views.size();
    }
}
