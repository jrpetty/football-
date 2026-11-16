package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [anim] To every client that can see the player (and the player's own): a player's emote began (its act's number, 0 to
 *  stop), and for how many ticks it plays at most. The client poses that player's model from it (client/PlayerEmotesClient). */
public record PlayerEmotePayload(int player, int act, int ticks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayerEmotePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "player_emote"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerEmotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.player());
            buf.writeVarInt(p.act());
            buf.writeVarInt(p.ticks());
        },
        buf -> new PlayerEmotePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
