package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Caravans;
import com.jrpetty.mcassistant.entity.Heraldry;
import com.jrpetty.mcassistant.entity.Vassals;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [war] Vassal towns (entity/Vassals): a decisive win in a war for lordship makes the loser a vassal; its tribute goes
 * every week, every coin of it arriving; its folk's spirits fall; the overlord's banner and the town's style show;
 * resentment and strength make it rebel; and a full treasury buys its freedom.
 *
 * <p>Each on its own ground in x 1834000-1837999, z 66000, two towns 300 blocks apart.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class VassalGameTests {

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

    private static boolean chronicled(UUID v, String words) {
        for (Chronicle.Entry e : Chronicle.of(v)) if (e.text().contains(words)) return true;
        return false;
    }

    private static void start(ServerLevel level) {
        Kit.reset(level);
        WarAndPeace.resetForTests();
        level.setDayTime(40L * 24000L + 6000L);
    }

    // ------------------------------------------------------------------ vs01

    /** A war fought for lordship and won outright: the treaty makes the loser the winner's vassal. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "vs01_made")
    public static void vs01_made(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1834000;
        Villages.Village a = town(level, x, 10), b = town(level, x + 300, 6);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            helper.assertTrue(WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.SUBJUGATE, 0, "lordship over them"), "war for lordship");
            WarAndPeace.Terms t = new WarAndPeace.Terms(a.id(), b.id(), WarAndPeace.Goal.SUBJUGATE, 2.0, 2, 0, 0, "the whole");
            String done = WarAndPeace.makePeace(level, a.id(), b.id(), day, t, "for the tests", null);
            Kit.log("vs01 the treaty: " + done + "; " + Vassals.styled(b.id()));
            helper.assertTrue(a.id().equals(Vassals.overlord(b.id())), "the loser is the winner's vassal");
            helper.assertTrue(Vassals.vassals(a.id()).contains(b.id()), "and on the overlord's books");
            helper.assertTrue(chronicled(b.id(), "vassal of"), "the chronicle tells it");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ vs02

    /** On the week's day the tribute goes to the overlord, every coin of it arriving. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "vs02_tribute")
    public static void vs02_tribute(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1834600;
        Villages.Village a = town(level, x, 8), b = town(level, x + 300, 6);
        helper.assertTrue(a != null && b != null, "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            Vassals.makeForTests(level, a.id(), b.id(), day);
            Ledger.addCoins(b.id(), 100);
            int before = Ledger.coins(a.id()) + Ledger.coins(b.id()), bBefore = Ledger.coins(b.id());
            level.setDayTime((day + 7) * 24000L + 6000L);
            Vassals.dailyForTests(level, b, day + 7);
            for (VillageFolkEntity f : folk(b.id())) {
                for (int leg = 0; leg < 3 && f.trip() != null; leg++) {
                    Caravans.Trip trip = f.trip();
                    Caravans.arriveForTests(level, f);
                    if (!trip.homeward()) WarAndPeace.audienceForTests(level, f);
                }
            }
            int after = Ledger.coins(a.id()) + Ledger.coins(b.id());
            Kit.log("vs02 the tribute: " + bBefore + " -> " + Ledger.coins(b.id()) + "; together " + before + " -> " + after + "; " + Vassals.lines(b.id()));
            helper.assertTrue(Ledger.coins(b.id()) < bBefore, "the vassal paid");
            helper.assertTrue(after == before, "every coin of it arrived: " + before + " -> " + after);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ vs03

    /** A vassal's folk are lower in spirits, and its contentment falls. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "vs03_mood")
    public static void vs03_mood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1835200;
        Villages.Village a = town(level, x, 8), b = town(level, x + 300, 6);
        helper.assertTrue(a != null && b != null, "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            VillageFolkEntity f = folk(b.id()).get(0);
            List<Object[]> why = new ArrayList<>();
            int free = WarAndPeace.mood(f, day, 50, why);
            Vassals.makeForTests(level, a.id(), b.id(), day);
            int bound = WarAndPeace.mood(f, day, 50, new ArrayList<>());
            List<String> bad = new ArrayList<>();
            int content = WarAndPeace.contentment(b.id(), new ArrayList<>(), bad);
            Kit.log("vs03 spirits " + free + " -> " + bound + "; contentment " + content + " " + bad);
            helper.assertTrue(bound < free, "its folk's spirits fall");
            helper.assertTrue(content < 0 && String.join(",", bad).contains("vassalage"), "its contentment falls for the bond");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ vs04

    /** The overlord's banner goes up over the town's own, the reeve's post by the board, and its style on the board. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "vs04_banners")
    public static void vs04_banners(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1835800;
        Villages.Village a = town(level, x, 8), b = town(level, x + 300, 8);
        helper.assertTrue(a != null && b != null, "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            Heraldry.chooseForTests(level, a);
            Heraldry.chooseForTests(level, b);
            Heraldry.putForTests(level, b);
            Heraldry.Design d = Heraldry.design(a.id());
            if (d != null) stock(level, a.id(), Heraldry.item(level, a.id(), d), Heraldry.item(level, a.id(), d), Heraldry.item(level, a.id(), d),
                new ItemStack(Items.OAK_FENCE, 4));
            Vassals.makeForTests(level, a.id(), b.id(), day);
            for (int k = 1; k <= 3; k++) Vassals.dailyForTests(level, b, day + k);
            List<String> board = WarAndPeace.board(level, b.id(), day + 3);
            String post = Ledger.note(b.id(), "vassal.post");
            Kit.log("vs04 " + Vassals.styled(b.id()) + "; banners over its own " + Ledger.note(b.id(), "vassal.banners") + "; post " + post + "; board " + board);
            helper.assertTrue(Vassals.styled(b.id()).contains("vassal of " + Villages.name(a.id())), "its style: " + Vassals.styled(b.id()));
            helper.assertTrue(String.join(" ", board).contains("vassal of"), "on its board");
            helper.assertTrue(post != null && !post.isEmpty() || Ledger.note(b.id(), "vassal.banners") != null, "the overlord's banner flies in the town");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ vs05

    /** A vassal resentful enough, grown strong against its overlord, rises: a war for its freedom. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "vs05_rebellion")
    public static void vs05_rebellion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1836400;
        Villages.Village a = town(level, x, 5), b = town(level, x + 300, 12);
        helper.assertTrue(a != null && b != null, "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            int n = 0;
            for (VillageFolkEntity f : folk(b.id())) {
                if (f.isBaby() || n++ >= 6) continue;
                f.setJob(AssistantEntity.StationTask.GUARD);
                f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                f.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            }
            Vassals.makeForTests(level, a.id(), b.id(), day);
            Vassals.resentForTests(b.id(), 90);
            Vassals.dailyForTests(level, b, day + 1);
            Kit.log("vs05 at war " + Wars.atWar(a.id(), b.id()) + "; " + Vassals.lines(b.id()));
            helper.assertTrue(Wars.atWar(a.id(), b.id()), "the vassal rose against its overlord");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ vs06

    /** A vassal whose treasury holds the price buys its freedom: the coin to the overlord, the bond ended. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "vs06_freedom")
    public static void vs06_freedom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1837000;
        Villages.Village a = town(level, x, 8), b = town(level, x + 300, 6);
        helper.assertTrue(a != null && b != null, "two towns");
        helper.runAtTickTime(20, () -> {
            long day = level.getDayTime() / 24000L;
            Vassals.makeForTests(level, a.id(), b.id(), day);
            int price = Vassals.price(b.id());
            Ledger.addCoins(b.id(), price + 30);
            int aBefore = Ledger.coins(a.id());
            Vassals.dailyForTests(level, b, day + 1);
            Kit.log("vs06 price " + price + "; vassal " + Vassals.isVassal(b.id()) + "; the overlord " + aBefore + " -> " + Ledger.coins(a.id()));
            helper.assertTrue(!Vassals.isVassal(b.id()), "its freedom bought");
            helper.assertTrue(Ledger.coins(a.id()) == aBefore + price, "the price paid to the overlord");
            helper.assertTrue(chronicled(b.id(), "free of"), "the chronicle tells it");
            helper.succeed();
        });
    }
}
