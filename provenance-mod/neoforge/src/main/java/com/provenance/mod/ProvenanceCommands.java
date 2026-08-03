package com.provenance.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.provenance.core.ItemRecord;
import com.provenance.core.TransferService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Player and operator commands.
 *
 * <p>The transfer pair is the safe hand-to-hand route: the owner offers, the
 * receiver accepts, and only then does registered ownership move. Merely
 * handing someone the item does nothing.
 */
public final class ProvenanceCommands {

    private ProvenanceCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("provenance");

        root.then(Commands.literal("transfer")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer sender = context.getSource().getPlayerOrException();
                            ServerPlayer receiver = EntityArgument.getPlayer(context, "player");
                            return offerTransfer(sender, receiver);
                        })));

        root.then(Commands.literal("accept")
                .executes(context -> confirmTransfer(context.getSource().getPlayerOrException())));

        root.then(Commands.literal("cancel")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ItemRecord record = heldRecord(player);
                    if (record == null) {
                        return feedback(player, "That item has no provenance record.");
                    }
                    ProvenanceState.get().transfers().cancel(record.recordId());
                    return feedback(player, "Transfer cancelled.");
                }));

        // Operator tools.
        root.then(Commands.literal("admin")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("claim")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    ServerPlayer operator = context.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    ItemRecord record = heldRecord(operator);
                                    if (record == null) {
                                        return feedback(operator, "That item has no provenance record.");
                                    }
                                    boolean claimed = ProvenanceState.get().transfers().adminClaim(
                                            record, target.getUUID(), target.getGameProfile().getName(), true);
                                    return feedback(operator, claimed
                                            ? "Ownership assigned to " + target.getGameProfile().getName() + "."
                                            : "Refused: that item already has a registered owner.");
                                })))
                .then(Commands.literal("restore")
                        .executes(context -> {
                            ServerPlayer operator = context.getSource().getPlayerOrException();
                            ItemStack held = operator.getMainHandItem();
                            ItemRecord record = held.isEmpty() ? null : Stamps.peek(held);
                            if (record == null) {
                                return feedback(operator, "Hold the item you want to restore.");
                            }
                            if (record.forkedFromRecordId() == null) {
                                return feedback(operator,
                                        "That item was not split off another record, so there is nothing to restore.");
                            }
                            var restored = ProvenanceState.get().registry().restoreFork(record);
                            if (restored == null) {
                                return feedback(operator, "The original record is gone; cannot restore.");
                            }
                            Stamps.writeStamp(held, restored);
                            return feedback(operator, "Restored. The item's original history is back.");
                        }))
                .then(Commands.literal("register")
                        .then(Commands.argument("maker", StringArgumentType.string())
                                .executes(context -> registerHeld(
                                        context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "maker"), null))
                                .then(Commands.argument("manufacturer", StringArgumentType.greedyString())
                                        .executes(context -> registerHeld(
                                                context.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(context, "maker"),
                                                StringArgumentType.getString(context, "manufacturer"))))))
                .then(Commands.literal("stats")
                        .executes(context -> {
                            ProvenanceState state = ProvenanceState.get();
                            if (state == null) {
                                return 0;
                            }
                            var source = context.getSource();
                            var store = state.store();
                            var config = state.config();

                            source.sendSuccess(() -> Component.literal(
                                    "§6Provenance — store"
                                            + "\n§7 resident records: §f" + store.cachedCount()
                                            + " §7/ " + store.maxCachedRecords()
                                            + "\n§7 awaiting write:  §f" + store.dirtyCount()
                                            + "\n§7 last flush:      §f" + store.lastFlushWritten()
                                            + " record(s) in " + store.lastFlushDurationMillis() + " ms"
                                            + "\n§7 written total:   §f" + store.totalWritten()
                                            + "\n§6Provenance — buffers"
                                            + "\n§7 usage entries:   §f" + state.usage().pendingEntries()
                                            + "\n§7 open repairs:   §f" + state.repairs().openSessionCount()
                                            + "\n§7 pending trades: §f" + state.transfers().pendingCount()
                                            + "\n§7 tracked movers: §f" + state.distances().trackedPlayerCount()
                                            + "\n§6Provenance — tuning"
                                            + "\n§7 sample interval: §f" + config.distanceIntervalTicks() + " ticks"
                                            + "\n§7 flush interval:  §f" + config.usageFlushIntervalSeconds() + " s"
                                            + "\n§7 retention:       §f" + config.destroyedRecordRetentionDays()
                                            + " days"), false);

                            // Counting files walks every shard, so it happens
                            // off-thread and reports back when it is done.
                            state.countOnDiskAsync(count -> source.sendSuccess(() -> Component.literal(
                                    "§7 records on disk: §f" + (count < 0 ? "unavailable" : count)), false));
                            return 1;
                        }))
                .then(Commands.literal("flush")
                        .executes(context -> {
                            ProvenanceState state = ProvenanceState.get();
                            if (state != null) {
                                state.store().flush();
                            }
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Provenance records flushed."), true);
                            return 1;
                        }))
                .then(Commands.literal("verify")
                        .executes(context -> {
                            ServerPlayer operator = context.getSource().getPlayerOrException();
                            ItemRecord record = heldRecord(operator);
                            if (record == null) {
                                return feedback(operator, "That item has no provenance record.");
                            }
                            boolean consistent = record.verifyOverallMatchesContributors();
                            return feedback(operator, consistent
                                    ? "Consistent: overall totals match contributor totals."
                                    : "INCONSISTENT: overall totals disagree with contributors.");
                        })));

        event.getDispatcher().register(root);
    }

    private static int offerTransfer(ServerPlayer sender, ServerPlayer receiver) {
        ProvenanceState state = ProvenanceState.get();
        ItemRecord record = heldRecord(sender);
        if (state == null || record == null) {
            return feedback(sender, "Hold the item you want to transfer.");
        }

        TransferService.Result result = state.transfers().propose(
                record, sender.getUUID(), receiver.getUUID(),
                receiver.getGameProfile().getName(), System.currentTimeMillis());

        if (result == TransferService.Result.NOT_THE_OWNER) {
            return feedback(sender, "You are not the registered owner of that item.");
        }

        // The sender's own confirmation is implicit in issuing the offer.
        state.transfers().confirm(record, sender.getUUID(), System.currentTimeMillis());

        feedback(sender, "Offer sent. " + receiver.getGameProfile().getName()
                + " must run /provenance accept while holding it.");
        feedback(receiver, sender.getGameProfile().getName()
                + " has offered you ownership. Hold the item and run /provenance accept.");
        return 1;
    }

    private static int confirmTransfer(ServerPlayer receiver) {
        ProvenanceState state = ProvenanceState.get();
        ItemRecord record = heldRecord(receiver);
        if (state == null || record == null) {
            return feedback(receiver, "Hold the item you were offered.");
        }

        TransferService.Result result = state.transfers().confirm(
                record, receiver.getUUID(), System.currentTimeMillis());

        return switch (result) {
            case TRANSFERRED -> feedback(receiver, "Ownership transferred. The item's history is unchanged.");
            case AWAITING_OTHER_PARTY -> feedback(receiver, "Waiting on the other party to confirm.");
            case NOT_A_PARTY -> feedback(receiver, "That offer is not addressed to you.");
            case NOT_THE_OWNER -> feedback(receiver, "The sender no longer owns that item.");
            case NO_PENDING_TRANSFER -> feedback(receiver, "There is no pending offer for that item.");
        };
    }

    /**
     * Gives a held, not-yet-tracked item a proper creation record.
     *
     * <p>For equipment that enters the world by a route this mod cannot hook:
     * a kit, a shop, a command, or a workbench belonging to another mod that
     * fires no craft event. Without this, such an item is adopted on first use
     * with an honest but bleak "maker unknown".
     *
     * <p>Deliberately refuses an item that already has a record. Creation facts
     * are immutable, and an operator being able to rewrite who forged a relic
     * would undo the guarantee the whole system rests on. Attribution happens
     * once, before first use, or not at all.
     *
     * @param maker        a player name, or any maker name for issued equipment
     * @param manufacturer optional company shown on the Manufactured by line
     */
    private static int registerHeld(ServerPlayer operator, String maker, String manufacturer) {
        ProvenanceState state = ProvenanceState.get();
        ItemStack held = operator.getMainHandItem();
        if (state == null || held.isEmpty()) {
            return feedback(operator, "Hold the item you want to register.");
        }

        var category = Stamps.categoryOf(state.config(), held);
        if (category == null) {
            return feedback(operator, "That item is not tracked by Provenance.");
        }
        if (Stamps.readStamp(held) != null) {
            return feedback(operator, "That item already has a history, and creation facts cannot be rewritten. "
                    + "Register an item before it is first used.");
        }

        // A named player becomes a real UUID-keyed maker; anything else is
        // recorded as a name only, which is what an issuing body should be.
        String itemId = Stamps.itemId(held);
        ServerPlayer namedPlayer = operator.server.getPlayerList().getPlayerByName(maker);
        String company = manufacturer != null ? manufacturer : state.config().manufacturerFor(itemId);

        ItemRecord record = state.registry().registerCrafted(
                itemId, category,
                namedPlayer == null ? null : namedPlayer.getUUID(), maker,
                company == null ? null : com.provenance.core.RecordRegistry.slug(company), company);

        Stamps.writeStamp(held, com.provenance.core.ItemStamp.of(record));
        Stamps.refreshSummary(held, record);

        return feedback(operator, company == null
                ? "Registered as made by " + maker + "."
                : "Registered as made by " + maker + " of " + company + ".");
    }

    private static ItemRecord heldRecord(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        return stack.isEmpty() ? null : Stamps.peek(stack);
    }

    private static int feedback(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
        return 1;
    }
}
