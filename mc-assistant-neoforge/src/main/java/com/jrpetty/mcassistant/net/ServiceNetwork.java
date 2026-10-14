package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [shops-homes] The wire for the service screens (entity/ServicePoints): the screen to the client, its buttons back. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ServiceNetwork {

    private ServiceNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(ServiceScreenPayload.TYPE, ServiceScreenPayload.STREAM_CODEC, ServiceNetwork::handleScreen);
        registrar.playToServer(ServiceActionPayload.TYPE, ServiceActionPayload.STREAM_CODEC, ServiceNetwork::handleAction);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleScreen(ServiceScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.ServiceScreen.show(payload.data()));
    }

    private static void handleAction(ServiceActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.ServicePoints.act(player, payload.role(), payload.action(), payload.args(), payload.nonce());
            }
        });
    }
}
