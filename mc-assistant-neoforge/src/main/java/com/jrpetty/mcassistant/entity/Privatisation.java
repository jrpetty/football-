package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business.Holder;
import com.jrpetty.mcassistant.entity.Business.Stake;
import com.jrpetty.mcassistant.entity.Business.Type;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [econ-1a] Every trade a business, and every business somebody's own.
 *
 * <p><b>The producers.</b> A farmer's field, a woodcutter's holt, a miner's claim, a fisher's boat, a rancher's pens, a
 * hunter's hunting, a smelter's furnace: each is a business of its own, one to each hand ("Wren's Farm"), that sells what
 * it brings in to the market at the market's bid, buys what it uses (its seed, its torches, its feed) at the ask, pays its
 * hand's wage out of its till and keeps what is over. The makers, the shops, the café and the inn are already businesses
 * (Businesses); the town keeps only what is everybody's: the hall, the watch, the school, the storehouse and its market,
 * the mint, the harbour and the trading post.
 *
 * <p><b>A young town</b> (its market opening within its first ten days): its founders start as owner-operators. On Market
 * Opening Day each producing founder is given the title to its field, holt, claim or boat and its tools (a homestead), six
 * coins for its household and four for its business's till, out of the founders' purse in the treasury.
 *
 * <p><b>An old town</b> (a world played from before the market): every trade starts as the town's own business, run as it
 * always was (the treasury pays its hands; what it sells is the treasury's), and the council sells them off over a
 * fortnight, one or two a day, the smallest first (the fields, the boats and the holts, then the furnaces and the claims,
 * then the town's makers and shops nobody had taken on): each first to its own hand on a lease (a coin and a half in the
 * hundred of its worth a week to the treasury, the title its own after fifty-two payments, or sooner if it pays the rest
 * off), else at auction to the folk with the means (at no less than four fifths of its worth), else kept by the town and
 * offered again in a month. A hand who takes up a producing trade later is offered its business the same way.
 *
 * <p>Each sale goes in the chronicle; the programme's progress is on the Market page.
 */
public final class Privatisation {

    private Privatisation() {}

    /** A homestead's grant: coin for the household and for the business's till. */
    static final int HOUSEHOLD_GRANT = 6, FIRM_GRANT = 4;
    /** The programme's length, the most it sells a day, how long before the unsold are offered again. */
    static final int DAYS = 14, MOST_A_DAY = 2, AGAIN_AFTER = 28;
    /** A lease: so many parts in ten thousand of the worth a week, so many payments to the title. */
    static final int LEASE_BP = 150, LEASE_PAYMENTS = 52;
    /** An auction's reserve: so many tenths of the worth. */
    static final int RESERVE_TENTHS = 8;
    /** A field's, holt's or claim's worth as a title, pennies, beside its tools and stock. */
    static final long TITLE_PENCE = 800;

    // ------------------------------------------------------------------ the producers' businesses

    /** Is this a producing trade (one business a hand)? */
    public static boolean producer(StationTask t) {
        return typeFor(t) != null;
    }

    /** The business a producing trade's hand works its own: the farm, the woodlot, the mine... */
    @Nullable
    public static Type typeFor(StationTask t) {
        return switch (t) {
            case FARM -> Type.FARM;
            case WOOD -> Type.WOODLOT;
            case MINE, CAVE -> Type.MINE;
            case FISH, DIVER -> Type.FISHERY;
            case RANCH -> Type.RANCH;
            case HUNT -> Type.HUNTING;
            case SMELT -> Type.FOUNDRY;
            default -> null;
        };
    }

    /** A producer business's id: its type and its hand. */
    static String idFor(Type type, UUID hand) {
        return type.id() + ":" + hand;
    }

    /** The producer business this folk works, if it has one (Businesses.employer asks, once the market is at work). */
    @Nullable
    static Business firmOf(UUID village, VillageFolkEntity f) {
        if (!MarketMaker.enabled()) return null;
        Type type = typeFor(f.stationTask());
        return type == null ? null : Businesses.of(village, idFor(type, f.getUUID()));
    }

    /** "Wren's Farm". */
    static String nameFor(Type type, VillageFolkEntity f) {
        String n = f.displayNameCap();
        int sp = n.indexOf(' ');
        String first = sp > 0 ? n.substring(0, sp) : n;
        return first + (first.endsWith("s") ? "' " : "'s ") + type.title;
    }

    /** Every producing hand's business made, the town's until somebody takes it on. Returns the new ones. */
    static List<Business> ensure(ServerLevel level, UUID village, long day) {
        List<Business> made = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            Type type = typeFor(f.stationTask());
            if (type == null) continue;
            String id = idFor(type, f.getUUID());
            if (Businesses.of(village, id) != null) continue;
            Business b = new Business(id, type, nameFor(type, f));
            b.opened = day;
            b.owners.add(new Stake(Holder.TOWN, null, "the town", Business.WHOLE));
            b.markup = 0;
            Businesses.town(village).biz.put(id, b);
            b.note(day, "set up as the town's own " + type.title.toLowerCase(Locale.ROOT) + ", worked by " + f.displayNameCap());
            made.add(b);
        }
        if (!made.isEmpty()) Businesses.dirty();
        return made;
    }

    /** The folk a producer business is worked by (its id names it), if alive in town. */
    @Nullable
    static VillageFolkEntity handOf(UUID village, Business b) {
        int i = b.id.indexOf(':');
        if (i < 0) return null;
        try {
            VillageFolkEntity f = Homes.loaded(village, UUID.fromString(b.id.substring(i + 1)));
            return f != null && f.isAlive() && !f.isBaby() ? f : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean producerType(Type t) {
        return t.size == Business.Size.YARD;
    }

    // ------------------------------------------------------------------ a young town's homesteads

    /**
     * A young town's Market Opening Day: each producing founder the title to its homestead, and its grants out of the
     * founders' purse; the makers' and the shops' businesses are already their proprietors' (Businesses).
     */
    static void homesteads(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        ensure(level, id, day);
        CompoundTag p = prog(id);
        p.putLong("start", day);
        p.putBoolean("homesteads", true);
        List<String> who = new ArrayList<>();
        for (Business b : Businesses.all(id)) {
            if (!producerType(b.type) || !b.town()) continue;
            VillageFolkEntity f = handOf(id, b);
            if (f == null) continue;
            give(b, f, day, "given the title to its homestead on Market Opening Day");
            long home = Businesses.fromTreasury(id, HOUSEHOLD_GRANT * 100L);
            if (home >= 100) f.earn((int) (home / 100));
            if (home % 100 > 0) Businesses.toTreasury(id, home % 100);
            long till = Businesses.fromTreasury(id, FIRM_GRANT * 100L);
            b.till += till;
            Business.Day d = b.today(day);
            d.capital += till;
            b.allTime.capital += till;
            who.add(b.name);
            Accounts.add(p, "homesteads", 1);
        }
        Businesses.dirty();
        Accounts.dirty();
        if (!who.isEmpty()) {
            Villages.tell(id, day, "the founders were given the titles to their homesteads, each its own business: " + String.join(", ", who));
        }
    }

    /** A business wholly this folk's. */
    static void give(Business b, VillageFolkEntity f, long day, String why) {
        b.owners.clear();
        b.owners.add(new Stake(Holder.FOLK, f.getUUID(), f.displayNameCap(), Business.WHOLE));
        b.closedDay = -1;
        b.note(day, f.displayNameCap() + " " + why);
        Businesses.dirty();
    }

    // ------------------------------------------------------------------ an old town's programme

    static CompoundTag prog(UUID village) {
        return Accounts.sub(Accounts.town(village), "privatisation");
    }

    /** The council announces the programme: every trade the town's own business, to be sold to its hands. */
    static void begin(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        ensure(level, id, day);
        CompoundTag p = prog(id);
        p.putLong("start", day);
        int n = forSale(id).size();
        p.putInt("offered", n);
        Accounts.dirty();
        Villages.tell(id, day, "the council announced the privatisation: " + n + " of the town's enterprises (its fields, holts, claims, boats,"
            + " furnaces and the shops nobody had taken on) to be sold to their own hands over the next fortnight, the smallest first");
    }

    /** What the town holds that is for sale, the smallest first: the producers, then the makers, then the shops and inns. */
    static List<Business> forSale(UUID village) {
        List<Business> out = new ArrayList<>();
        for (Business b : Businesses.all(village)) {
            if (b.type.keeping != Business.Keeping.OWN || !b.town() || leased(village, b)) continue;
            out.add(b);
        }
        out.sort(Comparator.comparingInt(Privatisation::order).thenComparing(b -> b.id));
        return out;
    }

    /** The order the programme sells in: fields, boats and holts first; furnaces and claims; makers; shops and inns. */
    static int order(Business b) {
        return switch (b.type) {
            case FARM, FISHERY, WOODLOT -> 0;
            case RANCH, HUNTING, FOUNDRY, MINE -> 1;
            case SMITHY, TAILOR, BREWERY, APIARY, FLETCHER, ENCHANTER, POWDER, MAPROOM, BAKERY, BUTCHER, MILL -> 2;
            default -> 3;
        };
    }

    /**
     * The programme's day (MarketMaker.morning): new hands' businesses made; one or two sold, the smallest first, while
     * the fortnight lasts; after it, a new hand's business offered to it at once, the unsold again each month; the leases'
     * weekly payments taken.
     */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag p = prog(id);
        if (!p.contains("start")) p.putLong("start", day);
        long start = p.getLong("start");
        ensure(level, id, day);
        leases(level, id, day);
        List<Business> sale = forSale(id);
        if (sale.isEmpty()) return;
        long into = day - start;
        int today;
        if (into < DAYS) {
            long left = Math.max(1, DAYS - into);
            today = (int) Math.min(MOST_A_DAY, Math.max(1, (sale.size() + left - 1) / left));
        } else {
            today = 0;
            // After the fortnight: a hand's own new business offered at once; the rest every month.
            for (Business b : sale) {
                if (producerType(b.type) && day - b.opened <= 1) today++;
            }
            if (into % AGAIN_AFTER == 0) today = sale.size();
        }
        int sold = 0;
        for (Business b : sale) {
            if (sold >= today) break;
            String last = b.notes.isEmpty() ? "" : b.notes.get(b.notes.size() - 1);
            if (into >= DAYS && into % AGAIN_AFTER != 0 && !(producerType(b.type) && day - b.opened <= 1)) continue;
            if (last.startsWith(day + "|")) continue;
            offer(level, id, b, day);
            sold++;
        }
        Accounts.dirty();
    }

    /** What the business is worth to a buyer: business-books' worth (stock, till, goodwill) and the title to its ground. */
    public static long value(ServerLevel level, UUID village, Business b) {
        return Businesses.worth(level, village, b) + (producerType(b.type) ? TITLE_PENCE : 0);
    }

    /** One enterprise offered: to its own hand on a lease; else at auction to the folk with the means; else kept. */
    static String offer(ServerLevel level, UUID village, Business b, long day) {
        long value = value(level, village, b);
        CompoundTag p = prog(village);
        VillageFolkEntity hand = producerType(b.type) ? handOf(village, b) : firstHand(village, b);
        if (hand != null && owns(village, hand) < 2) {
            lease(village, b, hand, value, day);
            Accounts.add(p, "leased", 1);
            String line = hand.displayNameCap() + " took on " + b.name + " from the town on a lease (worth " + Businesses.pence(value)
                + ", " + Businesses.pence(value * LEASE_BP / 10000) + " a week, its own after " + LEASE_PAYMENTS + " payments)";
            Villages.tell(village, day, line);
            return line;
        }
        // At auction: the folk with the means bid up to half their purse, never more than a fifth over its worth.
        long reserve = value * RESERVE_TENTHS / 10;
        List<VillageFolkEntity> bidders = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive() && owns(village, f) < 2 && bidOf(f, value) >= reserve) bidders.add(f);
        }
        bidders.sort(Comparator.comparingLong((VillageFolkEntity f) -> -bidOf(f, value)).thenComparing(VillageFolkEntity::displayNameCap));
        if (!bidders.isEmpty()) {
            VillageFolkEntity buyer = bidders.get(0);
            long second = bidders.size() > 1 ? bidOf(bidders.get(1), value) + 100 : reserve;
            long price = Math.min(bidOf(buyer, value), Math.max(reserve, second));
            int coins = (int) ((price + 99) / 100);
            if (coins > 0 && buyer.spend(coins)) {
                Businesses.toTreasury(village, coins * 100L);
                give(b, buyer, day, "bought it at the town's auction for " + coins + " coin" + (coins == 1 ? "" : "s"));
                Accounts.add(p, "auctioned", 1);
                Accounts.add(p, "proceeds", coins * 100L);
                String line = buyer.displayNameCap() + " bought " + b.name + " at the town's auction for " + coins + " coin" + (coins == 1 ? "" : "s")
                    + (bidders.size() > 1 ? " against " + (bidders.size() - 1) + " other bidder" + (bidders.size() > 2 ? "s" : "") : "");
                Villages.tell(village, day, line);
                return line;
            }
        }
        b.note(day, "offered for sale and not taken: the town keeps it, to offer again in a month");
        Accounts.add(p, "unsold", 1);
        Businesses.dirty();
        return b.name + " found no taker; the town keeps it for now";
    }

    /** What a folk bids at auction: half its purse, no more than a fifth over the worth. Pennies. */
    static long bidOf(VillageFolkEntity f, long value) {
        return Math.min(f.purse() * 50L, value * 6 / 5);
    }

    /** The head hand of a business the town holds (its staff, the senior first), or null. */
    @Nullable
    static VillageFolkEntity firstHand(UUID village, Business b) {
        List<VillageFolkEntity> staff = Businesses.staff(village, b);
        staff.sort(Comparator.comparingInt((VillageFolkEntity f) -> -f.veteranLevel()).thenComparing(VillageFolkEntity::displayNameCap));
        return staff.isEmpty() ? null : staff.get(0);
    }

    /** How many businesses a folk owns already (two at most). */
    static int owns(UUID village, VillageFolkEntity f) {
        int n = 0;
        for (Business b : Businesses.all(village)) if (b.ownedBy(f.getUUID())) n++;
        return n;
    }

    // ------------------------------------------------------------------ leases

    static CompoundTag leasesOf(UUID village) {
        return Accounts.sub(prog(village), "leases");
    }

    static boolean leased(UUID village, Business b) {
        return leasesOf(village).contains(b.id);
    }

    /** A lease: the hand runs it as its own from today, paying for it weekly. */
    static void lease(UUID village, Business b, VillageFolkEntity hand, long value, long day) {
        give(b, hand, day, "took it on a lease from the town (worth " + Businesses.pence(value) + ")");
        CompoundTag l = new CompoundTag();
        l.putUUID("who", hand.getUUID());
        l.putLong("value", value);
        l.putLong("weekly", Math.max(1, value * LEASE_BP / 10000));
        l.putInt("paid", 0);
        l.putLong("start", day);
        leasesOf(village).put(b.id, l);
        Accounts.dirty();
    }

    /** The leases' weekly payments, out of the business's till into the treasury; the title its own when they are done. */
    static void leases(ServerLevel level, UUID village, long day) {
        CompoundTag all = leasesOf(village);
        for (String id : new ArrayList<>(all.getAllKeys())) {
            CompoundTag l = all.getCompound(id);
            Business b = Businesses.of(village, id);
            if (b == null || b.town()) {
                all.remove(id);
                continue;
            }
            long start = l.getLong("start");
            if (day <= start || (day - start) % 7 != 0) continue;
            long weekly = l.getLong("weekly");
            long paid = Businesses.payOut(level, village, b, weekly, day);
            Businesses.toTreasury(village, paid);
            Business.Day d = b.today(day);
            d.rent += weekly;
            b.allTime.rent += weekly;
            int n = l.getInt("paid") + 1;
            l.putInt("paid", n);
            long rest = (LEASE_PAYMENTS - n) * weekly;
            // Its title when the payments are done, or when its till can pay the rest off and keep its float.
            if (n >= LEASE_PAYMENTS || b.till > rest + Businesses.floatOf(b, day) * 2) {
                if (n < LEASE_PAYMENTS && rest > 0) {
                    long off = Businesses.payOut(level, village, b, rest, day);
                    Businesses.toTreasury(village, off);
                }
                all.remove(id);
                b.note(day, b.ownersWords() + " owns it outright now: the lease paid off");
                Villages.tell(village, day, b.ownersWords() + " paid off the lease on " + b.name + " and owns it outright");
            }
        }
        Accounts.dirty();
        Businesses.dirty();
    }

    // ------------------------------------------------------------------ in words

    /** The programme in a few lines (the Market page, /village market). */
    public static List<String> lines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        if (!MarketMaker.enabled() || MarketMaker.openedDay(village) < 0) return out;
        CompoundTag p = prog(village);
        int producers = 0, priv = 0, town = 0;
        for (Business b : Businesses.all(village)) {
            if (b.type.keeping != Business.Keeping.OWN) continue;
            if (producerType(b.type)) producers++;
            if (b.town()) town++; else priv++;
        }
        long day = Businesses.today(level);
        if (p.getBoolean("homesteads")) {
            out.add("Homesteads: " + p.getInt("homesteads") + " founders given the titles to their fields, holts, claims and boats on Market Opening Day.");
        } else if (p.contains("start")) {
            long into = day - p.getLong("start");
            out.add(into < DAYS ? "Privatisation: day " + (into + 1) + " of " + DAYS + "." : "Privatisation: the fortnight's programme is done; the unsold are offered again monthly.");
        }
        out.add(priv + " businesses in private hands (" + producers + " of them producers' own), " + town + " still the town's; "
            + leasesOf(village).size() + " on lease from the town.");
        if (p.getInt("leased") + p.getInt("auctioned") > 0) {
            out.add("Sold so far: " + p.getInt("leased") + " on lease to their own hands, " + p.getInt("auctioned") + " at auction ("
                + Businesses.pence(p.getLong("proceeds")) + " to the treasury)" + (p.getInt("unsold") > 0 ? ", " + p.getInt("unsold") + " offers not taken" : "") + ".");
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the producer businesses made now (the town's). */
    public static List<Business> ensureForTests(ServerLevel level, UUID village) {
        return ensure(level, village, Businesses.today(level));
    }

    /** Tests: one business offered now. */
    public static String offerForTests(ServerLevel level, UUID village, Business b) {
        return offer(level, village, b, Businesses.today(level));
    }

    /** Tests: the programme begun now. */
    public static void beginForTests(ServerLevel level, Villages.Village v) {
        begin(level, v, Businesses.today(level));
    }

    /** Tests: the programme's morning now. */
    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }
}
