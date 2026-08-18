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
        registrar.playToServer(ZonePayload.TYPE, ZonePayload.STREAM_CODEC, AssistantNetwork::handleZone);
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

    /** A zone drawn on the map. Looser range than a spoken order — the map
     *  legitimately shows the whole operation — but the same ownership rule,
     *  a hard cap on the footprint, and the server picks the Y itself. */
    private static void handleZone(ZonePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity target = player.level().getEntity(payload.entityId());
            if (!(target instanceof AssistantEntity assistant)) return;
            if (!assistant.isOwner(player)) return;
            if (assistant.distanceToSqr(player) > 256.0 * 256.0) return;
            int minX = Math.min(payload.minX(), payload.maxX());
            int maxX = Math.max(payload.minX(), payload.maxX());
            int minZ = Math.min(payload.minZ(), payload.maxZ());
            int maxZ = Math.max(payload.minZ(), payload.maxZ());
            if (maxX - minX < 3 || maxZ - minZ < 3) {
                assistant.say("That plot's too small to work — drag a bigger box.");
                return;
            }
            if (maxX - minX > 96 || maxZ - minZ > 96) {
                assistant.say("That's more ground than I can work — keep it under 96 blocks a side.");
                return;
            }
            int y = assistant.blockPosition().getY();
            com.jrpetty.mcassistant.entity.WorkZone old = assistant.workZone();
            assistant.setWorkZone(com.jrpetty.mcassistant.entity.WorkZone.of(
                new net.minecraft.core.BlockPos(minX, y, minZ),
                new net.minecraft.core.BlockPos(maxX, y, maxZ),
                old != null ? old.depth() : com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH));
            assistant.say("New patch from the map — " + assistant.workZone().describe() + ".");
        });
    }
}
