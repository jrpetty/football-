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
 * Place Marker — points a specialist at a spot.
 *
 *   right-click a block .... that spot becomes the nearest assistant's home:
 *                            where it sleeps out the night, where Go Home
 *                            sends it, and where a hauler delivers its loads.
 *   right-click the air .... same, but where YOU are standing.
 *
 * <p>No naming and no anvil. It used to do nothing at all until you renamed it
 * in an anvil to a place name — which is typing, which this mod does not do,
 * and the named waypoints it produced were only ever read by the chat commands
 * and the patrol goal, both of which are switched off. So it was an item that
 * demanded a step you cannot take in order to feed a system that is not
 * running.
 *
 * <p>A name given in an anvil still registers a waypoint as well, so nothing is
 * lost for whoever turns the command layer back on — but it is no longer the
 * price of the item doing anything.
 *
 * <p>Reusable: it is a pointer, not a consumable.
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
        AssistantEntity a = AssistantCommands.findAssistant(sp);
        if (a == null) {
            actionBar(sp, "§eNo assistant of yours nearby — place a spawner first.");
            return;
        }
        a.setHome(pos);
        // A name is optional now, not a precondition. If somebody has renamed
        // the marker in an anvil, register that waypoint too.
        String name = placeName(stack);
        if (name != null) a.setWaypoint(name, pos);

        String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        actionBar(sp, "§a" + a.displayNameCap() + "'s home is now " + where
            + (a.stationTask() == AssistantEntity.StationTask.HAUL
                ? " — it'll deliver its loads here."
                : " — it'll sleep and return here.")
            + (name == null ? "" : " (also saved as \"" + name + "\")"));
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
