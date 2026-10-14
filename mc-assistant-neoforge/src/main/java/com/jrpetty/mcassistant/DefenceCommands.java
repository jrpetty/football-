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
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> build(ctx, true))))
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
            int far = Math.max(Math.max(s.n(), s.e()), Math.max(s.s(), s.w()));
            int y = c.getY();
            // From high over the south-west, the whole ring; over the north gatehouse; along the east walkway.
            out.add(view("wall-1-from-the-air", c.getX() - far - 30, y + far + 20, c.getZ() + far + 30, c.getX(), y, c.getZ()));
            out.add(view("wall-2-the-north-gatehouse", c.getX() + 6, y + 12, c.getZ() - s.n() + 14, c.getX(), y + 4, c.getZ() - s.n() - 3));
            out.add(view("wall-3-outside-the-west-gate", c.getX() - s.w() - 24, y + 8, c.getZ() + 10, c.getX() - s.w(), y + 4, c.getZ()));
            out.add(view("wall-4-a-corner-tower", c.getX() + s.e() + 18, y + 16, c.getZ() + s.s() + 18, c.getX() + s.e() + 3, y + 6, c.getZ() + s.s() + 3));
        }
        out.addAll(CastleWall.lines(level, v));
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", out)), false);
        return Math.max(1, n);
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
            out.add(view("muster-2-the-ring", at.getX() + 12, at.getY() + 5, at.getZ() + 3, at.getX(), at.getY() + 1, at.getZ()));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.join(" | ", out)), false);
        return 1;
    }

    private static String view(String name, int x, int y, int z, int ax, int ay, int az) {
        return "VIEW " + name + " " + x + " " + y + " " + z + " " + ax + " " + ay + " " + az;
    }
}
