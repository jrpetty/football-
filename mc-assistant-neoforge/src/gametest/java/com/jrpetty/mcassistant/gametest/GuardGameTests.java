package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Craftsmanship;
import com.jrpetty.mcassistant.entity.Patrols;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The watch between the bells, and the makers' hands. A guard runs to a monster that comes
 * near one of the village's own and fights it; the watch's beats are different parts of the
 * town; the leader is walked about with a guard at its shoulder; and a smith of thirty years
 * makes better tools than a beginner, and turns down what is beyond a beginner.
 *
 * <p>As with the village tests, each has its own batch (they share every static in the mod)
 * and its own ground, far from the others: x 100000 to 106000, z 12000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class GuardGameTests {

    private static final String EMPTY = "empty";

    /** A guard of the village, with a sword in its hand. */
    private static void arm(VillageFolkEntity g) {
        g.setJob(StationTask.GUARD);
        g.insertItem(new ItemStack(Items.IRON_SWORD));
        g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
    }

    /** Up to this level at the trade it works now. */
    private static void train(VillageFolkEntity f, int level) {
        for (int i = 0; i < 800 && f.veteranLevel() < level; i++) f.awardXp(250);
    }

    /** A zombie that stays where it is put and does not burn in the sun (a pumpkin on its head). */
    private static Zombie zombieAt(ServerLevel level, double x, double z) {
        Zombie zb = EntityType.ZOMBIE.create(level);
        zb.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        zb.setPersistenceRequired();
        zb.setNoAi(true);
        BlockPos at = Kit.surface(level, (int) Math.floor(x), (int) Math.floor(z));
        zb.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(zb);
        return zb;
    }

    private static double flat(VillageFolkEntity a, VillageFolkEntity b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Stand a folk on the ground here, still. */
    private static void standAt(ServerLevel level, VillageFolkEntity f, int x, int z) {
        BlockPos at = Kit.surface(level, x, z);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, f.getYRot(), 0.0F);
        f.getNavigation().stop();
    }

    // ============================================================ keeping them safe

    /**
     * A monster comes up beside one of the village's folk, out of the guard's own sight (eighteen
     * blocks off): the watch's look round sends the guard, which runs there and strikes it. Then
     * a folk a monster hurts shouts for the watch, and the guard answers.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "g01_guard_answers")
    public static void g01_guard_answers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 100000, z = 12000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity citizen = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity guard = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 6, z), 0.0F);
        helper.assertTrue(citizen != null && guard != null, "a village of two");
        UUID village = citizen.ownerId();
        helper.assertTrue(village != null && village.equals(guard.ownerId()), "both of the one village");
        arm(guard);
        citizen.setNoAi(true);                       // it stays where it is, to be got at
        Zombie[] zombie = new Zombie[2];
        long[] sent = { -1 }, struck = { -1 };
        helper.runAtTickTime(40, () -> {
            // The guard well beyond its own eyes' reach (it takes on what comes within eight blocks
            // by itself); the monster at the citizen's elbow.
            standAt(level, guard, (int) Math.floor(citizen.getX()) - 18, (int) Math.floor(citizen.getZ()));
            guard.setTarget(null);
            zombie[0] = zombieAt(level, citizen.getX() + 3, citizen.getZ());
            Kit.log("g01 the zombie at " + zombie[0].blockPosition().toShortString() + ", the citizen "
                + citizen.displayNameCap() + " at " + citizen.blockPosition().toShortString() + ", the guard "
                + guard.displayNameCap() + " " + String.format("%.1f", guard.distanceTo(zombie[0])) + " blocks off");
            Patrols.tick(level, Villages.get(village));
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Zombie zb = zombie[0];
            if (zb == null) return;
            if (sent[0] < 0 && guard.getUUID().equals(Patrols.answeredByForTests(zb.getUUID()))) {
                sent[0] = t;
                Kit.log("g01 the guard was sent at tick " + t + ", target "
                    + (guard.getTarget() == null ? "none" : guard.getTarget().getType().toShortString()));
            }
            if (t % 100 == 0) {
                Kit.log("g01 tick " + t + ": guard at " + guard.blockPosition().toShortString() + ", "
                    + String.format("%.1f", guard.distanceTo(zb)) + " from the zombie (health " + zb.getHealth() + "), target "
                    + (guard.getTarget() == null ? "none" : guard.getTarget().getType().toShortString()) + "; " + guard.debugLine());
            }
            if (struck[0] < 0 && sent[0] >= 0 && (zb.getLastHurtByMob() == guard || !zb.isAlive())) {
                struck[0] = t;
                Kit.log("g01 the guard struck the zombie at tick " + t + " (health " + zb.getHealth() + ")");
                zb.discard();
                // A folk hurt by a monster (one twenty blocks off, beyond the look round's sixteen)
                // shouts for the watch, and the guard answers it.
                guard.setTarget(null);
                standAt(level, guard, (int) Math.floor(citizen.getX()) - 12, (int) Math.floor(citizen.getZ()));
                Zombie far = zombieAt(level, citizen.getX() + 20, citizen.getZ() + 4);
                zombie[1] = far;
                citizen.hurt(level.damageSources().mobAttack(far), 1.0F);
                UUID by = Patrols.answeredByForTests(far.getUUID());
                Kit.log("g01 the citizen cried out: answered by " + (by == null ? "nobody" : by.equals(guard.getUUID()) ? "the guard" : by)
                    + ", the guard's target " + (guard.getTarget() == null ? "none" : guard.getTarget().getType().toShortString()));
                helper.assertTrue(guard.getUUID().equals(by) && guard.getTarget() == far,
                    "a folk set on by a monster shouts for the watch, and the guard goes for it");
                far.discard();
                helper.succeed();
                return;
            }
            if (t >= 1100) {
                helper.fail("the guard never got to the zombie: sent " + (sent[0] >= 0) + ", " + guard.debugLine()
                    + ", " + String.format("%.1f", guard.distanceTo(zb)) + " blocks off");
            }
        });
    }

    // ============================================================ the beats

    /**
     * Three guards walk three beats: a slice of the town each, no stop on two beats, the four
     * quarters of the town all walked between them, and the street at a building's door among
     * the stops. Each guard sets off for a stop of its own beat.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "g02_beats")
    public static void g02_beats(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 101500, z = 12000;
        Kit.hold(level, x, z, 72);
        Kit.prepare(level, x, z, 72);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity citizen = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        List<VillageFolkEntity> guards = new ArrayList<>();
        for (int i = 0; i < 3; i++) guards.add(VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2 + i, z + 2), 0.0F));
        helper.assertTrue(citizen != null && guards.stream().allMatch(g -> g != null), "a village with three guards");
        UUID village = citizen.ownerId();
        List<List<BlockPos>> walked = new ArrayList<>();      // the beats, once drawn at tick 20
        helper.runAtTickTime(20, () -> {
            for (VillageFolkEntity g : guards) arm(g);
            Villages.Village v = Villages.get(village);
            BlockPos c = v.centre();
            // Two houses on the plan's lots: the street at each door is a stop.
            BlockPos houseA = Kit.surface(level, c.getX() + 22, c.getZ() + 22);
            BlockPos houseB = Kit.surface(level, c.getX() - 30, c.getZ() - 8);
            com.jrpetty.mcassistant.village.Ledger.built(village, "house", houseA, Direction.NORTH);
            com.jrpetty.mcassistant.village.Ledger.built(village, "house", houseB, Direction.EAST);
            List<List<BlockPos>> beats = Patrols.beatsForTests(level, village);
            StringBuilder sb = new StringBuilder();
            for (List<BlockPos> b : beats) {
                sb.append(" [");
                for (BlockPos p : b) sb.append(' ').append(p.getX() - c.getX()).append(',').append(p.getZ() - c.getZ());
                sb.append(" ]");
            }
            Kit.log("g02 " + beats.size() + " beats:" + sb);
            helper.assertTrue(beats.size() == 3, "three guards, three beats: " + beats.size());
            Set<Long> seen = new HashSet<>();
            boolean[] quarter = new boolean[4];
            double[][] middle = new double[3][2];
            for (int i = 0; i < beats.size(); i++) {
                List<BlockPos> b = beats.get(i);
                helper.assertTrue(!b.isEmpty(), "every beat has stops on it");
                for (BlockPos p : b) {
                    helper.assertTrue(seen.add(p.asLong()), "no stop is on two beats: " + p.toShortString());
                    int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
                    if (dx > 0 && dz > 0) quarter[0] = true;
                    if (dx < 0 && dz > 0) quarter[1] = true;
                    if (dx < 0 && dz < 0) quarter[2] = true;
                    if (dx > 0 && dz < 0) quarter[3] = true;
                    middle[i][0] += dx / (double) b.size();
                    middle[i][1] += dz / (double) b.size();
                }
            }
            helper.assertTrue(quarter[0] && quarter[1] && quarter[2] && quarter[3], "the beats between them cover the town's four quarters");
            for (int i = 0; i < 3; i++) {
                for (int j = i + 1; j < 3; j++) {
                    double d = Math.hypot(middle[i][0] - middle[j][0], middle[i][1] - middle[j][1]);
                    helper.assertTrue(d >= 12.0, "two beats walk different parts of the town, not one: " + String.format("%.1f", d) + " apart");
                }
            }
            for (BlockPos house : List.of(houseA, houseB)) {
                boolean byDoor = seen.stream().map(BlockPos::of).anyMatch(p -> Math.abs(p.getX() - house.getX()) <= 10 && Math.abs(p.getZ() - house.getZ()) <= 10);
                helper.assertTrue(byDoor, "the street by a house at " + house.toShortString() + " is on a beat");
            }
            walked.addAll(beats);
        });
        // Each guard on its own beat, and off to a stop on it. A guard that can't set off this tick
        // (in the air from a hop, or held a moment) stands and looks about, and tries again after
        // Patrols.LOOK, so each has a few looks' time to be on its way.
        Set<UUID> off = new HashSet<>();
        Set<Integer> beatsWalked = new HashSet<>();
        helper.onEachTick(() -> {
            if (walked.isEmpty() || off.size() == guards.size()) return;
            BlockPos c = Villages.get(village).centre();
            for (VillageFolkEntity g : guards) {
                if (off.contains(g.getUUID())) continue;
                List<BlockPos> mine = Patrols.beatForTests(g);
                boolean going = Patrols.round(g, level);
                BlockPos to = Patrols.headingForTests(g);
                if (!going || to == null || !mine.contains(to)) {
                    if (helper.getTick() % 40 == 0) Kit.log("g02 @" + helper.getTick() + " " + g.displayNameCap() + " not off yet (going " + going
                        + ", on the ground " + g.onGround() + "): " + g.debugLine());
                    continue;
                }
                off.add(g.getUUID());
                beatsWalked.add(walked.indexOf(mine));
                Kit.log("g02 @" + helper.getTick() + " " + g.displayNameCap() + " walks beat " + walked.indexOf(mine) + ", off to "
                    + (to.getX() - c.getX()) + "," + (to.getZ() - c.getZ()) + "; says: " + Patrols.line(g));
            }
            if (off.size() < guards.size()) return;
            helper.assertTrue(beatsWalked.size() == 3, "three guards on three different beats: " + beatsWalked);
            helper.succeed();
        });
    }

    // ============================================================ the leader's escort

    /**
     * The leader has an escort: the better of its two guards. It walks with the leader wherever
     * it goes in the town, two to four blocks off; goes for a monster that comes near it; and
     * stands down when the leader is back at its own work.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "g03_escort")
    public static void g03_escort(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 103000, z = 12000;
        Kit.hold(level, x, z, 64);
        Kit.prepare(level, x, z, 64);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity elder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity veteran = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 3, z), 0.0F);
        VillageFolkEntity rookie = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3, z), 0.0F);
        helper.assertTrue(elder != null && veteran != null && rookie != null, "a village of three");
        UUID village = elder.ownerId();
        // phase: 0 setting up, 1 following, 2 following again, 3 a monster near the leader, 4 at work, 5 done
        int[] phase = { 0 };
        long[] since = { 0 };
        Zombie[] zombie = new Zombie[1];
        int[][] spots = { { 14, 8 }, { -12, -14 } };
        helper.runAtTickTime(5, () -> {
            arm(veteran);
            arm(rookie);
            train(veteran, 12);
            // A barracks, so that the escort is kept whatever becomes of the second guard.
            Villages.builtAtForTests(village, "barracks", Kit.surface(level, x + 30, z + 30));
            elder.setNoAi(true);                      // moved about by the test, a stop at a time
            elder.ensurePersona();
            Villages.electElder(village, elder, level.getDayTime() / 24000L);
            UUID chosen = Patrols.chooseEscortForTests(level, village);
            Kit.log("g03 the leader " + elder.displayNameCap() + "; guards " + veteran.displayNameCap() + " (level " + veteran.veteranLevel()
                + ") and " + rookie.displayNameCap() + " (level " + rookie.veteranLevel() + "); escort "
                + (chosen == null ? "none" : chosen.equals(veteran.getUUID()) ? veteran.displayNameCap() : "the other") + "; status: "
                + Patrols.escortLine(village));
            helper.assertTrue(veteran.getUUID().equals(chosen), "the better guard is the leader's escort");
            helper.assertTrue(Patrols.escortLine(village).equals("escorted by " + veteran.displayNameCap()),
                "the status can say who escorts the leader: " + Patrols.escortLine(village));
            phase[0] = 1;
            since[0] = helper.getTick();
            placeElder(level, elder, heart, spots[0]);
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (phase[0] == 0 || phase[0] == 5) return;
            double d = flat(veteran, elder);
            if (t % 50 == 0) {
                Kit.log("g03 tick " + t + " phase " + phase[0] + ": escort " + String.format("%.1f", d) + " from the leader, escorting "
                    + Patrols.escorting(veteran) + ", the leader about " + Patrols.leaderAboutForTests(village) + "; " + veteran.debugLine());
            }
            if (phase[0] == 1 || phase[0] == 2) {
                // Out and about, and not on its own plot: should its plot have moved under it, a step on.
                if (!Patrols.leaderAboutForTests(village) && t % 40 == 0) placeElder(level, elder, heart, spots[phase[0] - 1]);
                if (Patrols.escorting(veteran) && d <= 4.5 && !Patrols.escorting(rookie)) {
                    Kit.log("g03 the escort is at the leader's shoulder (" + String.format("%.1f", d) + " blocks) at tick " + t);
                    if (phase[0] == 1) {
                        phase[0] = 2;
                        placeElder(level, elder, heart, spots[1]);
                    } else {
                        phase[0] = 3;
                        zombie[0] = zombieAt(level, elder.getX() + 4, elder.getZ());
                    }
                    since[0] = t;
                }
            } else if (phase[0] == 3) {
                Zombie zb = zombie[0];
                if (veteran.getTarget() == zb || zb.getLastHurtByMob() == veteran) {
                    Kit.log("g03 the escort went for the monster by the leader at tick " + t);
                    zb.discard();
                    veteran.setTarget(null);
                    // Back at its own work: the escort goes back to its beat.
                    WorkZone home = elder.workZone();
                    if (home == null) {
                        elder.setJob(StationTask.STORE);
                        home = elder.workZone();
                    }
                    BlockPos c = home.center();
                    standAt(level, elder, c.getX(), c.getZ());
                    phase[0] = 4;
                    since[0] = t;
                }
            } else if (phase[0] == 4) {
                if (!Patrols.leaderAboutForTests(village) && !Patrols.escorting(veteran)) {
                    Kit.log("g03 the leader at its work, and the escort stood down at tick " + t);
                    phase[0] = 5;
                    helper.succeed();
                    return;
                }
            }
            if (t - since[0] > 500) {
                helper.fail("phase " + phase[0] + " never came good: escort " + String.format("%.1f", d) + " from the leader, escorting "
                    + Patrols.escorting(veteran) + ", the leader about " + Patrols.leaderAboutForTests(village) + "; " + veteran.debugLine());
            }
        });
    }

    /** The leader set down out and about in the town, off its own plot. */
    private static void placeElder(ServerLevel level, VillageFolkEntity elder, BlockPos heart, int[] at) {
        WorkZone own = elder.workZone();
        for (int k = 0; k < 6; k++) {
            int px = heart.getX() + at[0] + k * 3, pz = heart.getZ() + at[1];
            if (own != null && own.containsColumn(new BlockPos(px, heart.getY(), pz))) continue;
            standAt(level, elder, px, pz);
            return;
        }
        standAt(level, elder, heart.getX() + at[0], heart.getZ() + at[1]);
    }

    // ============================================================ the makers' hands

    /**
     * A beginner at the anvil and a smith of thirty years each forge an iron pickaxe out of the
     * village's stores: the beginner's wears through sooner, the veteran's lasts a third longer
     * and is worth more; a master's comes off the anvil tempered. The beginner can't make armour
     * yet, and the veteran can work diamond. Each says so of its trade.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "g04_smith_quality")
    public static void g04_smith_quality(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 104500, z = 12000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity novice = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity veteran = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        VillageFolkEntity master = VillageFolkSpawnerBlock.raise(level, heart.west(2), 0.0F);
        helper.assertTrue(novice != null && veteran != null && master != null, "a village of three");
        UUID village = novice.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            BlockPos chest = Kit.surface(level, heart.getX() + 4, heart.getZ());
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
            net.minecraft.world.Container box = (net.minecraft.world.Container) level.getBlockEntity(chest);
            box.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            box.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
            novice.setJob(StationTask.SMITH);
            veteran.setJob(StationTask.SMITH);
            master.setJob(StationTask.SMITH);
            train(veteran, 30);
            train(master, 40);
            ItemStack[] picks = new ItemStack[3];
            VillageFolkEntity[] smiths = { novice, veteran, master };
            for (int i = 0; i < 3; i++) {
                boolean made = com.jrpetty.mcassistant.entity.Crafts.now(smiths[i], level, v);
                picks[i] = takeOut(level, village, Items.IRON_PICKAXE);
                Kit.log("g04 " + smiths[i].displayNameCap() + " (level " + smiths[i].veteranLevel() + "): made " + made + ", a pickaxe "
                    + (picks[i].isEmpty() ? "none" : picks[i].getMaxDamage() + " uses, " + Craftsmanship.gradeOf(picks[i])
                        + (picks[i].isEnchanted() ? ", enchanted" : "") + ", worth " + String.format("%.2f", com.jrpetty.mcassistant.entity.Prices.of(picks[i]))));
                helper.assertTrue(made && !picks[i].isEmpty(), "each smith forges an iron pickaxe out of the stores");
            }
            int usual = new ItemStack(Items.IRON_PICKAXE).getMaxDamage();
            helper.assertTrue(picks[0].getMaxDamage() < usual && Craftsmanship.gradeOf(picks[0]) == Craftsmanship.Grade.ROUGH,
                "a beginner's pick wears through sooner than most");
            helper.assertTrue(picks[1].getMaxDamage() > usual && picks[1].getMaxDamage() > picks[0].getMaxDamage()
                    && Craftsmanship.gradeOf(picks[1]) == Craftsmanship.Grade.FINE,
                "a smith of thirty years makes a pick that lasts longer: " + picks[1].getMaxDamage() + " against " + picks[0].getMaxDamage());
            helper.assertTrue(com.jrpetty.mcassistant.entity.Prices.of(picks[1]) > com.jrpetty.mcassistant.entity.Prices.of(picks[0]),
                "and the better pick is worth more");
            helper.assertTrue(picks[2].isEnchanted() && picks[2].getMaxDamage() > picks[1].getMaxDamage(),
                "a master's pick comes off the anvil tempered");
            helper.assertTrue(!Craftsmanship.canMake(novice.veteranLevel(), Items.IRON_CHESTPLATE)
                    && Craftsmanship.canMake(veteran.veteranLevel(), Items.DIAMOND_PICKAXE),
                "a beginner can't make armour yet; a smith of thirty years can work diamond");
            String says = com.jrpetty.mcassistant.entity.Trades.explain(veteran);
            String green = Craftsmanship.line(novice);
            Kit.log("g04 the veteran: " + says + " / the beginner: " + green);
            helper.assertTrue(says.contains("a third longer") && says.contains("diamond"), "the smith says what its hand can do");
            helper.assertTrue(green.contains("still learning"), "and so does the beginner");
            helper.succeed();
        });
    }

    /** The first one of these out of the village's stores, taken out (empty if there is none). */
    private static ItemStack takeOut(ServerLevel level, UUID village, net.minecraft.world.item.Item item) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.is(item)) {
                    c.setItem(i, ItemStack.EMPTY);
                    c.setChanged();
                    return s;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Made to order: a beginner at the anvil turns down a diamond pickaxe ("beyond me yet"); a
     * smith of thirty years takes the order, and the pick it hands over the next day is its own
     * fine work.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "g05_order_by_skill")
    public static void g05_order_by_skill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 106000, z = 12000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity novice = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity veteran = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(novice != null && veteran != null, "a village of two");
        helper.runAtTickTime(10, () -> {
            novice.setJob(StationTask.SMITH);
            veteran.setJob(StationTask.SMITH);
            train(veteran, 30);
            net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            p.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 1.5);
            p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            p.getInventory().setItem(31, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            p.getInventory().setItem(32, new ItemStack(Items.DIAMOND, 3));
            p.getInventory().setItem(33, new ItemStack(Items.STICK, 2));
            String no = com.jrpetty.mcassistant.entity.Dealings.order(novice, p, "make me a diamond pickaxe");
            String yes = com.jrpetty.mcassistant.entity.Dealings.order(veteran, p, "make me a diamond pickaxe");
            level.setDayTime(level.getDayTime() + 24000L);
            String ready = com.jrpetty.mcassistant.entity.Dealings.order(veteran, p, "is it ready");
            level.setDayTime(level.getDayTime() - 24000L);
            ItemStack pick = ItemStack.EMPTY;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (p.getInventory().getItem(i).is(Items.DIAMOND_PICKAXE)) pick = p.getInventory().getItem(i);
            }
            Kit.log("g05 the beginner: " + no + " / the veteran (level " + veteran.veteranLevel() + "): " + yes + " / " + ready
                + " / the pick: " + (pick.isEmpty() ? "none" : pick.getMaxDamage() + " uses, " + Craftsmanship.gradeOf(pick)));
            helper.assertTrue(no.contains("beyond me yet"), "a beginner turns down what is beyond its hand");
            helper.assertTrue(yes.startsWith("Right you are"), "a smith of thirty years takes the order");
            helper.assertTrue(!pick.isEmpty() && Craftsmanship.gradeOf(pick) == Craftsmanship.Grade.FINE
                    && pick.getMaxDamage() > new ItemStack(Items.DIAMOND_PICKAXE).getMaxDamage(),
                "and what it hands over is its own fine work");
            helper.succeed();
        });
    }
}
