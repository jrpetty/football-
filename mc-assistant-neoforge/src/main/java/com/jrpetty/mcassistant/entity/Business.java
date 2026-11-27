package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [business-books] One business and its books: who owns it, its till, its account with the town's counting house, what
 * it owes, its stock and what it cost, and its days. Everything in pennies (a hundred to the coin), so a carrot at half
 * a coin is fifty pennies and nothing rounds away. Plain data with its own save; what it does is Businesses.
 *
 * <p>Its owners are a set of stakes that come to the whole (ten thousand parts): a proprietor holds all of it, a couple
 * half each, a player who bought in a share. The drawings are shared out by stake, and so is a call for capital.
 */
public final class Business {

    /** What sort of place it is: its name, the buildings that make it, its size (for its rent), and who keeps its money. */
    public enum Type {
        CAFE("Café", Size.MEDIUM, Keeping.OWN, "cafe"),
        RESTAURANT("Restaurant", Size.LARGE, Keeping.OWN, "restaurant"),
        TAVERN("Tavern", Size.MEDIUM, Keeping.OWN, "tavern"),
        INN("Inn", Size.LARGE, Keeping.OWN, "inn"),
        BAKERY("Bakery", Size.MEDIUM, Keeping.OWN, "bakery"),
        SHOP("Shop", Size.MEDIUM, Keeping.OWN, "store", "shop", "grandbazaar"),
        BREWERY("Brewery", Size.MEDIUM, Keeping.OWN, "brewery"),
        SMITHY("Smithy", Size.MEDIUM, Keeping.OWN, "smithy", "foundry", "greatforge"),
        TAILOR("Tailor's workshop", Size.MEDIUM, Keeping.OWN, "workshop"),
        FLETCHER("Fletcher's", Size.SMALL, Keeping.OWN, "fletcher"),
        APIARY("Apiary", Size.SMALL, Keeping.OWN, "apiary"),
        ENCHANTER("Enchanter's tower", Size.SMALL, Keeping.OWN, "enchanter"),
        POWDER("Powder hut", Size.SMALL, Keeping.OWN, "powderhut"),
        MAPROOM("Map room", Size.SMALL, Keeping.OWN, "maproom"),
        BUTCHER("Hunter's lodge", Size.SMALL, Keeping.OWN, "huntlodge"),
        MILL("Mill", Size.SMALL, Keeping.OWN, "windmill", "watermill"),
        FISHMARKET("Fish market", Size.SMALL, Keeping.OWN),
        THEATRE("Theatre", Size.LARGE, Keeping.OWN, "playhouse", "theatre"),
        STABLES("Stables", Size.MEDIUM, Keeping.OWN, "stable"),
        // The town's own: its storehouse (the public warehouse and wholesaler), its market, its harbour office and its
        // lettings. Their money is the treasury's: their books are kept the same, their coin is the town's.
        STOREHOUSE("The town stores", Size.LARGE, Keeping.TOWN, "storehouse", "storage"),
        MARKET("The market", Size.MEDIUM, Keeping.TOWN, "market"),
        HARBOUR("Harbour office", Size.MEDIUM, Keeping.TOWN, "harbour"),
        TRADING_POST("Trading post", Size.SMALL, Keeping.TOWN, "tradingpost"),
        AUCTION("Auction house", Size.LARGE, Keeping.TOWN, "auction"),
        // Kept by their own books elsewhere (the bank's vault, a roadside keeper's till, a player's stall): mirrored here.
        BANK("Bank", Size.MEDIUM, Keeping.MIRROR, "bank"),
        STALL("Market stall", Size.SMALL, Keeping.MIRROR),
        // [econ-1a] The producers: a folk's field, holt, claim, boat, pens, hunting or furnace, one business a hand (its
        // owner-operator's, or the town's until it is sold to its hand: Privatisation). No building of their own to rent.
        FARM("Farm", Size.YARD, Keeping.OWN),
        WOODLOT("Woodlot", Size.YARD, Keeping.OWN),
        MINE("Mine", Size.YARD, Keeping.OWN),
        FISHERY("Fishery", Size.YARD, Keeping.OWN),
        RANCH("Ranch", Size.YARD, Keeping.OWN),
        HUNTING("Hunting", Size.YARD, Keeping.OWN),
        FOUNDRY("Foundry", Size.YARD, Keeping.OWN),
        // [econ-1b] A business a folk opened of its own (Enterprise): its kind (BusinessTypes) in the firm's record.
        ENTERPRISE("Firm", Size.SMALL, Keeping.OWN);

