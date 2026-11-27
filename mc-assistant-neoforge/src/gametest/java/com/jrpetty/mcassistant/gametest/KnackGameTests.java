package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkSkills;
import com.jrpetty.mcassistant.entity.FolkSkills.Knack;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * A folk's own knacks (entity/FolkSkills): a point at every fifth level of its best trade, six at
 * most and none for a child; what it chooses by its trade, its nature, what it cares about and how
 * it lives (a Homemaker renting its house reaches for Nest Egg and has about three tenths of the
 * price put toward it, never the whole; a Merchant a money knack; a miner a miner's knack; a
 * cheerful Free Spirit Bright Spirit); a trade's knack quickening its own trade only; the knacks
 * kept through a save; and what the talk screen's Skills page and the About card are sent.
 *
 * <p>Like the village tests, each runs on its own ground, far from the others, in a batch of its
 * own, and everything a test checks happens inside one callback, so no folk's own beat chooses a
 * knack under it while it looks.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class KnackGameTests {

    private static final String EMPTY = "empty";

    /** A village's first folk on clean ground at x, z, past the morning's payday (so only what a test runs runs). */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, int z) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null, "a village");
        return f;
    }

    /** Set it to a trade and bring it up to a level there, a little experience at a time. */
    private static void raiseTo(VillageFolkEntity f, StationTask trade, int level) {
        f.setJob(trade);
        for (int i = 0; i < 20000 && f.tradeLevel(trade) < level; i++) f.awardXp(25);
    }

    /** Who it is: these two traits, and what it cares about most. */
    private static void nature(VillageFolkEntity f, Social.Trait a, Social.Trait b, Values.Value cares) {
        f.life().setTraitsForTests(a, b);
        Values.setForTests(f, cares, 100);
    }

    // ============================================================ points

    /**
     * Points come with experience: none at level nought, the first at level 5 (and still one at 9), the
     * second at 10, the next at 15; a bar part-way to it; and none at all for a child, however good it is.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kn01_points")
    public static void kn01_points(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 140000, 40000);
        ServerLevel level = helper.getLevel();
        VillageFolkEntity kid = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, 140003, 40000), 0.0F);
        helper.assertTrue(kid != null, "a second folk");
        helper.runAtTickTime(10, () -> {
            f.setJob(StationTask.MINE);
            Kit.log("kn01 new: " + FolkSkills.describe(f));
            helper.assertTrue(FolkSkills.earned(f) == 0 && FolkSkills.nextPointAt(f) == 5,
                "no points at level nought, the first at level 5: " + FolkSkills.describe(f));
            raiseTo(f, StationTask.MINE, 5);
            helper.assertTrue(f.tradeLevel(StationTask.MINE) == 5 && FolkSkills.earned(f) == 1 && FolkSkills.free(f) == 1,
                "a point at level 5: " + FolkSkills.describe(f));
            raiseTo(f, StationTask.MINE, 9);
            helper.assertTrue(FolkSkills.earned(f) == 1 && FolkSkills.nextPointAt(f) == 10 && FolkSkills.progressPercent(f) > 50,
                "still one at level 9, the next at 10, and most of the way there: " + FolkSkills.describe(f));
            raiseTo(f, StationTask.MINE, 10);
            helper.assertTrue(FolkSkills.earned(f) == 2 && FolkSkills.nextPointAt(f) == 15, "two at level 10: " + FolkSkills.describe(f));
            // Its best trade counts, whatever it works now.
            f.setJob(StationTask.FARM);
            helper.assertTrue(FolkSkills.earned(f) == 2 && FolkSkills.bestTrade(f) == StationTask.MINE,
                "a miner turned farmer keeps its points: " + FolkSkills.describe(f));
            raiseTo(f, StationTask.FARM, 3);
            helper.assertTrue(FolkSkills.earned(f) == 2, "and a few levels at the new trade add nothing: " + FolkSkills.describe(f));
            // Six at most.
            raiseTo(f, StationTask.MINE, 40);
            helper.assertTrue(FolkSkills.earned(f) == 6 && FolkSkills.nextPointAt(f) == 0, "six at most: " + FolkSkills.describe(f));
            // A child: none, however good it is.
            raiseTo(kid, StationTask.MINE, 10);
            kid.setChild(true);
            helper.assertTrue(FolkSkills.earned(kid) == 0 && FolkSkills.free(kid) == 0 && FolkSkills.chooseNow(level, kid) == null,
                "a child has no knacks: " + FolkSkills.describe(kid));
            helper.succeed();
        });
    }

    // ============================================================ Nest Egg

    /**
     * A Homemaker renting its house from the village, with a point to spend, chooses Nest Egg first:
     * the treasury puts about three tenths of the house's price into what the household has put by
     * toward buying it — never the whole price, and the house still rented — and the chronicle and
     * its memory have it. Another, when the treasury is empty, has it set aside and paid when the
     * treasury has the coin again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kn02_nest_egg")
    public static void kn02_nest_egg(GameTestHelper helper) {
        VillageFolkEntity home = founder(helper, 141500, 40000);
        ServerLevel level = helper.getLevel();
        UUID id = home.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, 141500, 40000);
        VillageFolkEntity late = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(late != null && id.equals(late.ownerId()), "a second household");
        for (int n = 0; n < 2; n++) {
            BlockPos at = Kit.surface(level, heart.getX() - 30 + 14 * n, heart.getZ() + 22);
            BuildGoal.stamp(level, "house", at, Direction.NORTH, 13,
                com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(id, "house", at, Direction.NORTH);
        }
        helper.runAtTickTime(10, () -> {
            for (VillageFolkEntity f : List.of(home, late)) {
                nature(f, Social.Trait.GENEROUS, Social.Trait.SHY, Values.Value.HOMES);
                f.setJob(StationTask.FARM);
                f.spend(f.purse());
                f.rentFree(false);
            }
            Homes.tickForTests(level, v);
            BlockPos houseA = Homes.homeOf(home), houseB = Homes.homeOf(late);
            helper.assertTrue(houseA != null && houseB != null && !houseA.equals(houseB), "each household has a house of its own");
            helper.assertTrue("RENTED".equals(Homes.tenureForTests(id, houseA)) && "RENTED".equals(Homes.tenureForTests(id, houseB)),
                "both rented from the village");
            helper.assertTrue(Homes.rentsAndWantsToOwn(home) && Homes.rentsAndWantsToOwn(late), "and both want to buy");
            raiseTo(home, StationTask.FARM, 5);
            raiseTo(late, StationTask.FARM, 5);
            Ledger.addCoins(id, 1000);
            int treasury = Ledger.coins(id);
            int price = Homes.termsForTests(id, houseA)[3];
            int expected = (int) Math.round(price * FolkSkills.NEST_EGG_PERCENT / 100.0);
            Kit.log("kn02 before: " + FolkSkills.describe(home) + " | ranked " + FolkSkills.ranked(home).subList(0, 3)
                + " | price " + price + ", treasury " + treasury);

            Knack k = FolkSkills.chooseNow(level, home);
            int[] t = Homes.termsForTests(id, houseA);
            Kit.log("kn02 chose " + k + ": put by " + t[2] + " of " + t[3] + "; treasury " + treasury + " -> " + Ledger.coins(id)
                + " | " + FolkSkills.describe(home) + " | " + Homes.talk(home));
            helper.assertTrue(k == Knack.NEST_EGG, "a Homemaker renting its house chooses Nest Egg: " + k);
            helper.assertTrue(t[2] == expected && t[2] > 0, "about three tenths of the price put by toward the house: " + t[2] + " of " + t[3]);
            helper.assertTrue(t[2] * 100 >= t[3] * 25 && t[2] * 100 <= t[3] * 35 && t[2] < t[3],
                "a good start, never the whole price: " + t[2] + " of " + t[3]);
            helper.assertTrue("RENTED".equals(Homes.tenureForTests(id, houseA)), "the house still rented: the rest is saved for");
            helper.assertTrue(Ledger.coins(id) == treasury - expected, "out of the treasury: " + treasury + " -> " + Ledger.coins(id));
            helper.assertTrue(home.knacks().nestDue() == 0 && home.purse() == 0, "paid in full, none of it into the purse");
            boolean told = false;
            for (Villages.News n : Villages.news(id)) told |= n.text().contains("Nest Egg") && n.text().contains(home.displayNameCap());
            helper.assertTrue(told, "the chronicle has it: " + Villages.news(id));
            boolean remembered = false;
            for (Persona.Memory m : home.persona().memories()) remembered |= m.text().contains("Nest Egg");
            helper.assertTrue(remembered, "and so does it");
            helper.assertTrue(!FolkSkills.open(home, Knack.NEST_EGG) && FolkSkills.chooseNow(level, home) == null,
                "once only, and no point left over");

            // An empty treasury: set aside, and paid when it has the coin.
            Ledger.takeCoins(id, Ledger.coins(id));
            Knack k2 = FolkSkills.chooseNow(level, late);
            int[] t2 = Homes.termsForTests(id, houseB);
            helper.assertTrue(k2 == Knack.NEST_EGG && t2[2] == 0 && late.knacks().nestDue() == expected,
                "with nothing in the treasury it is set aside: " + k2 + ", put by " + t2[2] + ", owed " + late.knacks().nestDue());
            helper.assertTrue(FolkSkills.encode(late).contains("\nN|0|" + expected), "and the Skills page says so: " + FolkSkills.encode(late));
            Ledger.addCoins(id, 500);
            FolkSkills.payOwedForTests(level, late);
            int[] t3 = Homes.termsForTests(id, houseB);
            Kit.log("kn02 set aside, then paid: put by " + t3[2] + " of " + t3[3] + ", owed " + late.knacks().nestDue());
            helper.assertTrue(t3[2] == expected && late.knacks().nestDue() == 0, "paid once the treasury has it: " + t3[2]);
            helper.succeed();
        });
    }

    // ============================================================ choice

    /**
     * What it chooses follows who it is: a Merchant at heart (with no house to save for) a money knack;
     * a miner who cares for the next age a miner's knack; a cheerful Free Spirit Bright Spirit. It says
     * so, and keeps the reason.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kn03_choice")
    public static void kn03_choice(GameTestHelper helper) {
        VillageFolkEntity merchant = founder(helper, 143000, 40000);
        ServerLevel level = helper.getLevel();
        BlockPos heart = Kit.surface(level, 143000, 40000);
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        VillageFolkEntity cheer = VillageFolkSpawnerBlock.raise(level, heart.west(2), 0.0F);
        helper.assertTrue(miner != null && cheer != null, "three folk");
        helper.runAtTickTime(10, () -> {
            nature(merchant, Social.Trait.GENEROUS, Social.Trait.SHY, Values.Value.WEALTH);
            merchant.spend(merchant.purse());
            raiseTo(merchant, StationTask.FARM, 5);
            Kit.log("kn03 merchant ranks: " + FolkSkills.ranked(merchant));
            Knack m = FolkSkills.chooseNow(level, merchant);
            helper.assertTrue(m == Knack.THRIFTY || m == Knack.HAGGLER, "a Merchant chooses a money knack: " + m);
            helper.assertTrue(merchant.knacks().chosen().get(0).why().contains("Merchant"), "because it is a Merchant at heart: "
                + merchant.knacks().chosen().get(0).why());

            nature(miner, Social.Trait.GENEROUS, Social.Trait.SHY, Values.Value.PROGRESS);
            raiseTo(miner, StationTask.MINE, 5);
            Kit.log("kn03 miner ranks: " + FolkSkills.ranked(miner));
            Knack n = FolkSkills.chooseNow(level, miner);
            helper.assertTrue(n != null && n.family == FolkSkills.Family.TRADE && n.trades.contains(StationTask.MINE),
                "a miner chooses a miner's knack: " + n);

            nature(cheer, Social.Trait.CHEERFUL, Social.Trait.GENEROUS, Values.Value.LEISURE);
            raiseTo(cheer, StationTask.FARM, 5);
            Kit.log("kn03 cheerful ranks: " + FolkSkills.ranked(cheer));
            Knack c = FolkSkills.chooseNow(level, cheer);
            helper.assertTrue(c == Knack.BRIGHT_SPIRIT, "a cheerful Free Spirit chooses Bright Spirit: " + c);
            // Its nature closes the opposite's: a cheerful folk never takes Grim Resolve.
            helper.assertTrue(!FolkSkills.open(cheer, Knack.GRIM_RESOLVE), "the cheerful can't take Grim Resolve");
            // One a day, at most, of its own accord: the beat's choice waits for tomorrow.
            raiseTo(cheer, StationTask.FARM, 10);
            FolkSkills.tick(level, cheer);
            helper.assertTrue(FolkSkills.spent(cheer) == 1 && FolkSkills.free(cheer) == 1, "one a day: the second waits for tomorrow");
            helper.succeed();
        });
    }

    // ============================================================ what the knacks do

    /**
     * A trade's knack quickens that trade only: Steady Hands is five in the hundred on a miner's pace,
     * nothing once it farms, and back when it mines again; Drilled is a point of armour on a guard and
     * none off duty; a Haggler's wage comes to a twentieth more over twenty days; a Thrifty folk pays
     * a tenth less.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kn04_effects")
    public static void kn04_effects(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 144500, 40000);
        ServerLevel level = helper.getLevel();
        helper.runAtTickTime(10, () -> {
            f.life().setTraitsForTests(Social.Trait.GENEROUS, Social.Trait.CURIOUS);
            f.setJob(StationTask.MINE);
            int before = FolkSkills.workPercent(f);
            helper.assertTrue(FolkSkills.grant(level, f, Knack.STEADY_HANDS), "Steady Hands given");
            int mining = FolkSkills.workPercent(f);
            f.setJob(StationTask.FARM);
            int farming = FolkSkills.workPercent(f);
            f.setJob(StationTask.MINE);
            int again = FolkSkills.workPercent(f);
            Kit.log("kn04 pace: " + before + " -> mining " + mining + ", farming " + farming + ", mining again " + again);
            helper.assertTrue(before == 0 && mining == 5, "Steady Hands: five in the hundred at the mine: " + mining);
            helper.assertTrue(farming == 0, "nothing at the farm: " + farming);
            helper.assertTrue(again == 5, "and back at the mine: " + again);
            helper.assertTrue(f.knacks().has(Knack.STEADY_HANDS) && !FolkSkills.active(f, Knack.GREEN_THUMB), "kept, the one it chose");

            // Drilled: armour on the watch only.
            f.setJob(StationTask.GUARD);
            double bare = f.getAttributeValue(Attributes.ARMOR);
            FolkSkills.grant(level, f, Knack.DRILLED);
            double drilled = f.getAttributeValue(Attributes.ARMOR);
            f.setJob(StationTask.FARM);
            FolkSkills.tick(level, f);
            double off = f.getAttributeValue(Attributes.ARMOR);
            Kit.log("kn04 armour: " + bare + " -> " + drilled + " on guard, " + off + " off it");
            helper.assertTrue(drilled == bare + 1.0 && off == bare, "Drilled: a point of armour on the watch, none off it");

            // Haggler: a twentieth more over twenty days, the odd coin when it comes due.
            FolkSkills.grant(level, f, Knack.HAGGLER);
            long start = level.getDayTime();
            int extra = 0;
            for (int d = 0; d < 20; d++) {
                level.setDayTime(start + 24000L * d);
                extra += FolkSkills.haggled(f, 4);
            }
            level.setDayTime(start);
            helper.assertTrue(extra == 4, "a Haggler on four coins a day has four more in twenty days: " + extra);

            // Thrifty: a tenth off.
            FolkSkills.grant(level, f, Knack.THRIFTY);
            helper.assertTrue(FolkSkills.thrifty(f, 10) == 9 && FolkSkills.thrifty(f, 20) == 18 && FolkSkills.thrifty(f, 1) == 1,
                "Thrifty: a tenth off, never under a coin");
            helper.succeed();
        });
    }

    // ============================================================ kept, and shown

    /**
     * The knacks are kept with the folk: through a save and a load it has the same knacks, chosen on the
     * same days for the same reasons, and a Nest Egg still owed; an older save with none has none. The
     * talk screen is sent them: the Skills page lists its points, its trades, its knacks and those still
     * open, through the reply's codec intact, and the About card has a Knacks line.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "kn05_kept_and_shown")
    public static void kn05_kept_and_shown(GameTestHelper helper) {
        VillageFolkEntity f = founder(helper, 146000, 40000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        helper.runAtTickTime(10, () -> {
            f.life().setTraitsForTests(Social.Trait.GENEROUS, Social.Trait.SHY);
            raiseTo(f, StationTask.MINE, 12);
            FolkSkills.grant(level, f, Knack.STEADY_HANDS);
            Ledger.takeCoins(id, Ledger.coins(id));
            FolkSkills.grant(level, f, Knack.NEST_EGG);                // owed: the treasury is empty
            int owed = f.knacks().nestDue();
            helper.assertTrue(owed > 0, "a Nest Egg owed: " + owed);

            // Through a save and a load.
            CompoundTag tag = new CompoundTag();
            f.addAdditionalSaveData(tag);
            helper.assertTrue(tag.contains("Knacks"), "saved with it");
            VillageFolkEntity back = McAssistantMod.VILLAGE_FOLK.get().create(level);
            helper.assertTrue(back != null, "a folk to load into");
            back.readAdditionalSaveData(tag);
            List<FolkSkills.Chosen> was = f.knacks().chosen(), now = back.knacks().chosen();
            Kit.log("kn05 saved " + was + " | loaded " + now);
            helper.assertTrue(now.equals(was), "the same knacks, days and reasons after a load: " + now);
            helper.assertTrue(back.knacks().nestDue() == owed && back.knacks().nestSum() == f.knacks().nestSum(), "and the Nest Egg still owed");
            helper.assertTrue(FolkSkills.earned(back) == FolkSkills.earned(f) && FolkSkills.spent(back) == 2, "its points too");
            CompoundTag older = tag.copy();
            older.remove("Knacks");
            VillageFolkEntity old = McAssistantMod.VILLAGE_FOLK.get().create(level);
            old.readAdditionalSaveData(older);
            helper.assertTrue(old.knacks().chosen().isEmpty() && old.knacks().nestDue() == 0, "an older save has none");
            // (The two loaded copies were never put in the world: nothing to take out of it.)

            // What the talk screen is sent.
            String page = FolkSkills.encode(f);
            Kit.log("kn05 the Skills page: " + page.replace('\n', ' '));
            helper.assertTrue(page.startsWith("P|2|2|0|15|"), "points earned, spent, free, and the next at 15: " + page.split("\n")[0]);
            helper.assertTrue(page.contains("\nT|Miner|12|1"), "its trades' levels");
            helper.assertTrue(page.contains("\nK|steady_hands|Steady Hands|TRADE|") && page.contains("\nK|nest_egg|Nest Egg|PURSE|"),
                "each knack it chose");
            helper.assertTrue(page.contains("\nO|keen_eye|Keen Eye|TRADE|") && !page.contains("\nO|steady_hands|"),
                "and those still open to it, not the ones it has");
            String card = FolkTalk.card(f);
            helper.assertTrue(card.contains("Knacks|Steady Hands, Nest Egg"), "the About card's Knacks line: " + card);
            FolkReplyPayload reply = new FolkReplyPayload(f.getId(), true, f.displayNameCap(), "about", "said", 60, "content", 0,
                "a stranger", false, "", "", "", false, "", card, "", page);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
            FolkReplyPayload.STREAM_CODEC.encode(buf, reply);
            FolkReplyPayload got = FolkReplyPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(got.skills().equals(page) && got.card().equals(card) && got.places().isEmpty()
                && got.name().equals(f.displayNameCap()), "the reply carries the Skills page through its codec, the old fields as they were");
            helper.succeed();
        });
    }
}
