package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.UseOnContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Village Charter — right-click the ground and somebody comes to live there.
 *
 * <p>The first use founds a settlement on that spot. Every use after it adds
 * another pair of hands to whichever settlement is nearest, up to a full
 * village of ten. Nothing else is required of you: they pick their own trades
 * to fill out the village, find their own ground, and build the place up in
 * their own time.
 */
public class VillageCharterItem extends Item {

    public VillageCharterItem(Properties properties) {
        super(properties.stacksTo(16).rarity(Rarity.UNCOMMON));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(ctx.getLevel() instanceof ServerLevel level)
            || !(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        BlockPos spot = ctx.getClickedPos().above();

        Villages.Village village = Villages.nearest(level, spot);
        boolean founding = village == null;
        if (founding) village = Villages.found(level, spot);

        int headcount = Villages.headcount(village.id());
        if (headcount >= Villages.VILLAGE_SIZE) {
            player.displayClientMessage(Component.literal(
                "That village is full — ten is a village. Found another further out."), true);
            return InteractionResult.CONSUME;
        }

        VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (folk == null) return InteractionResult.CONSUME;
        folk.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(), 0.0F);
        folk.rename(freshName(village.id()));
        level.addFreshEntity(folk);
        // Settling, choosing a trade and claiming ground all happen on the
        // folk's own agenda a moment from now — this only puts them there.

        player.displayClientMessage(Component.literal(
            founding
                ? "A village is founded here. They'll sort themselves out."
                : Villages.folkOf(village.id()).size() + " of ten have settled here."), true);
        if (!player.getAbilities().instabuild) ctx.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }

    /** A name nobody in this village is using yet. */
    private static String freshName(java.util.UUID villageId) {
        Set<String> used = new HashSet<>();
        for (AssistantEntity a : Villages.folkOf(villageId)) {
            used.add(a.getAssistantName().toLowerCase());
        }
        List<String> pool = Names.POOL;
        for (String candidate : pool) {
            if (!used.contains(candidate.toLowerCase())) return candidate;
        }
        return "folk_" + (used.size() + 1);
    }

}
