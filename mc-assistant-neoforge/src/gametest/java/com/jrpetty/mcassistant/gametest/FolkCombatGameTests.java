package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.FolkCombat;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [war] How a folk fights (entity/FolkCombat, goal/FolkFightGoal, BowAttackGoal): a shield raised in time takes an
 * arrow; an axe knocks a raised shield aside; an archer hits a target on the move and holds its shot with a friend in
 * the way; a crossbow is spanned and loosed; the blade waits on its cooldown and a practised fighter lands crits; a
 * veteran guard beats a zombie, a skeleton and a vindicator; and a veteran is never outfought by a recruit.
 *
 * <p>Each on its own ground in x 1830000-1833999, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FolkCombatGameTests {

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
        for (AssistantEntity a : Villages.folkOf(v)) if (a instanceof VillageFolkEntity f && !f.isBaby()) out.add(f);
        return out;
    }

    /** A guard, armed as asked, set down here; {@code battles} fought before (a veteran's steadiness). */
    private static VillageFolkEntity guard(ServerLevel level, VillageFolkEntity f, BlockPos at, ItemStack hand, boolean shield, int battles) {
        f.setJob(AssistantEntity.StationTask.GUARD);
        f.setItemSlot(EquipmentSlot.MAINHAND, hand);
        f.setItemSlot(EquipmentSlot.OFFHAND, shield ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
        f.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        f.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        f.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        f.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        if (battles > 0 && f.ownerId() != null) Ledger.note(f.ownerId(), "war.vet/" + f.getUUID(), Integer.toString(battles));
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        f.setHealth(f.getMaxHealth());
        return f;
    }

    private static <T extends Mob> T mob(ServerLevel level, EntityType<T> type, BlockPos at, boolean ai, double health) {
        T m = type.create(level);
        m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        m.setNoAi(!ai);
        m.setPersistenceRequired();
        if (health > 0) {
            m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
            m.setHealth((float) health);
        }
        level.addFreshEntity(m);
        return m;
    }

    private static void start(ServerLevel level) {
        Kit.reset(level);
        FolkCombat.resetForTests();
        level.setDayTime(40L * 24000L + 6000L);
    }

    private static BlockPos ground(ServerLevel level, int x, int dz) {
        return Kit.surface(level, x, Z + dz);
    }

    // ------------------------------------------------------------------ fc01

    /** A guard with a shield sees an arrow coming, raises its shield in time, and takes no hurt from it. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "fc01_shield")
    public static void fc01_shield(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1830000;
        Villages.Village v = town(level, x, 4);
        helper.assertTrue(v != null, "a town");
        AtomicReference<VillageFolkEntity> g = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            VillageFolkEntity f = guard(level, folk(v.id()).get(0), ground(level, x + 20, 20), new ItemStack(Items.IRON_SWORD), true, 2);
            f.setNoAi(true);                                            // it stands where it is; its eye for arrows is its own (FolkCombat.react)
            f.setYRot(180.0F);
            f.setYHeadRot(180.0F);
            f.yBodyRot = 180.0F;
            g.set(f);
            Skeleton s = mob(level, EntityType.SKELETON, ground(level, x + 20, -2), false, 0);
            Arrow arrow = new Arrow(level, s, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            arrow.setPos(s.getX(), s.getEyeY(), s.getZ());
            double h = Math.sqrt((f.getX() - s.getX()) * (f.getX() - s.getX()) + (f.getZ() - s.getZ()) * (f.getZ() - s.getZ()));
            arrow.shoot(f.getX() - s.getX(), f.getY(0.5) - s.getEyeY() + h * 0.2, f.getZ() - s.getZ(), 1.6F, 0.0F);   // a skeleton's own arc
            level.addFreshEntity(arrow);
        });
        helper.runAtTickTime(80, () -> {
            VillageFolkEntity f = g.get();
            int[] st = FolkCombat.statsForTests(f);
            Kit.log("fc01 the arrow: health " + f.getHealth() + "/" + f.getMaxHealth() + ", blocked " + st[2] + ", shield " + f.getOffhandItem().getDamageValue());
            helper.assertTrue(st[2] >= 1, "the shield took the arrow");
            helper.assertTrue(f.getHealth() >= f.getMaxHealth(), "no hurt from it: " + f.getHealth());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fc02

    /** An axe on a raised shield knocks it aside, and it stays down a while. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fc02_axe")
    public static void fc02_axe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1830400;
        Villages.Village v = town(level, x, 4);
        helper.assertTrue(v != null, "a town");
        AtomicReference<VillageFolkEntity> axeman = new AtomicReference<>(), shieldman = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            List<VillageFolkEntity> all = folk(v.id());
            VillageFolkEntity a = all.get(0), b = all.get(1);
            a.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            b.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            BlockPos at = ground(level, x + 20, 20);
            a.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            b.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.8, 180, 0);
            a.setNoAi(true);
            b.setNoAi(true);
            b.startUsingItem(InteractionHand.OFF_HAND);
            axeman.set(a);
            shieldman.set(b);
        });
        helper.runAtTickTime(32, () -> {
            VillageFolkEntity a = axeman.get(), b = shieldman.get();
            helper.assertTrue(b.isBlocking(), "the shield is up");
            FolkCombat.strike(a, b);
            Kit.log("fc02 after the axe: blocking " + b.isBlocking() + ", knocked aside " + FolkCombat.shieldDown(b));
            helper.assertTrue(!b.isBlocking() && FolkCombat.shieldDown(b), "the axe knocked the shield aside");
            helper.assertTrue(!FolkCombat.raiseNow(b, 10), "and it cannot be raised again yet");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fc03

    /** An archer at range hits a target walking across its front: the shot leads it. */
    @GameTest(template = EMPTY, timeoutTicks = 700, batch = "fc03_archer")
    public static void fc03_archer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1830800;
        Villages.Village v = town(level, x, 4);
        helper.assertTrue(v != null, "a town");
        AtomicReference<Husk> target = new AtomicReference<>();
        AtomicReference<VillageFolkEntity> archer = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            VillageFolkEntity f = guard(level, folk(v.id()).get(0), ground(level, x + 20, 30), new ItemStack(Items.BOW), false, 2);
            f.insertGiven(new ItemStack(Items.ARROW, 64));
            Husk h = mob(level, EntityType.HUSK, ground(level, x + 10, 46), false, 200);
            f.setTarget(h);
            archer.set(f);
            target.set(h);
        });
        helper.onEachTick(() -> {
            Husk h = target.get();
            if (h == null || !h.isAlive()) return;
            double vx = (level.getGameTime() / 120) % 2 == 0 ? 0.12 : -0.12;          // to and fro across the archer's front
            h.setDeltaMovement(vx, 0, 0);
            h.moveTo(h.getX() + vx, h.getY(), h.getZ(), h.getYRot(), 0);
            if (archer.get() != null && archer.get().getTarget() != h) archer.get().setTarget(h);
        });
        helper.runAtTickTime(640, () -> {
            Husk h = target.get();
            float lost = 200 - h.getHealth();
            int left = archer.get().countMatching(s -> s.is(Items.ARROW));
            Kit.log("fc03 the archer: " + (64 - left) + " arrows loosed, the target lost " + lost + " health");
            helper.assertTrue(64 - left >= 4, "it shot: " + (64 - left));
            helper.assertTrue(lost >= 2 * 4, "it hit the moving target more than once: " + lost);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fc04

    /** An archer with a friend standing in the way holds its shot (or steps round it): the friend is never hit. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "fc04_friend")
    public static void fc04_friend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1831200;
        Villages.Village v = town(level, x, 5);
        helper.assertTrue(v != null, "a town");
        AtomicReference<VillageFolkEntity> friend = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            List<VillageFolkEntity> all = folk(v.id());
            VillageFolkEntity f = guard(level, all.get(0), ground(level, x + 20, 30), new ItemStack(Items.BOW), false, 1);
            f.insertGiven(new ItemStack(Items.ARROW, 32));
            Husk h = mob(level, EntityType.HUSK, ground(level, x + 20, 44), false, 200);
            VillageFolkEntity mate = all.get(1);
            BlockPos mid = ground(level, x + 20, 37);
            mate.moveTo(mid.getX() + 0.5, mid.getY(), mid.getZ() + 0.5, 0, 0);
            mate.setNoAi(true);
            friend.set(mate);
            f.setTarget(h);
        });
        helper.runAtTickTime(360, () -> {
            VillageFolkEntity mate = friend.get();
            Kit.log("fc04 the friend in the way: " + mate.getHealth() + "/" + mate.getMaxHealth());
            helper.assertTrue(mate.getHealth() >= mate.getMaxHealth(), "the friend in the way was never hit");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fc05

    /** A crossbow is spanned (its real charge time) and loosed at the target. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "fc05_crossbow")
    public static void fc05_crossbow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1831600;
        Villages.Village v = town(level, x, 4);
        helper.assertTrue(v != null, "a town");
        AtomicReference<Husk> target = new AtomicReference<>();
        AtomicReference<VillageFolkEntity> shooter = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            VillageFolkEntity f = guard(level, folk(v.id()).get(0), ground(level, x + 20, 30), new ItemStack(Items.CROSSBOW), false, 1);
            f.insertGiven(new ItemStack(Items.ARROW, 16));
            Husk h = mob(level, EntityType.HUSK, ground(level, x + 20, 42), false, 200);
            f.setTarget(h);
            target.set(h);
            shooter.set(f);
        });
        helper.succeedWhen(() -> {
            Husk h = target.get();
            helper.assertTrue(h != null && h.getHealth() < 200, "the crossbow was spanned and loosed, and hit");
            Kit.log("fc05 the crossbow: the target at " + h.getHealth() + ", arrows left " + shooter.get().countMatching(s -> s.is(Items.ARROW)));
        });
    }

    // ------------------------------------------------------------------ fc06

    /** At a tough zombie: the blows wait on the sword's cooldown, and a practised guard lands crits. */
    @GameTest(template = EMPTY, timeoutTicks = 700, batch = "fc06_timing")
    public static void fc06_timing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1832000;
        Villages.Village v = town(level, x, 4);
        helper.assertTrue(v != null, "a town");
        AtomicReference<VillageFolkEntity> g = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            VillageFolkEntity f = guard(level, folk(v.id()).get(0), ground(level, x + 20, 20), new ItemStack(Items.IRON_SWORD), true, 3);
            Husk h = mob(level, EntityType.HUSK, ground(level, x + 20, 23), false, 400);
            f.setTarget(h);
            g.set(f);
        });
        helper.runAtTickTime(600, () -> {
            int[] st = FolkCombat.statsForTests(g.get());
            int cd = FolkCombat.cooldown(new ItemStack(Items.IRON_SWORD));
            Kit.log("fc06 blows " + st[0] + ", crits " + st[1] + ", shortest gap " + st[3] + " (the sword's cooldown " + cd + ")");
            helper.assertTrue(st[0] >= 5, "it fought: " + st[0] + " blows");
            helper.assertTrue(st[3] >= cd, "never a blow before the cooldown was up: " + st[3] + " < " + cd);
            helper.assertTrue(st[1] >= 1, "a practised guard lands crits: " + st[1]);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fc07

    /** A veteran guard in iron with a sword and shield beats a zombie, a skeleton and a vindicator, one after another. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "fc07_veteran")
    public static void fc07_veteran(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        level.setDayTime(40L * 24000L + 18000L);                  // night: nothing burns in the sun
        int x = 1832400;
        Villages.Village v = town(level, x, 4);
        helper.assertTrue(v != null, "a town");
        AtomicReference<VillageFolkEntity> g = new AtomicReference<>();
        List<Mob> foes = new ArrayList<>();
        helper.runAtTickTime(20, () -> {
            VillageFolkEntity f = guard(level, folk(v.id()).get(0), ground(level, x + 20, 20), new ItemStack(Items.IRON_SWORD), true, 3);
            g.set(f);
        });
        int[] at = { 40, 600, 1200 };
        EntityType<?>[] kinds = { EntityType.ZOMBIE, EntityType.SKELETON, EntityType.VINDICATOR };
        for (int i = 0; i < 3; i++) {
            final int k = i;
            helper.runAtTickTime(at[i], () -> {
                VillageFolkEntity f = g.get();
                f.setHealth(f.getMaxHealth());
                Mob m = (Mob) mob(level, (EntityType<? extends Mob>) kinds[k], ground(level, x + 20, 28), true, 0);
                m.setTarget(f);
                f.setTarget(m);
                foes.add(m);
            });
        }
        helper.runAtTickTime(1800, () -> {
            VillageFolkEntity f = g.get();
            int dead = 0;
            for (Mob m : foes) if (!m.isAlive()) dead++;
            Kit.log("fc07 the veteran: " + dead + " of " + foes.size() + " beaten; its health " + f.getHealth() + "/" + f.getMaxHealth()
                + "; " + java.util.Arrays.toString(FolkCombat.statsForTests(f)));
            helper.assertTrue(f.isAlive(), "the veteran lived");
            helper.assertTrue(dead >= 2, "it beat most of them: " + dead + " of 3");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fc08

    /** How long the duel runs (ticks): long enough for a hundred blows or so each, so the tally is the hands' and not luck's. */
    private static final int DUEL = 3000;
    /**
     * The least share of the recruit's damage the veteran deals over the duel. Skill tells in the veteran's crits, its
     * shield and a hand a little quicker between blows; who is in reach when is chance, and over a thousand ticks the
     * veteran's share ran from 0.9 to 1.3 of the recruit's. Over three times as long, falling under 0.8 would mean the
     * veteran was outfought, not unlucky.
     */
    private static final double OUTFOUGHT_UNDER = 0.8;

    /** A veteran and a recruit, the same kit, set at each other: the veteran is the more practised, and never outfought. */
    @GameTest(template = EMPTY, timeoutTicks = DUEL + 200, batch = "fc08_duel")
    public static void fc08_duel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 1832800;
        Villages.Village v = town(level, x, 5);
        helper.assertTrue(v != null, "a town");
        AtomicReference<VillageFolkEntity[]> pair = new AtomicReference<>();
        helper.runAtTickTime(20, () -> {
            List<VillageFolkEntity> all = folk(v.id());
            VillageFolkEntity vet = guard(level, all.get(0), ground(level, x + 20, 20), new ItemStack(Items.IRON_SWORD), true, 3);
            VillageFolkEntity rec = all.get(1);
            rec.setJob(AssistantEntity.StationTask.FARM);
            rec.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            rec.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            for (EquipmentSlot s : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
                rec.setItemSlot(s, vet.getItemBySlot(s).copy());
            }
            BlockPos at = ground(level, x + 20, 24);
            rec.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
            pair.set(new VillageFolkEntity[]{ vet, rec });
        });
        helper.onEachTick(() -> {
            VillageFolkEntity[] p = pair.get();
            if (p == null) return;
            for (int i = 0; i < 2; i++) {
                if (p[i].getHealth() < 6.0F) p[i].setHealth(p[i].getMaxHealth());   // a bout, not a death
                if (p[i].getTarget() != p[1 - i]) p[i].setTarget(p[1 - i]);
            }
        });
        helper.runAtTickTime(DUEL, () -> {
            VillageFolkEntity[] p = pair.get();
            int[] vet = FolkCombat.statsForTests(p[0]), rec = FolkCombat.statsForTests(p[1]);
            Kit.log("fc08 the duel: the veteran " + java.util.Arrays.toString(vet) + ", the recruit " + java.util.Arrays.toString(rec)
                + "; skill " + FolkCombat.skill(p[0]) + " against " + FolkCombat.skill(p[1]));
            helper.assertTrue(FolkCombat.skill(p[0]) > FolkCombat.skill(p[1]), "the veteran the more practised");
            float dv = FolkCombat.dealtForTests(p[0]), dr = FolkCombat.dealtForTests(p[1]);
            Kit.log("fc08 damage dealt: the veteran " + dv + ", the recruit " + dr + " (" + (dr > 0 ? dv / dr : 0) + ")");
            helper.assertTrue(dr > 0 && dv >= OUTFOUGHT_UNDER * dr, "the veteran is never outfought: dealt " + dv + " against " + dr);
            helper.succeed();
        });
    }
}
