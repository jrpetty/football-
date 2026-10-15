package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [borders-map] The map table's wire (WarRoom*Payload): registered on its own, beside the mod's own (AssistantNetwork). */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class WarRoomNetwork {

    private WarRoomNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(WarRoomAskPayload.TYPE, WarRoomAskPayload.STREAM_CODEC, WarRoomNetwork::handleAsk);
        registrar.playToClient(WarRoomDataPayload.TYPE, WarRoomDataPayload.STREAM_CODEC, WarRoomNetwork::handleData);
        registrar.playToClient(WarRoomTilesPayload.TYPE, WarRoomTilesPayload.STREAM_CODEC, WarRoomNetwork::handleTiles);
    }

    private static void handleAsk(WarRoomAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) {
                com.jrpetty.mcassistant.Guard.run("war room ask", () ->
                    com.jrpetty.mcassistant.entity.WarRoom.ask(p, payload.table(), payload.claimsHave(), payload.want(), payload.overlay()));
            }
        });
    }

    /** Clientbound: the classes behind these calls only load on the client. */
    private static void handleData(WarRoomDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.WarRoomScreen.heard(payload.data()));
    }

    private static void handleTiles(WarRoomTilesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.WarRoomScreen.tiles(payload.heads(), payload.bodies()));
    }
}
