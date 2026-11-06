package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [interviews] What the town's interviews bring into the world: the letter of application. Registered here, out of the
 * way of the mod's own register, and joined to it with one line (McAssistantMod).
 */
public final class InterviewItems {

    private InterviewItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    /** A sheet of paper written in the candidate's own hand: a paper and an ink sac. Its words are its own (its tooltip). */
    public static final DeferredItem<LetterOfApplicationItem> LETTER_OF_APPLICATION = ITEMS.register("letter_of_application",
        () -> new LetterOfApplicationItem(new Item.Properties().stacksTo(1)));

    /** Joined to the mod's bus (McAssistantMod): the item, and its place in the creative tabs. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(InterviewItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(LETTER_OF_APPLICATION);
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(LETTER_OF_APPLICATION);
    }
}
