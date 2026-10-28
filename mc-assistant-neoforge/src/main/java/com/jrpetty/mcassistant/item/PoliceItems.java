package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [police] The watch's own things, registered here and joined to the mod's bus with one line (McAssistantMod):
 * <ul>
 * <li><b>The Constable's Badge</b> (ConstableBadgeItem): a star of gold about an iron boss, an iron ingot and four gold
 *     nuggets at the bench. The smith makes one out of the stores for the town's constable, who wears it, and for every
 *     player the watch swears in as a special constable (entity/PlayerLaw); a town with no smith has its shop's workshop
 *     make it. Sworn to a town, it is a warrant: shown to a culprit, an arrest; used in the air, the beat.</li>
 * </ul>
 */
public final class PoliceItems {

    private PoliceItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    public static final DeferredItem<ConstableBadgeItem> CONSTABLE_BADGE = ITEMS.register("constable_badge",
        () -> new ConstableBadgeItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /** Joined to the mod's bus (McAssistantMod): the items, and their places in the creative tabs. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(PoliceItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(CONSTABLE_BADGE);
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(CONSTABLE_BADGE);
    }
}
