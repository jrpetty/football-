package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.FolkProfile;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [folk-ui] The wire for a folk's profile, registered on its own beside AssistantNetwork's: the screen asks, the server
 * answers with the profile (FolkProfile.build) for that player only. The screen asks again every few seconds while it
 * is open and never when it is shut, so the cost is one small tag every few seconds per player looking at a profile.
 * The folk is found by its id in the player's own world (a map lookup); any folk of any town may be looked at, as the
 * town's books already show everyone, but a refresh asked for sooner than {@link #MIN_GAP} after the last is dropped.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ProfileNetwork {

    private ProfileNetwork() {}

    /** The least time between two profiles for one player, in game ticks (a refresh is asked for every 60). */
    static final long MIN_GAP = 10L;
    /** When each player was last sent a profile (game time), for MIN_GAP. One entry a player; let go with the world. */
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(FolkProfileAskPayload.TYPE, FolkProfileAskPayload.STREAM_CODEC, ProfileNetwork::handleAsk);
        registrar.playToClient(FolkProfilePayload.TYPE, FolkProfilePayload.STREAM_CODEC, ProfileNetwork::handleProfile);
    }

    private static void handleAsk(FolkProfileAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) answer(player, payload);
        });
    }

    /** The answer to an ask: the profile sent, or nothing (no such folk here, or asked again too soon). */
    @Nullable
    public static FolkProfilePayload answer(ServerPlayer player, FolkProfileAskPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        long now = level.getGameTime();
        Long last = LAST.get(player.getUUID());
        if (!payload.open() && last != null && now - last < MIN_GAP && now >= last) return null;
        Entity e = level.getEntity(payload.folk());
        if (!(e instanceof VillageFolkEntity f) || !f.isAlive()) return null;
        LAST.put(player.getUUID(), now);
        FolkProfilePayload out = new FolkProfilePayload(FolkProfile.build(f, player), payload.open());
        PacketDistributor.sendToPlayer(player, out);
        return out;
    }

    /** The world has closed (SessionReset): forget who was sent what. */
    public static void reset() {
        LAST.clear();
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleProfile(FolkProfilePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.FolkProfileScreen.receive(payload.data(), payload.open()));
    }
}
