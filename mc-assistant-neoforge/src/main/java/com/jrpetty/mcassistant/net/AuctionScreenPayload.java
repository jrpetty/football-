package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [fleet] The auction's bid screen (entity/Auctions.screenData): the lot being called, its bids, your coin, the day's
 *  lots and the past sales. Sent when the screen is opened and again whenever the auction moves on. */
public record AuctionScreenPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AuctionScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "auction_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = buf.readNbt();
            return new AuctionScreenPayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
