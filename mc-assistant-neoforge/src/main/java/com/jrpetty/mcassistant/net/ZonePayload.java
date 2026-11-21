package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A work zone drawn on the map screen: the bot it is for and the rectangle's
 * corners in world X/Z. The server re-checks ownership, clamps the size, and
 * supplies the Y and depth itself — the client never gets to say anything
 * about a zone but its footprint.
 */
public record ZonePayload(int entityId, int minX, int minZ, int maxX, int maxZ)
    implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZonePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "zone"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ZonePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ZonePayload::entityId,
            ByteBufCodecs.VAR_INT, ZonePayload::minX,
            ByteBufCodecs.VAR_INT, ZonePayload::minZ,
            ByteBufCodecs.VAR_INT, ZonePayload::maxX,
            ByteBufCodecs.VAR_INT, ZonePayload::maxZ,
            ZonePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
