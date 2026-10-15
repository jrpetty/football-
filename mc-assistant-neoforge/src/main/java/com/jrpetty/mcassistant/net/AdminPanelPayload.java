package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [admin] The admin panel's state, server to an operator's client: the towns, the chosen town's inspect lines, the
 * jobs running, the server's tick time, the player's recent actions and the last result (admin/AdminPanel).
 */
public record AdminPanelPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AdminPanelPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "admin_panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminPanelPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeNbt(p.data()),
        buf -> {
            CompoundTag t = buf.readNbt();
            return new AdminPanelPayload(t == null ? new CompoundTag() : t);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
