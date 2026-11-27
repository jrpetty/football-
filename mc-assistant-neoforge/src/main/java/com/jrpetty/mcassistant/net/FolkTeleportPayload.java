package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * [teleport] "Take me to this folk": a click on "Teleport to ..." under a folk's name in the town's books or on
 * its card. Only the folk's id rides. Nothing else the client might say is believed: the server asks again
 * whether the player is in creative (and, on a server, an operator), finds the folk itself and picks the
 * spot to stand on (entity/FolkTeleport.request).
 */
public record FolkTeleportPayload(UUID folk) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FolkTeleportPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folk_teleport"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FolkTeleportPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeUUID(p.folk()),
        buf -> new FolkTeleportPayload(buf.readUUID()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
