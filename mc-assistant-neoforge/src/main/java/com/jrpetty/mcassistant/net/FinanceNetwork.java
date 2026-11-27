package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [purse20] The wire for a player's finances: the page and the notices to the client, its asks back. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class FinanceNetwork {

    private FinanceNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(FinancePayload.TYPE, FinancePayload.STREAM_CODEC, FinanceNetwork::handlePage);
        registrar.playToServer(FinanceAskPayload.TYPE, FinanceAskPayload.STREAM_CODEC, FinanceNetwork::handleAsk);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handlePage(FinancePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.FinanceClient.heard(payload.kind(), payload.data()));
    }

    private static void handleAsk(FinanceAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.Guard.run("the finances screen", () -> com.jrpetty.mcassistant.entity.FinanceSheet.act(player, payload.action(), payload.args()));
            }
        });
    }
}
