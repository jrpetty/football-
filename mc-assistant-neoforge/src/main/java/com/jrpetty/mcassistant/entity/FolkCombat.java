package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war] How a folk fights, one brain for every armed folk: the watch, the militia and the armies, the cave team, the
 * Nether runners, a farmer set on by a zombie. The goals ask it (goal/FolkFightGoal for the blade, BowAttackGoal for
 * the bow and the crossbow); the game's own attributes and enchantments do the arithmetic.
 *
 * <ul>
 * <li><b>Timing.</b> A blade has its weapon's cooldown, as a player's has: a sword about twelve ticks, an axe twenty
 *     and more. A blow comes when the cooldown is up, never before; between blows it steps back and circles, out of
 *     the other's reach, rather than stand trading hits.</li>
 * <li><b>Crits and sweeps.</b> With room overhead and the cooldown up, a practised fighter jumps and strikes as it
 *     comes down: half again the damage, and the crit's sparks. A sword in a crowd sweeps the foes round its man.
 *     A blow on the run knocks the other back the further.</li>
 * <li><b>Shields.</b> Up when the other winds up to strike or an arrow is coming (a moment's reaction, quicker for a
 *     veteran), down to strike. An axe on a raised shield knocks it aside for five seconds, as it does a player's;
 *     a fighter holding an axe and a sword uses the axe for that and goes back to the sword. A shield takes the
 *     blows it stops, wears and breaks; the watch's are made good out of the stores (WatchKit).</li>
 * <li><b>Bows.</b> A full draw for range, a quick shot up close; the shot leads a moving target and arcs for
 *     distance; never through a friend in the way (it holds the shot and steps aside); cornered, it goes to the
 *     blade (BowAttackGoal).</li>
 * <li><b>Healing.</b> Badly hurt and out of the line, a golden apple or a potion of healing out of its pack.</li>
 * <li><b>Skill.</b> From its trade and its battles: a guard is steadier than a farmer, a veteran of three battles
 *     steadier again. Skill is the reaction to a blow coming, the timing of the swing and the chance it takes a crit;
 *     a recruit is clumsier but still sensible.</li>
 * </ul>
 * Its cost: a few fields per fighter in a map, a look for arrows every few ticks only for those holding a shield,
 * and its decisions every few ticks, inside the goals that were already running.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FolkCombat {

    private FolkCombat() {}

    /** How long a shield an axe has knocked aside stays down. */
    public static final int SHIELD_DOWN = 100;
    /** The fewest ticks between blows with anything (a fist, a stick). */
    static final int QUICKEST = 10;
    /** A crit: so much more damage. */
    static final double CRIT = 0.5;
    static final ResourceLocation CRIT_ID = ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folk_crit");

    /** One fighter's state in a fight: its last blow, its shield, a crit on the way down, what it has done (for the tests). */
    static final class State {
        long lastBlow = -1000, shieldDownUntil, raisedUntil, critUntil, axeUntil, reactAt = -1;
        int blows, crits, blocked, minGap = Integer.MAX_VALUE;
        float dealt;
        int strafe = 1;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    static State state(LivingEntity e) {
        if (STATES.size() > 1024) {                                  // those who have not fought for five minutes, let go
            long now = e.level().getGameTime();
            STATES.values().removeIf(s -> now - Math.max(s.lastBlow, s.raisedUntil) > 6000);
        }
        return STATES.computeIfAbsent(e.getUUID(), k -> new State());
    }

    // ------------------------------------------------------------------ the fight goal's handles (goal/FolkFightGoal)

    public static boolean healNow(AssistantEntity a) { return heal(a); }
    public static void chooseBladeNow(AssistantEntity a, LivingEntity t, long now) { chooseBlade(a, t, now); }
    public static boolean readyNow(LivingEntity e, long now) { return ready(e, now); }
    public static boolean raiseNow(LivingEntity e, int ticks) { return raise(e, ticks); }
    public static boolean jumpNow(net.minecraft.world.entity.Mob m, long now) { return jumpForCrit(m, now); }

    /** Is a crit on its way (it has jumped for one, and it is not yet spent)? */
    public static boolean critComing(LivingEntity e, long now) {
        State s = STATES.get(e.getUUID());
        return s != null && now <= s.critUntil;
    }

    /** Which way it circles (1 or -1), and the other way. */
    public static int strafeSide(LivingEntity e) { return state(e).strafe; }
    public static void flip(LivingEntity e) { State s = state(e); s.strafe = -s.strafe; }

    public static void resetForTests() {
        STATES.clear();
    }

    /** Forgotten when it is (dead, or out of the world): the map keeps only those who fought lately. */
    static void forget(UUID id) {
        STATES.remove(id);
    }

    // ------------------------------------------------------------------ skill

    /**
     * How practised a fighter it is, 0.1 (never held a blade) to 0.95: a guard and the cave team and the Nether runners
     * more than a farmer, and each battle fought (and come home from) a little more.
     */
    public static double skill(LivingEntity e) {
        if (!(e instanceof AssistantEntity a)) return 0.5;
        double s = 0.25;
        AssistantEntity.StationTask t = a.stationTask();
        if (t == AssistantEntity.StationTask.GUARD) s += 0.3;
        else if (t == AssistantEntity.StationTask.CAVE || t == AssistantEntity.StationTask.NETHER || t == AssistantEntity.StationTask.HUNT) s += 0.15;
        UUID town = a instanceof VillageFolkEntity f ? f.ownerId() : null;
        if (town != null) s += Math.min(0.3, Armies.battles(town, a.getUUID()) * 0.1);
        return Mth.clamp(s, 0.1, 0.95);
    }

    // ------------------------------------------------------------------ the blade

    /** The ticks between blows with this in hand: its attack speed, as a player's cooldown counts it. */
    public static int cooldown(ItemStack weapon) {
        double[] speed = { 4.0 };
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.is(Attributes.ATTACK_SPEED) && mod.operation() == AttributeModifier.Operation.ADD_VALUE) speed[0] += mod.amount();
        });
        return Math.max(QUICKEST, (int) Math.ceil(20.0 / Math.max(0.25, speed[0])));
    }

    /** Is its blow ready (the cooldown up, and a moment for a recruit's slower hand)? */
    static boolean ready(LivingEntity e, long now) {
        State s = state(e);
        int wait = cooldown(e.getMainHandItem()) + (int) Math.round((1.0 - skill(e)) * 3);
        return now - s.lastBlow >= wait;
    }

    /**
     * A blow at the other, now: with the axe on a raised shield, the shield knocked aside; a crit if it is coming down
     * from its jump; a sweep round its man if a sword and a crowd; the further knock on the run. Through the game's own
     * blow (Mob.doHurtTarget: its weapon's damage, its enchantments, its knockback). Whether it struck.
     */
    public static boolean strike(LivingEntity attacker, LivingEntity target) {
        if (!(attacker instanceof net.minecraft.world.entity.Mob mob) || !(attacker.level() instanceof ServerLevel level)) return false;
        State s = state(attacker);
        long now = level.getGameTime();
        int gap = (int) (now - s.lastBlow);
        if (s.blows > 0) s.minGap = Math.min(s.minGap, gap);
        s.lastBlow = now;
        s.blows++;
        lower(attacker);
        mob.swing(InteractionHand.MAIN_HAND);
        ItemStack weapon = mob.getMainHandItem();
        if (weapon.getItem() instanceof AxeItem && target.isBlocking()) {
            knockAside(target);
            level.playSound(null, target.blockPosition(), SoundEvents.SHIELD_BREAK, SoundSource.HOSTILE, 1.0F, 0.8F + level.getRandom().nextFloat() * 0.4F);
            return true;
        }
        boolean crit = now <= s.critUntil && !mob.onGround() && mob.getDeltaMovement().y < 0;
        AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (crit && dmg != null) dmg.addOrUpdateTransientModifier(new AttributeModifier(CRIT_ID, CRIT, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        boolean hit;
        float before = target.getHealth();
        try {
            hit = mob.doHurtTarget(target);
            s.dealt += Math.max(0.0F, before - target.getHealth());
        } finally {
            if (dmg != null) dmg.removeModifier(CRIT_ID);
        }
        if (!hit) return false;
        if (crit) {
            s.crits++;
            s.critUntil = 0;
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.6), target.getZ(), 10, 0.3, 0.3, 0.3, 0.2);
            level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.0F, 1.0F);
        }
        if (mob.isSprinting()) {
            target.knockback(0.5, Mth.sin(mob.getYRot() * Mth.DEG_TO_RAD), -Mth.cos(mob.getYRot() * Mth.DEG_TO_RAD));
            mob.setSprinting(false);
        }
        if (weapon.getItem() instanceof SwordItem) sweep(level, mob, target);
        return true;
    }

    /** A sword's sweep: the foes round its man (within a block and a half of it) caught by the edge. */
    static void sweep(ServerLevel level, net.minecraft.world.entity.Mob mob, LivingEntity target) {
        int n = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(1.5, 0.25, 1.5),
                x -> x != target && x != mob && x.isAlive() && foe(mob, x))) {
            if (n++ >= 3) break;
            e.knockback(0.4, Mth.sin(mob.getYRot() * Mth.DEG_TO_RAD), -Mth.cos(mob.getYRot() * Mth.DEG_TO_RAD));
            e.hurt(level.damageSources().mobAttack(mob), 1.0F);
        }
        if (n > 0) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY(0.5), target.getZ(), 1, 0, 0, 0, 0);
            level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0F, 1.0F);
        }
    }

    /** Is that one its foe (fair game for a sweep): an enemy fighter in a battle, else a monster? */
    static boolean foe(LivingEntity me, LivingEntity other) {
        Armies.Fighter f = Armies.fighter(me.getUUID());
        if (f != null && f.army != null) return Armies.fair(f.army.town, other);
        return other instanceof Enemy && !(other instanceof Player);
    }

    /** Ready a crit: a jump now, the blow on the way down (within half a second). With room overhead, on its feet. */
    static boolean jumpForCrit(net.minecraft.world.entity.Mob mob, long now) {
        if (!mob.onGround() || mob.isInWater()) return false;
        if (!mob.level().getBlockState(mob.blockPosition().above(2)).isAir()) return false;
        mob.getJumpControl().jump();
        state(mob).critUntil = now + 12;
        return true;
    }

    // ------------------------------------------------------------------ the shield

    /** Has it a shield on its arm (or in its pack, to put there)? */
    static boolean hasShield(LivingEntity e) {
        return e.getOffhandItem().getItem() instanceof ShieldItem;
    }

    /** Its shield knocked aside by an axe: down now, and no raising it for five seconds. */
    public static void knockAside(LivingEntity target) {
        if (target instanceof Player p) {
            p.disableShield();
            return;
        }
        if (target.isUsingItem() && target.getUsedItemHand() == InteractionHand.OFF_HAND) target.stopUsingItem();
        state(target).shieldDownUntil = target.level().getGameTime() + SHIELD_DOWN;
    }

    /** Is its shield knocked aside just now? */
    public static boolean shieldDown(LivingEntity e) {
        State s = STATES.get(e.getUUID());
        return s != null && e.level().getGameTime() < s.shieldDownUntil;
    }

    /** Shield up (the real blocking pose), for so many ticks at least. Whether it is up. */
    static boolean raise(LivingEntity e, int ticks) {
        if (!hasShield(e) || shieldDown(e)) return false;
        if (e.isUsingItem() && e.getUsedItemHand() == InteractionHand.MAIN_HAND) return false;     // drawing a bow
        if (!e.isUsingItem()) e.startUsingItem(InteractionHand.OFF_HAND);
        State s = state(e);
        s.raisedUntil = Math.max(s.raisedUntil, e.level().getGameTime() + ticks);
        return true;
    }

    /** Shield down, to strike. */
    static void lower(LivingEntity e) {
        if (e.isUsingItem() && e.getUsedItemHand() == InteractionHand.OFF_HAND) e.stopUsingItem();
    }

    /** Does the brain hold the shield arm now (so the watch's own shield habit leaves it be)? */
    public static boolean handlesShield(LivingEntity e) {
        State s = STATES.get(e.getUUID());
        return s != null && e.level().getGameTime() < s.raisedUntil + 20;
    }

    /**
     * Every few ticks, for one holding a shield: an arrow coming its way (within a dozen blocks, flying at it) raises
     * the shield, after a moment's reaction (the quicker for skill), and holds it till the arrow is past.
     */
    public static void react(LivingEntity e) {
        if (!hasShield(e) || shieldDown(e) || !(e.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        State s = state(e);
        if (now < s.raisedUntil) {
            if (!e.isUsingItem()) e.startUsingItem(InteractionHand.OFF_HAND);
            return;
        }
        if (e.isUsingItem() && e.getUsedItemHand() == InteractionHand.OFF_HAND && s.raisedUntil > 0 && now >= s.raisedUntil) {
            lower(e);
            s.raisedUntil = 0;
        }
        for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, e.getBoundingBox().inflate(20.0), x -> !x.onGround() && x.getOwner() != e)) {
            Vec3 v = arrow.getDeltaMovement(), to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(arrow.position());
            if (v.lengthSqr() < 0.04 || v.normalize().dot(to.normalize()) < 0.9) continue;
            // A moment's reaction: none for the practised (its eye on the bows), a few ticks for a recruit.
            if (s.reactAt < 0) s.reactAt = now + Math.max(0, Math.round((0.8 - skill(e)) * 8));
            if (now >= s.reactAt) {
                raise(e, 20);
                s.reactAt = -1;
                lookAt(e, arrow);
            }
            return;
        }
        s.reactAt = -1;
    }

    static void lookAt(LivingEntity e, Entity at) {
        if (e instanceof net.minecraft.world.entity.Mob m) m.getLookControl().setLookAt(at, 60.0F, 60.0F);
    }

    /** A blow or an arrow on a folk's raised shield: counted, and the shield worn for it (as a player's is). */
    @SubscribeEvent
    public static void onBlock(LivingShieldBlockEvent e) {
        if (!(e.getEntity() instanceof AssistantEntity a) || !e.getBlocked() || a.level().isClientSide) return;
        state(a).blocked++;
        ItemStack shield = a.getUseItem();
        if (shield.getItem() instanceof ShieldItem && e.getBlockedDamage() >= 3.0F) {
            int wear = 1 + Mth.floor(e.getBlockedDamage());
            shield.hurtAndBreak(wear, a, a.getUsedItemHand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND);
        }
    }

    // ------------------------------------------------------------------ choosing the weapon

    /**
     * The right blade for this enemy: the axe for a raised shield (if it carries one; back to the sword three seconds
     * after), else the quickest killer in its pack (damage over the cooldown). Swapped into its hand.
     */
    static void chooseBlade(AssistantEntity a, LivingEntity target, long now) {
        State s = state(a);
        boolean axe = target.isBlocking() && !(a.getMainHandItem().getItem() instanceof AxeItem);
        if (axe) {
            int i = slot(a, st -> st.getItem() instanceof AxeItem);
            if (i >= 0) {
                swap(a, i);
                s.axeUntil = now + 60;
            }
            return;
        }
        if (a.getMainHandItem().getItem() instanceof AxeItem && now > s.axeUntil) {
            int i = slot(a, st -> st.getItem() instanceof SwordItem);
            if (i >= 0) swap(a, i);
        }
    }

    /** Is it holding the axe for a shield just now (its weapon swap waits)? */
    public static boolean busy(LivingEntity e) {
        State s = STATES.get(e.getUUID());
        return s != null && e.level().getGameTime() < s.axeUntil;
    }

    static int slot(AssistantEntity a, java.util.function.Predicate<ItemStack> what) {
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && what.test(inv.get(i))) return i;
        return -1;
    }

    static void swap(AssistantEntity a, int i) {
        var inv = a.getInventoryItems();
        ItemStack held = a.getMainHandItem();
        a.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
        inv.set(i, held);
    }

    /** Badly hurt and out of the line: a golden apple or a potion of healing out of its pack. Whether it took one. */
    static boolean heal(AssistantEntity a) {
        if (a.getHealth() > a.getMaxHealth() * 0.35F || Armies.inLine(a)) return false;
        int i = slot(a, st -> st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE));
        if (i >= 0) {
            ItemStack apple = a.getInventoryItems().get(i);
            a.eat(a.level(), apple.copyWithCount(1));
            apple.shrink(1);
            return true;
        }
        i = slot(a, st -> st.has(DataComponents.POTION_CONTENTS) && (st.is(Items.POTION) || st.is(Items.SPLASH_POTION))
            && st.get(DataComponents.POTION_CONTENTS).is(net.minecraft.world.item.alchemy.Potions.HEALING));
        if (i < 0) i = slot(a, st -> st.has(DataComponents.POTION_CONTENTS) && st.get(DataComponents.POTION_CONTENTS).is(net.minecraft.world.item.alchemy.Potions.STRONG_HEALING));
        if (i < 0) return false;
        ItemStack potion = a.getInventoryItems().get(i);
        PotionContents pc = potion.get(DataComponents.POTION_CONTENTS);
        if (pc != null) pc.forEachEffect(a::addEffect);
        a.getInventoryItems().set(i, new ItemStack(Items.GLASS_BOTTLE));
        a.level().playSound(null, a.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        return true;
    }

    // ------------------------------------------------------------------ the bow

    /**
     * Where to aim at a target that moves: where it will be when the arrow gets there (its pace over the flight), the
     * lead tempered by skill.
     */
    public static Vec3 lead(LivingEntity shooter, LivingEntity target, float velocity) {
        Vec3 at = new Vec3(target.getX(), target.getY(0.3333), target.getZ());
        double flight = shooter.distanceTo(target) / Math.max(0.5, velocity * 0.9);
        Vec3 v = target.getDeltaMovement();
        double k = 0.5 + 0.5 * skill(shooter);
        return at.add(v.x * flight * k, 0, v.z * flight * k);
    }

    /** Is the shot clear: nobody of its own (its town's folk, its army, a player it works for) within a block of the line? */
    public static boolean clearShot(LivingEntity shooter, LivingEntity target) {
        Vec3 from = shooter.getEyePosition(), to = target.position().add(0, target.getBbHeight() * 0.5, 0);
        AABB box = new AABB(from, to).inflate(1.0);
        for (LivingEntity e : shooter.level().getEntitiesOfClass(LivingEntity.class, box, x -> x != shooter && x != target && x.isAlive())) {
            if (!friend(shooter, e)) continue;
            Vec3 p = e.position().add(0, e.getBbHeight() * 0.5, 0);
            Vec3 d = to.subtract(from);
            double t = Mth.clamp(p.subtract(from).dot(d) / Math.max(1e-6, d.lengthSqr()), 0, 1);
            if (from.add(d.scale(t)).distanceToSqr(p) < 1.0) return false;
        }
        return true;
    }

    /** One of its own: of its town, of its army, or the player it works for. */
    static boolean friend(LivingEntity me, LivingEntity other) {
        if (me instanceof VillageFolkEntity f && other instanceof VillageFolkEntity o && f.ownerId() != null && f.ownerId().equals(o.ownerId())) return true;
        Armies.Fighter a = Armies.fighter(me.getUUID()), b = Armies.fighter(other.getUUID());
        if (a != null && b != null && a.army == b.army) return true;
        if (me instanceof AssistantEntity as && other instanceof Player p && p == as.getOwnerPlayer()) return true;
        return other instanceof Player p && me instanceof VillageFolkEntity f2 && f2.ownerId() != null && Ledger.citizen(f2.ownerId(), p.getUUID());
    }

    /** How long to draw: a full draw (twenty ticks, a recruit's a little longer) for range, a quick one close in. */
    public static int draw(LivingEntity shooter, double distSq) {
        if (distSq < 8 * 8) return 10;
        return 20 + (int) Math.round((1.0 - skill(shooter)) * 6);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the damage its blows have done. */
    public static float dealtForTests(LivingEntity e) {
        State s = STATES.get(e.getUUID());
        return s == null ? 0 : s.dealt;
    }

    /** Tests: blows struck, crits, blows blocked, the shortest gap between two blows. */
    public static int[] statsForTests(LivingEntity e) {
        State s = STATES.get(e.getUUID());
        return s == null ? new int[]{ 0, 0, 0, Integer.MAX_VALUE } : new int[]{ s.blows, s.crits, s.blocked, s.minGap };
    }
}
