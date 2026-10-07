package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ-trade] A town's trade book: what it is good at and what it is short of, ware by ware — food, timber,
 * stone, ore and iron, coal, wool and the crafts.
 *
 * <p>For each ware the book has:
 * <ul>
 * <li><b>What it holds</b> against <b>what it keeps</b> for itself (the levels the town's own budget holds back:
 *     a full larder, a few hundred logs and a few hundred of stone by its size, its smiths' iron, the
 *     smelters' coal, the beds' wool), and against what its age still asks for (Villages.needs). Over
 *     the keep it has a <b>surplus</b>, and what it can afford to part with is Budget.spare's word, never
 *     a rule of thumb. Under it, or short of what the age wants, it has a <b>shortage</b>.</li>
 * <li><b>What it makes a day</b> (the economy's books, item by item) and <b>what goes out a day</b> (eaten,
 *     built, sold, sent), kept as running averages, and so how many <b>days of cover</b> it has.</li>
 * <li><b>How its land leans</b> (Homeland): a mountain town's miners dig more, a river town farms more, a
 *     forest town cuts more timber.</li>
 * <li>Its <b>price here</b> (PriceIndex: what one costs in this town today) and its <b>worth to the town</b>:
 *     that price, more if the town is short of it and less if it has a glut. A good it is short of is
 *     worth more to it. This is what each town reckons a deal by (TradeTalks): its own price, never
 *     the other's.</li>
 * </ul>
 * The book is read from the stores afresh every half a minute or so, and its running rates are kept in
 * the ledger, so they outlast a restart.
 */
public final class TradeBook {

    private TradeBook() {}

    /** The wares the towns trade in, each with the need it answers, the trade that makes it, and its usual lot. */
    public enum Ware {
        FOOD("food", Villages.Task.FOOD, StationTask.FARM, Items.BREAD, 8),
        TIMBER("timber", Villages.Task.LOGS, StationTask.WOOD, Items.OAK_LOG, 16),
        STONE("stone", Villages.Task.STONE, StationTask.MINE, Items.COBBLESTONE, 64),
        IRON("ore and iron", Villages.Task.IRON, StationTask.MINE, Items.IRON_INGOT, 4),
        COAL("coal", Villages.Task.COAL, StationTask.MINE, Items.COAL, 8),
        WOOL("wool", Villages.Task.NONE, StationTask.RANCH, Items.WHITE_WOOL, 8),
        CRAFTS("crafts", Villages.Task.NONE, StationTask.SMITH, Items.IRON_PICKAXE, 1);

        public final String word;
        final Villages.Task task;
        /** The trade whose hands make it: what a deal leans the town's shares toward or away from. */
        public final StationTask trade;
        /** What stands for it when the town has none of it to name (a want, a price). */
        final Item usual;
        /** The smallest amount worth putting on a caravan for. */
        public final int lot;

        Ware(String word, Villages.Task task, StationTask trade, Item usual, int lot) {
            this.word = word;
            this.task = task;
            this.trade = trade;
            this.usual = usual;
            this.lot = lot;
        }

