package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The shop's workshop: the shopkeeper and the hands it takes on, making at the shop's bench everything
 * the shop sells and the town wears out — tools and blades, armour and shields, bows and arrows, the
 * household's chests and lanterns — out of the village's stores, by every recipe the game knows.
 * <ul>
 * <li><b>Every blueprint.</b> The makers know every recipe the game has (RecipeBook: every crafting
 *     recipe, shaped or not, of any plank or any wool; every furnace firing; the smithing table's
 *     netherite), a modpack's with them, and work out the whole way from what the stores hold (Bench:
 *     logs to planks to sticks to the sword), taking out exactly what the recipe takes and putting back
 *     what it leaves (the rest of the planks, the sticks, an empty bucket). Never the builders' timber
 *     and stone, the smith's iron, the coal, nor anything the age is putting by.</li>
 * <li><b>The age's say.</b> Nothing the village's age has not come to (Tiers): wooden tools, tables and
 *     chests in the Wood Age; stone tools and swords, leather armour and whatever is fired in the Stone
 *     Age; iron tools, swords and armour, shields, buckets, rails, anvils and lanterns in the Iron Age;
 *     diamond in the Diamond Age, and netherite at the smithing table in the Nether Age.</li>
 * <li><b>The order book.</b> What the shop keeps made, and how many of each: the watch's blades,
 *     armour, shields, bows and arrows (a piece for every guard who wears worse than the age's best the
 *     stores can run to, and a spare for every four); the storehouse's rack of spare tools (Toolrack: the
 *     shop's hands keep it once there is a shop); the shop's own shelves (Cafe.shopWares); whatever folk
 *     and players buy and ask for most (Stockroom's books); what a player orders; and whatever another
 *     of the village's trades asks to have kept (demand). The emptiest first, one piece at a time.</li>
 * <li><b>Its hands.</b> The shopkeeper makes between customers; the shop takes on hands for its bench
 *     (a tag on the folk, kept with it in the world): one for every fifteen folk the town has, four at
 *     the most, while the order book has work in it. They are of the shop's trade and paid at its rate,
 *     out of the hands the village can spare (between trades, or idle in a trade with more hands than it
 *     wants), and work at the crafting table in the shop's back room.</li>
 * <li><b>Fire and anvil.</b> What wants firing is the smelter's: with a smelter at work the shop sends
 *     its firing to the smeltery (glass, an ingot, charcoal) and makes the rest when it comes back.</li>
 * <li><b>The watch fitted out.</b> A guard who wears worse than the shop's stock has is given the
 *     better piece (a blade, a helmet...), the village paying for it (no coin changes hands: the shop's
 *     takings are the treasury's), and its old piece goes back into the stores. Workers take their
 *     spares off the rack, and buy their tools at the counter; players buy at the counter.</li>
 * </ul>
 * The town's books (the Shops page's workshop) and /village workshop show the makers, what each made
 * today and of what, the order book against the stock, and what the next age will let them make.
 */
public final class Workshop {

    private Workshop() {}

    /** The tag on a hand of the shop's bench (kept with it in the world). */
    public static final String HAND = "mca_shop_hand";
    /** One hand for every so many folk in the town, and the most the shop takes on. */
    static final int FOLK_A_HAND = 15, MOST_HANDS = 4;
    /** How often the order book is worked out afresh, the staff looked over, a hand taken on, the watch fitted out (ticks). */
    static final long LOOK = 600L, STAFF = 200L, HIRE = 2400L, OUTFIT = 600L;
    /** How long a piece shows as being made, on the card. */
    static final long DOING = 700L;
    /** A spare piece of the watch's armour for every so many guards; arrows a guard, and the most kept. */
    static final int WATCH_SPARE = 4, ARROWS_A_GUARD = 16, ARROWS_MOST = 64;
    /** The most of a thing a player may have on order, and how long an order stands (days). */
    static final int MOST_ORDERED = 16, ORDER_DAYS = 3;
    /** A tool or a piece of armour this worn or less is no spare and no upgrade (percent of its wear left). */
    static final int FIT = 50;

    // ------------------------------------------------------------------ the books

    /** Why something is on the order book, and how many the town wants kept of it. */
    record Need(int count, String why) {}

    /** A piece of work done today: when, by whom (and was it a hand), what, out of what, and what for. */
    record Entry(String time, String who, String role, String what, String from, String why) {}

    /** A player's order: what, how many, for whom, and the day it was put in. */
    record Order(Item item, int count, String who, long day) {}

    /** What a maker is at just now, what for, and till when. */
    private record Doing(String what, String why, long until) {}

    /** A village's workshop: its order book, its hands, and its day. */
    static final class Shop {
        long looked = -100000L, staffed = -100000L, hired = -100000L, outfitted = -100000L;
        long day = Long.MIN_VALUE;
        int handsWanted, backlog;
        final Map<String, Need> need = new LinkedHashMap<>();
        List<Stockroom.Ware> wares = List.of();
        /** On the shop's list, and the age not come to it yet. */
        final List<String> waiting = new ArrayList<>();
        final List<Entry> log = new ArrayList<>();
        final Map<UUID, Integer> madeBy = new HashMap<>();
        int madeToday, madeYesterday, toTheWatch;
        /** What the shop has sent to the smeltery to be fired, and how many. */
        final Map<Item, Integer> firing = new LinkedHashMap<>();
        final List<Order> orders = new ArrayList<>();
    }

    private static final Map<UUID, Shop> SHOPS = new ConcurrentHashMap<>();
    /** The wares the workshop has had on any order book, by their name in the shop's books (Stockroom.find). */
    private static final Map<String, Stockroom.Ware> KNOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Doing> DOINGS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SHOPS.clear();
        DOINGS.clear();
    }

    static Shop shop(UUID village) {
        return SHOPS.computeIfAbsent(village, k -> new Shop());
    }

    /** The day turned over: today's tally is yesterday's. */
    private static void roll(ServerLevel level, Shop s) {
        long today = level.getDayTime() / 24000L;
        if (s.day == today) return;
        if (s.day != Long.MIN_VALUE) {
            s.madeYesterday = today - s.day == 1 ? s.madeToday : 0;
            s.madeToday = 0;
            s.toTheWatch = 0;
            s.log.clear();
            s.madeBy.clear();
            s.orders.removeIf(o -> today - o.day() > ORDER_DAYS);
        }
        s.day = today;
    }

    // ------------------------------------------------------------------ another trade's wants

    /**
     * Something another of the village's trades wants kept made at the shop's bench (a luxury the homes
     * ask for, a festival's lanterns): for each thing, how many to keep in the stores. The workshop makes
     * up to it, by the same rules as everything else on its book.
     */
    public interface Demand {
        void wants(ServerLevel level, Villages.Village v, java.util.function.ObjIntConsumer<Item> want);
    }

    private static final Map<String, Demand> DEMANDS = new ConcurrentHashMap<>();

    /** Put a trade's wants on every shop's order book, under this reason ("the homes' comforts"). */
    public static void demand(String why, Demand d) {
        DEMANDS.put(why, d);
    }

    // ------------------------------------------------------------------ who works there

    /** Is the shop built (or standing)? */
    public static boolean stands(UUID village) {
        return Villages.builtAt(village, "shop") != null || Villages.hasBuilt(village, "shop");
    }

    private static boolean shopFolk(AssistantEntity a) {
        return a instanceof VillageFolkEntity f && f.stationTask() == StationTask.SHOP && !f.isBaby() && f.isAlive();
    }

    /** The most experienced first: its years at the trade, then everything it has done. */
    private static int compareHands(VillageFolkEntity a, VillageFolkEntity b) {
        if (a.veteranLevel() != b.veteranLevel()) return Integer.compare(b.veteranLevel(), a.veteranLevel());
        if (a.lifetimeXp() != b.lifetimeXp()) return Integer.compare(b.lifetimeXp(), a.lifetimeXp());
        return a.getUUID().compareTo(b.getUUID());
    }

    /** Who keeps the shop: the one of its trade who is no hand (the most experienced, if two are), or, with
     *  only hands left, the most experienced of them (it takes the shop over at the next look). */
    @Nullable
    public static VillageFolkEntity keeper(@Nullable UUID village) {
        if (village == null) return null;
        VillageFolkEntity best = null, bestHand = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!shopFolk(a)) continue;
            VillageFolkEntity f = (VillageFolkEntity) a;
            if (f.getTags().contains(HAND)) {
                if (bestHand == null || compareHands(f, bestHand) < 0) bestHand = f;
            } else if (best == null || compareHands(f, best) < 0) {
                best = f;
            }
        }
        return best != null ? best : bestHand;
    }

    /** Is this folk a hand at the shop's bench (of the shop's trade, and not its keeper)? */
    public static boolean isHand(@Nullable VillageFolkEntity f) {
        if (f == null || f.stationTask() != StationTask.SHOP || !f.getTags().contains(HAND)) return false;
        return keeper(f.ownerId()) != f;
    }

    /** The shop's hands. */
    public static List<VillageFolkEntity> hands(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        VillageFolkEntity keeper = keeper(village);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (shopFolk(a) && a != keeper && a.getTags().contains(HAND)) out.add((VillageFolkEntity) a);
        }
        out.sort(Workshop::compareHands);
        return out;
    }

    /** How many hands the shop wants at its bench (worked out with the order book): the town's trades count
     *  the shop's share as its keeper and these (Villages.target). */
    public static int handsWanted(@Nullable UUID village) {
        Shop s = village == null ? null : SHOPS.get(village);
        return s == null ? 0 : s.handsWanted;
    }

    /** Is the shop open, with somebody at its bench? Then the storehouse's rack of spares is the shop's to keep. */
    public static boolean keepsTheRack(ServerLevel level, Villages.Village v) {
        return stands(v.id()) && keeper(v.id()) != null;
    }

    /**
     * The shop's staff looked over: one keeper, the rest its hands. A hand that left the trade is no hand;
     * with no keeper left, the most experienced hand takes the shop over; and of two keepers (a newcomer
     * the village gave the shop) the newer is the keeper's hand.
     */
    static void staff(ServerLevel level, UUID village) {
        List<VillageFolkEntity> at = new ArrayList<>(), keepers = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            if (!shopFolk(f)) {
                if (f.getTags().contains(HAND)) f.removeTag(HAND);
                continue;
            }
            at.add(f);
            if (!f.getTags().contains(HAND)) keepers.add(f);
        }
        if (at.isEmpty()) return;
        long day = level.getDayTime() / 24000L;
        if (keepers.isEmpty()) {
            at.sort(Workshop::compareHands);
            VillageFolkEntity best = at.get(0);
            best.removeTag(HAND);
            best.brain("keeps the shop now");
            Villages.tell(village, day, best.displayNameCap() + " took over the shop");
            FolkTalk.speak(best, FolkTalk.pick(best.getRandom(), "The shop's mine to keep now. I'll do right by it.",
                "Well — somebody has to keep the shop. It'll be me."));
            return;
        }
        if (keepers.size() > 1) {
            keepers.sort(Workshop::compareHands);
            VillageFolkEntity keeper = keepers.get(0);
            for (int i = 1; i < keepers.size(); i++) {
                VillageFolkEntity f = keepers.get(i);
                f.addTag(HAND);
                f.brain("a hand at the shop's bench, under " + keeper.displayNameCap());
            }
        }
    }

    /**
     * A hand taken on, if the shop wants more than it has: one the village can spare — between trades, or
     * standing idle in a trade with more hands than it wants (never the storehouse's staff, the watch, a
     * craft's one hand, a scout, a hired hand or one on the town's works). Its trade is the shop's now, its
     * ground the shop, its wage the shop's rate. Returns it, or null.
     */
    @Nullable
    static VillageFolkEntity hire(ServerLevel level, Villages.Village v, Shop s) {
        UUID id = v.id();
        BlockPos at = Villages.builtAt(id, "shop");
        if (at == null || keeper(id) == null || hands(id).size() >= s.handsWanted) return null;
        // Within the town's shape (Villages.share): a hand short of the shop's share, so that nobody is taken
        // on only to be sent off again to a trade shorter of hands. (A town too small for a shop by its shape
        // goes by the workshop's own reckoning.)
        if (Villages.wants(id, StationTask.SHOP) && Villages.share(id, StationTask.SHOP) > -1.0) return null;
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isHired() || f.isShowcase()) continue;
            if (TownJobs.busy(f) || f.isSleeping()) continue;
            StationTask t = f.stationTask();
            double score;
            if (t == StationTask.NONE) score = 100;
            else if (t == StationTask.HAUL || t == StationTask.STORE || t == StationTask.GUARD || t == StationTask.SCOUT || t.isCraft()) continue;
            else if (Villages.overStaffed(id, t) && f.workedOut()) score = 50;
            else continue;
            score -= Math.sqrt(f.blockPosition().distSqr(at)) / 16.0;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        if (best == null) return null;
        takeOn(level, v, best, at);
        return best;
    }

    /** This folk a hand at the shop's bench: of the shop's trade, its ground the shop, and said so. */
    static void takeOn(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos shopAt) {
        StationTask was = f.stationTask();
        f.addTag(HAND);
        f.setStation(shopAt, StationTask.SHOP);
        f.assignPlot(WorkZone.around(shopAt, 5, WorkZone.DEFAULT_DEPTH), "the shop");
        VillageFolkEntity keeper = keeper(v.id());
        f.brain("taken on as a hand at the shop's bench" + (keeper != null && keeper != f ? ", under " + keeper.displayNameCap() : ""));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The shop's taken me on — a hand at the bench. Swords and pots and all sorts!",
            "A hand at the shop's bench now. Better than standing about.", "I'm to make things for the shop. I'll learn every recipe there is."));
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " was taken on as a hand at the shop's bench"
            + (was == StationTask.NONE ? "" : ", from " + was.label));
    }

    // ------------------------------------------------------------------ the order book

    /** The kinds of things the order book keeps, best first: the age's best the stores can run to is made. */
    private static final List<Item> SWORDS = List.of(Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD);
    private static final Map<Toolrack.Tool, List<Item>> TOOLS = Map.of(
        Toolrack.Tool.PICKAXE, List.of(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE),
        Toolrack.Tool.AXE, List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE),
        Toolrack.Tool.HOE, List.of(Items.NETHERITE_HOE, Items.DIAMOND_HOE, Items.IRON_HOE, Items.STONE_HOE, Items.WOODEN_HOE),
        Toolrack.Tool.SWORD, SWORDS,
        Toolrack.Tool.ROD, List.of(Items.FISHING_ROD),
        Toolrack.Tool.SHEARS, List.of(Items.SHEARS));
    private static final EquipmentSlot[] ARMOUR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private static final Map<EquipmentSlot, List<Item>> SUITS = Map.of(
        EquipmentSlot.HEAD, List.of(Items.NETHERITE_HELMET, Items.DIAMOND_HELMET, Items.IRON_HELMET, Items.LEATHER_HELMET),
        EquipmentSlot.CHEST, List.of(Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE, Items.LEATHER_CHESTPLATE),
        EquipmentSlot.LEGS, List.of(Items.NETHERITE_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.IRON_LEGGINGS, Items.LEATHER_LEGGINGS),
        EquipmentSlot.FEET, List.of(Items.NETHERITE_BOOTS, Items.DIAMOND_BOOTS, Items.IRON_BOOTS, Items.LEATHER_BOOTS));

    /** The order book: what the shop keeps made, the town's needs first (refreshed every half a minute). */
    public static List<Stockroom.Ware> wares(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        // No shop and nobody keeping one: the shop's own shelves, as they always were.
        if (v == null || !stands(village) && keeper(village) == null) return Cafe.shopWares();
        Shop s = shop(village);
        long now = level.getGameTime();
        if (now - s.looked >= LOOK || now < s.looked) look(level, v, s);
        return s.wares;
    }

    /** How many of this ware the town wants kept for its own needs (the watch, the rack, an order...): 0 if none. */
    public static int need(ServerLevel level, UUID village, String key) {
        Shop s = SHOPS.get(village);
        if (s == null) return 0;
        Need n = s.need.get(key);
        return n == null ? 0 : n.count();
    }

    /** What a ware is wanted for, in words ("the watch", "the storehouse's rack"), or "the shelves". */
    static String why(UUID village, String key) {
        Shop s = SHOPS.get(village);
        Need n = s == null ? null : s.need.get(key);
        return n == null ? "the shelves" : n.why();
    }

    /** May the shop make this ware in the village's age (Tiers)? A ware of a seller's own recipe always. */
    public static boolean allows(ServerLevel level, UUID village, Stockroom.Ware w) {
        return w.make() == null || Tiers.allows(level, Villages.ageOf(village), w.make());
    }

    /** A ware the workshop keeps that is on none of the sellers' fixed lists, by its name in the books. */
    @Nullable
    public static Stockroom.Ware wareFor(String key) {
        return KNOWN.get(key);
    }

    /** A ware of the workshop's own for this thing: none kept but what the town needs and what sells. */
    static Stockroom.Ware ware(Item it) {
        String key = Stockroom.key(new ItemStack(it));
        Stockroom.Ware known = KNOWN.get(key);
        if (known != null) return known;
        int batch = Math.max(1, Math.min(8, new ItemStack(it).getMaxStackSize() == 1 ? 1 : 4));
        Stockroom.Ware w = Stockroom.ware(it, 0, 0, Math.max(16, batch * 8), batch, false);
        KNOWN.putIfAbsent(w.key(), w);
        return KNOWN.get(w.key());
    }

    /** Add so many wanted of a thing, for this reason, to a book of needs. */
    private static void want(Map<String, Need> need, Map<String, Item> items, Item it, int n, String why) {
        if (it == null || n <= 0) return;
        String key = Stockroom.key(new ItemStack(it));
        items.putIfAbsent(key, it);
        Need was = need.get(key);
        if (was == null) {
            need.put(key, new Need(n, why));
        } else {
            need.put(key, new Need(was.count() + n, was.why().contains(why) ? was.why() : was.why() + " and " + why));
        }
    }

    /**
     * The order book worked out: the town's needs first (the watch's, then the rack's, then the players'
     * orders and the other trades' wants), then the shop's own shelves, then whatever else sells — all of
     * it at what the age lets the shop make; and how many hands that wants at the bench.
     */
    static void look(ServerLevel level, Villages.Village v, Shop s) {
        s.looked = level.getGameTime();
        roll(level, s);
        UUID id = v.id();
        Villages.Age age = Villages.ageOf(id);
        // The best hand the shop has: what it can plan is what the shop can make.
        VillageFolkEntity best = keeper(id);
        for (VillageFolkEntity h : hands(id)) if (best == null || h.veteranLevel() > best.veteranLevel()) best = h;
        Bench.Hand hand = Bench.handOf(level, v, best, "shop");
        Map<String, Need> need = new LinkedHashMap<>();
        Map<String, Item> items = new HashMap<>();
        // The watch: a blade, a suit of armour, a shield and a bow for every guard who has worse, and arrows.
        List<VillageFolkEntity> watch = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.GUARD && !f.isBaby() && f.isAlive()) watch.add(f);
        }
        int spare = watch.isEmpty() ? 0 : (watch.size() + WATCH_SPARE - 1) / WATCH_SPARE;
        Item sword = watch.isEmpty() ? null : choose(level, v, age, hand, wearable(watch, SWORDS));
        if (sword != null) {
            int worse = 0;
            for (VillageFolkEntity g : watch) if (g.mayUseTier(new ItemStack(sword)) && bestBlade(g) < blade(new ItemStack(sword))) worse++;
            want(need, items, sword, worse, "the watch");
        }
        for (EquipmentSlot slot : ARMOUR) {
            Item piece = watch.isEmpty() ? null : choose(level, v, age, hand, wearable(watch, SUITS.get(slot)));
            if (piece == null) continue;
            int worse = 0;
            for (VillageFolkEntity g : watch) {
                if (g.mayUseTier(new ItemStack(piece)) && armour(g.getItemBySlot(slot)) < armour(new ItemStack(piece))) worse++;
            }
            want(need, items, piece, Math.min(8, worse + spare), "the watch");
        }
        if (!watch.isEmpty()) {
            int noShield = 0, noBow = 0;
            for (VillageFolkEntity g : watch) {
                if (g.countCarried(st -> st.getItem() instanceof ShieldItem) == 0 && !(g.getItemBySlot(EquipmentSlot.OFFHAND).getItem() instanceof ShieldItem)) noShield++;
                if (g.countCarried(st -> st.getItem() instanceof BowItem) == 0 && !(g.getMainHandItem().getItem() instanceof BowItem)) noBow++;
            }
            if (Tiers.allows(level, age, Items.SHIELD)) want(need, items, Items.SHIELD, noShield, "the watch");
            want(need, items, Items.BOW, noBow, "the watch");
            want(need, items, Items.ARROW, Math.min(ARROWS_MOST, ARROWS_A_GUARD * watch.size()), "the watch");
        }
        // The storehouse's rack of spares (Toolrack), the shop's to keep: a spare of the age's best the stores
        // can run to for every so many hands that wear one out, and one for every hand whose own is gone.
        for (Toolrack.Tool t : Toolrack.Tool.values()) {
            int wanted = Toolrack.wanted(id, t);
            if (wanted <= 0) continue;
            Item it = choose(level, v, age, hand, TOOLS.get(t));
            if (it == null) continue;
            int missing = 0;
            java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(t.word) + "s?\\b");
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (Toolrack.of(a.stationTask()) != t || a.isBaby()) continue;
                for (String gap : a.missingEssentials()) if (word.matcher(gap.toLowerCase(Locale.ROOT)).find()) { missing++; break; }
            }
            int other = Toolrack.onRack(level, id, t) - spares(level, id, it);
            want(need, items, it, Math.max(0, wanted + missing - Math.max(0, other)), "the storehouse's rack");
        }
        // What players have ordered.
        for (Order o : s.orders) want(need, items, o.item(), o.count(), "an order of " + o.who() + "'s");
        // What the village's other trades want kept (the homes' comforts...).
        for (Map.Entry<String, Demand> d : DEMANDS.entrySet()) {
            try {
                d.getValue().wants(level, v, (it, n) -> want(need, items, it, Math.min(64, n), d.getKey()));
            } catch (RuntimeException e) {
                // another trade's list that will not be read: left off the book
            }
        }
        // The book: the needs first (the shop's own ware for the thing if it has one), then the shelves, then
        // whatever else the shop and the stores sell or are asked for and could make.
        Map<String, Stockroom.Ware> shelves = new LinkedHashMap<>();
        for (Stockroom.Ware w : Cafe.shopWares()) shelves.put(w.key(), w);
        List<Stockroom.Ware> book = new ArrayList<>();
        for (Map.Entry<String, Need> e : need.entrySet()) {
            Stockroom.Ware w = shelves.get(e.getKey());
            book.add(w != null ? w : ware(items.get(e.getKey())));
        }
        for (Stockroom.Ware w : shelves.values()) if (!need.containsKey(w.key())) book.add(w);
        for (Item it : bestSellers(level, id, 12)) {
            String key = Stockroom.key(new ItemStack(it));
            if (need.containsKey(key) || shelves.containsKey(key)) continue;
            book.add(ware(it));
        }
        // What the age lets it make goes on the book; the rest waits for its age.
        List<Stockroom.Ware> open = new ArrayList<>();
        s.waiting.clear();
        for (Stockroom.Ware w : book) {
            if (allows(level, id, w)) open.add(w);
            else if (s.waiting.size() < 12) s.waiting.add(Stockroom.plural(w.key()) + " (" + Tiers.of(level, w.make()).label + ")");
        }
        s.need.clear();
        s.need.putAll(need);
        s.wares = List.copyOf(open);
        // How much work is on the book, in pieces, and the hands it wants: one for every six pieces short, and
        // one for every fifteen folk in the town at the most (four), while the book has work in it.
        int backlog = 0;
        for (Stockroom.Ware w : s.wares) {
            int gap = Stockroom.target(level, id, Stockroom.Seller.SHOP, w) - Market.stock(level, id, w.is());
            if (gap > 0) backlog += (gap + Math.max(1, w.batch()) - 1) / Math.max(1, w.batch());
        }
        s.backlog = backlog;
        int bySize = Math.max(1, Math.min(MOST_HANDS, Villages.headcount(id) / FOLK_A_HAND));
        boolean busy = backlog > 0 || s.madeToday + s.madeYesterday > 0;
        s.handsWanted = !stands(id) || keeper(id) == null || !busy ? 0 : Math.max(1, Math.min(bySize, (backlog + 5) / 6));
    }

    /** Of these, those some guard of the watch may wear or wield (a guard new to it wears no diamond). */
    private static List<Item> wearable(List<VillageFolkEntity> watch, List<Item> tiers) {
        List<Item> out = new ArrayList<>();
        for (Item it : tiers) {
            ItemStack one = new ItemStack(it);
            for (VillageFolkEntity g : watch) if (g.mayUseTier(one)) { out.add(it); break; }
        }
        return out;
    }

    /** Of these, best first, the first the age allows that the stores can run to; failing that, the best the
     *  age allows (its shortage on the books). Wood only in the Wood Age; gold never. */
    @Nullable
    private static Item choose(ServerLevel level, Villages.Village v, Villages.Age age, Bench.Hand hand, List<Item> tiers) {
        Item firstAllowed = null;
        for (Item it : tiers) {
            if (!Tiers.allows(level, age, it)) continue;
            if (BuiltInRegistries.ITEM.getKey(it).getPath().startsWith("wooden_") && age != Villages.Age.WOOD) continue;
            if (firstAllowed == null) firstAllowed = it;
            if (Bench.plan(level, v, it, 1, hand).ok()) return it;
        }
        return firstAllowed;
    }

    /** The spares of this very thing in the stores (half its wear left, or more). */
    private static int spares(ServerLevel level, UUID village, Item it) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.is(it) && Toolrack.left(st) >= FIT) n += st.getCount();
            }
        }
        return n;
    }

    /** What the shop and the stores have sold (and been asked for and not had) most this week, that the game
     *  has a way to make: the best sellers first. */
    private static List<Item> bestSellers(ServerLevel level, UUID village, int most) {
        Map<String, Integer> sold = new HashMap<>();
        for (Stockroom.Seller s : new Stockroom.Seller[]{ Stockroom.Seller.SHOP, Stockroom.Seller.STORES }) {
            for (Stockroom.Line l : Stockroom.book(level, village, s).lines.values()) {
                int d = Stockroom.Line.week(l.sold) + Stockroom.Line.week(l.missed);
                if (d > 0) sold.merge(l.key, d, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> order = new ArrayList<>(sold.entrySet());
        order.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : order) {
            if (out.size() >= most) break;
            ItemStack one = Stockroom.sampleOf(e.getKey());
            if (one.isEmpty() || Cafe.isDrink(one) || RecipeBook.waysFor(level, one.getItem()).isEmpty()) continue;
            out.add(one.getItem());
        }
        return out;
    }

    // ------------------------------------------------------------------ the shop's round

    /**
     * The shop's round, at each piece of its makers' work (rate-limited per village): the staff looked
     * over, the order book worked out, a hand taken on if it wants one, and the watch fitted out of its stock.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (!stands(v.id()) && keeper(v.id()) == null) return;
        Shop s = shop(v.id());
        roll(level, s);
        long now = level.getGameTime();
        if (now - s.staffed >= STAFF || now < s.staffed) {
            s.staffed = now;
            staff(level, v.id());
        }
        if (now - s.looked >= LOOK || now < s.looked) look(level, v, s);
        if (now - s.hired >= HIRE || now < s.hired) {
            s.hired = now;
            hire(level, v, s);
        }
        if (now - s.outfitted >= OUTFIT || now < s.outfitted) {
            s.outfitted = now;
            outfit(level, v);
        }
    }

    /**
     * A piece of work done at the shop's bench (Stockroom.restock made it): in the workshop's day, with who
     * made it, what, out of what and what for; on the maker's card as what it is making; and a hand goes to
     * its crafting table in the shop's back room to make it.
     */
    static void made(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity maker, Stockroom.Made made) {
        Shop s = shop(v.id());
        roll(level, s);
        ItemStack out = made.out();
        String what = Bench.words(out.getItem(), out.getCount());
        String key = Stockroom.key(out);
        String forWhat = why(v.id(), key);
        List<String> in = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : made.plan().takes.entrySet()) {
            if (in.size() >= 4) { in.add("more"); break; }
            in.add(Bench.words(e.getKey(), e.getValue()));
        }
        String role = maker == null ? "the shop" : isHand(maker) ? "hand" : "shopkeeper";
        String who = maker == null ? "the shop" : maker.displayNameCap();
        s.log.add(new Entry(clock(level), who, role, what, String.join(", ", in), forWhat));
        if (s.log.size() > 40) s.log.remove(0);
        s.madeToday++;
        if (maker == null) return;
        s.madeBy.merge(maker.getUUID(), 1, Integer::sum);
        DOINGS.put(maker.getUUID(), new Doing(what, forWhat, level.getGameTime() + DOING));
        if (isHand(maker)) atTheBench(level, v, maker);
    }

    /** A hand to its crafting table in the shop's back room (setting its own down there if the shop has none). */
    private static void atTheBench(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos bench;
        try {
            bench = Trades.workstation(f, level, v, Blocks.CRAFTING_TABLE, st -> st.is(Items.CRAFTING_TABLE), BuildGoal.Part.CRAFTING_TABLE);
        } catch (RuntimeException e) {
            bench = null;
        }
        if (bench == null) return;
        if (f.blockPosition().distSqr(bench) > 6.0) f.walkTo(bench, 0.9D);
        f.getLookControl().setLookAt(bench.getX() + 0.5, bench.getY() + 0.5, bench.getZ() + 0.5);
        f.swing(InteractionHand.MAIN_HAND);
    }

    /** "half past nine": the time of day, for the day's book. */
    private static String clock(ServerLevel level) {
        long t = (level.getDayTime() + 6000L) % 24000L;
        int h = (int) (t / 1000L), m = (int) ((t % 1000L) * 60L / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    // ------------------------------------------------------------------ the smeltery's part

    /** What the shop's books say of a piece waiting on the smeltery (Stockroom.restock). */
    static final String WAITS_ON_THE_SMELTER = "the smelter is firing it";

    /** Is there a smelter at work in the village, to fire what the shop wants fired? */
    static boolean smelterAtWork(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() == StationTask.SMELT && !a.isBaby() && a.isAlive()) return true;
        }
        return false;
    }

    /**
     * A piece of the shop's work that wants something fired first (glass for a bottle, an ingot of the raw
     * iron, charcoal for a torch): with a smelter at work, the firing is the smeltery's. It goes on the
     * smelter's list and the piece waits for it; nothing is taken now. Returns what it waits on, in words, or
     * null if there is nothing to fire (or no smelter: then the shop's bench fires it itself).
     */
    @Nullable
    static String forTheSmelter(ServerLevel level, Villages.Village v, Bench.Plan p) {
        if (!p.ok() || !smelterAtWork(v.id())) return null;
        List<String> fired = new ArrayList<>();
        Shop s = shop(v.id());
        for (Bench.Step st : p.steps) {
            if (st.fire() != RecipeBook.Fire.FURNACE) continue;
            s.firing.merge(st.made(), st.count(), Math::max);
            fired.add(st.words());
        }
        return fired.isEmpty() ? null : String.join(", ", fired) + " from the smeltery";
    }

    /**
     * The smelter's part of the shop's work (Links.tend): what the shop sent to be fired, fired out of the
     * stores in the smeltery's furnaces with their fuel (Bench), two lots at a round at most, and into the
     * stores for the shop's bench. Returns what it fired, or null.
     */
    @Nullable
    public static String fire(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        Shop s = id == null ? null : SHOPS.get(id);
        if (v == null || s == null || s.firing.isEmpty()) return null;
        Bench.Hand hand = Bench.handOf(level, v, f, "smeltery");
        List<String> done = new ArrayList<>();
        String how = "";
        Economy.openCraft(id, StationTask.SMELT);
        try {
            Iterator<Map.Entry<Item, Integer>> it = s.firing.entrySet().iterator();
            while (it.hasNext() && done.size() < 2) {
                Map.Entry<Item, Integer> e = it.next();
                it.remove();
                Bench.Plan p = Bench.plan(level, v, e.getKey(), e.getValue(), hand);
                if (!p.ok()) continue;
                ItemStack out = Bench.make(level, v, p, f, hand);
                if (out.isEmpty()) continue;
                done.add(Bench.words(out.getItem(), out.getCount()));
                how = p.chain();
            }
        } finally {
            Economy.closeCraft();
        }
        if (done.isEmpty()) return null;
        // The pieces that waited on the smeltery are tried again at once.
        for (Stockroom.Line l : Stockroom.book(level, id, Stockroom.Seller.SHOP).lines.values()) {
            if (!WAITS_ON_THE_SMELTER.equals(l.why)) continue;
            l.shortOf = "";
            l.why = "";
            l.triedAt = -100000L;
        }
        String words = String.join(" and ", done);
        roll(level, s);
        s.log.add(new Entry(clock(level), f.displayNameCap(), "smelter", words, how, "the shop's bench"));
        if (s.log.size() > 40) s.log.remove(0);
        f.swing(InteractionHand.MAIN_HAND);
        f.note(AssistantEntity.Deed.THINGS_MADE, done.size());
        f.brain("fired " + words + " for the shop's workshop");
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fired " + words + " for the shop. Mind, it's still warm.",
                "The shop wanted " + words + " — done, and in the stores."));
        }
        return words;
    }

    // ------------------------------------------------------------------ the watch

    /** How good a piece of armour is: its defence, then its toughness. */
    static int armour(ItemStack s) {
        if (!(s.getItem() instanceof ArmorItem a)) return 0;
        return a.getDefense() * 100 + Math.round(a.getToughness() * 10) + (s.isEnchanted() ? 1 : 0);
    }

    /** How good a blade is: what its metal adds to a blow. */
    static int blade(ItemStack s) {
        if (!(s.getItem() instanceof SwordItem sw)) return -1;
        return Math.round(sw.getTier().getAttackDamageBonus() * 10) + 10 + (s.isEnchanted() ? 1 : 0);
    }

    /** The best blade a folk carries or holds. */
    static int bestBlade(VillageFolkEntity f) {
        int best = blade(f.getMainHandItem());
        for (ItemStack st : f.getInventoryItems()) best = Math.max(best, blade(st));
        return best;
    }

    /** The best of the stores' that the score likes better than {@code over}: where it is. */
    private record Found(Container box, int slot, int score) {}

    @Nullable
    private static Found bestInStores(ServerLevel level, UUID village, Predicate<ItemStack> what, java.util.function.ToIntFunction<ItemStack> score, int over) {
        Found best = null;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty() || !what.test(st) || Toolrack.left(st) < FIT) continue;
                int sc = score.applyAsInt(st);
                if (sc > over && (best == null || sc > best.score())) best = new Found(c, i, sc);
            }
        }
        return best;
    }

    /**
     * A guard's old piece back into the stores, the storehouse first (Stacking), booked into the storehouse's
     * books as brought in by the guard: not a making (the watch's round may come in a maker's piece of work,
     * and Market.intoStores would count it the maker's).
     */
    private static void backIntoStores(ServerLevel level, Villages.Village v, ItemStack stack, String who) {
        List<Container> boxes = new ArrayList<>();
        int storehouse = -1;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            if (c instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity) storehouse = boxes.size();
            boxes.add(c);
        }
        int[] took = new int[boxes.size()];
        ItemStack left = Stacking.insert(boxes, stack.copy(), took);
        if (storehouse >= 0 && took[storehouse] > 0) Storekeeping.bookIn(level, v.id(), who, stack, took[storehouse], false);
        if (!left.isEmpty()) Crafts.store(level, v, left);
    }

    /** One of what was found, out of the stores and booked out of the storehouse if it came out of it. */
    private static ItemStack takeOut(ServerLevel level, UUID village, Found f, String to) {
        ItemStack got = f.box().getItem(f.slot()).split(1);
        if (f.box().getItem(f.slot()).isEmpty()) f.box().setItem(f.slot(), ItemStack.EMPTY);
        f.box().setChanged();
        if (f.box() instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity) Storekeeping.bookOut(level, village, to, got, 1, null);
        return got;
    }

    /**
     * The watch fitted out of the shop's stock: each guard that wears worse than the best piece the stores
     * hold for a slot (and may wear it) is given it, and its old piece goes back into the stores; a guard
     * whose best blade is worse than the stores' best is given that. The village pays: no coin changes
     * hands, the shop's takings being the treasury's, but the shop's books count it, and keep more of what
     * the watch takes. Returns how many pieces went out.
     */
    public static int outfit(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Shop s = shop(id);
        roll(level, s);
        int put = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity g) || g.stationTask() != StationTask.GUARD || g.isBaby() || !g.isAlive()) continue;
            List<String> given = new ArrayList<>();
            for (EquipmentSlot slot : ARMOUR) {
                ItemStack worn = g.getItemBySlot(slot);
                Found f = bestInStores(level, id, st -> st.getItem() instanceof ArmorItem ai && ai.getEquipmentSlot() == slot && g.mayUseTier(st),
                    Workshop::armour, armour(worn));
                if (f == null) continue;
                ItemStack got = takeOut(level, id, f, g.displayNameCap());
                if (!worn.isEmpty()) backIntoStores(level, v, worn.copy(), g.displayNameCap());   // the old piece back into the stores
                g.setItemSlot(slot, got);
                given.add(Bench.words(got.getItem(), 1));
                Stockroom.sold(level, id, Stockroom.Seller.SHOP, got, 1, 0);       // to the watch, paid for by the village
            }
            if (!g.isPackFull()) {
                Found f = bestInStores(level, id, st -> st.getItem() instanceof SwordItem && g.mayUseTier(st), Workshop::blade, bestBlade(g));
                if (f != null) {
                    ItemStack got = takeOut(level, id, f, g.displayNameCap());
                    ItemStack left = g.insertGiven(got.copy());
                    if (left.isEmpty()) {
                        given.add(Bench.words(got.getItem(), 1));
                        Stockroom.sold(level, id, Stockroom.Seller.SHOP, got, 1, 0);
                    } else {
                        backIntoStores(level, v, left, g.displayNameCap());
                    }
                }
            }
            if (given.isEmpty()) continue;
            put += given.size();
            s.toTheWatch += given.size();
            String words = String.join(", ", given);
            s.log.add(new Entry(clock(level), g.displayNameCap(), "guard", words, "the shop's stock", "the watch, paid by the village"));
            if (s.log.size() > 40) s.log.remove(0);
            g.brain("fitted out from the shop: " + words);
            if (level.getRandom().nextInt(2) == 0) {
                FolkTalk.speak(g, FolkTalk.pick(level.getRandom(), "New " + (given.size() == 1 ? words.replaceFirst("^(a|an) ", "") : "kit") + " from the shop. Let them come.",
                    "The shop's fitted me out: " + words + ". The village's coin well spent."));
            }
        }
        return put;
    }

    // ------------------------------------------------------------------ orders and refusals

    /** Why the shop (or the storekeeper) may not make this thing yet, or "" if the age lets it. */
    public static String refuse(ServerLevel level, Villages.Village v, Item it) {
        return Tiers.refusal(level, Villages.ageOf(v.id()), it);
    }

    /**
     * A player's order at the shop's workshop: so many of a thing put on its order book, for them. Refused if
     * the game has no way to make it, or the village's age has not come to it. Returns what was said.
     */
    public static String order(ServerLevel level, Villages.Village v, Item it, int n, String who) {
        ItemStack one = new ItemStack(it);
        String name = Bench.plural(one.getHoverName().getString().toLowerCase(Locale.ROOT));
        if (it == Items.AIR || RecipeBook.waysFor(level, it).isEmpty()) return "Nobody knows a way to make " + name + ": there's no recipe for them.";
        String no = refuse(level, v, it);
        if (!no.isEmpty()) return "We can't make those yet: " + no + ".";
        int count = Math.max(1, Math.min(MOST_ORDERED, n));
        Shop s = shop(v.id());
        roll(level, s);
        s.orders.removeIf(o -> o.item() == it && o.who().equals(who));
        s.orders.add(new Order(it, count, who, level.getDayTime() / 24000L));
        s.looked = -100000L;                                        // on the book at the next look
        return "On the workshop's book: " + Bench.words(it, count) + " for " + who + ". The hands make it up out of the stores, "
            + "and it goes on the shelves for you to buy at the counter, once the village's own needs are met.";
    }

    /** Is this on a player's order at the shop: then it is for sale there. */
    static boolean ordered(UUID village, ItemStack s) {
        Shop shop = SHOPS.get(village);
        if (shop == null) return false;
        for (Order o : shop.orders) if (s.is(o.item())) return true;
        return false;
    }

    // ------------------------------------------------------------------ the card

    /** "Making a stone sword for the shop": what a maker at the shop's bench is at, or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Doing d = DOINGS.get(f.getUUID());
        if (d == null || f.level().getGameTime() > d.until() || f.stationTask() != StationTask.SHOP) return null;
        return "Making " + d.what() + " for the shop";
    }

    /** The card's line: what it is making and what for, and how many pieces it has made today. */
    @Nullable
    public static String making(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Shop s = id == null ? null : SHOPS.get(id);
        Doing d = DOINGS.get(f.getUUID());
        int today = s == null ? 0 : s.madeBy.getOrDefault(f.getUUID(), 0);
        boolean now = d != null && f.level().getGameTime() <= d.until();
        if (!now && today == 0) return null;
        return (now ? d.what() + " for the shop" + (d.why().equals("the shelves") ? "" : " (for " + d.why() + ")") : "nothing just now")
            + " · " + today + (today == 1 ? " piece" : " pieces") + " made today";
    }

    /** Who it works for (a hand), or what it keeps (the keeper), for the card. */
    public static String staffLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "the shop";
        VillageFolkEntity keeper = keeper(id);
        int hands = hands(id).size();
        if (isHand(f)) {
            return "the shop's workshop, " + (keeper != null ? "under " + keeper.displayNameCap() + ", the shopkeeper" : "with no keeper yet")
                + "; at the crafting table in its back room, paid as the shop's hand";
        }
        return "the shop: its counter, its books" + (hands > 0 ? ", and its " + hands + (hands == 1 ? " hand" : " hands") + " at the bench" : "")
            + "; it makes between customers";
    }

    // ------------------------------------------------------------------ the town's books

    /**
     * The workshop for the town's books (the Shops page) and /village workshop: whether the shop stands and
     * is open, its keeper and hands (wanted and had), each maker and what it made today, the day's book of
     * pieces made (who, what, of what, what for), the order book against the stock, what is waiting on the
     * age, what is at the smeltery, the players' orders, the blueprints known and open to the age, and what
     * the next age will let it make.
     */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag t = new CompoundTag();
        Villages.Village v = Villages.get(village);
        if (v == null) return t;
        Shop s = shop(village);
        if (keeper(village) != null || stands(village)) wares(level, village);    // the book worked out, if it is due
        roll(level, s);
        Villages.Age age = Villages.ageOf(village);
        t.putBoolean("built", stands(village));
        VillageFolkEntity keeper = keeper(village);
        t.putBoolean("open", keeper != null);
        t.putString("keeper", keeper == null ? "" : keeper.displayNameCap());
        t.putString("age", age.label);
        t.putString("nextAge", age == Villages.Age.NETHER ? "" : Villages.Age.values()[age.ordinal() + 1].label);
        int[] counts = Tiers.counts(level, age);
        t.putInt("blueprints", counts[0]);
        t.putInt("allowed", counts[1]);
        t.putInt("nextCount", Tiers.nextAgeCount(level, age));
        ListTag next = new ListTag();
        for (String n : Tiers.nextAge(level, age, 10)) next.add(StringTag.valueOf(n));
        t.put("next", next);
        List<VillageFolkEntity> hands = hands(village);
        t.putInt("hands", hands.size());
        t.putInt("handsWanted", s.handsWanted);
        t.putInt("madeToday", s.madeToday);
        t.putInt("madeYesterday", s.madeYesterday);
        t.putInt("toTheWatch", s.toTheWatch);
        t.putInt("backlog", s.backlog);
        ListTag makers = new ListTag();
        List<VillageFolkEntity> all = new ArrayList<>();
        if (keeper != null) all.add(keeper);
        all.addAll(hands);
        for (VillageFolkEntity f : all) {
            CompoundTag m = new CompoundTag();
            m.putString("name", f.displayNameCap());
            m.putString("role", f == keeper ? "shopkeeper" : "hand");
            m.putInt("level", f.veteranLevel());
            m.putInt("made", s.madeBy.getOrDefault(f.getUUID(), 0));
            String doing = doing(f);
            m.putString("doing", doing == null ? (f.isSleeping() ? "asleep" : f.offWorkNow() ? "off work" : "at the bench") : doing);
            makers.add(m);
        }
        t.put("makers", makers);
        ListTag log = new ListTag();
        for (int i = s.log.size() - 1; i >= 0 && log.size() < 20; i--) {
            Entry e = s.log.get(i);
            CompoundTag l = new CompoundTag();
            l.putString("time", e.time());
            l.putString("who", e.who());
            l.putString("role", e.role());
            l.putString("what", e.what());
            l.putString("from", e.from());
            l.putString("why", e.why());
            log.add(l);
        }
        t.put("log", log);
        ListTag book = new ListTag();
        Stockroom.Book shopBook = Stockroom.book(level, village, Stockroom.Seller.SHOP);
        for (Stockroom.Ware w : s.wares) {
            if (book.size() >= 48) break;
            int target = Stockroom.target(level, village, Stockroom.Seller.SHOP, w);
            int have = Market.stock(level, village, w.is());
            Stockroom.Line l = shopBook.lines.get(w.key());
            if (target <= 0 && have <= 0 && (l == null || Stockroom.Line.week(l.made) == 0)) continue;
            CompoundTag r = new CompoundTag();
            r.putString("id", w.key());
            r.putString("item", BuiltInRegistries.ITEM.getKey(w.sample().getItem()).toString());
            r.putString("name", Stockroom.nameOf(w.key()));
            r.putInt("have", have);
            r.putInt("target", target);
            r.putInt("need", need(level, village, w.key()));
            r.putString("why", why(village, w.key()));
            r.putInt("madeToday", l == null ? 0 : l.made[0]);
            String sh = l == null || l.shortOf.isEmpty() ? "" : l.shortOf + (l.why.isEmpty() ? "" : " (" + l.why + ")");
            r.putString("short", sh);
            r.putString("how", l == null ? "" : l.how);
            r.putString("status", have >= target ? "stocked" : !sh.isEmpty() ? "short" : "to make");
            book.add(r);
        }
        t.put("book", book);
        ListTag waiting = new ListTag();
        for (String w : s.waiting) waiting.add(StringTag.valueOf(w));
        t.put("waiting", waiting);
        ListTag firing = new ListTag();
        for (Map.Entry<Item, Integer> e : s.firing.entrySet()) firing.add(StringTag.valueOf(Bench.words(e.getKey(), e.getValue())));
        t.put("firing", firing);
        ListTag orders = new ListTag();
        for (Order o : s.orders) orders.add(StringTag.valueOf(Bench.words(o.item(), o.count()) + " for " + o.who()));
        t.put("orders", orders);
        ListTag rule = new ListTag();
        for (String r : Tiers.rule()) rule.add(StringTag.valueOf(r));
        t.put("rule", rule);
        return t;
    }

    /** The workshop as a page of text (/village workshop). */
    public static String page(ServerLevel level, Villages.Village v) {
        CompoundTag t = report(level, v.id());
        StringBuilder sb = new StringBuilder();
        if (!t.getBoolean("built") && !t.getBoolean("open")) {
            sb.append("No shop yet: the shop and its workshop come with a town of eighteen in the Iron Age.\n");
        } else {
            sb.append(t.getBoolean("open") ? "Kept by " + t.getString("keeper") : "Nobody keeps the shop yet")
                .append("; ").append(t.getInt("hands")).append(t.getInt("hands") == 1 ? " hand" : " hands").append(" at the bench (it wants ")
                .append(t.getInt("handsWanted")).append("); ").append(t.getInt("madeToday")).append(" pieces made today, ")
                .append(t.getInt("madeYesterday")).append(" yesterday; ").append(t.getInt("toTheWatch")).append(" to the watch today\n");
        }
        sb.append("Blueprints: ").append(t.getInt("blueprints")).append(" known (every recipe in the game), ").append(t.getInt("allowed"))
            .append(" open to ").append(t.getString("age"));
        ListTag next = t.getList("next", net.minecraft.nbt.Tag.TAG_STRING);
        if (!t.getString("nextAge").isEmpty() && next.size() > 0) {
            List<String> n = new ArrayList<>();
            for (int i = 0; i < Math.min(6, next.size()); i++) n.add(next.getString(i));
            sb.append("; ").append(t.getString("nextAge")).append(" will let us make ").append(String.join(", ", n))
                .append(" and ").append(Math.max(0, t.getInt("nextCount") - n.size())).append(" more");
        }
        sb.append('\n');
        ListTag makers = t.getList("makers", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < makers.size(); i++) {
            CompoundTag m = makers.getCompound(i);
            sb.append("  ").append(m.getString("name")).append(" (").append(m.getString("role")).append(", level ").append(m.getInt("level"))
                .append("): ").append(m.getInt("made")).append(" made today — ").append(m.getString("doing")).append('\n');
        }
        ListTag log = t.getList("log", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (log.size() > 0) sb.append("Made today:\n");
        for (int i = 0; i < Math.min(8, log.size()); i++) {
            CompoundTag l = log.getCompound(i);
            sb.append("  ").append(l.getString("time")).append(' ').append(l.getString("who")).append(" (").append(l.getString("role")).append("): ")
                .append(l.getString("what")).append(l.getString("from").isEmpty() ? "" : ", of " + l.getString("from"))
                .append(" — for ").append(l.getString("why")).append('\n');
        }
        ListTag book = t.getList("book", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (book.size() > 0) sb.append("The order book (stock / target):\n");
        for (int i = 0; i < Math.min(16, book.size()); i++) {
            CompoundTag r = book.getCompound(i);
            sb.append("  ").append(r.getString("name")).append(": ").append(r.getInt("have")).append(" / ").append(r.getInt("target"))
                .append(" — ").append(r.getString("why"));
            if (!r.getString("short").isEmpty()) sb.append("; short of ").append(r.getString("short"));
            sb.append('\n');
        }
        String[] waiting = strings(t, "waiting"), firing = strings(t, "firing"), orders = strings(t, "orders");
        if (waiting.length > 0) sb.append("Waiting on the age: ").append(String.join(", ", waiting)).append('\n');
        if (firing.length > 0) sb.append("At the smeltery: ").append(String.join(", ", firing)).append('\n');
        if (orders.length > 0) sb.append("Ordered: ").append(String.join(", ", orders)).append('\n');
        return sb.toString().stripTrailing();
    }

    private static String[] strings(CompoundTag t, String key) {
        ListTag l = t.getList(key, net.minecraft.nbt.Tag.TAG_STRING);
        String[] out = new String[l.size()];
        for (int i = 0; i < l.size(); i++) out[i] = l.getString(i);
        return out;
    }

    // ------------------------------------------------------------------ commands and tests

    /** A hand taken on now (ops, the client smoke): the nearest grown folk who is not the keeper. */
    @Nullable
    public static VillageFolkEntity hireNow(ServerLevel level, Villages.Village v, BlockPos near) {
        BlockPos at = Villages.builtAt(v.id(), "shop");
        if (at == null) return null;
        VillageFolkEntity keeper = keeper(v.id()), best = null;
        double bestD = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.isShowcase() || f == keeper || isHand(f)) continue;
            double d = f.blockPosition().distSqr(near);
            if (d < bestD) { bestD = d; best = f; }
        }
        if (best == null) return null;
        if (keeper == null) {
            best.setStation(at, StationTask.SHOP);
            best.assignPlot(WorkZone.around(at, 5, WorkZone.DEFAULT_DEPTH), "the shop");
            return best;
        }
        takeOn(level, v, best, at);
        return best;
    }

    /**
     * The client smoke's scene (ops): a shop by this spot if the village has none (put up at once, as the
     * showcase's buildings are), a keeper and a hand for it, the hand at its crafting table and every maker
     * at a piece of work. Says where: "WORKSHOP x y z", "DOOR x y z", "BENCH x y z", "HAND name x y z".
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos near) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        if (Villages.builtAt(id, "shop") == null) {
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
            BlockPos at = new BlockPos(near.getX(), y, near.getZ());
            BuildGoal.stamp(level, "shop", at, net.minecraft.core.Direction.NORTH, 13,
                com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            com.jrpetty.mcassistant.village.Ledger.built(id, "shop", at, net.minecraft.core.Direction.NORTH);
        }
        com.jrpetty.mcassistant.village.Ledger.Building b = Villages.builtStructure(id, "shop");
        BlockPos at = Villages.builtAt(id, "shop");
        if (at == null) return out;
        out.add("WORKSHOP " + at.getX() + " " + at.getY() + " " + at.getZ());
        if (b != null) {
            BlockPos door = TownLife.fittings(b).door();
            if (door != null) out.add("DOOR " + door.getX() + " " + door.getY() + " " + door.getZ());
        }
        if (keeper(id) == null) hireNow(level, v, at);
        VillageFolkEntity hand = hands(id).isEmpty() ? hireNow(level, v, at) : hands(id).get(0);
        if (hand != null) {
            BlockPos bench = null;
            try {
                bench = Trades.workstation(hand, level, v, Blocks.CRAFTING_TABLE, st -> st.is(Items.CRAFTING_TABLE), BuildGoal.Part.CRAFTING_TABLE);
            } catch (RuntimeException e) {
                // no bench to be had: the hand stands where it is
            }
            if (bench != null) {
                out.add("BENCH " + bench.getX() + " " + bench.getY() + " " + bench.getZ());
                BlockPos stand = Trades.floorSpot(level, bench, 1);
                if (stand != null) hand.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                hand.getLookControl().setLookAt(bench.getX() + 0.5, bench.getY() + 0.5, bench.getZ() + 0.5);
            }
            out.add("HAND " + hand.displayNameCap() + " " + hand.getBlockX() + " " + hand.getBlockY() + " " + hand.getBlockZ());
        }
        out.addAll(workNow(level, v));
        return out;
    }

    /** Every maker at the shop's bench does a piece of work now (ops, the client smoke). Returns what they made. */
    public static List<String> workNow(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> all = new ArrayList<>();
        VillageFolkEntity keeper = keeper(v.id());
        if (keeper != null) all.add(keeper);
        all.addAll(hands(v.id()));
        for (VillageFolkEntity f : all) {
            if (Crafts.now(f, level, v)) out.add(f.displayNameCap() + ": " + (doing(f) == null ? "set the counter out" : doing(f)));
        }
        return out;
    }

    /** Tests: this folk a hand at the shop's bench, now. */
    public static void appointForTests(VillageFolkEntity f) {
        f.setJob(StationTask.SHOP);
        f.addTag(HAND);
    }

    /** Tests: the order book worked out afresh, now. */
    public static List<Stockroom.Ware> lookForTests(ServerLevel level, Villages.Village v) {
        Shop s = shop(v.id());
        look(level, v, s);
        return s.wares;
    }

    /** Tests: today's pieces, as "who|role|what|from|why". */
    public static List<String> logForTests(UUID village) {
        Shop s = SHOPS.get(village);
        List<String> out = new ArrayList<>();
        if (s != null) for (Entry e : s.log) out.add(e.who() + "|" + e.role() + "|" + e.what() + "|" + e.from() + "|" + e.why());
        return out;
    }

    /** Tests: what the shop has sent to the smeltery to be fired. */
    public static Map<Item, Integer> firingForTests(UUID village) {
        Shop s = SHOPS.get(village);
        return s == null ? Map.of() : Map.copyOf(s.firing);
    }

    /** For ops and the smoke: the order book's top line, in words ("stone sword 0 / 2, for the watch"). */
    public static String topLine(ServerLevel level, Villages.Village v) {
        for (Stockroom.Ware w : wares(level, v.id())) {
            int target = Stockroom.target(level, v.id(), Stockroom.Seller.SHOP, w);
            if (target <= 0) continue;
            return Stockroom.nameOf(w.key()) + " " + Market.stock(level, v.id(), w.is()) + " / " + target + ", for " + why(v.id(), w.key());
        }
        return "";
    }
}
