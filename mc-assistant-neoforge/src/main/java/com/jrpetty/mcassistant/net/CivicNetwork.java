package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [player-civic] The civic pages' wire (CivicPagePayload): registered on its own, beside the mod's own (AssistantNetwork). */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CivicNetwork {

    private CivicNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(CivicPagePayload.TYPE, CivicPagePayload.STREAM_CODEC, CivicNetwork::handlePage);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handlePage(CivicPagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.CivicScreen.show(payload));
    }
}
