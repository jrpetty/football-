package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Confirm and spawn, from the founding screen: this many folk to found the village at this board. */
public record FoundingChoicePayload(BlockPos board, int count) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FoundingChoicePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "founding_choice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FoundingChoicePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeBlockPos(p.board());
            buf.writeVarInt(p.count());
        },
        buf -> new FoundingChoicePayload(buf.readBlockPos(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
