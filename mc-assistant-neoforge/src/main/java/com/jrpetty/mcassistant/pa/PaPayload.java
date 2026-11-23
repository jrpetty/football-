package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [pa33] To the client: the Player AI Assistant's panel ({@link #STATE}), a line it says ({@link #SAY}), a question
 *  to answer from the Guide ({@link #GUIDE}), or "open the panel" ({@link #OPEN}). Each a tag the client reads as is. */
public record PaPayload(int kind, CompoundTag data) implements CustomPacketPayload {

    public static final int STATE = 0, SAY = 1, GUIDE = 2, OPEN = 3, HUD = 4;

    public static final CustomPacketPayload.Type<PaPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "pa"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.kind());
            buf.writeNbt(p.data());
        },
        buf -> {
            int kind = buf.readVarInt();
            CompoundTag t = buf.readNbt();
            return new PaPayload(kind, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
