package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.item.KiteItem;
import com.jrpetty.mcassistant.item.LeisureItems;
import net.minecraft.util.FastColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * [leisure] The client's side of home and play (item/LeisureItems): the football rolling on the grass
 * (FootballRenderer), the kite in the sky on its string (KiteRenderer), the pieces on a draughts board
 * (DraughtsBoardRenderer), and the kite in the hand in its dye's colour (its paper; its sticks and bows as they are).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LeisureClient {

    private LeisureClient() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LeisureItems.FOOTBALL_ENTITY.get(), FootballRenderer::new);
        event.registerEntityRenderer(LeisureItems.KITE_ENTITY.get(), KiteRenderer::new);
        event.registerBlockEntityRenderer(LeisureItems.DRAUGHTS_BOARD_BE.get(), DraughtsBoardRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(FootballRenderer.LAYER, FootballRenderer::createLayer);
    }

    @SubscribeEvent
    public static void onItemColours(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? FastColor.ARGB32.opaque(KiteItem.colour(stack)) : -1, LeisureItems.KITE.get());
    }
}
