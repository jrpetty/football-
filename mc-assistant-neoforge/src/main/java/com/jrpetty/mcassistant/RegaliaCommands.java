package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Regalia;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [regalia19] /village regalia: who in the nearest town wears what for its office (entity/Regalia), and for the
 * pictures and the tests (operators): the town's land set (its leader's title and dress follow it), and the folk
 * nearest made the lord's eldest child or its partner (the royal house's coronet).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class RegaliaCommands {

    private RegaliaCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        List<String> lands = new ArrayList<>();
        for (Homeland.Land l : Homeland.Land.values()) lands.add(l.name().toLowerCase(Locale.ROOT));
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("regalia").executes(RegaliaCommands::court)
                .then(Commands.literal("land").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("land", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(lands, b))
                        .executes(ctx -> land(ctx, StringArgumentType.getString(ctx, "land")))))
                .then(Commands.literal("heir").requires(s -> s.hasPermission(2)).executes(ctx -> house(ctx, false)))
                .then(Commands.literal("wed").requires(s -> s.hasPermission(2)).executes(ctx -> house(ctx, true)))));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, String s) {
        ctx.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static int court(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Regalia.freshForTests(v.id());
        StringBuilder sb = new StringBuilder("REGALIA " + Villages.name(v.id()) + ":");
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            Regalia.Kind k = Regalia.kind(f);
            if (k == Regalia.Kind.NONE) continue;
            f.showRegalia();
            sb.append(n++ == 0 ? " " : "; ").append(Regalia.plate(f).getString()).append(" (").append(k.name().toLowerCase(Locale.ROOT)).append(")");
        }
        if (n == 0) sb.append(" nobody leads it yet");
        return say(ctx, sb.toString());
    }

    private static int land(CommandContext<CommandSourceStack> ctx, String name) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Homeland.Land l;
        try { l = Homeland.Land.valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return say(ctx, "No such land."); }
        Homeland.setForTests(v.id(), l);
        return court(ctx);
    }

    /** The folk nearest made the lord's eldest child (heir) or its partner (wed). */
    private static int house(CommandContext<CommandSourceStack> ctx, boolean wed) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        UUID lord = Villages.elder(v.id());
        if (lord == null) return say(ctx, "Nobody leads it.");
        Vec3 at = ctx.getSource().getPosition();
        VillageFolkEntity best = null;
        VillageFolkEntity lordFolk = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            if (f.getUUID().equals(lord)) { lordFolk = f; continue; }
            if (best == null || f.distanceToSqr(at) < best.distanceToSqr(at)) best = f;
        }
        if (best == null || lordFolk == null) return say(ctx, "Nobody to choose.");
        if (wed) {
            best.life().partnerWith(lord, lordFolk.displayNameCap());
            lordFolk.life().partnerWith(best.getUUID(), best.displayNameCap());
        } else {
            if (!best.parentIds().contains(lord)) best.parentIds().add(lord);
            best.setAgeForTests(Math.max(30, best.ageYears()));
        }
        return court(ctx);
    }
}
