package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.MapTableBlock;
import com.jrpetty.mcassistant.block.MapTableBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [borders-map] The war room's map table (MapTableBlock), registered here and joined to the mod with one line
 * (McAssistantMod), as WarItems is. Crafted of two paper, a compass, two planks, a cartography table and two sticks.
 */
public final class BorderItems {

    private BorderItems() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McAssistantMod.MODID);

    public static final DeferredBlock<MapTableBlock> MAP_TABLE = BLOCKS.registerBlock("map_table", MapTableBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD).noOcclusion().ignitedByLava());
    public static final DeferredItem<BlockItem> MAP_TABLE_ITEM = ITEMS.registerSimpleBlockItem(MAP_TABLE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MapTableBlockEntity>> MAP_TABLE_BE =
        BLOCK_ENTITIES.register("map_table", () -> BlockEntityType.Builder.of(MapTableBlockEntity::new, MAP_TABLE.get()).build(null));

    /** Joined to the mod's bus (McAssistantMod): the block, its item and block entity, and its places in the creative tabs. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(BorderItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(MAP_TABLE_ITEM);
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(MAP_TABLE_ITEM);
    }
}
