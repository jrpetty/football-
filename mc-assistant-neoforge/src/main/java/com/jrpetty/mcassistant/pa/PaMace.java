package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * [pa38] The mace's smash, from level 9 (the gear ladder): against something tough (a ravager, a brute, an enderman, a
 * witch: twenty-four health or more) or when told to, it gets above it and comes down on it with the mace, as a player
 * does. Up by a wind charge thrown at its feet as it jumps (the game's own blast), or, with none, by pillaring up four
 * blocks beside it; then off the top towards it, and the blow lands while it is still falling. The damage is the game's
 * own mace (MaceItem: four a block for the first three, two a block to eight, one a block beyond, with Density and
 * Breach as the enchantments say), the ground-shaking knock to everything round the target (never its player, nor
 * anyone's pets), and the fall it lands from costs nothing when the blow lands, exactly as a player's. Miss, and it
 * takes the fall like anyone. Its pillar comes down again after.
 */
final class PaMace extends Goal {

    enum Phase { READY, WIND, PILLAR, LEAP, FALL }

    static final int PILLAR = 4;

    private final PlayerAssistantEntity a;
    @Nullable private LivingEntity target;
    private Phase phase = Phase.READY;
    private int phaseAt, laid, startAt;
    private double baseY;
    @Nullable private BlockPos column;
    private boolean ordered, struck;
    private final List<BlockPos> placed = new ArrayList<>();
    /** Pillar blocks left standing after a smash: taken down once the fight is over. */
    private static final java.util.Map<PlayerAssistantEntity, List<BlockPos>> LEFT = new java.util.WeakHashMap<>();

    PaMace(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    static boolean strong(LivingEntity t) {
        return t.getMaxHealth() >= 24.0F || t.getType() == EntityType.WITHER_SKELETON || t.getType() == EntityType.WARDEN;
    }

    static int maceSlot(PlayerAssistantEntity a) {
        if (a.getMainHandItem().getItem() instanceof MaceItem) return -1;
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() instanceof MaceItem) return i;
        return -2;
    }

    static boolean hasWind(PlayerAssistantEntity a) {
        return a.countMatching(s -> s.is(Items.WIND_CHARGE)) > 0;
    }

    static boolean hasBlocks(PlayerAssistantEntity a) {
        return a.countMatching(PaTaskGoal::pillarBlock) >= PILLAR;
    }

    @Override
    public boolean canUse() {
        if (a.level().isClientSide || !a.onGround() || PaBow.drawing(a) || PaCrossbow.busy(a)) return false;
        PaField f = a.mind.field;
        int slot = maceSlot(a);
        if (slot == -2) { f.smashOrder = null; return false; }
        ItemStack mace = slot == -1 ? a.getMainHandItem() : a.getInventoryItems().get(slot);
        if (!PaLevels.canUse(a, mace)) { f.smashOrder = null; return false; }
        LivingEntity t = null;
        ordered = false;
        if (f.smashOrder != null) {
            if (a.tickCount > f.smashUntil) { f.smashOrder = null; return false; }
            Entity e = ((ServerLevel) a.level()).getEntity(f.smashOrder);
            if (e instanceof LivingEntity le && le.isAlive()) { t = le; ordered = true; }
            else { f.smashOrder = null; return false; }
        } else {
            if (a.tickCount - f.smashUntil < 200) return false;
            LivingEntity cur = a.getTarget();
            if (cur == null || !cur.isAlive() || cur instanceof Player || !strong(cur)) return false;
            t = cur;
        }
        double d = a.distanceTo(t);
        if (d > 12 || !a.hasLineOfSight(t)) return false;
        if (!hasWind(a) && !hasBlocks(a)) return false;
        if (!headroom(a.blockPosition(), hasWind(a) ? 7 : PILLAR + 3)) return false;
        target = t;
        return true;
    }

    private boolean headroom(BlockPos feet, int h) {
        for (int i = 0; i <= h; i++) {
            BlockPos p = feet.above(i);
            if (!a.level().getBlockState(p).getCollisionShape(a.level(), p).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && a.tickCount - startAt < 200 && !(phase == Phase.FALL && a.onGround() && a.tickCount - phaseAt > 4);
    }

    @Override
    public void start() {
        startAt = a.tickCount;
        struck = false;
        placed.clear();
        PaField f = a.mind.field;
        f.smashOrder = null;
        f.smashUntil = a.tickCount;
        int slot = maceSlot(a);
        if (slot >= 0) {
            ItemStack held = a.getMainHandItem().copy();
            a.setItemSlot(EquipmentSlot.MAINHAND, a.getInventoryItems().get(slot).copy());
            a.getInventoryItems().set(slot, held);
        }
        a.getNavigation().stop();
        a.playAnim("hammer");                                                // the mace tapped in its palm: everyone sees what's coming
        PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.mace_up", "item", target == null ? "it" : PaThrows.mob(target)), true);
        baseY = a.getY();
        column = a.blockPosition();
        to(hasWind(a) ? Phase.WIND : Phase.PILLAR);
    }

    private void to(Phase p) {
        phase = p;
        phaseAt = a.tickCount;
    }

    @Override
    public void stop() {
        if (!placed.isEmpty()) LEFT.computeIfAbsent(a, k -> new ArrayList<>()).addAll(placed);
        placed.clear();
        if (phase == Phase.FALL && !struck) {
            a.mind.field.smashMisses++;
            PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.mace_miss"), true);
        }
        phase = Phase.READY;
        target = null;
        a.mind.field.smashUntil = a.tickCount;
    }

    @Override
    public void tick() {
        LivingEntity t = target;
        if (t == null) return;
        a.getLookControl().setLookAt(t, 30.0F, 30.0F);
        int in = a.tickCount - phaseAt;
        switch (phase) {
            case WIND -> {
                if (in == 6) a.setDeltaMovement(a.getDeltaMovement().x, 0.42, a.getDeltaMovement().z);           // the jump
                if (in == 8) {                                                                                  // and the charge at its feet
                    a.removeMatching(s -> s.is(Items.WIND_CHARGE), 1);
                    WindCharge wc = new WindCharge(EntityType.WIND_CHARGE, a.level());
                    wc.setOwner(a);
                    wc.setPos(a.getX(), a.getY() - 0.2, a.getZ());
                    wc.setDeltaMovement(0, -1.5, 0);
                    a.level().addFreshEntity(wc);
                    a.swing(InteractionHand.OFF_HAND);
                    a.level().playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.WIND_CHARGE_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (a.getRandom().nextFloat() * 0.4F + 0.8F));
                    PaField f = a.mind.field;
                    f.impulseFrom = a.position();
                    f.impulseAt = a.tickCount;
                }
                if (in > 10 && a.getDeltaMovement().y < 0.05) to(Phase.FALL);
                if (in > 40) to(Phase.FALL);
            }
            case PILLAR -> {
                if (laid >= PILLAR) { to(Phase.LEAP); laid = 0; return; }
                if (in % 9 == 0) a.setDeltaMovement(0, 0.42, 0);                                              // jump straight up
                if (a.getY() > baseY + laid + 1.05 && column != null) {                                         // at the top of the jump: a block under it
                    BlockPos at = column.above(laid);
                    if (a.level().getBlockState(at).canBeReplaced() && placeFrom(at)) { laid++; phaseAt = a.tickCount - 8; }
                    else { to(Phase.LEAP); laid = 0; }
                }
                if (in > 60) { to(Phase.LEAP); laid = 0; }
            }
            case LEAP -> {
                Vec3 d = t.position().subtract(a.position()).multiply(1, 0, 1);
                double len = d.length();
                Vec3 h = len < 1e-3 ? Vec3.ZERO : d.scale(Math.min(0.32, len / 12.0) / len);
                a.setDeltaMovement(h.x, 0.42, h.z);                                                              // off the top, towards it
                to(Phase.FALL);
            }
            case FALL -> {
                Vec3 d = t.position().subtract(a.position()).multiply(1, 0, 1);
                double len = d.length();
                if (len > 0.3) {                                                                                 // steering in the air, as a player can
                    Vec3 v = a.getDeltaMovement();
                    Vec3 push = d.scale(0.03 / len);
                    Vec3 hv = new Vec3(v.x + push.x, 0, v.z + push.z);
                    if (hv.length() > 0.32) hv = hv.scale(0.32 / hv.length());
                    a.setDeltaMovement(hv.x, v.y, hv.z);
                }
                if (!struck && MaceItem.canSmashAttack(a) && a.getDeltaMovement().y < 0 && a.isWithinMeleeAttackRange(t)) {
                    a.swing(InteractionHand.MAIN_HAND);
                    struck = a.doHurtTarget(t);
                    target = struck ? null : t;
                }
            }
            default -> { }
        }
    }

    /** One of its pillar blocks set under it (a player's pillar: cobblestone, dirt, planks, netherrack). */
    private boolean placeFrom(BlockPos at) {
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!PaTaskGoal.pillarBlock(s) || !(s.getItem() instanceof BlockItem bi)) continue;
            BlockState st = bi.getBlock().defaultBlockState();
            if (!a.level().setBlock(at, st, 3)) return false;
            s.shrink(1);
            a.swing(InteractionHand.MAIN_HAND);
            var snd = st.getSoundType();
            a.level().playSound(null, at, snd.getPlaceSound(), SoundSource.BLOCKS, (snd.getVolume() + 1.0F) / 2.0F, snd.getPitch() * 0.8F);
            placed.add(at.immutable());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the blow itself (from its doHurtTarget)

    /** The extra a mace smash adds to this blow (0 when it isn't one): the game's own sum, from how far it has fallen. */
    static float bonus(PlayerAssistantEntity a, Entity target) {
        ItemStack held = a.getMainHandItem();
        if (!(held.getItem() instanceof MaceItem mace) || !MaceItem.canSmashAttack(a) || !PaLevels.canUse(a, held)) return 0;
        DamageSource src = a.damageSources().mobAttack(a);
        return mace.getAttackDamageBonus(target, (float) a.getAttributeValue(Attributes.ATTACK_DAMAGE), src);
    }

    /**
     * The blow landed: the game's mace, for an assistant's hand. The ground shakes round the target (the knock, double from
     * over five blocks, never its player, an assistant or anyone's pets), the smash sound, the mace a point more worn, and
     * the fall it was in costs nothing (it stops falling where it struck).
     */
    static void landed(PlayerAssistantEntity a, LivingEntity target, float bonus, float dealt) {
        if (!(a.level() instanceof ServerLevel sl)) return;
        float fall = a.fallDistance;
        PaField f = a.mind.field;
        f.smashes++;
        f.lastSmashDamage = dealt;
        f.lastSmashFall = fall;
        a.setDeltaMovement(a.getDeltaMovement().x, 0.01, a.getDeltaMovement().z);
        SoundEvent snd = target.onGround() ? (fall > 5.0F ? SoundEvents.MACE_SMASH_GROUND_HEAVY : SoundEvents.MACE_SMASH_GROUND) : SoundEvents.MACE_SMASH_AIR;
        sl.playSound(null, a.getX(), a.getY(), a.getZ(), snd, a.getSoundSource(), 1.0F, 1.0F);
        if (target.onGround()) {
            sl.levelEvent(2013, target.getOnPos(), 750);
            Player owner = a.getOwnerPlayer();
            for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(MaceItem.SMASH_ATTACK_KNOCKBACK_RADIUS))) {
                if (e == a || e == target || e.isSpectator() || e instanceof Player || e == owner || e instanceof com.jrpetty.mcassistant.entity.AssistantEntity) continue;
                if (e instanceof TamableAnimal ta && ta.isTame()) continue;
                if (e instanceof ArmorStand as && as.isMarker()) continue;
                Vec3 v = e.position().subtract(target.position());
                if (v.lengthSqr() > MaceItem.SMASH_ATTACK_KNOCKBACK_RADIUS * MaceItem.SMASH_ATTACK_KNOCKBACK_RADIUS) continue;
                double power = (MaceItem.SMASH_ATTACK_KNOCKBACK_RADIUS - v.length()) * 0.7F * (fall > 5.0F ? 2 : 1) * (1.0 - e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                if (power <= 0) continue;
                Vec3 push = v.lengthSqr() < 1e-6 ? Vec3.ZERO : v.normalize().scale(power);
                e.push(push.x, 0.7F, push.z);
            }
        }
        a.getMainHandItem().hurtAndBreak(1, a, EquipmentSlot.MAINHAND);
        a.resetFallDistance();
        f.impulseFrom = null;
        PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.mace_smash", "n", String.valueOf(Math.round(dealt)), "item", PaThrows.mob(target)), true);
        if (dealt >= 20) a.playAnim("cheer");
    }

    /**
     * Its fall from its own wind charge's lift counts only below where it was launched, as a player's does (the game
     * forgives a player the height its own wind charge gave it). Returns the distance to count, or the fall as it was.
     */
    static float fallToCount(PlayerAssistantEntity a, float fall) {
        PaField f = a.mind.field;
        if (f.impulseFrom == null) return fall;
        double from = f.impulseFrom.y;
        f.impulseFrom = null;
        if (a.tickCount - f.impulseAt > 200) return fall;
        if (from < a.getY()) return 0;
        return (float) Math.min(fall, from - a.getY());
    }

    /** Once the fight is over, its pillar comes down (a hand's pace a block, top first) and the blocks go back in its pack. */
    static void tidy(PlayerAssistantEntity a) {
        List<BlockPos> l = LEFT.get(a);
        if (l == null || l.isEmpty() || a.getTarget() != null || a.tickCount % 12 != 0) return;
        BlockPos top = l.get(l.size() - 1);
        if (top.distToCenterSqr(a.getEyePosition()) > a.reach() * a.reach()) {
            if (top.distToCenterSqr(a.position()) > 24 * 24) { l.clear(); return; }                           // wandered off: leave it be
            if (a.getNavigation().isDone()) a.getNavigation().moveTo(top.getX() + 1.5, top.getY() - PILLAR + 1, top.getZ() + 0.5, 1.0);
            return;
        }
        l.remove(l.size() - 1);
        BlockState st = a.level().getBlockState(top);
        if (st.isAir() || !PaTaskGoal.pillarBlock(new ItemStack(st.getBlock().asItem()))) return;
        a.getLookControl().setLookAt(top.getX() + 0.5, top.getY() + 0.5, top.getZ() + 0.5);
        a.swing(InteractionHand.MAIN_HAND);
        a.level().destroyBlock(top, false, a);
        ItemStack back = new ItemStack(st.getBlock().asItem());
        ItemStack left = a.insertGiven(back);
        if (!left.isEmpty()) a.spawnAtLocation(left);
    }
}
