package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * What a folk wants for itself, and how it gets it: free from the stores until the town has a shop, bought at the
 * town's price from then on.
 *
 * <p>[econ-prices] <b>Before the shop.</b> A young town keeps its folk out of one larder: a meal, its rations, the
 * tool of its trade and a bed for a child come out of the stores, as they always have.
 *
 * <p><b>From the day the shop opens</b> (ShopStock.open), every want of a folk's own is bought, at the town's price
 * (PriceIndex), out of its own purse, the coin going into the treasury that pays the wages:
 * <ul>
 * <li>its meals and its food: a meal from the stores (Meals), the rations it carries to its work, a packed lunch,
 *     food sent out to it by courier;</li>
 * <li>the tool of its trade, and a spare off the rack (the watch's blades are issued: they stay the town's);</li>
 * <li>a treat, a drink, something for its home, its own clothes;</li>
 * <li>a bed and the furnishing for a house it OWNS. A house it rents is furnished by its landlord, the town.</li>
 * </ul>
 * What its work uses stays the town's: the field's seed, the mine's torches, the builders' blocks, the watch's
 * arrows and kit.
 *
 * <p><b>Buyers who mind the price.</b> A folk weighs the price against what it expects to pay: the usual worth,
 * more if it is well off and less if it is poor, less again if it is thrifty (or a Merchant at heart), more if it
 * is generous (or a Free Spirit), and pulled toward what it paid for the same last time. Food and the tool of its
 * trade it buys whatever the price, though it buys the cheaper food when its favourite is dear. A treat or a luxury
 * over what it thinks fair it leaves on the shelf, and the town's prices hear of it (a refusal brings a price down).
 * Cheaper than it expected, it buys more: a day or two's food put by, a second treat, a luxury sooner.
 *
 * <p><b>Paying.</b> Prices are reckoned to the hundredth of a coin and coin is whole: a folk hands over a coin and
 * the change is kept on its account at the counter, against the next thing it buys. One that cannot pay for its
 * food or its tool has it on the slate, paid back first out of its next wages, and is never left hungry or without
 * its tool; a child's is its family's to pay; one with no wage and no coin is fed out of the poor box. A slate past
 * two weeks of a field hand's wage is let go (the poor box again).
 *
 * <p><b>The living wage.</b> Two meals a day and the cheapest rent in the town, at today's prices, must fit in the
 * lowest wage. Each morning the town reckons it (costOfLiving); short, it is in the books and the elder tells the
 * morning assembly.
 */
public final class Purchases {

    private Purchases() {}

    /** What a thing is wanted for: its own (food, the tool of its trade, a treat, its home) or its work (the town's). */
    public enum Need {
        FOOD("food", true),
        TOOL("the tool of its trade", true),
        CLOTHES("clothes", false),
        TREAT("a treat", false),
        LUXURY("something for its home", false),
        HOME("furnishing for its own home", false),
        WORK("the town's work", false);

        public final String words;
        /** Bought whatever the price, on the slate if need be. */
        public final boolean essential;

        Need(String words, boolean essential) {
            this.words = words;
            this.essential = essential;
        }
    }

    // ------------------------------------------------------------------ the shop open, or not

    private static final Map<UUID, Boolean> OPEN_FOR_TESTS = new ConcurrentHashMap<>();

    /** Is the town's shop open (ShopStock.open): from then on, folk buy their own. */
    public static boolean open(UUID village) {
        Boolean forced = OPEN_FOR_TESTS.get(village);
        return forced != null ? forced : ShopStock.open(village);
    }

    /** Tests: the shop taken as open (true), shut (false), or as it stands (null). */
    public static void openForTests(UUID village, @Nullable Boolean open) {
        if (open == null) OPEN_FOR_TESTS.remove(village);
        else OPEN_FOR_TESTS.put(village, open);
    }

    /** Does this folk pay for this, here and now? Not before the shop opens, nor for the town's work, nor the watch's kit. */
    static boolean pays(UUID village, VillageFolkEntity f, Need need) {
        if (village == null || need == Need.WORK || !open(village)) return false;
        return !(need == Need.TOOL && f.stationTask() == StationTask.GUARD);       // the watch's blades are issued
    }

    // ------------------------------------------------------------------ accounts

    /** A folk's account at the town's counters, in hundredths of a coin: over nought it owes (the slate), under it the
     *  change it is owed. And what it last bought and refused, for its card. */
    static final class Account {
        long balance;
        long spentToday, spentDay = -1;
        String lastBought = "";
        String lastRefused = "";
        long refusedDay = -100;
    }

