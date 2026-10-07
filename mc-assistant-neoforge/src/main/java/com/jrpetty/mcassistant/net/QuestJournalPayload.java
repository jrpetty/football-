package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [quests] A player's quest journal, its pages as the server wrote them (entity/QuestRun.journal): a line a quest, a
 * step or an ending page. The client opens the journal's screen on it.
 */
public record QuestJournalPayload(String text) implements CustomPacketPayload {

    public static final int MOST = 32000;

    public static final CustomPacketPayload.Type<QuestJournalPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "quest_journal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestJournalPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeUtf(p.text().length() > MOST ? p.text().substring(0, MOST) : p.text(), MOST),
        buf -> new QuestJournalPayload(buf.readUtf(MOST)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
