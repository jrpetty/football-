package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [orders17] A button of the property office pressed: what (the op) and on what (its argument, as the server wrote it). */
public record PropertyAskPayload(String op, String arg) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PropertyAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "property_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PropertyAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.op(), 32);
            buf.writeUtf(p.arg(), 256);
        },
        buf -> new PropertyAskPayload(buf.readUtf(32), buf.readUtf(256)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
