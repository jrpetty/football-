package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One order, aimed at one assistant, sent from the Orders screen. Carries the
 * bot's entity id and an {@link com.jrpetty.mcassistant.AssistantActions} id —
 * the server re-checks ownership and range before acting on either.
 */
public record OrderPayload(int entityId, int action) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OrderPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OrderPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OrderPayload::entityId,
            ByteBufCodecs.VAR_INT, OrderPayload::action,
            OrderPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
