package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village's own books: what it keeps for itself, what it can spare, and what its coin goes on.
 *
 * <p><b>Its own needs first.</b> Before anything is sold the village keeps what it needs: a full
 * larder, a tool for every hand and spares besides, armour and arms for its guards, the timber and
 * stone its building wants, the ore its smiths and smelters work, the wool its beds wait on, the
 * seed and saplings for next year. Only what is over that is spare.
 *
 * <p><b>Selling when it can.</b> A village sells to players only once it is making enough for
 * itself — fed, bedded, every hand tooled, its wages paid in full. Until then it sells only a
 * glut (four times what it keeps, and more: the miners' mountain of cobblestone). It sells at the
 * price list's worth (Prices) and a quarter over, so what it sells pays for what it cannot make.
 *
 * <p><b>Its coin.</b> Two days' wages are kept back first; then what it is saving for (wool for
 * beds, a hive, the drover's pair: Market.saveFor); what is left is free.
 */
public final class Budget {

    private Budget() {}

    /** What a player pays over the price list's worth. */
    public static final double PLAYER_MARKUP = 1.25;
    /** A glut: so far over what it keeps that even a struggling village sells it. */
    private static final int GLUT = 4;
    /** How long a look at the stores is trusted. */
    private static final long FRESH = 600L;

    /** The kinds of tool, weapon and armour the village keeps one of for every hand that uses it. */
    enum Kit { PICKAXE, AXE, HOE, SHOVEL, SWORD, BOW, SHEARS, ROD, SHIELD, HELMET, CHEST, LEGS, BOOTS }

    /** One look at the village's stores and needs. */
    private static final class Books {
        long at;
        final Map<Item, Integer> held = new HashMap<>();
        final Map<Kit, Integer> keepKit = new EnumMap<>(Kit.class);
        /** For each kit kind, how many of each item of it the village keeps (its best, up to the count). */
        final Map<Item, Integer> keptKit = new HashMap<>();
        int food, larder, head;
        final EnumSet<Villages.Task> short_ = EnumSet.noneOf(Villages.Task.class);
        final List<String> wants = new ArrayList<>();
        boolean bedsWait;
        /** [fletcher] The arrows kept back from the counter: the raid's reserve, where the town keeps a fletcher. */
        int arrowsKept = 2;
    }

    private static final Map<UUID, Books> BOOKS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BOOKS.clear();
    }

    /** Forget the last look (the stores have just changed a lot: a test, a big sale). */
    public static void forget(UUID village) {
        BOOKS.remove(village);
    }

    private static Books books(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        Books b = BOOKS.get(village);
        if (b != null && now - b.at < FRESH && now >= b.at) return b;
        b = new Books();
        b.at = now;
        b.head = Math.max(1, Villages.headcount(village));
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) b.held.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        // The larder, counted from the same look at the stores (Villages.stock is a cached count,
        // ten seconds stale, and a larder just filled still read empty).
        for (Map.Entry<Item, Integer> e : b.held.entrySet()) {
            ItemStack one = new ItemStack(e.getKey());
            Market.Good g = Market.goodFor(one);
            if (one.get(net.minecraft.core.component.DataComponents.FOOD) != null || g != null && g.need() == Villages.Task.FOOD) {
                b.food += e.getValue();
            }
        }
        b.larder = Math.max(256, 3 * Villages.larderForBirth(village));
        for (Villages.Need n : Villages.needs(level, village)) b.short_.add(n.task());
        b.bedsWait = Market.bedsShort(village) > 0;
        b.arrowsKept = Fletchers.arrowsKept(village, 2);                  // [fletcher] the watch's reserve is not for sale
        // A tool for every hand that uses one, and spares (one and a tenth of the village more).
        Map<AssistantEntity.StationTask, Integer> trades = new EnumMap<>(AssistantEntity.StationTask.class);
        int toolsShort = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby()) continue;
            trades.merge(a.stationTask(), 1, Integer::sum);
            for (String gap : JobSpec.missing(a)) {
                String g = gap.toLowerCase(java.util.Locale.ROOT);
                if (g.contains("pickaxe") || g.contains("axe") || g.contains("hoe") || g.contains("sword") || g.contains("shears")
                        || g.contains("rod") || g.contains("shovel") || g.contains("bow")) { toolsShort++; break; }
            }
        }
        int spares = 1 + b.head / 10;
        int hands = b.head / 5;                                       // builders and town hands
        int guards = trades.getOrDefault(AssistantEntity.StationTask.GUARD, 0);
        b.keepKit.put(Kit.PICKAXE, trades.getOrDefault(AssistantEntity.StationTask.MINE, 0) + hands + spares);
        b.keepKit.put(Kit.AXE, trades.getOrDefault(AssistantEntity.StationTask.WOOD, 0) + hands + spares);
        b.keepKit.put(Kit.HOE, trades.getOrDefault(AssistantEntity.StationTask.FARM, 0) + spares);
        b.keepKit.put(Kit.SHOVEL, hands + spares);
        b.keepKit.put(Kit.SWORD, guards + trades.getOrDefault(AssistantEntity.StationTask.HUNT, 0) + spares);
        b.keepKit.put(Kit.BOW, guards + spares);
        b.keepKit.put(Kit.SHEARS, trades.getOrDefault(AssistantEntity.StationTask.RANCH, 0) + spares);
        b.keepKit.put(Kit.ROD, trades.getOrDefault(AssistantEntity.StationTask.FISH, 0) + spares);
        for (Kit k : new Kit[]{ Kit.SHIELD, Kit.HELMET, Kit.CHEST, Kit.LEGS, Kit.BOOTS }) b.keepKit.put(k, guards + spares);
        // Of each kind, it keeps its best: the dearest first, up to the count.
        Map<Kit, List<Item>> byKit = new EnumMap<>(Kit.class);
        for (Item it : b.held.keySet()) {
            Kit k = kitOf(new ItemStack(it));
            if (k != null) byKit.computeIfAbsent(k, x -> new ArrayList<>()).add(it);
        }
        for (Map.Entry<Kit, List<Item>> e : byKit.entrySet()) {
            List<Item> items = e.getValue();
            items.sort((x, y) -> Double.compare(Prices.each(y), Prices.each(x)));
            int left = b.keepKit.getOrDefault(e.getKey(), spares);
            for (Item it : items) {
                int keep = Math.min(left, b.held.getOrDefault(it, 0));
                b.keptKit.put(it, keep);
                left -= keep;
            }
        }
        // What it wants before it sells: the village is making enough for itself.
        if (b.food < b.larder) b.wants.add("a full larder");                // counted now, not this morning
        if (Market.bedsShort(village) > 1) b.wants.add("beds for everyone");
        if (toolsShort > 0) b.wants.add("tools for every hand");
        int share = Market.lastShare(village);
        if (share >= 0 && share < 100) b.wants.add("the wages paid in full");
        BOOKS.put(village, b);
        return b;
    }

    /** Is the village making enough for itself — fed, bedded, tooled and paid — so that it can sell? */
    public static boolean selfSufficient(ServerLevel level, UUID village) {
        return books(level, village).wants.isEmpty();
    }

    /** What it still wants for itself before it sells ("a full larder", "beds for everyone"...). */
    public static List<String> wants(ServerLevel level, UUID village) {
        return List.copyOf(books(level, village).wants);
    }

    /** What kind of kit this is, or null if it is no tool, weapon or armour. */
    @Nullable
    static Kit kitOf(ItemStack s) {
        Item it = s.getItem();
        if (it instanceof PickaxeItem) return Kit.PICKAXE;
        if (it instanceof AxeItem) return Kit.AXE;
        if (it instanceof HoeItem) return Kit.HOE;
        if (it instanceof ShovelItem) return Kit.SHOVEL;
        if (it instanceof SwordItem) return Kit.SWORD;
        if (it instanceof BowItem || it instanceof CrossbowItem) return Kit.BOW;
        if (it instanceof ShearsItem) return Kit.SHEARS;
        if (it instanceof FishingRodItem) return Kit.ROD;
        if (it instanceof ShieldItem) return Kit.SHIELD;
        if (it instanceof ArmorItem a) {
            return switch (a.getType()) {
                case HELMET -> Kit.HELMET;
                case CHESTPLATE -> Kit.CHEST;
                case LEGGINGS -> Kit.LEGS;
                case BOOTS -> Kit.BOOTS;
                default -> null;
            };
        }
        return null;
    }

    /** How many of this the village keeps for itself, whatever it has. */
    public static int keep(ServerLevel level, UUID village, ItemStack sample) {
        return keep(books(level, village), sample);
    }

    private static int keep(Books b, ItemStack s) {
        Item it = s.getItem();
        int held = b.held.getOrDefault(it, 0);
        if (Market.isCoin(s)) return Integer.MAX_VALUE;
        if (kitOf(s) != null) return b.keptKit.getOrDefault(it, 0);
        Market.Good g = Market.goodFor(s);
        // Whatever the village is short of itself, it keeps all of.
        // (Food goes by the larder, counted just now, below: the village's list of shortages is a
        // few seconds stale, and read a larder just filled as empty.)
        if (g != null && g.need() != Villages.Task.NONE && g.need() != Villages.Task.FOOD && b.short_.contains(g.need())) return Integer.MAX_VALUE;
        if (s.is(ItemTags.WOOL)) return b.bedsWait ? Integer.MAX_VALUE : 32;
        if (s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW)) return b.arrowsKept;   // [fletcher] the raid's reserve
        if (s.is(ItemTags.BEDS)) return b.bedsWait ? Integer.MAX_VALUE : 2;
        Economy.Kind kind = Economy.kindOf(s);
        boolean food = s.get(net.minecraft.core.component.DataComponents.FOOD) != null
            || (g != null && g.need() == Villages.Task.FOOD);
        if (s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS)
                || s.is(ItemTags.SAPLINGS)) return 64;
        if (food || s.is(Items.WHEAT)) {
            // Out of a full larder only, and never the last sixteen of a thing.
            int over = b.food - b.larder;
            return over <= 0 ? Integer.MAX_VALUE : Math.max(16, held - over);
        }
        if (s.is(ItemTags.LOGS)) return Math.max(256, 8 * b.head);
        if (s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)) return Math.max(384, 12 * b.head);
        if (s.is(ItemTags.PLANKS)) return 128;
        if (s.is(Items.IRON_INGOT)) return 32 + 2 * b.head;
        if (s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE)) return 64;
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) return 64 + 4 * b.head;
        if (s.is(Items.GOLD_INGOT)) return 27;
        if (s.is(Items.DIAMOND)) return 5;
        if (s.is(Items.OBSIDIAN)) return 14;
        if (s.is(Items.REDSTONE) || s.is(Items.LAPIS_LAZULI) || s.is(Items.COPPER_INGOT) || s.is(Items.QUARTZ)) return 32;
        if (s.is(Items.LEATHER) || s.is(Items.STRING) || s.is(Items.EGG)) return 16;
        if (s.is(Items.BONE) || s.is(Items.FEATHER) || s.is(Items.GLASS) || s.is(Items.SAND)) return 32;
        if (s.is(Items.TORCH)) return 64;
        if (s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) return 2 + b.head / 4;
        if (s.is(Items.CHEST) || s.is(Items.FURNACE) || s.is(Items.CRAFTING_TABLE)) return 4;
        if (kind == Economy.Kind.TIMBER || kind == Economy.Kind.STONE) return 64;
        if (kind == Economy.Kind.ORE || kind == Economy.Kind.ANIMAL || kind == Economy.Kind.PLANT) return 16;
        return 2;                                                      // a couple of anything else, for itself
    }

    /**
     * How many of this the village can spare. Its own needs come first; and until it is making
     * enough for itself it parts only with a glut.
     */
    public static int spare(ServerLevel level, UUID village, ItemStack sample) {
        if (sample.isEmpty() || Prices.each(sample.getItem()) <= 0) return 0;
        // What the crafts make to sell — the café's drinks, the brewer's potions, the enchanter's
        // work, the tailor's banners and rugs — is the village's to sell whenever it has it: nobody
        // here needs an enchanted pick to eat.
        if (luxury(sample)) return Market.stock(level, village, s -> ItemStack.isSameItemSameComponents(s, sample));
        if (Cuisine.isDish(sample)) return Cuisine.spare(level, village, sample);   // [culture2] the cook's to sell, two kept for the feast
        Books b = books(level, village);
        int held = b.held.getOrDefault(sample.getItem(), 0);
        int keep = keep(b, sample);
        if (keep == Integer.MAX_VALUE || held <= keep) return 0;
        if (b.wants.isEmpty()) return held - keep;
        // Short of something itself: no arms, armour or tools leave the village, however many it has.
        if (kitOf(sample) != null) return 0;
        long glut = (long) keep * GLUT;
        return keep > 0 && held > glut ? (int) (held - glut) : 0;
    }

    /** Made to be sold, not needed: drinks and potions, enchanted things, banners and rugs, books. */
    static boolean luxury(ItemStack s) {
        return Cafe.isDrink(s) || s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)
            || s.isEnchanted() || s.is(Items.ENCHANTED_BOOK) || s.is(ItemTags.BANNERS) || s.is(ItemTags.WOOL_CARPETS)
            || s.is(Items.BOOK) || s.is(Items.HONEY_BOTTLE)
            || FireworksMaker.elytra(s);                              // [fireworks] made for the players' wings, not the town's
    }

    /** One thing for sale: what, how many, and its price each. */
    public record Offer(Item item, int count, double each) {
        public String name() {
            return new ItemStack(item).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What the village can sell, the dearest lots first, at a player's price. */
    public static List<Offer> forSale(ServerLevel level, UUID village, int most) {
        Books b = books(level, village);
        List<Offer> out = new ArrayList<>();
        for (Item it : b.held.keySet()) {
            ItemStack one = new ItemStack(it);
            if (luxury(one) || it == Items.POTION || it == Items.ENCHANTED_BOOK) continue;   // on the café's and the shop's counters
            if (Weave.unsellable(one)) continue;                                              // [weave] a forged coin is never sold
            int n = spare(level, village, one);
            if (n <= 0) continue;
            double each = playerPrice(one);
            if (each <= 0) continue;
            out.add(new Offer(it, n, each));
        }
        out.sort((x, y) -> Double.compare(y.each() * Math.min(y.count(), y.item().getDefaultMaxStackSize()),
            x.each() * Math.min(x.count(), x.item().getDefaultMaxStackSize())));
        return out.size() > most ? out.subList(0, most) : out;
    }

    /** A player's price for one of these: the market's or the price list's worth, and a quarter over. */
    public static double playerPrice(ItemStack one) {
        Market.Good g = Market.goodFor(one);
        double worth = g != null ? g.value() : Prices.of(one.copyWithCount(1));
        return worth * PLAYER_MARKUP;
    }

    /**
     * The market's good for a thing on a counter, or — for anything else the village can price —
     * a good of its own, sold one at a time at its worth (the shop's armour and arms).
     */
    @Nullable
    public static Market.Good goodFor(ItemStack s) {
        Market.Good g = Market.goodFor(s);
        if (g != null) return g;
        if (s.isEmpty() || Market.isCoin(s)) return null;
        double worth = Prices.each(s.getItem());
        if (worth <= 0) return null;
        Item it = s.getItem();
        return new Market.Good(s.getHoverName().getString(), x -> x.is(it), worth, 1, Villages.Task.NONE);
    }

    // ------------------------------------------------------------------ talk and the books

    /** "What can the village spare?" — the storekeeper's answer (or the elder's), with prices. */
    public static String answer(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "I've nothing to sell — I've no village yet.";
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST) return "Nothing for you. Not a crumb.";
        List<Offer> offers = forSale(level, village, 8);
        List<String> wants = wants(level, village);
        if (offers.isEmpty()) {
            return wants.isEmpty()
                ? "We've nothing over just now — everything we make, we use. Come back after the next harvest."
                : "We can't spare anything yet. We see to ourselves first, and we're still short: we want "
                    + String.join(", ", wants) + ". When we're making enough for ourselves, the rest is for sale.";
        }
        StringBuilder sb = new StringBuilder(wants.isEmpty()
            ? "We've seen to ourselves, so here's what we can spare: "
            : "We're not making enough for ourselves yet, but we've more of these than we'll ever use: ");
        boolean first = true;
        for (Offer o : offers) {
            if (!first) sb.append("; ");
            first = false;
            int lot = Math.min(o.count(), o.item().getDefaultMaxStackSize());
            sb.append(o.count() > lot ? lot + "+" : String.valueOf(o.count())).append(' ').append(o.name()).append(" at ").append(priceWords(o.each()));
        }
        sb.append(". Ask the stores for what you want — \"could I have 2 ").append(offers.get(0).name()).append("?\"")
            .append(" The café and the shop sell what they make, too.");
        if (title == Standing.Title.UNWELCOME) sb.append(" Mind, it's double for you.");
        return sb.toString();
    }

    /** "3 coins each", or "1 coin for 4" for cheap things. */
    static String priceWords(double each) {
        if (each >= 1.0) {
            int c = (int) Math.round(each);
            return c + (c == 1 ? " coin each" : " coins each");
        }
        int per = (int) Math.max(2, Math.round(1.0 / Math.max(0.01, each)));
        return "1 coin for " + per;
    }

    /** The budget for the status and the board: the coin, what is kept back, what is free, and what is for sale. */
    public static String line(ServerLevel level, UUID village) {
        int coins = Ledger.coins(village);
        int wages = 2 * Market.wageBill(village);
        int saved = Market.saved(village, level.getGameTime());
        int free = Math.max(0, coins - wages - saved);
        StringBuilder sb = new StringBuilder().append(coins).append(" coin — ").append(Math.min(coins, wages))
            .append(" kept for two days' wages");
        if (saved > 0) sb.append(", ").append(saved).append(" put by");
        sb.append(", ").append(free).append(" free");
        List<String> wants = wants(level, village);
        List<Offer> offers = forSale(level, village, 4);
        if (!offers.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Offer o : offers) names.add(Math.min(o.count(), 999) + " " + o.name());
            sb.append(wants.isEmpty() ? "; for sale: " : "; selling only its glut: ").append(String.join(", ", names));
        } else if (!wants.isEmpty()) {
            sb.append("; selling nothing till it has ").append(String.join(", ", wants));
        } else {
            sb.append("; nothing over to sell");
        }
        return sb.toString();
    }
}
