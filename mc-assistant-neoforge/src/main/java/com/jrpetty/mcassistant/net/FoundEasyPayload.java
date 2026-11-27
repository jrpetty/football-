package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [found23] To the client: the founding screen ({@link #OPEN}), a notice ({@link #TOAST}) or the welcome card ({@link #WELCOME}),
 *  each a tag the client draws as it is (entity/FoundingEasy writes them). */
public record FoundEasyPayload(int kind, CompoundTag data) implements CustomPacketPayload {

    public static final int OPEN = 0, TOAST = 1, WELCOME = 2;

    public static final CustomPacketPayload.Type<FoundEasyPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "found_easy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FoundEasyPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.kind());
            buf.writeNbt(p.data());
        },
        buf -> {
            int kind = buf.readVarInt();
            CompoundTag t = buf.readNbt();
            return new FoundEasyPayload(kind, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
