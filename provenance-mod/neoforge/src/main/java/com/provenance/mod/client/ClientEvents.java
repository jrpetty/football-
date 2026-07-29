package com.provenance.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.provenance.core.ItemStamp;
import com.provenance.mod.Network;
import com.provenance.mod.Provenance;
import com.provenance.mod.ProvenanceComponents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * Client input and tooltip.
 *
 * <p>Inspection is bound to a key rather than right-click, because weapons,
 * shields, tools and rods all already use right-click for their own actions and
 * stealing it would break them.
 */
@EventBusSubscriber(modid = Provenance.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    public static final KeyMapping INSPECT_KEY = new KeyMapping(
            "key.provenance.inspect",
            KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_H,
            "key.categories.provenance");

    private ClientEvents() {
    }

    @EventBusSubscriber(modid = Provenance.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(INSPECT_KEY);
        }
    }

    /**
     * The key works in two places: holding an item in the world, and hovering
     * one in any inventory screen.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        while (INSPECT_KEY.consumeClick()) {
            if (minecraft.screen instanceof AbstractContainerScreen<?> container) {
                var hovered = container.getSlotUnderMouse();
                if (hovered != null && !hovered.getItem().isEmpty()) {
                    requestInspection(hovered.getSlotIndex());
                    continue;
                }
            }
            if (minecraft.screen == null || minecraft.screen instanceof ItemHistoryScreen) {
                requestInspection(-1);
            }
        }
    }

    /** Slot -1 means "whatever is in my main hand". */
    public static void requestInspection(int slot) {
        requestInspection(slot, 0, true, "");
    }

    public static void requestInspection(int slot, int page, boolean descending, String search) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new Network.InspectRequest(slot, page, descending, search));
    }

    /**
     * The concise tooltip. Deliberately four lines at most: the full history
     * belongs on the Item History screen, not dumped into item lore.
     */
    @SubscribeEvent
    public static void onTooltip(RenderTooltipEvent.GatherComponents event) {
        ItemStack stack = event.getItemStack();
        ItemStamp stamp = stack.get(ProvenanceComponents.STAMP.get());
        if (stamp == null) {
            return;
        }
        event.getTooltipElements().add(net.minecraft.util.Either.left(
                Component.translatable("tooltip.provenance.inspect",
                                INSPECT_KEY.getTranslatedKeyMessage())
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY)));
    }
}
