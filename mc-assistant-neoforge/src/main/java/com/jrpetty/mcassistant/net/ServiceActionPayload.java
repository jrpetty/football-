package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [shops-homes] A button on a service screen: the trade it is for, what it does ("buy", "sell", "order", "haggle",
 * "deposit", "mend", "tab", "close"...), its arguments, and a number one higher than the screen's last request, so the
 * server answers each request once (entity/ServicePoints.act checks everything again: nothing the client says about
 * prices or stock is believed).
 */
public record ServiceActionPayload(String role, String action, CompoundTag args, int nonce) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServiceActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "service_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServiceActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.role(), 16);
            buf.writeUtf(p.action(), 24);
            buf.writeNbt(p.args());
            buf.writeVarInt(p.nonce());
        },
        buf -> {
            String role = buf.readUtf(16);
            String action = buf.readUtf(24);
            CompoundTag t = buf.readNbt();
            int nonce = buf.readVarInt();
            return new ServiceActionPayload(role, action, t == null ? new CompoundTag() : t, nonce);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
