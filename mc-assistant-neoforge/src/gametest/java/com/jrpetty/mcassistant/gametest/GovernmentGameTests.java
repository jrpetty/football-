package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Ceremonies;
import com.jrpetty.mcassistant.entity.Dissent;
import com.jrpetty.mcassistant.entity.Elections;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.Factions;
import com.jrpetty.mcassistant.entity.FormChoice;
import com.jrpetty.mcassistant.entity.GovActions;
import com.jrpetty.mcassistant.entity.GovReport;
import com.jrpetty.mcassistant.entity.Government;
import com.jrpetty.mcassistant.entity.Identity;
import com.jrpetty.mcassistant.entity.Ideology;
import com.jrpetty.mcassistant.entity.LawBook;
import com.jrpetty.mcassistant.entity.Policy;
import com.jrpetty.mcassistant.entity.Politics;
import com.jrpetty.mcassistant.entity.Polls;
import com.jrpetty.mcassistant.entity.Regimes;
import com.jrpetty.mcassistant.entity.Rivalry;
import com.jrpetty.mcassistant.entity.TaxBook;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BannerBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [featcivic] How towns are governed: the new forms (a representative democracy, a planned state, military rule), the
 * election you can watch, a player's ballot, the planned state's ban on private business, unrest into protest and
 * reform, the towns' governments between them, and a revolution and a coup.
 *
 * <ul>
 * <li><b>gv01</b>: each new form installs its own way: a democracy calls an election and its folk stand for parties;
 *     a planned state's congress appoints a chair (its ceremony waiting for the square); a general takes command;
 *     each form's rules, title and flag; the founding odds put a moderate town under an elected government.</li>
 * <li><b>gv02</b>: an election in a democracy: posters for every candidate, the ballot box set out, a rally in the
 *     square, the voters queueing at the box and dropping their ballots in, the count; the winner leads and its
 *     personality sets the policy; the seats on the council shared out; the losers' posters down.</li>
 * <li><b>gv03</b>: a citizen player's ballot, cast as the ballot screen casts it, counts at the count; a second is
 *     refused; a non-citizen is told why it may not vote.</li>
 * <li><b>gv04</b>: a planned state bars a private business (the charter refused, a folk's venture barred, the state's
 *     taxes: no toll, no profits tax), and a player is told why and what it can do instead.</li>
 * <li><b>gv05</b>: unrest in a democracy with a curfew: the meter rises with the town's grievances, a protest gathers in
 *     the square with banners in hand, and the petition carried lifts the curfew (a reform).</li>
 * <li><b>gv06</b>: a democracy and a military government on uneasy terms grow worse and stop trading (an embargo),
 *     while two democracies grow warmer; the board's relations say why.</li>
 * <li><b>gv07</b>: a planned state's town rises: it becomes a democracy, the old chair leaves office, the old order's
 *     posters come down and a new flag goes up; then a lordship with a strong watch falls to a coup.</li>
 * <li><b>gv08</b>: the edges: a tie between two newcomers to office is settled by drawing lots; nobody standing
 *     leaves the one in office as caretaker.</li>
 * </ul>
 * Each on its own ground in x 8740000-8790000, z 87000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class GovernmentGameTests {

    private static final String EMPTY = "empty";
    private static final int X = 8740000, Z = 87000;

    // ------------------------------------------------------------------ helpers

    private static Villages.Village town(GameTestHelper helper, int x, int z, int n) {
        ServerLevel level = helper.getLevel();
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null, "a town at " + x + ", " + z);
        for (VillageFolkEntity f : folk(v.id())) f.ensurePersona();
        return v;
    }

    private static List<VillageFolkEntity> folk(UUID v) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v)) if (a instanceof VillageFolkEntity f && !f.isBaby()) out.add(f);
        return out;
    }

    private static void start(ServerLevel level, long time) {
        Kit.reset(level);
        Politics.resetForTests();
        level.setDayTime(time);
        level.updateSkyBrightness();
        Identity.liveForTests();
    }

    private static boolean told(UUID v, String words) {
        for (Chronicle.Entry e : Chronicle.of(v)) if (e.text().contains(words)) return true;
        return false;
    }

    private static Villages.Village governed(GameTestHelper helper, ServerLevel level, int x, int n, Government.Form form) {
        Villages.Village v = town(helper, x, Z, n);
        Identity.seedForTests(level, v);
        Government.setForTests(level, v, form);
        return v;
    }

    private static String Assemblies_now(UUID village) {
        return com.jrpetty.mcassistant.entity.Assemblies.now(village);
    }

    // ============================================================ gv01 the new forms

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "gv01_forms")
    public static void gv01_new_forms_install_their_own_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        Villages.Village dem = governed(helper, level, X, 9, Government.Form.DEMOCRACY);
        Villages.Village plan = governed(helper, level, X + 300, 8, Government.Form.PLANNED);
        Villages.Village gen = town(helper, X + 600, Z, 8);
        helper.runAtTickTime(10, () -> {
            Identity.seedForTests(level, gen);
            List<VillageFolkEntity> g = folk(gen.id());
            g.get(0).setJob(StationTask.GUARD);
            g.get(1).setJob(StationTask.GUARD);
            Government.setForTests(level, gen, Government.Form.JUNTA);
            // A democracy: elected, its title the mayor, its candidates its parties' (where two have followings).
            helper.assertTrue(Government.of(dem.id()) == Government.Form.DEMOCRACY && Government.holdsElections(dem.id()), "a democracy elects");
            helper.assertTrue("mayor".equals(Government.title(dem.id())), "its leader is the mayor: " + Government.title(dem.id()));
            helper.assertTrue(Factions.following(dem.id()).size() >= 2, "its folk lean to two parties at least: " + Factions.following(dem.id()));
            // A planned state: no election, the congress appoints a chair, its ceremony waits for the square.
            helper.assertTrue(!Government.holdsElections(plan.id()) && Villages.elder(plan.id()) != null, "the chair appointed: " + Villages.elderName(plan.id()));
            helper.assertTrue("chair of the committee".equals(Government.title(plan.id())), "its title: " + Government.title(plan.id()));
            helper.assertTrue(Government.votersForTests(plan.id()) <= 5, "the committee alone votes on its questions");
            helper.assertTrue(told(plan.id(), "appointed chair of the committee") || told(plan.id(), "chair of the committee"), "the chronicle has the appointment");
            // Military rule: the senior guard takes command.
            UUID general = Villages.elder(gen.id());
            helper.assertTrue(general != null && (general.equals(g.get(0).getUUID()) || general.equals(g.get(1).getUUID())), "a guard commands: " + Villages.elderName(gen.id()));
            helper.assertTrue("general".equals(Government.title(gen.id())), "its title: " + Government.title(gen.id()));
            helper.assertTrue(Regimes.shareLean(gen.id(), StationTask.GUARD) > 1.2, "the general's watch is the stronger");
            // Each form's rules on the board, every form with its strengths and weaknesses.
            CompoundTag report = GovReport.report(level, plan, null);
            helper.assertTrue(report.getCompound("rules").getString("business").contains("No private business"), "the planned state's rules say no business");
            helper.assertTrue(report.getList("compare", 10).size() == Government.Form.values().length, "every form compared");
            for (Government.Form f : Government.Form.values()) {
                helper.assertTrue(Regimes.rules(f).strengths().size() >= 2 && Regimes.rules(f).weaknesses().size() >= 2, f + " has real strengths and weaknesses");
            }
            // The flag beside the board, in the form's colour.
            BlockPos flag = Regimes.raiseFlag(level, plan, Government.Form.PLANNED, false);
            helper.assertTrue(flag != null && level.getBlockState(flag).getBlock() instanceof BannerBlock, "the government's flag stands: " + flag);
            // The founding: a moderate town is all but certain to elect.
            int[] moderate = { 10, -5, 0, 5, 15, -5, 5 };
            helper.assertTrue(FormChoice.othersOdds(moderate) < 0.2, "a moderate town elects: odds of another form " + FormChoice.othersOdds(moderate));
            Kit.log("gv01 plan: " + Villages.elderName(plan.id()) + " ceremony " + Ceremonies.dueForTests(plan.id()) + "; general " + Villages.elderName(gen.id()));
            helper.succeed();
        });
    }

    // ============================================================ gv02 an election you can watch

    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "gv02_election")
    public static void gv02_election_posters_queue_count_policy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = X + 6000;
        Villages.Village v = governed(helper, level, x, 8, Government.Form.DEMOCRACY);
        UUID id = v.id();
        int[] maxQueue = { 0 };
        boolean[] counted = { false };
        String[] result = { "" };
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            int n = Elections.callForTests(level, v, day + 1);
            helper.assertTrue(n >= 2, "two stand at least: " + Elections.standingForTests(id));
            Map<UUID, Integer> posters = Polls.posters(id);
            int total = 0;
            for (int p : posters.values()) total += p;
            Kit.log("gv02 standing " + Elections.standingForTests(id) + "; posters " + posters);
            helper.assertTrue(posters.size() >= 2 && total >= 4, "every candidate's posters up: " + posters);
            BlockPos box = Polls.station(level, v, false);
            helper.assertTrue(Polls.boxAt(level, box), "the ballot box set out: " + box);
        });
        helper.runAtTickTime(40, () -> {
            // Polling day, late enough that every voter's hour has come.
            long day = level.getDayTime() / 24000L;
            level.setDayTime((day + 1) * 24000L + 10500L);
        });
        helper.onEachTick(() -> {
            if (helper.getTick() < 41 || counted[0]) return;
            long t = level.getDayTime() % 24000L;
            if (t > 11000L) level.setDayTime(level.getDayTime() - (t - 10500L));     // the polls held open till all have voted
            if (helper.getTick() % 200 == 0) Kit.log("gv02 t" + helper.getTick() + ": ballots " + Polls.ballotsForTests(id).size() + ", queue " + Polls.queued(id)
                + ", " + Assemblies_now(id));
            maxQueue[0] = Math.max(maxQueue[0], Polls.queued(id));
            int ballots = Polls.ballotsForTests(id).size(), voters = Government.votersForTests(id);
            int atBox = 0;
            for (VillageFolkEntity f : folk(id)) if (Elections.votedForTests(id, f.getUUID())[1]) atBox++;
            if (ballots >= voters - 1 && atBox >= Math.max(3, voters / 2) || helper.getTick() > 3600 && ballots >= 3) {
                Kit.log("gv02 ballots " + ballots + " of " + voters + ", " + atBox + " at the box, the longest queue " + maxQueue[0]);
                Elections.Result r = Elections.countForTests(level, v);
                counted[0] = true;
                result[0] = r.winner() == null ? "" : r.winner().name();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(counted[0], "the count held");
            helper.assertTrue(maxQueue[0] >= 2, "a queue at the box: the longest " + maxQueue[0]);
            helper.assertTrue(!result[0].isEmpty() && result[0].equals(Villages.elderName(id)), "the winner leads: " + result[0] + " / " + Villages.elderName(id));
            Policy.Stance s = Policy.of(id);
            helper.assertTrue(s != null && s.who().equals(result[0]), "the winner's personality is the policy: " + (s == null ? "none" : s.who()));
            VillageFolkEntity mayor = null;
            for (VillageFolkEntity f : folk(id)) if (f.getUUID().equals(Villages.elder(id))) mayor = f;
            helper.assertTrue(mayor != null, "the mayor is loaded");
            Values.Value top = Values.top(mayor);
            String build = s.build();
            helper.assertTrue(!build.isEmpty() && (top != Values.Value.SAFETY || build.equals("smithy")) && (top != Values.Value.WEALTH || build.equals("shop")),
                "what it builds first follows what it cares for: " + top + " -> " + build);
            int seats = 0;
            for (int k : Factions.seats(id).values()) seats += k;
            helper.assertTrue(seats == Factions.seatsFor(Government.votersForTests(id)), "the council's seats shared out: " + Factions.seats(id));
            helper.assertTrue(!Polls.lastRows(id).isEmpty(), "the result kept for the board's chart");
            Kit.log("gv02 policy " + s + "; seats " + Factions.seats(id));
        });
    }

    // ============================================================ gv03 a player's ballot

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "gv03_player_ballot")
    public static void gv03_player_ballot_counts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = X + 12000;
        Villages.Village v = governed(helper, level, x, 6, Government.Form.DEMOCRACY);
        UUID id = v.id();
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            Elections.callForTests(level, v, day + 1);
            List<String> standing = Elections.standingForTests(id);
            helper.assertTrue(standing.size() >= 2, "two stand: " + standing);
            BlockPos box = Polls.station(level, v, false);
            helper.assertTrue(box != null, "a ballot box");
            ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
            stranger.moveTo(box.getX() + 0.5, box.getY(), box.getZ() + 1.5);
            UUID first = null;
            for (VillageFolkEntity f : folk(id)) if (standing.get(standing.size() - 1).startsWith(f.displayNameCap() + ":")) first = f.getUUID();
            helper.assertTrue(first != null, "the last-listed candidate is loaded");
            String refused = GovActions.voteForTests(level, v, stranger, first);
            helper.assertTrue(refused.startsWith("Not cast") && refused.contains("citizen"), "a stranger may not vote: " + refused);
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.moveTo(box.getX() + 0.5, box.getY(), box.getZ() + 1.5);
            Ledger.addCitizen(id, p.getUUID(), p.getName().getString());
            // Early: the day before polling day (a postal vote: it counts if the player is away on the day).
            String said = GovActions.voteForTests(level, v, p, first);
            Kit.log("gv03 " + said);
            helper.assertTrue(said.startsWith("Your ballot is in the box") && said.contains("early"), "the ballot cast, early: " + said);
            helper.assertTrue(first.equals(Polls.ballotsForTests(id).get(p.getUUID())), "the player's ballot is in the box");
            String again = GovActions.voteForTests(level, v, p, first);
            helper.assertTrue(again.contains("already"), "a second ballot refused: " + again);
            Elections.Result r = Elections.countForTests(level, v);
            int forFirst = 0;
            for (Map.Entry<Elections.Candidate, Integer> e : r.votes().entrySet()) if (e.getKey().id().equals(first)) forFirst = e.getValue();
            helper.assertTrue(forFirst >= 2, "the player's vote is counted with the candidate's own: " + r.votes());
            helper.assertTrue(r.voted() >= folk(id).size() + 1 - 1 && r.voters() >= folk(id).size(), "the player counted among the voters: " + r.voted() + " of " + r.voters());
            helper.succeed();
        });
    }

    // ============================================================ gv04 the planned state and private business

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "gv04_planned_business")
    public static void gv04_planned_state_forbids_private_business(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = X + 18000;
        Villages.Village v = governed(helper, level, x, 7, Government.Form.PLANNED);
        Villages.Village free = governed(helper, level, x + 300, 7, Government.Form.DEMOCRACY);
        helper.runAtTickTime(10, () -> {
            UUID id = v.id();
            String barred = Regimes.businessBarred(id);
            helper.assertTrue(barred != null && barred.startsWith("In a planned state you cannot open a private business"), "barred, and why: " + barred);
            helper.assertTrue(Regimes.businessBarred(free.id()) == null, "a democracy allows it");
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            BlockPos heart = Kit.surface(level, x, Z);
            p.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
            Ledger.addCitizen(id, p.getUUID(), p.getName().getString());
            String charter = Enterprise.charter(level, v, p, "baker");
            helper.assertTrue(charter.contains("planned state"), "the charter refused: " + charter);
            // The state's taxes: no toll and no profits tax, whatever the books say.
            TaxBook.setForTests(id, TaxBook.Lever.TOLL, 2);
            helper.assertTrue(TaxBook.rate(id, TaxBook.Lever.TOLL) == 0 && TaxBook.rate(id, TaxBook.Lever.PROFITS) == 0, "no toll, no profits tax");
            // Its law: nobody owns a house; what a player can do there, on the board.
            CompoundTag you = GovReport.report(level, v, p).getCompound("you");
            String cannot = you.getList("cannot", 8).toString();
            helper.assertTrue(cannot.contains("cannot open a private business"), "the player told it cannot open a business: " + cannot);
            helper.assertTrue(you.getList("can", 8).toString().contains("Work for the state"), "and what it can do: " + you.getList("can", 8));
            helper.succeed();
        });
    }

    // ============================================================ gv05 unrest, protest, reform

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "gv05_unrest")
    public static void gv05_unrest_protest_and_reform(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 8500);
        int x = X + 24000;
        Villages.Village v = governed(helper, level, x, 10, Government.Form.DEMOCRACY);
        UUID id = v.id();
        boolean[] banners = { false };
        helper.runAtTickTime(10, () -> {
            LawBook.setForTests(id, LawBook.Law.CURFEW, 1);
            // Folk who care for their evenings, under a curfew, in a town doing badly: a grievance each.
            for (VillageFolkEntity f : folk(id)) {
                Values.setForTests(f, Values.Value.LEISURE, 95);
                Values.setForTests(f, Values.Value.TRADITION, 0);
                f.life().setTraitsForTests(com.jrpetty.mcassistant.entity.Social.Trait.GRUMPY, com.jrpetty.mcassistant.entity.Social.Trait.CURIOUS);
            }
            int before = Dissent.level(id);
            Dissent.stir(id, level.getDayTime() / 24000L, 30, "a free neighbour");      // talk from abroad, as Ideology's pamphlets bring
            for (int i = 0; i < 4; i++) Dissent.dailyForTests(level, v);
            int after = Dissent.level(id);
            Kit.log("gv05 unrest " + before + " -> " + after + " (" + Dissent.why(id) + ")");
            helper.assertTrue(after > before, "the meter rises with the grievances: " + before + " -> " + after);
            helper.assertTrue(after < Dissent.STRIKE, "a democracy never gets as far as a strike: " + after);
            Dissent.setForTests(id, 62);
            helper.assertTrue(Dissent.beginForTests(level, v, "PROTEST"), "a protest gathers in the square");
        });
        helper.onEachTick(() -> {
            if (helper.getTick() > 20) for (VillageFolkEntity f : folk(id)) if (Dissent.holdsBanner(f)) banners[0] = true;
        });
        helper.runAtTickTime(600, () -> {
            helper.assertTrue(banners[0], "banners in the protesters' hands");
            // The petition: put round, signed, put to the mayor, who gives way: the curfew lifted.
            Dissent.setForTests(id, 50);
            LawBook.setForTests(id, LawBook.Law.CURFEW, 1);             // (the mayor may have lifted it of its own accord meanwhile)
            Dissent.startPetitionForTests(level, v);
            String[] p = Dissent.petition(id);
            helper.assertTrue(p != null && p[0].contains("curfew"), "the petition asks an end to the curfew: " + (p == null ? "none" : p[0]));
            int was = Dissent.level(id);
            Dissent.presentForTests(level, v);
            helper.assertTrue(!LawBook.curfew(id), "the reform: the curfew lifted");
            helper.assertTrue(Dissent.level(id) < was, "the unrest falls: " + was + " -> " + Dissent.level(id));
            helper.assertTrue(told(id, "petition"), "the chronicle has the petition");
            helper.succeed();
        });
    }

    // ============================================================ gv06 the towns' governments between them

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "gv06_ideology")
    public static void gv06_ideology_sways_relations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = X + 30000;
        Villages.Village dem = governed(helper, level, x, 6, Government.Form.DEMOCRACY);
        Villages.Village gen = governed(helper, level, x + 320, 6, Government.Form.JUNTA);
        Villages.Village dem2 = governed(helper, level, x - 320, 6, Government.Form.REEVE);
        helper.runAtTickTime(10, () -> {
            Ledger.relate(dem.id(), gen.id(), -30 - Ledger.relation(dem.id(), gen.id()));
            int opposed = Ideology.dailyForTests(level, dem, gen);
            int kindred = Ideology.dailyForTests(level, dem, dem2);
            Kit.log("gv06 opposed " + opposed + ", kindred " + kindred + "; relations " + Ideology.relations(dem.id()).size());
            helper.assertTrue(opposed <= -1, "a democracy and a general grow worse: " + opposed);
            helper.assertTrue(kindred >= 1, "two elected towns grow warmer: " + kindred);
            helper.assertTrue(Ideology.embargoed(dem.id(), gen.id()) && Rivalry.embargoed(dem.id(), gen.id()), "they stop trading: an embargo");
            helper.assertTrue(!Ideology.embargoed(dem.id(), dem2.id()), "the two democracies trade on");
            boolean said = false;
            for (String[] r : Ideology.relations(dem.id())) if (r[0].equals(Villages.name(gen.id())) && r[4].contains("ruled")) said = true;
            helper.assertTrue(said, "the board says why: " + Ideology.relations(dem.id()).stream().map(r -> r[0] + ": " + r[4]).toList());
            helper.succeed();
        });
    }

    // ============================================================ gv07 a revolution and a coup

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "gv07_revolution")
    public static void gv07_revolution_and_coup_change_the_form(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = X + 36000;
        Villages.Village plan = governed(helper, level, x, 8, Government.Form.PLANNED);
        Villages.Village lord = town(helper, x + 320, Z, 8);
        helper.runAtTickTime(10, () -> {
            UUID id = plan.id();
            UUID chair = Villages.elder(id);
            helper.assertTrue(chair != null, "a chair to put out");
            Regimes.raiseFlag(level, plan, Government.Form.PLANNED, false);
            Dissent.carryForTests(level, plan, Government.Form.DEMOCRACY, false);
            helper.assertTrue(Government.of(id) == Government.Form.DEMOCRACY, "the rising made it a democracy: " + Government.of(id));
            helper.assertTrue(!chair.equals(Villages.elder(id)), "the old chair is out of office");
            BlockPos flag = Regimes.parse(Ledger.note(id, "gov.flag"));
            helper.assertTrue(flag != null && level.getBlockState(flag).getBlock() == BannerBlock.byColor(Regimes.colour(Government.Form.DEMOCRACY)),
                "the new flag in the new colour: " + (flag == null ? "none" : level.getBlockState(flag)));
            helper.assertTrue(told(id, "rose against"), "the chronicle has the rising");
            // A lordship with a strong watch in a martial town: the watch seizes the hall.
            Identity.seedForTests(level, lord);
            List<VillageFolkEntity> f = folk(lord.id());
            for (int i = 0; i < 3; i++) f.get(i).setJob(StationTask.GUARD);
            Government.setForTests(level, lord, Government.Form.LORD);
            Dissent.carryForTests(level, lord, Government.Form.JUNTA, true);
            helper.assertTrue(Government.of(lord.id()) == Government.Form.JUNTA, "the coup made it military rule");
            UUID gen = Villages.elder(lord.id());
            boolean guard = false;
            for (int i = 0; i < 3; i++) if (f.get(i).getUUID().equals(gen)) guard = true;
            helper.assertTrue(guard, "a guard commands now: " + Villages.elderName(lord.id()));
            helper.assertTrue(told(lord.id(), "seized the hall"), "the chronicle has the coup");
            helper.succeed();
        });
    }

    // ============================================================ gv08 the edges

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "gv08_edges")
    public static void gv08_tie_by_lot_and_nobody_stands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = X + 42000;
        Villages.Village v = governed(helper, level, x, 4, Government.Form.DEMOCRACY);
        Villages.Village empty = governed(helper, level, x + 320, 4, Government.Form.REEVE);
        helper.runAtTickTime(10, () -> {
            List<VillageFolkEntity> f = folk(v.id());
            helper.assertTrue(f.size() == 4, "four folk");
            UUID elder = Villages.elder(v.id());
            List<VillageFolkEntity> others = new ArrayList<>();
            VillageFolkEntity e0 = null;
            for (VillageFolkEntity x2 : f) if (x2.getUUID().equals(elder)) e0 = x2; else others.add(x2);
            if (e0 == null) e0 = others.remove(others.size() - 1);
            // The two who stand are not in office; one voter for each (the one in office one of them), and each votes for itself.
            String r = Polls.tieForTests(level, v, others.get(0), others.get(1), others.get(2), e0);
            Kit.log("gv08 tie: " + r);
            helper.assertTrue(r.startsWith("a tie, decided by drawing lots|"), "a tie settled by lot: " + r);
            helper.assertTrue(told(v.id(), "drawing lots"), "the chronicle says so");
            // Nobody stands: the one in office stays on as caretaker until the next.
            UUID before = Villages.elder(empty.id());
            Polls.nobodyStoodForTests(level, empty);
            helper.assertTrue(told(empty.id(), "nobody stood"), "the chronicle: nobody stood");
            helper.assertTrue(java.util.Objects.equals(before, Villages.elder(empty.id())), "the one in office stays on");
            helper.succeed();
        });
    }

    // ============================================================ gv09 a rally in the square

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "gv09_rally")
    public static void gv09_candidate_rally_gathers_a_crowd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 4000);
        int x = X + 48000;
        Villages.Village v = governed(helper, level, x, 8, Government.Form.DEMOCRACY);
        UUID id = v.id();
        int[] most = { 0 };
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            helper.assertTrue(Elections.callForTests(level, v, day + 1) >= 2, "an election called");
            helper.assertTrue(Polls.rallyForTests(level, v), "a candidate's rally begins");
        });
        helper.onEachTick(() -> {
            String now = com.jrpetty.mcassistant.entity.Assemblies.now(id);
            if (now != null && now.contains("speech in the square")) {
                int[] p = com.jrpetty.mcassistant.entity.Assemblies.progress(id);
                most[0] = Math.max(most[0], p == null ? 0 : p[1]);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(told(id, "spoke in the square"), "the rally held, into the chronicle");
            helper.assertTrue(most[0] >= 2, "a crowd gathered to listen: " + most[0]);
        });
    }
}
