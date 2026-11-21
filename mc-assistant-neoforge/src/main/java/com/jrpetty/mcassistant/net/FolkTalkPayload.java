package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Something a player says to a folk from the talk screen: a topic (a
 * {@link com.jrpetty.mcassistant.entity.TalkTopic} ordinal) and, for SAY, the
 * words typed. The server checks the folk is real and near before it listens.
 */
public record FolkTalkPayload(int entityId, int topic, String text) implements CustomPacketPayload {

    public static final int MAX_TEXT = 160;

    public static final CustomPacketPayload.Type<FolkTalkPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folk_talk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FolkTalkPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId());
            buf.writeVarInt(p.topic());
            buf.writeUtf(p.text(), MAX_TEXT);
        },
        buf -> new FolkTalkPayload(buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_TEXT)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
