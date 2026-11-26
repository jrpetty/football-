package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [till] The checkout (entity/Tills.screen): the business, who's at the till, what's for sale, the basket priced; or
 *  {@code gone} when the player has walked away. */
public record TillScreenPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TillScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "till_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TillScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = buf.readNbt();
            return new TillScreenPayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
