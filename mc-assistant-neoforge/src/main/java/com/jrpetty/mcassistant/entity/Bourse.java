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
import java.util.Locale;
import java.util.UUID;

import com.jrpetty.mcassistant.entity.Business.Holder;
import com.jrpetty.mcassistant.entity.Business.Stake;

/**
 * [capital] The bourse: the shares of the town's firms, bought and sold at a small exchange on the market square.
 *
 * <p><b>Shares.</b> A firm's ownership is ten thousand parts (Business.Stake); a share is a hundred of them, a hundredth of
 * the firm, and its founders' stakes are its first shares. Whoever holds them draws its share of the week's profit
 * (Businesses.draw: the dividend), and stands last in a bankruptcy: what the receiver has left after the wages, the town,
 * the market and the lender is shared out by the shares, and then the shares are worth nothing (wiped).
 *
 * <p><b>Prices.</b> Each firm has a last price, a share's worth as the bourse last traded it. Folk value a share by what
 * the firm owns and earns: its worth (till, stock, kit, less what it owes) over its hundred shares, and eight weeks of its
 * last week's profit (a firm that lost money is worth its books). A firm is listed at that, and never under what its
 * founders put in a share. Each morning the folk post their orders: a holder short of coin, or holding a firm that lost
 * money last week, asks to sell at a tenth under what it reckons a share worth; a saver with coin over its week's keep
 * bids, at what it reckons a share worth, for the firm that earns best for its price. The bids and asks are matched (the
 * best bid against the cheapest ask, at the price between them), coin moves from purse to purse, the parts from holder
 * to holder, and the last price is set.
 * What is not matched stands for the day for players to take.
 *
 * <p><b>Raising capital.</b> A firm the lender will lend no more, short of coin, or a thriving one, may issue new shares
 * (up to a fifth more), offered at a twentieth under their worth: what they bring goes into its till, and its holders'
 * parts are shared down to make room. <b>Profit-sharing:</b> a firm that cannot match a better offer for a skilled hand may
 * keep it with two shares from its founder's stake instead (LabourMarket.counter).
 *
 * <p>Players trade too (/village capital shares buy|sell): a buy takes the cheapest asks standing; a sell takes the best
 * bids standing, or stands as an ask for three days. What a player's shares sell for waits at the hall (TownBonds' due).
 */
public final class Bourse {

    private Bourse() {}

    /** Parts in a share. */
    static final int SHARE = 100;
    /** A firm's worth reckons in so many weeks of its last week's profit. */
    static final int EARN_WEEKS = 8;
    /** The most a firm issues at once, in shares; the discount it offers them at, in the hundred. */
    static final int ISSUE_MOST = 20, ISSUE_DISCOUNT = 5;
    /** A player's ask stands so many days. */
    static final int PLAYER_ASK_DAYS = 3;

    static CompoundTag book(UUID village) {
        return CapitalBook.part(village, "bourse");
    }

    static CompoundTag firmBook(UUID village, String id) {
        return Accounts.sub(Accounts.sub(book(village), "firms"), id);
    }

    static ListTag orders(UUID village) {
        return CapitalBook.list(book(village), "orders");
    }

    /** The firms listed: the folk's and players' own firms, open. */
    public static List<Business> listed(UUID village) {
        List<Business> out = new ArrayList<>();
        for (Business b : Businesses.all(village)) if (b.open && Enterprise.isFirm(b) && !b.town() && !b.mirror()) out.add(b);
        return out;
    }

    // ------------------------------------------------------------------ worth and price

    /** What a share is worth to the folk, pennies: the firm's books over its hundred shares, and eight weeks' profit. */
    public static long fair(ServerLevel level, UUID village, Business b) {
        long day = Businesses.today(level);
        long book = Math.max(0, Enterprise.equity(village, b));
        long net = Math.max(0, b.last(day - 1, 7).net());
        return Math.max(1, (book + EARN_WEEKS * net) / 100);
    }

