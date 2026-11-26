package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [wpc] The property package's item, joined to the mod with one line (McAssistantMod): the deed (DeedItem), issued by the
 * register when land, a house or premises is bought or passed on; never made at a bench.
 */
public final class PropertyDeeds {

    private PropertyDeeds() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    public static final DeferredItem<DeedItem> DEED = ITEMS.register("deed", () -> new DeedItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(PropertyDeeds::onTabs);
        com.jrpetty.mcassistant.entity.Makers.unmade("deed", "the register writes a deed for a title it holds: a made one would be a forgery");
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(DEED);
    }
}