        public final String title;
        public final Size size;
        public final Keeping keeping;
        public final String[] structures;

        Type(String title, Size size, Keeping keeping, String... structures) {
            this.title = title;
            this.size = size;
            this.keeping = keeping;
            this.structures = structures;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        @Nullable
        public static Type of(String id) {
            for (Type t : values()) if (t.id().equalsIgnoreCase(id)) return t;
            return null;
        }
    }

    /** A building's size, for its rent: a day's rent in a hamlet, in pennies (a village pays half as much again...). */
    public enum Size {
        SMALL(60), MEDIUM(120), LARGE(200),
        // [econ-1a] A producer's field, holt or boat: titled to it, no rent (Privatisation's lease is its price).
        YARD(0);

        public final int rentPence;

        Size(int rentPence) {
            this.rentPence = rentPence;
        }
    }

    /** Whose money: its own till (a private business), the treasury's (the town's own), or another book's (mirrored). */
    public enum Keeping { OWN, TOWN, MIRROR }

    /** Who holds a stake. */
    public enum Holder { FOLK, PLAYER, TOWN }

    /** One owner's stake: who, and how many of the ten thousand parts. */
    public record Stake(Holder holder, @Nullable UUID who, String name, int parts) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("h", holder.name());
            if (who != null) t.putUUID("w", who);
            t.putString("n", name);
            t.putInt("p", parts);
            return t;
        }

