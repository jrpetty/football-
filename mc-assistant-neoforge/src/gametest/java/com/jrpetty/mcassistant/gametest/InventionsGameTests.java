package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Armies;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.CityTree;
import com.jrpetty.mcassistant.entity.InventionArms;
import com.jrpetty.mcassistant.entity.InventionWorks;
import com.jrpetty.mcassistant.entity.Inventions;
import com.jrpetty.mcassistant.entity.Intel;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Scholars;
import com.jrpetty.mcassistant.entity.TownLook;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.Vassals;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.InventionItems;
import com.jrpetty.mcassistant.village.ArmsEdge;
import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.InventionTree;
import com.jrpetty.mcassistant.village.InventionTree.Invention;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * [tech-tree] The town's inventions (entity/Inventions, Scholars, InventionWorks, InventionArms): the scholars appointed
 * and the leader choosing by its heart; the learning going in morning by morning, the working model waiting on the
 * stores at half way, and the invention known after its days, told, its model in the stores, kept over a restart; hard
 * times and a lost scholar; a eureka and a failed experiment; a spy's sketches and a vassal's teaching; the buildings
 * wanted once known and what they do standing; and the new arms made by the smith and carried into the field, with their
 * edge in the blow and on paper.
 *
 * <p>Deterministic: the day is set past the morning's books (so the town's own morning never runs under the test) and
 * each test calls the inventions' morning itself, its dice fixed. Each on its own ground in x 2280000-2299999, z 70000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class InventionsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;
    private static final int APART = 260;

    /** A town of {@code n} at x, in the Stone Age, past the morning's books, its inventions forgotten. */
    private static Villages.Village town(GameTestHelper helper, ServerLevel level, int x, int n, boolean reset) {
        if (reset) {
            Kit.reset(level);
            Inventions.resetForTests();
            level.setDayTime(30L * 24000L + 8000L);
        }
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null && stood > 0, "a town: " + stood + " stood");
        Villages.ageForTests(v.id(), Villages.Age.STONE);
        Inventions.clearForTests(v.id());
        return v;
    }

    private static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby()) {
                f.ensurePersona();
                out.add(f);
            }
        }
        return out;
    }

    private static void lean(VillageFolkEntity f, Values.Value top) {
        for (Values.Value v : Values.Value.values()) Values.setForTests(f, v, v == top ? 95 : 5);
    }

    private static void flat(VillageFolkEntity f) {
        for (Values.Value v : Values.Value.values()) Values.setForTests(f, v, 50);
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().toLowerCase(Locale.ROOT).contains(words.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        Villages.forgetStock();
    }

    private static void stock(ServerLevel level, UUID village, ItemStack... goods) {
        List<BlockPos> stores = Villages.storeChests(level, village);
        int g = 0;
        for (BlockPos p : stores) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && g < goods.length; i++) {
                if (!c.getItem(i).isEmpty()) continue;
                c.setItem(i, goods[g++].copy());
            }
            c.setChanged();
        }
        Villages.forgetStock();
    }

    private static int count(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    private static BlockPos building(ServerLevel level, UUID village, String name, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, name, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, name, p, Direction.NORTH);
        return p;
    }

    // ============================================================ iv01: research goes on over days, and is done

    /**
     * A town of eight in the Stone Age: its first morning a scholar is appointed and a Provider leader sets it to the
     * Heavy Plough; its learning goes in morning by morning (its reading, its evenings at the desk); with no paper in the
     * stores for its notes the model waits and the work stops at half way, said in the chronicle; the paper and the
     * makings brought, the model is built and, its days served, the plough is known: told, first in the world, its
     * working model in the stores, the next invention chosen; the books' page has it known; it is kept over a restart;
     * and /village inventions says so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "iv01_research_over_days")
    public static void iv01_research_over_days(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2280000;
        Villages.Village v = town(helper, level, x, 8, true);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            List<VillageFolkEntity> folk = grown(id);
            helper.assertTrue(folk.size() >= 6, "grown folk: " + folk.size());
            Villages.electElder(id, folk.get(0), day);
            lean(folk.get(0), Values.Value.FOOD);
            Inventions.rollForTests(new int[]{ 999, 999 });
            emptyStores(level, id);
            Inventions.morningForTests(level, v, day);
            List<UUID> scholars = Scholars.appointed(id);
            Invention now = Inventions.current(id);
            Kit.log("iv01 day " + day + ": scholars " + scholars.size() + " (" + (scholars.isEmpty() ? "" : Scholars.whyChosen(level, scholars.get(0)))
                + "); researching " + now + "; rate " + Inventions.rate(level, id, day));
            helper.assertTrue(scholars.size() == 1, "a town of eight keeps one scholar: " + scholars.size());
            helper.assertTrue(!scholars.contains(Villages.elder(id)), "never the leader");
            helper.assertTrue(now == Invention.HEAVY_PLOUGH, "a Provider sets its scholars to the plough first: " + now);
            helper.assertTrue("heavy_plough".equals(Inventions.researching(id)), "the public face says so: " + Inventions.researching(id));
            helper.assertTrue(told(id, "set the scholars to The Heavy Plough"), "the chronicle has the choice");
            helper.assertTrue(told(id, "took up a scholar's desk"), "and the scholar");
            int last = Inventions.progress(id);
            helper.assertTrue(last > 0, "the first morning's learning: " + last);
            boolean capped = false;
            long knownOn = -1;
            for (int d = 1; d <= 20 && knownOn < 0; d++) {
                for (UUID s : scholars) Scholars.beatsForTests(id, s, day + d - 1, 10, false);
                Inventions.morningForTests(level, v, day + d);
                int p = Inventions.progress(id);
                Kit.log("iv01 day +" + d + ": learning " + p + " of " + now.cost * 100 + (Inventions.known(id, now) ? " KNOWN" : "")
                    + "; " + Inventions.rate(level, id, day + d).why());
                if (Inventions.known(id, now)) {
                    knownOn = day + d;
                    break;
                }
                if (!capped && p >= now.cost * 50) {
                    capped = true;
                    helper.assertTrue(p == now.cost * 50, "no further than half way without the model: " + p);
                    helper.assertTrue(told(id, "wait on the stores for its working model"), "the chronicle says it waits on the stores");
                    int held = p;
                    for (UUID s : scholars) Scholars.beatsForTests(id, s, day + d, 10, false);
                    Inventions.morningForTests(level, v, day + d + 1);
                    helper.assertTrue(Inventions.progress(id) == held && !Inventions.known(id, now), "held at half way: " + Inventions.progress(id));
                    d++;
                    stock(level, id, new ItemStack(Items.PAPER, 4), new ItemStack(Items.OAK_PLANKS, 8), new ItemStack(Items.STICK, 4),
                        new ItemStack(Items.COBBLESTONE, 4));
                    continue;
                }
                helper.assertTrue(p > last, "the learning goes on day by day: " + last + " -> " + p);
                last = p;
            }
            helper.assertTrue(capped, "the work waited on the stores at half way");
            helper.assertTrue(knownOn > 0 && knownOn - day >= now.days, "known after its days: day " + knownOn + " from " + day);
            helper.assertTrue(Inventions.known(id, "heavy_plough"), "the public face knows it");
            helper.assertTrue(told(id, "worked out The Heavy Plough"), "the chronicle has it done");
            helper.assertTrue(InventionsHelper.name(id).equals(Inventions.firstForTests(Invention.HEAVY_PLOUGH)), "the first in the world: "
                + Inventions.firstForTests(Invention.HEAVY_PLOUGH));
            int models = count(level, id, s -> s.is(InventionItems.WORKING_MODEL.get()));
            helper.assertTrue(models == 1, "its working model into the stores: " + models);
            helper.assertTrue(count(level, id, s -> s.is(Items.PAPER)) < 4, "the notes' paper used");
            Invention next = Inventions.current(id);
            helper.assertTrue(next != null && next != now, "the next taken up: " + next);
            // The books' page: the tree, the plough known, the next in hand.
            CompoundTag report = Inventions.report(level, id);
            ListTag tree = report.getList("tree", Tag.TAG_COMPOUND);
            String plough = "", nextState = "";
            for (int i = 0; i < tree.size(); i++) {
                if (tree.getCompound(i).getString("id").equals("heavy_plough")) plough = tree.getCompound(i).getString("state");
                if (tree.getCompound(i).getString("id").equals(next.id())) nextState = tree.getCompound(i).getString("state");
            }
            helper.assertTrue(tree.size() == Invention.values().length && plough.equals("known") && nextState.equals("researching"),
                "the page's tree: " + tree.size() + ", plough " + plough + ", next " + nextState);
            // Kept over a restart.
            Inventions.reloadForTests(level.registryAccess());
            helper.assertTrue(Inventions.known(id, Invention.HEAVY_PLOUGH) && Inventions.current(id) == next, "kept over a restart");
            BlockPos heart = v.centre();
            List<String> said = Kit.command(level, "execute positioned " + heart.getX() + " " + heart.getY() + " " + heart.getZ() + " run village inventions");
            Kit.log("iv01 /village inventions: " + said);
            helper.assertTrue(String.join(" ", said).contains("[x] The Heavy Plough"), "the command shows it known");
            Inventions.rollForTests(null);
            helper.succeed();
        });
    }

    // ============================================================ iv02: the leader's heart, hard times, a lost scholar, luck

    /**
     * A Guardian leader takes up arms, a Provider the fields, and a leader of no strong mind takes up what a player
     * suggests; a famine slows the learning; a scholar taken for the watch leaves its desk and the next best head takes it;
     * a eureka puts a quarter on at a stroke, and a failed experiment at the Iron Age's work takes a tenth off.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "iv02_leader_and_hard_times")
    public static void iv02_leader_and_hard_times(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2282000;
        Villages.Village v = town(helper, level, x, 9, true);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            List<VillageFolkEntity> folk = grown(id);
            VillageFolkEntity elder = folk.get(0);
            Villages.electElder(id, elder, day);
            Inventions.rollForTests(new int[]{ 999, 999 });
            lean(elder, Values.Value.SAFETY);
            Invention guardian = Inventions.chooseForTests(level, id, day);
            lean(elder, Values.Value.FOOD);
            Invention provider = Inventions.chooseForTests(level, id, day);
            flat(elder);
            String said = Inventions.suggest(level, v, "Steve", Invention.TREADWHEEL_CRANE);
            Invention suggested = Inventions.chooseForTests(level, id, day);
            Kit.log("iv02 Guardian " + guardian + ", Provider " + provider + ", suggested " + suggested + " (" + said + ")");
            helper.assertTrue(guardian != null && guardian.branch == InventionTree.Branch.MILITARY, "a Guardian takes up arms: " + guardian);
            helper.assertTrue(provider != null && provider.branch == InventionTree.Branch.AGRICULTURE, "a Provider the fields: " + provider);
            helper.assertTrue(suggested == Invention.TREADWHEEL_CRANE, "a player's suggestion weighed: " + suggested);
            helper.assertTrue(told(id, "Steve suggested"), "the suggestion in the chronicle");
            // Hard times: a famine.
            Inventions.morningForTests(level, v, day);
            List<UUID> scholars = Scholars.appointed(id);
            helper.assertTrue(!scholars.isEmpty(), "a scholar");
            int easy = Inventions.rate(level, id, day + 1).hundredths();
            Leader.booksForTests(id, new Leader.Books(0, 0, 10, 0, 10, 0.5, Leader.Plan.FAMINE, day));
            int hard = Inventions.rate(level, id, day + 1).hundredths();
            Kit.log("iv02 learning a day: " + easy + " in good times, " + hard + " in a famine (" + Inventions.rate(level, id, day + 1).why() + ")");
            helper.assertTrue(hard < easy && hard >= easy / 2, "a famine slows it: " + easy + " -> " + hard);
            Leader.booksForTests(id, new Leader.Books(500, 20, 10, 20, 10, 50, Leader.Plan.PLENTY, day));
            // A scholar taken for the watch: the next best head takes its desk.
            UUID gone = scholars.get(0);
            VillageFolkEntity g = (VillageFolkEntity) level.getEntity(gone);
            helper.assertTrue(g != null, "the scholar is about");
            g.setJob(StationTask.GUARD);
            Inventions.morningForTests(level, v, day + 1);
            List<UUID> after = Scholars.appointed(id);
            Kit.log("iv02 scholars after " + g.displayNameCap() + " went to the watch: " + after);
            helper.assertTrue(!after.contains(gone) && after.size() == scholars.size(), "the next best took its desk: " + after);
            helper.assertTrue(told(id, "left the scholars' desks for the watch"), "said in the chronicle");
            // A eureka: a quarter of it at a stroke.
            Invention now = Inventions.current(id);
            int before = Inventions.progress(id);
            int rate = Inventions.rate(level, id, day + 2).hundredths();
            Inventions.rollForTests(new int[]{ 0, 999 });
            Inventions.morningForTests(level, v, day + 2);
            int eureka = Inventions.progress(id) - before;
            Kit.log("iv02 a eureka on " + now + ": +" + eureka + " (the day's " + rate + ")");
            helper.assertTrue(Inventions.known(id, now) || eureka >= now.cost * 25, "a eureka puts a quarter on: " + eureka);
            helper.assertTrue(told(id, "had a eureka"), "the eureka told");
            // A failed experiment at the Iron Age's work.
            Villages.ageForTests(id, Villages.Age.IRON);
            Inventions.beginForTests(level, id, Invention.MEDICINE, day + 2);
            Inventions.progressForTests(id, 3000);
            Inventions.rollForTests(new int[]{ 999, 0 });
            int r3 = Inventions.rate(level, id, day + 3).hundredths();
            Inventions.morningForTests(level, v, day + 3);
            int fell = Inventions.progress(id);
            Kit.log("iv02 a failed experiment: 3000 + the day's " + r3 + " -> " + fell);
            helper.assertTrue(fell == Math.min(3000 + r3 - Invention.MEDICINE.cost * 10, Invention.MEDICINE.cost * 50), "a tenth lost: " + fell);
            helper.assertTrue(told(id, "went up in smoke"), "the failure told");
            Inventions.rollForTests(null);
            helper.succeed();
        });
    }

    // ============================================================ iv03: what is known, built, and what it does

    /**
     * The academy wanted once the town keeps a scholar; the watermill, the clocktower and the aqueduct wanted only once
     * known; standing, the watermill grinds for the bakery (three times the batch) and drives the smith's hammer, the
     * clocktower quickens every trade and strikes the hour, the aqueduct's water lifts the town; the crane, the ledgers,
     * physic and the millstones each do what they say.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "iv03_buildings_and_effects")
    public static void iv03_buildings_and_effects(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2284000;
        Villages.Village v = town(helper, level, x, 12, true);
        UUID id = v.id();
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            Villages.ageForTests(id, Villages.Age.IRON);
            List<VillageFolkEntity> folk = grown(id);
            Villages.electElder(id, folk.get(0), day);
            Inventions.rollForTests(new int[]{ 999, 999 });
            Inventions.morningForTests(level, v, day);
            int n = Villages.headcount(id);
            List<String> extras = new ArrayList<>();
            InventionWorks.wanted(id, n, Villages.Age.IRON, extras, s -> true);
            helper.assertTrue(extras.contains(InventionWorks.ACADEMY), "a town of " + n + " with a scholar wants an academy: " + extras);
            helper.assertTrue(!extras.contains(InventionWorks.WATERMILL) && !extras.contains(InventionWorks.CLOCKTOWER),
                "nothing it does not know: " + extras);
            helper.assertTrue(Villages.whyBuild(id, InventionWorks.ACADEMY).contains("scholars"), "why: " + Villages.whyBuild(id, InventionWorks.ACADEMY));
            // Known: wanted.
            int smithBefore = CityTree.workPercent(id, StationTask.SMITH), farmBefore = CityTree.workPercent(id, StationTask.FARM);
            int buildBefore = CityTree.buildPercent(id), takingsBefore = CityTree.takingsPercent(id);
            int lifeBefore = CityTree.lifespan(id, 80);
            for (Invention i : new Invention[]{ Invention.WINDMILL, Invention.TREADWHEEL_CRANE, Invention.WATERMILL, Invention.CLOCKTOWER,
                Invention.AQUEDUCT, Invention.DOUBLE_ENTRY, Invention.MEDICINE }) Inventions.grant(level, id, i);
            extras.clear();
            InventionWorks.wanted(id, n, Villages.Age.IRON, extras, s -> true);
            List<String> wanted = Villages.projectsWanted(id);
            Kit.log("iv03 wanted once known: " + extras + "; the town's list " + wanted);
            helper.assertTrue(extras.contains(InventionWorks.WATERMILL) && extras.contains(InventionWorks.CLOCKTOWER) && extras.contains(InventionWorks.AQUEDUCT),
                "the watermill, the clocktower and the aqueduct wanted once known: " + extras);
            helper.assertTrue(wanted.contains(InventionWorks.WATERMILL), "on the town's list: " + wanted);
            helper.assertTrue(Villages.spoken(InventionWorks.CLOCKTOWER).equals("the clocktower"), "named");
            // The millstones: a farming town of eight wants its windmill.
            List<String> look = new ArrayList<>();
            folk.get(1).setJob(StationTask.FARM);
            TownLook.wanted(id, 8, Villages.Age.STONE, look, s -> true);
            helper.assertTrue(look.contains(TownLook.WINDMILL), "the windmill wanted from eight once its stones are known: " + look);
            folk.get(1).setJob(StationTask.NONE);
            // The crane, the ledgers, physic: at once.
            helper.assertTrue(CityTree.buildPercent(id) == buildBefore + 12, "the crane: building " + buildBefore + " -> " + CityTree.buildPercent(id));
            helper.assertTrue(CityTree.takingsPercent(id) > takingsBefore, "the ledgers: takings " + takingsBefore + " -> " + CityTree.takingsPercent(id));
            helper.assertTrue(CityTree.lifespan(id, 80) == lifeBefore + 8, "physic: a tenth longer, " + lifeBefore + " -> " + CityTree.lifespan(id, 80));
            // Standing: the watermill, the clocktower, the aqueduct.
            helper.assertTrue(InventionWorks.batchTimes(id, false) == 1 || InventionWorks.batchTimes(id, false) == 3, "no mill yet");
            int noMill = InventionWorks.batchTimes(id, false);
            BlockPos heart = v.centre();
            building(level, id, InventionWorks.WATERMILL, heart.offset(30, 0, 0));
            building(level, id, InventionWorks.CLOCKTOWER, heart.offset(-30, 0, 0));
            building(level, id, InventionWorks.AQUEDUCT, heart.offset(0, 0, 30));
            int smithAfter = CityTree.workPercent(id, StationTask.SMITH), farmAfter = CityTree.workPercent(id, StationTask.FARM);
            List<String> good = new ArrayList<>();
            int water = InventionWorks.contentment(id, good);
            Kit.log("iv03 standing: batch " + noMill + " -> " + InventionWorks.batchTimes(id, false) + "; smith " + smithBefore + " -> " + smithAfter
                + "; farm " + farmBefore + " -> " + farmAfter + "; contentment +" + water + " " + good);
            helper.assertTrue(noMill == 1 && InventionWorks.batchTimes(id, false) == 3 && InventionWorks.milled(id), "the watermill grinds: three times the batch");
            helper.assertTrue(smithAfter == smithBefore + 8 + 3, "the trip-hammer and the clock: smith " + smithBefore + " -> " + smithAfter);
            helper.assertTrue(farmAfter == farmBefore + 3, "the clock: every trade 3% quicker, " + farmBefore + " -> " + farmAfter);
            helper.assertTrue(water == 3, "the aqueduct's clean water: " + water);
            // The clock strikes the hour: three at three in the afternoon (one struck now, two to come).
            level.setDayTime(day * 24000L + 8990L);
            InventionWorks.chimeForTests(level, v);
            level.setDayTime(day * 24000L + 9010L);
            InventionWorks.chimeForTests(level, v);
            int left = InventionWorks.strokesForTests(id);
            Kit.log("iv03 three o'clock: strokes still to strike " + left);
            helper.assertTrue(left == 2, "three strokes at three, one struck: " + left);
            level.setDayTime(day * 24000L + 8000L);
            Inventions.rollForTests(null);
            helper.succeed();
        });
    }

    // ============================================================ iv04: new arms made, and carried into a battle

    /**
     * A town that knows Steel, the Pike, the War Hammer and the Longbow, its foundry standing: its smith blasts steel of
     * the stores' iron and coal and forges steel swords, pikes and war hammers, and (with no fletcher) longbows, the first
     * of each in the chronicle; at war with a town that keeps horses, its army's line is handed the pikes out of the stores
     * at the muster, and a pikeman is worth more in the line. The blow: a war hammer goes through a zombie's iron that a
     * sword does not, and a pike strikes a rider half again as hard. On paper: pikes braced against a charge, and longbows in
     * the archers' duel, win more of the same battle.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "iv04_arms_made_and_used")
    public static void iv04_arms_made_and_used(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2286000;
        Kit.reset(level);
        Inventions.resetForTests();
        WarAndPeace.resetForTests();
        Campaigns.resetForTests();
        Campaigns.hurryForTests(true);
        level.setDayTime(40L * 24000L + 1200L);
        Villages.Village a = town(helper, level, x, 12, false), b = town(helper, level, x + APART, 8, false);
        helper.assertTrue(!a.id().equals(b.id()), "two towns");
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        AtomicReference<Zombie[]> marks = new AtomicReference<>();
        // Two zombies in iron, put out a few ticks ahead so their armour is on them (the game puts it on at their tick).
        helper.runAtTickTime(8, () -> {
            BlockPos at = a.centre().offset(12, 0, 12);
            marks.set(new Zombie[]{ armoured(level, at), armoured(level, at.east(3)) });
        });
        helper.runAtTickTime(20, () -> {
            UUID id = a.id();
            long day = level.getDayTime() / 24000L;
            Villages.ageForTests(id, Villages.Age.IRON);
            for (Invention i : new Invention[]{ Invention.PIKE, Invention.STEEL, Invention.WAR_HAMMER, Invention.LONGBOW }) Inventions.grant(level, id, i);
            building(level, id, InventionWorks.FOUNDRY, a.centre().offset(-30, 0, 0));
            emptyStores(level, id);
            stock(level, id, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.COAL, 32), new ItemStack(Items.STICK, 64),
                new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.STRING, 16));
            List<VillageFolkEntity> folk = grown(id);
            UUID elder = Villages.elder(id);
            VillageFolkEntity smith = null;
            for (VillageFolkEntity f : folk) if (!f.getUUID().equals(elder)) { smith = f; break; }
            smith.setJob(StationTask.SMITH);
            // The blow, first (nobody in the field yet): a war hammer through iron, a pike at a rider.
            Zombie z1 = marks.get()[0], z2 = marks.get()[1];
            smith.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            float h1 = z1.getHealth();
            z1.hurt(level.damageSources().mobAttack(smith), 6.0F);
            float sword = h1 - z1.getHealth();
            smith.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(InventionItems.WAR_HAMMER.get()));
            float h2 = z2.getHealth();
            z2.hurt(level.damageSources().mobAttack(smith), 6.0F);
            float hammer = h2 - z2.getHealth();
            smith.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            float rider = InventionArms.blowForTests(new ItemStack(InventionItems.PIKE.get()), 6.0F, true, 0);
            Kit.log("iv04 a blow of six at a zombie in iron (" + z1.getArmorValue() + " points): the sword's took " + sword + ", the hammer's " + hammer
                + "; a pike's at a rider " + rider);
            helper.assertTrue(hammer > sword + 0.5F, "the hammer goes through the iron: " + sword + " -> " + hammer);
            helper.assertTrue(rider == 9.0F, "a pike at a rider: half again: " + rider);
            z1.discard();
            z2.discard();
            // The watch, and the war: the arms come before the age's iron.
            int g = 0;
            for (VillageFolkEntity f : folk) {
                if (f.getUUID().equals(elder) || f == smith || g >= 6) continue;
                f.setJob(StationTask.GUARD);
                f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                g++;
            }
            for (VillageFolkEntity f : grown(b.id())) {
                if (f.getUUID().equals(Villages.elder(b.id()))) continue;
                f.setJob(StationTask.GUARD);
                f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
            Ledger.relate(a.id(), b.id(), -70 - Ledger.relation(a.id(), b.id()));
            WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.TRIBUTE, 20, "twenty coins in tribute");
            WarAndPeace.backdateForTests(a.id(), b.id(), 3);
            // The smith at its work.
            List<String> made = new ArrayList<>();
            for (int k = 0; k < 40; k++) {
                String m = InventionArms.smith(level, a, smith);
                if (m == null) break;
                made.add(m);
            }
            int steel = count(level, id, s -> s.is(InventionItems.STEEL_INGOT.get())), swords = count(level, id, s -> s.is(InventionItems.STEEL_SWORD.get()));
            int pikes = count(level, id, s -> s.is(InventionItems.PIKE.get())), hammers = count(level, id, s -> s.is(InventionItems.WAR_HAMMER.get()));
            int longbows = count(level, id, s -> s.is(InventionItems.LONGBOW.get()));
            Kit.log("iv04 the smith made " + made.size() + ": " + made + "; in the stores " + steel + " steel, " + swords + " steel swords, " + pikes
                + " pikes, " + hammers + " hammers, " + longbows + " longbows");
            helper.assertTrue(made.contains("an ingot of steel at the foundry"), "steel blasted at the foundry");
            helper.assertTrue(swords >= 1 && pikes >= 2 && hammers >= 1 && longbows >= 1, "the new arms forged");
            helper.assertTrue(told(id, "made the town's first pike"), "the first pike in the chronicle");
            helper.assertTrue(count(level, id, s -> s.is(Items.IRON_INGOT)) < 64, "of the stores' iron");
            // A town that keeps horses: the muster hands the line its pikes.
            Ledger.built(b.id(), "stable", b.centre().offset(20, 0, 20), Direction.NORTH);
            Campaigns.watchForTests(true);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, false, false);
            helper.assertTrue(c != null, "a campaign begun");
            Campaigns.nextForTests(level, c);
            camp.set(c);
            Armies.Army army = c.army(0);
            helper.assertTrue(army != null, "the army mustered");
            int poled = 0, plain = 0, polePower = 0;
            for (Armies.Fighter f : army.fighters()) {
                if (!(level.getEntity(f.id()) instanceof VillageFolkEntity e)) continue;
                boolean pole = e.getMainHandItem().is(InventionItems.PIKE.get()) || e.getMainHandItem().is(InventionItems.WAR_HAMMER.get());
                if (pole) { poled++; polePower = Math.max(polePower, InventionArms.powerForTests(e)); }
                else if (f.role() == BattleModel.Role.LINE) plain++;
            }
            Kit.log("iv04 the muster: " + poled + " with pikes or hammers, " + plain + " with their swords; " + army.missileWords()
                + "; a pikeman's worth " + polePower);
            helper.assertTrue(poled >= 1, "the line handed the town's pikes at the muster");
            helper.assertTrue(polePower > 0, "a pikeman worth something in the line: " + polePower);
            // On paper: the same battle a hundred times over, with the pikes and without, against a charge.
            int without = 0, with = 0, lbWithout = 0, lbWith = 0;
            for (int t = 0; t < 120; t++) {
                if (paper(t, 0, 0, BattleModel.Tactic.CAVALRY) == 0) without++;
                if (paper(t, 9, 0, BattleModel.Tactic.CAVALRY) == 0) with++;
                if (paper(t, 0, 0, BattleModel.Tactic.STRAIGHT) == 0) lbWithout++;
                if (paper(t, 0, 6, BattleModel.Tactic.STRAIGHT) == 0) lbWith++;
            }
            Kit.log("iv04 on paper, of 120: against a charge " + without + " won without pikes, " + with + " with nine; archers' duel " + lbWithout
                + " won with bows, " + lbWith + " with six longbows");
            helper.assertTrue(with > without, "the pikes win more against a charge: " + without + " -> " + with);
            ArmsEdge.Kit bows = new ArmsEdge.Kit();
            bows.longbows = 6;
            helper.assertTrue(ArmsEdge.volley(bows, 6) > 1.3 && lbWith >= lbWithout, "the longbows loose harder in the duel: "
                + ArmsEdge.volley(bows, 6) + "; won " + lbWithout + " -> " + lbWith);
        });
        helper.runAtTickTime(60, () -> {
            Campaigns.Campaign c = camp.get();
            if (c != null) Campaigns.resetForTests();
            helper.succeed();
        });
    }

    /** A zombie in a full suit of iron, standing still. */
    private static Zombie armoured(ServerLevel level, BlockPos at) {
        Zombie z = EntityType.ZOMBIE.create(level);
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        z.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
        z.setNoAi(true);
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        z.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        z.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        level.addFreshEntity(z);
        return z;
    }

    /** One battle on paper, side 0 (with so many pikes and longbows) against an equal side 1 (its tactic). The winner. */
    private static int paper(int seed, int pikes, int longbows, BattleModel.Tactic theirs) {
        BattleModel.Side us = new BattleModel.Side("Us", 20, 20 * 6, 6, 60, BattleModel.Tactic.STRAIGHT);
        BattleModel.Side them = new BattleModel.Side("Them", 20, 20 * 6, 6, 60, theirs);
        us.kit.pikes = pikes;
        us.kit.longbows = longbows;
        them.kit.armour = 8;
        us.kit.armour = 8;
        return BattleModel.resolve(us, them, 0, 0, 0, 0, 0, new Random(1000L + seed)).winner();
    }

    // ============================================================ iv05: a spy's sketches, a vassal's teaching, the admin

    /**
     * A rival that knows the Pike, and our spy home from it with a fresh report: the scholars at the Pike get two fifths
     * of it at a stroke, once a report. A vassal that knows the Longbow teaches its overlord. And the admin's tools: one
     * invention unlocked, the work in hand finished, everything of the age granted, and reset.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "iv05_spies_vassals_admin")
    public static void iv05_spies_vassals_admin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2290000;
        Villages.Village a = town(helper, level, x, 8, true), b = town(helper, level, x + APART, 7, false);
        helper.runAtTickTime(5, () -> {
            UUID us = a.id(), them = b.id();
            long day = level.getDayTime() / 24000L;
            Inventions.rollForTests(new int[]{ 999, 999 });
            Villages.electElder(us, grown(us).get(0), day);
            Inventions.grant(level, them, Invention.PIKE);
            Ledger.relate(us, them, -70 - Ledger.relation(us, them));
            Inventions.morningForTests(level, a, day);
            Inventions.beginForTests(level, us, Invention.PIKE, day);
            Intel.file(us, new Intel.Report(them, day, 7, 2, 0, 0, 0, 1, 10, ""));
            int before = Inventions.progress(us);
            int rate = Inventions.rate(level, us, day + 1).hundredths();
            Inventions.morningForTests(level, a, day + 1);
            int gained = Inventions.progress(us) - before;
            Kit.log("iv05 a spy's sketches of the pike: +" + gained + " (the day's own " + rate + ")");
            helper.assertTrue(gained >= Invention.PIKE.cost * 40 || Inventions.known(us, Invention.PIKE), "two fifths at a stroke: " + gained);
            helper.assertTrue(told(us, "a stolen idea"), "the stolen idea told");
            int again = Inventions.progress(us);
            Inventions.morningForTests(level, a, day + 2);
            helper.assertTrue(Inventions.known(us, Invention.PIKE) || Inventions.progress(us) - again <= rate + 100, "the same report used once");
            // A vassal teaches its overlord.
            Inventions.grant(level, them, Invention.LONGBOW);
            int knewBefore = Inventions.knownIds(us).size();
            Vassals.makeForTests(level, us, them, day);
            long teach = day + 3 - Math.floorMod(day + 3, 3) + 3;
            Inventions.newMorningForTests(them);
            Inventions.morningForTests(level, b, teach);
            Kit.log("iv05 the vassal's teaching on day " + teach + ": the overlord knows " + Inventions.knownIds(us));
            helper.assertTrue(Inventions.knownIds(us).size() == knewBefore + 1, "the overlord taught one thing by its vassal: " + Inventions.knownIds(us));
            helper.assertTrue(told(them, "were made to teach"), "the vassal's teaching told");
            // The admin: unlock, finish, everything of the age, reset.
            BlockPos h = a.centre();
            String at = "execute positioned " + h.getX() + " " + h.getY() + " " + h.getZ() + " run ";
            List<String> unlock = Kit.command(level, at + "village inventions grant steel");
            Invention now = Inventions.current(us);
            List<String> finish = Kit.command(level, at + "village inventions now");
            List<String> all = Kit.command(level, at + "village inventions all");
            int known = Inventions.knownIds(us).size();
            Kit.log("iv05 admin: " + unlock + " / " + finish + " / " + all + "; known " + known);
            helper.assertTrue(Inventions.known(us, Invention.STEEL), "granted by command");
            helper.assertTrue(now == null || Inventions.known(us, now), "the work in hand finished by command: " + now);
            int stoneAge = 0;
            for (Invention i : Invention.values()) if (i.age <= Villages.ageOf(us).ordinal()) stoneAge++;
            helper.assertTrue(known >= stoneAge, "everything of its age known: " + known + " of " + stoneAge);
            Kit.command(level, at + "village inventions reset");
            helper.assertTrue(Inventions.knownIds(us).isEmpty(), "forgotten by command");
            Inventions.rollForTests(null);
            helper.succeed();
        });
    }

    /** The town's name, as the firsts keep it. */
    private static final class InventionsHelper {
        static String name(UUID id) {
            return Villages.name(id);
        }
    }
}
