package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** "Make time run faster": mode 0 sets {@code factor}, 1 is a step faster, 2 a step slower. */
public record TimeSpeedAskPayload(int mode, int factor) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TimeSpeedAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "time_speed_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimeSpeedAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.mode());
            buf.writeVarInt(p.factor());
        },
        buf -> new TimeSpeedAskPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
