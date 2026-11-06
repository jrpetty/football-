package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.item.GarmentItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * [fashion] The client's side of the garments: the model the folk's own clothes are drawn on (FashionModel), and the
 * garments' colours in a hand or a chest, the cloth tinted with its dye (or its wool's or leather's own colour, undyed)
 * and the buttons, bands and buckles drawn as they are, the way the game draws dyed leather.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FashionClient {

    private FashionClient() {}

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(FashionModel.LAYER, FashionModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void onItemColours(RegisterColorHandlersEvent.Item event) {
        for (DeferredItem<GarmentItem> it : McAssistantMod.GARMENTS) {
            Garment g = it.get().garment();
            if (!g.dyeable()) continue;
            event.register((stack, tint) -> tint > 0 ? -1
                : FastColor.ARGB32.opaque(stack.has(DataComponents.DYED_COLOR) ? DyedItemColor.getOrDefault(stack, g.cloth.natural) : g.cloth.natural),
                it.get());
        }
    }
}
