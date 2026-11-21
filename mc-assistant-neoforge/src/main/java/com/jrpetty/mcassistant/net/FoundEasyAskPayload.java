package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [found23] To the server: the founding screen's and the welcome card's asks ("found", "board", "elder"...), with their arguments. */
public record FoundEasyAskPayload(String action, CompoundTag args) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FoundEasyAskPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "found_easy_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FoundEasyAskPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.action(), 64);
            buf.writeNbt(p.args());
        },
        buf -> {
            String action = buf.readUtf(64);
            CompoundTag t = buf.readNbt();
            return new FoundEasyAskPayload(action, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
