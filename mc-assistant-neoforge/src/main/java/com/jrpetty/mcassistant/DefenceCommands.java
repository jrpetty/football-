package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.Muster;
import com.jrpetty.mcassistant.entity.Raids;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.WallPlan;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [defence] /village wall and /village muster — the town wall (entity/CastleWall) and the muster at the square
 * (entity/Muster). Registered on its own: brigadier joins it to the rest of /village by name.
 *
 * <pre>
 *   /village wall            the nearest town's wall: where its lines are, how much is laid, what it still wants
 *   /village wall now        (ops) a batch of the wall's works now, as a hand would lay it
 *   /village wall build      (ops) the whole wall laid at once, out of nothing (the pictures; the stores are not touched)
 *   /village wall stage      (ops) the smoke stage: the wall built, and VIEW lines to photograph it from the air
 *   /village wall gate day|night|wagon  (ops) a gate close up: open by day, shut at night, a wagon in its passage
 *   /village muster          (ops) the bell rung for a drill (a minute with nobody in sight): the town gathers on the
 *                            square and the watch rings it round; VIEW lines over the square
 * </pre>
 */
public final class DefenceCommands {

    private DefenceCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("wall").executes(DefenceCommands::page)
                .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(DefenceCommands::now))
                .then(Commands.literal("build").requires(src -> src.hasPermission(2)).executes(ctx -> build(ctx, false)))
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> build(ctx, true)))
                .then(Commands.literal("gate").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("what", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(DefenceCommands::gate))))
            .then(Commands.literal("muster").requires(src -> src.hasPermission(2)).executes(DefenceCommands::muster)));
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<String> lines = CastleWall.lines(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        int n = CastleWall.workForTests(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("WALL a batch: " + n + " blocks | "
            + String.join(" | ", CastleWall.lines(ctx.getSource().getLevel(), v))), false);
        return Math.max(1, n);
    }

    private static int build(CommandContext<CommandSourceStack> ctx, boolean stage) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        int n = CastleWall.buildNow(level, v, true);
        List<String> out = new ArrayList<>();
        out.add("WALL built " + n + " blocks");
        if (stage) {
            WallPlan.Sides s = CastleWall.sides(v.id());
            BlockPos c = v.centre();
            int y = c.getY();
            // From clear air high over the town, the whole ring; over the north gatehouse; outside the west gate; a corner.
            int[] air = CastleWall.airView(level, v);
            out.add(view("wall-1-from-the-air", air[0], air[1], air[2], air[3], air[4], air[5]));
            // The gates at their own floors (a town on a hill has them well above or below its heart).
            int ny = y, wy = y;
            for (CastleWall.Gate g : CastleWall.gates(level, v.id())) {
                if (g.out() == net.minecraft.core.Direction.NORTH) ny = g.gateway().getY();
                if (g.out() == net.minecraft.core.Direction.WEST) wy = g.gateway().getY();
            }
            out.add(view("wall-2-the-north-gatehouse", c.getX() + 6, ny + 12, c.getZ() - s.n() + 14, c.getX(), ny + 3, c.getZ() - s.n() - 3));
            out.add(view("wall-3-outside-the-west-gate", c.getX() - s.w() - 24, wy + 8, c.getZ() + 10, c.getX() - s.w(), wy + 3, c.getZ()));
            out.add(view("wall-4-a-corner-tower", c.getX() + s.e() + 18, y + 16, c.getZ() + s.s() + 18, c.getX() + s.e() + 3, y + 6, c.getZ() + s.s() + 3));
        }
        out.addAll(CastleWall.lines(level, v));
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", out)), false);
        return Math.max(1, n);
    }

    private static int gate(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        String what = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "what");
        List<String> out = CastleWall.gateStage(ctx.getSource().getLevel(), v, what);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", out)), false);
        return 1;
    }

    private static int muster(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        boolean rung = Raids.warAlarm(level, v, "a drill: everybody to the square", null, 1200L);
        BlockPos at = Muster.pointForTests(v.id());
        List<String> out = new ArrayList<>();
        out.add("MUSTER " + (rung ? "the bell rung" : "the bell was already ringing") + " in " + Villages.name(v.id())
            + (at == null ? "; no place on the square" : " | at " + at.toShortString()));
        if (at != null) {
            out.add(view("muster-1-over-the-square", at.getX() - 9, at.getY() + 14, at.getZ() + 9, at.getX(), at.getY(), at.getZ()));
            out.add(view("muster-2-the-ring", at.getX() + 12, at.getY() + 9, at.getZ() + 6, at.getX(), at.getY(), at.getZ()));   // [war-polish] high enough to see the ring as a ring
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", out)), false);
        return 1;
    }

    private static String view(String name, int x, int y, int z, int ax, int ay, int az) {
        return "VIEW " + name + " " + x + " " + y + " " + z + " " + ax + " " + ay + " " + az;
    }
}
