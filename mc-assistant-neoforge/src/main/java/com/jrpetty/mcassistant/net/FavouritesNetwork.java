package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Favourites;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
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
 * [ui] The wire for a player's favourites (entity/Favourites): the screen and the stars ask, the server answers that
 * player alone. A list or a folk's detail is built only when asked (the screen asks on opening and every few seconds
 * while it stays open), and a refresh asked for sooner than {@link #MIN_GAP} after the last is dropped. A milestone of
 * a followed folk goes out as it happens, to whoever follows it and is on line.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class FavouritesNetwork {

    private FavouritesNetwork() {}

    /** The least time between two refreshes for one player, in game ticks. */
    static final long MIN_GAP = 10L;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(FavouritesAskPayload.TYPE, FavouritesAskPayload.STREAM_CODEC, FavouritesNetwork::handleAsk);
        registrar.playToClient(FavouritesPayload.TYPE, FavouritesPayload.STREAM_CODEC, FavouritesNetwork::handleAnswer);
        Favourites.onNotice(FavouritesNetwork::notice);
    }

    private static void handleAsk(FavouritesAskPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            FavouritesPayload out = answer(player, payload);
            if (out != null) PacketDistributor.sendToPlayer(player, out);
        });
    }

    /** The answer to an ask, to send, or null (asked again too soon, or nothing to say). */
    @Nullable
    public static FavouritesPayload answer(ServerPlayer player, FavouritesAskPayload p) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        long now = level.getGameTime();
        switch (p.action()) {
            case "list", "detail" -> {
                boolean refresh = p.arg() == 0;
                Long last = LAST.get(player.getUUID());
                if (refresh && last != null && now - last < MIN_GAP && now >= last) return null;
                LAST.put(player.getUUID(), now);
                CompoundTag t = p.action().equals("list") ? Favourites.listTag(player) : Favourites.detailTag(player, p.folk());
                return t == null ? null : new FavouritesPayload(t, false);
            }
            case "add", "remove", "toggle" -> {
                boolean on = Favourites.has(player.getUUID(), p.folk());
                String said;
                if (p.action().equals("remove") || p.action().equals("toggle") && on) {
                    Favourites.remove(player.getUUID(), p.folk());
                    said = "Taken off your favourites.";
                } else {
                    VillageFolkEntity f = find(player.getServer(), p.folk());
                    if (f == null) return star(p.folk(), on, "Too far away to add just now.");
                    Favourites.Added a = Favourites.add(player, f);
                    said = a == Favourites.Added.FULL ? "Your favourites are full (" + Favourites.CAP + "): take one off first."
                        : f.displayNameCap() + " is on your favourites.";
                }
                return star(p.folk(), Favourites.has(player.getUUID(), p.folk()), said);
            }
            case "move" -> {
                Favourites.move(player.getUUID(), p.folk(), p.arg());
                LAST.put(player.getUUID(), now);
                return new FavouritesPayload(Favourites.listTag(player), false);
            }
            default -> {
                return null;
            }
        }
    }

    private static FavouritesPayload star(UUID folk, boolean on, String said) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", "star");
        t.putUUID("uuid", folk);
        t.putBoolean("fav", on);
        t.putString("said", said);
        return new FavouritesPayload(t, false);
    }

    @Nullable
    private static VillageFolkEntity find(@Nullable MinecraftServer server, UUID id) {
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof VillageFolkEntity f && f.isAlive()) return f;
        }
        return null;
    }

    /** Open the favourites screen on this player's client: the list, or one folk's detail (the command, the pictures). */
    public static void show(ServerPlayer player, @Nullable UUID folk) {
        CompoundTag t = folk == null ? Favourites.listTag(player) : Favourites.detailTag(player, folk);
        if (t == null) return;
        LAST.put(player.getUUID(), player.level().getGameTime());
        PacketDistributor.sendToPlayer(player, new FavouritesPayload(t, true));
    }

    /** Shut the favourites screen on this player's client, if it is up. */
    public static void close(ServerPlayer player) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", "close");
        PacketDistributor.sendToPlayer(player, new FavouritesPayload(t, false));
    }

    private static void notice(Favourites.Notice n) {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(n.player());
        if (p == null) return;
        CompoundTag t = new CompoundTag();
        t.putString("kind", "toast");
        t.putUUID("uuid", n.folk());
        t.putString("name", n.name());
        t.putString("event", n.kind());
        t.putString("text", n.text());
        PacketDistributor.sendToPlayer(p, new FavouritesPayload(t, false));
    }

    /** The world has closed (SessionReset): forget who was sent what. */
    public static void reset() {
        LAST.clear();
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handleAnswer(FavouritesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.FavouritesClient.receive(payload.data(), payload.open()));
    }
}
