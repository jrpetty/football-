package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [biz30] The business dashboard's wire: one ask, one answer (BizData*Payload), registered on its own. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class BizDataNetwork {

    private BizDataNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(BizDataAskPayload.TYPE, BizDataAskPayload.STREAM_CODEC, BizDataNetwork::handleAsk);
        registrar.playToClient(BizDataPayload.TYPE, BizDataPayload.STREAM_CODEC, BizDataNetwork::handleData);
    }

    private static void handleAsk(BizDataAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) {
                com.jrpetty.mcassistant.Guard.run("business data ask", () -> com.jrpetty.mcassistant.entity.BizData.ask(p, payload.id(), payload.range()));
            }
        });
    }

    /** Clientbound: the screen's class loads on the client only. */
    private static void handleData(BizDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.BusinessDataScreen.show(payload.data()));
    }
}
