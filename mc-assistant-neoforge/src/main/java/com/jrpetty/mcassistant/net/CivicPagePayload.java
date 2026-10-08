package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * [player-civic] A page for a player: the Leader's page, the hustings, the trades (entity/PlayerCivic.send). Its
 * title, its words (a line to each "\n"), and its buttons, each "label\tcommand\ttooltip": a button runs its command
 * as the player, and the command answers with the page again.
 */
public record CivicPagePayload(String title, String text, List<String> buttons) implements CustomPacketPayload {

    public static final int MAX_TEXT = 32000;

    public static final CustomPacketPayload.Type<CivicPagePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "civic_page"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CivicPagePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.title(), 256);
            buf.writeUtf(p.text(), MAX_TEXT);
            buf.writeVarInt(p.buttons().size());
            for (String b : p.buttons()) buf.writeUtf(b, 512);
        },
        buf -> {
            String title = buf.readUtf(256);
            String text = buf.readUtf(MAX_TEXT);
            int n = Math.min(64, buf.readVarInt());
            List<String> buttons = new ArrayList<>();
            for (int i = 0; i < n; i++) buttons.add(buf.readUtf(512));
            return new CivicPagePayload(title, text, buttons);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
