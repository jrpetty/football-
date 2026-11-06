package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.CityTree;
import com.jrpetty.mcassistant.entity.CityTree.Civic;
import com.jrpetty.mcassistant.entity.Contentment;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The city's research (CityTree): a village earns research points every morning and its leader sets
 * the town to work on one civic after another, chosen by what it cares about and what the village is
 * short of; a civic is only open once the one before it in its branch is done; and once done, each
 * does what it says (Cheap Homes takes a fifth off a big rent, Common Tools speeds every trade, Crop
 * Rotation the farmers, Feast Days the contentment, Paved Roads the step, the Watch Drills a guard).
 * The board and the town's books say what is being studied and what is done.
 *
 * <p>Deterministic: nothing here waits on the folk doing anything. Each test sets the day's time past
 * the morning's books (Market.tick only runs them from 500 to 6000 of the day), so the village's own
 * morning cannot pick a civic under the test, and calls the research's morning itself. Each has its
 * own batch and its own ground: x 130000 to 137500, z 40000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class ResearchGameTests {

    private static final String EMPTY = "empty";

    /** A village of {@code n} founders at x, z, after its morning, with no research done. */
    private static Villages.Village village(GameTestHelper helper, ServerLevel level, int x, int z, int n) {
        Kit.reset(level);
        CityTree.resetForTests();
        level.setDayTime(8000);                    // past the morning's books: nothing of the village's own runs them
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null && stood > 0, "a village: " + stood + " stood");
        CityTree.clearForTests(v.id());
        return v;
    }

    /** The village's grown folk, in the order it knows them. */
    private static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isBaby()) out.add(f);
        return out;
    }

    /** Cares for this above all (95) and little for the rest (5). */
    private static void lean(VillageFolkEntity f, Values.Value top) {
        for (Values.Value v : Values.Value.values()) Values.setForTests(f, v, v == top ? 95 : 5);
    }

    private static boolean told(UUID village, String words) {
        for (Villages.News n : Villages.news(village)) if (n.text().contains(words)) return true;
        return false;
    }

    // ============================================================ the points

    /**
     * Points accrue on a morning: the rate (a point for every four grown folk, never less than one),
     * once a morning however often it is called; the leader picks a first-tier civic on the first
     * morning and the chronicle says so; and with the points in hand the next morning finishes it,
     * says so, starts the next and carries nothing over it did not earn.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "r01_research_points")
    public static void r01_research_points(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = village(helper, level, 130000, 40000, 8);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            List<VillageFolkEntity> folk = grown(id);
            helper.assertTrue(!folk.isEmpty(), "grown folk");
            Villages.electElder(id, folk.get(0), day);
            int rate = CityTree.rate(id).points();
            int before = CityTree.points(id);
            CityTree.morningForTests(level, v, day);
            int after = CityTree.points(id);
            Civic chosen = CityTree.current(id);
            CityTree.morningForTests(level, v, day);              // the same morning again: nothing more
            int again = CityTree.points(id);
            CityTree.morningForTests(level, v, day + 1);
            int next = CityTree.points(id);
            Kit.log("r01 the points: " + folk.size() + " grown, " + rate + " a day (" + CityTree.rate(id).why() + "); "
                + before + " -> " + after + " (again " + again + ") -> " + next + "; studying " + chosen + "; news "
                + Villages.news(id));
            helper.assertTrue(rate >= Math.max(1, Math.min(folk.size(), 16) / 4), "a point for every four grown folk, at least one: " + rate);
            helper.assertTrue(before == 0 && after == rate, "the morning's points: " + before + " -> " + after);
            helper.assertTrue(again == after, "once a morning: " + again);
            helper.assertTrue(next == after + rate, "and again the next morning: " + next);
            helper.assertTrue(chosen != null && chosen.tier == 1, "a first-tier civic chosen on the first morning: " + chosen);
            helper.assertTrue(told(id, "set the town to work on " + chosen.title), "the chronicle says what was chosen");
            // Enough in hand by the next morning: it is done, said, and the next begun from nothing.
            CityTree.pointsForTests(id, chosen.cost() - rate);
            CityTree.morningForTests(level, v, day + 2);
            Civic then = CityTree.current(id);
            Kit.log("r01 finished: " + CityTree.done(id) + "; now " + then + ", " + CityTree.points(id) + " in hand");
            helper.assertTrue(CityTree.has(id, chosen), "finished with the points: " + CityTree.done(id));
            helper.assertTrue(CityTree.done(id).get(chosen) == day + 2, "on the day: " + CityTree.done(id).get(chosen));
            helper.assertTrue(then != null && then != chosen, "and the next chosen: " + then);
            helper.assertTrue(CityTree.points(id) == 0, "the points spent on it: " + CityTree.points(id));
            helper.assertTrue(told(id, "finished " + chosen.title), "the chronicle says it was finished");
            helper.succeed();
        });
    }

    // ============================================================ the leader's choice

    /**
     * The leader chooses by what it cares about. A Provider elected for a full larder sets the town to
     * work on the Land first (Crop Rotation, the Land's first tier); the same village under a Homemaker
     * elected for homes starts on Cheap Homes. Its reason is kept, and the chronicle names it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "r02_research_choice")
    public static void r02_research_choice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = village(helper, level, 131500, 40000, 6);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity elder = grown(id).get(0);
            Villages.electElder(id, elder, day);
            elder.life().setTraitsForTests(Social.Trait.SHY, Social.Trait.GENEROUS);
            // A Provider, elected for a full larder.
            lean(elder, Values.Value.FOOD);
            Ledger.note(id, "mandate", elder.getUUID() + "|FOOD");
            List<CityTree.Weighed> food = CityTree.weigh(level, id);
            Civic first = CityTree.chooseForTests(level, id, day);
            Kit.log("r02 a Provider: " + food + " -> " + first + " (" + CityTree.report(id).getString("why") + ")");
            helper.assertTrue(first != null && first.branch == CityTree.Branch.LAND, "a Provider starts on the Land: " + first);
            helper.assertTrue(first == Civic.CROP_ROTATION, "with Crop Rotation, the Land's first: " + first);
            helper.assertTrue(told(id, elder.displayNameCap()) && told(id, "Crop Rotation"), "the chronicle names who chose and what");
            helper.assertTrue(!CityTree.report(id).getString("why").isEmpty(), "and why");
            // The same village under a Homemaker, elected for homes.
            CityTree.clearForTests(id);
            lean(elder, Values.Value.HOMES);
            Ledger.note(id, "mandate", elder.getUUID() + "|HOMES");
            List<CityTree.Weighed> homes = CityTree.weigh(level, id);
            Civic second = CityTree.chooseForTests(level, id, day);
            Kit.log("r02 a Homemaker: " + homes + " -> " + second + " (" + CityTree.report(id).getString("why") + ")");
            helper.assertTrue(second == Civic.CHEAP_HOMES, "a Homemaker starts on Cheap Homes: " + second);
            Ledger.forget(id, "mandate");
            helper.succeed();
        });
    }

    // ============================================================ prerequisites

    /**
     * A civic is open only once the one before it in its branch is done: Apprentice Halls can be
     * neither picked nor granted before Common Tools, nor Master Workshops before the rest of Industry;
     * the first tiers (one a branch) are open at the start; with Common Tools done, Apprentice Halls can be
     * picked. And left to the leader, every civic it ever chooses is open when it chooses it, all the
     * way through the tree. [perks] Ten branches now, and eight pairs of which a town may have only one: the
     * leader's way ends with every branch done to its top and one of each pair, the other closed.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "r03_research_prerequisites")
    public static void r03_research_prerequisites(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = village(helper, level, 133000, 40000, 6);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            Villages.electElder(id, grown(id).get(0), day);
            List<Civic> open = CityTree.openNow(id);
            String picked = CityTree.pick(level, id, Civic.APPRENTICE_HALLS, day);
            String granted = CityTree.grant(level, id, Civic.MASTER_WORKSHOPS, day);
            Kit.log("r03 at the start: open " + open + "; pick Apprentice Halls: " + picked + "; grant Master Workshops: " + granted);
            helper.assertTrue(open.size() == CityTree.Branch.values().length, "the first tiers are open, one a branch: " + open);   // [perks]
            for (Civic c : open) helper.assertTrue(c.tier == 1, "only first tiers: " + c);
            helper.assertTrue(CityTree.current(id) != Civic.APPRENTICE_HALLS && !CityTree.open(id, Civic.APPRENTICE_HALLS),
                "Apprentice Halls is not open before Common Tools: " + picked);
            helper.assertTrue(!CityTree.has(id, Civic.MASTER_WORKSHOPS), "Master Workshops cannot be granted out of turn: " + granted);
            CityTree.grant(level, id, Civic.COMMON_TOOLS, day);
            String now = CityTree.pick(level, id, Civic.APPRENTICE_HALLS, day);
            helper.assertTrue(CityTree.current(id) == Civic.APPRENTICE_HALLS, "with Common Tools done it can be picked: " + now);
            // The leader's way, through the whole tree.
            CityTree.clearForTests(id);
            List<Civic> order = new ArrayList<>();
            // [perks] Until there is nothing left to choose: every civic but the closed half of each pair.
            for (int i = 0; i < CityTree.ALL; i++) {
                Civic c = CityTree.chooseForTests(level, id, day + i);
                if (c == null) break;
                // [perks] Where the tier below is a pair, either of the two opens it (the other is closed for good).
                boolean ready = c.befores().isEmpty();
                for (Civic b : c.befores()) ready |= CityTree.has(id, b);
                helper.assertTrue(ready, c + " chosen before any of " + c.befores());
                Civic rival = c.rival();
                helper.assertTrue(rival == null || !CityTree.has(id, rival), c + " chosen after its pair " + rival);
                CityTree.grant(level, id, c, day + i);
                order.add(c);
            }
            Civic after = CityTree.chooseForTests(level, id, day + CityTree.ALL);
            List<String> board = CityTree.board(id);
            int closed = CityTree.pairs().size();
            Kit.log("r03 the leader's order: " + order + "; then " + after + "; board " + board);
            helper.assertTrue(CityTree.done(id).size() == CityTree.ALL - closed && after == null && CityTree.finished(id),
                "all done but the closed half of each pair, nothing left: " + CityTree.done(id).size() + " of " + CityTree.ALL);
            helper.assertTrue(!board.isEmpty() && board.get(0).contains("whole tree done"), "the board says so: " + board);
            helper.succeed();
        });
    }

    // ============================================================ the effects

    /**
     * Cheap Homes lowers the rent: a manor in a Stone Age village of fourteen (a field hand's day is two
     * coins there, so the manor lets at four) lets at three once it is done; a house at a coin a day
     * stays at a coin but is let off one payday in five. Home Loans takes a tenth off the price.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "r04_research_cheap_homes")
    public static void r04_research_cheap_homes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = village(helper, level, 134500, 40000, 14);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            Villages.ageForTests(id, Villages.Age.STONE);
            int manor = Homes.rentForTests(id, "manor"), house = Homes.rentForTests(id, "house");
            int price = Homes.priceForTests(id, "house");
            CityTree.grant(level, id, Civic.CHEAP_HOMES, day);
            int manorAfter = Homes.rentForTests(id, "manor"), houseAfter = Homes.rentForTests(id, "house");
            boolean letOff = CityTree.rentFreeToday(id, house, 5), letOffNot = CityTree.rentFreeToday(id, house, 6);
            CityTree.grant(level, id, Civic.BUILDERS_GUILD, day);
            CityTree.grant(level, id, Civic.HOME_LOANS, day);
            int priceAfter = Homes.priceForTests(id, "house");
            Kit.log("r04 Cheap Homes in " + Villages.rank(id) + " of " + Villages.headcount(id) + ": a manor " + manor + " -> " + manorAfter
                + ", a house " + house + " -> " + houseAfter + " (let off on day 5 " + letOff + ", day 6 " + letOffNot + "); "
                + "Home Loans: a house's price " + price + " -> " + priceAfter);
            helper.assertTrue(manor >= 3, "a manor in a Stone Age village of fourteen lets at three or more: " + manor);
            helper.assertTrue(manorAfter < manor, "Cheap Homes lowers the rent: " + manor + " -> " + manorAfter);
            helper.assertTrue(manorAfter >= manor * 7 / 10, "by about a fifth, no more: " + manorAfter);
            helper.assertTrue(houseAfter == house && house <= 2 && letOff && !letOffNot,
                "a small rent stays as it is, and is let off one payday in five");
            helper.assertTrue(priceAfter < price && priceAfter >= price * 85 / 100, "Home Loans: a tenth off the price: " + price + " -> " + priceAfter);
            helper.succeed();
        });
    }

    /**
     * Common Tools raises every trade's pace by three in the hundred (CityTree.workPercent, and so
     * the folk's own pace sum), Crop Rotation the farmers' by five more; the Builders' Guild speeds the
     * builders; Feast Days lifts the village's contentment by three; Paved Roads quickens every step;
     * and the Watch Drills give a guard a point more armour.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "r05_research_effects")
    public static void r05_research_effects(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = village(helper, level, 136000, 40000, 6);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            List<VillageFolkEntity> folk = grown(id);
            helper.assertTrue(folk.size() >= 2, "two grown folk");
            VillageFolkEntity farmer = folk.get(0), guard = folk.get(1);
            farmer.setJob(StationTask.FARM);
            guard.setJob(StationTask.GUARD);
            int farm0 = CityTree.workPercent(id, StationTask.FARM), pace0 = farmer.workBonusPercent(), ticks0 = farmer.actionPaceTicks();
            CityTree.grant(level, id, Civic.COMMON_TOOLS, day);
            int farm1 = CityTree.workPercent(id, StationTask.FARM), mine1 = CityTree.workPercent(id, StationTask.MINE);
            int pace1 = farmer.workBonusPercent(), ticks1 = farmer.actionPaceTicks();
            CityTree.grant(level, id, Civic.CROP_ROTATION, day);
            int farm2 = CityTree.workPercent(id, StationTask.FARM), mine2 = CityTree.workPercent(id, StationTask.MINE);
            Kit.log("r05 Common Tools: farming " + farm0 + " -> " + farm1 + "%, mining " + mine1 + "%; the farmer's pace "
                + pace0 + " -> " + pace1 + "% (" + ticks0 + " -> " + ticks1 + " ticks); Crop Rotation: farming " + farm2 + "%, mining " + mine2 + "%");
            helper.assertTrue(farm0 == 0 && farm1 == 3 && mine1 == 3, "Common Tools: +3% for every trade: " + farm1 + ", " + mine1);
            helper.assertTrue(CityTree.workPercent(id, StationTask.NONE) == 0, "but not for no trade at all");
            helper.assertTrue(pace1 >= pace0 && (pace0 >= 42 || pace0 <= -30 || pace1 == pace0 + 3),
                "and so the farmer's own pace: " + pace0 + " -> " + pace1);
            helper.assertTrue(ticks1 <= ticks0, "its work no slower: " + ticks0 + " -> " + ticks1);
            helper.assertTrue(farm2 == 8 && mine2 == 3, "Crop Rotation: +5% more for farmers only: " + farm2 + ", " + mine2);
            // The Builders' Guild.
            int build0 = farmer.buildPaceTicks();
            CityTree.grant(level, id, Civic.CHEAP_HOMES, day);
            CityTree.grant(level, id, Civic.BUILDERS_GUILD, day);
            int build1 = farmer.buildPaceTicks();
            helper.assertTrue(CityTree.buildPercent(id) == 10 && build1 <= build0, "the Builders' Guild: " + build0 + " -> " + build1 + " ticks a block");
            // Feast Days: the village's contentment, worked out afresh before and after.
            Contentment.resetForTests();
            int content0 = Contentment.of(level, id).score();
            CityTree.grant(level, id, Civic.FEAST_DAYS, day);
            Contentment.resetForTests();
            int content1 = Contentment.of(level, id).score();
            helper.assertTrue(content1 == Math.min(100, content0 + 3), "Feast Days: +3 contentment: " + content0 + " -> " + content1);
            // Paved Roads and the Watch Drills, on the folk at once.
            double step0 = farmer.getAttributeValue(Attributes.MOVEMENT_SPEED), armour0 = guard.getAttributeValue(Attributes.ARMOR);
            CityTree.grant(level, id, Civic.MARKET_CHARTER, day);
            CityTree.grant(level, id, Civic.PAVED_ROADS, day);
            CityTree.grant(level, id, Civic.WATCH_DRILLS, day);
            double step1 = farmer.getAttributeValue(Attributes.MOVEMENT_SPEED), armour1 = guard.getAttributeValue(Attributes.ARMOR);
            double farmerArmour = farmer.getAttributeValue(Attributes.ARMOR);
            Kit.log("r05 Builders' Guild " + build0 + " -> " + build1 + "; Feast Days " + content0 + " -> " + content1
                + "; Paved Roads step " + step0 + " -> " + step1 + "; Watch Drills a guard's armour " + armour0 + " -> " + armour1
                + " (a farmer's " + farmerArmour + ")");
            helper.assertTrue(step1 > step0 && step1 <= step0 * 1.06, "Paved Roads: a twentieth quicker on its feet: " + step0 + " -> " + step1);
            helper.assertTrue(Math.abs(armour1 - armour0 - 1.0) < 1e-6, "the Watch Drills: a guard has a point more armour: " + armour0 + " -> " + armour1);
            helper.succeed();
        });
    }

    // ============================================================ telling

    /**
     * What the town is studying and what it has done are on the village board (its line in "What
     * we're working towards", and the journal's page of it), in the town's books (the Research page's
     * snapshot: twenty civics, their states, the pick, the leader's reason and the history), and in
     * /village research.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "r06_research_shown")
    public static void r06_research_shown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = village(helper, level, 137500, 40000, 6);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            Villages.electElder(id, grown(id).get(0), day);
            CityTree.grant(level, id, Civic.COMMON_TOOLS, day);
            Civic next = CityTree.chooseForTests(level, id, day);
            helper.assertTrue(next != null, "something chosen");
            List<String> board = VillageBoards.compose(level, id);
            String line = "";
            for (String l : board) if (l.contains("Researching")) line = l;
            String page = VillageBoards.page(board)[1];
            CompoundTag books = Annals.snapshot(level, v);
            CompoundTag r = books.getCompound("research");
            ListTag civics = r.getList("civics", Tag.TAG_COMPOUND);
            CompoundTag tools = new CompoundTag();
            for (int i = 0; i < civics.size(); i++) if (civics.getCompound(i).getString("key").equals("common_tools")) tools = civics.getCompound(i);
            ListTag history = r.getList("history", Tag.TAG_STRING);
            List<String> said = CityTree.lines(id);
            Kit.log("r06 the board: " + line + " | the books: now " + r.getString("now") + " by " + r.getString("by") + ": "
                + r.getString("why") + "; " + civics.size() + " civics; Common Tools " + tools + "; history " + history + " | /village research: " + said);
            helper.assertTrue(line.startsWith("F") && line.contains(next.title) && line.contains("Common Tools"),
                "the board's research line names what is studied and what is done: " + line);
            helper.assertTrue(page.contains("Researching: " + next.title), "and the board's page in the journal");
            helper.assertTrue(civics.size() == CityTree.ALL, "the books have the whole tree: " + civics.size());
            helper.assertTrue(r.getString("now").equals(next.key()) && !r.getString("why").isEmpty(), "what is studied, and why: " + r);
            helper.assertTrue(tools.getString("state").equals("done") && tools.getLong("day") == day, "Common Tools done on the day: " + tools);
            helper.assertTrue(history.size() == 1 && history.getString(0).contains("Common Tools — done on day " + day), "the history: " + history);
            int locked = 0, open = 0;
            for (int i = 0; i < civics.size(); i++) {
                String s = civics.getCompound(i).getString("state");
                if (s.equals("locked")) locked++;
                if (s.equals("open")) open++;
            }
            helper.assertTrue(locked > 0 && open > 0, "some open, some locked: " + open + " open, " + locked + " locked");
            helper.assertTrue(said.size() == 1 + CityTree.Branch.values().length && said.get(0).contains(next.title),
                "/village research: a head and a line a branch: " + said);                                                 // [perks] ten branches
            helper.succeed();
        });
    }
}
