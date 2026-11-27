package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Favourites;
import com.jrpetty.mcassistant.entity.FolkIdentity;
import com.jrpetty.mcassistant.entity.FolkProfile;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Individual;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.net.FavouritesAskPayload;
import com.jrpetty.mcassistant.net.FavouritesNetwork;
import com.jrpetty.mcassistant.net.FavouritesPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * [ui] Favourites and the folk's own identity on its screens: a player follows a folk and stops (the list capped);
 * the list and each folk's record survive a save and a load; a snapshot goes into its history each morning, and what
 * changed (a rise) onto its timeline; the history is kept to its size; a folk that dies is remembered on the list with
 * its day; one that is unloaded is still listed as it was last seen; the profile carries its colour, saying and
 * chips, and the talk screen's context its colour and its star; the wire answers the one who asks, no sooner than it
 * should.
 *
 * <p>Each on its own ground, x 3400000 to 3500000 on z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class UiGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    /** A town of so many folk at x, the favourites book empty. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, int n) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Favourites.resetForTests();
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i % 12).east(i / 12 * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            folk.add(f);
        }
        level.setDayTime(40 * 24000L + 3000L);
        for (VillageFolkEntity f : folk) {
            f.ensurePersona();
            f.life().setTraitsForTests(Social.Trait.HARDWORKING, Social.Trait.CHEERFUL);
            Individual.ensure(f);
            f.clearQueue();
            f.setJob(StationTask.FARM);
        }
        return folk;
    }

    private static boolean listed(ServerPlayer p, VillageFolkEntity f) {
        ListTag cards = Favourites.listTag(p).getList("cards", Tag.TAG_COMPOUND);
        for (int i = 0; i < cards.size(); i++) if (cards.getCompound(i).getUUID("uuid").equals(f.getUUID())) return true;
        return false;
    }

    // ============================================================ ui01: follow, unfollow, the cap

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ui01_follow")
    public static void ui01_follow(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 3400000, Favourites.CAP + 1);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0);
        helper.assertTrue(Favourites.list(p.getUUID()).isEmpty(), "nobody followed before a star");
        helper.assertTrue(Favourites.add(p, a) == Favourites.Added.ADDED, "added");
        helper.assertTrue(Favourites.add(p, a) == Favourites.Added.ALREADY, "added once only");
        helper.assertTrue(Favourites.has(p.getUUID(), a.getUUID()) && listed(p, a), "on the list and its cards");
        helper.assertTrue(Favourites.historySize(a.getUUID()) == 1, "its first snapshot: " + Favourites.historySize(a.getUUID()));
        helper.assertTrue(Favourites.last(a.getUUID()).getString("name").equals(a.displayNameCap()), "its snapshot has its name");
        for (int i = 1; i < Favourites.CAP; i++) helper.assertTrue(Favourites.add(p, t.get(i)) == Favourites.Added.ADDED, "added " + i);
        helper.assertTrue(Favourites.add(p, t.get(Favourites.CAP)) == Favourites.Added.FULL, "the list full at " + Favourites.CAP);
        // Moved to the foot of the list, then taken off.
        helper.assertTrue(Favourites.move(p.getUUID(), a.getUUID(), 99), "moved down");
        List<java.util.UUID> l = Favourites.list(p.getUUID());
        helper.assertTrue(l.get(l.size() - 1).equals(a.getUUID()), "at the foot of the list");
        helper.assertTrue(Favourites.remove(p.getUUID(), a.getUUID()), "taken off");
        helper.assertTrue(!Favourites.has(p.getUUID(), a.getUUID()) && Favourites.historySize(a.getUUID()) == 0,
            "off the list, and its record gone with nobody following it");
        helper.assertTrue(!Favourites.remove(p.getUUID(), a.getUUID()), "not taken off twice");
        Kit.log("[VT] ui01 followed " + Favourites.list(p.getUUID()).size() + " of " + Favourites.CAP);
        p.discard();
        helper.succeed();
    }

    // ============================================================ ui02: kept across a save and a load

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ui02_saved")
    public static void ui02_saved(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 3402000, 2);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0), b = t.get(1);
        Favourites.add(p, a);
        Favourites.add(p, b);
        Favourites.move(p.getUUID(), b.getUUID(), -1);
        Favourites.historyForTests(a.getUUID(), 5);
        int events = Favourites.events(a.getUUID()).size();
        Favourites.reloadForTests(helper.getLevel().registryAccess());
        List<java.util.UUID> l = Favourites.list(p.getUUID());
        helper.assertTrue(l.size() == 2 && l.get(0).equals(b.getUUID()) && l.get(1).equals(a.getUUID()), "the list in its order after a load: " + l);
        helper.assertTrue(Favourites.historySize(a.getUUID()) == 6, "its history after a load: " + Favourites.historySize(a.getUUID()));
        helper.assertTrue(Favourites.events(a.getUUID()).size() == events && events > 0, "its events after a load: " + events);
        helper.assertTrue(Favourites.last(a.getUUID()).getString("trade").equals(Favourites.last(a.getUUID()).getString("trade"))
            && !Favourites.last(a.getUUID()).getString("name").isEmpty(), "its last snapshot after a load");
        p.discard();
        helper.succeed();
    }

    // ============================================================ ui03: a snapshot each morning, a rise on its timeline

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ui03_mornings")
    public static void ui03_mornings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> t = town(helper, 3404000, 1);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0);
        Favourites.add(p, a);
        Favourites.morning(level.getServer(), level.getDayTime() / 24000L);    // today's done: the next is tomorrow's
        int before = Favourites.historySize(a.getUUID());
        int lvl = a.veteranLevel();
        a.awardXp(com.jrpetty.mcassistant.entity.AssistantEntity.xpForLevel(lvl + 2) + 1);
        a.earn(40);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 100L);   // the next morning
        helper.runAfterDelay(140, () -> {                                    // the book's beat: every hundred ticks
            int after = Favourites.historySize(a.getUUID());
            helper.assertTrue(after == before + 1, "a snapshot the next morning: " + before + " -> " + after);
            CompoundTag last = Favourites.last(a.getUUID());
            helper.assertTrue(last.getInt("level") == a.veteranLevel() && last.getInt("level") > lvl, "its level in it: " + last.getInt("level"));
            boolean rise = false;
            for (CompoundTag e : Favourites.events(a.getUUID())) rise |= e.getString("kind").equals("level");
            helper.assertTrue(rise, "its rise on its timeline: " + Favourites.events(a.getUUID()));
            CompoundTag card = Favourites.listTag(p).getList("cards", Tag.TAG_COMPOUND).getCompound(0);
            helper.assertTrue(card.getInt("d1_level") > 0, "the card shows the rise since yesterday: " + card.getInt("d1_level"));
            CompoundTag d = Favourites.detailTag(p, a.getUUID());
            helper.assertTrue(d != null && d.getIntArray("level").length == after && d.getLongArray("days").length == after,
                "the detail's series as long as its history");
            helper.assertTrue(d.getCompound("self").getInt("colour") != 0 || d.getCompound("self").contains("colour"), "its identity in the detail");
            Kit.log("[VT] ui03 history " + before + " -> " + after + ", events " + Favourites.events(a.getUUID()).size());
            p.discard();
            helper.succeed();
        });
    }

    // ============================================================ ui04: the history kept to its size

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ui04_capped")
    public static void ui04_capped(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 3406000, 1);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0);
        Favourites.add(p, a);
        Favourites.historyForTests(a.getUUID(), 400);
        int n = Favourites.historySize(a.getUUID());
        helper.assertTrue(n <= Favourites.DAILY + Favourites.WEEKLY && n > Favourites.DAILY, "kept to its size: " + n);
        long[] days = Favourites.detailTag(p, a.getUUID()).getLongArray("days");
        for (int i = 1; i < days.length; i++) helper.assertTrue(days[i] > days[i - 1], "oldest first, no repeats at " + i);
        for (int i = 1; i < days.length - Favourites.DAILY; i++) helper.assertTrue(days[i] - days[i - 1] >= 7, "older ones a week apart at " + i);
        for (int i = days.length - Favourites.DAILY + 1; i < days.length; i++) helper.assertTrue(days[i] - days[i - 1] == 1, "the last sixty day by day");
        Kit.log("[VT] ui04 kept " + n + " snapshots, from day " + days[0] + " to " + days[days.length - 1]);
        p.discard();
        helper.succeed();
    }

    // ============================================================ ui05: the dead remembered

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ui05_remembered")
    public static void ui05_remembered(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 3408000, 2);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0);
        String name = a.displayNameCap();
        Favourites.add(p, a);
        a.kill();
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(Favourites.dead(a.getUUID()), "remembered as dead");
            helper.assertTrue(Favourites.has(p.getUUID(), a.getUUID()), "still on the list");
            List<CompoundTag> ev = Favourites.events(a.getUUID());
            helper.assertTrue(!ev.isEmpty() && ev.get(ev.size() - 1).getString("kind").equals("died"), "its death on its timeline: " + ev);
            CompoundTag card = Favourites.listTag(p).getList("cards", Tag.TAG_COMPOUND).getCompound(0);
            helper.assertTrue(card.getBoolean("dead") && card.getLong("died") >= 0 && card.getString("name").equals(name), "its card: remembered, with its day");
            helper.assertTrue(Favourites.byName(p.getUUID(), name) != null, "found on the list by name, dead");
            helper.assertTrue(Favourites.remove(p.getUUID(), a.getUUID()), "and may be taken off");
            p.discard();
            helper.succeed();
        });
    }

    // ============================================================ ui06: an unloaded folk still listed

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ui06_away")
    public static void ui06_away(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 3410000, 2);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0);
        String name = a.displayNameCap();
        int level = a.veteranLevel();
        Favourites.add(p, a);
        a.discard();                                                     // gone from the world as an unloaded folk is
        helper.runAfterDelay(3, () -> {
            CompoundTag card = Favourites.listTag(p).getList("cards", Tag.TAG_COMPOUND).getCompound(0);
            helper.assertTrue(card.getString("name").equals(name) && card.getInt("level") == level, "listed as last seen: " + card);
            helper.assertTrue(!card.getBoolean("about") && !card.getBoolean("dead") && card.getInt("entity") == -1, "away, not dead");
            CompoundTag d = Favourites.detailTag(p, a.getUUID());
            helper.assertTrue(d != null && !d.getBoolean("about") && d.getCompound("last").getString("name").equals(name), "its detail as last seen");
            Favourites.lookAll(helper.getLevel().getServer());                // looking at the others leaves it be
            helper.assertTrue(Favourites.has(p.getUUID(), a.getUUID()) && !Favourites.dead(a.getUUID()), "still followed");
            p.discard();
            helper.succeed();
        });
    }

    // ============================================================ ui07: its identity on its screens

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ui07_identity")
    public static void ui07_identity(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 3412000, 2);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0), b = t.get(1);
        b.life().setTraitsForTests(Social.Trait.SHY, Social.Trait.CURIOUS);
        CompoundTag self = FolkProfile.build(a, p).getCompound("self");
        helper.assertTrue(self.getInt("colour") == FolkIdentity.rgb(a), "its colour");
        helper.assertTrue(!self.getString("saying").isEmpty(), "a saying");
        ListTag chips = self.getList("chips", Tag.TAG_COMPOUND);
        boolean hard = false, cheer = false;
        for (int i = 0; i < chips.size(); i++) {
            hard |= chips.getCompound(i).getString("t").equals("Hardworking");
            cheer |= chips.getCompound(i).getString("t").equals("Cheerful");
        }
        helper.assertTrue(hard && cheer, "its traits as chips: " + chips);
        helper.assertTrue(!self.getList("own", Tag.TAG_COMPOUND).isEmpty(), "its own life");
        ListTag bChips = FolkProfile.build(b, p).getCompound("self").getList("chips", Tag.TAG_COMPOUND);
        helper.assertTrue(!bChips.getCompound(0).getString("t").equals(chips.getCompound(0).getString("t")), "another folk's chips are its own");
        helper.assertTrue(!FolkProfile.build(a, p).getBoolean("fav"), "not starred before");
        String ctx = FolkTalk.talkContext(a, p);
        helper.assertTrue(ctx.contains(" colour=" + Integer.toHexString(FolkIdentity.rgb(a))) && !ctx.contains(" fav"), "the talk context's colour: " + ctx);
        Favourites.add(p, a);
        helper.assertTrue(FolkProfile.build(a, p).getBoolean("fav") && FolkTalk.talkContext(a, p).contains(" fav"), "starred on both screens");
        p.discard();
        helper.succeed();
    }

    // ============================================================ ui08: the wire

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ui08_wire")
    public static void ui08_wire(GameTestHelper helper) {
        List<VillageFolkEntity> t = town(helper, 3414000, 1);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        VillageFolkEntity a = t.get(0);
        FavouritesPayload star = FavouritesNetwork.answer(p, new FavouritesAskPayload("toggle", a.getUUID(), 0));
        helper.assertTrue(star != null && star.data().getString("kind").equals("star") && star.data().getBoolean("fav"), "a star adds it");
        FavouritesPayload list = FavouritesNetwork.answer(p, new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 1));
        helper.assertTrue(list != null && list.data().getList("cards", Tag.TAG_COMPOUND).size() == 1, "the list, asked for on opening");
        helper.assertTrue(FavouritesNetwork.answer(p, new FavouritesAskPayload("list", FavouritesAskPayload.NOBODY, 0)) == null,
            "a refresh asked for at once is dropped");
        FavouritesPayload detail = FavouritesNetwork.answer(p, new FavouritesAskPayload("detail", a.getUUID(), 1));
        helper.assertTrue(detail != null && detail.data().getString("kind").equals("detail"), "its detail, asked for");
        FavouritesPayload off = FavouritesNetwork.answer(p, new FavouritesAskPayload("toggle", a.getUUID(), 0));
        helper.assertTrue(off != null && !off.data().getBoolean("fav") && !Favourites.has(p.getUUID(), a.getUUID()), "a star again takes it off");
        helper.assertTrue(FavouritesNetwork.answer(p, new FavouritesAskPayload("detail", a.getUUID(), 1)) == null, "no detail of one not followed");
        p.discard();
        helper.succeed();
    }
}