    /** A share's last price, pennies (its worth, the first time it is asked). */
    public static long last(ServerLevel level, UUID village, Business b) {
        CompoundTag f = firmBook(village, b.id);
        if (!f.contains("last")) {
            // Listed at what a share is worth, and never under what its founders put in a share.
            CompoundTag r = Enterprise.rec(village, b.id);
            f.putLong("last", Math.max(fair(level, village, b), r == null ? 1 : r.getLong("capital") / 100));
            f.putLong("listed", Businesses.today(level));
            CapitalBook.dirty();
        }
        return f.getLong("last");
    }

    /** All its shares at the last price, across the listed firms, pennies. */
    public static long capitalisation(ServerLevel level, UUID village) {
        long n = 0;
        for (Business b : listed(village)) n += last(level, village, b) * 100;
        return n;
    }

    /** How many shares (whole) a holder has in a firm. */
    static int shares(Business b, Holder h, @Nullable UUID who) {
        int n = 0;
        for (Stake s : b.owners) if (s.holder() == h && java.util.Objects.equals(s.who(), who)) n += s.parts();
        return n / SHARE;
    }

    /** What a holder's shares are worth at the last prices, pennies. */
    public static long worthOf(ServerLevel level, UUID village, UUID who) {
        long n = 0;
        for (Business b : listed(village)) {
            int parts = 0;
            for (Stake s : b.owners) if (who.equals(s.who())) parts += s.parts();
            n += last(level, village, b) * parts / SHARE;
        }
        return n;
    }

    // ------------------------------------------------------------------ the parts moved

    /** So many parts from one holder to another (the stakes merged by holder). False if the seller has not got them. */
    static boolean move(Business b, Holder fromH, @Nullable UUID from, Holder toH, @Nullable UUID to, String toName, int parts) {
        int have = 0;
        for (Stake s : b.owners) if (s.holder() == fromH && java.util.Objects.equals(s.who(), from)) have += s.parts();
        if (parts <= 0 || have < parts) return false;
        int left = parts;
        for (int i = 0; i < b.owners.size() && left > 0; i++) {
            Stake s = b.owners.get(i);
            if (s.holder() != fromH || !java.util.Objects.equals(s.who(), from)) continue;
            int k = Math.min(left, s.parts());
            left -= k;
            b.owners.set(i, new Stake(s.holder(), s.who(), s.name(), s.parts() - k));
        }
        b.owners.removeIf(s -> s.parts() <= 0);
        boolean merged = false;
        for (int i = 0; i < b.owners.size(); i++) {
            Stake s = b.owners.get(i);
            if (s.holder() == toH && java.util.Objects.equals(s.who(), to)) {
                b.owners.set(i, new Stake(s.holder(), s.who(), toName, s.parts() + parts));
                merged = true;
                break;
            }
        }
        if (!merged) b.owners.add(new Stake(toH, to, toName, parts));
        Businesses.dirty();
        return true;
    }

    /** New parts for a buyer of an issue: every holder's parts shared down to make room (the odd parts to the biggest). */
    static void dilute(Business b, Holder toH, @Nullable UUID to, String toName, int newParts) {
        int keep = Business.WHOLE - newParts;
        int sum = 0, big = -1, bigParts = -1;
        for (int i = 0; i < b.owners.size(); i++) {
            Stake s = b.owners.get(i);
            int p = (int) ((long) s.parts() * keep / Business.WHOLE);
            b.owners.set(i, new Stake(s.holder(), s.who(), s.name(), p));
            sum += p;
            if (p > bigParts) { bigParts = p; big = i; }
        }
        if (big >= 0 && sum < keep) {
            Stake s = b.owners.get(big);
            b.owners.set(big, new Stake(s.holder(), s.who(), s.name(), s.parts() + keep - sum));
        }
        b.owners.removeIf(s -> s.parts() <= 0);
        for (int i = 0; i < b.owners.size(); i++) {
            Stake s = b.owners.get(i);
            if (s.holder() == toH && java.util.Objects.equals(s.who(), to)) {
                b.owners.set(i, new Stake(s.holder(), s.who(), toName, s.parts() + newParts));
                Businesses.dirty();
                return;
            }
        }
        b.owners.add(new Stake(toH, to, toName, newParts));
        Businesses.dirty();
    }

