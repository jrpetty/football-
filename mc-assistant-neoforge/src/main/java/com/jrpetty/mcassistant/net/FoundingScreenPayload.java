package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The founding screen (client/FoundingScreen), opened from a board waiting for its village:
 * which board, the count to start at, the most this server lets a village be founded with,
 * and how many folk the world holds against what it may. {@code open} false closes it again
 * (the board has been answered, by somebody else or by a command).
 */
public record FoundingScreenPayload(BlockPos board, boolean open, int start, int most, int worldFolk, int worldCap)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FoundingScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "founding_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FoundingScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeBlockPos(p.board());
            buf.writeBoolean(p.open());
            buf.writeVarInt(p.start());
            buf.writeVarInt(p.most());
            buf.writeVarInt(p.worldFolk());
            buf.writeVarInt(p.worldCap());
        },
        buf -> new FoundingScreenPayload(buf.readBlockPos(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
