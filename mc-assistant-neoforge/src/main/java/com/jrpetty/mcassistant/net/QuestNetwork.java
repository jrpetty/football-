package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * [quests] The quests' wire, registered on its own beside AssistantNetwork's: the marks over the folk and a folk's
 * quest buttons to the client, the journal's pages to it, and the journal's buttons back.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class QuestNetwork {

    private QuestNetwork() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(QuestMarksPayload.TYPE, QuestMarksPayload.STREAM_CODEC, QuestNetwork::handleMarks);
        r.playToClient(QuestOfferPayload.TYPE, QuestOfferPayload.STREAM_CODEC, QuestNetwork::handleOffer);
        r.playToClient(QuestJournalPayload.TYPE, QuestJournalPayload.STREAM_CODEC, QuestNetwork::handleJournal);
        r.playToServer(QuestActionPayload.TYPE, QuestActionPayload.STREAM_CODEC, QuestNetwork::handleAction);
    }

    /** Clientbound: the classes behind these calls only load on the client. */
    private static void handleMarks(QuestMarksPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.QuestClient.marks(payload.marks()));
    }

    private static void handleOffer(QuestOfferPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.QuestClient.offer(payload.entityId(), payload.choices()));
    }

    private static void handleJournal(QuestJournalPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.QuestClient.journal(payload.text()));
    }

    private static void handleAction(QuestActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.QuestRun.action(player, payload.what(), payload.quest());
            }
        });
    }
}
