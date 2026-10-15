package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * [pets] The collar in the hand shows its dye, as leather armour does: its band (the item's first layer) takes the
 * colour it was dyed at the crafting table, plain leather brown till then; its buckle and tag (the second) stay brass.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class PetsClient {

    private PetsClient() {}

    /** An undyed collar: plain tanned leather. */
    private static final int LEATHER = 0xA06540;

    @SubscribeEvent
    public static void onItemColours(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? FastColor.ARGB32.opaque(DyedItemColor.getOrDefault(stack, LEATHER)) : -1,
            McAssistantMod.COLLAR.get());
    }
}
