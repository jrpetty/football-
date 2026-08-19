package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's plot book, sent whenever they open the plots menu or change
 * anything in it. Codec written by hand: a varint count and then each entry
 * field by field — no clever composite to misjudge the overload limits of.
 */
public record PlotListPayload(List<Entry> plots) implements CustomPacketPayload {

    /** One plot as the menu sees it: the name, the trade it was saved with
     *  ("" for ground staked without one), its footprint, and how many of the
     *  crew are working it right now. */
    public record Entry(String name, String job, int minX, int minZ,
                        int maxX, int maxZ, int depth, int workers) {

        public int sizeX() { return maxX - minX + 1; }
        public int sizeZ() { return maxZ - minZ + 1; }
    }

    public static final CustomPacketPayload.Type<PlotListPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "plot_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlotListPayload> STREAM_CODEC =
        StreamCodec.of(PlotListPayload::write, PlotListPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, PlotListPayload payload) {
        buf.writeVarInt(payload.plots.size());
        for (Entry e : payload.plots) {
            buf.writeUtf(e.name());
            buf.writeUtf(e.job());
            buf.writeVarInt(e.minX());
            buf.writeVarInt(e.minZ());
            buf.writeVarInt(e.maxX());
            buf.writeVarInt(e.maxZ());
            buf.writeVarInt(e.depth());
            buf.writeVarInt(e.workers());
        }
    }

    private static PlotListPayload read(RegistryFriendlyByteBuf buf) {
        int count = Math.min(64, buf.readVarInt());
        List<Entry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new Entry(buf.readUtf(), buf.readUtf(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new PlotListPayload(out);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