    // ------------------------------------------------------------------ orders

    /** An order: a firm, buy or sell, its holder, how many shares, at most/least so many pennies a share; issued shares too. */
    static CompoundTag order(String firm, boolean buy, String kind, String who, String name, int n, long price, long day) {
        CompoundTag o = new CompoundTag();
        o.putString("firm", firm);
        o.putBoolean("buy", buy);
        o.putString("kind", kind);
        o.putString("who", who);
        o.putString("name", name);
        o.putInt("n", n);
        o.putLong("price", Math.max(1, price));
        o.putLong("day", day);
        return o;
    }

    /** The morning: yesterday's folk orders cleared (a player's stands its days), the folk's new orders, firms' issues, and
     *  the matching. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<Business> firms = listed(id);
        CompoundTag bk = book(id);
        ListTag ord = orders(id);
        ord.removeIf(t -> !"player".equals(((CompoundTag) t).getString("kind")) || day - ((CompoundTag) t).getLong("day") >= PLAYER_ASK_DAYS);
        for (Business b : firms) {
            CompoundTag f = firmBook(id, b.id);
            f.putLong("prev", last(level, id, b));
            f.putInt("vol", 0);
        }
        if (firms.isEmpty()) return;
        // The holders: a holder short of coin sells what covers it; one holding a firm that lost money last week sells half
        // (never a founder below half its firm).
        for (Business b : firms) {
            long fair = fair(level, id, b);
            long net = b.last(day - 1, 7).net();
            CompoundTag r = Enterprise.rec(id, b.id);
            UUID founder = r != null && r.hasUUID("founder") ? r.getUUID("founder") : null;
            for (Stake s : new ArrayList<>(b.owners)) {
                if (s.holder() != Holder.FOLK || s.who() == null) continue;
                VillageFolkEntity f = Homes.loaded(id, s.who());
                if (f == null) continue;
                int held = shares(b, Holder.FOLK, s.who());
                int floor = s.who().equals(founder) ? 51 : 0;
                int canSell = held - floor;
                if (canSell <= 0) continue;
                long price = Math.max(1, fair * 9 / 10);                  // a little under what it reckons a share worth
                int keep = CapitalBook.keep(id, f);
                int n = 0;
                if (f.purse() < keep / 2) n = (int) Math.min(canSell, Math.max(1, ((keep - f.purse()) * 100L + price - 1) / price));
                else if (net < 0 && !s.who().equals(founder)) n = Math.max(1, canSell / 2);
                if (n > 0) ord.add(order(b.id, false, "folk", s.who().toString(), f.displayNameCap(), n, price, day));
            }
            // A firm the lender will lend no more, short of coin, or one thriving, issues new shares.
            issue(level, id, b, r, day, ord);
        }
        // The savers: coin over the week's keep bids for the firm that earns best for its price.
        Business best = null;
        double bestYield = 0;
        for (Business b : firms) {
            long net = b.last(day - 1, 7).net();
            long price = Math.max(1, Math.max(last(level, id, b), fair(level, id, b)));
            double y = net / 100.0 / price;
            if (net > 0 && y > bestYield) { bestYield = y; best = b; }
        }
        if (best != null) {
            long fair = fair(level, id, best);
            long bid = Math.max(1, fair);                                       // no more than it reckons a share worth
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired()) continue;
                if (Bank.thrift(f) < 0 || Homes.rentsAndWantsToOwn(f)) continue;
                int over = f.purse() - CapitalBook.keep(id, f);
                if (over * 100L < bid * 2) continue;
                int n = (int) Math.min(10, over * 50L / bid);                  // half of what it has over, in shares
                if (n > 0) ord.add(order(best.id, true, "folk", f.getUUID().toString(), f.displayNameCap(), n, bid, day));
            }
        }
        match(level, id, day);
        bk.putLong("on", day);
        CapitalBook.dirty();
    }

    /** A firm's issue of new shares, if it wants capital: the lender will lend it nothing and it is short, or it is thriving. */
    static void issue(ServerLevel level, UUID village, Business b, @Nullable CompoundTag r, long day, ListTag ord) {
        if (r == null || day - b.opened < 7 || r.contains("petition")) return;
        for (Tag t : ord) if (((CompoundTag) t).getString("kind").equals("issue") && ((CompoundTag) t).getString("firm").equals(b.id)) return;
        CompoundTag f = firmBook(village, b.id);
        if (day - f.getLong("issuedOn") < 7 && f.contains("issuedOn")) return;
        boolean thriving = "thriving".equals(r.getString("status"));
        boolean short_ = b.till < Businesses.floatOf(b, day) && FolkCredit.judge(level, village, b, day).room() <= 0 && b.last(day - 1, 7).net() > 0;
        if (!thriving && !short_) return;
        if (f.getInt("issuedAll") >= 40) return;                                // never more than two fifths of it out in new shares
        long price = Math.max(1, fair(level, village, b) * (100 - ISSUE_DISCOUNT) / 100);
        int n = thriving ? ISSUE_MOST / 2 : ISSUE_MOST;
        ord.add(order(b.id, false, "issue", b.id, b.name + " (new shares)", n, price, day));
        f.putLong("issuedOn", day);
    }

