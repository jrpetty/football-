package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [orders17] The property office's screen (entity/PropertyOffice.page): its tabs, rows and buttons, composed on the server. */
public record PropertyPagePayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PropertyPagePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "property_page"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PropertyPagePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = buf.readNbt();
            return new PropertyPagePayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
