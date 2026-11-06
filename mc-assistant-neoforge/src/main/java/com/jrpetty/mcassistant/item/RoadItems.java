package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [roles-e] The road's own things, registered here and joined to the mod's bus with one line (McAssistantMod):
 * <ul>
 * <li><b>The Driving Whip</b>: an ash stock with a leather grip and a braided lash, a stick, string and leather at the
 *     bench. The town's carter has one made out of the stores and holds it on the wagon's bench (entity/Carters); between
 *     runs it rides in its pack. A prop: it does nothing a stick would not.</li>
 * </ul>
 */
public final class RoadItems {

    private RoadItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    public static final DeferredItem<Item> DRIVING_WHIP = ITEMS.register("driving_whip", () -> new Item(new Item.Properties().stacksTo(1)));

    /** Joined to the mod's bus (McAssistantMod): the item, and its place in the creative tabs. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(RoadItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(DRIVING_WHIP);
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(DRIVING_WHIP);
    }
}
