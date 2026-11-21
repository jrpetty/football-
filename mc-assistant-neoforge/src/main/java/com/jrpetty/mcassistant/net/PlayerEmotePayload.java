package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [anim] To every client that can see the player (and the player's own): a player's emote began (its act's number, 0 to
 *  stop), for how many ticks it plays at most, and the angle the player's own turned camera watches it from (degrees
 *  round from its front; ANY_ANGLE for the camera's own sway). The client poses that player's model from it (client/PlayerEmotesClient). */
public record PlayerEmotePayload(int player, int act, int ticks, int camera) implements CustomPacketPayload {

    /** No camera angle asked for: the emote camera's own (a slow sway about the front). */
    public static final int ANY_ANGLE = -999;

    public PlayerEmotePayload(int player, int act, int ticks) {
        this(player, act, ticks, ANY_ANGLE);
    }

    public static final CustomPacketPayload.Type<PlayerEmotePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "player_emote"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerEmotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.player());
            buf.writeVarInt(p.act());
            buf.writeVarInt(p.ticks());
            buf.writeVarInt(p.camera());
        },
        buf -> new PlayerEmotePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
