package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Battlefields;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.Diplomacy;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [war] The armies in the field, by command.
 * <pre>
 *   /village war battle         the nearest town's campaign: its armies, their strength and spirit, where they are,
 *                               the field and the hill to watch it from
 *   /village war join           fight for the nearest town at war, under its Captain: a citizen as a volunteer, anybody
 *                               else as a sword for hire (paid ten coins out of its treasury, if it can)
 *   /village war battle stage   (ops) for the pictures: the nearest town at war with its nearest neighbour (declared
 *                               now if it is not), and the campaign begun at once, its waits short; prints
 *                               "FIELD x y z AXIS ax az VIEW x y z" (or "VIEW none")
 *   /village war battle siege   (ops) as stage, the neighbour holding its walls
 *   /village war battle next    (ops) the campaign's next stage now (a march brought to its end first); prints
 *                               "STAGE name" and what to look at now
 *   /village battle stage &lt;phase&gt;  (ops) for the pictures: a battle staged between the nearest town and its neighbour
 *                               (one raised 180 blocks east with none near), twelve a side armed for nothing, the field
 *                               here, set at muster, march, lineup, parley, volley, clash, rout or aftermath and held
 *                               there; prints "STAGED ..." and "VIEW name x y z ax ay az" for each scene to look at
 *   /village battle stage end   (ops) the staged battle let go: the armies home
 *   /village war battle view    (ops) where to look from now: "VIEW name x y z ax ay az" for each scene (the muster,
 *                               the column, the camp, the lines from the side and the hill, the Captain, the clash,
 *                               the gate)
 * </pre>
 */
public final class BattleCommands {

    private BattleCommands() {}

    /** A sword for hire's pay. */
    static final int HIRE = 10;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("battle")
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("phase", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                            java.util.stream.Stream.concat(Campaigns.PHASES.stream(), java.util.stream.Stream.of("end")), b))
                        .executes(BattleCommands::stagePhase))))
            .then(Commands.literal("war")
                .then(Commands.literal("join").executes(BattleCommands::join))
                .then(Commands.literal("battle").executes(BattleCommands::status)
                    .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> stage(ctx, false)))
                    .then(Commands.literal("siege").requires(src -> src.hasPermission(2)).executes(ctx -> stage(ctx, true)))
                    .then(Commands.literal("next").requires(src -> src.hasPermission(2)).executes(BattleCommands::next))
                    .then(Commands.literal("view").requires(src -> src.hasPermission(2)).executes(BattleCommands::view)))));
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 4);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<String> lines = Campaigns.lines(ctx.getSource().getLevel(), v.id());
        Campaigns.Campaign c = Campaigns.ofTown(v.id());
        if (c != null) {
            Battlefields.Ground g = c.ground();
            lines.add("The field: " + g.name() + " at " + g.centre().getX() + ", " + g.centre().getZ()
                + (g.view() != null ? "; watch it from the rise at " + g.view().getX() + ", " + g.view().getZ() : ""));
        }
        if (lines.isEmpty()) lines.add(Villages.name(v.id()) + " has no army in the field.");
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int join(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        String said = Campaigns.join(ctx.getSource().getLevel(), v, p, Ledger.citizen(v.id(), p.getUUID()), HIRE);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx, boolean siege) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village o = null;
        for (Villages.Village n : Diplomacy.neighboursOf(v.id())) { o = n; break; }
        if (o == null) {
            double best = Double.MAX_VALUE;
            for (Villages.Village n : Villages.every()) {
                if (n.id().equals(v.id()) || !n.dim().equals(v.dim())) continue;
                double d = n.centre().distSqr(v.centre());
                if (d < best) { best = d; o = n; }
            }
        }
        if (o == null) {
            ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " has no neighbour to fight."));
            return 0;
        }
        long day = level.getDayTime() / 24000L;
        if (!Wars.atWar(v.id(), o.id())) WarAndPeace.declare(level, v.id(), o.id(), day, WarAndPeace.Goal.TRIBUTE, 20, "tribute of twenty coins");
        Campaigns.hurryForPictures();
        Campaigns.Campaign c = Campaigns.beginForTests(level, v, o, siege, true);
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("No campaign could begin."));
            return 0;
        }
        Battlefields.Ground g = c.ground();
        BlockPos view = g.view();
        String out = "FIELD " + g.centre().getX() + " " + g.centre().getY() + " " + g.centre().getZ() + " AXIS " + String.format(java.util.Locale.ROOT, "%.3f %.3f", g.ax(), g.az())
            + " VIEW " + (view == null ? "none" : view.getX() + " " + view.getY() + " " + view.getZ());
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static int stagePhase(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        String phase = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "phase");
        if (phase.equals("end")) {
            String done = Campaigns.unstage(level, v.id());
            ctx.getSource().sendSuccess(() -> Component.literal(done), false);
            return 1;
        }
        if (!Campaigns.PHASES.contains(phase)) {
            ctx.getSource().sendFailure(Component.literal("A phase: " + String.join(", ", Campaigns.PHASES) + ", or end."));
            return 0;
        }
        List<String> out = Campaigns.stage(level, v, BlockPos.containing(ctx.getSource().getPosition()), phase);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
        return out.isEmpty() || out.get(0).startsWith("NO-") ? 0 : 1;
    }

    private static int next(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Campaigns.Campaign c = Campaigns.ofTown(v.id());
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " has no army in the field."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        Campaigns.onForPictures(level, c);
        String out = "STAGE " + c.stage() + "\n" + String.join("\n", Campaigns.views(level, c));
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static int view(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Campaigns.Campaign c = Campaigns.ofTown(v.id());
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " has no army in the field."));
            return 0;
        }
        String out = "STAGE " + c.stage() + "\n" + String.join("\n", Campaigns.views(ctx.getSource().getLevel(), c));
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }
}
