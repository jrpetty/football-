package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Village Storehouse kept: its storekeeper at the counter, and its books.
 * <ul>
 * <li><b>The counter.</b> A folk that comes to the storehouse for something (WithdrawGoal), or draws
 *     on the stores from wherever it is (AssistantEntity.drawFrom), is served by the storekeeper when
 *     one is on duty there — awake, on shift, not on its break, and at the storehouse: a moment's
 *     service at the counter for one who walked in, a swing of the arm, and a word ("Sixteen torches
 *     for you, Holt."). With nobody at the counter (no storekeeper, or asleep, or out), folk serve
 *     themselves as they always have, so the village never waits on one pair of hands.</li>
 * <li><b>The books.</b> A day's books, turned over each morning: what went in and what came out, by
 *     item; who brought it and who took it; the requests the storekeeper served and the ones folk
 *     served themselves; the couriers' runs and what they carried (Couriers); and how the last tidy
 *     went — the slots used before and after, the stacks it merged, the slots free.</li>
 * <li><b>The tidy.</b> The storekeeper sets the storehouse in order often while it is on duty, and
 *     at once after a big delivery (VillageFolkEntity.tidyTheStorehouse).</li>
 * </ul>
 * Shown on the town's books (the Stores page: Annals.snapshot "storehouse") and by /village stores.
 * The books are kept in memory for the day; a server restart starts the day's books afresh.
 */
public final class Storekeeping {

    private Storekeeping() {}

    /** How long the storekeeper takes to hand a request over at the counter (ticks). */
    public static final int SERVICE_TICKS = 30;
    /** How far from the storehouse's door a storekeeper is still at its counter. */
    static final int COUNTER = 12;
    /** Goods in since the last tidy that call for another tidy at once: a big delivery. */
    static final int BIG_DELIVERY = 384;
    /** Entries kept in the day's running list. */
    private static final int LINES = 12;

    /** A day of the storehouse's books. */
    static final class Day {
        final long day;
        /** By item (Stockroom.key): {in, out}. */
        final Map<String, int[]> items = new LinkedHashMap<>();
        /** By folk (its name): {brought in, took out}. */
        final Map<String, int[]> folk = new LinkedHashMap<>();
        int served, self, runs, runGoods, deliveries;
        final ArrayDeque<String> lines = new ArrayDeque<>();

        Day(long day) {
            this.day = day;
        }

        int in() {
            int n = 0;
            for (int[] v : items.values()) n += v[0];
            return n;
        }

        int out() {
            int n = 0;
            for (int[] v : items.values()) n += v[1];
            return n;
        }

        void line(String s) {
            lines.addLast(s);
            while (lines.size() > LINES) lines.removeFirst();
        }
    }

    /** The last tidy: when, by whom, the slots used before and after, the slots there are, and the
     *  stacks merged in the store chests round about. */
    record Tidy(long day, long tick, String by, int before, int after, int slots, int chestMerges) {
        int merged() {
            return Math.max(0, before - after) + chestMerges;
        }
    }

    private static final Map<UUID, Day> TODAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Tidy> TIDY = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SINCE_TIDY = new ConcurrentHashMap<>();
    /** When each storekeeper last said something at the counter (so a burst of draws is one word). */
    private static final Map<UUID, Long> SPOKE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TODAY.clear();
        TIDY.clear();
        SINCE_TIDY.clear();
        SPOKE.clear();
    }

    static Day day(ServerLevel level, UUID village) {
        long today = level.getDayTime() / 24000L;
        Day d = TODAY.get(village);
        if (d == null || d.day != today) {
            d = new Day(today);
            TODAY.put(village, d);
        }
        return d;
    }

    // ------------------------------------------------------------------ duty

    /**
     * The storekeeper at its counter: a storekeeper of the village, grown, awake, on shift and not
     * on its break, standing at the storehouse. Null with nobody there — folk serve themselves.
     */
    @Nullable
    public static VillageFolkEntity onDuty(ServerLevel level, @Nullable UUID village) {
        if (village == null) return null;
        BlockPos door = Storehouses.doorFor(level, village);
        if (door == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.STORE) continue;
            if (!f.isAlive() || f.isBaby() || f.isSleeping() || f.offWorkNow()) continue;
            if (f.distanceToSqr(door.getX() + 0.5, door.getY(), door.getZ() + 0.5) > COUNTER * COUNTER) continue;
            return f;
        }
        return null;
    }

    /** The village's storekeeper, on duty or not (the first, if it has more than one). */
    @Nullable
    public static VillageFolkEntity keeper(@Nullable UUID village) {
        if (village == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.STORE && f.isAlive() && !f.isBaby()) return f;
        }
        return null;
    }

    /** Who serves this folk at the counter: the storekeeper on duty, unless that is this folk itself. */
    @Nullable
    public static VillageFolkEntity counterFor(VillageFolkEntity folk) {
        if (!(folk.level() instanceof ServerLevel level)) return null;
        VillageFolkEntity k = onDuty(level, folk.ownerId());
        return k == folk ? null : k;
    }

    // ------------------------------------------------------------------ the books: in

    /** Into the storehouse: one lot, booked to who brought it (null: the stores' own dealings). */
    public static void bookIn(ServerLevel level, UUID village, @Nullable String who, ItemStack what, int n, boolean courier) {
        if (village == null || what.isEmpty() || n <= 0) return;
        bookIn(level, village, who, List.of(what.copyWithCount(n)), courier);
    }

    /** Into the storehouse: a load, booked to who brought it. A courier's load is a delivery. */
    public static void bookIn(ServerLevel level, UUID village, @Nullable String who, List<ItemStack> lots, boolean courier) {
        if (village == null || lots.isEmpty()) return;
        Day d = day(level, village);
        int n = 0;
        for (ItemStack s : lots) {
            if (s.isEmpty()) continue;
            d.items.computeIfAbsent(Stockroom.key(s), k -> new int[2])[0] += s.getCount();
            n += s.getCount();
        }
        if (n <= 0) return;
        if (who != null) d.folk.computeIfAbsent(who, k -> new int[2])[0] += n;
        if (courier) d.deliveries++;
        SINCE_TIDY.merge(village, n, Integer::sum);
        if (who != null) d.line(time(level) + " " + who + (courier ? " delivered " : " brought in ") + list(lots));
        // A courier's load taken in at the counter, when the storekeeper is there to take it.
        VillageFolkEntity keeper = courier && who != null ? onDuty(level, village) : null;
        if (keeper != null && !who.equals(keeper.displayNameCap())) {
            keeper.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            long now = level.getGameTime();
            Long last = SPOKE.get(keeper.getUUID());
            if ((last == null || now - last >= 200L || now < last) && level.getRandom().nextInt(2) == 0) {
                SPOKE.put(keeper.getUUID(), now);
                FolkTalk.speak(keeper, FolkTalk.pick(level.getRandom(), capFirst(list(lots)) + " in. Thank you, " + who + ".",
                    "In the book: " + list(lots) + ". Good work, " + who + "."));
            }
        }
    }

    // ------------------------------------------------------------------ the books: out

    /** Out of the storehouse, booked to who took it (null: the town's own works), and who served it. */
    public static void bookOut(ServerLevel level, UUID village, @Nullable String who, ItemStack what, int n,
                               @Nullable VillageFolkEntity servedBy) {
        if (village == null || what.isEmpty() || n <= 0) return;
        Day d = day(level, village);
        d.items.computeIfAbsent(Stockroom.key(what), k -> new int[2])[1] += n;
        if (who != null) d.folk.computeIfAbsent(who, k -> new int[2])[1] += n;
    }

    /**
     * A folk drew on the stores from wherever it was (drawFrom) and some of it came out of the
     * storehouse: one request. Served by the storekeeper if one is at the counter — it hands it over
     * and says so — or by the folk itself.
     */
    public static void handedOut(VillageFolkEntity folk, List<ItemStack> lots) {
        if (!(folk.level() instanceof ServerLevel level) || folk.ownerId() == null || lots.isEmpty()) return;
        request(level, folk, counterFor(folk), lots, 60);
    }

    /** A folk came to the storehouse for something (WithdrawGoal) and took it: one request, served by
     *  {@code keeper} (it has had its moment at the counter) or by the folk itself. */
    public static void withdrew(VillageFolkEntity folk, @Nullable VillageFolkEntity keeper, List<ItemStack> lots) {
        if (!(folk.level() instanceof ServerLevel level) || folk.ownerId() == null || lots.isEmpty()) return;
        request(level, folk, keeper, lots, 10);
    }

    private static void request(ServerLevel level, VillageFolkEntity folk, @Nullable VillageFolkEntity keeper,
                                List<ItemStack> lots, int quiet) {
        UUID village = folk.ownerId();
        Day d = day(level, village);
        String who = folk.displayNameCap();
        for (ItemStack s : lots) bookOut(level, village, who, s, s.getCount(), keeper);
        if (keeper != null) {
            d.served++;
            d.line(time(level) + " " + keeper.displayNameCap() + " served " + who + " " + list(lots));
            keeper.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            boolean near = keeper.distanceToSqr(folk) < 16.0 * 16.0;
            if (near) keeper.getLookControl().setLookAt(folk, 30.0F, 30.0F);
            long now = level.getGameTime();
            Long last = SPOKE.get(keeper.getUUID());
            if (last == null || now - last >= quiet || now < last) {
                SPOKE.put(keeper.getUUID(), now);
                // Handed over across the counter, or (drawn from out on a plot) booked out to it.
                FolkTalk.speak(keeper, near ? handOverLine(level, lots, folk.displayNameCap())
                    : capFirst(list(lots)) + " out to " + folk.displayNameCap() + ". In the book it goes.");
            }
        } else {
            d.self++;
            d.line(time(level) + " " + who + " helped itself to " + list(lots));
        }
    }

    /** "Sixteen torches for you, Holt." — what the storekeeper says as it hands a request over. */
    static String handOverLine(ServerLevel level, List<ItemStack> lots, String name) {
        String what = list(lots);
        String said = capFirst(what);
        return switch (level.getRandom().nextInt(3)) {
            case 0 -> said + " for you, " + name + ".";
            case 1 -> "There you are, " + name + " — " + what + ".";
            default -> said + ". Mind how you carry them, " + name + ".";
        };
    }

    // ------------------------------------------------------------------ the couriers' runs

    /** A courier's run done, booked: who ran it, what it was, how much it carried, who sent it. */
    static void bookRun(ServerLevel level, UUID village, String courier, String what, int goods, String sentBy) {
        Day d = day(level, village);
        d.runs++;
        d.runGoods += Math.max(0, goods);
        d.line(time(level) + " run: " + courier + " — " + what + (goods > 0 ? " (" + goods + " carried)" : "")
            + (sentBy.isEmpty() ? "" : ", sent by " + sentBy));
    }

    // ------------------------------------------------------------------ the tidy

    /** The storekeeper has just tidied: the storehouse's slots before and after, and the store chests' merges. */
    static void tidied(ServerLevel level, UUID village, String by, int before, int after, int slots, int chestMerges) {
        Tidy t = new Tidy(level.getDayTime() / 24000L, level.getGameTime(), by, before, after, slots, chestMerges);
        TIDY.put(village, t);
        SINCE_TIDY.put(village, 0);
        day(level, village).line(time(level) + " " + by + " tidied the storehouse: " + before + " slots to " + after
            + " (" + t.merged() + " stacks merged)");
    }

    /** Has so much come in since the last tidy that the storehouse wants another now? */
    static boolean bigDeliverySinceTidy(UUID village) {
        return SINCE_TIDY.getOrDefault(village, 0) >= BIG_DELIVERY;
    }

    /** Tests: the last tidy, as {before, after, merged, slots}, or null. */
    @Nullable
    public static int[] lastTidyForTests(UUID village) {
        Tidy t = TIDY.get(village);
        return t == null ? null : new int[]{ t.before(), t.after(), t.merged(), t.slots() };
    }

    /** Tests: today's requests, as {served by the storekeeper, served themselves}, and the runs booked. */
    public static int[] requestsForTests(ServerLevel level, UUID village) {
        Day d = day(level, village);
        return new int[]{ d.served, d.self, d.runs, d.runGoods };
    }

    /** Tests: today's in and out of one item. */
    public static int[] itemForTests(ServerLevel level, UUID village, ItemStack s) {
        int[] v = day(level, village).items.get(Stockroom.key(s));
        return v == null ? new int[2] : v.clone();
    }

    // ------------------------------------------------------------------ shown

    /** The storehouse's books for the town's books (the Stores page). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag t = new CompoundTag();
        StorehouseBlockEntity store = Storehouses.storeFor(level, id);
        BlockPos door = Storehouses.doorFor(level, id);
        t.putBoolean("stands", door != null);
        if (store != null) {
            int slots = store.getContainerSize(), used = store.used();
            t.putInt("slots", slots);
            t.putInt("used", used);
            t.putInt("free", slots - used);
        }
        VillageFolkEntity keeper = keeper(id);
        VillageFolkEntity duty = onDuty(level, id);
        t.putString("keeper", keeper == null ? "" : keeper.displayNameCap());
        t.putBoolean("on_duty", duty != null);
        t.putString("keeper_doing", keeper == null ? "" : duty != null ? "at the counter"
            : keeper.isSleeping() ? "asleep" : keeper.offWorkNow() ? "off work" : "away from the counter");
        Day d = day(level, id);
        t.putInt("in", d.in());
        t.putInt("out", d.out());
        t.putInt("served", d.served);
        t.putInt("self", d.self);
        t.putInt("runs", d.runs);
        t.putInt("run_goods", d.runGoods);
        t.putInt("deliveries", d.deliveries);
        List<Map.Entry<String, int[]>> items = new ArrayList<>(d.items.entrySet());
        items.sort((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        ListTag il = new ListTag();
        for (int i = 0; i < Math.min(12, items.size()); i++) {
            CompoundTag r = new CompoundTag();
            r.putString("id", items.get(i).getKey());
            r.putString("name", Stockroom.nameOf(items.get(i).getKey()));
            r.putInt("in", items.get(i).getValue()[0]);
            r.putInt("out", items.get(i).getValue()[1]);
            il.add(r);
        }
        t.put("items", il);
        List<Map.Entry<String, int[]>> folk = new ArrayList<>(d.folk.entrySet());
        folk.sort((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        ListTag fl = new ListTag();
        for (int i = 0; i < Math.min(10, folk.size()); i++) {
            CompoundTag r = new CompoundTag();
            r.putString("name", folk.get(i).getKey());
            r.putInt("brought", folk.get(i).getValue()[0]);
            r.putInt("took", folk.get(i).getValue()[1]);
            fl.add(r);
        }
        t.put("folk", fl);
        Tidy tidy = TIDY.get(id);
        if (tidy != null) {
            CompoundTag r = new CompoundTag();
            r.putString("by", tidy.by());
            r.putInt("before", tidy.before());
            r.putInt("after", tidy.after());
            r.putInt("merged", tidy.merged());
            r.putInt("slots", tidy.slots());
            r.putInt("free", tidy.slots() - tidy.after());
            r.putLong("ago", Math.max(0L, level.getGameTime() - tidy.tick()) / 20L);
            t.put("tidy", r);
        }
        ListTag ll = new ListTag();
        for (String s : d.lines) ll.add(StringTag.valueOf(s.length() > 160 ? s.substring(0, 160) : s));
        t.put("lines", ll);
        Couriers.report(level, v, t);
        Sweepers.report(level, v, t);                       // the street sweeper's day, and what lies about the town
        return t;
    }

    /** /village stores: the storehouse, its staff, its run list and the day's books, in words. */
    public static String page(ServerLevel level, Villages.Village v) {
        CompoundTag t = report(level, v);
        StringBuilder sb = new StringBuilder();
        if (!t.getBoolean("stands")) sb.append("No Village Storehouse stands yet: the stores are the chests at the heart.\n");
        else if (t.contains("slots")) sb.append("The storehouse: ").append(t.getInt("used")).append(" of ").append(t.getInt("slots"))
            .append(" slots used, ").append(t.getInt("free")).append(" free.\n");
        String keeper = t.getString("keeper");
        sb.append(keeper.isEmpty() ? "No storekeeper: folk serve themselves, and the run list sends the couriers out.\n"
            : "Storekeeper: " + keeper + ", " + t.getString("keeper_doing") + ".\n");
        sb.append("Today: ").append(t.getInt("in")).append(" in, ").append(t.getInt("out")).append(" out; requests ")
            .append(t.getInt("served")).append(" served at the counter, ").append(t.getInt("self")).append(" self-served; ")
            .append(t.getInt("runs")).append(" courier runs carrying ").append(t.getInt("run_goods")).append(".\n");
        sb.append(Sweepers.line(t)).append("\n");
        if (t.contains("tidy")) {
            CompoundTag r = t.getCompound("tidy");
            sb.append("Last tidy (").append(r.getString("by")).append(", ").append(r.getLong("ago")).append("s ago): ")
                .append(r.getInt("before")).append(" slots to ").append(r.getInt("after")).append(", ")
                .append(r.getInt("merged")).append(" stacks merged, ").append(r.getInt("free")).append(" free.\n");
        }
        ListTag staff = t.getList("staff", 10);
        if (!staff.isEmpty()) {
            sb.append("Staff:\n");
            for (int i = 0; i < staff.size(); i++) {
                CompoundTag s = staff.getCompound(i);
                sb.append("  ").append(s.getString("name")).append(" (").append(s.getString("role")).append(", ")
                    .append(s.getInt("wage")).append(" a day)");
                if (s.getString("role").equals("courier")) sb.append(": ").append(s.getInt("runs")).append(" runs, ")
                    .append(s.getInt("moved")).append(" carried");
                if (s.getInt("swept") > 0 || s.getString("role").equals("sweeper"))
                    sb.append(s.getString("role").equals("sweeper") ? ": " : ", ").append(s.getInt("swept")).append(" swept in");
                sb.append(" — ").append(s.getString("doing")).append(".\n");
            }
        }
        ListTag running = t.getList("running", 10), queued = t.getList("queued", 10);
        if (!running.isEmpty() || !queued.isEmpty()) {
            sb.append("Run list:\n");
            for (int i = 0; i < running.size(); i++) {
                CompoundTag r = running.getCompound(i);
                sb.append("  under way: ").append(r.getString("what")).append(" — ").append(r.getString("courier"))
                    .append(", ").append(r.getString("stage")).append(".\n");
            }
            for (int i = 0; i < queued.size(); i++) {
                sb.append("  waiting: ").append(queued.getCompound(i).getString("what")).append(".\n");
            }
        }
        ListTag items = t.getList("items", 10);
        if (!items.isEmpty()) {
            sb.append("By item (in/out): ");
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                CompoundTag r = items.getCompound(i);
                parts.add(r.getString("name") + " " + r.getInt("in") + "/" + r.getInt("out"));
            }
            sb.append(String.join(", ", parts)).append(".\n");
        }
        ListTag folk = t.getList("folk", 10);
        if (!folk.isEmpty()) {
            sb.append("By folk (brought/took): ");
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < folk.size(); i++) {
                CompoundTag r = folk.getCompound(i);
                parts.add(r.getString("name") + " " + r.getInt("brought") + "/" + r.getInt("took"));
            }
            sb.append(String.join(", ", parts)).append(".\n");
        }
        ListTag lines = t.getList("lines", 8);
        for (int i = Math.max(0, lines.size() - 6); i < lines.size(); i++) sb.append("  ").append(lines.getString(i)).append("\n");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ words

    /** "16 torches, 8 bread": a load in a line. */
    static String list(List<ItemStack> lots) {
        Map<String, Integer> by = new LinkedHashMap<>();
        for (ItemStack s : lots) if (!s.isEmpty()) by.merge(Stockroom.key(s), s.getCount(), Integer::sum);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : by.entrySet()) {
            if (parts.size() == 3) { parts.add("more"); break; }
            int n = e.getValue();
            parts.add(words(n) + " " + (n == 1 ? Stockroom.nameOf(e.getKey()).toLowerCase(Locale.ROOT) : Stockroom.plural(e.getKey())));
        }
        if (parts.isEmpty()) return "nothing";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static String capFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final String[] SMALL = { "no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty" };
    private static final String[] TENS = { "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };

    /** A count as a folk says it: "sixteen", "thirty-two", "a hundred and twenty". */
    static String words(int n) {
        if (n < 0) return Integer.toString(n);
        if (n <= 20) return SMALL[n];
        if (n < 100) return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + SMALL[n % 10]);
        if (n < 1000 && n % 100 == 0) return (n == 100 ? "a" : SMALL[n / 100]) + " hundred";
        return Integer.toString(n);
    }

    /** The hour of the day the books write against an entry: "08:40". */
    private static String time(ServerLevel level) {
        long t = (level.getDayTime() + 6000L) % 24000L;
        int h = (int) (t / 1000L), m = (int) ((t % 1000L) * 60L / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }
}
