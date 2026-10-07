package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.FeedTroughBlock;
import com.jrpetty.mcassistant.block.FieldBlockEntity;
import com.jrpetty.mcassistant.block.FishTrapBlock;
import com.jrpetty.mcassistant.block.NestingBoxBlock;
import com.jrpetty.mcassistant.block.RainBarrelBlock;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [fields] The tools of the fields and the pens, the bees and the water: registered here, out of the way of the mod's
 * own register, and joined to it with one line (McAssistantMod). Copper is common and the town had next to no use for
 * it; half of these give it one.
 * <ul>
 * <li><b>The copper watering can</b> (WateringCanItem): sixteen waterings, filled at any water, the well or a rain
 *     barrel; a farmer waters a three-by-three of its crops at a time, and in a drought it carries the can, not a
 *     bucket.</li>
 * <li><b>The seed satchel</b> (SeedSatchelItem): four stacks of seed at the hip, so a farmer sows a whole field a
 *     trip.</li>
 * <li><b>The copper sickle</b> (SickleItem): reaps the ripe crops three by three.</li>
 * <li><b>The nesting box</b>, <b>the feed trough</b>, <b>the fish trap</b> and <b>the rain barrel</b>: blocks set out in
 *     the pen, in the water and by the workshops (block/).</li>
 * <li><b>The bee smoker</b> (BeeSmokerItem): the bees calmed while the hives are emptied.</li>
 * </ul>
 * Who makes each and how the folk use it is entity/FieldTools; every one has a real recipe, so the town's makers know it.
 */
public final class FieldItems {

    private FieldItems() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McAssistantMod.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(McAssistantMod.MODID);

    // ------------------------------------------------------------------ what the tools hold

    /** The water in a can, in waterings (none to sixteen). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> WATER =
        COMPONENTS.registerComponentType("water", b -> b.persistent(Codec.intRange(0, 64)).networkSynchronized(ByteBufCodecs.VAR_INT));
    /** The seed in a satchel: up to four stacks. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> SEEDS =
        COMPONENTS.registerComponentType("seeds", b -> b.persistent(ItemContainerContents.CODEC)
            .networkSynchronized(ItemContainerContents.STREAM_CODEC).cacheEncoding());

    /** Copper at the edge: softer than iron and quicker to dull, two hundred cuts in it. */
    public static final Tier COPPER = new SimpleTier(BlockTags.INCORRECT_FOR_STONE_TOOL, 200, 5.0F, 1.0F, 13,
        () -> Ingredient.of(Items.COPPER_INGOT));

    // ------------------------------------------------------------------ the blocks

