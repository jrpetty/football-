package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [pa35] The work clock: the Player AI Assistant works at a real player's pace, and so does anything built on it
 * (its chest and logistics jobs too). One place, so every job agrees.
 *
 * <p><b>Breaking</b> takes exactly what it takes a player: the game's own destroy-speed maths for the block, the tool
 * in hand (its tier, the correct-tool rule, Efficiency), Haste and Mining Fatigue, the underwater penalty (unless Aqua
 * Affinity) and the off-the-ground penalty. No level buff or debuff: a level-10 assistant digs stone with an iron pick
 * exactly as fast as a level-1 one, and as you would.
 *
 * <p><b>Hand actions</b> (placing a block, planting, bone meal, a stack in or out of a chest, tilling, shearing,
 * feeding, lighting a torch, a craft at the bench) go at a hand's pace: about one every 0.6 s, jittered between 0.5 and
 * 0.75 s, never quicker, however many of the same in a row. A new assistant hesitates a little (to about 0.8 s on
 * average at level 1); practice takes that off, never the cap. Levels improve planning, aim and capability instead.
 *
 * <p>Pure maths at the top (unit-tested); the entity's view below. Logistics code should call {@link #hand} for the
 * gap after each action and {@link #breakTicks} for each block, and nothing else.
 */
public final class PaPace {

    private PaPace() {}

    /** A hand action's gap, in ticks: never under 10 (0.5 s); at level 10 between 10 and 15 (about 0.6 s on average). */
    public static final int HAND_MIN = PaHand.MIN, HAND_MAX = PaHand.MAX;
    /** At level 1 a little hesitation stretches the top of the range to this (an average of about 0.8 s). */
    public static final int HAND_MAX_L1 = PaHand.MAX_L1;
    /** Ticks between one block broken and the next swing at a block, as a player's (the game's own destroy delay). */
    public static final int BREAK_DELAY = 5;

    // ------------------------------------------------------------------ pure maths (the game's, written out)

    /**
     * A player's dig speed with this tool speed and these circumstances (Player.getDestroySpeed): the tool's speed,
     * plus the Efficiency bonus (level squared plus one) when the tool suits the block; Haste or Conduit Power a fifth
     * quicker per level; Mining Fatigue 0.3, 0.09, 0.0027, 0.00081; times the break-speed attribute; under water times
     * the submerged speed (0.2, or 1 with Aqua Affinity); a fifth off the ground.
     */
    public static float digSpeed(float toolSpeed, int efficiency, int haste, int fatigue, float breakSpeedAttr, boolean submerged,
                                 float submergedSpeed, boolean onGround) {
        float f = toolSpeed;
        if (f > 1.0F && efficiency > 0) f += efficiency * efficiency + 1;
        if (haste > 0) f *= 1.0F + haste * 0.2F;
        if (fatigue > 0) f *= switch (fatigue) {
            case 1 -> 0.3F;
            case 2 -> 0.09F;
            case 3 -> 0.0027F;
            default -> 8.1E-4F;
        };
        f *= breakSpeedAttr;
        if (submerged) f *= submergedSpeed;
        if (!onGround) f /= 5.0F;
        return f;
    }

    /**
     * Ticks to break a block (BlockBehaviour.getDestroyProgress, summed a tick at a time as the player's game does):
     * progress per tick is speed / hardness / (30 with the right tool for its drops, else 100). Unbreakable: never
     * (Integer.MAX_VALUE). A block of no hardness (a flower, a crop) breaks at once (1 tick: a hand action).
     */
    public static int ticksToBreak(float hardness, float digSpeed, boolean correctForDrops) {
        if (hardness < 0) return Integer.MAX_VALUE;
        if (hardness == 0) return 1;
        if (digSpeed <= 0) return Integer.MAX_VALUE;
        float perTick = digSpeed / hardness / (correctForDrops ? 30.0F : 100.0F);
        if (perTick >= 1.0F) return 1;
        // the game adds the progress up in floats each tick and breaks at >= 1: count the same way, so we agree to the tick
        float sum = 0;
        int ticks = 0;
        while (sum < 1.0F && ticks < 100000) { sum += perTick; ticks++; }
        return ticks;
    }

    /** A hand action's gap, in ticks, at this level: 10 to 15 at level 10, stretching to 10 to 22 at level 1. */
    public static int handTicks(RandomSource r, int level) {
        int top = handTop(level);
        return HAND_MIN + r.nextInt(top - HAND_MIN + 1);
    }

    /** The slowest a hand action's gap can draw at this level (PaHand). */
    public static int handTop(int level) {
        return PaHand.top(level);
    }

    /** The average gap, in seconds, at this level (PaHand). */
    public static double handSeconds(int level) {
        return PaHand.seconds(level);
    }

    // ------------------------------------------------------------------ the entity's view

    /** Ticks for this entity to break this block, as a player in its place with its main-hand tool. */
    public static int breakTicks(LivingEntity e, BlockState state, BlockGetter level, BlockPos pos) {
        ItemStack tool = e.getMainHandItem();
        float hardness = state.getDestroySpeed(level, pos);
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        int eff = enchant(e, tool, Enchantments.EFFICIENCY);
        int haste = 0;
        if (e.hasEffect(MobEffects.DIG_SPEED)) haste = e.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1;
        if (e.hasEffect(MobEffects.CONDUIT_POWER)) haste = Math.max(haste, e.getEffect(MobEffects.CONDUIT_POWER).getAmplifier() + 1);
        int fatigue = e.hasEffect(MobEffects.DIG_SLOWDOWN) ? e.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier() + 1 : 0;
        float breakAttr = (float) attr(e, Attributes.BLOCK_BREAK_SPEED, 1.0);
        boolean aqua = enchant(e, e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD), Enchantments.AQUA_AFFINITY) > 0;
        float submergedSpeed = (float) attr(e, Attributes.SUBMERGED_MINING_SPEED, aqua ? 1.0 : 0.2);
        boolean submerged = e.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        // the Efficiency bonus is the mining-efficiency attribute for a player; a mob has none of its own, so it's read off the tool
        // off the ground as a player is: in the air on the way up or down (a mob just set down reads "not on ground" for a tick)
        boolean grounded = e.onGround() || Math.abs(e.getDeltaMovement().y) < 1e-3 || e.isPassenger();
        float speed = digSpeed(tool.getDestroySpeed(state), eff, haste, fatigue, breakAttr, submerged, submergedSpeed, grounded);
        return ticksToBreak(hardness, speed, correct);
    }

    /** The gap after a hand action, for this assistant (its level's hesitation, never under the cap). */
    public static int hand(PlayerAssistantEntity a) {
        return handTicks(a.getRandom(), a.veteranLevel());
    }

    private static int enchant(LivingEntity e, ItemStack s, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        if (s.isEmpty() || !s.isEnchanted()) return 0;
        return e.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolder(key)
            .map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, s)).orElse(0);
    }

    private static double attr(LivingEntity e, Holder<Attribute> a, double fallback) {
        var inst = e.getAttribute(a);
        return inst == null ? fallback : inst.getValue();
    }

    // ------------------------------------------------------------------ the crack overlay, as a player's digging shows

    /** A block being broken: where, when it began, how long it takes. */
    public static final class Dig {
        BlockPos pos;
        long began;
        int ticks;
        int stage = -1;
    }

    /** It has begun breaking a block that takes this long (its work goal asked the clock). */
    public static void began(PlayerAssistantEntity a, int ticks) {
        if (a.mind.dig == null) a.mind.dig = new Dig();
        Dig d = a.mind.dig;
        d.pos = null;
        d.began = a.level().getGameTime();
        d.ticks = Math.max(1, ticks);
    }

    /** The block it is hitting (each swing tells the clock where). */
    public static void hitting(PlayerAssistantEntity a, BlockPos pos) {
        Dig d = a.mind.dig;
        if (d == null) return;
        if (d.pos == null || !d.pos.equals(pos)) {
            if (d.pos != null) clear(a, d);
            d.pos = pos.immutable();
        }
    }

    /** Every tick: the cracks drawn as the work goes (players near see them, as they would a player's). */
    public static void tick(PlayerAssistantEntity a) {
        Dig d = a.mind.dig;
        if (d == null || d.pos == null || !(a.level() instanceof ServerLevel level)) return;
        long gone = level.getGameTime() - d.began;
        if (level.getBlockState(d.pos).isAir() || gone > d.ticks + 40 || a.distanceToSqr(d.pos.getX() + 0.5, d.pos.getY() + 0.5, d.pos.getZ() + 0.5) > 64) {
            clear(a, d);
            a.mind.dig = null;
            return;
        }
        int stage = (int) Math.min(9, gone * 10 / d.ticks);
        if (stage != d.stage) {
            d.stage = stage;
            level.destroyBlockProgress(a.getId(), d.pos, stage);
        }
    }

    private static void clear(PlayerAssistantEntity a, Dig d) {
        if (d.pos != null && a.level() instanceof ServerLevel level) level.destroyBlockProgress(a.getId(), d.pos, -1);
        d.stage = -1;
    }
}
