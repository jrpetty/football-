package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * [individual] Spectacles: two panes of glass in a frame of gold wire, made by the town's smith (a gold nugget each
 * side, the frame bent round, two glass panes; the crafting table's recipe) for an old folk whose eyes have gone and
 * who likes to read, the scholar and the librarian first. The folk wears them for good (entity/Keepsakes: carried,
 * never banked or sold), and they show on its face; an old folk at close work in them (the enchanter, the tailor,
 * the smith, the storekeeper's books) sees the fine work again and works the quicker for it. A player can hand a pair
 * to an old folk who has none.
 */
public final class IndividualItems {

    private IndividualItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    public static final DeferredItem<Item> SPECTACLES = ITEMS.register("spectacles",
        () -> new Item(new Item.Properties().stacksTo(1)) {
            @Override
            public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
                lines.add(Component.translatable("item.mc_assistant.spectacles.desc").withColor(0xA89A7A));
            }
        });

    /** Joined to the mod's bus (McAssistantMod): the item and its places in the creative tabs. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(IndividualItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(SPECTACLES);
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(SPECTACLES);
    }
}
