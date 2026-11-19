package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business.Holder;
import com.jrpetty.mcassistant.entity.Business.Stake;

/**
 * [capital] The land register: a title to every plot, who holds it, what it earns and what it is worth.
 *
 * <p><b>Titles.</b> Each house (the town's to let, a household's own, a player's), each firm's premises (the town's,
 * let to the firm for its rent) and each homestead (a producer's field, holt, claim, boat or pens, its own since Market
 * Opening Day or its lease) has a title in the register, written up each morning from who holds it.
 *
 * <p><b>What land is worth.</b> What it earns in a week (a house its rent, a let premises the rent its firm pays, a
 * homestead half its last week's profit or a let plot's rent, whichever is more), twelve weeks of it, and more in a
 * growing town (a tenth more for every tenth its folk grew by in the week, a fifth at most; a shrinking town less).
 * Each morning's reckoning moves the value three tenths of the way, so one good week does not double it. The town's
 * land index is what the plots it had the week its market opened are worth now against what they were worth then.
 *
 * <p><b>Rents follow the land.</b> Once the market has been open a week, the town's house rents follow its land index:
 * the square root of it, never more than a twentieth up or down in a week, and never over a quarter above or under a
 * sixth below. <b>A rent-wage spiral</b> is watched for: rents are already reckoned on the field hand's wage, so a rise in
 * the land index while the wage index has risen too (both a tenth in a fortnight) holds rents where they are, and the
 * register says so.
 *
 * <p><b>Idle land for sale or lease.</b> A homestead whose hand has gone (died, left, taken another trade) stands idle;
 * after two mornings it is offered: a folk at that trade with no homestead of its own takes it on a lease from the town
 * (Privatisation.lease: a weekly payment, its own after fifty-two), and a firm's premises its firm has given up stand
 * on the register as to let. A player may buy the title to any plot the town holds (a premises or an idle homestead),
 * for its value, out of its own coin into the treasury: from then the rent its firm pays goes to the player, waiting at
 * the hall (/village capital land, buy, collect).
 */
public final class LandTitles {

    private LandTitles() {}

    /** Weeks of earnings a title is worth; the share of the way a morning moves it, in the hundred. */
    static final int YEARS_WEEKS = 12, STEP = 30;
    /** Mornings a homestead stands idle before it is offered. */
    static final int IDLE_DAYS = 2;
    /** Rents follow the land: at most so much a week, within so much of the old. */
    static final double RENT_STEP = 0.05, RENT_MOST = 1.25, RENT_LEAST = 5.0 / 6.0;

    static CompoundTag book(UUID village) {
        return CapitalBook.part(village, "land");
    }

    static CompoundTag titles(UUID village) {
        return Accounts.sub(book(village), "titles");
    }

