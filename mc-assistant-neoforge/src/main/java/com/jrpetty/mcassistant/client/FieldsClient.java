package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.item.FieldItems;
import com.jrpetty.mcassistant.item.SeedSatchelItem;
import com.jrpetty.mcassistant.item.WateringCanItem;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * [fields] The tools look as full as they are: the watering can shows the water in its mouth and a drip at the rose
 * while there is any in it (the "water" property, its fill), and the seed satchel bulges, its flap lifted on the seed,
 * once it holds any ("seeds"). The item models choose by these (models/item/copper_watering_can.json, seed_satchel.json).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FieldsClient {

    private FieldsClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(FieldItems.WATERING_CAN.get(), ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "water"),
                (stack, level, holder, seed) -> WateringCanItem.water(stack) / (float) WateringCanItem.CAPACITY);
            ItemProperties.register(FieldItems.SEED_SATCHEL.get(), ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "seeds"),
                (stack, level, holder, seed) -> Math.min(1.0F, SeedSatchelItem.total(stack) / (float) SeedSatchelItem.MOST));
        });
    }
}
