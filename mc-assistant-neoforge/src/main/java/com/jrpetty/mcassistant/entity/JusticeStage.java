package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Justice.Charge;
import com.jrpetty.mcassistant.entity.Justice.JCase;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;

/**
 * [wpe] Operators only, for the camera and the screenshots (never a player's way in): {@code /village justicestage
 * <scene> <player>} sets a justice scene up around a player in the nearest town. "build" raises a jailhouse and a
 * courthouse beside the player (on cleared ground) if the town has none; "arrest" has a guard put the irons on the
 * player and lead it off towards the jail; "jail" locks it in a cell, sentenced; "court" holds it and sits the court on
 * a witnessed case at once; "notice" shows the arrest notice; "free" lets it go and clears its cases.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class JusticeStage {

    private JusticeStage() {}

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("village").then(Commands.literal("justicestage").requires(s -> s.hasPermission(2))
            .then(Commands.argument("scene", StringArgumentType.word()).then(Commands.argument("who", EntityArgument.player()).executes(c -> {
                ServerPlayer p = EntityArgument.getPlayer(c, "who");
                String said = stage(p, StringArgumentType.getString(c, "scene"));
                c.getSource().sendSuccess(() -> Component.literal("JUSTICESTAGE " + said), false);
                return 1;
            })))));
    }

    /** The town the scenes are staged in (set by "build"), so the later scenes keep to it wherever the player has walked. */
    @Nullable private static java.util.UUID STAGED;

    static String stage(ServerPlayer p, String scene) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = scene.equals("build") || STAGED == null ? Villages.nearest(level, p.blockPosition(), 400) : Villages.get(STAGED);
        if (v != null && scene.equals("build")) STAGED = v.id();
        if (v == null) return "no town near";
        Justice.hurryForTests(!scene.equals("slow"));
        switch (scene) {
            case "build" -> {
                BlockPos at = p.blockPosition();
                Direction f = Direction.NORTH;
                clear(level, at, 6, 10);                                   // room to see the scenes from
                if (Jail.of(v.id()) == null) {
                    BlockPos site = at.relative(Direction.EAST, 12);
                    clear(level, site, 9, 9);
                    com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, Jail.STRUCTURE, site, f, 13,
                        com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
                    Ledger.built(v.id(), Jail.STRUCTURE, site, f);
                    Villages.builtAtForTests(v.id(), Jail.STRUCTURE, site);
                    Ledger.Building b = Jail.of(v.id());
                    if (b != null) Jail.fit(level, v, b);
                }
                if (Assize.courthouse(v.id()) == null) {
                    BlockPos site = at.relative(Direction.WEST, 12);
                    clear(level, site, 9, 12);
                    com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, Assize.COURTHOUSE, site, f, 13,
                        com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
                    Ledger.built(v.id(), Assize.COURTHOUSE, site, f);
                    Villages.builtAtForTests(v.id(), Assize.COURTHOUSE, site);
                }
                return "built for " + Villages.name(v.id());
            }
            case "arrest" -> {
                VillageFolkEntity g = guard(level, v, p);
                if (g == null) return "no guard";
                JCase c = witnessed(level, v, p, Charge.BURGLARY);
                Arrests.Task t = new Arrests.Task(c.id, p.getUUID(), Arrests.Phase.GO, level.getGameTime());
                t.told = true;
                Arrests.put(g, t);
                g.moveTo(p.getX() + 1.5, p.getY(), p.getZ() + 1.0);
                Arrests.arrest(level, v, g, t, c, p);
                return "arrested by " + g.displayNameCap();
            }
            case "jail" -> {
                JCase c = witnessed(level, v, p, Charge.ASSAULT);
                Jail.Spot s = Jail.spot(level, v, p.getUUID());
                if (s != null) p.teleportTo(s.inside.getX() + 0.5, s.inside.getY(), s.inside.getZ() + 0.5);
                Jail.lockUp(level, v, p.getUUID(), c, s, null);
                if (s != null && s.door != null) WatchHouse.setDoor(level, s.door, false);
                c.stage = Justice.Stage.CONVICTED;
                c.finding = "assault";
                c.sentence = "two days in the cells";
                Jail.sentence(level, v, p, c, 2 * com.jrpetty.mcassistant.JusticeConfig.jailDayMinutes() * 60.0);
                return "jailed in " + (s == null ? "?" : s.words);
            }
            case "court" -> {
                JCase c = witnessed(level, v, p, Charge.MANSLAUGHTER);
                Jail.lockUp(level, v, p.getUUID(), c, Jail.spot(level, v, p.getUUID()), null);
                Assize.defend(p, c, "lawyer", "");
                c.courtAt = level.getGameTime();
                return "court called on " + c.title();
            }
            case "notice" -> {
                JCase c = witnessed(level, v, p, Charge.BURGLARY);
                Justice.notice(p, "You are under arrest", "You are under arrest for " + c.chargeWords() + " at " + c.place + " (witness: "
                    + Justice.nonEmpty(c.firstWitness(), "the watch's evidence") + ").", "The watch is walking you to cell 1 of the jailhouse. Pulling away is resisting arrest. "
                    + "You'll be held there until the court sits; you can prepare your defence or hire a lawyer on this page.");
                return "notice shown";
            }
            case "free", "slow" -> {
                Jail.release(level, p.getUUID(), "Let go by the operator.");
                Restraint.off(p);
                Justice.forgetForTests(p.getUUID());
                return "freed";
            }
            default -> { return "scenes: build, arrest, jail, court, notice, free"; }
        }
    }

    /** A case against the player as if a folk nearby saw it and told the watch. */
    static JCase witnessed(ServerLevel level, Villages.Village v, ServerPlayer p, Charge charge) {
        VillageFolkEntity w = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != AssistantEntity.StationTask.GUARD && f.isAlive()) {
                if (w == null || f.distanceTo(p) < w.distanceTo(p)) w = f;
            }
        }
        JCase c = Justice.open(level, v.id(), charge, p.getUUID(), Belonging.called(p), true, w == null ? null : w.getUUID(),
            w == null ? "a house" : w.displayNameCap(), false, Justice.placeOf(level, v, p.blockPosition()), p.blockPosition());
        c.goods = "a loaf and a candlestick";
        if (w != null) {
            Justice.addWitness(c, w, false, level.getGameTime(), level.getRandom());
            for (Justice.Wit x : c.witnesses) x.reported = true;
        }
        c.stage = Justice.Stage.WARRANT;
        Justice.changed();
        return c;
    }

    @Nullable
    static VillageFolkEntity guard(ServerLevel level, Villages.Village v, ServerPlayer p) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity g : Patrols.watch(v.id())) if (best == null || g.distanceTo(p) < best.distanceTo(p)) best = g;
        return best;
    }

    private static void clear(ServerLevel level, BlockPos c, int rx, int rz) {
        for (int dx = -rx; dx <= rx; dx++) for (int dz = -rz; dz <= rz; dz++) {
            level.setBlock(c.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 0; dy <= 9; dy++) level.setBlock(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
        }
    }
}