    // ------------------------------------------------------------------ the morning

    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag bk = book(id);
        CompoundTag all = titles(id);
        java.util.Set<String> seen = new java.util.HashSet<>();
        double growth = growth(id, day);
        // The houses.
        for (Homes.Home h : Homes.homes(id).values()) {
            if (MineGround.reserved(id, h.anchor.getX(), h.anchor.getZ())) continue;   // [mine7] no title on the mine's ground
            String key = "house:" + h.anchor.asLong();
            seen.add(key);
            CompoundTag t = Accounts.sub(all, key);
            t.putString("kind", "house");
            t.putString("name", Homes.address(id, v, h));
            switch (h.tenure) {
                case OWNED -> { t.putString("holder", "folk"); t.putString("holderName", namesOf(id, h)); }
                case PLAYER -> { t.putString("holder", "player"); t.putString("holderName", h.landlordName); }
                default -> { t.putString("holder", "town"); t.putString("holderName", "the town"); }
            }
            int rent = Math.max(0, h.rent > 0 ? h.rent : Homes.rent(id, h));
            t.putLong("earns", rent * 700L);
            t.putBoolean("idle", h.members.isEmpty());
            value(t, rent * 700L, growth, 0);
        }
        // The firms' premises and the homesteads.
        for (Business b : Businesses.all(id)) {
            if (b.mirror() || "storehouse".equals(b.id)) continue;
            if (b.at != null && MineGround.reserved(id, b.at.getX(), b.at.getZ())) continue;   // [mine7] nor on the mine's ground
            boolean home = Privatisation.producerType(b.type);
            if (!home && !Enterprise.isFirm(b)) continue;
            String key = "firm:" + b.id;
            seen.add(key);
            CompoundTag t = Accounts.sub(all, key);
            t.putString("kind", home ? "homestead" : "premises");
            t.putString("name", b.name);
            Business.Day w = b.last(day - 1, 7);
            long rent = Businesses.rent(id, b) * 7;
            long earns = home ? Math.max(rent, Math.max(0, w.net()) / 2) : rent;
            t.putLong("earns", earns);
            String landlord = t.getString("landlord");
            if (!landlord.isEmpty()) {
                t.putString("holder", "player");
                t.putString("holderName", t.getString("landlordName"));
            } else if (home && !b.town()) {
                t.putString("holder", b.owners.isEmpty() ? "town" : "folk");
                t.putString("holderName", b.owners.isEmpty() ? "the town" : b.ownersWords());
            } else {
                t.putString("holder", "town");
                t.putString("holderName", "the town");
            }
            t.putBoolean("leased", Privatisation.leased(id, b));
            boolean idle = !b.open || home && Privatisation.handOf(id, b) == null;
            t.putInt("idleDays", idle ? t.getInt("idleDays") + 1 : 0);
            t.putBoolean("idle", idle);
            value(t, earns, growth, home ? Privatisation.TITLE_PENCE : 0);
            if (home && b.open && t.getInt("idleDays") >= IDLE_DAYS && landlord.isEmpty()) offerIdle(level, v, b, t, day);
        }
        PropertyMarket.titles(level, id, all, seen, growth);            // [orders17] the players' buildings on the register
        for (String k : new ArrayList<>(all.getAllKeys())) if (!seen.contains(k)) all.remove(k);
        // The index, and the rents that follow it.
        long sum = 0;
        for (String k : all.getAllKeys()) sum += all.getCompound(k).getLong("value");
        bk.putLong("sum", sum);
        bk.putInt("plots", all.size());
        // The index's base: each title's worth the week the market opened (the plots built since are not in it, so a
        // street of new cottages does not read as land getting cheaper).
        if (!bk.getBoolean("based") && sum > 0 && day - MarketMaker.openedDay(id) >= 7) {
            bk.putBoolean("based", true);
            for (String k : all.getAllKeys()) all.getCompound(k).putLong("base", Math.max(1, all.getCompound(k).getLong("value")));
        }
        if (day % 7 == 0 && bk.getLong("rentOn") != day + 1) {
            bk.putLong("rentOn", day + 1);
            rents(id, day);
        }
        CapitalBook.dirty();
    }

    /** A title's value moved three tenths of the way to twelve weeks' earnings, by the town's growth. */
    static void value(CompoundTag t, long weekEarns, double growth, long least) {
        long target = Math.max(least, Math.round(weekEarns * YEARS_WEEKS * growth));
        long was = t.getLong("value");
        t.putLong("value", was <= 0 ? target : was + (target - was) * STEP / 100);
    }

    /** The town's growth factor: a tenth more for every tenth its folk grew by in the week, within a fifth either way. */
    static double growth(UUID village, long day) {
        CompoundTag bk = book(village);
        ListTag heads = CapitalBook.list(bk, "heads");
        int now = Villages.headcount(village);
        if (heads.isEmpty() || ((CompoundTag) heads.get(heads.size() - 1)).getLong("day") != day) {
            CompoundTag h = new CompoundTag();
            h.putLong("day", day);
            h.putInt("n", now);
            heads.add(h);
            while (heads.size() > 8) heads.remove(0);
        }
        int then = ((CompoundTag) heads.get(0)).getInt("n");
        double g = then <= 0 ? 0 : (now - then) / (double) then;
        return Math.max(0.8, Math.min(1.2, 1.0 + g));
    }

    static String namesOf(UUID village, Homes.Home h) {
        List<String> out = new ArrayList<>();
        for (VillageFolkEntity f : Homes.loadedMembers(village, h)) if (!f.isBaby()) out.add(f.displayNameCap());
        return out.isEmpty() ? "its household" : String.join(" and ", out);
    }

    /** An idle homestead offered: a folk at its trade with none of its own takes it on a lease from the town. */
    static void offerIdle(ServerLevel level, Villages.Village v, Business b, CompoundTag t, long day) {
        UUID id = v.id();
        if (!t.getBoolean("offered")) {
            t.putBoolean("offered", true);
            CapitalBook.tell(id, day, b.name + " stands idle, its hand gone: the town offers it for sale or lease at " + Businesses.pence(t.getLong("value")));
        }
        StationTask want = null;
        for (StationTask s : StationTask.values()) if (Privatisation.typeFor(s) == b.type) { want = s; break; }
        if (want == null) return;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() != want) continue;
            if (Privatisation.firmOf(id, f) != null && Privatisation.firmOf(id, f).open && Privatisation.handOf(id, Privatisation.firmOf(id, f)) == f) continue;
            if (Privatisation.owns(id, f) > 0) continue;
            long value = Math.max(Privatisation.TITLE_PENCE, t.getLong("value"));
            Privatisation.lease(id, b, f, value, day);
            t.putBoolean("offered", false);
            t.putInt("idleDays", 0);
            Accounts.add(book(id), "leasedAll", 1);
            CapitalBook.tell(id, day, f.displayNameCap() + " took the idle " + b.name + " on a lease from the town (worth " + Businesses.pence(value) + ")");
            f.persona().remember(day, "took on " + b.name + " on a lease", 2);
            return;
        }
    }

    /** Rents follow the land, a twentieth a week at most, and held where they are in a rent-wage spiral. */
    static void rents(UUID village, long day) {
        CompoundTag bk = book(village);
        if (MarketMaker.openedDay(village) < 0 || day - MarketMaker.openedDay(village) < 7) return;
        double idx = index(village);
        double was = bk.contains("rentFactor") ? bk.getDouble("rentFactor") : 1.0;
        double target = Math.max(RENT_LEAST, Math.min(RENT_MOST, Math.sqrt(idx)));
        // The spiral: the wage index up a tenth in a fortnight, and the land too.
        double wage = wageIndex(village);
        double wageThen = bk.contains("wage2w") ? bk.getDouble("wage2w") : wage;
        ListTag wl = CapitalBook.list(bk, "wages");
        CompoundTag wt = new CompoundTag();
        wt.putLong("day", day);
        wt.putDouble("w", wage);
        wl.add(wt);
        while (wl.size() > 3) wl.remove(0);
        bk.putDouble("wage2w", ((CompoundTag) wl.get(0)).getDouble("w"));
        boolean spiral = target > was && wageThen > 0 && wage > wageThen * 1.1 && idx > 1.1;
        bk.putBoolean("spiral", spiral);
        double next = spiral ? was : Math.max(was - RENT_STEP, Math.min(was + RENT_STEP, target));
        bk.putDouble("rentFactor", next);
        if (Math.abs(next - was) >= 0.01) CapitalBook.tell(village, day, "rents " + (next > was ? "rose" : "fell") + " with the price of land: houses let at "
            + Math.round(next * 100) + " in the hundred of their old rent");
        else if (spiral && !bk.getBoolean("spiralTold")) CapitalBook.tell(village, day, "the council held rents where they are: land and wages both rising, a rent-wage spiral feared");
        bk.putBoolean("spiralTold", spiral);
    }

    /** The town's wage index: its living wage and the wages its hands are paid on, on average (LabourMarket). */
    static double wageIndex(UUID village) {
        return Math.max(1, LabourMarket.livingWage(village));
    }

    /** Homes.rent: the factor the land puts on a house's rent (1 until the market has been open a week). */
    public static double rentFactor(@Nullable UUID village) {
        if (village == null || !MarketMaker.live(village)) return 1.0;
        CompoundTag bk = book(village);
        return bk.contains("rentFactor") ? bk.getDouble("rentFactor") : 1.0;
    }

    /** The land index: a plot's worth today against the week the market opened (1 till then). */
    public static double index(UUID village) {
        CompoundTag all = titles(village);
        long now = 0, then = 0;
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            if (t.getLong("base") <= 0) continue;
            now += t.getLong("value");
            then += t.getLong("base");
        }
        return then <= 0 ? 1.0 : now / (double) then;
    }

    // ------------------------------------------------------------------ players

    /** The titles, the town's plots that can be bought first, numbered for /village capital land buy. */
    static List<Map.Entry<String, CompoundTag>> listing(UUID village) {
        List<Map.Entry<String, CompoundTag>> out = new ArrayList<>();
        CompoundTag all = titles(village);
        for (String k : all.getAllKeys()) out.add(Map.entry(k, all.getCompound(k)));
        out.sort(Comparator.comparing((Map.Entry<String, CompoundTag> e) -> !buyable(e.getKey(), e.getValue()))
            .thenComparing(e -> -e.getValue().getLong("value")).thenComparing(Map.Entry::getKey));
        return out;
    }

    static boolean buyable(String key, CompoundTag t) {
        return key.startsWith("firm:") && "town".equals(t.getString("holder")) && t.getString("landlord").isEmpty();
    }

    public static List<String> playerList(UUID village) {
        List<String> out = new ArrayList<>();
        int i = 1;
        for (Map.Entry<String, CompoundTag> e : listing(village)) {
            CompoundTag t = e.getValue();
            out.add(i++ + ". " + t.getString("name") + " (" + t.getString("kind") + "), " + t.getString("holderName") + ", worth " + Businesses.pence(t.getLong("value"))
                + ", earns " + Businesses.pence(t.getLong("earns")) + " a week" + (t.getBoolean("idle") ? ", idle" : "") + (buyable(e.getKey(), t) ? " - for sale" : ""));
            if (i > 30) break;
        }
        return out;
    }

    /** A player buys the title to the town's plot numbered so (its value, out of its coin into the treasury). */
    public static String playerBuys(ServerLevel level, Player p, UUID village, int n) {
        List<Map.Entry<String, CompoundTag>> l = listing(village);
        if (n < 1 || n > l.size()) return "No plot numbered " + n + ". /village capital land lists them.";
        Map.Entry<String, CompoundTag> e = l.get(n - 1);
        CompoundTag t = e.getValue();
        if (!buyable(e.getKey(), t)) return t.getString("name") + " is not the town's to sell (" + t.getString("holderName") + " holds it).";
        int price = (int) Math.max(1, (t.getLong("value") + 99) / 100);
        if (Market.coinsHeld(p) < price) return t.getString("name") + " costs " + price + Homes.coins(price) + "; you have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, price);
        CoinCensus.playerIn(village, price);
        Businesses.toTreasury(village, price * 100L);
        TownBudget.in(village, "fees", price * 100L);
        long day = level.getDayTime() / 24000L;
        t.putString("landlord", p.getUUID().toString());
        t.putString("landlordName", p.getName().getString());
        t.putString("holder", "player");
        t.putString("holderName", p.getName().getString());
        Accounts.add(book(village), "soldAll", price * 100L);
        CapitalBook.tell(village, day, p.getName().getString() + " bought the title to " + t.getString("name") + " from the town for " + price + Homes.coins(price));
        return "You hold the title to " + t.getString("name") + " now: its rent (" + Businesses.pence(t.getLong("earns")) + " a week) comes to you at the hall. "
            + "/village capital land collect.";
    }

    /** Businesses.overheads: a firm's rent to a player who holds its title instead of the treasury. True if it went there. */
    public static boolean toLandlord(ServerLevel level, UUID village, Business b, long rent, long day) {
        if (rent <= 0 || !MarketMaker.live(village)) return false;
        CompoundTag t = titles(village).getCompound("firm:" + b.id);
        String landlord = t.getString("landlord");
        if (landlord.isEmpty()) return false;
        long paid = Businesses.payOut(level, village, b, rent, day);
        Accounts.add(Accounts.sub(TownBonds.book(village), "due"), landlord, paid);
        Accounts.add(t, "paidAll", paid);
        CapitalBook.dirty();
        return true;
    }

    public static String playerLine(Player p, UUID village) {
        List<String> mine = new ArrayList<>();
        CompoundTag all = titles(village);
        for (String k : all.getAllKeys()) if (p.getUUID().toString().equals(all.getCompound(k).getString("landlord"))) mine.add(all.getCompound(k).getString("name"));
        return mine.isEmpty() ? "You hold no titles here." : "Your titles: " + String.join(", ", mine) + ".";
    }

    // ------------------------------------------------------------------ words

    public static String line(UUID village) {
        CompoundTag bk = book(village);
        int idle = 0;
        CompoundTag all = titles(village);
        for (String k : all.getAllKeys()) if (all.getCompound(k).getBoolean("idle")) idle++;
        return String.format(java.util.Locale.ROOT, "Land: %d titles worth %s, index %.2f, rents x%.2f%s, %d idle, %d leased from idle",
            bk.getInt("plots"), Businesses.pence(bk.getLong("sum")), index(village), rentFactor(village), bk.getBoolean("spiral") ? " (held: rent-wage spiral)" : "",
            idle, bk.getInt("leasedAll"));
    }

    static CompoundTag report(UUID village) {
        CompoundTag bk = book(village);
        CompoundTag t = new CompoundTag();
        t.putInt("plots", bk.getInt("plots"));
        t.putLong("sum", bk.getLong("sum"));
        t.putDouble("index", index(village));
        t.putDouble("rentFactor", rentFactor(village));
        t.putBoolean("spiral", bk.getBoolean("spiral"));
        t.putInt("leasedAll", bk.getInt("leasedAll"));
        t.putLong("soldAll", bk.getLong("soldAll"));
        ListTag rows = new ListTag();
        int i = 1;
        for (Map.Entry<String, CompoundTag> e : listing(village)) {
            CompoundTag r = e.getValue().copy();
            r.putInt("n", i++);
            r.putBoolean("forSale", buyable(e.getKey(), e.getValue()));
            rows.add(r);
        }
        t.put("rows", rows);
        return t;
    }

    // ------------------------------------------------------------------ tests

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    @Nullable
    public static CompoundTag titleForTests(UUID village, String key) {
        CompoundTag all = titles(village);
        return all.contains(key) ? all.getCompound(key) : null;
    }

    public static void baseForTests(UUID village, long base) {
        book(village).putBoolean("based", true);
        CompoundTag all = titles(village);
        for (String k : all.getAllKeys()) all.getCompound(k).putLong("base", base);
    }

    public static void rentsForTests(UUID village, long day) {
        rents(village, day);
    }

    static void resetForTests() {
    }
}
