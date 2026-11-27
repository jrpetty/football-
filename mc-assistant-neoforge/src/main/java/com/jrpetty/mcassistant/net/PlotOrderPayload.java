package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything the plots menu asks of the server, in one payload. The op says
 * what; the other two fields mean something only for the ops that use them.
 * Plot names are server-generated strings handed back unchanged — the client
 * can only ever name a plot the server already told it about.
 *
 * ops: 0 = send me the list · 1 = stake a new plot where I stand
 *      2 = toggle this bot on this plot · 3 = forget this plot
 *      4 = cycle this plot's name
 */
public record PlotOrderPayload(int op, int entityId, String plotName)
    implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlotOrderPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "plot_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlotOrderPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlotOrderPayload::op,
            ByteBufCodecs.VAR_INT, PlotOrderPayload::entityId,
            ByteBufCodecs.STRING_UTF8, PlotOrderPayload::plotName,
            PlotOrderPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
