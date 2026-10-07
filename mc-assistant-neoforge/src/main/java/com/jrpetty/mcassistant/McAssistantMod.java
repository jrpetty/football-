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

    /** The loaded jar's version (0.<build>.0, from the newest changelog entry: build.gradle), or "?" outside the game. */
    public static String version() {
        try {
            net.neoforged.fml.ModList mods = net.neoforged.fml.ModList.get();
            if (mods == null) return "?";
            return mods.getModContainerById(MODID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(Registries.MENU, MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    private static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<AssistantEntity>> ASSISTANT =
        ENTITY_TYPES.register("assistant", () -> EntityType.Builder
            .of(AssistantEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .build("assistant"));

    /** Village Folk: the same hands, with nobody telling them what to do. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.jrpetty.mcassistant.entity.VillageFolkEntity>>
        VILLAGE_FOLK = ENTITY_TYPES.register("village_folk", () -> EntityType.Builder
            .of(com.jrpetty.mcassistant.entity.VillageFolkEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .build("village_folk"));

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

    // The Village Folk Spawner: the same shape as the assistant's — craft it,
    // place it, and a settler stands up. The first one puts up the board of a village
    // to be founded, where you choose how many start it (entity/Founding).
    public static final DeferredBlock<com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock> FOLK_SPAWNER =
        BLOCKS.registerBlock("village_folk_spawner",
            com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_YELLOW)
                .strength(1.0F, 2.0F)
                .sound(SoundType.GRASS));

    public static final DeferredItem<BlockItem> FOLK_SPAWNER_ITEM =
        ITEMS.registerSimpleBlockItem(FOLK_SPAWNER);

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

    // The Village Storehouse: storehouse units, twenty-seven of which stacked in a cube join
    // into one store the size of twenty-seven chests — where a whole village keeps its goods.
    public static final DeferredBlock<com.jrpetty.mcassistant.block.StorehouseBlock> STOREHOUSE =
        BLOCKS.registerBlock("storehouse_unit",
            com.jrpetty.mcassistant.block.StorehouseBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.5F, 600.0F)
                .sound(SoundType.WOOD));

    public static final DeferredItem<com.jrpetty.mcassistant.item.StorehouseItem> STOREHOUSE_ITEM =
        ITEMS.registerItem("storehouse_unit",
            props -> new com.jrpetty.mcassistant.item.StorehouseItem(STOREHOUSE.get(), props));

    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
        net.minecraft.world.level.block.entity.BlockEntityType<com.jrpetty.mcassistant.block.StorehouseBlockEntity>> STOREHOUSE_BE =
        BLOCK_ENTITIES.register("storehouse", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
            .of(com.jrpetty.mcassistant.block.StorehouseBlockEntity::new, STOREHOUSE.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<com.jrpetty.mcassistant.menu.StorehouseMenu>> STOREHOUSE_MENU =
        MENU_TYPES.register("storehouse", () -> new MenuType<>(
            (net.minecraft.world.inventory.MenuType.MenuSupplier<com.jrpetty.mcassistant.menu.StorehouseMenu>)
                com.jrpetty.mcassistant.menu.StorehouseMenu::new,
            net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));

    // The Village Board: ten panels wide and five high, set up on every village's square, saying
    // what the village is doing, how it is getting on and what it is working towards.
    public static final DeferredBlock<com.jrpetty.mcassistant.block.VillageBoardBlock> VILLAGE_BOARD =
        BLOCKS.registerBlock("village_board",
            com.jrpetty.mcassistant.block.VillageBoardBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F, 600.0F)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK));

    public static final DeferredItem<com.jrpetty.mcassistant.item.VillageBoardItem> VILLAGE_BOARD_ITEM =
        ITEMS.registerItem("village_board", com.jrpetty.mcassistant.item.VillageBoardItem::new,
            new net.minecraft.world.item.Item.Properties().stacksTo(1));

    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
        net.minecraft.world.level.block.entity.BlockEntityType<com.jrpetty.mcassistant.block.VillageBoardBlockEntity>> VILLAGE_BOARD_BE =
        BLOCK_ENTITIES.register("village_board", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
            .of(com.jrpetty.mcassistant.block.VillageBoardBlockEntity::new, VILLAGE_BOARD.get()).build(null));

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

    /** Settles one villager, founding a village if there isn't one nearby. */
    public static final DeferredItem<net.minecraft.world.item.Item> VILLAGE_CHARTER =
        ITEMS.registerItem("village_charter",
            com.jrpetty.mcassistant.item.VillageCharterItem::new);

    /** A village's coin: minted from its gold from the Iron Age, paid out in wages, spent
     *  at its stalls, and what a player is paid in when they sell to it. */
    public static final DeferredItem<net.minecraft.world.item.Item> VILLAGE_COIN =
        ITEMS.registerSimpleItem("village_coin");

    // [crime] The stocks: the council's sentence for a second offence, sat in on the square for a day (entity/Trial).
    // Three planks over two logs; the town puts a pair up out of its stores the first time a sentence wants them.
    public static final DeferredBlock<com.jrpetty.mcassistant.block.StocksBlock> STOCKS =
        BLOCKS.registerBlock("stocks",
            com.jrpetty.mcassistant.block.StocksBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F, 3.0F)
                .sound(SoundType.WOOD)
                .noOcclusion());

    public static final DeferredItem<BlockItem> STOCKS_ITEM =
        ITEMS.registerSimpleBlockItem(STOCKS);

    /** [crime] A copper coin cast to pass for the town's own: what a forger passes at the stores (entity/Mischief). */
    public static final DeferredItem<net.minecraft.world.item.Item> FORGED_COIN =
        ITEMS.registerSimpleItem("forged_coin");

    /** The mod's own creative tab: everything it adds, in one place, the village folk spawner first. */
    public static final DeferredHolder<net.minecraft.world.item.CreativeModeTab, net.minecraft.world.item.CreativeModeTab> TAB =
        TABS.register("village_folk", () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(net.minecraft.network.chat.Component.translatable("itemGroup.mc_assistant"))
            .icon(() -> new net.minecraft.world.item.ItemStack(FOLK_SPAWNER_ITEM.get()))
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((params, out) -> {
                out.accept(FOLK_SPAWNER_ITEM.get());
                out.accept(VILLAGE_CHARTER.get());
                out.accept(VILLAGE_COIN.get());
                out.accept(STOREHOUSE_ITEM.get());
                out.accept(VILLAGE_BOARD_ITEM.get());
                out.accept(ASSISTANT_SPAWNER_ITEM.get());
                out.accept(JOB_BOARD_ITEM.get());
                out.accept(ZONE_MARKER.get());
                out.accept(PLACE_MARKER.get());
                out.accept(MEMORY_CORE.get());
                out.accept(STOCKS_ITEM.get());              // [crime]
                out.accept(FORGED_COIN.get());              // [crime]
            })
            .build());

    /** Master switch for the chat / slash / voice command layer. Off while the
     *  specialisation flow (spawner -> management screen -> zone marker) is the
     *  way you run a crew; the parser and voice engine stay built, just idle. */
    public static final boolean MANUAL_COMMANDS_ENABLED = false;

    public McAssistantMod(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        // Every tunable number lives in config/mc_assistant-common.toml.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, AssistantConfig.SPEC);
        ENTITY_TYPES.register(modBus);
        MENU_TYPES.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(this::onEntityAttributes);
        modBus.addListener(this::onBuildCreativeTabs);
        modBus.addListener(ChunkLoad::onRegisterControllers);

        NeoForge.EVENT_BUS.register(AssistantCommands.class);
        NeoForge.EVENT_BUS.register(ChatControl.class);
        NeoForge.EVENT_BUS.register(GraveWatch.class);
        NeoForge.EVENT_BUS.register(PlotBookKeeper.class);
        NeoForge.EVENT_BUS.register(VillageSpawner.class);
        NeoForge.EVENT_BUS.register(Colonies.class);
        NeoForge.EVENT_BUS.register(SleepWatch.class);
        NeoForge.EVENT_BUS.register(VillagerTakeover.class);
        NeoForge.EVENT_BUS.register(VillageCommands.class);
        NeoForge.EVENT_BUS.register(WarFootingCommands.class);        // [war-prep] /village war footing
        NeoForge.EVENT_BUS.register(SessionReset.class);
        NeoForge.EVENT_BUS.register(ChunkLoad.class);
        NeoForge.EVENT_BUS.register(StallWatch.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Market.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Roads.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Caravans.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Raids.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Tavern.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Citizens.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Laws.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Diplomacy.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.JobMarket.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Quests.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Hire.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Land.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Founding.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.WarScouting.class);   // [war-scouting] spies, pickets, captives
        NeoForge.EVENT_BUS.register(TimeSpeed.class);
    }

    private void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ASSISTANT.get(), AssistantEntity.createAttributes().build());
        event.put(VILLAGE_FOLK.get(),
            com.jrpetty.mcassistant.entity.VillageFolkEntity.createAttributes().build());
    }

    /** Put the spawner in the Functional Blocks creative tab. */
    private void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ASSISTANT_SPAWNER_ITEM);
            event.accept(FOLK_SPAWNER_ITEM);
            event.accept(VILLAGE_CHARTER);
            event.accept(JOB_BOARD_ITEM);
            event.accept(STOREHOUSE_ITEM);
            event.accept(VILLAGE_BOARD_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(VILLAGE_COIN);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PLACE_MARKER);
            event.accept(MEMORY_CORE);
            event.accept(ZONE_MARKER);
        }
    }
}
