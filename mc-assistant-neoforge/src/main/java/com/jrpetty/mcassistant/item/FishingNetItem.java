package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.Fleet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [itemaudit] The fishing fleet's net (entity/Fleet) in a player's own hands. The fleet's boats cast it at sea; a player
 * casts it over open water, from a boat or off the bank, and it comes up with what a boat's net does, two to four fish
 * a haul (Fleet.netHaul), and wears a little with each as the fleet's does: five string knotted, ninety-six hauls. A
 * cast takes a while to gather the shoal (ten seconds between hauls), and a net wants room to open out: the spot cast
 * at and most of the water round it.
 */
public class FishingNetItem extends Item {

    /** Ticks between one haul and the next. */
    static final int BETWEEN = 200;

    public FishingNetItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack net = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) return InteractionResultHolder.pass(net);
        BlockPos at = hit.getBlockPos();
        if (!openWater(level, at)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("A net wants open water to spread in: cast it over a pond, a river or the sea."), true);
            }
            return InteractionResultHolder.fail(net);
        }
        if (level instanceof ServerLevel server) {
            List<ItemStack> haul = Fleet.netHaul(server, player.getRandom());
            for (ItemStack s : haul) {
                ItemStack one = s.copy();
                if (!player.getInventory().add(one)) player.drop(one, false);
            }
            server.playSound(null, at, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.PLAYERS, 0.9F, 0.6F);
            server.sendParticles(ParticleTypes.SPLASH, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 24, 1.0, 0.1, 1.0, 0.1);
            net.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
            player.awardStat(Stats.ITEM_USED.get(this));
            player.displayClientMessage(Component.literal("The net comes up with " + words(haul) + "."), true);
        }
        player.getCooldowns().addCooldown(this, BETWEEN);
        return InteractionResultHolder.sidedSuccess(net, level.isClientSide());
    }

    /** Water a net can open out in: the spot cast at, and seven of the nine round it on its level. */
    public static boolean openWater(Level level, BlockPos at) {
        if (!level.getFluidState(at).is(FluidTags.WATER)) return false;
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getFluidState(at.offset(dx, 0, dz)).is(FluidTags.WATER)) n++;
            }
        }
        return n >= 7;
    }

    /** "3 raw cod and a raw salmon". */
    static String words(List<ItemStack> haul) {
        java.util.Map<Item, Integer> counted = new java.util.LinkedHashMap<>();
        for (ItemStack s : haul) counted.merge(s.getItem(), s.getCount(), Integer::sum);
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<Item, Integer> e : counted.entrySet()) {
            String name = new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(Locale.ROOT);
            out.add(e.getValue() > 1 ? e.getValue() + " " + name : (name.matches("^[aeiou].*") ? "an " : "a ") + name);
        }
        if (out.isEmpty()) return "nothing but weed";
        if (out.size() == 1) return out.get(0);
        return String.join(", ", out.subList(0, out.size() - 1)) + " and " + out.get(out.size() - 1);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Cast it over open water for a haul of fish.").withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(Component.literal("The fleet's boats carry one each.").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
}
