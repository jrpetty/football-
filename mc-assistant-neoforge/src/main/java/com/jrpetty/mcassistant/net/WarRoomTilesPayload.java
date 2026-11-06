package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * [borders-map] Terrain tiles for the map table (server to client): each its key, its stamp, and its 64 by 64 packed
 * map colours (0 where the viewer's town knows nothing). A dozen at most an answer.
 */
public record WarRoomTilesPayload(List<long[]> heads, List<byte[]> bodies) implements CustomPacketPayload {

    public static final int SIDE = 64;

    public static final CustomPacketPayload.Type<WarRoomTilesPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "war_room_tiles"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarRoomTilesPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            int n = Math.min(p.heads().size(), 16);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                buf.writeLong(p.heads().get(i)[0]);
                buf.writeLong(p.heads().get(i)[1]);
                buf.writeByteArray(p.bodies().get(i));
            }
        },
        buf -> {
            int n = Math.min(buf.readVarInt(), 16);
            List<long[]> heads = new ArrayList<>();
            List<byte[]> bodies = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                heads.add(new long[]{ buf.readLong(), buf.readLong() });
                bodies.add(buf.readByteArray(SIDE * SIDE));
            }
            return new WarRoomTilesPayload(heads, bodies);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
