package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * [wpb] The wire for where a player's work is (entity/WorkWay): the workplace, the way in (a mine's head), the field's
 * outline, the current task's spot, and whether the player has the arrow on. Drawn by client/WorkWayClient as a compass
 * arrow with the distance at the top of the screen and a beam at the spot.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class WorkNetwork {

    private WorkNetwork() {}

    public record WayPayload(CompoundTag data) implements CustomPacketPayload {
        public static final Type<WayPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "work_way"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WayPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeNbt(p.data()),
            buf -> {
                CompoundTag t = buf.readNbt();
                return new WayPayload(t == null ? new CompoundTag() : t);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(WayPayload.TYPE, WayPayload.STREAM_CODEC, WorkNetwork::handle);
    }

    private static void handle(WayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.WorkWayClient.set(payload.data()));
    }

    /** The way to this player (a connection without the channel is sent nothing). */
    public static void send(ServerPlayer p, CompoundTag way) {
        if (p.connection == null || !p.connection.hasChannel(WayPayload.TYPE)) return;
        PacketDistributor.sendToPlayer(p, new WayPayload(way));
    }
}
