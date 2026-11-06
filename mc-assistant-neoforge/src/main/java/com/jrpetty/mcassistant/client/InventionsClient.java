package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.item.ArbalestItem;
import com.jrpetty.mcassistant.item.InventionItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CrossbowItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * [tech-tree] The longbow and the arbalest drawn as they are drawn: the longbow's string pulled back in three steps as
 * the bow's is ("pull", "pulling"); the arbalest's prod bent as it is spanned, over its own longer span, and the bolt
 * laid in it once it is spanned ("charged"). The item models choose by these (models/item/longbow.json, arbalest.json).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class InventionsClient {

    private InventionsClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ResourceLocation pull = ResourceLocation.withDefaultNamespace("pull"), pulling = ResourceLocation.withDefaultNamespace("pulling"),
                charged = ResourceLocation.withDefaultNamespace("charged");
            ItemProperties.register(InventionItems.LONGBOW.get(), pull, (stack, level, e, seed) ->
                e == null || e.getUseItem() != stack ? 0.0F : (stack.getUseDuration(e) - e.getUseItemRemainingTicks()) / 20.0F);
            ItemProperties.register(InventionItems.LONGBOW.get(), pulling, (stack, level, e, seed) ->
                e != null && e.isUsingItem() && e.getUseItem() == stack ? 1.0F : 0.0F);
            ItemProperties.register(InventionItems.ARBALEST.get(), pull, (stack, level, e, seed) ->
                e == null || CrossbowItem.isCharged(stack) ? 0.0F
                    : (stack.getUseDuration(e) - e.getUseItemRemainingTicks()) / (float) Math.max(1, ArbalestItem.spanTicks(stack, e)));
            ItemProperties.register(InventionItems.ARBALEST.get(), pulling, (stack, level, e, seed) ->
                e != null && e.isUsingItem() && e.getUseItem() == stack && !CrossbowItem.isCharged(stack) ? 1.0F : 0.0F);
            ItemProperties.register(InventionItems.ARBALEST.get(), charged, (stack, level, e, seed) -> CrossbowItem.isCharged(stack) ? 1.0F : 0.0F);
        });
    }
}
