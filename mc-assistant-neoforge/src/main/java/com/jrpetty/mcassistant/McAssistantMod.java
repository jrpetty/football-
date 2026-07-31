package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.block.AssistantSpawnerBlock;
import com.jrpetty.mcassistant.block.JobBoardBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.item.PlaceMarkerItem;
import com.jrpetty.mcassistant.menu.AssistantMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * MC Assistant — an in-game companion entity you command through chat
 * ("!follow", "!gather logs 16") or the /assistant command. This is the
 * NeoForge port of the mc-assistant project: the logic runs server-side on
 * the entity itself, so it works in single player and on dedicated servers.
 */
@Mod(McAssistantMod.MODID)
public final class McAssistantMod {
    public static final String MODID = "mc_assistant";

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(Registries.MENU, MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<AssistantEntity>> ASSISTANT =
        ENTITY_TYPES.register("assistant", () -> EntityType.Builder
            .of(AssistantEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .build("assistant"));

    // Extended menu type: the entity id travels to the client in the buffer.
    public static final DeferredHolder<MenuType<?>, MenuType<AssistantMenu>> ASSISTANT_MENU =
        MENU_TYPES.register("assistant", () -> IMenuTypeExtension.create(AssistantMenu::new));

    // The Assistant Spawner block + its item. Right-click the placed block to
    // summon your assistant (or spawn a fresh one) at that spot.
    public static final DeferredBlock<AssistantSpawnerBlock> ASSISTANT_SPAWNER =
        BLOCKS.registerBlock("assistant_spawner",
            AssistantSpawnerBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.0F, 6.0F)
                .sound(SoundType.STONE));

    public static final DeferredItem<BlockItem> ASSISTANT_SPAWNER_ITEM =
        ITEMS.registerSimpleBlockItem(ASSISTANT_SPAWNER);

    // The Job Board block: right-click to set the crew's role preset and mark it
    // the town center, which the autonomous crew organizes its labour around.
    public static final DeferredBlock<JobBoardBlock> JOB_BOARD =
        BLOCKS.registerBlock("job_board",
            JobBoardBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F)
                .sound(SoundType.WOOD));

    public static final DeferredItem<BlockItem> JOB_BOARD_ITEM =
        ITEMS.registerSimpleBlockItem(JOB_BOARD);

    // The Place Marker item: rename it in an anvil to a place name, then
    // right-click a spot to save it as that named waypoint for your assistant.
    public static final DeferredItem<PlaceMarkerItem> PLACE_MARKER =
        ITEMS.registerItem("place_marker", PlaceMarkerItem::new);

    // The Memory Core: dropped when a companion dies — right-click the ground
    // to bring that exact bot back (name, level, role, waypoints, station).
    public static final DeferredItem<com.jrpetty.mcassistant.item.MemoryCoreItem> MEMORY_CORE =
        ITEMS.registerItem("memory_core", com.jrpetty.mcassistant.item.MemoryCoreItem::new);

    // The Work Zone Marker: click two corners to fence off a patch of world,
    // click more blocks to extend it, then right-click an assistant to assign it.
    public static final DeferredItem<com.jrpetty.mcassistant.item.ZoneMarkerItem> ZONE_MARKER =
        ITEMS.registerItem("zone_marker", com.jrpetty.mcassistant.item.ZoneMarkerItem::new);

    /** Master switch for the chat / slash / voice command layer. Off while the
     *  specialisation flow (spawner -> management screen -> zone marker) is the
     *  way you run a crew; the parser and voice engine stay built, just idle. */
    public static final boolean MANUAL_COMMANDS_ENABLED = false;

    public McAssistantMod(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        MENU_TYPES.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(this::onEntityAttributes);
        modBus.addListener(this::onBuildCreativeTabs);
        modBus.addListener(ChunkLoad::onRegisterControllers);

        NeoForge.EVENT_BUS.register(AssistantCommands.class);
        NeoForge.EVENT_BUS.register(ChatControl.class);
        NeoForge.EVENT_BUS.register(GraveWatch.class);
    }

    private void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ASSISTANT.get(), AssistantEntity.createAttributes().build());
    }

    /** Put the spawner in the Functional Blocks creative tab. */
    private void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ASSISTANT_SPAWNER_ITEM);
            event.accept(JOB_BOARD_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PLACE_MARKER);
            event.accept(MEMORY_CORE);
            event.accept(ZONE_MARKER);
        }
    }
}
