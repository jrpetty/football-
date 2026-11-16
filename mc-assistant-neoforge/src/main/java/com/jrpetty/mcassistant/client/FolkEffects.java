package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AnimConfig;
import com.jrpetty.mcassistant.entity.FolkAction;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.entity.Manner;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * [anim] What a folk's work and life give off, on the client, from its act: sparks off the anvil as the hammer lands,
 * chips off the log, stone dust off the rock face, clods of earth off the hoe, the splash of the cast, flour off the dough,
 * steam off the pot, smoke round the hive, the enchanter's glyphs, crumbs as it eats, notes off the lute, sweat off a tired
 * worker's brow, a tear at the grave, angry puffs in a quarrel; and a quiet sound to some of it (the ring of the anvil,
 * the knock of the axe), so a working town is heard as well as seen.
 *
 * <p>Cheap: worked out once a tick for a folk the renderer is drawing anyway (so a folk out of sight, or behind the
 * camera, costs nothing), only within the player's range (AnimConfig, 24 blocks by default; SOME halves it and keeps one
 * in three; NONE none), no allocation but the particles' own options, made once.
 */
public final class FolkEffects {

    private FolkEffects() {}

    private static final BlockParticleOption WOOD = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LOG.defaultBlockState());
    private static final BlockParticleOption PLANK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState());
    private static final BlockParticleOption STONE = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    private static final BlockParticleOption DIRT = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    private static final BlockParticleOption BRICK = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BRICKS.defaultBlockState());
    private static final BlockParticleOption WOOL = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.WHITE_WOOL.defaultBlockState());
    private static final BlockParticleOption GRAVEL = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GRAVEL.defaultBlockState());
    private static ItemParticleOption wheat, seeds, bread;

    /** From FolkRenderer.render: once a tick for each folk being drawn. */
    public static void tick(VillageFolkEntity f, double distSq) {
        FolkAction.Seen s = f.animSeen();
        if (s.fxTick == f.tickCount) return;
        s.fxTick = f.tickCount;
        AnimConfig.Effects how = AnimConfig.effects();
        if (how == AnimConfig.Effects.NONE || f.isInvisible()) return;
        int range = how == AnimConfig.Effects.SOME ? AnimConfig.range() / 2 : AnimConfig.range();
        if (distSq > (double) range * range) return;
        Act act = Act.of(s.act);
        Level level = f.level();
        RandomSource r = f.getRandom();
        boolean few = how == AnimConfig.Effects.SOME;
        // The moments the clip's work lands, crossed since the last tick.
        FolkAnimations.Clip c = act == Act.NONE ? null : FolkAnimations.clip(act);
        if (c != null && c.hits.length > 0) {
            float now = FolkAnimations.secondsFor(f, c, f.tickCount);
            float was = s.fxSec;
            s.fxSec = now;
            if (was >= 0.0F && s.fxAct == s.act) {
                for (float h : c.hits) {
                    boolean crossed = now >= was ? h > was && h <= now : h > was || h <= now;   // a loop come round again
                    if (crossed && (!few || r.nextInt(3) == 0)) hit(f, act, level, r);
                }
            }
            s.fxAct = s.act;
        } else {
            s.fxSec = -1.0F;
        }
        if (!few || r.nextInt(3) == 0) steady(f, act, level, r);
    }

    /** Where its hands are at their work: in front of it at the height of its waist (a little over for a grown folk). */
    private static double fx(VillageFolkEntity f, double ahead) {
        return f.getX() - Mth.sin(f.yBodyRot * Mth.DEG_TO_RAD) * ahead;
    }

    private static double fz(VillageFolkEntity f, double ahead) {
        return f.getZ() + Mth.cos(f.yBodyRot * Mth.DEG_TO_RAD) * ahead;
    }

    private static void burst(Level level, ParticleOptions p, double x, double y, double z, int n, RandomSource r, double spread, double up) {
        for (int i = 0; i < n; i++) {
            level.addParticle(p, x + (r.nextDouble() - 0.5) * 0.2, y + (r.nextDouble() - 0.5) * 0.15, z + (r.nextDouble() - 0.5) * 0.2,
                (r.nextDouble() - 0.5) * spread, r.nextDouble() * up, (r.nextDouble() - 0.5) * spread);
        }
    }

    private static void sound(VillageFolkEntity f, SoundEvent e, float volume, float pitch) {
        f.level().playLocalSound(f.getX(), f.getY() + 1.0, f.getZ(), e, SoundSource.NEUTRAL, volume, pitch * (0.92F + f.getRandom().nextFloat() * 0.16F), false);
    }

    /** The work landing: the hammer on the anvil, the axe in the log. */
    private static void hit(VillageFolkEntity f, Act act, Level level, RandomSource r) {
        float k = f.isBaby() ? 0.5F : 1.0F;
        double waist = f.getY() + 0.9 * k, x = fx(f, 0.75), z = fz(f, 0.75);
        switch (act) {
            case HAMMER, REPAIR -> {
                double y = act == Act.REPAIR ? f.getY() + 1.3 : waist;
                burst(level, ParticleTypes.SMALL_FLAME, x, y, z, 3, r, 0.12, 0.08);
                burst(level, ParticleTypes.ELECTRIC_SPARK, x, y, z, 2, r, 0.2, 0.12);
                sound(f, SoundEvents.ANVIL_PLACE, 0.12F, 1.8F);
            }
            case CHOP -> {
                burst(level, WOOD, x, f.getY() + 1.2, z, 5, r, 0.2, 0.15);
                sound(f, SoundEvents.WOOD_HIT, 0.5F, 0.9F);
            }
            case PICK, CHISEL -> {
                burst(level, STONE, x, act == Act.PICK ? f.getY() + 1.1 : waist, z, 5, r, 0.2, 0.15);
                sound(f, SoundEvents.STONE_HIT, 0.45F, act == Act.CHISEL ? 1.4F : 1.0F);
            }
            case DIG, HOE, PLANT, WATER -> {
                double dx = fx(f, act == Act.DIG ? 0.6 : 0.9), dz = fz(f, act == Act.DIG ? 0.6 : 0.9);
                if (act == Act.WATER) burst(level, ParticleTypes.SPLASH, dx, f.getY() + 0.1, dz, 4, r, 0.1, 0.1);
                else burst(level, DIRT, dx, f.getY() + 0.1, dz, act == Act.PLANT ? 2 : 4, r, 0.15, 0.2);
                if (act != Act.WATER) sound(f, SoundEvents.ROOTED_DIRT_HIT, 0.35F, 1.0F);
            }
            case HARVEST -> {
                if (wheat == null) wheat = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.WHEAT));
                burst(level, wheat, fx(f, 0.8), f.getY() + 0.3, fz(f, 0.8), 3, r, 0.1, 0.12);
                sound(f, SoundEvents.CROP_BREAK, 0.4F, 1.1F);
            }
            case FISH -> {
                double fx2 = fx(f, 3.5), fz2 = fz(f, 3.5);
                burst(level, ParticleTypes.SPLASH, fx2, f.getY() - 0.3, fz2, 8, r, 0.1, 0.12);
                burst(level, ParticleTypes.FISHING, fx2, f.getY() - 0.3, fz2, 3, r, 0.05, 0.05);
            }
            case KNEAD -> burst(level, ParticleTypes.WHITE_ASH, x, waist, z, 4, r, 0.08, 0.05);
            case SAW -> {
                burst(level, PLANK, x, waist, z, 3, r, 0.1, 0.05);
                sound(f, SoundEvents.WOOD_HIT, 0.25F, 1.6F);
            }
            case BUILD -> {
                burst(level, BRICK, fx(f, 0.9), f.getY() + 1.4, fz(f, 0.9), 3, r, 0.1, 0.1);
                sound(f, SoundEvents.STONE_PLACE, 0.35F, 1.1F);
            }
            case SWEEP -> burst(level, GRAVEL, fx(f, 0.6), f.getY() + 0.1, fz(f, 0.6), 3, r, 0.12, 0.08);
            case COUNT_COINS -> burst(level, ParticleTypes.WAX_ON, x, waist, z, 1, r, 0.02, 0.02);
            case MILK -> burst(level, ParticleTypes.WHITE_ASH, x, f.getY() + 0.5, z, 2, r, 0.02, 0.0);
            case SHEAR -> burst(level, WOOL, fx(f, 0.9), waist, fz(f, 0.9), 4, r, 0.12, 0.1);
            case FEED -> {
                if (seeds == null) seeds = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.WHEAT_SEEDS));
                burst(level, seeds, fx(f, 0.6), f.getY() + 1.1, fz(f, 0.6), 6, r, 0.25, 0.1);
            }
            case SMOKE_HIVE -> burst(level, ParticleTypes.SMOKE, x, waist + 0.2, z, 3, r, 0.04, 0.03);
            case STOKE -> {
                burst(level, ParticleTypes.SMALL_FLAME, x, waist - 0.2, z, 2, r, 0.05, 0.05);
                burst(level, ParticleTypes.SMOKE, x, waist, z, 2, r, 0.02, 0.05);
            }
            case STOCK -> sound(f, SoundEvents.WOOD_PLACE, 0.3F, 1.0F);
            case TINKER -> burst(level, DustParticleOptions.REDSTONE, x, waist, z, 3, r, 0.05, 0.05);
            case EAT -> {
                ItemStack food = f.getMainHandItem().isEmpty() ? FolkActionLayer.mainOf(Act.EAT) : f.getMainHandItem();
                if (bread == null || bread.getItem() != food) bread = new ItemParticleOption(ParticleTypes.ITEM, food);
                double mouth = f.getEyeY() - 0.2 * k;
                burst(level, bread, fx(f, 0.35), mouth, fz(f, 0.35), 4, r, 0.06, 0.04);
                sound(f, SoundEvents.GENERIC_EAT, 0.3F, 1.1F);
            }
            case LUTE, DRUM, DANCE -> level.addParticle(ParticleTypes.NOTE, f.getX() + (r.nextDouble() - 0.5) * 0.6, f.getY() + 2.1 * k,
                f.getZ() + (r.nextDouble() - 0.5) * 0.6, r.nextInt(25) / 24.0, 0.0, 0.0);
            case ROW -> burst(level, ParticleTypes.SPLASH, fx(f, 0.2) + Mth.cos(f.yBodyRot * Mth.DEG_TO_RAD) * 1.1, f.getY(),
                fz(f, 0.2) + Mth.sin(f.yBodyRot * Mth.DEG_TO_RAD) * 1.1, 5, r, 0.1, 0.1);
            case THRUST -> sound(f, SoundEvents.PLAYER_ATTACK_SWEEP, 0.15F, 1.4F);
            case CLAP -> {
                if (r.nextInt(3) == 0) sound(f, SoundEvents.WOOL_HIT, 0.2F, 1.8F);
            }
            default -> { }
        }
    }

    /** What goes on all the while: steam off the pot, the glyphs over the book, a tear, a puff of temper, sweat. */
    private static void steady(VillageFolkEntity f, Act act, Level level, RandomSource r) {
        int t = f.tickCount + f.getId();
        float k = f.isBaby() ? 0.5F : 1.0F;
        double waist = f.getY() + 0.9 * k;
        switch (act) {
            case STIR_POT -> { if (t % 6 == 0) level.addParticle(ParticleTypes.WHITE_SMOKE, fx(f, 0.8), waist + 0.15, fz(f, 0.8), 0.0, 0.04, 0.0); }
            case BREW -> {
                if (t % 9 == 0) level.addParticle(ParticleTypes.WITCH, fx(f, 0.8), waist + 0.2, fz(f, 0.8), 0.0, 0.05, 0.0);
                if (t % 5 == 0) level.addParticle(ParticleTypes.BUBBLE_POP, fx(f, 0.8), waist, fz(f, 0.8), 0.0, 0.02, 0.0);
            }
            case ENCHANT -> {
                if (t % 3 == 0) level.addParticle(ParticleTypes.ENCHANT, fx(f, 0.9) + (r.nextDouble() - 0.5), waist + 0.6, fz(f, 0.9) + (r.nextDouble() - 0.5),
                    (r.nextDouble() - 0.5) * 0.5, -0.4, (r.nextDouble() - 0.5) * 0.5);
            }
            case SMOKE_HIVE -> { if (t % 7 == 0) level.addParticle(ParticleTypes.SMOKE, fx(f, 0.7), waist + 0.2, fz(f, 0.7), 0.0, 0.02, 0.0); }
            case MOURN -> { if (t % 50 == 0) level.addParticle(ParticleTypes.FALLING_WATER, fx(f, 0.25), f.getEyeY() - 0.5, fz(f, 0.25), 0.0, 0.0, 0.0); }
            case QUARREL, PROTEST -> { if (t % 30 == 0) level.addParticle(ParticleTypes.ANGRY_VILLAGER, f.getX(), f.getY() + 2.2 * k, f.getZ(), 0.0, 0.0, 0.0); }
            case SWIM -> { if (t % 10 == 0) level.addParticle(ParticleTypes.SPLASH, f.getX(), f.getY() + 0.9, f.getZ(), 0.0, 0.05, 0.0); }
            case FALL -> {
                FolkAction.Seen s = f.animSeen();
                if (f.tickCount - s.start == 1) burst(level, ParticleTypes.POOF, f.getX(), f.getY() + 0.1, f.getZ(), 6, r, 0.08, 0.02);
            }
            case PRAY -> { if (t % 40 == 0) level.addParticle(ParticleTypes.END_ROD, f.getX(), f.getY() + 1.9 * k, f.getZ(), 0.0, 0.01, 0.0); }
            default -> { }
        }
        // Sweat off the brow of a tired folk at heavy work.
        if (act.work() && Manner.gaitOf(f.clientManner()) == Manner.TIRED && t % 25 == 0) {
            level.addParticle(ParticleTypes.FALLING_WATER, f.getX() + (r.nextDouble() - 0.5) * 0.3, f.getEyeY() + 0.1, f.getZ() + (r.nextDouble() - 0.5) * 0.3, 0.0, 0.0, 0.0);
        }
    }
}
