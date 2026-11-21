package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.CivicLand;
import com.jrpetty.mcassistant.entity.Elections;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.KnightManors;
import com.jrpetty.mcassistant.entity.Knights;
import com.jrpetty.mcassistant.entity.Retinue;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.TaxBook;
import com.jrpetty.mcassistant.entity.TaxUnrest;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [civic-land] Taxes set by the council and how the town takes them (TaxBook, TaxUnrest); knighthood, land, the manor
 * and the retinue (Knights, KnightManors, Retinue).
 *
 * <p>Each on its own ground (x 2220000 to 2238000, z 70000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CivicLandGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    /** A town of so many folk about this spot, all of one village, each at a trade (some at the watch), the batch live. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int n, int guards) {
        Kit.reset(level);
        CivicLand.liveForTests(true);
        Kit.hold(level, x, Z, 96);
        Kit.prepare(level, x, Z, 96);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3 + (i % 4) * 2, Z - 2 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(i < guards ? StationTask.GUARD : i % 2 == 0 ? StationTask.FARM : StationTask.WOOD);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        for (VillageFolkEntity f : folk) helper.assertTrue(village != null && village.equals(f.ownerId()), "all of one village");
        return folk;
    }

    /** A chest of the town's stores beside its heart, holding these. */
    private static Container stores(ServerLevel level, UUID village, int x, ItemStack... goods) {
        BlockPos at = Kit.surface(level, x + 6, Z + 6);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        c.setChanged();
        Villages.forgetStores(village);
        return c;
    }

    /** The town's arms a plain white field (and its motto chosen), so a knight's banner wants only a white banner and a yellow dye. */
    private static void plainArms(UUID village) {
        Ledger.note(village, "culture.banner", "white|");
        Ledger.note(village, "culture.motto", "Steady and true");
    }

    private static int count(Container c, java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static int carried(Player p, Item it) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(it)) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    /** A player the whole town knows and likes, a citizen of it. */
    private static ServerPlayer citizen(GameTestHelper helper, ServerLevel level, UUID village, List<VillageFolkEntity> folk, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        String name = p.getName().getString();
        Ledger.addCitizen(village, p.getUUID(), name);
        for (VillageFolkEntity f : folk) {
            f.persona().met(p.getUUID());
            f.persona().feelFor(p.getUUID(), name, 45);
        }
        Standing.stir(village, p.getUUID());
        return p;
    }

    // ============================================================ cl01 tax day

    /**
     * Tax day with a head tax of two coins: the collector chosen (not one of the watch), every hand at a trade with coin
     * in its purse pays two into the treasury; a poor folk with nothing is let off while the treasury holds three days'
     * wages, and with the treasury empty it owes the two instead (arrears), taken first the next tax day once it has coin.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl_cl01_tax_day")
    public static void cl01_tax_day_collects_lets_off_and_owes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2220000;
        level.setDayTime(5 * 24000L + 3000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 6, 1);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        long day = level.getDayTime() / 24000L;
        TaxBook.setForTests(id, TaxBook.Lever.HEAD, 2);
        folk.get(1).setJob(StationTask.SHOP);                           // shopkeepers: the collector is one of them
        folk.get(2).setJob(StationTask.SHOP);
        VillageFolkEntity poor = folk.get(5);
        for (VillageFolkEntity f : folk) if (f != poor) f.earn(10);
        Ledger.addCoins(id, 500);
        int before = Ledger.coins(id);
        int[] purses = new int[folk.size()];
        for (int i = 0; i < folk.size(); i++) purses[i] = folk.get(i).purse();
        TaxBook.collectNowForTests(level, v);
        UUID collector = TaxBook.collectorForTests(id);
        helper.assertTrue(collector != null, "a collector is chosen");
        VillageFolkEntity c = null;
        for (VillageFolkEntity f : folk) if (f.getUUID().equals(collector)) c = f;
        helper.assertTrue(c != null && c.stationTask() != StationTask.GUARD, "the collector is one of the town, not of the watch: " + (c == null ? "-" : c.stationTask()));
        int paid = 0;
        for (int i = 0; i < folk.size(); i++) {
            VillageFolkEntity f = folk.get(i);
            if (f == poor) continue;
            helper.assertTrue(purses[i] - f.purse() == 2, f.displayNameCap() + " paid two coins: " + purses[i] + " -> " + f.purse());
            paid += 2;
        }
        helper.assertTrue(poor.purse() == 0 && TaxBook.arrears(id, poor.getUUID()) == 0, "the poor folk is let off while the treasury is full");
        helper.assertTrue(Ledger.coins(id) - before == paid, "the treasury took every coin paid: " + (Ledger.coins(id) - before) + " of " + paid);
        helper.assertTrue(TaxBook.bookedForTests(id, day, "head") == paid, "the books have the day's head tax: " + TaxBook.bookedForTests(id, day, "head"));
        // The treasury emptied: no mercy this time. The poor folk owes it.
        Ledger.takeCoins(id, Ledger.coins(id));
        TaxBook.collectNowForTests(level, v);
        helper.assertTrue(TaxBook.arrears(id, poor.getUUID()) == 2, "with an empty treasury the poor folk owes its two coins: " + TaxBook.arrears(id, poor.getUUID()));
        // With coin at last, it pays this week's and what it owes.
        poor.earn(9);
        TaxBook.collectNowForTests(level, v);
        helper.assertTrue(poor.purse() == 5 && TaxBook.arrears(id, poor.getUUID()) == 0, "the arrears are taken with the week's tax: purse "
            + poor.purse() + ", owing " + TaxBook.arrears(id, poor.getUUID()));
        Kit.log("cl01 head tax: " + paid + " coins from " + (folk.size() - 1) + " folk; collector " + c.displayNameCap() + " (" + c.stationTask()
            + "); poor folk let off, then owing, then paid up");
        helper.succeed();
    }

    // ============================================================ cl02 the council's vote

    /**
     * The council's sitting on the taxes: with an empty treasury it is moved that the head tax be raised; the motion is
     * read at the council's meeting, councillors say how they vote and why, and the count carries it or loses it (the
     * rate moves exactly when it carries). With the town angry and a heavy head tax, it is moved that it come down.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl_cl02_council_vote")
    public static void cl02_council_votes_on_the_taxes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2222000;
        level.setDayTime(3 * 24000L + 5000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 7, 1);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        TaxUnrest.dailyForTests(level, v);
        Ledger.takeCoins(id, Ledger.coins(id));
        String motion = TaxBook.motionForTests(level, v);
        helper.assertTrue(motion.contains("head tax be raised"), "an empty treasury moves a rise in the head tax: \"" + motion + "\"");
        List<String> lines = TaxBook.councilLinesForTests(level, id);
        String read = String.join(" / ", lines);
        helper.assertTrue(read.contains("On the taxes: it is moved") && (read.contains("Carried") || read.contains("Lost")),
            "the meeting reads the motion and the count: " + read);
        String vote = TaxBook.lastVoteForTests(id);
        helper.assertTrue(!vote.isEmpty(), "the vote is in the books");
        int head = TaxBook.rate(id, TaxBook.Lever.HEAD);
        helper.assertTrue(vote.contains("carried") == (head == 1), "the head tax moved exactly when the motion carried: " + vote + ", head " + head);
        String[] counts = vote.split(" ")[0].split("-");
        int ayes = Integer.parseInt(counts[0]), nays = Integer.parseInt(counts[1]);
        helper.assertTrue(ayes + nays >= 5, "the council and the leader all voted: " + vote);
        // Angry, with a heavy head tax: moved down.
        TaxBook.setForTests(id, TaxBook.Lever.HEAD, 3);
        TaxUnrest.setForTests(id, 70);
        String down = TaxBook.motionForTests(level, v);
        helper.assertTrue(down.contains("head tax be lowered to 2 coins"), "an angry town moves the head tax down: \"" + down + "\"");
        String sat = TaxBook.sit(level, v, level.getDayTime() / 24000L, "the council");
        helper.assertTrue(sat.contains("lowered"), "the quiet sitting votes on it: " + sat);
        Kit.log("cl02 " + read + " || " + vote + " || then: " + sat);
        helper.succeed();
    }

    // ============================================================ cl03 a protest

    /**
     * Unrest past sixty: the angry (poor, in arrears) gather before the town's heart with signs out of the stores (made of
     * its planks and a stick when there are not enough), chant, their ringleader speaks, the leader answers by the
     * council's vote, and at the end the signs go back into the stores and the answer is in the books.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "cl_cl03_protest")
    public static void cl03_protest_at_the_hall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2224000;
        level.setDayTime(4 * 24000L + 9200L);
        List<VillageFolkEntity> folk = town(helper, level, x, 8, 0);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Container chest = stores(level, id, x, new ItemStack(Items.OAK_SIGN, 2), new ItemStack(Items.OAK_PLANKS, 12), new ItemStack(Items.STICK, 4));
        TaxUnrest.dailyForTests(level, v);
        TaxBook.setForTests(id, TaxBook.Lever.HEAD, 3);
        for (VillageFolkEntity f : folk) TaxBook.arrearsForTests(id, f.getUUID(), 4);
        TaxUnrest.setForTests(id, 72);
        int angry = TaxUnrest.angryForTests(id).size();
        helper.assertTrue(angry >= 3, "the poor in arrears are angry: " + angry);
        String said = TaxUnrest.protestNow(level, v);
        int[] p0 = Assemblies.progress(id);
        helper.assertTrue(p0 != null && Assemblies.debug(id).startsWith("PROTEST"), "the protest gathers: " + said + " / " + Assemblies.debug(id));
        int signs = 0;
        for (VillageFolkEntity f : folk) if (TaxUnrest.holdsSign(f)) signs++;
        helper.assertTrue(signs >= 3, "signs in the protesters' hands, out of the stores (two there, three more made): " + signs);
        int signsWere = count(chest, s -> s.is(ItemTags.SIGNS)) + signs;
        final int handed = signs;
        final int[] seen = { 0 };
        helper.onEachTick(() -> {
            int[] p = Assemblies.progress(id);
            if (p != null) {
                seen[0] = Math.max(seen[0], p[3]);
                if (p[0] < 5) return;                                   // till it disperses (its close has been held)
            }
            String answer = com.jrpetty.mcassistant.entity.CivicLand.report(level, v).getCompound("unrest").getString("answer");
            helper.assertTrue(!answer.isEmpty(), "the council answered the protest");
            int held = 0;
            for (VillageFolkEntity f : folk) if (TaxUnrest.holdsSign(f) || f.getOffhandItem().is(ItemTags.SIGNS) || f.getMainHandItem().is(ItemTags.SIGNS)) held++;
            helper.assertTrue(held == 0, "every sign put down: " + held + " still held");
            int back = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(ItemTags.SIGNS));
            helper.assertTrue(back >= signsWere - 1, "the signs back in the stores: " + back + " of " + signsWere);
            String vote = TaxBook.lastVoteForTests(id);
            helper.assertTrue(!vote.isEmpty(), "the council's answer was a vote: " + vote);
            helper.assertTrue(vote.contains("carried") == (TaxBook.rate(id, TaxBook.Lever.HEAD) == 2), "gave way exactly when it carried: " + vote);
            Kit.log("cl03 protest: " + angry + " angry, " + handed + " signs, " + seen[0] + " lines read; " + answer + " (" + vote + ")");
            helper.succeed();
        });
    }

    // ============================================================ cl04 the revolt

    /**
     * A tax revolt: the angry strike (off shift, on the picket), refuse the collector to its face and chase it off; with
     * the collector chased, the next tax day the watch walks the round and they pay. Three days in, the leader is
     * challenged (an election called for two days on), the incumbent judged for it at the polls; the council giving
     * way ends it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl_cl04_revolt")
    public static void cl04_revolt_strikes_refuses_and_challenges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2226000;
        level.setDayTime(6 * 24000L + 3000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 9, 3);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        TaxUnrest.dailyForTests(level, v);
        TaxBook.setForTests(id, TaxBook.Lever.HEAD, 2);
        for (VillageFolkEntity f : folk) if (f.stationTask() != StationTask.GUARD) TaxBook.arrearsForTests(id, f.getUUID(), 3);
        String rose = TaxUnrest.revoltNow(level, v);
        helper.assertTrue(TaxUnrest.revolt(id), "the town has risen: " + rose);
        List<VillageFolkEntity> angry = TaxUnrest.angryForTests(id);
        helper.assertTrue(angry.size() >= 3, "three at least refuse: " + angry.size());
        // The strike.
        TaxUnrest.strikeTodayForTests(level, id);
        VillageFolkEntity striker = angry.get(0);
        helper.assertTrue(TaxUnrest.striking(striker) && !striker.onShift(), "a striker is off its work: striking " + TaxUnrest.striking(striker));
        // The collector refused and chased off.
        for (VillageFolkEntity f : folk) f.earn(20);
        TaxBook.collectNowForTests(level, v);
        int[] r = TaxBook.roundForTests(id);
        helper.assertTrue(r[4] == 1, "the collector was chased off: " + java.util.Arrays.toString(r));
        // The next tax day the watch walks the round: they pay, with bad grace.
        TaxBook.collectNowForTests(level, v);
        int[] r2 = TaxBook.roundForTests(id);
        helper.assertTrue(r2[4] == 0 && r2[1] > 0, "with the watch along, the town pays: " + java.util.Arrays.toString(r2));
        // The leader challenged: an early election.
        long day = level.getDayTime() / 24000L;
        TaxUnrest.challengeForTests(level, v);
        long vote = TaxUnrest.electionForTests(id);
        helper.assertTrue(vote == day + 2, "an election called for two days on: " + vote + " (today " + day + ")");
        UUID elder = Villages.elder(id);
        if (elder != null) {
            Elections.Candidate c = new Elections.Candidate(elder, "the leader", Values.Value.FOOD, Values.Value.HOMES, "more of the same");
            double lean = TaxUnrest.electionLean(level, angry.get(0), c);
            helper.assertTrue(lean < 0, "the one who led through it is judged for it: " + lean);
        }
        // The council gives way: it is over.
        TaxUnrest.setForTests(id, 70);
        TaxUnrest.councilGaveWayForTests(level, v);
        helper.assertTrue(!TaxUnrest.revolt(id), "the revolt ends when the council gives way");
        Kit.log("cl04 revolt: " + angry.size() + " angry; chased " + java.util.Arrays.toString(r) + "; guarded " + java.util.Arrays.toString(r2)
            + "; election day " + vote);
        helper.succeed();
    }

    // ============================================================ cl05 the toll and what heavy taxes do

    /**
     * The market toll takes its share of a sale into the treasury (a part of a coin carried to the next); heavy taxes make
     * folk keep their coin some days, sour the mood of those in arrears and cost the town its contentment.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl_cl05_toll_and_spirits")
    public static void cl05_toll_spending_mood_and_contentment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2228000;
        level.setDayTime(2 * 24000L + 4000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 8, 0);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        TaxUnrest.dailyForTests(level, v);
        long day = level.getDayTime() / 24000L;
        int before = Ledger.coins(id);
        TaxBook.setForTests(id, TaxBook.Lever.TOLL, 2);
        int t1 = TaxBook.toll(id, 50), t2 = TaxBook.toll(id, 5), t3 = TaxBook.toll(id, 5);
        helper.assertTrue(t1 == 5 && t2 + t3 == 1, "a tenth of the takings, the odd part carried: " + t1 + ", " + t2 + ", " + t3);
        helper.assertTrue(Ledger.coins(id) - before == 6 && TaxBook.bookedForTests(id, day, "toll") == 6, "the toll into the treasury and the books");
        // Heavy taxes: the coin kept.
        TaxBook.setForTests(id, TaxBook.Lever.HEAD, 3);
        int held = 0;
        for (VillageFolkEntity f : folk) {
            TaxBook.arrearsForTests(id, f.getUUID(), 2);
            if (TaxBook.holdsBack(f)) held++;
        }
        helper.assertTrue(held >= 1, "with the head tax at its heaviest some keep their coin: " + held + " of " + folk.size()
            + " (burden " + TaxUnrest.burden(id) + ")");
        List<Object[]> why = new ArrayList<>();
        int m = TaxUnrest.mood(folk.get(1), day, 50, why);
        helper.assertTrue(m < 50 && !why.isEmpty() && "arrears".equals(why.get(why.size() - 1)[0]), "owing back taxes sours its mood: " + m);
        TaxUnrest.setForTests(id, 70);
        List<String> good = new ArrayList<>(), bad = new ArrayList<>();
        int c = TaxUnrest.contentment(id, good, bad);
        helper.assertTrue(c <= -8 && bad.contains("the taxes are heavy"), "an angry town is the less content: " + c + " " + bad);
        TaxUnrest.dailyForTests(level, v);
        helper.assertTrue(!TaxUnrest.whyForTests(id).isEmpty(), "the reasons are in the books: " + TaxUnrest.whyForTests(id));
        Kit.log("cl05 toll " + t1 + "+" + t2 + "+" + t3 + "; " + held + " kept their coin; mood " + m + "; contentment " + c + " " + bad
            + "; why " + TaxUnrest.whyForTests(id));
        helper.succeed();
    }

    // ============================================================ cl06 a citizen's taxes

    /**
     * A citizen owes the head tax on tax day, is told so, and the talk screen offers to pay; saying "I'd like to pay my
     * taxes" to any folk pays it out of the coin it carries into the treasury, and counts toward its service.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl_cl06_citizen_pays")
    public static void cl06_citizen_owes_and_pays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2230000;
        level.setDayTime(3 * 24000L + 4000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 5, 0);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        ServerPlayer p = citizen(helper, level, id, folk, Kit.surface(level, x, Z + 2));
        try {
            TaxBook.setForTests(id, TaxBook.Lever.HEAD, 2);
            TaxBook.collectNowForTests(level, v);
            helper.assertTrue(TaxBook.owedBy(id, p.getUUID()) == 2, "the citizen owes the head tax: " + TaxBook.owedBy(id, p.getUUID()));
            helper.assertTrue(FolkTalk.talkContext(folk.get(1), p).contains(" taxowed"), "the talk screen offers to pay");
            p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 5));
            int before = Ledger.coins(id);
            String said = FolkTalk.answer(folk.get(1), p, TalkTopic.SAY, "I'd like to pay my taxes");
            helper.assertTrue(TaxBook.owedBy(id, p.getUUID()) == 0 && carried(p, McAssistantMod.VILLAGE_COIN.get()) == 3, "paid: " + said);
            helper.assertTrue(Ledger.coins(id) - before == 2, "into the treasury");
            helper.assertTrue(Knights.service(id, p.getUUID()) >= 2, "taxes paid count toward its service: " + Knights.service(id, p.getUUID()));
            String what = FolkTalk.answer(folk.get(2), p, TalkTopic.SAY, "What are the taxes here?");
            helper.assertTrue(what.contains("head tax"), "the taxes, asked about: " + what);
            Kit.log("cl06 paid: \"" + said + "\" / asked: \"" + what + "\"");
        } finally {
            Kit.noLeftoverPlayers(level);
        }
        helper.succeed();
    }

    // ============================================================ cl07 the knighting

    /**
     * A citizen whose service reaches a hundred is chosen in the morning, and knighted at dusk before the town: the bell,
     * the crowd, the leader's words; at the dubbing it is a knight, its name in chat carries the title, the land at the
     * town's edge is granted, the manor goes on the builders' list, two of the watch are named its retinue, and the
     * banner (the town's arms with a gold border) is woven out of the stores and put in its hands.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "cl_cl07_knighting")
    public static void cl07_knighted_before_the_town(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2232000;
        level.setDayTime(5 * 24000L + 11800L);
        List<VillageFolkEntity> folk = town(helper, level, x, 8, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.WHITE_BANNER, 2), new ItemStack(Items.YELLOW_DYE, 2));
        plainArms(id);                                                  // the town's arms a plain white field: the knight's, with the gold border
        ServerPlayer p = citizen(helper, level, id, folk, v.centre());
        String name = p.getName().getString();
        Knights.serveForTests(id, p.getUUID(), name, "q", 9);
        helper.assertTrue(Knights.service(id, p.getUUID()) >= Knights.SERVICE, "a hundred of service: " + Knights.service(id, p.getUUID()));
        Knights.morningForTests(level, v);
        helper.assertTrue(Knights.chosenForTests(id, p.getUUID()), "chosen for knighthood in the morning");
        boolean began = Knights.ceremonyForTests(level, v, p.getUUID(), name);
        helper.assertTrue(began && Assemblies.debug(id).startsWith("KNIGHTING"), "the town gathers: " + Assemblies.debug(id));
        helper.onEachTick(() -> {
            if (!Knights.isKnight(id, p.getUUID())) return;
            try {
                helper.assertTrue(p.getDisplayName().getString().startsWith("Knight "), "the title in its name: " + p.getDisplayName().getString());
                BlockPos plot = KnightManors.plotForTests(id, p.getUUID());
                helper.assertTrue(plot != null, "land granted at the edge");
                double out = Math.sqrt(plot.distSqr(new BlockPos(v.centre().getX(), plot.getY(), v.centre().getZ())));
                helper.assertTrue(out >= 20, "out at the town's edge: " + (int) out);
                helper.assertTrue("wanted".equals(KnightManors.manorForTests(id, p.getUUID())) && Villages.projectsWanted(id).contains(KnightManors.STRUCTURE),
                    "the manor is on the builders' list: " + Villages.projectsWanted(id));
                helper.assertTrue(Retinue.size(id, p.getUUID()) == 2, "two of the watch in its retinue (four guards, two kept home): " + Retinue.size(id, p.getUUID()));
                helper.assertTrue(carried(p, Items.WHITE_BANNER) == 1, "its banner in its hands");
                Kit.log("cl07 knighted: " + p.getDisplayName().getString() + "; land at " + plot.getX() + "," + plot.getZ() + " (" + (int) out
                    + " out); retinue " + Retinue.size(id, p.getUUID()) + "; " + Assemblies.debug(id));
            } finally {
                Kit.noLeftoverPlayers(level);
            }
            helper.succeed();
        });
    }

    // ============================================================ cl08 the manor and the retinue

    /**
     * The manor raised on the knight's land (as the builders raise it): the knight's own, its chests no part of the town's
     * stores, its key handed over by the next folk it talks to, its banner hung beside the door. The retinue called: two
     * of the watch go with the knight, paid out of the treasury; stood down, back to their posts; one of them killed is
     * dead, off the list, in the chronicle.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cl_cl08_manor_retinue")
    public static void cl08_manor_built_and_retinue_rides(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2234000;
        level.setDayTime(5 * 24000L + 4000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 8, 5);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.WHITE_BANNER, 3), new ItemStack(Items.YELLOW_DYE, 3));
        plainArms(id);
        ServerPlayer p = citizen(helper, level, id, folk, v.centre());
        String name = p.getName().getString();
        try {
            Knights.dub(level, v, p.getUUID(), name, false);
            helper.assertTrue(Knights.isKnight(id, p.getUUID()), "a knight");
            String built = KnightManors.buildNow(level, v, p.getUUID(), false);
            helper.assertTrue("built".equals(KnightManors.manorForTests(id, p.getUUID())), "the manor stands: " + built);
            BlockPos plot = KnightManors.plotForTests(id, p.getUUID());
            boolean registered = false;
            for (Ledger.Building b : Ledger.buildings(id)) if (b.structure().equals(KnightManors.STRUCTURE) && b.anchor().equals(plot)) registered = true;
            helper.assertTrue(registered, "on the town's register at its plot");
            for (BlockPos c : Villages.storeChests(level, id)) helper.assertTrue(!KnightManors.inAManor(id, c), "no chest of the manor among the stores: " + c);
            helper.assertTrue(KnightManors.inAManor(id, plot.above()), "its insides are the manor's");
            String hello = FolkTalk.answer(folk.get(6), p, TalkTopic.OPEN, "");
            helper.assertTrue(carried(p, Items.TRIPWIRE_HOOK) == 1, "the key handed over: " + hello);
            helper.assertTrue(KnightManors.hangForTests(level, v, p.getUUID()) && KnightManors.bannerHungForTests(id, p.getUUID()), "the banner by its door");
            // The retinue.
            Ledger.addCoins(id, 100);
            int before = Ledger.coins(id);
            String came = Retinue.call(level, id, p);
            int with = 0;
            for (VillageFolkEntity f : folk) if (f.isHired() && p.getUUID().equals(f.hiredBy())) with++;
            helper.assertTrue(with == 2, "two ride with the knight: " + came);
            helper.assertTrue(before - Ledger.coins(id) == 2 * Retinue.WAGE, "the town paid them a day: " + (before - Ledger.coins(id)));
            String home = Retinue.dismiss(level, id, p);
            for (VillageFolkEntity f : folk) helper.assertTrue(!(f.isHired() && p.getUUID().equals(f.hiredBy())), "stood down: " + home);
            VillageFolkEntity man = null;
            for (VillageFolkEntity f : folk) if (f.stationTask() == StationTask.GUARD && f.isAlive()) {
                Retinue.call(level, id, p);
                if (p.getUUID().equals(f.hiredBy())) { man = f; break; }
            }
            helper.assertTrue(man != null, "a man of the retinue to lose");
            man.kill();
            helper.assertTrue(Retinue.size(id, p.getUUID()) == 1, "the dead man is off the list: " + Retinue.size(id, p.getUUID()));
            Kit.log("cl08 " + built + " | " + hello + " | " + came + " | " + home);
        } finally {
            Kit.noLeftoverPlayers(level);
        }
        helper.succeed();
    }

    // ============================================================ cl09 the knight's vote, the fee, the strip

    /**
     * A knight's seat: its vote to lower the taxes is counted at the sitting, by name. Its title and choice of address
     * last over a restart. A knight that leaves its fee unpaid four weeks is stripped; one convicted is stripped; the
     * public hook (strip) does the same, and isKnight says so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl_cl09_vote_fee_strip")
    public static void cl09_knights_vote_restart_fee_and_strip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2236000;
        level.setDayTime(3 * 24000L + 5000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 7, 0);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        ServerPlayer p = citizen(helper, level, id, folk, v.centre());
        ServerPlayer q = citizen(helper, level, id, folk, v.centre().east(2));
        String name = p.getName().getString();
        try {
            TaxUnrest.dailyForTests(level, v);
            Knights.dub(level, v, p.getUUID(), name, false);
            Knights.setStyle(p, Knights.Style.DAME);
            helper.assertTrue(Knights.styled(p.getUUID(), name).equals("Dame " + name), "addressed as she chose: " + Knights.styled(p.getUUID(), name));
            String cast = Knights.vote(id, p.getUUID(), -1);
            helper.assertTrue(cast.contains("lowering"), cast);
            TaxBook.setForTests(id, TaxBook.Lever.HEAD, 2);
            TaxUnrest.setForTests(id, 65);
            String sat = TaxBook.sit(level, v, level.getDayTime() / 24000L, "the council");
            String vote = TaxBook.lastVoteForTests(id);
            helper.assertTrue(vote.split("\\|")[1].contains("Dame " + name), "the knight's aye counted by name: " + vote);
            // A restart: the record read back.
            CivicLand.reloadForTests();
            helper.assertTrue(Knights.isKnight(id, p.getUUID()) && Knights.style(p.getUUID()) == Knights.Style.DAME, "the title survives a restart");
            // The fee unpaid: stripped at four weeks.
            Knights.dub(level, v, q.getUUID(), "Second", false);
            p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
            for (int week = 0; week < 5 && Knights.isKnight(id, q.getUUID()); week++) {
                TaxBook.collectNowForTests(level, v);
                TaxBook.pay(level, id, p);                              // the first knight pays its fee each week; the second never does
            }
            helper.assertTrue(!Knights.isKnight(id, q.getUUID()), "a knight four weeks behind with its fee is stripped");
            helper.assertTrue(Knights.isKnight(id, p.getUUID()), "the knight who paid keeps the title");
            // Convicted: stripped.
            Knights.offence(level, id, p, true, "theft from the town's stores");
            helper.assertTrue(!Knights.isKnight(id, p.getUUID()) && !Knights.isKnightAnywhere(p.getUUID()), "stripped for a crime");
            helper.assertTrue(!p.getDisplayName().getString().startsWith("Dame "), "the title gone from its name: " + p.getDisplayName().getString());
            String again = Knights.strip(level, id, p.getUUID(), "again");
            helper.assertTrue(again.contains("no knight"), "the public hook knows: " + again);
            Kit.log("cl09 " + sat + " || " + vote);
        } finally {
            Kit.noLeftoverPlayers(level);
        }
        helper.succeed();
    }

    // ============================================================ cl10 away from the ceremony; a town that dies

    /**
     * A player chosen for knighthood who never comes (not on) is knighted in its absence on the third evening, and a
     * notice kept for when it next comes on; its banner kept for it too. The town dying, its knighthood lapses, kept in
     * the player's record as of a town that is no more.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cl_cl10_absent_and_fallen")
    public static void cl10_knighted_in_absence_and_the_town_gone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2238000;
        long day = 7;
        level.setDayTime(day * 24000L + 2000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 6, 0);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.WHITE_BANNER, 2), new ItemStack(Items.YELLOW_DYE, 2));
        plainArms(id);
        UUID away = UUID.nameUUIDFromBytes("cl10-away".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Ledger.addCitizen(id, away, "Wanderer");
        for (VillageFolkEntity f : folk) {
            f.persona().met(away);
            f.persona().feelFor(away, "Wanderer", 45);
        }
        Standing.stir(id, away);
        Knights.serveForTests(id, away, "Wanderer", "b", 5);
        Knights.morningForTests(level, v);
        helper.assertTrue(Knights.chosenForTests(id, away), "chosen while away");
        for (int evening = 0; evening < 3; evening++) {
            level.setDayTime((day + evening) * 24000L + 13400L);
            Knights.eveningForTests(level, v);
        }
        helper.assertTrue(Knights.isKnight(id, away), "knighted in its absence on the third evening");
        helper.assertTrue(!CivicLand.noticesForTests(away).isEmpty(), "a notice kept for it: " + CivicLand.noticesForTests(away));
        helper.assertTrue(Knights.bannerKeptForTests(id, away), "its banner kept for it");
        CivicLand.townGone(id);
        helper.assertTrue(!Knights.isKnightAnywhere(away), "the town gone, the knighthood lapses");
        Kit.log("cl10 absent knight: notices " + CivicLand.noticesForTests(away).size());
        helper.succeed();
    }
}