    /** The bids and asks matched, firm by firm: the best bid against the cheapest ask, at the price between them. */
    static void match(ServerLevel level, UUID village, long day) {
        ListTag ord = orders(village);
        for (Business b : listed(village)) {
            List<CompoundTag> bids = new ArrayList<>(), asks = new ArrayList<>();
            for (Tag t : ord) {
                CompoundTag o = (CompoundTag) t;
                if (!o.getString("firm").equals(b.id) || o.getInt("n") <= 0) continue;
                (o.getBoolean("buy") ? bids : asks).add(o);
            }
            bids.sort(Comparator.comparingLong((CompoundTag o) -> -o.getLong("price")));
            asks.sort(Comparator.comparingLong((CompoundTag o) -> o.getLong("price")));
            for (CompoundTag bid : bids) {
                for (CompoundTag ask : asks) {
                    if (bid.getInt("n") <= 0) break;
                    if (ask.getInt("n") <= 0 || ask.getLong("price") > bid.getLong("price")) continue;
                    if (ask.getString("who").equals(bid.getString("who"))) continue;
                    long price = (ask.getLong("price") + bid.getLong("price")) / 2;
                    int n = Math.min(bid.getInt("n"), ask.getInt("n"));
                    n = trade(level, village, b, bid, ask, n, price, day);
                    if (n <= 0) continue;
                }
            }
        }
        ord.removeIf(t -> ((CompoundTag) t).getInt("n") <= 0);
    }

