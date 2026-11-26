package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpc] A will (page "will", at the Town Hall or the bank): who gets the houses, the land, the premises and the savings — a
 * spouse, a child, a friend who lives here, the town, or the poor fund. Witnessed by the town's clerk, kept with its books.
 * Carried out when the player retires its character here (asked first: it can't be undone), or by the long-absence rule
 * (StandingOrders). Debts come first out of the estate: the bank account, then land sold back to the town; what is still
 * owed after that dies with the estate (heirs never inherit debts). Then the heirs get the property and new deeds. Folk
 * leave what they have by their own family line (Lineage), as before.
 */
public final class Wills implements PlayerLife.Page {

    static final String[] PARTS = { "houses", "land", "premises", "savings" };
    static final String[] PART_WORDS = { "Your houses", "Your land", "Your premises", "Your savings" };

    static CompoundTag book(UUID village) {
        return PropertyBook.town(village, "wills");
    }

    static CompoundTag of(UUID village, UUID who) {
        return Accounts.sub(book(village), who.toString());
    }

    public static boolean has(UUID village, UUID who) {
        return book(village).contains(who.toString()) && of(village, who).getLong("witnessed") > 0;
    }

    /** The heirs a player may name: "town", "charity", its spouse, each child, and the town's other residents. Value, label. */
    static List<String[]> heirs(ServerLevel level, UUID village, ServerPlayer p) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{ "town", "The town" });
        out.add(new String[]{ "charity", "The poor fund" });
        UUID spouse = PlayerFamily.spouse(village, p.getUUID());
        if (spouse != null && level.getEntity(spouse) instanceof VillageFolkEntity s) out.add(new String[]{ "f:" + spouse, "Your spouse, " + s.displayNameCap() });
        for (VillageFolkEntity c : PlayerFamily.children(level, village, p.getUUID())) out.add(new String[]{ "f:" + c.getUUID(), "Your child, " + c.displayNameCap() });
        for (UUID r : Belonging.residents(village)) {
            if (r.equals(p.getUUID())) continue;
            Belonging.Rp rp = Belonging.rp(r);
            String n = rp != null ? rp.full() : nameOf(level, r);
            if (!n.isEmpty()) out.add(new String[]{ "p:" + r, n + " (a resident)" });
        }
        return out;
    }

    static String nameOf(ServerLevel level, UUID who) {
        ServerPlayer p = PropertyBook.online(level, who);
        if (p != null) return Belonging.called(p);
        try {
            return level.getServer().getProfileCache().get(who).map(com.mojang.authlib.GameProfile::getName).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String label(ServerLevel level, UUID village, String heir) {
        if (heir.equals("town") || heir.isEmpty()) return "the town";
        if (heir.equals("charity")) return "the poor fund";
        UUID u = uuid(heir);
        if (u == null) return "the town";
        if (heir.startsWith("f:") && level.getEntity(u) instanceof VillageFolkEntity f) return f.displayNameCap();
        Belonging.Rp rp = Belonging.rp(u);
        String n = rp != null ? rp.full() : nameOf(level, u);
        return n.isEmpty() ? "their heir" : n;
    }

    @Nullable
    static UUID uuid(String heir) {
        try { return heir.length() > 2 ? UUID.fromString(heir.substring(2)) : null; } catch (IllegalArgumentException e) { return null; }
    }

    /** The will carried out: debts first, then each part to its heir. Returns what was done, in a line. */
    static String execute(ServerLevel level, Villages.Village v, UUID who, String name, String why) {
        UUID id = v.id();
        CompoundTag w = of(id, who);
        long day = PropertyBook.day(level);
        List<String> done = new ArrayList<>();
        // debts first: out of the account, then land sold back to the town
        int account = PropertyBook.account(id, who);
        for (Tag e : new ArrayList<>(Debts.list(id, who))) {
            CompoundTag t = (CompoundTag) e;
            int k = PropertyBook.payOffline(id, who, Math.min(account, t.getInt("owed")), PlayerFinance.Cat.LOAN_PAY, "the estate pays " + t.getString("kind"));
            account -= k;
            Debts.settle(level, id, who, t, k);
        }
        int left = Debts.total(id, who);
        if (left > 0) {
            for (LandPlots.Title t : LandPlots.of(id, who)) {
                if (left <= 0) break;
                int back = t.price() * LandPlots.BUY_BACK / 100;
                LandPlots.release(level, id, t.id());
                for (Tag e : new ArrayList<>(Debts.list(id, who))) {
                    CompoundTag d = (CompoundTag) e;
                    int k = Math.min(back, d.getInt("owed"));
                    if (k <= 0) continue;
                    Debts.settle(level, id, who, d, k);
                    back -= k;
                }
                done.add(t.name() + " sold to pay debts");
                left = Debts.total(id, who);
            }
        }
        if (Debts.total(id, who) > 0) { done.add(Debts.total(id, who) + " of debts died with the estate"); Debts.list(id, who).clear(); }
        // the houses
        String hHeir = w.getString("houses");
        for (Homes.Home h : new ArrayList<>(Homes.homes(id).values())) {
            if (h.tenure != Homes.Tenure.PLAYER || !who.equals(h.landlord)) continue;
            UUID to = hHeir.startsWith("p:") ? uuid(hHeir) : null;
            Bank.Loan l = Bank.playerLoan(id, who);
            if (to != null) {
                h.landlord = to;
                h.landlordName = label(level, id, hHeir);
                h.since = day;
                Homes.save(id, h);
                if (l != null && l.anchor == h.anchor.asLong()) { l.borrowers.clear(); l.borrowers.add(to); l.names = h.landlordName; Bank.keepLoan(id, l); }
                ServerPlayer hp = PropertyBook.online(level, to);
                if (hp != null) PropertyItems.deed(hp, id, "house", Long.toString(h.anchor.asLong()), Homes.address(id, v, h));
            } else {
                int price = Homes.price(id, h);
                int toBank = 0;
                if (l != null && l.anchor == h.anchor.asLong()) { toBank = Math.min(price, l.owed()); Mortgages.closeBySale(level, v, l, toBank); }
                h.tenure = Homes.Tenure.RENTED;
                h.landlord = null;
                h.landlordName = "";
                h.toLet = false;
                h.price = price;
                h.rent = Homes.rent(id, h);
                h.since = day;
                Homes.save(id, h);
                hand(level, id, hHeir, price - toBank);
            }
            done.add(Homes.address(id, v, h) + " to " + label(level, id, hHeir));
        }
        // the land
        String lHeir = w.getString("land");
        for (LandPlots.Title t : LandPlots.of(id, who)) {
            UUID to = lHeir.startsWith("p:") || lHeir.startsWith("f:") ? uuid(lHeir) : null;
            if (to != null) LandPlots.transfer(level, id, t.id(), to, label(level, id, lHeir));
            else { LandPlots.release(level, id, t.id()); if (lHeir.equals("charity")) hand(level, id, "charity", 0); }
            done.add(t.name() + " to " + label(level, id, lHeir));
        }
        // premises
        String pHeir = w.getString("premises");
        for (String k : Premises.mine(id, who)) {
            CompoundTag t = Premises.get(id, k);
            if (t == null) continue;
            UUID to = pHeir.startsWith("p:") ? uuid(pHeir) : null;
            if (to != null) { t.putUUID("holder", to); t.putString("holderName", label(level, id, pHeir)); }
            else Premises.book(id).remove(k);
            done.add(t.getString("name") + " to " + label(level, id, pHeir));
        }
        // savings
        int savings = PropertyBook.takeAccount(id, who, PropertyBook.account(id, who));
        if (savings > 0) {
            Ledger.addCoins(id, savings);
            hand(level, id, w.getString("savings"), savings);
            done.add(savings + " in savings to " + label(level, id, w.getString("savings")));
        }
        w.putLong("executed", day);
        PropertyBook.dirty();
        Villages.tell(id, day, name + "'s will was carried out (" + why + "): " + (done.isEmpty() ? "there was little to leave" : String.join("; ", done.subList(0, Math.min(5, done.size())))));
        return done.isEmpty() ? "There was little to leave." : String.join("; ", done) + ".";
    }

    /** Coin (already in the treasury) to an heir: a player's account, a folk's purse, or the town (and the poor fund) keeps it. */
    static void hand(ServerLevel level, UUID village, String heir, int coins) {
        if (coins <= 0) return;
        UUID u = uuid(heir);
        if (u != null && heir.startsWith("p:")) {
            if (Ledger.takeCoins(village, coins) > 0) PropertyBook.putAccount(village, u, label(level, village, heir), coins);
        } else if (u != null && heir.startsWith("f:") && level.getEntity(u) instanceof VillageFolkEntity f) {
            if (Ledger.takeCoins(village, coins) > 0) f.earn(coins);
        } else if (heir.equals("charity")) {
            Ledger.note(village, "wpc.poorfund", Integer.toString(Homes.parse(Ledger.note(village, "wpc.poorfund")) + coins));
        }
    }

    // ------------------------------------------------------------------ the page ("will")

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("will", "Your will", "No town near", p.registryAccess());
            page.text("A will is made with a town. Walk into one and open this again.", 4);
            return page;
        }
        UUID id = v.id(), me = p.getUUID();
        CompoundTag w = of(id, me);
        LifePage page = new LifePage("will", "Your will", has(id, me) ? "Witnessed on day " + w.getLong("witnessed") : "Not made yet",
            p.registryAccess());
        PropertyBook.confirmBox(page, c);
        PropertyBook.hint(p, page, "will.make", "Say who gets what you own here if you retire or are gone a long time. Your debts are paid first; heirs never inherit debts.");
        List<String[]> heirs = heirs(level, id, p);
        String[] hv = new String[heirs.size()], hl = new String[hv.length];
        for (int i = 0; i < hv.length; i++) { hv[i] = heirs.get(i)[0]; hl[i] = heirs.get(i)[1]; }
        page.heading("What you own here");
        int[] houses = Homes.playerHouses(id, me);
        int land = LandPlots.of(id, me).size(), prem = Premises.mine(id, me).size(), savings = PropertyBook.account(id, me);
        page.row(new ItemStack(Items.RED_BED), houses[0] + (houses[0] == 1 ? " house" : " houses") + ", " + land + (land == 1 ? " plot" : " plots") + " of land, " + prem + " premises",
            "Savings at the bank: " + savings + "; debts on your ledger: " + Debts.totalAll(id, me), "");
        page.heading("Who gets what");
        for (int i = 0; i < PARTS.length; i++) {
            String cur = w.getString(PARTS[i]);
            page.choice(PARTS[i], PART_WORDS[i], hv, hl, cur.isEmpty() ? "town" : cur);
        }
        page.buttons().button(has(id, me) ? "Witness the changes" : "Sign and have it witnessed", "sign", null).primary();
        if (has(id, me)) {
            page.heading("As it stands");
            for (int i = 0; i < PARTS.length; i++) page.text(PART_WORDS[i] + ": to " + label(level, id, w.getString(PARTS[i])) + ".", 0);
        }
        page.heading("Retiring");
        page.text("Retiring your character here carries out your will now: your property goes to your heirs and you start afresh. It can't be undone.", 2);
        page.buttons().button("Retire my character here", "retire", null, has(id, me), "Make your will first");
        page.buttons().button("Your debts", "debts", null).button("Back to your life in town", "life", null);
        return page;
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        String common = PropertyBook.common(p, c, action, args, this);
        if (common != null) return common;
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town near.";
        UUID id = v.id(), me = p.getUUID();
        CompoundTag f = args.getCompound("f");
        switch (action) {
            case "sign" -> {
                CompoundTag w = of(id, me);
                for (String part : PARTS) w.putString(part, f.getString(part).isEmpty() ? "town" : f.getString(part));
                w.putLong("witnessed", PropertyBook.day(level));
                VillageFolkEntity clerk = Insolvency.receiver(level, v);
                w.putString("witness", clerk == null ? "the town clerk" : clerk.displayNameCap());
                PropertyBook.dirty();
                return "Signed, and witnessed by " + w.getString("witness") + ". Houses to " + label(level, id, w.getString("houses")) + ", land to " + label(level, id, w.getString("land"))
                    + ", savings to " + label(level, id, w.getString("savings")) + ". Change it here any time.";
            }
            case "retire" -> {
                if (!has(id, me)) return "!Make your will first.";
                if (!args.getBoolean("sure")) return PropertyBook.ask(c, "retire", new CompoundTag(), "Retire your character in " + Villages.name(id) + "? Your debts are paid from your estate, "
                    + "and your houses, land, premises and savings go to your heirs as your will says. This can't be undone.", "Retire");
                String said = execute(level, v, me, Belonging.called(p), "retirement");
                return "Your will has been carried out: " + said;
            }
            case "debts" -> { PlayerLife.open(p, "debts", null); return "#open:"; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    // ------------------------------------------------------------------ tests

    public static void makeForTests(UUID village, UUID who, String houses, String land, String premises, String savings, long day) {
        CompoundTag w = of(village, who);
        w.putString("houses", houses);
        w.putString("land", land);
        w.putString("premises", premises);
        w.putString("savings", savings);
        w.putLong("witnessed", Math.max(1, day));
    }

    public static String executeForTests(ServerLevel level, Villages.Village v, UUID who, String name) {
        return execute(level, v, who, name, "a test");
    }
}