    /** By the village, by the folk. Only the balance is kept with the town (its ledger); the rest is for the card. */
    private static final Map<UUID, Map<UUID, Account>> ACCOUNTS = new ConcurrentHashMap<>();
    /** What each folk paid for each thing last time, each (by the folk, by the thing's name in the prices). */
    private static final Map<UUID, Map<String, Double>> LAST = new ConcurrentHashMap<>();
    /** When each folk last remarked on a price (tick count), so it says so now and then, not at every loaf. */
    private static final Map<UUID, Integer> SAID = new ConcurrentHashMap<>();
    /** What the poor box gave each town since the morning, in hundredths; and the slates let go. */
    private static final Map<UUID, long[]> POOR_BOX = new ConcurrentHashMap<>();
    /** The cost of living against the lowest wage, as reckoned this morning: the alarm's words, or none. */
    private static final Map<UUID, String> ALARM = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ACCOUNTS.clear();
        LAST.clear();
        SAID.clear();
        POOR_BOX.clear();
        ALARM.clear();
        OPEN_FOR_TESTS.clear();
    }

    private static Map<UUID, Account> accounts(UUID village) {
        return ACCOUNTS.computeIfAbsent(village, Purchases::load);
    }

    static Account account(UUID village, VillageFolkEntity f) {
        return accounts(village).computeIfAbsent(f.getUUID(), k -> new Account());
    }

    /** What this folk owes on the slate, in whole coins (rounded up): nought if nothing. */
    public static int slate(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return 0;
        Account a = accounts(village).get(f.getUUID());
        return a == null || a.balance <= 0 ? 0 : (int) ((a.balance + 99) / 100);
    }

    /** Tests: this folk's account, in hundredths (over nought: owed; under: change in hand). */
    public static long balanceForTests(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Account a = village == null ? null : accounts(village).get(f.getUUID());
        return a == null ? 0 : a.balance;
    }

    /** What this folk has been charged today at the town's counters, in hundredths of a coin. */
    public static long spentToday(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Account a = village == null ? null : accounts(village).get(f.getUUID());
        return a == null || a.spentDay != f.level().getDayTime() / 24000L ? 0 : a.spentToday;
    }

    /** Tests: as spentToday. */
    public static long spentTodayForTests(VillageFolkEntity f) {
        return spentToday(f);
    }

    /** The town's slates: {folk who owe, coins owed}. */
    public static int[] slates(UUID village) {
        int folk = 0;
        long owed = 0;
        for (Account a : accounts(village).values()) {
            if (a.balance <= 0) continue;
            folk++;
            owed += (a.balance + 99) / 100;
        }
        return new int[]{ folk, (int) Math.min(Integer.MAX_VALUE, owed) };
    }

    /** Can this folk pay so much (in coin, to the hundredth) out of its purse and its change, without the slate? */
    static boolean canPay(VillageFolkEntity f, double coins) {
        UUID village = f.ownerId();
        if (village == null) return false;
        long cents = Math.round(coins * 100);
        return account(village, f).balance + cents <= f.purse() * 100L;
    }

    /**
     * So much (in coin, to the hundredth) charged to this folk for {@code what}: whole coins over the counter out of its
     * purse into the treasury, the change kept on its account; what it cannot pay on the slate if {@code slate}, else
     * nothing charged at all (false). Booked: the coin spent in town, the price paid (remembered for next time), the
     * card. Returns the coins handed over now, or -1 if it could not pay.
     */
    static int charge(ServerLevel level, VillageFolkEntity f, UUID village, double coins, boolean slate, ItemStack what, int n) {
        long cents = Math.max(0, Math.round(coins * 100));
        Account a = account(village, f);
        long owed = a.balance + cents;
        if (!slate && owed > f.purse() * 100L) return -1;
        int due = owed <= 0 ? 0 : (int) ((owed + 99) / 100);
        int pay = Math.min(due, f.purse());
        if (pay > 0 && f.spend(pay)) {
            Ledger.addCoins(village, pay);
            Economy.spentInTown(village, pay);
        } else {
            pay = 0;
        }
        a.balance = owed - pay * 100L;
        long day = level.getDayTime() / 24000L;
        if (a.spentDay != day) { a.spentDay = day; a.spentToday = 0; }
        a.spentToday += cents;
        if (!what.isEmpty() && n > 0) {
            a.lastBought = (n > 1 ? n + " " : "") + lower(what.getHoverName().getString()) + " at "
                + String.format(Locale.ROOT, "%.2fc", coins / n);
            LAST.computeIfAbsent(f.getUUID(), k -> new ConcurrentHashMap<>()).put(PriceIndex.keyOf(what), coins / n);
        }
        save(village);
        return pay;
    }

    /** Payday (Market.payWages): the slate paid back first, out of the wage it has just been paid. */
    public static void payday(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return;
        Account a = accounts(village).get(f.getUUID());
        if (a == null || a.balance <= 0) return;
        int due = (int) ((a.balance + 99) / 100);
        int pay = Math.min(due, f.purse());
        if (pay <= 0 || !f.spend(pay)) return;
        a.balance -= pay * 100L;
        Ledger.addCoins(village, pay);
        Economy.spentInTown(village, pay);
        save(village);
        if (a.balance <= 0 && level.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Slate's clean again. Feels good, that.",
                "Paid off what I owed at the counter. A fresh start.", "Square with the shop at last."));
        }
    }

    // ------------------------------------------------------------------ what it expects, and what it will pay

    /** What this folk expects to see asked for one of these: its usual worth, pulled toward what it paid for the same
     *  last time (a price that has crept up for a month is the price it is used to). */
    public static double expects(VillageFolkEntity f, ItemStack one) {
        double e = PriceIndex.usual(one);
        Map<String, Double> last = LAST.get(f.getUUID());
        Double paid = last == null ? null : last.get(PriceIndex.keyOf(one));
        if (paid != null && paid > 0) e = 0.6 * e + 0.4 * paid;
        return Math.max(0.01, e);
    }

    /** The most this folk will pay for one of a thing it can do without: what it expects, by its means (the poor less,
     *  the wealthy more), its nature, and how far over it a treat (a quarter) or a luxury (a seventh) may go. */
    public static double willing(VillageFolkEntity f, ItemStack one, Need need) {
        double means = switch (Wealth.tier(f)) {
            case POOR -> 0.85;
            case GETTING_BY -> 0.95;
            case COMFORTABLE -> 1.05;
            case WELL_OFF -> 1.2;
            case WEALTHY -> 1.4;
        };
        return expects(f, one) * means * nature(f) * (need == Need.TREAT ? 1.25 : 1.15);
    }

    /** How free with its coin its nature makes it: thrifty and Merchants less, the generous and the Free Spirits more. */
    static double nature(VillageFolkEntity f) {
        double n = 1.0;
        if (f.knacks().has(FolkSkills.Knack.THRIFTY)) n -= 0.1;
        if (f.life().has(Social.Trait.GENEROUS)) n += 0.1;
        Values.Value top = f.persona().rolled() ? Values.top(f) : null;
        if (top == Values.Value.WEALTH) n -= 0.05;                            // a Merchant minds its coin
        if (top == Values.Value.LEISURE) n += 0.05;                            // a Free Spirit spends it
        return n;
    }

    /** What one of these costs this folk here today: the town's price (PriceIndex), dearer for an enchanted thing and a
     *  master's work as the counters ask it (Market.price), a tenth off on market day, a slow ware marked down and never
     *  under what it cost (Stockroom), and a tenth off for a Thrifty buyer (its knack). */
    public static double priceEach(ServerLevel level, UUID village, ItemStack one, @Nullable VillageFolkEntity buyer) {
        if (one.isEmpty()) return 0;
        ItemStack s = one.copyWithCount(1);
        double p = PriceIndex.each(level, village, s);
        if (Market.goodFor(s) != null) {
            if (s.isEnchanted()) p *= 3;
            p *= Craftsmanship.worth(s);
        }
        if (Market.marketDay(village, level.getDayTime() / 24000L)) p *= 0.9;
        p = asked(level, village, s, p);
        if (buyer != null && buyer.knacks().has(FolkSkills.Knack.THRIFTY)) p *= 0.9;
        return Math.max(0.01, p);
    }

    /** Stockroom.asked, to the hundredth: a slow ware a seller makes marked down, never under what it cost. */
    static double asked(ServerLevel level, UUID village, ItemStack one, double each) {
        Stockroom.Found found = Stockroom.find(one);
        if (found == null || !found.ware().made()) return each;
        int off = Stockroom.markdown(level, village, found.maker(), found.ware());
        double p = each * (100 - off) / 100.0;
        double cost = Stockroom.costEach(level, village, found.maker(), found.ware());
        return Math.max(cost > 0 ? cost : 0.01, p);
    }

    /**
     * How many of these this folk buys at this price, having come for {@code n}: food and its tool whatever the price,
     * and more food when it is a fifth or more under what it expects (a day or two's put by, as its purse runs to); a
     * treat or a luxury not at all when it is over what it will pay (the refusal booked, so the price comes down), one
     * more treat when it is a quarter under.
     */
    public static int decide(ServerLevel level, VillageFolkEntity f, ItemStack one, double each, Need need, int n) {
        UUID village = f.ownerId();
        if (village == null || n <= 0) return 0;
        double ratio = each / expects(f, one);
        int k = n;
        switch (need) {
            case FOOD -> {
                // Cheap: a day or two's meals put by, as far as its purse runs to without the slate.
                if (ratio <= 0.8) {
                    int extra = Math.min(4, Math.max(2, n / 2));
                    while (extra > 0 && !canPay(f, each * (n + extra))) extra--;
                    k = n + extra;
                }
            }
            case TOOL, WORK -> { }
            default -> {
                if (each > willing(f, one, need)) {
                    refuse(level, f, one, each, n);
                    return 0;
                }
                if (need == Need.TREAT && ratio <= 0.75 && canPay(f, each * (n + 1))) k = n + 1;
            }
        }
        if (k > n) {
            PriceIndex.bargain(village, one, k - n);
            remark(level, f, FolkTalk.pick(f.getRandom(), cap(lower(name(one))) + " at that price? I'll have " + words(k) + ".",
                "Cheap today — I'll take a few while it lasts.", "A bargain! " + cap(name(one)) + " for next to nothing."));
        } else if (need.essential && ratio >= 1.5) {
            remark(level, f, FolkTalk.pick(f.getRandom(), cap(name(one)) + "'s dear this week. Still, a body has to eat.",
                "Robbery, the price of " + lower(name(one)) + ". But what can you do?", "Dearer than ever. I'll pay it — I've no choice."));
        }
        return k;
    }

    /** Left on the shelf as too dear: the town's prices hear of it, and the folk says so now and then. */
    static void refuse(ServerLevel level, VillageFolkEntity f, ItemStack one, double each, int n) {
        UUID village = f.ownerId();
        if (village == null) return;
        PriceIndex.refused(village, one, n);
        Account a = account(village, f);
        a.lastRefused = lower(name(one)) + " at " + String.format(Locale.ROOT, "%.2fc", each);
        a.refusedDay = level.getDayTime() / 24000L;
        remark(level, f, FolkTalk.pick(f.getRandom(), String.format(Locale.ROOT, "%.1f for %s? Not at that price.", each, lower(name(one))),
            "Too dear by half. I'll wait till it comes down.", "I'd like " + lower(name(one)) + ", but not at that price.",
            "They want how much? I'll keep my coin, thank you."));
    }

    private static void remark(ServerLevel level, VillageFolkEntity f, String said) {
        Integer last = SAID.get(f.getUUID());
        if (last != null && f.tickCount - last < 2400 && f.tickCount >= last) return;
        if (level.getRandom().nextInt(3) != 0) return;
        SAID.put(f.getUUID(), f.tickCount);
        FolkTalk.speak(f, said);
    }

    // ------------------------------------------------------------------ getting what it wants

    /** What the last get() came away with (Toolrack's caller wants the tool itself). */
    private static ItemStack lastGot = ItemStack.EMPTY;

    /**
     * Up to {@code n} of what matches, for this folk, into its pack: the one way a folk gets what it wants for itself.
     * Free out of the stores before the shop opens (and always for the town's work); from then on bought at the town's
     * price, out of the shop's stock (the stores', for its food and its tool, when the shop has none), the food and the
     * tool chosen and paid for as the class comment has it. Returns how many it came away with (more than {@code n}
     * when it bought extra for cheapness), or nought.
     */
    public static int get(ServerLevel level, VillageFolkEntity f, Predicate<ItemStack> what, int n, Need need) {
        lastGot = ItemStack.EMPTY;
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || n <= 0) return 0;
        if (!pays(village, f, need)) return drawFree(level, v, f, what, n, need);
        // Who pays: itself, or for a child its family.
        VillageFolkEntity payer = payerFor(f);
        if (payer == null && !need.essential) return 0;                      // the poor box feeds, it does not treat
        List<ItemStack> offer = onOffer(level, village, what);
        if (offer.isEmpty()) return 0;
        ItemStack pick = choose(level, village, payer == null ? f : payer, f, offer, need);
        double each = priceEach(level, village, pick, payer);
        int k = payer == null ? n : decide(level, payer, pick, each, need, n);
        if (k <= 0) return 0;
        boolean earner = payer != null && !payer.isBaby() && payer.stationTask() != StationTask.NONE;
        if (payer != null && !need.essential && !canPay(payer, each * k)) {
            refuse(level, payer, pick, each, k);                             // more than it would (or could) pay
            return 0;
        }
        Predicate<ItemStack> exact = s -> ItemStack.isSameItemSameComponents(s, pick);
        int got = take(level, v, exact, k, need);
        if (got <= 0) {
            Stockroom.missed(level, village, Stockroom.Seller.SHOP, pick);
            return 0;
        }
        ItemStack left = f.insertGiven(pick.copyWithCount(got));
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);                                   // no room in its pack: back it goes
            got -= left.getCount();
            if (got <= 0) return 0;
        }
        int coins = 0;
        if (payer == null) {
            poorBox(village, each * got);                                    // no wage and no coin: the poor box feeds it
        } else {
            coins = charge(level, payer, village, each * got, need.essential && earner, pick, got);
            if (coins < 0) {
                // A grown folk with no trade and no coin: its food out of the poor box, not the slate it could never pay.
                poorBox(village, each * got);
                coins = 0;
            } else if (need.essential && slate(payer) > 0) {
                remark(level, payer, FolkTalk.pick(f.getRandom(), "Put it on the slate, would you? I'll settle on payday.",
                    "On the slate, please — I'm a bit short till the wages.", "I'll pay you back out of my wages, I promise."));
            }
        }
        Stockroom.sold(level, village, Stockroom.Seller.SHOP, pick, got, coins);
        PriceIndex.bought(village, pick, got);
        lastGot = pick.copyWithCount(got);
        return got;
    }

    /** The tool of this folk's trade off the rack (Toolrack.issue): the town's, before the shop opens and for the
     *  watch; bought (on the slate if need be) after. What it came away with, or empty. */
    public static ItemStack tool(ServerLevel level, Villages.Village v, VillageFolkEntity f, Toolrack.Tool tool) {
        if (!pays(v.id(), f, Need.TOOL)) return Toolrack.issue(level, v, f, tool);
        if (f.isPackFull()) return ItemStack.EMPTY;
        int got = get(level, f, s -> Toolrack.is(tool, s) && !Toolrack.worn(s) && f.mayUseTier(s), 1, Need.TOOL);
        return got > 0 ? lastGot.copy() : ItemStack.EMPTY;
    }

    /** Who pays for what this folk wants: itself; for a child, the grown folk of its household with the most coin (none:
     *  the poor box); for a grown folk with no coin and no wage, the poor box. */
    @Nullable
    static VillageFolkEntity payerFor(VillageFolkEntity f) {
        if (!f.isBaby()) return f.purse() > 0 || f.stationTask() != StationTask.NONE || accountHasChange(f) ? f : null;
        UUID village = f.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, f.getUUID());
        if (h == null) return null;
        VillageFolkEntity best = null;
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
            if (m == f || m.isBaby()) continue;
            if (best == null || m.purse() > best.purse()) best = m;
        }
        return best;
    }

    private static boolean accountHasChange(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Account a = village == null ? null : accounts(village).get(f.getUUID());
        return a != null && a.balance < 0;
    }

    private static void poorBox(UUID village, double coins) {
        POOR_BOX.computeIfAbsent(village, k -> new long[2])[0] += Math.round(coins * 100);
    }

    /** The things on offer that match, one of each (at most a dozen): out of the stores, which the shop sells from. */
    static List<ItemStack> onOffer(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        List<ItemStack> out = new ArrayList<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                boolean seen = false;
                for (ItemStack o : out) if (ItemStack.isSameItemSameComponents(o, s)) { seen = true; break; }
                if (!seen) out.add(s.copyWithCount(1));
                if (out.size() >= 12) return out;
            }
        }
        return out;
    }

    /**
     * Which of what is on offer it buys. Food: the best meal for the money by its means (the poor mind the price most,
     * the wealthy hardly at all), its favourite a little better. Its tool: the best metal it may use and can pay for, else
     * the cheapest it may use. Anything else: the best bargain against what it expects.
     */
    static ItemStack choose(ServerLevel level, UUID village, VillageFolkEntity buyer, VillageFolkEntity f, List<ItemStack> offer, Need need) {
        if (offer.size() == 1) return offer.get(0);
        switch (need) {
            case FOOD -> {
                double care = switch (Wealth.tier(buyer)) {
                    case POOR -> 1.0;
                    case GETTING_BY -> 0.8;
                    case COMFORTABLE -> 0.6;
                    case WELL_OFF -> 0.4;
                    case WEALTHY -> 0.25;
                } + (1.0 - nature(buyer));
                Item fav = f.persona().rolled() ? net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    ResourceLocation.withDefaultNamespace(f.persona().food())) : Items.AIR;
                ItemStack best = offer.get(0);
                double bestScore = -1;
                for (ItemStack s : offer) {
                    double q = AssistantEntity.foodQuality(s) * (s.is(fav) ? 1.2 : 1.0);
                    double p = priceEach(level, village, s, buyer);
                    double score = q / Math.pow(Math.max(0.05, p), Math.max(0.1, care));
                    if (score > bestScore) { bestScore = score; best = s; }
                }
                return best;
            }
            case TOOL -> {
                List<ItemStack> sorted = new ArrayList<>(offer);
                sorted.sort(Comparator.comparingInt((ItemStack s) -> -(Toolrack.metal(s) * 200 + Toolrack.left(s))));
                for (ItemStack s : sorted) if (canPay(buyer, priceEach(level, village, s, buyer))) return s;
                sorted.sort(Comparator.comparingDouble(s -> priceEach(level, village, s, buyer)));
                return sorted.get(0);
            }
            default -> {
                ItemStack best = offer.get(0);
                double bestRatio = Double.MAX_VALUE;
                for (ItemStack s : offer) {
                    double r = priceEach(level, village, s, buyer) / expects(buyer, s);
                    if (r < bestRatio) { bestRatio = r; best = s; }
                }
                return best;
            }
        }
    }

    /** So many of exactly this out of the shop's stock; for its food and its tool, out of the stores if the shop has none. */
    private static int take(ServerLevel level, Villages.Village v, Predicate<ItemStack> exact, int k, Need need) {
        int inShop = ShopStock.count(level, v.id(), exact);
        int n = Math.min(k, inShop);
        // All or nothing at the shop: as many as it has.
        if (n > 0 && ShopStock.take(level, v, exact, n)) return n;
        if (!need.essential) return 0;
        int inStores = Market.stock(level, v.id(), exact);
        n = Math.min(k, inStores);
        return n > 0 && TownWork.take(level, v, exact, n) ? n : 0;
    }

    /** Free out of the stores into the pack (the town's own, before the shop; the town's work always), as a hand at the
     *  counter is handed it: booked out in the storehouse's books, and in the town's prices as wanted. */
    private static int drawFree(ServerLevel level, Villages.Village v, VillageFolkEntity f, Predicate<ItemStack> what, int n, Need need) {
        int moved = 0;
        boolean full = false;
        List<ItemStack> fromStore = new ArrayList<>();
        ItemStack first = ItemStack.EMPTY;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (moved >= n || full) break;
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            boolean store = c instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity;
            for (int i = 0; i < c.getContainerSize() && moved < n; i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty() || !what.test(st)) continue;
                int take = Math.min(st.getCount(), n - moved);
                ItemStack left = f.insertGiven(st.copyWithCount(take));
                int taken = take - left.getCount();
                if (taken <= 0) { full = true; break; }
                if (store) fromStore.add(st.copyWithCount(taken));
                if (first.isEmpty()) first = st.copyWithCount(1);
                if (need != Need.WORK) PriceIndex.drawn(v.id(), st, taken);
                st.shrink(taken);
                if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                moved += taken;
            }
            c.setChanged();
        }
        if (!fromStore.isEmpty()) Storekeeping.handedOut(f, fromStore);
        lastGot = first.isEmpty() ? ItemStack.EMPTY : first.copyWithCount(moved);
        return moved;
    }

    // ------------------------------------------------------------------ other ways in

    /** Is this withdrawal (WithdrawGoal: a folk come to the stores for {@code word}) its own food, to be bought now the
     *  town has a shop? The town's stores only: its own chest and its household's are its own. */
    public static boolean buysFood(VillageFolkEntity f, String word, @Nullable BlockPos chest) {
        UUID village = f.ownerId();
        if (village == null || chest == null || !(f.level() instanceof ServerLevel level)) return false;
        if (!word.equals("ration") && !word.equals("meal") && !word.equals("food")) return false;
        return pays(village, f, Need.FOOD) && (Villages.storeChests(level, village).contains(chest) || Villages.inStoreArea(village, chest));
    }

    /**
     * A courier has handed this folk {@code given} of what it was sent out with (Couriers): its food, or the tool of
     * its trade, is paid for on delivery (on the slate if need be); seed, torches and the like are the town's work.
     */
    public static void delivered(ServerLevel level, VillageFolkEntity to, @Nullable String ask, Predicate<ItemStack> what, int given) {
        UUID village = to.ownerId();
        if (village == null || ask == null || given <= 0) return;
        Need need = ask.equals("ration") || ask.equals("meal") ? Need.FOOD : isToolWord(ask) ? Need.TOOL : Need.WORK;
        if (!pays(village, to, need)) return;
        ItemStack sample = ItemStack.EMPTY;
        for (ItemStack s : to.getInventoryItems()) if (!s.isEmpty() && what.test(s)) { sample = s.copyWithCount(1); break; }
        if (sample.isEmpty()) return;
        VillageFolkEntity payer = payerFor(to);
        double each = priceEach(level, village, sample, payer);
        if (payer == null) {
            poorBox(village, each * given);
            return;
        }
        boolean earner = !payer.isBaby() && payer.stationTask() != StationTask.NONE;
        int coins = charge(level, payer, village, each * given, earner, sample, given);
        if (coins < 0) { poorBox(village, each * given); coins = 0; }
        Stockroom.sold(level, village, Stockroom.Seller.STORES, sample, given, coins);
        PriceIndex.bought(village, sample, given);
    }

    private static boolean isToolWord(String ask) {
        for (Toolrack.Tool t : Toolrack.Tool.values()) if (t.word.equals(ask)) return true;
        return false;
    }

    /**
     * A thing for a home (a child's bed, the furnishing Decor sets out): who pays and how much. Nought when the town does
     * (before the shop opens; a house it lets, as its landlord); for a house its household owns, the whole coins of the
     * town's price.
     */
    public static int homePrice(ServerLevel level, UUID village, boolean owned, ItemStack what) {
        if (!owned || !open(village) || what.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(priceEach(level, village, what, null) - 1e-6));
    }

    /** A household paid {@code coins} for a thing for its own home (Homes, Decor): in the shop's books and the prices. */
    public static void homeBought(ServerLevel level, UUID village, ItemStack what, int coins) {
        if (what.isEmpty()) return;
        Economy.spentInTown(village, coins);
        Stockroom.sold(level, village, Stockroom.Seller.SHOP, what, 1, coins);
        PriceIndex.bought(village, what, 1);
    }

    /** One of these at the town's price, in whole coins (a coin at least): a gift bought (Families, Birthdays), a dish
     *  at the café's counter (TownBell). */
    public static int coinPrice(ServerLevel level, UUID village, ItemStack what, @Nullable VillageFolkEntity buyer) {
        return Math.max(1, (int) Math.round(priceEach(level, village, what, buyer)));
    }

    // ------------------------------------------------------------------ the living wage

    /** Two meals of the cheapest wholesome food the town has, and the cheapest rent in it, a day at today's prices. */
    public static double costOfLiving(ServerLevel level, UUID village) {
        return 2 * mealPrice(level, village) + cheapestRent(village);
    }

    /** A meal at today's price: the cheapest wholesome food on the board the stores have, else bread. */
    public static double mealPrice(ServerLevel level, UUID village) {
        double best = Double.MAX_VALUE;
        for (Market.Good g : Market.GOODS) {
            if (g.need() != Villages.Task.FOOD) continue;
            ItemStack one = PriceIndex.sampleOfGood(g);
            if (one.isEmpty() || one.get(DataComponents.FOOD) == null || AssistantEntity.foodQuality(one) < 60) continue;
            PriceIndex.Line l = PriceIndex.town(village).lines.get("g:" + g.name());
            if (l != null && l.stock <= 0) continue;
            best = Math.min(best, PriceIndex.each(level, village, g));
        }
        if (best == Double.MAX_VALUE) {
            Market.Good bread = Market.goodFor(new ItemStack(Items.BREAD));
            best = bread == null ? 0.3 : PriceIndex.each(level, village, bread);
        }
        return best;
    }

    /** The cheapest rent a household pays in the town (a house's, by its size and quarter), a day. */
    public static int cheapestRent(UUID village) {
        int best = Integer.MAX_VALUE;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (h.tenure == Homes.Tenure.GIVEN || h.tenure == Homes.Tenure.PLAYER) continue;
            best = Math.min(best, Homes.rent(village, h));
        }
        return best == Integer.MAX_VALUE ? Math.max(1, (Wealth.tradeWage(StationTask.FARM, village) + 1) / 2) : best;
    }

    /** The lowest wage in the town: the plainest trade's day's pay, at the rate the leader set (WAGES may change how). */
    public static int lowestWage(UUID village) {
        int low = Integer.MAX_VALUE;
        for (StationTask t : StationTask.values()) {
            int w = Wealth.tradeWage(t, village);
            if (w > 0) low = Math.min(low, w);
        }
        if (low == Integer.MAX_VALUE) low = 1;
        return Math.max(1, (int) Math.round(low * Leader.payRate(village) / 100.0));
    }

    /** How far the cost of living is over the lowest wage, a day (nought when it fits): for the leader and the wages. */
    public static double livingShort(ServerLevel level, UUID village) {
        return Math.max(0, costOfLiving(level, village) - lowestWage(village));
    }

    /** This morning's alarm on the cost of living, or null when it fits (or the shop is not open: nobody pays yet). */
    @Nullable
    public static String alarm(UUID village) {
        return ALARM.get(village);
    }

    /** "Cost of living: two meals (bread at 0.32c) and rent of 1c, 1.64c a day; the lowest wage is 2c: it fits." */
    public static String livingLine(ServerLevel level, UUID village) {
        double meal = mealPrice(level, village), living = costOfLiving(level, village);
        int rent = cheapestRent(village), wage = lowestWage(village);
        String fits = living <= wage ? "it fits" : "SHORT by " + String.format(Locale.ROOT, "%.2fc", living - wage);
        return "Cost of living: two meals at " + String.format(Locale.ROOT, "%.2fc", meal) + " and the cheapest rent, " + rent + "c — "
            + String.format(Locale.ROOT, "%.2fc", living) + " a day against the lowest wage of " + wage + "c: " + fits
            + (open(village) ? "." : " (the stores feed the folk free until the shop opens).");
    }

    /**
     * The morning (PriceIndex.morning, the prices just reckoned): the cost of living weighed against the lowest wage, and
     * a slate run past two weeks of that wage let go to the poor box.
     */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        double living = costOfLiving(level, id);
        int wage = lowestWage(id);
        if (open(id) && living > wage) {
            String words = String.format(Locale.ROOT, "two meals and the cheapest rent come to %.2fc a day, more than a field hand's %dc", living, wage);
            ALARM.put(id, words);
            String last = Ledger.note(id, "living.told");
            long told = -10;
            try { told = Long.parseLong(String.valueOf(last)); } catch (NumberFormatException ignored) { }
            if (day - told >= 3) {
                Ledger.note(id, "living.told", Long.toString(day));
                Villages.tell(id, day, "the cost of living is over the lowest wage: " + words);
                Market.assemblyNews(id, "Folk on the lowest wage can't keep themselves: " + words
                    + ". The pay or the prices have to change.");
            }
        } else {
            ALARM.remove(id);
        }
        // A slate past two weeks of the lowest wage: the rest let go.
        long cap = 14L * wage * 100;
        boolean changed = false;
        for (Account a : accounts(id).values()) {
            if (a.balance <= cap) continue;
            POOR_BOX.computeIfAbsent(id, k -> new long[2])[1] += a.balance - cap;
            a.balance = cap;
            changed = true;
        }
        if (changed) save(id);
        long[] box = POOR_BOX.remove(id);
        if (box != null && box[0] + box[1] >= 100) {
            Villages.tell(id, day, "the poor box fed those with no coin and no wage" + (box[1] > 0 ? ", and let go of slates too big to pay" : "")
                + String.format(Locale.ROOT, " (%.0f coins' worth)", (box[0] + box[1]) / 100.0));
        }
    }

    // ------------------------------------------------------------------ what a player reads

    /** The folk card's line: what it owes on the slate, the change it has at the counter, what it last bought and refused. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return null;
        if (!open(village)) return f.isBaby() ? null : "Fed and kitted out of the town's stores, free, until the shop opens.";
        Account a = accounts(village).get(f.getUUID());
        List<String> parts = new ArrayList<>();
        int owed = slate(f);
        if (owed > 0) parts.add("owes " + owed + (owed == 1 ? " coin" : " coins") + " on the slate, paid back first from its wages");
        else if (a != null && a.balance < 0) parts.add(String.format(Locale.ROOT, "%.2fc change on account at the counter", -a.balance / 100.0));
        if (a != null && !a.lastBought.isEmpty()) parts.add("last bought " + a.lastBought);
        if (a != null && !a.lastRefused.isEmpty() && f.level().getDayTime() / 24000L - a.refusedDay <= 2) {
            parts.add("left " + a.lastRefused + " on the shelf as too dear");
        }
        if (parts.isEmpty()) return f.isBaby() ? "Its family buys its food." : "Buys its own food and things at the town's prices.";
        return cap(String.join("; ", parts)) + ".";
    }

    /** "How are prices?" in its own words. */
    public static String talk(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Prices? I couldn't tell you. I'm not from round here.";
        List<String> moves = PriceIndex.moveLines(village);
        String news = moves.isEmpty() ? "Nothing much has moved this week." : cap(moves.get(0)) + ".";
        int owed = slate(f);
        String me = owed > 0 ? " I owe " + owed + " at the counter, mind — it comes out of my wages." : "";
        String living = alarm(village) != null ? " Between you and me, a field hand can't live on the wage, the way things are." : "";
        return (open(village) ? news : "We take what we need from the stores till the shop opens. " + news) + me + living;
    }

    // ------------------------------------------------------------------ kept with the town

    /** The slates (and the change on account) into the town's ledger: "uuid:balance,...". */
    private static void save(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Account> e : accounts(village).entrySet()) {
            if (e.getValue().balance == 0) continue;
            sb.append(sb.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue().balance);
        }
        Ledger.note(village, "purchases.slate", sb.toString());
    }

    private static Map<UUID, Account> load(UUID village) {
        Map<UUID, Account> out = new ConcurrentHashMap<>();
        String note = Ledger.note(village, "purchases.slate");
        if (note == null || note.isEmpty()) return out;
        for (String part : note.split(",")) {
            String[] f = part.split(":");
            if (f.length != 2) continue;
            try {
                Account a = new Account();
                a.balance = Long.parseLong(f[1]);
                out.put(UUID.fromString(f[0]), a);
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ words

    private static String name(ItemStack s) {
        return s.getHoverName().getString();
    }

    private static String words(int n) {
        return switch (n) {
            case 2 -> "two";
            case 3 -> "three";
            case 4 -> "four";
            default -> Integer.toString(n);
        };
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Tests: a folk's account at the counter made fresh. */
    public static void forgetForTests(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village != null) accounts(village).remove(f.getUUID());
        LAST.remove(f.getUUID());
    }

    /** Tests: what the poor box has given this town since the morning, in hundredths. */
    public static long poorBoxForTests(UUID village) {
        long[] b = POOR_BOX.get(village);
        return b == null ? 0 : b[0];
    }

    /** Tests: the thing the last get() came away with. */
    public static ItemStack lastForTests() {
        return lastGot.copy();
    }
}