    /** One trade: the buyer's coin to the seller (whole coins), the parts to the buyer. Returns the shares that changed hands. */
    static int trade(ServerLevel level, UUID village, Business b, CompoundTag bid, CompoundTag ask, int n, long price, long day) {
        String bk = bid.getString("kind"), sk = ask.getString("kind");
        VillageFolkEntity buyer = "folk".equals(bk) ? folk(village, bid.getString("who")) : null;
        if ("folk".equals(bk)) {
            if (buyer == null) { bid.putInt("n", 0); return 0; }
            n = (int) Math.min(n, buyer.purse() * 100L / Math.max(1, price));
            if (n <= 0) { bid.putInt("n", 0); return 0; }
        } else if (!"player".equals(bk)) return 0;
        // The seller has the shares (an issue makes them).
        if (!"issue".equals(sk)) {
            Holder sh = "player".equals(sk) ? Holder.PLAYER : Holder.FOLK;
            UUID su = uuid(ask.getString("who"));
            n = Math.min(n, shares(b, sh, su));
            if (n <= 0) { ask.putInt("n", 0); return 0; }
        }
        int coins = (int) ((n * price + 99) / 100);
        // The buyer pays.
        if (buyer != null) {
            if (!buyer.spend(coins)) return 0;
        }
        // (A player buyer has paid already: playerBuys.)
        Holder bh = "player".equals(bk) ? Holder.PLAYER : Holder.FOLK;
        UUID bu = uuid(bid.getString("who"));
        String bname = bid.getString("name");
        if ("issue".equals(sk)) {
            int parts = n * SHARE * Business.WHOLE / (Business.WHOLE + n * SHARE);
            dilute(b, bh, bu, bname, parts);
            b.till += coins * 100L;
            b.today(day).capital += coins * 100L;
            b.allTime.capital += coins * 100L;
            CompoundTag f = firmBook(village, b.id);
            f.putInt("issuedAll", f.getInt("issuedAll") + n);
            Accounts.add(f, "raisedAll", coins * 100L);
            b.note(day, "issued " + n + " new shares to " + bname + " for " + coins + Homes.coins(coins) + ", into its till");
        } else {
            Holder sh = "player".equals(sk) ? Holder.PLAYER : Holder.FOLK;
            UUID su = uuid(ask.getString("who"));
            int had = "player".equals(sk) ? shares(b, Holder.PLAYER, su) : 0;   // [purse20]
            if (!move(b, sh, su, bh, bu, bname, n * SHARE)) {
                if (buyer != null) buyer.earn(coins);
                return 0;
            }
            if ("player".equals(sk)) {
                Accounts.add(Accounts.sub(TownBonds.book(village), "due"), ask.getString("who"), coins * 100L);
                PlayerFinance.accrued(ask.getString("who"), village, coins * 100L, PlayerFinance.Cat.CASHED);   // [purse20] waiting at the hall
                PlayerFinance.costSold(su, "share:" + b.id, n, had);
            } else {
                VillageFolkEntity seller = folk(village, ask.getString("who"));
                if (seller != null) seller.earn(coins);
                else Businesses.toTreasury(village, coins * 100L);              // (the seller gone: the town keeps it for its household)
            }
        }
        if ("player".equals(bk)) PlayerFinance.costAdd(bu, "share:" + b.id, coins * 100L);   // [purse20] what its shares cost it
        bid.putInt("n", bid.getInt("n") - n);
        ask.putInt("n", ask.getInt("n") - n);
        CompoundTag f = firmBook(village, b.id);
        long each = coins * 100L / n;
        f.putLong("last", each);
        f.putInt("vol", f.getInt("vol") + n);
        f.putInt("volAll", f.getInt("volAll") + n);
        f.putLong("high", Math.max(f.getLong("high"), each));
        f.putLong("low", f.getLong("low") <= 0 ? each : Math.min(f.getLong("low"), each));
        ListTag tr = CapitalBook.list(book(village), "trades");
        CompoundTag t = new CompoundTag();
        t.putLong("day", day);
        t.putString("firm", b.name);
        t.putString("buyer", bname);
        t.putString("seller", ask.getString("name"));
        t.putInt("n", n);
        t.putLong("each", each);
        tr.add(t);
        while (tr.size() > 20) tr.remove(0);
        if (buyer != null) buyer.persona().remember(day, "bought " + n + " shares of " + b.name, 1);
        CapitalBook.dirty();
        return n;
    }

    @Nullable
    static VillageFolkEntity folk(UUID village, String who) {
        UUID u = uuid(who);
        return u == null ? null : Homes.loaded(village, u);
    }

    @Nullable
    static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    // ------------------------------------------------------------------ dividends, retention, bankruptcy

    /** Businesses.draw: the week's dividend, what the holders drew. */
    static void dividend(UUID village, Business b, long given, long day) {
        if (given <= 0 || !Enterprise.isFirm(b)) return;
        CompoundTag f = firmBook(village, b.id);
        f.putLong("divWeek", given);
        Accounts.add(f, "divAll", given);
        CapitalBook.dirty();
    }

