package com.jrpetty.mcassistant.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * [wpa] A player's ID card (page "id"): its role-play name and bio, its portrait, the towns whose book it signed (since
 * when, resident or citizen), its job, its home and family, its respect where it stands, and its badges (knight,
 * leader, office). Opened from its papers (CitizenPapersItem), from the Town Hall, or by right-clicking another player
 * with an empty hand (BelongingSync): the register is the truth, the card a view of it, so it is always today's.
 */
public final class IdCard implements PlayerLife.Page {

    /** Right-click on another player: its card, if it has signed any town's book. */
    public static void inspect(ServerPlayer p, ServerPlayer other) {
        if (Belonging.rp(other.getUUID()) == null && Belonging.townsOf(other.getUUID()).isEmpty()) {
            p.displayClientMessage(Component.literal(other.getGameProfile().getName() + " hasn't signed any town's book: no papers to show."), true);
            return;
        }
        PlayerLife.open(p, "id", LifePage.args("who", other.getUUID().toString()));
    }

    /** Open the player's own card. */
    public static void openOwn(ServerPlayer p) {
        PlayerLife.open(p, "id", LifePage.args("who", p.getUUID().toString()));
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        UUID who = p.getUUID();
        try { if (!c.str("who").isEmpty()) who = UUID.fromString(c.str("who")); } catch (IllegalArgumentException ignored) { }
        boolean own = who.equals(p.getUUID());
        ServerPlayer them = level.getServer().getPlayerList().getPlayer(who);
        String user = them != null ? them.getGameProfile().getName() : userName(level, who);
        Belonging.Rp rp = Belonging.rp(who);
        String name = rp != null ? rp.full() : user;
        LifePage page = new LifePage("id", "Papers: " + name, rp != null ? "Known in town as " + rp.full() + (own ? "" : " (" + user + ")") : "No town name yet", p.registryAccess());
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        if (them != null) head.set(DataComponents.PROFILE, new ResolvableProfile(them.getGameProfile()));
        if (rp == null) {
            page.row(head, name, own ? "Sign a town's book at its Town Hall, then tell it who you are." : "Has no town name.", "");
            if (own) page.buttons().button("The Town Hall", "hall", null).primary();
        } else {
            String about = (rp.age() > 0 ? rp.age() + " years" : "") + (rp.trade().isEmpty() ? "" : (rp.age() > 0 ? ", " : "") + rp.trade())
                + (rp.look().isEmpty() || rp.look().equals("plain") ? "" : (rp.age() > 0 || !rp.trade().isEmpty() ? ", " : "") + "dresses " + rp.look());
            page.row(head, rp.full(), about.isEmpty() ? "In town since day " + rp.since() : about + ". In town since day " + rp.since() + ".", "");
            if (!rp.bio().isEmpty()) page.text("\"" + rp.bio() + "\"", 0);
        }
        // the towns
        page.heading("Towns");
        List<UUID> towns = Belonging.townsOf(who);
        if (towns.isEmpty()) page.text("On no town's book.", 4);
        for (UUID t : towns) {
            boolean citizen = Citizens.is(t, who);
            String rating = TownOpinion.ratingOf(t, who, level.getGameTime());
            page.row(new ItemStack(citizen ? Items.BLUE_BANNER : Items.WHITE_BANNER), Villages.name(t) + (citizen ? ": citizen" : ": resident"),
                "On the book since day " + Belonging.registeredDay(t, who) + ". Respect: " + rating + "." + badges(t, who), "");
        }
        // work, home and family where the player is
        Villages.Village v = PlayerLife.here(p);
        if (them != null) {
            page.heading("Work and home");
            String job = PlayerJobs.line(them);
            page.row(new ItemStack(Items.CLOCK), job.isEmpty() ? "No job just now" : job, "", "");
            if (v != null) {
                int[] houses = Homes.playerHouses(v.id(), who);
                page.row(new ItemStack(Items.RED_BED), houses[0] > 0 ? "Owns " + houses[0] + (houses[0] == 1 ? " house" : " houses") + " in " + Villages.name(v.id())
                    : PlayerFamily.homeOf(v.id(), who) != null ? "Lives with family in " + Villages.name(v.id()) : "No house of their own here", "", "");
                UUID spouse = PlayerFamily.spouse(v.id(), who);
                if (spouse != null) page.row(new ItemStack(Items.POPPY), "Married to " + Mischief.nameOf(level, v.id(), spouse), "", "");
            }
        }
        if (own) {
            List<String> was = Belonging.formerNames(who);
            if (!was.isEmpty()) {
                page.heading("Former names");
                for (String w : was) page.text(w, 4);
            }
            page.heading("Your card");
            page.text("Others see this card when they right-click you with an empty hand. Change it at a Town Hall.", 4);
            LifePage.Row r = page.buttons().button("The Town Hall", "hall", null);
            if (rp != null) r.button(rp.plate() ? "Hide my name over my head" : "Show my name over my head", "plate", LifePage.args("on", !rp.plate()));
        }
        return page;
    }

    /** A player's username, online or not. */
    static String userName(ServerLevel level, UUID who) {
        var cache = level.getServer().getProfileCache();
        if (cache != null) {
            var g = cache.get(who);
            if (g.isPresent()) return g.get().getName();
        }
        return "a player";
    }

    /** ", a knight, the leader" and the like, for a town. */
    static String badges(UUID town, UUID who) {
        StringBuilder b = new StringBuilder();
        if (PlayerLeader.leads(town, who)) b.append(" Leads the town.");
        if (Knights.isKnight(town, who)) b.append(" A knight of the town.");
        if (Belonging.onJuryRoll(town, who)) b.append(" On the jury roll.");
        if (Belonging.inMilitia(town, who)) b.append(" In the militia.");
        return b.toString();
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        switch (action) {
            case "hall" -> { TownHall.open(p, "join"); return "#open:"; }
            case "plate" -> {
                String said = Belonging.setPlate(p, args.getBoolean("on"));
                c.args.putString("who", p.getUUID().toString());
                return said;
            }
            default -> { return null; }
        }
    }
}
