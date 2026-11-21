package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The wire for a player's market stall (entity/PlayerStalls): its screen to the client, and the screen's
 * buttons back. Registered on its own, beside AssistantNetwork's.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class StallNetwork {

    private StallNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(StallScreenPayload.TYPE, StallScreenPayload.STREAM_CODEC, StallNetwork::handleScreen);
        registrar.playToServer(StallActionPayload.TYPE, StallActionPayload.STREAM_CODEC, StallNetwork::handleAction);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleScreen(StallScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.StallScreen.show(payload.data()));
    }

    private static void handleAction(StallActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.PlayerStalls.act(player, payload.action(), payload.args());
            }
        });
    }
}
