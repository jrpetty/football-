package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.UseOnContext;


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
        BlockPos spot = ctx.getClickedPos().relative(ctx.getClickedFace());

        // The same path the spawner block and the /village command take, so a
        // chartered settler is founded, kitted, kept awake and counted exactly
        // as any other — it used to have a copy of its own that had quietly
        // fallen behind the real one.
        boolean founding = Villages.nearest(level, spot, Villages.VILLAGE_RANGE * 2) == null;
        int stood = VillageFolkSpawnerBlock.raiseParty(level, spot, player.getYRot(),
            founding ? VillageFolkSpawnerBlock.foundingParty() : 1);
        if (stood == 0) {
            player.displayClientMessage(Component.literal(
                "That village is full at "
                    + com.jrpetty.mcassistant.AssistantConfig.villageGrowthCap()
                    + ". Found another further out."), true);
            return InteractionResult.CONSUME;
        }
        // Settling, choosing a trade and claiming ground all happen on the
        // folk's own agenda a moment from now — this only puts them there.

        Villages.Village v = Villages.nearest(level, spot, Villages.VILLAGE_RANGE * 2);
        int total = v == null ? stood : Villages.headcount(v.id());
        player.displayClientMessage(Component.literal(
            founding
                ? "A village of " + total + " is founded here. They will run it themselves."
                : total + " live here now."), true);
        if (!player.getAbilities().instabuild) ctx.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
}
