package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** How fast time is set to run (1 = normal) and how fast the server is really managing, in tenths. */
public record TimeSpeedPayload(int factor, int actualX10) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TimeSpeedPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "time_speed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimeSpeedPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.factor());
            buf.writeVarInt(p.actualX10());
        },
        buf -> new TimeSpeedPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
