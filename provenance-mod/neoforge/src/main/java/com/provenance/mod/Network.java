package com.provenance.mod;

import com.provenance.core.ContributorQuery;
import com.provenance.core.ItemRecord;
import com.provenance.core.StatKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

/**
 * Client/server messaging for the Item History screen.
 *
 * <p>The traffic is deliberately one-directional in substance: the client may
 * only ask to look at an item it is holding or hovering, and the server replies
 * with a finished, read-only {@link HistorySnapshot}. No packet carries a
 * statistic, an owner or a milestone from client to server, so there is nothing
 * for a modified client to forge.
 */
public final class Network {

    private static final String VERSION = "1";

    private Network() {
    }

    /** Client asks to inspect a stack, identified by where it sits. */
    public record InspectRequest(int slot, int contributorPage, boolean sortDescending,
                                 String search) implements CustomPacketPayload {

        public static final Type<InspectRequest> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(Provenance.MOD_ID, "inspect_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, InspectRequest> CODEC =
                StreamCodec.of((buf, value) -> {
                    buf.writeVarInt(value.slot());
                    buf.writeVarInt(value.contributorPage());
                    buf.writeBoolean(value.sortDescending());
                    buf.writeUtf(value.search() == null ? "" : value.search(), 64);
                }, buf -> new InspectRequest(
                        buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server's read-only reply. */
    public record HistoryResponse(HistorySnapshot snapshot) implements CustomPacketPayload {

        public static final Type<HistoryResponse> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(Provenance.MOD_ID, "history_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryResponse> CODEC =
                StreamCodec.of((buf, value) -> HistorySnapshot.write(buf, value.snapshot()),
                        buf -> new HistoryResponse(HistorySnapshot.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Where an arriving snapshot goes on the client.
     *
     * <p>An indirection on purpose. Naming a client-only class from this
     * common-side registration would put it in the dedicated server's bytecode,
     * and the client setup installs the real handler instead.
     */
    private static volatile Consumer<HistorySnapshot> clientReceiver = snapshot -> {
    };

    /** Called from client setup only. */
    public static void setClientReceiver(Consumer<HistorySnapshot> receiver) {
        clientReceiver = receiver == null ? snapshot -> {
        } : receiver;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(InspectRequest.TYPE, InspectRequest.CODEC, Network::handleInspect);
        registrar.playToClient(HistoryResponse.TYPE, HistoryResponse.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> clientReceiver.accept(payload.snapshot())));
    }

    /**
     * Resolves the request against the player's own inventory, server-side.
     *
     * <p>The client sends a slot index, not an item identity, so it cannot ask
     * about an item it does not hold.
     */
    private static void handleInspect(InspectRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ProvenanceState state = ProvenanceState.get();
            if (state == null) {
                return;
            }

            ItemStack stack = resolveSlot(player, request.slot());
            if (stack == null || stack.isEmpty()) {
                return;
            }

            // Inspection is read-only: peek rather than claim, so looking at an
            // item never rotates its token or migrates it.
            ItemRecord record = Stamps.peek(stack);
            if (record == null) {
                // Never inspected before. Register it now so the player sees a
                // real record rather than an empty screen.
                record = Stamps.track(stack, player);
                if (record == null) {
                    return;
                }
            }

            // Inspection is off the hot path, so this is where a rename is
            // picked up.
            Stamps.refreshCustomName(stack, record);

            // Fold in whatever this item has accrued since the last flush, so
            // the screen shows totals current to the second even though records
            // are only written once a minute.
            state.flushUsage(record.recordId());

            var track = state.registry().trackFor(record);
            StatKey secondary = ContributorQuery.secondaryFor(record.category());
            ContributorQuery.Page page = ContributorQuery.page(record,
                    track.primaryStat(), secondary,
                    request.search().isEmpty() ? null : request.search(),
                    request.sortDescending(),
                    request.contributorPage(),
                    state.config().contributorPageSize());

            HistorySnapshot snapshot = HistorySnapshot.of(record, track, page, secondary,
                    state.config().reducedAnimations());
            context.reply(new HistoryResponse(snapshot));
        });
    }

    /** Slot -1 means the main hand; otherwise an index into the player's inventory. */
    private static ItemStack resolveSlot(ServerPlayer player, int slot) {
        if (slot < 0) {
            return player.getMainHandItem();
        }
        if (slot >= player.getInventory().getContainerSize()) {
            return null;
        }
        return player.getInventory().getItem(slot);
    }
}
