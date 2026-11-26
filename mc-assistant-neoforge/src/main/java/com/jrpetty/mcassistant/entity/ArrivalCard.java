package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpa] The arrival card: the first time a player walks into a town, a card says where it is and what a newcomer can do
 * there, each a button: join the village (the Town Hall's register), find work, find a home, open a shop, become a
 * citizen, and what's on. Shown once a town (it replaces BizGuide's line in chat), and again any time from the Town
 * Hall or Your life in town. It waits while the player has a chest or a bench open, so it never snatches a screen.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class ArrivalCard implements PlayerLife.Page {

    /** Players with a card to show when they are free: the town. */
    private static final Map<UUID, UUID> PENDING = new ConcurrentHashMap<>();

    /** The player has come into this town (FolkEvents): its card, the first time. True if the card has it in hand. */
    public static boolean arrive(ServerPlayer p, UUID village) {
        if (Belonging.arrived(p.getUUID(), village)) return true;
        if (p.getUUID().equals(Advancements.founder(village))) {                // its own town: it knows the way round
            Belonging.markArrived(p.getUUID(), village);
            return false;
        }
        PENDING.put(p.getUUID(), village);
        tryShow(p);
        return true;
    }

    private static void tryShow(ServerPlayer p) {
        UUID village = PENDING.get(p.getUUID());
        if (village == null) return;
        if (p.containerMenu != p.inventoryMenu || p.isSleeping() || p.isSpectator()) return;   // busy: next time
        PENDING.remove(p.getUUID());
        Belonging.markArrived(p.getUUID(), village);
        PlayerLife.open(p, "arrival", LifePage.args("town", village.toString()));
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        if (PENDING.isEmpty() || !(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 7) return;
        tryShow(p);
    }

    @Nullable
    static Villages.Village town(ServerPlayer p, PlayerLife.Ctx c) {
        try {
            Villages.Village v = Villages.get(UUID.fromString(c.str("town")));
            if (v != null) return v;
        } catch (IllegalArgumentException ignored) { }
        return PlayerLife.here(p);
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = town(p, c);
        if (v == null) {
            LifePage page = new LifePage("arrival", "No town here", "Walk into one and its card comes up", p.registryAccess());
            page.buttons().button("Your life in town", "life", null);
            return page;
        }
        UUID id = v.id();
        String name = Villages.name(id);
        Standing.View view = Standing.of(id, p.getUUID(), level.getGameTime());
        LifePage page = new LifePage("arrival", "Welcome to " + name, Villages.ageOf(id).label + " · " + Villages.headcount(id) + " people · you are "
            + view.title().words, p.registryAccess());
        String character = Identity.summary(id);
        if (character != null && !character.isEmpty()) page.text(character, 4);
        boolean reg = Belonging.registered(id, p.getUUID());
        boolean citizen = Citizens.is(id, p.getUUID());
        page.heading(reg ? "You live here" : "New in town?");
        if (!reg) {
            page.row(new ItemStack(Items.WRITABLE_BOOK), "Join the village", "Sign the town's book at the Town Hall: a resident, a bed if you fall, "
                + "and a name of your own in town. Free.", "").button("Join village", "join", null).primary().button("Show me", "show", null);
        } else {
            page.row(new ItemStack(Items.WRITABLE_BOOK), "On the book since day " + Belonging.registeredDay(id, p.getUUID()),
                citizen ? "And a citizen." : "A resident. Citizenship is the next step.", "").button("The Town Hall", "join", null);
        }
        page.heading("What you can do");
        page.row(new ItemStack(Items.CLOCK), "Find work", "The job adverts: openings and today's shifts, the pay and the hours.", "")
            .button("Job adverts", "jobs", null);
        page.row(new ItemStack(Items.RED_BED), "Find a home", "A room at the inn for the night, or a house for sale.", "")
            .button("Ask about houses", "say", LifePage.args("t", "Is there a house for sale?"));
        page.row(new ItemStack(McAssistantMod.SHOP_COUNTER_ITEM.get()), "Open a shop", "A building with a counter: yours, built for you, or bought.", "")
            .button("Open a shop", "shop", null);
        page.row(new ItemStack(Items.BLUE_BANNER), "Become a citizen", citizen ? "You are one." : "A vote, a say, a tenth off at the shop and the café.", "")
            .button("How", "citizen", null, !citizen, "You're a citizen already");
        page.row(new ItemStack(Items.FIREWORK_ROCKET), "What's on", "Votes, the court, festivals and the market, from the town's notices.", "")
            .button("Notices", "notices", null);
        page.buttons().button("Not now", "close", null).button("Your life in town", "life", null);
        page.text("This card comes up once. The Town Hall and Your life in town (the U key) have it again.", 4);
        return page;
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        Villages.Village v = town(p, c);
        switch (action) {
            case "join" -> { TownHall.open(p, "join"); return "#open:"; }
            case "citizen" -> { TownHall.open(p, "citizen"); return "#open:"; }
            case "notices" -> { TownHall.open(p, "duties"); return "#open:"; }
            case "jobs" -> { PlayerLife.open(p, "jobs", null); return "#open:"; }
            case "shop" -> { PlayerLife.open(p, "start", LifePage.args("kind", "shop")); return "#open:"; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            case "say" -> { return LifeTown.sayToNearest(p, args.getString("t")); }
            case "close" -> { return "#close"; }
            case "show" -> {
                if (v == null) return "!No town here.";
                return TownHall.showMe(p, TownHallDesk.showMe(p.serverLevel(), v), TownHallDesk.showMeLabel(p.serverLevel(), v));
            }
            default -> { return null; }
        }
    }

    public static void resetForTests() {
        PENDING.clear();
    }
}
