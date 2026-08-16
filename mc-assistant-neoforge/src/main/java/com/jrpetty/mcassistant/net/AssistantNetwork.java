package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client → server orders. The Orders screen isn't a container, so it can't ride
 * the vanilla button channel the management screen uses; this is its wire.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AssistantNetwork {

    private AssistantNetwork() {}

    /** How far away an order may be given — generous, but not unlimited. */
    private static final double ORDER_RANGE = 48.0;

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(OrderPayload.TYPE, OrderPayload.STREAM_CODEC, AssistantNetwork::handleOrder);
    }

    private static void handleOrder(OrderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity target = player.level().getEntity(payload.entityId());
            // Never trust the client: it must be a real assistant, owned by this
            // player, in this world, and close enough to be shouted at.
            if (!(target instanceof AssistantEntity assistant)) return;
            if (!assistant.isOwner(player)) return;
            if (assistant.distanceToSqr(player) > ORDER_RANGE * ORDER_RANGE) {
                assistant.say("Too far away to hear you — get closer.");
                return;
            }
            AssistantActions.apply(assistant, player, payload.action());
        });
    }
}
