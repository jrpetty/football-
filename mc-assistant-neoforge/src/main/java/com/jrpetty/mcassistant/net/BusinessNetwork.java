package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * [business-books] The wire for a business's books: the screen to the client. Its buttons are commands
 * (/village business ...), so nothing comes back this way. Registered on its own, beside the stall's.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class BusinessNetwork {

    private BusinessNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BusinessScreenPayload.TYPE, BusinessScreenPayload.STREAM_CODEC, BusinessNetwork::handleScreen);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleScreen(BusinessScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.BusinessScreen.show(payload.data()));
    }
}