    /** LabourMarket.counter: the firm cannot match the offer, so it keeps its skilled hand with two shares out of its founder's
     *  stake (the founder keeping over half). True if it did. */
    public static boolean profitShare(ServerLevel level, UUID village, String employer, VillageFolkEntity hand, long day) {
        if (!MarketMaker.live(village) || !employer.startsWith("firm:")) return false;
        Business b = Businesses.of(village, employer.substring(5));
        if (b == null || !b.open || !Enterprise.isFirm(b) || b.ownedBy(hand.getUUID())) return false;
        if (b.last(day - 1, 7).net() <= 0) return false;                       // shares in a firm losing money keep nobody
        CompoundTag r = Enterprise.rec(village, b.id);
        UUID founder = r != null && r.hasUUID("founder") ? r.getUUID("founder") : null;
        if (founder == null || shares(b, Holder.FOLK, founder) < 53) return false;
        if (shares(b, Holder.FOLK, hand.getUUID()) >= 6) return false;          // it has had its share
        if (!move(b, Holder.FOLK, founder, Holder.FOLK, hand.getUUID(), hand.displayNameCap(), 2 * SHARE)) return false;
        Accounts.add(firmBook(village, b.id), "sharedAll", 2);
        b.note(day, "kept " + hand.displayNameCap() + " with two shares in the firm (profit-sharing), against a better offer");
        CapitalBook.tell(village, day, b.name + " kept " + hand.displayNameCap() + " with a share in its profits, against a better offer");
        hand.persona().remember(day, "was given a share in " + b.name, 3);
        return true;
    }

    /** Enterprise.bankrupt: what the receiver left after the creditors is shared by the shares; then they are worth nothing. */
    static void wiped(UUID village, Business b, long day) {
        CompoundTag f = firmBook(village, b.id);
        if (f.getBoolean("wiped")) return;
        f.putBoolean("wiped", true);
        long last = f.getLong("last");
        f.putLong("last", 0);
        List<String> who = new ArrayList<>();
        for (Stake s : b.owners) {
            if (s.parts() < SHARE) continue;
            who.add(s.name() + " " + s.parts() / SHARE);
            if (s.holder() == Holder.FOLK && s.who() != null) {
                VillageFolkEntity h = Homes.loaded(village, s.who());
                if (h != null) h.persona().remember(day, "lost my shares in " + b.name + " when it went bankrupt", -3);
            }
        }
        ListTag l = CapitalBook.list(book(village), "wiped");
        CompoundTag w = new CompoundTag();
        w.putLong("day", day);
        w.putString("firm", b.name);
        w.putLong("last", last);
        w.putString("holders", String.join(", ", who));
        l.add(w);
        while (l.size() > 10) l.remove(0);
        if (!who.isEmpty()) CapitalBook.tell(village, day, b.name + "'s shares were wiped out after its creditors were paid what could be: " + String.join(", ", who) + " shares");
        CapitalBook.dirty();
    }

    // ------------------------------------------------------------------ players

    @Nullable
    static Business find(UUID village, String name) {
        String n = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (Business b : listed(village)) if (b.name.toLowerCase(Locale.ROOT).startsWith(n) || b.id.equalsIgnoreCase(name)) return b;
        for (Business b : listed(village)) if (b.name.toLowerCase(Locale.ROOT).contains(n)) return b;
        return null;
    }

