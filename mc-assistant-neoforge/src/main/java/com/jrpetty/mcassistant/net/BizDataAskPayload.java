package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [biz30] The dashboard asks (client to server): this business's data ("" for the asker's first), over so many days. */
public record BizDataAskPayload(String id, int range) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BizDataAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "biz_data_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BizDataAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.id(), 128);
            buf.writeVarInt(p.range());
        },
        buf -> new BizDataAskPayload(buf.readUtf(128), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
