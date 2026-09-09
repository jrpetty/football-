package com.jrpetty.mcassistant.net;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client → server orders. The Orders screen isn't a container, so it can't ride
 * the vanilla button channel the management screen uses; this is its wire.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AssistantNetwork {

    private AssistantNetwork() {}

    /** How far away an order may be given — generous, but not unlimited. */
    private static final double ORDER_RANGE = 48.0;

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(OrderPayload.TYPE, OrderPayload.STREAM_CODEC, AssistantNetwork::handleOrder);
        registrar.playToServer(ZonePayload.TYPE, ZonePayload.STREAM_CODEC, AssistantNetwork::handleZone);
        registrar.playToServer(PlotOrderPayload.TYPE, PlotOrderPayload.STREAM_CODEC, AssistantNetwork::handlePlotOrder);
        registrar.playToClient(PlotListPayload.TYPE, PlotListPayload.STREAM_CODEC, AssistantNetwork::handlePlotList);
    }

    private static void handleOrder(OrderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity target = player.level().getEntity(payload.entityId());
            // Never trust the client: it must be a real assistant, owned by this
            // player, in this world, and close enough to be shouted at.
            if (!(target instanceof AssistantEntity assistant)) return;
            if (!assistant.isOwner(player)) return;
            if (assistant.distanceToSqr(player) > ORDER_RANGE * ORDER_RANGE) {
                assistant.say("Too far away to hear you — get closer.");
                return;
            }
            AssistantActions.apply(assistant, player, payload.action());
        });
    }

    /** A zone drawn on the map. Looser range than a spoken order — the map
     *  legitimately shows the whole operation — but the same ownership rule,
     *  a hard cap on the footprint, and the server picks the Y itself. */
    private static void handleZone(ZonePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity target = player.level().getEntity(payload.entityId());
            if (!(target instanceof AssistantEntity assistant)) return;
            if (!assistant.isOwner(player)) return;
            if (assistant.distanceToSqr(player) > 256.0 * 256.0) return;
            int minX = Math.min(payload.minX(), payload.maxX());
            int maxX = Math.max(payload.minX(), payload.maxX());
            int minZ = Math.min(payload.minZ(), payload.maxZ());
            int maxZ = Math.max(payload.minZ(), payload.maxZ());
            if (maxX - minX < 3 || maxZ - minZ < 3) {
                assistant.say("That plot's too small to work — drag a bigger box.");
                return;
            }
            if (maxX - minX > 96 || maxZ - minZ > 96) {
                assistant.say("That's more ground than I can work — keep it under 96 blocks a side.");
                return;
            }
            int y = assistant.blockPosition().getY();
            com.jrpetty.mcassistant.entity.WorkZone old = assistant.workZone();
            com.jrpetty.mcassistant.entity.WorkZone drawn =
                com.jrpetty.mcassistant.entity.WorkZone.of(
                    new net.minecraft.core.BlockPos(minX, y, minZ),
                    new net.minecraft.core.BlockPos(maxX, y, maxZ),
                    old != null ? old.depth() : com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH);
            // Drawn ground is staked ground: it goes in the plot book named,
            // like the wand's, so the plots menu sees every field however it
            // was marked.
            com.jrpetty.mcassistant.ZonePresets.Preset staked =
                com.jrpetty.mcassistant.ZonePresets.stake(player, drawn, assistant.stationTask());
            assistant.assignPlot(drawn, staked.name());
            assistant.say("\"" + staked.name() + "\" it is — " + drawn.describe() + ".");
        });
    }

    /**
     * The plots menu. One serverbound payload carries every button on it; the
     * answer to all of them is the same — the refreshed plot book — so the
     * open screen updates itself after each click.
     */
    /** The last bot ordered from the book, per owner — what Undo takes back. */
    private static final java.util.Map<java.util.UUID, Integer> lastOrdered =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static void handlePlotOrder(PlotOrderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            switch (payload.op()) {
                case 1 -> {   // stake a plot where the player stands
                    com.jrpetty.mcassistant.ZonePresets.Preset staked =
                        com.jrpetty.mcassistant.ZonePresets.stake(player,
                            com.jrpetty.mcassistant.entity.WorkZone.around(
                                player.blockPosition(), 12,
                                com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH),
                            AssistantEntity.StationTask.NONE);
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Staked \"" + staked.name() + "\" — 25 by 25, right here."), true);
                }
                case 2 -> {   // toggle a bot on a plot
                    com.jrpetty.mcassistant.ZonePresets.Preset preset =
                        com.jrpetty.mcassistant.ZonePresets.byName(player, payload.plotName());
                    Entity target = player.level().getEntity(payload.entityId());
                    if (preset == null || !(target instanceof AssistantEntity assistant)) break;
                    if (!assistant.isOwner(player)) break;
                    if (assistant.distanceToSqr(player) > 256.0 * 256.0) break;
                    lastOrdered.put(player.getUUID(), assistant.getId());
                    if (preset.zone().equals(assistant.workZone())) {
                        assistant.leavePlot();
                        assistant.say("Off \"" + preset.name() + "\" — waiting on your word.");
                    } else {
                        com.jrpetty.mcassistant.ZonePresets.applyGround(assistant, preset);
                        // Bare ground learns from its first worker, so the plot
                        // row stops reading as untyped the moment someone with
                        // a trade steps onto it.
                        if (preset.job() == AssistantEntity.StationTask.NONE
                            && assistant.stationTask() != AssistantEntity.StationTask.NONE) {
                            com.jrpetty.mcassistant.ZonePresets.stake(
                                player, preset.zone(), assistant.stationTask());
                        }
                        assistant.say("On \"" + preset.name() + "\" now"
                            + (assistant.stationTask() == AssistantEntity.StationTask.NONE
                               ? " — set the plot's trade (or mine) and I'll get to it." : "."));
                    }
                }
                case 3 -> com.jrpetty.mcassistant.ZonePresets.forget(player, payload.plotName());
                case 4 -> com.jrpetty.mcassistant.ZonePresets.rename(player, payload.plotName());
                case 6 -> {   // copy the picked plot as a blueprint
                    com.jrpetty.mcassistant.ZonePresets.Preset preset =
                        com.jrpetty.mcassistant.ZonePresets.byName(player, payload.plotName());
                    if (preset != null) {
                        com.jrpetty.mcassistant.ZonePresets.copyBlueprint(player, preset);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "Copied \"" + preset.name() + "\" — stand where the new one goes and press Stamp."), true);
                    }
                }
                case 7 -> {   // stamp the blueprint where the player stands
                    com.jrpetty.mcassistant.ZonePresets.Preset staked =
                        com.jrpetty.mcassistant.ZonePresets.stampBlueprint(player);
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        staked == null
                            ? "Nothing copied yet — pick a plot and press Copy first."
                            : "Stamped \"" + staked.name() + "\" — chest spots are lit for a moment."), true);
                }
                case 9 -> {   // every free hand onto the picked plot
                    com.jrpetty.mcassistant.ZonePresets.Preset preset =
                        com.jrpetty.mcassistant.ZonePresets.byName(player, payload.plotName());
                    if (preset != null) {
                        int put = 0;
                        for (AssistantEntity mate : AssistantEntity.allFor(player.getUUID())) {
                            if (!mate.isAlive() || mate.workZone() != null
                                || !mate.patchName().isEmpty()) continue;
                            // A hauler with a linked route is working, not free.
                            if (mate.stationTask() == AssistantEntity.StationTask.HAUL
                                && mate.preferredChest() != null
                                && mate.deliveryChest() != null) continue;
                            if (mate.distanceToSqr(player) > 256.0 * 256.0) continue;
                            com.jrpetty.mcassistant.ZonePresets.applyGround(mate, preset);
                            mate.say("On \"" + preset.name() + "\" with the rest.");
                            put++;
                        }
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            put == 0 ? "Nobody's free to send."
                                : put + (put == 1 ? " hand" : " hands")
                                    + " put on \"" + preset.name() + "\"."), true);
                    }
                }
                case 10 -> {   // take back the last order given from the book
                    Integer id = lastOrdered.get(player.getUUID());
                    AssistantEntity bot = id != null
                        && player.level().getEntity(id) instanceof AssistantEntity a
                        && a.isAlive() ? a : null;
                    if (bot == null || !bot.undoOrder()) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "Nothing to take back yet."), true);
                    } else {
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            bot.getAssistantName() + " goes back to how they stood."), true);
                    }
                }
                case 8 -> {   // the plot's working hours
                    AssistantEntity.Shift next =
                        com.jrpetty.mcassistant.ZonePresets.cycleShift(player, payload.plotName());
                    if (next != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "\"" + payload.plotName() + "\" works " + next.label + " now."), true);
                    }
                }
                case 5 -> {   // give the plot a trade — the ground defines the work
                    AssistantEntity.StationTask next =
                        com.jrpetty.mcassistant.ZonePresets.cycleTrade(player, payload.plotName());
                    if (next != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "\"" + payload.plotName() + "\" is "
                            + (next == AssistantEntity.StationTask.NONE
                               ? "bare ground now — workers keep their own trades."
                               : next.label + " ground now.")), true);
                    }
                }
                default -> { }
            }
            sendPlotBook(player);
        });
    }

    /** The plot book as the menu shows it, freshly counted: workers, what
     *  they banked today, and the ground's condition score. */
    public static void sendPlotBook(ServerPlayer player) {
        // Ground worked from before the book existed — or set by any path the
        // book ever missed — is ADOPTED the moment the book opens: every area
        // a bot is working shows up here, named and ready to crew. Overlapping
        // ground counts as known, so two bots on drifted zones can never make
        // the book flip an entry back and forth between them.
        for (AssistantEntity mate : AssistantEntity.allFor(player.getUUID())) {
            if (!mate.isAlive() || mate.workZone() == null) continue;
            boolean known = false;
            for (com.jrpetty.mcassistant.ZonePresets.Preset p
                    : com.jrpetty.mcassistant.ZonePresets.list(player)) {
                if (p.zone().equals(mate.workZone())
                    || p.zone().containsColumn(mate.workZone().center())
                    || mate.workZone().containsColumn(p.zone().center())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                com.jrpetty.mcassistant.ZonePresets.Preset adopted =
                    com.jrpetty.mcassistant.ZonePresets.stake(
                        player, mate.workZone(), mate.stationTask());
                mate.adoptPatchName(adopted.name());
            }
        }
        java.util.List<PlotListPayload.Entry> entries = new java.util.ArrayList<>();
        for (com.jrpetty.mcassistant.ZonePresets.Preset p
                : com.jrpetty.mcassistant.ZonePresets.list(player)) {
            int workers = 0, yield = 0;
            for (AssistantEntity mate : AssistantEntity.allFor(player.getUUID())) {
                if (mate.isAlive() && p.zone().equals(mate.workZone())) {
                    workers++;
                    yield += mate.producedTodayTotal();
                }
            }
            entries.add(new PlotListPayload.Entry(
                p.name(),
                p.job() == AssistantEntity.StationTask.NONE ? "" : p.job().title,
                p.zone().min().getX(), p.zone().min().getZ(),
                p.zone().max().getX(), p.zone().max().getZ(),
                p.zone().depth(),
                workers,
                yield,
                com.jrpetty.mcassistant.ZonePresets.health(player.serverLevel(), p),
                com.jrpetty.mcassistant.ZonePresets.chestFill(player.serverLevel(), p),
                p.shift().label));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
            player, new PlotListPayload(entries));
    }

    /** Clientbound: the plot book arrives — hand it to the client side. The
     *  class behind this call only loads on the client, and a dedicated server
     *  never receives a clientbound payload in the first place. */
    private static void handlePlotList(PlotListPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.jrpetty.mcassistant.client.PlotsClient.showPlots(payload.plots()));
    }
}
