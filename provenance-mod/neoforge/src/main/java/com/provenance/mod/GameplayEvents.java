package com.provenance.mod;

import com.provenance.core.ArmourDamageAllocator;
import com.provenance.core.BreakdownKind;
import com.provenance.core.ItemCategory;
import com.provenance.core.ItemRecord;
import com.provenance.core.MilestoneAward;
import com.provenance.core.RecordRegistry;
import com.provenance.core.RepairSessionTracker;
import com.provenance.core.StatKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Translates gameplay into record updates.
 *
 * <p>Everything here is event-driven. Nothing scans inventories, and nothing
 * iterates tracked items on a tick — see {@link TickEvents} for the only
 * periodic work, which is batched.
 */
public final class GameplayEvents {

    /**
     * Damage is stored in hundredths of a heart so the totals stay integral
     * without losing the sub-heart resolution armour calculations produce.
     */
    private static final double DAMAGE_SCALE = 100.0d;

    private GameplayEvents() {
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        ItemStack stack = event.getCrafting();
        ItemCategory category = Stamps.categoryOf(state.config(), stack);
        if (category == null) {
            return;
        }

        // An upgraded item arrives already stamped, because the smithing
        // handler carried the identity across. Do not re-register it.
        if (Stamps.readStamp(stack) != null) {
            return;
        }

        CompanyBridge.Company company = CompanyBridge.Holder.get()
                .companyForCraft(player, event.getInventory().getClass().getSimpleName());

        ItemRecord record = state.registry().registerCrafted(
                Stamps.itemId(stack), category,
                player.getUUID(), player.getGameProfile().getName(),
                company == null ? null : company.id(),
                company == null ? null : company.name());

        Stamps.writeStamp(stack, com.provenance.core.ItemStamp.of(record));
    }

    // ------------------------------------------------------------------
    // Mining, digging and chopping
    // ------------------------------------------------------------------

    /**
     * Block breaking.
     *
     * <p>Runs at lowest priority and does not receive cancelled events, so a
     * break another mod or a protection plugin refused never reaches a counter.
     * By the time this fires, the break is genuinely happening.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !Stamps.shouldCount(player)) {
            return;
        }
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        // The tool actually in hand. A block broken while a different tool is
        // held is that tool's work, not the pickaxe sitting in the backpack.
        ItemStack tool = player.getMainHandItem();
        ItemRecord record = Stamps.resolve(tool, player);
        if (record == null) {
            return;
        }

        BlockState blockState = event.getState();
        String blockId = blockIdOf(blockState);
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        RecordRegistry registry = state.registry();

        switch (record.category()) {
            case PICKAXE -> {
                notify(player, registry.record(record, id, name, StatKey.BLOCKS_MINED, 1), record);
                registry.recordBreakdown(record, id, name, BreakdownKind.BLOCK_MINED, blockId, 1);
                if (isOre(blockState)) {
                    registry.record(record, id, name, StatKey.ORES_MINED, 1);
                }
                registry.record(record, id, name, StatKey.DEEPEST_BLOCK_Y, event.getPos().getY());
            }
            case AXE -> {
                registry.record(record, id, name, StatKey.BLOCKS_CHOPPED, 1);
                if (isLog(blockState)) {
                    notify(player, registry.record(record, id, name, StatKey.LOGS_CHOPPED, 1), record);
                    registry.recordBreakdown(record, id, name, BreakdownKind.WOOD_CHOPPED, blockId, 1);
                }
            }
            case SHOVEL -> {
                notify(player, registry.record(record, id, name, StatKey.BLOCKS_DUG, 1), record);
                registry.recordBreakdown(record, id, name, BreakdownKind.BLOCK_DUG, blockId, 1);
            }
            case SHEARS -> {
                notify(player, registry.record(record, id, name, StatKey.UTILITY_USES, 1), record);
                registry.record(record, id, name, StatKey.BLOCKS_CUT, 1);
            }
            case HOE -> {
                notify(player, registry.record(record, id, name, StatKey.BLOCKS_WORKED, 1), record);
                registry.recordBreakdown(record, id, name, BreakdownKind.CROP_WORKED, blockId, 1);
                registry.record(record, id, name, StatKey.CROPS_HARVESTED, 1);
            }
            default -> {
                // A sword or bow used to break a block is not doing its job;
                // nothing category-specific to record.
            }
        }
    }

    private static String blockIdOf(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "unknown" : id.toString();
    }

    private static boolean isOre(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.create(
                ResourceLocation.fromNamespaceAndPath("c", "ores")))
                || state.is(net.minecraft.tags.BlockTags.create(
                ResourceLocation.fromNamespaceAndPath("forge", "ores")));
    }

    private static boolean isLog(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LOGS);
    }

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------

    /** Damage dealt by a player's weapon, and the hit that carried it. */
    @SubscribeEvent
    public static void onDamageDealt(LivingDamageEvent.Post event) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        long amount = Math.round(event.getNewDamage() * DAMAGE_SCALE);
        if (amount <= 0) {
            return;
        }

