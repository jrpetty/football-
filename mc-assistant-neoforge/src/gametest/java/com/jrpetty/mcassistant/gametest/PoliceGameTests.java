package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Crime;
import com.jrpetty.mcassistant.entity.Laws;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PlayerLaw;
import com.jrpetty.mcassistant.entity.Police;
import com.jrpetty.mcassistant.entity.Raids;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Watch;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.item.PoliceItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [police] The watch as the town's police (Police, Roster, WatchHouse, Beats, Incidents, PlayerLaw). The roster deals
 * every guard both kinds of work over a week, and the bell sends every one of them to the walls whatever the roster
 * said; a robbed folk runs to a guard with it and the guard runs to the scene; a thief seen in the act is chased
 * through the streets and caught by the collar; a fight is broken up, a warning the first time and a fine the next; a
 * prisoner is walked on a lead to a cell of the watch house, held there and fed, walked to the council and back,
 * serves its day in the cells and is let out with a word; the beat's passing puts a thief off where it has walked and
 * nowhere else; the curfew is a warning, then a fine; a player who helps itself to the stores is warned, fined,
 * barred, and welcome again once it pays; a player sworn in as a special constable, with a badge the town made of its
 * own iron and gold, arrests a culprit and is paid for the case; and the watch house goes up with its cells, iron
 * barred, its notice board and its casebook.
 *
 * <p>Each test has its own batch and its own ground: x 1540000 to 1559999, z 66000. The other features' tests run
 * with the watch stood down (Kit.reset); these stand it up again.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PoliceGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ helpers

    /** A fresh world for the watch: the others' leftovers gone, the watch stood up. */
    private static void start(ServerLevel level, int x, int r) {
        Kit.reset(level);
        Police.standDownForTests(false);
        Kit.hold(level, x, Z, r);
        Kit.prepare(level, x, Z, r);
    }

    /** Level, open ground: a few blocks of earth under it and nothing over it. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 12; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** The next day, at this hour. */
    private static void morning(ServerLevel level, long hour) {
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + hour);
        level.updateSkyBrightness();
    }

    private static VillageFolkEntity folk(GameTestHelper helper, ServerLevel level, BlockPos at, String who) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        helper.assertTrue(f != null, "raised " + who);
        return f;
    }

    private static VillageFolkEntity guard(GameTestHelper helper, ServerLevel level, BlockPos at, String who) {
        VillageFolkEntity g = folk(helper, level, at, who);
        g.setJob(StationTask.GUARD);
        // Its ground where it stands, and its trade kept: a test's town has more of the watch than its sums would keep, and
        // a newcomer's ground is otherwise planned off by the town's edge.
        g.setWorkZone(com.jrpetty.mcassistant.entity.WorkZone.around(g.blockPosition(), 8, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH));
        g.keepTradeForTests();
        g.setShift(AssistantEntity.Shift.ALWAYS);
        return g;
    }

    private static void at(VillageFolkEntity f, BlockPos p, Direction looks) {
        f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, looks.toYRot(), 0.0F);
        f.setYHeadRot(looks.toYRot());
        f.setYBodyRot(looks.toYRot());
        f.getNavigation().stop();
    }

    /** A chest of the stores by the heart, with these in it. */
    private static BlockPos stores(ServerLevel level, BlockPos heart, ItemStack... things) {
        BlockPos at = heart.offset(3, 0, 3);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        if (level.getBlockEntity(at) instanceof Container c) {
            for (int i = 0; i < things.length; i++) c.setItem(i, things[i]);
            c.setChanged();
        }
        return at;
    }

    private static int count(ServerLevel level, BlockPos chest, net.minecraft.world.item.Item item) {
        int n = 0;
        if (level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        }
        return n;
    }

    /** Take so many of a thing out of the chest (as a hand reaching in would). */
    private static int takeFrom(ServerLevel level, BlockPos chest, net.minecraft.world.item.Item item, int n) {
        int took = 0;
        if (level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize() && took < n; i++) {
                ItemStack s = c.getItem(i);
                if (!s.is(item)) continue;
                int k = Math.min(n - took, s.getCount());
                s.shrink(k);
                took += k;
            }
            c.setChanged();
        }
        return took;
    }

    /** The town's guards (its own, grown, not hired out). */
    private static List<VillageFolkEntity> watchOf(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity g && g.stationTask() == StationTask.GUARD && !g.isBaby() && !g.isHired()) out.add(g);
        }
        return out;
    }

    private static String names(List<VillageFolkEntity> l) {
        List<String> out = new ArrayList<>();
        for (VillageFolkEntity f : l) out.add(f.displayNameCap());
        return String.join(", ", out);
    }

    private static void logCase(String prefix, Crime.Case c) {
        Kit.log(prefix + " case #" + c.id + " " + c.title() + ": " + c.what() + "; " + c.stage() + "; accused " + c.accusedName()
            + "; verdict " + c.verdict() + "; sentence " + c.sentence());
        for (Crime.Clue k : c.clues()) Kit.log(prefix + "   clue: " + k.text());
        for (String n : c.notes()) Kit.log(prefix + "   note " + n);
    }

    // ============================================================ pl01: the roster, and the bell over it

    /**
     * A watch of five. A week of rosters, drawn day by day: every guard has stood on the walls and walked the streets
     * (or kept the desk, the cases, an escort or an event) in it, one rests each day, and the captain's line is on the
     * board and each guard's duty on its card. Then the bell: every guard's duty is the walls, the roster stands down on
     * the board, a guard breaking up a fight lets it go, the guard resting turns out, and each makes for a post on the
     * wall with its bow or for a gate.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl01_roster_raid")
    public static void pl01_roster_raid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1540000;
        start(level, x, 56);
        BlockPos heart = flat(level, x, Z, 44);
        morning(level, 3000L);
        List<VillageFolkEntity> watch = new ArrayList<>();
        VillageFolkEntity a = folk(helper, level, heart, "a farmer");             // the first raised: the town's middle
        VillageFolkEntity b = folk(helper, level, heart.offset(2, 0, 0), "a second farmer");
        for (int i = 0; i < 5; i++) watch.add(guard(helper, level, heart.offset(-4 + 2 * i, 0, -6), "guard " + (i + 1)));
        UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.STONE);
        for (VillageFolkEntity g : watch) {
            g.insertItem(new ItemStack(Items.BOW));
            g.insertItem(new ItemStack(Items.ARROW, 32));
        }
        // The wall, as a village's builder leaves it, with its gates, its posts and its bell.
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "fortify", heart, Direction.NORTH, com.jrpetty.mcassistant.village.TownPlan.PLAZA,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Villages.noteProject(village, "fortify", level.getGameTime());
        Villages.builtAtForTests(village, "fortify", heart);
        Watch.keep(level, v, true);
        Watch.bell(level, v, true);
        int[] phase = { 0 };
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            for (int d = 0; d < 7; d++) {
                Map<UUID, String> r = Police.drawForTests(level, v, day - 6 + d);
                List<String> line = new ArrayList<>();
                for (VillageFolkEntity g : watch) line.add(g.displayNameCap() + " " + r.get(g.getUUID()));
                Kit.log("pl01 day " + (day - 6 + d) + ": " + String.join(", ", line));
                helper.assertTrue(r.size() == 5, "every guard on the day's roster: " + r.size());
                helper.assertTrue(r.containsValue("WALLS") && r.containsValue("BEAT") && r.containsValue("REST"),
                    "each day the walls, a beat and a guard resting: " + r.values());
            }
            for (VillageFolkEntity g : watch) {
                helper.assertTrue(Police.bothKindsForTests(village, g.getUUID()), g.displayNameCap() + " has both kinds of work in the week");
            }
            Police.tickForTests(level, v);
            String board = Police.boardForTests(level, village);
            CompoundTag report = Police.reportForTests(level, v);
            String card = Police.cardForTests(watch.get(0));
            Kit.log("pl01 the board:\n" + board + "\npl01 captain " + report.getString("captain") + "; the first guard's card: " + card);
            helper.assertTrue(board.contains("The watch today") && board.contains("captain"), "the roster and its captain on the board");
            helper.assertTrue(!report.getString("captain").isEmpty(), "a captain drew it");
            helper.assertTrue(card.contains("today: "), "the guard's card has its duty today: " + card);
            // A fight in the street, and a guard sent to it.
            Police.fightForTests(level, a, b);
            VillageFolkEntity sent = null;
            for (VillageFolkEntity g : watch) if ("SEPARATE".equals(Police.taskForTests(g))) sent = g;
            helper.assertTrue(sent != null, "a guard is sent to break up the fight");
            // The bell.
            helper.assertTrue(Raids.warAlarm(level, v, "raiders sighted on the road", null, 2400L), "the bell rings");
            for (VillageFolkEntity g : watch) {
                helper.assertTrue("BELL".equals(Police.dutyForTests(level, g)), g.displayNameCap() + " is called to the walls, whatever the roster said");
                helper.assertTrue(g.onShift(), g.displayNameCap() + " turns out, resting or not");
            }
            String bell = Police.boardForTests(level, village);
            helper.assertTrue(bell.contains("stands down"), "the board says the roster stands down: " + bell);
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] != 1) return;
            if (t % 5 == 0) for (VillageFolkEntity g : watch) Raids.guardDuty(g);
            List<VillageFolkEntity> walls = new ArrayList<>(), busy = new ArrayList<>(), up = new ArrayList<>();
            for (VillageFolkEntity g : watch) {
                if (!Police.taskForTests(g).isEmpty()) busy.add(g);
                boolean atGate = false;
                for (Watch.Gate gate : Watch.gates(level, village)) if (g.blockPosition().distSqr(gate.inside()) <= 8 * 8) atGate = true;
                if (g.post() != null || Raids.headingForPost(g) || atGate) walls.add(g);
                if (g.holdingAPost()) up.add(g);
            }
            if (t % 100 == 0) Kit.log("pl01 tick " + t + ": for the walls " + names(walls) + "; up " + names(up) + "; still on police work " + names(busy));
            if (busy.isEmpty() && walls.size() == watch.size() && !up.isEmpty()) {
                Kit.log("pl01 every guard to the walls at tick " + t + "; on the wall already: " + names(up));
                helper.succeed();
                return;
            }
            if (t > 1500) helper.fail("pl01 not every guard went to the walls: for the walls " + names(walls) + ", up " + names(up) + ", busy " + names(busy));
        });
    }

    // ============================================================ pl02: a crime reported at a run

    /**
     * A purse picked with no guard near. When it is reported the victim runs to the guard twenty-odd blocks off, calling
     * for the watch, tells it what happened, and the guard runs back with it to the scene and keeps it for the case.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl02_witness_runs")
    public static void pl02_witness_runs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1542000;
        start(level, x, 48);
        BlockPos heart = flat(level, x, Z, 40);
        morning(level, 2500L);
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-2, 0, 0), "the culprit");
        VillageFolkEntity g = guard(helper, level, heart.offset(0, 0, -22), "the guard");
        UUID village = victim.ownerId();
        culprit.setJob(StationTask.FARM);
        victim.spend(victim.purse());
        victim.earn(10);
        int[] id = { -1 };
        boolean[] ran = { false }, responded = { false };
        helper.runAtTickTime(20, () -> {
            at(victim, heart.offset(0, 0, 2), Direction.NORTH);
            at(culprit, heart.offset(-1, 0, 2), Direction.EAST);
            at(g, heart.offset(0, 0, -22), Direction.NORTH);            // its back to the square, too far off to see it
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "the purse is picked");
            // Off and away before it is missed: nobody to chase at the scene.
            at(culprit, heart.offset(-34, 0, 0), Direction.WEST);
            culprit.setNoAi(true);
            Crime.reportForTests(level, c);
            id[0] = c.id;
            Kit.log("pl02 reported: the victim's task " + Police.taskForTests(victim) + ", the guard " + String.format("%.1f", g.distanceTo(victim)) + " off");
            helper.assertTrue("REPORT".equals(Police.taskForTests(victim)), "the victim runs to the watch with it");
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("pl02 the case is gone"); return; }
            if ("REPORT".equals(Police.taskForTests(victim))) ran[0] = true;
            if ("RESPOND".equals(Police.taskForTests(g))) responded[0] = true;
            long day = level.getDayTime() / 24000L;
            if (t % 100 == 0) {
                Kit.log("pl02 tick " + t + ": the victim " + Police.taskForTests(victim) + " (" + Police.doingForTests(victim) + "), "
                    + String.format("%.1f", victim.distanceTo(g)) + " from the guard; the guard " + Police.taskForTests(g) + " ("
                    + Police.doingForTests(g) + "), " + String.format("%.1f", Math.sqrt(g.blockPosition().distSqr(c.where))) + " from the scene");
            }
            if (Police.tallyForTests(village, day - 1, "report") >= 1) {
                logCase("pl02", c);
                CompoundTag rec = Police.guardRecordForTests(g);
                boolean told = false, came = false;
                for (String n : c.notes()) {
                    if (n.contains(victim.displayNameCap() + " ran to " + g.displayNameCap())) told = true;
                    if (n.contains("came at a run")) came = true;
                }
                helper.assertTrue(ran[0] && responded[0], "the victim ran to the guard, and the guard responded");
                helper.assertTrue(told && came, "the case has it: told at a run, and the watch at the scene");
                helper.assertTrue(Math.sqrt(g.blockPosition().distSqr(c.where)) <= 4.5, "the guard is at the scene");
                helper.assertTrue(rec.getInt("responded") == 1, "on the guard's record");
                helper.succeed();
                return;
            }
            if (t > 1500) helper.fail("pl02 the guard never reached the scene: the victim " + Police.taskForTests(victim) + ", the guard " + Police.taskForTests(g));
        });
    }

    // ============================================================ pl03: a thief seen in the act, chased and caught

    /**
     * A guard six blocks off sees a purse picked in front of it: "Stop, thief!", the culprit runs for it, and the guard
     * runs it down, catches it by the collar and arrests it; the case has the culprit accused, caught in the act, and the
     * town's books have the chase.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl03_chase")
    public static void pl03_chase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1544000;
        start(level, x, 56);
        BlockPos heart = flat(level, x, Z, 48);
        morning(level, 3000L);
        VillageFolkEntity victim = folk(helper, level, heart, "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-1, 0, 0), "the culprit");
        VillageFolkEntity g = guard(helper, level, heart.offset(6, 0, 0), "the guard");
        UUID village = victim.ownerId();
        culprit.setJob(StationTask.FARM);
        victim.spend(victim.purse());
        victim.earn(10);
        int[] id = { -1 };
        double[] ran = { 0.0 };
        BlockPos[] scene = { heart };
        helper.runAtTickTime(20, () -> {
            at(victim, heart.offset(0, 0, 2), Direction.NORTH);
            at(culprit, heart.offset(-1, 0, 2), Direction.EAST);
            at(g, heart.offset(6, 0, 2), Direction.WEST);
            g.setNoAi(true);                                             // looking that way when it is done
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            g.setNoAi(false);
            helper.assertTrue(c != null, "the purse is picked");
            id[0] = c.id;
            scene[0] = culprit.blockPosition();
            Kit.log("pl03 the deed: the guard " + Police.taskForTests(g) + ", the culprit " + Police.taskForTests(culprit));
            helper.assertTrue("CHASE".equals(Police.taskForTests(g)) && "FLEE".equals(Police.taskForTests(culprit)), "seen in the act: the chase is on");
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("pl03 the case is gone"); return; }
            ran[0] = Math.max(ran[0], Math.sqrt(culprit.blockPosition().distSqr(scene[0])));
            if (t % 40 == 0) {
                Kit.log("pl03 tick " + t + ": the culprit " + Police.doingForTests(culprit) + ", " + String.format("%.1f", ran[0]) + " from the scene at most; the guard "
                    + Police.doingForTests(g) + ", " + String.format("%.1f", g.distanceTo(culprit)) + " behind");
            }
            String held = Police.custodyForTests(culprit);
            if (!held.isEmpty()) {
                logCase("pl03", c);
                long day = level.getDayTime() / 24000L;
                boolean caughtClue = false;
                for (Crime.Clue k : c.clues()) if (k.text().contains("Caught in the act by " + g.displayNameCap())) caughtClue = true;
                Kit.log("pl03 caught after a run of " + String.format("%.1f", ran[0]) + " blocks; in custody: " + held);
                helper.assertTrue(ran[0] >= 3.0, "a real chase: the culprit ran for it, " + String.format("%.1f", ran[0]) + " blocks");
                helper.assertTrue(c.stage() == Crime.Stage.ACCUSED && culprit.getUUID().equals(c.accused()), "the culprit accused: " + c.stage());
                helper.assertTrue(caughtClue, "caught in the act, on the case");
                helper.assertTrue(Police.guardRecordForTests(g).getInt("chases") == 1, "a chase won on the guard's record");
                helper.assertTrue(Police.tallyForTests(village, day - 1, "chase") == 1, "the chase in the town's books");
                helper.succeed();
                return;
            }
            if (Police.taskForTests(g).isEmpty() && t > 30) helper.fail("pl03 the guard gave up the chase: " + Police.doingForTests(g));
            if (t > 1500) helper.fail("pl03 not caught: the guard " + Police.taskForTests(g) + ", " + String.format("%.1f", g.distanceTo(culprit)) + " behind");
        });
    }

    // ============================================================ pl04: a fight broken up

    /**
     * Two folk come to blows in the street. The guard sixteen blocks off runs to it, pushes them apart and takes their
     * names: a warning each the first time, and both walked home; the second time a fine of two coins each, out of their
     * purses into the treasury.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2000, batch = "pl04_fight")
    public static void pl04_fight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1546000;
        start(level, x, 48);
        BlockPos heart = flat(level, x, Z, 40);
        morning(level, 3000L);
        folk(helper, level, heart, "a neighbour");                                  // the first raised: the town's middle
        BlockPos street = heart.offset(16, 0, 10);
        VillageFolkEntity a = folk(helper, level, street, "the first");
        VillageFolkEntity b = folk(helper, level, street.offset(2, 0, 0), "the second");
        VillageFolkEntity g = guard(helper, level, street.offset(0, 0, -16), "the guard");
        UUID village = a.ownerId();
        for (VillageFolkEntity f : List.of(a, b)) {
            f.setJob(StationTask.FARM);
            f.spend(f.purse());
            f.earn(10);
        }
        int[] round = { 0 };
        int[] before = new int[3];
        int[] start = new int[2];
        helper.runAtTickTime(20, () -> {
            start[0] = a.purse();
            start[1] = b.purse();
            Police.fightForTests(level, a, b);
            Kit.log("pl04 the fight: " + Police.taskForTests(a) + "/" + Police.taskForTests(b) + "; the guard " + Police.taskForTests(g));
            helper.assertTrue("BRAWL".equals(Police.taskForTests(a)) && "BRAWL".equals(Police.taskForTests(b)), "the two come to blows");
            helper.assertTrue("SEPARATE".equals(Police.taskForTests(g)), "the guard is called to it");
            round[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (round[0] == 0) return;
            CompoundTag rec = Police.guardRecordForTests(g);
            if (t % 100 == 0) Kit.log("pl04 tick " + t + ": round " + round[0] + ", the guard " + Police.doingForTests(g) + ", fights " + rec.getInt("fights"));
            if (round[0] == 1 && rec.getInt("fights") == 1) {
                CompoundTag ra = Police.folkRecordForTests(a), rb = Police.folkRecordForTests(b);
                Kit.log("pl04 broken up at tick " + t + ": " + Police.taskForTests(a) + "/" + Police.taskForTests(b) + "; warnings " + rec.getInt("warnings"));
                helper.assertTrue(ra.getInt("brawls") == 1 && rb.getInt("brawls") == 1, "both names taken");
                helper.assertTrue(rec.getInt("warnings") == 2 && a.purse() >= start[0] && b.purse() >= start[1],
                    "a warning each, the first time, and no fine: purses " + start[0] + " -> " + a.purse() + ", " + start[1] + " -> " + b.purse());
                helper.assertTrue("WALKED".equals(Police.taskForTests(a)) && "WALKED".equals(Police.taskForTests(b)), "and each sent home");
                // The second time.
                Police.endTaskForTests(a);
                Police.endTaskForTests(b);
                at(g, street.offset(0, 0, -16), Direction.SOUTH);
                at(a, street, Direction.EAST);
                at(b, street.offset(2, 0, 0), Direction.WEST);
                before[0] = a.purse();
                before[1] = b.purse();
                before[2] = Ledger.coins(village);
                Police.fightForTests(level, a, b);
                round[0] = 2;
                return;
            }
            if (round[0] == 2 && rec.getInt("fights") == 2) {
                long day = level.getDayTime() / 24000L;
                Kit.log("pl04 the second fight broken up at tick " + t + ": purses " + before[0] + " -> " + a.purse() + ", " + before[1] + " -> " + b.purse()
                    + "; the treasury " + before[2] + " -> " + Ledger.coins(village));
                helper.assertTrue(a.purse() == before[0] - 2 && b.purse() == before[1] - 2, "two coins' fine each, out of their purses");
                helper.assertTrue(Ledger.coins(village) == before[2] + 4, "into the treasury");
                helper.assertTrue(Police.tallyForTests(village, day - 1, "fight") == 2, "both fights in the town's books");
                helper.succeed();
                return;
            }
            if (t > 1900) helper.fail("pl04 round " + round[0] + " never broken up: the guard " + Police.taskForTests(g) + ", fights " + rec.getInt("fights"));
        });
    }

    // ============================================================ pl05: arrest, the cells, the court, jail and release

    /**
     * The whole of a prisoner's time with the watch. A culprit accused of a purse of twelve coins is arrested of an
     * afternoon (after the court's hours) and walked on a lead to a cell of the watch house (iron bars, an iron door),
     * locked in and held. The council sits: the watch walks it there; it owns up and is sentenced to a day in the cells,
     * is walked back and locked in again, has its dinner out of the stores at the town's cost the next noon, and at the
     * end of its day is let out with a word.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "pl05_custody")
    public static void pl05_custody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1548000;
        start(level, x, 56);
        BlockPos heart = flat(level, x, Z, 46);
        morning(level, 11000L);                                          // after the court's hours: nobody tried before the test says
        folk(helper, level, heart, "a neighbour");                                  // the first raised: the town's middle
        VillageFolkEntity victim = folk(helper, level, heart.offset(-8, 0, 10), "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-9, 0, 10), "the culprit");
        BlockPos site = heart.offset(16, 0, -18);
        List<VillageFolkEntity> watch = new ArrayList<>();
        for (int i = 0; i < 3; i++) watch.add(guard(helper, level, heart.offset(14 + 2 * i, 0, -6), "guard " + (i + 1)));
        VillageFolkEntity g = watch.get(0);
        UUID village = victim.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.STONE);
        culprit.setJob(StationTask.FARM);
        victim.spend(victim.purse());
        victim.earn(12);
        BlockPos chest = stores(level, heart, new ItemStack(Items.BREAD, 8), new ItemStack(Items.IRON_BARS, 16), new ItemStack(Items.IRON_DOOR, 2),
            new ItemStack(Items.LEAD, 2), new ItemStack(Items.BOOK), new ItemStack(Items.INK_SAC), new ItemStack(Items.FEATHER));
        int[] phase = { 0 }, id = { -1 };
        long[] mark = { 0L, -1L };
        boolean[] seen = new boolean[4];                                 // on the lead, walked to the court, in the dock, walked back
        helper.runAtTickTime(10, () -> {
            Police.watchHouseForTests(level, v, site);
            for (int i = 0; i < 3; i++) Police.fitForTests(level, v);   // the bars, then a door at a time
            List<List<BlockPos>> cells = Police.cellsForTests(village);
            helper.assertTrue(cells.size() == 2, "a watch house with two cells: " + cells.size());
            for (List<BlockPos> c : cells) {
                helper.assertTrue(level.getBlockState(c.get(3)).is(Blocks.IRON_DOOR), "an iron door on each cell");
                for (int k = 5; k < c.size(); k++) helper.assertTrue(level.getBlockState(c.get(k)).is(Blocks.IRON_BARS), "iron bars on its front");
            }
        });
        helper.runAtTickTime(30, () -> {
            for (int i = 0; i < 3; i++) at(watch.get(i), heart.offset(14 + 2 * i, 0, -6), Direction.NORTH);   // out of sight of it
            at(victim, heart.offset(-8, 0, 10), Direction.NORTH);
            at(culprit, heart.offset(-9, 0, 10), Direction.EAST);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "the purse is picked");
            Police.worthForTests(c, 12);
            Crime.honestyForTests(culprit, 70);                          // it will own up before the council
            Crime.accuseForTests(c, culprit);
            id[0] = c.id;
            helper.assertTrue(Police.arrestForTests(level, g, culprit, c.id, "picking a purse"), "arrested");
            helper.assertTrue("LED".equals(Police.custodyForTests(culprit)), "on its way to the cells: " + Police.custodyForTests(culprit));
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { helper.fail("pl05 the case is gone"); return; }
            String state = Police.custodyForTests(culprit);
            CompoundTag rec = Police.custodyRecordForTests(culprit);
            List<BlockPos> cell = Police.cellsForTests(village).get(Math.max(0, rec.getInt("cell")));
            BlockPos inside = cell.get(0), door = cell.get(3);
            double in = Math.sqrt(culprit.distanceToSqr(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5));
            long now = level.getDayTime(), day = now / 24000L;
            if (culprit.isLeashed() && culprit.getLeashHolder() == g) seen[0] = true;
            if ("COURT_WALK".equals(state)) seen[1] = true;
            if ("COURT".equals(state)) seen[2] = true;
            if ("BACK".equals(state)) seen[3] = true;
            if (t % 100 == 0) {
                Kit.log("pl05 tick " + t + " phase " + phase[0] + ": " + state + " (" + Police.doingForTests(culprit) + "), " + String.format("%.1f", in)
                    + " from the cell; the guard " + Police.doingForTests(g) + "; the case " + c.stage() + " | " + g.debugLine());
            }
            switch (phase[0]) {
                case 1 -> {                                                     // walked to the cell on a lead
                    if (!"CELL".equals(state)) {
                        if (t > 1400) helper.fail("pl05 never locked in: " + state + ", " + String.format("%.1f", in) + " from the cell");
                        return;
                    }
                    Kit.log("pl05 locked in at tick " + t + ", " + String.format("%.1f", in) + " from the cell's middle");
                    helper.assertTrue(seen[0], "walked there on the guard's lead");
                    helper.assertTrue(in <= 1.2, "in the cell");
                    helper.assertTrue(!level.getBlockState(door).getValue(DoorBlock.OPEN), "the cell's iron door shut on it");
                    helper.assertTrue(!culprit.isLeashed(), "off the lead");
                    mark[0] = t;
                    phase[0] = 2;
                }
                case 2 -> {                                                     // held, then the council sits
                    if (t < mark[0] + 80) return;
                    helper.assertTrue("CELL".equals(state) && in <= 1.5, "held in its cell");
                    Crime.hurryForTests(true);
                    helper.assertTrue(Crime.trialNowForTests(level, v), "the council sits");
                    phase[0] = 3;
                }
                case 3 -> {                                                     // the court, and the sentence
                    if (c.stage() != Crime.Stage.CONVICTED) {
                        if (!c.stage().open()) { logCase("pl05", c); helper.fail("pl05 the case ended " + c.stage()); }
                        if (t > 3600) { logCase("pl05", c); helper.fail("pl05 never sentenced: " + state + ", " + c.stage()); }
                        return;
                    }
                    logCase("pl05", c);
                    Kit.log("pl05 sentenced at tick " + t + ": walked to the council " + seen[1] + ", in the dock " + seen[2]);
                    helper.assertTrue(seen[1], "walked to the council by the watch");
                    helper.assertTrue("jail".equals(Crime.sentenceForTests(culprit)), "sentenced to the cells: " + c.sentence());
                    phase[0] = 4;
                }
                case 4 -> {                                                     // back to the cell to serve it
                    if (!"CELL".equals(state) || rec.getLong("until") <= 0) {
                        if (t > 4600) helper.fail("pl05 never taken back: " + state);
                        return;
                    }
                    Kit.log("pl05 back in the cells at tick " + t + " till " + rec.getLong("until") + " (now " + now + ")");
                    helper.assertTrue(seen[3] || in <= 1.2, "walked back to its cell");
                    helper.assertTrue(in <= 1.2 && rec.getLong("until") >= now + 20000L, "serving its day in the cell");
                    // The next noon: its dinner, at the town's cost.
                    level.setDayTime((day + 1) * 24000L + 6100L);
                    mark[0] = t;
                    phase[0] = 5;
                }
                case 5 -> {                                                     // fed
                    if (rec.getLong("fed") != day) {
                        if (t > mark[0] + 400) helper.fail("pl05 never fed in the cells");
                        return;
                    }
                    Kit.log("pl05 fed at tick " + t + " on day " + day + "; the stores' bread now " + Crime.storesForTests(level, v, Items.BREAD));
                    helper.assertTrue(Police.tallyForTests(village, day, "fed") >= 1, "its dinner out of the stores, on the watch's books");
                    helper.assertTrue("CELL".equals(state) && rec.getLong("until") > now, "still serving");
                    level.setDayTime(rec.getLong("until") + 200L);              // its time up
                    phase[0] = 6;
                }
                case 6 -> {                                                     // let out
                    if (!state.isEmpty()) {
                        if (t > 5800) helper.fail("pl05 never let out: " + state);
                        return;
                    }
                    Kit.log("pl05 let out at tick " + t + "; " + Police.doingForTests(culprit));
                    helper.assertTrue(Police.tallyForTests(village, day - 3, "release") >= 1, "let out, in the town's books");
                    helper.assertTrue(!"jail".equals(Crime.sentenceForTests(culprit)), "its time served");
                    helper.assertTrue(Police.folkRecordForTests(culprit).getInt("arrested") == 1, "the arrest on its record");
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ============================================================ pl06: the beat puts a thief off where it walks

    /**
     * A guard on the beat: its route takes in the market and the square. Where it has just stood the chance of a deed is
     * a quarter of what it was, and a folk minded to steal there thinks better of it ("the beat") with the guard long out
     * of sight; across the town where no beat has been, nothing puts it off. And the town's temptations are fewer.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl06_beat")
    public static void pl06_beat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1550000;
        start(level, x, 56);
        BlockPos heart = flat(level, x, Z, 46);
        morning(level, 3000L);
        VillageFolkEntity tempted = folk(helper, level, heart, "a folk minded to steal");   // the first raised: the town's middle
        VillageFolkEntity beat = guard(helper, level, heart.offset(4, 0, 0), "the guard on the beat");
        VillageFolkEntity other = guard(helper, level, heart.offset(0, 0, -36), "a guard resting");
        at(tempted, heart.offset(-36, 0, 0), Direction.NORTH);
        UUID village = beat.ownerId();
        Villages.Village v = Villages.get(village);
        // The town's leader at its own work by the heart: a leader out about the streets has a guard walk with it, and a
        // leader stood still out on a street all test long (the folk minded to steal, were it the leader) took the beat.
        VillageFolkEntity leader = folk(helper, level, heart.offset(-4, 0, -4), "the leader");
        leader.setJob(StationTask.FARM);
        leader.setWorkZone(com.jrpetty.mcassistant.entity.WorkZone.around(leader.blockPosition(), 6, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH));
        leader.keepTradeForTests();
        Villages.electElder(village, leader, level.getDayTime() / 24000L);
        Villages.builtAtForTests(village, "market", heart.offset(-12, 0, 12));
        tempted.setNoAi(true);
        BlockPos far = heart.offset(0, 0, 38);
        BlockPos[] market = { null };
        int[] phase = { 0 };
        helper.runAtTickTime(20, () -> {
            Police.setDutyForTests(level, beat, "BEAT");
            Police.setDutyForTests(level, other, "REST");
            List<String> names = Police.beatNamesForTests(level, beat);
            List<BlockPos> stops = Police.beatForTests(level, beat);
            Kit.log("pl06 the beat: " + String.join(" | ", names));
            for (int i = 0; i < names.size(); i++) if (names.get(i).startsWith("the market")) market[0] = stops.get(i);
            helper.assertTrue(market[0] != null, "the beat takes in the market: " + names);
            helper.assertTrue(names.stream().anyMatch(n -> n.startsWith("the square")), "and the square: " + names);
            Kit.log("pl06 before the beat: the chance at the market " + String.format("%.2f", Police.chanceAtForTests(level, village, market[0]))
                + ", the day's temptations x" + String.format("%.2f", Police.coverForTests(level, v)));
            // Set down by the market: its first stop.
            at(beat, market[0].offset(3, 0, 0), Direction.WEST);
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] != 1) return;
            boolean onDuty = t % 4 != 0 || Police.dutyStepForTests(level, beat);
            double atMarket = Police.chanceAtForTests(level, village, market[0]);
            if (t % 100 == 0) Kit.log("pl06 tick " + t + ": the guard " + String.format("%.1f", Math.sqrt(beat.blockPosition().distSqr(market[0])))
                + " from the market stop; the chance there " + String.format("%.2f", atMarket) + "; its duty " + Police.dutyForTests(level, beat)
                + (onDuty ? "" : " (the duty let it be)") + " | " + beat.debugLine());
            if (atMarket > 0.5) {
                if (t > 1400) helper.fail("pl06 the beat never stood at the market");
                return;
            }
            // The guard gone on, well out of sight.
            at(beat, heart.offset(34, 0, 34), Direction.NORTH);
            beat.setNoAi(true);
            at(tempted, market[0], Direction.NORTH);
            String there = Police.deterrentForTests(level, tempted, Crime.Kind.PICKPOCKET, market[0]);
            at(tempted, far, Direction.NORTH);
            String elsewhere = Police.deterrentForTests(level, tempted, Crime.Kind.PICKPOCKET, far);
            double chanceFar = Police.chanceAtForTests(level, village, far);
            double cover = Police.coverForTests(level, v);
            Kit.log("pl06 at the market: chance " + String.format("%.2f", atMarket) + ", put off by " + there + "; across the town: chance "
                + String.format("%.2f", chanceFar) + ", put off by " + elsewhere + "; the day's temptations x" + String.format("%.2f", cover));
            helper.assertTrue(atMarket <= 0.5 && chanceFar == 1.0, "the chance of a deed is lower where the beat passed, and only there");
            helper.assertTrue("the beat".equals(there), "a thief at the market thinks better of it: " + there);
            helper.assertTrue(elsewhere == null, "nothing puts it off where the beat has not been: " + elsewhere);
            helper.assertTrue(cover < 1.0, "the town's temptations fewer for its beats: " + cover);
            helper.succeed();
        });
    }

    // ============================================================ pl07: the curfew, a warning then a fine

    /**
     * The council calls a curfew. Of an evening after the tenth bell the guard on the beat finds a folk out on the square:
     * a warning, and sent home. Out again, the same night: two coins' fine, into the treasury.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl07_curfew")
    public static void pl07_curfew(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1552000;
        start(level, x, 48);
        BlockPos heart = flat(level, x, Z, 40);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 15400L);
        level.updateSkyBrightness();
        VillageFolkEntity out = folk(helper, level, heart, "a folk out late");     // the first raised: the town's middle
        VillageFolkEntity g = guard(helper, level, heart.offset(0, 0, -6), "the guard");
        UUID village = g.ownerId();
        Villages.Village v = Villages.get(village);
        out.setJob(StationTask.FARM);
        out.spend(out.purse());
        out.earn(10);
        BlockPos[] square = { null };
        int[] phase = { 0 };
        helper.runAtTickTime(20, () -> {
            String called = Police.curfewForTests(level, v, true);
            Police.setDutyForTests(level, g, "BEAT");
            List<String> names = Police.beatNamesForTests(level, g);
            List<BlockPos> stops = Police.beatForTests(level, g);
            for (int i = 0; i < names.size(); i++) if (names.get(i).startsWith("the square")) square[0] = stops.get(i);
            Kit.log("pl07 " + called + "; the evening beat: " + String.join(" | ", names));
            helper.assertTrue(called.startsWith("CURFEW on"), "the curfew is called");
            helper.assertTrue(square[0] != null, "the evening beat takes in the square");
            helper.assertTrue(Police.boardForTests(level, village).contains("Curfew"), "and the board says so");
            at(out, square[0].offset(1, 0, 1), Direction.NORTH);
            out.setNoAi(true);                                           // standing about on the square
            at(g, square[0].offset(4, 0, 0), Direction.WEST);
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] != 1) return;
            if (t % 4 == 0) Police.dutyStepForTests(level, g);
            CompoundTag r = Police.folkRecordForTests(out);
            if (t % 100 == 0) Kit.log("pl07 tick " + t + ": the guard " + String.format("%.1f", g.distanceTo(out)) + " from the folk; its curfew record " + r.getInt("curfew"));
            if (r.getInt("curfew") < 1) {
                if (t > 1400) helper.fail("pl07 the beat never found the folk out after curfew");
                return;
            }
            Kit.log("pl07 warned at tick " + t + ": " + Police.taskForTests(out) + "; the guard's warnings " + Police.guardRecordForTests(g).getInt("warnings"));
            helper.assertTrue("WALKED".equals(Police.taskForTests(out)) && out.purse() == 10, "a warning, no fine, and sent home");
            helper.assertTrue(Police.guardRecordForTests(g).getInt("warnings") == 1, "a warning on the guard's record");
            // Out again.
            Police.endTaskForTests(out);
            int treasury = Ledger.coins(village);
            String second = Police.curfewCheckForTests(level, g, out);
            Kit.log("pl07 out again: " + second + "; the purse " + out.purse() + ", the treasury " + treasury + " -> " + Ledger.coins(village));
            helper.assertTrue("fined".equals(second) && out.purse() == 8, "the second time a fine of two coins");
            helper.assertTrue(Ledger.coins(village) == treasury + 2, "into the treasury");
            helper.assertTrue(Police.folkRecordForTests(out).getInt("curfew") == 2, "both on its record");
            helper.succeed();
        });
    }

    // ============================================================ pl08: a player who helps itself to the stores

    /**
     * A player takes bread from the town's stores in front of a folk. The first time the guard comes to it with a
     * warning ("Put that back, stranger") and, the bread not put back, has it back for the stores; the second time a
     * fine out of its purse into the treasury and the bread taken back; the third time it is barred: the shops refuse it
     * and its name is on the board as wanted, though the watch does not fight it. It pays its fine to the watch, and its
     * name is clear.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "pl08_player_thief")
    public static void pl08_player_thief(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1554000;
        start(level, x, 40);
        BlockPos heart = flat(level, x, Z, 30);
        morning(level, 3000L);
        VillageFolkEntity seer = folk(helper, level, heart.offset(-3, 0, 0), "a folk who sees it");   // the first raised
        VillageFolkEntity g = guard(helper, level, heart.offset(10, 0, 0), "the guard");
        UUID village = g.ownerId();
        Villages.Village v = Villages.get(village);
        seer.setJob(StationTask.FARM);
        BlockPos chest = stores(level, heart, new ItemStack(Items.BREAD, 10));
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.teleportTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 1.5);
        p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
        int[] phase = { 0 };
        helper.runAtTickTime(20, () -> {
            takeFrom(level, chest, Items.BREAD, 3);
            p.getInventory().add(new ItemStack(Items.BREAD, 3));
            PlayerLaw.offenceForTests(level, v, p, seer, "taking 3 bread from the stores", 2, Map.of(Items.BREAD, 3));
            CompoundTag r = PlayerLaw.recordForTests(village, p.getUUID());
            Kit.log("pl08 the first time: " + r + "; the guard " + Police.taskForTests(g));
            helper.assertTrue(r.getInt("offences") == 1 && r.getInt("warnings") == 1 && !r.getBoolean("barred"), "a warning the first time");
            helper.assertTrue("PLAYER".equals(Police.taskForTests(g)), "the guard comes to it with the warning");
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] != 1) return;
            if (!Police.taskForTests(g).isEmpty()) {
                if (t > 1000) { Kit.noLeftoverPlayers(level); helper.fail("pl08 the guard never got to the player"); }
                return;
            }
            phase[0] = 2;
            Kit.log("pl08 warned in person at tick " + t + ", the guard " + String.format("%.1f", g.distanceTo(p)) + " off");
            boolean near = g.distanceTo(p) <= 4.0;
            // Not put back: the watch has it back.
            int base = Crime.storesForTests(level, v, Items.BREAD);
            PlayerLaw.putBackTimeForTests(p.getUUID());
            PlayerLaw.tickForTests(level, v);
            int carried = p.getInventory().countItem(Items.BREAD), stocked = Crime.storesForTests(level, v, Items.BREAD) - base;
            // The second time: a fine, and the bread taken back.
            int base2 = Crime.storesForTests(level, v, Items.BREAD);
            takeFrom(level, chest, Items.BREAD, 3);
            p.getInventory().add(new ItemStack(Items.BREAD, 3));
            int coins = Market.coinsHeld(p), treasury = Ledger.coins(village);
            PlayerLaw.offenceForTests(level, v, p, seer, "taking 3 bread from the stores", 2, Map.of(Items.BREAD, 3));
            int fined = coins - Market.coinsHeld(p), paidIn = Ledger.coins(village) - treasury;
            int carried2 = p.getInventory().countItem(Items.BREAD), stocked2 = Crime.storesForTests(level, v, Items.BREAD) - base2;
            // The third: barred.
            takeFrom(level, chest, Items.BREAD, 3);
            p.getInventory().add(new ItemStack(Items.BREAD, 3));
            PlayerLaw.offenceForTests(level, v, p, seer, "taking 3 bread from the stores", 2, Map.of(Items.BREAD, 3));
            long day = level.getDayTime() / 24000L;
            boolean barred = PlayerLaw.barredForTests(village, p.getUUID());
            boolean refused = Laws.banished(village, p.getUUID(), day), fought = Laws.outlaw(village, p);
            String board = Police.boardForTests(level, village);
            CompoundTag r = PlayerLaw.recordForTests(village, p.getUUID());
            // It pays.
            int before = Market.coinsHeld(p);
            String paid = PlayerLaw.payForTests(g, p);
            boolean clear = !PlayerLaw.barredForTests(village, p.getUUID()) && !Laws.banished(village, p.getUUID(), day);
            int after = Market.coinsHeld(p);
            Kit.noLeftoverPlayers(level);
            Kit.log("pl08 not put back: carried " + carried + ", the stores +" + stocked + "; the second time fined " + fined + " (" + paidIn
                + " to the treasury), carried " + carried2 + ", the stores +" + stocked2 + "; the third: " + r + "; barred " + barred + ", refused " + refused
                + ", fought " + fought + "\npl08 the board:\n" + board + "\npl08 pays: " + paid + " (" + before + " -> " + after + "); clear " + clear);
            helper.assertTrue(near, "the guard came to it");
            helper.assertTrue(carried == 0 && stocked == 3, "not put back in time: the watch had the bread back for the stores");
            helper.assertTrue(fined == 2 && paidIn == 2, "the second time a fine of two, into the treasury");
            helper.assertTrue(carried2 == 0 && stocked2 == 0, "and the bread taken back");
            helper.assertTrue(barred && refused && !fought, "the third time barred: refused by the town, but not fought");
            helper.assertTrue(board.contains(p.getName().getString()), "its name on the board as wanted");
            helper.assertTrue(before - after == r.getInt("fee") && r.getInt("fee") >= 10, "it pays its fine to the watch: " + paid);
            helper.assertTrue(clear, "and its name is clear: " + paid);
            helper.succeed();
        });
    }

    // ============================================================ pl09: a special constable

    /**
     * A citizen asks to be sworn in. The town has no badge, so its workshop makes one of the stores' iron ingot and four
     * gold nuggets; then the guard swears it in and binds the badge to it. Shown to an innocent folk the badge holds
     * nobody ("On what charge?"); shown to the accused culprit, it is an arrest: the culprit follows the constable to the
     * guard, who takes it in. The council convicts, the case is closed, and the treasury pays the constable its wage.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pl09_constable")
    public static void pl09_constable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1556000;
        start(level, x, 48);
        BlockPos heart = flat(level, x, Z, 40);
        morning(level, 11000L);                                          // after the court's hours: tried when the test says
        VillageFolkEntity innocent = folk(helper, level, heart, "an innocent");    // the first raised: the town's middle
        VillageFolkEntity g = guard(helper, level, heart.offset(0, 0, -12), "the guard");
        VillageFolkEntity victim = folk(helper, level, heart.offset(-8, 0, 8), "the victim");
        VillageFolkEntity culprit = folk(helper, level, heart.offset(-9, 0, 8), "the culprit");
        UUID village = g.ownerId();
        Villages.Village v = Villages.get(village);
        for (VillageFolkEntity f : List.of(victim, culprit, innocent)) f.setJob(StationTask.FARM);
        victim.spend(victim.purse());
        victim.earn(10);
        // An Iron Age town (a badge is iron and gold worked together), whose constable wears its own badge already.
        Villages.ageForTests(village, Villages.Age.IRON);
        g.insertItem(new ItemStack(PoliceItems.CONSTABLE_BADGE.get()));
        BlockPos chest = stores(level, heart, new ItemStack(Items.IRON_INGOT, 24), new ItemStack(Items.GOLD_NUGGET, 4));
        Ledger.addCoins(village, 20);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.teleportTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
        Ledger.addCitizen(village, p.getUUID(), p.getName().getString());
        int[] phase = { 0 }, id = { -1 }, coins = { 0 };
        ItemStack[] badge = { ItemStack.EMPTY };
        helper.runAtTickTime(20, () -> {
            String first = PlayerLaw.swearForTests(g, p);
            int iron0 = Crime.storesForTests(level, v, Items.IRON_INGOT), gold0 = Crime.storesForTests(level, v, Items.GOLD_NUGGET);
            Kit.log("pl09 the stores before: iron " + iron0 + ", nuggets " + gold0 + ", gold " + Crime.storesForTests(level, v, Items.GOLD_INGOT)
                + "; the age " + Villages.ageOf(village) + "; the chest's own: iron " + count(level, chest, Items.IRON_INGOT));
            boolean made = Police.makeBadgeForTests(level, v);
            int iron = iron0 - Crime.storesForTests(level, v, Items.IRON_INGOT), gold = gold0 - Crime.storesForTests(level, v, Items.GOLD_NUGGET);
            String second = PlayerLaw.swearForTests(g, p);
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (p.getInventory().getItem(i).is(PoliceItems.CONSTABLE_BADGE.get())) badge[0] = p.getInventory().getItem(i);
            }
            Kit.log("pl09 asked: " + first + "\npl09 made " + made + " (of " + iron + " iron ingot and " + gold + " gold nuggets from the stores)\npl09 sworn: " + second);
            if (badge[0].isEmpty() || !PlayerLaw.swornForTests(village, p.getUUID())) { Kit.noLeftoverPlayers(level); helper.fail("pl09 not sworn: " + second); return; }
            helper.assertTrue(first.contains("badge") && made && iron == 1 && gold == 4, "a badge made of the stores' iron and gold for it");
            helper.assertTrue(village.equals(PlayerLaw.badgeTownForTests(badge[0])), "the badge sworn to the town");
            // Shown to an innocent: no charge, no arrest.
            String refused = PlayerLaw.badgeArrestForTests(level, p, innocent, badge[0]);
            helper.assertTrue(refused != null && refused.contains("On what charge") && Police.taskForTests(innocent).isEmpty(), "nobody is held on a badge alone: " + refused);
            // The culprit, accused.
            at(g, heart.offset(0, 0, -12), Direction.NORTH);             // its back to it, well off
            at(victim, heart.offset(-8, 0, 8), Direction.NORTH);
            at(culprit, heart.offset(-9, 0, 8), Direction.EAST);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "the purse is picked");
            Crime.honestyForTests(culprit, 70);
            Crime.accuseForTests(c, culprit);
            id[0] = c.id;
            String arrest = PlayerLaw.badgeArrestForTests(level, p, culprit, badge[0]);
            Kit.log("pl09 refused: " + refused + "\npl09 the arrest: " + arrest);
            helper.assertTrue("LED".equals(Police.taskForTests(culprit)), "the culprit comes along with the constable: " + arrest);
            // The constable walks to the guard.
            p.teleportTo(g.getX() + 2.0, g.getY(), g.getZ());
            g.setNoAi(true);
            coins[0] = Market.coinsHeld(p);
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            if (c == null) { Kit.noLeftoverPlayers(level); helper.fail("pl09 the case is gone"); return; }
            if (t % 100 == 0) Kit.log("pl09 tick " + t + " phase " + phase[0] + ": the culprit " + Police.doingForTests(culprit) + ", "
                + String.format("%.1f", culprit.distanceTo(g)) + " from the guard; custody " + Police.custodyForTests(culprit) + "; the case " + c.stage());
            if (phase[0] == 1) {
                CompoundTag k = PlayerLaw.constableRecordForTests(village, p.getUUID());
                if (k.getInt("arrests") < 1) {
                    if (t > 1200) { Kit.noLeftoverPlayers(level); helper.fail("pl09 never handed over"); }
                    return;
                }
                Kit.log("pl09 handed over at tick " + t + ": custody " + Police.custodyForTests(culprit));
                if (Police.custodyForTests(culprit).isEmpty() && Police.tallyForTests(village, level.getDayTime() / 24000L - 1, "arrest") < 1) {
                    Kit.noLeftoverPlayers(level);
                    helper.fail("pl09 the guard did not take it in");
                    return;
                }
                g.setNoAi(false);
                Crime.hurryForTests(true);
                if (!Crime.trialNowForTests(level, v)) { Kit.noLeftoverPlayers(level); helper.fail("pl09 the council did not sit"); return; }
                phase[0] = 2;
                return;
            }
            if (c.stage().open()) {
                if (t > 2800) { Kit.noLeftoverPlayers(level); logCase("pl09", c); helper.fail("pl09 never closed: " + c.stage()); }
                return;
            }
            logCase("pl09", c);
            CompoundTag k = PlayerLaw.constableRecordForTests(village, p.getUUID());
            int wage = Market.coinsHeld(p) - coins[0];
            Kit.noLeftoverPlayers(level);
            Kit.log("pl09 closed: " + c.stage() + "; the constable " + k + "; its coins +" + wage);
            helper.assertTrue(c.stage() == Crime.Stage.CONVICTED, "the council convicts: " + c.verdict());
            helper.assertTrue(k.getInt("cases") == 1 && k.getInt("paid") == 3 && wage == 3, "the constable paid three coins for the case: " + k);
            helper.succeed();
        });
    }

    // ============================================================ pl10: the watch house goes up

    /**
     * A Stone Age town with a watch of three wants a watch house, and says why. Up, the watch fits it out from the stores:
     * the cells' fence posts give way to iron bars and their doors to iron ones, the notice board of the wanted and the
     * day's roster goes up in signs, and the casebook goes on the desk's lectern. Two cells, each with its bed.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pl10_watch_house")
    public static void pl10_watch_house(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1558000;
        start(level, x, 48);
        BlockPos heart = flat(level, x, Z, 40);
        morning(level, 3000L);
        List<VillageFolkEntity> watch = new ArrayList<>();
        VillageFolkEntity f = folk(helper, level, heart, "a folk");                 // the first raised: the town's middle
        for (int i = 0; i < 3; i++) watch.add(guard(helper, level, heart.offset(-2 + 2 * i, 0, 4), "guard " + (i + 1)));
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos chest = stores(level, heart, new ItemStack(Items.IRON_BARS, 16), new ItemStack(Items.IRON_DOOR, 2), new ItemStack(Items.OAK_SIGN, 3),
            new ItemStack(Items.BOOK), new ItemStack(Items.INK_SAC), new ItemStack(Items.FEATHER));
        BlockPos site = heart.offset(0, 0, -20);
        helper.runAtTickTime(20, () -> {
            Villages.ageForTests(village, Villages.Age.STONE);
            boolean wants = Police.wantsWatchHouseForTests(village);
            List<String> projects = Villages.projectsWanted(village);
            Kit.log("pl10 the town wants: " + projects);
            helper.assertTrue(wants && projects.contains("watchhouse"), "a watch of three wants a watch house: " + projects);
            Police.watchHouseForTests(level, v, site);
            helper.assertTrue(!Police.wantsWatchHouseForTests(village), "and wants no second");
            List<List<BlockPos>> cells = Police.cellsForTests(village);
            helper.assertTrue(cells.size() == 2, "two cells: " + cells.size());
            for (List<BlockPos> c : cells) {
                helper.assertTrue(level.getBlockState(c.get(1)).getBlock() instanceof BedBlock && level.getBlockState(c.get(2)).getBlock() instanceof BedBlock,
                    "a bed in each cell, foot and head");
                helper.assertTrue(level.getBlockState(c.get(3)).getBlock() instanceof DoorBlock, "a door on each, as built: " + level.getBlockState(c.get(3)));
                helper.assertTrue(level.getBlockState(c.get(0)).isAir() && level.getBlockState(c.get(0).above()).isAir(), "room to stand in it");
            }
            for (int i = 0; i < 4; i++) Police.fitForTests(level, v);
            int barsLeft = Crime.storesForTests(level, v, Items.IRON_BARS), doorsLeft = Crime.storesForTests(level, v, Items.IRON_DOOR);
            for (List<BlockPos> c : cells) {
                helper.assertTrue(level.getBlockState(c.get(3)).is(Blocks.IRON_DOOR), "fitted with an iron door");
                for (int k = 5; k < c.size(); k++) helper.assertTrue(level.getBlockState(c.get(k)).is(Blocks.IRON_BARS), "and iron bars on its front");
            }
            BlockPos lectern = Police.watchHouseSpotForTests(village, "lectern");
            BlockPos roster = Police.watchHouseSpotForTests(village, "roster");
            BlockPos wanted = Police.watchHouseSpotForTests(village, "wantedA");
            boolean book = level.getBlockState(lectern).getBlock() instanceof LecternBlock && level.getBlockState(lectern).getValue(LecternBlock.HAS_BOOK);
            String rosterSign = level.getBlockEntity(roster) instanceof SignBlockEntity s ? s.getFrontText().getMessage(0, false).getString()
                + " " + s.getFrontText().getMessage(1, false).getString() : "";
            String wantedSign = level.getBlockEntity(wanted) instanceof SignBlockEntity s ? s.getFrontText().getMessage(0, false).getString() : "";
            Kit.log("pl10 fitted: the stores' bars " + barsLeft + ", doors " + doorsLeft + "; the casebook on the lectern " + book + "; the roster sign '"
                + rosterSign + "'; the wanted sign '" + wantedSign + "'");
            helper.assertTrue(barsLeft == 16 - 8 && doorsLeft == 0, "the iron out of the stores: eight bars and two doors");
            helper.assertTrue(book, "the casebook on the desk's lectern");
            helper.assertTrue(!rosterSign.isBlank() && wantedSign.contains("WANTED"), "the notice board: the roster and the wanted");
            helper.succeed();
        });
    }

    // ============================================================ pl11: the operators' stage sees to its own cast

    /**
     * A town of six farmers and no watch at all (as a played world's town can be, its one guard taken for the ferry or
     * the smithy). The operators' stage still has its pictures: it appoints three of the town to the watch (its folk,
     * and folk stood up beside its heart for the parts nobody is left for), and every scene is set — a prisoner in a
     * cell, a guard greeting a folk on the beat, a chase down the avenue by a guard of its own, and an arrest walked to
     * the cells on a lead, still on the lead a few seconds on (the leader's escort does not take a guard with a
     * prisoner on its lead).
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "pl11_stage_cast")
    public static void pl11_stage_cast(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1559000;
        start(level, x, 56);
        BlockPos heart = flat(level, x, Z, 48);
        morning(level, 3000L);
        List<VillageFolkEntity> town = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            VillageFolkEntity f = folk(helper, level, heart.offset(-5 + 2 * i, 0, i == 0 ? 0 : -3), "farmer " + (i + 1));
            f.setJob(StationTask.FARM);
            f.keepTradeForTests();
            town.add(f);
        }
        UUID village = town.get(0).ownerId();
        Villages.Village v = Villages.get(village);
        VillageFolkEntity[] led = new VillageFolkEntity[2];
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(watchOf(village).isEmpty(), "no watch to begin with");
            List<String> said = Police.stageForTests(level, v, heart.offset(24, 0, 22));
            String out = String.join("\n", said);
            Kit.log("pl11 the stage said:\n" + out);
            List<VillageFolkEntity> watch = watchOf(village);
            helper.assertTrue(out.contains("appointed to the watch for the stage"), "the stage appoints its own guards");
            helper.assertTrue(watch.size() >= 3, "three guards now: " + names(watch));
            for (String scene : List.of("cell", "house", "beat", "chase", "arrest")) {
                helper.assertTrue(out.contains("VIEW " + scene + " "), "a view of the " + scene);
            }
            helper.assertTrue(!out.contains("the town wants"), "nothing wanting for the cast");
            VillageFolkEntity beat = null, chaser = null, escort = null;
            for (VillageFolkEntity g : watch) {
                if ("BEAT".equals(Police.dutyForTests(level, g)) && Police.taskForTests(g).isEmpty()) beat = g;
                if (!Police.taskForTests(g).isEmpty()) chaser = g;
                if (Police.engaged(g) && Police.taskForTests(g).isEmpty()) escort = g;
            }
            Kit.log("pl11 on the beat " + (beat == null ? "nobody" : beat.displayNameCap()) + ", giving chase " + (chaser == null ? "nobody"
                : chaser.displayNameCap() + " (" + Police.taskForTests(chaser) + ")") + ", the escort " + (escort == null ? "nobody" : escort.displayNameCap()));
            helper.assertTrue(beat != null && chaser != null && escort != null && beat != chaser && chaser != escort,
                "a guard each for the beat, the chase and the arrest");
            int inCells = 0;
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity f)) continue;
                String c = Police.custodyForTests(f);
                if ("CELL".equals(c)) inCells++;
                if ("LED".equals(c)) led[0] = f;
            }
            helper.assertTrue(inCells == 1 && led[0] != null, "one in a cell and one on the lead: " + inCells + ", " + (led[0] != null));
            led[1] = escort;
        });
        helper.runAtTickTime(120, () -> {
            String c = Police.custodyForTests(led[0]);
            Kit.log("pl11 a hundred ticks on: " + led[0].displayNameCap() + " " + c + "; " + led[1].displayNameCap() + " " + led[1].debugLine());
            helper.assertTrue("LED".equals(c) || "CELL".equals(c), "the arrest still on its lead, or in the cells: " + c);
            helper.assertTrue(!com.jrpetty.mcassistant.entity.Patrols.escorting(led[1]), "the escort not taken to walk with the leader");
            helper.succeed();
        });
    }

    // ============================================================ pl12: the watch's first hand

    /**
     * A town of eight has no watch and wants none. Grown to twelve with no guard (its one guard gone), it wants one, and
     * its own sums would never find it a hand: none of its trades is over its share. The leader asks, once a day: a hand
     * from a trade that can spare it takes up the watch, says so, and the town is told; and not a second the same day.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pl12_first_hand")
    public static void pl12_first_hand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1559500;
        start(level, x, 48);
        BlockPos heart = flat(level, x, Z, 40);
        morning(level, 3000L);
        // Every trade at its share and none over it: seven farmers, two miners, a woodcutter, a smelter and a fisher.
        StationTask[] jobs = { StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.MINE, StationTask.MINE,
            StationTask.WOOD, StationTask.SMELT, StationTask.FARM, StationTask.FARM, StationTask.FARM, StationTask.FISH };
        List<VillageFolkEntity> town = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            VillageFolkEntity f = folk(helper, level, heart.offset(-7 + 2 * i, 0, i == 0 ? 0 : -4), jobs[i] + " " + (i + 1));
            f.setJob(jobs[i]);
            f.keepTradeForTests();
            town.add(f);
        }
        UUID village = town.get(0).ownerId();
        Villages.Village v = Villages.get(village);
        helper.runAtTickTime(20, () -> {
            double small = Villages.share(village, StationTask.GUARD);
            helper.assertTrue(Police.firstHandForTests(level, v) == null, "a town of eight keeps no watch: its share " + small);
            for (int i = 8; i < 12; i++) {
                VillageFolkEntity f = folk(helper, level, heart.offset(-7 + 2 * (i - 8), 0, 4), jobs[i] + " " + (i + 1));
                f.setJob(jobs[i]);
                f.keepTradeForTests();
                town.add(f);
            }
            double share = Villages.share(village, StationTask.GUARD);
            List<String> shares = new ArrayList<>(), over = new ArrayList<>();
            for (StationTask t : List.of(StationTask.FARM, StationTask.MINE, StationTask.WOOD, StationTask.SMELT, StationTask.FISH)) {
                shares.add(t + " " + String.format(java.util.Locale.ROOT, "%.2f", Villages.share(village, t)));
                if (Villages.overStaffed(village, t)) over.add(t.name());
            }
            Kit.log("pl12 at eight the watch's share " + String.format(java.util.Locale.ROOT, "%.2f", small) + "; at twelve "
                + String.format(java.util.Locale.ROOT, "%.2f", share) + "; the trades: " + shares + "; over: " + over + "; the vacancy " + Villages.vacancy(village));
            helper.assertTrue(over.isEmpty(), "no trade over its share, so the town's own sums move nobody: " + over);
            VillageFolkEntity g = Police.firstHandForTests(level, v);
            helper.assertTrue(g != null, "a town of twelve with no guard finds one: the watch's share " + share);
            Kit.log("pl12 " + g.displayNameCap() + " took up the watch; the town's news: " + (Villages.news(village).isEmpty() ? "" : Villages.news(village).get(0).text()));
            helper.assertTrue(g.stationTask() == StationTask.GUARD, "it is a guard now: " + g.stationTask());
            helper.assertTrue(!g.isElder(), "not the leader");
            helper.assertTrue(Police.tallyForTests(village, level.getDayTime() / 24000L, "watch") == 1, "the watch's book has it");
            helper.assertTrue(Police.firstHandForTests(level, v) == null, "and no second the same day");
            helper.succeed();
        });
    }
}
