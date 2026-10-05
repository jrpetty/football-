package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town's books, day by day: what the village was each morning, kept for four hundred days, so
 * a player at the board can see it grow and see why.
 *
 * <p>Every morning (Market.tick, when the day's books close) the village is written down: its
 * people (grown, children, born, died, come and gone), its ground (age, buildings, beds, households
 * housed and waiting), its money (the treasury, the purses, its worth, the stores' worth), its
 * work (what it made in all and of each kind, what each trade made and how many work at each,
 * what came in and what went out), its stores (food, timber, stone, coal, iron), and how content
 * it is. {@link #snapshot} reads it all back out for the analytics screen (client/CityScreen),
 * with the village as it stands now: every trade's numbers, every folk, the leader's profile, the
 * homes, the neighbours, the latest news, and a reading of what is driving its growth and what is
 * holding it back.
 */
public final class Annals {

    private Annals() {}

    /** How many days are kept. */
    public static final int KEEP = 400;
    /** How many days of the item-by-item books are kept (the totals are kept for ever). */
    public static final int ITEM_KEEP = 100;

    /** What is written each morning, in order (the client reads the names). */
    public static final List<String> KEYS = List.of(
        "pop", "adults", "kids", "born", "died", "moved_in", "moved_out", "age", "buildings", "room", "bedded",
        "housed", "waiting", "coins", "purses", "worth", "stores_worth", "output", "takings", "sold", "tithe", "wages",
        "spent", "food", "logs", "stone", "coal", "iron", "content", "idle", "guards", "renown", "food_days10",
        "out_food", "out_timber", "out_stone", "out_ore", "out_animal", "out_craft", "out_plant",
        "rent", "house_sales", "saving", "owned", "rented", "rent_free");

    /** Today's comings and goings, before the morning writes them down. */
    private static final Map<UUID, int[]> TODAY = new ConcurrentHashMap<>();
    private static final int BORN = 0, DIED = 1, IN = 2, OUT = 3;

    public static void resetForTests() {
        TODAY.clear();
    }

    public static void born(@Nullable UUID village) {
        if (village != null) TODAY.computeIfAbsent(village, k -> new int[4])[BORN]++;
    }

    /** A death, and how (old age, misfortune, the raiders...): kept by cause for the books. */
    public static void died(@Nullable UUID village, String how) {
        if (village == null) return;
        TODAY.computeIfAbsent(village, k -> new int[4])[DIED]++;
        String key = "annals.causes";
        Map<String, Integer> causes = causes(village);
        causes.merge(how == null || how.isEmpty() ? "unknown" : how, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : causes.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey().replace(';', ' ').replace('=', ' ')).append('=').append(e.getValue());
        }
        Ledger.note(village, key, sb.toString());
    }

    /** A folk gone from one village to another (married away, moved on). */
    public static void moved(@Nullable UUID from, @Nullable UUID to) {
        if (from != null) TODAY.computeIfAbsent(from, k -> new int[4])[OUT]++;
        if (to != null) TODAY.computeIfAbsent(to, k -> new int[4])[IN]++;
    }

    static Map<String, Integer> causes(UUID village) {
        Map<String, Integer> out = new TreeMap<>();
        String note = Ledger.note(village, "annals.causes");
        if (note == null || note.isEmpty()) return out;
        for (String part : note.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try { out.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ the morning's record

    /** The village as it is this morning, written down (after Economy.closeTheDay has closed yesterday). */
    public static void record(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int[] moves = TODAY.remove(id);
        if (moves == null) moves = new int[4];
        Map<String, Integer> n = new HashMap<>();
        int adults = 0, kids = 0, idle = 0, guards = 0, bedded = 0, purses = 0;
        Map<StationTask, Integer> heads = new EnumMap<>(StationTask.class);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase()) continue;
            if (f.isBaby()) { kids++; continue; }
            adults++;
            purses += f.purse();
            StationTask t = f.stationTask();
            if (t == StationTask.NONE) idle++;
            else heads.merge(t, 1, Integer::sum);
            if (t == StationTask.GUARD) guards++;
            if (f.bedPos() != null) bedded++;
        }
        n.put("pop", adults + kids);
        n.put("adults", adults);
        n.put("kids", kids);
        n.put("born", moves[BORN]);
        n.put("died", moves[DIED]);
        n.put("moved_in", moves[IN]);
        n.put("moved_out", moves[OUT]);
        n.put("age", Villages.ageOf(id).ordinal());
        n.put("buildings", Ledger.buildings(id).size());
        n.put("room", Villages.housing(id));
        n.put("bedded", bedded);
        int[] homes = Homes.counts(level, id);
        n.put("housed", homes[0]);
        n.put("waiting", homes[1]);
        n.put("coins", Ledger.coins(id));
        n.put("purses", purses);
        n.put("worth", Math.max(0, Economy.worth(id)));
        n.put("stores_worth", parse(Ledger.note(id, "worth.stores")));
        Economy.Day d = Economy.yesterdayBooks(id);
        n.put("output", d == null ? 0 : (int) Math.round(d.total()));
        n.put("takings", d == null ? 0 : d.takings);
        n.put("sold", d == null ? 0 : d.sold);
        n.put("tithe", d == null ? 0 : d.tithe);
        n.put("rent", d == null ? 0 : d.rent);
        n.put("house_sales", d == null ? 0 : d.houses);
        n.put("saving", homes.length > 8 ? homes[8] : 0);
        n.put("owned", homes[3]);
        n.put("rented", homes[4]);
        n.put("rent_free", homes.length > 13 ? homes[13] : 0);
        n.put("wages", d == null ? 0 : d.wages);
        n.put("spent", d == null ? 0 : d.spent);
        int r = Villages.storesRadius(id);
        n.put("food", Villages.stock(level, v.centre(), Villages.Task.FOOD, r));
        n.put("logs", Villages.stock(level, v.centre(), Villages.Task.LOGS, r));
        n.put("stone", Villages.stock(level, v.centre(), Villages.Task.STONE, r));
        n.put("coal", Villages.stock(level, v.centre(), Villages.Task.COAL, r));
        n.put("iron", Villages.stock(level, v.centre(), Villages.Task.IRON, r));
        n.put("content", Contentment.score(id));
        n.put("idle", idle);
        n.put("guards", guards);
        n.put("renown", Villages.renown(id));
        Leader.Books books = Leader.books(id);
        n.put("food_days10", books == null ? 0 : (int) Math.round(Math.min(99.0, books.days()) * 10));
        String[] kinds = { "out_food", "out_timber", "out_stone", "out_ore", "out_animal", "out_craft", "out_plant" };
        for (int i = 0; i < kinds.length; i++) {
            n.put(kinds[i], d == null || i >= d.kinds.length ? 0 : (int) Math.round(d.kinds[i]));
        }
        StringBuilder sb = new StringBuilder("v1|");
        for (int i = 0; i < KEYS.size(); i++) sb.append(i == 0 ? "" : ",").append(n.getOrDefault(KEYS.get(i), 0));
        sb.append('|');
        boolean first = true;
        if (d != null) {
            for (Map.Entry<StationTask, Double> e : d.trades.entrySet()) {
                int val = (int) Math.round(e.getValue());
                if (val <= 0) continue;
                sb.append(first ? "" : ",").append(e.getKey().name()).append(':').append(val);
                first = false;
            }
        }
        sb.append('|');
        first = true;
        for (Map.Entry<StationTask, Integer> e : heads.entrySet()) {
            sb.append(first ? "" : ",").append(e.getKey().name()).append(':').append(e.getValue());
            first = false;
        }
        Ledger.note(id, "annals/" + day, sb.toString());
        Ledger.forget(id, "annals/" + (day - KEEP));                          // the oldest day let go
        recordItems(id, day);
        String first0 = Ledger.note(id, "annals.first");
        if (first0 == null || first0.isEmpty()) Ledger.note(id, "annals.first", Long.toString(day));
        // An election since yesterday: into the history of elections.
        String last = Ledger.note(id, "election.last");
        String seen = Ledger.note(id, "annals.election.seen");
        if (last != null && !last.isEmpty() && !last.equals(seen)) {
            Ledger.note(id, "annals.election.seen", last);
            String hist = Ledger.note(id, "annals.elections");
            String entry = last.replace(';', ',');
            String all = hist == null || hist.isEmpty() ? entry : hist + ";" + entry;
            String[] parts = all.split(";");
            if (parts.length > 40) all = String.join(";", java.util.Arrays.copyOfRange(parts, parts.length - 40, parts.length));
            Ledger.note(id, "annals.elections", all);
        }
    }

    // ------------------------------------------------------------------ item by item

    /**
     * Yesterday's making, item by item, into the books: "oak_log=212/0/WOOD;bread=40/0/FARM.COOK;
     * iron_ingot=9/1/SMELT" (made, used up in making other things, the trades that made it, most
     * first), and onto the totals since the village began.
     */
    static void recordItems(UUID id, long day) {
        Map<String, int[]> items = Economy.yesterdayItems(id);
        Map<String, Map<StationTask, Integer>> makers = Economy.yesterdayMakers(id);
        StringBuilder sb = new StringBuilder();
        Map<String, long[]> totals = itemTotals(id);
        for (Map.Entry<String, int[]> e : items.entrySet()) {
            int[] n = e.getValue();
            if (n[0] <= 0 && n[1] <= 0) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(n[0]).append('/').append(n[1]).append('/');
            Map<StationTask, Integer> by = makers.getOrDefault(e.getKey(), Map.of());
            List<Map.Entry<StationTask, Integer>> order = new ArrayList<>(by.entrySet());
            order.sort((a, b) -> b.getValue() - a.getValue());
            for (int i = 0; i < Math.min(3, order.size()); i++) sb.append(i == 0 ? "" : ".").append(order.get(i).getKey().name());
            long[] t = totals.computeIfAbsent(e.getKey(), k -> new long[2]);
            t[0] += n[0];
            t[1] += n[1];
        }
        Ledger.note(id, "annals.items/" + day, sb.length() == 0 ? "-" : sb.toString());   // "-": a day nothing was made
        Ledger.forget(id, "annals.items/" + (day - ITEM_KEEP));
        StringBuilder tb = new StringBuilder();
        for (Map.Entry<String, long[]> e : totals.entrySet()) {
            if (tb.length() > 0) tb.append(';');
            tb.append(e.getKey()).append('=').append(e.getValue()[0]).append('/').append(e.getValue()[1]);
        }
        Ledger.note(id, "annals.items.total", tb.toString());
    }

    /** The totals since the village began: {made, used}, item by item. */
    static Map<String, long[]> itemTotals(UUID id) {
        Map<String, long[]> out = new TreeMap<>();
        String note = Ledger.note(id, "annals.items.total");
        if (note == null || note.isEmpty()) return out;
        for (String part : note.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            String[] n = part.substring(eq + 1).split("/");
            try {
                out.put(part.substring(0, eq), new long[]{ Long.parseLong(n[0]), n.length > 1 ? Long.parseLong(n[1]) : 0 });
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** One day of the item books: made and used, item by item, and who made each. */
    record ItemDay(long day, Map<String, int[]> items, Map<String, String> makers) {}

    static List<ItemDay> itemDays(UUID village) {
        List<ItemDay> out = new ArrayList<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith("annals.items/") || e.getValue() == null || e.getValue().isEmpty()) continue;
            long day;
            try { day = Long.parseLong(e.getKey().substring(13)); } catch (NumberFormatException ex) { continue; }
            Map<String, int[]> items = new HashMap<>();
            Map<String, String> makers = new HashMap<>();
            if (!e.getValue().equals("-")) {
                for (String part : e.getValue().split(";")) {
                    int eq = part.lastIndexOf('=');
                    if (eq <= 0) continue;
                    String[] n = part.substring(eq + 1).split("/", -1);
                    String key = part.substring(0, eq);
                    items.put(key, new int[]{ parse(n[0]), n.length > 1 ? parse(n[1]) : 0 });
                    if (n.length > 2 && !n[2].isEmpty()) makers.put(key, n[2]);
                }
            }
            out.add(new ItemDay(day, items, makers));
        }
        out.sort((a, b) -> Long.compare(a.day(), b.day()));
        return out;
    }

    /** The item an entry in the books names. */
    static net.minecraft.world.item.Item itemOf(String id) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return rl == null ? net.minecraft.world.item.Items.AIR : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
    }

    /** What stands for a need of the village's in the item books: logs for timber, raw iron and ingots for iron... */
    @Nullable
    public static java.util.function.Predicate<net.minecraft.world.item.ItemStack> forTask(Villages.Task t) {
        return switch (t) {
            case LOGS -> st -> st.is(net.minecraft.tags.ItemTags.LOGS);
            case STONE -> st -> Economy.kindOf(st) == Economy.Kind.STONE;
            case COAL -> st -> st.is(net.minecraft.world.item.Items.COAL) || st.is(net.minecraft.world.item.Items.CHARCOAL);
            case IRON -> st -> st.is(net.minecraft.world.item.Items.RAW_IRON) || st.is(net.minecraft.world.item.Items.IRON_INGOT);
            case FOOD -> st -> Economy.kindOf(st) == Economy.Kind.FOOD;
            case DIAMOND -> st -> st.is(net.minecraft.world.item.Items.DIAMOND);
            case OBSIDIAN -> st -> st.is(net.minecraft.world.item.Items.OBSIDIAN);
            default -> null;
        };
    }

    /** How many of what matches the village made a day, on average, over the last so many days of its books. */
    public static double ratePerDay(UUID village, @Nullable java.util.function.Predicate<net.minecraft.world.item.ItemStack> what, int days) {
        if (what == null) return 0;
        List<ItemDay> all = itemDays(village);
        if (all.isEmpty()) return 0;
        int from = Math.max(0, all.size() - days);
        Map<String, Boolean> matches = new HashMap<>();
        long sum = 0;
        for (int i = from; i < all.size(); i++) {
            for (Map.Entry<String, int[]> e : all.get(i).items().entrySet()) {
                boolean m = matches.computeIfAbsent(e.getKey(), k -> what.test(new net.minecraft.world.item.ItemStack(itemOf(k))));
                if (m) sum += e.getValue()[0];
            }
        }
        return sum / (double) (all.size() - from);
    }

    /**
     * The production page: every item the village has made, with what it made of it yesterday, in
     * the last week and month and in all, what it used of it, how many it has in its stores, what
     * one is worth, who makes it, and its day-by-day making; the whole of it a day; and the leader's
     * reading of it against what the village is short of.
     */
    private static CompoundTag production(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<ItemDay> days = itemDays(id);
        int n = days.size();
        int[] dayNums = new int[n];
        for (int i = 0; i < n; i++) dayNums[i] = (int) days.get(i).day();
        out.put("days", new IntArrayTag(dayNums));
        Map<String, long[]> totals = itemTotals(id);
        // What the stores hold now, item by item.
        Map<String, Integer> onHand = new HashMap<>();
        for (net.minecraft.core.BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack st = c.getItem(i);
                if (!st.isEmpty()) onHand.merge(Economy.id(st), st.getCount(), Integer::sum);
            }
        }
        java.util.Set<String> ids = new java.util.TreeSet<>(totals.keySet());
        for (ItemDay d : days) ids.addAll(d.items().keySet());
        int[] madeAll = new int[n], kindsAll = new int[n];
        long[] worthAll = new long[n];
        List<CompoundTag> rows = new ArrayList<>();
        for (String key : ids) {
            net.minecraft.world.item.Item item = itemOf(key);
            if (item == net.minecraft.world.item.Items.AIR) continue;
            net.minecraft.world.item.ItemStack one = new net.minecraft.world.item.ItemStack(item);
            double each = Math.max(0, Prices.of(one));
            int[] series = new int[n];
            int used7 = 0;
            Map<String, Integer> who = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int[] c = days.get(i).items().get(key);
                if (c == null) continue;
                series[i] = c[0];
                madeAll[i] += c[0];
                if (c[0] > 0) kindsAll[i]++;
                worthAll[i] += Math.round(c[0] * each);
                if (i >= n - 7) used7 += c[1];
                String m = days.get(i).makers().get(key);
                if (m != null && i >= n - 30) for (String t : m.split("\\.")) who.merge(t, c[0], Integer::sum);
            }
            int d1 = n == 0 ? 0 : series[n - 1], w7 = 0, prev7 = 0, m30 = 0;
            for (int i = Math.max(0, n - 7); i < n; i++) w7 += series[i];
            for (int i = Math.max(0, n - 14); i < Math.max(0, n - 7); i++) prev7 += series[i];
            for (int i = Math.max(0, n - 30); i < n; i++) m30 += series[i];
            long[] t = totals.getOrDefault(key, new long[2]);
            if (t[0] <= 0 && m30 <= 0 && used7 <= 0) continue;                // only ever used, long ago
            CompoundTag r = new CompoundTag();
            r.putString("id", key);
            Economy.Kind k = Economy.kindOf(one);
            r.putString("kind", k == null ? "other" : k.name().toLowerCase(Locale.ROOT));
            r.putIntArray("series", series);
            r.putInt("d1", d1);
            r.putInt("w7", w7);
            r.putInt("prev7", prev7);
            r.putInt("m30", m30);
            r.putLong("total", t[0]);
            r.putLong("used_total", t[1]);
            r.putInt("used7", used7);
            r.putInt("on_hand", onHand.getOrDefault(key, 0));
            r.putInt("each100", (int) Math.round(each * 100));
            List<Map.Entry<String, Integer>> makers = new ArrayList<>(who.entrySet());
            makers.sort((a, b) -> b.getValue() - a.getValue());
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, Integer> e : makers) {
                try { names.add(StationTask.valueOf(e.getKey()).title); } catch (IllegalArgumentException ex) { names.add(e.getKey()); }
            }
            r.putString("by", String.join(", ", names));
            rows.add(r);
        }
        // Most worth a day first; at most two hundred kinds of thing.
        rows.sort((a, b) -> Long.compare((long) b.getInt("w7") * b.getInt("each100"), (long) a.getInt("w7") * a.getInt("each100")));
        ListTag list = new ListTag();
        for (int i = 0; i < Math.min(200, rows.size()); i++) list.add(rows.get(i));
        out.put("items", list);
        out.putIntArray("made", madeAll);
        out.putIntArray("kinds", kindsAll);
        int[] worth = new int[n];
        for (int i = 0; i < n; i++) worth[i] = (int) Math.min(Integer.MAX_VALUE, worthAll[i]);
        out.putIntArray("worth", worth);
        // The leader's reading: what the village is short of, against what it is making of it.
        List<String> reading = new ArrayList<>();
        for (Villages.Need need : Villages.needs(level, id)) {
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> what = forTask(need.task());
            if (what == null || need.amount() <= 0) continue;
            double rate = ratePerDay(id, what, 7);
            String line = need.what() + ": " + need.amount() + " more wanted; making " + (rate >= 10 ? Long.toString(Math.round(rate))
                : String.format(Locale.ROOT, "%.1f", rate)) + " a day";
            line += rate <= 0 ? " — none at all: the leader puts hands to it first" : need.amount() / rate > 5
                ? " — " + Math.round(need.amount() / rate) + " days at this rate: more hands to it" : " — there in " + Math.max(1, Math.round(need.amount() / rate)) + " days";
            reading.add(line);
        }
        out.put("reading", strings(reading));
        return out;
    }

    /** One day of the books, read back: the numbers by KEYS, what each trade made, how many worked at each. */
    record Day(long day, int[] values, Map<String, Integer> made, Map<String, Integer> hands) {}

    static List<Day> days(UUID village) {
        List<Day> out = new ArrayList<>();
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith("annals/") || e.getValue().isEmpty()) continue;
            long day;
            try { day = Long.parseLong(e.getKey().substring(7)); } catch (NumberFormatException ex) { continue; }
            String[] parts = e.getValue().split("\\|", -1);
            if (parts.length < 2 || !parts[0].equals("v1")) continue;
            String[] nums = parts[1].split(",");
            int[] values = new int[KEYS.size()];
            for (int i = 0; i < Math.min(nums.length, values.length); i++) values[i] = parse(nums[i]);
            out.add(new Day(day, values, pairs(parts.length > 2 ? parts[2] : ""), pairs(parts.length > 3 ? parts[3] : "")));
        }
        out.sort((a, b) -> Long.compare(a.day(), b.day()));
        return out;
    }

    private static Map<String, Integer> pairs(String s) {
        Map<String, Integer> out = new HashMap<>();
        if (s.isEmpty()) return out;
        for (String p : s.split(",")) {
            int c = p.indexOf(':');
            if (c > 0) out.put(p.substring(0, c), parse(p.substring(c + 1)));
        }
        return out;
    }

    static int parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    static int key(String name) {
        return KEYS.indexOf(name);
    }

    // ------------------------------------------------------------------ the snapshot for the screen

    /**
     * Everything the analytics screen shows, in one tag: the days of the books as series, every
     * trade's numbers, every folk, the leader, the homes, the stores, the neighbours, the news, the
     * board's own page, and what is driving the village's growth.
     */
    public static CompoundTag snapshot(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long today = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        out.putString("name", Villages.name(id));
        out.putString("age", Villages.ageOf(id).label);
        out.putString("rank", Villages.rank(id).label);
        out.putString("land", Homeland.line(id));
        out.putLong("today", today);
        out.putString("look", Palettes.line(id));
        // The series.
        List<Day> days = days(id);
        ListTag keys = new ListTag();
        for (String k : KEYS) keys.add(StringTag.valueOf(k));
        out.put("keys", keys);
        int[] dayNums = new int[days.size()];
        for (int i = 0; i < days.size(); i++) dayNums[i] = (int) days.get(i).day();
        out.put("days", new IntArrayTag(dayNums));
        CompoundTag series = new CompoundTag();
        for (int k = 0; k < KEYS.size(); k++) {
            int[] s = new int[days.size()];
            for (int i = 0; i < days.size(); i++) s[i] = days.get(i).values()[k];
            series.put(KEYS.get(k), new IntArrayTag(s));
        }
        out.put("series", series);
        CompoundTag tradeOut = new CompoundTag(), tradeHands = new CompoundTag();
        java.util.Set<String> trades = new java.util.TreeSet<>();
        for (Day d : days) { trades.addAll(d.made().keySet()); trades.addAll(d.hands().keySet()); }
        for (String t : trades) {
            int[] made = new int[days.size()], hands = new int[days.size()];
            for (int i = 0; i < days.size(); i++) {
                made[i] = days.get(i).made().getOrDefault(t, 0);
                hands[i] = days.get(i).hands().getOrDefault(t, 0);
            }
            tradeOut.put(t, new IntArrayTag(made));
            tradeHands.put(t, new IntArrayTag(hands));
        }
        out.put("trade_out", tradeOut);
        out.put("trade_hands", tradeHands);
        // Now: the trades, the people, the leader, the homes, the rest.
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk.add(f);
        out.put("jobs", jobs(id, folk, days));
        out.put("people", people(level, id, folk, today));
        out.put("players", players(level, id));
        out.put("leader", leader(level, id, folk, today));
        out.put("homes", homes(level, id, folk));
        out.put("now", now(level, v, folk));
        out.put("drivers", strings(drivers(level, v, folk, days)));
        out.put("news", strings(news(id)));
        out.put("needs", strings(needs(level, id)));
        out.put("neighbours", strings(neighbours(id)));
        out.put("society", society(id, folk));
        out.put("league", league(level, v));
        out.put("production", production(level, v));
        out.put("shops", Stockroom.inventoryReport(level, id));
        out.put("stock", stock(level, v));
        // The storehouse's books, its staff (the storekeeper, the couriers) and its run list: the Stores page.
        out.put("storehouse", Storekeeping.report(level, v));
        out.put("buildings", buildings(level, v));
        out.put("research", CityTree.report(id));                // the city's research: the tree, the pick and why
        out.put("jobmarket", JobMarket.report(level, id));         // the job market between towns: the Jobs page's other view
        List<String> queue = new ArrayList<>();
        for (String p : Villages.projectsWanted(id)) queue.add(Villages.spoken(p));
        out.put("queue", strings(queue));
        Map<String, Integer> causes = causes(id);
        CompoundTag c = new CompoundTag();
        for (Map.Entry<String, Integer> e : causes.entrySet()) c.putInt(e.getKey(), e.getValue());
        out.put("causes", c);
        String[] page = VillageBoards.page(VillageBoards.compose(level, id));
        out.putString("board_title", page[0]);
        out.putString("board", page[1].length() > 20000 ? page[1].substring(0, 20000) : page[1]);
        return out;
    }

    private static ListTag strings(List<String> lines) {
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s.length() > 400 ? s.substring(0, 400) : s));
        return l;
    }

    /** Every trade the village works: hands, idle hands, levels, pay, what it made yesterday and this week, per head. */
    private static ListTag jobs(UUID id, List<VillageFolkEntity> folk, List<Day> days) {
        Map<StationTask, int[]> j = new EnumMap<>(StationTask.class);       // hands, levels, wages, purses
        for (VillageFolkEntity f : folk) {
            if (f.isBaby()) continue;
            int[] a = j.computeIfAbsent(f.stationTask(), k -> new int[4]);
            a[0]++;
            a[1] += f.veteranLevel();
            a[2] += Wealth.wage(f);
            a[3] += f.purse();
        }
        Economy.Day y = Economy.yesterdayBooks(id);
        double weekTotal = 0;
        Map<String, Integer> week = new HashMap<>();
        for (int i = Math.max(0, days.size() - 7); i < days.size(); i++) {
            for (Map.Entry<String, Integer> e : days.get(i).made().entrySet()) {
                week.merge(e.getKey(), e.getValue(), Integer::sum);
                weekTotal += e.getValue();
            }
        }
        ListTag out = new ListTag();
        java.util.Set<StationTask> all = new java.util.LinkedHashSet<>(j.keySet());
        for (String t : week.keySet()) {
            try { all.add(StationTask.valueOf(t)); } catch (IllegalArgumentException ignored) { }
        }
        for (StationTask t : all) {
            int[] a = j.getOrDefault(t, new int[4]);
            CompoundTag c = new CompoundTag();
            c.putString("id", t.name());
            c.putString("title", t == StationTask.NONE ? "Unassigned" : t.title);
            c.putInt("ordinal", t.ordinal());
            c.putInt("hands", a[0]);
            c.putInt("level", a[0] == 0 ? 0 : Math.round(a[1] / (float) a[0]));
            c.putInt("wage", a[0] == 0 ? Wealth.tradeWage(t, id) : Math.round(a[2] / (float) a[0]));
            c.putInt("purses", a[3]);
            int yd = y == null ? 0 : (int) Math.round(y.trades.getOrDefault(t, 0.0));
            int wk = week.getOrDefault(t.name(), 0);
            c.putInt("yesterday", yd);
            c.putInt("week", wk);
            c.putInt("per_head", a[0] == 0 ? 0 : Math.round(wk / 7f / a[0]));
            c.putInt("share", weekTotal <= 0 ? 0 : (int) Math.round(wk * 100.0 / weekTotal));
            c.putInt("wage_bill", a[2]);
            out.add(c);
        }
        return out;
    }

    /** Every folk: trade, level, age, purse, pay, what it made yesterday, mood, nature, home. */
    private static ListTag people(ServerLevel level, UUID id, List<VillageFolkEntity> folk, long today) {
        List<VillageFolkEntity> sorted = new ArrayList<>(folk);
        sorted.sort((a, b) -> Integer.compare(Economy.madeYesterday(b), Economy.madeYesterday(a)));
        ListTag out = new ListTag();
        for (VillageFolkEntity f : sorted) {
            if (out.size() >= 200) break;
            CompoundTag c = new CompoundTag();
            c.putString("name", f.displayNameCap());
            c.putString("trade", f.isBaby() ? "Child" : f.stationTask() == StationTask.NONE ? "Unassigned" : f.stationTask().title);
            c.putInt("ordinal", f.stationTask().ordinal());
            c.putInt("level", f.veteranLevel());
            c.putInt("age", f.bornDay() == VillageFolkEntity.UNKNOWN ? -1 : (int) Math.max(0, today - f.bornDay()));
            c.putInt("years", f.ageYears());
            c.putInt("purse", f.purse());
            c.putInt("wage", f.isBaby() ? 0 : Wealth.wage(f));
            c.putInt("made", Economy.madeYesterday(f));
            c.putInt("mood", f.persona().mood());
            c.putString("type", f.isBaby() ? "" : Values.type(f));
            c.putString("wealth", f.isBaby() ? "" : Wealth.tier(f).name().toLowerCase(Locale.ROOT).replace('_', ' '));
            c.putInt("worth", f.isBaby() ? 0 : Wealth.worth(f));
            if (!f.isBaby()) {
                // Its net worth, laid out: loose money, put by toward a house, a house of its own, what it
                // carries, the comforts of home; and all it has earned.
                c.putInt("house_fund", Homes.savedShare(f));
                c.putInt("house_owned", Homes.ownedShare(f));
                c.putInt("goods", Wealth.belongings(f));
                c.putInt("comforts", f.comforts() * 3);
                c.putInt("earned", f.earnedInAll());
            }
            c.putString("partner", f.life().partnerName());
            c.putBoolean("leader", f.getUUID().equals(Villages.elder(id)));
            c.putBoolean("bed", f.bedPos() != null);
            out.add(c);
        }
        return out;
    }

    /**
     * The players with a stake in the village (its citizens, and anyone who owns a house in it): their
     * loose money (the village coin they carry, when they are on), the houses they own and what those
     * are worth, the rent their tenants owe them, and all of it together.
     */
    private static ListTag players(ServerLevel level, UUID id) {
        java.util.Map<UUID, String> who = new java.util.LinkedHashMap<>(Ledger.citizens(id));
        for (net.minecraft.server.level.ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (Homes.playerHouses(id, p.getUUID())[0] > 0) who.putIfAbsent(p.getUUID(), p.getName().getString());
        }
        ListTag out = new ListTag();
        for (java.util.Map.Entry<UUID, String> e : who.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("name", e.getValue());
            net.minecraft.server.level.ServerPlayer on = level.getServer().getPlayerList().getPlayer(e.getKey());
            int loose = on == null ? -1 : Market.coinsHeld(on);
            int[] houses = Homes.playerHouses(id, e.getKey());
            int due = 0;
            try {
                String n = Ledger.note(id, "rentdue/" + e.getKey());
                due = n == null || n.isEmpty() ? 0 : Integer.parseInt(n.trim());
            } catch (NumberFormatException ignored) { }
            c.putInt("loose", loose);
            c.putInt("houses", houses[0]);
            c.putInt("house_worth", houses[1]);
            c.putInt("rent_due", due);
            c.putInt("worth", Math.max(0, loose) + houses[1] + due);
            c.putBoolean("online", on != null);
            c.putBoolean("citizen", Ledger.citizen(id, e.getKey()));
            out.add(c);
        }
        return out;
    }

    /**
     * Everything the village holds, item by item, for the Stock page: how many in all, in how many
     * stacks, how many in the storehouse and how many in its other store chests, how many more are on
     * their way in the workers' production chests, what one is worth, how many the village keeps back
     * (and why: the builders' timber, the age's iron...), whether its next age wants it, and how many are
     * somebody's marked work or enchanted. With the storehouse's slots used and free.
     */
    private static CompoundTag stock(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        net.minecraft.core.BlockPos door = Storehouses.doorFor(level, id);
        java.util.Map<String, long[]> by = new java.util.LinkedHashMap<>();       // n, stacks, storehouse, chests, on the way, marked
        java.util.Map<String, net.minecraft.world.item.ItemStack> kind = new java.util.HashMap<>();
        int slotsUsed = 0, slotsAll = 0;
        java.util.function.BiConsumer<net.minecraft.world.Container, Integer> count = (c, where) -> {
            for (int i = 0; i < c.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                String key = Economy.id(s);
                long[] r = by.computeIfAbsent(key, k -> new long[6]);
                kind.putIfAbsent(key, s.copyWithCount(1));
                if (where < 2) { r[0] += s.getCount(); r[1]++; }
                r[2 + where] += s.getCount();
                if (where < 2 && !s.getComponentsPatch().isEmpty()) r[5] += s.getCount();
            }
        };
        for (net.minecraft.core.BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            boolean house = p.equals(door);
            if (house) {
                slotsAll += c.getContainerSize();
                for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) slotsUsed++;
            }
            count.accept(c, house ? 0 : 1);
        }
        for (long at : VillageFolkEntity.productionChests(id)) {
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(at);
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof net.minecraft.world.Container c) count.accept(c, 2);
        }
        // What the village keeps back of its plain stacks, and why; and what its next age wants.
        java.util.Map<net.minecraft.world.item.Item, String> why = new java.util.HashMap<>();
        java.util.Map<net.minecraft.world.item.Item, int[]> book = Bench.keepBook(level, v, why);
        List<java.util.function.Predicate<net.minecraft.world.item.ItemStack>> wanted = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, id)) {
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> p = forTask(n.task());
            if (p != null) wanted.add(p);
        }
        ListTag rows = new ListTag();
        long total = 0;
        double worth = 0;
        for (java.util.Map.Entry<String, long[]> e : by.entrySet()) {
            long[] r = e.getValue();
            net.minecraft.world.item.ItemStack one = kind.get(e.getKey());
            CompoundTag c = new CompoundTag();
            c.putString("id", e.getKey());
            Economy.Kind k = Economy.kindOf(one);
            c.putString("kind", k == null ? "other" : k.name().toLowerCase(Locale.ROOT));
            c.putLong("n", r[0]);
            c.putLong("stacks", r[1]);
            c.putLong("house", r[2]);
            c.putLong("chests", r[3]);
            c.putLong("way", r[4]);
            c.putLong("marked", r[5]);
            double each = Wealth.value(one);
            c.putInt("each100", (int) Math.round(each * 100));
            int[] kb = book.get(one.getItem());
            int kept = kb == null ? 0 : Math.max(0, kb[0] - kb[1]);
            c.putInt("kept", kept);
            c.putString("why", kept > 0 ? why.getOrDefault(one.getItem(), "") : "");
            boolean want = false;
            for (java.util.function.Predicate<net.minecraft.world.item.ItemStack> p : wanted) if (p.test(one)) { want = true; break; }
            c.putBoolean("wanted", want);
            rows.add(c);
            total += r[0];
            worth += each * r[0];
        }
        CompoundTag out = new CompoundTag();
        out.put("rows", rows);
        out.putLong("total", total);
        out.putInt("kinds", by.size());
        out.putInt("worth", (int) Math.round(worth));
        out.putInt("slots_used", slotsUsed);
        out.putInt("slots_all", slotsAll);
        out.putBoolean("storehouse", door != null);
        out.putInt("chests", Math.max(0, Villages.storeChests(level, id).size() - (door != null ? 1 : 0)));
        out.putInt("work_chests", VillageFolkEntity.productionChests(id).size());
        return out;
    }

    /** The leader's profile: who, what it cares about, how long, what it promised, what the village thinks of it. */
    private static CompoundTag leader(ServerLevel level, UUID id, List<VillageFolkEntity> folk, long today) {
        CompoundTag c = new CompoundTag();
        UUID who = Villages.elder(id);
        c.putString("title", Homeland.leaderTitle(id));
        c.putString("escort", Patrols.escortLine(id));
        c.putString("line", Leader.line(id));
        c.putString("election", Elections.line(id, today));
        Values.Value m = Elections.mandate(id);
        c.putString("mandate", m == null ? "" : m.cares);
        Orders.Order o = Orders.current(id);
        c.putString("orders", o == null ? "" : o.title);
        List<String> council = new ArrayList<>();
        for (VillageFolkEntity x : Council.members(id)) council.add(x.displayNameCap());
        c.putString("council", String.join(", ", council));
        String hist = Ledger.note(id, "annals.elections");
        c.putString("elections", hist == null ? "" : hist);
        c.putLong("elected_on", Villages.electedOn(id));
        VillageFolkEntity f = null;
        for (VillageFolkEntity x : folk) if (x.getUUID().equals(who)) f = x;
        if (f == null) {
            // Nobody leads yet (a new village, or the leader just dead and nobody stood in yet).
            String name = Villages.elderName(id);
            c.putString("name", name.isEmpty() ? "Nobody yet" : name);
            if (name.isEmpty()) c.putString("line", "Nobody leads the village yet: it will look for someone to, at the board.");
            return c;
        }
        c.putString("name", f.displayNameCap());
        c.putString("trade", f.stationTask().title);
        c.putInt("level", f.veteranLevel());
        c.putString("type", Values.describe(f));
        c.putIntArray("values", Values.of(f));
        ListTag names = new ListTag();
        for (Values.Value val : Values.Value.values()) names.add(StringTag.valueOf(val.type));
        c.put("value_names", names);
        c.putString("traits", f.life().traitsLabel());
        c.putInt("age", f.bornDay() == VillageFolkEntity.UNKNOWN ? -1 : (int) Math.max(0, today - f.bornDay()));
        c.putInt("purse", f.purse());
        c.putString("partner", f.life().partnerName());
        c.putInt("children", f.life().children());
        BlockPosHolder home = new BlockPosHolder(Homes.homeOf(f));
        c.putString("home", home.pos == null ? "no house of its own" : Villages.builtAt(id, "townhall") != null
            && home.pos.equals(Villages.builtAt(id, "townhall")) ? "the leader's hall" : "a house of its own");
        c.putInt("pace", Leader.pace(id));
        c.putInt("pay", Leader.payRate(id));
        // What the village thinks of it: the share who like it, and how much on average.
        int liked = 0, counted = 0, sum = 0;
        for (VillageFolkEntity x : folk) {
            if (x == f || x.isBaby()) continue;
            int a = x.life().affinity(f.getUUID());
            sum += a;
            counted++;
            if (a >= 0) liked++;
        }
        c.putInt("approval", counted == 0 ? 100 : Math.round(liked * 100f / counted));
        c.putInt("regard", counted == 0 ? 0 : Math.round(sum / (float) counted));
        List<String> memories = new ArrayList<>();
        for (Persona.Memory mem : f.persona().memories()) {
            if (memories.size() >= 6) break;
            memories.add("Day " + mem.day() + ": " + mem.text());
        }
        c.put("memories", strings(memories));
        return c;
    }

    private record BlockPosHolder(@Nullable net.minecraft.core.BlockPos pos) {}

    private static CompoundTag homes(ServerLevel level, UUID id, List<VillageFolkEntity> folk) {
        int[] h = Homes.counts(level, id);
        CompoundTag c = new CompoundTag();
        String[] names = { "housed", "waiting", "given", "owned", "rented", "players", "empty", "for_sale",
            "saving", "rent_yesterday", "saved", "owed", "sales_yesterday", "rent_free" };
        for (int i = 0; i < names.length && i < h.length; i++) c.putInt(names[i], h[i]);
        CompoundTag report = Homes.report(level, id);
        c.put("rows", report.getList("rows", net.minecraft.nbt.Tag.TAG_COMPOUND));
        c.putInt("hand_wage", report.getInt("hand_wage"));
        c.putInt("room", Villages.housing(id));
        c.putInt("beds_made", Villages.bedsMadeUp(level, id));
        int bedded = 0;
        for (VillageFolkEntity f : folk) if (f.bedPos() != null) bedded++;
        c.putInt("bedded", bedded);
        c.putInt("folk", folk.size());
        c.putString("line", Homes.line(level, id));
        return c;
    }

    /** The figures of this moment (not yet in the books): the stores, the treasury, contentment and its reasons. */
    private static CompoundTag now(ServerLevel level, Villages.Village v, List<VillageFolkEntity> folk) {
        UUID id = v.id();
        CompoundTag c = new CompoundTag();
        int r = Villages.storesRadius(id);
        for (Villages.Task t : new Villages.Task[]{ Villages.Task.FOOD, Villages.Task.LOGS, Villages.Task.STONE, Villages.Task.COAL,
                Villages.Task.IRON, Villages.Task.DIAMOND, Villages.Task.OBSIDIAN }) {
            c.putInt(t.name().toLowerCase(Locale.ROOT), Villages.stock(level, v.centre(), t, r));
        }
        c.putInt("coins", Ledger.coins(id));
        int purses = 0;
        for (VillageFolkEntity f : folk) purses += f.purse();
        c.putInt("purses", purses);
        c.putInt("worth", Economy.worth(id));
        c.putInt("output_yesterday", Economy.yesterday(id));
        c.putInt("output_week_avg", Economy.weekAverage(id));
        Integer trend = Economy.trend(id);
        c.putInt("trend", trend == null ? 0 : trend);
        Contentment.View view = Contentment.of(level, id);
        c.putInt("content", view.score());
        c.putString("content_word", view.word());
        int rest = Math.max(0, view.score() - view.food() - view.homes() - view.mood() - view.safety() - view.amenities() - view.wages());
        int[] parts = { view.food(), view.homes(), view.mood(), view.safety(), view.amenities(), view.wages(), rest };
        c.putIntArray("content_parts", parts);
        c.putIntArray("content_max", new int[]{ 25, 20, 25, 10, 14, 5, 5 });           // what each part can come to (Contentment)
        c.put("content_good", strings(view.good()));
        c.put("content_bad", strings(view.bad()));
        c.putString("economy", Economy.line(id));
        c.putString("next", Villages.whyBuild(id, Villages.nextProject(id)));
        c.putString("set_aside", Villages.setAside(id));
        c.putString("built", String.join(", ", Villages.builtList(id)));
        c.putInt("renown", Villages.renown(id));
        Leader.Books b = Leader.books(id);
        c.putString("food_books", b == null ? "" : "stock " + b.stock() + ", in " + Math.round(b.inAvg()) + " a day, eaten "
            + Math.round(b.useAvg()) + " a day, " + String.format(Locale.ROOT, "%.1f", b.days()) + " days put by");
        c.putString("wealth", Wealth.bestPaid(id, 5));
        c.putString("open", String.valueOf(Cafe.openLine(level, id)));
        c.putString("first_day", String.valueOf(Ledger.note(id, "annals.first")));
        return c;
    }

    /**
     * The village as a society: how old its folk are (in years, as folk count them), how its money is
     * spread among them (the Gini of the purses, the median, the richest tenth's share, the richest),
     * their natures and traits, how they feel, how skilled they are and the best hand at each trade,
     * its couples, households and friendships, and who is best liked.
     */
    private static CompoundTag society(UUID id, List<VillageFolkEntity> folk) {
        CompoundTag c = new CompoundTag();
        int[] ages = new int[10], moods = new int[5], levels = new int[6];
        Map<String, Integer> natures = new TreeMap<>(), traits = new TreeMap<>();
        List<VillageFolkEntity> grown = new ArrayList<>();
        int old = 0, partnered = 0, children = 0, friends = 0, rivals = 0;
        for (VillageFolkEntity f : folk) {
            ages[Math.max(0, Math.min(9, f.ageYears() / 10))]++;
            moods[Math.max(0, Math.min(4, f.persona().mood() / 20))]++;
            friends += f.life().friends().size();
            rivals += f.life().rivals().size();
            if (f.isBaby()) { children++; continue; }
            grown.add(f);
            if (f.isOld()) old++;
            if (f.life().partner() != null) partnered++;
            int lv = f.veteranLevel();
            levels[lv < 5 ? 0 : lv < 10 ? 1 : lv < 15 ? 2 : lv < 20 ? 3 : lv < 30 ? 4 : 5]++;
            natures.merge(Values.type(f), 1, Integer::sum);
            for (Social.Trait t : f.life().traits()) traits.merge(t.title(), 1, Integer::sum);
        }
        c.putIntArray("ages", ages);
        c.putIntArray("moods", moods);
        c.putIntArray("levels", levels);
        c.put("natures", counts(natures));
        c.put("traits", counts(traits));
        c.putInt("old", old);
        c.putInt("children", children);
        c.putInt("grown", grown.size());
        c.putInt("couples", partnered / 2);
        c.putInt("single", grown.size() - partnered);
        c.putInt("friendships", friends / 2);
        c.putInt("rivalries", rivals / 2);
        // How the money is spread: the purses of the grown, poorest first.
        List<VillageFolkEntity> byPurse = new ArrayList<>(grown);
        byPurse.sort((a, b) -> Integer.compare(Math.max(0, a.purse()), Math.max(0, b.purse())));
        int n = byPurse.size();
        long total = 0, weighted = 0;
        for (int i = 0; i < n; i++) {
            int x = Math.max(0, byPurse.get(i).purse());
            total += x;
            weighted += (long) (2 * (i + 1) - n - 1) * x;
        }
        c.putInt("gini", n == 0 || total == 0 ? 0 : (int) Math.round(weighted * 100.0 / ((double) n * total)));
        c.putInt("median", n == 0 ? 0 : Math.max(0, byPurse.get(n / 2).purse()));
        long top = 0, bottom = 0;
        int tenth = Math.max(1, (int) Math.ceil(n / 10.0));
        for (int i = 0; i < n; i++) {
            int x = Math.max(0, byPurse.get(i).purse());
            if (i >= n - tenth) top += x;
            if (i < n / 2) bottom += x;
        }
        c.putInt("top_tenth", total == 0 ? 0 : (int) Math.round(top * 100.0 / total));
        c.putInt("bottom_half", total == 0 ? 0 : (int) Math.round(bottom * 100.0 / total));
        List<String> richest = new ArrayList<>();
        for (int i = n - 1; i >= Math.max(0, n - 5); i--) {
            VillageFolkEntity f = byPurse.get(i);
            richest.add(f.displayNameCap() + "|" + f.stationTask().title + "|" + f.purse());
        }
        c.put("richest", strings(richest));
        // The best hand at each trade.
        Map<StationTask, VillageFolkEntity> best = new EnumMap<>(StationTask.class);
        for (VillageFolkEntity f : grown) {
            if (f.stationTask() == StationTask.NONE) continue;
            VillageFolkEntity b = best.get(f.stationTask());
            if (b == null || f.veteranLevel() > b.veteranLevel()) best.put(f.stationTask(), f);
        }
        List<String> masters = new ArrayList<>();
        for (Map.Entry<StationTask, VillageFolkEntity> e : best.entrySet()) {
            masters.add(e.getKey().title + "|" + e.getValue().displayNameCap() + "|" + e.getValue().veteranLevel());
        }
        c.put("masters", strings(masters));
        // Who is best liked: the warmth the others feel for each, on average.
        List<String> liked = new ArrayList<>();
        if (grown.size() >= 3) {
            List<Object[]> warmth = new ArrayList<>();
            for (VillageFolkEntity f : grown) {
                int sum = 0, counted = 0;
                for (VillageFolkEntity o : grown) {
                    if (o == f) continue;
                    sum += o.life().affinity(f.getUUID());
                    counted++;
                }
                warmth.add(new Object[]{ f.displayNameCap(), counted == 0 ? 0 : Math.round(sum / (float) counted) });
            }
            warmth.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
            for (int i = 0; i < Math.min(5, warmth.size()); i++) liked.add(warmth.get(i)[0] + "|" + warmth.get(i)[1]);
        }
        c.put("liked", strings(liked));
        // Households: how many, how big.
        int households = 0, members = 0, largest = 0;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (h.members.isEmpty()) continue;
            households++;
            members += h.members.size();
            largest = Math.max(largest, h.members.size());
        }
        c.putInt("households", households);
        c.putInt("household_avg10", households == 0 ? 0 : Math.round(members * 10f / households));
        c.putInt("household_max", largest);
        return c;
    }

    /** Every village in the world, biggest first, with this one marked: folk, age, worth, buildings, and its terms with this one. */
    private static ListTag league(ServerLevel level, Villages.Village here) {
        List<Villages.Village> all = new ArrayList<>();
        for (Villages.Village o : Villages.every()) if (o.dim().equals(level.dimension())) all.add(o);
        all.sort((a, b) -> Integer.compare(Villages.headcount(b.id()), Villages.headcount(a.id())));
        ListTag out = new ListTag();
        for (Villages.Village o : all) {
            if (out.size() >= 24) break;
            CompoundTag c = new CompoundTag();
            c.putString("name", Villages.name(o.id()));
            c.putInt("folk", Villages.headcount(o.id()));
            c.putString("age", Villages.ageOf(o.id()).label);
            c.putInt("worth", Math.max(0, Economy.worth(o.id())));
            c.putInt("buildings", Ledger.buildings(o.id()).size());
            int dx = o.centre().getX() - here.centre().getX(), dz = o.centre().getZ() - here.centre().getZ();
            c.putInt("dist", (int) Math.round(Math.sqrt(dx * (double) dx + dz * (double) dz)));
            c.putString("dir", compass(dx, dz));
            boolean self = o.id().equals(here.id());
            c.putBoolean("self", self);
            c.putString("terms", self ? "" : Diplomacy.terms(here.id(), o.id()).words);
            out.add(c);
        }
        return out;
    }

    private static CompoundTag counts(Map<String, Integer> m) {
        CompoundTag c = new CompoundTag();
        for (Map.Entry<String, Integer> e : m.entrySet()) c.putInt(e.getKey(), e.getValue());
        return c;
    }

    /** Every building: what, where from the heart, its storeys, whether it is going up, how furnished, who lives there. */
    private static ListTag buildings(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Map<Long, Homes.Home> homes = Homes.homes(id);
        ListTag out = new ListTag();
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (out.size() >= 300) break;
            CompoundTag c = new CompoundTag();
            c.putString("kind", b.structure());
            c.putString("title", TownLife.title(b.structure()).replaceFirst("^The ", ""));
            int dx = b.anchor().getX() - v.centre().getX(), dz = b.anchor().getZ() - v.centre().getZ();
            c.putInt("dist", (int) Math.round(Math.sqrt(dx * (double) dx + dz * (double) dz)));
            c.putString("dir", compass(dx, dz));
            c.putInt("storeys", Ledger.grown(id, b.anchor()) || Grow.tall(id, b.anchor()) ? 2 : 1);
            c.putBoolean("raising", Grow.raisingNow(id, b.anchor()));
            int[] fur = Interiors.progress(level, id, b);
            c.putInt("furnished", fur[0]);
            c.putInt("furnish_of", fur[1]);
            Homes.Home h = homes.get(b.anchor().asLong());
            if (h != null) {
                c.putInt("living", h.members.size());
                c.putString("tenure", Homes.seat(h) ? "the leader's" : h.members.isEmpty() && h.tenure != Homes.Tenure.PLAYER ? "empty" : h.tenure.word);
                if (h.price > 0) c.putInt("price", h.price);
            }
            out.add(c);
        }
        return out;
    }

    private static String compass(int dx, int dz) {
        if (Math.abs(dx) < 4 && Math.abs(dz) < 4) return "at the heart";
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return names[(int) Math.floorMod(Math.round(a / 45.0), 8)];
    }

    private static List<String> news(UUID id) {
        List<com.jrpetty.mcassistant.village.Chronicle.Entry> all = com.jrpetty.mcassistant.village.Chronicle.of(id);
        List<String> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < 30; i--) out.add("Day " + all.get(i).day() + ": " + all.get(i).text());
        return out;
    }

    private static List<String> needs(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, id)) out.add(n.what());
        return out;
    }

    private static List<String> neighbours(UUID id) {
        List<String> out = new ArrayList<>();
        String n = Diplomacy.status(id);
        if (n != null) for (String part : n.split(";")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    // ------------------------------------------------------------------ what drives it

    /**
     * What is making the village grow, and what is holding it back, read from its books: each line
     * marked + (driving growth), - (holding it back), = (holding steady) or ! (what would help most).
     */
    static List<String> drivers(ServerLevel level, Villages.Village v, List<VillageFolkEntity> folk, List<Day> days) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        if (days.isEmpty()) {
            out.add("=The town's books are written each morning: come back tomorrow for the first of them.");
            return out;
        }
        Day last = days.get(days.size() - 1);
        int window = Math.min(7, days.size());
        Day then = days.get(days.size() - window);
        int pop = last.values()[key("pop")], popThen = then.values()[key("pop")];
        int born = 0, died = 0, in = 0, gone = 0;
        for (int i = days.size() - window; i < days.size(); i++) {
            born += days.get(i).values()[key("born")];
            died += days.get(i).values()[key("died")];
            in += days.get(i).values()[key("moved_in")];
            gone += days.get(i).values()[key("moved_out")];
        }
        int dp = pop - popThen;
        String span = window <= 1 ? "since yesterday" : "over the last " + window + " days";
        out.add((dp > 0 ? "+" : dp < 0 ? "-" : "=") + "Population " + (dp > 0 ? "up " + dp : dp < 0 ? "down " + (-dp) : "steady") + " " + span
            + " (" + popThen + " to " + pop + "): " + born + " born, " + died + " died, " + in + " came, " + gone + " left.");
        // Room: births wait on beds.
        int room = Villages.housing(id);
        int everyone = folk.size();
        if (everyone >= room) out.add("-Every bed is taken (" + everyone + " folk, room for " + room + "): no child is born until there is a house for it. Houses come first.");
        else out.add("+Room for " + (room - everyone) + " more (" + room + " beds made up): the village can grow.");
        // Food: births wait on the larder too.
        Leader.Books b = Leader.books(id);
        if (b != null) {
            double net = b.useAvg() - b.inAvg();
            if (b.days() < 1.5) out.add("-Food is short: " + String.format(Locale.ROOT, "%.1f", b.days()) + " days put by, " + Math.round(b.inAvg())
                + " meals in a day against " + Math.round(b.useAvg()) + " eaten. No children are raised on an empty larder.");
            else if (net > 0 && b.inAvg() < b.useAvg() * 0.9) out.add("-More eaten than grown: " + Math.round(b.inAvg()) + " meals in a day against "
                + Math.round(b.useAvg()) + " eaten; the " + String.format(Locale.ROOT, "%.1f", b.days()) + " days put by are gone in about "
                + Math.round(b.stock() / net) + " at this rate. More hands to the fields and the water.");
            else out.add("+The larder is full enough: " + String.format(Locale.ROOT, "%.1f", b.days()) + " days put by, "
                + Math.round(b.inAvg()) + " meals in a day against " + Math.round(b.useAvg()) + " eaten.");
        }
        // Output: up or down, and which trades moved it.
        int outNow = sum(days, key("output"), days.size() - window, days.size());
        int prevFrom = Math.max(0, days.size() - 2 * window), prevTo = days.size() - window;
        if (prevTo - prevFrom >= 2) {
            int outBefore = sum(days, key("output"), prevFrom, prevTo);
            double a = outNow / (double) window, bb = outBefore / (double) (prevTo - prevFrom);
            if (bb > 0) {
                int pct = (int) Math.round((a - bb) * 100.0 / bb);
                Map<String, Double> change = new HashMap<>();
                for (int i = days.size() - window; i < days.size(); i++) {
                    for (var e : days.get(i).made().entrySet()) change.merge(e.getKey(), e.getValue() / (double) window, Double::sum);
                }
                for (int i = prevFrom; i < prevTo; i++) {
                    for (var e : days.get(i).made().entrySet()) change.merge(e.getKey(), -e.getValue() / (double) (prevTo - prevFrom), Double::sum);
                }
                List<Map.Entry<String, Double>> moves = new ArrayList<>(change.entrySet());
                moves.sort((x, y) -> Double.compare(Math.abs(y.getValue()), Math.abs(x.getValue())));
                List<String> who = new ArrayList<>();
                for (int i = 0; i < Math.min(3, moves.size()); i++) {
                    long dv = Math.round(moves.get(i).getValue());
                    if (dv == 0) continue;
                    who.add(title(moves.get(i).getKey()) + (dv > 0 ? " +" : " ") + dv);
                }
                out.add((pct >= 3 ? "+" : pct <= -3 ? "-" : "=") + "Output " + (pct >= 3 ? "up " + pct + "%" : pct <= -3 ? "down " + (-pct) + "%" : "holding steady")
                    + ": " + Math.round(a) + " coins' worth a day against " + Math.round(bb) + " the days before"
                    + (who.isEmpty() ? "." : ", mostly " + String.join(", ", who) + " a day."));
            }
        }
        // What it makes, item by item: more or less than the week before, and the most of it.
        List<ItemDay> idays = itemDays(id);
        if (idays.size() >= 2) {
            int iw = Math.min(7, idays.size());
            Map<String, Integer> lately = new HashMap<>();
            long now = 0, before = 0;
            for (int i = idays.size() - iw; i < idays.size(); i++) {
                for (Map.Entry<String, int[]> e : idays.get(i).items().entrySet()) {
                    now += e.getValue()[0];
                    lately.merge(e.getKey(), e.getValue()[0], Integer::sum);
                }
            }
            int bFrom = Math.max(0, idays.size() - 2 * iw), bTo = idays.size() - iw;
            for (int i = bFrom; i < bTo; i++) for (int[] c : idays.get(i).items().values()) before += c[0];
            List<Map.Entry<String, Integer>> most = new ArrayList<>(lately.entrySet());
            most.sort((p, q) -> q.getValue() - p.getValue());
            List<String> top = new ArrayList<>();
            for (int i = 0; i < Math.min(3, most.size()); i++) {
                if (most.get(i).getValue() <= 0) break;
                top.add(new net.minecraft.world.item.ItemStack(itemOf(most.get(i).getKey())).getHoverName().getString().toLowerCase(Locale.ROOT)
                    + " " + Math.round(most.get(i).getValue() / (double) iw));
            }
            double perDay = now / (double) iw, was = bTo - bFrom > 0 ? before / (double) (bTo - bFrom) : 0;
            int pct = was > 0 ? (int) Math.round((perDay - was) * 100 / was) : 0;
            out.add((was <= 0 || Math.abs(pct) < 5 ? "=" : pct > 0 ? "+" : "-") + "Making " + Math.round(perDay) + " things a day"
                + (was > 0 ? (Math.abs(pct) < 5 ? ", as the week before" : (pct > 0 ? ", up " : ", down ") + Math.abs(pct) + "% on the week before") : "")
                + (top.isEmpty() ? "." : "; most of all " + String.join(", ", top) + " a day."));
        }
        // The best and the worst trades for their hands.
        Map<String, Integer> week = new HashMap<>();
        for (int i = days.size() - window; i < days.size(); i++) for (var e : days.get(i).made().entrySet()) week.merge(e.getKey(), e.getValue(), Integer::sum);
        Map<String, Integer> hands = last.hands();
        String best = null, worst = null;
        double bestPer = -1, worstPer = Double.MAX_VALUE;
        for (Map.Entry<String, Integer> e : hands.entrySet()) {
            if (e.getValue() <= 0) continue;
            double per = week.getOrDefault(e.getKey(), 0) / (double) window / e.getValue();
            if (per > bestPer) { bestPer = per; best = e.getKey(); }
            if (per < worstPer && week.getOrDefault(e.getKey(), 0) > 0) { worstPer = per; worst = e.getKey(); }
        }
        if (best != null) out.add("+Most made per hand: " + title(best) + ", " + Math.round(bestPer) + (Math.round(bestPer) == 1 ? " coin's" : " coins'") + " worth a day each ("
            + hands.get(best) + " at it).");
        String topTrade = null;
        int topWeek = 0, total = 0;
        for (var e : week.entrySet()) { total += e.getValue(); if (e.getValue() > topWeek) { topWeek = e.getValue(); topTrade = e.getKey(); } }
        if (topTrade != null && total > 0) out.add("+The biggest earner: " + title(topTrade) + ", " + Math.round(topWeek * 100.0 / total)
            + "% of all the village made " + span + ".");
        if (worst != null && !worst.equals(best) && worstPer < bestPer / 3) out.add("=Least made per hand: " + title(worst) + ", "
            + Math.round(worstPer) + " a day each.");
        // Idle hands.
        int idle = last.values()[key("idle")], adults = Math.max(1, last.values()[key("adults")]);
        if (idle * 5 > adults) out.add("-" + idle + " of " + adults + " grown folk have no trade yet: hands the village is not using.");
        // Money: wages against what comes in.
        int takings = sum(days, key("takings"), days.size() - window, days.size()) + sum(days, key("sold"), days.size() - window, days.size())
            + sum(days, key("tithe"), days.size() - window, days.size()) + sum(days, key("rent"), days.size() - window, days.size())
            + sum(days, key("house_sales"), days.size() - window, days.size());
        int wages = sum(days, key("wages"), days.size() - window, days.size()) + sum(days, key("spent"), days.size() - window, days.size());
        int coinsNow = last.values()[key("coins")], coinsThen = then.values()[key("coins")];
        out.add((takings >= wages ? "+" : "-") + "Money " + span + ": " + takings + " in, " + wages + " out (wages and buying in); the treasury "
            + (coinsNow >= coinsThen ? "up " + (coinsNow - coinsThen) : "down " + (coinsThen - coinsNow)) + " to " + coinsNow + ".");
        int worthNow = last.values()[key("worth")], worthThen = then.values()[key("worth")];
        if (worthThen > 0) out.add((worthNow >= worthThen ? "+" : "-") + "Worth " + (worthNow >= worthThen ? "up " : "down ")
            + Math.abs(worthNow - worthThen) + " to " + worthNow + " (the treasury, the stores and the purses).");
        // Contentment.
        int content = Contentment.score(id);
        Contentment.View view = Contentment.of(level, id);
        if (content < 40) out.add("-The folk are unhappy (" + content + "/100: " + String.join(", ", view.bad()) + "): unhappy folk work slower and some leave.");
        else if (content >= 70) out.add("+The folk are content (" + content + "/100): they work faster and stay.");
        else out.add("=Contentment " + content + "/100" + (view.bad().isEmpty() ? "." : ": " + String.join(", ", view.bad()) + "."));
        // The age, and what stands between it and the next.
        List<Villages.Need> needs = Villages.needs(level, id);
        if (!needs.isEmpty()) {
            List<String> short_ = new ArrayList<>();
            for (int i = 0; i < Math.min(4, needs.size()); i++) short_.add(needs.get(i).what());
            out.add("-Short of, for the next age: " + String.join("; ", short_) + ".");
        }
        String aside = Villages.setAside(id);
        if (!aside.isEmpty()) out.add("-Set aside: " + aside + ".");
        // What would help most.
        if (everyone >= room) out.add("!Most help now: houses. Every bed is full.");
        else if (b != null && (b.days() < 1.5 || b.inAvg() < b.useAvg() * 0.7)) out.add("!Most help now: food. More hands on the fields and the water.");
        else if (idle * 5 > adults) out.add("!Most help now: work for the idle hands (a workplace for a new trade).");
        else if (!needs.isEmpty()) out.add("!Most help now: " + needs.get(0).what() + ".");
        else out.add("!Nothing holding it back: it grows as fast as its children are born.");
        return out;
    }

    private static int sum(List<Day> days, int k, int from, int to) {
        int s = 0;
        for (int i = Math.max(0, from); i < Math.min(days.size(), to); i++) s += days.get(i).values()[k];
        return s;
    }

    private static String title(String trade) {
        try {
            return StationTask.valueOf(trade).title.toLowerCase(Locale.ROOT) + "s";
        } catch (IllegalArgumentException e) {
            return trade.toLowerCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the days written down, and the numbers of the last. */
    public static int daysForTests(UUID village) {
        return days(village).size();
    }

    public static int lastForTests(UUID village, String key) {
        List<Day> d = days(village);
        return d.isEmpty() ? -1 : d.get(d.size() - 1).values()[key(key)];
    }

    public static List<String> driversForTests(ServerLevel level, Villages.Village v) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f) folk.add(f);
        return drivers(level, v, folk, days(v.id()));
    }
}
