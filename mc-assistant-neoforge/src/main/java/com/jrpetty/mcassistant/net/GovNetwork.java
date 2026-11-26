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
 * [featcivic] The wire for the Town Hall and the ballot screen (client/GovScreen): the server's page (entity/GovReport),
 * and every button back as an action (entity/GovActions). Nothing is sent as a command or as chat.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class GovNetwork {

    private GovNetwork() {}

    public record PagePayload(CompoundTag data) implements CustomPacketPayload {
        public static final Type<PagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "gov_page"));
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

    public record ActionPayload(String action, CompoundTag args) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "gov_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.action(), 64);
                buf.writeNbt(p.args());
            },
            buf -> {
                String a = buf.readUtf(64);
                CompoundTag t = buf.readNbt();
                return new ActionPayload(a, t == null ? new CompoundTag() : t);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(PagePayload.TYPE, PagePayload.STREAM_CODEC, GovNetwork::handlePage);
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.STREAM_CODEC, GovNetwork::handleAction);
    }

    private static void handlePage(PagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.GovScreen.show(payload.data()));
    }

    private static void handleAction(ActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) com.jrpetty.mcassistant.entity.GovActions.act(player, payload.action(), payload.args());
        });
    }

    /** A page to this player (none to a connection without the mod's channel: a test's stand-in player). */
    public static void send(ServerPlayer p, CompoundTag page) {
        if (p.connection == null || !p.connection.hasChannel(PagePayload.TYPE)) return;
        PacketDistributor.sendToPlayer(p, new PagePayload(page));
    }

    /** Client: a button's action to the server. */
    public static void act(String action, CompoundTag args) {
        PacketDistributor.sendToServer(new ActionPayload(action, args));
    }
}
