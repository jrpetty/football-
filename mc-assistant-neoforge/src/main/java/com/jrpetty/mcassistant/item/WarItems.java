package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [war] What the armies carry that the game does not have, registered here and joined to the mod with one line
 * (McAssistantMod), as NetherItems is.
 * <ul>
 * <li><b>The War Horn</b> (WarHornItem): a goat horn bound in leather (a goat horn and a leather), or one turned of bone
 *     with a copper mouthpiece (two bones, a copper ingot and a leather); its calls are the goat horn's own. A town's
 *     Captain carries one into the field, made out of the stores when there is none (Armies.raise).</li>
 * </ul>
 */
public final class WarItems {

    private WarItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    public static final DeferredItem<WarHornItem> WAR_HORN = ITEMS.register("war_horn",
        () -> new WarHornItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /** Joined to the mod's bus (McAssistantMod): the item and its place in the creative tab. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(WarItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(WAR_HORN);
    }
}
