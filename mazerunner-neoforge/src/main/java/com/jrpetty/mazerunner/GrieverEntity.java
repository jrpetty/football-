package com.jrpetty.mazerunner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Griever — the Maze's nocturnal hunter. A big, fast, tough spider-kin that
 * roams the corridors at night; its sting inflicts the "Changing" (poison,
 * weakness, nausea). Kill one and it yields a Griever Serum. Reuses the vanilla
 * spider model/renderer as a mechanical-arachnid stand-in.
 */
public class GrieverEntity extends Spider {

    public GrieverEntity(EntityType<? extends Spider> type, Level level) {
        super(type, level);
        setPersistenceRequired(); // stays through the night; the runtime clears them at dawn
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 60.0)
            .add(Attributes.MOVEMENT_SPEED, 0.33)
            .add(Attributes.ATTACK_DAMAGE, 7.0)
            .add(Attributes.FOLLOW_RANGE, 34.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.5)
            .add(Attributes.ATTACK_KNOCKBACK, 1.0);
    }

    /** The sting: on a landed hit, inflict the Changing. */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity victim) {
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 140, 0));
        }
        return hit;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        spawnAtLocation(new ItemStack(ModItems.GRIEVER_SERUM.get()));
    }
}
