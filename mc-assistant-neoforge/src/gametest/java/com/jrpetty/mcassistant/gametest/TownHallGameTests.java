package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.client.TalkMenu;
import com.jrpetty.mcassistant.entity.ArrivalCard;
import com.jrpetty.mcassistant.entity.Belonging;
import com.jrpetty.mcassistant.entity.Citizens;
import com.jrpetty.mcassistant.entity.Crime;
import com.jrpetty.mcassistant.entity.FirstWeek;
import com.jrpetty.mcassistant.entity.IdCard;
import com.jrpetty.mcassistant.entity.InWorld;
import com.jrpetty.mcassistant.entity.Jury;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.Muster;
import com.jrpetty.mcassistant.entity.Planner;
import com.jrpetty.mcassistant.entity.PlayerLeader;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.ServicePoints;
import com.jrpetty.mcassistant.entity.ServiceRole;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.TownHall;
import com.jrpetty.mcassistant.entity.TownHallDesk;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.TownHallItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * [wpa] The Town Hall and belonging: the hall's desk set in and opening the hall's page, the register, the identity
 * (a name of the player's own over its head, unique, renamed for a fee with a record), the papers, citizenship by the
 * checklist and a fee asked for first, the jury called to a real sitting and waited for, a vote on what is put to the
 * town, the militia and the army's button, the leader's office, the arrival card, the first week, and no player-facing
 * line that tells a player to type a command. Each on its own ground (x 8,120,000 to 8,300,000, z 91,000), a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownHallGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 91000;

    /** Flat ground, a town at its heart, and so many folk with coin. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int n) {
        Kit.reset(level);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos ground = Kit.surface(level, x, Z);
        int y = ground.getY();
        for (int dx = -30; dx <= 30; dx++) {
            for (int dz = -30; dz <= 30; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, Z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int h = 0; h <= 14; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 3 + (i % 4) * 2, y, Z - 4 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.earn(40);
            folk.add(f);
        }
        return folk;
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        if (coins > 0) p.getInventory().setItem(0, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        Belonging.resetForTests(p.getUUID());
        Planner.resetForTests(p.getUUID());
        return p;
    }

    private static int coins(ServerPlayer p) {
        return p.getInventory().countItem(McAssistantMod.VILLAGE_COIN.get());
    }

    private static void friend(UUID village, ServerPlayer p, List<VillageFolkEntity> folk) {
        for (VillageFolkEntity f : folk) f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 60);
        Standing.stir(village, p.getUUID());
    }

    /** A button on the page by its label: its "on" and its "why". */
    private static CompoundTag button(CompoundTag page, String label) {
        for (Tag t : page.getList("blocks", Tag.TAG_COMPOUND)) {
            ListTag b = ((CompoundTag) t).getList("b", Tag.TAG_COMPOUND);
            for (int i = 0; i < b.size(); i++) if (b.getCompound(i).getString("l").equals(label)) return b.getCompound(i);
        }
        return null;
    }

    private static String act(ServerPlayer p, String action, CompoundTag args) {
        PlayerLife.act(p, "hall", action, args == null ? new CompoundTag() : args);
        return PlayerLife.messageForTests(p);
    }

    private static CompoundTag form(Object... kv) {
        CompoundTag a = new CompoundTag();
        a.put("f", LifePage.args(kv));
        return a;
    }

    /** A meeting hall put up on the town's books, south of the heart, its door toward the heart. */
    private static BlockPos hall(ServerLevel level, UUID id, BlockPos heart) {
        BlockPos at = heart.offset(0, 0, -14);
        BuildGoal.stamp(level, "hall", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "hall", at, Direction.NORTH);
        return at;
    }

    // ============================================================ th01: the desk

    /** A hall without a desk has one set in; the desk is the HALL point, and a click on it opens the Town Hall's page. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th01_hall_desk")
    public static void th01_hall_desk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8120000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            hall(level, id, heart);
            BlockPos desk = TownHallDesk.fitForTests(level, v);
            Kit.log("[VT] th01 desk " + desk);
            helper.assertTrue(desk != null, "a desk is set in the hall");
            helper.assertTrue(level.getBlockState(desk).is(McAssistantMod.SERVICE_COUNTER.get()), "a service counter");
            ServicePoints.Point pt = ServicePoints.at(level, desk);
            helper.assertTrue(pt != null && pt.role() == ServiceRole.HALL, "the HALL's point: " + (pt == null ? "none" : pt.role()));
            helper.assertTrue(desk.equals(TownHallDesk.desk(level, id)), "the town's desk");
            ServerPlayer p = player(helper, desk.relative(pt.front()), 0);
            ServicePoints.openAt(p, desk);
            helper.assertTrue(PlayerLife.ctx(p).page.equals("hall"), "the desk opens the Town Hall's page: " + PlayerLife.ctx(p).page);
            CompoundTag page = PlayerLife.pageForTests(p);
            Kit.log("[VT] th01 " + page.getString("title") + " / " + page.getString("sub"));
            helper.assertTrue(page.getString("title").endsWith("Town Hall"), "its title");
            helper.assertTrue(TownHallDesk.atDesk(p, v), "at the desk");
            helper.assertTrue(TownHallDesk.spawnFor(level, v) != null, "a place to wake");
            helper.succeed();
        });
    }

    // ============================================================ th02: the register

    /** Join village: at the desk only (Show me points the way otherwise); then a resident, waking at the hall. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th02_register")
    public static void th02_register(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8130000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            hall(level, id, heart);
            BlockPos desk = TownHallDesk.fitForTests(level, v);
            helper.assertTrue(desk != null, "a desk");
            ServerPlayer p = player(helper, heart.offset(20, 0, 20), 0);
            TownHall.open(p, "join");
            CompoundTag join = button(PlayerLife.pageForTests(p), "Join village");
            helper.assertTrue(join != null && !join.getBoolean("on") && !join.getString("why").isEmpty(), "away from the desk, Join rests and says why: " + join);
            String far = act(p, "register", null);
            helper.assertTrue(far.startsWith("!") && !Belonging.registered(id, p.getUUID()), "not from afar: " + far);
            String shown = act(p, "show", null);
            Kit.log("[VT] th02 show: " + shown);
            helper.assertTrue(shown.contains("arrow"), "Show me points the way");
            ServicePoints.Point pt = ServicePoints.at(level, desk);
            p.moveTo(desk.relative(pt.front()).getX() + 0.5, desk.getY(), desk.relative(pt.front()).getZ() + 0.5);
            String said = act(p, "register", null);
            Kit.log("[VT] th02 register: " + said);
            helper.assertTrue(Belonging.registered(id, p.getUUID()) && !said.startsWith("!"), "registered: " + said);
            helper.assertTrue(said.contains("live in") && said.contains("Next"), "what happened and what's next: " + said);
            helper.assertTrue(p.getRespawnPosition() != null, "wakes at the hall");
            helper.assertTrue(Belonging.residents(id).contains(p.getUUID()), "on the town's list of residents");
            helper.assertTrue(act(p, "register", null).contains("already"), "once");
            Belonging.roundTripForTests(level);
            helper.assertTrue(Belonging.registered(id, p.getUUID()), "kept over a save");
            helper.succeed();
        });
    }

    // ============================================================ th03: the identity

    /**
     * A name of the player's own: written in after registering, over its head (its display name) and the town's word for
     * it; nobody else's (a second player is refused it); a new name later costs its fee, asked first, and the old one
     * stays on record.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th03_identity")
    public static void th03_identity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8140000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 30);
            String early = act(p, "identity", form("first", "Ada", "last", "Marsh"));
            helper.assertTrue(early.startsWith("!") && Belonging.rp(p.getUUID()) == null, "no name before the book: " + early);
            act(p, "register", null);                                   // no hall: the elder's book, anywhere in town
            helper.assertTrue(Belonging.registered(id, p.getUUID()), "on the book");
            String bad = act(p, "identity", form("first", "Ad4", "last", "Marsh"));
            helper.assertTrue(bad.startsWith("!"), "letters only: " + bad);
            String said = act(p, "identity", form("first", "ada", "last", "Marsh", "bio", "Baker's daughter, new in town.", "age", "25", "trade", "cook", "look", "worker"));
            Kit.log("[VT] th03 identity: " + said + " / display " + p.getDisplayName().getString());
            Belonging.Rp rp = Belonging.rp(p.getUUID());
            helper.assertTrue(rp != null && rp.full().equals("Ada Marsh"), "written in (capitalised): " + said);
            helper.assertTrue(p.getDisplayName().getString().equals("Ada Marsh"), "over its head and in chat: " + p.getDisplayName().getString());
            helper.assertTrue(Belonging.called(p).equals("Ada Marsh") && Belonging.first(p).equals("Ada"), "what the town calls it");
            // Nobody else's.
            ServerPlayer q = player(helper, heart.offset(2, 0, 0), 0);
            act(q, "register", null);
            String taken = act(q, "identity", form("first", "Ada", "last", "Marsh"));
            Kit.log("[VT] th03 taken: " + taken);
            helper.assertTrue(taken.startsWith("!") && Belonging.rp(q.getUUID()) == null, "the name is taken: " + taken);
            // A new name: the fee, asked first, the old name kept.
            PlayerLife.ctx(p).args.putBoolean("edit", true);
            int before = coins(p);
            act(p, "identity", form("first", "Ada", "last", "Brook", "bio", "", "age", "25", "trade", "cook", "look", "worker"));
            helper.assertTrue(PlayerLife.ctx(p).args.contains("ask") && Belonging.rp(p.getUUID()).last().equals("Marsh"), "asked before the fee");
            CompoundTag page = PlayerLife.pageForTests(p);
            helper.assertTrue(button(page, "Yes, " + Belonging.RENAME_FEE + "c") != null, "the question on the page");
            String renamed = act(p, "confirm", null);
            Kit.log("[VT] th03 renamed: " + renamed + " was " + Belonging.formerNames(p.getUUID()));
            helper.assertTrue(Belonging.rp(p.getUUID()).full().equals("Ada Brook"), "renamed: " + renamed);
            helper.assertTrue(coins(p) == before - Belonging.RENAME_FEE, "the fee paid: " + (before - coins(p)));
            helper.assertTrue(!Belonging.formerNames(p.getUUID()).isEmpty() && Belonging.formerNames(p.getUUID()).get(0).contains("Ada Marsh"), "the old name on record");
            String hide = act(p, "plate", LifePage.args("on", false));
            helper.assertTrue(p.getDisplayName().getString().equals(p.getGameProfile().getName()), "the plate hidden: " + hide);
            act(p, "plate", LifePage.args("on", true));
            helper.assertTrue(p.getDisplayName().getString().equals("Ada Brook"), "and shown");
            helper.succeed();
        });
    }

    // ============================================================ th04: the papers

    /** Papers at the desk: free the first time, in the pack and the player's own; a replacement for the fee. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th04_papers")
    public static void th04_papers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8150000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 10);
            act(p, "register", null);
            String said = act(p, "identity", form("first", "Wren", "last", "Holloway", "bio", "", "age", "0", "trade", "", "look", "plain"));
            Kit.log("[VT] th04 identity: " + said);
            helper.assertTrue(TownHallItems.carriesPapers(p, id), "papers given with the name at the book: " + said);
            helper.assertTrue(act(p, "papers", null).contains("already"), "not twice");
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 10));
            String again = act(p, "papers", null);
            Kit.log("[VT] th04 replaced: " + again);
            helper.assertTrue(TownHallItems.carriesPapers(p, id) && coins(p) == 10 - Belonging.PAPERS_FEE, "a replacement for the fee: " + again);
            // The card: its own, from the papers' owner.
            IdCard.openOwn(p);
            CompoundTag card = PlayerLife.pageForTests(p);
            helper.assertTrue(card.getString("title").contains("Wren Holloway"), "the card: " + card.getString("title"));
            helper.succeed();
        });
    }

    // ============================================================ th05: citizenship

    /** The checklist: each step and how far, Apply resting with a reason till all are met, the fee asked first, then a citizen. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th05_citizenship")
    public static void th05_citizenship(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8160000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 30);
            act(p, "register", null);
            TownHall.open(p, "citizen");
            CompoundTag apply = button(PlayerLife.pageForTests(p), "Apply (" + Citizens.FEE + "c)");
            Kit.log("[VT] th05 before: " + apply);
            helper.assertTrue(apply != null && !apply.getBoolean("on") && apply.getString("why").contains("friend"), "not a friend yet: says so");
            friend(id, p, folk);
            apply = button(PlayerLife.pageForTests(p), "Apply (" + Citizens.FEE + "c)");
            helper.assertTrue(apply != null && apply.getBoolean("on"), "ready: " + apply);
            act(p, "citizen", null);
            helper.assertTrue(!Citizens.is(id, p.getUUID()) && PlayerLife.ctx(p).args.contains("ask"), "asked first");
            String said = act(p, "confirm", null);
            Kit.log("[VT] th05 " + said);
            helper.assertTrue(Citizens.is(id, p.getUUID()), "a citizen: " + said);
            helper.assertTrue(coins(p) == 30 - Citizens.FEE, "the fee paid");
            helper.assertTrue(said.contains("Next"), "and what's next");
            helper.succeed();
        });
    }

    // ============================================================ th06: the jury

    /**
     * A citizen on the jury roll is called when the council sits; the council waits for the jury's word after it has heard
     * the case, and the juror's verdict is counted: the innocent with nothing against it is acquitted, the jury's vote in.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "th06_jury")
    public static void th06_jury(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8170000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 2500L);
        int[] id = { -1 };
        ServerPlayer[] pl = { null };
        boolean[] voted = { false };
        helper.runAtTickTime(20, () -> {
            UUID village = folk.get(0).ownerId();
            Villages.Village v = Villages.get(village);
            BlockPos heart = Kit.surface(level, x, Z);
            VillageFolkEntity victim = folk.get(0), culprit = folk.get(1);
            VillageFolkEntity innocent = VillageFolkSpawnerBlock.raise(level, heart.offset(12, 0, 0), 0.0F);
            helper.assertTrue(innocent != null, "the innocent");
            Crime.hurryForTests(true);
            victim.setNoAi(true);
            victim.spend(victim.purse());
            victim.earn(10);
            culprit.spend(culprit.purse());
            culprit.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.EASYGOING);
            Values.setForTests(culprit, Values.Value.WEALTH, 85);
            Crime.honestyForTests(culprit, 20);
            Crime.honestyForTests(innocent, 70);
            ServerPlayer p = player(helper, heart.offset(1, 0, 1), 0);
            pl[0] = p;
            Ledger.addCitizen(village, p.getUUID(), p.getGameProfile().getName());
            act(p, "register", null);
            String roll = act(p, "jury", LifePage.args("on", true));
            helper.assertTrue(Belonging.onJuryRoll(village, p.getUUID()), "on the roll: " + roll);
            Crime.Case c = Crime.commitForTests(level, culprit, Crime.Kind.PICKPOCKET, victim, null);
            helper.assertTrue(c != null, "a purse picked");
            Crime.reportForTests(level, c);
            Crime.accuseForTests(c, innocent);
            helper.assertTrue(Crime.trialNowForTests(level, v), "the council sits");
            helper.assertTrue(Jury.calledTo(p.getUUID()) == c.id, "the juror called");
            helper.assertTrue(!Planner.of(p.getUUID(), 0).isEmpty(), "on the planner");
            TownHall.open(p, "duties");
            CompoundTag page = PlayerLife.pageForTests(p);
            helper.assertTrue(button(page, "Guilty") != null && button(page, "Not guilty") != null, "the verdict's buttons");
            id[0] = c.id;
        });
        helper.onEachTick(() -> {
            if (id[0] < 0) return;
            Crime.Case c = Crime.caseForTests(id[0]);
            long t = helper.getTick();
            if (c == null) { helper.fail("th06 the case is gone"); return; }
            if (!voted[0] && Jury.waiting(id[0])) {
                // Heard; the court waits on the jury.
                helper.assertTrue(c.stage() == Crime.Stage.TRIAL, "the court waits for the jury: " + c.stage());
                String v = act(pl[0], "verdict", LifePage.args("case", id[0], "g", false));
                Kit.log("[VT] th06 verdict: " + v);
                helper.assertTrue(v.startsWith("Your verdict"), "the juror's verdict: " + v);
                voted[0] = true;
            }
            if (c.stage() == Crime.Stage.ACQUITTED) {
                Kit.log("[VT] th06 acquitted; jury now " + Jury.sittingOn(pl[0].getUUID()));
                helper.assertTrue(voted[0], "not before the jury's word");
                helper.assertTrue(Jury.sittingOn(pl[0].getUUID()) < 0, "the jury stood down");
                Crime.hurryForTests(false);
                helper.succeed();
                return;
            }
            if (c.stage() == Crime.Stage.CONVICTED) { Crime.hurryForTests(false); helper.fail("th06 the innocent convicted"); }
            if (t > 2200) { Crime.hurryForTests(false); helper.fail("th06 still " + c.stage()); }
        });
    }

    // ============================================================ th07: a vote

    /** A citizen votes aye on what's put to the town, from the Duties tab; a resident's vote rests and says why. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th07_vote")
    public static void th07_vote(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8180000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            long day = level.getDayTime() / 24000L;
            int q = TownHall.questionForTests(level, v, "a new well", day + 1);
            helper.assertTrue(q > 0, "a question put");
            ServerPlayer resident = player(helper, heart, 0);
            act(resident, "register", null);
            TownHall.open(resident, "duties");
            CompoundTag aye = button(PlayerLife.pageForTests(resident), "Aye");
            helper.assertTrue(aye != null && !aye.getBoolean("on") && aye.getString("why").contains("citizen"), "a resident has no vote, and is told: " + aye);
            ServerPlayer p = player(helper, heart.offset(1, 0, 0), 0);
            Ledger.addCitizen(id, p.getUUID(), p.getGameProfile().getName());
            helper.assertTrue(TownHall.contextFlags(id, p).contains("referendum"), "the talk menu offers the vote");
            String said = act(p, "vote", LifePage.args("aye", true));
            Kit.log("[VT] th07 " + said);
            helper.assertTrue(said.startsWith("Your vote is in: aye"), "voted: " + said);
            helper.assertTrue(!TownHall.contextFlags(id, p).contains("referendum"), "and no longer offered");
            // The talk menu's own entries, said as typed.
            boolean ayeEntry = false;
            for (TalkMenu.Entry e : TalkMenu.all()) if (e.text().equals("I vote aye")) ayeEntry = true;
            helper.assertTrue(ayeEntry, "Vote aye is a talk menu option");
            helper.succeed();
        });
    }

    // ============================================================ th08: the militia and the army

    /** The militia joined by a resident; Join the army is the same way in as the old command (no campaign: it says so). */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th08_militia")
    public static void th08_militia(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8190000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 0);
            String early = act(p, "militia", LifePage.args("on", true));
            helper.assertTrue(early.startsWith("!") && !Belonging.inMilitia(id, p.getUUID()), "the town's own only: " + early);
            act(p, "register", null);
            String said = act(p, "militia", LifePage.args("on", true));
            helper.assertTrue(Belonging.inMilitia(id, p.getUUID()), "in the militia: " + said);
            String button = Muster.playerJoins(level, v, p);
            String typed = PlayerLife.command(p, "village war join");
            Kit.log("[VT] th08 button: " + button + " / command: " + typed);
            helper.assertTrue(button.contains("no army") && typed.contains("no army"), "one way in for both: " + button);
            helper.succeed();
        });
    }

    // ============================================================ th09: the leader's office

    /** A leader's Office tab: the minimum wage and a law set by buttons (a law asked first); a resident sees no Office. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "th09_office")
    public static void th09_office(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8200000;
        List<VillageFolkEntity> folk = town(helper, level, x, 6);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Villages.Village v = Villages.get(id);
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 40);
            TownHall.open(p, "office");
            helper.assertTrue(PlayerLife.ctx(p).tab.equals("office") && !PlayerLife.pageForTests(p).toString().contains("\"office\""), "no Office tab for a resident");
            List<String> stage = PlayerLeader.stage(level, v, p);
            Kit.log("[VT] th09 stage " + stage);
            helper.assertTrue(PlayerLeader.leads(id, p.getUUID()), "the player leads");
            TownHall.open(p, "office");
            CompoundTag page = PlayerLife.pageForTests(p);
            helper.assertTrue(button(page, "Set the minimum wage") != null && button(page, "Put it to the town") != null, "the office's buttons");
            String wage = act(p, "minwage", form("wage", "8"));
            Kit.log("[VT] th09 wage: " + wage);
            helper.assertTrue(!wage.startsWith("!"), "the minimum wage set: " + wage);
            act(p, "law", LifePage.args("law", "CURFEW", "n", 1));
            helper.assertTrue(PlayerLife.ctx(p).args.contains("ask"), "a law asked first");
            String law = act(p, "confirm", null);
            Kit.log("[VT] th09 law: " + law);
            helper.assertTrue(com.jrpetty.mcassistant.entity.LawBook.curfew(id), "the curfew is law: " + law);
            act(p, "referendum", form("q", "should we build a well", "qtext", ""));
            helper.assertTrue(PlayerLife.ctx(p).args.contains("ask"), "a question asked first");
            String put = act(p, "confirm", null);
            Kit.log("[VT] th09 referendum: " + put);
            helper.assertTrue(!put.isEmpty() && !put.startsWith("!"), "the question put: " + put);
            helper.succeed();
        });
    }

    // ============================================================ th10: no command hints

    private static final Pattern HINT = Pattern.compile("\"[^\"\\n]*(^|[\\s(\"'])/village\\b[^\"\\n]*\"");
    /** Operators' tools (their own commands named to them), and a package's own (WP-B's charter words, fixed there). */
    private static final List<String> EXEMPT = List.of("/admin/", "Stage.java", "AdminCommands.java", "AdminScreen.java", "AnimCommands.java",
        "AdminSpec.java", "AssistantConfig.java", "ThemeCommands.java", "Gallery.java", "VillageCommands.java", "Sport.java",
        "MyBusinesses.java", "Enterprise.java");

    /** No line a player is shown tells it to type "/village": the mod's sources (where the test can see them), the lang file and the Guide. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "th10_no_command_hints")
    public static void th10_no_command_hints(GameTestHelper helper) {
        List<String> found = new ArrayList<>();
        int files = 0;
        Path src = null;
        for (String s : new String[]{ "../src/main/java", "src/main/java", "../../src/main/java" }) {
            Path c = Path.of(s);
            if (Files.isDirectory(c)) { src = c; break; }
        }
        if (src != null) {
            try (Stream<Path> all = Files.walk(src)) {
                for (Path f : all.filter(q -> q.toString().endsWith(".java")).toList()) {
                    String path = f.toString().replace('\\', '/');
                    if (EXEMPT.stream().anyMatch(path::contains)) continue;
                    files++;
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String l = lines.get(i).trim();
                        if (l.startsWith("*") || l.startsWith("/*") || l.startsWith("//")) continue;
                        if (l.contains("Commands.literal(")) continue;
                        Matcher m = HINT.matcher(l);
                        if (m.find()) found.add(f.getFileName() + ":" + (i + 1) + " " + m.group());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        // The lang file and the Guide, from the classpath.
        Pattern word = Pattern.compile("(^|[\\s(\"'])/village\\b");
        for (String r : new String[]{ "assets/mc_assistant/lang/en_us.json", "assets/mc_assistant/guide/civic.json", "assets/mc_assistant/guide/start.json",
                "assets/mc_assistant/guide/life.json", "assets/mc_assistant/guide/money.json", "assets/mc_assistant/guide/business.json" }) {
            try (InputStream in = TownHallGameTests.class.getClassLoader().getResourceAsStream(r)) {
                if (in == null) continue;
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = word.matcher(text);
                if (m.find()) found.add(r + " names /village");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        Kit.log("[VT] th10 sources " + (src == null ? "not found" : src.toAbsolutePath()) + ", " + files + " files; hints " + found);
        helper.assertTrue(found.isEmpty(), "lines that tell a player to type a command: " + found);
        helper.succeed();
    }

    // ============================================================ th11: the arrival card

    /** The first time into a town: the card, once; its Join village opens the Town Hall's Join tab. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "th11_arrival")
    public static void th11_arrival(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8210000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 0);
            ArrivalCard.resetForTests();
            helper.assertTrue(ArrivalCard.arrive(p, id), "the card has it");
            helper.assertTrue(PlayerLife.ctx(p).page.equals("arrival") && Belonging.arrived(p.getUUID(), id), "the card shows: " + PlayerLife.ctx(p).page);
            CompoundTag card = PlayerLife.pageForTests(p);
            Kit.log("[VT] th11 " + card.getString("title") + " / " + card.getString("sub"));
            for (String b : new String[]{ "Join village", "Job adverts", "Ask about houses", "Open a shop", "How", "Notices" }) {
                helper.assertTrue(button(card, b) != null, "the card's " + b);
            }
            PlayerLife.act(p, "arrival", "join", new CompoundTag());
            helper.assertTrue(PlayerLife.ctx(p).page.equals("hall") && PlayerLife.ctx(p).tab.equals("join"), "Join village: the hall's Join tab");
            PlayerLife.open(p, "life", null);
            ArrivalCard.arrive(p, id);
            helper.assertTrue(PlayerLife.ctx(p).page.equals("life"), "once a town");
            helper.succeed();
        });
    }

    // ============================================================ th12: the routes

    /** The commands that were command-only now open the hall on the tab where their button is. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "th12_routes")
    public static void th12_routes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8220000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        helper.runAtTickTime(10, () -> {
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 0);
            String[][] want = { { "village war join", "duties" }, { "village labour minwage 5", "office" }, { "village identity law curfew 1", "office" },
                { "village referendum vote aye", "duties" }, { "village capital deposit 5", "money" }, { "village knight retinue hire", "office" } };
            for (String[] w : want) {
                InWorld.Route r = InWorld.route(w[0]);
                helper.assertTrue(r != null && r.action().equals("life:hall#" + w[1]), w[0] + " routes to the hall's " + w[1] + ": " + (r == null ? "none" : r.action()));
                PlayerLife.open(p, "all", null);
                PlayerLife.act(p, "all", "go", LifePage.args("a", r.action()));
                helper.assertTrue(PlayerLife.ctx(p).page.equals("hall"), w[0] + " opens the hall");
                PlayerLife.pageForTests(p);
                helper.assertTrue(PlayerLife.ctx(p).tab.equals(w[1]) || w[1].equals("office"), w[0] + " on its tab: " + PlayerLife.ctx(p).tab);
            }
            helper.succeed();
        });
    }

    // ============================================================ fw01, fw02: the first week

    /** Signing the book starts the week and ticks the first step (a coin or two from the treasury); three folk who know you tick another. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fw01_first_week")
    public static void fw01_first_week(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8230000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            Ledger.addCoins(id, 50);
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 0);
            helper.assertTrue(FirstWeek.town(p.getUUID()) == null, "no week before the book");
            act(p, "register", null);
            helper.assertTrue(id.equals(FirstWeek.town(p.getUUID())), "the week follows the town");
            helper.assertTrue(FirstWeek.done(p.getUUID(), FirstWeek.Step.REGISTER), "the first step ticked");
            helper.assertTrue(coins(p) == FirstWeek.REWARD_COINS, "and paid: " + coins(p));
            helper.assertTrue(FirstWeek.next(p.getUUID()) == FirstWeek.Step.ROOM, "next: a room");
            friend(id, p, folk.subList(0, 3));
            FirstWeek.check(p);
            helper.assertTrue(FirstWeek.done(p.getUUID(), FirstWeek.Step.NEIGHBOUR), "three neighbours who know you");
            TownHall.open(p, "week");
            CompoundTag page = PlayerLife.pageForTests(p);
            helper.assertTrue(button(page, "Show me") != null, "each step has Show me");
            String show = act(p, "weekshow", LifePage.args("s", "BUY"));
            Kit.log("[VT] fw01 show: " + show);
            helper.assertTrue(show.contains("arrow") || show.contains("there"), "the arrow to the market: " + show);
            act(p, "weekskip", null);
            helper.assertTrue(FirstWeek.skipped(p.getUUID()), "hushed");
            helper.succeed();
        });
    }

    /** A first bill: the citizen's fee counts (and the chapter after the week waits for it). */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fw02_first_bill")
    public static void fw02_first_bill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8240000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        helper.runAtTickTime(10, () -> {
            UUID id = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 40);
            act(p, "register", null);
            friend(id, p, folk);
            act(p, "citizen", null);
            act(p, "confirm", null);
            helper.assertTrue(Citizens.is(id, p.getUUID()), "a citizen");
            FirstWeek.check(p);
            Kit.log("[VT] fw02 done " + FirstWeek.count(p.getUUID()) + ", next " + FirstWeek.next(p.getUUID()));
            helper.assertTrue(FirstWeek.done(p.getUUID(), FirstWeek.Step.BILL), "the fee is a first bill");
            helper.assertTrue(!FirstWeek.done(p.getUUID(), FirstWeek.Step.CITIZEN), "chapter two waits for the week");
            helper.succeed();
        });
    }

    // ============================================================ th13: inspecting a player

    /** Right-click another player: its card if it signed a book, a word if not. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "th13_inspect")
    public static void th13_inspect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8250000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        helper.runAtTickTime(10, () -> {
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 0);
            ServerPlayer q = player(helper, heart.offset(1, 0, 0), 0);
            PlayerLife.open(p, "life", null);
            IdCard.inspect(p, q);
            helper.assertTrue(PlayerLife.ctx(p).page.equals("life"), "nobody's papers: no card");
            act(q, "register", null);
            act(q, "identity", form("first", "Tobias", "last", "Fenwick", "bio", "Smith by trade.", "age", "40", "trade", "smith", "look", "worker"));
            IdCard.inspect(p, q);
            CompoundTag card = PlayerLife.pageForTests(p);
            Kit.log("[VT] th13 " + card.getString("title"));
            helper.assertTrue(PlayerLife.ctx(p).page.equals("id") && card.getString("title").contains("Tobias Fenwick"), "its card");
            helper.assertTrue(card.toString().contains("Smith by trade."), "its bio");
            helper.succeed();
        });
    }
}
