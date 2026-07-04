package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.AssistantCommands;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Place Marker — a hand-held way to name a spot for your assistant. Rename it in
 * an anvil to a place name ("mine", "base", "farm"), then:
 *   - right-click a block: saves that spot as that named waypoint;
 *   - right-click the air:  saves where YOU are standing.
 * Afterwards, "go to the mine" walks the assistant there. The item is reusable —
 * it's a pointer, not a consumable, so one marker can label many places (rename
 * and re-use). This is the physical-item twin of "remember this spot as the X".
 */
public class PlaceMarkerItem extends Item {

    public PlaceMarkerItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (!ctx.getLevel().isClientSide && player instanceof ServerPlayer sp) {
            // Save the block ON TOP of the one clicked — where a body would stand.
            mark(sp, ctx.getItemInHand(), ctx.getClickedPos().above());
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            mark(sp, stack, sp.blockPosition()); // no block targeted -> mark where we stand
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private void mark(ServerPlayer sp, ItemStack stack, BlockPos pos) {
        String name = placeName(stack);
        if (name == null) {
            actionBar(sp, "§eName this Place Marker in an anvil first (e.g. \"mine\"), then right-click a spot.");
            return;
        }
        AssistantEntity a = AssistantCommands.findAssistant(sp);
        if (a == null) {
            actionBar(sp, "§eNo assistant nearby to remember \"" + name + "\" — spawn one first.");
            return;
        }
        a.setWaypoint(name, pos);
        actionBar(sp, "§aMarked \"" + name + "\" at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
            + " — say \"go to the " + name + "\".");
    }

    private static void actionBar(ServerPlayer sp, String msg) {
        sp.displayClientMessage(Component.literal(msg), true);
    }

    /** Waypoint name = the item's anvil-given custom name; a leading "the " is
     *  trimmed so it lines up with how "go to the X" is parsed. null if unnamed. */
    @Nullable
    private static String placeName(ItemStack stack) {
        if (!stack.has(DataComponents.CUSTOM_NAME)) return null;
        String raw = stack.getHoverName().getString().trim().toLowerCase();
        if (raw.startsWith("the ")) raw = raw.substring(4).trim();
        return raw.isEmpty() ? null : raw;
    }
}
