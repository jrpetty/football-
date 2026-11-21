package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Envoys;
import com.jrpetty.mcassistant.entity.Intel;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Pickets;
import com.jrpetty.mcassistant.entity.Raids;
import com.jrpetty.mcassistant.entity.Spies;
import com.jrpetty.mcassistant.entity.Spying;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarScouting;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scouts at war (Spying, Spies, Pickets, Intel): a scout sent to an enemy town counts its guards from the
 * world and files a dated report; reports age and the leader's belief drifts further from them the older
 * they are, bent by its temper; the strength reckoning goes by the report; a spy near the walls is spotted
 * and run down by the watch, questioned, and let go at peace; a picket on the road sees the enemy coming
 * and the bell rings before they are at the gate.
 *
 * <p>Each test has its own batch and its own ground: x 780000 to 790000, z 66000. The towns' own look round
 * (WarScouting's tick) is held off in each, so that the test drives the part it is about.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class ScoutingGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** Grown folk of a town, in a fixed order. */
    private static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isBaby()) out.add(f);
        out.sort(java.util.Comparator.comparing(VillageFolkEntity::getUUID));
        return out;
    }

    /** A folk stood still on the ground here. */
    private static void standAt(ServerLevel level, VillageFolkEntity f, int x, int z) {
        BlockPos at = Kit.surface(level, x, z);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, f.getYRot(), 0.0F);
        f.getNavigation().stop();
    }

    private static void guard(VillageFolkEntity g) {
        g.setJob(StationTask.GUARD);
        g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
    }

    /** Two towns, a and b, so far apart along x; returns them, or fails. */
    private static Villages.Village[] twoTowns(GameTestHelper helper, ServerLevel level, int x, int apart, int na, int nb) {
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        Kit.hold(level, x + apart, Z, 100);
        Kit.prepare(level, x + apart, Z, 100);
        BlockPos ha = Kit.surface(level, x, Z), hb = Kit.surface(level, x + apart, Z);
        VillageFolkSpawnerBlock.raiseParty(level, ha, 0.0F, na);
        VillageFolkSpawnerBlock.raiseParty(level, hb, 0.0F, nb);
        Villages.Village a = Villages.nearest(level, ha, Villages.VILLAGE_RANGE);
        Villages.Village b = Villages.nearest(level, hb, Villages.VILLAGE_RANGE);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        return new Villages.Village[]{ a, b };
    }

    // ============================================================ si01: a scout counts the enemy

    /**
     * Two towns fall into a feud (on their guard: tension). The first sends one of its folk to watch the
     * second, which has four guards out of doors by its square, two in iron and one with a bow. The scout
     * lies at its vantage, counts what it sees, comes home and files a report dated the day it watched,
     * with the four guards, the two in iron and the bow in it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "si01_spy_counts")
    public static void si01_spy_counts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        WarScouting.pauseForTests(true);
        level.setDayTime(2000);
        int x = 780000;
        Villages.Village[] t = twoTowns(helper, level, x, 260, 3, 6);
        Villages.Village a = t[0], b = t[1];
        Ledger.relate(a.id(), b.id(), -60 - Ledger.relation(a.id(), b.id()));
        VillageFolkEntity[] spy = new VillageFolkEntity[1];
        long[] day = { -1 }, homeAt = { -1 };
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(Wars.footing(a.id()) == Wars.Footing.TENSION, "a feud puts the town on its guard: " + Wars.footing(a.id()));
            List<VillageFolkEntity> theirs = grown(b.id());
            helper.assertTrue(theirs.size() >= 4, "four grown folk in the second town, " + theirs.size());
            for (int i = 0; i < 4; i++) {
                VillageFolkEntity g = theirs.get(i);
                guard(g);
                g.setNoAi(true);
                if (i < 2) g.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                if (i == 3) g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                // On the street on our side of their square, out of doors (the scout's vantage is thirty-odd blocks off).
                standAt(level, g, b.centre().getX() - 22 + i * 3, b.centre().getZ() + 6);
            }
            spy[0] = grown(a.id()).get(0);
            spy[0].insertItem(new ItemStack(Items.BREAD, 3));
            Spying.watchForTests(200);
            Spying.boldForTests(false);
            day[0] = level.getDayTime() / 24000L;
            boolean sent = Spying.send(level, spy[0], a, b.id(), day[0]);
            Spying.atVantageForTests(spy[0]);
            Kit.log("si01 " + spy[0].displayNameCap() + " sent " + sent + " to watch " + Villages.name(b.id()) + ", at "
                + spy[0].blockPosition().toShortString() + ", " + (int) Math.sqrt(spy[0].blockPosition().distSqr(b.centre())) + " from its heart");
            helper.assertTrue(sent, "a town on its guard sends somebody to look");
        });
        helper.onEachTick(() -> {
            VillageFolkEntity s = spy[0];
            if (s == null || homeAt[0] >= 0) return;
            Spying.Mission m = Spying.missionOf(s);
            if (helper.getTick() % 40 == 0 && m != null) {
                Kit.log("si01 tick " + helper.getTick() + ": " + m.phase() + ", guards seen " + m.guardsSeen() + "; " + s.hobbyNow());
            }
            if (m == null || m.phase() != Spying.Phase.HOME) return;
            homeAt[0] = helper.getTick();
            Spying.homeNowForTests(level, s);
            Intel.Report r = Intel.latest(a.id(), b.id());
            Kit.log("si01 home at tick " + homeAt[0] + ": " + (r == null ? "no report" : "day " + r.day() + ", " + r.guards() + " guards, "
                + r.armoured() + " in iron, " + r.archers() + " bows, walls " + r.walls() + ", gates " + r.gates() + ", food " + r.foodDays()
                + ", folk " + r.folk() + " — \"" + r.note() + "\"; " + Intel.summary(r)));
            helper.assertTrue(r != null, "a report filed");
            helper.assertTrue(r.day() == day[0], "dated the day it watched: " + r.day() + " vs " + day[0]);
            helper.assertTrue(r.guards() == 4, "the four guards out of doors counted, not " + r.guards());
            helper.assertTrue(r.armoured() == 2 && r.archers() == 1, "two in iron and one bow: " + r.armoured() + ", " + r.archers());
            helper.assertTrue(s.expedition() == null, "home, and back to its own day");
            helper.succeed();
        });
    }

    // ============================================================ si02: reports age, the guess drifts

    /**
     * A report of twenty guards, filed today. The leader's belief from it is exact the day it was made
     * and drifts further each day it lies about (never back); it is marked old from the fifth day. A
     * prickly leader makes light of the same report, a wary one makes more of it. With no report there
     * is only rumour, and the reckoning is a third sure.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "si02_report_age")
    public static void si02_report_age(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        WarScouting.pauseForTests(true);
        level.setDayTime(2000);
        int x = 782000;
        Villages.Village[] t = twoTowns(helper, level, x, 260, 3, 3);
        Villages.Village a = t[0], b = t[1];
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            Intel.temperForTests(a.id(), Envoys.Temper.STEADY);
            // No report yet: rumour.
            Intel.Report heard = Intel.estimate(a.id(), b.id(), day);
            Intel.Reckoning k0 = Intel.strength(level, a.id(), b.id());
            Kit.log("si02 rumour: " + heard.guards() + " guards (" + heard.note() + "), sure " + k0.sure() + "; " + k0.words());
            helper.assertTrue("rumour".equals(heard.note()) && k0.sure() < 0.5, "with no report, rumour, and little sure of it");
            Intel.Report r = new Intel.Report(b.id(), day, 40, 20, 10, 6, 4, 4, 30, "counted from the ridge");
            Intel.file(a.id(), r);
            int last = -1;
            StringBuilder sb = new StringBuilder("si02 the belief by the report's age:");
            for (int age : new int[]{ 0, 1, 3, 5, 8, 12 }) {
                Intel.Report e = Intel.estimate(a.id(), b.id(), day + age);
                int off = Math.abs(e.guards() - r.guards());
                sb.append(" ").append(age).append("d ").append(e.guards()).append(" (out ").append(off).append(", ")
                    .append(Intel.ageWords(r, day + age)).append(")");
                helper.assertTrue(off >= last, "the guess never grows better with age: " + off + " after " + last + " at " + age + " days");
                if (age == 0) helper.assertTrue(off == 0, "today's report is believed as it stands");
                last = off;
            }
            Kit.log(sb.toString());
            helper.assertTrue(last >= 4, "twelve days on, the guess is well out: " + last);
            helper.assertTrue(!Intel.stale(r, day + 4) && Intel.stale(r, day + 5), "old from the fifth day");
            double sureNew = Intel.strength(a.id(), b.id(), day).sure(), sureOld = Intel.strength(a.id(), b.id(), day + 8).sure();
            Kit.log("si02 sure: today " + sureNew + ", eight days on " + sureOld);
            helper.assertTrue(sureOld < sureNew, "an old report is trusted less");
            // The leader's temper bends it.
            Intel.temperForTests(a.id(), Envoys.Temper.PRICKLY);
            int prickly = Intel.estimate(a.id(), b.id(), day).guards();
            Intel.temperForTests(a.id(), Envoys.Temper.WARY);
            int wary = Intel.estimate(a.id(), b.id(), day).guards();
            Kit.log("si02 twenty guards reported: a prickly leader believes " + prickly + ", a wary one " + wary);
            helper.assertTrue(prickly < 20 && wary > 20, "prickly makes light of them, wary makes more of them");
            helper.succeed();
        });
    }

    // ============================================================ si03: the council's reckoning

    /**
     * A town of nine with three guards. With a report of a strong enemy (fourteen guards, ten in iron, a
     * wall all round, forty days' food) it reckons itself the weaker and wants more guards; with a report
     * of a weak one (a single guard, no wall, little food) it reckons itself the stronger. The reckoning
     * is the report's, not the world's: the enemy itself is the same both times.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "si03_council_reckons")
    public static void si03_council_reckons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        WarScouting.pauseForTests(true);
        level.setDayTime(2000);
        int x = 784000;
        Villages.Village[] t = twoTowns(helper, level, x, 260, 9, 4);
        Villages.Village a = t[0], b = t[1];
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            Wars.begin(a.id(), b.id(), day);
            Intel.temperForTests(a.id(), Envoys.Temper.STEADY);
            List<VillageFolkEntity> ours = grown(a.id());
            for (int i = 0; i < 3 && i < ours.size(); i++) guard(ours.get(i));
            Leader.booksForTests(a.id(), new Leader.Books(200, 20, 20, 20, 20, 20, Leader.Plan.STEADY, day));
            Intel.file(a.id(), new Intel.Report(b.id(), day, 40, 14, 10, 5, 4, 4, 40, "a strong town"));
            Intel.Reckoning strong = Intel.strength(level, a.id(), b.id());
            int wantStrong = Intel.guardsNeeded(level, a.id(), b.id(), day);
            Kit.log("si03 against a strong report: ours " + strong.ours().guards() + " guards field " + fmt(strong.ours().field()) + " held "
                + fmt(strong.ours().held()) + "; theirs " + strong.theirs().guards() + " field " + fmt(strong.theirs().field()) + " held "
                + fmt(strong.theirs().held()) + "; attack " + fmt(strong.attack()) + " defend " + fmt(strong.defend()) + " balance "
                + fmt(strong.balance()) + "; want " + wantStrong + " guards; " + strong.words());
            int mine = 0;
            for (VillageFolkEntity f : grown(a.id())) if (f.stationTask() == StationTask.GUARD) mine++;
            helper.assertTrue(strong.ours().guards() == mine && mine >= 3, "our own guards known exactly: " + strong.ours().guards() + " of " + mine);
            helper.assertTrue(strong.theirs().guards() == 14, "theirs as the report has it: " + strong.theirs().guards());
            helper.assertTrue(strong.weaker() && !strong.stronger(), "a report of a strong enemy: we reckon ourselves the weaker");
            Intel.file(a.id(), new Intel.Report(b.id(), day, 6, 1, 0, 0, 0, 0, 5, "a weak town"));
            Intel.Reckoning weak = Intel.strength(level, a.id(), b.id());
            int wantWeak = Intel.guardsNeeded(level, a.id(), b.id(), day);
            Kit.log("si03 against a weak report: theirs " + weak.theirs().guards() + " field " + fmt(weak.theirs().field()) + " held "
                + fmt(weak.theirs().held()) + "; attack " + fmt(weak.attack()) + " defend " + fmt(weak.defend()) + " balance "
                + fmt(weak.balance()) + "; want " + wantWeak + " guards; " + weak.words());
            helper.assertTrue(weak.stronger() && !weak.weaker(), "a report of a weak enemy: we reckon ourselves the stronger");
            helper.assertTrue(wantStrong > wantWeak, "more guards wanted against the strong report: " + wantStrong + " vs " + wantWeak);
            // A war begun on a belief, and the truth told: the chronicle says how far out it was.
            Intel.acted(a.id(), b.id(), day, "went to war with");
            Intel.file(a.id(), new Intel.Report(b.id(), day, 40, 14, 10, 5, 4, 4, 40, "a strong town after all"));
            String line = Intel.learned(a.id(), b.id(), 14, day);
            Kit.log("si03 the chronicle: " + line);
            helper.assertTrue(line != null && line.contains("thinking it held one guard") && line.contains("fourteen"),
                "a council that went to war on a bad report is told of it");
            helper.succeed();
        });
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    // ============================================================ si04: the watch catches a spy

    /**
     * At war. A spy from the second town lies in the grass a few blocks from one of the first town's
     * guards. The watch's look round spots it, the guard runs it down, and it is held: its errand over,
     * questioned (the first town now has a report on the second, from it), and the two towns' relations
     * the worse for it. At peace it is let go, and sets off home.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "si04_spy_caught")
    public static void si04_spy_caught(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        WarScouting.pauseForTests(true);
        level.setDayTime(3000);
        int x = 786000;
        Villages.Village[] t = twoTowns(helper, level, x, 260, 4, 3);
        Villages.Village a = t[0], b = t[1];
        VillageFolkEntity[] who = new VillageFolkEntity[2];
        int[] before = { 0 };
        long[] caughtAt = { -1 };
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            Wars.begin(a.id(), b.id(), day);
            before[0] = Ledger.relation(a.id(), b.id());
            VillageFolkEntity g = grown(a.id()).get(0);
            guard(g);
            VillageFolkEntity s = grown(b.id()).get(0);
            s.insertItem(new ItemStack(Items.BREAD, 3));
            boolean sent = Spying.send(level, s, b, a.id(), day);
            helper.assertTrue(sent, "the enemy sends a spy");
            s.setNoAi(true);                                  // it lies still in the grass, watching
            standAt(level, g, a.centre().getX() + 20, a.centre().getZ());
            standAt(level, s, a.centre().getX() + 25, a.centre().getZ() + 1);
            who[0] = g;
            who[1] = s;
            Kit.log("si04 the spy " + s.displayNameCap() + " of " + Villages.name(b.id()) + " " + String.format("%.1f", g.distanceTo(s))
                + " blocks from the guard " + g.displayNameCap());
        });
        helper.onEachTick(() -> {
            VillageFolkEntity g = who[0], s = who[1];
            if (g == null || caughtAt[0] >= 0) return;
            long tick = helper.getTick();
            if (tick % 20 == 0) Spies.tick(level, a);
            if (tick % 40 == 0) Kit.log("si04 tick " + tick + ": chaser " + Spies.chaserForTests(s) + ", " + String.format("%.1f", g.distanceTo(s))
                + " apart; guard " + g.hobbyNow());
            if (!Spies.held(s)) return;
            caughtAt[0] = tick;
            Intel.Report told = Intel.latest(a.id(), b.id());
            int after = Ledger.relation(a.id(), b.id());
            Kit.log("si04 caught at tick " + tick + ": captives " + Spies.captives(a.id()).size() + ", errand " + (s.expedition() == null ? "over" : "still on")
                + "; questioned: " + (told == null ? "nothing" : told.guards() + " guards, " + told.folk() + " folk — " + told.note())
                + "; relations " + before[0] + " -> " + after);
            helper.assertTrue(Spies.captives(a.id()).size() == 1 && s.expedition() == null, "held, its errand over");
            helper.assertTrue(told != null && told.note().contains("questioned"), "questioned: what it knows of its own town is ours");
            helper.assertTrue(after < before[0], "caught spying does the peace no good");
            // Peace: let go home.
            s.setNoAi(false);
            Wars.end(a.id(), b.id());
            Ledger.relate(a.id(), b.id(), 10 - Ledger.relation(a.id(), b.id()));
            Spies.letGoForTests(level, a);
            Spying.Mission m = Spying.missionOf(s);
            Kit.log("si04 at peace: captives " + Spies.captives(a.id()).size() + ", " + s.displayNameCap() + " "
                + (m == null ? "not on its way" : "on its way home (" + m.phase() + ")"));
            helper.assertTrue(Spies.captives(a.id()).isEmpty() && !Spies.held(s) && s.expedition() != null, "let go at peace, and walking home");
            helper.succeed();
        });
    }

    // ============================================================ si05: a picket rings the bell early

    /**
     * At war. The first town (four guards: two stay in, two can be spared) puts a picket out on the road
     * toward the enemy. Three of the enemy's folk, armed, come up the road toward the town at a walk. The
     * picket sees them, runs home, and the alarm bell is rung (the gates shut, the watch out) while they
     * are still well outside the town.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "si05_picket_warns")
    public static void si05_picket_warns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        WarScouting.pauseForTests(true);
        level.setDayTime(3000);
        int x = 788000;
        Kit.hold(level, x + 120, Z, 60);                      // the road between, out past the post
        Kit.prepare(level, x + 120, Z, 60);
        Villages.Village[] t = twoTowns(helper, level, x, 260, 6, 4);
        Villages.Village a = t[0], b = t[1];
        VillageFolkEntity[] picket = new VillageFolkEntity[1];
        List<VillageFolkEntity> enemy = new ArrayList<>();
        long[] rangAt = { -1 };
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            Wars.begin(a.id(), b.id(), day);
            List<VillageFolkEntity> ours = grown(a.id());
            for (int i = 0; i < 4 && i < ours.size(); i++) guard(ours.get(i));
            List<VillageFolkEntity> on = Pickets.postNowForTests(level, a);
            helper.assertTrue(!on.isEmpty(), "a picket put out on the road toward the enemy");
            picket[0] = on.get(0);
            Pickets.Post p = Pickets.postOf(picket[0]);
            standAt(level, picket[0], p.at().getX(), p.at().getZ());
            List<VillageFolkEntity> theirs = grown(b.id());
            for (int i = 0; i < 3 && i < theirs.size(); i++) {
                VillageFolkEntity e = theirs.get(i);
                e.setNoAi(true);
                e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                standAt(level, e, p.at().getX() + 30, p.at().getZ() - 2 + i * 2);
                enemy.add(e);
            }
            Kit.log("si05 " + picket[0].displayNameCap() + " on picket on " + p.road() + " at " + p.at().toShortString() + " ("
                + (int) Math.sqrt(p.at().distSqr(a.centre())) + " from the heart); three of " + Villages.name(b.id()) + " 30 blocks beyond it");
        });
        helper.onEachTick(() -> {
            if (picket[0] == null || rangAt[0] >= 0) return;
            long tick = helper.getTick();
            // The enemy walks up the road toward the town, steadily.
            double nearest = Double.MAX_VALUE;
            for (VillageFolkEntity e : enemy) {
                e.setPos(e.getX() - 0.08, e.getY(), e.getZ());
                nearest = Math.min(nearest, Math.sqrt(Math.pow(e.getX() - a.centre().getX(), 2) + Math.pow(e.getZ() - a.centre().getZ(), 2)));
            }
            if (tick % 40 == 0) {
                Kit.log("si05 tick " + tick + ": picket " + (Pickets.runningForTests(picket[0]) ? "running" : "watching") + " at "
                    + (int) Math.sqrt(picket[0].blockPosition().distSqr(a.centre())) + " from the heart; the enemy " + (int) nearest + " off");
            }
            if (!Raids.underAlarm(a.id())) return;
            rangAt[0] = tick;
            Intel.Sighting s = Intel.lastSighting(a.id(), b.id());
            Kit.log("si05 the bell at tick " + tick + " (" + Raids.why(a.id()) + "), the enemy still " + (int) nearest + " blocks from the heart; seen: "
                + (s == null ? "nothing" : s.what() + " x" + s.size() + " by " + s.by()));
            helper.assertTrue(nearest > Villages.townReach(a.id()), "the bell rang before they reached the town: " + (int) nearest);
            helper.assertTrue(s != null && s.size() == 3, "the sighting is on the war map");
            helper.succeed();
        });
    }
}
