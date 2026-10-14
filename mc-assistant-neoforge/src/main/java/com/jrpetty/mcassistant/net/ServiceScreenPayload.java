package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [shops-homes] A service point's screen (entity/ServicePoints.screen): the trade, its keeper and hours, the player's
 *  standing and coin, and the trade's own panel; or {@code gone} when the player has walked away from it. */
public record ServiceScreenPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServiceScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "service_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServiceScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = buf.readNbt();
            return new ServiceScreenPayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
