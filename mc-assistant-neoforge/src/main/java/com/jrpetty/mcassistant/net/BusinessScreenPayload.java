package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [business-books] A business's books for its screen (entity/BusinessBooks.books), or the town's list of them. */
public record BusinessScreenPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BusinessScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "business_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BusinessScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = buf.readNbt();
            return new BusinessScreenPayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
