package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featpa] Combat roles, chosen on its Stance screen (Orders > Combat stance), each its own look on camera:
 * <ul>
 * <li><b>Shield</b>: a shield up in its off hand, it plants itself between whoever it guards (you, or a villager you
 *     picked) and the trouble, calls every monster near them onto itself (they turn and come at it), takes the blows on
 *     the shield and strikes back with the shield dropped for the swing.</li>
 * <li><b>Archer</b>: bow or crossbow out, it keeps its distance (backing off and stepping sideways between shots) and
 *     shoots first at whatever is going for a villager, then at whatever is going for you.</li>
 * <li><b>Medic</b>: it keeps out of the fight and goes to the hurt (you, villagers, the town's folk), kneels and treats
 *     them: a golden apple for the badly hurt, a healing potion (thrown, if it's a splash), food otherwise, hearts over
 *     them as it does; and the badly wounded with a monster on them it pulls back out of the fight first.</li>
 * </ul>
 * And in a raid or an attack on a town near by (Raids' alarm, the muster at the square, a vanilla raid), it joins the
 * town's guards at the square in its stance (its own way: as a guard with a blade) till the all-clear.
 */
public final class PaStance {

    private PaStance() {}

    public enum Kind {
        NONE("Its own way", "Fights as it always has: blade close, bow at range, guards you."),
        SHIELD("Shield", "Draws the monsters onto itself, blocks with a shield, guards you or a villager."),
        ARCHER("Archer", "Keeps its distance with a bow or crossbow, shooting first at what goes for villagers."),
        MEDIC("Medic", "Stays out of the fight; heals you and the villagers, pulls the wounded back.");

        public final String label, blurb;

        Kind(String label, String blurb) { this.label = label; this.blurb = blurb; }

        public static Kind of(int i) { return values()[Math.floorMod(i, values().length)]; }
    }

    public static Kind of(PlayerAssistantEntity a) {
        if (a.level().isClientSide) return Kind.of(a.stanceSynced());
        PaData.Rec r = a.rec();
        return r == null ? Kind.NONE : Kind.of(r.stance);
    }

    /** Set from the screen: kept in its record, shown on its nameplate, said once. */
    public static void set(PlayerAssistantEntity a, Kind k) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        Kind was = Kind.of(r.stance);
        r.stance = k.ordinal();
        PaData.touch();
        a.setStanceSynced(k.ordinal());
        if (a.getPose() == Pose.CROUCHING) a.setPose(Pose.STANDING);
        if (a.isUsingItem() && k != Kind.SHIELD) a.stopUsingItem();
        // its engine's own stance: the archer keeps its bow up, the others fight as the fight calls
        AssistantEntity.Stance want = k == Kind.ARCHER ? AssistantEntity.Stance.RANGED : k == Kind.SHIELD ? AssistantEntity.Stance.MELEE : AssistantEntity.Stance.AUTO;
        for (int i = 0; i < 3 && a.combatStance() != want; i++) a.cycleStance();
        if (was != k) {
            PaTalk.reply(a, PaLogiTalk.line(a, "kit.st_" + k.name().toLowerCase(java.util.Locale.ROOT)));
            switch (k) {
                case SHIELD -> { a.playAnim("block"); if (!armShield(a)) PaTalk.comment(a, 0, PaLogiTalk.line(a, "kit.st_no_shield"), true); }
                case ARCHER -> { a.playAnim("salute"); armBow(a); if (!hasBow(a)) PaTalk.comment(a, 0, PaLogiTalk.line(a, "kit.st_no_bow"), true); }
                case MEDIC -> { a.playAnim("kneel"); if (remedies(a) == 0) PaTalk.comment(a, 0, PaLogiTalk.line(a, "kit.st_no_remedy"), true); }
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ who it guards, and what threatens them

    /** Who it guards: the villager you picked (while it lives and is near), else you. */
    @Nullable
    static LivingEntity ward(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r != null && r.guardWho != null && a.level() instanceof ServerLevel sl) {
            var e = sl.getEntity(r.guardWho);
            if (e instanceof LivingEntity le && le.isAlive() && le.distanceToSqr(a) < 64 * 64) return le;
        }
        return a.getOwnerPlayer();
    }

    /** A villager of any kind: the town's folk or the game's own. */
    static boolean villager(LivingEntity e) {
        return e instanceof VillageFolkEntity || e instanceof AbstractVillager;
    }

    /** A monster or a raider it may fight (never a person, a villager, a pet, anybody's assistant). */
    static boolean hostile(LivingEntity e) {
        return e.isAlive() && (e instanceof Enemy || e instanceof Raider) && PlayerAssistantEntity.fairGame(e);
    }

    /** The threats round a spot, the most pressing first: those going for a villager, then for its ward, then the nearest. */
    static List<Mob> threats(PlayerAssistantEntity a, Vec3 at, double radius, @Nullable LivingEntity ward) {
        List<Mob> out = new ArrayList<>(a.level().getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(radius, 8, radius), m -> hostile(m)));
        out.sort(Comparator.comparingDouble(m -> score(a, m, ward, at)));
        return out;
    }

    private static double score(PlayerAssistantEntity a, Mob m, @Nullable LivingEntity ward, Vec3 at) {
        double s = m.position().distanceTo(at);
        LivingEntity t = m.getTarget();
        if (t != null && villager(t)) s -= 40;
        else if (t != null && t == ward) s -= 30;
        else if (t instanceof Player) s -= 20;
        if (!a.level().getEntitiesOfClass(LivingEntity.class, m.getBoundingBox().inflate(4), PaStance::villager).isEmpty()) s -= 10;
        return s;
    }

    // ------------------------------------------------------------------ a town under attack near by

    static final Map<UUID, UUID> DEFENDING = new ConcurrentHashMap<>();

    /** The town it is helping defend just now, or null. */
    @Nullable
    public static Villages.Village defending(PlayerAssistantEntity a) {
        UUID id = DEFENDING.get(a.getUUID());
        return id == null ? null : Villages.get(id);
    }

    /** Is this town being attacked: its alarm, its muster, a vanilla raid, or raiders and monsters among its folk? */
    static boolean underAttack(ServerLevel level, Villages.Village v) {
        if (com.jrpetty.mcassistant.entity.Raids.underAlarm(v.id()) || com.jrpetty.mcassistant.entity.Muster.called(v.id())) return true;
        if (level.getRaidAt(v.centre()) != null) return true;
        return !level.getEntitiesOfClass(Raider.class, new AABB(v.centre()).inflate(48, 16, 48), Raider::isAlive).isEmpty();
    }

    // ------------------------------------------------------------------ its tick: the archer's mark, the raid, the badge

    public static void tick(PlayerAssistantEntity a) {
        if (a.level().isClientSide || a.tickCount % 5 != 0) return;
        PaData.Rec r = a.rec();
        if (r == null) return;
        if (a.stanceSynced() != r.stance) a.setStanceSynced(r.stance);
        ServerLevel level = (ServerLevel) a.level();
        Kind k = Kind.of(r.stance);
        // a town attacked near by: it joins the defence, and leaves at the all-clear
        if (a.tickCount % 20 == 0) {
            Villages.Village now = defending(a);
            if (now != null && (!underAttack(level, now) || !r.helpTown)) {
                DEFENDING.remove(a.getUUID());
                PaTalk.reply(a, PaLogiTalk.line(a, "kit.st_raid_over", "item", Villages.name(now.id())));
            } else if (now == null && r.helpTown && !a.isSleeping()) {
                Villages.Village v = Villages.nearest(level, a.blockPosition(), 96);
                if (v != null && underAttack(level, v)) {
                    DEFENDING.put(a.getUUID(), v.id());
                    PaTalk.reply(a, PaLogiTalk.line(a, "kit.st_raid_join", "item", Villages.name(v.id()), "what", k == Kind.NONE ? "guard" : k.label.toLowerCase()));
                    a.playAnim("salute");
                }
            }
        }
        Villages.Village town = defending(a);
        // the archer picks its mark: what's going for a villager first
        if (k == Kind.ARCHER || town != null && k != Kind.MEDIC && k != Kind.SHIELD) {
            LivingEntity w = ward(a);
            Vec3 at = town != null ? Vec3.atCenterOf(town.centre()) : w != null ? w.position() : a.position();
            List<Mob> th = threats(a, at, town != null ? 40 : 20, w);
            if (!th.isEmpty() && (a.getTarget() == null || !a.getTarget().isAlive() || a.tickCount % 40 == 0)) {
                Mob m = th.get(0);
                if (a.getTarget() != m) a.setTarget(m);
                if (k == Kind.ARCHER) armBow(a);                              // after: a fresh target puts the sword in its hand
            }
        }
        // the medic keeps out of fights it didn't start: a target is let go unless it's right on top of it
        if (k == Kind.MEDIC && a.getTarget() != null) {
            LivingEntity t = a.getTarget();
            if (!(t instanceof Mob m && m.getTarget() == a && t.distanceToSqr(a) < 9)) a.setTarget(null);
        }
    }

    // ------------------------------------------------------------------ kit

    static boolean hasShield(PlayerAssistantEntity a) {
        return a.getOffhandItem().is(Items.SHIELD) || a.countMatching(s -> s.is(Items.SHIELD)) > 0;
    }

    static boolean hasBow(PlayerAssistantEntity a) {
        return a.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem
            || a.countMatching(s -> s.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) > 0;
    }

    /** The shield into its off hand (what was there into its pack). */
    static boolean armShield(PlayerAssistantEntity a) {
        if (a.getOffhandItem().is(Items.SHIELD)) return true;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.get(i).is(Items.SHIELD)) continue;
            ItemStack was = a.getOffhandItem();
            a.setItemSlot(EquipmentSlot.OFFHAND, inv.get(i));
            inv.set(i, was);
            return true;
        }
        return false;
    }

    /** The bow (or crossbow) into its hand. */
    static void armBow(PlayerAssistantEntity a) {
        if (a.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) return;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!(s.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) || !a.mayShoot(s)) continue;
            ItemStack was = a.getMainHandItem();
            a.setItemSlot(EquipmentSlot.MAINHAND, s);
            inv.set(i, was);
            return;
        }
    }

    /** Is it an archer in a fight just now (PaBowGoal keeps a longer range, and steps sideways)? */
    static boolean archer(PlayerAssistantEntity a) {
        return of(a) == Kind.ARCHER;
    }

    // ------------------------------------------------------------------ the shield

    static final class ShieldGoal extends Goal {

        private final PlayerAssistantEntity a;
        @Nullable private Mob foe;
        private int lastSwing, tauntAt = -1000, saidAt = -10000, noShieldSaid = -100000, pathAt;
        /** Tests: monsters it called onto itself, and blows its shield took. */
        int taunts;

        ShieldGoal(PlayerAssistantEntity a) {
            this.a = a;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private boolean mine() {
            Kind k = of(a);
            return k == Kind.SHIELD || k == Kind.NONE && defending(a) != null;
        }

        @Nullable
        private Vec3 centre() {
            Villages.Village v = defending(a);
            if (v != null) return Vec3.atCenterOf(v.centre());
            LivingEntity w = ward(a);
            return w == null ? null : w.position();
        }

        @Override
        public boolean canUse() {
            if (!mine() || a.isSleeping()) return false;
            Vec3 c = centre();
            if (c == null) return false;
            List<Mob> th = threats(a, c, defending(a) != null ? 40 : 14, ward(a));
            foe = th.isEmpty() ? null : th.get(0);
            return foe != null;
        }

        @Override
        public boolean canContinueToUse() {
            return mine() && foe != null && foe.isAlive() && !a.isSleeping() && centre() != null && foe.position().distanceTo(centre()) < 48;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            if (!armShield(a) && a.tickCount - noShieldSaid > 2400 && of(a) == Kind.SHIELD) {
                noShieldSaid = a.tickCount;
                PaTalk.comment(a, 1, PaLogiTalk.line(a, "kit.st_no_shield"), true);
            }
            a.equipBestWeapon();
            pathAt = -100;
        }

        @Override
        public void stop() {
            if (a.isUsingItem() && a.getUsedItemHand() == InteractionHand.OFF_HAND) a.stopUsingItem();
            a.getNavigation().stop();
            foe = null;
        }

        @Override
        public void tick() {
            Mob f = foe;
            Vec3 c = centre();
            if (f == null || c == null) return;
            LivingEntity w = ward(a);
            // the next foe if a nearer one goes for its ward
            if (a.tickCount % 10 == 0) {
                List<Mob> th = threats(a, c, defending(a) != null ? 40 : 14, w);
                if (!th.isEmpty() && th.get(0) != f && th.get(0).getTarget() != a) { f = th.get(0); foe = f; }
            }
            if (a.getTarget() != f) a.setTarget(f);
            // calls them onto itself: every monster near its ward turns on it
            if (a.tickCount - tauntAt >= 20) {
                tauntAt = a.tickCount;
                int turned = 0;
                for (Mob m : threats(a, c, 12, w)) {
                    if (m.getTarget() == a || m instanceof Creeper) continue;
                    m.setTarget(a);
                    turned++;
                }
                if (turned > 0) {
                    taunts += turned;
                    a.level().playSound(null, a.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL, 0.6F, 0.7F);   // the shield banged to call them
                    if (a.tickCount - saidAt > 400) { saidAt = a.tickCount; PaTalk.comment(a, 1, PaLogiTalk.line(a, "kit.st_taunt", "item", f.getName().getString().toLowerCase()), true); }
                }
            }
            a.getLookControl().setLookAt(f, 30.0F, 30.0F);
            double d = a.distanceTo(f);
            boolean shield = a.getOffhandItem().is(Items.SHIELD);
            if (d <= 2.8) {
                a.getNavigation().stop();
                if (a.tickCount - lastSwing >= 14) {                         // the shield down for the swing
                    if (a.isUsingItem()) a.stopUsingItem();
                    a.swing(InteractionHand.MAIN_HAND);
                    a.doHurtTarget(f);
                    lastSwing = a.tickCount;
                } else if (shield && a.tickCount - lastSwing >= 4 && !a.isUsingItem()) {
                    a.startUsingItem(InteractionHand.OFF_HAND);              // and straight back up
                }
                return;
            }
            if (shield && !a.isUsingItem()) a.startUsingItem(InteractionHand.OFF_HAND);
            // between its ward and the foe, a couple of blocks out from its ward
            Vec3 guard;
            if (w != null && defending(a) == null) {
                Vec3 dir = f.position().subtract(w.position()).multiply(1, 0, 1);
                guard = dir.lengthSqr() < 0.01 ? f.position() : w.position().add(dir.normalize().scale(Math.min(2.5, dir.length() * 0.5)));
                if (f.distanceTo(w) < 5 || f.getTarget() == a) guard = f.position();
            } else guard = f.position();
            if (a.getNavigation().isDone() || a.tickCount - pathAt >= 10) {
                pathAt = a.tickCount;
                a.getNavigation().moveTo(guard.x, guard.y, guard.z, 1.15);
            }
        }
    }

    // ------------------------------------------------------------------ the medic

    /** Its remedies: golden apples, healing potions, food (counted). */
    static int remedies(PlayerAssistantEntity a) {
        return a.countMatching(s -> remedy(s) > 0);
    }

    /** 3 a golden apple, 2 a healing potion, 1 food, 0 none. */
    static int remedy(ItemStack s) {
        if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) return 3;
        if ((s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) && heals(s)) return 2;
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH)) return 1;
        return 0;
    }

    static boolean heals(ItemStack s) {
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return false;
        boolean[] good = {false};
        pc.forEachEffect(e -> { if (e.getEffect().is(MobEffects.HEAL) || e.getEffect().is(MobEffects.REGENERATION)) good[0] = true; });
        return good[0];
    }

    /** Somebody hurt it can see to: you, villagers, the town's folk (the worst first). */
    @Nullable
    static LivingEntity patient(PlayerAssistantEntity a, double radius, Map<Integer, Integer> seen) {
        LivingEntity best = null;
        double worst = 0.75;
        Villages.Village town = defending(a);
        AABB box = town != null ? new AABB(town.centre()).inflate(40, 12, 40) : a.getBoundingBox().inflate(radius, 8, radius);
        for (LivingEntity e : a.level().getEntitiesOfClass(LivingEntity.class, box, x -> x.isAlive() && (villager(x) || x == a.getOwnerPlayer()))) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            Integer next = seen.get(e.getId());
            if (next != null && next > a.tickCount) continue;
            double frac = e.getHealth() / Math.max(1, e.getMaxHealth());
            if (e instanceof Player p && p.getFoodData().getFoodLevel() < 8) frac = Math.min(frac, 0.74);   // hungry: food from the medic too
            if (frac < worst) { worst = frac; best = e; }
        }
        return best;
    }

    static final class MedicGoal extends Goal {

        private final PlayerAssistantEntity a;
        @Nullable private LivingEntity who;
        private int stage, tend, pathAt, saidAt = -10000, noneSaid = -100000, pullUntil;
        @Nullable private Vec3 safe;
        private final Map<Integer, Integer> seen = new java.util.HashMap<>();
        /** Tests: patients treated, and pulled back. */
        int treated, pulled;

        MedicGoal(PlayerAssistantEntity a) {
            this.a = a;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (of(a) != Kind.MEDIC || a.isSleeping() || a.tickCount % 5 != 0) return false;
            who = patient(a, 24, seen);
            if (who != null && remedies(a) == 0) {
                if (a.tickCount - noneSaid > 1200) { noneSaid = a.tickCount; PaTalk.comment(a, 1, PaLogiTalk.line(a, "kit.st_no_remedy"), true); }
                // nothing to treat with: it can still pull the badly hurt out of a fight
                if (!(who.getHealth() / who.getMaxHealth() < 0.35 && threatOn(who) != null && who instanceof Mob)) return false;
            }
            return who != null;
        }

        @Override
        public boolean canContinueToUse() {
            return of(a) == Kind.MEDIC && who != null && who.isAlive() && stage < 9 && !a.isSleeping();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            stage = 0;
            tend = 0;
            pathAt = -100;
            safe = null;
            if (a.getTarget() != null) a.setTarget(null);
        }

        @Override
        public void stop() {
            if (a.getPose() == Pose.CROUCHING) a.setPose(Pose.STANDING);
            a.getNavigation().stop();
            if (who != null) seen.put(who.getId(), a.tickCount + 40);
            who = null;
        }

        @Nullable
        private Mob threatOn(LivingEntity e) {
            List<Mob> th = threats(a, e.position(), 6, e);
            return th.isEmpty() ? null : th.get(0);
        }

        @Override
        public void tick() {
            LivingEntity p = who;
            if (p == null) return;
            double d = a.distanceTo(p);
            a.getLookControl().setLookAt(p, 30.0F, 30.0F);
            float frac = p.getHealth() / Math.max(1, p.getMaxHealth());
            // the badly hurt with a monster on them: pulled back out of it first (a villager, who can be led)
            if (stage == 0 && frac < 0.35F && p instanceof Mob led && threatOn(p) != null) {
                Mob m = threatOn(p);
                Vec3 away = p.position().subtract(m.position()).multiply(1, 0, 1);
                Villages.Village town = defending(a) != null ? defending(a) : Villages.nearest(a.level(), p.blockPosition(), 96);
                safe = town != null && town.centre().distSqr(p.blockPosition()) > 36 ? Vec3.atBottomCenterOf(town.centre())
                    : p.position().add(away.lengthSqr() < 0.01 ? new Vec3(8, 0, 0) : away.normalize().scale(9));
                stage = 1;
                pullUntil = a.tickCount + 140;
                PaTalk.comment(a, 1, PaLogiTalk.line(a, "kit.st_pull", "item", p.getName().getString()), true);
            }
            if (stage == 1) {
                Mob led = (Mob) p;
                if (d > 2.0) { if (a.tickCount - pathAt >= 10) { pathAt = a.tickCount; a.getNavigation().moveTo(p, 1.25); } if (a.tickCount < pullUntil) return; }
                // its hand on them: they come along with it, step for step
                if (safe != null && a.position().distanceTo(safe) > 2.0 && a.tickCount < pullUntil) {
                    if (a.tickCount - pathAt >= 10) { pathAt = a.tickCount; a.getNavigation().moveTo(safe.x, safe.y, safe.z, 1.0); }
                    led.getNavigation().moveTo(a, 1.1);
                    Vec3 tow = a.position().subtract(led.position()).multiply(1, 0, 1);
                    if (tow.length() > 1.6) led.setDeltaMovement(led.getDeltaMovement().add(tow.normalize().scale(0.12)));
                    if (a.tickCount % 10 == 0) a.swing(InteractionHand.OFF_HAND);
                    return;
                }
                pulled++;
                stage = 2;
                return;
            }
            // to their side (a splash potion from a few blocks off)
            ItemStack rx = pick(frac);
            boolean splash = rx.is(Items.SPLASH_POTION) || rx.is(Items.LINGERING_POTION);
            double reach = splash ? 6.0 : 2.2;
            if (d > reach) {
                if (a.getPose() == Pose.CROUCHING) a.setPose(Pose.STANDING);
                if (a.tickCount - pathAt >= 10) { pathAt = a.tickCount; a.getNavigation().moveTo(p, 1.2); }
                return;
            }
            a.getNavigation().stop();
            if (rx.isEmpty()) { stage = 9; return; }
            if (tend == 0) {
                holdForShow(rx);
                if (!splash) { a.setPose(Pose.CROUCHING); a.playAnim("tend"); }
            }
            tend++;
            if (!splash && tend % 6 == 0) a.swing(InteractionHand.MAIN_HAND);
            if (tend < (splash ? 6 : 24)) return;
            treat(p, rx, splash);
            treated++;
            stage = 9;
            if (a.getPose() == Pose.CROUCHING) a.setPose(Pose.STANDING);
            seen.put(p.getId(), a.tickCount + 60);
        }

        /** The remedy for how hurt they are: a golden apple for the worst, a potion, food. */
        private ItemStack pick(float frac) {
            ItemStack best = ItemStack.EMPTY;
            int bestRank = -1;
            ItemStack hand = a.getMainHandItem();
            List<ItemStack> all = new ArrayList<>(a.getInventoryItems());
            all.add(hand);
            for (ItemStack s : all) {
                int r = remedy(s);
                if (r == 0) continue;
                int rank = frac < 0.35F ? r : r == 3 ? 0 : r;                // apples kept for the badly hurt
                if (rank > bestRank) { bestRank = rank; best = s; }
            }
            return best;
        }

        /** The remedy in its hand while it tends (the weapon back in the pack). */
        private void holdForShow(ItemStack rx) {
            if (a.getMainHandItem() == rx) return;
            var inv = a.getInventoryItems();
            for (int i = 0; i < inv.size(); i++) {
                if (inv.get(i) != rx) continue;
                ItemStack was = a.getMainHandItem();
                a.setItemSlot(EquipmentSlot.MAINHAND, rx);
                inv.set(i, was);
                return;
            }
        }

        private void treat(LivingEntity p, ItemStack rx, boolean splash) {
            ServerLevel level = (ServerLevel) a.level();
            String what;
            if (splash) {
                ThrownPotion tp = new ThrownPotion(level, a);
                tp.setItem(rx.copyWithCount(1));
                Vec3 to = p.getEyePosition().subtract(a.getEyePosition());
                tp.shoot(to.x, to.y + to.horizontalDistance() * 0.2, to.z, 0.75F, 2.0F);
                level.addFreshEntity(tp);
                level.playSound(null, a.blockPosition(), SoundEvents.SPLASH_POTION_THROW, SoundSource.NEUTRAL, 0.6F, 0.9F);
                rx.shrink(1);
                what = "a potion";
            } else if (remedy(rx) == 3) {
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
                p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 0));
                p.heal(4.0F);
                level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.8F, 1.0F);
                rx.shrink(1);
                what = "a golden apple";
            } else if (remedy(rx) == 2) {
                PotionContents pc = rx.get(DataComponents.POTION_CONTENTS);
                if (pc != null) pc.forEachEffect(e -> {
                    if (e.getEffect().value().isInstantenous()) e.getEffect().value().applyInstantenousEffect(a, a, p, e.getAmplifier(), 1.0);
                    else p.addEffect(new MobEffectInstance(e));
                });
                level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 0.8F, 1.0F);
                rx.shrink(1);
                a.insertItem(new ItemStack(Items.GLASS_BOTTLE));
                what = "a healing potion";
            } else {
                FoodProperties f = rx.get(DataComponents.FOOD);
                int n = f == null ? 2 : f.nutrition();
                if (p instanceof Player pl && pl.getFoodData().needsFood()) pl.getFoodData().eat(n, f == null ? 0.3F : f.saturation());
                p.heal(Math.max(2.0F, n));
                level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.8F, 1.0F);
                what = rx.getHoverName().getString().toLowerCase();
                rx.shrink(1);
            }
            if (rx.isEmpty() && a.getMainHandItem() == rx) a.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + p.getBbHeight() + 0.3, p.getZ(), 5, 0.4, 0.3, 0.4, 0.0);
            if (a.tickCount - saidAt > 300) {
                saidAt = a.tickCount;
                String name = p == a.getOwnerPlayer() ? "you" : p.getName().getString();
                PaTalk.comment(a, 1, PaLogiTalk.line(a, p == a.getOwnerPlayer() ? "kit.st_healed_you" : "kit.st_healed", "item", name, "what", what), true);
            }
        }
    }

    // ------------------------------------------------------------------ to the square, in a raid

    static final class RallyGoal extends Goal {

        private final PlayerAssistantEntity a;
        private int pathAt;

        RallyGoal(PlayerAssistantEntity a) {
            this.a = a;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            Villages.Village v = defending(a);
            return v != null && a.getTarget() == null && a.distanceToSqr(Vec3.atCenterOf(v.centre())) > 14 * 14;
        }

        @Override
        public boolean canContinueToUse() {
            Villages.Village v = defending(a);
            return v != null && a.getTarget() == null && a.distanceToSqr(Vec3.atCenterOf(v.centre())) > 8 * 8;
        }

        @Override
        public void start() {
            pathAt = -100;
        }

        @Override
        public void tick() {
            Villages.Village v = defending(a);
            if (v == null) return;
            if (a.tickCount - pathAt >= 20) {
                pathAt = a.tickCount;
                BlockPos c = v.centre();
                a.setSprinting(a.distanceToSqr(Vec3.atCenterOf(c)) > 20 * 20);
                a.getNavigation().moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0.9);
            }
        }

        @Override
        public void stop() {
            a.setSprinting(false);
        }
    }

    /** The goals, for its goal list. */
    static void register(PlayerAssistantEntity a, net.minecraft.world.entity.ai.goal.GoalSelector g) {
        g.addGoal(1, new ShieldGoal(a));
        g.addGoal(1, new MedicGoal(a));
        g.addGoal(2, new RallyGoal(a));
    }

    /** Tests: the stance goals' tallies (taunts, treated, pulled). */
    public static int[] talliesForTests(PlayerAssistantEntity a) {
        int[] out = new int[3];
        a.paGoalsForTests().forEach(g -> {
            if (g instanceof ShieldGoal s) out[0] += s.taunts;
            if (g instanceof MedicGoal m) { out[1] += m.treated; out[2] += m.pulled; }
        });
        return out;
    }
}
