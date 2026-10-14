package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Armies;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Battles;
import com.jrpetty.mcassistant.entity.Campaigns;
import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.Caravans;
import com.jrpetty.mcassistant.entity.Envoys;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Spies;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [war] The armies in the field (entity/Campaigns, Armies, Battles): a muster draws up in proper lines (roles,
 * Captain, banner, sergeants, facing the enemy) and its campaign is kept with the world; a battle watched is
 * fought in full, the parley first, and ends in a break with wounds before deaths and nobody outside it touched;
 * a battle nobody watches is reckoned on paper, the stronger winning, written in the books, and the loser sues
 * for peace; a siege takes a gate with a ram of real logs out of the stores, and the town makes peace; and two
 * great towns go from a quarrel to war, to battle, to a treaty.
 *
 * <p>Each on its own ground in x 1820000-1839999, z 66000, two towns 260 blocks apart; the waits cut short
 * (Campaigns.hurryForTests). The day is set just after the day's dealings, so nothing else throws its dice.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BattleGameTests {

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

    /** The ground between the two towns held and made, for the field and the march. */
    private static void between(ServerLevel level, int x) {
        for (int dx = 64; dx <= APART - 64; dx += 64) {
            Kit.hold(level, x + dx, Z, 48);
            Kit.prepare(level, x + dx, Z, 48);
        }
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

    /** So many of the town's grown folk (never the elder) set to the watch and armed: a blade, a shield, iron; every third a bow. */
    private static List<VillageFolkEntity> guards(UUID v, int n, boolean iron) {
        List<VillageFolkEntity> out = new ArrayList<>();
        UUID e = Villages.elder(v);
        int k = 0;
        for (VillageFolkEntity f : folk(v)) {
            if (out.size() >= n) break;
            if (f.getUUID().equals(e) || f.isBaby()) continue;
            f.setJob(AssistantEntity.StationTask.GUARD);
            f.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(iron ? Items.IRON_SWORD : Items.STONE_SWORD));
            if (iron) f.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            if (k++ % 3 == 2) {
                f.insertGiven(new ItemStack(Items.BOW));
                f.insertGiven(new ItemStack(Items.ARROW, 24));
            } else f.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            out.add(f);
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
        Campaigns.resetForTests();
        Campaigns.hurryForTests(true);
        level.setDayTime(40L * 24000L + 1200L);                 // morning: a battle by day
    }

    private static void war(ServerLevel level, Villages.Village a, Villages.Village b) {
        long day = level.getDayTime() / 24000L;
        Ledger.relate(a.id(), b.id(), -70 - Ledger.relation(a.id(), b.id()));
        WarAndPeace.declare(level, a.id(), b.id(), day, WarAndPeace.Goal.TRIBUTE, 20, "twenty coins in tribute");
        WarAndPeace.backdateForTests(a.id(), b.id(), 3);
    }

    /** Where this fighter stands across its army's front (its offset along the line's own right). */
    private static double across(double[] p, double ox, double oz, double fx, double fz) {
        return (p[0] - ox) * -fz + (p[2] - oz) * fx;
    }

    private static double depth(double[] p, double ox, double oz, double fx, double fz) {
        return (p[0] - ox) * fx + (p[2] - oz) * fz;
    }

    // ------------------------------------------------------------------ bt01

    /**
     * The muster: the army raised out of the watch draws up on the square in proper lines facing the enemy: the
     * shield line in one straight rank a block and a half apart, the archers behind it, the Captain with the
     * banner behind them, a sergeant for every six; its fighters walk to their places and face the enemy; and the
     * campaign reads back from the books as it was.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "bt01_muster")
    public static void bt01_muster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        Campaigns.hurryForTests(false);                          // the muster stands drawn up its full time, to be looked at
        int x = 1820000;
        Villages.Village a = town(level, x, 12), b = town(level, x + APART, 8);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            guards(a.id(), 8, true);
            guards(b.id(), 4, false);
            stock(level, a.id(), new ItemStack(Items.WHITE_BANNER), new ItemStack(Items.RED_BANNER), new ItemStack(Items.BLUE_BANNER),   // one goes over the gate for the war
                new ItemStack(Items.LEAD, 4), new ItemStack(Items.WHITE_WOOL, 27),
                new ItemStack(Items.OAK_FENCE, 12), new ItemStack(Items.CAMPFIRE));
            war(level, a, b);
            Campaigns.watchForTests(true);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, false, false);
            helper.assertTrue(c != null, "a campaign begun");
            Campaigns.nextForTests(level, c);
            camp.set(c);
            Armies.Army army = c.army(0);
            helper.assertTrue(c.stage() == Campaigns.Stage.MUSTER && army != null, "the army mustered: " + c.stage());
            Map<BattleModel.Role, Integer> roles = Armies.rolesForTests(army);
            Armies.Fighter cap = army.officer(Armies.Rank.CAPTAIN), bearer = army.officer(Armies.Rank.BEARER), sgt = army.officer(Armies.Rank.SERGEANT);
            Kit.log("bt01 the army of " + Villages.name(a.id()) + ": " + roles + ", Captain " + (cap == null ? "none" : cap.name()) + ", bearer "
                + (bearer == null ? "none" : bearer.name()) + ", tactic " + army.tactic() + ", spirit " + army.morale());
            helper.assertTrue(cap != null, "a Captain");
            helper.assertTrue(bearer != null, "a standard-bearer with the town's banner");
            helper.assertTrue(sgt != null, "a sergeant");
            helper.assertTrue(roles.getOrDefault(BattleModel.Role.LINE, 0) >= 2 && roles.getOrDefault(BattleModel.Role.ARCHER, 0) >= 1,
                "a shield line and archers: " + roles);
            helper.assertTrue(roles.getOrDefault(BattleModel.Role.MEDIC, 0) <= 1, "one medic at most");
        });
        helper.runAtTickTime(400, () -> {
            Campaigns.Campaign c = camp.get();
            Armies.Army army = c.army(0);
            Villages.Village enemy = b;
            double dx = enemy.centre().getX() - a.centre().getX(), dz = enemy.centre().getZ() - a.centre().getZ(), d = Math.sqrt(dx * dx + dz * dz);
            double fx = dx / d, fz = dz / d, ox = a.centre().getX() + 0.5 + fx * 4, oz = a.centre().getZ() + 0.5 + fz * 4;
            List<Double> lineAcross = new ArrayList<>();
            double capDepth = 0, archerDepth = 0;
            int inPlace = 0, seen = 0, facing = 0;
            for (Armies.Fighter f : army.fighters()) {
                double[] p = Armies.placeForTests(f);
                if (f.role() == BattleModel.Role.LINE && f.rank() != Armies.Rank.CAPTAIN && f.rank() != Armies.Rank.BEARER) {
                    helper.assertTrue(Math.abs(depth(p, ox, oz, fx, fz)) < 0.3, "the line in one straight rank: " + depth(p, ox, oz, fx, fz));
                    lineAcross.add(across(p, ox, oz, fx, fz));
                }
                if (f.rank() == Armies.Rank.CAPTAIN) capDepth = depth(p, ox, oz, fx, fz);
                if (f.role() == BattleModel.Role.ARCHER) archerDepth = depth(p, ox, oz, fx, fz);
                if (level.getEntity(f.id()) instanceof VillageFolkEntity e) {
                    seen++;
                    double ex = e.getX() - p[0], ez = e.getZ() - p[2];
                    if (ex * ex + ez * ez < 4.0) {
                        inPlace++;
                        float diff = Math.abs(((e.getYRot() - (float) p[3]) % 360 + 540) % 360 - 180);
                        if (diff < 30) facing++;
                    }
                }
            }
            lineAcross.sort(Double::compare);
            for (int i = 1; i < lineAcross.size(); i++) {
                helper.assertTrue(Math.abs(lineAcross.get(i) - lineAcross.get(i - 1) - 1.5) < 0.05, "even spacing in the line: " + lineAcross);
            }
            Kit.log("bt01 drawn up: " + inPlace + " of " + seen + " in place, " + facing + " facing the enemy; the Captain " + capDepth
                + " behind, the archers " + archerDepth);
            helper.assertTrue(capDepth < -8 && archerDepth < -2, "the archers behind the line and the Captain behind them");
            helper.assertTrue(inPlace * 4 >= seen * 3, "the fighters in their places: " + inPlace + " of " + seen);
            helper.assertTrue(facing * 4 >= inPlace * 3, "facing the enemy: " + facing + " of " + inPlace);
            Campaigns.Campaign back = Campaigns.reloadForTests(c);
            helper.assertTrue(back != null && back.stage() == c.stage() && back.army(0) != null
                && back.army(0).fighters().size() == army.fighters().size() && back.army(0).tactic() == army.tactic(),
                "the campaign reads back from the books as it was");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ bt02

    /**
     * A battle watched, fought in full, six against six: the Captains parley between the lines first; then the
     * fighting, until one side breaks (in good order, in a rout, or on its knees). Those beaten down yield
     * (deaths switched off: nobody dies), and a villager and a townsfolk standing by the field are not touched.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4200, batch = "bt02_in_full")
    public static void bt02_in_full(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1822000;
        Villages.Village a = town(level, x, 9), b = town(level, x + APART, 9);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        AtomicReference<Villager> stander = new AtomicReference<>();
        AtomicReference<VillageFolkEntity> looker = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            between(level, x);
            guards(a.id(), 6, true);
            guards(b.id(), 6, true);
            war(level, a, b);
            Battles.deathsForTests(0);
            Campaigns.watchForTests(true);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, false, false);
            Campaigns.nextForTests(level, c);                         // the muster
            Campaigns.nextForTests(level, c);                         // the march
            Campaigns.arriveForTests(c);
            camp.set(c);
        });
        helper.runAtTickTime(60, () -> {
            Campaigns.Campaign c = camp.get();
            Campaigns.nextForTests(level, c);                         // camped (arrived), and on to the battle
            if (c.stage() == Campaigns.Stage.CAMP) Campaigns.nextForTests(level, c);
            helper.assertTrue(c.battle() != null && c.battle().detailed(), "the battle fought in full: " + c.stage());
            BlockPos side = c.ground().at(0, 14);
            Villager v = EntityType.VILLAGER.create(level);
            v.moveTo(side.getX() + 0.5, Kit.surface(level, side.getX(), side.getZ()).getY(), side.getZ() + 0.5);
            v.setNoAi(true);
            level.addFreshEntity(v);
            stander.set(v);
            UUID elder = Villages.elder(a.id());
            for (VillageFolkEntity f : folk(a.id())) {
                if (f.getUUID().equals(elder) || Armies.inLine(f) || f.isBaby()) continue;
                BlockPos s2 = c.ground().at(0, -14);
                f.moveTo(s2.getX() + 0.5, Kit.surface(level, s2.getX(), s2.getZ()).getY(), s2.getZ() + 0.5);
                f.setNoAi(true);
                looker.set(f);
                break;
            }
        });
        helper.succeedWhen(() -> {
            Campaigns.Campaign c = camp.get();
            helper.assertTrue(c != null && c.battle() == null && c.stage().ordinal() >= Campaigns.Stage.AFTER.ordinal(), "the battle is over");
            List<String> course = new ArrayList<>();
            for (Chronicle.Entry e : Chronicle.of(a.id())) if (e.text().contains("Battle of")) course.add(e.text());
            String books = WarAndPeace.booksForTests(a.id(), b.id());
            Kit.log("bt02 " + c.name() + ": won " + c.won() + "; " + course + "; " + books);
            helper.assertTrue(c.won() >= 0, "a side won the field");
            helper.assertTrue(chronicled(a.id(), "Battle of") && chronicled(b.id(), "Battle of"), "both chronicles tell of the battle");
            helper.assertTrue(lastBattleLine(a.id(), b.id()).contains("Captains met"), "the parley came first: " + lastBattleLine(a.id(), b.id()));
            int dead = 0, down = 0;
            for (int i = 0; i < 2; i++) {
                Armies.Army army = c.army(i);
                if (army == null) continue;
                for (Armies.Fighter f : army.fighters()) {
                    if (!(level.getEntity(f.id()) instanceof VillageFolkEntity)) dead++;
                }
            }
            for (String l : WarAndPeace.battlesForTests(a.id(), b.id())) down += Integer.parseInt(l.split("\\|")[3]) + Integer.parseInt(l.split("\\|")[4]);
            Kit.log("bt02 beaten down " + down + ", missing " + dead + "; the villager " + stander.get().getHealth() + "/" + stander.get().getMaxHealth()
                + ", the onlooker " + (looker.get() == null ? "none" : looker.get().getHealth() + "/" + looker.get().getMaxHealth()));
            helper.assertTrue(down > 0, "fighters were beaten down");
            helper.assertTrue(dead == 0, "with deaths off, nobody died: " + dead + " missing");
            helper.assertTrue(stander.get().getHealth() >= stander.get().getMaxHealth(), "the villager by the field untouched");
            helper.assertTrue(looker.get() == null || looker.get().getHealth() >= looker.get().getMaxHealth(), "the townsfolk by the field untouched");
        });
    }

    private static String lastBattleLine(UUID a, UUID b) {
        return String.join(" / ", WarAndPeace.courseForTests(a, b));
    }

    // ------------------------------------------------------------------ bt03

    /**
     * A battle nobody watches is reckoned on paper by the same model: the much stronger town wins it, both towns'
     * books have it (the battle, its day, the score), the loser's weariness rises, and on the next day of the war
     * the loser, reckoning itself the weaker after it, sues for peace under a white flag.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "bt03_on_paper")
    public static void bt03_on_paper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1824000;
        Villages.Village a = town(level, x, 14), b = town(level, x + APART, 10);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            guards(a.id(), 9, true);
            guards(b.id(), 6, true);                                    // outmatched, not hopeless: no surrender at the parley
            war(level, a, b);
            Campaigns.watchForTests(false);
            Battles.parleyForTests(null);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, false, false);
            Campaigns.nextForTests(level, c);
            Campaigns.nextForTests(level, c);
            Campaigns.arriveForTests(c);
            Campaigns.nextForTests(level, c);
            if (c.stage() == Campaigns.Stage.CAMP) Campaigns.nextForTests(level, c);
            if (c.stage() == Campaigns.Stage.BATTLE) Campaigns.nextForTests(level, c);
            int w = c.won();
            Villages.Village winner = w == 0 ? a : b, loser = w == 0 ? b : a;
            Kit.log("bt03 " + c.name() + ": stage " + c.stage() + ", won " + w + "; " + WarAndPeace.battlesForTests(a.id(), b.id())
                + "; score " + Campaigns.score(a.id(), b.id()) + "; weariness " + WarAndPeace.weariness(loser.id()));
            helper.assertTrue(w >= 0, "a side held the field");
            helper.assertTrue(!WarAndPeace.battlesForTests(a.id(), b.id()).isEmpty() && !WarAndPeace.battlesForTests(b.id(), a.id()).isEmpty(),
                "the battle in both towns' books");
            helper.assertTrue(Campaigns.score(winner.id(), loser.id()) == 1 && Campaigns.score(loser.id(), winner.id()) == -1, "the war's score moved");
            helper.assertTrue(WarAndPeace.weariness(loser.id()) > 0, "the loser the wearier");
            // Home again, and the next day of the war: the beaten side sues for peace.
            for (int k = 0; k < 4 && c.stage() != Campaigns.Stage.DONE; k++) Campaigns.nextForTests(level, c);
            long day = level.getDayTime() / 24000L;
            WarAndPeace.backdateForTests(a.id(), b.id(), 9);
            WarAndPeace.dailyForTests(level, a, b, day + 1);
            boolean flag = false;
            for (VillageFolkEntity f : folk(loser.id())) {
                Caravans.Trip t = f.trip();
                if (t != null && t.errand() == Envoys.Errand.PEACE && t.destination().equals(winner.id())) flag = true;
            }
            Kit.log("bt03 after: the white flag " + flag + "; terms " + WarAndPeace.terms(level, a.id(), b.id()).words());
            helper.assertTrue(flag, "the beaten side sues for peace");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ bt04

    /**
     * A siege: the town holds its walls; the army marches on it, rings its bell, camps before the gate and builds
     * a ram of three logs out of its own town's stores; at the assault the ram breaks the gate's doors, the few
     * defenders are beaten before it, and the town makes peace on the besiegers' whole terms.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4200, batch = "bt04_siege")
    public static void bt04_siege(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1826000;
        Villages.Village a = town(level, x, 14), b = town(level, x + APART, 6);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        AtomicReference<Campaigns.Campaign> camp = new AtomicReference<>();
        AtomicReference<List<BlockPos>> doors = new AtomicReference<>();
        int[] logs = new int[2];
        helper.runAtTickTime(20, () -> {
            between(level, x);
            Kit.hold(level, x + APART, Z, 80);
            CastleWall.readyForTests(true);
            CastleWall.buildNow(level, b, true);
            List<BlockPos> d = new ArrayList<>();
            for (CastleWall.Gate g : CastleWall.gates(level, b.id())) if (g.stands()) d.addAll(g.doors());
            doors.set(d);
            guards(a.id(), 9, true);
            guards(b.id(), 2, false);
            stock(level, a.id(), new ItemStack(Items.OAK_LOG, 3), new ItemStack(Items.LEAD, 4));
            logs[0] = Market.stock(level, a.id(), s -> s.is(Items.OAK_LOG));
            war(level, a, b);
            Battles.deathsForTests(0);
            Campaigns.watchForTests(true);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, true, true);
            Campaigns.nextForTests(level, c);
            logs[1] = Market.stock(level, a.id(), s -> s.is(Items.OAK_LOG));
            Campaigns.nextForTests(level, c);
            Campaigns.arriveForTests(c);
            camp.set(c);
            Kit.log("bt04 the wall's gates standing: " + d.size() + " doors; logs in the stores " + logs[0] + " -> " + logs[1]);
        });
        helper.runAtTickTime(60, () -> {
            Campaigns.Campaign c = camp.get();
            Campaigns.nextForTests(level, c);                         // before the gate: the camp and the ram
            Kit.log("bt04 at the gate: " + c.stage() + ", the bell " + com.jrpetty.mcassistant.entity.Raids.underAlarm(b.id()));
            helper.assertTrue(c.stage() == Campaigns.Stage.SIEGE_CAMP || c.stage() == Campaigns.Stage.ASSAULT, "camped before the gate: " + c.stage());
            helper.assertTrue(com.jrpetty.mcassistant.entity.Raids.underAlarm(b.id()), "the bell rung in the besieged town");
            helper.assertTrue(logs[0] - logs[1] >= 3, "three logs out of the stores for the ram: " + logs[0] + " -> " + logs[1]);
            if (c.stage() == Campaigns.Stage.SIEGE_CAMP) Campaigns.nextForTests(level, c);   // the assault
        });
        helper.succeedWhen(() -> {
            Campaigns.Campaign c = camp.get();
            helper.assertTrue(c != null && doors.get() != null && c.stage().ordinal() >= Campaigns.Stage.ASSAULT.ordinal(), "the assault begun");
            helper.assertTrue(!Wars.atWar(a.id(), b.id()), "the town made peace");
            int left = 0;
            for (BlockPos p : doors.get()) if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) left++;
            Kit.log("bt04 taken: doors left " + left + " of " + doors.get().size() + "; " + WarAndPeace.booksForTests(a.id(), b.id())
                + "; captives " + Spies.captives(a.id()).size() + "; stage " + c.stage());
            helper.assertTrue(doors.get().isEmpty() || left < doors.get().size(), "the ram broke the gate's doors");
            helper.assertTrue(chronicled(b.id(), "took the town"), "the town's chronicle tells it was taken");
            helper.assertTrue(!com.jrpetty.mcassistant.entity.Raids.underAlarm(b.id()), "the bell stopped");
        });
    }

    // ------------------------------------------------------------------ bt05

    /**
     * The whole of it, on paper: two great towns in a strained neighbourhood quarrel, their councils of hawks go
     * to war, the stronger takes the field, the battle is fought and written up, and within a month the war ends
     * in a treaty in both towns' books.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bt05_soak")
    public static void bt05_soak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        level.setDayTime(40L * 24000L + 11200L);
        int x = 1828000;
        Villages.Village a = town(level, x, 14), b = town(level, x + APART, 10);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            Campaigns.watchForTests(false);
            WarAndPeace.greatForTests(a.id());
            WarAndPeace.greatForTests(b.id());
            for (Villages.Village t : new Villages.Village[]{ a, b }) {
                long day = level.getDayTime() / 24000L;
                Villages.chooseElder(t.id(), day);
                for (VillageFolkEntity f : folk(t.id())) {
                    f.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
                    Values.setForTests(f, Values.Value.SAFETY, 95);
                }
            }
            guards(a.id(), 8, true);
            guards(b.id(), 4, true);
            stock(level, a.id(), new ItemStack(Items.RED_BANNER), new ItemStack(Items.OAK_SIGN, 4), new ItemStack(Items.OAK_FENCE, 4));
            Ledger.relate(a.id(), b.id(), -40 - Ledger.relation(a.id(), b.id()));
            long day0 = level.getDayTime() / 24000L;
            WarAndPeace.wrongForTests(a.id(), b.id(), day0, WarAndPeace.Wrong.LAND, "a boundary stone between us was moved in the night");
            String path = "";
            boolean warred = false, fought = false;
            for (int k = 0; k < 40 && !(warred && !Wars.atWar(a.id(), b.id())); k++) {
                long day = day0 + k;
                WarAndPeace.dailyForTests(level, a, b, day);
                // Whatever is on the road gets there the same day (the herald, the white flag): the test does not wait.
                for (Villages.Village t : new Villages.Village[]{ a, b }) {
                    for (VillageFolkEntity f : folk(t.id())) {
                        Caravans.Trip trip = f.trip();
                        if (trip != null && trip.errand() != null && !trip.homeward()) {
                            Caravans.arriveForTests(level, f);
                            WarAndPeace.audienceForTests(level, f);
                        }
                        for (int leg = 0; leg < 2 && f.trip() != null && f.trip().homeward(); leg++) Caravans.arriveForTests(level, f);   // and home again
                    }
                }
                if (Wars.atWar(a.id(), b.id()) && !warred) { warred = true; path += " war@" + k; }
                Campaigns.Campaign c = Campaigns.of(a.id(), b.id());
                for (int s = 0; c != null && s < 20 && c.stage() != Campaigns.Stage.DONE; s++) {
                    if (c.stage() == Campaigns.Stage.MARCH || c.stage() == Campaigns.Stage.SIEGE_MARCH || c.stage() == Campaigns.Stage.HOME) Campaigns.arriveForTests(c);
                    Campaigns.nextForTests(level, c);
                    if (c.won() >= 0 && !fought) { fought = true; path += " battle@" + k + "(" + c.won() + ")"; }
                }
            }
            Kit.log("bt05 the chain:" + path + "; at war " + Wars.atWar(a.id(), b.id()) + "; " + WarAndPeace.booksForTests(a.id(), b.id())
                + "; battles " + WarAndPeace.battlesForTests(a.id(), b.id()));
            helper.assertTrue(warred, "the great towns went to war");
            helper.assertTrue(fought, "a battle was fought");
            helper.assertTrue(!Wars.atWar(a.id(), b.id()) && WarAndPeace.booksForTests(a.id(), b.id()).contains("treaties 1/1"), "a treaty ended it");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ bt06

    /**
     * The stand-off ends at the parley: a hopelessly outnumbered town yields, the victor takes its surrender, and peace
     * is made there and then with not a blow struck; the mercy is in both chronicles. Another time, the surrender
     * refused: the battle is fought.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "bt06_parley")
    public static void bt06_parley(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1830000 - APART - 400;
        Villages.Village a = town(level, x, 14), b = town(level, x + APART, 8);
        helper.assertTrue(a != null && b != null && !a.id().equals(b.id()), "two towns");
        helper.runAtTickTime(20, () -> {
            guards(a.id(), 10, true);
            guards(b.id(), 2, false);
            war(level, a, b);
            Campaigns.watchForTests(false);
            Battles.parleyForTests(true);
            Campaigns.Campaign c = Campaigns.beginForTests(level, a, b, false, false);
            Campaigns.nextForTests(level, c);
            Campaigns.nextForTests(level, c);
            Campaigns.arriveForTests(c);
            for (int k = 0; k < 3 && Wars.atWar(a.id(), b.id()); k++) Campaigns.nextForTests(level, c);
            Kit.log("bt06 taken: at war " + Wars.atWar(a.id(), b.id()) + "; battles " + WarAndPeace.battlesForTests(a.id(), b.id())
                + "; " + WarAndPeace.booksForTests(a.id(), b.id()));
            helper.assertTrue(!Wars.atWar(a.id(), b.id()), "peace at the parley");
            helper.assertTrue(WarAndPeace.battlesForTests(a.id(), b.id()).isEmpty(), "not a blow struck");
            helper.assertTrue(chronicled(a.id(), "took its surrender") && chronicled(b.id(), "we were spared"), "the mercy in both chronicles");
            helper.succeed();
        });
    }
}
