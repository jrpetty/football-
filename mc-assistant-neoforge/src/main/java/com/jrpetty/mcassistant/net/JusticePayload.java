package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [wpe] To the client: the justice system's state it draws. "r" the restrained in sight (entity id, who leads it, cuffs
 * or rope), for the wrists and the lead (client/JusticeClient); "hud" the player's own custody (the jail HUD: where, why,
 * what is left), or nothing once it is free.
 */
public record JusticePayload(CompoundTag tag) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<JusticePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "justice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JusticePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.COMPOUND_TAG.encode(buf, p.tag()),
        buf -> new JusticePayload(ByteBufCodecs.COMPOUND_TAG.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
