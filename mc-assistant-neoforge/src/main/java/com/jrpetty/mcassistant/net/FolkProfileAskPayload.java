package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * [folk-ui] The profile screen asking for a folk's profile: on opening, and again every few seconds while it stays open
 * (client/FolkProfileScreen), so nothing is sent for a screen nobody is looking at. {@code open} asks the client to open
 * the screen with the answer (the first ask), rather than only refresh one that is already up.
 */
public record FolkProfileAskPayload(UUID folk, boolean open) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FolkProfileAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folk_profile_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FolkProfileAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, p.folk());
            buf.writeBoolean(p.open());
        },
        buf -> new FolkProfileAskPayload(UUIDUtil.STREAM_CODEC.decode(buf), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
