package com.jrpetty.mcassistant.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * [nether] The Runner's Satchel: five leathers and a string, waxed against the heat with a magma cream. It holds nine
 * stacks, and it does not burn: dropped in lava it floats, whole, with everything in it (Item.Properties.fireResistant,
 * the game's own rule for netherite). The Nether runners each carry one, and pack the haul into it as they go
 * (NetherWork.packSatchel): more room than a pack alone, and what a runner who dies in the lava was carrying comes home
 * all the same, in the satchel the others fish out (or the rescue party finds). At home it is emptied into the
 * storehouse.
 *
 * <p>A player's: right-click to pack what is in your pack (not your hotbar) into it, the things that stack (ore,
 * quartz, dust, rods, wart); sneak and right-click to empty it back out. Lose your life in the lava and your satchel,
 * and what is in it, is waiting for you on the surface.
 */
public class RunnersSatchelItem extends Item {

    /** Stacks it holds. */
    public static final int SLOTS = 9;

    public RunnersSatchelItem(Properties properties) {
        super(properties);
    }

    /** What is in it, the stacks as they are. */
    public static List<ItemStack> contents(ItemStack satchel) {
        List<ItemStack> out = new ArrayList<>();
        ItemContainerContents c = satchel.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        for (ItemStack s : c.nonEmptyItemsCopy()) out.add(s);
        return out;
    }

    /** How many things are in it. */
    public static int count(ItemStack satchel) {
        int n = 0;
        for (ItemStack s : contents(satchel)) n += s.getCount();
        return n;
    }

    static void write(ItemStack satchel, List<ItemStack> stacks) {
        NonNullList<ItemStack> slots = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(SLOTS, stacks.size()); i++) slots.set(i, stacks.get(i));
        satchel.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
    }

    /** As much of this as will go into the satchel (topping up its stacks first, then a slot of its own). Returns what is
     *  left over. */
    public static ItemStack pack(ItemStack satchel, ItemStack what) {
        if (what.isEmpty() || what.getItem() instanceof RunnersSatchelItem) return what;
        List<ItemStack> in = contents(satchel);
        ItemStack left = what.copy();
        for (ItemStack s : in) {
            if (left.isEmpty()) break;
            if (!ItemStack.isSameItemSameComponents(s, left)) continue;
            int room = s.getMaxStackSize() - s.getCount();
            int n = Math.min(room, left.getCount());
            if (n <= 0) continue;
            s.grow(n);
            left.shrink(n);
        }
        while (!left.isEmpty() && in.size() < SLOTS) {
            int n = Math.min(left.getMaxStackSize(), left.getCount());
            in.add(left.copyWithCount(n));
            left.shrink(n);
        }
        write(satchel, in);
        return left;
    }

    /** Everything out of it (it is empty after). */
    public static List<ItemStack> unpack(ItemStack satchel) {
        List<ItemStack> out = contents(satchel);
        satchel.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        return out;
    }

    /** What a player's satchel packs: what stacks, and is not food (the haul, not the dinner). */
    public static boolean packable(ItemStack s) {
        return !s.isEmpty() && s.getMaxStackSize() > 1 && s.get(DataComponents.FOOD) == null && !(s.getItem() instanceof RunnersSatchelItem);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack satchel = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(satchel);
        Inventory inv = player.getInventory();
        int moved = 0;
        if (player.isShiftKeyDown()) {
            for (ItemStack s : unpack(satchel)) {
                moved += s.getCount();
                if (!inv.add(s)) player.drop(s, false);
            }
            level.playSound(null, player.blockPosition(), SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.8F, 1.0F);
            player.displayClientMessage(Component.literal(moved == 0 ? "The satchel is empty." : "Emptied the satchel: " + moved + " things."), true);
            return InteractionResultHolder.consume(satchel);
        }
        Predicate<ItemStack> haul = RunnersSatchelItem::packable;
        for (int i = Inventory.getSelectionSize(); i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!haul.test(s)) continue;
            ItemStack left = pack(satchel, s);
            moved += s.getCount() - left.getCount();
            inv.items.set(i, left);
        }
        level.playSound(null, player.blockPosition(), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.8F, 1.0F);
        player.displayClientMessage(Component.literal(moved == 0 ? "Nothing in your pack to put in it (or it is full)."
            : "Packed " + moved + " things into the satchel (" + contents(satchel).size() + " of " + SLOTS + " stacks)."), true);
        return InteractionResultHolder.consume(satchel);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        List<ItemStack> in = contents(stack);
        if (in.isEmpty()) {
            lines.add(Component.literal("Empty").withStyle(ChatFormatting.GRAY));
        } else {
            for (int i = 0; i < Math.min(5, in.size()); i++) {
                ItemStack s = in.get(i);
                lines.add(Component.literal(s.getCount() + " " + s.getHoverName().getString()).withStyle(ChatFormatting.GRAY));
            }
            if (in.size() > 5) lines.add(Component.literal("and " + (in.size() - 5) + " more").withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal("Fire-proof: it floats on lava, whole.").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Use to pack your pack's haul in; sneak to empty it.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