    /** A player buys so many shares of a firm off the cheapest asks standing (folk's, issues, players'). */
    public static String playerBuys(ServerLevel level, Player p, UUID village, String firm, int n) {
        Business b = find(village, firm);
        if (b == null) return "No firm by that name on the bourse. Everything you can do, its Capital tab, lists them.";
        long day = level.getDayTime() / 24000L;
        List<CompoundTag> asks = new ArrayList<>();
        for (Tag t : orders(village)) {
            CompoundTag o = (CompoundTag) t;
            if (o.getString("firm").equals(b.id) && !o.getBoolean("buy") && o.getInt("n") > 0 && !o.getString("who").equals(p.getUUID().toString())) asks.add(o);
        }
        if (asks.isEmpty()) return "Nobody is selling " + b.name + " today. Its last price is " + Businesses.pence(last(level, village, b)) + " a share.";
        asks.sort(Comparator.comparingLong((CompoundTag o) -> o.getLong("price")));
        int got = 0, spent = 0;
        for (CompoundTag ask : asks) {
            if (got >= n) break;
            int k = Math.min(n - got, ask.getInt("n"));
            int coins = (int) ((k * ask.getLong("price") + 99) / 100);
            if (Market.coinsHeld(p) < coins) {
                k = (int) (Market.coinsHeld(p) * 100L / Math.max(1, ask.getLong("price")));
                coins = (int) ((k * ask.getLong("price") + 99) / 100);
            }
            if (k <= 0) break;
            Market.payOut(p, coins);
            CoinCensus.playerIn(village, coins, p, PlayerFinance.Cat.INVESTED, "shares bought");
            CompoundTag bid = order(b.id, true, "player", p.getUUID().toString(), p.getName().getString(), k, ask.getLong("price"), day);
            int done = trade(level, village, b, bid, ask, k, ask.getLong("price"), day);
            int paidFor = (int) ((done * ask.getLong("price") + 99) / 100);
            if (paidFor < coins) {
                // What the trade did not take, back to the player.
                Dealings.giveCoins(p, coins - paidFor);
                CoinCensus.playerOut(village, coins - paidFor, p, PlayerFinance.Cat.INVESTED, "share money not spent, back");
            }
            got += done;
            spent += paidFor;
        }
        orders(village).removeIf(t -> ((CompoundTag) t).getInt("n") <= 0);
        if (got <= 0) return "You've not the coin for a share of " + b.name + " at " + Businesses.pence(asks.get(0).getLong("price")) + ".";
        CapitalBook.tell(village, day, p.getName().getString() + " bought " + got + " shares of " + b.name + " on the bourse");
        return "You bought " + got + " shares of " + b.name + " for " + spent + Homes.coins(spent) + ". You hold " + shares(b, Holder.PLAYER, p.getUUID())
            + " of its 100 shares; your dividends wait in its books for you (the firm's drawings).";
    }

    /** A player sells so many of its shares: to the best bids standing, the rest posted as an ask (at its price, or the last). */
    public static String playerSells(ServerLevel level, Player p, UUID village, String firm, int n, long pricePence) {
        Business b = find(village, firm);
        if (b == null) return "No firm by that name on the bourse.";
        int have = shares(b, Holder.PLAYER, p.getUUID());
        n = Math.min(n, have);
        if (n <= 0) return "You hold no shares of " + b.name + ".";
        long day = level.getDayTime() / 24000L;
        long price = pricePence > 0 ? pricePence : last(level, village, b);
        CompoundTag ask = order(b.id, false, "player", p.getUUID().toString(), p.getName().getString(), n, price, day);
        orders(village).add(ask);
        int before = n;
        List<CompoundTag> bids = new ArrayList<>();
        for (Tag t : orders(village)) {
            CompoundTag o = (CompoundTag) t;
            if (o.getString("firm").equals(b.id) && o.getBoolean("buy") && o.getInt("n") > 0 && o.getLong("price") >= price) bids.add(o);
        }
        bids.sort(Comparator.comparingLong((CompoundTag o) -> -o.getLong("price")));
        for (CompoundTag bid : bids) {
            if (ask.getInt("n") <= 0) break;
            trade(level, village, b, bid, ask, Math.min(bid.getInt("n"), ask.getInt("n")), bid.getLong("price"), day);
        }
        orders(village).removeIf(t -> ((CompoundTag) t).getInt("n") <= 0);
        int sold = before - ask.getInt("n");
        return (sold > 0 ? "Sold " + sold + " shares of " + b.name + "; the coin waits for you at the hall (the Town Hall's Money tab: Collect what's due). " : "")
            + (ask.getInt("n") > 0 ? ask.getInt("n") + " stand for sale at " + Businesses.pence(price) + " a share for " + PLAYER_ASK_DAYS + " days." : "");
    }

