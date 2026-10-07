package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [fields] The bee smoker: a copper can with a spout, a leather bellows at its side, a coal smouldering in it. A puff of
 * its cool smoke over the hives calms the bees, so they never anger while the honey and the comb are taken. A player
 * squeezing it near hives calms every bee within eight blocks for thirty seconds (and a hive emptied in that time
 * gives up its comb or its honey without a sting: FieldTools); the beekeeper carries one and smokes every full hive
 * on its round. Each squeeze wears it a little (a hundred and twenty-eight in it).
 */
public class BeeSmokerItem extends Item {

    /** How far its smoke reaches, and how long the bees stay calm (ticks). */
    public static final int REACH = 8;
    public static final long CALM_FOR = 600L;

    /** Where the bees have been smoked lately: a level, a spot, till when. */
    public record Calm(ResourceKey<Level> dim, BlockPos at, long until) {}

    private static final List<Calm> CALM = new CopyOnWriteArrayList<>();

    public BeeSmokerItem(Properties properties) {
        super(properties);
    }

    /** Are the bees calm here (smoked within the last half-minute)? */
    public static boolean calm(Level level, BlockPos p) {
        long now = level.getGameTime();
        for (Calm c : CALM) {
            if (c.dim() == level.dimension() && now <= c.until() && c.at().distSqr(p) <= (REACH + 2) * (REACH + 2)) return true;
        }
        return false;
    }

    /** The calm spots still calm (the rest forgotten), for the round that keeps the bees quiet there. */
    public static List<Calm> calmNow(long now) {
        CALM.removeIf(c -> now > c.until());
        return new ArrayList<>(CALM);
    }

    public static void resetForTests() {
        CALM.clear();
    }

    /**
     * A puff over here: the smoke drifting, the angry bees round it calmed, the spot calm for a while. Returns how many
     * angry bees were calmed.
     */
    public static int puff(ServerLevel level, BlockPos at, Vec3 from) {
        CALM.add(new Calm(level.dimension(), at.immutable(), level.getGameTime() + CALM_FOR));
        int calmed = 0;
        for (Bee b : level.getEntitiesOfClass(Bee.class, new AABB(at).inflate(REACH), Bee::isAlive)) {
            if (b.isAngry()) calmed++;
            b.stopBeingAngry();
            b.setTarget(null);
        }
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, from.x, from.y, from.z, 3, 0.15, 0.1, 0.15, 0.01);
        level.sendParticles(ParticleTypes.SMOKE, from.x, from.y, from.z, 14, 0.3, 0.2, 0.3, 0.02);
        level.sendParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 0.8, at.getZ() + 0.5, 5, 0.4, 0.3, 0.4, 0.01);
        level.playSound(null, at, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.25F, 1.8F);
        level.playSound(null, at, SoundEvents.BEEHIVE_WORK, SoundSource.NEUTRAL, 0.5F, 0.7F);
        return calmed;
    }

    /** Smoke over a hive: a column of it drifting up from the entrance. */
    public static void smokeHive(ServerLevel level, BlockPos hive) {
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, hive.getX() + 0.5, hive.getY() + 1.1, hive.getZ() + 0.5, 2, 0.2, 0.05, 0.2, 0.01);
        level.sendParticles(ParticleTypes.SMOKE, hive.getX() + 0.5, hive.getY() + 0.6, hive.getZ() + 0.5, 12, 0.35, 0.35, 0.35, 0.02);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack smoker = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            BlockPos at = player.blockPosition();
            Vec3 nozzle = player.getEyePosition().add(player.getLookAngle().scale(0.8)).add(0, -0.3, 0);
            int calmed = puff(server, at, nozzle);
            int hives = 0;
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-REACH, -3, -REACH), at.offset(REACH, 4, REACH))) {
                if (!(level.getBlockState(p).getBlock() instanceof BeehiveBlock)) continue;
                smokeHive(server, p);
                hives++;
                if (hives >= 8) break;
            }
            player.displayClientMessage(Component.literal(hives == 0 && calmed == 0 ? "A puff of smoke. No bees to calm here."
                : "The bees settle" + (hives > 0 ? " round " + (hives == 1 ? "the hive" : hives + " hives") : "")
                + " for half a minute" + (calmed > 0 ? " (" + calmed + " angry bee" + (calmed == 1 ? "" : "s") + " calmed)" : "") + "."), true);
            smoker.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        }
        player.getCooldowns().addCooldown(this, 20);
        return InteractionResultHolder.sidedSuccess(smoker, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Use near hives: the bees calm for 30 s").withStyle(ChatFormatting.GRAY));
    }
}
