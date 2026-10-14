package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkNeeds;
import com.jrpetty.mcassistant.entity.FolkProfile;
import com.jrpetty.mcassistant.entity.FolkSchedule;
import com.jrpetty.mcassistant.entity.FolkThought;
import com.jrpetty.mcassistant.entity.Individual;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.net.FolkProfileAskPayload;
import com.jrpetty.mcassistant.net.FolkProfilePayload;
import com.jrpetty.mcassistant.net.ProfileNetwork;
import com.jrpetty.mcassistant.village.BoardPages;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [folk-ui] Each folk as a person, and the board as a dashboard: the profile's tag says true things (its family, friends,
 * things, plan) and reaches only the player who asks, no sooner than it should; the day it plans is the day it lives
 * (at work, on its break, its evening, asleep); its needs read its real state and change what it does (tired: early to
 * bed; company: met by keeping it); every page the books ever had still opens, by number and by name, in exactly one of
 * the board's sections, and the Overview's alerts point at real pages.
 *
 * <p>Each on its own ground, x 1780000 to 1790000 on z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FolkProfileGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A town of so many folk at x, at this time of day on a day that is no day of rest, traits set, queues clear. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, int n, long timeOfDay) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        long day = 40;
        while (RestDay.today(village, day)) day++;
        level.setDayTime(day * 24000L + timeOfDay);
        level.updateSkyBrightness();
        for (VillageFolkEntity f : folk) {
            f.ensurePersona();
            f.life().setTraitsForTests(Social.Trait.EASYGOING, Social.Trait.CHEERFUL);
            Individual.ensure(f);
            f.clearQueue();
            f.setJob(StationTask.FARM);
            FolkNeeds.historyForTests(f, day, day, 0, level.getGameTime(), -1);   // came today, slept, fed, no company yet
        }
        return folk;
    }

    private static void at(ServerLevel level, long timeOfDay) {
        level.setDayTime(level.getDayTime() / 24000L * 24000L + timeOfDay);
        level.updateSkyBrightness();
    }

    private static boolean listHas(ListTag l, UUID id) {
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).hasUUID("uuid") && l.getCompound(i).getUUID("uuid").equals(id)) return true;
        return false;
    }

    // ============================================================ fp01: the profile says true things

    /** A folk with a partner, a child, a friend and bread in its pack: its profile has all four, its day covers 24 hours. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fp01_profile_true")
    public static void fp01_profile_true(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 1780000, 4, 3000L);
        VillageFolkEntity a = t.get(0), b = t.get(1), c = t.get(2), d = t.get(3);
        a.life().partnerWith(b.getUUID(), b.displayNameCap());
        b.life().partnerWith(a.getUUID(), a.displayNameCap());
        c.life().setParents(a.displayNameCap(), b.displayNameCap());
        a.life().hadAChild();
        a.life().feel(d.getUUID(), d.displayNameCap(), Social.FRIEND + 20);
        a.insertItem(new ItemStack(Items.BREAD, 5));
        CompoundTag p = FolkProfile.build(a, null);
        CompoundTag head = p.getCompound("head"), life = p.getCompound("life");
        helper.assertTrue(head.getString("name").equals(a.displayNameCap()), "its name: " + head.getString("name"));
        helper.assertTrue(head.getIntArray("needs").length == FolkNeeds.COUNT, "four needs");
        for (int v : head.getIntArray("needs")) helper.assertTrue(v >= 0 && v <= 100, "a need in 0..100: " + v);
        helper.assertTrue(!head.getString("thought").isEmpty(), "a thought");
        helper.assertTrue(life.getCompound("partner").hasUUID("uuid") && life.getCompound("partner").getUUID("uuid").equals(b.getUUID()),
            "its partner is " + b.displayNameCap() + ": " + life.getCompound("partner"));
        helper.assertTrue(listHas(life.getList("children", Tag.TAG_COMPOUND), c.getUUID()), "its child " + c.displayNameCap() + " listed");
        helper.assertTrue(listHas(life.getList("friends", Tag.TAG_COMPOUND), d.getUUID()), "its friend " + d.displayNameCap() + " listed");
        helper.assertTrue(!listHas(life.getList("friends", Tag.TAG_COMPOUND), b.getUUID()), "its partner is not counted as a friend too");
        // The child's own profile has both its parents, a click away.
        ListTag parents = FolkProfile.build(c, null).getCompound("life").getList("parent_list", Tag.TAG_COMPOUND);
        helper.assertTrue(listHas(parents, a.getUUID()) && listHas(parents, b.getUUID()), "the child's parents: " + parents);
        boolean bread = false;
        ListTag items = p.getCompound("things").getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) bread |= items.getCompound(i).getString("id").equals("minecraft:bread");
        helper.assertTrue(bread, "the bread in its pack is among its things: " + items);
        helper.assertTrue(p.getCompound("things").getInt("purse") == a.purse(), "its purse as it is");
        helper.assertTrue(p.getCompound("work").getInt("level") == a.veteranLevel(), "its level as it is");
        ListTag slots = p.getCompound("day").getList("slots", Tag.TAG_COMPOUND);
        int end = 0;
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag s = slots.getCompound(i);
            helper.assertTrue(s.getInt("from") == end, "the day has no gap at " + end + ": " + slots);
            end = s.getInt("to");
        }
        helper.assertTrue(end == 24000, "the day runs to its end: " + end);
        for (String tab : FolkProfile.TABS) helper.assertTrue(p.getCompound("card").contains(tab), "the card's lines for " + tab);
        helper.succeed();
    }

    // ============================================================ fp02: synced to the one who asks, and not too often

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fp02_profile_synced")
    public static void fp02_profile_synced(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 1782000, 1, 3000L);
        VillageFolkEntity a = t.get(0);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        FolkProfilePayload first = ProfileNetwork.answer(player, new FolkProfileAskPayload(a.getUUID(), true));
        helper.assertTrue(first != null && first.open() && first.data().getUUID("uuid").equals(a.getUUID()), "the profile, to open");
        helper.assertTrue(first.data().getCompound("life").contains("you"), "what it thinks of the player who asked");
        helper.assertTrue(ProfileNetwork.answer(player, new FolkProfileAskPayload(a.getUUID(), false)) == null,
            "a refresh asked for at once is dropped");
        helper.assertTrue(ProfileNetwork.answer(player, new FolkProfileAskPayload(UUID.randomUUID(), true)) == null, "nobody: no answer");
        a.earn(7);
        helper.runAfterDelay(12, () -> {
            FolkProfilePayload again = ProfileNetwork.answer(player, new FolkProfileAskPayload(a.getUUID(), false));
            helper.assertTrue(again != null && !again.open(), "a refresh after the gap");
            helper.assertTrue(again.data().getCompound("things").getInt("purse") == a.purse(), "the refresh has its purse as it is now: "
                + again.data().getCompound("things").getInt("purse") + " vs " + a.purse());
            player.discard();
            helper.succeed();
        });
    }

    // ============================================================ fp03: the plan is the day it lives

    /** At work, on its break, of an evening and at night: what its plan says, it is doing, and its shift agrees. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fp03_schedule_lived")
    public static void fp03_schedule_lived(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> t = town(helper, 1784000, 1, 3000L);
        VillageFolkEntity f = t.get(0);
        long bs = f.breakStart(), bl = f.breakLength();
        helper.assertTrue(bs < 12000 && bl > 0, "a break inside the working day: " + bs + " for " + bl);
        long work = bs >= 1500 ? bs - 600 : bs + bl + 600;
        at(level, work);
        Kit.log("fp03 work at " + work + ": plan " + FolkSchedule.planned(f, level.getDayTime()) + ", now " + FolkSchedule.now(f) + ", why '" + f.offWorkWhy() + "'");
        helper.assertTrue(FolkSchedule.planned(f, level.getDayTime()) == FolkSchedule.Kind.WORK, "planned: work at " + work);
        helper.assertTrue(FolkSchedule.now(f) == FolkSchedule.Kind.WORK && !f.offWorkNow(), "at work, and on shift: " + f.offWorkWhy());
        at(level, bs + bl / 2);
        helper.assertTrue(FolkSchedule.planned(f, level.getDayTime()) == FolkSchedule.Kind.BREAK, "planned: its break");
        helper.assertTrue(FolkSchedule.now(f) == FolkSchedule.Kind.BREAK && f.offWorkNow(), "on its break: " + f.offWorkWhy());
        at(level, 13000L);
        helper.assertTrue(f.bedtimeTick() > 13000L, "its bedtime is after the evening: " + f.bedtimeTick());
        helper.assertTrue(FolkSchedule.planned(f, level.getDayTime()) == FolkSchedule.Kind.FREE, "planned: free time of an evening");
        helper.assertTrue(FolkSchedule.now(f) == FolkSchedule.Kind.FREE && f.offWorkNow(), "off work of an evening: " + f.offWorkWhy());
        at(level, 20000L);
        helper.assertTrue(FolkSchedule.planned(f, level.getDayTime()) == FolkSchedule.Kind.SLEEP, "planned: asleep at night");
        helper.assertTrue(FolkSchedule.now(f) == FolkSchedule.Kind.SLEEP && !f.onShift(), "in bed, or going: " + FolkSchedule.now(f));
        helper.succeed();
    }

    // ============================================================ fp04: needs read its life and change what it does

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fp04_needs_drive")
    public static void fp04_needs_drive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> t = town(helper, 1786000, 2, 10000L);
        VillageFolkEntity a = t.get(0), b = t.get(1);
        long day = level.getDayTime() / 24000L, now = level.getGameTime();
        int[] fine = FolkNeeds.nowForTests(a).clone();
        long rested = a.bedtimeTick();
        helper.assertTrue(fine[FolkNeeds.FOOD] >= FolkNeeds.LOW && fine[FolkNeeds.REST] >= FolkNeeds.LOW, "fed and rested: "
            + java.util.Arrays.toString(fine));
        // Five days here, no bed for three nights, two meals missed, nothing eaten for twelve hours.
        FolkNeeds.historyForTests(a, day - 5, day - 4, 2, now - 12000L, -1);
        int[] worn = FolkNeeds.nowForTests(a).clone();
        Kit.log("fp04 fine " + java.util.Arrays.toString(fine) + " worn " + java.util.Arrays.toString(worn));
        helper.assertTrue(worn[FolkNeeds.FOOD] < FolkNeeds.LOW, "hungry after missed meals: " + worn[FolkNeeds.FOOD]);
        helper.assertTrue(worn[FolkNeeds.REST] < FolkNeeds.LOW, "tired after nights without a bed: " + worn[FolkNeeds.REST]);
        helper.assertTrue(a.bedtimeTick() < rested, "worn out, it goes to bed earlier: " + a.bedtimeTick() + " vs " + rested);
        String thought = FolkThought.line(a);
        helper.assertTrue(thought.contains("famished") || thought.contains("Food first"), "it thinks of food first: " + thought);
        // Lonely (no partner, no friends, nobody lately); an evening beside a neighbour mends it.
        helper.assertTrue(worn[FolkNeeds.SOCIAL] < FolkNeeds.LOW, "lonely: " + worn[FolkNeeds.SOCIAL]);
        at(level, 13000L);
        b.moveTo(a.getX() + 2, a.getY(), a.getZ(), 0F, 0F);
        a.socialBeat();
        helper.assertTrue(a.companyAt() >= now, "company kept beside a neighbour of an evening");
        int social = FolkNeeds.nowForTests(a)[FolkNeeds.SOCIAL];
        helper.assertTrue(social >= FolkNeeds.LOW, "company met: " + social);
        helper.succeed();
    }

    // ============================================================ fp05: every page the books had still opens

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fp05_board_pages")
    public static void fp05_board_pages(GameTestHelper helper) {
        // Every page in exactly one section, and every section's page a page.
        for (int i = 0; i < BoardPages.PAGES.length; i++) {
            int in = 0;
            for (BoardPages.Section s : BoardPages.SECTIONS) for (String p : s.pages()) if (p.equals(BoardPages.PAGES[i])) in++;
            helper.assertTrue(in == 1, BoardPages.PAGES[i] + " is in " + in + " sections");
        }
        for (BoardPages.Section s : BoardPages.SECTIONS) {
            for (String p : s.pages()) helper.assertTrue(BoardPages.pageNumber(p) >= 0, s.name() + " names no page " + p);
            helper.assertTrue(BoardPages.pageNumber(s.name()) == BoardPages.pageNumber(s.pages()[0]), "the section " + s.name() + " opens at its first page");
        }
        // The old numbers (/village stats, the smoke test, Quarters, PlayerStalls, JobMarketCommands) open the old pages.
        String[][] numbered = { { "0", "Overview" }, { "2", "Money" }, { "4", "Shops" }, { "5", "Jobs" }, { "6", "Folk" }, { "7", "Society" },
            { "9", "Homes" }, { "10", "Buildings" }, { "11", "Stores" }, { "17", "News" }, { "20", "Museum" }, { "21", "Culture" } };
        for (String[] n : numbered) {
            int page = Integer.parseInt(n[0]);
            helper.assertTrue(BoardPages.PAGES[page].equals(n[1]), "page " + page + " is " + BoardPages.PAGES[page] + ", not " + n[1]);
        }
        // Every name the commands open the books at.
        for (String name : new String[]{ "Trade", "War map", "Interviews", "War", "Identity", "School", "Caves", "Watch", "Trading Post",
                "Auction", "Cases", "Maps", "Prices", "Nether", "News", "Transport" }) {
            helper.assertTrue(BoardPages.pageNumber(name) >= 0, "the page " + name + " opens");
        }
        // The Overview's readings and alerts, for a real town: in range, and every alert a page.
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> t = town(helper, 1788000, 3, 3000L);
        Villages.Village v = Villages.get(t.get(0).ownerId());
        CompoundTag books = Annals.snapshot(level, v);
        CompoundTag ov = books.getCompound("overview");
        helper.assertTrue(ov.getInt("safety") >= 0 && ov.getInt("safety") <= 100 && ov.getInt("looks") >= 0 && ov.getInt("looks") <= 100,
            "safety and looks in 0..100: " + ov.getInt("safety") + ", " + ov.getInt("looks"));
        helper.assertTrue(ov.getInt("adults") >= ov.getInt("workers"), "no more at work than grown");
        ListTag alerts = ov.getList("alerts", Tag.TAG_COMPOUND);
        for (int i = 0; i < alerts.size(); i++) {
            helper.assertTrue(BoardPages.pageNumber(alerts.getCompound(i).getString("page")) >= 0, "the alert's page opens: " + alerts.getCompound(i));
        }
        ListTag people = books.getList("people", Tag.TAG_COMPOUND);
        helper.assertTrue(!people.isEmpty() && !people.getCompound(0).getString("now").isEmpty(), "each folk row says what it is doing now");
        helper.succeed();
    }
}
