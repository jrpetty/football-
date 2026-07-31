package com.jrpetty.mazerunner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.level.block.state.BlockState;

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
            level().playSound(null, this, SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 1.6F, 0.6F);
        }
        return hit;
    }

    // ------------------------------------------------------------- voice

    /**
     * Roars once when it first locks onto a runner — the moment you learn you
     * have been found. Resets when it loses the scent so it can roar again.
     */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (getTarget() != null) {
            if (!roaring) {
                roaring = true;
                // volume 3 → audible ~48 blocks, so it carries down the corridors
                level().playSound(null, this, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0F, 0.65F);
            }
        } else {
            roaring = false;
        }
    }

    private boolean roaring;

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    /** Heavy metallic footfalls — you can hear one coming round a corner. */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.IRON_GOLEM_STEP, 0.7F, 0.75F);
    }

    @Override
    protected float getSoundVolume() {
        return 1.4F;
    }

    @Override
    public float getVoicePitch() {
        return 0.62F; // well below the vanilla source — bigger, slower, wrong
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        spawnAtLocation(new ItemStack(ModItems.GRIEVER_SERUM.get()));
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer killer) {
            MazeAdvancements.award(killer, MazeAdvancements.GRIEVER_SLAYER);
        }
    }
}
