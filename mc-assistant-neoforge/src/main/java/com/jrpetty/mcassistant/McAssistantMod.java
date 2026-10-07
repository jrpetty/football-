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

    /** [transport] The ferry bell: stands on the bank by each of a ferry's landings; rung, it calls the ferry over
     *  (block/FerryBellBlock, entity/Ferries). Made at the bench of a copper ingot, a stick and two planks. */
    public static final DeferredBlock<com.jrpetty.mcassistant.block.FerryBellBlock> FERRY_BELL =
        BLOCKS.registerBlock("ferry_bell",
            com.jrpetty.mcassistant.block.FerryBellBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE)
                .strength(1.5F, 3.0F)
                .sound(SoundType.COPPER)
                .noOcclusion()
                .requiresCorrectToolForDrops());

    public static final DeferredItem<BlockItem> FERRY_BELL_ITEM =
        ITEMS.registerSimpleBlockItem(FERRY_BELL);
    // [arms] The town's arms (entity/Arms): the festival tabard, eight wool cut like a tunic, given a banner's arms at
    // the crafting table as a shield is (TabardDecorationRecipe); and the loom's patterns for the three charges a town
    // is granted for what it lives by, each a sheet of paper and a fish, a pickaxe or wheat.
    private static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, MODID);
    public static final DeferredItem<net.minecraft.world.item.Item> TABARD =
        ITEMS.registerSimpleItem("tabard", new net.minecraft.world.item.Item.Properties().stacksTo(1));
    public static final DeferredItem<net.minecraft.world.item.BannerPatternItem> FISH_PATTERN = patternItem("fish");
    public static final DeferredItem<net.minecraft.world.item.BannerPatternItem> PICK_PATTERN = patternItem("pick");
    public static final DeferredItem<net.minecraft.world.item.BannerPatternItem> SHEAF_PATTERN = patternItem("sheaf");
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>,
        net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<com.jrpetty.mcassistant.item.TabardDecorationRecipe>> TABARD_DECORATION =
        RECIPE_SERIALIZERS.register("tabard_decoration",
            () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(com.jrpetty.mcassistant.item.TabardDecorationRecipe::new));

    private static DeferredItem<net.minecraft.world.item.BannerPatternItem> patternItem(String charge) {
        net.minecraft.tags.TagKey<net.minecraft.world.level.block.entity.BannerPattern> tag = net.minecraft.tags.TagKey.create(
            Registries.BANNER_PATTERN, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "pattern_item/" + charge));
        return ITEMS.register(charge + "_banner_pattern", () -> new net.minecraft.world.item.BannerPatternItem(tag,
            new net.minecraft.world.item.Item.Properties().stacksTo(1)));
    }
    // [pets] The town's pets (entity/Pets). The pet bowl, the dog bed and the cat basket are blocks a household sets
    // out at home; the collar (the tailor's, of leather, dyeable) is put on its pet; the treats are the cook's. Each
    // has a real recipe, so the town's makers know it (Bench, Tiers, Prices), and the household gets it out of the
    // stores or buys it at the shop (Purchases).
    public static final DeferredBlock<com.jrpetty.mcassistant.block.PetBowlBlock> PET_BOWL =
        BLOCKS.registerBlock("pet_bowl", com.jrpetty.mcassistant.block.PetBowlBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.6F).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredItem<BlockItem> PET_BOWL_ITEM = ITEMS.registerSimpleBlockItem(PET_BOWL);
    public static final DeferredBlock<com.jrpetty.mcassistant.block.PetBedBlock> DOG_BED =
        BLOCKS.registerBlock("dog_bed", p -> new com.jrpetty.mcassistant.block.PetBedBlock(p, false),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.4F).sound(SoundType.WOOL).noOcclusion());
    public static final DeferredItem<BlockItem> DOG_BED_ITEM = ITEMS.registerSimpleBlockItem(DOG_BED);
    public static final DeferredBlock<com.jrpetty.mcassistant.block.PetBedBlock> CAT_BED =
        BLOCKS.registerBlock("cat_bed", p -> new com.jrpetty.mcassistant.block.PetBedBlock(p, true),
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.4F).sound(SoundType.BAMBOO_WOOD).noOcclusion());
    public static final DeferredItem<BlockItem> CAT_BED_ITEM = ITEMS.registerSimpleBlockItem(CAT_BED);
    public static final DeferredItem<net.minecraft.world.item.Item> COLLAR =
        ITEMS.registerSimpleItem("collar", new net.minecraft.world.item.Item.Properties().stacksTo(16));
    /** No food of the folk's (it has no food in it for them to eat at a meal): what a pet is given, by hand. */
    public static final DeferredItem<net.minecraft.world.item.Item> PET_TREAT =
        ITEMS.registerSimpleItem("pet_treat");
    /** [fashion] The tailor's garments (item/Garment): coats, a jacket, a shawl, a waistcoat, hats, a scarf, a brooch, the show's rosette. */
    public static final java.util.List<DeferredItem<com.jrpetty.mcassistant.item.GarmentItem>> GARMENTS =
        com.jrpetty.mcassistant.item.GarmentItem.register(ITEMS);
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
    /** [civic] The opening ribbon: red cloth strung across a great work's end, cut by the leader when it opens
     *  (entity/BigWorks). String and red dye at a crafting table: the shop's workshop makes them for the town when a
     *  work is voted for, or they are made there and then out of the stores. */
    public static final DeferredBlock<com.jrpetty.mcassistant.block.RibbonBlock> RIBBON =
        BLOCKS.registerBlock("opening_ribbon",
            com.jrpetty.mcassistant.block.RibbonBlock::new,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .strength(0.2F)
                .sound(SoundType.WOOL)
                .noOcclusion()
                .noCollission()
                .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY));

    public static final DeferredItem<BlockItem> RIBBON_ITEM =
        ITEMS.registerSimpleBlockItem(RIBBON);
    // [quests] The quests' things (entity/QuestItems): the journal; what a quest's giver makes for it out of the stores
    // (a sealed letter, a parcel, the peace terms, a spy's report, an old miner's journal, a child's wooden toy or its
    // drawing); a family's heirlooms; the smugglers' ledger; and the town's honours, its medal and its key.
    public static final DeferredItem<com.jrpetty.mcassistant.item.QuestJournalItem> QUEST_JOURNAL =
        ITEMS.registerItem("quest_journal", com.jrpetty.mcassistant.item.QuestJournalItem::new,
            new net.minecraft.world.item.Item.Properties().stacksTo(1));
    public static final DeferredItem<net.minecraft.world.item.Item> SEALED_LETTER = quest("sealed_letter", net.minecraft.world.item.Rarity.COMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> PARCEL = quest("parcel", net.minecraft.world.item.Rarity.COMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> PEACE_TERMS = quest("peace_terms", net.minecraft.world.item.Rarity.UNCOMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> SPY_REPORT = quest("spy_report", net.minecraft.world.item.Rarity.COMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> SMUGGLERS_LEDGER = quest("smugglers_ledger", net.minecraft.world.item.Rarity.UNCOMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> MINERS_JOURNAL = quest("miners_journal", net.minecraft.world.item.Rarity.COMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> WOODEN_TOY = quest("wooden_toy", net.minecraft.world.item.Rarity.COMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> CHILDS_DRAWING = quest("childs_drawing", net.minecraft.world.item.Rarity.COMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> HEIRLOOM_RING = quest("heirloom_ring", net.minecraft.world.item.Rarity.UNCOMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> HEIRLOOM_LOCKET = quest("heirloom_locket", net.minecraft.world.item.Rarity.UNCOMMON);
    public static final DeferredItem<net.minecraft.world.item.Item> TOWN_MEDAL = quest("town_medal", net.minecraft.world.item.Rarity.RARE);
    public static final DeferredItem<net.minecraft.world.item.Item> TOWN_KEY = quest("town_key", net.minecraft.world.item.Rarity.EPIC);

    /** [quests] A quest's thing: one to a stack (each is somebody's own, named on it). */
    private static DeferredItem<net.minecraft.world.item.Item> quest(String name, net.minecraft.world.item.Rarity rarity) {
        return ITEMS.registerSimpleItem(name, new net.minecraft.world.item.Item.Properties().stacksTo(1).rarity(rarity));
    }
    /** [fleet] The fishing fleet's net: knotted of five string by the tailor, a boat's haul two to four fish at a cast,
     *  worn a little with each haul (entity/Fleet). [itemaudit] A player casts it over open water too (item/FishingNetItem). */
    public static final DeferredItem<com.jrpetty.mcassistant.item.FishingNetItem> FISHING_NET =
        ITEMS.registerItem("fishing_net", com.jrpetty.mcassistant.item.FishingNetItem::new,
            new net.minecraft.world.item.Item.Properties().durability(96));

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
                out.accept(RIBBON_ITEM.get());                       // [civic]
                out.accept(FISHING_NET.get());                           // [fleet]
                out.accept(STOREHOUSE_ITEM.get());
                out.accept(VILLAGE_BOARD_ITEM.get());
                out.accept(ASSISTANT_SPAWNER_ITEM.get());
                out.accept(JOB_BOARD_ITEM.get());
                out.accept(ZONE_MARKER.get());
                out.accept(PLACE_MARKER.get());
                out.accept(MEMORY_CORE.get());
                out.accept(TABARD.get());                     // [arms]
                out.accept(FISH_PATTERN.get());
                out.accept(PICK_PATTERN.get());
                out.accept(SHEAF_PATTERN.get());
                out.accept(PET_BOWL_ITEM.get());                 // [pets]
                out.accept(DOG_BED_ITEM.get());
                out.accept(CAT_BED_ITEM.get());
                out.accept(COLLAR.get());
                out.accept(PET_TREAT.get());
                for (DeferredItem<com.jrpetty.mcassistant.item.GarmentItem> g : GARMENTS) out.accept(g.get());   // [fashion]
                out.accept(STOCKS_ITEM.get());              // [crime]
                out.accept(FORGED_COIN.get());              // [crime]
                for (DeferredItem<?> q : java.util.List.of(QUEST_JOURNAL, SEALED_LETTER, PARCEL, PEACE_TERMS, SPY_REPORT,   // [quests]
                        SMUGGLERS_LEDGER, MINERS_JOURNAL, WOODEN_TOY, CHILDS_DRAWING, HEIRLOOM_RING, HEIRLOOM_LOCKET, TOWN_MEDAL, TOWN_KEY)) {
                    out.accept(q.get());
                }
                out.accept(FERRY_BELL_ITEM.get());                       // [transport]
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
        RECIPE_SERIALIZERS.register(modBus);                          // [arms] the tabard given a banner's arms
        com.jrpetty.mcassistant.item.CivicItems.register(modBus);      // [player-civic] the masters' goods, and their recipes
        com.jrpetty.mcassistant.item.WorkItems.register(modBus);       // [workitems] the mine's, the woods' and the roads' tools, thatch
        com.jrpetty.mcassistant.item.FieldItems.register(modBus);      // [fields] the tools of the fields and the pens, the bees and the water
        com.jrpetty.mcassistant.item.InterviewItems.register(modBus);  // [interviews] the letter of application
        com.jrpetty.mcassistant.item.KitchenItems.register(modBus);    // [kitchen] the kitchen, the cellar and the healer's shelf
        com.jrpetty.mcassistant.item.IndividualItems.register(modBus); // [individual] spectacles
        com.jrpetty.mcassistant.item.LeisureItems.register(modBus);    // [leisure] the quilt, the lute, draughts, kites, the football, lanterns, slates
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
        NeoForge.EVENT_BUS.register(IndividualCommands.class);        // [individual] /village individual
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
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.QuestRun.class);     // [quests] the quests' steps, day by day
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Hire.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Land.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Founding.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.WarScouting.class);   // [war-scouting] spies, pickets, captives
        NeoForge.EVENT_BUS.register(TimeSpeed.class);
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Transport.class);    // [transport] railways, carts, ferries, bridges
        NeoForge.EVENT_BUS.register(com.jrpetty.mcassistant.entity.Golems.class);       // [golems] a golem fallen, its iron left lying
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
            event.accept(FERRY_BELL_ITEM);                               // [transport]
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(VILLAGE_COIN);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PLACE_MARKER);
            event.accept(MEMORY_CORE);
            event.accept(ZONE_MARKER);
            event.accept(QUEST_JOURNAL);                                   // [quests]
        }
    }
}
