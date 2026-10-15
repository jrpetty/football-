package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Armies;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Battles;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.Enchantments;
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
 * [war-bows] Bows and crossbows in the wars: an army mustered with both out of the stores (as its Captain likes them);
 * the crossbow's bolt flat and hard at the crossbow's own power; the crossbow slow to span against the bow's draw; the
 * arrows counted and running out (the archers then draw their blades and join the reserve); a third of the spent
 * arrows gathered off the field by the side that holds it; and the crossbow company loosing a rank at a time, the
 * ranks changing places between volleys (the counter-march).
 *
 * <p>Each on its own ground in x 1960000-1979999, z 66000; two towns 260 blocks apart.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WarBowsGameTests {

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

    /** The ground for a staged battle: the town's neighbour (raised 260 east) and the field between held and made. */
    private static void stageGround(ServerLevel level, int x) {
        for (int dx : new int[]{ APART / 2, APART }) {
            Kit.hold(level, x + dx, Z, 64);
            Kit.prepare(level, x + dx, Z, 64);
        }
    }

    /** A town staged at the volleys (its rival raised 260 east): the battle, or null. */
    private static Battles.Battle volleys(ServerLevel level, Villages.Village a, int x) {
        stageGround(level, x);
        List<String> out = Campaigns.stage(level, a, Kit.surface(level, x + APART / 2, Z), "volley");
        Campaigns.Campaign c = Campaigns.ofTown(a.id());
        Kit.log("wb staged: " + (out.isEmpty() ? "nothing" : out.get(0)));
        return c == null ? null : c.battle();
    }

    private static Campaigns.Campaign campaign(Villages.Village a) {
        return Campaigns.ofTown(a.id());
    }

    private static int arrows(VillageFolkEntity e) {
        return e.countMatching(s -> s.is(Items.ARROW));
    }

    // ------------------------------------------------------------------ wb01

    /**
     * The muster: an Iron Age town whose stores hold bows, crossbows and arrows, its Captain favouring a mix, musters
     * a missile arm of both, out of the stores: every bowman with a bow, every crossbowman with a crossbow, each with a
     * quiver, and every arrow carried one the stores gave up (none made out of nothing). The muster says "N bows, M
     * crossbows".
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "wb01_muster")
    public static void wb01_muster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1960000;
        Villages.Village a = town(level, x, 24), b = town(level, x + APART, 10);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            Villages.ageForTests(a.id(), Villages.Age.IRON);
            UUID elder = Villages.elder(a.id());
            int k = 0;
            for (AssistantEntity e : Villages.folkOf(a.id())) {
                if (k >= 18 || !(e instanceof VillageFolkEntity f) || f.isBaby() || f.getUUID().equals(elder)) continue;
                f.setJob(AssistantEntity.StationTask.GUARD);
                f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                f.setItemSlot(EquipmentSlot.OFFHAND, k % 2 == 0 ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
                f.removeMatching(s -> s.is(Items.ARROW) || s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem, 999);
                k++;
            }
            for (int i = 0; i < 3; i++) {
                Homes.storeForTests(level, a, new ItemStack(Items.BOW));
                Homes.storeForTests(level, a, new ItemStack(Items.CROSSBOW));
            }
            Homes.storeForTests(level, a, new ItemStack(Items.ARROW, 64));
            Homes.storeForTests(level, a, new ItemStack(Items.ARROW, 64));
            Ledger.note(a.id(), "war.ranged", "MIX");
            int before = Market.stock(level, a.id(), s -> s.is(Items.ARROW));
            Ledger.relate(a.id(), b.id(), -70 - Ledger.relation(a.id(), b.id()));
            long day = level.getDayTime() / 24000L;
            WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.TRIBUTE, 20, "twenty coins in tribute");
            Campaigns.watchForTests(false);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, false, false);
            helper.assertTrue(c != null, "a campaign begun");
            Campaigns.nextForTests(level, c);
            Armies.Army army = c.army(0);
            helper.assertTrue(c.stage() == Campaigns.Stage.MUSTER && army != null, "the army mustered: " + c.stage());
            int carried = 0;
            for (Armies.Fighter f : army.fighters()) {
                if (f.role() != BattleModel.Role.ARCHER || !(level.getEntity(f.id()) instanceof VillageFolkEntity e)) continue;
                boolean hasCross = e.countMatching(s -> s.getItem() instanceof CrossbowItem) > 0 || e.getMainHandItem().getItem() instanceof CrossbowItem;
                boolean hasBow = e.countMatching(s -> s.getItem() instanceof BowItem) > 0 || e.getMainHandItem().getItem() instanceof BowItem;
                helper.assertTrue(f.crossbow() ? hasCross : hasBow, e.displayNameCap() + " carries its " + (f.crossbow() ? "crossbow" : "bow"));
                helper.assertTrue(arrows(e) > 0, e.displayNameCap() + " has a quiver");
                carried += arrows(e);
            }
            int after = Market.stock(level, a.id(), s -> s.is(Items.ARROW));
            Kit.log("wb01 mustered: " + army.missileWords() + " (" + army.ranged() + "); arrows in the stores " + before + " -> " + after + ", carried " + carried);
            helper.assertTrue(army.missiles(false) >= 1 && army.missiles(true) >= 1, "both bows and crossbows: " + army.missileWords());
            helper.assertTrue(army.missileWords().contains("bow") && army.missileWords().contains("crossbow"), "the muster in words: " + army.missileWords());
            helper.assertTrue(carried > 0 && before - after >= carried, "every arrow carried came out of the stores: " + carried + " carried, "
                + (before - after) + " taken");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wb02

    /**
     * The bolt: a crossbow's shot is a real arrow out of the shooter's pack, fired by the crossbow's own shooting at the
     * crossbow's power, flat and hard: far more punch than a bow's arcing volley arrow at the same mark. Piercing on
     * the crossbow carries into the bolt.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "wb02_bolt")
    public static void wb02_bolt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1964000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        helper.runAtTickTime(20, () -> {
            List<VillageFolkEntity> grown = new ArrayList<>();
            for (AssistantEntity e : Villages.folkOf(a.id())) if (e instanceof VillageFolkEntity f && !f.isBaby()) grown.add(f);
            helper.assertTrue(grown.size() >= 3, "three grown folk");
            VillageFolkEntity xb = grown.get(0), bow = grown.get(1), mark = grown.get(2);
            BlockPos g = Kit.surface(level, x, Z);
            xb.teleportTo(x + 0.5, g.getY(), Z + 0.5);
            bow.teleportTo(x + 0.5, g.getY(), Z + 2.5);
            mark.teleportTo(x + 14.5, g.getY(), Z + 1.5);
            ItemStack cross = new ItemStack(Items.CROSSBOW);
            cross.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.PIERCING), 2);
            xb.setItemSlot(EquipmentSlot.MAINHAND, cross);
            xb.removeMatching(s -> s.is(Items.ARROW), 999);
            xb.insertGiven(new ItemStack(Items.ARROW, 4));
            bow.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            bow.removeMatching(s -> s.is(Items.ARROW), 999);
            bow.insertGiven(new ItemStack(Items.ARROW, 4));
            AbstractArrow bolt = Armies.boltForTests(level, xb, mark), arrow = Armies.looseForTests(level, bow, mark);
            helper.assertTrue(bolt != null && arrow != null, "a bolt and an arrow loosed");
            double boltSpeed = bolt.getDeltaMovement().length(), arrowSpeed = arrow.getDeltaMovement().length();
            int boltHit = (int) Math.ceil(bolt.getBaseDamage() * boltSpeed), arrowHit = (int) Math.ceil(arrow.getBaseDamage() * arrowSpeed);
            Kit.log("wb02 the bolt at " + String.format(java.util.Locale.ROOT, "%.2f", boltSpeed) + " a tick (" + boltHit + " damage, pierce "
                + bolt.getPierceLevel() + "), the bow's volley arrow at " + String.format(java.util.Locale.ROOT, "%.2f", arrowSpeed) + " (" + arrowHit + ")");
            helper.assertTrue(bolt.shotFromCrossbow() && bolt.getWeaponItem() != null && bolt.getWeaponItem().is(Items.CROSSBOW), "shot from the crossbow");
            helper.assertTrue(boltSpeed > 2.8, "at the crossbow's power: " + boltSpeed);
            helper.assertTrue(boltHit >= 6 && boltHit > arrowHit, "the bolt hits harder than the volley arrow: " + boltHit + " against " + arrowHit);
            helper.assertTrue(Math.abs(bolt.getDeltaMovement().y) < Math.abs(arrow.getDeltaMovement().y) * 2 + 0.6, "flat, not lobbed");
            helper.assertTrue(bolt.getPierceLevel() > 0, "Piercing carried into the bolt");
            helper.assertTrue(arrows(xb) == 3 && arrows(bow) == 3, "one real arrow each out of their packs: " + arrows(xb) + ", " + arrows(bow));
            helper.assertTrue(!CrossbowItem.isCharged(xb.getMainHandItem()), "the charge spent");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wb03

    /**
     * The reload: in the archers' duel the bowmen loose every volley while each crossbowman, slow to span, looses about
     * every other: fewer shots a man for the crossbows, and the crossbow's reload longer than the bow's draw.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wb03_reload")
    public static void wb03_reload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1968000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        helper.runAtTickTime(20, () -> helper.assertTrue(volleys(level, a, x) != null, "a battle staged at the volleys"));
        helper.runAtTickTime(560, () -> {
            Campaigns.Campaign c = campaign(a);
            int bows = 0, bowShots = 0, xbows = 0, xbowShots = 0, crossReload = 0, bowDraw = 0;
            for (int i = 0; i < 2; i++) {
                for (Armies.Fighter f : c.army(i).fighters()) {
                    if (f.role() != BattleModel.Role.ARCHER || !(level.getEntity(f.id()) instanceof VillageFolkEntity e)) continue;
                    if (f.crossbow()) {
                        xbows++;
                        xbowShots += f.loosed();
                        crossReload = Armies.reloadTicks(e, e.getMainHandItem());
                    } else {
                        bows++;
                        bowShots += f.loosed();
                        bowDraw = Armies.reloadTicks(e, e.getMainHandItem());
                    }
                }
            }
            Kit.log("wb03 " + bows + " bows loosed " + bowShots + ", " + xbows + " crossbows loosed " + xbowShots + "; reload " + crossReload
                + " ticks against a draw of " + bowDraw);
            helper.assertTrue(bows > 0 && xbows > 0, "bows and crossbows both in the field: " + bows + ", " + xbows);
            helper.assertTrue(xbowShots > 0, "the crossbows loosed");
            helper.assertTrue(xbowShots * bows < bowShots * xbows, "fewer shots a man for the crossbows: " + xbowShots + "/" + xbows + " against "
                + bowShots + "/" + bows);
            helper.assertTrue(crossReload > bowDraw, "the crossbow slower to make ready: " + crossReload + " against " + bowDraw);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wb04

    /**
     * Arrows run out: archers left with two arrows each loose them and no more (nothing made out of nothing), then put
     * up their bows, draw their blades and join the reserve.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wb04_run_out")
    public static void wb04_run_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1972000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        Map<UUID, Integer> given = new HashMap<>(), before = new HashMap<>();
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(volleys(level, a, x) != null, "a battle staged at the volleys");
            for (Armies.Fighter f : campaign(a).army(0).fighters()) {
                if (f.role() != BattleModel.Role.ARCHER || !(level.getEntity(f.id()) instanceof VillageFolkEntity e)) continue;
                e.removeMatching(s -> s.is(Items.ARROW), 999);
                e.insertGiven(new ItemStack(Items.ARROW, 2));
                // A crossbow spanned already holds its bolt, in the hand or put by in the pack with a blade drawn instead.
                int spanned = CrossbowItem.isCharged(e.getMainHandItem()) ? 1 : 0;
                for (ItemStack s : e.getInventoryItems()) if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) spanned++;
                given.put(f.id(), 2 + spanned);
                before.put(f.id(), f.loosed());                       // what it loosed at the volleys before its quiver was emptied
            }
        });
        helper.runAtTickTime(620, () -> {
            Campaigns.Campaign c = campaign(a);
            int out = 0, shots = 0, most = 0;
            for (Armies.Fighter f : c.army(0).fighters()) {
                Integer had = given.get(f.id());
                if (had == null || !(level.getEntity(f.id()) instanceof VillageFolkEntity e)) continue;
                int since = f.loosed() - before.getOrDefault(f.id(), 0);
                shots += since;
                most += had;
                helper.assertTrue(since <= had, e.displayNameCap() + " loosed no more than it had: " + since + " of " + had);
                if (f.role() == BattleModel.Role.RESERVE) {
                    out++;
                    helper.assertTrue(e.getMainHandItem().getItem() instanceof SwordItem || e.getMainHandItem().getItem() instanceof AxeItem,
                        e.displayNameCap() + " has drawn its blade: " + e.getMainHandItem());
                }
            }
            Kit.log("wb04 " + given.size() + " archers with " + most + " arrows loosed " + shots + "; " + out + " out of arrows, blades drawn, in the reserve");
            helper.assertTrue(!given.isEmpty(), "archers to run dry");
            helper.assertTrue(out == given.size(), "every archer out of arrows has joined the reserve: " + out + " of " + given.size());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wb05

    /**
     * Arrows gathered: after the volleys, the rout and the aftermath, the side that holds the field gathers a third of
     * the arrows loosed on it (both sides') into its supply, to go home to its stores; never more than were loosed.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wb05_recover")
    public static void wb05_recover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1976000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        AtomicReference<BlockPos> field = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(volleys(level, a, x) != null, "a battle staged at the volleys");
            field.set(Kit.surface(level, x + APART / 2, Z));
        });
        helper.runAtTickTime(420, () -> {
            Campaigns.stage(level, a, field.get(), "rout");
            Campaigns.stage(level, a, field.get(), "aftermath");
            Campaigns.Campaign c = campaign(a);
            Battles.Battle b = c.battle();
            int spent = b.spent(0) + b.spent(1), before = c.army(b.winner()).carried(Items.ARROW);
            b.gatherForTests();
            b.gatherForTests();                                        // once only
            int after = c.army(b.winner()).carried(Items.ARROW);
            Kit.log("wb05 " + spent + " arrows loosed on the field; " + b.gathered() + " gathered by side " + b.winner() + " (" + before + " -> " + after
                + " in its supply); " + b.story().get(b.story().size() - 1));
            helper.assertTrue(spent > 0, "arrows loosed in the volleys");
            helper.assertTrue(b.gathered() == BattleModel.recovered(spent) && b.gathered() > 0 && b.gathered() <= spent,
                "a third of the spent arrows gathered: " + b.gathered() + " of " + spent);
            helper.assertTrue(after - before == b.gathered(), "into the victor's supply, once: " + (after - before));
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ wb06

    /**
     * The counter-march: the crossbow company looses a rank at a time, the ranks changing places between volleys: no
     * crossbowman in two volleys running, and the first rank back again two volleys on.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wb06_counter_march")
    public static void wb06_counter_march(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1978000;
        Villages.Village a = town(level, x, 8);
        helper.assertTrue(a != null, "a town");
        helper.runAtTickTime(20, () -> helper.assertTrue(volleys(level, a, x) != null, "a battle staged at the volleys"));
        helper.runAtTickTime(560, () -> {
            Battles.Battle b = campaign(a).battle();
            int checked = 0;
            for (int i = 0; i < 2; i++) {
                List<List<UUID>> log = b.bolted(i);
                Kit.log("wb06 side " + i + ": " + log.size() + " crossbow volleys, by " + sizes(log));
                for (int k = 1; k < log.size(); k++) {
                    Set<UUID> both = new HashSet<>(log.get(k));
                    both.retainAll(log.get(k - 1));
                    helper.assertTrue(both.isEmpty(), "side " + i + ": volley " + k + " by the other rank, not the one that just loosed");
                    if (k >= 2) checked++;
                }
                // The same rank every other volley: a crossbowman who held his bolt (no mark in sight) may miss his
                // rank's turn, but never looses in the other rank's.
                Set<UUID> first = new HashSet<>(), second = new HashSet<>();
                for (int k = 0; k < log.size(); k++) (k % 2 == 0 ? first : second).addAll(log.get(k));
                first.retainAll(second);
                helper.assertTrue(first.isEmpty(), "side " + i + ": the first rank back two volleys on, the second between: " + first);
            }
            helper.assertTrue(checked >= 2, "volleys enough to see the ranks change: " + checked);
            helper.succeed();
        });
    }

    private static String sizes(List<List<UUID>> log) {
        List<Integer> out = new ArrayList<>();
        for (List<UUID> l : log) out.add(l.size());
        return out.toString();
    }
}
