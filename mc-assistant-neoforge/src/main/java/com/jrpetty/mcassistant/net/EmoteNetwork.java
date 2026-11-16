package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [anim] The wire for the player's emotes: the emote up to the server, and to everybody who can see the player. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class EmoteNetwork {

    private EmoteNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(EmotePayload.TYPE, EmotePayload.STREAM_CODEC, EmoteNetwork::handleEmote);
        registrar.playToClient(PlayerEmotePayload.TYPE, PlayerEmotePayload.STREAM_CODEC, EmoteNetwork::handlePlayerEmote);
    }

    private static void handleEmote(EmotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.PlayerEmotes.emote(player, payload.act(), payload.target());
            }
        });
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handlePlayerEmote(PlayerEmotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.PlayerEmotesClient.heard(payload.player(), payload.act(), payload.ticks(), payload.camera()));
    }
}
