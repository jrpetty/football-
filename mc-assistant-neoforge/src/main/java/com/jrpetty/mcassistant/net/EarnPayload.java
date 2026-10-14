package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [earning] To the client: the Earnings page ({@link #PAGE}), a payment's toast ({@link #TOAST}) or the shift HUD
 *  ({@link #HUD}), each a tag the client draws as it is. */
public record EarnPayload(int kind, CompoundTag data) implements CustomPacketPayload {

    public static final int PAGE = 0, TOAST = 1, HUD = 2, MARKER = 3;

    public static final CustomPacketPayload.Type<EarnPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "earn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EarnPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.kind());
            buf.writeNbt(p.data());
        },
        buf -> {
            int kind = buf.readVarInt();
            CompoundTag t = buf.readNbt();
            return new EarnPayload(kind, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
