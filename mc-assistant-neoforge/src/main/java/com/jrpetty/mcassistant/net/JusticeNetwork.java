package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** [wpe] The wire for the justice system: the restrained (for the cuffs and the lead) and the jail HUD, to the client. */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class JusticeNetwork {

    private JusticeNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(JusticePayload.TYPE, JusticePayload.STREAM_CODEC, JusticeNetwork::handle);
    }

    /** To one player, if its connection has the mod's channel (a test's stand-in player, a client without the mod: nothing). */
    public static void send(net.minecraft.server.level.ServerPlayer p, net.minecraft.nbt.CompoundTag t) {
        if (p.connection == null || !p.connection.hasChannel(JusticePayload.TYPE)) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new JusticePayload(t));
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handle(JusticePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.JusticeClient.heard(payload.tag()));
    }
}
