package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * [player15] The wire for the player's own pages (entity/PlayerLife): your life in town, your shop, starting a business,
 * your businesses, the jobs board and everything else a player can do. The server writes each page (LifePage) and the
 * client draws it (client/LifeScreen); every button comes back as an action on that page. One action, "cmd", runs one
 * of the mod's own player commands on the server for a button that always sent it as a chat command, so nothing a player
 * does needs a command typed or sent as chat (a server may ban them).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class LifeNetwork {

    private LifeNetwork() {}

    /** A page for the screen, or a word for the open one. */
    public record PagePayload(CompoundTag data) implements CustomPacketPayload {
        public static final Type<PagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "life_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PagePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeNbt(p.data()),
            buf -> {
                CompoundTag t = buf.readNbt();
                return new PagePayload(t == null ? new CompoundTag() : t);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A button pressed: the page it is on, what it does, and its arguments (with the page's choices under "f"). */
    public record ActionPayload(String page, String action, CompoundTag args) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "life_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.page(), 32);
                buf.writeUtf(p.action(), 512);
                buf.writeNbt(p.args());
            },
            buf -> {
                String page = buf.readUtf(32);
                String action = buf.readUtf(512);
                CompoundTag t = buf.readNbt();
                return new ActionPayload(page, action, t == null ? new CompoundTag() : t);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(PagePayload.TYPE, PagePayload.STREAM_CODEC, LifeNetwork::handlePage);
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.STREAM_CODEC, LifeNetwork::handleAction);
    }

    /** Clientbound: the class behind this call only loads on the client. */
    private static void handlePage(PagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.LifeScreen.show(payload.data()));
    }

    private static void handleAction(ActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.jrpetty.mcassistant.entity.PlayerLife.act(player, payload.page(), payload.action(), payload.args());
            }
        });
    }

    /** A page to this player. */
    public static void send(ServerPlayer p, CompoundTag page) {
        // A connection without the mod's channel (a test's stand-in player, a client without the mod) is sent nothing.
        if (p.connection == null || !p.connection.hasChannel(PagePayload.TYPE)) return;
        PacketDistributor.sendToPlayer(p, new PagePayload(page));
    }

    /** Client: a button's action to the server. */
    public static void act(String page, String action, CompoundTag args) {
        PacketDistributor.sendToServer(new ActionPayload(page, action, args));
    }

    /** Client: one of the mod's player commands, run on the server without being sent as a command or as chat. */
    public static void command(String line) {
        CompoundTag t = new CompoundTag();
        t.putString("line", line.startsWith("/") ? line.substring(1) : line);
        act("cmd", "run", t);
    }
}