    // ------------------------------------------------------------------ words

    /** The folk card's line, or "". */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !MarketMaker.live(id)) return "";
        List<String> out = new ArrayList<>();
        for (Business b : listed(id)) {
            int n = shares(b, Holder.FOLK, f.getUUID());
            if (n > 0) out.add(n + " of " + b.name);
        }
        return out.isEmpty() ? "" : "Holds shares: " + String.join(", ", out);
    }

    public static String line(ServerLevel level, UUID village) {
        StringBuilder sb = new StringBuilder("Bourse:");
        for (Business b : listed(village)) {
            CompoundTag f = firmBook(village, b.id);
            sb.append(' ').append(b.name).append(' ').append(Businesses.pence(last(level, village, b))).append(" (worth ").append(Businesses.pence(fair(level, village, b)))
                .append(", vol ").append(f.getInt("vol")).append("/").append(f.getInt("volAll")).append(", holders ").append(b.owners.size())
                .append(", div ").append(Businesses.pence(f.getLong("divAll"))).append(", issued ").append(f.getInt("issuedAll")).append(");");
        }
        return sb.toString();
    }

    static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag t = new CompoundTag();
        ListTag rows = new ListTag();
        for (Business b : listed(village)) {
            CompoundTag f = firmBook(village, b.id);
            CompoundTag r = new CompoundTag();
            r.putString("name", b.name);
            r.putLong("last", last(level, village, b));
            r.putLong("prev", f.contains("prev") ? f.getLong("prev") : last(level, village, b));
            r.putLong("fair", fair(level, village, b));
            r.putInt("vol", f.getInt("vol"));
            r.putInt("volAll", f.getInt("volAll"));
            r.putLong("divWeek", f.getLong("divWeek"));
            r.putLong("divAll", f.getLong("divAll"));
            r.putInt("issued", f.getInt("issuedAll"));
            int bids = 0, asks = 0;
            for (Tag o : orders(village)) {
                CompoundTag oc = (CompoundTag) o;
                if (!oc.getString("firm").equals(b.id)) continue;
                if (oc.getBoolean("buy")) bids += oc.getInt("n"); else asks += oc.getInt("n");
            }
            r.putInt("bids", bids);
            r.putInt("asks", asks);
            ListTag hs = new ListTag();
            for (Stake s : b.owners) {
                CompoundTag h = new CompoundTag();
                h.putString("name", s.name() + (s.holder() == Holder.PLAYER ? " (player)" : s.holder() == Holder.TOWN ? " (the town)" : ""));
                h.putDouble("shares", s.parts() / (double) SHARE);
                hs.add(h);
            }
            r.put("holders", hs);
            rows.add(r);
        }
        t.put("rows", rows);
        t.put("trades", CapitalBook.list(book(village), "trades").copy());
        t.put("wiped", CapitalBook.list(book(village), "wiped").copy());
        t.putLong("cap", capitalisation(level, village));
        return t;
    }

    // ------------------------------------------------------------------ tests

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    public static int sharesForTests(Business b, @Nullable UUID who) {
        int n = 0;
        for (Stake s : b.owners) if (java.util.Objects.equals(s.who(), who)) n += s.parts();
        return n;
    }

    public static void orderForTests(UUID village, String firm, boolean buy, VillageFolkEntity f, int n, long price, long day) {
        orders(village).add(order(firm, buy, "folk", f.getUUID().toString(), f.displayNameCap(), n, price, day));
    }

    public static void matchForTests(ServerLevel level, UUID village, long day) {
        match(level, village, day);
    }

    public static CompoundTag firmForTests(UUID village, String id) {
        return firmBook(village, id);
    }

    public static int ordersForTests(UUID village) {
        return orders(village).size();
    }

    static void resetForTests() {
    }
}
