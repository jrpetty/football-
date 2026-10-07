package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CityTree;
import com.jrpetty.mcassistant.entity.CityTree.Civic;
import com.jrpetty.mcassistant.entity.Fears;
import com.jrpetty.mcassistant.entity.Fleet;
import com.jrpetty.mcassistant.entity.FolkSkills;
import com.jrpetty.mcassistant.entity.FolkSkills.Knack;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Gatherings;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PerkEvents;
import com.jrpetty.mcassistant.entity.Perks;
import com.jrpetty.mcassistant.entity.Plaques;
import com.jrpetty.mcassistant.entity.Quirks;
import com.jrpetty.mcassistant.entity.Quirks.Quirk;
import com.jrpetty.mcassistant.entity.Reigns;
import com.jrpetty.mcassistant.entity.Seasons;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wonders;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [perks] Perks: the town's, the leader's and the folk's. The research tree of ten branches, with its pairs (the one
 * a town takes closes the other for good) and a wonder at the top of each, one in the world, raised by the first town
 * to build it and its dues laid by out of the stores; the leader's perk in office by what it cares about, its skills
 * (each line in order, a point a level) and the legacies its reign leaves (three of a kind at most, a plaque each);
 * and the folk's knacks (one of its own for every trade, a master's at level thirty, the new trades' through the old
 * nearest them) and their quirks (each with its effect, a parent's handed on one time in two, the town known for the
 * commonest).
 *
 * <p>Deterministic: nothing here waits on the folk doing anything. Each test wakes the perks (the resets between the
 * tests put them to sleep: Perks.liveForTests), takes every founder's own quirks away so only the test's count, sets
 * the time past the morning's books and calls what it tests itself. Each has its own batch and its own ground:
 * x 1480000 to 1498000, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PerksGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** The research tree's first twenty, by the keys a saved town holds them by. */
    private static final String[] OLD_TWENTY = { "common_tools", "apprentice_halls", "guild_charters", "master_workshops",
        "crop_rotation", "herd_books", "seed_exchange", "granaries", "cheap_homes", "builders_guild", "home_loans", "housing_fund",
        "feast_days", "tavern_songs", "healers", "rest_day_charter", "market_charter", "paved_roads", "watch_drills", "counting_house" };

    // ============================================================ the ground

    /**
     * A town of {@code n} founders at x, z, no research done, nobody with a quirk. The leader's perks awake only where
     * the test is of them ({@code live}): elsewhere whoever the town chose to lead would lean what is measured.
     */
    private static Villages.Village town(GameTestHelper helper, int x, int z, int n, boolean fresh, boolean live) {
        ServerLevel level = helper.getLevel();
        if (fresh) {
            Kit.reset(level);
            Perks.liveForTests(live);
            CityTree.resetForTests();
            level.setDayTime(24000L * 6 + 8000);          // past the morning's books: nothing of the town's own runs them
        }
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null && stood > 0, "a town: " + stood + " stood");
        CityTree.clearForTests(v.id());
        for (VillageFolkEntity f : grown(v.id())) Quirks.setForTests(f);
        return v;
    }

    private static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isBaby()) out.add(f);
        return out;
    }

    /** Cares for this above all (95) and little for the rest (5). */
    private static void lean(VillageFolkEntity f, Values.Value top) {
        for (Values.Value v : Values.Value.values()) Values.setForTests(f, v, v == top ? 95 : 5);
    }

    /** At this trade, at this level. */
    private static void trade(VillageFolkEntity f, StationTask t, int level) {
        f.setJob(t);
        f.tradeXpForTests(t, AssistantEntity.xpForLevel(level));
    }

    /** The civic done, and the tier before it in its branch first (the first of a pair, where the tier is one). */
    private static void upTo(ServerLevel level, UUID id, Civic c, long day) {
        if (CityTree.has(id, c)) return;
        List<Civic> before = c.befores();
        boolean ready = before.isEmpty();
        for (Civic b : before) ready |= CityTree.has(id, b);
        if (!ready) {
            for (Civic b : before) {
                if (!CityTree.locked(id, b)) {
                    upTo(level, id, b, day);
                    break;
                }
            }
        }
        CityTree.grant(level, id, c, day);
    }

    /** Every store chest of the town emptied, and one marked chest here with these in it. */
    private static void stores(ServerLevel level, UUID id, BlockPos at, ItemStack... goods) {
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
            }
        }
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
    }

    private static int stock(ServerLevel level, UUID id, Item it) {
        Villages.forgetStock();
        return Market.stock(level, id, s -> s.is(it));
    }

    private static boolean told(UUID village, String words) {
        for (Villages.News n : Villages.news(village)) if (n.text().contains(words)) return true;
        return false;
    }

    private static CompoundTag civicTag(CompoundTag report, Civic c) {
        ListTag l = report.getList("civics", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getString("key").equals(c.key())) return l.getCompound(i);
        return new CompoundTag();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    // ============================================================ the tree

    /**
     * The tree: ten branches, sixty civics and more, the first twenty kept by the keys a saved town holds them by; at
     * least five pairs, each of one tier of one branch; at the top of every branch one wonder, each a real building
     * (a sound drawing the builders know, on the lot it wants). A Merchant at heart weighs the Free Market above the
     * Guild Monopolies and a Traditionalist the other way; once the Monopolies are done the Free Market is closed for
     * good (granted, picked by the leader or not, it stays closed, and the books say why). A second town under a
     * Merchant takes the Free Market, and the two come out measurably different: a pickaxe 11 coins in the one and 9 in
     * the other, the other's smiths 6% quicker. The town's ethos leans the choice (its seam: the Sea, by its ways), a
     * leader sets the study, and the books and the identity tell it all.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk01_perks_tree")
    public static void pk01_perks_tree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1480000, Z, 5, true, false);
        Villages.Village second = town(helper, 1480700, Z, 5, false, false);
        UUID id = v.id(), other = second.id();
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            helper.assertTrue(!id.equals(other), "two towns");
            helper.assertTrue(CityTree.Branch.values().length == 10, "ten branches: " + CityTree.Branch.values().length);
            helper.assertTrue(CityTree.ALL >= 60, "sixty civics or more: " + CityTree.ALL);
            for (String old : OLD_TWENTY) helper.assertTrue(CityTree.byKey(old) != null, "the old civic " + old + " kept");
            List<Civic[]> pairs = CityTree.pairs();
            helper.assertTrue(pairs.size() >= 5, "five pairs or more: " + pairs.size());
            for (Civic[] p : pairs) {
                helper.assertTrue(p[0].branch == p[1].branch && p[0].tier == p[1].tier && p[0].rival() == p[1] && p[1].rival() == p[0],
                    "a pair is one tier of one branch, each the other's: " + p[0] + " / " + p[1]);
            }
            for (CityTree.Branch b : CityTree.Branch.values()) {
                List<Civic> tops = new ArrayList<>();
                for (Civic c : Civic.values()) if (c.branch == b && c.wonder()) tops.add(c);
                helper.assertTrue(tops.size() == 1 && tops.get(0).tier == b.tiers(), "one wonder at the top of " + b + ": " + tops);
                Wonders.Wonder w = Wonders.of(tops.get(0));
                helper.assertTrue(w != null && Blueprints.has(w.structure) && BuildGoal.STRUCTURES.contains(w.structure)
                    && TownPlan.placeFor(w.structure).equals(w.place), "the wonder of " + b + " is a building the builders know: " + w);
            }
            // The pair, weighed by the leader's heart.
            upTo(level, id, Civic.MASTER_WORKSHOPS, day);
            helper.assertTrue(CityTree.open(id, Civic.GUILD_MONOPOLIES) && CityTree.open(id, Civic.FREE_MARKET), "both of the pair open");
            VillageFolkEntity leader = grown(id).get(0);
            Villages.electElder(id, leader, day);
            lean(leader, Values.Value.WEALTH);
            double[] merchant = pairScores(level, id);
            lean(leader, Values.Value.TRADITION);
            double[] traditional = pairScores(level, id);
            Kit.log("pk01 a Merchant: Monopolies " + merchant[0] + ", Free Market " + merchant[1] + "; a Traditionalist: "
                + traditional[0] + ", " + traditional[1]);
            helper.assertTrue(merchant[1] > merchant[0] && traditional[0] > traditional[1],
                "a Merchant leans to the Free Market, a Traditionalist to the Monopolies");
            String granted = CityTree.grant(level, id, Civic.GUILD_MONOPOLIES, day);
            String refused = CityTree.grant(level, id, Civic.FREE_MARKET, day);
            String picked = CityTree.leaderPick(level, id, Civic.FREE_MARKET, day, "Tester");
            Kit.log("pk01 " + granted + " | " + refused + " | " + picked);
            helper.assertTrue(CityTree.has(id, Civic.GUILD_MONOPOLIES) && !CityTree.has(id, Civic.FREE_MARKET)
                && CityTree.current(id) != Civic.FREE_MARKET, "the Free Market closed: neither granted nor picked");
            helper.assertTrue(CityTree.locked(id, Civic.FREE_MARKET) && CityTree.lockedWhy(id, Civic.FREE_MARKET).contains("Guild Monopolies"),
                "closed, and why: " + CityTree.lockedWhy(id, Civic.FREE_MARKET));
            CompoundTag free = civicTag(CityTree.report(id), Civic.FREE_MARKET);
            helper.assertTrue(free.getString("state").equals("closed") && free.getString("rival").equals("Guild Monopolies"),
                "the books show it closed: " + free);
            helper.assertTrue(told(id, "Free Market"), "the chronicle says the Free Market is closed");
            // Another town, under a Merchant, goes the other way, and the two come out measurably different.
            VillageFolkEntity merchantLeader = grown(other).get(0);
            Villages.electElder(other, merchantLeader, day);
            lean(merchantLeader, Values.Value.WEALTH);
            upTo(level, other, Civic.MASTER_WORKSHOPS, day);
            double[] theirs = pairScores(level, other);
            CityTree.grant(level, other, theirs[1] > theirs[0] ? Civic.FREE_MARKET : Civic.GUILD_MONOPOLIES, day);
            ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
            double ours = Perks.priceEach(id, tool, null, 10.0), theirPrice = Perks.priceEach(other, tool, null, 10.0);
            Kit.log("pk01 two towns: a pickaxe " + ours + " here, " + theirPrice + " there; the smith " + CityTree.workPercent(id, StationTask.SMITH)
                + "% here, " + CityTree.workPercent(other, StationTask.SMITH) + "% there");
            helper.assertTrue(CityTree.has(other, Civic.FREE_MARKET) && CityTree.locked(other, Civic.GUILD_MONOPOLIES),
                "the Merchant's town takes the Free Market");
            helper.assertTrue(near(ours, 11.0) && near(theirPrice, 9.0)
                && CityTree.workPercent(other, StationTask.SMITH) == CityTree.workPercent(id, StationTask.SMITH) + 6,
                "the one town's tools dearer, the other's cheaper and its smiths quicker");
            // The town's ethos leans the choice.
            java.util.function.ToIntBiFunction<UUID, CityTree.Branch> ethosWas = CityTree.ETHOS;
            CityTree.ETHOS = (village, b) -> b == CityTree.Branch.SEA ? 500 : 0;
            Civic chosen;
            try {
                chosen = CityTree.chooseForTests(level, id, day);
            } finally {
                CityTree.ETHOS = ethosWas;
            }
            helper.assertTrue(chosen == Civic.FISHWIVES_GUILD, "a town of the sea studies the sea's first: " + chosen);
            // The leader sets the study itself (a player who leads does, from its Leader page).
            String set = CityTree.leaderPick(level, id, Civic.WATCH_HOUSE, day, "Tester");
            helper.assertTrue(CityTree.current(id) == Civic.WATCH_HOUSE && told(id, "Tester set the town to work on Watch House"),
                "the leader's own choice: " + set);
            // The books and the identity.
            CompoundTag books = Annals.snapshot(level, v);
            CompoundTag perks = books.getCompound("perks");
            helper.assertTrue(perks.getList("wonders", Tag.TAG_COMPOUND).size() == Wonders.Wonder.values().length
                && perks.getList("ways", Tag.TAG_COMPOUND).size() == pairs.size() && perks.getList("skills", Tag.TAG_COMPOUND).size() == 12,
                "the Perks page has the wonders, the ways and the skills: " + perks.getAllKeys());
            List<String> identity = Perks.identityLines(id);
            Kit.log("pk01 identity: " + identity);
            helper.assertTrue(identity.stream().anyMatch(s -> s.contains("Guild Monopolies (not Free Market)")), "its ways in its identity");
            helper.succeed();
        });
    }

    /** {the Monopolies', the Free Market's} scores as the leader weighs the town's study now. */
    private static double[] pairScores(ServerLevel level, UUID id) {
        double[] out = { Double.NaN, Double.NaN };
        for (CityTree.Weighed w : CityTree.weigh(level, id)) {
            if (w.civic() == Civic.GUILD_MONOPOLIES) out[0] = w.score();
            if (w.civic() == Civic.FREE_MARKET) out[1] = w.score();
        }
        return out;
    }

    // ============================================================ the new branches

    /**
     * Defence: the Watch House gives a guard two hearts; the Standing Army a guard more on the books (from two to
     * three), a point more of blow and a coin more of pay, and closes the Militia; the Fletchers' Charter sixteen more
     * arrows. The other way, in the same town after: the Militia a point of armour on everybody grown and a guard
     * fewer (but never none); the Earthworks six blocks more sight from the wall and the hunters 5% quicker; the
     * Arena's plans drawn do nothing, but raised it is the guards' (two more blow and armour) and the town's (+3).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk02_perks_defence")
    public static void pk02_perks_defence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1482000, Z, 6, true, false);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            List<VillageFolkEntity> folk = grown(id);
            VillageFolkEntity guard = folk.get(0), hand = folk.get(1);
            trade(guard, StationTask.GUARD, 5);
            trade(hand, StationTask.FARM, 5);
            CityTree.tend(guard);
            CityTree.tend(hand);
            double hp0 = guard.getMaxHealth(), hit0 = guard.getAttributeValue(Attributes.ATTACK_DAMAGE);
            double armour0 = hand.getAttributeValue(Attributes.ARMOR), guardArmour0 = guard.getAttributeValue(Attributes.ARMOR);
            double sight0 = FolkSkills.sightBonus(guard);
            helper.assertTrue(near(Perks.share(id, StationTask.GUARD, 2.0), 2.0), "two guards wanted, as the town would have it");
            upTo(level, id, Civic.WATCH_HOUSE, day);
            CityTree.tend(guard);
            double hpWatch = guard.getMaxHealth();
            helper.assertTrue(hpWatch >= hp0 + 4 - 1e-6, "the Watch House: two hearts more (a guard's doubled): " + hp0 + " -> " + hpWatch);
            CityTree.grant(level, id, Civic.STANDING_ARMY, day);
            CityTree.tend(guard);
            helper.assertTrue(near(guard.getAttributeValue(Attributes.ATTACK_DAMAGE), hit0 + 1), "the Standing Army: a point more blow");
            helper.assertTrue(near(Perks.share(id, StationTask.GUARD, 2.0), 3.0), "and a guard more: " + Perks.share(id, StationTask.GUARD, 2.0));
            helper.assertTrue(Perks.wageExtra(guard, 5) == 1 && Perks.wageExtra(hand, 5) == 0, "a coin more for a guard, nothing for a farmer");
            helper.assertTrue(CityTree.locked(id, Civic.MILITIA), "the Militia closed");
            CityTree.grant(level, id, Civic.FLETCHERS_CHARTER, day);
            helper.assertTrue(Perks.quiver(id, guard) == 16, "the Fletchers' Charter: sixteen arrows more");
            // The other way.
            CityTree.clearForTests(id);
            upTo(level, id, Civic.MILITIA, day);
            CityTree.tend(guard);
            CityTree.tend(hand);
            helper.assertTrue(near(hand.getAttributeValue(Attributes.ARMOR), armour0 + 1)
                && near(guard.getAttributeValue(Attributes.ARMOR), guardArmour0 + 1), "the Militia: a point of armour on everybody");
            helper.assertTrue(near(guard.getAttributeValue(Attributes.ATTACK_DAMAGE), hit0) && near(guard.getMaxHealth(), hpWatch),
                "no Standing Army's blow now, the Watch House's hearts still");
            helper.assertTrue(near(Perks.share(id, StationTask.GUARD, 3.0), 2.0) && near(Perks.share(id, StationTask.GUARD, 1.0), 1.0),
                "a guard fewer, never none");
            CityTree.grant(level, id, Civic.FLETCHERS_CHARTER, day);
            int hunt0 = CityTree.workPercent(id, StationTask.HUNT);
            CityTree.grant(level, id, Civic.EARTHWORKS, day);
            helper.assertTrue(near(FolkSkills.sightBonus(guard), sight0 + 6), "the Earthworks: six blocks more sight: " + FolkSkills.sightBonus(guard));
            helper.assertTrue(CityTree.workPercent(id, StationTask.HUNT) == hunt0 + 5 && CityTree.locked(id, Civic.STONE_WALLS),
                "the hunters 5% quicker; the Stone Walls closed");
            // The Arena: plans, then the building.
            CityTree.grant(level, id, Civic.ARENA, day);
            CityTree.tend(guard);
            double hitPlans = guard.getAttributeValue(Attributes.ATTACK_DAMAGE);
            List<String> good = new ArrayList<>(), bad = new ArrayList<>();
            int c0 = Perks.contentment(id, good, bad);
            Villages.noteProject(id, "arena", level.getGameTime());
            CityTree.tend(guard);
            good.clear();
            int c1 = Perks.contentment(id, good, bad);
            Kit.log("pk02 the Arena: blow " + hit0 + " -> " + hitPlans + " (plans) -> " + guard.getAttributeValue(Attributes.ATTACK_DAMAGE)
                + "; contentment " + c0 + " -> " + c1 + " " + good);
            helper.assertTrue(near(hitPlans, hit0), "the plans alone do nothing");
            helper.assertTrue(Wonders.owns(id, Civic.ARENA) && near(guard.getAttributeValue(Attributes.ATTACK_DAMAGE), hit0 + 2)
                && near(guard.getAttributeValue(Attributes.ARMOR), guardArmour0 + 1 + 2), "raised: the guards two more blow and armour");
            helper.assertTrue(c1 == c0 + 3 && good.contains("the games at the Arena"), "and the town 3 more content");
            helper.succeed();
        });
    }

    /**
     * Lore, the Sea and the Arcane: Primers and the Scholars' Endowment teach the school a quarter, then a fifth, more;
     * the Printing Press prints a new book once more on a plain book from the stores (and not without one); the
     * Scholars two points more a morning, the Surveyors one and the scouts a quarter further. The Fishwives' fishers
     * 6% quicker; the Navigators sail in the rain (not a storm); the Diving Bells a breath more for everybody; the
     * Shipwrights a boat more. Herbals the brewer 6%; the Blaze Wardens' ward; the Nether Charts a party every day,
     * and quartz and a rod more home; the Alchemists three powders from a rod.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk03_perks_lore_sea_arcane")
    public static void pk03_perks_lore_sea_arcane(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1484000, Z, 6, true, false);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            List<VillageFolkEntity> folk = grown(id);
            VillageFolkEntity scout = folk.get(0), diver = folk.get(1);
            trade(scout, StationTask.SCOUT, 5);
            trade(diver, StationTask.DIVER, 5);
            int rate0 = CityTree.rate(id).points();
            upTo(level, id, Civic.PRIMERS, day);
            helper.assertTrue(CityTree.schoolPercent(id) == 25, "Primers: a quarter more at school");
            CityTree.grant(level, id, Civic.PRINTING_PRESS, day);
            BlockPos chest = Kit.surface(level, 1484000 + 6, Z + 6);
            stores(level, id, chest);
            boolean none = Perks.printed(level, v, new ItemStack(Items.WRITTEN_BOOK));
            stores(level, id, chest, new ItemStack(Items.BOOK, 2));
            boolean printed = Perks.printed(level, v, new ItemStack(Items.WRITTEN_BOOK));
            helper.assertTrue(!none && printed && stock(level, id, Items.BOOK) == 1 && stock(level, id, Items.WRITTEN_BOOK) == 1,
                "the press prints on a plain book from the stores, and not without one");
            CityTree.grant(level, id, Civic.SCHOLARS_ENDOWMENT, day);
            helper.assertTrue(CityTree.rate(id).points() == rate0 + 2 && CityTree.schoolPercent(id) == 45
                && CityTree.locked(id, Civic.CRAFTSMENS_ENDOWMENT), "the Scholars: two points more, the school a fifth more, the Craftsmen closed");
            CityTree.grant(level, id, Civic.SURVEYORS_OFFICE, day);
            helper.assertTrue(CityTree.rate(id).points() == rate0 + 3 && Perks.scoutRange(scout, 100) == 125,
                "the Surveyors: a point more, the scouts a quarter further: " + Perks.scoutRange(scout, 100));
            // The sea.
            int fish0 = CityTree.workPercent(id, StationTask.FISH);
            upTo(level, id, Civic.FISHWIVES_GUILD, day);
            helper.assertTrue(CityTree.workPercent(id, StationTask.FISH) == fish0 + 6, "the Fishwives: fishers 6% quicker");
            Fleet.weatherForTests("rain");
            String rain0 = Perks.keptInForTests(level, id);
            CityTree.grant(level, id, Civic.NAVIGATORS_GUILD, day);
            String rain1 = Perks.keptInForTests(level, id);
            Fleet.weatherForTests("storm");
            String storm = Perks.keptInForTests(level, id);
            Fleet.weatherForTests(null);
            helper.assertTrue("the rain".equals(rain0) && rain1 == null && "a storm".equals(storm),
                "the Navigators sail in the rain, never a storm: " + rain0 + " / " + rain1 + " / " + storm);
            CityTree.tend(diver);
            double breath0 = diver.getAttributeValue(Attributes.OXYGEN_BONUS);
            CityTree.grant(level, id, Civic.DIVING_BELLS, day);
            CityTree.tend(diver);
            helper.assertTrue(near(diver.getAttributeValue(Attributes.OXYGEN_BONUS), breath0 + 1), "the Diving Bells: a breath more");
            int boats0 = Fleet.boatsWanted(id);
            CityTree.grant(level, id, Civic.SHIPWRIGHTS, day);
            helper.assertTrue(Fleet.boatsWanted(id) == boats0 + 1, "the Shipwrights: a boat more: " + boats0 + " -> " + Fleet.boatsWanted(id));
            // The arcane.
            int brew0 = CityTree.workPercent(id, StationTask.BREW);
            upTo(level, id, Civic.HERBALS, day);
            helper.assertTrue(CityTree.workPercent(id, StationTask.BREW) == brew0 + 6, "Herbals: the brewer 6% quicker");
            VillageFolkEntity runner = folk.get(2);
            trade(runner, StationTask.NETHER, 5);
            DamageSource fire = level.damageSources().inFire();
            float unwarded = PerkEvents.fireForTests(runner, fire, 4F);
            helper.assertTrue(!CityTree.blazeWarded(id) && CityTree.netherGap(id) == 2 && CityTree.netherWalkPercent(id) == 100,
                "no ward, a run every other day");
            CityTree.grant(level, id, Civic.BLAZE_WARDENS, day);
            float warded = PerkEvents.fireForTests(runner, fire, 4F), farmer = PerkEvents.fireForTests(scout, fire, 4F);
            helper.assertTrue(unwarded == 4F && warded == 2F && farmer == 4F, "the Blaze Wardens: a Nether runner takes half the fire, nobody else");
            CityTree.grant(level, id, Civic.NETHER_CHARTS, day);
            helper.assertTrue(CityTree.netherGap(id) == 1 && CityTree.netherWalkPercent(id) == 75,
                "the Nether Charts: a run every day, a quarter less walking");
            helper.assertTrue(CityTree.powderPerRod(id) == 2, "two powders to a rod");
            CityTree.grant(level, id, Civic.ALCHEMISTS_GUILD, day);
            helper.assertTrue(CityTree.powderPerRod(id) == 3, "the Alchemists: three");
            helper.succeed();
        });
    }

    /**
     * The ways, in the Industry, the Land, Homes, Wellbeing, Trade and Faith: the Guild Monopolies' crafted goods a
     * tenth dearer, the Free Market's a tenth cheaper and its crafts 6% quicker; the Open Granary's food free, the
     * Private Larders' a fifth dearer and its wage a twentieth more; the Turnpikes' roads and bridges half again as
     * fast and every step 4% quicker, the Observer Pattern Books' rails a quarter; Patronage's +3 and buskers' coins,
     * Plain Living's -2, its shorter breaks and its pace; Open Borders' caravans and warmth, Tariffs' takings and their
     * cooling; and Saints' Days' feast on the second day of each season.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk04_perks_ways")
    public static void pk04_perks_ways(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1486000, Z, 5, true, false);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity hand = grown(id).get(0), busker = grown(id).get(1);
            trade(hand, StationTask.FARM, 5);
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE), bread = new ItemStack(Items.BREAD);
            helper.assertTrue(near(Perks.priceEach(id, pick, null, 10.0), 10.0) && near(Perks.priceEach(id, bread, null, 10.0), 10.0), "plain prices");
            upTo(level, id, Civic.GUILD_MONOPOLIES, day);
            helper.assertTrue(near(Perks.priceEach(id, pick, null, 10.0), 11.0) && near(Perks.priceEach(id, bread, null, 10.0), 10.0),
                "the Monopolies: a tool a tenth dearer, bread as it was: " + Perks.priceEach(id, pick, null, 10.0));
            CityTree.clearForTests(id);
            int smith0 = CityTree.workPercent(id, StationTask.SMITH);
            upTo(level, id, Civic.FREE_MARKET, day);
            int smith1 = CityTree.workPercent(id, StationTask.SMITH);
            helper.assertTrue(near(Perks.priceEach(id, pick, null, 10.0), 9.0), "the Free Market: a tenth cheaper");
            CityTree.clearForTests(id);
            upTo(level, id, Civic.MASTER_WORKSHOPS, day);
            int smithChartered = CityTree.workPercent(id, StationTask.SMITH);
            helper.assertTrue(smith1 == smithChartered + 6 && smith0 == 0, "and its crafts 6% quicker: " + smithChartered + " -> " + smith1);
            // The Land.
            CityTree.clearForTests(id);
            upTo(level, id, Civic.OPEN_GRANARY, day);
            List<String> good = new ArrayList<>(), bad = new ArrayList<>();
            helper.assertTrue(Perks.foodFree(id) && Perks.contentment(id, good, bad) == 2, "the Open Granary: food free, +2: " + good);
            CityTree.clearForTests(id);
            upTo(level, id, Civic.PRIVATE_LARDERS, day);
            helper.assertTrue(!Perks.foodFree(id) && near(Perks.priceEach(id, bread, null, 10.0), 12.0), "Private Larders: bread a fifth dearer");
            helper.assertTrue(Perks.wageExtra(hand, 20) == 1, "and a twentieth more wage: " + Perks.wageExtra(hand, 20));
            // Homes and works.
            CityTree.clearForTests(id);
            CityTree.tend(hand);
            double step0 = hand.getAttributeValue(Attributes.MOVEMENT_SPEED);
            upTo(level, id, Civic.TURNPIKES, day);
            CityTree.tend(hand);
            helper.assertTrue(CityTree.worksSteps(id, "roads", 6) == 9 && CityTree.worksSteps(id, "bridges", 12) == 18
                && CityTree.worksSteps(id, "rails", 8) == 8, "the Turnpikes: the roads and the bridges half again");
            helper.assertTrue(near(hand.getAttributeValue(Attributes.MOVEMENT_SPEED), step0 * 1.04), "and every step 4% quicker");
            CityTree.clearForTests(id);
            upTo(level, id, Civic.OBSERVER_PATTERN_BOOKS, day);
            helper.assertTrue(CityTree.worksSteps(id, "rails", 8) == 10 && CityTree.locked(id, Civic.TURNPIKES), "the Pattern Books: the rails a quarter");
            // Wellbeing.
            CityTree.clearForTests(id);
            double tips0 = Perks.tipsForTests(busker);
            upTo(level, id, Civic.PATRONAGE, day);
            good.clear();
            int patron = Perks.contentment(id, good, bad);
            helper.assertTrue(patron == 3 && near(Perks.tipsForTests(busker), tips0 + 0.12), "Patronage: +3, and the buskers' coins: " + good);
            CityTree.clearForTests(id);
            upTo(level, id, Civic.REST_DAY_CHARTER, day);
            int pace0 = CityTree.workPercent(id, StationTask.FARM), rest0 = CityTree.restPercent(id);
            CityTree.grant(level, id, Civic.PLAIN_LIVING, day);
            bad.clear();
            int plain = Perks.contentment(id, new ArrayList<>(), bad);
            helper.assertTrue(plain == -2 && CityTree.restPercent(id) == rest0 * 95 / 100,
                "Plain Living: -2 and breaks a twentieth shorter: " + rest0 + " -> " + CityTree.restPercent(id) + " " + bad);
            helper.assertTrue(CityTree.workPercent(id, StationTask.FARM) == pace0 + 3, "and every trade 3% quicker");
            // Trade.
            CityTree.clearForTests(id);
            upTo(level, id, Civic.COUNTING_HOUSE, day);
            int takings0 = CityTree.takingsPercent(id), lots0 = Perks.caravanLots(id);
            long even = day - Math.floorMod(day, 2L);
            CityTree.grant(level, id, Civic.OPEN_BORDERS, day);
            helper.assertTrue(Perks.caravanLots(id) == lots0 + 2 && CityTree.takingsPercent(id) == takings0 * 103 / 100
                && Perks.warmth(id, even) == 1 && Perks.warmth(id, even + 1) == 1, "Open Borders: two lots, 3% more, a neighbour warmer each day");
            CityTree.clearForTests(id);
            upTo(level, id, Civic.TARIFFS, day);
            helper.assertTrue(CityTree.takingsPercent(id) == takings0 * 106 / 100 && Perks.warmth(id, even + 1) == -1 && Perks.warmth(id, even) == 0,
                "Tariffs: 6% more, and the neighbours cool every other day");
            // Faith: a saint's feast.
            CityTree.clearForTests(id);
            upTo(level, id, Civic.REMEMBRANCE, day);
            long saint = -1;
            for (long d = day + 1; d < day + 400 && saint < 0; d++) {
                if (Seasons.dayInSeason(id, d) == 2 && Math.floorMod(d, 7L) != 6) saint = d;
            }
            helper.assertTrue(saint > 0, "a season's second day ahead");
            Gatherings.Kind before = Gatherings.tonight(id, saint);
            CityTree.grant(level, id, Civic.SAINTS_DAYS, day);
            Gatherings.Kind after = Gatherings.tonight(id, saint);
            helper.assertTrue(before == null && after == Gatherings.Kind.FEAST, "Saints' Days: a feast on day " + saint + ": " + before + " -> " + after);
            double crime0 = Perks.crimeFactor(id);
            CityTree.grant(level, id, Civic.ALMSHOUSE, day);
            helper.assertTrue(Perks.crimeFactor(id) < crime0, "the Almshouse: less tempted: " + crime0 + " -> " + Perks.crimeFactor(id));
            helper.succeed();
        });
    }

    // ============================================================ the wonders

    /**
     * One in the world. Two towns study to the Cathedral: the plans drawn, each lays by its dues (twelve gold and
     * thirty-two glass) out of its stores once they hold them all, and wants the building then and not before. The
     * first raises it: it is that town's (its renown thirty more, its contentment four more and its folk living
     * longer), and the world is told. The other's dues go back into its stores the next morning, it no longer wants the
     * building, and a third town can no longer study it at all; built anyway, it is a fine building and no wonder.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk05_perks_wonder")
    public static void pk05_perks_wonder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village a = town(helper, 1488000, Z, 5, true, false);
        Villages.Village b = town(helper, 1488700, Z, 5, false, false);
        UUID ida = a.id(), idb = b.id();
        helper.assertTrue(!ida.equals(idb), "two towns");
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            Wonders.Wonder cathedral = Wonders.of(Civic.CATHEDRAL);
            BlockPos chestA = Kit.surface(level, 1488000 + 6, Z + 6), chestB = Kit.surface(level, 1488700 + 6, Z + 6);
            stores(level, ida, chestA);
            stores(level, idb, chestB);
            upTo(level, ida, Civic.CATHEDRAL, day);
            upTo(level, idb, Civic.CATHEDRAL, day);
            String shortA = Wonders.shortForTests(level, ida, cathedral);
            helper.assertTrue(CityTree.has(ida, Civic.CATHEDRAL) && !Wonders.paid(ida, cathedral) && shortA.contains("gold")
                && !Villages.projectsWanted(ida).contains("cathedral"), "the plans drawn, the dues not laid by: short of " + shortA);
            stores(level, ida, chestA, new ItemStack(Items.GOLD_INGOT, 12), new ItemStack(Items.GLASS, 32));
            stores(level, idb, chestB, new ItemStack(Items.GOLD_INGOT, 12), new ItemStack(Items.GLASS, 32));
            Wonders.morningForTests(level, a, day + 1);
            Wonders.morningForTests(level, b, day + 1);
            helper.assertTrue(Wonders.paid(ida, cathedral) && Wonders.paid(idb, cathedral) && stock(level, ida, Items.GOLD_INGOT) == 0
                && stock(level, idb, Items.GLASS) == 0, "both lay their dues by, out of their stores");
            helper.assertTrue(Villages.projectsWanted(ida).contains("cathedral") && Villages.whyBuild(ida, "cathedral").contains("wonder of the world"),
                "and want the building: " + Villages.projectsWanted(ida));
            int renown0 = Villages.renown(ida), life0 = CityTree.lifespan(ida, 80);
            List<String> good = new ArrayList<>(), bad = new ArrayList<>();
            int content0 = Perks.contentment(ida, good, bad);
            Villages.noteProject(ida, "cathedral", level.getGameTime());
            Wonders.Claim k = Wonders.claim(cathedral);
            good.clear();
            int content1 = Perks.contentment(ida, good, bad);
            Kit.log("pk05 claimed " + k + "; renown " + renown0 + " -> " + Villages.renown(ida) + "; content " + content0 + " -> " + content1
                + "; " + Wonders.worldLines());
            helper.assertTrue(k != null && k.village().equals(ida) && Wonders.owns(ida, Civic.CATHEDRAL), "the first to raise it holds it");
            helper.assertTrue(Villages.renown(ida) == renown0 + Wonders.RENOWN && content1 == content0 + 4 && CityTree.lifespan(ida, 80) == life0 + 8,
                "renown, contentment, longer lives");
            helper.assertTrue(told(idb, "has raised the Cathedral") && told(ida, "the only one in the world"), "the world told");
            // The other town: its dues back, its plans shelved.
            Wonders.morningForTests(level, b, day + 2);
            helper.assertTrue(!Wonders.paid(idb, cathedral) && stock(level, idb, Items.GOLD_INGOT) == 12 && stock(level, idb, Items.GLASS) == 32,
                "the other's dues back in its stores");
            helper.assertTrue(!Villages.projectsWanted(idb).contains("cathedral") && Wonders.stateWords(idb, Civic.CATHEDRAL).contains("raised by"),
                "it wants it no longer: " + Wonders.stateWords(idb, Civic.CATHEDRAL));
            CityTree.clearForTests(idb);
            upTo(level, idb, Civic.ALMSHOUSE, day);
            helper.assertTrue(!CityTree.open(idb, Civic.CATHEDRAL) && CityTree.lockedWhy(idb, Civic.CATHEDRAL).contains("raised by"),
                "nobody else may study it now: " + CityTree.lockedWhy(idb, Civic.CATHEDRAL));
            Villages.noteProject(idb, "cathedral", level.getGameTime());
            helper.assertTrue(Wonders.claim(cathedral).village().equals(ida) && told(idb, "no wonder of the world"),
                "built anyway, it is no wonder");
            helper.succeed();
        });
    }

    // ============================================================ the leader

    /**
     * The leader's perk in office, by what it cares about most: a Visionary's research 15% more, a Provider's larder
     * a tenth further, a Merchant's takings 5% higher, a Guardian's guard more, a Homemaker's building 10% quicker, a
     * Traditionalist's colds a quarter shorter and +1 content, a Free Spirit's spirits. None in the tests' quiet.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk06_perks_office")
    public static void pk06_perks_office(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1490000, Z, 5, true, true);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity leader = grown(id).get(0), other = grown(id).get(1);
            Villages.electElder(id, leader, day);
            lean(leader, Values.Value.PROGRESS);
            Reigns.forgetForTests();
            CityTree.Rate rate = CityTree.rate(id);
            helper.assertTrue(rate.percent() == 15 && rate.why().contains("Visionary"), "a Visionary: research 15% more: " + rate);
            lean(leader, Values.Value.FOOD);
            Reigns.forgetForTests();
            helper.assertTrue(CityTree.mealPercent(id) == 110, "a Provider: meals a tenth further apart: " + CityTree.mealPercent(id));
            lean(leader, Values.Value.WEALTH);
            Reigns.forgetForTests();
            helper.assertTrue(CityTree.takingsPercent(id) == 105, "a Merchant: the takings 5% higher: " + CityTree.takingsPercent(id));
            lean(leader, Values.Value.SAFETY);
            Reigns.forgetForTests();
            helper.assertTrue(near(Perks.share(id, StationTask.GUARD, 2.0), 3.0), "a Guardian: a guard more");
            lean(leader, Values.Value.HOMES);
            Reigns.forgetForTests();
            helper.assertTrue(Perks.buildPercent(id) == 10, "a Homemaker: building 10% quicker: " + Perks.buildPercent(id));
            lean(leader, Values.Value.TRADITION);
            Reigns.forgetForTests();
            List<String> good = new ArrayList<>();
            int content = Perks.contentment(id, good, new ArrayList<>());
            helper.assertTrue(Perks.coldLength(other, 1000) == 750 && content == 1, "a Traditionalist: colds a quarter shorter, +1: " + good);
            lean(leader, Values.Value.LEISURE);
            Reigns.forgetForTests();
            List<Object[]> why = new ArrayList<>();
            int spirits = Perks.mood(other, day, 50, why);
            boolean said = why.stream().anyMatch(o -> "reign".equals(o[0]));
            helper.assertTrue(spirits == 52 && said, "a Free Spirit: everybody 2 the happier: " + spirits);
            String card = Reigns.cardLine(leader);
            helper.assertTrue(card.contains("Free Spirit") || card.contains("level"), "the leader's card says so: " + card);
            // The tests' quiet: no perk.
            lean(leader, Values.Value.FOOD);
            Perks.liveForTests(false);
            int quiet = CityTree.mealPercent(id);
            Perks.liveForTests(true);
            helper.assertTrue(quiet == 100 && CityTree.mealPercent(id) == 110, "asleep, nothing; awake again, the Provider's");
            helper.succeed();
        });
    }

    /**
     * The leader's skills: a point a level, each line in its order. With none, Diplomat is refused (Orator first);
     * then the Orator (12 more for what it puts to the vote, 6 at its own election) and the Diplomat (its envoys heard
     * 15 warmer, the neighbours warming every other day), and nothing more till the next level. A folk leader chooses
     * its own by its heart: a Guardian the Warden, and crime a third rarer; the card says so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk07_perks_skills")
    public static void pk07_perks_skills(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1492000, Z, 5, true, true);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity first = grown(id).get(0), second = grown(id).get(1);
            lean(first, Values.Value.TRADITION);
            Villages.electElder(id, first, day);
            Reigns.morningForTests(level, v, day);
            Reigns.Reign r = Reigns.reign(id);
            helper.assertTrue(r != null && r.leader().equals(first.getUUID()), "the reign begun: " + r);
            UUID lid = first.getUUID();
            helper.assertTrue(Reigns.book(id, lid).level() == 0 && Reigns.book(id, lid).free() == 0, "no skills to begin with");
            Reigns.xpForTests(id, 80);
            helper.assertTrue(Reigns.book(id, lid).level() == 2 && Reigns.book(id, lid).free() == 2, "two levels: two points");
            String refused = Reigns.take(id, lid, Reigns.Skill.DIPLOMAT, day, "Tester");
            helper.assertTrue(!Reigns.book(id, lid).skills().contains(Reigns.Skill.DIPLOMAT) && refused.contains("Orator"), "Orator first: " + refused);
            Reigns.take(id, lid, Reigns.Skill.ORATOR, day, "Tester");
            Reigns.take(id, lid, Reigns.Skill.DIPLOMAT, day, "Tester");
            String more = Reigns.take(id, lid, Reigns.Skill.STATESMAN, day, "Tester");
            helper.assertTrue(Reigns.skill(id, Reigns.Skill.ORATOR) && Reigns.skill(id, Reigns.Skill.DIPLOMAT)
                && !Reigns.skill(id, Reigns.Skill.STATESMAN), "the Orator and the Diplomat, not yet the Statesman: " + more);
            long even = day - Math.floorMod(day, 2L);
            helper.assertTrue(Perks.envoyWarmth(id, null) == 15 && Perks.warmth(id, even) == 1 && Perks.warmth(id, even + 1) == 0,
                "the Diplomat's envoys and warmth");
            helper.assertTrue(Perks.oratory(id, lid.toString()) == 12 && Perks.oratory(id, second.getUUID().toString()) == 0
                && Perks.hustings(id, null, lid) == 6, "the Orator's word, its own and nobody else's");
            // A folk leader's own choice.
            lean(second, Values.Value.SAFETY);
            Villages.electElder(id, second, day + 1);
            Reigns.morningForTests(level, v, day + 1);
            Reigns.xpForTests(id, 25);
            Reigns.morningForTests(level, v, day + 2);
            Kit.log("pk07 " + Reigns.lines(id, day + 2));
            helper.assertTrue(Reigns.skill(id, Reigns.Skill.WARDEN) && near(Perks.crimeFactor(id), 0.67),
                "a Guardian chooses the Warden: crime a third rarer: " + Perks.crimeFactor(id));
            helper.assertTrue(Reigns.legacies(id).isEmpty(), "a reign of a day leaves nothing");
            String card = FolkTalk.card(second);
            helper.assertTrue(card.contains("Leads|") && card.contains("Warden"), "its card: " + card);
            helper.succeed();
        });
    }

    /**
     * Legacies: a reign of three days or more leaves the one it did most of (built most: Halls, building 3% quicker
     * for good), told, with a plaque wanted before the hall; a reign of a day, nothing; and three of a kind at most.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk08_perks_legacies")
    public static void pk08_perks_legacies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1494000, Z, 5, true, true);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity a = grown(id).get(0), b = grown(id).get(1);
            lean(a, Values.Value.SAFETY);
            lean(b, Values.Value.SAFETY);
            Villages.electElder(id, a, day);
            Reigns.morningForTests(level, v, day);
            Map<Reigns.Deed, Integer> built = new EnumMap<>(Reigns.Deed.class);
            built.put(Reigns.Deed.BUILD, 5);
            built.put(Reigns.Deed.MERRY, 1);
            Reigns.deedsForTests(id, built);
            Reigns.forgetForTests();
            int build0 = Perks.buildPercent(id);
            Villages.electElder(id, b, day + 4);
            Reigns.morningForTests(level, v, day + 4);
            List<Reigns.Legacy> legs = Reigns.legacies(id);
            Kit.log("pk08 " + Reigns.lines(id, day + 4));
            helper.assertTrue(legs.size() == 1 && legs.get(0).deed() == Reigns.Deed.BUILD && legs.get(0).leader().equals(a.displayNameCap()),
                "the first reign leaves its Halls: " + legs);
            Reigns.forgetForTests();
            helper.assertTrue(Perks.buildPercent(id) == build0 + 3, "building 3% quicker for good: " + Perks.buildPercent(id));
            helper.assertTrue(told(id, a.displayNameCap() + "'s Halls"), "told");
            boolean plaque = false;
            for (Plaques.Plaque p : Plaques.plaques(id)) plaque |= p.site() == Plaques.Site.LEGACY && p.lines()[0].contains(a.displayNameCap());
            helper.assertTrue(plaque, "a plaque wanted for it");
            // A reign of a day.
            Villages.electElder(id, a, day + 5);
            Reigns.morningForTests(level, v, day + 5);
            helper.assertTrue(Reigns.legacies(id).size() == 1, "a reign of a day leaves nothing");
            // Three of a kind at most.
            long d = day + 5;
            for (int k = 0; k < 3; k++) {
                Reigns.deedsForTests(id, built);
                d += 4;
                Villages.electElder(id, k % 2 == 0 ? b : a, d);
                Reigns.morningForTests(level, v, d);
            }
            Reigns.forgetForTests();
            int plaques = 0;
            for (Plaques.Plaque p : Plaques.plaques(id)) if (p.site() == Plaques.Site.LEGACY) plaques++;
            helper.assertTrue(Reigns.legacies(id).size() == 3 && Reigns.legacy(id, Reigns.Deed.BUILD) == 3 && plaques == 3,
                "three of a kind at most: " + Reigns.legacyWords(id) + "; " + plaques + " plaques");
            helper.assertTrue(Perks.buildPercent(id) == build0 + 9 && told(id, "as much of that as it can hold"), "nine in all");
            helper.succeed();
        });
    }

    // ============================================================ the folk

    /**
     * Knacks: every trade has one of its own and a master's; a scout's Pathfinder (8% quicker) and Surveyor's Eye (a
     * quarter further); the Master Miner only at level thirty (12% quicker); Fireproof halves the fire; a guard's
     * Featherlight (eight arrows more), Iron Whisperer (golems mended), Piglin-Friend (left be), Veteran (two more
     * armour) and True Shot (a quarter harder); a fisher's Deep Lungs; and a Showman at a feast, +2 for two days.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk09_perks_knacks")
    public static void pk09_perks_knacks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1496000, Z, 6, true, false);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            helper.assertTrue(Knack.values().length >= 50, "fifty knacks or more: " + Knack.values().length);
            for (StationTask t : StationTask.values()) {
                if (t == StationTask.NONE) continue;
                boolean own = false, master = false;
                for (Knack k : Knack.values()) {
                    if (!k.trades.contains(t)) continue;
                    own |= k.family == FolkSkills.Family.TRADE;
                    master |= k.family == FolkSkills.Family.MASTER;
                }
                helper.assertTrue(own && master, "the " + t + " has a knack of its own and a master's");
            }
            List<VillageFolkEntity> folk = grown(id);
            VillageFolkEntity scout = folk.get(0), miner = folk.get(1), smelter = folk.get(2), guard = folk.get(3), fisher = folk.get(4),
                cook = folk.get(5);
            trade(scout, StationTask.SCOUT, 5);
            helper.assertTrue(FolkSkills.open(scout, Knack.PATHFINDER) && FolkSkills.open(scout, Knack.SURVEYORS_EYE), "a scout's knacks open");
            int pace0 = FolkSkills.workPercent(scout);
            FolkSkills.grant(level, scout, Knack.PATHFINDER);
            FolkSkills.grant(level, scout, Knack.SURVEYORS_EYE);
            helper.assertTrue(FolkSkills.workPercent(scout) == pace0 + 8 && Perks.scoutRange(scout, 100) == 125,
                "Pathfinder 8% quicker, Surveyor's Eye a quarter further");
            trade(miner, StationTask.MINE, 29);
            boolean early = FolkSkills.open(miner, Knack.MASTER_MINER);
            trade(miner, StationTask.MINE, 30);
            int mine0 = FolkSkills.workPercent(miner);
            boolean open = FolkSkills.open(miner, Knack.MASTER_MINER);
            FolkSkills.grant(level, miner, Knack.MASTER_MINER);
            helper.assertTrue(!early && open && FolkSkills.workPercent(miner) == mine0 + 12, "the Master Miner at thirty, not before");
            trade(smelter, StationTask.SMELT, 5);
            FolkSkills.grant(level, smelter, Knack.FIREPROOF);
            DamageSource fire = level.damageSources().inFire();
            helper.assertTrue(PerkEvents.fireForTests(smelter, fire, 4F) == 2F && PerkEvents.fireForTests(cook, fire, 4F) == 4F
                && PerkEvents.fireForTests(smelter, level.damageSources().generic(), 4F) == 4F, "Fireproof: half the fire, and only the fire");
            trade(guard, StationTask.GUARD, 30);
            FolkSkills.grant(level, guard, Knack.FEATHERLIGHT);
            helper.assertTrue(Perks.quiver(id, guard) == 8, "Featherlight: eight arrows more");
            trade(miner, StationTask.GOLEMS, 5);                       // the golem keeper's knack, at its trade
            FolkSkills.grant(level, miner, Knack.IRON_WHISPERER);
            IronGolem golem = EntityType.IRON_GOLEM.create(level);
            golem.moveTo(miner.getX() + 2, miner.getY(), miner.getZ(), 0, 0);
            level.addFreshEntity(golem);
            golem.setHealth(50F);
            int mended = FolkSkills.mendGolemsForTests(miner);
            helper.assertTrue(mended >= 1 && golem.getHealth() == 52F, "the Iron Whisperer mends a golem: " + golem.getHealth());
            golem.discard();
            FolkSkills.grant(level, guard, Knack.PIGLIN_FRIEND);
            Piglin piglin = EntityType.PIGLIN.create(level);
            piglin.moveTo(guard.getX() - 2, guard.getY(), guard.getZ(), 0, 0);
            helper.assertTrue(PerkEvents.friendForTests(guard, piglin) && !PerkEvents.friendForTests(cook, piglin), "the piglins leave a Piglin-Friend be");
            FolkSkills.keepUpForTests(guard);
            double armour0 = guard.getAttributeValue(Attributes.ARMOR);
            FolkSkills.grant(level, guard, Knack.VETERAN);
            FolkSkills.keepUpForTests(guard);
            helper.assertTrue(near(guard.getAttributeValue(Attributes.ARMOR), armour0 + 2), "a Veteran: two more armour");
            FolkSkills.grant(level, guard, Knack.TRUE_SHOT);
            Arrow arrow = new Arrow(level, guard, new ItemStack(Items.ARROW), null);
            Arrow other = new Arrow(level, cook, new ItemStack(Items.ARROW), null);
            helper.assertTrue(PerkEvents.arrowForTests(level.damageSources().arrow(arrow, guard), 4F) == 5F
                && PerkEvents.arrowForTests(level.damageSources().arrow(other, cook), 4F) == 4F, "True Shot: a quarter harder");
            trade(fisher, StationTask.FISH, 5);
            FolkSkills.keepUpForTests(fisher);
            double breath0 = fisher.getAttributeValue(Attributes.OXYGEN_BONUS);
            FolkSkills.grant(level, fisher, Knack.DEEP_LUNGS);
            FolkSkills.keepUpForTests(fisher);
            helper.assertTrue(near(fisher.getAttributeValue(Attributes.OXYGEN_BONUS), breath0 + 2), "Deep Lungs");
            trade(cook, StationTask.FIREWORKS, 5);                      // the fireworks maker's knack
            FolkSkills.grant(level, cook, Knack.SHOWMAN);
            List<String> good = new ArrayList<>();
            int c0 = Perks.contentment(id, good, new ArrayList<>());
            Perks.feasted(cook, day);
            good.clear();
            int c1 = Perks.contentment(id, good, new ArrayList<>());
            helper.assertTrue(c1 == c0 + 2 && good.contains("a feast to remember"), "a Showman's feast: +2: " + good);
            helper.succeed();
        });
    }

    /**
     * Quirks, each with its effect: Fleet-footed's step, Frail's hearts and colds, Hardy's short colds, an Iron
     * Stomach's meals, Broad Shoulders' load, a Smooth-talker's price, Hawk-eyed sight, Fearless's blow and its running
     * to help, an Early Bird's morning, Clumsy hands, a Wanderlust scout, the Squeamish and the hunt, Green Fingers'
     * crops, a Bookworm's learning, a Musical busker's coins, a Born Leader's votes; on the card. A child takes a
     * parent's quirk one time in two (and says whose); a town where one quirk runs in a fifth of its folk is known for it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pk10_perks_quirks")
    public static void pk10_perks_quirks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 1498000, Z, 6, true, false);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            List<VillageFolkEntity> folk = grown(id);
            VillageFolkEntity a = folk.get(0), b = folk.get(1), c = folk.get(2), d = folk.get(3), e = folk.get(4), f = folk.get(5);
            for (VillageFolkEntity x : folk) {
                trade(x, StationTask.FARM, 5);
                Quirks.tendForTests(x);
            }
            double step0 = a.getAttributeValue(Attributes.MOVEMENT_SPEED), hp0 = b.getMaxHealth(), hit0 = e.getAttributeValue(Attributes.ATTACK_DAMAGE);
            int upkeep0 = d.traitUpkeepPercent(), haul0 = FolkSkills.haulBonus(d);
            double sight0 = FolkSkills.sightBonus(e), tips0 = Perks.tipsForTests(f);
            Quirks.setForTests(a, Quirk.FLEET_FOOTED);
            Quirks.setForTests(b, Quirk.FRAIL);
            Quirks.setForTests(c, Quirk.HARDY);
            Quirks.setForTests(d, Quirk.IRON_STOMACH, Quirk.BROAD_SHOULDERS);
            Quirks.setForTests(e, Quirk.SMOOTH_TALKER, Quirk.HAWK_EYED);
            for (VillageFolkEntity x : folk) Quirks.tendForTests(x);
            helper.assertTrue(near(a.getAttributeValue(Attributes.MOVEMENT_SPEED), step0 * 1.08), "Fleet-footed: 8% quicker");
            helper.assertTrue(near(b.getMaxHealth(), hp0 - 4) && Perks.coldOdds(b, 8) == 4, "Frail: two hearts less, colds twice as easy");
            helper.assertTrue(Perks.coldLength(c, 1000) == 500 && Perks.coldOdds(c, 8) == 8, "Hardy: a cold half as long");
            helper.assertTrue(d.traitUpkeepPercent() >= upkeep0 * 6 / 5 - 1 && d.traitUpkeepPercent() > upkeep0
                && FolkSkills.haulBonus(d) == haul0 + 16, "an Iron Stomach a fifth longer between meals; Broad Shoulders sixteen more");
            helper.assertTrue(near(Perks.priceEach(id, new ItemStack(Items.BREAD), e, 10.0), 9.5) && near(FolkSkills.sightBonus(e), sight0 + 4),
                "a Smooth-talker pays 5% less; Hawk-eyed sees four further");
            e.individual().fears.add(Fears.Fear.MONSTERS);
            Quirks.setForTests(e, Quirk.FEARLESS);
            Quirks.tendForTests(e);
            helper.assertTrue(!e.individual().fears.contains(Fears.Fear.MONSTERS), "the Fearless fear no monster");
            helper.assertTrue(near(e.getAttributeValue(Attributes.ATTACK_DAMAGE), hit0 + 1) && Quirks.answersCries(e) && !Quirks.answersCries(a),
                "Fearless: a point more blow, and it runs to help");
            // The hour, the hands and the trade.
            long now = level.getDayTime();
            Quirks.setForTests(a, Quirk.EARLY_BIRD);
            level.setDayTime(day * 24000L + 3000);
            int morning = Perks.workPercent(a);
            level.setDayTime(day * 24000L + 9000);
            int afternoon = Perks.workPercent(a);
            level.setDayTime(now);
            helper.assertTrue(morning == 5 && afternoon == 0, "an Early Bird: 5% before noon, nothing after: " + morning + "/" + afternoon);
            Quirks.setForTests(b, Quirk.CLUMSY);
            helper.assertTrue(Perks.workPercent(b) == -3, "Clumsy: 3% slower");
            Quirks.setForTests(c, Quirk.WANDERLUST);
            trade(c, StationTask.SCOUT, 5);
            helper.assertTrue(Perks.workPercent(c) == 10, "a Wanderlust scout: 10% quicker");
            Quirks.setForTests(d, Quirk.SQUEAMISH);
            helper.assertTrue(Quirks.refuses(d, StationTask.HUNT) && Quirks.instead(d, StationTask.HUNT) == StationTask.FARM
                && Quirks.pull(d, StationTask.HUNT) < -50, "the Squeamish never hunt: the fields instead");
            // Green Fingers.
            Quirks.setForTests(a, Quirk.GREEN_FINGERS);
            BlockPos crop = a.blockPosition().offset(2, 0, 0);
            level.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 2);
            level.setBlock(crop, Blocks.WHEAT.defaultBlockState(), 2);
            boolean grew = Quirks.greenFingersForTests(a);
            BlockState s = level.getBlockState(crop);
            helper.assertTrue(grew && s.getBlock() instanceof CropBlock cb && cb.getAge(s) == 1 && Quirks.pull(a, StationTask.FARM) == 4,
                "Green Fingers: the crop comes on, and the fields pull it: " + s);
            List<String> paper = new ArrayList<>();
            int onPaper = Quirks.onPaper(a, StationTask.FARM, "opening", paper), offPaper = Quirks.onPaper(a, StationTask.MINE, "opening", new ArrayList<>());
            helper.assertTrue(onPaper == 20 && offPaper == 0 && paper.contains("green-fingered"),
                "and at an interview for the fields, twenty points on its paper: " + onPaper + " " + paper);
            // A Bookworm learns a tenth faster.
            trade(b, StationTask.FARM, 5);
            trade(d, StationTask.FARM, 5);
            Quirks.setForTests(b, Quirk.BOOKWORM);
            Quirks.setForTests(d);
            int xb = b.xpInTrade(StationTask.FARM), xd = d.xpInTrade(StationTask.FARM);
            b.awardXp(100);
            d.awardXp(100);
            int gb = b.xpInTrade(StationTask.FARM) - xb, gd = d.xpInTrade(StationTask.FARM) - xd;
            helper.assertTrue(gb == gd + 10, "a Bookworm: a tenth more: " + gb + " / " + gd);
            Quirks.setForTests(f, Quirk.MUSICAL, Quirk.BORN_LEADER);
            helper.assertTrue(near(Perks.tipsForTests(f), tips0 + 0.15) && Perks.hustings(id, f, f.getUUID()) == 7,
                "a Musical busker's coins; a Born Leader's votes");
            Quirks.setForTests(a, Quirk.FLEET_FOOTED);
            String card = FolkTalk.card(a);
            helper.assertTrue(card.contains("Quirks|Fleet-footed"), "on its card: " + card);
            // Handed on.
            Quirks.setForTests(b, Quirk.LUCKY);
            Quirks.setForTests(c, Quirk.HARDY);
            Quirk took = Quirks.inheritForTests(d, b, c, true);
            helper.assertTrue((took == Quirk.LUCKY || took == Quirk.HARDY) && Quirks.has(d, took)
                && Quirks.cardLine(d).contains("from " + (took == Quirk.LUCKY ? b : c).displayNameCap()), "a parent's quirk, and whose: " + Quirks.cardLine(d));
            Quirk none = Quirks.inheritForTests(e, b, c, false);
            helper.assertTrue(none == null && !Quirks.of(e).isEmpty() && !Quirks.cardLine(e).contains("from "), "or its own: " + Quirks.cardLine(e));
            // Known for it.
            for (VillageFolkEntity x : folk) Quirks.setForTests(x, Quirk.LUCKY);
            String known = Quirks.knownFor(id);
            helper.assertTrue(known.contains("its luck"), "the town is known for its luck: " + known);
            helper.succeed();
        });
    }
}
