package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [earning] From the client: the Earnings page asked for, a shift taken or quit, an order or a contract accepted. The
 *  server checks everything again (entity/Earnings.act). */
public record EarnActionPayload(String action, CompoundTag args) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EarnActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "earn_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EarnActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.action(), 64);
            buf.writeNbt(p.args());
        },
        buf -> {
            String a = buf.readUtf(64);
            CompoundTag t = buf.readNbt();
            return new EarnActionPayload(a, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
