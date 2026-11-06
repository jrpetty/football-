package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * [fleet] The wire for the auction's bid screen (entity/Auctions): the screen to the client, its buttons back.
 * Registered on its own, beside the stall's (StallNetwork).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AuctionNetwork {

    private AuctionNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(AuctionScreenPayload.TYPE, AuctionScreenPayload.STREAM_CODEC, AuctionNetwork::handleScreen);
        registrar.playToServer(AuctionActionPayload.TYPE, AuctionActionPayload.STREAM_CODEC, AuctionNetwork::handleAction);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleScreen(AuctionScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.AuctionScreen.show(payload.data()));
    }

    private static void handleAction(AuctionActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.Auctions.act(player, payload.action(), payload.args());
            }
        });
    }
}
