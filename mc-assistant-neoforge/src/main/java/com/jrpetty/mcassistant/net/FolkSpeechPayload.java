package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A few words a folk says out loud, shown in a bubble over its head to whoever is
 * near enough to hear — never in the chat.
 */
public record FolkSpeechPayload(int entityId, String text, int ticks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FolkSpeechPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folk_speech"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FolkSpeechPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId());
            buf.writeUtf(p.text(), 256);
            buf.writeVarInt(p.ticks());
        },
        buf -> new FolkSpeechPayload(buf.readVarInt(), buf.readUtf(256), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
