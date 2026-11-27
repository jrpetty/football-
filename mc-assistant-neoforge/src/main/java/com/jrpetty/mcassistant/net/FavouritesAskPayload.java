package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * [ui] The favourites screen (and the star on a folk's profile or talk screen) asking the server: {@code list} (the
 * player's list, as cards), {@code detail} (one folk in full), {@code add}, {@code remove} or {@code toggle} a folk,
 * or {@code move} it {@code arg} places up (negative) or down the list. Sent when the screen opens or the player acts,
 * and again every few seconds while the screen stays open; never otherwise.
 */
public record FavouritesAskPayload(String action, UUID folk, int arg) implements CustomPacketPayload {

    public static final UUID NOBODY = new UUID(0L, 0L);

    public static final CustomPacketPayload.Type<FavouritesAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "favourites_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FavouritesAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.action(), 16);
            UUIDUtil.STREAM_CODEC.encode(buf, p.folk());
            buf.writeVarInt(p.arg());
        },
        buf -> new FavouritesAskPayload(buf.readUtf(16), UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
