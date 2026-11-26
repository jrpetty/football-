package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] Where the week's life ticks: each folk's part (its hours counted; a service, a christening, a funeral, a
 * dinner, its club's meeting held to: {@link #hold}) and each town's round once a second (the stalls, the fair, the
 * dinner invitations, the clubs, the toasts at a wedding).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class LifeTick {

    private LifeTick() {}

    private static final Map<UUID, Boolean> BUSY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BUSY.clear();
        LifeLog.resetForTests();
        LifeWeights.resetForTests();
        WorkHours.resetForTests();
        WeekendLife.resetForTests();
        Stalls.resetForTests();
        Fairground.resetForTests();
        Friends.resetForTests();
        Church.resetForTests();
        Clubs.resetForTests();
    }

    /** Once a second a folk (VillageFolkEntity.aiStep): its working hours counted, a fifth of an hour every ten seconds. */
    static void tick(VillageFolkEntity f) {
        if (f.tickCount % 200 != 11 || f.isBaby() || f.ownerId() == null || f.level().isNight()) return;
        if (f.onShift() && !f.offWorkNow()) LifeLog.worked(f);
    }

    /** Its part in the week's events now (VillageFolkEntity.aiStep, before sport): true while it is about one. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        boolean b = f.ownerId() != null && !Visitors.is(f) && !f.isSleeping()
            && (Church.hold(level, f) || Friends.hold(level, f) || Clubs.hold(level, f));
        if (b) BUSY.put(f.getUUID(), true);
        else BUSY.remove(f.getUUID());
        return b;
    }

    /** Between its looks: still about it? */
    public static boolean busy(VillageFolkEntity f) {
        return BUSY.containsKey(f.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % 20 != 7) return;
        ServerLevel level = server.overworld();
        for (Villages.Village v : Villages.every()) {
            if (!level.isLoaded(v.centre())) continue;
            round(level, v);
        }
    }

    /**
     * Operators only, for the camera and the smoke test: {@code /village life weekend|stalls|fair|match|choir|service|off}. A
     * player never needs it: everything here happens by itself on its day.
     */
    @SubscribeEvent
    public static void onCommands(net.neoforged.neoforge.event.RegisterCommandsEvent e) {
        e.getDispatcher().register(net.minecraft.commands.Commands.literal("village").then(net.minecraft.commands.Commands.literal("life")
            .requires(s -> s.hasPermission(2))
            .then(net.minecraft.commands.Commands.argument("what", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
                ServerLevel level = c.getSource().getLevel();
                Villages.Village v = Villages.nearest(level, net.minecraft.core.BlockPos.containing(c.getSource().getPosition()), 400);
                if (v == null) { c.getSource().sendFailure(net.minecraft.network.chat.Component.literal("No town near.")); return 0; }
                String said = camera(level, v, com.mojang.brigadier.arguments.StringArgumentType.getString(c, "what"));
                c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(said), false);
                return 1;
            }))));
    }

    static String camera(ServerLevel level, Villages.Village v, String what) {
        long day = level.getDayTime() / 24000L;
        switch (what) {
            case "weekend" -> { WorkHours.forceForTests = true; return "The week kept in this town whatever its age."; }
            case "where" -> {
                net.minecraft.core.BlockPos c = v.centre(), g = Clubs.green(level, v).centre(), f = Church.font(level, v.id());
                return "centre " + c.getX() + " " + c.getY() + " " + c.getZ() + " green " + g.getX() + " " + g.getY() + " " + g.getZ()
                    + " font " + (f == null ? "none" : f.getX() + " " + f.getY() + " " + f.getZ()) + " day " + day;
            }
            case "off" -> {
                WorkHours.forceForTests = false;
                Fairground.forceForTests(v.id(), false);
                for (Clubs.Club cl : Clubs.Club.values()) Clubs.forceMeetingForTests(v.id(), cl, false);
                Church.forceAll = false;
                Stalls.clear(level, v.id());
                Clubs.endMatch(level, v, day, "off");
                return "Back to the calendar.";
            }
            case "stalls" -> {
                WorkHours.forceForTests = true;
                int n = 0, shoppers = 0;
                net.minecraft.world.item.ItemStack[] samples = { new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POPPY, 3),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 4), new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COD, 2),
                    new net.minecraft.world.item.ItemStack(McAssistantMod.WOODEN_TOY.get(), 1), new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE, 6),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SWEET_BERRIES, 6) };
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (!(a instanceof VillageFolkEntity f) || f.isBaby() || Visitors.is(f)) continue;
                    if (n < 6) {
                        java.util.List<net.minecraft.world.item.ItemStack> w = Stalls.surplus(level, f);
                        if (w.isEmpty()) w = java.util.List.of(samples[n % samples.length].copy(), samples[(n + 2) % samples.length].copy());
                        if (Stalls.raise(level, v, f.getUUID(), f.displayNameCap(), w, day, false) != null) {
                            WeekendLife.forceForTests(f, WeekendLife.Outing.SELL);
                            n++;
                            continue;
                        }
                    }
                    WeekendLife.forceForTests(f, WeekendLife.Outing.MARKET);
                    f.earn(10);
                    shoppers++;
                }
                net.minecraft.core.BlockPos first = Stalls.at(v.id(), firstSeller(v.id()));
                return n + " stalls up in the square; " + shoppers + " folk out shopping." + (first == null ? "" : " stall " + first.getX() + " " + first.getY() + " " + first.getZ());
            }
            case "fair" -> {
                WorkHours.forceForTests = true;
                Fairground.forceForTests(v.id(), true);
                Fairground.round(level, v);
                return "Fair day: " + Fairground.lampsUp(v.id()) + " lanterns; traders on the road in.";
            }
            case "match" -> {
                WorkHours.forceForTests = true;
                Clubs.forceMeetingForTests(v.id(), Clubs.Club.SPORTS, true);
                String m = Clubs.matchForTests(level, v);
                net.minecraft.core.BlockPos g = Clubs.green(level, v).centre();
                return m == null ? "No match: " + Clubs.whyNot(level, v.id(), Clubs.Club.SPORTS) + " (too few free to play)" : "Kick-off: " + m + " green " + g.getX() + " " + g.getY() + " " + g.getZ();
            }
            case "choir" -> { Clubs.forceMeetingForTests(v.id(), Clubs.Club.CHOIR, true); return "Choir practice now."; }
            case "service" -> { Church.forceAll = true; return "Everybody to the Sunday service (set the time to a Sunday morning)."; }
            default -> { return "weekend, stalls, fair, match, choir, service or off."; }
        }
    }

    @javax.annotation.Nullable
    static UUID firstSeller(UUID village) {
        java.util.List<Stalls.Stall> l = Stalls.of(village);
        return l.isEmpty() ? new UUID(0, 0) : l.get(0).seller;
    }

    /** A town's round. */
    public static void round(ServerLevel level, Villages.Village v) {
        try {
            Stalls.round(level, v);
            Fairground.round(level, v);
            Friends.round(level, v);
            Friends.speakToasts(level, v.id());
            Clubs.round(level, v);
        } catch (RuntimeException ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[featlife] the round of {} failed", Villages.name(v.id()), ex);
        }
    }
}
