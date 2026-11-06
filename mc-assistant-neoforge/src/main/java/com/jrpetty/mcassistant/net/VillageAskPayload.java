package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The village journal key: "tell me about the village I'm standing in". */
public record VillageAskPayload(int what) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VillageAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillageAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeVarInt(p.what()),
        buf -> new VillageAskPayload(buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
