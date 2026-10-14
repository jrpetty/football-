package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.CameraCommands;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Armies;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Battles;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.WarDress;
import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [war-polish] Battles that read at a glance: the two armies in clearly different colours (trims, tunics, painted
 * shields, name tags), put on at the muster and every bit of it taken off again after the battle; the lines drawn
 * up in even ranks and files in every formation, the Captain behind them; the war's course written once per event;
 * one battle, one outcome, however it is staged on; and the pictures' camera kept out of the ground.
 *
 * <p>Each on its own ground in x 1920000-1939999, z 66000; two towns 260 blocks apart.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WarPolishGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final int APART = 260;

    private static Villages.Village town(ServerLevel level, int x, int n) {
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        return Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
    }

    private static void start(ServerLevel level) {
        Kit.reset(level);
        WarAndPeace.resetForTests();
        Campaigns.resetForTests();
        Campaigns.hurryForTests(true);
        level.setDayTime(40L * 24000L + 1200L);
    }

    private static void war(ServerLevel level, Villages.Village a, Villages.Village b) {
        long day = level.getDayTime() / 24000L;
        Ledger.relate(a.id(), b.id(), -70 - Ledger.relation(a.id(), b.id()));
        WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.TRIBUTE, 20, "twenty coins in tribute");
        WarAndPeace.backdateForTests(a.id(), b.id(), 3);
    }

    /** The ground for a staged battle: the town's neighbour (raised 260 east) and the field between held and made. */
    private static void stageGround(ServerLevel level, int x) {
        for (int dx : new int[]{ APART / 2, APART }) {
            Kit.hold(level, x + dx, Z, 64);
            Kit.prepare(level, x + dx, Z, 64);
        }
    }

    /** So many grown folk to the watch with a blade: every other in an iron chestplate (the rest none), all but every third a shield. */
    private static void guards(UUID v, int n) {
        UUID elder = Villages.elder(v);
        int k = 0;
        for (AssistantEntity a : Villages.folkOf(v)) {
            if (k >= n || !(a instanceof VillageFolkEntity f) || f.isBaby() || f.getUUID().equals(elder)) continue;
            f.setJob(AssistantEntity.StationTask.GUARD);
            f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            f.setItemSlot(EquipmentSlot.CHEST, k % 2 == 0 ? new ItemStack(Items.IRON_CHESTPLATE) : ItemStack.EMPTY);
            f.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            f.setItemSlot(EquipmentSlot.OFFHAND, k % 3 == 2 ? ItemStack.EMPTY : new ItemStack(Items.SHIELD));
            k++;
        }
    }

    private static Map<UUID, ItemStack[]> kits(ServerLevel level, Campaigns.Campaign c) {
        Map<UUID, ItemStack[]> out = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            if (c.army(i) == null) continue;
            for (Armies.Fighter f : c.army(i).fighters()) {
                if (level.getEntity(f.id()) instanceof VillageFolkEntity e) {
                    out.put(f.id(), new ItemStack[]{ e.getItemBySlot(EquipmentSlot.CHEST).copy(), e.getItemBySlot(EquipmentSlot.FEET).copy(),
                        e.getOffhandItem().copy(), e.getItemBySlot(EquipmentSlot.HEAD).copy() });
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ wp01

    /**
     * The colours: two towns at war muster in clearly different colours (the trims of every armour piece in the
     * army's material, a leather tunic in its dye on anybody with no metal on the chest, the shields painted in its
     * colour, the name tags in its team's colour), and after the battle every bit of it is gone: the kit exactly as it
     * was, no tunic or standard left anywhere, the teams struck. Two towns of the same colour get contrasting ones.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wp01_colours")
    public static void wp01_colours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1920000;
        Villages.Village a = town(level, x, 30), b = town(level, x + APART, 14);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        WarDress.Tint[] clash = WarDress.pick(List.of(DyeColor.BLUE), List.of(DyeColor.LIGHT_BLUE, DyeColor.CYAN));
        helper.assertTrue(clash[0] == WarDress.Tint.LAPIS && clash[1].apart(clash[0]), "a blue town against a blue one: the second in a contrasting colour, not "
            + clash[1]);
        helper.runAtTickTime(20, () -> {
            guards(a.id(), 26);
            guards(b.id(), 10);
            war(level, a, b);
            Campaigns.watchForTests(false);
            Battles.parleyForTests(false);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, false, false);
            Campaigns.nextForTests(level, c);
            helper.assertTrue(c.stage() == Campaigns.Stage.MUSTER && c.army(0) != null && c.army(1) != null, "both armies mustered: " + c.stage());
            WarDress.Tint[] t = WarDress.tintsForTests(c);
            helper.assertTrue(t[0].apart(t[1]), "the two armies in clearly different colours: " + t[0] + " and " + t[1]);
            int trimmed = 0, tunics = 0, shields = 0, standards = 0, line = 0;
            for (int i = 0; i < 2; i++) {
                PlayerTeam team = level.getScoreboard().getPlayerTeam(WarDress.teamForTests(c.army(i).town()));
                helper.assertTrue(team != null && team.getColor() == t[i].chat, "army " + i + "'s team in its colour: " + (team == null ? "none" : team.getColor()));
                for (Armies.Fighter f : c.army(i).fighters()) {
                    if (!(level.getEntity(f.id()) instanceof VillageFolkEntity e)) continue;
                    if (i == 0 && f.role() == BattleModel.Role.LINE && (f.rank() == Armies.Rank.NONE || f.rank() == Armies.Rank.SERGEANT)) line++;
                    helper.assertTrue(team.getPlayers().contains(e.getScoreboardName()), e.displayNameCap() + " on its army's team");
                    for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HEAD }) {
                        ItemStack s = e.getItemBySlot(slot);
                        if (slot == EquipmentSlot.HEAD && s.getItem() instanceof BannerItem) { standards++; continue; }
                        if (!(s.getItem() instanceof ArmorItem)) continue;
                        helper.assertTrue(t[i].material.equals(WarDress.trimForTests(s)), e.displayNameCap() + "'s " + slot + " trimmed in " + t[i]);
                        trimmed++;
                    }
                    ItemStack chest = e.getItemBySlot(EquipmentSlot.CHEST);
                    helper.assertTrue(chest.getItem() instanceof ArmorItem, e.displayNameCap() + " has something on its chest in the colours");
                    if (chest.is(Items.LEATHER_CHESTPLATE)) {
                        tunics++;
                        int dye = chest.getOrDefault(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(0, false)).rgb();
                        helper.assertTrue(dye == (t[i].dye.getTextureDiffuseColor() & 0xFFFFFF), "the tunic dyed " + t[i].dye);
                    }
                    if (e.getOffhandItem().getItem() instanceof ShieldItem) {
                        shields++;
                        helper.assertTrue(e.getOffhandItem().get(DataComponents.BASE_COLOR) == t[i].dye, "the shield painted " + t[i].dye);
                    }
                }
            }
            Kit.log("wp01 dressed: " + t[0] + " against " + t[1] + "; " + trimmed + " pieces trimmed, " + tunics + " tunics, " + shields + " shields, "
                + standards + " helm standards (" + line + " in army 0's line)");
            helper.assertTrue(trimmed > 0 && tunics > 0 && shields > 0, "trims, tunics and shields all in the colours");
            helper.assertTrue(line <= WarDress.COMPANY || standards > 0, "a company standard on a helm past the first company");
        });
        helper.runAtTickTime(40, () -> {
            Campaigns.Campaign c = Campaigns.ofTown(a.id());
            Map<UUID, ItemStack[]> k = kits(level, c);                // what each wore in the colours (a tunic: lent)
            for (int i = 0; i < 4 && c.stage().ordinal() < Campaigns.Stage.AFTER.ordinal(); i++) {
                if (c.stage() == Campaigns.Stage.MARCH) Campaigns.arriveForTests(c);
                Campaigns.nextForTests(level, c);
            }
            for (int i = 0; i < 4 && c.stage() == Campaigns.Stage.BATTLE; i++) Campaigns.nextForTests(level, c);
            helper.assertTrue(c.stage().ordinal() >= Campaigns.Stage.AFTER.ordinal(), "the battle fought (on paper): " + c.stage());
            int left = 0, lent = 0;
            for (Map.Entry<UUID, ItemStack[]> en : k.entrySet()) {
                if (!(level.getEntity(en.getKey()) instanceof VillageFolkEntity e)) continue;
                if (WarDress.dressed(e)) left++;
                for (ItemStack s : e.getInventoryItems()) if (WarDress.dressed(s) || s.is(Items.LEATHER_CHESTPLATE)) lent++;
                ItemStack chest = e.getItemBySlot(EquipmentSlot.CHEST), feet = e.getItemBySlot(EquipmentSlot.FEET);
                ItemStack was = en.getValue()[0];
                helper.assertTrue(feet.isEmpty() || WarDress.trimForTests(feet) == null, e.displayNameCap() + "'s boots without the battle's trim");
                if (was.is(Items.LEATHER_CHESTPLATE)) helper.assertTrue(chest.isEmpty() || !chest.is(Items.LEATHER_CHESTPLATE),
                    e.displayNameCap() + "'s lent tunic taken back");
                else if (!chest.isEmpty()) helper.assertTrue(WarDress.trimForTests(chest) == null, e.displayNameCap() + "'s chestplate its own again");
                if (e.getOffhandItem().getItem() instanceof ShieldItem) {
                    helper.assertTrue(!e.getOffhandItem().has(DataComponents.BASE_COLOR), e.displayNameCap() + "'s shield plain again");
                }
            }
            for (int i = 0; i < 2; i++) {
                UUID town = i == 0 ? a.id() : b.id();
                helper.assertTrue(level.getScoreboard().getPlayerTeam(WarDress.teamForTests(town)) == null, "the army's team struck");
            }
            Kit.log("wp01 after the battle (" + c.stage() + "): " + left + " still dressed, " + lent + " lent pieces left");
            helper.assertTrue(left == 0, "nobody left in battle dress: " + left);
            helper.assertTrue(lent == 0, "no tunic or standard kept: " + lent);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp02

    /**
     * The line-up: in every formation the line stands in even ranks and files, each a little off its slot at most (no
     * clumps, no gaps), and the Captain with the standard behind the centre, behind the line.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wp02_lineup")
    public static void wp02_lineup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1924000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        helper.runAtTickTime(20, () -> {
            stageGround(level, x);
            List<String> out = Campaigns.stage(level, a, Kit.surface(level, x + APART / 2, Z), "lineup");
            helper.assertTrue(!out.isEmpty() && out.get(0).startsWith("STAGED lineup"), "staged: " + out);
            Campaigns.Campaign c = Campaigns.ofTown(a.id());
            helper.assertTrue(out.stream().anyMatch(l -> l.startsWith("VIEW behind0")) && out.stream().anyMatch(l -> l.startsWith("VIEW behind1")),
                "a view from behind each army");
            for (BattleModel.Formation form : BattleModel.Formation.values()) {
                Battles.formForTests(level, c, 0, form);
                double[] o = Battles.originForTests(c, 0);
                List<double[]> line = new ArrayList<>();
                double capDepth = 0, lineBack = 0;
                for (Armies.Fighter f : c.army(0).fighters()) {
                    if (f.down()) continue;
                    double[] p = Armies.placeForTests(f);
                    double depth = (p[0] - o[0]) * o[2] + (p[2] - o[1]) * o[3];
                    if (f.rank() == Armies.Rank.CAPTAIN) capDepth = depth;
                    if (f.role() == BattleModel.Role.LINE && (f.rank() == Armies.Rank.NONE || f.rank() == Armies.Rank.SERGEANT)) {
                        line.add(new double[]{ p[0], p[2] });
                        lineBack = Math.min(lineBack, depth);
                    }
                }
                int n = line.size();
                helper.assertTrue(n >= 3, "a line to draw up: " + n);
                double[] ideal = nearestRange(form, n), actual = nearest(line);
                Kit.log("wp02 " + form + " of " + n + ": nearest neighbour " + fmt(actual) + " (slots " + fmt(ideal) + "); Captain " + capDepth
                    + ", the line's back " + lineBack);
                helper.assertTrue(actual[0] >= ideal[0] - SLACK && actual[1] <= ideal[1] + SLACK,
                    form + ": even ranks and files, " + fmt(actual) + " against " + fmt(ideal));
                helper.assertTrue(capDepth < lineBack, form + ": the Captain behind the line (" + capDepth + " against " + lineBack + ")");
            }
            helper.succeed();
        });
    }

    /** How far a mark may sit off its slot, both ways (Battles.STILL_JITTER and STILL_STEP), twice: two neighbours. */
    private static final double SLACK = 0.65;

    /** The nearest-neighbour distance of every slot of n in this formation: the least and the most. */
    private static double[] nearestRange(BattleModel.Formation f, int n) {
        List<double[]> slots = new ArrayList<>();
        for (int k = 0; k < n; k++) slots.add(f.slot(k, n));
        return nearest(slots);
    }

    private static double[] nearest(List<double[]> ps) {
        double lo = Double.MAX_VALUE, hi = 0;
        for (int i = 0; i < ps.size(); i++) {
            double best = Double.MAX_VALUE;
            for (int j = 0; j < ps.size(); j++) {
                if (i == j) continue;
                double dx = ps.get(i)[0] - ps.get(j)[0], dz = ps.get(i)[1] - ps.get(j)[1];
                best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
            }
            lo = Math.min(lo, best);
            hi = Math.max(hi, best);
        }
        return new double[]{ lo, hi };
    }

    private static String fmt(double[] r) {
        return String.format(java.util.Locale.ROOT, "%.2f-%.2f", r[0], r[1]);
    }

    // ------------------------------------------------------------------ wp03

    /** The war's course: a battle staged on and staged again from its start writes each thing once in both towns' books. */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wp03_log")
    public static void wp03_log(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1928000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        helper.runAtTickTime(20, () -> {
            stageGround(level, x);
            BlockPos field = Kit.surface(level, x + APART / 2, Z);
            for (String phase : new String[]{ "muster", "lineup", "parley", "muster", "lineup" }) Campaigns.stage(level, a, field, phase);
            Campaigns.Campaign c = Campaigns.ofTown(a.id());
            helper.assertTrue(c != null, "a staged battle");
            for (UUID[] p : new UUID[][]{ { c.attacker(), c.defender() }, { c.defender(), c.attacker() } }) {
                List<String> written = Battles.courseWrittenForTests(p[0], p[1]);
                Set<String> once = new HashSet<>(written);
                Kit.log("wp03 " + Villages.name(p[0]) + "'s course: " + written);
                helper.assertTrue(!written.isEmpty(), "the war's course written");
                helper.assertTrue(once.size() == written.size(), "each event written once: " + written);
                helper.assertTrue(new HashSet<>(WarAndPeace.courseForTests(p[0], p[1])).size() == WarAndPeace.courseForTests(p[0], p[1]).size(),
                    "and read once");
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wp04

    /**
     * One battle, one outcome: staged at the clash it stays locked (nobody breaks on a stage, however long); staged on
     * to the rout, one side breaks, once; on to the aftermath, the same outcome, and the field held as it is.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wp04_outcome")
    public static void wp04_outcome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1932000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        AtomicReference<BlockPos> field = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            stageGround(level, x);
            field.set(Kit.surface(level, x + APART / 2, Z));
            Campaigns.stage(level, a, field.get(), "lineup");
            Campaigns.stage(level, a, field.get(), "clash");
            Campaigns.hurryForTests(true);                       // the phases short: a battle not held would be over in seconds
        });
        helper.runAtTickTime(160, () -> {
            Battles.Battle b = Campaigns.ofTown(a.id()).battle();
            helper.assertTrue(b != null && b.outcome() == null && outcomes(b) == 0, "held at the clash: no outcome yet: " + (b == null ? "none" : b.story()));
            Campaigns.stage(level, a, field.get(), "rout");
            helper.assertTrue(b.outcome() == BattleModel.Outcome.ROUT && outcomes(b) == 1, "the rout: one outcome: " + b.story());
            Campaigns.stage(level, a, field.get(), "aftermath");
            Campaigns.hurryForTests(true);
        });
        helper.runAtTickTime(300, () -> {
            Campaigns.Campaign c = Campaigns.ofTown(a.id());
            Battles.Battle b = c.battle();
            helper.assertTrue(b != null && !b.over(), "the aftermath held for the pictures, not written up");
            Kit.log("wp04 the story: " + b.story());
            helper.assertTrue(b.outcome() == BattleModel.Outcome.ROUT && b.winner() == 0 && outcomes(b) == 1, "still the one outcome: " + b.story());
            helper.succeed();
        });
    }

    /** How many times the battle's story says who held the field. */
    private static int outcomes(Battles.Battle b) {
        int n = 0;
        for (String s : b.story()) if (s.contains("held the field") || s.contains("drew off")) n++;
        return n;
    }

    // ------------------------------------------------------------------ wp05

    /** The pictures' camera: one set inside a block of stone steps out of it to where it sees its mark; one in open air stays. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wp05_camera")
    public static void wp05_camera(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1936000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos g = Kit.surface(level, x, Z);
        for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy <= 4; dy++) for (int dz = -2; dz <= 2; dz++) level.setBlockAndUpdate(g.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
        Vec3 target = new Vec3(x + 14.5, g.getY() + 1, Z + 0.5);
        CameraCommands.Spot in = CameraCommands.clearSpot(level, new Vec3(x + 0.5, g.getY() + 1, Z + 0.5), target);
        CameraCommands.Spot open = CameraCommands.clearSpot(level, new Vec3(x + 24.5, g.getY() + 3, Z + 0.5), target);
        Kit.log("wp05 inside the stone: " + in + "; in the open: " + open);
        helper.assertTrue(in.how().equals("moved") && CameraCommands.sees(level, in.feet(), target), "out of the stone to a clear view: " + in);
        helper.assertTrue(open.how().equals("clear"), "a clear view kept: " + open);
        helper.succeed();
    }
}
