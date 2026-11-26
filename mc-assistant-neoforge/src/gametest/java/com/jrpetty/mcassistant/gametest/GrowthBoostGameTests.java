package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.Colonies;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Blink;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.IdleCensus;
import com.jrpetty.mcassistant.entity.Newcomers;
import com.jrpetty.mcassistant.entity.Settlers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [grow54] Villages start at a working size, and small ones are brought up to it by settlers who walk in.
 * <ul>
 * <li><b>gb01</b>: a village founded as the world founds one it comes upon, at the new defaults (thirty to forty): by
 *     its first night everybody has a bed (in a house or at the camp), is fed and has a trade; the first fields go out
 *     on one side of the town; every plot lies inside the ground it keeps awake; and there is no crowd standing idle.</li>
 * <li><b>gb02</b>: a village of ten (the size the world grew them at before) with a player at its heart: settlers come
 *     in parties, each set down out of the player's sight and walking in, never jumping, and are taken in at the edge
 *     of town, a bed and a trade each, till it has thirty; the board says "Settlers arriving: n on the road"; and at
 *     thirty no more come.</li>
 * <li><b>gb03</b>: the rules: the defaults, a colony's party, who sends for settlers, and a party from outside (not
 *     settlers) set down out of a player's sight too.</li>
 * </ul>
 * x 12,700,000 to 12,740,000, z 93500.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class GrowthBoostGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 93500;

    // ============================================================ gb01: founded at thirty to forty, working on day one

    @GameTest(template = EMPTY, timeoutTicks = 30000, batch = "gb01_natural_base")
    public static void gb01_natural_base(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        Settlers.liveForTests(false, -1);                    // the founding on its own
        level.setDayTime(1000);
        int cx = 12_700_000;
        int r = 170;
        Kit.hold(level, cx, Z, r);
        Kit.prepare(level, cx, Z, r);
        Kit.generousTerrain(level, cx, Z);
        BlockPos heart = Kit.surface(level, cx, Z);
        VillageSpawner.foundForTests(level, heart);
        level.getServer().tickRateManager().setTickRate(10000.0F);
        final long[] start = { -1 };
        final UUID[] id = { null };
        final int[] founders = { 0 };
        final List<Double> idle = new ArrayList<>();
        final boolean[] employedSeen = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            try {
                if (start[0] < 0) {
                    if (Founding.near(level, heart, 8)) {
                        if (t % 400 == 0) Kit.log("[VT] gb01 @" + t + " " + Founding.status(level.getServer()));
                        return;
                    }
                    Villages.Village v = Villages.nearest(level, heart, 96);
                    if (v == null) { if (t > 8000) fail(helper, "no village founded"); return; }
                    id[0] = v.id();
                    start[0] = t;
                    founders[0] = Villages.headcount(v.id());
                    Kit.log("[VT] gb01 founded at " + t + " with " + founders[0] + " folk; camp beds " + VillageSpawner.campBeds(level, v.centre()).size()
                        + "; housing " + Villages.housing(v.id()));
                    if (founders[0] < AssistantConfig.villageMinFolk() || founders[0] > AssistantConfig.villageMaxFolk()) {
                        fail(helper, "founded at the new size, " + AssistantConfig.villageMinFolk() + " to " + AssistantConfig.villageMaxFolk() + ": " + founders[0]);
                    }
                    return;
                }
                UUID v = id[0];
                Villages.Village town = Villages.get(v);
                if (town == null) { fail(helper, "the village is gone"); return; }
                long since = t - start[0];
                long dayTime = level.getDayTime() % 24000L;
                Tally k = tally(v);
                // Every hand at a trade within a quarter of a day of standing up.
                if (since == 6000) {
                    Kit.log("[VT] gb01 +6000: " + k.line() + "; trades " + trades(v));
                    if (k.noTrade > 1) { fail(helper, "everybody at a trade within a quarter day: " + k.noTrade + " without one"); return; }
                    employedSeen[0] = true;
                }
                // No idle crowd, by day.
                if (since > 2000 && since % 1000 == 0 && dayTime > 1500 && dayTime < 11000) {
                    IdleCensus.Count c = IdleCensus.count(level, v);
                    idle.add(c.share());
                    Kit.log("[VT] gb01 idle +" + since + ": " + c.line());
                }
                if (since % 2000 == 0) Kit.log("[VT] gb01 +" + since + " (day time " + dayTime + "): " + k.line() + "; food in the stores "
                    + Villages.stock(level, town.centre(), Villages.Task.FOOD, Villages.storesRadius(v)) + "; built " + Villages.builtList(v));
                // The first night: everybody in bed somewhere, fed, nobody lost.
                if (employedSeen[0] && dayTime >= 15000 && dayTime < 22000) {
                    int heads = Villages.headcount(v);
                    int food = Villages.stock(level, town.centre(), Villages.Task.FOOD, Villages.storesRadius(v));
                    double meanIdle = idle.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    Kit.log("[VT] gb01 the first night: " + k.line() + "; housing " + Villages.housing(v) + "; food in the stores " + food
                        + "; mean idle share by day " + String.format("%.2f", meanIdle) + " over " + idle.size() + " counts");
                    Kit.Expect e = new Kit.Expect();
                    e.that(heads >= founders[0], "nobody lost: " + heads + " of " + founders[0]);
                    e.that(k.noBed <= 1, "everybody has a bed, in a house or at the camp: " + k.noBed + " without");
                    e.that(Villages.housing(v) >= heads, "room for everybody: " + Villages.housing(v) + " for " + heads);
                    e.that(k.hungry <= 1, "everybody fed (food on them): " + k.hungry + " with none");
                    e.that(food + k.foodCarried >= heads * 4, "food enough for days yet: " + food + " in the stores, " + k.foodCarried + " carried");
                    e.that(k.noTrade <= 1, "everybody at a trade: " + k.noTrade + " without");
                    e.that(!idle.isEmpty() && meanIdle <= 0.35, "no crowd standing idle by day: mean share " + meanIdle);
                    // The first fields out on one side of the town, the other sides left for houses.
                    int side = Villages.fieldsSide(v), on = 0, off = 0;
                    int outside = 0;
                    boolean heartHeld = !com.jrpetty.mcassistant.ChunkLoad.ownersForTests(level,
                        net.minecraft.world.level.ChunkPos.asLong(town.centre())).isEmpty();
                    for (AssistantEntity a : Villages.folkOf(v)) {
                        if (a.workZone() == null) continue;
                        BlockPos c = a.workZone().center();
                        int dx = c.getX() - town.centre().getX(), dz = c.getZ() - town.centre().getZ();
                        // Its plot's ground kept awake by the town or by the folk itself (ChunkLoad), so it is worked unwatched.
                        if (com.jrpetty.mcassistant.ChunkLoad.ownersForTests(level, net.minecraft.world.level.ChunkPos.asLong(c)).isEmpty()) {
                            outside++;
                            Kit.log("[VT] gb01 plot on ground nobody keeps awake: " + a.getAssistantName() + " " + a.stationTask() + " at " + dx + "," + dz);
                        }
                        if (a.stationTask() != AssistantEntity.StationTask.FARM) continue;
                        if (side >= 0 && Villages.along(side, dx, dz) > 0) on++; else off++;
                    }
                    Kit.log("[VT] gb01 fields side " + side + ": " + on + " fields on it, " + off + " off it; plots on ground nobody keeps awake: " + outside
                        + "; the town's ring " + VillageSpawner.loadedRadiusFor(heads) + " chunks, its heart held " + heartHeld);
                    e.that(side >= 0 && on >= off, "the fields go out on one side of the town: " + on + " on it, " + off + " off");
                    e.that(heartHeld && outside == 0, "the heart and every plot on ground kept awake: " + outside + " not, heart " + heartHeld);
                    level.getServer().tickRateManager().setTickRate(20.0F);
                    if (!e.clean()) { fail(helper, e.summary()); return; }
                    helper.succeed();
                }
            } catch (RuntimeException ex) {
                Kit.log("[VT] gb01 threw " + ex);
                fail(helper, "threw " + ex);
            }
        });
    }

    // ============================================================ gb02: settlers walk in to a village of ten

    @GameTest(template = EMPTY, timeoutTicks = 16000, batch = "gb02_settlers_walk_in")
    public static void gb02_settlers_walk_in(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        Blink.unwatchAll();
        level.setDayTime(1000);
        int cx = 12_710_000;
        int r = 200;
        Kit.hold(level, cx, Z, r);
        Kit.prepare(level, cx, Z, r);
        Kit.generousTerrain(level, cx, Z);
        BlockPos heart = Kit.surface(level, cx, Z);
        Founding.foundNow(level, heart, 10, 0F, null);
        final int base = Settlers.base();
        final long[] start = { -1 }, full = { -1 };
        final UUID[] id = { null };
        final Map<UUID, Vec3> last = new HashMap<>();
        final Set<UUID> seenOnce = new HashSet<>(), takenIn = new HashSet<>();
        final List<String> faults = new ArrayList<>();
        final boolean[] boardSeen = { false };
        final int[] partiesAtFull = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            try {
                if (start[0] < 0) {
                    if (Founding.near(level, heart, 8)) return;
                    Villages.Village v = Villages.nearest(level, heart, 96);
                    if (v == null) { if (t > 6000) fail(helper, "no village"); return; }
                    id[0] = v.id();
                    start[0] = t;
                    // A player stands at the heart: nothing may come into being, or jump, within sight of it.
                    Blink.watch(level, v.centre().getX() + 0.5, v.centre().getZ() + 0.5);
                    Settlers.liveForTests(true, 400);
                    Kit.log("[VT] gb02 a village of " + Villages.headcount(v.id()) + " at " + t + "; settlers wanted to " + base);
                    if (Villages.headcount(v.id()) != 10) fail(helper, "a village of ten: " + Villages.headcount(v.id()));
                    return;
                }
                UUID v = id[0];
                Villages.Village town = Villages.get(v);
                if (town == null) { fail(helper, "the village is gone"); return; }
                Vec3 eye = new Vec3(town.centre().getX() + 0.5, 0, town.centre().getZ() + 0.5);
                // Every settler watched from its first tick to its taking in: where it stood up, and every step.
                for (CompoundTag p : Settlers.partiesForTests(v)) {
                    boolean in = "in".equals(p.getString("stage"));
                    for (VillageFolkEntity f : Newcomers.membersForTests(level, p.getString("pid"))) {
                        UUID u = f.getUUID();
                        Vec3 now = f.position();
                        double fromEye = Math.hypot(now.x - eye.x, now.z - eye.z);
                        if (seenOnce.add(u)) {
                            Kit.log("[VT] gb02 settler " + f.getAssistantName() + " of party " + p.getString("pid") + " stood up "
                                + (int) fromEye + " blocks from the player");
                            if (fromEye <= Blink.SIGHT) faults.add(f.getAssistantName() + " came into being in sight, " + (int) fromEye + " blocks off");
                        }
                        Vec3 was = last.put(u, now);
                        if (was != null && !takenIn.contains(u)) {
                            double hop = Math.hypot(now.x - was.x, now.z - was.z);
                            double wasEye = Math.hypot(was.x - eye.x, was.z - eye.z);
                            if (hop > Blink.HOP && Math.min(fromEye, wasEye) <= Blink.SIGHT) {
                                faults.add(f.getAssistantName() + " jumped " + String.format("%.1f", hop) + " blocks in sight");
                            }
                        }
                        if (in && takenIn.add(u)) {
                            Kit.log("[VT] gb02 " + f.getAssistantName() + " taken in at " + (int) fromEye + " blocks from the heart; trade "
                                + f.stationTask() + ", bed " + f.bedPos() + "; " + Villages.headcount(v) + " folk");
                        }
                    }
                }
                int road = Settlers.onTheRoad(v);
                String line = Settlers.boardLine(level, v);
                if (road > 0 && line != null && line.startsWith("Settlers arriving: " + road + " on the road")) {
                    if (!boardSeen[0]) Kit.log("[VT] gb02 the board: " + line);
                    boardSeen[0] = true;
                }
                if (road > 0 && !boardLines(level, v).stream().anyMatch(s -> s.contains("Settlers arriving: " + road + " on the road"))) {
                    faults.add("the board does not say settlers are on the road: " + boardLines(level, v));
                }
                long since = t - start[0];
                if (since % 1000 == 0) Kit.log("[VT] gb02 +" + since + ": " + Villages.headcount(v) + " folk, " + road + " on the road, "
                    + Settlers.partiesForTests(v).size() + " parties; housing " + Villages.housing(v) + "; " + tally(v).line());
                if (!faults.isEmpty()) {
                    fail(helper, String.join("; ", faults.subList(0, Math.min(4, faults.size()))));
                    return;
                }
                if (full[0] < 0 && Villages.headcount(v) >= base) {
                    full[0] = t;
                    partiesAtFull[0] = Settlers.partiesForTests(v).size();
                    Kit.log("[VT] gb02 at " + base + " after " + since + " ticks, " + partiesAtFull[0] + " parties");
                }
                // Past the base: none more set out; everybody taken in has a bed and a trade.
                if (full[0] >= 0 && t - full[0] >= 1600) {
                    Tally k = tally(v);
                    Kit.Expect e = new Kit.Expect();
                    int settled = 0;
                    for (CompoundTag p : Settlers.partiesForTests(v)) settled += p.getList("members", Tag.TAG_COMPOUND).size();
                    e.that(Villages.headcount(v) >= base, "brought up to " + base + ": " + Villages.headcount(v));
                    e.that(settled <= base - 10, "no more settlers than it was short of: " + settled + " for " + (base - 10));
                    e.that(Settlers.partiesForTests(v).size() == partiesAtFull[0] && Settlers.onTheRoad(v) == 0,
                        "no more sent for at the base: " + Settlers.partiesForTests(v).size() + " parties against " + partiesAtFull[0]);
                    e.that(Settlers.boardLine(level, v) == null, "the board stops calling for settlers: " + Settlers.boardLine(level, v));
                    e.that(boardSeen[0], "the board said settlers were on the road");
                    e.that(settled >= (base - 10) / 2 && seenOnce.size() >= settled, "they came as settlers on the road, every one watched in: "
                        + seenOnce.size() + " seen of " + settled);
                    e.that(k.noBed <= 1, "a bed each: " + k.noBed + " without");
                    e.that(Villages.housing(v) >= Villages.headcount(v), "room for them all: " + Villages.housing(v));
                    e.that(k.noTrade <= 1, "a trade each: " + k.noTrade + " without; " + trades(v));
                    Settlers.liveForTests(false, -1);
                    Blink.unwatchAll();
                    if (!e.clean()) { fail(helper, e.summary()); return; }
                    helper.succeed();
                }
            } catch (RuntimeException ex) {
                Kit.log("[VT] gb02 threw " + ex);
                fail(helper, "threw " + ex);
            }
        });
    }

    // ============================================================ gb03: the rules

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "gb03_rules")
    public static void gb03_rules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Blink.unwatchAll();
        level.setDayTime(1000);
        Kit.Expect e = new Kit.Expect();
        e.that(AssistantConfig.villageMinFolk() == 30 && AssistantConfig.villageMaxFolk() == 40,
            "the world founds its villages with thirty to forty: " + AssistantConfig.villageMinFolk() + "-" + AssistantConfig.villageMaxFolk());
        e.that(Settlers.base() == 30, "settlers are sent for up to thirty: " + Settlers.base());
        e.that(Colonies.party() <= Colonies.PARTY_MOST && Colonies.party() >= 2, "a colony goes out with ten at most: " + Colonies.party());
        int cx = 12_730_000;
        Kit.hold(level, cx, Z, 200);
        Kit.prepare(level, cx, Z, 200);
        BlockPos heart = Kit.surface(level, cx, Z);
        int stood = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raiseParty(level, heart, 0F, 12);
        Villages.Village v = Villages.nearest(level, heart, 64);
        e.that(stood == 12 && v != null, "a village of twelve: " + stood);
        if (v != null) {
            e.that(Settlers.boosting(v.id()), "a village of twelve sends for settlers");
            Settlers.liveForTests(true, 400);
            e.that(Settlers.boardLine(level, v.id()) != null && Settlers.boardLine(level, v.id()).startsWith("Settlers sent for"),
                "its board says so: " + Settlers.boardLine(level, v.id()));
            Settlers.liveForTests(false, -1);
            // A party from outside (not settlers), with a player at the heart: set down out of sight.
            Blink.watch(level, v.centre().getX() + 0.5, v.centre().getZ() + 0.5);
            String pid = Newcomers.outsideForTests(level, v, 2);
            e.that(pid != null, "a party from outside comes");
            if (pid != null) {
                for (VillageFolkEntity f : Newcomers.membersForTests(level, pid)) {
                    double d = Math.hypot(f.getX() - v.centre().getX() - 0.5, f.getZ() - v.centre().getZ() - 0.5);
                    Kit.log("[VT] gb03 a newcomer from outside stood up " + (int) d + " blocks from the player at the heart");
                    e.that(d > Blink.SIGHT, "set down out of the player's sight: " + (int) d);
                }
            }
            Blink.unwatchAll();
        }
        helper.assertTrue(e.clean(), e.summary());
        helper.succeed();
    }

    // ============================================================ helpers

    private static void fail(GameTestHelper helper, String why) {
        Settlers.liveForTests(false, -1);
        Blink.unwatchAll();
        helper.getLevel().getServer().tickRateManager().setTickRate(20.0F);
        helper.fail(why);
    }

    private static List<String> boardLines(ServerLevel level, UUID v) {
        return com.jrpetty.mcassistant.entity.Referendums.board(level, v);
    }

    private record Tally(int grown, int kids, int noTrade, int noBed, int hungry, int foodCarried) {
        String line() {
            return grown + " grown, " + kids + " children; " + noTrade + " without a trade, " + noBed + " without a bed, " + hungry
                + " with no food on them (" + foodCarried + " carried)";
        }
    }

    private static Tally tally(UUID v) {
        int grown = 0, kids = 0, noTrade = 0, noBed = 0, hungry = 0, carried = 0;
        for (AssistantEntity a : Villages.folkOf(v)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            if (f.isBaby()) { kids++; continue; }
            grown++;
            if (f.stationTask() == AssistantEntity.StationTask.NONE) noTrade++;
            if (f.bedPos() == null) noBed++;
            int food = f.countFood();
            carried += food;
            if (food <= 0) hungry++;
        }
        return new Tally(grown, kids, noTrade, noBed, hungry, carried);
    }

    private static Map<String, Integer> trades(UUID v) {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (AssistantEntity a : Villages.folkOf(v)) if (!a.isBaby()) m.merge(a.stationTask().name(), 1, Integer::sum);
        return m;
    }
}
