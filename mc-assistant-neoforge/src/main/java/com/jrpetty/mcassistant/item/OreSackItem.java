package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.List;

/**
 * [workitems] The ore sack: three hides of leather sewn with string, a drawstring at its neck. It holds four stacks of
 * ore, raw metal, coal, gems and the like, and nothing else, as a bundle holds anything: right-click a stack onto it (or
 * it onto a stack) in the inventory to put it in, an empty slot to take one out, and use it to tip the lot out. Carried,
 * it fills itself with what is picked up. A miner or a cave dweller with one goes home the less often (entity/WorkTools).
 */
public class OreSackItem extends Item {

    /** Stacks it holds. */
    public static final int SLOTS = 4;

    public OreSackItem(Properties properties) {
        super(properties);
    }

    /** The minerals: ores, raw metals, coal and charcoal, gems, dusts and shards, flint. */
    public static boolean mineral(ItemStack s) {
        if (s.isEmpty() || s.getItem() instanceof OreSackItem) return false;
        return s.is(Tags.Items.ORES) || s.is(Tags.Items.RAW_MATERIALS) || s.is(ItemTags.COALS) || s.is(Tags.Items.GEMS)
            || s.is(Tags.Items.DUSTS) || s.is(Items.FLINT) || s.is(Items.AMETHYST_SHARD) || s.is(Items.QUARTZ)
            || s.is(Items.CLAY_BALL);
    }

    /** What it holds, as stacks (copies). */
    public static List<ItemStack> contents(ItemStack sack) {
        List<ItemStack> out = new ArrayList<>();
        ItemContainerContents c = sack.get(DataComponents.CONTAINER);
        if (c != null) for (ItemStack s : c.nonEmptyItemsCopy()) out.add(s);
        return out;
    }

    static void set(ItemStack sack, List<ItemStack> stacks) {
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack s : stacks) if (!s.isEmpty()) kept.add(s);
        if (kept.isEmpty()) sack.remove(DataComponents.CONTAINER);
        else sack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(kept));
    }

    /** How many items it holds. */
    public static int count(ItemStack sack) {
        int n = 0;
        for (ItemStack s : contents(sack)) n += s.getCount();
        return n;
    }

    /** How full, nought to one (a stack's room each slot). */
    public static float fullness(ItemStack sack) {
        float f = 0;
        for (ItemStack s : contents(sack)) f += s.getCount() / (float) s.getMaxStackSize();
        return Math.min(1.0F, f / SLOTS);
    }

    /** Put as much of this into the sack as will go (minerals only): onto its stacks first, then a stack of its own. Returns
     *  what is left over (the stack given is not changed). */
    public static ItemStack add(ItemStack sack, ItemStack in) {
        if (!mineral(in)) return in.copy();
        List<ItemStack> held = contents(sack);
        ItemStack left = in.copy();
        for (ItemStack s : held) {
            if (left.isEmpty()) break;
            if (!ItemStack.isSameItemSameComponents(s, left)) continue;
            int move = Math.min(left.getCount(), s.getMaxStackSize() - s.getCount());
            if (move <= 0) continue;
            s.grow(move);
            left.shrink(move);
        }
        while (!left.isEmpty() && held.size() < SLOTS) {
            int move = Math.min(left.getCount(), left.getMaxStackSize());
            held.add(left.copyWithCount(move));
            left.shrink(move);
        }
        set(sack, held);
        return left;
    }

    /** The last stack out of the sack, or empty. */
    public static ItemStack removeOne(ItemStack sack) {
        List<ItemStack> held = contents(sack);
        if (held.isEmpty()) return ItemStack.EMPTY;
        ItemStack out = held.remove(held.size() - 1);
        set(sack, held);
        return out;
    }

    /** Everything out of the sack. */
    public static List<ItemStack> empty(ItemStack sack) {
        List<ItemStack> held = contents(sack);
        sack.remove(DataComponents.CONTAINER);
        return held;
    }

    @Override
    public boolean overrideStackedOnOther(ItemStack sack, Slot slot, ClickAction action, Player player) {
        if (sack.getCount() != 1 || action != ClickAction.SECONDARY) return false;
        ItemStack there = slot.getItem();
        if (there.isEmpty()) {
            ItemStack out = removeOne(sack);
            if (!out.isEmpty()) {
                ItemStack left = slot.safeInsert(out);
                if (!left.isEmpty()) add(sack, left);
                player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
            }
        } else if (mineral(there)) {
            ItemStack trial = add(sack.copy(), there);
            int room = there.getCount() - trial.getCount();
            if (room > 0) {
                ItemStack taken = slot.safeTake(there.getCount(), room, player);
                add(sack, taken);
                player.playSound(SoundEvents.BUNDLE_INSERT, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
            }
        }
        return true;
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack sack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
        if (sack.getCount() != 1 || action != ClickAction.SECONDARY || !slot.allowModification(player)) return false;
        if (other.isEmpty()) {
            ItemStack out = removeOne(sack);
            if (!out.isEmpty()) {
                access.set(out);
                player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
            }
        } else if (mineral(other)) {
            ItemStack left = add(sack, other);
            if (left.getCount() < other.getCount()) {
                other.setCount(left.getCount());
                player.playSound(SoundEvents.BUNDLE_INSERT, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
            }
        }
        return true;
    }

    /** Tipped out: everything into the player's pack, what will not go dropped at its feet. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack sack = player.getItemInHand(hand);
        List<ItemStack> held = contents(sack);
        if (held.isEmpty()) return InteractionResultHolder.fail(sack);
        if (!level.isClientSide) {
            for (ItemStack s : empty(sack)) {
                if (!player.getInventory().add(s)) player.drop(s, true);
            }
            level.playSound(null, player.blockPosition(), SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.8F, 0.8F);
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResultHolder.sidedSuccess(sack, level.isClientSide());
    }

    @Override
    public boolean isBarVisible(ItemStack sack) {
        return count(sack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack sack) {
        return Math.min(13, 1 + Mth.floor(fullness(sack) * 12.0F));
    }

    @Override
    public int getBarColor(ItemStack sack) {
        return Mth.color(0.75F, 0.55F, 0.35F);
    }

    @Override
    public void appendHoverText(ItemStack sack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        List<ItemStack> held = contents(sack);
        if (held.isEmpty()) {
            lines.add(Component.literal("Empty: holds four stacks of ore, raw metal, coal and gems").withStyle(ChatFormatting.GRAY));
            return;
        }
        for (ItemStack s : held) {
            lines.add(Component.literal(s.getCount() + " ").append(s.getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal(held.size() + " of " + SLOTS + " stacks").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Burnt or lost in the void, it spills what it held. */
    @Override
    public void onDestroyed(ItemEntity e) {
        for (ItemStack s : empty(e.getItem())) {
            net.minecraft.world.Containers.dropItemStack(e.level(), e.getX(), e.getY(), e.getZ(), s);
        }
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return true;
    }
}