        /** Is this of the ware, as the stores count it? */
        public boolean holds(ItemStack s) {
            if (s.isEmpty() || Market.isCoin(s)) return false;
            return switch (this) {
                case FOOD -> s.get(DataComponents.FOOD) != null || s.is(Items.WHEAT);
                case TIMBER -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);
                case STONE -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.STONE);
                case IRON -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE);
                case COAL -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
                case WOOL -> s.is(ItemTags.WOOL);
                case CRAFTS -> Economy.kindOf(s) == Economy.Kind.CRAFT;
            };
        }

        /**
         * Is this something a town would load on a caravan for the ware? Proper food the market deals in (bread,
         * potatoes, the cooked meats; not rotten flesh), logs not planks, cobblestone not smooth stone, ingots and
         * raw iron, coal, wool. The crafts are sold at home, at the shop and the café, not carried.
         */
        public boolean carried(ItemStack s) {
            if (!holds(s)) return false;
            Market.Good g = Market.goodFor(s);
            return switch (this) {
                case FOOD -> s.get(DataComponents.FOOD) != null && g != null && g.need() == Villages.Task.FOOD;
                case TIMBER -> s.is(ItemTags.LOGS);
                case STONE -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
                case IRON -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON);
                case COAL, WOOL -> true;
                case CRAFTS -> false;
            };
        }

        public boolean traded() { return this != CRAFTS; }

        /** How many of the ware one of these counts as: wheat is a third of a meal, a plank a quarter of a log. */
        double counts(ItemStack s) {
            if (this == FOOD && s.is(Items.WHEAT)) return s.getCount() / 3.0;
            if (this == TIMBER && s.is(ItemTags.PLANKS)) return s.getCount() / 4.0;
            return s.getCount();
        }

        @Nullable
        public static Ware of(ItemStack s) {
            for (Ware w : values()) if (w.holds(s)) return w;
            return null;
        }
    }

    public enum Status {
        SURPLUS("to spare"), SHORT("short"), EVEN("enough");

        public final String word;
        Status(String word) { this.word = word; }
    }

    /**
     * One ware in a town's book.
     *
     * @param held   what the stores hold of it (meals for food, logs for timber)
     * @param keep   what the town keeps for itself before it parts with any
     * @param spare  what it can afford to send (Budget.spare, summed over what it holds of the ware)
     * @param want   how far it is short: under its keep, or under what its age asks for, whichever is more
     * @param made   made a day (running average)
     * @param used   gone out a day: eaten, built with, sold, sent (running average)
     * @param days   days of cover at that rate, or -1 when nothing goes out
     * @param land   how its land leans to the trade that makes it (Homeland.lean: 1 for an ordinary place)
     * @param item   what it would send of it (the kind it has most to spare of), or what stands for it
     * @param price  one of that item in coin, here, today (PriceIndex)
     * @param worth  one of it to this town: the price, more when short and less in a glut
     */
    public record Entry(Ware ware, int held, int keep, int spare, int want, double made, double used, double days,
                        double land, Item item, double price, double worth, Status status) {

        public String itemName() {
            return new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
        }
    }

    /** A town's whole book, as last read. */
    public record Book(UUID town, long at, Map<Ware, Entry> entries) {
        public Entry get(Ware w) { return entries.get(w); }

        public List<Entry> surpluses() {
            List<Entry> out = new ArrayList<>();
            for (Entry e : entries.values()) if (e.status() == Status.SURPLUS && e.ware().traded()) out.add(e);
            out.sort((a, b) -> Double.compare(b.spare() * b.price(), a.spare() * a.price()));
            return out;
        }

        public List<Entry> shortages() {
            List<Entry> out = new ArrayList<>();
            for (Entry e : entries.values()) if (e.status() == Status.SHORT && e.ware().traded()) out.add(e);
            out.sort((a, b) -> Double.compare(b.want() * b.worth(), a.want() * a.worth()));
            return out;
        }
    }

    /** How long a reading of the stores is trusted. */
    private static final long FRESH = 600L;
    private static final Map<UUID, Book> BOOKS = new ConcurrentHashMap<>();
    /** The running rates, by town and ware: {made a day, gone out a day, held at the last morning, mornings kept}. */
    private static final Map<UUID, double[][]> RATES = new ConcurrentHashMap<>();
    /** What came in and went out by the deals since the last morning, by town and ware: {in, out}. */
    private static final Map<UUID, double[][]> MOVED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BOOKS.clear();
        RATES.clear();
        MOVED.clear();
    }

    /** Read the stores afresh at the next asking (a delivery, a test). */
    public static void forget(UUID village) {
        BOOKS.remove(village);
        Budget.forget(village);
    }

    // ------------------------------------------------------------------ reading the book

    /** The town's book, read from its stores (afresh if the last reading is stale). */
    public static Book of(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        Book b = BOOKS.get(village);
        if (b != null && now - b.at() < FRESH && now >= b.at()) return b;
        b = read(level, village, now);
        BOOKS.put(village, b);
        return b;
    }

    private static Book read(ServerLevel level, UUID village, long now) {
        Map<Item, Integer> held = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && !s.isEnchanted()) held.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        int head = Math.max(1, Villages.headcount(village));
        Map<Villages.Task, Integer> ageWants = new EnumMap<>(Villages.Task.class);
        for (Villages.Need n : Villages.needs(level, village)) ageWants.merge(n.task(), n.amount(), Math::max);
        double[][] rates = rates(village);
        Map<Ware, Entry> out = new EnumMap<>(Ware.class);
        for (Ware w : Ware.values()) {
            double count = 0;
            int spare = 0;
            Item best = null;
            int bestSpare = 0, mostHeld = 0;
            Item most = null;
            for (Map.Entry<Item, Integer> e : held.entrySet()) {
                ItemStack s = new ItemStack(e.getKey(), Math.max(1, e.getValue()));
                if (!w.holds(s)) continue;
                count += w.counts(s);
                if (!w.carried(s)) continue;
                if (e.getValue() > mostHeld) { mostHeld = e.getValue(); most = e.getKey(); }
                int sp = Budget.spare(level, village, new ItemStack(e.getKey()));
                if (sp <= 0) continue;
                spare += sp;
                if (sp > bestSpare) { bestSpare = sp; best = e.getKey(); }
            }
            int have = (int) Math.floor(count);
            int keep = keepOf(w, village, head, held);
            // Food is reckoned all together, as the larder is: a hundred loaves and a hundred potatoes over the
            // larder are a hundred meals to spare, not two hundred (Budget.spare reads each kind against the
            // larder's whole overflow).
            if (w == Ware.FOOD) spare = Math.min(spare, Math.max(0, have - keep));
            if (w == Ware.CRAFTS) spare = 0;
            int ageWant = w.task == Villages.Task.NONE ? 0 : ageWants.getOrDefault(w.task, 0);
            int want = Math.max(Math.max(0, keep - have), ageWant);
            if (w == Ware.CRAFTS) want = toolsShort(village);
            Item item = best != null ? best : most != null ? most : w.usual;
            double made = rates[w.ordinal()][0], used = rates[w.ordinal()][1];
            if (w == Ware.FOOD) {
                Leader.Books lb = Leader.books(village);
                if (lb != null && lb.useAvg() > 0) used = Math.max(used, lb.useAvg());
                else if (used <= 0) used = Leader.guessedUse(village);
            }
            double days = used > 0.05 ? have / used : -1;
            double land = Homeland.lean(village, w.trade);
            double price = PriceIndex.each(level, village, new ItemStack(item));
            Status status = spare >= w.lot && w.traded() ? Status.SURPLUS : want > 0 ? Status.SHORT : Status.EVEN;
            double worth = price * need(status, want, spare, keep);
            out.put(w, new Entry(w, have, keep, spare, want, made, used, days, land, item, price, worth, status));
        }
        return new Book(village, now, out);
    }

    /**
     * What the town keeps of the ware before it parts with any: the same levels its own budget holds back
     * (Budget.keep) — the larder (three times what a birth asks, two hundred and fifty-six at least), eight
     * logs a head, twelve stone a head, its smiths' iron, the smelters' coal, wool for the beds that wait.
     */
    static int keepOf(Ware w, UUID village, int head, Map<Item, Integer> held) {
        return switch (w) {
            case FOOD -> Math.max(256, 3 * Villages.larderForBirth(village));
            case TIMBER -> Math.max(256, 8 * head);
            case STONE -> Math.max(384, 12 * head);
            case IRON -> 32 + 2 * head;
            case COAL -> 64 + 4 * head;
            case WOOL -> 32 + 3 * Math.max(0, Market.bedsShort(village));
            case CRAFTS -> 0;
        };
    }

    /** Grown hands without the tool their trade wants. */
    private static int toolsShort(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby()) continue;
            for (String gap : JobSpec.missing(a)) {
                String g = gap.toLowerCase(Locale.ROOT);
                if (g.contains("pickaxe") || g.contains("axe") || g.contains("hoe") || g.contains("shears") || g.contains("rod")) { n++; break; }
            }
        }
        return n;
    }

    /**
     * How much more (or less) one of a thing is worth to the town than its price: up to three quarters again
     * when it is short of it (by how far short against what it keeps), down to three quarters of the price
     * when it has a glut of it. The stone a town has none of is worth more to it than the stone piled in a
     * mining town's yard is to the mining town; that difference is the whole of the gain in a trade.
     */
    static double need(Status s, int want, int spare, int keep) {
        double k = Math.max(1, keep);
        if (s == Status.SHORT) return 1.0 + 0.75 * Math.min(1.0, want / k);
        if (s == Status.SURPLUS) return 1.0 - 0.25 * Math.min(1.0, spare / k);
        return 1.0;
    }

    /** What one of this item is worth to the town, by its own price (PriceIndex) and its own need. */
    public static double worth(ServerLevel level, UUID village, Item item) {
        ItemStack one = new ItemStack(item);
        Ware w = Ware.of(one);
        double price = PriceIndex.each(level, village, one);
        if (w == null) return price;
        Entry e = of(level, village).get(w);
        return price * need(e.status(), e.want(), e.spare(), e.keep());
    }

    /**
     * Has each something the other wants? One has a surplus of a ware the other is short of — either way
     * round. What sets an elder thinking of sending an envoy to talk trade (Envoys.choose).
     */
    public static boolean complementary(ServerLevel level, UUID a, UUID b) {
        Book x = of(level, a), y = of(level, b);
        for (Entry e : x.surpluses()) if (y.get(e.ware()).status() == Status.SHORT) return true;
        for (Entry e : y.surpluses()) if (x.get(e.ware()).status() == Status.SHORT) return true;
        return false;
    }

    // ------------------------------------------------------------------ the rates

    private static double[][] rates(UUID village) {
        return RATES.computeIfAbsent(village, v -> {
            double[][] r = new double[Ware.values().length][4];
            String kept = Ledger.note(v, "trade.rates");
            if (kept != null && !kept.isEmpty()) {
                for (String part : kept.split(";")) {
                    String[] kv = part.split(":", 2);
                    if (kv.length < 2) continue;
                    try {
                        Ware w = Ware.valueOf(kv[0]);
                        String[] n = kv[1].split("/");
                        for (int i = 0; i < Math.min(4, n.length); i++) r[w.ordinal()][i] = Double.parseDouble(n[i]);
                    } catch (RuntimeException ignored) {
                        // a ware this book no longer keeps
                    }
                }
            }
            return r;
        });
    }

    /** A deal's delivery came in (+) or went out (-) of the town's stores: for the morning's rates. */
    static void moved(UUID village, Ware w, int n) {
        double[][] m = MOVED.computeIfAbsent(village, v -> new double[Ware.values().length][2]);
        if (n >= 0) m[w.ordinal()][0] += n; else m[w.ordinal()][1] -= n;
    }

    /**
     * The morning: yesterday's making (the economy's books, item by item) and what went out (what the stores
     * held yesterday, and made, and were sent, less what they hold now) into the running averages, a third
     * new to two thirds old. Kept in the ledger.
     */
    public static void morning(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        double[][] r = rates(id);
        double[] made = new double[Ware.values().length];
        for (Map.Entry<String, int[]> e : Economy.yesterdayItems(id).entrySet()) {
            Item it = itemOf(e.getKey());
            if (it == null) continue;
            ItemStack s = new ItemStack(it, Math.max(1, e.getValue()[0]));
            for (Ware w : Ware.values()) if (w.holds(s)) { made[w.ordinal()] += w.counts(s); break; }
        }
        forget(id);
        Book now = of(level, id);
        double[][] moved = MOVED.remove(id);
        StringBuilder sb = new StringBuilder();
        for (Ware w : Ware.values()) {
            double[] x = r[w.ordinal()];
            int held = now.get(w).held();
            double in = moved == null ? 0 : moved[w.ordinal()][0], out = moved == null ? 0 : moved[w.ordinal()][1];
            double used = x[3] < 1 ? 0 : Math.max(0, x[2] + made[w.ordinal()] + in - out - held);
            if (x[3] < 1) {
                x[0] = made[w.ordinal()];
                x[1] = used;
            } else {
                x[0] = (x[0] * 2 + made[w.ordinal()]) / 3.0;
                x[1] = (x[1] * 2 + used) / 3.0;
            }
            x[2] = held;
            x[3] = Math.min(99, x[3] + 1);
            if (sb.length() > 0) sb.append(';');
            sb.append(w.name()).append(':').append(round(x[0])).append('/').append(round(x[1])).append('/')
                .append((int) x[2]).append('/').append((int) x[3]);
        }
        Ledger.note(id, "trade.rates", sb.toString());
        forget(id);
    }

    @Nullable
    static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    private static String round(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    /** Tests: the running rates as though a few mornings had been kept: made and gone out a day. */
    public static void ratesForTests(UUID village, Ware w, double made, double used) {
        double[] x = rates(village)[w.ordinal()];
        x[0] = made;
        x[1] = used;
        x[3] = Math.max(x[3], 3);
        BOOKS.remove(village);
    }

    // ------------------------------------------------------------------ telling it

    /** "Food: 120 held, keeps 256 — short 136 (2.1 days' cover); makes 18 a day, uses 57; the land: ordinary." */
    public static String line(Entry e) {
        StringBuilder sb = new StringBuilder(capital(e.ware().word)).append(": ").append(e.held()).append(" held, keeps ").append(e.keep());
        if (e.status() == Status.SURPLUS) sb.append(" — ").append(e.spare()).append(" to spare (").append(e.itemName()).append(")");
        else if (e.status() == Status.SHORT) sb.append(" — short ").append(e.want());
        else sb.append(" — enough");
        if (e.days() >= 0) sb.append(", ").append(e.days() >= 99 ? "99+" : String.format(Locale.ROOT, "%.1f", e.days())).append(" days' cover");
        sb.append("; makes ").append(round(e.made())).append(" a day, ").append(round(e.used())).append(" go out");
        sb.append("; ").append(landWords(e.land()));
        sb.append("; ").append(Budget.priceWords(e.price())).append(" here, worth ").append(Budget.priceWords(e.worth())).append(" to us");
        return sb.toString();
    }

    public static String landWords(double lean) {
        return lean >= 1.45 ? "the land lives by it" : lean >= 1.15 ? "the land leans to it"
            : lean <= 0.75 ? "poor ground for it" : lean <= 0.95 ? "the land is against it" : "ordinary ground for it";
    }

    /** "Good at stone and iron; short of food and wool." */
    public static String summary(Book b) {
        List<String> good = new ArrayList<>(), shortOf = new ArrayList<>();
        for (Entry e : b.surpluses()) good.add(e.ware().word);
        for (Entry e : b.shortages()) shortOf.add(e.ware().word);
        String s = good.isEmpty() ? "Nothing much to spare" : "Good at " + join(good);
        return s + (shortOf.isEmpty() ? "; short of nothing" : "; short of " + join(shortOf));
    }

    static String join(List<String> parts) {
        if (parts.size() <= 1) return parts.isEmpty() ? "" : parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
