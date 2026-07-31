package com.jrpetty.mazerunner;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Standalone items (block items live in {@link ModBlocks}). */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MazeRunnerMod.MODID);

    /** Cure for the Changing, harvested from a slain Griever. */
    public static final DeferredItem<GrieverSerumItem> GRIEVER_SERUM = ITEMS.register("griever_serum",
        () -> new GrieverSerumItem(new Item.Properties().stacksTo(8).rarity(Rarity.UNCOMMON)));

    private ModItems() {}
}
