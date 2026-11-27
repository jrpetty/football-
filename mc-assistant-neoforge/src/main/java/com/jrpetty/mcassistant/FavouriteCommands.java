package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Favourites;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [ui] A player's favourites by command (entity/Favourites; the same as the star on a folk's screens):
 * <pre>
 *   /village favourite add NAME      follow a folk (the nearest of that name, here first, then anywhere loaded)
 *   /village favourite remove NAME   stop following it (alive, away or dead)
 *   /village favourite list          the list, each with its trade, level, mood and where it is
 *   /village favourite show [NAME]   the favourites screen, or one folk's detail on it (the pictures)
 *   /village favourite close         shut that screen
 * </pre>
 */
public final class FavouriteCommands {

    private FavouriteCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("favourite")
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> add(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list").executes(FavouriteCommands::list))
                .then(Commands.literal("show").executes(ctx -> show(ctx, ""))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> show(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("close").executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) com.jrpetty.mcassistant.net.FavouritesNetwork.close(p);
                    return 1;
                }))));
    }

    @Nullable
    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer p) return p;
        ctx.getSource().sendFailure(Component.literal("Only a player keeps favourites."));
        return null;
    }

    /** The nearest living folk of this name: in the player's world first, then in any loaded world. */
    @Nullable
    static VillageFolkEntity named(ServerPlayer p, String name) {
        String n = name.trim();
        VillageFolkEntity best = null;
        for (ServerLevel level : p.server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (!(e instanceof VillageFolkEntity f) || !f.isAlive() || !f.displayNameCap().equalsIgnoreCase(n)) continue;
                boolean here = f.level() == p.level();
                if (best == null || here && (best.level() != p.level() || f.distanceToSqr(p) < best.distanceToSqr(p))) best = f;
            }
        }
        return best;
    }

    private static int add(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer p = player(ctx);
        if (p == null) return 0;
        VillageFolkEntity f = named(p, name);
        if (f == null) {
            ctx.getSource().sendFailure(Component.literal("Nobody called " + name.trim() + " about just now."));
            return 0;
        }
        Favourites.Added a = Favourites.add(p, f);
        String said = switch (a) {
            case ADDED -> f.displayNameCap() + " is on your favourites (" + Favourites.list(p.getUUID()).size() + " of " + Favourites.CAP + ").";
            case ALREADY -> f.displayNameCap() + " is already on your favourites.";
            case FULL -> "Your favourites are full (" + Favourites.CAP + "): take one off first.";
        };
        if (a == Favourites.Added.FULL) ctx.getSource().sendFailure(Component.literal(said));
        else ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return a == Favourites.Added.FULL ? 0 : 1;
    }

    @Nullable
    private static UUID listed(ServerPlayer p, String name) {
        UUID u = Favourites.byName(p.getUUID(), name);
        if (u == null) {
            VillageFolkEntity f = named(p, name);
            if (f != null && Favourites.has(p.getUUID(), f.getUUID())) u = f.getUUID();
        }
        return u;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer p = player(ctx);
        if (p == null) return 0;
        UUID u = listed(p, name);
        if (u == null || !Favourites.remove(p.getUUID(), u)) {
            ctx.getSource().sendFailure(Component.literal(name.trim() + " is not on your favourites."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(name.trim() + " is off your favourites."), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = player(ctx);
        if (p == null) return 0;
        CompoundTag t = Favourites.listTag(p);
        var cards = t.getList("cards", 10);
        if (cards.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("You follow nobody yet: star a folk on its profile (the star by its name)."), false);
            return 1;
        }
        List<String> lines = new ArrayList<>();
        lines.add("FAVOURITES " + cards.size() + " of " + Favourites.CAP + ":");
        for (int i = 0; i < cards.size(); i++) {
            CompoundTag c = cards.getCompound(i);
            String state = c.getBoolean("dead") ? "remembered, died day " + c.getLong("died")
                : c.getBoolean("about") ? c.getString("doing") : "away (last seen day " + c.getLong("day") + ")";
            lines.add("  " + (i + 1) + ". " + c.getString("name") + ": " + c.getString("trade")
                + (c.getBoolean("child") ? "" : ", level " + c.getInt("level")) + ", mood " + c.getInt("mood")
                + (c.getString("town").isEmpty() ? "" : ", " + c.getString("town")) + " · " + state);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return cards.size();
    }

    private static int show(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer p = player(ctx);
        if (p == null) return 0;
        UUID u = null;
        if (!name.isBlank()) {
            u = listed(p, name);
            if (u == null) {
                ctx.getSource().sendFailure(Component.literal(name.trim() + " is not on your favourites."));
                return 0;
            }
        }
        com.jrpetty.mcassistant.net.FavouritesNetwork.show(p, u);
        ctx.getSource().sendSuccess(() -> Component.literal("FAVOURITES shown" + (name.isBlank() ? "" : ": " + name.trim())), false);
        return 1;
    }
}
