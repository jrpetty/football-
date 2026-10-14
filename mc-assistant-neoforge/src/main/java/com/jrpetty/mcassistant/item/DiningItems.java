package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.block.PlaceSettingBlockEntity;
import com.jrpetty.mcassistant.entity.DiningSeat;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [dining] Eating out's things, registered here out of the way of the mod's own register and joined to it with one line
 * (McAssistantMod): the place setting (PlaceSettingBlock, its entity), the plate and the cup that lay it (PlaceSettingItem),
 * and a chair's seat for a player at a table (entity/DiningSeat). The game-bus listeners of the dining rooms (entity/Dining,
 * entity/Diners) are joined here too.
 */
public final class DiningItems {

    private DiningItems() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McAssistantMod.MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, McAssistantMod.MODID);

    public static final DeferredBlock<PlaceSettingBlock> PLACE_SETTING = BLOCKS.registerBlock("place_setting", PlaceSettingBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.3F).sound(SoundType.DECORATED_POT).noOcclusion()
            .pushReaction(PushReaction.DESTROY));

    public static final DeferredItem<PlaceSettingItem> PLATE = ITEMS.registerItem("plate",
        p -> new PlaceSettingItem(PLACE_SETTING.get(), PlaceSettingItem.Kind.PLATE, p), new Item.Properties().stacksTo(16));
    public static final DeferredItem<PlaceSettingItem> CUP = ITEMS.registerItem("cup",
        p -> new PlaceSettingItem(PLACE_SETTING.get(), PlaceSettingItem.Kind.CUP, p), new Item.Properties().stacksTo(16));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlaceSettingBlockEntity>> PLACE_SETTING_BE =
        BLOCK_ENTITIES.register("place_setting", () -> BlockEntityType.Builder.of(PlaceSettingBlockEntity::new, PLACE_SETTING.get()).build(null));

    public static final DeferredHolder<EntityType<?>, EntityType<DiningSeat>> SEAT = ENTITIES.register("dining_seat",
        () -> EntityType.Builder.<DiningSeat>of(DiningSeat::new, MobCategory.MISC).sized(0.001F, 0.001F).clientTrackingRange(8)
            .updateInterval(20).noSummon().noSave().build("dining_seat"));

    /** Joined to the mod's bus (McAssistantMod): the things, and their places in the creative tabs; and the dining rooms'
     *  listeners on the game's bus. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener(DiningItems::onTabs);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Dining.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Diners.class);
        com.jrpetty.mcassistant.entity.Dining.hook();
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey()) || event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(PLATE);
            event.accept(CUP);
        }
    }
}
