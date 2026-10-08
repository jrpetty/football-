package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [diver] What the town's fires burn, kind by kind, and the rule that puts the diver's kelp on them first.
 *
 * <p>The sixty days' town ran short of coal again and again: down to nine and then none about the twentieth day, and
 * swinging between twelve and a hundred and forty after, while every furnace in the place was fed on it. A dried kelp
 * block is the game's own fuel (a furnace smelts twenty things on one, two and a half coals' worth), and a town by the
 * water can make as many as its kelp beds grow. So:
 * <ul>
 * <li><b>Kelp first.</b> While the stores hold dried kelp blocks, the smelters and the cooks burn them and no coal
 *     ({@link #kelpInStores}): the smelter's furnaces (SmeltGoal), the bench's firings for the café and the shop
 *     (Bench), the couriers' fuel out to the smeltery ({@link #drawForSmelter}). A smelter that keeps thirty-two coal
 *     in its pack banks it instead. Coal goes on the fire only when there are no kelp blocks; it is kept for the
 *     torches, the forge and the watch.</li>
 * <li><b>The books.</b> Every load put on a fire is written down by kind ({@link #burnt}): kelp blocks, coal, charcoal,
 *     wood, and the rest; by the day, a month of them kept. The Production page and the diver's trade book read it
 *     ("kelp blocks kept 400 coal in the stores this month": a block smelts twenty, a coal eight).</li>
 * </ul>
 */
public final class FuelBook {

    private FuelBook() {}

    /** A dried kelp block: the diver's fuel. */
    public static final Predicate<ItemStack> KELP_BLOCK = s -> s.is(Items.DRIED_KELP_BLOCK);
    /** Things a furnace smelts on one dried kelp block (4,000 ticks of a 200-tick firing), and on one coal. */
    public static final int KELP_SMELTS = 20, COAL_SMELTS = 8;
    /** Days of fuel books kept: a month and a little. */
    static final int KEPT_DAYS = 32;
    /** How long a look at the stores for kelp blocks holds good. */
    static final long LOOK_FOR = 600L;

    /** The kinds the books keep, in the order they are written. */
    public enum Kind {
        KELP("kelp blocks"), COAL("coal"), CHARCOAL("charcoal"), WOOD("wood"), OTHER("other fuel");

        public final String words;

        Kind(String words) { this.words = words; }

        static Kind of(ItemStack s) {
            if (s.is(Items.DRIED_KELP_BLOCK)) return KELP;
            if (s.is(Items.COAL) || s.is(Items.COAL_BLOCK)) return COAL;
            if (s.is(Items.CHARCOAL)) return CHARCOAL;
            if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(Items.STICK)) return WOOD;
            return OTHER;
        }
    }

    /** The day's books by village: {day, kelp, coal, charcoal, wood, other}. */
    private static final Map<UUID, long[]> TODAY = new ConcurrentHashMap<>();
    /** When each village's stores were last looked at for kelp blocks, and what was found: {gameTime, 0/1}. */
    private static final Map<UUID, long[]> KELP_SEEN = new ConcurrentHashMap<>();
    /** Tests: kelp in the stores as the fires see it (true, false), or the stores' own (null). */
    private static volatile Boolean kelpForTests;

    public static void resetForTests() {
        TODAY.clear();
        KELP_SEEN.clear();
        kelpForTests = null;
    }

    public static void kelpForTests(@Nullable Boolean on) {
        kelpForTests = on;
        KELP_SEEN.clear();
    }

    // ------------------------------------------------------------------ kelp first

    /** Has the town dried kelp blocks in its stores for the fires? Looked at once every half a minute. */
    public static boolean kelpInStores(ServerLevel level, @Nullable UUID village) {
        if (village == null || Villages.get(village) == null) return false;
        Boolean t = kelpForTests;
        if (t != null) return t;
        long now = level.getGameTime();
        long[] seen = KELP_SEEN.get(village);
        if (seen != null && now - seen[0] < LOOK_FOR && now >= seen[0]) return seen[1] != 0L;
        boolean any = Market.stock(level, village, KELP_BLOCK) > 0;
        KELP_SEEN.put(village, new long[]{ now, any ? 1L : 0L });
        return any;
    }

    /** The stores changed under the fires (kelp blocks banked or drawn): the next look reads them afresh. */
    public static void forget(@Nullable UUID village) {
        if (village != null) KELP_SEEN.remove(village);
    }

    /**
     * The fuel a courier carries out to the smelter with its ore: four kelp blocks (eighty firings) while the stores
     * have them, and only with none, eight coal or charcoal as before. Returns how many it took.
     */
    public static int drawForSmelter(AssistantEntity c, BlockPos heart, int radius) {
        int kelp = c.drawFrom(heart, KELP_BLOCK, 4, radius);
        if (kelp > 0) {
            forget(c.ownerId());
            return kelp;
        }
        return c.drawFrom(heart, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 8, radius);
    }

    /** Is this smelter carrying fuel it will burn now: kelp blocks, or with none in the stores, coal or wood? */
    public static boolean fuelled(VillageFolkEntity f) {
        if (f.countCarried(KELP_BLOCK) > 0) return true;
        if (f.level() instanceof ServerLevel level && kelpInStores(level, f.ownerId())) {
            return f.countCarried(s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) > 0;
        }
        return f.countCarried(AssistantEntity.SMELT_FUEL) > 0;
    }

    // ------------------------------------------------------------------ the books

    /** So much of this put on a fire by one of the town's hands (the smelter's furnaces, the diver's smoker). */
    public static void burnt(AssistantEntity a, ItemStack fuel) {
        if (a instanceof VillageFolkEntity f && f.ownerId() != null && f.level() instanceof ServerLevel level) {
            burnt(level, f.ownerId(), fuel, fuel.getCount());
        }
    }

    /** So many of this put on the town's fires. */
    public static void burnt(ServerLevel level, @Nullable UUID village, ItemStack fuel, int n) {
        if (village == null || fuel.isEmpty() || n <= 0) return;
        long day = level.getDayTime() / 24000L;
        long[] d = TODAY.get(village);
        if (d == null || d[0] != day) {
            if (d != null) keep(village, d);
            d = read(village, day);
            TODAY.put(village, d);
        }
        d[1 + Kind.of(fuel).ordinal()] += n;
        keep(village, d);
        if (Kind.of(fuel) == Kind.KELP) forget(village);
    }

    /** A bench's firings: what each fuel item went on the fire, by item (Bench.take). */
    public static void burnt(ServerLevel level, @Nullable UUID village, Map<Item, Integer> fuels) {
        for (Map.Entry<Item, Integer> e : fuels.entrySet()) burnt(level, village, new ItemStack(e.getKey()), e.getValue());
    }

    private static long[] read(UUID village, long day) {
        long[] d = new long[1 + Kind.values().length];
        d[0] = day;
        String s = Ledger.note(village, "fuel/" + day);
        if (s != null && !s.isEmpty()) {
            String[] p = s.split(",");
            for (int i = 0; i < Math.min(p.length, Kind.values().length); i++) {
                try { d[1 + i] = Long.parseLong(p[i].trim()); } catch (NumberFormatException ignored) { }
            }
        }
        return d;
    }

    private static void keep(UUID village, long[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < d.length; i++) {
            if (i > 1) sb.append(',');
            sb.append(d[i]);
        }
        Ledger.note(village, "fuel/" + d[0], sb.toString());
        Ledger.forget(village, "fuel/" + (d[0] - KEPT_DAYS));
    }

    /** What the town's fires burnt over the last so many days, by kind (today's included). */
    public static long[] lastDays(UUID village, long today, int days) {
        long[] out = new long[Kind.values().length];
        long[] now = TODAY.get(village);
        for (long day = today - days + 1; day <= today; day++) {
            long[] d = now != null && now[0] == day ? now : read(village, day);
            for (int i = 0; i < out.length; i++) out[i] += d[1 + i];
        }
        return out;
    }

    /** The coal the kelp blocks stood in for: a block smelts twenty, a coal eight. */
    public static long coalKept(long kelpBlocks) {
        return Math.round(kelpBlocks * (double) KELP_SMELTS / COAL_SMELTS);
    }

    /**
     * The Production page's word on the fires (Annals): what they burnt this month by kind, and what the kelp kept;
     * empty for a town whose fires have burnt nothing written down.
     */
    public static List<String> lines(ServerLevel level, UUID village) {
        long today = level.getDayTime() / 24000L;
        long[] m = lastDays(village, today, 30);
        long[] w = lastDays(village, today, 7);
        List<String> out = new ArrayList<>();
        long all = 0;
        for (long n : m) all += n;
        if (all <= 0) return out;
        List<String> kinds = new ArrayList<>();
        for (Kind k : Kind.values()) if (m[k.ordinal()] > 0) kinds.add(m[k.ordinal()] + " " + k.words);
        out.add("Fuel on the town's fires this month: " + String.join(", ", kinds) + " (this week " + w[Kind.KELP.ordinal()]
            + " kelp blocks, " + (w[Kind.COAL.ordinal()] + w[Kind.CHARCOAL.ordinal()]) + " coal)");
        if (m[Kind.KELP.ordinal()] > 0) {
            out.add("The diver's kelp blocks kept " + coalKept(m[Kind.KELP.ordinal()]) + " coal in the stores this month, for the torches,"
                + " the forge and the watch");
        }
        return out;
    }

    /** "kelp blocks kept 400 coal in the stores this month", or null with none burnt. */
    @Nullable
    public static String keptLine(ServerLevel level, UUID village) {
        long[] m = lastDays(village, level.getDayTime() / 24000L, 30);
        long kelp = m[Kind.KELP.ordinal()];
        if (kelp <= 0) return null;
        return "kelp blocks kept " + coalKept(kelp) + " coal in the stores this month (" + kelp + " blocks on the fires, "
            + m[Kind.COAL.ordinal()] + " coal)";
    }
}
