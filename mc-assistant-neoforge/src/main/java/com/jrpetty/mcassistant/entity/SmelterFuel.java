package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [econ32] A smelter with ore to run is never left at a cold furnace while the town has logs or coal.
 *
 * <p>The couriers took a smelter its ore and its fuel together, and only while it had fewer than eight ore in its pack: a
 * smelter holding its ore and nothing it might burn was "stocked" and left to it, by the storehouse's runs (Couriers) and
 * by a carrier with no storehouse alike (VillageFolkEntity.haulerRound). The fuel that went with the ore was eight coal and
 * nothing else, whether or not the smelter would burn coal just then (a town putting coal by for its age burns wood), and
 * no logs ever went out, though the smelter's own rule is wood first while the stores are low. The smelter itself scooped
 * fuel only from the chests within its own reach. So a smelter that had its ore stood by its furnaces saying "the
 * smeltery's out of fuel" with two hundred logs in the stores, and the iron the town had dug lay raw in its pack.
 * Now:
 * <ul>
 * <li><b>Fuel on its own.</b> A smelter with ore and no fuel it may burn now ({@link #cold}) gets a run of fuel alone.</li>
 * <li><b>The right fuel.</b> What goes out ({@link #draw}) is what it will burn: the stores' coal or charcoal when the town
 *     is not putting coal by and the stores are over their floor; else the logs the builders can spare; and while the age
 *     waits on iron and the smelter holds iron ore, a few logs out of the builders' stock, or (wood all gone) the coal under
 *     the floor: iron for the age comes before a reserve of timber.</li>
 * <li><b>Fetched.</b> A smelter at a cold furnace with ore in hand fetches that fuel from the stores itself, once in half
 *     a minute ({@link #fetch}), rather than waiting on a carrier.</li>
 * </ul>
 */
public final class SmelterFuel {

    private SmelterFuel() {}

    /** Fuel to a run (coal), or logs to a run. */
    static final int COAL_RUN = 8, LOG_RUN = 12;
    /** How often a smelter fetches its own fuel (ticks). */
    static final long FETCH_EVERY = 600L;

    private static final Map<UUID, Long> FETCHED = new ConcurrentHashMap<>();
    /** Fuel runs and fetches, by town (the books, the tests): {runs, fetched, logs, coal}. */
    private static final Map<UUID, int[]> TALLY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        FETCHED.clear();
        TALLY.clear();
        ORE.clear();
    }

    /** Tests and the books: {fuel draws by carriers, fetches by smelters, logs sent, coal sent}. */
    public static int[] tally(UUID village) {
        int[] t = TALLY.get(village);
        return t == null ? new int[4] : t.clone();
    }

    static final Predicate<ItemStack> COAL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
    static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
    static final Predicate<ItemStack> IRON_ORE = s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE);

    /** Does this smelter hold ore to run and nothing it may burn on its furnaces just now? */
    public static boolean cold(VillageFolkEntity f) {
        if (f.stationTask() != AssistantEntity.StationTask.SMELT) return false;
        if (f.countCarried(AssistantEntity.SMELTABLE_ORE) <= 0) return false;
        return f.countCarried(f.smeltFuel()) <= 0 && f.countCarried(FuelBook.KELP_BLOCK) <= 0;
    }

    /** The logs the builders can spare: past half the timber the houses want, and never the last sixty-four. */
    static int logsToSpare(ServerLevel level, UUID village) {
        int stored = Market.stock(level, village, LOGS);
        int keep = Math.max(64, com.jrpetty.mcassistant.village.VillageMath.timberWanted(Villages.headcount(village)) / 2);
        return Math.max(0, stored - keep);
    }

    /**
     * Fuel for this smelter drawn out of the stores into {@code carrier}'s pack (the smelter's own, when it fetches it): what
     * the smelter will burn now, as the town stands. Returns how many were drawn.
     */
    static int draw(ServerLevel level, VillageFolkEntity carrier, VillageFolkEntity smelter, BlockPos heart, int radius) {
        UUID village = smelter.ownerId();
        if (village == null) return 0;
        boolean saving = smelter.savingCoal();
        boolean low = Fuel.low(level, village);
        int got = 0, logs = 0, coal = 0;
        if (!saving && !low) {
            coal = carrier.drawFrom(heart, COAL, COAL_RUN, radius);
            got += coal;
        }
        if (got <= 0) {
            int spare = Math.min(LOG_RUN, logsToSpare(level, village));
            if (spare > 0) logs = carrier.drawFrom(heart, LOGS, spare, radius);
            // The age waits on iron and this smelter holds iron ore: a few logs out of the builders' stock all the same.
            if (logs <= 0 && smelter.countCarried(IRON_ORE) > 0 && MineHands.kept(level, village)) {
                logs = carrier.drawFrom(heart, LOGS, 6, radius);
                // No wood in the stores at all: the coal under the floor, if the smelter would burn it (not while the age
                // puts coal by: then the furnace waits for the woodcutters).
                if (logs <= 0 && !saving) {
                    coal = carrier.drawFrom(heart, COAL, 4, radius);
                }
            }
            got += logs + coal;
        }
        if (got > 0) {
            int[] t = TALLY.computeIfAbsent(village, k -> new int[4]);
            t[carrier == smelter ? 1 : 0]++;
            t[2] += logs;
            t[3] += coal;
        }
        return got;
    }

    /**
     * A smelter at a cold furnace with ore in hand fetches its fuel from the stores itself (once in half a minute). True if
     * it has fuel now.
     */
    public static boolean fetch(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || !cold(f)) return false;
        UUID village = f.ownerId();
        BlockPos heart = f.villageCentre();
        if (village == null || heart == null || Villages.get(village) == null) return false;
        long now = level.getGameTime();
        Long last = FETCHED.get(f.getUUID());
        if (last != null && now - last < FETCH_EVERY && now >= last) return false;
        FETCHED.put(f.getUUID(), now);
        int radius = Math.min(112, Math.max(48, Villages.storesRadius(village)));
        int got = draw(level, f, f, heart, radius);
        if (got <= 0) return false;
        f.brain("fetched " + got + " fuel from the stores for the furnaces");
        return f.countCarried(f.smeltFuel()) > 0;
    }

    private static final Map<UUID, long[]> ORE = new ConcurrentHashMap<>();

    /**
     * Is this smelter kept at its furnaces (not called to the town's works, nor lent out) while the age waits on the
     * mine's iron (MineHands) and there is iron ore to run, in its pack or in the stores? The smelters of a local run's
     * Iron Age town spent a tenth to a quarter of their day at their furnaces, and as much at the town's works (a
     * gatehouse, a fire put out, a scar grassed over), with the iron the age wanted raw in their packs.
     */
    public static boolean kept(ServerLevel level, VillageFolkEntity f) {
        if (f.stationTask() != AssistantEntity.StationTask.SMELT || f.ownerId() == null) return false;
        if (!MineHands.kept(level, f.ownerId())) return false;
        if (f.countCarried(IRON_ORE) > 0) return true;
        long now = level.getGameTime();
        long[] k = ORE.get(f.ownerId());
        if (k == null || now - k[0] >= 600 || now < k[0]) {
            k = new long[]{ now, Market.stock(level, f.ownerId(), IRON_ORE) };
            ORE.put(f.ownerId(), k);
        }
        return k[1] >= 8;
    }

    /** A line for the books: the fuel runs today. */
    @Nullable
    public static String line(UUID village) {
        int[] t = TALLY.get(village);
        if (t == null || t[0] + t[1] == 0) return null;
        return "fuel out to the smelters: " + t[0] + " runs by the carriers, " + t[1] + " fetched by the smelters (" + t[2] + " logs, " + t[3] + " coal)";
    }
}
