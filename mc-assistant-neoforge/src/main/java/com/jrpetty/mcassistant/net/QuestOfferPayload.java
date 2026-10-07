package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [quests] What a player can say to the folk it is talking to about its quests, as the talk screen's buttons: one a
 * line, "label|TOPIC|text|tip" ("I'll do it|QUEST_YES||Take on: Lost at Dusk", a choice's key as its text). Sent just
 * before the folk's answer, so the screen built from the answer has them; empty, the screen has none.
 */
public record QuestOfferPayload(int entityId, String choices) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<QuestOfferPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "quest_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestOfferPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId());
            buf.writeUtf(p.choices(), 4096);
        },
        buf -> new QuestOfferPayload(buf.readVarInt(), buf.readUtf(4096)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
