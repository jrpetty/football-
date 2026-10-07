package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Caravans;
import com.jrpetty.mcassistant.entity.Envoys;
import com.jrpetty.mcassistant.entity.Intel;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * War and peace between towns (entity/WarAndPeace): a feud with a real grievance and a hawk for an elder
 * goes to the council of war, which votes, and the herald goes with the ultimatum; a dove does not go to
 * war; a town that yields pays and stays at peace; a town that refuses is at war on both books, with a
 * banner of real blocks over each; the side that reckons itself the weaker by its scouts' report sues
 * for peace and the treaty concedes the goal; an ally's guards walk over and stand on the walls, and the
 * peace (when the cost tells on both) is on both books and sends them home; and with wars switched off,
 * nobody goes to war.
 *
 * <p>Each on its own ground in x 760000-779999, z 66000, in a batch of its own, two (or three) towns 300
 * blocks apart. The day is set just after the day's dealings (11200), so the towns' own diplomacy and war
 * do not throw their dice in the middle of a test: each step is called as the day would call it.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WarAndPeaceGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private static Villages.Village town(ServerLevel level, int x, int n) {
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        return Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
    }

    private static List<VillageFolkEntity> folk(UUID v) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v)) {
            if (a instanceof VillageFolkEntity f) {
                f.ensurePersona();
                out.add(f);
            }
        }
        return out;
    }

    /** The town's elder chosen, with this nature (its first trait its temper) and this at heart. */
    private static VillageFolkEntity elder(ServerLevel level, UUID v, Values.Value cares, Social.Trait... traits) {
        List<VillageFolkEntity> all = folk(v);                  // each one's nature rolled first: the town chooses among them
        long day = level.getDayTime() / 24000L;
        Villages.chooseElder(v, day);
        if (Villages.elder(v) == null && !all.isEmpty()) Villages.electElder(v, all.get(0), day);
        UUID e = Villages.elder(v);
        for (VillageFolkEntity f : folk(v)) {
            if (f.getUUID().equals(e)) {
                f.life().setTraitsForTests(traits);
                Values.setForTests(f, cares, 95);
                return f;
            }
        }
        return null;
    }

    /** So many of the town's folk (never the elder) set to the watch. */
    private static void guards(UUID v, int n) {
        UUID e = Villages.elder(v);
        for (VillageFolkEntity f : folk(v)) {
            if (n <= 0) break;
            if (f.getUUID().equals(e) || f.isBaby()) continue;
            f.setJob(AssistantEntity.StationTask.GUARD);
            n--;
        }
    }

    /** Everybody in the town a Guardian at heart, and a grumpy one: a council that votes for war. */
    private static void hawks(UUID v) {
        UUID e = Villages.elder(v);
        for (VillageFolkEntity f : folk(v)) {
            if (f.getUUID().equals(e)) continue;
            f.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
            Values.setForTests(f, Values.Value.SAFETY, 95);
            Values.setForTests(f, Values.Value.WEALTH, 5);
        }
    }

    /** Into the town's stores (its first store chest). */
    private static void stock(ServerLevel level, UUID v, ItemStack... goods) {
        List<BlockPos> stores = Villages.storeChests(level, v);
        if (stores.isEmpty()) return;
        Container c = (Container) level.getBlockEntity(stores.get(0));
        int slot = 0;
        for (ItemStack s : goods) {
            while (slot < c.getContainerSize() && !c.getItem(slot).isEmpty()) slot++;
            if (slot >= c.getContainerSize()) break;
            c.setItem(slot, s);
        }
        c.setChanged();
    }

    /** The two in a feud, with a quarrel over the land fresh in the first's books. */
    private static void feud(UUID a, UUID b, long day) {
        int r = Ledger.relation(a, b);
        Ledger.relate(a, b, -70 - r);
        WarAndPeace.wrongForTests(a, b, day, WarAndPeace.Wrong.LAND, "a boundary stone between us was moved in the night");
    }

    /** The envoy of this town on the road to that one on this errand, or null. */
    private static VillageFolkEntity envoy(UUID from, UUID to, Envoys.Errand errand) {
        for (VillageFolkEntity f : folk(from)) {
            Caravans.Trip t = f.trip();
            if (t != null && t.errand() == errand && t.destination().equals(to) && !t.homeward()) return f;
        }
        return null;
    }

    private static boolean chronicled(UUID v, String words) {
        for (Chronicle.Entry e : Chronicle.of(v)) if (e.text().contains(words)) return true;
        return false;
    }

    private static void start(ServerLevel level) {
        Kit.reset(level);
        WarAndPeace.resetForTests();
        level.setDayTime(40L * 24000L + 11200L);                // just after the day's dealings: nothing rolls its dice mid-test
    }

    // ------------------------------------------------------------------ wp01

    /**
     * A feud with a real grievance (a quarrel over the land) and a hawk for an elder (prickly, a Guardian):
     * the elder puts war to the council, going by its scouts' report of the other town; the council of
     * Guardians votes for the ultimatum, the vote is on the board and in the chronicle, both towns stand
     * on their guard, and a herald sets out with the demands.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wp01_hawk_council")
    public static void wp01_hawk_council(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 760000;
        Villages.Village a = town(level, x, 6), b = town(level, x + 300, 4);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            elder(level, a.id(), Values.Value.SAFETY, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
            elder(level, b.id(), Values.Value.FOOD, Social.Trait.CHEERFUL, Social.Trait.GENEROUS);
            hawks(a.id());
            guards(a.id(), 2);
            feud(a.id(), b.id(), day);
            Intel.file(a.id(), new Intel.Report(b.id(), day - 1, 4, 0, 0, 0, 0, 0, 6, "a quiet place, nobody on the walls"));
            WarAndPeace.Decision d = WarAndPeace.weigh(level, a, b, day);
            Kit.log("wp01 the elder weighs it: go " + d.go() + ", hawk " + d.hawk() + ": " + d.why() + "; goal " + d.goal() + " " + d.goalText());
            helper.assertTrue(d.go(), "a hawk in a feud with a grievance would go to war: " + d.why());
            helper.assertTrue(d.why().contains("scouts' report of day " + (day - 1)), "it goes by the scouts' report: " + d.why());
            String vote = WarAndPeace.councilNow(level, a, b, false);
            Kit.log("wp01 the council of war: " + vote);
            String stage = WarAndPeace.quarrelStage(a.id(), b.id());
            helper.assertTrue("HERALD_DUE".equals(stage), "the council voted for the ultimatum: " + stage + " / " + vote);
            List<String> board = WarAndPeace.board(level, a.id(), day);
            Kit.log("wp01 the board: " + board);
            helper.assertTrue(board.stream().anyMatch(l -> l.contains("council of war voted")), "the vote is on the board: " + board);
            helper.assertTrue(chronicled(a.id(), "council of war voted"), "and in the chronicle");
            helper.assertTrue(VillageBoards.compose(level, a.id()).stream().anyMatch(l -> l.contains("council of war")), "on the town's own board");
            helper.assertTrue(WarAndPeace.heraldNow(level, a, b), "the herald sets out");
            VillageFolkEntity herald = envoy(a.id(), b.id(), Envoys.Errand.WAR);
            Kit.log("wp01 the herald: " + (herald == null ? "none" : herald.displayNameCap()) + "; footing " + Wars.footing(a.id()) + "/"
                + Wars.footing(b.id()) + "; at war " + Wars.atWar(a.id(), b.id()));
            helper.assertTrue(herald != null, "a herald is on the road to " + Villages.name(b.id()) + " on the business of war");
            helper.assertTrue(Wars.footing(a.id()) == Wars.Footing.TENSION && Wars.footing(b.id()) == Wars.Footing.TENSION,
                "both on their guard, short of war");
            helper.assertTrue(!Wars.atWar(a.id(), b.id()), "and not at war yet");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp02

    /** The same feud and grievance with a dove for an elder (warm-hearted, a Merchant): no war, day after day. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wp02_dove")
    public static void wp02_dove(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 762000;
        Villages.Village a = town(level, x, 6), b = town(level, x + 300, 4);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            elder(level, a.id(), Values.Value.WEALTH, Social.Trait.CHEERFUL, Social.Trait.SOCIABLE);
            hawks(a.id());                                     // its folk may be hawks; its elder is not
            guards(a.id(), 3);
            feud(a.id(), b.id(), day);
            WarAndPeace.Decision d = WarAndPeace.weigh(level, a, b, day);
            Kit.log("wp02 the dove weighs it: go " + d.go() + ", hawk " + d.hawk() + ": " + d.why());
            helper.assertTrue(!d.go() && d.why().contains("dove"), "a dove does not go to war: " + d.why());
            for (int k = 0; k < 10; k++) {
                WarAndPeace.wrongForTests(a.id(), b.id(), day + k, WarAndPeace.Wrong.THEFT, "a sheep went missing, and they're to blame");
                WarAndPeace.dailyForTests(level, a, b, day + k);
            }
            Kit.log("wp02 after ten days: quarrel " + WarAndPeace.quarrelStage(a.id(), b.id()) + ", at war " + Wars.atWar(a.id(), b.id())
                + ", relation " + Ledger.relation(a.id(), b.id()));
            helper.assertTrue(WarAndPeace.quarrelStage(a.id(), b.id()) == null && !Wars.atWar(a.id(), b.id()), "ten days of grievances, and no war");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp03

    /**
     * An ultimatum for tribute to a soft, weak town whose scouts have counted the other's guards: it
     * yields, the coin goes home in the herald's purse to the other's treasury, and there is no war.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wp03_yield")
    public static void wp03_yield(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 764000;
        Villages.Village a = town(level, x, 6), b = town(level, x + 300, 4);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            elder(level, a.id(), Values.Value.SAFETY, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
            elder(level, b.id(), Values.Value.FOOD, Social.Trait.CHEERFUL, Social.Trait.GENEROUS);
            guards(a.id(), 4);
            feud(a.id(), b.id(), day);
            Ledger.addCoins(b.id(), 40);
            Intel.file(b.id(), new Intel.Report(a.id(), day, 6, 4, 0, 0, 0, 0, 10, "four on the watch, day and night"));
            WarAndPeace.quarrelForTests(a.id(), b.id(), WarAndPeace.Goal.TRIBUTE, 12, "12 coins in tribute", day);
            helper.assertTrue(WarAndPeace.heraldNow(level, a, b), "the herald sets out");
            VillageFolkEntity herald = envoy(a.id(), b.id(), Envoys.Errand.WAR);
            helper.assertTrue(herald != null, "a herald on the road");
            int aBefore = Ledger.coins(a.id()), bBefore = Ledger.coins(b.id());
            Caravans.arriveForTests(level, herald);
            String said = WarAndPeace.audienceForTests(level, herald);
            int purse = WarAndPeace.purseForTests(herald);
            Kit.log("wp03 the audience: " + said + "; the herald's purse " + purse + "; " + Villages.name(b.id()) + " " + bBefore + " -> "
                + Ledger.coins(b.id()));
            helper.assertTrue(purse == 12 && Ledger.coins(b.id()) == bBefore - 12, "it yields: twelve coins out of its treasury into the herald's purse");
            // The herald travels light: home with nothing but the purse (what it carried of its own is not the tribute).
            for (int i = 0; i < herald.getInventoryItems().size(); i++) herald.getInventoryItems().set(i, ItemStack.EMPTY);
            Caravans.arriveForTests(level, herald);
            Kit.log("wp03 home: " + Villages.name(a.id()) + " " + aBefore + " -> " + Ledger.coins(a.id()) + "; at war " + Wars.atWar(a.id(), b.id())
                + "; quarrel " + WarAndPeace.quarrelStage(a.id(), b.id()) + "; " + WarAndPeace.booksForTests(a.id(), b.id()));
            helper.assertTrue(Ledger.coins(a.id()) == aBefore + 12, "the tribute reaches the treasury at home");
            helper.assertTrue(!Wars.atWar(a.id(), b.id()) && WarAndPeace.quarrelStage(a.id(), b.id()) == null, "and there is no war");
            helper.assertTrue(chronicled(b.id(), "yielded"), "the yielding is in its chronicle");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp04

    /**
     * A prickly town with guards of its own and nothing but rumour of the other refuses the ultimatum: war
     * on both books, and each town's war banner hung, of real blocks: the one a red banner out of its
     * stores, the other made of six of its wool and a stick.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wp04_refused")
    public static void wp04_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 766000;
        Villages.Village a = town(level, x, 6), b = town(level, x + 300, 5);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            elder(level, a.id(), Values.Value.SAFETY, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
            elder(level, b.id(), Values.Value.SAFETY, Social.Trait.GRUMPY, Social.Trait.SHY);
            guards(a.id(), 2);
            guards(b.id(), 2);
            feud(a.id(), b.id(), day);
            stock(level, a.id(), new ItemStack(Items.RED_BANNER));
            stock(level, b.id(), new ItemStack(Items.WHITE_WOOL, 6), new ItemStack(Items.STICK, 2));
            int aBanners = Market.stock(level, a.id(), s -> s.is(Items.RED_BANNER)), bWool = Market.stock(level, b.id(), s -> s.is(net.minecraft.tags.ItemTags.WOOL));
            WarAndPeace.quarrelForTests(a.id(), b.id(), WarAndPeace.Goal.BORDER, 0, "a border where we say it runs", day);
            helper.assertTrue(WarAndPeace.heraldNow(level, a, b), "the herald sets out");
            VillageFolkEntity herald = envoy(a.id(), b.id(), Envoys.Errand.WAR);
            helper.assertTrue(herald != null, "a herald on the road");
            Caravans.arriveForTests(level, herald);
            String said = WarAndPeace.audienceForTests(level, herald);
            BlockPos ba = WarAndPeace.bannerAt(a.id(), b.id()), bb = WarAndPeace.bannerAt(b.id(), a.id());
            Kit.log("wp04 the audience: " + said + "; at war " + Wars.atWar(a.id(), b.id()) + "/" + Wars.atWar(b.id(), a.id()) + " since "
                + Wars.since(a.id(), b.id()) + "; " + WarAndPeace.booksForTests(a.id(), b.id()) + "; banners " + ba + " "
                + (ba == null ? "" : level.getBlockState(ba)) + ", " + bb + " " + (bb == null ? "" : level.getBlockState(bb)));
            helper.assertTrue(Wars.atWar(a.id(), b.id()) && Wars.atWar(b.id(), a.id()) && Wars.since(a.id(), b.id()) == day,
                "the refusal begins the war, on both towns' books, today");
            helper.assertTrue(WarAndPeace.booksForTests(a.id(), b.id()).startsWith("book true/true"), "each keeps the war's page");
            helper.assertTrue(ba != null && level.getBlockState(ba).getBlock() instanceof AbstractBannerBlock, "a war banner hangs in " + Villages.name(a.id()));
            helper.assertTrue(bb != null && level.getBlockState(bb).getBlock() instanceof AbstractBannerBlock, "and in " + Villages.name(b.id()));
            int aLeft = Market.stock(level, a.id(), s -> s.is(Items.RED_BANNER)), bLeft = Market.stock(level, b.id(), s -> s.is(net.minecraft.tags.ItemTags.WOOL));
            Kit.log("wp04 the stores: red banners " + aBanners + " -> " + aLeft + ", wool " + bWool + " -> " + bLeft);
            helper.assertTrue(aLeft == aBanners - 1 && bLeft == bWool - 6, "out of the stores: the banner, and the wool for the other");
            helper.assertTrue(chronicled(a.id(), "declared war") && chronicled(b.id(), "declared war"), "in both chronicles");
            helper.assertTrue(Wars.footing(a.id()) == Wars.Footing.WAR, "the town is at war");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp05

    /**
     * At war for tribute: after the war has stood its while, the side whose scouts have counted the
     * other's guards, and so reckons itself the weaker, sends its elder under a white flag; the other, much
     * the stronger, makes peace, and the treaty concedes the whole of the goal: the tribute paid out of the
     * envoy's purse, the war ended on both books, the treaty in both, the banners down, both stood down.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wp05_weaker_sues")
    public static void wp05_weaker_sues(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 768000;
        Villages.Village a = town(level, x, 6), b = town(level, x + 300, 4);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        AtomicInteger stoodDown = new AtomicInteger();
        WarAndPeace.listen((lv, v) -> { if (v.equals(a.id()) || v.equals(b.id())) stoodDown.incrementAndGet(); });
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            elder(level, a.id(), Values.Value.SAFETY, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
            elder(level, b.id(), Values.Value.FOOD, Social.Trait.CHEERFUL, Social.Trait.GENEROUS);
            guards(a.id(), 4);
            feud(a.id(), b.id(), day);
            Ledger.addCoins(b.id(), 30);
            stock(level, a.id(), new ItemStack(Items.RED_BANNER));
            helper.assertTrue(WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.TRIBUTE, 10, "10 coins in tribute"), "war declared");
            WarAndPeace.backdateForTests(a.id(), b.id(), 8);
            Intel.file(b.id(), new Intel.Report(a.id(), day, 6, 4, 0, 0, 0, 0, 10, "four guards on the walls"));
            WarAndPeace.Reckoning bOwn = WarAndPeace.own(level, b.id()), bOfA = WarAndPeace.of(level, b.id(), a.id(), day);
            WarAndPeace.Reckoning aOwn = WarAndPeace.own(level, a.id()), aOfB = WarAndPeace.of(level, a.id(), b.id(), day);
            Kit.log("wp05 the reckonings: " + Villages.name(b.id()) + " " + bOwn.strength() + " against " + bOfA.strength() + " (" + bOfA.source()
                + "); " + Villages.name(a.id()) + " " + aOwn.strength() + " against " + aOfB.strength() + " (" + aOfB.source() + ")");
            int aBefore = Ledger.coins(a.id());
            BlockPos banner = WarAndPeace.bannerAt(a.id(), b.id());
            WarAndPeace.dailyForTests(level, a, b, day);
            VillageFolkEntity flag = envoy(b.id(), a.id(), Envoys.Errand.PEACE);
            Kit.log("wp05 the white flag: " + (flag == null ? "none" : flag.displayNameCap() + (flag.isElder() ? " (the elder)" : "")
                + ", carrying " + WarAndPeace.purseForTests(flag) + " coins"));
            helper.assertTrue(flag != null, "the weaker side by its scouts' report sues for peace");
            helper.assertTrue(envoy(a.id(), b.id(), Envoys.Errand.PEACE) == null, "the stronger does not");
            Caravans.arriveForTests(level, flag);
            String said = WarAndPeace.audienceForTests(level, flag);
            Kit.log("wp05 the talks: " + said + "; " + WarAndPeace.booksForTests(a.id(), b.id()) + "; " + Villages.name(a.id()) + " " + aBefore
                + " -> " + Ledger.coins(a.id()) + "; stood down " + stoodDown.get() + "; banner " + banner + " now "
                + (banner == null ? "" : level.getBlockState(banner)));
            helper.assertTrue(!Wars.atWar(a.id(), b.id()) && !Wars.atWar(b.id(), a.id()), "the war is over on both books");
            helper.assertTrue(WarAndPeace.booksForTests(a.id(), b.id()).contains("treaties 1/1") && said.contains("concedes the whole"),
                "a treaty in both, conceding the whole of the goal: " + said);
            helper.assertTrue(Ledger.coins(a.id()) == aBefore + 10, "the tribute paid, out of the envoy's purse");
            helper.assertTrue(stoodDown.get() == 2, "both towns stood down: " + stoodDown.get());
            helper.assertTrue(banner == null || !(level.getBlockState(banner).getBlock() instanceof AbstractBannerBlock), "the war banner taken down");
            helper.assertTrue(Wars.footing(a.id()) == Wars.Footing.PEACE && Wars.footing(b.id()) == Wars.Footing.PEACE, "both at peace");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp06

    /**
     * An ally called: its envoy carries the call, the ally answers and two of its guards walk over and
     * stand on the walls, raising the town's strength. Neither side reckons itself the weaker; the cost of
     * the war footing tells, they talk, and peace ends the war on both books with a treaty recorded; the
     * ally's guards go home.
     */
    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "wp06_allies_peace")
    public static void wp06_allies_peace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 770000;
        Villages.Village a = town(level, x, 6), b = town(level, x + 300, 4), c = town(level, x + 600, 6);
        helper.assertTrue(a != null && b != null && c != null && !b.id().equals(c.id()) && !a.id().equals(b.id()), "three towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            elder(level, a.id(), Values.Value.SAFETY, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
            elder(level, b.id(), Values.Value.FOOD, Social.Trait.SOCIABLE, Social.Trait.GENEROUS);
            elder(level, c.id(), Values.Value.SAFETY, Social.Trait.SOCIABLE, Social.Trait.HARDWORKING);
            guards(a.id(), 2);
            guards(c.id(), 4);
            feud(a.id(), b.id(), day);
            Ledger.relate(b.id(), c.id(), 75 - Ledger.relation(b.id(), c.id()));
            Ledger.note(b.id(), "ally/" + c.id(), Long.toString(day));
            Ledger.note(c.id(), "ally/" + b.id(), Long.toString(day));
            Ledger.addCoins(a.id(), 100);
            Ledger.addCoins(b.id(), 20);
            WarAndPeace.Reckoning before = WarAndPeace.own(level, b.id());
            helper.assertTrue(WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.REVENGE, 6, "6 coins for the wrongs done us"), "war declared");
            WarAndPeace.dailyForTests(level, b, c, day);
            VillageFolkEntity call = envoy(b.id(), c.id(), Envoys.Errand.WAR);
            helper.assertTrue(call != null, "an envoy carries the call to arms to the ally");
            Caravans.arriveForTests(level, call);
            String answered = WarAndPeace.audienceForTests(level, call);
            Kit.log("wp06 the call to arms: " + answered + "; allies of " + Villages.name(b.id()) + ": " + WarAndPeace.alliesOf(b.id()).size());
            helper.assertTrue(WarAndPeace.alliesOf(b.id()).contains(c.id()), "the ally answers the call");
            WarAndPeace.townDailyForTests(level, c, day);
            List<VillageFolkEntity> going = new ArrayList<>();
            for (VillageFolkEntity f : folk(c.id())) {
                if (f.trip() != null && f.trip().errand() == Envoys.Errand.WAR && f.trip().destination().equals(b.id())) going.add(f);
            }
            helper.assertTrue(going.size() == 2, "two of its four guards set out for the walls: " + going.size());
            for (VillageFolkEntity g : going) Caravans.arriveForTests(level, g);
            WarAndPeace.Reckoning after = WarAndPeace.own(level, b.id());
            Kit.log("wp06 " + Villages.name(b.id()) + "'s strength " + before.strength() + " (" + before.guards() + " guards) -> " + after.strength()
                + " (" + after.guards() + " guards, " + after.garrison() + " the ally's); as " + Villages.name(a.id()) + " hears it: "
                + WarAndPeace.of(level, a.id(), b.id(), day).strength());
            helper.assertTrue(after.garrison() == 2 && after.guards() == before.guards() + 2 && after.strength() >= before.strength() + 5,
                "the ally's guards on the walls raise its strength");
            WarAndPeace.backdateForTests(a.id(), b.id(), 10);
            WarAndPeace.costForTests(a.id(), b.id(), 40);
            WarAndPeace.costForTests(b.id(), a.id(), 40);
            WarAndPeace.dailyForTests(level, a, b, day);
            VillageFolkEntity flag = envoy(b.id(), a.id(), Envoys.Errand.PEACE);
            if (flag == null) flag = envoy(a.id(), b.id(), Envoys.Errand.PEACE);
            Kit.log("wp06 the cost tells: " + (flag == null ? "no white flag" : flag.displayNameCap() + " of " + Villages.name(flag.ownerId())));
            helper.assertTrue(flag != null, "the cost of the war footing brings them to talk");
            Caravans.arriveForTests(level, flag);
            String said = WarAndPeace.audienceForTests(level, flag);
            Kit.log("wp06 the talks: " + said + "; " + WarAndPeace.booksForTests(a.id(), b.id()));
            helper.assertTrue(!Wars.atWar(a.id(), b.id()) && !Wars.atWar(b.id(), a.id()), "peace ends the war on both books");
            helper.assertTrue(WarAndPeace.booksForTests(a.id(), b.id()).contains("treaties 1/1"), "the treaty recorded in both");
            helper.assertTrue(WarAndPeace.board(level, a.id(), day).stream().anyMatch(l -> l.startsWith("RN|Treaty with"))
                && WarAndPeace.board(level, b.id(), day).stream().anyMatch(l -> l.startsWith("RN|Treaty with")), "and on both boards");
            int home = 0;
            for (VillageFolkEntity g : going) {
                if (g.trip() != null && g.trip().destination().equals(c.id())) {
                    Caravans.arriveForTests(level, g);
                    if (g.trip() == null) home++;
                }
            }
            Kit.log("wp06 the ally's guards home: " + home + " of " + going.size() + "; " + Villages.name(b.id()) + " now "
                + WarAndPeace.own(level, b.id()).strength());
            helper.assertTrue(home == 2, "the ally's guards go home at the peace");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp07

    /**
     * With villageWars off, the same hawk in the same feud does not go to war: it will not put it to the
     * council, ten days pass with no quarrel, a declaration is refused, and an ultimatum already on the
     * road is let drop.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wp07_switched_off")
    public static void wp07_switched_off(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 772000;
        Villages.Village a = town(level, x, 6), b = town(level, x + 300, 4);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            // The config's own switch, if the test server has its config to set; whatever happens, set back after.
            boolean set = false, real = false;
            try {
                AssistantConfig.VILLAGE_WARS.set(false);
                set = true;
                real = !AssistantConfig.villageWars();
            } catch (RuntimeException e) {
                Kit.log("wp07 the config could not be set here (" + e.getMessage() + "): the test switch instead");
            }
            if (!real) WarAndPeace.switchForTests(false);
            try {
                long day = level.getDayTime() / 24000L;
                elder(level, a.id(), Values.Value.SAFETY, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
                hawks(a.id());
                guards(a.id(), 3);
                feud(a.id(), b.id(), day);
                WarAndPeace.Decision d = WarAndPeace.weigh(level, a, b, day);
                Kit.log("wp07 villageWars off (" + (real ? "the config" : "the test switch") + "): go " + d.go() + ": " + d.why());
                helper.assertTrue(!d.go() && d.why().contains("switched off"), "nobody goes to war: " + d.why());
                for (int k = 0; k < 10; k++) WarAndPeace.dailyForTests(level, a, b, day + k);
                helper.assertTrue(WarAndPeace.quarrelStage(a.id(), b.id()) == null, "ten days and no quarrel");
                helper.assertTrue(!WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.BORDER, 0, "a border"), "no declaration");
                WarAndPeace.quarrelForTests(a.id(), b.id(), WarAndPeace.Goal.TRIBUTE, 10, "10 coins in tribute", day);
                boolean sent = WarAndPeace.heraldNow(level, a, b);
                VillageFolkEntity herald = envoy(a.id(), b.id(), Envoys.Errand.WAR);
                String said = "";
                if (herald != null) {
                    Caravans.arriveForTests(level, herald);
                    said = WarAndPeace.audienceForTests(level, herald);
                }
                Kit.log("wp07 an ultimatum on the road anyway: sent " + sent + "; " + said + "; at war " + Wars.atWar(a.id(), b.id())
                    + "; quarrel " + WarAndPeace.quarrelStage(a.id(), b.id()));
                helper.assertTrue(!Wars.atWar(a.id(), b.id()), "an ultimatum already sent comes to nothing");
                helper.succeed();
            } finally {
                if (set) AssistantConfig.VILLAGE_WARS.set(true);
                WarAndPeace.switchForTests(null);
            }
        });
    }
}
