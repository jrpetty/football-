package com.jrpetty.mcassistant.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * [fashion] A garment (Garment): one to a stack, named by its colour once it is dyed ("Crimson Long Coat"), and
 * telling what it is worn as. Folk wear what they buy, are given or win (entity/Fashion); a player buys one at the
 * town's shop or store, dyes one at a crafting table, and gives one to a folk, who puts it on.
 */
public class GarmentItem extends Item {

    private final Garment garment;

    public GarmentItem(Properties properties, Garment garment) {
        super(properties.stacksTo(1));
        this.garment = garment;
    }

    public Garment garment() {
        return garment;
    }

    /** Every garment registered with the mod's items, in the table's order (McAssistantMod.GARMENTS). */
    public static List<DeferredItem<GarmentItem>> register(DeferredRegister.Items items) {
        List<DeferredItem<GarmentItem>> out = new ArrayList<>();
        for (Garment g : Garment.values()) out.add(items.registerItem(g.id, p -> new GarmentItem(p, g)));
        return List.copyOf(out);
    }

    @Override
    public Component getName(ItemStack stack) {
        DyedItemColor c = stack.get(DataComponents.DYED_COLOR);
        if (c == null || !garment.dyeable()) return super.getName(stack);
        return Component.translatable("item.mc_assistant.garment.dyed",
            Garment.colourCap(Garment.nearest(c.rgb()).getId()), super.getName(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        lines.add(Component.translatable("item.mc_assistant.garment.worn." + garment.slot.name().toLowerCase(java.util.Locale.ROOT))
            .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
