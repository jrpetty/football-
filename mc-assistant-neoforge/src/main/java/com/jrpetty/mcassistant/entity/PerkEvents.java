package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import javax.annotation.Nullable;

/**
 * [perks] The perks the game's own events carry: the harm fire does a Fireproof folk and a Blaze Warden's
 * Nether-goer (half), the bite of a True Shot's arrow (a quarter more), the rod a Blaze Hunter's blaze drops, and the
 * piglins that leave a Piglin-Friend be.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PerkEvents {

    private PerkEvents() {}

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity hurt = event.getEntity();
        if (hurt.level().isClientSide) return;
        float was = event.getAmount();
        float now = was;
        if (hurt instanceof VillageFolkEntity f) now = fire(f, event.getSource(), now);
        now = arrow(event.getSource(), now);
        if (now != was) event.setAmount(now);
    }

    /** Fire's harm to a folk: half for a Fireproof folk at its trade, half for a Nether-goer of a Blaze Wardens town. */
    static float fire(VillageFolkEntity f, DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.IS_FIRE)) return amount;
        float a = amount;
        if (FolkSkills.active(f, FolkSkills.Knack.FIREPROOF)) a *= 0.5F;
        if (CityTree.blazeWarded(f.ownerId()) && (Nether.away(f) || FolkSkills.netherTrade(f.stationTask()))) a *= 0.5F;
        return a;
    }

    /** An arrow's bite: a quarter more from a True Shot (a folk at its trade with the knack). */
    static float arrow(DamageSource source, float amount) {
        if (!(source.getDirectEntity() instanceof AbstractArrow a) || !(a.getOwner() instanceof VillageFolkEntity shooter)) return amount;
        return FolkSkills.active(shooter, FolkSkills.Knack.TRUE_SHOT) ? amount * 1.25F : amount;
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Blaze blaze) || blaze.level().isClientSide) return;
        VillageFolkEntity killer = killer(event.getSource());
        if (killer == null || !FolkSkills.active(killer, FolkSkills.Knack.BLAZE_HUNTER)) return;
        event.getDrops().add(new ItemEntity(blaze.level(), blaze.getX(), blaze.getY(), blaze.getZ(), new ItemStack(Items.BLAZE_ROD)));
    }

    @Nullable
    static VillageFolkEntity killer(DamageSource source) {
        if (source.getEntity() instanceof VillageFolkEntity f) return f;
        if (source.getDirectEntity() instanceof AbstractArrow a && a.getOwner() instanceof VillageFolkEntity f) return f;
        return null;
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        LivingEntity mob = event.getEntity();
        if (!(mob instanceof AbstractPiglin) && !(mob instanceof ZombifiedPiglin)) return;
        if (event.getNewAboutToBeSetTarget() instanceof VillageFolkEntity f && friend(f, mob)) event.setCanceled(true);
    }

    /** Does a piglin leave this folk be: a Piglin-Friend, unless it has struck the piglin itself? */
    static boolean friend(VillageFolkEntity f, LivingEntity piglin) {
        return f.knacks().has(FolkSkills.Knack.PIGLIN_FRIEND) && piglin.getLastHurtByMob() != f;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the fire's harm to this folk, as the event would have it. */
    public static float fireForTests(VillageFolkEntity f, DamageSource source, float amount) {
        return fire(f, source, amount);
    }

    /** Tests: an arrow's bite, as the event would have it. */
    public static float arrowForTests(DamageSource source, float amount) {
        return arrow(source, amount);
    }

    /** Tests: would a piglin leave this folk be? */
    public static boolean friendForTests(VillageFolkEntity f, LivingEntity piglin) {
        return friend(f, piglin);
    }
}
