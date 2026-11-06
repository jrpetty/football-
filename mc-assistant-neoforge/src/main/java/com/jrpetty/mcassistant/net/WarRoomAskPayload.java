package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [borders-map] The map table's screen asks (client to server): the overlay again (every second and a half while it is
 * open), with the borders' version it has, and the terrain tiles it wants, each as its key and the stamp of the copy it
 * has (0 for none): {@code want} = key, stamp, key, stamp...
 */
public record WarRoomAskPayload(BlockPos table, boolean overlay, long claimsHave, long[] want) implements CustomPacketPayload {

    /** At most this many tiles asked for at once (the server answers a dozen of them). */
    public static final int MOST = 32;

    public static final CustomPacketPayload.Type<WarRoomAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "war_room_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarRoomAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeBlockPos(p.table());
            buf.writeBoolean(p.overlay());
            buf.writeLong(p.claimsHave());
            int n = Math.min(p.want().length / 2, MOST);
            buf.writeVarInt(n);
            for (int i = 0; i < n * 2; i++) buf.writeLong(p.want()[i]);
        },
        buf -> {
            BlockPos table = buf.readBlockPos();
            boolean overlay = buf.readBoolean();
            long have = buf.readLong();
            int n = Math.min(buf.readVarInt(), MOST);
            long[] want = new long[n * 2];
            for (int i = 0; i < n * 2; i++) want[i] = buf.readLong();
            return new WarRoomAskPayload(table, overlay, have, want);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
