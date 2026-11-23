package com.jrpetty.mcassistant.pa;

import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * [pa33c-2b] Its hearts and hunger, worked each tick on the server: hearts by level (3 at level 1 to 10 at level 10,
 * from the server config), the hunger bar (PaHunger) drained by real work, healing only while fed, starving by the
 * difficulty's rules; eating like a player (the best food for the moment, never wasting a full bar, golden apples left
 * for fights), healing potions once it has the level for them.
 */
final class PaVitals {

    private PaVitals() {}

    /** Its level, 1..10, for hearts and what it may use (the round-3 grind refines this). */
    static int level(PlayerAssistantEntity a) {
        return Math.max(1, Math.min(10, a.veteranLevel()));
    }

    static void tick(PlayerAssistantEntity a) {
        PaHunger h = a.hunger;
        // hearts by level
        if (a.tickCount % 20 == 0) {
            var hp = a.getAttribute(Attributes.MAX_HEALTH);
            float want = PaHunger.maxHealth(level(a), PaConfig.startHearts(), PaConfig.maxHearts());
            if (hp != null && Math.abs(hp.getBaseValue() - want) > 0.01) {
                hp.setBaseValue(want);
                if (a.getHealth() > a.getMaxHealth()) a.setHealth(a.getMaxHealth());
            }
            levelPerks(a, level(a));                                   // [pa33d] quicker on its feet, harder hits, a longer look ahead
        }
        // work: sprinting (quicker than a walk) costs as a player's sprint does
        Vec3 now = a.position();
        if (a.lastPos != null) {
            double d = Math.hypot(now.x - a.lastPos.x, now.z - a.lastPos.z);
            if (d > 0.21 && d < 2.0) h.exhaust((float) (0.1 * d));
            else if (a.isInWater() && d > 0.01 && d < 2.0) h.exhaust((float) (0.01 * d));
        }
        a.lastPos = now;
        int diff = a.level().getDifficulty() == Difficulty.PEACEFUL ? 0 : a.level().getDifficulty() == Difficulty.EASY ? 1
            : a.level().getDifficulty() == Difficulty.NORMAL ? 2 : 3;
        boolean regen = a.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        float change = h.tick(diff, a.getHealth(), a.getMaxHealth(), regen);
        if (change > 0) a.heal(change);
        else if (change < 0) a.hurt(a.damageSources().starve(), -change);
        if (a.tickCount % 20 == 7) {
            if (h.wantsFood(a.getHealth(), a.getMaxHealth()) && (a.getTarget() == null || h.food() <= 6)) eat(a);
            if (level(a) >= 5 && a.getHealth() < a.getMaxHealth() * 0.35F) potion(a);
            a.syncFood();
        }
    }

    /** The food for now: hurt, the most filling; otherwise what fills the bar without waste. Rotten food only when starving. */
    static int bestFood(PlayerAssistantEntity a, boolean hurt, int room, boolean starving) {
        int best = -1;
        double bestScore = -1e9;
        for (int i = 0; i < a.getInventoryItems().size(); i++) {
            ItemStack s = a.getInventoryItems().get(i);
            FoodProperties fp = s.isEmpty() ? null : s.get(DataComponents.FOOD);
            if (fp == null) continue;
            if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) continue;     // kept for a fight
            boolean bad = s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO) || s.is(Items.PUFFERFISH)
                || s.is(Items.SUSPICIOUS_STEW);
            if (bad && !starving) continue;
            double score = hurt ? fp.saturation() * 2 + fp.nutrition() : fp.nutrition() <= room ? 10 + fp.saturation() : 10 - (fp.nutrition() - room) * 2;
            if (bad) score -= 50;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    static boolean eat(PlayerAssistantEntity a) {
        PaHunger h = a.hunger;
        int slot = bestFood(a, a.getHealth() < a.getMaxHealth(), PaHunger.MAX_FOOD - h.food(), h.food() <= 2);
        if (slot < 0) return false;
        ItemStack s = a.getInventoryItems().get(slot);
        FoodProperties fp = s.get(DataComponents.FOOD);
        if (fp == null) return false;
        a.level().playSound(null, a.getX(), a.getY(), a.getZ(), a.getEatingSound(s), SoundSource.NEUTRAL, 0.7F, 0.9F + a.getRandom().nextFloat() * 0.2F);
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        h.eat(fp.nutrition(), fp.saturation());
        for (FoodProperties.PossibleEffect e : fp.effects()) if (a.getRandom().nextFloat() < e.probability()) a.addEffect(e.effect());
        s.shrink(1);
        if (s.isEmpty()) a.getInventoryItems().set(slot, ItemStack.EMPTY);
        a.syncFood();
        return true;
    }

    /** A healing or regeneration potion from its pack, once it is level 5 or more. */
    static boolean potion(PlayerAssistantEntity a) {
        for (int i = 0; i < a.getInventoryItems().size(); i++) {
            ItemStack s = a.getInventoryItems().get(i);
            if (!s.is(Items.POTION)) continue;
            PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
            if (pc == null) continue;
            boolean[] heals = {false};
            pc.forEachEffect(e -> { if (e.getEffect() == MobEffects.HEAL || e.getEffect() == MobEffects.REGENERATION) heals[0] = true; });
            if (!heals[0]) continue;
            pc.forEachEffect(e -> {
                if (e.getEffect().value().isInstantenous()) e.getEffect().value().applyInstantenousEffect(null, null, a, e.getAmplifier(), 1.0);
                else a.addEffect(e);
            });
            a.getInventoryItems().set(i, new ItemStack(Items.GLASS_BOTTLE));
            a.level().playSound(null, a.getX(), a.getY(), a.getZ(), net.minecraft.sounds.SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 0.7F, 1.0F);
            return true;
        }
        return false;
    }

    private static final net.minecraft.resources.ResourceLocation LEVEL_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "pa_level");

    /** [pa33d] What its level does to its body: walking speed, damage, how far it plans a path. */
    static void levelPerks(PlayerAssistantEntity a, int lv) {
        perk(a, Attributes.MOVEMENT_SPEED, PaLevels.speedPercent(lv) / 100.0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        perk(a, Attributes.ATTACK_DAMAGE, PaLevels.damage(lv), net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        perk(a, Attributes.FOLLOW_RANGE, PaLevels.followRange(lv) - 32, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
    }

    private static void perk(PlayerAssistantEntity a, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double amount,
                             net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation op) {
        var inst = a.getAttribute(attr);
        if (inst == null) return;
        var cur = inst.getModifier(LEVEL_ID);
        if (cur != null && Math.abs(cur.amount() - amount) < 1e-6) return;
        inst.removeModifier(LEVEL_ID);
        if (amount != 0) inst.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(LEVEL_ID, amount, op));
    }
}
