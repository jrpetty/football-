package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpa] The role-play names in the world: over the heads and in chat (the game's own display name, set through its
 * NameFormat event on both sides, so the citizen's title still follows it), sent to every client when one changes or a
 * player logs in; and a right-click on another player with an empty hand, which opens their ID card (IdCard).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class BelongingSync {

    private BelongingSync() {}

    /** The client's copy: player to the name over its head. */
    public static final Map<UUID, String> CLIENT_NAMES = new ConcurrentHashMap<>();

    /** Every role-play name with its plate on, to the clients. */
    public record NamesPayload(CompoundTag names) implements CustomPacketPayload {
        public static final Type<NamesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "rp_names"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NamesPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeNbt(p.names()),
            buf -> {
                CompoundTag t = buf.readNbt();
                return new NamesPayload(t == null ? new CompoundTag() : t);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The mod bus: the payload's registration. */
    @EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Wire {
        private Wire() {}

        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            event.registrar("1").optional().playToClient(NamesPayload.TYPE, NamesPayload.STREAM_CODEC, BelongingSync::handle);
        }
    }

    private static void handle(NamesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.jrpetty.mcassistant.client.RpNamesClient.apply(payload.names()));
    }

    /** The names of every player online with a plate showing. */
    static CompoundTag names(MinecraftServer server) {
        CompoundTag t = new CompoundTag();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String n = Belonging.plate(p.getUUID());
            if (n != null) t.putString(p.getUUID().toString(), n);
        }
        return t;
    }

    /** Send the names to everyone online. */
    public static void broadcast(MinecraftServer server) {
        if (server == null) return;
        CompoundTag t = names(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.connection == null || !p.connection.hasChannel(NamesPayload.TYPE)) continue;
            PacketDistributor.sendToPlayer(p, new NamesPayload(t));
        }
    }

    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat e) {
        Player p = e.getEntity();
        String n = p.level().isClientSide() ? CLIENT_NAMES.get(p.getUUID()) : Belonging.plate(p.getUUID());
        if (n != null && !n.isEmpty()) e.setDisplayname(Component.literal(n));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            p.refreshDisplayName();
            broadcast(p.server);
        }
    }

    /** Right-click another player with an empty hand: their ID card, if they have signed any town's book. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getTarget() instanceof ServerPlayer other)) return;
        if (e.getHand() != InteractionHand.MAIN_HAND || !p.getMainHandItem().isEmpty()) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        IdCard.inspect(p, other);
    }
}
