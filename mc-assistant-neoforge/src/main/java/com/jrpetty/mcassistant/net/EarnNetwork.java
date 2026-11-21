package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [earning] The wire for earning: the page, the toasts and the shift HUD to the client; its buttons back. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class EarnNetwork {

    private EarnNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(EarnPayload.TYPE, EarnPayload.STREAM_CODEC, EarnNetwork::handleEarn);
        registrar.playToServer(EarnActionPayload.TYPE, EarnActionPayload.STREAM_CODEC, EarnNetwork::handleAction);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleEarn(EarnPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.EarnClient.heard(payload.kind(), payload.data()));
    }

    private static void handleAction(EarnActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.Earnings.act(player, payload.action(), payload.args());
            }
        });
    }
}
