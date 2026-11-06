package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The village journal's page: the village's name and everything /village status says about it. */
public record VillagePagePayload(String title, String text) implements CustomPacketPayload {

    public static final int MAX_TEXT = 32000;

    public static final CustomPacketPayload.Type<VillagePagePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "village_page"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagePagePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.title(), 256);
            buf.writeUtf(p.text().length() > MAX_TEXT ? p.text().substring(0, MAX_TEXT) : p.text(), MAX_TEXT);
        },
        buf -> new VillagePagePayload(buf.readUtf(256), buf.readUtf(MAX_TEXT)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
