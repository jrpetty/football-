package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The town's books for the analytics screen (Annals.snapshot): the days as series, the trades, the folk, the leader. */
public record CityStatsPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CityStatsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "city_stats"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CityStatsPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = buf.readNbt();
            return new CityStatsPayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
