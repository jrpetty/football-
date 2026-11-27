package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Militia;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarFooting;
import com.jrpetty.mcassistant.entity.WarWorks;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * [war-prep] /village war footing — the town on a war footing (entity/WarFooting, Militia, WarWorks, WarStores).
 * Registered on its own: brigadier joins it to the rest of /village (and of /village war) by name.
 *
 * <pre>
 *   /village war footing           the nearest village's war page: its footing and whom against, the watch and
 *                                  how the enemy was reckoned, the volunteers, the militia, the fortifications,
 *                                  the armoury, the training yard, the siege stores and what it all costs a day
 *   /village war footing now       (ops) the leader's war morning now: volunteers, the militia, the works
 *   /village war footing tension   (ops) on its guard against the nearest other town (a feud between them)
 *   /village war footing war       (ops) at war with the nearest other town
 *   /village war footing peace     (ops) peace with everybody, the feuds let go; the morning stands it down
 *   /village war footing stage     (ops) the smoke stage: the armoury and the training yard put up where you
 *                                  stand, the racks filled, the militia at the dummies and the watch with them
 * </pre>
 */
public final class WarFootingCommands {

    private WarFootingCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("war")
                .then(Commands.literal("footing").executes(WarFootingCommands::page)
                    .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(WarFootingCommands::now))
                    .then(Commands.literal("tension").requires(src -> src.hasPermission(2)).executes(ctx -> set(ctx, Wars.Footing.TENSION)))
                    .then(Commands.literal("war").requires(src -> src.hasPermission(2)).executes(ctx -> set(ctx, Wars.Footing.WAR)))
                    .then(Commands.literal("peace").requires(src -> src.hasPermission(2)).executes(ctx -> set(ctx, Wars.Footing.PEACE)))
                    .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(WarFootingCommands::stage)))));
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
        List<String> lines = WarFooting.page(ctx.getSource().getLevel(), v.id());
        ctx.getSource().sendSuccess(() -> Component.literal("WARFOOTING " + Villages.name(v.id()) + "\n" + String.join("\n", lines)), false);
        return lines.size();
    }

    private static int now(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        WarFooting.morning(level, v, level.getDayTime() / 24000L);
        return page(ctx);
    }

    /** The nearest other town, to be at odds with. */
    @Nullable
    private static Villages.Village other(Villages.Village v) {
        Villages.Village best = null;
        double bd = Double.MAX_VALUE;
        for (Villages.Village w : Villages.every()) {
            if (w.id().equals(v.id()) || !w.dim().equals(v.dim())) continue;
            double d = w.centre().distSqr(v.centre());
            if (d < bd) { bd = d; best = w; }
        }
        return best;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, Wars.Footing to) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        UUID id = v.id();
        if (to == Wars.Footing.PEACE) {
            for (UUID e : Wars.enemies(id)) Wars.end(id, e);
            for (Villages.Village w : Villages.every()) {
                int r = w.id().equals(id) ? 0 : Ledger.relation(id, w.id());
                if (r < -20) Ledger.relate(id, w.id(), -20 - r);
            }
        } else {
            Villages.Village o = other(v);
            if (o == null) {
                ctx.getSource().sendFailure(Component.literal("There is no other town for " + Villages.name(id) + " to be at odds with."));
                return 0;
            }
            int r = Ledger.relation(id, o.id());
            if (r > -60) Ledger.relate(id, o.id(), -60 - r);
            if (to == Wars.Footing.WAR && !Wars.atWar(id, o.id())) Wars.begin(id, o.id(), day);
            if (to == Wars.Footing.TENSION) Wars.end(id, o.id());
        }
        WarFooting.morning(level, v, day);
        return page(ctx);
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        List<String> out = Militia.stage(level, v, at);
        String said = String.join(" ", out);
        ctx.getSource().sendSuccess(() -> Component.literal("WARSTAGE " + said), false);
        return out.size();
    }

    /** Tests and the smoke runs: the works this town has in hand. */
    public static List<String> works(UUID village) {
        return WarWorks.worksForTests(village);
    }
}
