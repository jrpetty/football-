package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [war-after] What comes after a war that the game does not have, registered here and joined to the mod with one line
 * (McAssistantMod), as WarItems is.
 * <ul>
 * <li><b>The Wanted Poster</b> (WantedPosterItem): a sheet of paper, an ink sac and red dye at the bench make a blank one; used in a
 *     town with outlaws posted (or one of its allies), it is copied off the board with the most wanted of them; a guard
 *     writes one up out of the stores' paper when asked. Written up, it names the outlaw, the town, the crime and the
 *     bounty, for whoever means to bring the outlaw in (entity/Outlaws).</li>
 * </ul>
 */
public final class AfterWarItems {

    private AfterWarItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    public static final DeferredItem<WantedPosterItem> WANTED_POSTER = ITEMS.register("wanted_poster",
        () -> new WantedPosterItem(new Item.Properties().stacksTo(16)));

    /** Joined to the mod's bus (McAssistantMod): the item and its places in the creative tabs. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(AfterWarItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(WANTED_POSTER);
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(WANTED_POSTER);
    }
}
