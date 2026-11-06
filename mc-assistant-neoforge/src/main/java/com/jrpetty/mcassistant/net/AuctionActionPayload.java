package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [fleet] A button on the bid screen: "bid" (with the coins), "put" (the thing in hand, with a reserve), "close". The
 *  server checks the player is at the town, and takes the coin from their own pack (entity/Auctions.act). */
public record AuctionActionPayload(String action, CompoundTag args) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AuctionActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "auction_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.action(), 16);
            buf.writeNbt(p.args());
        },
        buf -> {
            String action = buf.readUtf(16);
            CompoundTag t = buf.readNbt();
            return new AuctionActionPayload(action, t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
