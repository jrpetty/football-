package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [vision27] The camera screen (client/CameraScreen) asking the server: {@code info} (the town's heart, the folk to
 * follow, whether this player may run a time-lapse), {@code happening} (somewhere something is going on just now),
 * {@code timelapse} ({@code a} times faster for {@code b} real seconds) or {@code stop} (the time-lapse ended early).
 * Sent only when the player presses something on that screen.
 */
public record CameraAskPayload(String action, int a, int b) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CameraAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "camera_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.action(), 16);
            buf.writeVarInt(p.a());
            buf.writeVarInt(p.b());
        },
        buf -> new CameraAskPayload(buf.readUtf(16), buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
