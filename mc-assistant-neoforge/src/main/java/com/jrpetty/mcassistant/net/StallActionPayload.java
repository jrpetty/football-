package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A button on a market stall's screen: what it does ("rent", "prices", "till", "goods", "giveup") and the
 * stall it was pressed at (x, y, z), with the prices for "prices". The server checks the stall is there,
 * near, and the player's before it does anything (entity/PlayerStalls.act).
 */
public record StallActionPayload(String action, CompoundTag args) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StallActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "stall_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StallActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.action(), 32);
            buf.writeNbt(p.args());
        },
        buf -> {
            String action = buf.readUtf(32);
            CompoundTag t = buf.readNbt();
            return new StallActionPayload(action, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
