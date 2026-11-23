package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [pa33] To the server: something the player did in the assistant's panel, its wheel, its talk box or by voice. */
public record PaActionPayload(String action, CompoundTag args) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PaActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "pa_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.action());
            buf.writeNbt(p.args());
        },
        buf -> {
            String action = ByteBufCodecs.STRING_UTF8.decode(buf);
            CompoundTag t = buf.readNbt();
            return new PaActionPayload(action, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
