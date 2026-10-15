package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business.Day;
import com.jrpetty.mcassistant.entity.Business.Holder;
import com.jrpetty.mcassistant.entity.Business.Lot;
import com.jrpetty.mcassistant.entity.Business.Stake;
import com.jrpetty.mcassistant.entity.Business.Type;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [business-books] Every place in a town that buys and sells is a business with its own books.
 *
 * <p><b>Who owns it.</b> A business belongs to its owners, a set of stakes that come to the whole: a proprietor (its
 * head cook, its shopkeeper, or the folk in town with the means and the head for it), a couple who put up the money
 * together, a player who bought it or bought into it. The town owns only what is everybody's: the stores (its public
 * warehouse, which sells to every business at its unit price), the market square, the harbour office, the trading post
 * and the auction house; their books are kept the same way, their coin is the treasury's. When a place opens, its
 * proprietor puts up the start-up capital (a week's rent and a float) out of its savings, its partner the same, and the
 * bank lends the rest if there is one; the town's stores give it trade credit. An owner who dies leaves its stake to its
 * partner or a grown child; an owner who leaves town sells it back. A player can buy one, or buy into one.
 *
 * <p><b>Its till.</b> Every coin it takes over the counter goes into its own till (from the treasury that the counters
 * have always paid into, at once): the café's teas, the shop's tools, the tavern's pints, the inn's rooms, the fish
 * market's catch, the theatre's seats, a player's stall. Out of the till it pays for everything: its stock, its staff
 * (each morning, out of the till, in place of the treasury: their wage by worth, less the town's wage tax), its rent to
 * the town that owns the building, its lights, its repairs, the market toll on what it takes, the interest on what it
 * owes. All in pennies, a hundred to the coin, so a carrot at half a coin is fifty pennies and thirty of them fifteen
 * coins exactly.
 *
 * <p><b>Its costs are real.</b> What it sells it has bought or made. It buys from the town's stores at their unit price
 * (PriceIndex, never under what the stores' own goods cost the town), in any amount its till runs to (a few, a normal
 * order, a bulk order at a discount: three in the hundred off sixty-four or more, six off two hundred and fifty-six, ten
 * off a thousand), or from another business that has it (the shop buys the smith's picks from the smithy). Each lot goes
 * into its stock at what it paid, and its stock is costed at the weighted average. What its hands make (Crafts.now:
 * Economy.openCraft) is costed at exactly the ingredients used, out of its own stock at their cost or bought at the
 * stores' price there and then, the fuel among them: three wheat at five pennies make a loaf that cost fifteen. When it
 * sells, the sale books the takings, the cost of what was sold (its own average cost) and the margin, item by item.
 *
 * <p><b>Its prices</b> are the town's going price (PriceIndex, by supply and demand), never under what a piece costs it in
 * full (its average cost and its share of the week's wages, rent and running costs) and its markup, nor more than three
 * fifths over the going price, or the folk go elsewhere. The markup moves: up when the week ran at a loss, down when it
 * does well enough to undercut the stores; every change is written in its books with the reason.
 *
 * <p><b>The bottom line.</b> Net profit = takings - cost of goods - wages - rent - running costs - taxes - losses -
 * interest. Once a week the owners draw what the week made over a float (three days' costs), shared by stake: a folk's
 * into its purse, the town's into the treasury, a player's kept for it to take. A loss is met out of the till, then the
 * owners' savings, then the stores' trade credit (two days' takings, at half a coin in the hundred a day after a week),
 * then the bank. A business three days at a loss raises its prices; a week, puts a hand on short time; ten days, stops
 * ordering its dearest-to-make line; a fortnight with its credit gone, it is wound up: its stock sold to the stores at
 * cost, its debts paid out of what is left, and the town keeps the premises until someone buys them.
 *
 * <p><b>Its stock</b> stays in the town's stores in its name until its hands use it (bonded): each morning the books are
 * counted against what the stores hold, and what the town used of a business's goods (the larder's bread, the watch's
 * swords) the town pays for at the business's trade price. Fresh food held long over what sells spoils a little each day.
 *
 * <p><b>The town's stores</b> keep books too: what the producers brought in at what their wages came to a piece (the
 * farmers' wheat, the miners' ore), what they sold to whom, and what the town used itself.
 */
public final class Businesses {

    private Businesses() {}

    /** Volume discounts at the stores: so many or more, so many in the hundred off. */
    public static final int[][] DISCOUNTS = { { 1024, 10 }, { 256, 6 }, { 64, 3 } };
    /** The most a business asks over the going price (the folk go elsewhere), and the least markup it lives on. */
    static final double MOST_OVER_GOING = 1.6, LEAST_MARKUP = 0.10, MOST_MARKUP = 1.20;
    /** Trade credit at the stores: two days' takings, at least this many pennies; interest after a week, a day. */
    static final long LEAST_CREDIT = 2000;
    static final int CREDIT_BP_A_DAY = 50, CREDIT_FREE_DAYS = 7;
    /** What a business holds at most, by its size (units): its share of the stores. */
    static final Map<Business.Size, Integer> ROOM = Map.of(Business.Size.SMALL, 256, Business.Size.MEDIUM, 1024, Business.Size.LARGE, 2048);

    // ------------------------------------------------------------------ money in words

    /** "15.00c", "0.50c", "1,204.35c". */
    public static String coins(double c) {
        return String.format(Locale.ROOT, "%,.2fc", c);
    }

    /** A ware's name in the books ("Wheat", "Apple Cider"), from its registry name when the server has no words for it. */
    public static String nameOf(String key) {
        String n = Stockroom.nameOf(key);
        if (n.startsWith("item.") || n.startsWith("block.")) {
            String path = n.substring(n.lastIndexOf('.') + 1).replace('_', ' ');
            n = path.isEmpty() ? n : Character.toUpperCase(path.charAt(0)) + path.substring(1);
        }
        return n;
    }

    /** Pennies in words: "15.00c". */
    public static String pence(long p) {
        return coins(p / 100.0);
    }

    // ------------------------------------------------------------------ the books, kept with the world

    /** One town's businesses, the stores' own books and the town's odd pennies. */
    static final class Town {
        final Map<String, Business> biz = new LinkedHashMap<>();
        /** The town's pennies short of a coin (the treasury is whole coins). */
        long pence;
        /** The stores' own stock books: what the town's goods cost it, by ware. */
        final Map<String, Lot> stores = new HashMap<>();
        /** The last morning it was closed, and the stores' own day (pennies): sold, cost of goods, bought in, used. */
        long closed = -1;
    }

    static final class Store extends SavedData {
        final Map<UUID, Town> towns = new HashMap<>();

        static Store load(CompoundTag tag, HolderLookup.Provider p) {
            Store s = new Store();
            for (Tag e : tag.getList("towns", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) e;
                if (!t.hasUUID("id")) continue;
                Town town = new Town();
                town.pence = t.getLong("pence");
                town.closed = t.getLong("closed");
                for (Tag b : t.getList("biz", Tag.TAG_COMPOUND)) {
                    Business x = Business.load((CompoundTag) b);
                    if (x != null) town.biz.put(x.id, x);
                }
                CompoundTag st = t.getCompound("stores");
                for (String k : st.getAllKeys()) {
                    long[] a = java.util.Arrays.copyOf(st.getLongArray(k), 2);
                    Lot l = new Lot();
                    l.qty = a[0];
                    l.cost = a[1];
                    town.stores.put(k, l);
                }
                s.towns.put(t.getUUID("id"), town);
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider p) {
            ListTag all = new ListTag();
            for (Map.Entry<UUID, Town> e : towns.entrySet()) {
                CompoundTag t = new CompoundTag();
                t.putUUID("id", e.getKey());
                t.putLong("pence", e.getValue().pence);
                t.putLong("closed", e.getValue().closed);
                ListTag bs = new ListTag();
                for (Business b : e.getValue().biz.values()) bs.add(b.save());
                t.put("biz", bs);
                CompoundTag st = new CompoundTag();
                for (Map.Entry<String, Lot> l : e.getValue().stores.entrySet()) {
                    if (l.getValue().qty > 0) st.putLongArray(l.getKey(), new long[]{ l.getValue().qty, l.getValue().cost });
                }
                t.put("stores", st);
                all.add(t);
            }
            tag.put("towns", all);
            return tag;
        }
    }

    @Nullable private static Store loose;

    private static Store store() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Store();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Store::new, Store::load, null), "mc_assistant_business");
    }

    static Town town(UUID village) {
        return store().towns.computeIfAbsent(village, k -> new Town());
    }

    static void dirty() {
        store().setDirty();
    }

    /** Every business of a town (a copy), the town's own among them. */
    public static List<Business> all(UUID village) {
        return new ArrayList<>(town(village).biz.values());
    }

    /** A business by its id ("cafe", "shop", "stall:<player>"), or null. */
    @Nullable
    public static Business of(UUID village, String id) {
        return town(village).biz.get(id);
    }

    /** The town's forgotten (a village gone): its books go with it. */
    public static void forget(UUID village) {
        if (store().towns.remove(village) != null) dirty();
    }

    // ------------------------------------------------------------------ the treasury, to the penny

    /** Pennies into the treasury: whole coins in, the odd pennies kept beside it. */
    static void toTreasury(UUID village, long p) {
        if (p <= 0) return;
        Town t = town(village);
        t.pence += p;
        long whole = t.pence / 100;
        if (whole > 0) {
            Ledger.addCoins(village, (int) Math.min(Integer.MAX_VALUE, whole));
            t.pence -= whole * 100;
        }
        dirty();
    }

    /** Pennies out of the treasury, as many as it has up to {@code p}: returns how many. */
    static long fromTreasury(UUID village, long p) {
        if (p <= 0) return 0;
        Town t = town(village);
        long got = Math.min(p, t.pence);
        t.pence -= got;
        long need = p - got;
        if (need > 0) {
            int coins = (int) Math.min(Integer.MAX_VALUE, (need + 99) / 100);
            int took = Ledger.takeCoins(village, coins);
            long in = took * 100L;
            if (in > need) {
                t.pence += in - need;
                in = need;
            }
            got += in;
        }
        dirty();
        return got;
    }

    /** The treasury to the penny. */
    public static long treasuryPence(UUID village) {
        return Ledger.coins(village) * 100L + town(village).pence;
    }

    /** The odd pennies the town holds beside its treasury. */
    public static long townPence(UUID village) {
        return town(village).pence;
    }

    // ------------------------------------------------------------------ which businesses there are

    /** Today's day number. */
    static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** Where a building of this type stands in the town, or null. */
    @Nullable
    static BlockPos premises(UUID village, Type t) {
        for (String s : t.structures) {
            BlockPos at = Villages.builtAt(village, s);
            if (at != null) return at;
        }
        return null;
    }

    /**
     * The town's businesses brought up to date with what stands: one for each kind of establishment built (its premises
     * found), the stores always, the bank once it is open. A new one is set up with its owners and its capital.
     */
    public static void ensure(ServerLevel level, UUID village) {
        Town t = town(village);
        long day = today(level);
        if (!t.biz.containsKey("storehouse")) {
            Business s = new Business("storehouse", Type.STOREHOUSE, "The town stores");
            s.owners.add(new Stake(Holder.TOWN, null, "the town", Business.WHOLE));
            s.opened = day;
            t.biz.put(s.id, s);
            dirty();
        }
        if (!t.biz.containsKey(Type.FISHMARKET.id()) && FishMarket.standsForBooks(level, village)) {
            open(level, village, Type.FISHMARKET, Type.FISHMARKET.id(), null, day);
        }
        for (Type type : Type.values()) {
            if (type == Type.STOREHOUSE || type == Type.STALL || type == Type.FISHMARKET) continue;
            // Not yet a trade of their own here (no hands stationed, nothing sold under their roof): kept for later rounds.
            if (type == Type.BUTCHER || type == Type.MILL || type == Type.STABLES) continue;
            if (t.biz.containsKey(type.id())) continue;
            BlockPos at = type == Type.BANK ? (Bank.open(village) ? premises(village, type) : null) : premises(village, type);
            if (at == null) continue;
            open(level, village, type, type.id(), at, day);
        }
    }

    /** A new business: its name, its owners, its capital. */
    static Business open(ServerLevel level, UUID village, Type type, String id, @Nullable BlockPos at, long day) {
        Town t = town(village);
        Business b = new Business(id, type, type.title);
        b.at = at;
        b.opened = day;
        t.biz.put(id, b);
        if (type.keeping == Business.Keeping.TOWN) {
            b.owners.add(new Stake(Holder.TOWN, null, "the town", Business.WHOLE));
        } else if (type.keeping == Business.Keeping.OWN) {
            setUp(level, village, b, day);
        }
        dirty();
        return b;
    }

    /** What it takes to start one: a week's rent and a float of ten coins, in pennies. */
    static long startUp(UUID village, Business b) {
        return rent(village, b) * 7 + 1000;
    }

    /**
     * Its proprietor found and its capital put up: the one with the most claim to it (its own head hand, a Merchant at
     * heart, a Haggler or a Thrifty one, savings), its partner alongside if it has coin, each owning what it put in; the
     * bank lends what they are short of, else it starts on the stores' trade credit. With nobody grown in town, the town
     * keeps it until somebody can.
     */
    static void setUp(ServerLevel level, UUID village, Business b, long day) {
        VillageFolkEntity who = proprietorFor(level, village, b, null);
        b.owners.clear();
        if (who == null) {
            b.owners.add(new Stake(Holder.TOWN, null, "the town", Business.WHOLE));
            b.note(day, "opened in the town's hands: nobody to take it on yet");
            return;
        }
        long need = startUp(village, b);
        List<VillageFolkEntity> partners = new ArrayList<>();
        partners.add(who);
        UUID mate = who.life().partner();
        VillageFolkEntity partner = mate == null ? null : Homes.loaded(village, mate);
        if (partner != null && partner.isAlive() && !partner.isBaby() && partner.purse() >= 4) partners.add(partner);
        Map<VillageFolkEntity, Long> put = new LinkedHashMap<>();
        long raised = 0;
        for (VillageFolkEntity f : partners) {
            // Each puts in what the rest of the start-up wants, up to half its savings.
            int coins = (int) Math.min((need - raised + 99) / 100, f.purse() / 2);
            if (coins > 0 && f.spend(coins)) {
                put.put(f, coins * 100L);
                raised += coins * 100L;
            }
        }
        b.till += raised;
        if (raised <= 0) {
            b.owners.add(new Stake(Holder.FOLK, who.getUUID(), who.displayNameCap(), Business.WHOLE));
        } else {
            int left = Business.WHOLE;
            int i = 0;
            for (Map.Entry<VillageFolkEntity, Long> e : put.entrySet()) {
                int parts = ++i == put.size() ? left : (int) (Business.WHOLE * e.getValue() / raised);
                left -= parts;
                b.owners.add(new Stake(Holder.FOLK, e.getKey().getUUID(), e.getKey().displayNameCap(), parts));
            }
        }
        if (b.owners.isEmpty()) b.owners.add(new Stake(Holder.FOLK, who.getUUID(), who.displayNameCap(), Business.WHOLE));
        Day d = b.today(day);
        d.capital += raised;
        b.allTime.capital += raised;
        long short_ = need - raised;
        String how = raised > 0 ? pence(raised) + " of their own" : "nothing of their own";
        if (short_ > 0) {
            long lent = bankLends(village, b, short_);
            if (lent > 0) how += ", " + pence(lent) + " lent by the bank";
            else how += ", and the stores' trade credit";
        }
        b.note(day, "opened by " + b.ownersWords() + " with " + how);
        Villages.tell(village, day, b.ownersWords() + " opened the " + b.type.title.toLowerCase(Locale.ROOT) + " as their own business");
    }

    /**
     * The best to take this business on: no more than two of its own already; its own hands first, then a head for
     * business (a Merchant at heart, a Haggler, a Thrifty one) and the savings to back it. {@code not}: one to pass over.
     */
    @Nullable
    static VillageFolkEntity proprietorFor(ServerLevel level, UUID village, Business b, @Nullable UUID not) {
        VillageFolkEntity best = null;
        double bestScore = -1e9;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.getUUID().equals(not)) continue;
            int owns = 0;
            for (Business o : all(village)) if (o.ownedBy(f.getUUID())) owns++;
            if (owns >= 2) continue;
            double s = 0;
            Business works = employer(village, f);
            if (works == b) s += 6;
            if (b.type == Type.FISHMARKET && f.stationTask() == StationTask.FISH || b.type == Type.BUTCHER && f.stationTask() == StationTask.HUNT
                || b.type == Type.MILL && f.stationTask() == StationTask.FARM || b.type == Type.STABLES && f.stationTask() == StationTask.RANCH) s += 5;
            if (f.persona().rolled() && Values.top(f) == Values.Value.WEALTH) s += 3;
            if (f.knacks().has(FolkSkills.Knack.HAGGLER)) s += 2;
            if (f.knacks().has(FolkSkills.Knack.THRIFTY)) s += 1;
            s += Math.min(4, f.purse() / 20.0);
            s -= owns * 2;
            if (s > bestScore || s == bestScore && best != null && f.displayNameCap().compareTo(best.displayNameCap()) < 0) {
                best = f;
                bestScore = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ staff

    /** The business a folk works at (its post first: the barkeep at the tavern, the baker at the bakery), or null. */
    @Nullable
    public static Business employer(@Nullable UUID village, VillageFolkEntity f) {
        if (village == null || f.isBaby()) return null;
        Town t = town(village);
        Hospitality.Role role = Hospitality.role(f);
        Type type = switch (role) {
            case BARKEEP -> Type.TAVERN;
            case INNKEEPER -> Type.INN;
            case BAKER -> Type.BAKERY;
            case WAITER, HOST, POT_WASHER -> Type.RESTAURANT;
            default -> null;
        };
        if (type != null && t.biz.containsKey(type.id())) return t.biz.get(type.id());
        Type byTrade = Business.forTrade(f.stationTask());
        if (byTrade == null) return Privatisation.firmOf(village, f);       // [econ-1a] a producer's own business (its field, holt, boat...)
        if (byTrade == Type.CAFE) {
            for (Type k : new Type[]{ Type.CAFE, Type.RESTAURANT, Type.TAVERN, Type.BAKERY, Type.INN }) {
                if (t.biz.containsKey(k.id())) return t.biz.get(k.id());
            }
            return null;
        }
        return t.biz.get(byTrade.id());
    }

    /** A business's hands, today. */
    public static List<VillageFolkEntity> staff(UUID village, Business b) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && employer(village, f) == b) out.add(f);
        }
        out.sort(Comparator.comparing(VillageFolkEntity::displayNameCap));
        return out;
    }

    /**
     * Payday (Market.payWages, for each hand before the town pays it): a private business's hand is paid by its business,
     * out of its till: its wage by worth less the town's wage tax (into the treasury), half on short time. Returns the
     * coins it was paid, or -1 if the town pays it.
     */
    public static int paysHand(ServerLevel level, Villages.Village v, VillageFolkEntity f, int wage) {
        Business b = employer(v.id(), f);
        if (b == null || b.town() || b.mirror() || !b.open || wage <= 0) return -1;
        long day = today(level);
        int due = b.shortTime.contains(f.getUUID()) ? (wage + 1) / 2 : wage;
        int tax = Market.taxOn(f, due);
        long needP = due * 100L;
        if (b.till < needP) raise(level, v.id(), b, needP - b.till, day);
        int paid = (int) Math.min(due, b.till / 100);
        b.till -= paid * 100L;
        int taxed = Math.min(tax, paid);
        int net = paid - taxed;
        if (taxed > 0) toTreasury(v.id(), taxed * 100L);
        if (taxed > 0) Accounts.taxIn(v.id(), taxed * 100L);           // [econ-1a] the town's IOUs to its market paid first out of its taxes
        f.paid(net);
        Purchases.payday(level, f);
        Accounts.payday(level, f);                                      // [econ-1a] what it owes the market, out of its wage
        Day d = b.today(day);
        d.wages += paid * 100L;
        b.allTime.wages += paid * 100L;
        d.staffNames.put(f.getUUID(), f.displayNameCap());
        d.staffPay.merge(f.getUUID(), paid * 100L, Long::sum);
        if (paid < due) b.note(day, f.displayNameCap() + " paid " + paid + " of " + due + ": the till was empty and the credit gone");
        dirty();
        return net;
    }

    // ------------------------------------------------------------------ paying out, and finding the money

    /**
     * Pay so many pennies out of the business to the town: from its till, raising what it is short of; what it cannot find
     * it owes the town (its account, past its credit: the books say so). Returns the coin actually paid now, which the
     * caller passes on; the rest is a debt, not coin.
     */
    static long payOut(ServerLevel level, UUID village, Business b, long p, long day) {
        if (p <= 0) return 0;
        if (b.town()) return 0;
        if (b.till < p) raise(level, village, b, p - b.till, day);
        long paid = Math.min(p, Math.max(0, b.till));
        b.till -= paid;
        if (paid < p) {
            b.account -= p - paid;
            b.note(day, "owes the town " + pence(p - paid) + " it could not pay");
        }
        dirty();
        return paid;
    }

    /** Pay the town so many pennies: what the till runs to into the treasury, the rest owed. */
    static void payTown(ServerLevel level, UUID village, Business b, long p, long day) {
        toTreasury(village, payOut(level, village, b, p, day));
    }

    /**
     * Find money a business is short of, into its till: its owners' savings (up to half of what each holds, by stake),
     * then the stores' trade credit (coin out of the treasury, owed back), then the bank. Returns the pennies raised.
     */
    static long raise(ServerLevel level, UUID village, Business b, long need, long day) {
        if (need <= 0 || b.town()) return 0;
        long raised = 0;
        Day d = b.today(day);
        for (Stake s : b.owners) {
            if (raised >= need) break;
            if (s.holder() != Holder.FOLK || s.who() == null) continue;
            VillageFolkEntity f = Homes.loaded(village, s.who());
            if (f == null || !f.isAlive()) continue;
            long want = (need - raised) * s.parts() / Business.WHOLE + 99;
            int coins = (int) Math.min(f.purse() / 2, Math.max(1, want / 100));
            if (coins > 0 && f.spend(coins)) {
                raised += coins * 100L;
                d.capital += coins * 100L;
                b.allTime.capital += coins * 100L;
            }
        }
        if (raised >= 500) b.note(day, b.ownersWords() + " put in " + pence(raised) + " of their savings");
        b.till += raised;
        if (raised < need) {
            long room = creditLimit(b, day) + Math.min(0, b.account);
            long take = Math.min(need - raised, Math.max(0, room));
            long got = take > 0 ? fromTreasury(village, take) : 0;
            if (got > 0) {
                b.till += got;
                b.account -= got;
                raised += got;
            }
        }
        if (raised < need) {
            long lent = bankLends(village, b, need - raised);
            raised += lent;
        }
        dirty();
        return raised;
    }

    /** Trade credit at the stores: two days' takings, never under twenty coins. */
    static long creditLimit(Business b, long day) {
        Day w = b.last(day, 7);
        return Math.max(LEAST_CREDIT, w.revenue * 2 / 7);
    }

    /** The bank lends what it can spare (whole coins, out of its vault), at its rate: returns the pennies lent. */
    static long bankLends(UUID village, Business b, long need) {
        if (need <= 0 || !Bank.open(village)) return 0;
        int coins = Bank.lendToFirm(village, (int) Math.min(Integer.MAX_VALUE, (need + 99) / 100));
        if (coins <= 0) return 0;
        b.till += coins * 100L;
        b.loan += coins * 100L;
        b.lender = "bank";
        dirty();
        return coins * 100L;
    }

    // ------------------------------------------------------------------ selling

    /** The last folk's charge at the counter, to the penny (Purchases.charge): the sale it is for picks it up. */
    private static String lastKey = "";
    private static long lastCents = -1, lastTick = -1;
    @Nullable private static UUID lastVillage;

    /** Purchases.charge: so many pennies charged for this (whole coins in the treasury, the change on the folk's account). */
    public static void charged(ServerLevel level, UUID village, ItemStack what, long cents) {
        if (what.isEmpty()) return;                                     // a bill of several dishes: each booked at its own price
        lastVillage = village;
        lastKey = Stockroom.key(what);
        lastCents = cents;
        lastTick = level.getGameTime();
    }

    /** The business behind a seller's counter, or null (no such business standing: the town's stores take it). */
    @Nullable
    static Business behind(UUID village, Stockroom.Seller s) {
        Town t = town(village);
        return switch (s) {
            case SHOP -> t.biz.get(Type.SHOP.id());
            case CAFE -> t.biz.containsKey(Type.CAFE.id()) ? t.biz.get(Type.CAFE.id()) : t.biz.get(Type.RESTAURANT.id());
            case TAVERN -> t.biz.containsKey(Type.TAVERN.id()) ? t.biz.get(Type.TAVERN.id()) : t.biz.get(Type.CAFE.id());
            case MARKET -> t.biz.get(Type.MARKET.id());
            case STORES -> t.biz.get("storehouse");
        };
    }

    /**
     * A sale at a counter (Stockroom.sold, after the counter's code has put the coin in the treasury): into the seller's
     * books, and a private business's takings out of the treasury into its own till. Already paid: the coin moved here is
     * the buyer's, once.
     */
    public static void sold(ServerLevel level, UUID village, Stockroom.Seller seller, ItemStack what, int count, int coin) {
        if (village == null || what.isEmpty() || count <= 0 || TownLod.away(village)) return;
        // A place opens for business the morning after it stands (Businesses.morning): till then the town runs its counter.
        Business b = behind(village, seller);
        if (b == null) b = of(village, "storehouse");
        if (b == null) return;
        String key = Stockroom.key(what);
        long p = Math.max(0, coin) * 100L;
        if (lastCents >= 0 && village.equals(lastVillage) && key.equals(lastKey) && lastTick == level.getGameTime()) p = lastCents;
        lastCents = -1;
        sale(level, village, b, what, key, count, p, true);
    }

    /**
     * A sale booked: the takings (a private business's into its till, out of the treasury where the counter put them), the
     * cost of what went out of its stock (bought in from the stores or a maker first if it had too few), the margin, and
     * the market toll on a private business's takings. {@code inTreasury}: the buyer's coin is in the treasury now.
     */
    static void sale(ServerLevel level, UUID village, Business b, ItemStack what, String key, long n, long p, boolean inTreasury) {
        long day = today(level);
        Day d = b.today(day);
        Business.Line line = d.line(key);
        long cogs;
        if (b.town()) {
            cogs = storesTake(level, village, key, what, n);
            // [econ-1a] The storehouse is the town's market: what it sells over its counter is its own takings, not the treasury's.
            if (inTreasury && b.id.equals("storehouse") && MarketMaker.live(village)) MarketMaker.cashIn(village, fromTreasury(village, p));
        } else {
            if (inTreasury && !b.mirror()) {
                long got = fromTreasury(village, p);
                b.till += got;
                b.account += p - got;                                    // the counting house owes it the rest
            }
            Lot lot = b.stock.computeIfAbsent(key, k -> new Lot());
            if (lot.qty < n && !b.mirror()) supply(level, village, b, what, key, n - lot.qty, day, false);
            if (lot.qty >= n) cogs = lot.take(n);
            else {
                // A player's own goods at a stall, or what was had with no history: at the standard cost.
                long had = lot.qty;
                cogs = lot.take(had) + Math.round(CostBook.of(what).total() * 100 * (n - had));
            }
            int pct = CivicLand.live() ? TaxBook.tollPercent(village) : 0;
            long toll = !b.mirror() && pct > 0 ? p * pct / 100 : 0;
            if (toll > 0) {
                payTown(level, village, b, toll, day);
                d.taxes += toll;
                b.allTime.taxes += toll;
            }
        }
        d.revenue += p;
        d.cogs += cogs;
        line.units += n;
        line.revenue += p;
        line.cogs += cogs;
        b.allTime.revenue += p;
        b.allTime.cogs += cogs;
        Business.Line all = b.allTime.line(key);
        all.units += n;
        all.revenue += p;
        all.cogs += cogs;
        dirty();
    }

    /** A service sold (a room, a seat, a fish at the quay): into the business's books and its till. */
    public static void service(ServerLevel level, UUID village, Type type, ItemStack what, int n, long p, boolean inTreasury) {
        if (village == null || p <= 0 || TownLod.away(village)) return;
        Business b = of(village, type.id());
        if (b == null) b = of(village, "storehouse");                  // not open as a business yet: the town's takings
        if (b == null) return;
        String key = what.isEmpty() ? "service/" + type.id() : Stockroom.key(what);
        if (what.isEmpty()) {
            Day d = b.today(today(level));
            if (inTreasury && !b.town()) b.till += fromTreasury(village, p);
            d.revenue += p;
            Business.Line l = d.line(key);
            l.units += n;
            l.revenue += p;
            b.allTime.revenue += p;
            b.allTime.line(key).units += n;
            b.allTime.line(key).revenue += p;
            dirty();
            return;
        }
        sale(level, village, b, what, key, n, p, inTreasury);
    }

    /** A service sold where there is no level to hand (Inn.till): the overworld's. */
    public static void service(UUID village, Type type, String what, long p) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) service(server.overworld(), village, type, ItemStack.EMPTY, 1, p, true);
    }

    /** A player's stall sold this (PlayerStalls): its own books, its till the stall's (the toll already taken). */
    public static void stallSold(ServerLevel level, UUID village, UUID player, String name, ItemStack what, int n, int coins, int toll) {
        if (village == null || what.isEmpty()) return;
        String id = "stall:" + player;
        Business b = of(village, id);
        if (b == null) {
            b = open(level, village, Type.STALL, id, null, today(level));
            b.name = name.isEmpty() ? "A player's stall" : name + "'s stall";
            b.owners.add(new Stake(Holder.PLAYER, player, name, Business.WHOLE));
        }
        sale(level, village, b, what, Stockroom.key(what), n, coins * 100L, false);
        if (toll > 0) {
            Day d = b.today(today(level));
            d.taxes += toll * 100L;
            b.allTime.taxes += toll * 100L;
        }
        dirty();
    }

    // ------------------------------------------------------------------ buying stock

    /** The stores' unit price for one of this today, in coin: the going price, never under what the town's own cost it. */
    public static double storesEach(ServerLevel level, UUID village, ItemStack one) {
        String key = Stockroom.key(one);
        Double forced = PRICE_FOR_TESTS.get(key);
        if (forced != null) return forced;
        double each = MarketMaker.live(village) ? MarketMaker.ask(level, village, one) : PriceIndex.each(level, village, one.copyWithCount(1));   // [econ-1a]
        Lot l = town(village).stores.get(key);
        if (l != null && l.qty > 0) each = Math.max(each, l.each() / 100.0);
        return Math.max(0.01, each);
    }

    /** The discount on an order of so many, in the hundred. */
    public static int discount(long qty) {
        for (int[] t : DISCOUNTS) if (qty >= t[0]) return t[1];
        return 0;
    }

    /** What an order of so many at this unit price comes to (pennies), the discount taken: {total, discount}. */
    public static long[] orderCost(double each, long qty) {
        long unitH = Math.round(each * 10000);                     // hundredths of a penny
        long gross = Math.round(unitH * (double) qty / 100.0);
        long off = gross * discount(qty) / 100;
        return new long[]{ gross - off, off, unitH };
    }

    /** What happened to an order: done, or why not. */
    public record OrderResult(boolean ok, long qty, long total, long discount, String words) {}

    /**
     * An order from the town's stores: so many of a thing, at the stores' unit price, less the volume discount, paid
     * out of the business's till (or its trade credit for a business that runs to it) into the treasury; limited by what
     * the town can spare (its own needs first: Budget) and the room the business has. The goods are kept in the stores in
     * its name until its hands fetch them; each enters its stock at exactly what it paid. {@code credit}: may it draw on
     * its trade credit (its own reorders do; a player's order is paid out of the till only).
     */
    public static OrderResult order(ServerLevel level, UUID village, Business b, ItemStack one, long qty, boolean credit) {
        long day = today(level);
        if (qty <= 0 || one.isEmpty()) return new OrderResult(false, 0, 0, 0, "Nothing to order.");
        if (b.town()) return new OrderResult(false, 0, 0, 0, "The town's own stores do not buy from themselves.");
        String key = Stockroom.key(one);
        int spare = SPARE_ALL_FOR_TESTS ? Market.stock(level, village, Stockroom.matcher(key)) : Budget.spare(level, village, one.copyWithCount(1));
        if (spare <= 0) return new OrderResult(false, 0, 0, 0, "The stores can't spare any " + nameOf(key).toLowerCase(Locale.ROOT) + ": the town needs it.");
        long room = ROOM.get(b.type.size) - b.stockUnits();
        long n = Math.min(qty, Math.min(spare, Math.max(0, room)));
        if (n <= 0) return new OrderResult(false, 0, 0, 0, "No room for more: its share of the stores is full.");
        double each = storesEach(level, village, one);
        long[] cost = orderCost(each, n);
        long canPay = b.till + (credit ? Math.max(0, creditLimit(b, day) + Math.min(0, b.account)) : 0);
        if (cost[0] > canPay) {
            return new OrderResult(false, 0, cost[0], cost[1], n + " " + nameOf(key).toLowerCase(Locale.ROOT) + " at " + coins(each)
                + " come to " + pence(cost[0]) + "; the till has " + pence(b.till) + (credit ? " and its credit " + pence(canPay - b.till) : "") + ".");
        }
        MarketMaker.inOrTreasury(village, payOut(level, village, b, cost[0], day));   // [econ-1a] into the market's cash once it is open
        bought(level, village, b, key, one, n, cost[0], "the town stores", cost[2], cost[1], day);
        storesSold(level, village, key, one, n, cost[0], b.name);
        return new OrderResult(true, n, cost[0], cost[1], "Bought " + n + " " + nameOf(key).toLowerCase(Locale.ROOT) + " at "
            + coins(each) + (cost[1] > 0 ? ", " + discount(n) + "% off" : "") + ": " + pence(cost[0]) + ".");
    }

    /** Stock in, at what it cost: into the lot, the day's purchases and the order book. */
    static void bought(ServerLevel level, UUID village, Business b, String key, ItemStack one, long n, long pence, String from, long unitH, long off, long day) {
        b.stock.computeIfAbsent(key, k -> new Lot()).add(n, pence);
        Day d = b.today(day);
        d.bought += pence;
        b.allTime.bought += pence;
        b.orders.add(new Business.Order(day, from, key, n, unitH, off, pence));
        while (b.orders.size() > 40) b.orders.remove(0);
        dirty();
    }

    /**
     * Stock a business had too few of, had there and then: from another business that has it (the shop of the smithy),
     * at that one's trade price, else from the town's stores at their unit price. Paid out of its till (its credit when
     * short). {@code made}: wanted for making (the ingredients), not to sell as it is.
     */
    static void supply(ServerLevel level, UUID village, Business b, ItemStack what, String key, long n, long day, boolean made) {
        if (n <= 0) return;
        ItemStack one = what.copyWithCount(1);
        for (Business o : all(village)) {
            if (o == b || o.town() || o.mirror()) continue;
            Lot has = o.stock.get(key);
            if (has == null || has.qty <= 0) continue;
            long k = Math.min(n, has.qty);
            long cost = has.take(k);
            long price = Math.round(cost * (1 + o.markup / 2));
            long paid = payOut(level, village, b, price, day);
            o.till += paid;
            o.account += price - paid;                                   // what the buyer owes the town, the town owes the maker
            Day od = o.today(day);
            od.revenue += price;
            od.cogs += cost;
            Business.Line ol = od.line(key);
            ol.units += k;
            ol.revenue += price;
            ol.cogs += cost;
            o.allTime.revenue += price;
            o.allTime.cogs += cost;
            bought(level, village, b, key, one, k, price, o.name, Math.round(price * 100.0 / k), 0, day);
            n -= k;
            if (n <= 0) return;
        }
        double each = storesEach(level, village, one);
        long[] cost = orderCost(each, n);
        MarketMaker.inOrTreasury(village, payOut(level, village, b, cost[0], day));   // [econ-1a] into the market's cash once it is open
        bought(level, village, b, key, one, n, cost[0], "the town stores", cost[2], cost[1], day);
        storesSold(level, village, key, one, n, cost[0], b.name);
    }

    // ------------------------------------------------------------------ the town's stores' own books

    /** The stores sold so many of this to a business: their books (the cost out of their own stock). */
    static void storesSold(ServerLevel level, UUID village, String key, ItemStack one, long n, long p, String to) {
        Business s = of(village, "storehouse");
        if (s == null) return;
        long cogs = storesTake(level, village, key, one, n);
        long day = today(level);
        Day d = s.today(day);
        d.revenue += p;
        d.cogs += cogs;
        Business.Line l = d.line(key);
        l.units += n;
        l.revenue += p;
        l.cogs += cogs;
        s.allTime.revenue += p;
        s.allTime.cogs += cogs;
        d.toTown += 0;
        s.note(day, "sold " + n + " " + nameOf(key).toLowerCase(Locale.ROOT) + " to " + to + " for " + pence(p));
        dirty();
    }

    /** So many of a ware out of the stores' own stock books: their cost (at the standard cost when the books have none). */
    static long storesTake(ServerLevel level, UUID village, String key, ItemStack one, long n) {
        Lot l = town(village).stores.get(key);
        long had = l == null ? 0 : Math.min(n, l.qty);
        long cost = l == null ? 0 : l.take(had);
        if (had < n) cost += Math.round(CostBook.of(one).total() * 100 * (n - had));
        return cost;
    }

    /** Goods into the stores at what they cost the town (a producer's work at its wages, a player's lot at what was paid). */
    public static void storesIn(UUID village, ItemStack one, long n, long pence) {
        if (village == null || one.isEmpty() || n <= 0) return;
        town(village).stores.computeIfAbsent(Stockroom.key(one), k -> new Lot()).add(n, pence);
        dirty();
    }

    // ------------------------------------------------------------------ making

    /** What a maker's hands are making for, by its trade (Economy.openCraft): its business, or null for the town's. */
    @Nullable
    static Business makerFor(UUID village, StationTask trade) {
        Town t = town(village);
        Type type = Business.forTrade(trade);
        if (type == null) return null;
        if (type == Type.CAFE) {
            for (Type k : new Type[]{ Type.CAFE, Type.RESTAURANT, Type.BAKERY, Type.TAVERN }) if (t.biz.containsKey(k.id())) return t.biz.get(k.id());
            return null;
        }
        Business b = t.biz.get(type.id());
        return b == null || b.town() || b.mirror() ? null : b;
    }

    /** Is this burnt in the making (its fuel's share)? */
    static boolean fuel(String key) {
        return key.equals("minecraft:coal") || key.equals("minecraft:charcoal") || key.endsWith("_planks") || key.endsWith("_log")
            || key.equals("minecraft:stick") || key.equals("minecraft:blaze_powder") || key.equals("minecraft:dried_kelp_block")
            || key.equals("minecraft:coal_block");
    }

    /**
     * A maker's piece of work done (Economy.closeCraft): what it took out of the stores and put back, item by item. The
     * business it works for used the difference: each ingredient out of its own stock at its cost (bought there and then at
     * the stores' price when it had too few), the fuel among them; what came out is its stock at exactly that cost, shared
     * over what was made by what each is worth.
     */
    public static void crafted(UUID village, @Nullable StationTask trade, Map<String, Integer> net, Map<String, ItemStack> kinds) {
        if (village == null || trade == null || net.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        Business b = makerFor(village, trade);
        if (b == null) return;
        Map<String, Long> used = new LinkedHashMap<>();
        Map<String, Long> made = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new HashMap<>();
        for (Map.Entry<String, Integer> e : net.entrySet()) {
            ItemStack k = kinds.get(e.getKey());
            if (k == null || e.getValue() == 0) continue;
            String key = Stockroom.key(k);
            samples.putIfAbsent(key, k.copyWithCount(1));
            if (e.getValue() < 0) used.merge(key, (long) -e.getValue(), Long::sum);
            else made.merge(key, (long) e.getValue(), Long::sum);
        }
        if (made.isEmpty() || used.isEmpty() && !sellsToStores(b.type)) return;
        made(level, village, b, used, made, samples);
    }

    /** A batch made at a business's bench (Bakery): its plan's makings and fuel, less what went back, into what it made. */
    public static void benchMade(ServerLevel level, UUID village, Type type, Bench.Plan plan, ItemStack made) {
        Business b = of(village, type.id());
        if (b == null || b.town() || made.isEmpty()) return;
        Map<String, Long> used = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new HashMap<>();
        java.util.function.BiConsumer<net.minecraft.world.item.Item, Long> use = (it, n) -> {
            ItemStack one = new ItemStack(it);
            String key = Stockroom.key(one);
            samples.putIfAbsent(key, one);
            used.merge(key, n, Long::sum);
        };
        plan.takes.forEach((it, n) -> use.accept(it, (long) n));
        plan.burnt.forEach((it, n) -> use.accept(it, (long) n));
        plan.back.forEach((it, n) -> use.accept(it, (long) -n));
        used.values().removeIf(n -> n <= 0);
        if (used.isEmpty()) return;
        String key = Stockroom.key(made);
        samples.put(key, made.copyWithCount(1));
        made(level, village, b, used, Map.of(key, (long) made.getCount()), samples);
    }

    /** The making booked: ingredients out at cost, products in at that cost. Returns the cost of the batch (pennies). */
    static long made(ServerLevel level, UUID village, Business b, Map<String, Long> used, Map<String, Long> made, Map<String, ItemStack> samples) {
        long day = today(level);
        Day d = b.today(day);
        long materials = 0, fuelCost = 0;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> e : used.entrySet()) {
            String key = e.getKey();
            long n = e.getValue();
            Lot lot = b.stock.computeIfAbsent(key, k -> new Lot());
            if (lot.qty < n) supply(level, village, b, samples.get(key), key, n - lot.qty, day, true);
            long c = lot.take(n);
            if (fuel(key)) fuelCost += c; else materials += c;
            d.line(key).used += n;
            b.allTime.line(key).used += n;
            parts.add(n + " " + nameOf(key).toLowerCase(Locale.ROOT) + " " + pence(c));
        }
        long total = materials + fuelCost;
        double weight = 0;
        Map<String, Double> w = new HashMap<>();
        for (Map.Entry<String, Long> e : made.entrySet()) {
            double x = Math.max(0.01, CostBook.of(samples.get(e.getKey())).total()) * e.getValue();
            w.put(e.getKey(), x);
            weight += x;
        }
        long left = total;
        int i = 0;
        for (Map.Entry<String, Long> e : made.entrySet()) {
            long share = ++i == made.size() ? left : Math.round(total * w.get(e.getKey()) / weight);
            left -= share;
            b.stock.computeIfAbsent(e.getKey(), k -> new Lot()).add(e.getValue(), share);
            d.line(e.getKey()).made += e.getValue();
            b.allTime.line(e.getKey()).made += e.getValue();
            b.cards.put(e.getKey(), e.getValue() + " made" + (parts.isEmpty() ? " by its hands' work alone" : " of " + String.join(", ", parts))
                + (fuelCost > 0 ? " (fuel " + pence(fuelCost) + ")" : "") + ": " + pence(share) + ", " + coins(share / 100.0 / e.getValue()) + " each, day " + (day + 1));
        }
        if (sellsToStores(b.type)) for (Map.Entry<String, Long> e : made.entrySet()) toStores(level, village, b, e.getKey(), samples.get(e.getKey()), e.getValue(), day);
        dirty();
        return total;
    }

    /** A maker with no counter of its own (the smithy, the brewery, the bakery, the apiary...) sells what it makes to the
     *  town's stores, where the shop, the café and the town buy it. */
    public static boolean sellsToStores(Type t) {
        return switch (t) {
            case BAKERY, BREWERY, SMITHY, TAILOR, FLETCHER, APIARY, ENCHANTER, POWDER, MAPROOM, BUTCHER, MILL -> true;
            default -> false;
        };
    }

    /**
     * What a maker made, sold to the town's stores: at the stores' paying price (four fifths of the going price), never
     * under what it cost the maker and its markup (a producer is paid a price that covers its costs, its hands' time among
     * them: a maker at a loss raises its markup like any business). The treasury pays
     * into its till; the goods go into the stores' books at that price.
     */
    static void toStores(ServerLevel level, UUID village, Business b, String key, ItemStack one, long n, long day) {
        Lot lot = b.stock.get(key);
        if (lot == null || lot.qty <= 0 || one == null || one.isEmpty()) return;
        long k = Math.min(n, lot.qty);
        long cost = lot.take(k);
        double bid = MarketMaker.live(village) ? MarketMaker.bid(level, village, one) : Market.bidEach(level, village, one.copyWithCount(1));   // [econ-1a]
        long price = Math.max(Math.round(bid * 100 * k), Math.round(cost * (1 + b.markup)));
        long got = MarketMaker.outOrTreasury(village, price);                // [econ-1a] the market pays, once it is open
        b.till += got;
        b.account += price - got;                                        // what the treasury could not pay now, it owes
        Day d = b.today(day);
        d.revenue += price;
        d.cogs += cost;
        d.toTown += price;
        Business.Line l = d.line(key);
        l.units += k;
        l.revenue += price;
        l.cogs += cost;
        b.allTime.revenue += price;
        b.allTime.cogs += cost;
        b.allTime.toTown += price;
        Business.Line all = b.allTime.line(key);
        all.units += k;
        all.revenue += price;
        all.cogs += cost;
        storesIn(village, one, k, price);
        Business s = of(village, "storehouse");
        if (s != null) {
            s.today(day).bought += price;
            s.allTime.bought += price;
            s.orders.add(new Business.Order(day, b.name, key, k, Math.round(price * 100.0 / k), 0, price));
            while (s.orders.size() > 40) s.orders.remove(0);
        }
    }

    // ------------------------------------------------------------------ prices

    /**
     * The least a business lets one of this go for: its full cost a piece (its average cost and its share of the week's
     * wages, rent and running costs over what it sold) and its markup; never more than three fifths over the going price.
     * Only once it has a few days' books to go by. Returns {@code each} when it has nothing to say.
     */
    public static double priceEach(ServerLevel level, UUID village, ItemStack one, double each) {
        if (village == null || one.isEmpty()) return each;
        Business b = behind(village, Stockroom.sellerFor(one));
        if (b == null || b.town() || b.mirror()) return each;
        long day = today(level);
        if (day - b.opened < 3) return each;
        // Only what it deals in itself: what it holds in stock or makes (the stores' own bread is the stores' to price).
        String key = Stockroom.key(one);
        Lot held = b.stock.get(key);
        if ((held == null || held.qty <= 0) && !b.cards.containsKey(key)) return each;
        // Worked out once a minute a ware: the counters ask it at every sign and every purchase.
        String memo = village + "|" + b.id + "|" + key;
        double[] m = PRICE_MEMO.get(memo);
        long now = level.getGameTime();
        double full;
        if (m != null && now - (long) m[0] < 1200 && now >= (long) m[0]) {
            full = m[1];
        } else {
            full = fullCost(level, village, b, one, day);
            if (PRICE_MEMO.size() > 4096) PRICE_MEMO.clear();
            PRICE_MEMO.put(memo, new double[]{ now, full });
        }
        if (full <= 0) return each;
        double floor = full * (1 + b.markup);
        return Math.max(each, Math.min(floor, each * MOST_OVER_GOING));
    }

    private static final Map<String, double[]> PRICE_MEMO = new ConcurrentHashMap<>();

    /** What one of this costs the business in full: its average cost, and its overheads shared over what it sells. */
    static double fullCost(ServerLevel level, UUID village, Business b, ItemStack one, long day) {
        String key = Stockroom.key(one);
        Lot lot = b.stock.get(key);
        double each = lot != null && lot.qty > 0 ? lot.each() / 100.0 : CostBook.of(one).materials() + CostBook.of(one).fuel();
        Day w = b.last(day - 1, 7);
        long units = 0;
        for (Business.Line l : w.lines.values()) units += l.units;
        if (units <= 0 || w.revenue <= 0) return each;
        // The overheads shared by the coin each sale brings in: a dear thing bears more of them than a cheap one.
        double overheads = (w.wages + w.rent + w.other + w.interest) / 100.0;
        double shareOfTakings = w.cogs <= 0 ? 0 : overheads / (w.cogs / 100.0);
        return each * (1 + Math.min(3.0, shareOfTakings));
    }

    // ------------------------------------------------------------------ the morning

    /**
     * Each morning (Market.tick, before the wages): the businesses brought up to date with what stands; for each, the
     * stock counted against the stores (the town paying for what it used of it, the stale written off), the day's rent,
     * lights and repairs, interest on what it owes, its trade credit paid down, its weekly drawings, how it is doing and
     * what it does about it, and its orders for the day; then the stores' own books.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (TownLod.away(id)) return;
        ensure(level, id);
        Town t = town(id);
        if (t.closed >= day) return;
        t.closed = day;
        CoinCensus.morning(level, v, day);
        producers(level, v, day);
        stocktake(level, v, day);
        for (Business b : all(id)) {
            if (b.town() || b.mirror()) continue;
            if (!b.open) continue;
            overheads(level, id, b, day);
            interest(level, id, b, day);
            spoil(level, id, b, day);
            repay(level, id, b, day);
            if ((day - b.opened) % 7 == 6) draw(level, id, b, day);
            review(level, id, b, day);
            reorder(level, id, b, day);
            b.today(day).cashEnd = b.till;
        }
        owners(level, v, day);
        dirty();
    }

    /** The day's rent (to the town, which owns the building), a torch for the evening, and the repairs. */
    static void overheads(ServerLevel level, UUID village, Business b, long day) {
        Day d = b.today(day);
        long rent = rent(village, b);
        payTown(level, village, b, rent, day);
        d.rent += rent;
        b.allTime.rent += rent;
        long other = 0;
        Villages.Village v = Villages.get(village);
        // Its lights: a torch of an evening out of the stores, while they have plenty, at the stores' price.
        if (v != null && b.type.size != Business.Size.YARD && Market.stock(level, village, s -> s.is(Items.TORCH)) > 32
            && takeLights(level, v)) {
            long torch = Math.round(storesEach(level, village, new ItemStack(Items.TORCH)) * 100);
            MarketMaker.inOrTreasury(village, payOut(level, village, b, torch, day));   // [econ-1a] the torch is the market's to sell
            d.other += torch;
            b.allTime.other += torch;
        }
        // Its repairs: a tenth of its rent, to the town's builders.
        other += rent / 10;
        payTown(level, village, b, other, day);
        d.other += other;
        b.allTime.other += other;
    }

    /** [econ-1a] Its evening's torch out of the stores: paid for in its overheads, so not charged again at the take. */
    static boolean takeLights(ServerLevel level, Villages.Village v) {
        try (Accounts.Scope s = Accounts.free("a business's lights, paid in its overheads")) {
            return TownWork.take(level, v, st -> st.is(Items.TORCH), 1);
        }
    }

    /** A day's rent, in pennies: its size, at what the place is (a village half as much again as a hamlet...). */
    static long rent(UUID village, Business b) {
        return (long) b.type.size.rentPence * Wealth.standing(village) / 10;
    }

    /** Interest: the bank's on its loan (a week's rate a day at a time), the stores' on credit owed past a week. */
    static void interest(ServerLevel level, UUID village, Business b, long day) {
        Day d = b.today(day);
        long due = 0;
        if (b.loan > 0) {
            long i = Math.max(1, b.loan * b.rateBp / 10000 / 7);
            b.loan += i;
            due += i;
        }
        if (b.account < 0 && creditDays(b, day) > CREDIT_FREE_DAYS) {
            long i = Math.max(1, -b.account * CREDIT_BP_A_DAY / 10000);
            b.account -= i;
            due += i;
        }
        d.interest += due;
        b.allTime.interest += due;
    }

    /** How many days in a row it has owed the stores (from its day books' closing cash: a rough count). */
    static int creditDays(Business b, long day) {
        int n = 0;
        for (int i = b.days.size() - 1; i >= 0; i--) {
            if (b.days.get(i).cashEnd > 0 && b.days.get(i).day < day) break;
            n++;
        }
        return n;
    }

    /** What it owes paid down out of the till over a float: the stores' credit first, then the bank's, a coin at a time. */
    static void repay(ServerLevel level, UUID village, Business b, long day) {
        long floor = floatOf(b, day);
        if (b.account < 0 && b.till > floor) {
            long pay = Math.min(-b.account, b.till - floor);
            b.till -= pay;
            b.account += pay;
            toTreasury(village, pay);
        }
        if (b.loan > 0 && b.till > floor + 100 && b.lender.equals("bank")) {
            int coins = (int) Math.min(Math.min(b.loan, b.till - floor) / 100, Math.max(1, b.loan / 800));
            if (coins > 0) {
                b.till -= coins * 100L;
                b.loan -= coins * 100L;
                if (Bank.open(village)) Bank.firmRepaid(village, coins);
                else Ledger.addCoins(village, coins);           // the bank gone: what it was owed goes to the town that took on its books
                if (b.loan <= 0) { b.loan = 0; b.lender = ""; b.note(day, "paid off its loan"); }
            }
        }
        dirty();
    }

    /** The float it keeps in its till: three days' costs, never under ten coins. */
    static long floatOf(Business b, long day) {
        Day w = b.last(day - 1, 7);
        long costs = w.wages + w.rent + w.other + w.bought;
        return Math.max(1000, costs * 3 / 7);
    }

    /** Fresh food held long over what it sells goes stale: a twentieth of the excess a day, written off at cost. */
    static void spoil(ServerLevel level, UUID village, Business b, long day) {
        Day d = b.today(day);
        Day w = b.last(day - 1, 7);
        for (Map.Entry<String, Lot> e : b.stock.entrySet()) {
            Lot lot = e.getValue();
            if (lot.qty <= 0) continue;
            ItemStack one = Stockroom.sampleOf(e.getKey());
            if (one.isEmpty() || !perishable(one)) continue;
            Business.Line l = w.lines.get(e.getKey());
            long use = l == null ? 0 : (l.units + l.used + 6) / 7;
            long keep = Math.max(8, use * 3);
            if (lot.qty <= keep) continue;
            long gone = Math.max(1, (lot.qty - keep) / 20);
            long cost = lot.take(gone);
            d.losses += cost;
            b.allTime.losses += cost;
            if (cost >= 100) b.note(day, gone + " " + nameOf(e.getKey()).toLowerCase(Locale.ROOT) + " went stale (" + pence(cost) + ")");
        }
    }

    /** Fresh food: what spoils. Bread keeps a few days, a cake less; dried and baked goods are counted the same. */
    static boolean perishable(ItemStack one) {
        return one.get(DataComponents.FOOD) != null && !one.is(Items.DRIED_KELP) && !one.is(Items.GOLDEN_CARROT) && !one.is(Items.GOLDEN_APPLE)
            && !one.is(Items.HONEY_BOTTLE);
    }

    /**
     * The week's drawings: what the week made over the float, shared by stake: a folk's into its purse, the town's into the
     * treasury, a player's kept for it to take. Whole coins; the odd pennies stay in the till.
     */
    static void draw(ServerLevel level, UUID village, Business b, long day) {
        Day week = b.last(day - 1, 7);
        long net = week.net();
        long spare = b.till - floatOf(b, day);
        long draw = Math.min(net, spare);
        if (draw < 100 || b.account < 0) return;
        Day d = b.today(day);
        long given = 0;
        for (Stake s : b.owners) {
            long share = draw * s.parts() / Business.WHOLE / 100 * 100;
            if (share <= 0) continue;
            switch (s.holder()) {
                case FOLK -> {
                    VillageFolkEntity f = s.who() == null ? null : Homes.loaded(village, s.who());
                    if (f == null || !f.isAlive()) continue;
                    f.earn((int) (share / 100));
                }
                case PLAYER -> b.ownersDue.merge(s.who(), share, Long::sum);
                case TOWN -> toTreasury(village, share);
            }
            given += share;
        }
        b.till -= given;
        d.drawings += given;
        b.allTime.drawings += given;
        if (given > 0) b.note(day, "the owners drew " + pence(given) + " of the week's " + pence(net) + " profit");
    }

    /**
     * How it is doing, and what it does about it: a week at a loss three days running raises its markup; a week, a hand
     * on short time; ten days, its dearest-to-make line dropped from its orders; a fortnight with its credit gone, wound
     * up. A good week (over a fifth of its takings) brings its prices down toward the going price.
     */
    static void review(ServerLevel level, UUID village, Business b, long day) {
        if (day - b.opened < 3) return;
        Day week = b.last(day - 1, 7);
        long net = week.net();
        if (net < 0) b.lossDays++;
        else b.lossDays = Math.max(0, b.lossDays - 2);
        if (net < 0 && b.lossDays % 3 == 0 && b.markup < MOST_MARKUP) {
            double was = b.markup;
            b.markup = Math.min(MOST_MARKUP, b.markup + 0.10);
            b.note(day, "prices up: markup " + pct(was) + " -> " + pct(b.markup) + ", the week lost " + pence(-net)
                + " (wages " + pence(week.wages) + ", rent " + pence(week.rent) + " on takings " + pence(week.revenue) + ")");
        } else if (net > week.revenue / 5 && week.revenue > 0 && b.markup > LEAST_MARKUP && (day - b.opened) % 7 == 0) {
            double was = b.markup;
            b.markup = Math.max(LEAST_MARKUP, b.markup - 0.05);
            b.note(day, "prices down: markup " + pct(was) + " -> " + pct(b.markup) + ", a good week (" + pence(net) + " on " + pence(week.revenue)
                + ") and the stores' going price to compete with");
        }
        if (b.lossDays == 7) {
            List<VillageFolkEntity> hands = staff(village, b);
            hands.removeIf(f -> b.shortTime.contains(f.getUUID()) || b.ownedBy(f.getUUID()));
            if (!hands.isEmpty()) {
                VillageFolkEntity f = hands.get(hands.size() - 1);
                b.shortTime.add(f.getUUID());
                b.note(day, f.displayNameCap() + " put on short time (half pay): a week at a loss");
                FolkTalk.speak(f, "Short time, they say. Half pay till trade picks up.");
            }
        }
        if (b.lossDays == 10) {
            String worst = null;
            double worstMargin = 1e9;
            for (Map.Entry<String, Business.Line> e : week.lines.entrySet()) {
                Business.Line l = e.getValue();
                if (l.revenue <= 0 || b.dropped.contains(e.getKey())) continue;
                double m = (l.revenue - l.cogs) / (double) l.revenue;
                if (m < worstMargin) { worstMargin = m; worst = e.getKey(); }
            }
            if (worst != null) {
                b.dropped.add(worst);
                b.note(day, "stopped ordering " + nameOf(worst).toLowerCase(Locale.ROOT) + ": its margin was " + pct(worstMargin));
            }
        }
        if (net >= 0 && !b.shortTime.isEmpty() && b.lossDays == 0) {
            b.shortTime.clear();
            b.note(day, "back on full time: trade picked up");
        }
        if (b.lossDays >= 14 && b.account <= -creditLimit(b, day) && b.till < 100) windUp(level, village, b, day, "a fortnight at a loss, its credit gone");
    }

    static String pct(double x) {
        return Math.round(x * 100) + "%";
    }

    /**
     * Wound up: its stock sold back to the stores at cost (the treasury pays), what it owes paid out of what that brings,
     * the owners bearing what is left over or short; the premises the town's, to sell to the next to take it on.
     */
    static void windUp(ServerLevel level, UUID village, Business b, long day, String why) {
        long stock = 0;
        for (Map.Entry<String, Lot> e : b.stock.entrySet()) {
            if (e.getValue().qty <= 0) continue;
            storesIn(village, Stockroom.sampleOf(e.getKey()), e.getValue().qty, e.getValue().cost);
            stock += e.getValue().cost;
        }
        b.stock.clear();
        b.till += MarketMaker.outOrTreasury(village, stock);                // [econ-1a] the market buys it back, once it is open
        if (b.account < 0) {
            long pay = Math.min(-b.account, b.till);
            b.till -= pay;
            b.account += pay;
            toTreasury(village, pay);
            if (b.account < 0) {
                b.note(day, "its last " + pence(-b.account) + " owed to the stores written off");
                b.account = 0;
            }
        }
        if (b.loan > 0) {
            int coins = (int) Math.min(b.loan, b.till) / 100;
            if (coins > 0 && Bank.open(village)) Bank.firmRepaid(village, coins);
            b.till -= coins * 100L;
            b.loan -= coins * 100L;
            if (b.loan > 0) b.note(day, "its last " + pence(b.loan) + " owed to the bank written off");
            b.loan = 0;
        }
        // What is left goes to its owners.
        long left = b.till;
        for (Stake s : b.owners) {
            long share = left * s.parts() / Business.WHOLE / 100 * 100;
            if (share <= 0) continue;
            if (s.holder() == Holder.FOLK && s.who() != null) {
                VillageFolkEntity f = Homes.loaded(village, s.who());
                if (f != null) { f.earn((int) (share / 100)); b.till -= share; }
            } else if (s.holder() == Holder.PLAYER) {
                b.ownersDue.merge(s.who(), share, Long::sum);
                b.till -= share;
            }
        }
        toTreasury(village, b.till);
        b.till = 0;
        String was = b.ownersWords() + (b.owners.size() == 1 ? " is" : " are");
        b.owners.clear();
        b.owners.add(new Stake(Holder.TOWN, null, "the town", Business.WHOLE));
        b.closedDay = day;
        b.shortTime.clear();
        b.lossDays = 0;
        b.markup = 0.35;
        b.note(day, "wound up (" + why + "): " + was + " out; the town keeps it till someone takes it on");
        Villages.tell(village, day, "the " + b.type.title.toLowerCase(Locale.ROOT) + " was wound up: " + why);
        dirty();
    }

    /**
     * Each morning the businesses its owners have left are seen to: an owner dead leaves its stake to its partner or a
     * grown child; one gone from town has sold it back to the town; a business the town is holding (wound up, or with
     * nobody to take it on) is taken on by the best placed with the means (its price: its stock at cost and its till, and
     * a month's profit for its goodwill).
     */
    static void owners(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (Business b : all(id)) {
            if (b.type.keeping != Business.Keeping.OWN) continue;
            if (b.town() && day - b.closedDay >= 2 && day % 3 == 0) {
                VillageFolkEntity f = proprietorFor(level, id, b, null);
                if (f != null && f.purse() >= Math.max(4, worth(level, id, b) / 300)) {
                    long price = worth(level, id, b);
                    int put = (int) Math.min(f.purse() / 2, price / 100);
                    f.spend(put);
                    toTreasury(id, put * 100L);
                    long short_ = price - put * 100L;
                    b.owners.clear();
                    b.owners.add(new Stake(Holder.FOLK, f.getUUID(), f.displayNameCap(), Business.WHOLE));
                    if (short_ > 0) {
                        long lent = bankLends(id, b, short_);
                        if (lent > 0) { b.till -= lent; toTreasury(id, lent); }
                    }
                    b.note(day, f.displayNameCap() + " bought it of the town for " + pence(price));
                    Villages.tell(id, day, f.displayNameCap() + " took on the " + b.type.title.toLowerCase(Locale.ROOT) + " as their own business");
                }
            }
        }
    }

    /** What a business is worth to a buyer (pennies): its stock at cost, its till, less what it owes, and a month's profit. */
    public static long worth(ServerLevel level, UUID village, Business b) {
        long day = today(level);
        long goodwill = Math.max(0, b.last(day - 1, 28).net() / 28 * 28);
        return Math.max(1000, b.stockCost() + b.till + Math.min(0, b.account) - b.loan + goodwill);
    }

    /** An owner gone (VillageFolkEntity.die, leaveFor): its stake to its heir, or sold back to the town; a dead folk's
     *  purse to its heir too (it used to go with it). */
    public static void left(@Nullable UUID village, VillageFolkEntity f, boolean died) {
        if (village == null) return;
        VillageFolkEntity heir = died ? heirOf(village, f) : null;
        if (died && f.purse() > 0) {
            int purse = f.purse();
            if (f.spend(purse)) {
                if (heir != null) heir.earn(purse);
                else Ledger.addCoins(village, purse);
                long day = f.level().getDayTime() / 24000L;
                Villages.tell(village, day, f.displayNameCap() + "'s purse, " + purse + Homes.coins(purse) + ", went to "
                    + (heir != null ? heir.displayNameCap() : "the village"));
            }
        }
        long day = f.level().getDayTime() / 24000L;
        for (Business b : all(village)) {
            if (!b.ownedBy(f.getUUID())) continue;
            for (int i = 0; i < b.owners.size(); i++) {
                Stake s = b.owners.get(i);
                if (!f.getUUID().equals(s.who())) continue;
                if (heir != null) {
                    b.owners.set(i, new Stake(Holder.FOLK, heir.getUUID(), heir.displayNameCap(), s.parts()));
                    b.note(day, f.displayNameCap() + "'s share passed to " + heir.displayNameCap());
                } else {
                    if (!died && f.level() instanceof ServerLevel sl) {
                        // Gone from town: the town buys its share at what the business is worth, as far as the treasury runs.
                        long share = worth(sl, village, b) * s.parts() / Business.WHOLE;
                        long got = fromTreasury(village, share / 100 * 100);
                        f.earn((int) (got / 100));
                        toTreasury(village, got % 100);
                    }
                    b.owners.set(i, new Stake(Holder.TOWN, null, "the town", s.parts()));
                    b.note(day, f.displayNameCap() + (died ? " died; its share went to the town" : " left town; its share was sold back to the town"));
                }
            }
            merge(b);
            // Wholly the town's now: its affairs settled (its stock to the stores, its debts paid, its till to the treasury).
            if (b.town() && b.type.keeping == Business.Keeping.OWN && f.level() instanceof ServerLevel sl) {
                windUp(sl, village, b, day, died ? "its owner died with nobody to take it on" : "its owner left town");
            }
        }
        dirty();
    }

    /** Stakes of the same holder added together. */
    static void merge(Business b) {
        Map<String, Stake> by = new LinkedHashMap<>();
        for (Stake s : b.owners) {
            String k = s.holder() + "|" + s.who();
            Stake was = by.get(k);
            by.put(k, was == null ? s : new Stake(s.holder(), s.who(), s.name(), was.parts() + s.parts()));
        }
        b.owners.clear();
        b.owners.addAll(by.values());
    }

    @Nullable
    static VillageFolkEntity heirOf(UUID village, VillageFolkEntity f) {
        UUID partner = f.life().partner();
        VillageFolkEntity heir = partner == null ? null : Homes.loaded(village, partner);
        if (heir != null && heir.isAlive() && heir != f) return heir;
        for (AssistantEntity o : Villages.folkOf(village)) {
            if (o instanceof VillageFolkEntity c && c != f && c.isAlive() && !c.isBaby() && c.parentIds().contains(f.getUUID())) return c;
        }
        return null;
    }

    /**
     * A fire on a business's premises (FireBrigade, when it is out): a tenth of what it holds there spoilt by the fire and
     * the water, and three days' rent to the town's builders for the repairs, written in its books as its loss.
     */
    public static void fire(ServerLevel level, UUID village, BlockPos at, String line) {
        long day = today(level);
        for (Business b : all(village)) {
            if (b.town() || b.mirror() || b.at == null || b.at.distSqr(at) > 14 * 14) continue;
            long lost = 0;
            for (Lot lot : b.stock.values()) lost += lot.take((lot.qty + 9) / 10);
            long repairs = rent(village, b) * 3;
            payTown(level, village, b, repairs, day);
            Day d = b.today(day);
            d.losses += lost + repairs;
            b.allTime.losses += lost + repairs;
            b.note(day, "fire: " + pence(lost) + " of stock spoilt and " + pence(repairs) + " of repairs (" + line + ")");
        }
        dirty();
    }

    // ------------------------------------------------------------------ the stocktake

    /**
     * The stock counted against the stores: what the businesses hold in their name is there in the stores, or the town
     * used it (the larder's meals, the builders, the watch) and pays the business its trade price for it; what the stores'
     * own books hold over what is left is what the town used itself, at cost.
     */
    static void stocktake(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Town t = town(id);
        Map<String, Long> claims = new HashMap<>();
        for (Business b : t.biz.values()) {
            if (b.town() || b.mirror()) continue;
            for (Map.Entry<String, Lot> e : b.stock.entrySet()) if (e.getValue().qty > 0) claims.merge(e.getKey(), e.getValue().qty, Long::sum);
        }
        java.util.Set<String> keys = new java.util.HashSet<>(claims.keySet());
        keys.addAll(t.stores.keySet());
        Business stores = t.biz.get("storehouse");
        for (String key : keys) {
            long have = Market.stock(level, id, Stockroom.matcher(key)) + inShop(level, id, key);
            long claimed = claims.getOrDefault(key, 0L);
            if (claimed > have) {
                long over = claimed - have;
                List<Business> holders = new ArrayList<>();
                for (Business b : t.biz.values()) if (!b.town() && !b.mirror() && b.stock.containsKey(key) && b.stock.get(key).qty > 0) holders.add(b);
                holders.sort((a, b) -> Long.compare(b.stock.get(key).qty, a.stock.get(key).qty));
                for (Business b : holders) {
                    if (over <= 0) break;
                    Lot lot = b.stock.get(key);
                    long k = Math.min(over, lot.qty);
                    long cost = lot.take(k);
                    long price = Math.round(cost * (1 + b.markup / 2));
                    long got = MarketMaker.outOrTreasury(id, price);            // [econ-1a] the market pays, once it is open
                    b.till += got;
                    if (got < price) b.account += price - got;          // the town owes it the rest
                    Day d = b.today(day);
                    d.revenue += price;
                    d.cogs += cost;
                    d.toTown += price;
                    Business.Line l = d.line(key);
                    l.units += k;
                    l.revenue += price;
                    l.cogs += cost;
                    b.allTime.revenue += price;
                    b.allTime.cogs += cost;
                    b.allTime.toTown += price;
                    over -= k;
                }
                claimed = have;
            }
            Lot own = t.stores.get(key);
            if (own != null && own.qty > have - claimed) {
                long used = own.qty - Math.max(0, have - claimed);
                long cost = own.take(used);
                if (stores != null && cost > 0) {
                    Day d = stores.today(day);
                    d.other += cost;
                    stores.allTime.other += cost;
                }
            }
        }
        dirty();
    }

    /** What the shop's own floor and stockroom hold of a ware (Store: out of the stores, the shop's own). */
    static long inShop(ServerLevel level, UUID village, String key) {
        try {
            return ShopStock.held(level, village, Stockroom.matcher(key));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Yesterday's producers' work into the stores' books: what each producing trade brought in (Economy), at what the
     * town paid its hands for the day, shared over what they made by what each is worth, and their tools' wear: the wheat
     * the farmers grew costs the town their wages a piece. The stores sell it on at no less.
     */
    static void producers(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (MarketMaker.live(id)) return;                                   // [econ-1a] the market bought it, at the bid, and booked it in
        Map<String, int[]> items = Economy.yesterdayItems(id);
        Map<String, Map<StationTask, Integer>> makers = Economy.yesterdayMakers(id);
        if (items.isEmpty()) return;
        Map<StationTask, Long> wages = new java.util.EnumMap<>(StationTask.class);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != StationTask.NONE && employer(id, f) == null) {
                wages.merge(f.stationTask(), (long) Wealth.wage(f) * 100, Long::sum);
            }
        }
        Map<StationTask, Double> weight = new java.util.EnumMap<>(StationTask.class);
        Map<String, Map<StationTask, Double>> parts = new HashMap<>();
        for (Map.Entry<String, Map<StationTask, Integer>> e : makers.entrySet()) {
            ItemStack one = sampleFromId(e.getKey());
            if (one.isEmpty()) continue;
            double each = Math.max(0.01, CostBook.of(one).total());
            for (Map.Entry<StationTask, Integer> m : e.getValue().entrySet()) {
                if (Business.forTrade(m.getKey()) != null) continue;               // a business's own making is its own
                double w = each * m.getValue();
                weight.merge(m.getKey(), w, Double::sum);
                parts.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(m.getKey(), w);
            }
        }
        Business stores = of(id, "storehouse");
        for (Map.Entry<String, Map<StationTask, Double>> e : parts.entrySet()) {
            ItemStack one = sampleFromId(e.getKey());
            long n = 0;
            long pence = 0;
            for (Map.Entry<StationTask, Double> m : e.getValue().entrySet()) {
                Integer made = makers.get(e.getKey()).get(m.getKey());
                if (made == null || made <= 0) continue;
                long wage = wages.getOrDefault(m.getKey(), 0L);
                double share = weight.getOrDefault(m.getKey(), 0.0) <= 0 ? 0 : m.getValue() / weight.get(m.getKey());
                CostBook.Basis std = CostBook.of(one);
                double toolShare = std.labour() <= 0 ? 0 : (std.tools() + std.overhead()) / std.labour();
                pence += Math.round(wage * share * (1 + toolShare));
                n += made;
            }
            if (n <= 0) continue;
            storesIn(id, one, n, pence);
            if (stores != null) {
                Day d = stores.today(day);
                d.bought += pence;
                stores.allTime.bought += pence;
                Business.Line l = d.line(Stockroom.key(one));
                l.made += n;
                stores.allTime.line(Stockroom.key(one)).made += n;
                stores.orders.add(new Business.Order(day, "the town's own hands (their wages)", Stockroom.key(one), n,
                    Math.round(pence * 100.0 / n), 0, pence));
                while (stores.orders.size() > 40) stores.orders.remove(0);
            }
        }
    }

    static ItemStack sampleFromId(String id) {
        net.minecraft.world.item.Item it = CostBook.itemOf(id.contains(":") ? id : "minecraft:" + id);
        return it == null || it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it);
    }

    // ------------------------------------------------------------------ ordering

    /**
     * Its orders for the day, as a business would: for each thing it sold or used in the last week, a day's use is its
     * week's over seven; it reorders when it holds less than a day and a half of it, up to two days' worth of fresh food
     * (bought little and often) or a week's of what keeps (bought in bulk), a half more when the price is down and a
     * quarter less when it is up, within its room and half of what its till and credit run to.
     */
    static void reorder(ServerLevel level, UUID village, Business b, long day) {
        Day w = b.last(day - 1, 7);
        long budget = (b.till + Math.max(0, creditLimit(b, day) + Math.min(0, b.account))) / 2;
        for (Map.Entry<String, Business.Line> e : w.lines.entrySet()) {
            String key = e.getKey();
            if (b.dropped.contains(key) || key.startsWith("service/")) continue;
            Business.Line l = e.getValue();
            long use = (l.used + Math.max(0, l.units - l.made) + 6) / 7;
            if (use <= 0) continue;
            Lot lot = b.stock.get(key);
            long have = lot == null ? 0 : lot.qty;
            if (have * 2 >= use * 3) continue;                              // a day and a half in hand
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            // What it makes itself it does not buy: its own bread is baked, not ordered.
            if (l.made > 0 && l.used == 0) continue;
            double cover = perishable(one) ? 2 : 7;
            int trend = PriceIndex.trend(village, one);
            if (trend < 0) cover *= 1.5;
            else if (trend > 0) cover *= 0.75;
            long want = Math.round(use * cover) - have;
            if (want <= 0) continue;
            double each = storesEach(level, village, one);
            long affordable = (long) Math.floor(budget / Math.max(1.0, each * 100));
            want = Math.min(want, affordable);
            if (want <= 0) continue;
            OrderResult r = order(level, village, b, one, want, true);
            if (r.ok()) budget -= r.total();
        }
    }

    // ------------------------------------------------------------------ players

    /** May this player see this business's full books? Its owners, the town's citizens and friends, and admins. */
    public static boolean mayRead(ServerLevel level, UUID village, Business b, net.minecraft.world.entity.player.Player p) {
        if (p.isCreative() || p.hasPermissions(2) || b.ownedBy(p.getUUID()) || b.town()) return true;
        if (Citizens.is(village, p.getUUID())) return true;
        return Standing.of(village, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND);
    }

    /** A player buys a business: the town's at its worth, a folk's at a quarter over (its owners must be tempted). */
    public static String buy(ServerLevel level, UUID village, Business b, net.minecraft.server.level.ServerPlayer p) {
        long day = today(level);
        if (b.type.keeping != Business.Keeping.OWN) return "The " + b.name.toLowerCase(Locale.ROOT) + " is the town's: it's not for sale.";
        if (b.ownedBy(p.getUUID()) && b.owners.size() == 1) return "It's yours already.";
        if (!Citizens.is(village, p.getUUID()) && !Standing.of(village, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
            return "The town sells its businesses to its citizens and friends.";
        }
        long price = worth(level, village, b);
        if (!b.town()) price = price * 5 / 4;
        int coins = (int) ((price + 99) / 100);
        if (Market.coinsHeld(p) < coins) return b.name + " is " + coins + " coins. You have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, coins);
        CoinCensus.flow(village, CoinCensus.Flow.PLAYER_IN, coins);
        long paid = coins * 100L;
        long handed = 0;
        for (int i = 0; i < b.owners.size(); i++) {
            Stake s = b.owners.get(i);
            // Whole coins to each by its stake; the last takes what is left, so not a penny goes astray.
            long share = i == b.owners.size() - 1 ? paid - handed : paid * s.parts() / Business.WHOLE / 100 * 100;
            handed += share;
            switch (s.holder()) {
                case FOLK -> {
                    VillageFolkEntity f = s.who() == null ? null : Homes.loaded(village, s.who());
                    if (f != null) {
                        f.earn((int) (share / 100));
                        toTreasury(village, share % 100);
                    } else {
                        toTreasury(village, share);
                    }
                }
                case TOWN -> toTreasury(village, share);
                case PLAYER -> b.ownersDue.merge(s.who(), share, Long::sum);
            }
        }
        String was = b.ownersWords();
        b.owners.clear();
        b.owners.add(new Stake(Holder.PLAYER, p.getUUID(), p.getGameProfile().getName(), Business.WHOLE));
        b.closedDay = -1;
        b.note(day, p.getGameProfile().getName() + " bought it of " + was + " for " + coins + " coins");
        Villages.tell(village, day, p.getGameProfile().getName() + " bought the " + b.type.title.toLowerCase(Locale.ROOT) + " from " + was);
        dirty();
        return "The " + b.name.toLowerCase(Locale.ROOT) + " is yours, for " + coins + " coins. Its drawings come to you each week: take them at its books.";
    }

    /** A player buys into a business: its coin into the till as new capital, for a stake in proportion to its worth. */
    public static String invest(ServerLevel level, UUID village, Business b, net.minecraft.server.level.ServerPlayer p, int coins) {
        long day = today(level);
        if (b.type.keeping != Business.Keeping.OWN) return "That's the town's; it takes no partners.";
        if (coins < 5) return "Five coins at the least, to buy into it.";
        if (Market.coinsHeld(p) < coins) return "You have " + Market.coinsHeld(p) + " coins.";
        long worth = worth(level, village, b);
        long in = coins * 100L;
        int parts = (int) Math.max(1, Business.WHOLE * in / (worth + in));
        Market.payOut(p, coins);
        CoinCensus.flow(village, CoinCensus.Flow.PLAYER_IN, coins);
        b.till += in;
        Day d = b.today(day);
        d.capital += in;
        b.allTime.capital += in;
        // Every stake is diluted alike, and the player's added.
        List<Stake> was = new ArrayList<>(b.owners);
        b.owners.clear();
        int left = Business.WHOLE - parts;
        int given = 0;
        for (int i = 0; i < was.size(); i++) {
            int p2 = i == was.size() - 1 ? left - given : was.get(i).parts() * left / Business.WHOLE;
            given += p2;
            b.owners.add(new Stake(was.get(i).holder(), was.get(i).who(), was.get(i).name(), p2));
        }
        b.owners.add(new Stake(Holder.PLAYER, p.getUUID(), p.getGameProfile().getName(), parts));
        merge(b);
        b.note(day, p.getGameProfile().getName() + " bought in with " + coins + " coins for " + (parts * 100 / Business.WHOLE) + "%");
        dirty();
        return "You put " + coins + " coins into the " + b.name.toLowerCase(Locale.ROOT) + " for " + String.format(Locale.ROOT, "%.1f", parts / 100.0)
            + "% of it.";
    }

    /** A player takes its drawings: whole coins into its pack. */
    public static String takeDrawings(UUID village, Business b, net.minecraft.server.level.ServerPlayer p) {
        long due = b.ownersDue.getOrDefault(p.getUUID(), 0L);
        int coins = (int) (due / 100);
        if (coins <= 0) return "Nothing waiting for you at the " + b.name.toLowerCase(Locale.ROOT) + " (" + pence(due) + ").";
        b.ownersDue.put(p.getUUID(), due - coins * 100L);
        ItemStack c = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), coins);
        if (!p.getInventory().add(c)) p.drop(c, false);
        CoinCensus.flow(village, CoinCensus.Flow.PLAYER_OUT, coins);
        dirty();
        return "Your drawings: " + coins + " coins.";
    }

    /** A player who runs a business (or a stall) orders from the stores: out of the business's till, or a stall's out of the
     *  player's pack, the goods into the pack (the player carries them to the stall). */
    public static String playerOrder(ServerLevel level, UUID village, net.minecraft.server.level.ServerPlayer p, @Nullable Business b, ItemStack one, int qty) {
        if (one.isEmpty() || qty <= 0) return "Order what? Say the item and how many.";
        if (!Citizens.is(village, p.getUUID()) && !Standing.of(village, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) {
            return "The stores sell by the order to the town's citizens and friends.";
        }
        if (b != null && !b.mirror()) {
            if (!b.ownedBy(p.getUUID()) && !p.isCreative()) return "That's not your business to order for.";
            OrderResult r = order(level, village, b, one, qty, false);
            return r.words();
        }
        // A stall: paid out of the pack, carried away by the player.
        long day = today(level);
        String id = "stall:" + p.getUUID();
        Business stall = of(village, id);
        if (stall == null) {
            stall = open(level, village, Type.STALL, id, null, day);
            stall.name = p.getGameProfile().getName() + "'s stall";
            stall.owners.add(new Stake(Holder.PLAYER, p.getUUID(), p.getGameProfile().getName(), Business.WHOLE));
        }
        String key = Stockroom.key(one);
        int spare = Budget.spare(level, village, one.copyWithCount(1));
        long n = Math.min(qty, spare);
        if (n <= 0) return "The stores can't spare any " + nameOf(key).toLowerCase(Locale.ROOT) + ".";
        double each = storesEach(level, village, one);
        long[] cost = orderCost(each, n);
        int coins = (int) ((cost[0] + 99) / 100);
        if (Market.coinsHeld(p) < coins) return n + " " + nameOf(key).toLowerCase(Locale.ROOT) + " come to " + pence(cost[0]) + " (" + coins
            + " coins). You have " + Market.coinsHeld(p) + ".";
        Villages.Village v = Villages.get(village);
        boolean had;
        try (Accounts.Scope paid = Accounts.free("a player's order, paid below")) {   // [econ-1a] paid for here, not again at the stores
            had = v != null && TownWork.take(level, v, Stockroom.matcher(key), (int) n);
        }
        if (!had) return "The stores couldn't find that many after all.";
        Market.payOut(p, coins);
        CoinCensus.flow(village, CoinCensus.Flow.PLAYER_IN, coins);
        MarketMaker.inOrTreasury(village, cost[0]);                        // [econ-1a] the market's sale, once it is open
        long change = coins * 100L - cost[0];
        if (change > 0) stall.ownersDue.merge(p.getUUID(), change, Long::sum);  // the odd pennies kept for it
        bought(level, village, stall, key, one, n, cost[0], "the town stores", cost[2], cost[1], day);
        storesSold(level, village, key, one, n, cost[0], stall.name);
        long left = n;
        while (left > 0) {
            int k = (int) Math.min(left, one.getMaxStackSize());
            ItemStack got = one.copyWithCount(k);
            if (!p.getInventory().add(got)) p.drop(got, false);
            left -= k;
        }
        return "Bought " + n + " " + nameOf(key).toLowerCase(Locale.ROOT) + " at " + coins(each) + (cost[1] > 0 ? ", " + discount(n) + "% off" : "")
            + ": " + pence(cost[0]) + ". Carry them to your stall.";
    }

    // ------------------------------------------------------------------ tests

    private static final Map<String, Double> PRICE_FOR_TESTS = new ConcurrentHashMap<>();
    /** Tests: every one the stores hold may be ordered (the town's own needs not reckoned). */
    static boolean SPARE_ALL_FOR_TESTS;

    public static void spareAllForTests(boolean all) {
        SPARE_ALL_FOR_TESTS = all;
    }

    /** Tests: the stores' unit price of a ware fixed (null: as it goes). */
    public static void storesPriceForTests(ItemStack one, @Nullable Double each) {
        if (each == null) PRICE_FOR_TESTS.remove(Stockroom.key(one));
        else PRICE_FOR_TESTS.put(Stockroom.key(one), each);
    }

    public static void resetForTests() {
        PRICE_FOR_TESTS.clear();
        PRICE_MEMO.clear();
        SPARE_ALL_FOR_TESTS = false;
        store().towns.clear();
        lastCents = -1;
        dirty();
        CoinCensus.resetForTests();
    }

    /** Tests: a business opened now, its owners set by the town's own rules. */
    public static Business openForTests(ServerLevel level, UUID village, Type type, BlockPos at) {
        Business b = open(level, village, type, type.id(), at, today(level));
        return b;
    }

    /** Tests: the making of a batch booked for a business. */
    public static long madeForTests(ServerLevel level, UUID village, Business b, Map<String, Long> used, Map<String, Long> made, Map<String, ItemStack> samples) {
        return made(level, village, b, used, made, samples);
    }

    /** Tests: a business's morning, now (its town's closed day forgotten). */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        town(v.id()).closed = -1;
        morning(level, v, today(level));
    }

    /** Tests: a business wound up now. */
    public static void windUpForTests(ServerLevel level, UUID village, Business b) {
        windUp(level, village, b, today(level), "by test");
    }

    /** Tests: so many pennies out of the treasury into a business's till (its owners' capital). */
    public static void fundForTests(UUID village, Business b, long p) {
        long got = fromTreasury(village, p);
        b.till += got;
        b.allTime.capital += got;
        dirty();
    }

    /** [econ-1a] Tests: so many pennies out of the treasury, to nowhere (an empty treasury). */
    public static long fromTreasuryForTests(UUID village, long p) {
        return fromTreasury(village, p);
    }

    /** Tests: a business's day's rent, pennies. */
    public static long rentForTests(UUID village, Business b) {
        return rent(village, b);
    }

    /** Tests: the business's equity, pennies: till + what the stores owe it - what it owes + stock at cost - loan. */
    public static long equityForTests(Business b) {
        return b.till + b.account + b.stockCost() - b.loan;
    }

    /** Tests: review a business now. */
    public static void reviewForTests(ServerLevel level, UUID village, Business b) {
        review(level, village, b, today(level));
    }
}
