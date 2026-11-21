package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * [quests] The journal's buttons: give a quest up (1, with its number), or read the pages again (2). The server checks
 * the quest is the player's own before anything is done (entity/QuestRun.action).
 */
public record QuestActionPayload(int what, int quest) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<QuestActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "quest_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.what());
            buf.writeVarInt(p.quest());
        },
        buf -> new QuestActionPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
