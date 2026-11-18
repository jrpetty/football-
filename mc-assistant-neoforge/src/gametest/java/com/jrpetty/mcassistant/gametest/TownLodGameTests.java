package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.TownLod;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * [scale] Towns' level of detail (TownLod), in the scale band (x 1840000 on, z 66000): a town with nobody near sleeps
 * (its chunks let go, its folk saved with them, its stores and treasury kept by its step) and wakes with what it made;
 * and a town simulated asleep for ten days changes as it did over ten days in full.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownLodGameTests {

    private static final int Z = 66000;

    /**
     * Run {@code step} every server tick till it says it is done. Not helper.onEachTick: that books an entry for every
     * tick up to the timeout and walks them all each tick, and these tests run for hundreds of thousands. A failed
     * assertion is handed to the test framework to fail the test, and a pass (pass) too: the framework starts the next
     * batch from inside a pass, and not from inside a tick listener.
     */
    private static void each(GameTestHelper helper, java.util.function.BooleanSupplier step) {
        final boolean[] over = { false };
        Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> listener = e -> {
            if (over[0]) return;
            try {
                over[0] = step.getAsBoolean();
            } catch (RuntimeException ex) {
                over[0] = true;
                Kit.log("lod test stopped: " + ex);
                helper.getLevel().getServer().tickRateManager().setTickRate(20.0F);
                TownLod.forceForTests(helper.getLevel().getServer(), null);
                com.jrpetty.mcassistant.entity.FolkLod.liveForTests(false);
                helper.runAfterDelay(1, () -> { throw ex; });
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
    }

    /** The test passed, said from the framework's own tick. */
    private static void pass(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
    }

    /** Found a village of this many here, the ground held only for the founding; then {@code then} with it. */
    private static void found(GameTestHelper helper, int cx, int folk, Consumer<Villages.Village> then) {
        found(helper, cx, folk, false, then);
    }

    /** As found, with ponds, woods, stone hills and cattle round the town first (Kit.generousTerrain) if asked. */
    private static void found(GameTestHelper helper, int cx, int folk, boolean generous, Consumer<Villages.Village> then) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int outer = FoundingPlan.coreRadius(folk) + FoundingPlan.BAND_MAX + 16;
        Kit.hold(level, cx, Z, outer);
        Kit.prepare(level, cx, Z, outer);
        if (generous) Kit.generousTerrain(level, cx, Z);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.log("lod found " + folk + ": " + Kit.command(level, "village found " + folk + " " + cx + " " + Z));
        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
        each(helper, () -> {
            if (Founding.near(level, heart, 8)) return false;
            Villages.Village v = Villages.nearest(level, heart, 64);
            helper.assertTrue(v != null, "the village is founded");
            // The test's hold off: from here only the town's own chunk loading keeps its ground.
            for (int x = (cx - outer) >> 4; x <= (cx + outer) >> 4; x++)
                for (int z = (Z - outer) >> 4; z <= (Z + outer) >> 4; z++) level.setChunkForced(x, z, false);
            then.accept(v);
            return true;
        });
    }

    private static int all(ServerLevel level, UUID id) {
        return Market.stock(level, id, s -> true);
    }

    private static int bread(ServerLevel level, UUID id) {
        return Market.stock(level, id, s -> s.is(Items.BREAD));
    }

    /**
     * A town of twelve with nobody near goes to sleep: the chunks it forced are let go, its folk are saved with their
     * chunks (none loaded, the roll still twelve), its stores read as they stood; its step makes bread and coin at its
     * rates; it is still asleep with its bread after a restart (its state is in its ledger); and when somebody comes
     * near it wakes, its twelve folk back and the bread in its real chests.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "lod01_sleep_wake")
    public static void lod01_sleep_wake(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int[] phase = { 0 };
        final long[] at = { 0 };
        final int[] before = new int[4];     // all goods, bread, coins, forced chunks
        found(helper, 1846000, 12, v -> {
            UUID id = v.id();
            each(helper, () -> {
                long t = helper.getTick();
                switch (phase[0]) {
                    case 0 -> {                                 // awake, settling in: its chunks forced
                        if (at[0] == 0) at[0] = t;
                        if (t - at[0] < 300) return false;
                        before[0] = all(level, id);
                        before[1] = bread(level, id);
                        before[2] = Ledger.coins(id);
                        before[3] = ChunkLoad.forcedCount();
                        helper.assertTrue(before[3] > 0, "the town forces its chunks while it is awake");
                        TownLod.ratesForTests(id, new TownLod.Rates(Map.of("bread", 2.0), 3.0, 0.0, 5.0));
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.AWAY);
                        helper.assertTrue(TownLod.away(id), "with nobody near the town sleeps");
                        helper.assertTrue(ChunkLoad.forcedCount() < before[3],
                            "its forced chunks are let go: " + before[3] + " -> " + ChunkLoad.forcedCount());
                        helper.assertTrue(all(level, id) == before[0],
                            "its stores read as they stood: " + before[0] + " -> " + all(level, id));
                        phase[0] = 1;
                        at[0] = t;
                    }
                    case 1 -> {                                 // its folk saved with their chunks
                        if (AssistantEntity.countFor(id) > 0) {
                            if (t - at[0] > 4000) helper.fail("its folk are still loaded " + (t - at[0]) + " ticks on: " + AssistantEntity.countFor(id));
                            return false;
                        }
                        Kit.log("lod01 folk unloaded after " + (t - at[0]) + " ticks; roll " + Villages.headcount(id));
                        helper.assertTrue(Villages.headcount(id) == 12, "the roll is still twelve: " + Villages.headcount(id));
                        TownLod.stepForTests(id, 2.0);          // two days: 2 bread and 3 coin a head a day
                        helper.assertTrue(bread(level, id) == before[1] + 48,
                            "two days' bread for twelve is in its stores: " + before[1] + " -> " + bread(level, id));
                        helper.assertTrue(Ledger.coins(id) == before[2] + 72,
                            "two days' coin for twelve is in its treasury: " + before[2] + " -> " + Ledger.coins(id));
                        // A restart while it sleeps: what is in memory is gone, the ledger keeps the rest.
                        TownLod.reset();
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.AWAY);
                        helper.assertTrue(TownLod.away(id) && bread(level, id) == before[1] + 48,
                            "after a restart it is still asleep, its bread kept: " + bread(level, id));
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
                        helper.assertTrue(!TownLod.away(id), "somebody near: it wakes");
                        phase[0] = 2;
                        at[0] = t;
                    }
                    default -> {                                // awake again: its folk back, the bread in its chests
                        boolean back = AssistantEntity.countFor(id) == 12 && TownLod.settledForTests(id);
                        if (!back) {
                            if (t - at[0] > 4000) helper.fail("not back after " + (t - at[0]) + " ticks: folk "
                                + AssistantEntity.countFor(id) + ", settled " + TownLod.settledForTests(id));
                            return false;
                        }
                        int now = realBread(level, id);
                        Kit.log("lod01 woke after " + (t - at[0]) + " ticks: bread in the chests " + now + " (was " + before[1] + ")");
                        helper.assertTrue(now >= before[1] + 40, "the bread made asleep is in its real chests: " + before[1] + " -> " + now);
                        TownLod.forceForTests(level.getServer(), null);
                        pass(helper);
                        return true;
                    }
                }
                return false;
            });
        });
    }

    /**
     * Out of sight, folk travel their walks (FolkLod): in a town of twelve with nobody near, walks are travelled, and
     * every folk ends each one standing on firm ground, not in a block or the water; with a player at the heart, no folk
     * in that player's sight is ever travelling, every tick for twenty seconds.
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "lod03_travel_unseen")
    public static void lod03_travel_unseen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final long[] at = { 0, 0 };
        final net.minecraft.server.level.ServerPlayer[] you = { null };
        found(helper, 1854000, 12, v -> {
            UUID id = v.id();
            com.jrpetty.mcassistant.entity.FolkLod.liveForTests(true);
            at[1] = com.jrpetty.mcassistant.entity.FolkNavigation.travelledInAll();
            final long bad0 = com.jrpetty.mcassistant.entity.FolkNavigation.badLandings();
            each(helper, () -> {
                long t = helper.getTick();
                if (at[0] == 0) at[0] = t;
                long ran = t - at[0];
                if (ran < 1200) return false;                   // a minute with nobody near
                if (ran == 1200) {
                    long travelled = com.jrpetty.mcassistant.entity.FolkNavigation.travelledInAll() - at[1];
                    Kit.log("lod03 walks travelled out of sight in a minute: " + travelled);
                    helper.assertTrue(travelled > 0, "out of sight, folk travel their walks");
                    // [health] Every travel set its folk down clear and dry (looked at as each one ends). A folk seen in the
                    // water at the minute's end walked there as anybody may (a field's water hole on its way across the
                    // farm, on CI): not the travel's doing, so the water is the landings' check; a block is everybody's.
                    long bad = com.jrpetty.mcassistant.entity.FolkNavigation.badLandings() - bad0;
                    helper.assertTrue(bad == 0, bad + " travels ended in a block or the water, the last "
                        + com.jrpetty.mcassistant.entity.FolkNavigation.lastBadLanding());
                    for (AssistantEntity a : Villages.folkOf(id)) {
                        // Its body in no block (feet on farmland or a path sit a little into the block below them);
                        // one sat on a bench or a seat is sat in it, as it should be.
                        if (a.isPassenger() || a.getPose() == net.minecraft.world.entity.Pose.SITTING || level.getBlockState(a.blockPosition()).getBlock() instanceof net.minecraft.world.level.block.StairBlock
                                || level.getBlockState(a.blockPosition()).getBlock() instanceof net.minecraft.world.level.block.SlabBlock) continue;
                        if (a.isInWaterOrBubble()) Kit.log("lod03 " + a.getAssistantName() + " in the water at " + a.position() + ", travelling "
                            + (a.getNavigation() instanceof com.jrpetty.mcassistant.entity.FolkNavigation n && n.travelling()));
                        helper.assertTrue(level.noCollision(a, a.getBoundingBox().deflate(0.05)),
                            a.getAssistantName() + " stands clear of every block at " + a.position() + " (feet in "
                                + level.getBlockState(a.blockPosition()) + ", under it " + level.getBlockState(a.blockPosition().below())
                                + ", travelling " + (a.getNavigation() instanceof com.jrpetty.mcassistant.entity.FolkNavigation n && n.travelling())
                                + ", on ground " + a.onGround() + ", moving " + a.getDeltaMovement() + ")");
                    }
                    you[0] = helper.makeMockServerPlayerInLevel();
                    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(you[0].connection.getConnection());
                    you[0].teleportTo(level, v.centre().getX() + 0.5, v.centre().getY() + 1, v.centre().getZ() + 0.5, 0, 0);
                    return false;
                }
                for (AssistantEntity a : Villages.folkOf(id)) {
                    if (a.distanceTo(you[0]) < com.jrpetty.mcassistant.entity.FolkLod.SIGHT - 8
                            && a.getNavigation() instanceof com.jrpetty.mcassistant.entity.FolkNavigation n && n.travelling()) {
                        helper.fail(a.getAssistantName() + " travels in a player's sight, " + (int) a.distanceTo(you[0]) + " blocks off");
                    }
                }
                if (ran < 1600) return false;
                com.jrpetty.mcassistant.entity.FolkLod.liveForTests(false);
                level.getServer().getPlayerList().remove(you[0]);
                pass(helper);
                return true;
            });
        });
    }

    /**
     * [health] A sleeping town's parked chunk requests survive a save however many there are: two and a half thousand
     * (a town of forty, each folk's station asking for its square of chunks) written out to bytes as the game writes its
     * saves and read back whole, in the new form and in the old (an owner's id before every chunk, near 125,000 bytes:
     * past the 65,535 one saved string holds, so lost on every save before the ledger kept long notes in pieces).
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "lod04_parked_saved")
    public static void lod04_parked_saved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID town = UUID.randomUUID();
        java.util.Random r = new java.util.Random(11);
        java.util.List<TownLod.Parked> parked = new java.util.ArrayList<>();
        for (int owner = 0; owner < 50; owner++) {
            UUID who = new UUID(r.nextLong(), r.nextLong());
            for (int k = 0; k < 50; k++) parked.add(new TownLod.Parked(who, net.minecraft.world.level.ChunkPos.asLong(r.nextInt(400000) - 200000, r.nextInt(400000) - 200000)));
        }
        StringBuilder old = new StringBuilder();
        for (TownLod.Parked p : parked) {
            if (old.length() > 0) old.append(',');
            old.append(p.owner()).append('@').append(p.chunk());
        }
        Kit.log("lod04 " + parked.size() + " parked requests, the old note " + old.length() + " characters");
        helper.assertTrue(old.length() > 65535, "the old note is past one saved string: " + old.length());
        java.util.List<TownLod.Parked> back = TownLod.parkedAfterSaveForTests(town, parked, null, level.registryAccess());
        helper.assertTrue(back.equals(parked), "every request back after a save: " + back.size() + " of " + parked.size());
        java.util.List<TownLod.Parked> backOld = TownLod.parkedAfterSaveForTests(town, parked, old.toString(), level.registryAccess());
        helper.assertTrue(backOld.equals(parked), "and the old note, read the old way: " + backOld.size() + " of " + parked.size());
        // Any long note, not only this one: wide characters too (three bytes each in a save).
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 40000; i++) words.append(i % 2 == 0 ? "\u00e9" : "\u6728");
        Ledger.note(town, "a long note", words.toString());
        String wordsBack = Ledger.notesAfterSaveForTests(town, level.registryAccess()).get("a long note");
        Ledger.forget(town, "a long note");
        helper.assertTrue(words.toString().equals(wordsBack), "a long note of wide characters whole: "
            + (wordsBack == null ? "none" : wordsBack.length() + " characters"));
        Ledger.note(town, "short", "kept as it was");
        String shortBack = Ledger.notesAfterSaveForTests(town, level.registryAccess()).get("short");
        Ledger.forget(town, "short");
        helper.assertTrue("kept as it was".equals(shortBack), "a short note as ever: " + shortBack);
        helper.succeed();
    }

    /** The bread in the town's store chests themselves (not the kept count). */
    private static int realBread(ServerLevel level, UUID id) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.BREAD)) n += c.getItem(i).getCount();
        }
        return n;
    }

    private static final double DAYS = Double.parseDouble(System.getProperty("lod.days", "10"));
    /** Days the town settles in before it is measured: its founding stores eaten down, its fields and pens up. */
    private static final int WARM = 5;
    /** Below this many of a kind over the stretch, the kind is too few to compare (a handful either way). */
    private static final int FEW = 24;
    /** How near the sleeping town's change must come to the watched town's, of the watched one's. */
    private static final double TOLERANCE = 0.2;

    /** The stores as kinds of goods (food, timber, stone...), and the treasury and roll. */
    private static Map<String, Integer> picture(ServerLevel level, UUID id) {
        // Through the stores' own count (Market.stock), which a sleeping town answers from what it keeps: the same
        // reading awake and asleep. Plain goods only (what a sleeping town keeps): nothing named, enchanted or worn.
        Map<String, Integer> m = new HashMap<>();
        for (String kind : KINDS) {
            int n = Market.stock(level, id, s -> s.isComponentsPatchEmpty() && kind.equals(kindOf(s)));
            if (n != 0) m.put(kind, n);
        }
        m.put("coin", Ledger.coins(id));
        m.put("folk", Villages.recordedPopulation(id));
        return m;
    }

    /** The craft goods item by item, through the stores' own count (asleep: what the town keeps). */
    private static Map<String, Integer> craftItems(ServerLevel level, UUID id) {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (net.minecraft.world.item.Item it : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            ItemStack one = new ItemStack(it);
            if (one.isEmpty() || !"craft".equals(kindOf(one))) continue;
            int n = Market.stock(level, id, s -> s.is(it) && s.isComponentsPatchEmpty());
            if (n != 0) m.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).getPath(), n);
        }
        return m;
    }

    private static final java.util.List<String> KINDS = kinds();

    private static java.util.List<String> kinds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Economy.Kind k : Economy.Kind.values()) out.add(k.name().toLowerCase());
        out.add("other");
        return out;
    }

    private static String kindOf(ItemStack s) {
        Economy.Kind k = Economy.kindOf(s);
        return k == null ? "other" : k.name().toLowerCase();
    }

    /** The day-by-day trend of each reading over a stretch (least squares), times the stretch: what it gained, with a
     *  day's noise at either end (a chest emptied for a build the evening before the count) not taken for a trend. */
    private static Map<String, Double> trend(java.util.List<Map<String, Integer>> days) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (Map<String, Integer> d : days) keys.addAll(d.keySet());
        Map<String, Double> out = new HashMap<>();
        int n = days.size();
        double mx = (n - 1) / 2.0, sxx = 0;
        for (int i = 0; i < n; i++) sxx += (i - mx) * (i - mx);
        for (String k : keys) {
            double my = 0;
            for (Map<String, Integer> d : days) my += d.getOrDefault(k, 0);
            my /= n;
            double sxy = 0;
            for (int i = 0; i < n; i++) sxy += (i - mx) * (days.get(i).getOrDefault(k, 0) - my);
            out.put(k, sxx == 0 ? 0 : sxy / sxx * (n - 1));
        }
        return out;
    }

    /** How uncertain each of those trends is (twice its standard error, times the stretch): the day-to-day noise of
     *  the stores (a hundred stone drawn for a building one day, a mine's haul the next) read as a trend. */
    private static Map<String, Double> noise(java.util.List<Map<String, Integer>> days) {
        Map<String, Double> slopes = trend(days), out = new HashMap<>();
        int n = days.size();
        if (n < 3) return out;
        double mx = (n - 1) / 2.0, sxx = 0;
        for (int i = 0; i < n; i++) sxx += (i - mx) * (i - mx);
        for (Map.Entry<String, Double> e : slopes.entrySet()) {
            double b = e.getValue() / (n - 1), my = 0;
            for (Map<String, Integer> d : days) my += d.getOrDefault(e.getKey(), 0);
            my /= n;
            double ss = 0;
            for (int i = 0; i < n; i++) {
                double r = days.get(i).getOrDefault(e.getKey(), 0) - (my + b * (i - mx));
                ss += r * r;
            }
            out.put(e.getKey(), 2.0 * Math.sqrt(ss / (n - 2) / sxx) * (n - 1));
        }
        return out;
    }

    /**
     * A town of twenty on generous ground (it farms, fells, quarries and keeps cattle), watched for five days to
     * settle and then for {@code lod.days} days (ten), and then asleep for as long: its stores (kind by kind), treasury
     * and roll change asleep as they did watched (the trend of the daily counts), each within a fifth, a couple of dozen
     * for a kind barely moved, or the watched trend's own day-to-day noise (twice its standard error).
     */
    @GameTest(template = "empty", timeoutTicks = 1_200_000, batch = "lod02_full_vs_away")
    public static void lod02_full_vs_away(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final long span = Math.round(DAYS * 24000);
        final int[] phase = { 0 };
        final long[] at = { 0 };
        final Map<String, Integer>[] pic = new Map[3];
        // The readings at the start and at each day's end, watched and asleep.
        final java.util.List<Map<String, Integer>> watched = new java.util.ArrayList<>(), asleep = new java.util.ArrayList<>();
        found(helper, 1850000, 20, true, v -> {
            UUID id = v.id();
            level.getServer().tickRateManager().setTickRate(10000.0F);
            each(helper, () -> {
                long t = helper.getTick();
                if (at[0] == 0) at[0] = t;
                long ran = t - at[0];
                if (ran % 24000 == 0) {
                    Map<String, Integer> now = picture(level, id);
                    Kit.log("lod02 phase " + phase[0] + " day " + ran / 24000 + ": " + now);
                    if (phase[0] == 1 && ran <= span) watched.add(now);
                    if (phase[0] == 2 && ran <= span) asleep.add(now);
                }
                switch (phase[0]) {
                    case 0 -> {                                 // days to settle in (stores spent down, fields up)
                        if (ran < WARM * 24000L) return false;
                        pic[0] = picture(level, id);
                        watched.add(pic[0]);
                        phase[0] = 1;
                        at[0] = t;
                    }
                    case 1 -> {                                 // watched, in full
                        if (ran < span) return false;
                        pic[1] = picture(level, id);
                        Kit.log("lod02 rates learned: " + TownLod.ratesFor(id));
                        Kit.log("lod02 craft awake before sleep: " + craftItems(level, id));
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.AWAY);
                        asleep.add(picture(level, id));
                        Kit.log("lod02 craft kept at sleep: " + craftItems(level, id) + "; " + asleep.get(asleep.size() - 1));
                        phase[0] = 2;
                        at[0] = t;
                    }
                    case 2 -> {                                 // asleep, by its step
                        if (ran < span) return false;
                        Kit.log("lod02 craft kept at waking: " + craftItems(level, id));
                        TownLod.forceForTests(level.getServer(), TownLod.Lod.FULL);
                        phase[0] = 3;
                        at[0] = t;
                    }
                    default -> {                                // awake: what it did asleep in its chests
                        if (!TownLod.settledForTests(id) || ran < 200) {
                            if (ran > 6000) helper.fail("the woken town's goods were not settled in 6000 ticks");
                            return false;
                        }
                        pic[2] = picture(level, id);
                        Kit.log("lod02 craft in the chests awake: " + craftItems(level, id));
                        level.getServer().tickRateManager().setTickRate(20.0F);
                        compare(helper, pic, trend(watched), noise(watched), trend(asleep), asleep.get(asleep.size() - 1));
                        TownLod.forceForTests(level.getServer(), null);
                        pass(helper);
                        return true;
                    }
                }
                return false;
            });
        });
    }

    /**
     * Kind by kind, the trend watched against the trend asleep; the roll by what it ended at (children asleep are born
     * on waking); and the goods the sleeping town ended with are in its real chests once it has woken (within a few,
     * for what its folk used in the moments since).
     */
    private static void compare(GameTestHelper helper, Map<String, Integer>[] pic, Map<String, Double> watched,
                                Map<String, Double> noise, Map<String, Double> asleep, Map<String, Integer> kept) {
        java.util.Set<String> kinds = new java.util.TreeSet<>(watched.keySet());
        kinds.addAll(asleep.keySet());
        StringBuilder report = new StringBuilder();
        StringBuilder wrong = new StringBuilder();
        for (String k : kinds) {
            boolean folk = "folk".equals(k);
            int full = folk ? pic[1].getOrDefault(k, 0) - pic[0].getOrDefault(k, 0) : (int) Math.round(watched.getOrDefault(k, 0.0));
            int away = folk ? pic[2].getOrDefault(k, 0) - pic[1].getOrDefault(k, 0) : (int) Math.round(asleep.getOrDefault(k, 0.0));
            report.append(k).append(" full ").append(full).append(" away ").append(away);
            if (!folk) report.append(" (noise ").append(Math.round(noise.getOrDefault(k, 0.0))).append(')');
            report.append("; ");
            // Within a fifth, or a couple of dozen for a kind barely moved, or the watched trend's own noise.
            double allowed = Math.max(Math.max(TOLERANCE * Math.abs(full), "folk".equals(k) ? 2 : FEW),
                folk ? 0 : noise.getOrDefault(k, 0.0));
            if (Math.abs(away - full) > allowed) wrong.append(k).append(' ');
        }
        Kit.log("lod02 " + DAYS + " days, the trend watched vs asleep: " + report);
        StringBuilder lost = new StringBuilder();
        for (String k : KINDS) {
            int was = kept.getOrDefault(k, 0), now = pic[2].getOrDefault(k, 0);
            if (Math.abs(now - was) > Math.max(FEW, TOLERANCE * was)) lost.append(k).append(' ').append(was).append(" -> ").append(now).append("; ");
        }
        Kit.log("lod02 kept asleep " + kept + ", in the chests awake " + pic[2]);
        helper.assertTrue(lost.isEmpty(), "what the sleeping town kept is in its chests when it wakes: " + lost);
        helper.assertTrue(wrong.isEmpty(), "asleep the town changes as it did watched (within a fifth); not so for: "
            + wrong + "| " + report);
    }
}
