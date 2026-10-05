package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A folk's answer, for the one player it is talking to: what it said, and how
 * things stand between them — its mood and why, what it thinks of you, what the
 * village thinks of you, what it has asked of you, whether it is walking with you; what it
 * is doing this minute, its card (one "Label|what" fact a line) and the places it can show
 * you the way to ("key=Label;..."). {@code open} asks the client to open the talk screen.
 *
 * <p>{@code skills} is its Skills page: the knack points it has earned, spent and has to
 * spend, its trades' levels, the knacks it chose (with why and when) and the ones still open
 * to it, one fact a line, made by FolkSkills.encode. It rides last, so every field before it
 * is where it always was.
 */
public record FolkReplyPayload(int entityId, boolean open, String name, String about, String said,
                               int mood, String moodWord, int affinity, String standing,
                               boolean following, String asked, String village, String errand,
                               boolean canDeliver, String doing, String card, String places,
                               String skills) implements CustomPacketPayload {

    /** The longest Skills page the codec carries, in characters (the folk's side clips to fit). */
    public static final int MAX_SKILLS = 8192;

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
            buf.writeUtf(p.doing(), 256);
            buf.writeUtf(p.card(), 2048);
            buf.writeUtf(p.places(), 1024);
            buf.writeUtf(p.skills() == null ? "" : p.skills(), MAX_SKILLS);
        },
        buf -> new FolkReplyPayload(buf.readVarInt(), buf.readBoolean(), buf.readUtf(64), buf.readUtf(256),
            buf.readUtf(2048), buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readUtf(64),
            buf.readBoolean(), buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readBoolean(),
            buf.readUtf(256), buf.readUtf(2048), buf.readUtf(1024), buf.readUtf(MAX_SKILLS)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
