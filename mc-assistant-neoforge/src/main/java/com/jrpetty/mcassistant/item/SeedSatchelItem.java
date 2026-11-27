package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * [fields] The seed satchel: two pieces of leather stitched into a bag with a flap, on a string strap. It holds up to
 * four stacks of seed (wheat, beetroot, melon and pumpkin seed, seed carrots and potatoes) and nothing else, like a
 * bundle that only takes seed: right-click a stack of seed onto it (or it onto a stack) to pack it, right-click it on
 * an empty slot to take a stack out. Held in the hand, it sows: right-click farmland and every empty square of the
 * three-by-three round it is sown from it. A farmer carries one so it sows a whole field a trip (FieldTools).
 */
public class SeedSatchelItem extends Item {

    /** Stacks a satchel holds. */
    public static final int STACKS = 4;
    public static final int MOST = STACKS * 64;
    /** What goes in: the fields' seed. */
    public static final Predicate<ItemStack> SEED = AssistantEntity.FARM_SEEDS;
    private static final int LEATHER = 0x9C6B3F;

    public SeedSatchelItem(Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------------ what is in it

    /** Its seed, stack by stack (copies). */
    public static List<ItemStack> contents(ItemStack satchel) {
        List<ItemStack> out = new ArrayList<>();
        ItemContainerContents c = satchel.getOrDefault(FieldItems.SEEDS.get(), ItemContainerContents.EMPTY);
        for (ItemStack s : c.nonEmptyItemsCopy()) out.add(s);
        return out;
    }

    private static void set(ItemStack satchel, List<ItemStack> stacks) {
        List<ItemStack> keep = new ArrayList<>();
        for (ItemStack s : stacks) if (!s.isEmpty()) keep.add(s);
        satchel.set(FieldItems.SEEDS.get(), ItemContainerContents.fromItems(keep));
    }

    /** All the seed in it. */
    public static int total(ItemStack satchel) {
        return count(satchel, s -> true);
    }

    /** So much of what matches in it (read where it lies: no copies, it is asked often, as the pace of the work). */
    public static int count(ItemStack satchel, Predicate<ItemStack> what) {
        ItemContainerContents c = satchel.get(FieldItems.SEEDS.get());
        if (c == null) return 0;
        int n = 0;
        for (ItemStack s : c.nonEmptyItems()) if (what.test(s)) n += s.getCount();
        return n;
    }

    /** Seed packed in, onto its like first, then a new stack while there is room for one: what would not go. */
    public static ItemStack add(ItemStack satchel, ItemStack seed) {
        if (seed.isEmpty() || !SEED.test(seed)) return seed;
        List<ItemStack> in = contents(satchel);
        ItemStack left = seed.copy();
        for (ItemStack s : in) {
            if (left.isEmpty()) break;
            if (!ItemStack.isSameItemSameComponents(s, left)) continue;
            int move = Math.min(left.getCount(), s.getMaxStackSize() - s.getCount());
            s.grow(move);
            left.shrink(move);
        }
        while (!left.isEmpty() && in.size() < STACKS) {
            int move = Math.min(left.getCount(), left.getMaxStackSize());
            in.add(left.copyWithCount(move));
            left.shrink(move);
        }
        set(satchel, in);
        return left;
    }

    /** Up to so many of what matches taken out (of the first kind that matches): what came. */
    public static ItemStack take(ItemStack satchel, Predicate<ItemStack> what, int n) {
        List<ItemStack> in = contents(satchel);
        ItemStack out = ItemStack.EMPTY;
        for (ItemStack s : in) {
            if (n <= 0) break;
            if (!what.test(s) || !out.isEmpty() && !ItemStack.isSameItemSameComponents(s, out)) continue;
            int k = Math.min(n, s.getCount());
            if (out.isEmpty()) out = s.copyWithCount(k);
            else out.grow(k);
            s.shrink(k);
            n -= k;
        }
        if (!out.isEmpty()) set(satchel, in);
        return out;
    }

    /** The last stack taken out whole. */
    private static ItemStack takeLast(ItemStack satchel) {
        List<ItemStack> in = contents(satchel);
        if (in.isEmpty()) return ItemStack.EMPTY;
        ItemStack last = in.remove(in.size() - 1);
        set(satchel, in);
        return last;
    }

    // ------------------------------------------------------------------ packing it (as a bundle is packed)

    @Override
    public boolean overrideStackedOnOther(ItemStack satchel, Slot slot, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY) return false;
        ItemStack there = slot.getItem();
        if (there.isEmpty()) {
            ItemStack out = takeLast(satchel);
            if (!out.isEmpty()) {
                ItemStack back = slot.safeInsert(out);
                if (!back.isEmpty()) add(satchel, back);
                player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
            }
            return true;
        }
        if (!SEED.test(there)) return false;
        int before = there.getCount();
        ItemStack taken = slot.safeTake(before, before, player);
        ItemStack left = add(satchel, taken);
        if (!left.isEmpty()) slot.safeInsert(left);
        if (left.getCount() < before) player.playSound(SoundEvents.BUNDLE_INSERT, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
        return true;
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack satchel, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess carried) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) return false;
        if (other.isEmpty()) {
            ItemStack out = takeLast(satchel);
            if (out.isEmpty()) return false;
            carried.set(out);
            player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
            return true;
        }
        if (!SEED.test(other)) return false;
        ItemStack left = add(satchel, other);
        if (left.getCount() != other.getCount()) {
            carried.set(left);
            player.playSound(SoundEvents.BUNDLE_INSERT, 0.8F, 0.8F + player.level().getRandom().nextFloat() * 0.4F);
        }
        return true;
    }

    // ------------------------------------------------------------------ sowing from it

    /**
     * Held over farmland: the clicked square and every empty square of farmland round it (three by three, a level up
     * and down) sown, a seed a square, the satchel's first seed that will grow there.
     */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        if (!(level.getBlockState(pos).getBlock() instanceof FarmBlock)) return InteractionResult.PASS;
        ItemStack satchel = ctx.getItemInHand();
        if (total(satchel) <= 0) {
            if (ctx.getPlayer() != null && !level.isClientSide) {
                ctx.getPlayer().displayClientMessage(Component.literal("The satchel is empty: pack it with seed."), true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        int sown = sow(level, satchel, pos);
        if (sown <= 0) return InteractionResult.PASS;
        level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(ctx.getPlayer(), GameEvent.BLOCK_PLACE, pos.above());
        return InteractionResult.CONSUME;
    }

    /** Sow the empty farmland in the three-by-three round this square from the satchel. Returns the seed sown. */
    public static int sow(Level level, ItemStack satchel, BlockPos centre) {
        int sown = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos g = centre.offset(dx, dy, dz);
                    if (!(level.getBlockState(g).getBlock() instanceof FarmBlock) || !level.getBlockState(g.above()).isAir()) continue;
                    for (ItemStack s : contents(satchel)) {
                        if (!(s.getItem() instanceof BlockItem bi)) continue;
                        BlockState crop = bi.getBlock().defaultBlockState();
                        if (!crop.canSurvive(level, g.above())) continue;
                        ItemStack one = take(satchel, x -> ItemStack.isSameItemSameComponents(x, s), 1);
                        if (one.isEmpty()) continue;
                        level.setBlock(g.above(), crop, 3);
                        sown++;
                        break;
                    }
                }
            }
        }
        return sown;
    }

    // ------------------------------------------------------------------ how full it is

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return total(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.min(13, Math.round(13.0F * total(stack) / MOST));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return LEATHER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        List<ItemStack> in = contents(stack);
        if (in.isEmpty()) {
            lines.add(Component.literal("Empty: right-click seed onto it to pack it").withStyle(ChatFormatting.GRAY));
            return;
        }
        for (ItemStack s : in) {
            lines.add(Component.literal(s.getCount() + " " + s.getHoverName().getString().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal(total(stack) + " of " + MOST + "; right-click farmland to sow").withStyle(ChatFormatting.DARK_GREEN));
    }
}
