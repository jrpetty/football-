package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [ui] The server's answer about a player's favourites (entity/Favourites), for that player only: {@code kind} is
 * {@code list} (the cards), {@code detail} (one folk in full), {@code star} (a folk now on or off the list, for the
 * star on its screens) or {@code toast} (a milestone of a folk it follows). {@code open} asks the client to open the
 * favourites screen with it.
 */
public record FavouritesPayload(CompoundTag data, boolean open) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FavouritesPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "favourites"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FavouritesPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeNbt(p.data());
            buf.writeBoolean(p.open());
        },
        buf -> {
            CompoundTag t = CityStatsPayload.readBig(buf);   // [ui53] the books' budget, not 2 MiB
            return new FavouritesPayload(t == null ? new CompoundTag() : t, buf.readBoolean());
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
