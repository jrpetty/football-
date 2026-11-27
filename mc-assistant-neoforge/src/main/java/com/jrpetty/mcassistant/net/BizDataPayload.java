package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [biz30] A business's data for its dashboard (entity/BizData.build), server to client, when asked. */
public record BizDataPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BizDataPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "biz_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BizDataPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = CityStatsPayload.readBig(buf);   // [ui53] the books' budget, not 2 MiB
            return new BizDataPayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
