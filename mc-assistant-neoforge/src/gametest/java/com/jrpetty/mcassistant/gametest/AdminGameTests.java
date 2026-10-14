package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.admin.AdminConfig;
import com.jrpetty.mcassistant.admin.AdminConfirm;
import com.jrpetty.mcassistant.admin.AdminJobs;
import com.jrpetty.mcassistant.admin.AdminLog;
import com.jrpetty.mcassistant.admin.AdminRunner;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [admin] The admin toolkit (/village admin, admin/AdminRunner, entity/AdminTools): an age set gives a consistent town;
 * fifty folk added get homes, jobs and households; the army is kitted from the stores first and by decree for the rest;
 * a war's phase jump stages the battle and a peace ends the war; five days skipped run the towns' mornings; a source
 * without the level is refused and the refusal logged; every change leaves a "By decree" chronicle line; a destructive
 * action waits on its confirm token; a dry run changes nothing; the selectors pick the right towns and folk; a script
 * runs its lines in order; a cancelled job stops.
 *
 * <p>Each test has its own batch and its own ground: x 1900000 to 1919999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class AdminGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    /** Enough job slices for anything the tests start (a job slice a call). */
    private static final int JOB_TICKS = 400;

    // ------------------------------------------------------------------ helpers

    private static void reset(ServerLevel level) {
        Kit.reset(level);
        AdminJobs.resetForTests();
        AdminConfirm.resetForTests();
        AdminLog.resetForTests();
        AdminTools.resetForTests();
        AdminRunner.resetForTests();
        AdminConfig.enabledForTests(null);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 2000L);
    }

    private static Villages.Village town(ServerLevel level, int x, int n) {
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        return Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
    }

    /** A command source at the town's heart, at this permission level, that keeps what it is told. */
    private static CommandSourceStack src(ServerLevel level, Villages.Village v, int perm, List<String> said) {
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component c) { said.add(c.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        return level.getServer().createCommandSourceStack().withSource(sink).withLevel(level)
            .withPosition(Vec3.atCenterOf(v.centre())).withPermission(perm);
    }

    private static AdminRunner.Outcome run(CommandSourceStack s, String path, String values) {
        AdminRunner.Outcome o = AdminRunner.run(s, path, values);
        Kit.log("[ADMIN] " + path + " " + values + " -> " + o.ok() + " " + String.join(" | ", o.lines()));
        return o;
    }

    private static int decrees(UUID village) {
        int n = 0;
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().startsWith("By decree: ")) n++;
        return n;
    }

    private static boolean chronicleHas(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    // ------------------------------------------------------------------ ad01

    /** The Iron Age set: the age kept, its needs the Iron Age's own, the stores stocked with iron, the folk's tools iron, a decree line. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ad01_iron_age")
    public static void ad01_iron_age(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1900000, 8);
        helper.assertTrue(v != null, "a town");
        List<String> said = new ArrayList<>();
        AdminRunner.Outcome o = run(src(level, v, 4, said), "ages set", "iron none");
        helper.assertTrue(o.ok(), "ages set iron ran: " + o.lines());
        helper.assertTrue(Villages.ageOf(v.id()) == Villages.Age.IRON, "the town is in the Iron Age");
        List<String> needs = AdminTools.needs(level, v);
        helper.assertTrue(String.join(";", needs).contains("workshop") || String.join(";", needs).contains("iron"),
            "its needs are the Iron Age's (iron, a workshop...): " + needs);
        int iron = 0;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (level.getBlockEntity(p) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.IRON_INGOT)) iron += c.getItem(i).getCount();
        }
        helper.assertTrue(iron >= 128, "the stores were stocked for the age (iron " + iron + ")");
        int ironTools = 0, grown = 0;
        for (VillageFolkEntity f : AdminTools.folk(v.id())) {
            if (f.isBaby()) continue;
            grown++;
            boolean has = f.getMainHandItem().is(Items.IRON_PICKAXE);
            for (ItemStack s : f.getInventoryItems()) has |= s.is(Items.IRON_PICKAXE);
            if (has) ironTools++;
        }
        helper.assertTrue(grown > 0 && ironTools == grown, "every grown folk carries an iron pickaxe (" + ironTools + "/" + grown + ")");
        helper.assertTrue(chronicleHas(v.id(), "By decree: the town was brought into the Iron Age"), "the chronicle says it was by decree");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad02

    /** Fifty folk added: on the roll, every grown one with a trade, couples pledged, camp beds pitched, a decree line. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "ad02_fifty_folk")
    public static void ad02_fifty_folk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1902000, 6);
        helper.assertTrue(v != null, "a town");
        int before = Villages.headcount(v.id()), bedsBefore = com.jrpetty.mcassistant.VillageSpawner.campBeds(level, v.centre()).size();
        AdminRunner.Outcome o = run(src(level, v, 4, new ArrayList<>()), "people add", "50");
        helper.assertTrue(o.ok() && o.jobId() != null, "a job started: " + o.lines());
        AdminJobs.runForTests(level.getServer(), JOB_TICKS);
        int after = Villages.headcount(v.id());
        helper.assertTrue(after - before >= 50, "fifty more on the roll (" + before + " -> " + after + ")");
        int grown = 0, working = 0, partnered = 0, children = 0;
        for (VillageFolkEntity f : AdminTools.folk(v.id())) {
            if (f.isBaby()) { children++; continue; }
            grown++;
            if (f.stationTask() != StationTask.NONE) working++;
            if (f.life().partner() != null) partnered++;
        }
        helper.assertTrue(working >= grown - 6, "the new grown folk took trades (" + working + "/" + grown + ")");
        helper.assertTrue(partnered >= 4, "couples were pledged: households to come (" + partnered + " partnered)");
        helper.assertTrue(children > 0, "the spread has children (" + children + ")");
        int beds = com.jrpetty.mcassistant.VillageSpawner.campBeds(level, v.centre()).size();
        helper.assertTrue(beds > bedsBefore, "camp beds pitched for them (" + bedsBefore + " -> " + beds + ")");
        helper.assertTrue(chronicleHas(v.id(), "folk were brought to the town"), "a decree line for the folk");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad03

    /** The watch kitted in iron: the stores' two helmets used first, the rest a decree supply, said in the result. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ad03_kit")
    public static void ad03_kit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1904000, 8);
        helper.assertTrue(v != null, "a town");
        for (int i = 0; i < 4; i++) AdminTools.enlist(v);
        BlockPos chest = Kit.surface(level, v.centre().getX() + 3, v.centre().getZ() + 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        ((Container) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.IRON_HELMET, 2));
        AdminRunner.Outcome o = run(src(level, v, 4, new ArrayList<>()), "army kit", "iron stores");
        helper.assertTrue(o.ok(), "kit ran: " + o.lines());
        String line = String.join(" ", o.lines());
        helper.assertTrue(line.contains("2 pieces from the stores") && line.contains("by decree supply"),
            "it says where the kit came from: " + line);
        List<VillageFolkEntity> watch = AdminTools.guards(v.id());
        helper.assertTrue(watch.size() >= 4, "four on the watch (" + watch.size() + ")");
        for (VillageFolkEntity g : watch) helper.assertTrue(g.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), g.displayNameCap() + " wears iron");
        helper.assertTrue(((Container) level.getBlockEntity(chest)).getItem(0).isEmpty(), "the stores' helmets were used");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad04

    /** War declared, the phase jumped to the line-up (a campaign staged), then a peace: at war no longer. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "ad04_war_phase")
    public static void ad04_war_phase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        WarAndPeace.resetForTests();
        Campaigns.resetForTests();
        Campaigns.hurryForTests(true);
        Villages.Village a = town(level, 1906000, 10), b = town(level, 1906000 + 260, 10);
        for (int dx = 64; dx <= 196; dx += 64) { Kit.hold(level, 1906000 + dx, Z, 48); Kit.prepare(level, 1906000 + dx, Z, 48); }
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        CommandSourceStack s = src(level, a, 4, new ArrayList<>());
        helper.assertTrue(run(s, "army war", "rival").ok(), "war declared");
        helper.assertTrue(Wars.atWar(a.id(), b.id()), "the two are at war");
        AdminRunner.Outcome o = run(s, "army phase", "lineup");
        helper.assertTrue(o.ok() && String.join(" ", o.lines()).contains("STAGED lineup"), "the battle staged at the line-up: " + o.lines());
        helper.assertTrue(Campaigns.ofTown(a.id()) != null, "a campaign in the field");
        helper.assertTrue(run(s, "army phase", "peace").ok(), "peace made");
        helper.assertTrue(!Wars.atWar(a.id(), b.id()), "at war no longer");
        helper.assertTrue(chronicleHas(a.id(), "By decree: peace was made"), "the peace by decree in the chronicle");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad05

    /** Five days skipped: the clock five days on at the same hour, every town's morning round run (wages paid today). */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ad05_skip")
    public static void ad05_skip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1908000, 6);
        helper.assertTrue(v != null, "a town");
        long day0 = level.getDayTime() / 24000L, tod = level.getDayTime() % 24000L;
        AdminRunner.Outcome o = run(src(level, v, 4, new ArrayList<>()), "world skip", "5");
        helper.assertTrue(o.ok() && o.jobId() != null, "a skip job: " + o.lines());
        AdminJobs.runForTests(level.getServer(), JOB_TICKS);
        long day = level.getDayTime() / 24000L;
        helper.assertTrue(day == day0 + 5, "five days on (" + day0 + " -> " + day + ")");
        helper.assertTrue(level.getDayTime() % 24000L == tod, "at the same hour");
        helper.assertTrue(Ledger.paidOn(v.id()) == day, "the town's morning round ran today (paid on " + Ledger.paidOn(v.id()) + ")");
        helper.assertTrue(chronicleHas(v.id(), "5 day(s) were skipped"), "a decree line for the skip");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad06

    /** A source below the level is refused (and the refusal logged); with the toolkit off, everybody is. Nothing changes. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ad06_refused")
    public static void ad06_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1910000, 4);
        helper.assertTrue(v != null, "a town");
        AdminRunner.Outcome o = run(src(level, v, 0, new ArrayList<>()), "ages set", "diamond none");
        helper.assertTrue(!o.ok() && o.lines().get(0).contains("permission level"), "refused: " + o.lines());
        helper.assertTrue(Villages.ageOf(v.id()) == Villages.Age.WOOD, "the age unchanged");
        helper.assertTrue(AdminLog.last(1).get(0).contains("refused"), "the refusal is in the log: " + AdminLog.last(1));
        AdminConfig.enabledForTests(false);
        AdminRunner.Outcome off = run(src(level, v, 4, new ArrayList<>()), "economy coin", "100");
        AdminConfig.enabledForTests(null);
        helper.assertTrue(!off.ok() && off.lines().get(0).contains("switched off"), "switched off for everybody: " + off.lines());
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad07

    /** Each action that changes the town leaves its own "By decree" line in the chronicle; a look leaves none. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ad07_chronicle")
    public static void ad07_chronicle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1912000, 6);
        helper.assertTrue(v != null, "a town");
        CommandSourceStack s = src(level, v, 4, new ArrayList<>());
        int before = decrees(v.id());
        String[][] acts = { { "economy coin", "250" }, { "economy fill", "food" }, { "people heal", "" }, { "world season", "winter" },
            { "economy market", "" }, { "research unlock", "common_tools" }, { "people needs", "" } };
        for (String[] a : acts) helper.assertTrue(run(s, a[0], a[1]).ok(), a[0] + " ran");
        run(s, "inspect town", "");
        int after = decrees(v.id());
        helper.assertTrue(after - before == acts.length, "a decree line each (" + (after - before) + " for " + acts.length + ")");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad08

    /** A destructive action below destructiveLevel waits on its token; a run-out token is refused; a good one goes ahead. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ad08_confirm")
    public static void ad08_confirm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1914000, 8);
        helper.assertTrue(v != null, "a town");
        CommandSourceStack s = src(level, v, 2, new ArrayList<>());
        int before = Villages.headcount(v.id());
        AdminRunner.Outcome ask = run(s, "people remove", "2");
        helper.assertTrue(ask.confirmId() != null, "a confirm token: " + ask.lines());
        helper.assertTrue(Villages.headcount(v.id()) == before, "nobody sent away yet");
        AdminConfirm.expireForTests(ask.confirmId());
        helper.assertTrue(!AdminConfirm.confirm(s, ask.confirmId()).ok(), "a run-out token is refused");
        AdminRunner.Outcome again = run(s, "people remove", "2");
        AdminRunner.Outcome go = AdminConfirm.confirm(s, again.confirmId());
        helper.assertTrue(go.ok(), "confirmed: " + go.lines());
        helper.assertTrue(Villages.headcount(v.id()) == before - 2, "two sent away (" + before + " -> " + Villages.headcount(v.id()) + ")");
        helper.assertTrue(!AdminConfirm.confirm(s, again.confirmId()).ok(), "a token is good once");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad09

    /** A dry run says what would change and changes nothing: no folk, no job, the stores and the treasury as they were. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ad09_dry_run")
    public static void ad09_dry_run(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1916000, 6);
        helper.assertTrue(v != null, "a town");
        CommandSourceStack s = src(level, v, 4, new ArrayList<>());
        int folk = Villages.headcount(v.id()), stores = AdminTools.storesCount(level, v), coin = Ledger.coins(v.id()), lines = Chronicle.of(v.id()).size();
        AdminRunner.Outcome add = run(s, "people add", "20 --dry-run");
        AdminRunner.Outcome empty = run(s, "economy empty", "--dry-run");
        AdminRunner.Outcome money = run(s, "economy coin", "500 --dry-run");
        helper.assertTrue(add.lines().get(0).startsWith("DRY-RUN add 20 folk"), "it says what it would do: " + add.lines());
        helper.assertTrue(empty.lines().get(0).startsWith("DRY-RUN empty") && money.lines().get(0).startsWith("DRY-RUN"), "the others too");
        helper.assertTrue(AdminJobs.running().isEmpty(), "no job started");
        helper.assertTrue(Villages.headcount(v.id()) == folk && AdminTools.storesCount(level, v) == stores && Ledger.coins(v.id()) == coin,
            "nothing changed");
        helper.assertTrue(Chronicle.of(v.id()).size() == lines, "nothing in the chronicle");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad10

    /** The log: an action's line in memory and in <world>/mcassistant/admin-log.txt, with who, what, which town and how it went. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ad10_log")
    public static void ad10_log(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1918000, 4);
        helper.assertTrue(v != null, "a town");
        run(src(level, v, 4, new ArrayList<>()), "economy coin", "77");
        String line = AdminLog.last(1).get(0);
        helper.assertTrue(line.contains("| Server |") && line.contains("economy coin 77") && line.contains(Villages.name(v.id())) && line.contains("ok:"),
            "the log line has who, what, where and how: " + line);
        try {
            Path f = level.getServer().getWorldPath(LevelResource.ROOT).resolve("mcassistant").resolve("admin-log.txt");
            helper.assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains(line), "the line is in the log file");
        } catch (java.io.IOException e) {
            helper.fail("no log file: " + e);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad11

    /** Selectors: town:<name> picks that town, town:all every town, folk:@guard only the watch, folk:<name> one folk. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ad11_selectors")
    public static void ad11_selectors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village a = town(level, 1919000, 5), b = town(level, 1919000 + 600, 5);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        CommandSourceStack s = src(level, a, 4, new ArrayList<>());
        int ca = Ledger.coins(a.id()), cb = Ledger.coins(b.id());
        run(s, "economy coin", "11 town:\"" + Villages.name(b.id()) + "\"");
        helper.assertTrue(Ledger.coins(a.id()) == ca && Ledger.coins(b.id()) == cb + 11, "town:<name> picked the other town only");
        run(s, "economy coin", "5 town:all");
        helper.assertTrue(Ledger.coins(a.id()) == ca + 5 && Ledger.coins(b.id()) == cb + 16, "town:all picked both");
        AdminTools.enlist(a);
        AdminRunner.Outcome heal = run(s, "people heal", "folk:@guard");
        helper.assertTrue(heal.lines().get(0).startsWith("1 folk"), "folk:@guard picked the one guard: " + heal.lines());
        VillageFolkEntity one = AdminTools.folk(b.id()).get(0);
        AdminRunner.Outcome look = run(s, "inspect folk", "folk:\"" + one.displayNameCap() + "\"");
        helper.assertTrue(look.lines().get(0).contains(one.displayNameCap()) && look.lines().get(0).contains(Villages.name(b.id())),
            "folk:<name> found it in its own town: " + look.lines());
        helper.assertTrue(!run(s, "economy coin", "1 town:Nowhereville").ok(), "an unknown town is an error");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad12

    /** A script from the world's folder: its lines run in order, a wait between, as one job. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ad12_script")
    public static void ad12_script(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1917000, 4);
        helper.assertTrue(v != null, "a town");
        try {
            Path dir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("mcassistant").resolve("admin-scripts");
            Files.createDirectories(dir);
            Files.write(dir.resolve("adtest.txt"), List.of("# a test", "/village admin economy coin 7", "wait 1", "village admin economy coin 3"),
                StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            helper.fail("could not write the script: " + e);
        }
        int coin = Ledger.coins(v.id());
        AdminRunner.Outcome o = run(src(level, v, 4, new ArrayList<>()), "run", "adtest");
        helper.assertTrue(o.ok() && o.jobId() != null, "the script started: " + o.lines());
        AdminJobs.runForTests(level.getServer(), 5);
        helper.assertTrue(Ledger.coins(v.id()) == coin + 7, "its first line ran, and it waits (" + (Ledger.coins(v.id()) - coin) + ")");
        AdminJobs.runForTests(level.getServer(), JOB_TICKS);
        helper.assertTrue(Ledger.coins(v.id()) == coin + 10, "then the rest (" + (Ledger.coins(v.id()) - coin) + ")");
        helper.assertTrue(AdminJobs.running().isEmpty(), "the script finished");
        helper.succeed();
    }

    // ------------------------------------------------------------------ ad13

    /** A job cancelled part way stops: what it did stays, the rest is not done, the log says cancelled. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ad13_cancel")
    public static void ad13_cancel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        reset(level);
        Villages.Village v = town(level, 1915000, 4);
        helper.assertTrue(v != null, "a town");
        int before = Villages.headcount(v.id());
        AdminRunner.Outcome o = run(src(level, v, 4, new ArrayList<>()), "people add", "100");
        helper.assertTrue(o.jobId() != null, "a job: " + o.lines());
        AdminJobs.runForTests(level.getServer(), 2);
        helper.assertTrue(AdminJobs.cancel(o.jobId()), "cancelled");
        AdminJobs.runForTests(level.getServer(), JOB_TICKS);
        int added = Villages.headcount(v.id()) - before;
        helper.assertTrue(added > 0 && added < 100, "it stopped part way (" + added + " added)");
        helper.assertTrue(AdminJobs.running().isEmpty(), "the job is gone");
        boolean logged = false;
        for (String l : AdminLog.last(5)) logged |= l.contains("job " + o.jobId()) && l.contains("cancelled");
        helper.assertTrue(logged, "the log says it was cancelled: " + AdminLog.last(5));
        helper.succeed();
    }
}
