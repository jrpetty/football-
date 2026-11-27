package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.FittingBlock;
import com.jrpetty.mcassistant.block.FittingBlockEntity;
import com.jrpetty.mcassistant.block.TillBlock;
import com.jrpetty.mcassistant.block.TillBlockEntity;
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

import java.util.List;

/**
 * [till] The till, the self-service kiosk and the shop fittings (display shelf, glass case, produce crate, price board,
 * basket stand, larder, clothes rack): registered here and joined to the mod with one line (McAssistantMod). Each is
 * crafted at a crafting table, made by the town's joiners for its furniture shop, and sold at any till.
 */
public final class TillItems {

    private TillItems() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McAssistantMod.MODID);

    private static BlockBehaviour.Properties wood() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 6.0F).sound(SoundType.WOOD).noOcclusion();
    }

    public static final DeferredBlock<TillBlock> TILL = BLOCKS.registerBlock("till", p -> new TillBlock(p, false),
        BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
    public static final DeferredItem<BlockItem> TILL_ITEM = ITEMS.registerSimpleBlockItem(TILL);
    public static final DeferredBlock<TillBlock> KIOSK = BLOCKS.registerBlock("self_service_kiosk", p -> new TillBlock(p, true),
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 6.0F).sound(SoundType.WOOD).noOcclusion().lightLevel(s -> 4));
    public static final DeferredItem<BlockItem> KIOSK_ITEM = ITEMS.registerSimpleBlockItem(KIOSK);

    public static final DeferredBlock<FittingBlock> DISPLAY_SHELF = BLOCKS.registerBlock("display_shelf", p -> new FittingBlock(p, FittingBlock.Kind.SHELF), wood());
    public static final DeferredItem<BlockItem> DISPLAY_SHELF_ITEM = ITEMS.registerSimpleBlockItem(DISPLAY_SHELF);
    public static final DeferredBlock<FittingBlock> DISPLAY_CASE = BLOCKS.registerBlock("display_case", p -> new FittingBlock(p, FittingBlock.Kind.CASE),
        wood().sound(SoundType.GLASS));
    public static final DeferredItem<BlockItem> DISPLAY_CASE_ITEM = ITEMS.registerSimpleBlockItem(DISPLAY_CASE);
    public static final DeferredBlock<FittingBlock> PRODUCE_CRATE = BLOCKS.registerBlock("produce_crate", p -> new FittingBlock(p, FittingBlock.Kind.CRATE), wood());
    public static final DeferredItem<BlockItem> PRODUCE_CRATE_ITEM = ITEMS.registerSimpleBlockItem(PRODUCE_CRATE);
    public static final DeferredBlock<FittingBlock> PRICE_BOARD = BLOCKS.registerBlock("price_board", p -> new FittingBlock(p, FittingBlock.Kind.BOARD), wood());
    public static final DeferredItem<BlockItem> PRICE_BOARD_ITEM = ITEMS.registerSimpleBlockItem(PRICE_BOARD);
    public static final DeferredBlock<FittingBlock> BASKET_STAND = BLOCKS.registerBlock("basket_stand", p -> new FittingBlock(p, FittingBlock.Kind.BASKETS),
        wood().sound(SoundType.BAMBOO_WOOD));
    public static final DeferredItem<BlockItem> BASKET_STAND_ITEM = ITEMS.registerSimpleBlockItem(BASKET_STAND);
    public static final DeferredBlock<FittingBlock> LARDER = BLOCKS.registerBlock("larder", p -> new FittingBlock(p, FittingBlock.Kind.LARDER), wood());
    public static final DeferredItem<BlockItem> LARDER_ITEM = ITEMS.registerSimpleBlockItem(LARDER);
    public static final DeferredBlock<FittingBlock> CLOTHES_RACK = BLOCKS.registerBlock("clothes_rack", p -> new FittingBlock(p, FittingBlock.Kind.RACK), wood());
    public static final DeferredItem<BlockItem> CLOTHES_RACK_ITEM = ITEMS.registerSimpleBlockItem(CLOTHES_RACK);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TillBlockEntity>> TILL_BE =
        BLOCK_ENTITIES.register("till", () -> BlockEntityType.Builder.of(TillBlockEntity::new, TILL.get(), KIOSK.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FittingBlockEntity>> FITTING_BE =
        BLOCK_ENTITIES.register("shop_fitting", () -> BlockEntityType.Builder.of(FittingBlockEntity::new, DISPLAY_SHELF.get(), DISPLAY_CASE.get(),
            PRODUCE_CRATE.get(), PRICE_BOARD.get(), BASKET_STAND.get(), LARDER.get(), CLOTHES_RACK.get()).build(null));

    /** Every fitting's item, the shelf first. */
    public static List<DeferredItem<BlockItem>> fittingItems() {
        return List.of(DISPLAY_SHELF_ITEM, DISPLAY_CASE_ITEM, PRODUCE_CRATE_ITEM, PRICE_BOARD_ITEM, BASKET_STAND_ITEM, LARDER_ITEM, CLOTHES_RACK_ITEM);
    }

    /** Every block item of these: the till and the kiosk, then the fittings. */
    public static List<DeferredItem<BlockItem>> allItems() {
        List<DeferredItem<BlockItem>> out = new java.util.ArrayList<>(List.of(TILL_ITEM, KIOSK_ITEM));
        out.addAll(fittingItems());
        return out;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(TillItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey()) || event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            for (DeferredItem<BlockItem> it : allItems()) event.accept(it);
        }
    }
}
