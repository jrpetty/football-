package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [till] The wire for the checkout (entity/Tills): the screen to the client, its buttons back. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TillNetwork {

    private TillNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TillScreenPayload.TYPE, TillScreenPayload.STREAM_CODEC, TillNetwork::handleScreen);
        registrar.playToServer(TillActionPayload.TYPE, TillActionPayload.STREAM_CODEC, TillNetwork::handleAction);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleScreen(TillScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.TillScreen.show(payload.data()));
    }

    private static void handleAction(TillActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.Tills.act(player, payload.action(), payload.args(), payload.nonce());
            }
        });
    }
}
