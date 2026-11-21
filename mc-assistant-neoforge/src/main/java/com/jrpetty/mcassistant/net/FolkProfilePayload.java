package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** [folk-ui] A folk's profile (entity/FolkProfile.build), for the one player whose profile screen asked for it. */
public record FolkProfilePayload(CompoundTag data, boolean open) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FolkProfilePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "folk_profile"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FolkProfilePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeNbt(p.data());
            buf.writeBoolean(p.open());
        },
        buf -> {
            CompoundTag t = buf.readNbt();
            return new FolkProfilePayload(t == null ? new CompoundTag() : t, buf.readBoolean());
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
