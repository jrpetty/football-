package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.BusinessCoreBlock;
import com.jrpetty.mcassistant.block.BusinessCoreBlockEntity;
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
 * [wpb] The Business Core (BusinessCoreBlock), registered here and joined to the mod with one line (McAssistantMod). Made of
 * a book, a sign, a chest and planks; a business sets its own at its premises.
 */
public final class CoreItems {

    private CoreItems() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McAssistantMod.MODID);

    public static final DeferredBlock<BusinessCoreBlock> CORE = BLOCKS.registerBlock("business_core", BusinessCoreBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(2.5F, 1200F).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredItem<BlockItem> CORE_ITEM = ITEMS.registerSimpleBlockItem(CORE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BusinessCoreBlockEntity>> CORE_BE =
        BLOCK_ENTITIES.register("business_core", () -> BlockEntityType.Builder.of(BusinessCoreBlockEntity::new, CORE.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(CoreItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) event.accept(CORE_ITEM);
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(CORE_ITEM);
    }
}
