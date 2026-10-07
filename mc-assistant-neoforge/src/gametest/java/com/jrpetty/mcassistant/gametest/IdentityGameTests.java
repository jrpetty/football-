package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CityTree;
import com.jrpetty.mcassistant.entity.Disasters;
import com.jrpetty.mcassistant.entity.Elections;
import com.jrpetty.mcassistant.entity.Ethos;
import com.jrpetty.mcassistant.entity.Fame;
import com.jrpetty.mcassistant.entity.Floods;
import com.jrpetty.mcassistant.entity.Gatherings;
import com.jrpetty.mcassistant.entity.Government;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Identity;
import com.jrpetty.mcassistant.entity.LawBook;
import com.jrpetty.mcassistant.entity.Laws;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Newcomers;
import com.jrpetty.mcassistant.entity.Referendums;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Tavern;
import com.jrpetty.mcassistant.entity.TownTraits;
import com.jrpetty.mcassistant.entity.Tourists;
import com.jrpetty.mcassistant.entity.Treatment;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [identity] What makes a town itself (Identity, Ethos, Government, LawBook, TownTraits, Fame, Treatment).
 *
 * <ul>
 * <li><b>id01</b>: a port founded by Merchants and Free Spirits and a hill-town founded by Guardians and Traditionalists
 *     come out with different axes, governments and laws, different names from their histories, and measurably
 *     different in what they do: the watch, the gates, trust, newcomers, the buildings they want first.</li>
 * <li><b>id02</b>: a leader who cares for safe streets, in office, makes the town martial day by day; a raid and an
 *     election push it too, and the chronicle says when it has grown martial.</li>
 * <li><b>id03</b>: three real floods make the town Flood-hardy (with its story); its levee then goes up twice as fast,
 *     its folk do not panic, and the name fades when the floods are long past.</li>
 * <li><b>id04</b>: a town that makes the most cooked cod becomes famous for smoked fish: the traders pay it a quarter more
 *     for the same cod, other towns value its cod more, its fair takes coin, and its renown rises.</li>
 * <li><b>id05</b>: deeds (a book on the shelves, a festival, a hero, a war won) raise its renown, and its renown raises a
 *     hamlet to a village, told in the chronicle; tourists are drawn the more.</li>
 * <li><b>id06</b>: the law-book: a curfew sends folk to bed after dusk and shuts the tavern; the gates shut sooner; a
 *     loose day of rest is kept every other week; the apprentice age and the tithe follow the law.</li>
 * <li><b>id07</b>: a lord dies and its eldest child takes the seat, with no election; a player cannot stand; and when the
 *     town has had enough of the heir, a vote of no confidence puts the house out and the town elects.</li>
 * <li><b>id08</b>: a curfew in a commune goes to the whole town's vote; an elected leader decrees it; the elders vote on
 *     it among themselves; a commune pays everybody the same, and the chaplain's town keeps a feast mid-week.</li>
 * <li><b>id09</b>: the same party of newcomers is voted in by an open town and turned away by a closed one.</li>
 * <li><b>id10</b>: a player is trusted faster by an open, hospitable town than a closed, raid-scarred one; walking the
 *     closed town with a sword in hand, it is asked to put it away and then fined; and the folk say what their town is.</li>
 * </ul>
 * Each on its own ground in x 1460000-1479999, z 66000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class IdentityGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ helpers

    /** A town of n folk raised at x (and z), every folk's nature rolled. */
    private static Villages.Village town(GameTestHelper helper, int x, int z, int n) {
        ServerLevel level = helper.getLevel();
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
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

    /** Every folk of the town caring this much for each of these, and of this nature. */
    private static void founders(UUID v, Social.Trait a, Social.Trait b, Object... cares) {
        for (VillageFolkEntity f : folk(v)) {
            f.life().setTraitsForTests(a, b);
            for (int i = 0; i + 1 < cares.length; i += 2) Values.setForTests(f, (Values.Value) cares[i], (Integer) cares[i + 1]);
        }
    }

    private static void start(ServerLevel level, long time) {
        Kit.reset(level);
        level.setDayTime(time);
        level.updateSkyBrightness();
        Identity.liveForTests();
    }

    private static boolean told(UUID v, String words) {
        for (Chronicle.Entry e : Chronicle.of(v)) if (e.text().contains(words)) return true;
        return false;
    }

    /** One chest of the town's stores, filled with these (any other store chest emptied). */
    private static Container stores(ServerLevel level, UUID village, BlockPos at, ItemStack... goods) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length && i < box.getContainerSize(); i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static void allAxes(UUID v, int value) {
        for (Ethos.Axis a : Ethos.Axis.values()) Ethos.setForTests(v, a, value);
    }

    /** The plain town's law-book: a tithe of one in ten, the usual apprentice age and day of rest, nothing else. */
    private static void plainLaws(UUID v) {
        for (LawBook.Law l : LawBook.Law.values()) {
            LawBook.setForTests(v, l, l == LawBook.Law.TITHE || l == LawBook.Law.APPRENTICE || l == LawBook.Law.SABBATH ? 1 : 0);
        }
    }

    // ============================================================ id01: two towns, measurably different

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "id01_two_towns")
    public static void id01_two_towns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = 1460000;
        Villages.Village port = town(helper, x, Z, 9), hold = town(helper, x + 300, Z, 12);
        helper.assertTrue(!port.id().equals(hold.id()), "two towns");
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            UUID a = port.id(), b = hold.id();
            Homeland.setForTests(a, Homeland.Land.COAST);
            Homeland.setForTests(b, Homeland.Land.MOUNTAIN);
            // The port's founders: Merchants and Free Spirits, sociable and cheerful.
            founders(a, Social.Trait.SOCIABLE, Social.Trait.CHEERFUL, Values.Value.WEALTH, 85, Values.Value.LEISURE, 95,
                Values.Value.SAFETY, 5, Values.Value.TRADITION, 5);
            // The hold's: Guardians and Traditionalists, grumpy and shy.
            founders(b, Social.Trait.GRUMPY, Social.Trait.SHY, Values.Value.SAFETY, 95, Values.Value.TRADITION, 90,
                Values.Value.LEISURE, 5, Values.Value.PROGRESS, 5);
            Identity.Rec ra = Identity.seedForTests(level, port), rb = Identity.seedForTests(level, hold);
            Kit.log("id01 the port: " + Identity.summary(a) + " | " + Ethos.words(a) + " | laws: " + LawBook.notice(a, 9));
            Kit.log("id01 the hold: " + Identity.summary(b) + " | " + Ethos.words(b) + " | laws: " + LawBook.notice(b, 9));
            int dTrade = Ethos.lean(a, Ethos.Axis.TRADE) - Ethos.lean(b, Ethos.Axis.TRADE);
            int dDoors = Ethos.lean(a, Ethos.Axis.DOORS) - Ethos.lean(b, Ethos.Axis.DOORS);
            int dWar = Ethos.lean(b, Ethos.Axis.WAR) - Ethos.lean(a, Ethos.Axis.WAR);
            int dFaith = Ethos.lean(b, Ethos.Axis.FAITH) - Ethos.lean(a, Ethos.Axis.FAITH);
            helper.assertTrue(dDoors >= 60, "the port open, the hold closed: " + dDoors);
            helper.assertTrue(dWar >= 60, "the hold martial, the port peaceable: " + dWar);
            helper.assertTrue(dFaith >= 60, "the hold devout, the port worldly: " + dFaith);
            helper.assertTrue(dTrade >= 20, "the port the more mercantile: " + dTrade);
            helper.assertTrue(ra.gov != rb.gov, "different governments: " + ra.gov + " and " + rb.gov);
            int differ = 0;
            for (LawBook.Law l : LawBook.Law.values()) if (LawBook.value(a, l) != LawBook.value(b, l)) differ++;
            helper.assertTrue(differ >= 4, "different laws: " + differ + " of " + LawBook.Law.values().length + " differ");
            helper.assertTrue(LawBook.bordersClosed(b) && !LawBook.bordersClosed(a), "the hold's borders closed, the port's open");
            helper.assertTrue(LawBook.curfew(b) && !LawBook.curfew(a), "the hold keeps a curfew, the port none");
            // Their histories: the port's trade deals, the hold's raids.
            for (int i = 0; i < 3; i++) Identity.event(a, Identity.Ev.DEAL, day - 5 + i);
            for (int i = 0; i < 2; i++) Identity.event(b, Identity.Ev.RAID_LOST, day - 4 + i);
            TownTraits.reviewForTests(level, port, day);
            TownTraits.reviewForTests(level, hold, day);
            helper.assertTrue(TownTraits.has(a, TownTraits.Trait.MERCHANT_PRINCES), "the port earns Merchant Princes");
            helper.assertTrue(TownTraits.has(b, TownTraits.Trait.RAID_SCARRED), "the hold is Raid-scarred");
            String sa = Identity.summary(a), sb = Identity.summary(b);
            Kit.log("id01 after their histories: " + sa + " || " + sb);
            helper.assertTrue(!sa.equals(sb) && sa.contains("Merchant Princes") && sb.contains("Raid-scarred"), "the board's lines say so");
            // What they do, measured.
            double guardA = Ethos.shareLean(a, StationTask.GUARD), guardB = Ethos.shareLean(b, StationTask.GUARD);
            int trustA = Treatment.trust(a, 4), trustB = Treatment.trust(b, 4);
            long shutA = Ethos.gatesShut(a), shutB = Ethos.gatesShut(b);
            double inA = Ethos.newcomerLean(a), inB = Ethos.newcomerLean(b);
            Kit.log("id01 the watch's share x" + String.format("%.2f", guardA) + " / x" + String.format("%.2f", guardB) + "; trust 4 -> " + trustA
                + " / " + trustB + "; gates shut at " + shutA + " / " + shutB + "; newcomers " + String.format("%.1f / %.1f", inA, inB)
                + "; keeps x" + String.format("%.2f / %.2f", Ethos.keepFactor(a), Ethos.keepFactor(b)) + "; tavern nights " + Ethos.tavernNights(a)
                + " / " + Ethos.tavernNights(b) + "; customs " + Ethos.customsKept(a, 3) + " / " + Ethos.customsKept(b, 3));
            helper.assertTrue(guardB > guardA + 0.25, "more of the hold on the watch: x" + guardA + " against x" + guardB);
            helper.assertTrue(trustA > trustB + 1, "the port takes to a player faster: " + trustA + " against " + trustB);
            helper.assertTrue(shutA - shutB >= 1000, "the hold shuts its gates an hour sooner at least: " + shutA + " against " + shutB);
            helper.assertTrue(inA > 20 && inB < -40, "newcomers welcome in the port, not in the hold: " + inA + " / " + inB);
            helper.assertTrue(Ethos.tavernNights(a) > Ethos.tavernNights(b), "the port at the tavern more evenings");
            // The buildings they want first, in the Stone Age: the worldly port its tavern at nine folk, the devout hold its chapel.
            Villages.ageForTests(a, Villages.Age.STONE);
            Villages.ageForTests(b, Villages.Age.STONE);
            List<String> wa = Villages.projectsWanted(a), wb = Villages.projectsWanted(b);
            Kit.log("id01 wanted: the port " + wa + "; the hold " + wb);
            helper.assertTrue(wa.contains("tavern") && !wa.contains("chapel"), "the port wants its tavern at nine folk: " + wa);
            helper.assertTrue(wb.contains("chapel"), "the devout hold wants its chapel in the Stone Age: " + wb);
            helper.succeed();
        });
    }

    // ============================================================ id02: a leader moves the town

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "id02_leader_moves")
    public static void id02_leader_moves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = 1462000;
        Villages.Village v = town(helper, x, Z, 6);
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            UUID id = v.id();
            founders(id, Social.Trait.EASYGOING, Social.Trait.CHEERFUL, Values.Value.LEISURE, 40, Values.Value.SAFETY, 25);
            Identity.seedForTests(level, v);
            allAxes(id, 0);
            int before = Ethos.lean(id, Ethos.Axis.WAR);
            // A Guardian in office, grumpy and hard: it cares for safe streets above everything.
            VillageFolkEntity leader = folk(id).get(0);
            leader.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
            Values.setForTests(leader, Values.Value.SAFETY, 100);
            Values.setForTests(leader, Values.Value.LEISURE, 0);
            Villages.electElder(id, leader, day);
            for (int i = 1; i <= 14; i++) Identity.morningForTests(level, v, day + i);
            int after = Ethos.lean(id, Ethos.Axis.WAR);
            Kit.log("id02 martial " + before + " -> " + after + " after fourteen mornings under " + leader.displayNameCap() + "; " + Ethos.words(id));
            helper.assertTrue(after - before >= 12, "a Guardian in office makes the town martial: " + before + " -> " + after);
            // What happens to it moves it too: a raid held off, and an election for good wages and trade.
            int doors = Ethos.lean(id, Ethos.Axis.DOORS), war = Ethos.lean(id, Ethos.Axis.WAR), trade = Ethos.lean(id, Ethos.Axis.TRADE);
            Villages.tell(id, day + 14, "5 raiders came at the north gate in the night; the watch held them off and nobody was lost");
            Villages.tell(id, day + 14, "Fen was elected thane for good wages and trade (Fen 4, Bryn 2 (6 of 6 voted))");
            Kit.log("id02 after a raid and an election: war " + war + " -> " + Ethos.lean(id, Ethos.Axis.WAR) + ", doors " + doors + " -> "
                + Ethos.lean(id, Ethos.Axis.DOORS) + ", trade " + trade + " -> " + Ethos.lean(id, Ethos.Axis.TRADE));
            helper.assertTrue(Ethos.lean(id, Ethos.Axis.WAR) > war && Ethos.lean(id, Ethos.Axis.DOORS) < doors, "the raid makes it martial and wary");
            helper.assertTrue(Ethos.lean(id, Ethos.Axis.TRADE) > trade, "the election for trade makes it mercantile");
            helper.assertTrue(Identity.recForTests(id).count(Identity.Ev.RAID_HELD) == 1, "the raid is on its books");
            // Crossing into martial is told.
            Ethos.setForTests(id, Ethos.Axis.WAR, 20);
            Identity.morningForTests(level, v, day + 15);
            Ethos.setForTests(id, Ethos.Axis.WAR, 60);
            Identity.morningForTests(level, v, day + 16);
            Kit.log("id02 the chronicle: " + Identity.gazette(id, day + 17));
            helper.assertTrue(told(id, "has grown martial"), "the chronicle says the town has grown martial");
            helper.succeed();
        });
    }

    // ============================================================ id03: Flood-hardy

    /** The river and its low ground (as DisastersGameTests lays them): the town's river, a basin beside it, a house in it. */
    private static void riverside(ServerLevel level, UUID id, int x, int gy) {
        int high = gy + 1, low = gy;
        ground(level, x - 30, Z + 5, x + 30, Z + 44, high, Blocks.STONE.defaultBlockState());
        ground(level, x - 18, Z + 10, x + 18, Z + 26, low, Blocks.STONE.defaultBlockState());
        for (int xx = x - 26; xx <= x + 26; xx++) {
            for (int z = Z + 27; z <= Z + 31; z++) {
                for (int dy = 1; dy <= 14; dy++) level.setBlock(new BlockPos(xx, low + dy, z), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(xx, low - 2, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(xx, low - 1, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(xx, low, z), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        for (int xx = x - 18; xx <= x + 18; xx++) {
            for (int z = Z + 24; z <= Z + 26; z++) level.setBlock(new BlockPos(xx, low, z), Blocks.DIRT_PATH.defaultBlockState(), 2);
        }
        BlockPos anchor = new BlockPos(x + 8, low + 1, Z + 17);
        BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "house", anchor, Direction.NORTH);
        Floods.riverForTests(id, new BlockPos(x - 30, low, Z + 5), new BlockPos(x + 30, low, Z + 44), low);
    }

    private static void ground(ServerLevel level, int x0, int z0, int x1, int z1, int y, BlockState top) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = 1; dy <= 14; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                for (int dy = -4; dy < 0; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y, z), top, 2);
            }
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "id03_flood_hardy")
    public static void id03_flood_hardy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = 1464000;
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, Z), 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a town");
        Disasters.onForTests(true);
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        int gy = Kit.surface(level, x, Z).getY() - 1;
        riverside(level, id, x, gy);
        Homes.tickForTests(level, v);
        stores(level, id, Kit.surface(level, x - 3, Z - 3), new ItemStack(Items.DIRT, 64), new ItemStack(Items.DIRT, 64),
            new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.BREAD, 8));
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            Identity.seedForTests(level, v);
            // The first flood, and down again: the town plans its levee, and raises a step of it as any town would.
            int f1 = Floods.floodForTests(level, id, 1);
            Floods.drainForTests(level, id);
            int plain = Floods.leveeStepForTests(level, id);
            int f2 = Floods.floodForTests(level, id, 1);
            Floods.drainForTests(level, id);
            TownTraits.reviewForTests(level, v, day);
            boolean twoNotYet = !TownTraits.has(id, TownTraits.Trait.FLOOD_HARDY);
            int f3 = Floods.floodForTests(level, id, 1);
            Floods.drainForTests(level, id);
            TownTraits.reviewForTests(level, v, day);
            Identity.Rec r = Identity.recForTests(id);
            Kit.log("id03 floods of " + f1 + ", " + f2 + ", " + f3 + " cells; counted " + r.count(Identity.Ev.FLOOD) + "; traits: "
                + Identity.summary(id) + "; levee plan " + Floods.leveePlanForTests(id).size() + "; a plain step raised " + plain);
            helper.assertTrue(f1 > 0 && f2 > 0 && f3 > 0, "three real floods: " + f1 + ", " + f2 + ", " + f3);
            helper.assertTrue(twoNotYet, "two floods do not make a town Flood-hardy");
            helper.assertTrue(TownTraits.has(id, TownTraits.Trait.FLOOD_HARDY), "three do: " + Identity.summary(id));
            helper.assertTrue(told(id, "Flood-hardy, for the floods of"), "with its story in the chronicle");
            helper.assertTrue(plain == 3, "a plain town raises its levee three blocks a step: " + plain);
            int hardy = Floods.leveeStepForTests(level, id);
            Kit.log("id03 a Flood-hardy step raised " + hardy + "; what its folk say: " + TownTraits.floodWords(id, level.getRandom()));
            helper.assertTrue(hardy == 6, "a Flood-hardy town raises it twice as fast: " + hardy);
            helper.assertTrue(TownTraits.floodWords(id, level.getRandom()) != null, "and its folk keep calm");
            // Long past: the name fades.
            TownTraits.reviewForTests(level, v, day + 200);
            helper.assertTrue(!TownTraits.has(id, TownTraits.Trait.FLOOD_HARDY), "the name fades when the floods are long past");
            helper.assertTrue(told(id, "no longer called Flood-hardy"), "and the chronicle says so");
            helper.succeed();
        });
    }

    // ============================================================ id04: fame, and a better price

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "id04_fame")
    public static void id04_fame(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = 1466000;
        Villages.Village a = town(helper, x, Z, 4), b = town(helper, x + 300, Z, 4);
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            Identity.seedForTests(level, a);
            Identity.seedForTests(level, b);
            allAxes(a.id(), 0);
            allAxes(b.id(), 0);
            plainLaws(a.id());
            plainLaws(b.id());
            int renownBefore = Villages.renown(a.id());
            // The books: the one town has smoked sixty cod a day these ten days, the other twenty.
            Fame.madeForTests(a.id(), "cooked_cod", 60, 10, day);
            Fame.madeForTests(b.id(), "cooked_cod", 20, 10, day);
            Fame.reviewForTests(level, a, day);
            Fame.reviewForTests(level, b, day);
            Kit.log("id04 cod: " + String.format("%.1f / %.1f", Fame.scoreForTests(a.id(), Fame.Product.FISH), Fame.scoreForTests(b.id(), Fame.Product.FISH))
                + "; " + Identity.summary(a.id()) + " | " + Identity.summary(b.id()));
            helper.assertTrue(Fame.words(a.id()).contains("smoked fish"), "the first town is famous for its smoked fish: " + Fame.words(a.id()));
            helper.assertTrue(Fame.words(b.id()).isEmpty(), "the second is not: " + Fame.words(b.id()));
            helper.assertTrue(Identity.summary(a.id()).contains("famous for its smoked fish"), "the board's line says it");
            helper.assertTrue(Villages.renown(a.id()) >= renownBefore + 8, "fame is renown: " + renownBefore + " -> " + Villages.renown(a.id()));
            // Other towns value its cod more at the bargaining.
            double them = Fame.importWorth(b.id(), a.id(), Items.COOKED_COD), us = Fame.importWorth(a.id(), b.id(), Items.COOKED_COD);
            helper.assertTrue(them > us * 1.1, "its cod worth more to a buyer than the other's: " + them + " against " + us);
            // The same cod, the same stores: the traders pay the famous town more for it.
            stores(level, a.id(), Kit.surface(level, x - 4, Z - 4), cod(9));
            stores(level, b.id(), Kit.surface(level, x + 296, Z - 4), cod(9));
            int paidA = Market.sellSurplus(level, a, day), paidB = Market.sellSurplus(level, b, day);
            Kit.log("id04 market day: the famous town took " + paidA + " coin, the other " + paidB);
            helper.assertTrue(paidB > 0 && paidA >= paidB * 1.15, "a better price for the famous town's fish: " + paidA + " against " + paidB);
            // And its fair: buyers from round about, coin into the treasury, the fish out of the stores.
            int before = Market.stock(level, a.id(), s -> s.is(Items.COOKED_COD)), coins = Ledger.coins(a.id());
            int fair = Fame.fairForTests(level, a);
            Villages.forgetStock();
            int after = Market.stock(level, a.id(), s -> s.is(Items.COOKED_COD));
            Kit.log("id04 the smoked fish fair: " + fair + " coin, the cod " + before + " -> " + after + ", the treasury " + coins + " -> " + Ledger.coins(a.id()));
            helper.assertTrue(fair > 0 && after < before && Ledger.coins(a.id()) == coins + fair, "the fair sells the stores' fish for coin");
            helper.succeed();
        });
    }

    private static ItemStack[] cod(int stacks) {
        ItemStack[] out = new ItemStack[stacks];
        for (int i = 0; i < stacks; i++) out[i] = new ItemStack(Items.COOKED_COD, 64);
        return out;
    }

    // ============================================================ id05: renown and the title

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "id05_renown")
    public static void id05_renown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = 1468000;
        Villages.Village v = town(helper, x, Z, 9);
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            UUID id = v.id();
            Identity.seedForTests(level, v);
            Ledger.note(id, "rank", "HAMLET");
            int r0 = Villages.renown(id), draw0 = Tourists.drawForTests(id);
            Villages.Rank before = Villages.rank(id);
            // Deeds, as the chronicle tells them: a book on the library's shelves, a festival, a hero named.
            Villages.tell(id, day, "Bryn's new poem, \"The Mill Pond\", is on the library's shelves");
            Villages.tell(id, day, "the harvest festival was kept with a feast on the square: the year's harvest was good");
            Villages.tell(id, day, "Steve was named the hero of " + Villages.name(id));
            int r1 = Villages.renown(id);
            Villages.Rank after = Villages.rank(id);
            Villages.checkRank(level, v, day);
            Kit.log("id05 renown " + r0 + " -> " + r1 + "; " + before + " -> " + after + "; tourists' draw " + draw0 + " -> " + Tourists.drawForTests(id));
            helper.assertTrue(before == Villages.Rank.HAMLET, "nine folk in the Wood Age: a hamlet");
            helper.assertTrue(r1 >= r0 + 9, "a book, a festival and a hero are renown: " + r0 + " -> " + r1);
            helper.assertTrue(after == Villages.Rank.VILLAGE, "renown makes the hamlet a village: " + after);
            helper.assertTrue(told(id, "has grown into a village"), "told in the chronicle");
            helper.assertTrue(Tourists.drawForTests(id) > draw0, "and draws visitors the more");
            // A war won: renown again.
            Identity.peace(id, id, UUID.randomUUID(), day);
            helper.assertTrue(Villages.renown(id) >= r1 + 13, "a war won and a peace made: " + Villages.renown(id));
            helper.succeed();
        });
    }

    // ============================================================ id06: the law-book's effects

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "id06_laws")
    public static void id06_laws(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 24000L * 30 + 2000);
        int x = 1470000;
        Villages.Village v = town(helper, x, Z, 6);
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            UUID id = v.id();
            Identity.seedForTests(level, v);
            allAxes(id, 0);
            plainLaws(id);
            // A tavern, on the town's books.
            BlockPos tav = Kit.surface(level, x + 10, Z + 10);
            Ledger.built(id, "tavern", tav, Direction.NORTH);
            List<VillageFolkEntity> all = folk(id);
            // An evening this folk would spend at the tavern (two evenings in five, by its own reckoning: Tavern.goingTonight).
            long evening = day;
            int h = all.get(0).getUUID().hashCode();
            while (Math.floorMod(evening * 7 + h, 5) >= 2) evening++;
            level.setDayTime(evening * 24000L + 13200L);
            long[] usual = new long[all.size()];
            List<VillageFolkEntity> going = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                usual[i] = all.get(i).bedtimeTick();
                if (Tavern.evening(all.get(i), 13200L)) going.add(all.get(i));
            }
            long shutBefore = Ethos.gatesShut(id);
            double crimeBefore = Ethos.crimeFactor(id);
            LawBook.setForTests(id, LawBook.Law.CURFEW, 1);
            int sent = 0, still = 0;
            for (int i = 0; i < all.size(); i++) {
                long now = all.get(i).bedtimeTick();
                if (now <= 13050L && usual[i] > 13050L) sent++;
            }
            for (VillageFolkEntity f : going) if (Tavern.evening(f, 13200L)) still++;
            Kit.log("id06 bedtimes " + java.util.Arrays.toString(usual) + " -> " + all.get(0).bedtimeTick() + "; at the tavern " + going.size()
                + " -> " + still + "; the gates " + shutBefore + " -> " + Ethos.gatesShut(id) + "; crime x" + crimeBefore + " -> x" + Ethos.crimeFactor(id));
            helper.assertTrue(sent == all.size(), "a curfew sends every folk to bed soon after dusk: " + sent + " of " + all.size());
            helper.assertTrue(!going.isEmpty() && still == 0, "and empties the tavern: " + going.size() + " -> " + still);
            helper.assertTrue(Ethos.gatesShut(id) < shutBefore, "the gates shut sooner");
            helper.assertTrue(Ethos.crimeFactor(id) < crimeBefore, "and crime is kept down");
            // The day of rest: kept every week as a rule, every other week where it is kept loosely.
            Villages.ageForTests(id, Villages.Age.STONE);
            int kept = 0, loose = 0;
            for (long d = day + 7; d < day + 35; d++) if (RestDay.today(id, d)) kept++;
            LawBook.setForTests(id, LawBook.Law.SABBATH, 0);
            for (long d = day + 7; d < day + 35; d++) if (RestDay.today(id, d)) loose++;
            Kit.log("id06 days of rest in four weeks: " + kept + " kept, " + loose + " kept loosely");
            helper.assertTrue(kept == 4 && loose == 2, "kept loosely, half the days of rest: " + kept + " -> " + loose);
            // The apprentice age and the tithe follow the law.
            int[] from = new int[3];
            for (int i = 0; i < 3; i++) {
                LawBook.setForTests(id, LawBook.Law.APPRENTICE, i);
                from[i] = LawBook.apprenticeFrom(id, 1);
            }
            int[] tithe = new int[4];
            for (int i = 0; i < 4; i++) {
                LawBook.setForTests(id, LawBook.Law.TITHE, i);
                tithe[i] = LawBook.tithe(id, 100, 10);
            }
            Kit.log("id06 apprenticed from day " + java.util.Arrays.toString(from) + "; the tithe on 100 over: " + java.util.Arrays.toString(tithe));
            helper.assertTrue(from[0] == 0 && from[1] == 1 && from[2] == 2, "children at a grown-up's side sooner or later by law");
            helper.assertTrue(tithe[0] == 5 && tithe[1] == 10 && tithe[2] == 15 && tithe[3] == 20, "the tithe by law");
            helper.succeed();
        });
    }

    // ============================================================ id07: the lord's heir, and the town's patience

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "id07_lord")
    public static void id07_lord(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        Kit.noLeftoverPlayers(level);
        int x = 1472000;
        Villages.Village v = town(helper, x, Z, 6);
        final VillageFolkEntity[] heir = { null };
        final UUID[] lord = { null };
        helper.runAtTickTime(10, () -> {
            UUID id = v.id();
            Identity.seedForTests(level, v);
            Government.setForTests(level, v, Government.Form.LORD);
            lord[0] = Villages.elder(id);
            helper.assertTrue(lord[0] != null, "a lord in the seat: " + Identity.summary(id));
            List<VillageFolkEntity> others = new ArrayList<>();
            for (VillageFolkEntity f : folk(id)) if (!f.getUUID().equals(lord[0])) others.add(f);
            // Two grown children of the lord's: the elder of them is the heir.
            others.get(0).parentIds().add(lord[0]);
            others.get(0).setAgeForTests(34);
            others.get(1).parentIds().add(lord[0]);
            others.get(1).setAgeForTests(22);
            heir[0] = others.get(0);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            String barred = Government.standBarred(level, id, p);
            Kit.log("id07 a player asks to stand: " + barred);
            helper.assertTrue(barred != null && barred.contains("marry"), "no player stands in a lordship but by marrying in or a vote");
            for (AssistantEntity a : Villages.folkOf(id)) if (a.getUUID().equals(lord[0])) a.kill();
        });
        helper.runAtTickTime(20, () -> {
            UUID id = v.id();
            Elections.tick(level, v);
            List<String> line = Government.lineForTests(id);
            Kit.log("id07 after the lord's death: elder " + Villages.elderName(id) + "; the line " + line + "; election " + Ledger.note(id, "election.now"));
            helper.assertTrue(heir[0].getUUID().equals(Villages.elder(id)), "the eldest child takes the seat: " + Villages.elderName(id));
            helper.assertTrue(line.size() >= 2 && line.get(line.size() - 1).contains("eldest child of"), "and the line is kept: " + line);
            helper.assertTrue(told(id, "took the seat as lord"), "the chronicle says so");
            String now = Ledger.note(id, "election.now");
            helper.assertTrue(now == null || now.isEmpty(), "and nobody is elected");
            // The town has had enough of the heir: a vote of no confidence.
            for (VillageFolkEntity f : folk(id)) {
                if (f == heir[0]) continue;
                f.life().feel(heir[0].getUUID(), heir[0].displayNameCap(), -90);
                Values.setForTests(f, Values.Value.TRADITION, 0);
            }
            int q = Government.deposeForTests(level, v);
            Referendums.castAllForTests(level, v, q);
            int[] count = Referendums.countForTests(level, v, q);
            Kit.log("id07 no confidence: aye " + count[0] + ", nay " + count[1] + "; now " + Identity.summary(id) + "; next election "
                + Ledger.note(id, "election.next"));
            helper.assertTrue(q >= 0 && count[2] == 1, "the vote is carried: " + count[0] + " to " + count[1]);
            helper.assertTrue(Government.of(id) != Government.Form.LORD && Government.holdsElections(id), "the house is out, and the town elects");
            helper.assertTrue(Villages.elder(id) == null || !heir[0].getUUID().equals(Villages.elder(id)), "the heir no longer leads");
            helper.succeed();
        });
    }

    // ============================================================ id08: who decides

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "id08_who_decides")
    public static void id08_who_decides(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 2000);
        int x = 1474000;
        Villages.Village commune = town(helper, x, Z, 5), reeve = town(helper, x + 300, Z, 5), elders = town(helper, x + 600, Z, 6);
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            for (Villages.Village t : new Villages.Village[]{ commune, reeve, elders }) {
                Identity.seedForTests(level, t);
                allAxes(t.id(), 0);
                LawBook.setForTests(t.id(), LawBook.Law.CURFEW, 0);
                founders(t.id(), Social.Trait.GRUMPY, Social.Trait.HARDWORKING, Values.Value.SAFETY, 90, Values.Value.LEISURE, 5,
                    Values.Value.TRADITION, 60, Values.Value.WEALTH, 10);
            }
            Government.setForTests(level, commune, Government.Form.COMMUNE);
            Government.setForTests(level, reeve, Government.Form.REEVE);
            Government.setForTests(level, elders, Government.Form.COUNCIL);
            Villages.electElder(reeve.id(), folk(reeve.id()).get(0), day);
            // The commune: a curfew goes to the whole town's vote.
            LawBook.proposeForTests(level, commune, LawBook.Law.CURFEW, 1);
            List<String> open = Referendums.openForTests(commune.id());
            Kit.log("id08 the commune: " + open + "; curfew " + LawBook.curfew(commune.id()));
            helper.assertTrue(open.size() == 1 && open.get(0).contains(Government.KIND) && open.get(0).contains("curfew"),
                "a commune puts the curfew to the town: " + open);
            helper.assertTrue(!LawBook.curfew(commune.id()), "and nothing changes until it is counted");
            int q = Integer.parseInt(open.get(0).split(" ")[0]);
            Referendums.castAllForTests(level, commune, q);
            int[] count = Referendums.countForTests(level, commune, q);
            Kit.log("id08 the commune's count: aye " + count[0] + ", nay " + count[1] + "; curfew " + LawBook.curfew(commune.id()));
            helper.assertTrue(count[0] + count[1] >= 5 && count[2] == 1 && LawBook.curfew(commune.id()), "the town votes it in");
            // The elected leader decrees it; the elders vote among themselves; neither asks the town.
            LawBook.proposeForTests(level, reeve, LawBook.Law.CURFEW, 1);
            LawBook.proposeForTests(level, elders, LawBook.Law.CURFEW, 1);
            Kit.log("id08 the reeve's town: " + Referendums.openForTests(reeve.id()) + ", curfew " + LawBook.curfew(reeve.id())
                + "; the elders': " + Referendums.openForTests(elders.id()) + ", curfew " + LawBook.curfew(elders.id()));
            helper.assertTrue(Referendums.openForTests(reeve.id()).isEmpty() && LawBook.curfew(reeve.id()), "an elected leader decrees it");
            helper.assertTrue(Referendums.openForTests(elders.id()).isEmpty() && LawBook.curfew(elders.id()), "the elders vote it in among themselves");
            helper.assertTrue(told(reeve.id(), "decreed it") && told(elders.id(), "the council of elders voted"), "each said its own way");
            helper.assertTrue(Government.votersForTests(elders.id()) == 5 && Government.votersForTests(commune.id()) == 5,
                "the elders' five vote for their speaker; the whole commune for its steward");
            // A commune pays everybody alike; the elders' term is fourteen days, the commune's seven.
            helper.assertTrue(Ethos.wageForTests(commune.id(), 10, 6.0) == 6 && Ethos.wageForTests(reeve.id(), 10, 6.0) == 10, "a commune's equal wage");
            helper.assertTrue(Government.term(commune.id(), 10) == 7 && Government.term(elders.id(), 10) == 14, "their terms");
            // The chaplain's town keeps a feast mid-week.
            Government.setForTests(level, reeve, Government.Form.CHAPLAIN);
            long mid = (day / 7 + 1) * 7 + 3;
            helper.assertTrue(Gatherings.tonight(reeve.id(), mid) == Gatherings.Kind.FEAST && Gatherings.tonight(elders.id(), mid) == null,
                "a feast mid-week under the chaplain, none elsewhere");
            helper.assertTrue(!Government.holdsElections(reeve.id()) && Villages.elder(reeve.id()) != null, "and the chapel's choice leads it: "
                + Villages.elderName(reeve.id()));
            helper.succeed();
        });
    }

    // ============================================================ id09: newcomers, open and closed

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "id09_newcomers")
    public static void id09_newcomers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 3000);
        int x = 1476000;
        Villages.Village open = town(helper, x, Z, 6), closed = town(helper, x + 300, Z, 6);
        final int[] q = { -1, -1 };
        helper.runAtTickTime(10, () -> {
            long day = level.getDayTime() / 24000L;
            for (Villages.Village t : new Villages.Village[]{ open, closed }) {
                Identity.seedForTests(level, t);
                allAxes(t.id(), 0);
                Government.setForTests(level, t, Government.Form.REEVE);
                plainLaws(t.id());
                Leader.booksForTests(t.id(), new Leader.Books(400, 40, 30, 40, 30, 10, Leader.Plan.PLENTY, day));
                Villages.bedsForTests(t.id(), level.getGameTime(), 30);            // room for them, in both
                // The same kind of folk in both: generous, sociable, minding safety a little.
                founders(t.id(), Social.Trait.GENEROUS, Social.Trait.SOCIABLE, Values.Value.SAFETY, 40);
            }
            Ethos.setForTests(open.id(), Ethos.Axis.DOORS, 70);
            Ethos.setForTests(closed.id(), Ethos.Axis.DOORS, -70);
            LawBook.setForTests(closed.id(), LawBook.Law.BORDERS, 1);
            String pa = Newcomers.outsideForTests(level, open, 2), pb = Newcomers.outsideForTests(level, closed, 2);
            helper.assertTrue(pa != null && pb != null, "a party from outside comes to each");
            q[0] = Newcomers.arriveForTests(level, pa);
            q[1] = Newcomers.arriveForTests(level, pb);
        });
        helper.runAtTickTime(30, () -> {
            int[] ayes = new int[2];
            int[] of = new int[2];
            Villages.Village[] towns = { open, closed };
            for (int k = 0; k < 2; k++) {
                for (VillageFolkEntity f : folk(towns[k].id())) {
                    String j = Referendums.judgeForTests(level, f, towns[k].id(), q[k]);
                    if (j.startsWith("aye")) ayes[k]++;
                    of[k]++;
                    if (of[k] <= 2) Kit.log("id09 " + Villages.name(towns[k].id()) + ": " + f.displayNameCap() + " says " + j);
                }
            }
            Kit.log("id09 ayes: the open town " + ayes[0] + " of " + of[0] + ", the closed " + ayes[1] + " of " + of[1]);
            helper.assertTrue(q[0] >= 0 && q[1] >= 0, "each town is asked");
            helper.assertTrue(ayes[0] * 2 > of[0], "the open town would take them in: " + ayes[0] + " of " + of[0]);
            helper.assertTrue(ayes[1] * 2 < of[1] && ayes[1] < ayes[0], "the closed town turns them away: " + ayes[1] + " of " + of[1]);
            Referendums.castAllForTests(level, open, q[0]);
            Referendums.castAllForTests(level, closed, q[1]);
            int[] ca = Referendums.countForTests(level, open, q[0]), cb = Referendums.countForTests(level, closed, q[1]);
            Kit.log("id09 the counts: " + java.util.Arrays.toString(ca) + " / " + java.util.Arrays.toString(cb));
            helper.assertTrue(ca[2] == 1 && cb[2] == 0, "taken in by the one, turned away by the other");
            helper.succeed();
        });
    }

    // ============================================================ id10: how a town treats a player

    @GameTest(template = EMPTY, timeoutTicks = 1000, batch = "id10_players")
    public static void id10_players(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level, 3000);
        Kit.noLeftoverPlayers(level);
        int x = 1478000;
        Villages.Village open = town(helper, x, Z, 4), closed = town(helper, x + 300, Z, 4);
        final Player[] p = { null };
        final VillageFolkEntity[] witness = { null };
        helper.runAtTickTime(10, () -> {
            for (Villages.Village t : new Villages.Village[]{ open, closed }) {
                Identity.seedForTests(level, t);
                allAxes(t.id(), 0);
                Government.setForTests(level, t, Government.Form.REEVE);
                plainLaws(t.id());
            }
            Ethos.setForTests(open.id(), Ethos.Axis.DOORS, 70);
            TownTraits.awardForTests(level, open, TownTraits.Trait.HOSPITABLE);
            Ethos.setForTests(closed.id(), Ethos.Axis.DOORS, -70);
            TownTraits.awardForTests(level, closed, TownTraits.Trait.RAID_SCARRED);
            LawBook.setForTests(closed.id(), LawBook.Law.WEAPONS, 1);
            LawBook.setForTests(closed.id(), LawBook.Law.HOUSES, 1);
            p[0] = helper.makeMockPlayer(GameType.SURVIVAL);
            // A kindness worth four elsewhere, to a folk of each.
            VillageFolkEntity fo = folk(open.id()).get(0), fc = folk(closed.id()).get(0);
            Treatment.attachForTests(fo);
            Treatment.attachForTests(fc);
            fo.persona().feelFor(p[0].getUUID(), "Tester", 4);
            fc.persona().feelFor(p[0].getUUID(), "Tester", 4);
            int ao = fo.persona().affinity(p[0].getUUID()), ac = fc.persona().affinity(p[0].getUUID());
            Kit.log("id10 a kindness worth 4: the open town's folk " + ao + ", the closed town's " + ac);
            helper.assertTrue(ao >= ac + 3, "an open, hospitable town warms to a player faster: " + ao + " against " + ac);
            helper.assertTrue(LawBook.housesBarred(closed.id(), false) != null && LawBook.housesBarred(open.id(), false) == null,
                "the closed town sells its houses to citizens only");
            // What its folk say of it.
            String said = Identity.talk(fc, p[0], "What's this town like?");
            Kit.log("id10 asked what the town is like: " + said);
            helper.assertTrue(said != null && said.contains("the watch alone goes armed"), "a folk tells a visitor the town's ways and its law");
            CompoundTag page = Identity.report(level, closed);
            Kit.log("id10 the page's treatment: " + page.getList("treatment", 8));
            helper.assertTrue(page.getList("treatment", 8).getString(0).contains("coldly") || page.getList("treatment", 8).getString(0).contains("warily"),
                "the closed town greets strangers warily");
            // A sword in hand in the closed town, a folk looking on: asked to put it away, then fined.
            BlockPos heart = closed.centre();
            witness[0] = fc;
            fc.setNoAi(true);
            fc.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 3.5, 180.0F, 0.0F);
            p[0].moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
            p[0].setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            Identity.inTown(level, p[0], closed.id());
            helper.assertTrue(Laws.offences(closed.id(), p[0].getUUID()) == 0, "first, only asked to put it away");
            Identity.inTown(level, p[0], open.id());
            helper.assertTrue(Laws.offences(open.id(), p[0].getUUID()) == 0, "the open town lets anybody go armed");
        });
        helper.runAtTickTime(700, () -> {
            witness[0].getLookControl().setLookAt(p[0]);
            Identity.inTown(level, p[0], closed.id());
            int n = Laws.offences(closed.id(), p[0].getUUID());
            Kit.log("id10 still armed half a minute later: offences " + n + ", owes " + Laws.owes(closed.id(), p[0].getUUID()));
            helper.assertTrue(n == 1, "still armed after the warning, it is fined: " + n);
            helper.succeed();
        });
    }
}