        creditAttacker(event, state, amount);
        creditArmour(event, state);
    }

    private static void creditAttacker(LivingDamageEvent.Post event, ProvenanceState state, long amount) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker) || !Stamps.shouldCount(attacker)) {
            return;
        }

        ItemStack weapon = attacker.getMainHandItem();
        ItemRecord record = Stamps.resolve(weapon, attacker);
        if (record == null) {
            return;
        }

        UUID id = attacker.getUUID();
        String name = attacker.getGameProfile().getName();
        RecordRegistry registry = state.registry();

        registry.record(record, id, name, StatKey.DAMAGE_DEALT, amount);

        boolean ranged = event.getSource().getDirectEntity() != event.getSource().getEntity();
        if (ranged) {
            registry.record(record, id, name, StatKey.PROJECTILE_HITS, 1);
        } else {
            registry.record(record, id, name, StatKey.HITS_LANDED, 1);
        }
    }

    /**
     * Armour absorption.
     *
     * <p>The prevented amount is the difference between the damage that was
     * originally incoming and what actually landed. It is then split across the
     * worn pieces in proportion to their protection, so one event is recorded
     * once across the set rather than four times over.
     */
    private static void creditArmour(LivingDamageEvent.Post event, ProvenanceState state) {
        if (!(event.getEntity() instanceof ServerPlayer victim) || !Stamps.shouldCount(victim)) {
            return;
        }

        long prevented = Math.round(
                Math.max(0.0d, event.getOriginalDamage() - event.getNewDamage()) * DAMAGE_SCALE);
        if (prevented <= 0) {
            return;
        }

        List<ArmourDamageAllocator.Piece> pieces = new ArrayList<>(4);
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack piece = victim.getItemBySlot(slot);
            if (piece.isEmpty()) {
                continue;
            }
            ItemRecord record = Stamps.resolve(piece, victim);
            if (record == null || record.category() != ItemCategory.ARMOUR) {
                continue;
            }
            pieces.add(new ArmourDamageAllocator.Piece(record, protectionWeight(piece, slot)));
        }

        if (pieces.isEmpty()) {
            return;
        }

        UUID id = victim.getUUID();
        String name = victim.getGameProfile().getName();
        RecordRegistry registry = state.registry();

        for (ArmourDamageAllocator.Allocation allocation : ArmourDamageAllocator.allocate(pieces, prevented)) {
            notify(victim, registry.record(allocation.record(), id, name,
                    StatKey.DAMAGE_ABSORBED, allocation.amount()), allocation.record());
            registry.record(allocation.record(), id, name, StatKey.HITS_RECEIVED, 1);
        }
    }

    /**
     * How much of the mitigation this piece was responsible for. Uses the
     * piece's own armour attribute where available so the split reflects the
     * real protection logic rather than an assumption.
     */
    private static double protectionWeight(ItemStack stack, EquipmentSlot slot) {
        double weight = 0.0d;
        var modifiers = stack.getAttributeModifiers();
        for (var entry : modifiers.modifiers()) {
            if (entry.slot().test(slot)
                    && entry.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)) {
                weight += entry.modifier().amount();
            }
        }
        // A piece with no armour attribute still absorbed something (enchantments,
        // for instance), so give it a floor rather than dropping it entirely.
        return weight > 0.0d ? weight : 0.5d;
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer) || !Stamps.shouldCount(killer)) {
            return;
        }

        LivingEntity victim = event.getEntity();
        if (victim instanceof Player) {
            // Ordinary mob kills only. Player kills are not a provenance
            // statistic and are not recorded.
            return;
        }

        ItemStack weapon = killer.getMainHandItem();
        ItemRecord record = Stamps.resolve(weapon, killer);
        if (record == null) {
            return;
        }

        UUID id = killer.getUUID();
        String name = killer.getGameProfile().getName();
        RecordRegistry registry = state.registry();

        notify(killer, registry.record(record, id, name, StatKey.KILLS, 1), record);

        ResourceLocation mobId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType());
        registry.recordBreakdown(record, id, name, BreakdownKind.MOB_KILLED,
                mobId == null ? "unknown" : mobId.toString(), 1);

        if (record.category() == ItemCategory.RANGED_WEAPON || record.category() == ItemCategory.TRIDENT) {
            long distanceCm = Math.round(killer.distanceTo(victim) * 100.0d);
            registry.record(record, id, name, StatKey.LONGEST_KILL_CM, distanceCm);
        }
    }

    // ------------------------------------------------------------------
    // Fishing
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onFished(ItemFishedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Stamps.shouldCount(player)) {
            return;
        }
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        ItemStack rod = player.getMainHandItem();
        ItemRecord record = Stamps.resolve(rod, player);
        if (record == null || record.category() != ItemCategory.FISHING_ROD) {
            return;
        }

        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        RecordRegistry registry = state.registry();

        notify(player, registry.record(record, id, name, StatKey.CATCHES_TOTAL, 1), record);

        for (ItemStack caught : event.getDrops()) {
            String caughtId = Stamps.itemId(caught);
            registry.recordBreakdown(record, id, name, BreakdownKind.CATCH_TYPE, caughtId, 1);
            if (caught.is(net.minecraft.tags.ItemTags.FISHES)) {
                registry.record(record, id, name, StatKey.FISH_CAUGHT, 1);
            } else if (isJunk(caught)) {
                registry.record(record, id, name, StatKey.JUNK_CAUGHT, 1);
            } else {
                registry.record(record, id, name, StatKey.TREASURE_CAUGHT, 1);
            }
        }
    }

    private static boolean isJunk(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.LEATHER_BOOTS)
                || stack.is(net.minecraft.world.item.Items.ROTTEN_FLESH)
                || stack.is(net.minecraft.world.item.Items.STICK)
                || stack.is(net.minecraft.world.item.Items.STRING)
                || stack.is(net.minecraft.world.item.Items.BOWL)
                || stack.is(net.minecraft.world.item.Items.LILY_PAD)
                || stack.is(net.minecraft.world.item.Items.BAMBOO)
                || stack.is(net.minecraft.world.item.Items.TRIPWIRE_HOOK)
                || stack.is(net.minecraft.world.item.Items.INK_SAC);
    }

    // ------------------------------------------------------------------
    // Repairs
    // ------------------------------------------------------------------

    /**
     * An anvil repair is one discrete act, attributed to whoever performed it —
     * which is not necessarily the owner.
     */
    @SubscribeEvent
    public static void onAnvilRepair(AnvilRepairEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ProvenanceState state = ProvenanceState.get();
        if (state == null) {
            return;
        }

        ItemStack output = event.getOutput();
        ItemRecord record = Stamps.resolve(output, player);
        if (record == null) {
            return;
        }

        long restored = Math.max(0, event.getLeft().getDamageValue() - output.getDamageValue());
        RepairSessionTracker.Outcome outcome = state.repairs().discreteRepair(
                record.recordId(), player.getUUID(), restored, System.currentTimeMillis());

        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        if (outcome.countsAsNewRepair()) {
            state.registry().record(record, id, name, StatKey.REPAIRS, 1);
        }
        if (outcome.durabilityRestored() > 0) {
            state.registry().record(record, id, name, StatKey.DURABILITY_RESTORED, outcome.durabilityRestored());
        }
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /** Announces newly earned tiers. Cosmetic only — nothing is granted. */
    private static void notify(ServerPlayer player, List<MilestoneAward> awards, ItemRecord record) {
        if (awards.isEmpty() || player == null) {
            return;
        }
        for (MilestoneAward award : awards) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6" + displayName(record) + " reached §e" + award.tier().displayName()));
        }
    }

    private static String displayName(ItemRecord record) {
        String custom = record.customName();
        return custom == null || custom.isEmpty() ? record.currentItemId() : custom;
    }
}
