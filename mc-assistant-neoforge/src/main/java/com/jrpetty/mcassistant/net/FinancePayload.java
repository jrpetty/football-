package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [purse20] To the client: a player's finances ({@link #PAGE}), a notice ({@link #TOAST}: the week's summary, bills due)
 *  or a word back ({@link #SAID}), each a tag the client draws as it is (entity/FinanceSheet writes them). */
public record FinancePayload(int kind, CompoundTag data) implements CustomPacketPayload {

    public static final int PAGE = 0, TOAST = 1, SAID = 2;

    public static final CustomPacketPayload.Type<FinancePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "finance"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FinancePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.kind());
            buf.writeNbt(p.data());
        },
        buf -> {
            int kind = buf.readVarInt();
            CompoundTag t = buf.readNbt();
            return new FinancePayload(kind, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
