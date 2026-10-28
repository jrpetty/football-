package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FolkTeleport;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * [teleport] The wire for going straight to a folk from the screens: one serverbound payload, registered on its
 * own beside AssistantNetwork's and StallNetwork's. The client has already shut its screen when it sends this,
 * so whatever the server makes of it, yes or no, is said in chat where the player will see it.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TeleportNetwork {

    private TeleportNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(FolkTeleportPayload.TYPE, FolkTeleportPayload.STREAM_CODEC, TeleportNetwork::handle);
    }

    private static void handle(FolkTeleportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) act(player, payload);
        });
    }

    private static FolkTeleport.Outcome act(ServerPlayer player, FolkTeleportPayload payload) {
        FolkTeleport.Outcome said = FolkTeleport.request(player, payload.folk());
        player.sendSystemMessage(Component.literal(said.message()).withColor(said.ok() ? 0x9EE07A : 0xE0A070));
        return said;
    }

    /** Tests: exactly what the handler does with a payload from this player, and how it came out. */
    public static FolkTeleport.Outcome handleForTests(ServerPlayer player, FolkTeleportPayload payload) {
        return act(player, payload);
    }
}
