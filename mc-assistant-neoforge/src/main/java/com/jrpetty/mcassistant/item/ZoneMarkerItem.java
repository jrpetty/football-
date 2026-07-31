package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

import javax.annotation.Nullable;

/**
 * Work Zone Marker — how the player fences off a patch of world for a
 * specialist.
 *
 *   right-click a block .... first click sets one corner, the second closes the
 *                            box (the whole area between them becomes the zone)
 *   right-click more blocks  once a box exists, each further click GROWS it to
 *                            include that block — extend a farm row by row
 *   sneak + right-click ..... clears the marker and starts over (smaller zone)
 *   right-click an assistant  hands the zone to that bot as its workplace
 *
 * The pending zone rides in the item itself, so several can be marked out and
 * handed to different bots.
 */
public class ZoneMarkerItem extends Item {

    public ZoneMarkerItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.UNCOMMON));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (ctx.getLevel().isClientSide || player == null) return InteractionResult.SUCCESS;
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();

        if (player.isShiftKeyDown()) {
            clear(stack);
            tell(player, "Marker cleared — click a block to start a new zone.");
            return InteractionResult.CONSUME;
        }

        CompoundTag data = read(stack);
        WorkZone zone = WorkZone.load(data.contains("Zone") ? data.getCompound("Zone") : null);
        if (zone != null) {
            // Established box: every further click extends it.
            zone = zone.including(pos);
            store(stack, zone, null);
            tell(player, "Zone extended — now " + zone.describe() + ". Right-click an assistant to assign it.");
            return InteractionResult.CONSUME;
        }
        if (data.contains("Corner")) {
            zone = WorkZone.of(BlockPos.of(data.getLong("Corner")), pos, WorkZone.DEFAULT_DEPTH);
            store(stack, zone, null);
            tell(player, "Zone marked: " + zone.describe()
                + ". Click more blocks to extend it, or right-click an assistant to assign it.");
            return InteractionResult.CONSUME;
        }
        store(stack, null, pos);
        tell(player, "First corner set at " + pos.getX() + ", " + pos.getZ()
            + " — now click the opposite corner.");
        return InteractionResult.CONSUME;
    }

    /** Right-click an assistant: hand it the marked zone as its workplace. */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (!(target instanceof AssistantEntity bot)) return InteractionResult.PASS;
        if (!bot.isOwner(player)) {
            tell(player, "That's not your assistant.");
            return InteractionResult.CONSUME;
        }
        WorkZone zone = WorkZone.load(read(stack).contains("Zone") ? read(stack).getCompound("Zone") : null);
        if (zone == null) {
            tell(player, "Mark a zone first — click one corner, then the opposite one.");
            return InteractionResult.CONSUME;
        }
        bot.setWorkZone(zone);
        bot.say("This is my patch now — " + zone.describe() + "."
            + (bot.stationTask() == AssistantEntity.StationTask.NONE
                ? " Open me up and pick what I should do here."
                : " " + (bot.missingEssentials().isEmpty()
                    ? "Everything I need is here — getting to work."
                    : "I still need " + String.join(", ", bot.missingEssentials()) + ".")));
        return InteractionResult.CONSUME;
    }

    private static void tell(Player player, String message) {
        player.sendSystemMessage(Component.literal("<Zone Marker> " + message));
    }

    private static CompoundTag read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void store(ItemStack stack, @Nullable WorkZone zone, @Nullable BlockPos corner) {
        CompoundTag tag = new CompoundTag();
        if (zone != null) tag.put("Zone", zone.save());
        if (corner != null) tag.putLong("Corner", corner.asLong());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clear(ItemStack stack) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
    }
}
