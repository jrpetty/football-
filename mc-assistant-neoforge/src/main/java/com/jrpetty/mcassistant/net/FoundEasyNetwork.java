package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [found23] The wire for starting a town: the founding screen, its notices and the welcome card to the client, the asks back. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class FoundEasyNetwork {

    private FoundEasyNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(FoundEasyPayload.TYPE, FoundEasyPayload.STREAM_CODEC, FoundEasyNetwork::handlePage);
        registrar.playToServer(FoundEasyAskPayload.TYPE, FoundEasyAskPayload.STREAM_CODEC, FoundEasyNetwork::handleAsk);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handlePage(FoundEasyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.FoundingClient.heard(payload.kind(), payload.data()));
    }

    private static void handleAsk(FoundEasyAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.Guard.run("the founding screen", () -> com.jrpetty.mcassistant.entity.FoundingEasy.act(player, payload.action(), payload.args()));
            }
        });
    }
}
