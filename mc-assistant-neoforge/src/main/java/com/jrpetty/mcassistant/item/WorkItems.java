package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.MilestoneBlock;
import com.jrpetty.mcassistant.block.MilestoneBlockEntity;
import com.jrpetty.mcassistant.block.PitPropBlock;
import com.jrpetty.mcassistant.block.RopeBlock;
import com.jrpetty.mcassistant.block.ShippingCrateBlock;
import com.jrpetty.mcassistant.block.ShippingCrateBlockEntity;
import com.jrpetty.mcassistant.block.ThatchBlock;
import com.jrpetty.mcassistant.block.WindowBoxBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 * [workitems] The tools of the mine, the woods and the roads, and the roofs' thatch: registered here, out of the way of the
 * mod's own register, and joined to it with one line (McAssistantMod). What the town does with each is in entity/WorkTools
 * (the makers, the kit, the props and the sack, the saw), entity/Ropes, entity/Crates, entity/Thatch, entity/Milestones and
 * entity/WindowBoxes.
 * <ul>
 * <li><b>The pit prop</b> (PitPropBlock): a timber set the miners stand in the town's mine; it holds the roof.</li>
 * <li><b>The rope coil</b> (RopeCoilItem) and the rope it lets down (RopeBlock), climbed like a ladder.</li>
 * <li><b>The ore sack</b> (OreSackItem): four stacks of ore, coal and gems in a hand's one slot.</li>
 * <li><b>The felling saw</b> (FellingSawItem): a whole tree at a go.</li>
 * <li><b>Thatch</b> (ThatchBlock), its stairs and its slab: the Wood Age's roofs.</li>
 * <li><b>The milestone</b> (MilestoneBlock, MilestoneBlockEntity): the road crew's, every hundred blocks of the roads.</li>
 * <li><b>The shipping crate</b> (ShippingCrateBlock, ShippingCrateBlockEntity, CrateItem): nine stacks, kept when broken.</li>
 * <li><b>The window box</b> (WindowBoxBlock, WindowBoxItem): flowers under a household's window.</li>
 * </ul>
 */
public final class WorkItems {

