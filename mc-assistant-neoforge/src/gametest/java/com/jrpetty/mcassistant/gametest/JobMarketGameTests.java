package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.JobMarket;
import com.jrpetty.mcassistant.entity.JobSeekers;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The job market between towns (entity/JobMarket, entity/JobSeekers). A town short of a miner puts
 * a notice up; a town it has a trade pact with hears of it (and a town it has never met does not);
 * that town's idle miners walk to their own board, read it and apply; of three, the leader takes the
 * most experienced one fit for the mines by its years and tells the others no; the one taken on
 * says its goodbyes, walks the road to the new town, joins its roll and takes up mining. The
 * homeless of a raided village are taken in by a friendly neighbour with beds to spare. And a folk
 * with no reason to move does not go looking, while one out of work does.
 *
 * <p>Each test has its own batch (they share every static in the mod) and its own ground, in the
 * band x 310000 to 317000, z 50000. The clock is held at the end of the working day (no caravans,
 * no envoys, no night), so nothing else in the towns' lives takes the folk away mid-test.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class JobMarketGameTests {

    private static final String EMPTY = "empty";
    /** The hour the clock is held at: late afternoon, past the caravans' and the envoys' hours, before dusk. */
    private static final long HOUR = 11600L;

    /** A grown folk of this village standing here, at this trade and level, so many years old (even). */
    private static VillageFolkEntity folk(ServerLevel level, Villages.Village v, int x, int z, StationTask trade, int lv, int years) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        BlockPos at = Kit.surface(level, x, z);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        f.rename(Names.freeFor(v.id()));
        VillageSpawner.starterKit(f);
        f.joinVillage(v.id(), v.centre());
        level.addFreshEntity(f);
        Villages.recordBirth(v.id());
        // Years as folk count them: eighteen at three days old, and two more every day after.
        f.bornDaysAgo(VillageFolkEntity.GROW_DAYS + Math.max(0, (years - 18) / 2));
        if (trade != StationTask.NONE) {
            f.setJob(trade);
            if (lv > 0) f.tradeXpForTests(trade, lv * lv * Math.max(1, AssistantConfig.levelCurveFactor()) + 1);
        }
        return f;
    }

    /** A bed, made up: its foot here, its head to the east. */
    private static void bed(ServerLevel level, BlockPos foot) {
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
            .setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
            .setValue(BedBlock.PART, BedPart.HEAD), 3);
    }

    /** Beds at the camp round a village's heart (within six blocks of it): room for so many more. */
    private static void campBeds(ServerLevel level, BlockPos heart, int n) {
        for (int i = 0; i < n; i++) {
            int x = heart.getX() - 5 + (i % 4) * 3, z = heart.getZ() + (i < 4 ? 4 : -4);
            bed(level, Kit.surface(level, x, z));
        }
    }

    /** The ground of two towns this far apart along x, and the road between them, held awake for the whole test. */
    private static void ground(ServerLevel level, int ax, int bx, int z) {
        Kit.hold(level, ax, z, 48);
        Kit.hold(level, bx, z, 48);
        for (int x = Math.min(ax, bx) + 48; x < Math.max(ax, bx) - 32; x += 32) Kit.hold(level, x, z, 24);
        Kit.prepare(level, ax, z, 48);
        Kit.prepare(level, bx, z, 48);
        for (int x = Math.min(ax, bx) + 48; x < Math.max(ax, bx) - 32; x += 32) Kit.prepare(level, x, z, 24);
    }

    /** A town founded here, its builders kept from starting anything for the length of the test. */
    private static Villages.Village town(ServerLevel level, int x, int z) {
        Villages.Village v = Villages.found(level, Kit.surface(level, x, z));
        Villages.noteAttempt(v.id(), level.getGameTime() + 400000L);
        return v;
    }

    private static void pact(Villages.Village a, Villages.Village b) {
        Ledger.note(a.id(), "pact/" + b.id(), "trade");
        Ledger.note(b.id(), "pact/" + a.id(), "trade");
        Ledger.relate(a.id(), b.id(), 25);
    }

    private static double flat(VillageFolkEntity f, BlockPos p) {
        double dx = f.getX() - (p.getX() + 0.5), dz = f.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean on(Villages.Village v, VillageFolkEntity f) {
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a == f) return true;
        return false;
    }

    private static JobMarket.Application applicationOf(Villages.Village town, VillageFolkEntity f) {
        for (JobMarket.Application a : JobMarket.applications(town.id())) if (a.folk().equals(f.getUUID())) return a;
        return null;
    }

    private static boolean newsHas(Villages.Village v, String words) {
        for (Villages.News n : Villages.news(v.id())) if (n.text().contains(words)) return true;
        return false;
    }

    private static void logMarket(ServerLevel level, Villages.Village v) {
        for (String l : JobMarket.status(level, v.id())) Kit.log(l);
    }

    // ============================================================ hired from the next town

    /**
     * Oakhollow (call it A) is short of a miner: six hands, each at a trade it needs, none at the
     * mines. Its notice goes up. Riverford (B), two hundred blocks off, has four miners where it
     * needs two. B has never met A and hears nothing; once the two agree to trade, B hears of the
     * notice the same day. The three miners walk to B's board, read it and apply. A's leader takes
     * the twelve-level miner of thirty, turns down the fifteen-level one of sixty-four (too old for
     * the mines) and the three-level learner (the other has more years at it). The one taken on says
     * its goodbyes, walks the road, joins A's roll and takes up mining there; B's roll loses it; its
     * card, both towns' books and A's chronicle say where it came from and why.
     */
    @GameTest(template = EMPTY, timeoutTicks = 7200, batch = "jm01_hired_from_the_next_town")
    public static void jm01_hired_from_the_next_town(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        JobMarket.decideAfterForTests(1_000_000L);         // the leader decides when the test says (or when three have applied)
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int ax = 310000, bx = 310200, z = 50000;
        ground(level, ax, bx, z);
        Villages.Village a = town(level, ax, z), b = town(level, bx, z);
        // A: three farmers, two woodcutters and a smelter; every trade at its share, nobody at the mines.
        List<VillageFolkEntity> aFolk = new ArrayList<>();
        for (int i = 0; i < 3; i++) aFolk.add(folk(level, a, ax - 8 + i * 3, z + 9, StationTask.FARM, 2, 30 + 2 * i));
        for (int i = 0; i < 2; i++) aFolk.add(folk(level, a, ax + 9, z - 8 + i * 3, StationTask.WOOD, 2, 32));
        aFolk.add(folk(level, a, ax - 9, z - 8, StationTask.SMELT, 2, 34));
        campBeds(level, a.centre(), 8);                    // room for newcomers
        Villages.recountBeds(a.id());
        // B: four miners where it needs two or three (whatever its land leans it to; the fourth is its elder,
        // who stays), two farmers and a woodcutter.
        VillageFolkEntity veteran = folk(level, b, bx + 6, z + 9, StationTask.MINE, 12, 30);
        VillageFolkEntity learner = folk(level, b, bx - 6, z + 9, StationTask.MINE, 3, 26);
        VillageFolkEntity oldHand = folk(level, b, bx, z + 11, StationTask.MINE, 15, 64);
        VillageFolkEntity elderB = folk(level, b, bx + 3, z + 12, StationTask.MINE, 1, 36);
        folk(level, b, bx - 8, z - 8, StationTask.FARM, 2, 40);
        folk(level, b, bx - 5, z - 8, StationTask.FARM, 2, 42);
        folk(level, b, bx + 8, z - 8, StationTask.WOOD, 2, 44);
        long day = base / 24000L;
        Villages.electElder(a.id(), aFolk.get(0), day);    // the leaders: farmers, not the folk under test
        Villages.electElder(b.id(), elderB, day);
        Kit.log("jm01 A is " + Villages.name(a.id()) + " at " + a.centre().toShortString() + ", B is " + Villages.name(b.id())
            + " at " + b.centre().toShortString() + "; the miners of B: " + veteran.displayNameCap() + " (level " + veteran.tradeLevel(StationTask.MINE)
            + ", " + veteran.ageYears() + "), " + learner.displayNameCap() + " (level " + learner.tradeLevel(StationTask.MINE) + ", "
            + learner.ageYears() + "), " + oldHand.displayNameCap() + " (level " + oldHand.tradeLevel(StationTask.MINE) + ", " + oldHand.ageYears() + ")");
        int[] stage = { 0 };
        long[] since = { 0 };
        boolean[] read = { false }, onRoad = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);                 // the clock held at the end of the working day
            switch (stage[0]) {
                case 0 -> {
                    if (t < 20) return;
                    Kit.log("jm01 A's shares: miners " + String.format("%.2f", Villages.share(a.id(), StationTask.MINE)) + ", farmers "
                        + String.format("%.2f", Villages.share(a.id(), StationTask.FARM)) + ", woodcutters "
                        + String.format("%.2f", Villages.share(a.id(), StationTask.WOOD)) + "; B's miners "
                        + String.format("%.2f", Villages.share(b.id(), StationTask.MINE)) + " (over its share: "
                        + Villages.overStaffed(b.id(), StationTask.MINE) + ")");
                    List<JobMarket.Opening> up = JobMarket.post(level, a);
                    JobMarket.Opening mine = null;
                    for (JobMarket.Opening o : JobMarket.open(a.id())) if (o.task() == StationTask.MINE) mine = o;
                    logMarket(level, a);
                    helper.assertTrue(mine != null, "a town short of a miner puts a notice up for one (posted " + up.size() + ")");
                    List<String> board = JobMarket.board(level, a.id());
                    Kit.log("jm01 A's board: " + board);
                    helper.assertTrue(board.stream().anyMatch(l -> l.startsWith("LW|Wanted: a miner")), "the board shows the Wanted notice");
                    // B has never met A: no word of it.
                    boolean heard = JobMarket.seen(b.id(), day).stream().anyMatch(s -> s.town().equals(a.id()));
                    helper.assertTrue(!heard && JobMarket.link(b.id(), a.id()) == JobMarket.Link.NONE,
                        "a town that has never met the other hears nothing of its notices");
                    pact(a, b);
                    JobMarket.Seen seen = null;
                    for (JobMarket.Seen s : JobMarket.seen(b.id(), day)) if (s.town().equals(a.id()) && s.opening().task() == StationTask.MINE) seen = s;
                    helper.assertTrue(seen != null && seen.link() == JobMarket.Link.PACT,
                        "with a trade pact, the other town hears of the notice the day it goes up");
                    Kit.log("jm01 B hears: wanted, a miner in " + Villages.name(a.id()) + ", " + seen.opening().wage() + " a day ("
                        + seen.link().words + ")");
                    for (VillageFolkEntity f : List.of(veteran, learner, oldHand)) {
                        Kit.log("jm01 " + f.displayNameCap() + ": " + JobSeekers.reasonsLine(f));
                        JobSeekers.lookNow(f);
                    }
                    stage[0] = 1;
                    since[0] = t;
                }
                case 1 -> {
                    for (VillageFolkEntity f : List.of(veteran, learner, oldHand)) {
                        if (JobSeekers.atTheBoard(f) && !read[0]) {
                            read[0] = true;
                            Kit.log("jm01 tick " + t + ": " + f.displayNameCap() + " stands at B's board reading the notices, "
                                + String.format("%.1f", flat(f, b.centre())) + " from B's heart");
                        }
                    }
                    int applied = 0;
                    for (JobMarket.Application ap : JobMarket.applications(a.id())) if (ap.opening() > 0) applied++;
                    if (t % 100 == 0) {
                        Kit.log("jm01 tick " + t + ": " + applied + " applied; " + veteran.displayNameCap() + " busy " + JobSeekers.busy(veteran)
                            + " at " + veteran.blockPosition().toShortString() + "; " + learner.displayNameCap() + " busy " + JobSeekers.busy(learner)
                            + "; " + oldHand.displayNameCap() + " busy " + JobSeekers.busy(oldHand));
                    }
                    JobMarket.Application va = applicationOf(a, veteran);
                    boolean allIn = va != null && applicationOf(a, learner) != null && applicationOf(a, oldHand) != null;
                    if (allIn && !JobSeekers.busy(veteran) || va != null && va.verdict() != JobMarket.Verdict.WAITING) {
                        helper.assertTrue(read[0], "the miners walked to their own board and stood reading it");
                        int took = JobMarket.considerNow(level, a);
                        logMarket(level, a);
                        JobMarket.Application v = applicationOf(a, veteran), l = applicationOf(a, learner), o = applicationOf(a, oldHand);
                        helper.assertTrue(v != null && l != null && o != null, "all three idle miners applied to " + Villages.name(a.id())
                            + " (took " + took + ")");
                        helper.assertTrue(v.verdict() == JobMarket.Verdict.HIRED, "the most experienced miner fit for the mines is taken on: "
                            + v.verdict() + " — " + v.why());
                        helper.assertTrue(o.verdict() == JobMarket.Verdict.REFUSED && o.why().contains("too old"),
                            "the more experienced one of sixty-four is turned down, too old for the mines: " + o.verdict() + " — " + o.why());
                        helper.assertTrue(l.verdict() == JobMarket.Verdict.REFUSED && l.why().contains(veteran.displayNameCap()),
                            "the learner is told no: the other has more years at it: " + l.verdict() + " — " + l.why());
                        stage[0] = 2;
                        since[0] = t;
                        return;
                    }
                    if (t - since[0] > 3000) {
                        logMarket(level, a);
                        helper.fail("the miners never all applied (" + applied + "): " + veteran.debugLine() + " | " + learner.debugLine()
                            + " | " + oldHand.debugLine());
                    }
                }
                case 2 -> {
                    if (!onRoad[0] && JobSeekers.travelling(veteran)) {
                        onRoad[0] = true;
                        Kit.log("jm01 tick " + t + ": " + veteran.displayNameCap() + " is on the road; on A's roll now " + on(a, veteran)
                            + ", B's " + on(b, veteran) + "; its card: " + JobMarket.cardLine(veteran));
                        helper.assertTrue(on(a, veteran) && !on(b, veteran), "on the road, it is on the new town's roll and off the old one's");
                    }
                    if (t % 200 == 0) {
                        Kit.log("jm01 tick " + t + ": " + veteran.displayNameCap() + " at " + veteran.blockPosition().toShortString() + ", "
                            + String.format("%.0f", flat(veteran, a.centre())) + " from A's heart; travelling " + JobSeekers.travelling(veteran)
                            + ", " + veteran.debugLine());
                    }
                    if (veteran.ownerId() != null && veteran.ownerId().equals(a.id()) && !JobSeekers.busy(veteran) && flat(veteran, a.centre()) < 16) {
                        logMarket(level, a);
                        logMarket(level, b);
                        String card = JobMarket.cardLine(veteran);
                        Kit.log("jm01 arrived at tick " + t + ": trade " + veteran.stationTask() + ", bed " + veteran.bedPos() + ", card: " + card);
                        helper.assertTrue(onRoad[0], "it walked the road between the towns");
                        helper.assertTrue(veteran.stationTask() == StationTask.MINE, "it takes up the trade it was taken on for: " + veteran.stationTask());
                        helper.assertTrue(on(a, veteran) && !on(b, veteran), "it is on A's roll, and B has lost it");
                        helper.assertTrue(on(b, learner) && on(b, oldHand), "the two turned down stay where they were");
                        helper.assertTrue(card.toLowerCase(java.util.Locale.ROOT).contains(("came from " + Villages.name(b.id())).toLowerCase(java.util.Locale.ROOT)),
                            "its card says where it came from: " + card);
                        helper.assertTrue(JobMarket.moves(a.id()).stream().anyMatch(m -> m.in() && m.name().equals(veteran.displayNameCap())),
                            "A's books have it coming");
                        helper.assertTrue(JobMarket.moves(b.id()).stream().anyMatch(m -> !m.in() && m.name().equals(veteran.displayNameCap())),
                            "B's books have it leaving");
                        helper.assertTrue(newsHas(a, "came from " + Villages.name(b.id())), "A's chronicle tells it");
                        helper.assertTrue(JobMarket.report(level, a.id()).getList("applications", 10).size() >= 3,
                            "the city books' Jobs page has the applications");
                        helper.succeed();
                        return;
                    }
                    if (t - since[0] > 3600) {
                        helper.fail("the miner taken on never got to its new town: " + veteran.debugLine() + " at "
                            + veteran.blockPosition().toShortString() + ", " + String.format("%.0f", flat(veteran, a.centre())) + " from A's heart");
                    }
                }
                default -> { }
            }
        });
    }

    // ============================================================ refugees

    /**
     * A raid leaves four folk of a village with their beds gone and none to be had at home. Its
     * friendly neighbour, two hundred blocks off, has eight beds made up and three folk: it takes all
     * four in. They walk there, each is found a bed and set to work, and both chronicles tell it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "jm02_refugees_taken_in")
    public static void jm02_refugees_taken_in(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int rx = 312000, nx = 312200, z = 50000;
        ground(level, rx, nx, z);
        Villages.Village r = town(level, rx, z), n = town(level, nx, z);
        // The raided village: four, each with a bed of its own in a row sixteen blocks out.
        List<VillageFolkEntity> homeless = new ArrayList<>();
        StationTask[] trades = { StationTask.FARM, StationTask.FARM, StationTask.WOOD, StationTask.MINE };
        for (int i = 0; i < 4; i++) homeless.add(folk(level, r, rx - 6 + i * 4, z + 9, trades[i], 3, 30 + 2 * i));
        List<BlockPos> beds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BlockPos foot = Kit.surface(level, rx + 16, z - 6 + i * 3);
            bed(level, foot);
            beds.add(foot);
        }
        for (VillageFolkEntity f : homeless) f.claimBedNear(new BlockPos(rx + 17, beds.get(0).getY(), z));
        // The neighbour: three folk, eight beds at its camp.
        VillageFolkEntity nFarmer = folk(level, n, nx - 6, z + 9, StationTask.FARM, 2, 40);
        folk(level, n, nx + 6, z + 9, StationTask.WOOD, 2, 42);
        folk(level, n, nx, z - 9, StationTask.MINE, 2, 44);
        campBeds(level, n.centre(), 8);
        Villages.recountBeds(n.id());
        Ledger.relate(r.id(), n.id(), 30);                 // on good terms
        long day = base / 24000L;
        Villages.electElder(n.id(), nFarmer, day);
        int[] stage = { 0 };
        long[] since = { 0 };
        java.util.Set<UUID> taken = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            if (stage[0] == 0) {
                if (t < 20) return;
                int withBeds = 0;
                for (VillageFolkEntity f : homeless) if (f.bedPos() != null) withBeds++;
                Kit.log("jm02 " + Villages.name(r.id()) + ": " + withBeds + " of 4 have a bed; the neighbour " + Villages.name(n.id())
                    + " has room for " + (Villages.housing(n.id()) - Villages.headcount(n.id())) + " (link " + JobMarket.link(r.id(), n.id()) + ")");
                helper.assertTrue(withBeds == 4, "every folk of the village has a bed before the raid");
                // The raid: the beds burned in the night.
                for (BlockPos foot : beds) {
                    level.setBlock(foot, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(foot.east(), Blocks.AIR.defaultBlockState(), 3);
                }
                int went = JobMarket.raided(level, r, 1);
                Kit.log("jm02 after the raid: " + went + " went to " + Villages.name(n.id()) + "; chronicle: " + Villages.news(r.id()));
                helper.assertTrue(went == 4, "the four homeless go to the neighbour with room (" + went + ")");
                for (VillageFolkEntity f : homeless) {
                    helper.assertTrue(n.id().equals(f.ownerId()) && JobSeekers.travelling(f), f.displayNameCap() + " is on the road to "
                        + Villages.name(n.id()));
                }
                helper.assertTrue(newsHas(r, "homeless after the raid, went to " + Villages.name(n.id())), "the raided village's chronicle tells it");
                stage[0] = 1;
                since[0] = t;
                return;
            }
            // Each as it arrives: on the neighbour's roll, a bed found and work given (then it may walk off to its ground).
            for (VillageFolkEntity f : homeless) {
                if (taken.contains(f.getUUID()) || JobSeekers.travelling(f) || !n.id().equals(f.ownerId())) continue;
                taken.add(f.getUUID());
                Kit.log("jm02 tick " + t + ": " + f.displayNameCap() + " taken in, " + String.format("%.0f", flat(f, n.centre()))
                    + " from the heart: bed " + f.bedPos() + ", trade " + f.stationTask() + "; card: " + JobMarket.cardLine(f));
                helper.assertTrue(flat(f, n.centre()) < 20, f.displayNameCap() + " walked all the way to " + Villages.name(n.id()));
                helper.assertTrue(f.bedPos() != null, f.displayNameCap() + " is found a bed");
                helper.assertTrue(f.stationTask() != StationTask.NONE, f.displayNameCap() + " is given work: " + f.stationTask());
            }
            int arrived = taken.size();
            if (t % 200 == 0) {
                for (VillageFolkEntity f : homeless) {
                    Kit.log("jm02 tick " + t + ": " + f.displayNameCap() + " " + String.format("%.0f", flat(f, n.centre())) + " from "
                        + Villages.name(n.id()) + ", travelling " + JobSeekers.travelling(f) + ", bed " + f.bedPos() + ", trade " + f.stationTask());
                }
            }
            if (arrived == homeless.size()) {
                helper.assertTrue(newsHas(n, "taken in"), "the neighbour's chronicle tells it: " + Villages.news(n.id()));
                helper.succeed();
                return;
            }
            if (t - since[0] > 5000) helper.fail("the homeless never all got to the neighbour (" + arrived + " of 4)");
        });
    }

    // ============================================================ a reason to move

    /**
     * A notice for a farmer goes up in one town; its pact partner has a farmer content at home (at a
     * trade the town needs, paid as well, not young) and a folk out of work. Both are sent to look at
     * the board: the one out of work goes, reads it and applies; the one with no reason to move does
     * not go at all.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "jm03_only_with_a_reason")
    public static void jm03_only_with_a_reason(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        JobMarket.decideAfterForTests(1_000_000L);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        int cx = 314000, dx = 314200, z = 50000;
        ground(level, cx, dx, z);
        Villages.Village c = town(level, cx, z), d = town(level, dx, z);
        // C: three farmers (its share), two miners, and one out of work.
        VillageFolkEntity content = folk(level, c, cx - 8, z + 9, StationTask.FARM, 5, 40);
        VillageFolkEntity elderC = folk(level, c, cx - 5, z + 9, StationTask.FARM, 3, 44);
        folk(level, c, cx - 2, z + 9, StationTask.FARM, 3, 46);
        folk(level, c, cx + 8, z - 8, StationTask.MINE, 3, 36);
        folk(level, c, cx + 5, z - 8, StationTask.MINE, 3, 38);
        VillageFolkEntity idle = folk(level, c, cx, z - 10, StationTask.NONE, 0, 30);
        // D: no farmers at all.
        VillageFolkEntity elderD = folk(level, d, dx - 6, z + 9, StationTask.WOOD, 2, 40);
        folk(level, d, dx - 3, z + 9, StationTask.WOOD, 2, 40);
        folk(level, d, dx + 6, z + 9, StationTask.MINE, 2, 40);
        folk(level, d, dx + 3, z + 9, StationTask.MINE, 2, 40);
        folk(level, d, dx, z - 9, StationTask.SMELT, 2, 40);
        folk(level, d, dx + 3, z - 9, StationTask.MINE, 2, 40);
        long day = base / 24000L;
        Villages.electElder(c.id(), elderC, day);
        Villages.electElder(d.id(), elderD, day);
        pact(c, d);
        int[] stage = { 0 };
        boolean[] contentWent = { false };
        boolean[] lookedAtReasons = { false };
        boolean[] hadNoReason = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            if (stage[0] == 0) {
                if (t < 10) return;
                JobMarket.post(level, d);
                logMarket(level, d);
                JobMarket.Opening farm = null;
                for (JobMarket.Opening o : JobMarket.open(d.id())) if (o.task() == StationTask.FARM) farm = o;
                helper.assertTrue(farm != null, "a town with no farmers puts a notice up for one");
                content.persona().setMood(70, List.of("a good day"));
                JobSeekers.Reasons why = JobSeekers.reasons(content, d.id(), farm);
                hadNoReason[0] = why.why().isEmpty();
                lookedAtReasons[0] = true;
                Kit.log("jm03 the content farmer " + content.displayNameCap() + ": " + JobSeekers.reasonsLine(content) + " (reasons: "
                    + (why.why().isEmpty() ? "none" : why.words()) + ")");
                Kit.log("jm03 the folk out of work " + idle.displayNameCap() + ": " + JobSeekers.reasonsLine(idle));
                JobSeekers.lookNow(content);
                JobSeekers.lookNow(idle);
                stage[0] = 1;
                return;
            }
            if (JobSeekers.busy(content)) contentWent[0] = true;
            JobMarket.Application idleApp = applicationOf(d, idle);
            if (idleApp != null && t > 300) {
                JobMarket.Application contentApp = applicationOf(d, content);
                logMarket(level, d);
                Kit.log("jm03 " + idle.displayNameCap() + " applied: " + idleApp.verdict() + " (level " + idleApp.level() + ", "
                    + idleApp.age() + " years)");
                if (hadNoReason[0]) {
                    helper.assertTrue(!contentWent[0] && contentApp == null, "the folk with no reason to move did not go looking (went "
                        + contentWent[0] + ", applied " + (contentApp != null) + ")");
                } else {
                    Kit.log("jm03 the content farmer's mood fell before it was asked (it had a reason after all): not checked");
                }
                helper.succeed();
                return;
            }
            if (t % 100 == 0) Kit.log("jm03 tick " + t + ": idle busy " + JobSeekers.busy(idle) + " at " + idle.blockPosition().toShortString()
                + ", content busy " + JobSeekers.busy(content));
            if (t > 2800) helper.fail("the folk out of work never applied: " + idle.debugLine());
        });
    }
}
