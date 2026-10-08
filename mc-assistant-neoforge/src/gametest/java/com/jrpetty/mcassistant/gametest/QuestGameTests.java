package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Plaques;
import com.jrpetty.mcassistant.entity.QuestBook;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestMaker;
import com.jrpetty.mcassistant.entity.QuestRewards;
import com.jrpetty.mcassistant.entity.QuestRun;
import com.jrpetty.mcassistant.entity.QuestStories;
import com.jrpetty.mcassistant.entity.Quests;
import com.jrpetty.mcassistant.entity.SearchParties;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Quests for players (entity/QuestRun, QuestMaker, QuestStories and the four stories): a folk with a real need offering
 * a matching quest, taking it on, doing its steps, the pay out of a purse or the treasury, standing; two players and one
 * favour; giving up; a quest's thing made of the stores' real makings; a two-step chain; each story walked down its
 * main path, a branch of one, a secret that comes out; the book kept over a restart, the journal, the board's postings.
 *
 * <p>Each on its own ground (x 960000 to 978000, z 66000), in a batch of its own. The steps are done the way the
 * server's once-a-second look and a player's talk do them (QuestRun.checkForTests, QuestRun.talk, FolkTalk.answer), with
 * a mock player the server does not tick.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class QuestGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ helpers

    private static void clean(ServerLevel level) {
        Kit.reset(level);
        QuestBook.resetForTests();
        QuestRun.resetForTests();
        QuestRun.quietForTests(true);
        Standing.resetForTests();
        SearchParties.resetForTests();
    }

    private static BlockPos heart(ServerLevel level, int x, int r) {
        Kit.hold(level, x, Z, r);
        Kit.prepare(level, x, Z, r);
        // Every chunk of it live now: a spider set down out by the fields is seen at once (getEntitiesOfClass).
        if (!Kit.live(level, x, Z, r)) Kit.log("qg the ground round " + x + " not all live: " + Kit.notLive(level, x, Z, r) + " chunks");
        return Kit.surface(level, x, Z);
    }

    /** The time of day set, and the sky's light with it. */
    private static void time(ServerLevel level, long t) {
        level.setDayTime(t);
        level.updateSkyBrightness();
    }

    /** What all the town's stores hold of a thing (there may be a chest of the founding's besides the test's own). */
    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    /**
     * A hollow in a stone mound out past the town: the test world is a flat one, four blocks of ground over the
     * bottom of the world, so "under the ground" is built up rather than dug down. Its floor's middle (the camp).
     */
    private static BlockPos hollow(ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos camp = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos c : BlockPos.betweenClosed(camp.offset(-3, -1, -3), camp.offset(3, 4, 3))) level.setBlockAndUpdate(c, Blocks.STONE.defaultBlockState());
        for (BlockPos c : BlockPos.betweenClosed(camp.offset(-2, 0, -2), camp.offset(2, 2, 2))) level.setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
        return camp.immutable();
    }

    /** So many folk of one town, round its heart. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, BlockPos heart, int n) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos at = Kit.surface(level, heart.getX() + (i % 4) * 3 - 4, heart.getZ() + (i / 4) * 3 - 3);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            out.add(f);
        }
        UUID id = out.get(0).ownerId();
        for (VillageFolkEntity f : out) helper.assertTrue(id.equals(f.ownerId()), "one town");
        return out;
    }

    /** A chest of the town's stores this far from the heart, holding these. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        c.setChanged();
        return c;
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static int count(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (what.test(p.getInventory().getItem(i))) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    private static int questOf(ItemStack s) {
        var d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt("mca_quest");
    }

    /** The town's folk know this player a little: a visitor. */
    private static void known(Player p, int by, VillageFolkEntity... folk) {
        for (VillageFolkEntity f : folk) f.persona().feelFor(p.getUUID(), p.getName().getString(), by);
        if (folk.length > 0) Standing.stir(folk[0].ownerId(), p.getUUID());
    }

    private static void at(Player p, BlockPos pos) {
        p.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
    }

    private static String chronicle(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append(e.text()).append(" | ");
        return sb.toString();
    }

    private static String steps(Quest q) {
        StringBuilder sb = new StringBuilder();
        for (QuestBook.Step s : q.steps) sb.append(s.done ? "[x] " : "[ ] ").append(s.key).append(": ").append(s.text).append(s.note.isEmpty() ? "" : " (" + s.note + ")").append("; ");
        return sb.toString();
    }

    // ============================================================ qg01 a favour, from a real need, paid out of a purse

    /**
     * A folk badly hurt, and not a remedy in the stores: its partner offers a quest for three remedies (a gold "!" over
     * it). Asked "Any work for me?" it says so in its own words; "I'll do it" puts the quest in the player's journal (a
     * "?" over it now); the honey handed over completes it: one remedy goes to the patient, the rest into the stores,
     * the pay comes out of the partner's own purse (counted), and the partner and the town think the better of the
     * player.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg01_a_favour_from_a_real_need")
    public static void qg01_a_favour_from_a_real_need(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos heart = heart(level, 960000, 40);
        List<VillageFolkEntity> folk = town(helper, heart, 4);
        VillageFolkEntity sick = folk.get(0), partner = folk.get(1);
        UUID village = sick.ownerId();
        Villages.Village v = Villages.get(village);
        sick.life().partnerWith(partner.getUUID(), partner.displayNameCap());
        partner.life().partnerWith(sick.getUUID(), sick.displayNameCap());
        stores(level, heart, 6, 6, new ItemStack(Items.BREAD, 8));
        Villages.forgetStores(village);
        sick.setHealth(sick.getMaxHealth() * 0.4F);
        partner.earn(20);
        Quest q = QuestMaker.offerForTests(level, v, "healer");
        Kit.log("qg01 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + ", " + q.coins + " coins from the " + q.payer + "; " + steps(q)));
        helper.assertTrue(q != null && q.giver.equals(partner.getUUID()) && "remedy".equals(q.steps.get(0).item) && q.steps.get(0).count == 3,
            "the hurt folk's partner offers three remedies: " + (q == null ? "none" : q.giverName + " " + q.steps.get(0).item));
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        char mark0 = QuestRun.markFor(partner, p);
        String asked = FolkTalk.answer(partner, p, TalkTopic.JOBS, "");
        Kit.log("qg01 \"Any work for me?\" -> " + asked + " (mark " + mark0 + ")");
        helper.assertTrue(mark0 == '!' && asked.toLowerCase(java.util.Locale.ROOT).contains("three"), "a gold \"!\" over it, and the offer in its words: " + mark0 + " " + asked);
        String took = FolkTalk.answer(partner, p, TalkTopic.SAY, "I'll do it.");
        List<Quest> mine = QuestBook.active(p.getUUID());
        Kit.log("qg01 \"I'll do it.\" -> " + took + "; the journal: " + QuestRun.journal(p, QuestRun.day(level)).replace('\u001f', '/').replace('\n', ' '));
        helper.assertTrue(mine.size() == 1 && mine.get(0).id == q.id && q.state == State.ACTIVE, "taken: in the player's journal: " + mine.size() + " " + q.state);
        helper.assertTrue(QuestRun.markFor(partner, p) == '?', "a \"?\" over the partner now: " + QuestRun.markFor(partner, p));
        p.getInventory().add(new ItemStack(Items.HONEY_BOTTLE, 3));
        int purse0 = partner.purse(), coins0 = Market.coinsHeld(p), aff0 = partner.persona().affinity(p.getUUID());
        float hp0 = sick.getHealth();
        int honey0 = stock(level, village, s -> s.is(Items.HONEY_BOTTLE));
        Standing.stir(village, p.getUUID());
        int score0 = Standing.of(village, p.getUUID(), level.getGameTime()).score();
        String done = FolkTalk.answer(partner, p, TalkTopic.SAY, "Here you are — I've brought it.");
        int purse1 = partner.purse(), coins1 = Market.coinsHeld(p), aff1 = partner.persona().affinity(p.getUUID());
        Standing.stir(village, p.getUUID());
        int score1 = Standing.of(village, p.getUUID(), level.getGameTime()).score();
        int honey = stock(level, village, s -> s.is(Items.HONEY_BOTTLE)) - honey0;
        Kit.log("qg01 handed over -> " + done + " | purse " + purse0 + " -> " + purse1 + ", player's coin " + coins0 + " -> " + coins1 + ", warmth " + aff0 + " -> "
            + aff1 + ", standing " + score0 + " -> " + score1 + ", patient " + hp0 + " -> " + sick.getHealth() + ", honey in the stores " + honey);
        helper.assertTrue(q.state == State.DONE, "done: " + q.state + " " + steps(q));
        helper.assertTrue(q.coins > 0 && purse0 - purse1 == q.coins && coins1 - coins0 == q.coins,
            "paid out of the partner's own purse: " + q.coins + " promised, purse " + purse0 + " -> " + purse1 + ", player " + coins0 + " -> " + coins1);
        helper.assertTrue(aff1 - aff0 >= q.warmth && score1 > score0, "the partner and the town warmer: " + aff0 + " -> " + aff1 + ", standing " + score0 + " -> " + score1);
        helper.assertTrue(honey == 2 && sick.getHealth() > hp0, "one remedy to the patient, two in the stores: " + honey + ", " + hp0 + " -> " + sick.getHealth());
        helper.succeed();
    }

    // ============================================================ qg02 the town's quest: the treasury, two players, giving up

    /**
     * Spiders gathered about the town: its elder offers a den to clear, paid out of the treasury. One player takes it;
     * a second asking is told somebody has it already, and gets nothing. The kills count; told, the elder's treasury
     * pays (counted before and after). A second den, taken by the second player and given up: the elder thinks the
     * less of it, and so does the town.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg02_the_town_two_players_giving_up")
    public static void qg02_the_town_two_players_giving_up(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos heart = heart(level, 962000, 60);
        List<VillageFolkEntity> folk = town(helper, heart, 5);
        VillageFolkEntity elder = folk.get(0);
        UUID village = elder.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.electElder(village, elder, QuestRun.day(level));
        Ledger.addCoins(village, 50);
        // Two dens, set down now and looked at a moment later, once the town can see them.
        List<Spider> den = spiders(level, heart.offset(30, 0, 10), 3);
        List<Spider> den2 = spiders(level, heart.offset(-30, 0, -10), 3);
        helper.runAtTickTime(10, () -> {
            int seen = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                new net.minecraft.world.phys.AABB(v.centre()).inflate(96, 48, 96), net.minecraft.world.entity.LivingEntity::isAlive).size();
            Quest q = QuestMaker.offerForTests(level, v, "den");
            Kit.log("qg02 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + ", " + q.coins + " coins from the " + q.payer + "; " + steps(q))
                + " (monsters about the town " + seen + "; the spiders alive " + den.stream().filter(Spider::isAlive).count() + "+"
                + den2.stream().filter(Spider::isAlive).count() + ")");
            helper.assertTrue(q != null && q.giver.equals(elder.getUUID()) && "treasury".equals(q.payer) && q.coins > 0,
                "the elder offers a den, paid by the treasury: " + (q == null ? "none" : q.giverName + " " + q.payer + " " + q.coins));
            List<Spider> first = q.steps.get(0).at.distSqr(den.get(0).blockPosition()) < 24 * 24 ? den : den2;
            List<Spider> other = first == den ? den2 : den;
            Player one = helper.makeMockPlayer(GameType.SURVIVAL), two = helper.makeMockPlayer(GameType.SURVIVAL);
            String took = QuestRun.accept(level, elder, one);
            String second = QuestRun.accept(level, elder, two);
            Kit.log("qg02 the first: " + took + " | the second: " + second);
            helper.assertTrue(one.getUUID().equals(q.player) && QuestBook.active(two.getUUID()).isEmpty() && second.contains("already"),
                "one taker only: " + q.playerName + "; the second told: " + second);
            for (Spider s : first) QuestRun.killed(level, one, s);
            helper.assertTrue(q.steps.get(0).done, "the den cleared: " + steps(q));
            int treasury0 = Ledger.coins(village), coins0 = Market.coinsHeld(one);
            String told = QuestRun.talk(level, elder, one, null);
            int treasury1 = Ledger.coins(village), coins1 = Market.coinsHeld(one);
            Kit.log("qg02 told the elder -> " + told + " | treasury " + treasury0 + " -> " + treasury1 + ", player " + coins0 + " -> " + coins1);
            helper.assertTrue(q.state == State.DONE && treasury0 - treasury1 == q.coins && coins1 - coins0 == q.coins,
                "paid out of the treasury: " + q.coins + "; treasury " + treasury0 + " -> " + treasury1 + ", player " + coins0 + " -> " + coins1);
            // The second den: the second player takes it, and gives it up.
            for (Spider s : first) s.discard();
            Quest again = QuestMaker.offerForTests(level, v, "den");
            helper.assertTrue(again != null && again.steps.get(0).at.distSqr(other.get(0).blockPosition()) < 24 * 24, "the second den offered");
            QuestRun.accept(level, elder, two);
            int aff0 = elder.persona().affinity(two.getUUID()), town0 = folk.get(2).persona().affinity(two.getUUID());
            String gaveUp = QuestRun.abandon(level, again, two);
            int aff1 = elder.persona().affinity(two.getUUID()), town1 = folk.get(2).persona().affinity(two.getUUID());
            Kit.log("qg02 given up -> " + gaveUp + " | the elder " + aff0 + " -> " + aff1 + ", a neighbour " + town0 + " -> " + town1 + "; " + again.state);
            helper.assertTrue(again.state == State.ABANDONED && aff0 - aff1 >= 12, "given up: the elder let down: " + aff0 + " -> " + aff1);
            for (Spider s : other) s.discard();
            helper.succeed();
        });
    }

    private static List<Spider> spiders(ServerLevel level, BlockPos at, int n) {
        List<Spider> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Spider s = EntityType.SPIDER.create(level);
            BlockPos g = Kit.surface(level, at.getX() + i * 2, at.getZ());
            s.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0, 0);
            s.setNoAi(true);
            s.setPersistenceRequired();
            level.addFreshEntity(s);
            out.add(s);
        }
        return out;
    }

    // ============================================================ qg03 a letter, made of the stores' paper, and its answer

    /**
     * A folk with a close friend in the next town writes to it: the sealed letter is made at the offer's taking of the
     * town's own paper and red dye (counted out of its stores); carried over and read, the friend writes back on its
     * own town's paper (counted out of those stores), and the quest goes on to its second step: the reply carried home.
     * The friendship is the warmer, and the writer pays.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg03_a_letter_and_its_answer")
    public static void qg03_a_letter_and_its_answer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos a = heart(level, 964000, 30);
        Kit.hold(level, 964400, Z, 30);
        Kit.prepare(level, 964400, Z, 30);
        Kit.live(level, 964400, Z, 30);
        BlockPos b = Kit.surface(level, 964400, Z);
        List<VillageFolkEntity> here = town(helper, a, 4);
        List<VillageFolkEntity> there = town(helper, b, 4);
        helper.assertTrue(!here.get(0).ownerId().equals(there.get(0).ownerId()), "two towns");
        VillageFolkEntity writer = here.get(0), friend = there.get(0);
        UUID ours = writer.ownerId(), theirs = friend.ownerId();
        writer.life().feel(friend.getUUID(), friend.displayNameCap(), 50);
        writer.earn(20);
        Container mine = stores(level, a, 6, 6, new ItemStack(Items.PAPER, 4), new ItemStack(Items.RED_DYE, 2));
        Container yours = stores(level, b, 6, 6, new ItemStack(Items.PAPER, 2), new ItemStack(Items.RED_DYE, 1));
        Villages.forgetStores(ours);
        Villages.forgetStores(theirs);
        Quest q = QuestMaker.offerForTests(level, Villages.get(ours), "letter");
        Kit.log("qg03 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + "; " + steps(q)));
        helper.assertTrue(q != null && q.giver.equals(writer.getUUID()) && friend.getUUID().equals(q.steps.get(0).who), "a letter to the friend in the next town");
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        int paper0 = count(mine, s -> s.is(Items.PAPER)), dye0 = count(mine, s -> s.is(Items.RED_DYE));
        String took = QuestRun.accept(level, writer, p);
        int paper1 = count(mine, s -> s.is(Items.PAPER)), dye1 = count(mine, s -> s.is(Items.RED_DYE));
        int letters = count(p, s -> s.is(McAssistantMod.SEALED_LETTER.get()) && questOf(s) == q.id);
        Kit.log("qg03 taken -> " + took + " | the stores' paper " + paper0 + " -> " + paper1 + ", red dye " + dye0 + " -> " + dye1 + "; letters in hand " + letters);
        helper.assertTrue(letters == 1 && paper0 - paper1 == 1 && dye0 - dye1 == 1, "the sealed letter made of the stores' paper and dye: paper " + paper0 + " -> "
            + paper1 + ", dye " + dye0 + " -> " + dye1 + ", letters " + letters);
        int fpaper0 = count(yours, s -> s.is(Items.PAPER)), warm0 = friend.life().affinity(writer.getUUID());
        String read = QuestRun.talk(level, friend, p, null);
        int fpaper1 = count(yours, s -> s.is(Items.PAPER)), warm1 = friend.life().affinity(writer.getUUID());
        Kit.log("qg03 delivered -> " + read + " | the other town's paper " + fpaper0 + " -> " + fpaper1 + ", friendship " + warm0 + " -> " + warm1 + "; " + steps(q));
        helper.assertTrue(q.steps.size() == 2 && q.steps.get(0).done && !q.steps.get(1).done && "reply".equals(q.steps.get(1).key),
            "the chain goes on to its second step: " + steps(q));
        helper.assertTrue(fpaper0 - fpaper1 == 1 && warm1 > warm0 && count(p, s -> s.is(McAssistantMod.SEALED_LETTER.get()) && questOf(s) == q.id) == 1,
            "the reply written on the other town's own paper, the friendship warmer: " + fpaper0 + " -> " + fpaper1 + ", " + warm0 + " -> " + warm1);
        int purse0 = writer.purse();
        String home = QuestRun.talk(level, writer, p, null);
        Kit.log("qg03 the reply home -> " + home + " | purse " + purse0 + " -> " + writer.purse() + "; " + q.state);
        helper.assertTrue(q.state == State.DONE && purse0 - writer.purse() == q.coins, "done, paid out of the writer's purse: " + q.state + " " + q.coins);
        helper.succeed();
    }

    // ============================================================ qg04 Lost at Dusk: the main path

    /**
     * A child of a real household lost at dusk: the parent's offer, the child gone out to its place in the woods, its
     * wooden horse (of the stores' planks) dropped on the way; the friend's word, the toy picked up, the child found, its
     * secret kept, home, and to its parent: paid out of the parents' purses, a drawing of the stores' paper and dyes,
     * the child's lasting fondness, the chronicle.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg04_lost_at_dusk")
    public static void qg04_lost_at_dusk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos heart = heart(level, 966000, 70);
        List<VillageFolkEntity> folk = town(helper, heart, 4);
        VillageFolkEntity mother = folk.get(0), father = folk.get(1), neighbour = folk.get(2);
        UUID village = mother.ownerId();
        Villages.Village v = Villages.get(village);
        mother.life().partnerWith(father.getUUID(), father.displayNameCap());
        father.life().partnerWith(mother.getUUID(), mother.displayNameCap());
        mother.insertItem(new ItemStack(Items.BREAD, 2));
        father.insertItem(new ItemStack(Items.BREAD, 2));
        VillageFolkEntity child = mother.raiseChildWith(father);
        helper.assertTrue(child != null && child.isBaby(), "a child born");
        child.ensurePersona();
        mother.earn(20);
        father.earn(10);
        // A young town keeps its planks back from any maker (Bench); the parent whittles the toy of them all the same.
        stores(level, heart, 6, 6, new ItemStack(Items.OAK_PLANKS, 60), new ItemStack(Items.STICK, 4), new ItemStack(Items.PAPER, 2),
            new ItemStack(Items.YELLOW_DYE, 1), new ItemStack(Items.BLUE_DYE, 1));
        Villages.forgetStores(village);
        BlockPos place = Kit.surface(level, heart.getX() + 45, heart.getZ() + 20);
        Predicate<ItemStack> wood = s -> s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(Items.STICK);
        int planks0 = stock(level, village, wood);
        Quest q = QuestStories.beginForTests(level, v, "child", Map.of("child", child, "parent", mother, "friend", neighbour, "place", place, "variant", "woods"));
        Kit.log("qg04 begun: " + (q == null ? "no — " + QuestStories.whyForTests(village) : q.title + " by " + q.giverName + "; the child at "
            + child.blockPosition().toShortString() + ", its place " + place.toShortString()));
        helper.assertTrue(q != null && q.state == State.OFFERED, "the story offered: " + QuestStories.whyForTests(village));
        helper.assertTrue(child.blockPosition().distSqr(place) < 9, "the child gone to its place: " + child.blockPosition().toShortString());
        ItemEntity toy = q.flag("toy").isEmpty() ? null : level.getEntity(UUID.fromString(q.flag("toy"))) instanceof ItemEntity e ? e : null;
        int planks1 = stock(level, village, wood);
        helper.assertTrue(toy != null && toy.getItem().is(McAssistantMod.WOODEN_TOY.get()) && planks1 < planks0,
            "its wooden horse, of the stores' wood, on the way: " + planks0 + " -> " + planks1);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        known(p, 5, mother, neighbour);
        String took = QuestRun.accept(level, mother, p);
        Kit.log("qg04 taken -> " + took);
        helper.assertTrue(q.state == State.ACTIVE, "taken: " + q.state + " — " + took);
        String said = QuestRun.talk(level, neighbour, p, null);
        Kit.log("qg04 the friend -> " + said + "; " + steps(q));
        helper.assertTrue(q.current() != null && "toy".equals(q.current().key), "the friend's word, and on to the toy: " + steps(q));
        p.getInventory().add(toy.getItem().copy());
        toy.discard();
        QuestRun.checkForTests(level, p);
        helper.assertTrue(q.current() != null && "find".equals(q.current().key), "the toy found, the tracks next: " + steps(q));
        at(p, child.blockPosition());
        QuestRun.checkForTests(level, p);
        helper.assertTrue(q.current() != null && "secret".equals(q.current().key), "the child found, and its secret asked: " + steps(q));
        String secret = QuestRun.talk(level, child, p, "keep");
        Kit.log("qg04 the secret kept -> " + secret);
        child.moveTo(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5, 0, 0);
        QuestRun.checkForTests(level, p);
        helper.assertTrue(q.current() != null && "parent".equals(q.current().key), "home, and to its parent: " + steps(q));
        int purses0 = mother.purse() + father.purse(), coins0 = Market.coinsHeld(p), fond0 = child.persona().affinity(p.getUUID());
        int paper0 = stock(level, village, s -> s.is(Items.PAPER));
        String reunion = QuestRun.talk(level, mother, p, null);
        int purses1 = mother.purse() + father.purse(), coins1 = Market.coinsHeld(p), fond1 = child.persona().affinity(p.getUUID());
        int drawings = count(p, s -> s.is(McAssistantMod.CHILDS_DRAWING.get()) && questOf(s) == q.id);
        Kit.log("qg04 the reunion -> " + reunion + " | purses " + purses0 + " -> " + purses1 + ", player " + coins0 + " -> " + coins1 + ", the child " + fond0 + " -> "
            + fond1 + ", drawings " + drawings + "; ending: " + q.ending);
        Kit.log("qg04 the chronicle: " + chronicle(village));
        helper.assertTrue(q.state == State.DONE && purses0 - purses1 == q.coins && coins1 - coins0 == q.coins && q.coins > 0,
            "done, paid out of the parents' purses: " + q.coins + "; " + purses0 + " -> " + purses1);
        helper.assertTrue(fond1 >= 60 && drawings == 1 && paper0 - stock(level, village, s -> s.is(Items.PAPER)) == 1,
            "the child's lasting fondness, and its drawing of the stores' paper: " + fond1 + ", " + drawings);
        helper.assertTrue(chronicle(village).contains("was found by") && QuestBook.titles(p.getUUID(), village).contains("Finder of the Lost"),
            "the chronicle and the title: " + QuestBook.titles(p.getUUID(), village));
        helper.succeed();
    }

    // ============================================================ qg05 The Smugglers' Cave: the main path

    /**
     * Goods really go out of the stores into a camp in a cave (a third of the iron and the gold); the storekeeper asks a
     * friend of the town; the gossip, the accomplice, the lights at night, the camp, the ledger (of the stores' paper);
     * the accomplice turned in: the ledger to the elder, the council's trial (guilty, the fine into the treasury), the
     * goods back in the stores (counted), the storekeeper told, and the treasury pays.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg05_the_smugglers_cave")
    public static void qg05_the_smugglers_cave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos heart = heart(level, 968000, 60);
        List<VillageFolkEntity> folk = town(helper, heart, 7);
        VillageFolkEntity keeper = folk.get(0), elder = folk.get(1), ring = folk.get(2), acc = folk.get(3), gossip = folk.get(4);
        UUID village = keeper.ownerId();
        Villages.Village v = Villages.get(village);
        keeper.setJob(StationTask.STORE);
        acc.setJob(StationTask.HAUL);
        Villages.electElder(village, elder, QuestRun.day(level));
        ring.earn(30);
        Ledger.addCoins(village, 60);
        // String: the fishers' and the smith's two are kept back from any maker (Bench), so four for the ledger's one.
        stores(level, heart, 6, 6, new ItemStack(Items.IRON_INGOT, 24), new ItemStack(Items.GOLD_INGOT, 9), new ItemStack(Items.PAPER, 6),
            new ItemStack(Items.STRING, 4), new ItemStack(Items.INK_SAC, 2), new ItemStack(Items.CHEST, 1), new ItemStack(Items.TORCH, 4),
            new ItemStack(Items.CRAFTING_TABLE, 1));
        Villages.forgetStores(village);
        // A hollow in a hill out past the town: the camp.
        BlockPos camp = hollow(level, heart, 40, -20);
        Predicate<ItemStack> iron = s -> s.is(Items.IRON_INGOT);
        int iron0 = stock(level, village, iron);
        Quest q = QuestStories.beginForTests(level, v, "smugglers", Map.of("keeper", keeper, "elder", elder, "ring", ring, "accomplice", acc, "gossip", gossip,
            "camp", camp));
        Container cache = level.getBlockEntity(camp) instanceof Container c ? c : null;
        int iron1 = stock(level, village, iron), stolen = cache == null ? 0 : count(cache, iron);
        Kit.log("qg05 begun: " + (q == null ? "no — " + QuestStories.whyForTests(village) : q.title + "; stolen: " + q.flag("stolen") + "; iron in the stores " + iron0
            + " -> " + iron1 + ", in the camp " + stolen + "; the accomplice's purse " + acc.purse()));
        helper.assertTrue(q != null && cache != null && iron0 - iron1 == stolen && stolen > 0, "the goods really gone to the camp: " + iron0 + " -> " + iron1 + ", " + stolen);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        String refused = QuestRun.accept(level, keeper, p);
        helper.assertTrue(q.state == State.OFFERED, "not to a stranger: " + refused);
        known(p, 20, keeper, elder, gossip, folk.get(5), folk.get(6));
        String took = QuestRun.accept(level, keeper, p);
        Kit.log("qg05 a stranger -> " + refused + " | a friend of the town -> " + took);
        helper.assertTrue(q.state == State.ACTIVE, "taken by a friend of the town: " + took);
        Kit.log("qg05 the gossip -> " + QuestRun.talk(level, gossip, p, null));
        Kit.log("qg05 the accomplice -> " + QuestRun.talk(level, acc, p, null));
        at(p, QuestStories.place(q, "mouth"));
        QuestRun.checkForTests(level, p);
        helper.assertTrue("lights".equals(q.current().key), "the lights only show at night: " + steps(q));
        time(level, 14000);
        QuestRun.checkForTests(level, p);
        at(p, camp);
        QuestRun.checkForTests(level, p);
        helper.assertTrue("ledger".equals(q.current().key), "the camp found: " + steps(q));
        ItemStack ledger = ItemStack.EMPTY;
        for (int i = 0; i < cache.getContainerSize(); i++) {
            if (cache.getItem(i).is(McAssistantMod.SMUGGLERS_LEDGER.get())) {
                ledger = cache.getItem(i).copy();
                cache.setItem(i, ItemStack.EMPTY);
            }
        }
        helper.assertTrue(!ledger.isEmpty(), "the ledger in the camp's chest");
        p.getInventory().add(ledger);
        QuestRun.checkForTests(level, p);
        helper.assertTrue("confront".equals(q.current().key), "the ledger read: " + steps(q));
        Kit.log("qg05 confronted, turned in -> " + QuestRun.talk(level, acc, p, "turn"));
        int fines0 = Ledger.coins(village), accPurse0 = acc.purse();
        String trial = QuestRun.talk(level, elder, p, null);
        int iron2 = stock(level, village, iron);
        Kit.log("qg05 the trial -> " + trial + " | treasury " + fines0 + " -> " + Ledger.coins(village) + ", the accomplice's purse " + accPurse0 + " -> " + acc.purse()
            + ", iron back in the stores " + iron1 + " -> " + iron2);
        helper.assertTrue("guilty".equals(q.flag("verdict")) && iron2 == iron0 && acc.purse() < accPurse0, "guilty, fined, and the goods back: " + q.flag("verdict")
            + ", iron " + iron2 + "/" + iron0);
        int treasury0 = Ledger.coins(village), coins0 = Market.coinsHeld(p);
        String told = QuestRun.talk(level, keeper, p, null);
        Kit.log("qg05 the storekeeper told -> " + told + " | treasury " + treasury0 + " -> " + Ledger.coins(village) + ", player " + coins0 + " -> "
            + Market.coinsHeld(p) + "; ending: " + q.ending);
        Kit.log("qg05 the chronicle: " + chronicle(village));
        helper.assertTrue(q.state == State.DONE && treasury0 - Ledger.coins(village) == Market.coinsHeld(p) - coins0 && Market.coinsHeld(p) > coins0,
            "paid out of the treasury: " + treasury0 + " -> " + Ledger.coins(village));
        helper.assertTrue(chronicle(village).contains("found guilty"), "the trial in the chronicle");
        helper.succeed();
    }

    // ============================================================ qg06 The Cursed Mine: the main path

    /**
     * A spawner in the rock under the town's mine: the miners will not go down (held at the mine head in working hours);
     * the foreman's torches out of the stores, the old miner's tale and journal (of the stores' book and coal), the deep
     * level, the spawner broken, the place lit, a plaque chosen: the miners go back to work, the plaque is put up, the
     * treasury pays, and the player is the town's Curse-lifter.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg06_the_cursed_mine")
    public static void qg06_the_cursed_mine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 3000);
        BlockPos heart = heart(level, 970000, 60);
        List<VillageFolkEntity> folk = town(helper, heart, 5);
        VillageFolkEntity foreman = folk.get(0), old = folk.get(1), miner = folk.get(2), elder = folk.get(3);
        UUID village = foreman.ownerId();
        Villages.Village v = Villages.get(village);
        for (VillageFolkEntity m : new VillageFolkEntity[]{ foreman, old, miner }) m.setJob(StationTask.MINE);
        Villages.electElder(village, elder, QuestRun.day(level));
        Ledger.addCoins(village, 60);
        // Coal: the furnaces' eight are kept back from any maker (Bench), so twelve for the journal's one.
        stores(level, heart, 6, 6, new ItemStack(Items.BOOK, 1), new ItemStack(Items.COAL, 12), new ItemStack(Items.TORCH, 16));
        Villages.forgetStores(village);
        BlockPos site = Kit.surface(level, heart.getX() + 35, heart.getZ());
        // The test world is four blocks of ground over the bottom of the world: the "deep level" is out on the flat.
        BlockPos spawner = Kit.surface(level, site.getX() + 6, site.getZ() + 4);
        level.setBlockAndUpdate(spawner, Blocks.SPAWNER.defaultBlockState());
        Quest q = QuestStories.beginForTests(level, v, "mine", Map.of("site", site, "foreman", foreman, "old", old, "place", spawner, "kind", "spawner"));
        Kit.log("qg06 begun: " + (q == null ? "no — " + QuestStories.whyForTests(village) : q.title + " by " + q.giverName + "; " + q.offer));
        helper.assertTrue(q != null, "the story begun: " + QuestStories.whyForTests(village));
        boolean refusing = QuestStories.heldForTests(miner, level);
        Kit.log("qg06 the miner at " + miner.blockPosition().toShortString() + " refusing to go down: " + refusing + " (off work: " + miner.offWorkNow() + ")");
        if (!miner.offWorkNow()) helper.assertTrue(refusing, "a miner refuses to go down while the curse is on");
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        known(p, 5, foreman, old);
        Predicate<ItemStack> torch = s -> s.is(Items.TORCH);
        int torches0 = stock(level, village, torch);
        String took = QuestRun.accept(level, foreman, p);
        int torches1 = stock(level, village, torch), inHand = count(p, torch);
        Kit.log("qg06 taken -> " + took + " | torches in the stores " + torches0 + " -> " + torches1 + ", in hand " + inHand);
        helper.assertTrue(q.state == State.ACTIVE && inHand == 16 && torches0 - torches1 == 16, "the foreman's torches, out of the stores: "
            + torches0 + " -> " + torches1 + ", in hand " + inHand);
        int books0 = stock(level, village, s -> s.is(Items.BOOK)), coal0 = stock(level, village, s -> s.is(Items.COAL));
        String tale = QuestRun.talk(level, old, p, null);
        int journals = count(p, s -> s.is(McAssistantMod.MINERS_JOURNAL.get()) && questOf(s) == q.id);
        int books1 = stock(level, village, s -> s.is(Items.BOOK)), coal1 = stock(level, village, s -> s.is(Items.COAL));
        Kit.log("qg06 the old miner -> " + tale + " | journal " + journals + ", the stores' books " + books0 + " -> " + books1 + ", coal " + coal0 + " -> " + coal1);
        helper.assertTrue(journals == 1 && books0 - books1 == 1 && coal0 - coal1 == 1, "the old miner's journal, of the stores' book and coal");
        at(p, spawner.east(2));
        QuestRun.checkForTests(level, p);
        helper.assertTrue("seal".equals(q.current().key), "down at the deep level: " + steps(q));
        level.setBlockAndUpdate(spawner, Blocks.AIR.defaultBlockState());
        QuestRun.checkForTests(level, p);
        helper.assertTrue("light".equals(q.current().key), "the spawner broken: " + steps(q));
        for (int i = 0; i < 6; i++) level.setBlock(spawner.offset(-2 + (i % 3) * 2, 0, i < 3 ? -2 : 2), Blocks.TORCH.defaultBlockState(), 2);
        QuestRun.checkForTests(level, p);
        helper.assertTrue("mark".equals(q.current().key), "the deep level lit: " + steps(q));
        int treasury0 = Ledger.coins(village), coins0 = Market.coinsHeld(p);
        String marked = QuestRun.talk(level, foreman, p, "plaque");
        boolean plaque = false;
        for (Plaques.Plaque pl : Plaques.plaques(village)) plaque |= pl.site() == Plaques.Site.MEMORIAL && pl.lines()[0].contains("mine");
        boolean stillRefusing = QuestStories.heldForTests(miner, level);
        Kit.log("qg06 a plaque -> " + marked + " | plaque " + plaque + ", treasury " + treasury0 + " -> " + Ledger.coins(village) + ", player " + coins0 + " -> "
            + Market.coinsHeld(p) + ", still refusing " + stillRefusing + "; titles " + QuestBook.titles(p.getUUID(), village) + "; ending: " + q.ending);
        helper.assertTrue(q.state == State.DONE && plaque && !stillRefusing, "done: the plaque up, the miners back to work: " + q.state + " " + plaque + " " + stillRefusing);
        helper.assertTrue(treasury0 - Ledger.coins(village) == Market.coinsHeld(p) - coins0 && Market.coinsHeld(p) > coins0
            && QuestBook.titles(p.getUUID(), village).contains("Curse-lifter"), "paid out of the treasury, and a title");
        helper.succeed();
    }

    // ============================================================ qg07 The Stolen Heirloom: the main path

    /**
     * A family's heirloom (made of the stores' gold, paid for out of the family's purses) taken by a jealous neighbour:
     * the chest looked over, the neighbour and the son questioned, the neighbour accused, asked quietly for it back, the
     * heirloom carried home: the family pays and thinks the world of the player; the town strikes its medal for the
     * story, of the stores' gold.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg07_the_stolen_heirloom")
    public static void qg07_the_stolen_heirloom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos heart = heart(level, 972000, 40);
        List<VillageFolkEntity> folk = town(helper, heart, 4);
        Object[] setup = heirloomTown(helper, level, heart, folk);
        Quest q = (Quest) setup[0];
        VillageFolkEntity giver = folk.get(0), son = folk.get(1), neighbour = folk.get(2);
        UUID village = giver.ownerId();
        Predicate<ItemStack> heirloom = s -> (s.is(McAssistantMod.HEIRLOOM_RING.get()) || s.is(McAssistantMod.HEIRLOOM_LOCKET.get())) && questOf(s) == q.id;
        helper.assertTrue(neighbour.countCarried(heirloom) == 1, "the neighbour has it: " + neighbour.countCarried(heirloom));
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        known(p, 5, giver, son, neighbour);
        QuestRun.accept(level, giver, p);
        at(p, giver.blockPosition());
        QuestRun.checkForTests(level, p);
        Kit.log("qg07 the chest: " + steps(q));
        Kit.log("qg07 the neighbour -> " + QuestRun.talk(level, neighbour, p, null));
        Kit.log("qg07 the son -> " + QuestRun.talk(level, son, p, null));
        helper.assertTrue(q.current() != null && q.current().key.startsWith("accuse"), "the suspects questioned: " + steps(q));
        Kit.log("qg07 accused -> " + QuestRun.talk(level, giver, p, "neighbour"));
        String back = QuestRun.talk(level, neighbour, p, "quiet");
        Kit.log("qg07 asked quietly -> " + back + " | in hand " + count(p, heirloom));
        helper.assertTrue(count(p, heirloom) == 1 && neighbour.countCarried(heirloom) == 0, "the heirloom given back quietly");
        int purse0 = giver.purse() + son.purse(), coins0 = Market.coinsHeld(p), aff0 = giver.persona().affinity(p.getUUID());
        String home = QuestRun.talk(level, giver, p, null);
        int medals = count(p, s -> s.is(McAssistantMod.TOWN_MEDAL.get()));
        Kit.log("qg07 home -> " + home + " | family purses " + purse0 + " -> " + (giver.purse() + son.purse()) + ", player " + coins0 + " -> " + Market.coinsHeld(p)
            + ", warmth " + aff0 + " -> " + giver.persona().affinity(p.getUUID()) + ", medals " + medals + " (gold in the stores " + stock(level, village, s -> s.is(Items.GOLD_INGOT))
            + "); ending: " + q.ending);
        helper.assertTrue(q.state == State.DONE && giver.countCarried(heirloom) == 1, "done, the heirloom home: " + q.state);
        int paid = QuestRewards.num(q.flag("paid"));
        helper.assertTrue(paid > 0 && Market.coinsHeld(p) - coins0 == paid && purse0 - (giver.purse() + son.purse()) == paid && giver.persona().affinity(p.getUUID()) > aff0,
            "paid out of the family's purses, and warmer: " + paid + " paid");
        helper.assertTrue(medals == 1 && QuestBook.honoured(p.getUUID(), village, "medal"), "the town's medal, struck of the stores' gold: " + medals);
        helper.succeed();
    }

    /** The family (the first two of these, the second the first's son), a neighbour, the stores, and the heirloom taken by the neighbour. */
    private static Object[] heirloomTown(GameTestHelper helper, ServerLevel level, BlockPos heart, List<VillageFolkEntity> folk) {
        VillageFolkEntity giver = folk.get(0), son = folk.get(1), neighbour = folk.get(2);
        UUID village = giver.ownerId();
        son.parentIds().add(giver.getUUID());
        giver.earn(20);
        neighbour.earn(5);
        // The mint's twenty-seven gold bars and the fishers' two string are kept back from any maker (Bench): more than
        // that, for the heirloom (a bar's nuggets) and, at the story's end, the medal (a bar on a string).
        Container box = stores(level, heart, 6, 6, new ItemStack(Items.GOLD_INGOT, 32), new ItemStack(Items.EMERALD, 1), new ItemStack(Items.AMETHYST_SHARD, 1),
            new ItemStack(Items.STRING, 6), new ItemStack(Items.GLASS_PANE, 1), new ItemStack(Items.CRAFTING_TABLE, 1));
        Villages.forgetStores(village);
        int gold0 = stock(level, village, s -> s.is(Items.GOLD_INGOT)), treasury0 = Ledger.coins(village), purses0 = giver.purse() + son.purse();
        Quest q = QuestStories.beginForTests(level, Villages.get(village), "heirloom", Map.of("giver", giver, "child", son, "neighbour", neighbour, "truth", "neighbour"));
        Kit.log("heirloom begun: " + (q == null ? "no — " + QuestStories.whyForTests(village) : q.title + ": " + q.flag("owner") + " (" + q.flag("word") + ") in "
            + q.flag("hidden") + "; gold " + gold0 + " -> " + stock(level, village, s -> s.is(Items.GOLD_INGOT)) + ", treasury " + treasury0 + " -> " + Ledger.coins(village)
            + ", family purses " + purses0 + " -> " + (giver.purse() + son.purse())));
        helper.assertTrue(q != null, "the story begun: " + QuestStories.whyForTests(village));
        helper.assertTrue(stock(level, village, s -> s.is(Items.GOLD_INGOT)) < gold0 && Ledger.coins(village) > treasury0,
            "the heirloom made of the stores' gold, and paid for out of the family's purses into the treasury");
        return new Object[]{ q, box };
    }

    // ============================================================ qg08 The Stolen Heirloom: a branch (the wrong one accused, then shamed)

    /**
     * The same theft, the son accused first, wrongly: the son does not forgive the player, and son and father fall out;
     * then the neighbour accused, rightly, and shamed before the town (the whole town the colder to it); home: done, and
     * the ending says somebody was wrongly accused first.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg08_the_heirloom_wrongly_accused")
    public static void qg08_the_heirloom_wrongly_accused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos heart = heart(level, 974000, 40);
        List<VillageFolkEntity> folk = town(helper, heart, 4);
        Quest q = (Quest) heirloomTown(helper, level, heart, folk)[0];
        VillageFolkEntity giver = folk.get(0), son = folk.get(1), neighbour = folk.get(2), other = folk.get(3);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        known(p, 5, giver, son, neighbour, other);
        QuestRun.accept(level, giver, p);
        at(p, giver.blockPosition());
        QuestRun.checkForTests(level, p);
        QuestRun.talk(level, neighbour, p, null);
        QuestRun.talk(level, son, p, null);
        int sonToMe0 = son.persona().affinity(p.getUUID()), fatherToSon0 = giver.life().affinity(son.getUUID()), sonToFather0 = son.life().affinity(giver.getUUID());
        String wrong = QuestRun.talk(level, giver, p, "child");
        int sonToMe1 = son.persona().affinity(p.getUUID()), fatherToSon1 = giver.life().affinity(son.getUUID()), sonToFather1 = son.life().affinity(giver.getUUID());
        Kit.log("qg08 the son accused, wrongly -> " + wrong + " | the son of the player " + sonToMe0 + " -> " + sonToMe1 + ", father to son " + fatherToSon0 + " -> "
            + fatherToSon1 + ", son to father " + sonToFather0 + " -> " + sonToFather1 + "; " + steps(q));
        helper.assertTrue(sonToMe0 - sonToMe1 >= 25 && fatherToSon0 - fatherToSon1 >= 30 && sonToFather0 - sonToFather1 >= 30,
            "a false accusation lasts: " + sonToMe0 + " -> " + sonToMe1 + ", " + fatherToSon0 + " -> " + fatherToSon1);
        QuestBook.Step again = q.current();
        helper.assertTrue(again != null && again.key.startsWith("accuse") && again.optionLabel("child") == null && again.optionLabel("neighbour") != null,
            "think again, the son struck off: " + steps(q));
        QuestRun.talk(level, giver, p, "neighbour");
        int townToHer0 = other.life().affinity(neighbour.getUUID());
        String shamed = QuestRun.talk(level, neighbour, p, "expose");
        int townToHer1 = other.life().affinity(neighbour.getUUID());
        Kit.log("qg08 the neighbour shamed -> " + shamed + " | the town to the neighbour " + townToHer0 + " -> " + townToHer1);
        helper.assertTrue(townToHer0 - townToHer1 >= 15, "shamed before the town: " + townToHer0 + " -> " + townToHer1);
        QuestRun.talk(level, giver, p, null);
        Kit.log("qg08 home: " + q.state + "; outcome " + q.outcome + "; ending: " + q.ending);
        helper.assertTrue(q.state == State.DONE && q.outcome.contains("wrongly accused") && q.ending.contains("wrongly accused"),
            "done, and the wrong done remembered: " + q.outcome);
        helper.succeed();
    }

    // ============================================================ qg09 The Smugglers' Cave: a cut taken, and it comes out

    /**
     * The smugglers' ring offered a cut: taken, out of the ringleader's and the accomplice's purses (counted), the
     * ledger burnt, the storekeeper told nothing was found, and no pay from the town. Then it comes out: the town
     * thinks the worse of the player, the storekeeper most, and the chronicle says so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg09_a_cut_and_it_comes_out")
    public static void qg09_a_cut_and_it_comes_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 14000);
        BlockPos heart = heart(level, 976000, 60);
        List<VillageFolkEntity> folk = town(helper, heart, 6);
        VillageFolkEntity keeper = folk.get(0), elder = folk.get(1), ring = folk.get(2), acc = folk.get(3), gossip = folk.get(4);
        UUID village = keeper.ownerId();
        keeper.setJob(StationTask.STORE);
        Villages.electElder(village, elder, QuestRun.day(level));
        ring.earn(40);
        // String: the fishers' and the smith's two are kept back from any maker (Bench), so four for the ledger's one.
        stores(level, heart, 6, 6, new ItemStack(Items.IRON_INGOT, 24), new ItemStack(Items.PAPER, 6), new ItemStack(Items.STRING, 4),
            new ItemStack(Items.INK_SAC, 2), new ItemStack(Items.CHEST, 1), new ItemStack(Items.CRAFTING_TABLE, 1));
        Villages.forgetStores(village);
        BlockPos camp = hollow(level, heart, -40, 20);
        Quest q = QuestStories.beginForTests(level, Villages.get(village), "smugglers", Map.of("keeper", keeper, "elder", elder, "ring", ring, "accomplice", acc,
            "gossip", gossip, "camp", camp));
        helper.assertTrue(q != null, "begun: " + QuestStories.whyForTests(village));
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        known(p, 20, folk.toArray(new VillageFolkEntity[0]));
        QuestRun.accept(level, keeper, p);
        QuestRun.talk(level, gossip, p, null);
        QuestRun.talk(level, acc, p, null);
        at(p, QuestStories.place(q, "mouth"));
        QuestRun.checkForTests(level, p);
        at(p, camp);
        QuestRun.checkForTests(level, p);
        Container cache = (Container) level.getBlockEntity(camp);
        for (int i = 0; i < cache.getContainerSize(); i++) {
            if (cache.getItem(i).is(McAssistantMod.SMUGGLERS_LEDGER.get())) {
                p.getInventory().add(cache.getItem(i).copy());
                cache.setItem(i, ItemStack.EMPTY);
            }
        }
        QuestRun.checkForTests(level, p);
        helper.assertTrue("confront".equals(q.current().key), "at the choice: " + steps(q));
        int purses0 = ring.purse() + acc.purse(), coins0 = Market.coinsHeld(p);
        String cut = QuestRun.talk(level, acc, p, "cut");
        int purses1 = ring.purse() + acc.purse(), coins1 = Market.coinsHeld(p);
        Kit.log("qg09 a cut -> " + cut + " | their purses " + purses0 + " -> " + purses1 + ", the player " + coins0 + " -> " + coins1 + ", ledgers left "
            + count(p, s -> s.is(McAssistantMod.SMUGGLERS_LEDGER.get())));
        helper.assertTrue(coins1 > coins0 && purses0 - purses1 == coins1 - coins0 && count(p, s -> s.is(McAssistantMod.SMUGGLERS_LEDGER.get())) == 0,
            "the cut out of their purses, the ledger burnt");
        int treasury0 = Ledger.coins(village);
        String nothing = QuestRun.talk(level, keeper, p, null);
        Kit.log("qg09 told the storekeeper -> " + nothing + " | treasury " + treasury0 + " -> " + Ledger.coins(village) + "; " + q.state + " " + q.outcome);
        helper.assertTrue(q.state == State.DONE && Ledger.coins(village) == treasury0, "done, and the town pays nothing for nothing found");
        int keeper0 = keeper.persona().affinity(p.getUUID()), town0 = gossip.persona().affinity(p.getUUID());
        QuestStories.comesOutForTests(level, q);
        int keeper1 = keeper.persona().affinity(p.getUUID()), town1 = gossip.persona().affinity(p.getUUID());
        Kit.log("qg09 it came out | the storekeeper " + keeper0 + " -> " + keeper1 + ", the gossip " + town0 + " -> " + town1 + "; " + q.outcome);
        helper.assertTrue(keeper0 - keeper1 >= 25 && town0 - town1 >= 8 && chronicle(village).contains("it came out that"), "it came out: the town the colder");
        helper.succeed();
    }

    // ============================================================ qg10 kept with the world; the journal; the board's postings; the key

    /**
     * A quest taken and half done, written out and read back as a restart would: the same quest, its steps, its flags
     * and its player. The journal and /village quests say it. A posting taken off the quest board appears in the
     * journal, and claimed is done there too. The Key to the Town, carried, takes a tenth off what the town sells.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "qg_qg10_kept_the_journal_the_board")
    public static void qg10_kept_the_journal_the_board(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        time(level, 2000);
        BlockPos heart = heart(level, 978000, 40);
        List<VillageFolkEntity> folk = town(helper, heart, 4);
        VillageFolkEntity sick = folk.get(0), partner = folk.get(1);
        UUID village = sick.ownerId();
        Villages.Village v = Villages.get(village);
        sick.life().partnerWith(partner.getUUID(), partner.displayNameCap());
        partner.life().partnerWith(sick.getUUID(), sick.displayNameCap());
        // The mint's twenty-seven gold bars are kept back from any maker (Bench): the key's two bars and a nugget besides.
        stores(level, heart, 6, 6, new ItemStack(Items.BREAD, 4), new ItemStack(Items.GOLD_INGOT, 32), new ItemStack(Items.CRAFTING_TABLE, 1));
        Villages.forgetStores(village);
        sick.setHealth(sick.getMaxHealth() * 0.4F);
        partner.earn(10);
        Quest q = QuestMaker.offerForTests(level, v, "healer");
        helper.assertTrue(q != null, "an offer");
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        QuestRun.accept(level, partner, p);
        p.getInventory().add(new ItemStack(Items.HONEY_BOTTLE, 1));
        QuestRun.talk(level, partner, p, null);
        int before = q.steps.get(0).progress;
        int back = QuestBook.roundTripForTests();
        Quest again = QuestBook.get(q.id);
        Kit.log("qg10 kept: " + back + " quests back; #" + q.id + " " + (again == null ? "lost" : again.state + " " + again.title + " by " + again.giverName + " for "
            + again.playerName + ", progress " + again.steps.get(0).progress + "/" + again.steps.get(0).count + ", flags " + again.flags));
        helper.assertTrue(again != null && again != q && again.state == State.ACTIVE && p.getUUID().equals(again.player) && again.steps.get(0).progress == before
            && before == 1 && again.flag("sick").equals(sick.getStringUUID()), "the same quest after the restart");
        String journal = QuestRun.journal(p, QuestRun.day(level));
        List<String> said = QuestRun.describe(p);
        Kit.log("qg10 /village quests: " + String.join(" / ", said));
        helper.assertTrue(journal.contains(again.title) && journal.contains("Bring") && String.join(" ", said).contains(again.title), "the journal and the command say it");
        // A posting off the quest board, in the journal; claimed, done there too.
        Quests.Posting post = Quests.samples(QuestRun.day(level)).get(0);
        QuestRun.fromBoard(level, v, p, post);
        Quest board = null;
        for (Quest x : QuestBook.active(p.getUUID())) if (x.kind == QuestBook.Kind.BOARD) board = x;
        helper.assertTrue(board != null && board.title.toLowerCase().contains("iron"), "the posting in the journal: " + (board == null ? "none" : board.title));
        QuestRun.boardDone(level, p, post);
        helper.assertTrue(board.state == State.DONE, "claimed at the board, done in the journal: " + board.state);
        // The Key to the Town: struck of the stores' gold, and a tenth off what the town sells, carried.
        int price0 = QuestRewards.discountFor(village, p, 50);
        boolean given = QuestRewardsAccess.award(level, village, p);
        int price1 = QuestRewards.discountFor(village, p, 50);
        Kit.log("qg10 the key: given " + given + ", keys in hand " + count(p, s -> s.is(McAssistantMod.TOWN_KEY.get())) + "; 50 coins of goods cost " + price0 + " -> " + price1);
        helper.assertTrue(given && price0 == 50 && price1 == 45, "the key's tenth off: " + price0 + " -> " + price1);
        helper.succeed();
    }

    /** The key, awarded as the town would (QuestRewards.award is the town's own; the tests reach it through its ForTests door). */
    private static final class QuestRewardsAccess {
        static boolean award(ServerLevel level, UUID village, Player p) {
            return QuestRewards.awardForTests(level, village, p, "key");
        }
    }
}
