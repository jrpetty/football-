package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [admin] The admin panel asking the server: "open" (the key or the board's button), "refresh" with the town it shows,
 * or an admin command line to run ("ages set iron town:Oakford"), always checked on the server as if typed.
 */
public record AdminAskPayload(String what, String town, String line) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AdminAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "admin_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.what(), 16);
            buf.writeUtf(p.town(), 64);
            buf.writeUtf(p.line(), 512);
        },
        buf -> new AdminAskPayload(buf.readUtf(16), buf.readUtf(64), buf.readUtf(512)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
