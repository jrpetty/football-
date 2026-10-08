package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Fair;
import com.jrpetty.mcassistant.entity.Festivals;
import com.jrpetty.mcassistant.entity.Seasons;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Winter;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * [batchB] /village season, /village fair and /village festival — the town's seasons and its festivals
 * (entity/Seasons, entity/Festivals, entity/Fair, entity/Midwinter, entity/Winter). Registered on its own
 * (Brigadier joins it to the rest of /village).
 *
 * <pre>
 *   /village season                  the nearest town's season, the day of its year, its fields, its festivals
 *   /village season set &lt;1-28&gt;       (ops) turn the town's calendar so today is that day of its year (tests, pictures)
 *   /village fair                    the next fair, its entries so far, the last fair's ribbons
 *   /village fair enter              on fair day, near the board: enter what you hold (bread, wool, a fish, honey)
 *   /village festival &lt;name&gt;        (ops) where a festival stands: maypole, bonfire, fair, harvest, midwinter, snowman
 *   /village festival &lt;name&gt; now    (ops) its things put up at once out of the stores and its gathering called,
 *                                    whatever the hour; says where to look from (AT x y z)
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class SeasonsCommands {

    private SeasonsCommands() {}

    private static final List<String> NAMES = Arrays.asList("maypole", "bonfire", "fair", "harvest", "midwinter", "snowman");

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("season").executes(SeasonsCommands::season)
                .then(Commands.literal("set").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("day", IntegerArgumentType.integer(1, 28))
                        .executes(ctx -> setDay(ctx, IntegerArgumentType.getInteger(ctx, "day"))))))
            .then(Commands.literal("fair").executes(SeasonsCommands::fair)
                .then(Commands.literal("enter").executes(SeasonsCommands::enter)))
            .then(Commands.literal("festival").requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(NAMES, b))
                    .executes(ctx -> festival(ctx, false))
                    .then(Commands.literal("now").executes(ctx -> festival(ctx, true))))));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        return v;
    }

    private static int season(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        UUID id = v.id();
        String line = "SEASON " + Villages.name(id) + ": " + Seasons.dateLine(id, day) + " (day " + (Seasons.dayOfYear(id, day) + 1)
            + " of 28). " + String.format(java.util.Locale.ROOT, "Tended fields x%.2f of their set pace. ", Seasons.season(id, day).growth)
            + "Festivals: " + Festivals.status(level, id) + ".";
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int setDay(CommandContext<CommandSourceStack> ctx, int dayOfYear) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        Festivals.turnTo(v.id(), day, dayOfYear - 1);
        String line = "SEASON " + Villages.name(v.id()) + " turned: today is " + Seasons.dateLine(v.id(), day) + " (day "
            + (Seasons.dayOfYear(v.id(), day) + 1) + " of 28).";
        ctx.getSource().sendSuccess(() -> Component.literal(line), true);
        return 1;
    }

    private static int fair(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        String line = Fair.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int enter(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can enter the fair."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE);
        BlockPos board = v == null ? null : VillageBoards.lectern(v.id());
        if (v != null && board != null && p.blockPosition().distSqr(board) > 24 * 24) {
            ctx.getSource().sendFailure(Component.literal("Go to the board on the square to enter the fair."));
            return 0;
        }
        String said = Fair.enter(level, p, v, p.getMainHandItem());
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    private static int festival(CommandContext<CommandSourceStack> ctx, boolean now) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(java.util.Locale.ROOT);
        UUID id = v.id();
        if (name.equals("snowman")) {
            VillageFolkEntity child = null;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity k && k.isBaby() && !k.isSleeping()) { child = k; break; }
            }
            if (!now) {
                String line = "SNOWMAN " + Villages.name(id) + ": " + Festivals.snowmenForTests(id).size() + " blocks of snowman standing"
                    + (Festivals.snowmenForTests(id).isEmpty() ? "" : " AT " + at(Festivals.snowmenForTests(id).get(0)))
                    + "; builder " + Winter.builderForTests(id) + "; snowy " + com.jrpetty.mcassistant.entity.Sweepers.snowy(level, v) + ".";
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                return 1;
            }
            if (child == null) {
                ctx.getSource().sendFailure(Component.literal("SNOWMAN " + Villages.name(id) + ": no child awake to build one."));
                return 0;
            }
            String made = Winter.buildForTests(level, v, child);
            List<BlockPos> blocks = Festivals.snowmenForTests(id);
            String line = "SNOWMAN " + Villages.name(id) + ": " + made + (blocks.isEmpty() ? "" : " AT " + at(blocks.get(0))) + ".";
            ctx.getSource().sendSuccess(() -> Component.literal(line), true);
            return 1;
        }
        Festivals.Feast f = Festivals.Feast.byKey(name);
        if (f == null) {
            ctx.getSource().sendFailure(Component.literal("Which festival? " + String.join(", ", NAMES) + "."));
            return 0;
        }
        String why = null;
        boolean called = false;
        if (now) {
            why = Festivals.setUpForTests(level, v, f);
            if (f != Festivals.Feast.MIDWINTER) called = Festivals.callNow(level, v, f);
        }
        List<int[]> placed = Festivals.placedForTests(id, f);
        BlockPos where = !placed.isEmpty() ? new BlockPos(placed.get(0)[0], placed.get(0)[1], placed.get(0)[2])
            : f == Festivals.Feast.FAIR && VillageBoards.lectern(id) != null ? VillageBoards.lectern(id) : v.centre();
        String line = f.key.toUpperCase(java.util.Locale.ROOT) + " " + Villages.name(id) + ": " + Festivals.status(level, id)
            + (why == null ? "" : " — " + why) + (now ? (called ? "; the town is called to " + f.words : "; no gathering ("
            + com.jrpetty.mcassistant.entity.Assemblies.debug(id) + ")") : "") + "; " + placed.size() + " blocks put up AT " + at(where) + ".";
        ctx.getSource().sendSuccess(() -> Component.literal(line), now);
        return 1;
    }

    private static String at(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
