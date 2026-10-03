package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A folk's answer, for the one player it is talking to: what it said, and how
 * things stand between them — its mood and why, what it thinks of you, what the
 * village thinks of you, what it has asked of you, whether it is walking with you.
 * {@code open} asks the client to open the talk screen.
 */
public record FolkReplyPayload(int entityId, boolean open, String name, String about, String said,
                               int mood, String moodWord, int affinity, String standing,
                               boolean following, String asked, String village, String errand,
                               boolean canDeliver) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FolkReplyPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folk_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FolkReplyPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId());
            buf.writeBoolean(p.open());
            buf.writeUtf(p.name(), 64);
            buf.writeUtf(p.about(), 256);
            buf.writeUtf(p.said(), 2048);
            buf.writeVarInt(p.mood());
            buf.writeUtf(p.moodWord(), 64);
            buf.writeVarInt(p.affinity());
            buf.writeUtf(p.standing(), 64);
            buf.writeBoolean(p.following());
            buf.writeUtf(p.asked(), 256);
            buf.writeUtf(p.village(), 256);
            buf.writeUtf(p.errand(), 256);
            buf.writeBoolean(p.canDeliver());
        },
        buf -> new FolkReplyPayload(buf.readVarInt(), buf.readBoolean(), buf.readUtf(64), buf.readUtf(256),
            buf.readUtf(2048), buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readUtf(64),
            buf.readBoolean(), buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