    private WorkItems() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McAssistantMod.MODID);

    // ------------------------------------------------------------------ the mine
    public static final DeferredBlock<PitPropBlock> PIT_PROP = BLOCKS.registerBlock("pit_prop", PitPropBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion()
            .noCollission().ignitedByLava().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> PIT_PROP_ITEM = ITEMS.registerSimpleBlockItem(PIT_PROP);

    public static final DeferredBlock<RopeBlock> ROPE = BLOCKS.registerBlock("rope", RopeBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).strength(0.8F).sound(SoundType.WOOL).noOcclusion()
            .noCollission().ignitedByLava().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<RopeCoilItem> ROPE_COIL = ITEMS.registerItem("rope_coil", RopeCoilItem::new,
        new Item.Properties().stacksTo(16));

    public static final DeferredItem<OreSackItem> ORE_SACK = ITEMS.registerItem("ore_sack", OreSackItem::new,
        new Item.Properties().stacksTo(1));

    // ------------------------------------------------------------------ the woods
    public static final DeferredItem<FellingSawItem> FELLING_SAW = ITEMS.registerItem("felling_saw", FellingSawItem::new,
        new Item.Properties());

    // ------------------------------------------------------------------ the roofs
    public static final DeferredBlock<ThatchBlock> THATCH = BLOCKS.registerBlock("thatch", ThatchBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.6F).sound(SoundType.GRASS).ignitedByLava());
    public static final DeferredBlock<ThatchBlock.Stairs> THATCH_STAIRS = BLOCKS.registerBlock("thatch_stairs",
        p -> new ThatchBlock.Stairs(THATCH.get().defaultBlockState(), p),
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.6F).sound(SoundType.GRASS).ignitedByLava());
    public static final DeferredBlock<ThatchBlock.Slab> THATCH_SLAB = BLOCKS.registerBlock("thatch_slab", ThatchBlock.Slab::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.6F).sound(SoundType.GRASS).ignitedByLava());
    public static final DeferredItem<BlockItem> THATCH_ITEM = ITEMS.registerSimpleBlockItem(THATCH);
    public static final DeferredItem<BlockItem> THATCH_STAIRS_ITEM = ITEMS.registerSimpleBlockItem(THATCH_STAIRS);
    public static final DeferredItem<BlockItem> THATCH_SLAB_ITEM = ITEMS.registerSimpleBlockItem(THATCH_SLAB);

    // ------------------------------------------------------------------ the roads
    public static final DeferredBlock<MilestoneBlock> MILESTONE = BLOCKS.registerBlock("milestone", MilestoneBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(2.0F, 6.0F).sound(SoundType.STONE).noOcclusion()
            .requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> MILESTONE_ITEM = ITEMS.registerSimpleBlockItem(MILESTONE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MilestoneBlockEntity>> MILESTONE_BE =
        BLOCK_ENTITIES.register("milestone", () -> BlockEntityType.Builder.of(MilestoneBlockEntity::new, MILESTONE.get()).build(null));

    public static final DeferredBlock<ShippingCrateBlock> SHIPPING_CRATE = BLOCKS.registerBlock("shipping_crate", ShippingCrateBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 3.0F).sound(SoundType.WOOD).ignitedByLava()
            .pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<CrateItem> SHIPPING_CRATE_ITEM = ITEMS.registerItem("shipping_crate",
        p -> new CrateItem(SHIPPING_CRATE.get(), p), new Item.Properties().stacksTo(1));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShippingCrateBlockEntity>> SHIPPING_CRATE_BE =
        BLOCK_ENTITIES.register("shipping_crate", () -> BlockEntityType.Builder.of(ShippingCrateBlockEntity::new, SHIPPING_CRATE.get()).build(null));

    // ------------------------------------------------------------------ the houses
    public static final DeferredBlock<WindowBoxBlock> WINDOW_BOX = BLOCKS.registerBlock("window_box", WindowBoxBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(0.8F).sound(SoundType.WOOD).noOcclusion()
            .pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<WindowBoxItem> WINDOW_BOX_ITEM = ITEMS.registerItem("window_box",
        p -> new WindowBoxItem(WINDOW_BOX.get(), p), new Item.Properties());

    /** Joined to the mod's bus (McAssistantMod): the blocks, items and block entities, their places in the creative tabs,
     *  and the game's events they answer (WorkTools.listen: the props holding the roof, the saw, the sack). */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(WorkItems::onTabs);
        com.jrpetty.mcassistant.entity.WorkTools.listen(NeoForge.EVENT_BUS);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) {
            event.accept(PIT_PROP_ITEM);
            event.accept(ROPE_COIL);
            event.accept(ORE_SACK);
            event.accept(FELLING_SAW);
            event.accept(THATCH_ITEM);
            event.accept(THATCH_STAIRS_ITEM);
            event.accept(THATCH_SLAB_ITEM);
            event.accept(MILESTONE_ITEM);
            event.accept(SHIPPING_CRATE_ITEM);
            for (WindowBoxBlock.Flower f : WindowBoxBlock.Flower.values()) event.accept(WindowBoxItem.of(f));
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ROPE_COIL);
            event.accept(ORE_SACK);
            event.accept(FELLING_SAW);
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(THATCH_ITEM);
            event.accept(THATCH_STAIRS_ITEM);
            event.accept(THATCH_SLAB_ITEM);
            event.accept(PIT_PROP_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(MILESTONE_ITEM);
            event.accept(SHIPPING_CRATE_ITEM);
            event.accept(WindowBoxItem.of(WindowBoxBlock.Flower.POPPY));
        }
    }

    /** One of the work items, by name (the tests, the commands): null if there is none so called. */
    public static Item byName(String name) {
        return switch (name) {
            case "pit_prop" -> PIT_PROP_ITEM.get();
            case "rope_coil" -> ROPE_COIL.get();
            case "ore_sack" -> ORE_SACK.get();
            case "felling_saw" -> FELLING_SAW.get();
            case "thatch" -> THATCH_ITEM.get();
            case "thatch_stairs" -> THATCH_STAIRS_ITEM.get();
            case "thatch_slab" -> THATCH_SLAB_ITEM.get();
            case "milestone" -> MILESTONE_ITEM.get();
            case "shipping_crate" -> SHIPPING_CRATE_ITEM.get();
            case "window_box" -> WINDOW_BOX_ITEM.get();
            default -> null;
        };
    }

    /** All eight, one of each, as stacks (the showcase). */
    public static java.util.List<ItemStack> showcase() {
        return java.util.List.of(new ItemStack(PIT_PROP_ITEM.get()), new ItemStack(ROPE_COIL.get()), new ItemStack(ORE_SACK.get()),
            new ItemStack(FELLING_SAW.get()), new ItemStack(THATCH_ITEM.get()), new ItemStack(MILESTONE_ITEM.get()),
            new ItemStack(SHIPPING_CRATE_ITEM.get()), WindowBoxItem.of(WindowBoxBlock.Flower.CORNFLOWER));
    }
}