        static Stake load(CompoundTag t) {
            Holder h;
            try { h = Holder.valueOf(t.getString("h")); } catch (IllegalArgumentException e) { h = Holder.TOWN; }
            return new Stake(h, t.hasUUID("w") ? t.getUUID("w") : null, t.getString("n"), t.getInt("p"));
        }
    }

    /** The whole of a business, in parts. */
    public static final int WHOLE = 10000;

    /** So many of a thing in stock, and what they cost all told (pennies): a weighted average. */
    public static final class Lot {
        public long qty, cost;

        public double each() {
            return qty <= 0 ? 0 : cost / (double) qty;
        }

        /** Take {@code n} out at the average; returns their cost. */
        long take(long n) {
            if (n <= 0 || qty <= 0) return 0;
            long k = Math.min(n, qty);
            long c = k >= qty ? cost : Math.round(cost * (k / (double) qty));
            qty -= k;
            cost -= c;
            if (qty <= 0) { qty = 0; cost = 0; }
            return c;
        }

        void add(long n, long pence) {
            if (n <= 0) return;
            qty += n;
            cost += Math.max(0, pence);
        }
    }

    /** One line of a day's sales: units, revenue, cost of goods (pennies). */
    public static final class Line {
        public long units, revenue, cogs, used, made;
        /** [biz30] Units into its stock (bought in or made) and sales missed for want of it on the shelf. */
        public long in, missed;
    }

    /** A day's books (pennies). */
    public static final class Day {
        public long day;
        public long revenue, cogs, wages, rent, other, taxes, losses, interest, drawings, capital, bought, toTown;
        public final Map<String, Line> lines = new LinkedHashMap<>();
        /** Each hand's pay today: name and pence, by its id. */
        public final Map<UUID, String> staffNames = new HashMap<>();
        public final Map<UUID, Long> staffPay = new HashMap<>();
        public long cashEnd;
        /** [biz30] Its trade: sales made (transactions), the customers among them (distinct buyers, players apart),
         *  those who had bought before, the sales by the clock hour, and the minutes it was open and kept. */
        public long trans, customers, players, repeats, openMin, keptMin;
        public final int[] hours = new int[24];
        /** [biz30] Today's buyers (a hash of each name), to count each once and know a returning one. */
        public final java.util.Set<Integer> buyers = new java.util.HashSet<>();

        /** What is left when every cost is met. */
        public long net() {
            return revenue - cogs - wages - rent - other - taxes - losses - interest;
        }

        Line line(String key) {
            return lines.computeIfAbsent(key, k -> new Line());
        }

        /** [biz30] Tests: a ware's line, opened if need be. */
        public Line lineForTests(String key) {
            return line(key);
        }

        public void addAll(Day d) {
            revenue += d.revenue; cogs += d.cogs; wages += d.wages; rent += d.rent; other += d.other; taxes += d.taxes;
            losses += d.losses; interest += d.interest; drawings += d.drawings; capital += d.capital; bought += d.bought; toTown += d.toTown;
            trans += d.trans; customers += d.customers; players += d.players; repeats += d.repeats; openMin += d.openMin; keptMin += d.keptMin;
            for (int i = 0; i < 24; i++) hours[i] += d.hours[i];
            for (Map.Entry<String, Line> e : d.lines.entrySet()) {
                Line l = line(e.getKey());
                l.units += e.getValue().units;
                l.revenue += e.getValue().revenue;
                l.cogs += e.getValue().cogs;
                l.used += e.getValue().used;
                l.made += e.getValue().made;
                l.in += e.getValue().in;
                l.missed += e.getValue().missed;
            }
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("day", day);
            t.putLongArray("m", new long[]{ revenue, cogs, wages, rent, other, taxes, losses, interest, drawings, capital, bought, toTown, cashEnd });
            CompoundTag ls = new CompoundTag();
            for (Map.Entry<String, Line> e : lines.entrySet()) {
                Line l = e.getValue();
                ls.putLongArray(e.getKey(), l.in == 0 && l.missed == 0 ? new long[]{ l.units, l.revenue, l.cogs, l.used, l.made }
                    : new long[]{ l.units, l.revenue, l.cogs, l.used, l.made, l.in, l.missed });
            }
            t.put("l", ls);
            // [biz30] its trade, only when it had any (a quiet day costs nothing to keep)
            if (trans != 0 || openMin != 0 || keptMin != 0) t.putLongArray("t", new long[]{ trans, customers, players, repeats, openMin, keptMin });
            boolean anyHour = false;
            for (int h : hours) if (h != 0) { anyHour = true; break; }
            if (anyHour) t.putIntArray("h", hours);
            if (!buyers.isEmpty()) t.putIntArray("b", buyers.stream().mapToInt(Integer::intValue).toArray());
            ListTag st = new ListTag();
            for (Map.Entry<UUID, Long> e : staffPay.entrySet()) {
                CompoundTag s = new CompoundTag();
                s.putUUID("w", e.getKey());
                s.putString("n", staffNames.getOrDefault(e.getKey(), "?"));
                s.putLong("p", e.getValue());
                st.add(s);
            }
            t.put("s", st);
            return t;
        }

        static Day load(CompoundTag t) {
            Day d = new Day();
            d.day = t.getLong("day");
            long[] m = java.util.Arrays.copyOf(t.getLongArray("m"), 13);
            d.revenue = m[0]; d.cogs = m[1]; d.wages = m[2]; d.rent = m[3]; d.other = m[4]; d.taxes = m[5]; d.losses = m[6];
            d.interest = m[7]; d.drawings = m[8]; d.capital = m[9]; d.bought = m[10]; d.toTown = m[11]; d.cashEnd = m[12];
            CompoundTag ls = t.getCompound("l");
            for (String k : ls.getAllKeys()) {
                long[] a = java.util.Arrays.copyOf(ls.getLongArray(k), 7);
                Line l = d.line(k);
                l.units = a[0]; l.revenue = a[1]; l.cogs = a[2]; l.used = a[3]; l.made = a[4]; l.in = a[5]; l.missed = a[6];
            }
            long[] tr = java.util.Arrays.copyOf(t.getLongArray("t"), 6);
            d.trans = tr[0]; d.customers = tr[1]; d.players = tr[2]; d.repeats = tr[3]; d.openMin = tr[4]; d.keptMin = tr[5];
            int[] hs = t.getIntArray("h");
            for (int i = 0; i < Math.min(24, hs.length); i++) d.hours[i] = hs[i];
            for (int b : t.getIntArray("b")) d.buyers.add(b);
            for (Tag e : t.getList("s", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) e;
                if (!s.hasUUID("w")) continue;
                d.staffNames.put(s.getUUID("w"), s.getString("n"));
                d.staffPay.put(s.getUUID("w"), s.getLong("p"));
            }
            return d;
        }
    }

    /** A purchase: when, from whom, what, how many, at what a piece, the discount and the total (pennies). */
    public record Order(long day, String supplier, String item, long qty, long unitHundredths, long discount, long total) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("d", day);
            t.putString("s", supplier);
            t.putString("i", item);
            t.putLongArray("v", new long[]{ qty, unitHundredths, discount, total });
            return t;
        }

        static Order load(CompoundTag t) {
            long[] v = java.util.Arrays.copyOf(t.getLongArray("v"), 4);
            return new Order(t.getLong("d"), t.getString("s"), t.getString("i"), v[0], v[1], v[2], v[3]);
        }
    }

    // ------------------------------------------------------------------ the record

    public final String id;
    public final Type type;
    public String name;
    @Nullable public BlockPos at;
    public long opened;
    /** Trading, or wound up (its premises back with the town, waiting for another to take it on). */
    public boolean open = true;
    public final List<Stake> owners = new ArrayList<>();
    /** The coin in its till, in pennies. */
    public long till;
    /** Its account with the town's counting house: over nought the town owes it (takings held), under it owes the town. */
    public long account;
    /** What it owes on its loan, who lent it ("bank" or "town"), and the interest a week in hundredths of a percent. */
    public long loan;
    public String lender = "";
    public int rateBp = 400;
    /** A player owner's drawings not yet taken (pennies), by the player. */
    public final Map<UUID, Long> ownersDue = new HashMap<>();
    /** The markup it asks over a piece's full cost (materials, the hands' time, the overheads). */
    public double markup = 0.35;
    /** Its stock, by ware (Stockroom.key). */
    public final Map<String, Lot> stock = new LinkedHashMap<>();
    /** The last twenty-eight days, oldest first; and every day since it opened. */
    public final List<Day> days = new ArrayList<>();
    /** [biz30] Days past the last {@link #DAYS_KEPT}, folded into weekly totals (each keyed by its week's first day), a
     *  year and more of them; past that only {@link #allTime} holds them. */
    public final List<Day> weeks = new ArrayList<>();
    public static final int DAYS_KEPT = 56, WEEKS_KEPT = 60;
    public final Day allTime = new Day();
    public final List<Order> orders = new ArrayList<>();
    /** What happened: its price changes and why, new owners, losses, a hand put on short time ("day|words"). */
    public final List<String> notes = new ArrayList<>();
    /** Days in a row its week ran at a loss; the hands it has put on short time; wares it no longer orders. */
    public int lossDays;
    public final java.util.Set<UUID> shortTime = new java.util.HashSet<>();
    public final java.util.Set<String> dropped = new java.util.HashSet<>();
    /** The last batch of each ware it made, costed: "3 wheat at 0.08c, coal's share 0.01c: 0.27c a loaf" (by ware). */
    public final Map<String, String> cards = new LinkedHashMap<>();
    /** A player who has run it into the ground may not be the one to buy it straight back: the day it closed. */
    public long closedDay = -1;

    // ------------------------------------------------------------------ [featlife] its hours

    /**
     * [featlife] When it is open: the days of the week (a bit each, Monday the lowest: Calendar.dow), the hour it opens and
     * the hour it shuts (the clock's, six in the morning being the day's start), and who set them and why, in a few words.
     * Its hands work these hours and no others (WorkHours.shift); its counter serves in them. Set by its owner's character
     * when a folk owns it (WorkHours.choose), by the player who owns it on the hours screen, or the type's own (forType).
     */
    public record Hours(int days, int open, int close, String why) {
        public static final int WEEKDAYS = 0b0011111, SIX_DAYS = 0b0111111, ALL_WEEK = 0b1111111, MON_THU = 0b0001111;

        public boolean openOn(int dow) {
            return (days >> Math.floorMod(dow, 7) & 1) != 0;
        }

        public boolean openAt(int dow, int hour) {
            return openOn(dow) && hour >= open && hour < close;
        }

        public int dayCount() {
            return Integer.bitCount(days & ALL_WEEK);
        }

        public Hours withWhy(String w) {
            return new Hours(days, open, close, w);
        }

        /** "Mon–Sat, 7:00–18:00", "Every day, 10:00–23:00". */
        public String words() {
            return daysWords() + ", " + open + ":00–" + close + ":00";
        }

        public String daysWords() {
            int d = days & ALL_WEEK;
            if (d == ALL_WEEK) return "Every day";
            if (d == 0) return "Shut";
            // a run of days from Monday: "Mon–Fri"
            int first = Integer.numberOfTrailingZeros(d), last = 31 - Integer.numberOfLeadingZeros(d);
            if (Integer.bitCount(d) == last - first + 1) {
                return first == last ? Calendar.Day.values()[first].shortWord
                    : Calendar.Day.values()[first].shortWord + "–" + Calendar.Day.values()[last].shortWord;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) if (openOn(i)) sb.append(sb.length() == 0 ? "" : ", ").append(Calendar.Day.values()[i].shortWord);
            return sb.toString();
        }

        /** The hours a business of this type keeps unless somebody chooses others. */
        public static Hours forType(Type t) {
            return switch (t) {
                case TAVERN, INN, RESTAURANT, THEATRE -> new Hours(ALL_WEEK, 10, 23, "a tavern's hours: every day");
                case CAFE -> new Hours(ALL_WEEK, 7, 17, "a café's hours: every day");
                case SHOP, BAKERY, MARKET, FISHMARKET, STALL, TRADING_POST, AUCTION, STOREHOUSE, HARBOUR, STABLES ->
                    new Hours(SIX_DAYS, 6, 18, "a shop's hours: open on market day, shut on the day of rest");
                case BANK -> new Hours(WEEKDAYS, 7, 17, "the bank's hours");
                default -> new Hours(WEEKDAYS, 6, 18, "the working week: Monday to Friday");
            };
        }

        int[] save() {
            return new int[]{ days, open, close };
        }
    }

    /** [rota] Its staff rota (Rota): set by its owner or manager, or built by a folk owner's character; null for none. */
    @Nullable public Rota rota;

    /** [featlife] Its own hours, if any were chosen; else the type's. */
    @Nullable Hours hours;

    /** [featlife] When it is open (the Business Core hub reads and edits these: setHours). */
    public Hours hours() {
        return hours != null ? hours : Hours.forType(type);
    }

    /** [featlife] Were its hours chosen (by its owner or a player), rather than the type's? */
    public boolean hoursChosen() {
        return hours != null;
    }

    /** [featlife] New hours, from its owner or the hub. Days are kept to the week; the hours to 5:00–24:00, open before shut. */
    public void setHours(Hours h) {
        if (h == null) { hours = null; return; }
        int open = Math.max(5, Math.min(22, h.open())), close = Math.max(open + 1, Math.min(24, h.close()));
        hours = new Hours(h.days() & Hours.ALL_WEEK, open, close, h.why() == null ? "" : h.why());
    }

    public Business(String id, Type type, String name) {
        this.id = id;
        this.type = type;
        this.name = name;
    }

    /** Is this its town's own (its coin the treasury's)? */
    public boolean town() {
        return type.keeping == Keeping.TOWN || owners.isEmpty() || owners.size() == 1 && owners.get(0).holder() == Holder.TOWN;
    }

    /** Are its books mirrored from another's (the bank, a player's stall)? */
    public boolean mirror() {
        return type.keeping == Keeping.MIRROR;
    }

    /** The day's book, opened if need be ([biz30] eight weeks kept day by day, older days folded into their week). */
    public Day today(long day) {
        if (!days.isEmpty() && days.get(days.size() - 1).day == day) return days.get(days.size() - 1);
        // [biz30] an earlier day's book is that day's, not a second one after today's (which hid the first from its data)
        for (int i = days.size() - 1; i >= 0; i--) if (days.get(i).day == day) return days.get(i);
        Day d = new Day();
        d.day = day;
        int at = days.size();
        while (at > 0 && days.get(at - 1).day > day) at--;
        days.add(at, d);
        while (days.size() > DAYS_KEPT) fold(days.remove(0));
        return d;
    }

    /** [biz30] An old day into its week's total (its buyers' names let go: a week keeps counts only). */
    void fold(Day old) {
        long wk = Math.floorDiv(old.day, 7) * 7;
        Day found = weeks.isEmpty() || weeks.get(weeks.size() - 1).day != wk ? null : weeks.get(weeks.size() - 1);
        if (found == null) {
            found = new Day();
            found.day = wk;
            weeks.add(found);
            while (weeks.size() > WEEKS_KEPT) weeks.remove(0);
        }
        final Day w = found;
        w.addAll(old);
        w.cashEnd = old.cashEnd;
        w.staffNames.putAll(old.staffNames);
        old.staffPay.forEach((k, v) -> w.staffPay.merge(k, v, Long::sum));
    }

    /** [biz30] Its buyer of this sale noted on today's book: a new customer of the day, a player, one who had bought before. */
    public void buyer(Day d, @Nullable String who, boolean player) {
        d.trans++;
        if (who == null || who.isEmpty()) return;
        int h = who.hashCode();
        if (!d.buyers.add(h)) return;
        d.customers++;
        if (player) d.players++;
        for (Day e : days) if (e != d && e.buyers.contains(h)) { d.repeats++; break; }
    }

    /** The last {@code n} days' books added together (today among them). */
    public Day last(long today, int n) {
        Day sum = new Day();
        sum.day = today;
        for (Day d : days) if (today - d.day < n && d.day <= today) sum.addAll(d);
        return sum;
    }

    /** Its owners in words: "Wren Ashby", "Wren and Tam Ashby (half each)", "the town". */
    public String ownersWords() {
        if (owners.isEmpty()) return "the town";
        if (owners.size() == 1) return owners.get(0).holder() == Holder.TOWN ? "the town" : owners.get(0).name();
        List<String> out = new ArrayList<>();
        for (Stake s : owners) out.add(s.name() + " " + (s.parts() * 100 / WHOLE) + "%");
        return String.join(", ", out);
    }

    /** Is this folk or player one of its owners? */
    public boolean ownedBy(@Nullable UUID who) {
        if (who == null) return false;
        for (Stake s : owners) if (who.equals(s.who())) return true;
        return false;
    }

    /** What it holds in stock, at what it cost (pennies). */
    public long stockCost() {
        long n = 0;
        for (Lot l : stock.values()) n += l.cost;
        return n;
    }

    public long stockUnits() {
        long n = 0;
        for (Lot l : stock.values()) n += l.qty;
        return n;
    }

    void note(long day, String words) {
        notes.add(day + "|" + words);
        while (notes.size() > 30) notes.remove(0);
    }

    // ------------------------------------------------------------------ keeping

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putString("type", type.name());
        t.putString("name", name);
        if (at != null) t.putLong("at", at.asLong());
        t.putLong("opened", opened);
        t.putBoolean("open", open);
        ListTag own = new ListTag();
        for (Stake s : owners) own.add(s.save());
        t.put("owners", own);
        t.putLong("till", till);
        t.putLong("account", account);
        t.putLong("loan", loan);
        t.putString("lender", lender);
        t.putInt("rate", rateBp);
        CompoundTag due = new CompoundTag();
        for (Map.Entry<UUID, Long> e : ownersDue.entrySet()) due.putLong(e.getKey().toString(), e.getValue());
        t.put("due", due);
        t.putDouble("markup", markup);
        CompoundTag st = new CompoundTag();
        for (Map.Entry<String, Lot> e : stock.entrySet()) if (e.getValue().qty > 0) st.putLongArray(e.getKey(), new long[]{ e.getValue().qty, e.getValue().cost });
        t.put("stock", st);
        ListTag ds = new ListTag();
        for (Day d : days) ds.add(d.save());
        t.put("days", ds);
        if (!weeks.isEmpty()) {
            ListTag ws = new ListTag();
            for (Day w : weeks) ws.add(w.save());
            t.put("weeks", ws);
        }
        t.put("all", allTime.save());
        ListTag os = new ListTag();
        for (Order o : orders) os.add(o.save());
        t.put("orders", os);
        ListTag ns = new ListTag();
        for (String n : notes) ns.add(net.minecraft.nbt.StringTag.valueOf(n));
        t.put("notes", ns);
        t.putInt("loss", lossDays);
        ListTag sh = new ListTag();
        for (UUID u : shortTime) sh.add(net.minecraft.nbt.NbtUtils.createUUID(u));
        t.put("short", sh);
        ListTag dr = new ListTag();
        for (String k : dropped) dr.add(net.minecraft.nbt.StringTag.valueOf(k));
        t.put("dropped", dr);
        t.putLong("closed", closedDay);
        CompoundTag cs = new CompoundTag();
        for (Map.Entry<String, String> e : cards.entrySet()) cs.putString(e.getKey(), e.getValue());
        t.put("cards", cs);
        if (hours != null) {                                           // [featlife] its hours
            t.putIntArray("hrs", hours.save());
            t.putString("hrw", hours.why());
        }
        if (rota != null && (rota.active() || !rota.templates.isEmpty())) t.put("rota", rota.save());   // [rota]
        return t;
    }

    @Nullable
    static Business load(CompoundTag t) {
        Type type;
        try { type = Type.valueOf(t.getString("type")); } catch (IllegalArgumentException e) { return null; }
        Business b = new Business(t.getString("id"), type, t.getString("name"));
        if (t.contains("at")) b.at = BlockPos.of(t.getLong("at"));
        b.opened = t.getLong("opened");
        b.open = !t.contains("open") || t.getBoolean("open");
        for (Tag e : t.getList("owners", Tag.TAG_COMPOUND)) b.owners.add(Stake.load((CompoundTag) e));
        b.till = t.getLong("till");
        b.account = t.getLong("account");
        b.loan = t.getLong("loan");
        b.lender = t.getString("lender");
        b.rateBp = t.contains("rate") ? t.getInt("rate") : 400;
        CompoundTag due = t.getCompound("due");
        for (String k : due.getAllKeys()) {
            try { b.ownersDue.put(UUID.fromString(k), due.getLong(k)); } catch (IllegalArgumentException ignored) { }
        }
        b.markup = t.contains("markup") ? t.getDouble("markup") : 0.35;
        CompoundTag st = t.getCompound("stock");
        for (String k : st.getAllKeys()) {
            long[] a = java.util.Arrays.copyOf(st.getLongArray(k), 2);
            Lot l = new Lot();
            l.qty = a[0];
            l.cost = a[1];
            b.stock.put(k, l);
        }
        for (Tag e : t.getList("days", Tag.TAG_COMPOUND)) b.days.add(Day.load((CompoundTag) e));
        for (Tag e : t.getList("weeks", Tag.TAG_COMPOUND)) b.weeks.add(Day.load((CompoundTag) e));
        Day all = Day.load(t.getCompound("all"));
        b.allTime.addAll(all);
        for (Tag e : t.getList("orders", Tag.TAG_COMPOUND)) b.orders.add(Order.load((CompoundTag) e));
        for (Tag e : t.getList("notes", Tag.TAG_STRING)) b.notes.add(e.getAsString());
        b.lossDays = t.getInt("loss");
        for (Tag e : t.getList("short", Tag.TAG_INT_ARRAY)) b.shortTime.add(net.minecraft.nbt.NbtUtils.loadUUID(e));
        for (Tag e : t.getList("dropped", Tag.TAG_STRING)) b.dropped.add(e.getAsString());
        b.closedDay = t.contains("closed") ? t.getLong("closed") : -1;
        CompoundTag cs = t.getCompound("cards");
        for (String k : cs.getAllKeys()) b.cards.put(k, cs.getString(k));
        int[] hrs = t.getIntArray("hrs");                              // [featlife] its hours
        if (hrs.length == 3) b.setHours(new Hours(hrs[0], hrs[1], hrs[2], t.getString("hrw")));
        if (t.contains("rota")) b.rota = Rota.load(t.getCompound("rota"));   // [rota]
        return b;
    }

    /** [econ-1b] Tests: its books saved, and loaded back. */
    public CompoundTag saveForTests() {
        return save();
    }

    @Nullable
    public static Business loadForTests(CompoundTag t) {
        return load(t);
    }

    /** Which trade staffs it, by the trade its hands are stationed at (Businesses.employer reads the posts first). */
    @Nullable
    static Type forTrade(StationTask t) {
        return switch (t) {
            case COOK -> Type.CAFE;
            case SHOP -> Type.SHOP;
            case SMITH -> Type.SMITHY;
            case TAILOR -> Type.TAILOR;
            case BREW -> Type.BREWERY;
            case BEEKEEP -> Type.APIARY;
            case FLETCHER -> Type.FLETCHER;
            case ENCHANT -> Type.ENCHANTER;
            case FIREWORKS -> Type.POWDER;
            case CARTOGRAPHER -> Type.MAPROOM;
            case STORE -> Type.STOREHOUSE;
            case EMERALD -> Type.TRADING_POST;
            case BANK -> Type.BANK;
            default -> null;
        };
    }
}
