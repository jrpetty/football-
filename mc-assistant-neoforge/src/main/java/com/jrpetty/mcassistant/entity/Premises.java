package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [wpc] Premises: somewhere to trade from (page "premises"), and the register of who holds them.
 * <ul>
 * <li><b>Kinds.</b> A shop, a workshop, a warehouse, an office; a stall on the square for the week, or a pitch for market day
 *     (PlayerStalls.Licence).</li>
 * <li><b>Where, and what it costs.</b> The zone sets the price and the footfall: on the square ×1.6, the high street (the
 *     avenues) ×1.3, a quarter ×1.0, the edge ×0.7, by the smoke of a smelter or forge ×0.85. Value: the kind's base, the
 *     town's wages and land index, the zone. Rent a week: one and a half in the hundred of the value, times the footfall.
 *     Every listing shows the sum.</li>
 * <li><b>Supply.</b> The town's own trading buildings with nobody trading in them are to let or for sale; when fewer than two
 *     stand free and the treasury can bear it, the council asks its builders for another shop (a speculative build).</li>
 * <li><b>Uses, with effects.</b> A warehouse: more stock and a bulk discount; a workshop: benches for making; an office: a
 *     firm's paperwork, five in the hundred off its running costs (WP-B's books read {@link #costFactor}).</li>
 * <li><b>Holding.</b> Leased (rent each week by standing order) or bought (rates each week), held personally or in a firm
 *     of the player's. Premises may be let to a villager firm: the firm, a term of 4, 8 or 12 weeks and the rent; its rent
 *     comes out of the firm's till into the player's account; two weeks behind and the lease ends.</li>
 * <li><b>Bankruptcy.</b> A firm that goes under (Enterprise, WP-B, calls {@link #onBankrupt}): premises it holds are sold
 *     to the town at 85 in the hundred for its creditors, the town's lien (unpaid rent) first; premises its owner holds
 *     personally are kept, and the firm's lease on them ends.</li>
 * </ul>
 * Kept with the town's books (PropertyBook "premises").
 */
public final class Premises implements PlayerLife.Page {

    /** The kinds and their base values, coins. */
    static final String[] KINDS = { "shop", "workshop", "warehouse", "office" };
    /** Rent a week, in the thousand of the value (times the footfall); rates on a title, in the thousand; the town's buy-back. */
    static final int RENT_PERMILLE = 15, RATES_PERMILLE = 10, BUY_BACK = 80, FIRE_SALE = 85;

    static int base(String kind) {
        return switch (kind) {
            case "workshop" -> 160;
            case "warehouse" -> 180;
            case "office" -> 120;
            default -> 140;
        };
    }

    /** What kind of premises a town building is, or null for one that isn't for trading. */
    @Nullable
    static String kindOf(String structure) {
        return switch (structure) {
            case "shop", "store", "restaurant", "cafe", "tavern", "bakery", "brewery" -> "shop";
            case "workshop", "smithy", "fletcher", "wheelwright" -> "workshop";
            case "storage", "granary", "storehouse" -> "warehouse";
            default -> null;
        };
    }

    /** The zone of a spot: its name, its price factor, its footfall. */
    record Zone(String name, double price, double footfall) {}

    static Zone zone(UUID village, BlockPos heart, BlockPos at) {
        for (Ledger.Building b : Ledger.buildings(village)) {
            String s = b.structure();
            if ((s.equals("smeltery") || s.equals("foundry") || s.equals("smithy") || s.equals("brewery")) && !b.anchor().equals(at)
                    && b.anchor().distSqr(at) < 14 * 14) return new Zone("by the smoke", 0.85, 0.9);
        }
        int dx = Math.abs(at.getX() - heart.getX()), dz = Math.abs(at.getZ() - heart.getZ());
        int d = Math.max(dx, dz);
        if (d <= TownPlan.RING + TownPlan.LOT + 3) return new Zone("on the square", 1.6, 1.5);
        if (Math.min(dx, dz) <= TownPlan.AVENUE + TownPlan.LOT + 2 && d <= TownPlan.reach(2)) return new Zone("on the high street", 1.3, 1.25);
        if (d > TownPlan.reach(2)) return new Zone("at the edge", 0.7, 0.7);
        return new Zone("in a quarter", 1.0, 1.0);
    }

    /** {value, rent a week} and the sum in words. */
    record Price(int value, int rent, String words) {}

    static Price price(UUID village, BlockPos heart, BlockPos at, String kind) {
        Zone z = zone(village, heart, at);
        double wage = Math.max(0.5, Wealth.standing(village) / 10.0);
        double index = MarketMaker.live(village) ? LandTitles.index(village) : 1.0;
        int value = (int) Math.max(10, Math.round(base(kind) * wage * z.price() * index));
        int rent = (int) Math.max(1, Math.round(value * RENT_PERMILLE / 1000.0 * z.footfall()));
        String words = capital(kind) + " " + base(kind) + "c × wages " + fmt(wage) + (index != 1.0 ? " × land " + fmt(index) : "") + " × " + z.name() + " " + fmt(z.price())
            + " = " + value + "c. Rent: 1.5% × footfall " + fmt(z.footfall()) + " = " + rent + "c a week.";
        return new Price(value, rent, words);
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the register

    static CompoundTag book(UUID village) {
        return PropertyBook.town(village, "premises");
    }

    @Nullable
    static CompoundTag get(UUID village, String id) {
        CompoundTag b = book(village);
        return b.get(id) instanceof CompoundTag t ? t : null;
    }

    /** Who holds these premises (a player), or null. */
    @Nullable
    public static UUID holder(UUID village, String id) {
        CompoundTag t = get(village, id);
        return t != null && t.hasUUID("holder") ? t.getUUID("holder") : null;
    }

    /** A listing: a town building free to let or buy. */
    record Listing(String id, String structure, String kind, BlockPos anchor, Price price, Zone zone, String name) {}

    /** The town's trading buildings nobody trades in, not on the register: to let or for sale. */
    static List<Listing> free(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        List<Listing> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(id)) {
            String kind = kindOf(b.structure());
            if (kind == null) continue;
            String pid = "P" + b.anchor().asLong();
            if (get(id, pid) != null) continue;
            boolean used = false;
            for (Business f : Businesses.all(id)) if (f.open && f.at != null && f.at.distSqr(b.anchor()) < 10 * 10) { used = true; break; }
            for (PlayerShops.Shop s : PlayerShops.all(id)) if (s.anchor.distSqr(b.anchor()) < 10 * 10) { used = true; break; }
            if (used) continue;
            Zone z = zone(id, heart, b.anchor());
            out.add(new Listing(pid, b.structure(), kind, b.anchor(), price(id, heart, b.anchor(), kind), z,
                capital(BuildOrders.title(b.structure()).replaceFirst("^an? ", "")) + " " + PropertyMarket.where(id, b.anchor())));
        }
        out.sort((a, c) -> Integer.compare(c.price().rent(), a.price().rent()));
        return out;
    }

    /** The player's premises here (held personally, or in one of its firms). */
    static List<String> mine(UUID village, UUID player) {
        List<String> out = new ArrayList<>();
        CompoundTag b = book(village);
        for (String k : b.getAllKeys()) if (b.get(k) instanceof CompoundTag t && t.hasUUID("holder") && t.getUUID("holder").equals(player)) out.add(k);
        return out;
    }

    /** Take premises: leased (the first week's rent now) or bought (the price), held by the player or a firm of its. */
    static String take(ServerLevel level, ServerPlayer p, Villages.Village v, String pid, boolean buy, String firm, boolean sure, PlayerLife.Ctx c) {
        UUID id = v.id(), me = p.getUUID();
        Listing l = null;
        for (Listing x : free(level, v)) if (x.id().equals(pid)) l = x;
        if (l == null) return "!Those premises aren't free now.";
        if (Insolvency.bankrupt(id, me)) return "!Not while you're bankrupt.";
        Standing.Title title = Standing.of(id, me, level.getGameTime()).title();
        if (!Citizens.is(id, me) && !title.atLeast(Standing.Title.VISITOR)) return "!The town lets premises to those it knows: say hello to its folk first.";
        if (buy && !Citizens.is(id, me) && !title.atLeast(Standing.Title.FRIEND)) return "!The town sells premises to its friends and citizens; you can lease them now.";
        Business f = firm.isEmpty() ? null : Businesses.of(id, firm);
        if (!firm.isEmpty() && (f == null || !MyBusinesses.mine(id, me).contains(f))) return "!That firm isn't yours.";
        int cost = buy ? l.price().value() : l.price().rent();
        if (!sure && (buy || cost > 20)) {
            return PropertyBook.ask(c, buy ? "buy" : "lease", LifePage.args("id", pid, "firm", firm), (buy ? "Buy " : "Lease ") + l.name().toLowerCase(Locale.ROOT)
                + (f == null ? "" : " for " + f.name) + (buy ? " for " + cost + "? Rates are about " + Math.max(1, cost * RATES_PERMILLE / 1000) + " a week."
                : " at " + cost + " a week (the first week now, then each week from your account)?"), buy ? "Buy it" : "Lease it");
        }
        int paid = f != null ? payFromFirm(id, f, cost) : PropertyBook.pay(p, id, cost, buy ? PlayerFinance.Cat.INVESTED : PlayerFinance.Cat.RENT_OUT, (buy ? "premises bought: " : "premises leased: ") + l.name(), true);
        if (paid < cost) return "!" + (f != null ? f.name + "'s till hasn't " : "You haven't ") + cost + " (pack and account). Nothing was paid.";
        long day = PropertyBook.day(level);
        CompoundTag t = new CompoundTag();
        t.putString("kind", l.kind());
        t.putString("use", l.kind());
        t.putString("structure", l.structure());
        t.putString("name", l.name());
        t.putLong("anchor", l.anchor().asLong());
        t.putUUID("holder", me);
        t.putString("holderName", Belonging.called(p));
        t.putString("firm", firm);
        t.putString("tenure", buy ? "title" : "lease");
        t.putInt("value", l.price().value());
        t.putInt("rent", l.price().rent());
        t.putLong("since", day);
        t.putLong("paidTo", day + 7);
        book(id).put(pid, t);
        PropertyBook.dirty();
        if (buy) PropertyItems.deed(p, id, "premises", pid, l.name());
        Villages.tell(id, day, Belonging.called(p) + (buy ? " bought " : " took a lease on ") + l.name().toLowerCase(Locale.ROOT) + (f == null ? "" : " for " + f.name));
        Planner.add(p, (day + 7) * 24000L + 6000, "rent", (buy ? "Rates on " : "Rent on ") + l.name().toLowerCase(Locale.ROOT), l.anchor());
        return (buy ? "Bought: " : "Leased: ") + l.name().toLowerCase(Locale.ROOT) + (f == null ? ", in your own name" : ", held by " + f.name) + ". "
            + (buy ? "The deed is in your pack. " : "Rent comes out each week. ") + "Next: set its use, trade from it, or let it to a villager firm on the Your premises tab.";
    }

    static int payFromFirm(UUID village, Business f, int coins) {
        if (f.till < coins * 100L) return 0;
        f.till -= coins * 100L;
        Ledger.addCoins(village, coins);
        Businesses.dirty();
        return coins;
    }

    /** Let premises to a villager firm: the firm, the term, the rent. */
    static String letToFirm(ServerLevel level, ServerPlayer p, UUID village, String pid, String firm, int weeks, int rent) {
        CompoundTag t = get(village, pid);
        if (t == null || !p.getUUID().equals(holder(village, pid))) return "!Those premises aren't yours.";
        if (!t.getString("tenant").isEmpty()) return "!They're let already.";
        Business f = Businesses.of(village, firm);
        if (f == null || !f.open) return "!That firm isn't trading.";
        for (Business.Stake s : f.owners) if (s.holder() == Business.Holder.PLAYER) return "!Only a villager firm takes a lease here: " + f.name + " has a player owner.";
        if (f.till < rent * 100L) return "!" + f.name + " can't put down the first week's " + rent + ".";
        long day = PropertyBook.day(level);
        f.till -= rent * 100L;
        Businesses.dirty();
        PropertyBook.give(level, village, p.getUUID(), Belonging.called(p), rent, PlayerFinance.Cat.RENT_IN, "rent from " + f.name);
        t.putString("tenant", firm);
        t.putString("tenantName", f.name);
        t.putInt("tenantRent", rent);
        t.putLong("tenantUntil", day + weeks * 7L);
        t.putLong("tenantPaidTo", day + 7);
        t.putInt("tenantBehind", 0);
        PropertyBook.dirty();
        Villages.tell(village, day, f.name + " took a " + weeks + "-week lease on " + t.getString("name").toLowerCase(Locale.ROOT) + " from " + Belonging.called(p) + " at " + rent + " a week");
        return f.name + " has signed a " + weeks + "-week lease at " + rent + " a week; the first week's rent is paid to you. The rent comes out of its till each week into your account.";
    }

    /** Give premises back (a lease) or sell them to the town (a title, at four fifths of their value). */
    static String release(ServerLevel level, ServerPlayer p, UUID village, String pid, boolean sure, PlayerLife.Ctx c) {
        CompoundTag t = get(village, pid);
        if (t == null || !p.getUUID().equals(holder(village, pid))) return "!Those premises aren't yours.";
        boolean title = t.getString("tenure").equals("title");
        int back = title ? t.getInt("value") * BUY_BACK / 100 : 0;
        if (!sure) return PropertyBook.ask(c, "release", LifePage.args("id", pid), title ? "Sell " + t.getString("name").toLowerCase(Locale.ROOT) + " back to the town for " + back + "?"
            + (t.getString("tenant").isEmpty() ? "" : " " + t.getString("tenantName") + "'s lease passes to the town.") : "Give up the lease on " + t.getString("name").toLowerCase(Locale.ROOT) + "? No rent is refunded.", title ? "Sell it" : "Give it up");
        if (title) {
            int k = Ledger.takeCoins(village, back);
            if (k < back) { if (k > 0) Ledger.addCoins(village, k); return "!The treasury hasn't " + back + " just now."; }
            PropertyBook.give(level, village, p.getUUID(), Belonging.called(p), back, PlayerFinance.Cat.CASHED, "premises sold to the town");
        }
        book(village).remove(pid);
        PropertyBook.dirty();
        return title ? "Sold to the town for " + back + "." : "The lease is given up; the premises are the town's to let again.";
    }

    /** Each morning: rent and rates by standing order, firms' rent to their landlords, leases run out; the council's spec build. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag b = book(id);
        for (String k : new ArrayList<>(b.getAllKeys())) {
            if (!(b.get(k) instanceof CompoundTag t) || !t.hasUUID("holder")) continue;
            UUID who = t.getUUID("holder");
            if (t.getLong("paidTo") <= day) {
                boolean title = t.getString("tenure").equals("title");
                int due = title ? Math.max(1, t.getInt("value") * RATES_PERMILLE / 1000) : t.getInt("rent");
                Business f = t.getString("firm").isEmpty() ? null : Businesses.of(id, t.getString("firm"));
                int paid = f != null ? payFromFirm(id, f, due) : 0;
                if (paid < due) StandingOrders.bill(level, id, who, t.getString("holderName"), due, title ? PlayerFinance.Cat.TAXES : PlayerFinance.Cat.RENT_OUT,
                    (title ? "rates on " : "rent on ") + t.getString("name").toLowerCase(Locale.ROOT), title ? "rates" : "rent", "the town", "prem:" + k);
                t.putLong("paidTo", t.getLong("paidTo") + 7);
            }
            String tenant = t.getString("tenant");
            if (!tenant.isEmpty() && t.getLong("tenantPaidTo") <= day) {
                Business f = Businesses.of(id, tenant);
                int rent = t.getInt("tenantRent");
                if (f != null && f.open && f.till >= rent * 100L) {
                    f.till -= rent * 100L;
                    Businesses.dirty();
                    PropertyBook.give(level, id, who, t.getString("holderName"), rent, PlayerFinance.Cat.RENT_IN, "rent from " + t.getString("tenantName"));
                    t.putInt("tenantBehind", 0);
                } else {
                    t.putInt("tenantBehind", t.getInt("tenantBehind") + 1);
                    PropertyBook.tell(level, who, t.getString("tenantName") + " couldn't pay this week's rent on " + t.getString("name").toLowerCase(Locale.ROOT) + ".");
                }
                t.putLong("tenantPaidTo", t.getLong("tenantPaidTo") + 7);
                if (t.getInt("tenantBehind") >= 2 || day >= t.getLong("tenantUntil") || f == null || !f.open) endLease(level, id, t, t.getInt("tenantBehind") >= 2 ? "two weeks behind with the rent" : "its term is up");
            }
        }
        // the council's speculative shop, at most once a week
        if (day % 7 == 3 && free(level, v).size() < 2 && Ledger.coins(id) > 400) {
            Villages.request(id, "shop");
            Ledger.note(id, "wpc.specshop", Long.toString(day));
        }
        PropertyBook.dirty();
    }

    static void endLease(ServerLevel level, UUID village, CompoundTag t, String why) {
        String name = t.getString("tenantName");
        t.remove("tenant");
        t.remove("tenantName");
        t.remove("tenantRent");
        t.remove("tenantUntil");
        t.remove("tenantPaidTo");
        t.remove("tenantBehind");
        if (t.hasUUID("holder")) PropertyBook.tell(level, t.getUUID("holder"), name + "'s lease on " + t.getString("name").toLowerCase(Locale.ROOT) + " has ended: " + why + ". Let it again from Your premises.");
        PropertyBook.dirty();
    }

    // ------------------------------------------------------------------ what a firm's premises do

    /** A firm's running costs, as a share: 0.95 with an office of its own, else 1 (WP-B's books may read this). */
    public static double costFactor(UUID village, String firm) {
        for (String k : book(village).getAllKeys()) {
            if (book(village).get(k) instanceof CompoundTag t && firm.equals(t.getString("firm")) && t.getString("use").equals("office")) return 0.95;
        }
        return 1.0;
    }

    /** Extra stock a firm may keep, as a share (a warehouse: half as much again), and its bulk discount, in the hundred. */
    public static double stockFactor(UUID village, String firm) {
        for (String k : book(village).getAllKeys()) {
            if (book(village).get(k) instanceof CompoundTag t && firm.equals(t.getString("firm")) && t.getString("use").equals("warehouse")) return 1.5;
        }
        return 1.0;
    }

    static String effect(String use) {
        return switch (use) {
            case "warehouse" -> "stock half as much again, and 5% off bulk buys";
            case "workshop" -> "benches: its hands make goods here";
            case "office" -> "a firm's paperwork: 5% off its running costs";
            default -> "a counter and shelves: folk come in to buy";
        };
    }

    // ------------------------------------------------------------------ WP-B's seams (these signatures are fixed)

    /** A firm gone bankrupt, before its creditors are paid: its firm-held premises sold to the town for them (the lien first). */
    public static void onBankrupt(ServerLevel level, UUID village, Business b) {
        CompoundTag bk = book(village);
        for (String k : new ArrayList<>(bk.getAllKeys())) {
            if (!(bk.get(k) instanceof CompoundTag t)) continue;
            if (b.id.equals(t.getString("tenant"))) endLease(level, village, t, b.name + " has gone bankrupt");
            if (!b.id.equals(t.getString("firm"))) continue;
            if (!t.getString("tenure").equals("title")) { bk.remove(k); continue; }   // a lease ends with the firm
            int sale = t.getInt("value") * FIRE_SALE / 100;
            int lien = 0;
            for (Debts.Debt d : t.hasUUID("holder") ? Debts.of(village, t.getUUID("holder")) : List.<Debts.Debt>of()) if (d.ref().equals("prem:" + k)) lien += d.owed();
            int toFirm = Math.max(0, sale - lien);
            int paid = Ledger.takeCoins(village, toFirm);
            b.till += paid * 100L;
            Businesses.dirty();
            bk.remove(k);
            Villages.tell(village, Accounts.dayNow(), t.getString("name") + " was sold to the town for " + sale + " for " + b.name + "'s creditors" + (lien > 0 ? ", the town's lien of " + lien + " first" : ""));
        }
        PropertyBook.dirty();
    }

    /** A firm opened or closed: premises it holds or rents linked to it (open), or a villager firm's lease ended (closed). */
    public static void linkFirm(ServerLevel level, UUID village, Business b, boolean open) {
        CompoundTag bk = book(village);
        for (String k : bk.getAllKeys()) {
            if (!(bk.get(k) instanceof CompoundTag t)) continue;
            if (open && b.at != null && BlockPos.of(t.getLong("anchor")).distSqr(b.at) < 10 * 10 && t.getString("firm").isEmpty()) {
                boolean ownedByHolder = false;
                for (Business.Stake s : b.owners) if (s.who() != null && t.hasUUID("holder") && s.who().equals(t.getUUID("holder"))) ownedByHolder = true;
                if (ownedByHolder) t.putString("firm", b.id);
            }
            if (!open && b.id.equals(t.getString("tenant"))) endLease(level, village, t, b.name + " has closed");
        }
        PropertyBook.dirty();
    }

    /** A firm sold whole: premises it holds go with it to the buyer. */
    public static void transferWithFirm(ServerLevel level, UUID village, Business b, UUID from, UUID to) {
        CompoundTag bk = book(village);
        ServerPlayer buyer = PropertyBook.online(level, to);
        for (String k : bk.getAllKeys()) {
            if (!(bk.get(k) instanceof CompoundTag t) || !b.id.equals(t.getString("firm"))) continue;
            if (!t.hasUUID("holder") || !t.getUUID("holder").equals(from)) continue;
            t.putUUID("holder", to);
            t.putString("holderName", buyer == null ? "" : Belonging.called(buyer));
            if (buyer != null && t.getString("tenure").equals("title")) PropertyItems.deed(buyer, village, "premises", k, t.getString("name"));
        }
        PropertyBook.dirty();
    }

    /** Land a player holds title to (a farm on its own land, WP-B): all of it within its land titles, or its own buildings' ground. */
    public static boolean holdsTitle(ServerLevel level, UUID village, UUID player, int x0, int z0, int x1, int z1) {
        if (LandPlots.holdsTitle(village, player, x0, z0, x1, z1)) {
            LandPlots.markUsed(village, (x0 + x1) / 2, (z0 + z1) / 2, true);
            return true;
        }
        return PropertyMarket.ownsLandAt(village, player, new BlockPos(x0, 0, z0)) && PropertyMarket.ownsLandAt(village, player, new BlockPos(x1, 0, z1))
            && PropertyMarket.ownsLandAt(village, player, new BlockPos(x0, 0, z1)) && PropertyMarket.ownsLandAt(village, player, new BlockPos(x1, 0, z0));
    }

    // ------------------------------------------------------------------ the page ("premises")

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("premises", "Premises", "No town near", p.registryAccess());
            page.text("Premises are a town's. Walk into one and open this again.", 4);
            return page;
        }
        UUID id = v.id(), me = p.getUUID();
        List<String> mine = mine(id, me);
        LifePage page = new LifePage("premises", "Premises in " + Villages.name(id), "Somewhere to trade from: shops, workshops, warehouses, offices, stalls", p.registryAccess());
        if (c.tab.isEmpty()) c.tab = "browse";
        page.tab("browse", "To let and for sale", c.tab.equals("browse")).tab("mine", "Your premises" + (mine.isEmpty() ? "" : " (" + mine.size() + ")"), c.tab.equals("mine"))
            .tab("stalls", "Stalls and market", c.tab.equals("stalls")).tab("how", "How it works", c.tab.equals("how"));
        PropertyBook.confirmBox(page, c);
        switch (c.tab) {
            case "mine" -> mineTab(level, p, v, c, page, mine);
            case "stalls" -> stallsTab(level, p, v, page);
            case "how" -> howTab(page);
            default -> browseTab(level, p, v, c, page);
        }
        page.buttons().button("The builder: have premises built", "builder", null).button("Back to your life in town", "life", null);
        return page;
    }

    void browseTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        UUID id = v.id(), me = p.getUUID();
        PropertyBook.hint(p, page, "premises.browse", "Where premises stand sets their price and how many folk walk past: the square is dear and busy, the edge cheap and "
            + "quiet. Each listing shows the sum. Lease week by week, or buy; in your own name or a firm's.");
        List<Business> firms = MyBusinesses.mine(id, me);
        String[] fv = new String[firms.size() + 1], fl = new String[fv.length];
        fv[0] = "";
        fl[0] = "In your own name";
        for (int i = 0; i < firms.size(); i++) { fv[i + 1] = firms.get(i).id; fl[i + 1] = "In " + firms.get(i).name + "'s name"; }
        String firm = c.args.getString("firm");
        if (firms.size() > 0) page.choice("firm", "Hold it", fv, fl, firm);
        List<Listing> free = free(level, v);
        if (free.isEmpty()) {
            page.text("Nothing of the town's stands free just now." + (Ledger.note(id, "wpc.specshop") != null ? " The council has asked its builders for a new shop." : "")
                + " Have premises built (the builder), or take a stall.", 4);
        }
        for (Listing l : free) {
            int held = Market.coinsHeld(p) + PropertyBook.account(id, me);
            page.row(new ItemStack(icon(l.kind())), l.name(), capital(l.kind()) + ", " + l.zone().name() + ". " + l.price().words(), l.price().rent() + "c/wk")
                .button("Lease", "lease", LifePage.args("id", l.id()), held >= l.price().rent(), "You have " + held + " of the first week's " + l.price().rent())
                .button("Buy (" + l.price().value() + "c)", "buy", LifePage.args("id", l.id()), held >= l.price().value() || !firm.isEmpty(), "You have " + held + " of " + l.price().value())
                .button("Show me", "show", LifePage.args("at", l.anchor().asLong()));
        }
    }

    static net.minecraft.world.item.Item icon(String kind) {
        return switch (kind) {
            case "workshop" -> Items.CRAFTING_TABLE;
            case "warehouse" -> Items.BARREL;
            case "office" -> Items.WRITABLE_BOOK;
            default -> Items.OAK_SIGN;
        };
    }

    void mineTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, List<String> mine) {
        UUID id = v.id();
        PropertyBook.hint(p, page, "premises.mine", "Set each premises' use (a warehouse, a workshop, an office, a shop) for its effect, or let it to a villager firm for rent.");
        if (mine.isEmpty()) { page.text("You hold no premises here. The first tab lists what's free.", 4); return; }
        List<Business> villagers = new ArrayList<>();
        for (Business b : Businesses.all(id)) {
            if (!b.open) continue;
            boolean player = false;
            for (Business.Stake s : b.owners) if (s.holder() == Business.Holder.PLAYER) player = true;
            if (!player) villagers.add(b);
        }
        for (String k : mine) {
            CompoundTag t = get(id, k);
            if (t == null) continue;
            String use = t.getString("use");
            boolean title = t.getString("tenure").equals("title");
            String sub = (title ? "Yours outright" : "Leased at " + t.getInt("rent") + "c a week") + (t.getString("firm").isEmpty() ? "" : ", held by " + firmName(id, t.getString("firm")))
                + ". Use: " + use + " (" + effect(use) + ")." + (t.getString("tenant").isEmpty() ? "" : " Let to " + t.getString("tenantName") + " at " + t.getInt("tenantRent") + "c a week till day " + t.getLong("tenantUntil") + ".");
            page.row(new ItemStack(icon(use)), t.getString("name"), sub, title ? "worth " + t.getInt("value") : "")
                .button("Next use", "use", LifePage.args("id", k))
                .button("Show me", "show", LifePage.args("at", t.getLong("anchor")))
                .button(title ? "Sell to the town" : "Give up the lease", "release", LifePage.args("id", k));
            if (t.getString("tenant").isEmpty() && !villagers.isEmpty()) {
                String[] bv = new String[villagers.size()], bl = new String[bv.length];
                for (int i = 0; i < bv.length; i++) { bv[i] = villagers.get(i).id; bl[i] = villagers.get(i).name; }
                page.choice("tf_" + k, "Let to", bv, bl, bv[0]);
                page.choice("tw_" + k, "For", new String[]{ "4", "8", "12" }, new String[]{ "4 weeks", "8 weeks", "12 weeks" }, "8");
                int r = t.getInt("rent");
                page.choice("tr_" + k, "At", new String[]{ Integer.toString(Math.max(1, r * 4 / 5)), Integer.toString(r), Integer.toString(r * 6 / 5 + 1) },
                    new String[]{ Math.max(1, r * 4 / 5) + "c a week (low)", r + "c a week (the going rent)", (r * 6 / 5 + 1) + "c a week (high)" }, Integer.toString(r));
                page.buttons().button("Let it to that firm", "letfirm", LifePage.args("id", k));
            }
        }
    }

    static String firmName(UUID village, String firm) {
        Business b = Businesses.of(village, firm);
        return b == null ? "a firm" : b.name;
    }

    void stallsTab(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page) {
        UUID id = v.id();
        long day = PropertyBook.day(level);
        int md = Market.daysToMarket(id, day);
        PropertyBook.hint(p, page, "premises.stalls", "A stall is the cheapest start: a booth on the square for the week, or a pitch just for market day, when the square is full.");
        int wk = PlayerStalls.licenceFee(id, PlayerStalls.Licence.WEEKLY), mk = PlayerStalls.licenceFee(id, PlayerStalls.Licence.MARKET_DAY);
        page.row(new ItemStack(Items.BARREL), "A stall for the week", "A booth on the square, your name on it; folk buy whenever they want what you have", wk + "c")
            .button("Take one", "stall", LifePage.args("l", "WEEKLY"));
        page.row(new ItemStack(Items.OAK_FENCE), "A market-day pitch", md == 0 ? "Market day is today: the square is full" : "The next market is in " + md + (md == 1 ? " day" : " days"), mk + "c")
            .button("Take one", "stall", LifePage.args("l", "MARKET_DAY"), md <= 1, "Taken on market day or the day before (in " + md + " days)");
        page.text("Market days here: " + (md == 0 ? "today" : "next in " + md + " days") + (MarketDays.declared(id, day + md) ? " (" + MarketDays.kind(id, day + md).toLowerCase(Locale.ROOT) + " market)" : "") + ".", 4);
    }

    void howTab(LifePage page) {
        page.heading("Where, and what it costs");
        page.text("Value: the kind's base (shop 140, workshop 160, warehouse 180, office 120) × the town's wages × its land index × the zone: the square 1.6, the high street 1.3, a quarter 1.0, "
            + "the edge 0.7, by a smelter's or forge's smoke 0.85.", 0);
        page.text("Rent each week: 1.5% of the value × footfall (the square 1.5, the high street 1.25, a quarter 1, the edge 0.7, the smoke 0.9). Rates on premises you own: 1% a week.", 0);
        page.heading("Uses");
        for (String k : KINDS) page.text(capital(k) + ": " + effect(k) + ".", 0);
        page.heading("Letting to a villager firm");
        page.text("Choose the firm, 4, 8 or 12 weeks, and the rent. The rent comes out of the firm's till into your account each week; two weeks behind and the lease ends.", 0);
        page.heading("If a firm goes bankrupt");
        page.text("Premises held in the firm's name are sold to the town at 85% of their value for its creditors (any rent owed the town first). Premises you hold yourself stay yours; the firm's lease on them ends.", 0);
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        String common = PropertyBook.common(p, c, action, args, this);
        if (common != null) return common;
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town near.";
        UUID id = v.id();
        CompoundTag f = args.getCompound("f");
        if (f.contains("firm")) c.args.putString("firm", f.getString("firm"));
        String firm = args.contains("firm") ? args.getString("firm") : c.args.getString("firm");
        switch (action) {
            case "lease", "buy" -> { String s = take(level, p, v, args.getString("id"), action.equals("buy"), firm, args.getBoolean("sure"), c); if (!s.startsWith("!") && !s.isEmpty()) c.tab = "mine"; return s; }
            case "show" -> { return PropertyBook.showMe(p, BlockPos.of(args.getLong("at")), "The premises"); }
            case "use" -> {
                CompoundTag t = get(id, args.getString("id"));
                if (t == null || !p.getUUID().equals(holder(id, args.getString("id")))) return "!Those aren't yours.";
                int i = java.util.Arrays.asList(KINDS).indexOf(t.getString("use"));
                String next = KINDS[Math.floorMod(i + 1, KINDS.length)];
                t.putString("use", next);
                PropertyBook.dirty();
                return "Now used as " + ("aeiou".indexOf(next.charAt(0)) >= 0 ? "an " : "a ") + next + ": " + effect(next) + ".";
            }
            case "letfirm" -> {
                String k = args.getString("id");
                return letToFirm(level, p, id, k, f.getString("tf_" + k), Mortgages.parse(f.getString("tw_" + k), 8), Mortgages.parse(f.getString("tr_" + k), 5));
            }
            case "release" -> { return release(level, p, id, args.getString("id"), args.getBoolean("sure"), c); }
            case "stall" -> {
                PlayerStalls.Licence l = "MARKET_DAY".equals(args.getString("l")) ? PlayerStalls.Licence.MARKET_DAY : PlayerStalls.Licence.WEEKLY;
                String s = PlayerStalls.license(level, v, p, l);
                return s.startsWith("Your") || s.startsWith("The stall") || s.startsWith("Paid") ? s : "!" + s;
            }
            case "builder" -> { PlayerLife.open(p, "build", LifePage.args("for", "premises", "back", "premises")); return "#open:"; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    // ------------------------------------------------------------------ tests

    public static List<String> freeForTests(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Listing l : free(level, v)) out.add(l.id() + "|" + l.kind() + "|" + l.zone().name() + "|" + l.price().value() + "|" + l.price().rent());
        return out;
    }

    public static String takeForTests(ServerPlayer p, String id, boolean buy, String firm) {
        Villages.Village v = PlayerLife.here(p);
        PlayerLife.open(p, "premises", null);
        return v == null ? "!no town" : take(p.serverLevel(), p, v, id, buy, firm, true, PlayerLife.ctx(p));
    }

    public static String letForTests(ServerPlayer p, UUID village, String id, String firm, int weeks, int rent) {
        return letToFirm(p.serverLevel(), p, village, id, firm, weeks, rent);
    }

    @Nullable
    public static CompoundTag getForTests(UUID village, String id) {
        return get(village, id);
    }

    public static String zoneForTests(UUID village, BlockPos heart, BlockPos at) {
        return zone(village, heart, at).name();
    }

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }
}
