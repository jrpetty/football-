package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [quests] The marks over the folk near a player: "id:!" for a folk with work to give it, "id:?" for one a step of
 * its quests waits on, separated by ";". Sent when they change (entity/QuestRun.marks); the renderer draws them.
 */
public record QuestMarksPayload(String marks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<QuestMarksPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "quest_marks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestMarksPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeUtf(p.marks(), 8192),
        buf -> new QuestMarksPayload(buf.readUtf(8192)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
