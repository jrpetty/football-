package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [orders17] The wire for the property office (entity/PropertyOffice): its screen to the client, its buttons back. No commands. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PropertyNetwork {

    private PropertyNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(PropertyPagePayload.TYPE, PropertyPagePayload.STREAM_CODEC, PropertyNetwork::handlePage);
        registrar.playToServer(PropertyAskPayload.TYPE, PropertyAskPayload.STREAM_CODEC, PropertyNetwork::handleAsk);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handlePage(PropertyPagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.PropertyScreen.show(payload.data()));
    }

    private static void handleAsk(PropertyAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) com.jrpetty.mcassistant.entity.PropertyOffice.act(player, payload.op(), payload.arg());
        });
    }
}
