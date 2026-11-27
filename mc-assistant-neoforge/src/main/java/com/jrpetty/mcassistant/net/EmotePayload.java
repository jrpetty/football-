package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [anim] From the client: the player's emote (an act's number, FolkAction.Act), and the folk it is looking at (its id, or
 *  -1). The server checks everything again (entity/PlayerEmotes.emote). */
public record EmotePayload(int act, int target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EmotePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "emote"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EmotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.act());
            buf.writeVarInt(p.target() + 1);
        },
        buf -> new EmotePayload(buf.readVarInt(), buf.readVarInt() - 1));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
