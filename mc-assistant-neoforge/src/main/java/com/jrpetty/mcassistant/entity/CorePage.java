package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [wpb] A Business Core's public face, for anyone who isn't its owner: its name, owner and trade, its hours, what it sells and
 * at what, the staff on now, its name in town; before the court, the receiver's view. A player hand of it sees their own
 * work there too. And the ways on from it: make an offer, ask about work.
 */
public final class CorePage implements PlayerLife.Page {

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        UUID village;
        try { village = UUID.fromString(c.args.getString("v")); } catch (IllegalArgumentException e) { village = null; }
        String key = c.args.getString("k");
        CoreSites.Biz z = village == null ? null : CoreSites.biz(village, key);
        LifePage page = new LifePage("core", z == null ? "Business Core" : z.name(), "Business Core" + (village == null ? "" : " · " + Villages.name(village)), p.registryAccess());
        if (z == null) { page.text("This business has shut.", 3); return page; }
        if (z.book() != null && key.startsWith("firm:")) firm(level, p, village, z.book(), page);
        else if (z.farm() != null) farm(level, z.farm(), page);
        else if (z.shop() != null) shop(z.shop(), page);
        return page;
    }

    static void firm(ServerLevel level, ServerPlayer p, UUID village, Business b, LifePage page) {
        CompoundTag r = Enterprise.rec(village, b.id);
        BusinessTypes.BusinessType t = Enterprise.kindOf(village, b);
        if (!b.open) {                                                      // shut: the receiver's view, or wound up
            List<String> end = new ArrayList<>();
            end.add("Shut" + (b.closedDay >= 0 ? " on day " + (b.closedDay + 1) : "") + (r != null && !r.getString("status").isEmpty() ? ": " + r.getString("status") : "") + ".");
            for (int i = Math.max(0, b.notes.size() - 4); i < b.notes.size(); i++) { String n = b.notes.get(i); end.add("Day " + (Long.parseLong(n.substring(0, n.indexOf('|'))) + 1) + ": " + n.substring(n.indexOf('|') + 1)); }
            page.box(r != null && r.getString("status").contains("bankrupt") ? "In the receiver's hands" : "Closed", end, 3);
            return;
        }
        page.text(BusinessCore.statusLine(level, village, b, false), CoreSites.isOpen(level, village, b, r) ? 1 : 3);
        if (r != null && r.contains("petition")) {
            page.box("Before the court", List.of("Its creditors have taken it to court: " + r.getString("petition") + ".",
                "It trades no more till the case is heard. Declared bankrupt, the receiver sells its stock and kit and pays its creditors."), 3);
        }
        List<String> about = new ArrayList<>();
        about.add("Owned by " + b.ownersWords() + (t == null ? "" : "; a " + t.noun()) + ", trading since day " + (b.opened + 1) + ".");
        about.add("Hours: " + (r == null ? "open whenever its staff are in" : BusinessCore.hoursWords(r)) + (r != null && r.contains("discountDay") ? "; a tenth off on " + dayName(r.getInt("discountDay")) + "s" : "") + ".");
        double rep = Sellers.reputation(village, b);
        about.add("Its name in town: " + (rep >= 0.7 ? "very good" : rep >= 0.55 ? "good" : rep >= 0.4 ? "fair" : "poor") + (r != null && r.getString("quality").equals("fine") ? "; known for fine goods" : r != null && r.getString("quality").equals("cheap") ? "; known as cheap" : "") + ".");
        if (r != null && r.hasUUID("manager")) about.add("Its " + Deputy.title(r).toLowerCase(Locale.ROOT) + ": " + Deputy.name(village, r) + ".");
        page.box(b.name, about, 4);
        page.heading("What it sells");
        boolean any = false;
        if (t != null) for (String k : t.wares()) {
            ItemStack one = Stockroom.sampleOf(k);
            Business.Lot lot = b.stock.get(k);
            if (one.isEmpty() || lot == null || lot.qty <= 0) continue;
            any = true;
            page.row(one, Stockroom.plural(k), lot.qty + " in stock", String.format(Locale.ROOT, "%.2fc", Sellers.priceOf(level, village, b, one)));
        }
        if (!any) page.text("Nothing on the shelves just now.", 2);
        page.heading("On now");
        List<String> on = new ArrayList<>();
        for (VillageFolkEntity f : Enterprise.hands(village, b)) on.add(f.displayNameCap());
        for (ServerPlayer h : PlayerHiring.hands(level, "firm:" + b.id)) on.add(h.getGameProfile().getName());
        page.text(on.isEmpty() ? "Nobody at work here just now." : String.join(", ", on) + ".", 4);
        // A player hand of it: their own work here.
        if (WorkLife.employerKey(p).equals("firm:" + b.id)) {
            page.box("Your work here", List.of(PlayerJobs.line(p)), 1);
            page.buttons().button("My job", "myjob", null);
        }
        page.heading("Ways on");
        String no = BusinessOffers.whyNot(level, village, b, p.getUUID(), Business.WHOLE);
        boolean hiring = !PlayerHiring.postsOf(village, "firm:" + b.id).isEmpty();
        page.buttons().button("Make an offer", "offer", LifePage.args("firm", b.id), no.isEmpty(), no.isEmpty() ? "" : "Not possible: " + no)
            .button("Ask about work", "work", null, hiring, "It isn't hiring players just now");
    }

    static String dayName(int i) {
        return new String[]{ "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" }[Math.floorMod(i, 7)];
    }

    static void farm(ServerLevel level, PlayerFarms.Farm f, LifePage page) {
        page.text((f.paused ? "Idle" : "Working") + " · " + f.hands.size() + (f.hands.size() == 1 ? " hand" : " hands") + " · " + f.area() + " blocks", f.paused ? 3 : 1);
        page.box(f.name, List.of("A " + f.kind + " owned by " + f.ownerName + ", worked since day " + (f.since + 1) + ".",
            "Its hands: " + (f.hands.isEmpty() ? "none" : String.join(", ", f.hands.values().stream().map(h -> h[0]).toList())) + "."), 4);
        Business b = PlayerFarms.business(f.id);
        if (b != null) {
            page.heading("In its store");
            boolean any = false;
            for (var e : b.stock.entrySet()) {
                if (e.getValue().qty <= 0) continue;
                any = true;
                page.row(Stockroom.sampleOf(e.getKey()), Stockroom.plural(e.getKey()), e.getValue().qty + " held", "");
            }
            if (!any) page.text("Nothing put by just now.", 4);
        }
    }

    static void shop(PlayerShops.Shop s, LifePage page) {
        page.box(s.name, List.of("A shop kept by " + s.ownerName + (s.keeper != null ? ", with " + s.keeperName + " behind the counter" : "") + ".",
            String.format(Locale.ROOT, "Open %02d:00 to %02d:00.", s.opens, s.closes)), 4);
        page.heading("Its prices");
        if (s.prices.isEmpty()) page.text("No prices set yet.", 4);
        for (var e : s.prices.entrySet()) page.row(Stockroom.sampleOf(e.getKey()), Stockroom.plural(e.getKey()), "", e.getValue() + "c");
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        switch (action) {
            case "offer" -> { PlayerLife.open(p, "offer", LifePage.args("firm", args.getString("firm"))); return "#open:"; }
            case "work" -> { PlayerLife.open(p, "jobs", LifePage.args("tab", "adverts")); return "#open:"; }
            case "myjob" -> { PlayerLife.open(p, "jobs", LifePage.args("tab", "job")); return "#open:"; }
            default -> { return null; }
        }
    }

    static ItemStack icon() {
        return new ItemStack(Items.WRITABLE_BOOK);
    }
}
