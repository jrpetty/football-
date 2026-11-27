package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [till] A button on the checkout ("set", "pay", "sell", "self", "haggle", "tab", "close"...), its arguments, and a number
 *  one higher than the last, so each is answered once (entity/Tills.act checks everything again). */
public record TillActionPayload(String action, CompoundTag args, int nonce) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TillActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "till_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TillActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.action(), 24);
            buf.writeNbt(p.args());
            buf.writeVarInt(p.nonce());
        },
        buf -> {
            String action = buf.readUtf(24);
            CompoundTag t = buf.readNbt();
            int nonce = buf.readVarInt();
            return new TillActionPayload(action, t == null ? new CompoundTag() : t, nonce);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