    public static final DeferredBlock<NestingBoxBlock> NESTING_BOX = BLOCKS.registerBlock("nesting_box", NestingBoxBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0F).sound(SoundType.WOOD).noOcclusion().ignitedByLava());
    public static final DeferredBlock<FeedTroughBlock> FEED_TROUGH = BLOCKS.registerBlock("feed_trough", FeedTroughBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.2F).sound(SoundType.WOOD).noOcclusion().ignitedByLava());
    public static final DeferredBlock<FishTrapBlock> FISH_TRAP = BLOCKS.registerBlock("fish_trap", FishTrapBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(0.6F).sound(SoundType.BAMBOO_WOOD).noOcclusion()
            .randomTicks());
    public static final DeferredBlock<RainBarrelBlock> RAIN_BARREL = BLOCKS.registerBlock("rain_barrel", RainBarrelBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F).sound(SoundType.WOOD).noOcclusion().randomTicks()
            .ignitedByLava());

    public static final DeferredItem<BlockItem> NESTING_BOX_ITEM = ITEMS.registerSimpleBlockItem(NESTING_BOX);
    public static final DeferredItem<BlockItem> FEED_TROUGH_ITEM = ITEMS.registerSimpleBlockItem(FEED_TROUGH);
    public static final DeferredItem<BlockItem> FISH_TRAP_ITEM = ITEMS.registerSimpleBlockItem(FISH_TRAP);
    public static final DeferredItem<BlockItem> RAIN_BARREL_ITEM = ITEMS.registerSimpleBlockItem(RAIN_BARREL);

    /** What each block keeps: a nesting box's nine eggs, a trough's three feeds, a trap's six fish (a barrel's water is
     *  its state: its keeper is there only so the town can find it without looking). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FieldBlockEntity>> NESTING_BOX_BE =
        BLOCK_ENTITIES.register("nesting_box", () -> BlockEntityType.Builder.of(
            (p, s) -> new FieldBlockEntity(FieldItems.NESTING_BOX_BE.get(), p, s, FieldBlockEntity.Kind.BOX), NESTING_BOX.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FieldBlockEntity>> FEED_TROUGH_BE =
        BLOCK_ENTITIES.register("feed_trough", () -> BlockEntityType.Builder.of(
            (p, s) -> new FieldBlockEntity(FieldItems.FEED_TROUGH_BE.get(), p, s, FieldBlockEntity.Kind.TROUGH), FEED_TROUGH.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FieldBlockEntity>> FISH_TRAP_BE =
        BLOCK_ENTITIES.register("fish_trap", () -> BlockEntityType.Builder.of(
            (p, s) -> new FieldBlockEntity(FieldItems.FISH_TRAP_BE.get(), p, s, FieldBlockEntity.Kind.TRAP), FISH_TRAP.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FieldBlockEntity>> RAIN_BARREL_BE =
        BLOCK_ENTITIES.register("rain_barrel", () -> BlockEntityType.Builder.of(
            (p, s) -> new FieldBlockEntity(FieldItems.RAIN_BARREL_BE.get(), p, s, FieldBlockEntity.Kind.BARREL), RAIN_BARREL.get()).build(null));

    // ------------------------------------------------------------------ the tools

    public static final DeferredItem<WateringCanItem> WATERING_CAN = ITEMS.register("copper_watering_can",
        () -> new WateringCanItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<SeedSatchelItem> SEED_SATCHEL = ITEMS.register("seed_satchel",
        () -> new SeedSatchelItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<SickleItem> COPPER_SICKLE = ITEMS.register("copper_sickle",
        () -> new SickleItem(COPPER, new Item.Properties().durability(200).attributes(SwordItem.createAttributes(COPPER, 2, -2.2F))));
    public static final DeferredItem<BeeSmokerItem> BEE_SMOKER = ITEMS.register("bee_smoker",
        () -> new BeeSmokerItem(new Item.Properties().durability(128)));

    /** Every one of the eight, in the order the books and the docs give them. */
    public static List<Item> all() {
        return List.of(WATERING_CAN.get(), SEED_SATCHEL.get(), COPPER_SICKLE.get(), NESTING_BOX_ITEM.get(), FEED_TROUGH_ITEM.get(),
            FISH_TRAP_ITEM.get(), RAIN_BARREL_ITEM.get(), BEE_SMOKER.get());
    }

    /**
     * The age a thing of ours belongs to where its recipe would put it later than its use does (Tiers.material): the
     * satchel's leather and the barrel's copper hoop are the Stone Age's materials, but a satchel of hide and a barrel
     * for the rain are a farm's from its first season (the copper smelted as it comes). Null for the rest.
     */
    @Nullable
    public static Villages.Age ageOf(Item it) {
        if (!SEED_SATCHEL.isBound() || !RAIN_BARREL_ITEM.isBound()) return null;
        if (it == SEED_SATCHEL.get() || it == RAIN_BARREL_ITEM.get()) return Villages.Age.WOOD;
        return null;
    }

    /** Joined to the mod's bus (McAssistantMod): the blocks, the tools, what they hold, and their places in the tabs. */
    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(FieldItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) {
            for (Item it : all()) event.accept(it);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(WATERING_CAN);
            event.accept(SEED_SATCHEL);
            event.accept(COPPER_SICKLE);
            event.accept(BEE_SMOKER);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(NESTING_BOX_ITEM);
            event.accept(FEED_TROUGH_ITEM);
            event.accept(FISH_TRAP_ITEM);
            event.accept(RAIN_BARREL_ITEM);
        }
    }
}
