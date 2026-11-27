package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [admin] The admin panel's wire, registered on its own beside the others (StallNetwork's pattern). */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AdminNetwork {

    private AdminNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(AdminPanelPayload.TYPE, AdminPanelPayload.STREAM_CODEC, AdminNetwork::handlePanel);
        registrar.playToServer(AdminAskPayload.TYPE, AdminAskPayload.STREAM_CODEC, AdminNetwork::handleAsk);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handlePanel(AdminPanelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.AdminScreen.heard(payload.data()));
    }

    private static void handleAsk(AdminAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) com.jrpetty.mcassistant.admin.AdminPanel.ask(p, payload.what(), payload.town(), payload.line());
        });
    }
}
