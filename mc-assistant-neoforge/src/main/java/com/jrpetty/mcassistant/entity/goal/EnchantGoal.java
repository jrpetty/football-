package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Enchanting, done fairly: the assistant banks XP from its OWN kills, ore,
 * and smelting (see AssistantEntity#awardXp), then spends it — with lapis —
 * at a real enchanting table to put sensible enchantments on its gear. No
 * cheating: each enchant level costs XP and lapis it actually earned/carries.
 */
public class EnchantGoal extends Goal {

    private static final int XP_PER_LEVEL = 40;
    private static final int LAPIS_PER_LEVEL = 1;

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos tablePos;
    private Predicate<ItemStack> wanted = s -> true;
    private int enchantsApplied;
    private int stuckTicks;
    private int myGen;

    public EnchantGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.ENCHANT && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null
            && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.enchantsApplied = 0;
        this.stuckTicks = 0;
        this.wanted = filterFor(job != null ? job.arg() : null);

        this.tablePos = findTable();
        if (tablePos == null) {
            finish("No enchanting table within 16 blocks — craft or place one first.");
            return;
        }
        if (assistant.getXp() < XP_PER_LEVEL) {
            finish("Not enough experience yet (" + assistant.getXp() + " xp) — I earn it by fighting, mining ore, and smelting.");
            return;
        }
        if (assistant.countMatching(s -> s.is(Items.LAPIS_LAZULI)) < LAPIS_PER_LEVEL) {
            finish("I need lapis lazuli to enchant — give me some or \"grab lapis from the chest\".");
            return;
        }
        assistant.say("Heading to the enchanting table (" + assistant.getXp() + " xp banked).");
    }

    @Override
    public void stop() {
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        this.tablePos = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || tablePos == null) return;
        if (!assistant.level().getBlockState(tablePos).is(Blocks.ENCHANTING_TABLE)) {
            finish("The enchanting table is gone.");
            return;
        }

        double distSq = assistant.getEyePosition().distanceToSqr(
            tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    tablePos.getX() + 0.5, tablePos.getY(), tablePos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 140) finish("I couldn't reach the enchanting table.");
            return;
        }

        if (assistant.tickCount % 12 != 0) return; // one enchant every ~0.6s

        if (!enchantOne()) {
            finish(enchantsApplied > 0
                ? "Done — applied " + enchantsApplied + " enchantment" + (enchantsApplied == 1 ? "" : "s")
                    + ". " + assistant.getXp() + " xp left."
                : "Nothing to enchant right now (need affordable gear that isn't already enchanted).");
        }
    }

    /** Apply one affordable enchant to one eligible item. Returns false when done. */
    private boolean enchantOne() {
        for (ItemStack stack : allGear()) {
            if (stack.isEmpty() || !wanted.test(stack)) continue;
            ResourceKey<Enchantment> ench = pickEnchant(stack);
            if (ench == null) continue;
            Holder<Enchantment> holder = holder(ench);
            if (holder == null || EnchantmentHelper.getItemEnchantmentLevel(holder, stack) > 0) continue;

            int lapis = assistant.countMatching(s -> s.is(Items.LAPIS_LAZULI));
            int maxByXp = assistant.getXp() / XP_PER_LEVEL;
            int level = Math.min(Math.min(maxLevel(ench), maxByXp), lapis / LAPIS_PER_LEVEL);
            if (level < 1) return false; // can't afford any more

            assistant.spendXp(level * XP_PER_LEVEL);
            assistant.removeMatching(s -> s.is(Items.LAPIS_LAZULI), level * LAPIS_PER_LEVEL);
            stack.enchant(holder, level);
            assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            enchantsApplied++;
            assistant.say("Enchanted " + stack.getHoverName().getString() + " with "
                + enchName(ench) + " " + level + ".");
            return true;
        }
        return false;
    }

    /** Live gear references (worn + carried) so enchants apply in place. */
    private java.util.List<ItemStack> allGear() {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            out.add(assistant.getItemBySlot(slot));
        }
        out.addAll(assistant.getInventoryItems());
        return out;
    }

    @Nullable
    private Holder<Enchantment> holder(ResourceKey<Enchantment> key) {
        try {
            return assistant.level().registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** The primary enchant an item wants next (null if none applicable). */
    @Nullable
    private static ResourceKey<Enchantment> pickEnchant(ItemStack s) {
        String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (p.endsWith("_sword")) return Enchantments.SHARPNESS;
        if (s.is(Items.BOW)) return Enchantments.POWER;
        if (p.endsWith("_pickaxe") || p.endsWith("_axe") || p.endsWith("_shovel") || p.endsWith("_hoe")) {
            return Enchantments.EFFICIENCY;
        }
        if (s.getItem() instanceof ArmorItem || p.endsWith("_helmet") || p.endsWith("_chestplate")
            || p.endsWith("_leggings") || p.endsWith("_boots")) {
            return Enchantments.PROTECTION;
        }
        return null;
    }

    private static int maxLevel(ResourceKey<Enchantment> ench) {
        if (ench == Enchantments.PROTECTION) return 4;
        return 5; // sharpness / efficiency / power
    }

    private static String enchName(ResourceKey<Enchantment> ench) {
        return ench.location().getPath().replace('_', ' ');
    }

    private Predicate<ItemStack> filterFor(@Nullable String arg) {
        if (arg == null) return s -> true;
        String w = arg.trim().toLowerCase();
        return switch (w) {
            case "", "gear", "all", "everything", "kit" -> s -> true;
            case "tool", "tools" -> s -> {
                String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                return p.endsWith("_pickaxe") || p.endsWith("_axe") || p.endsWith("_shovel") || p.endsWith("_hoe");
            };
            case "weapon", "weapons", "sword" -> s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_sword") || s.is(Items.BOW);
            case "armor", "armour" -> s -> s.getItem() instanceof ArmorItem;
            default -> s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains(w);
        };
    }

    @Nullable
    private BlockPos findTable() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-16, -4, -16), feet.offset(16, 4, 16))) {
            if (!assistant.level().getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
