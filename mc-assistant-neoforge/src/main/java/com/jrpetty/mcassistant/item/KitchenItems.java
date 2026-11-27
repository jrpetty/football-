package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.CheeseWheelBlock;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * [kitchen] The kitchen, the cellar and the healer's shelf: eight things the town makes of its stores and uses, registered
 * here, out of the way of the mod's own register, and joined to it with one line (McAssistantMod). What the folk do with
 * them is entity/Kitchen's.
 * <ul>
 * <li><b>The packed lunch</b> (bread, cooked meat or fish and an apple, two to a bundle): the cook packs it for a hand
 *     working far off, who eats it where it is at the midday meal.</li>
 * <li><b>The cheese wheel</b> (three milk buckets and three wheat round an egg, the buckets back): a block, eaten in
 *     slices; the town's reserve, and set out on the café's and the tavern's tables. Its slices are an item of their
 *     own, for the stores when a wheel is cut in short times.</li>
 * <li><b>The honey cake</b> (a honey bottle, two wheat, an egg and sugar): feast food, for birthdays, weddings, Founding
 *     Day and the festivals.</li>
 * <li><b>Mead</b> (a honey bottle, sugar and a glass bottle) and <b>cider</b> (three apples, sugar and a glass bottle):
 *     the brewer's, at the tavern's bar, in the toasts and at the harvest.</li>
 * <li><b>The fish pie</b> (cod or salmon, a potato, wheat and an egg): a hearty meal, the cook's answer to a glut.</li>
 * <li><b>Herbal tea</b> (sweet berries or a flower, sugar and a water bottle): the healer's cup for a cold.</li>
 * <li><b>The bandage</b> (two paper and a string, or a wool and two string, three to a roll): the watch's and the cave
 *     team's, and the healer's for the hurt.</li>
 * </ul>
 */
public final class KitchenItems {

    private KitchenItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);

    // ------------------------------------------------------------------ what eating them does

    /** A packed lunch: a full meal, a loaf's and a roast's worth between two bundles. */
    public static final FoodProperties LUNCH_FOOD = new FoodProperties.Builder().nutrition(9).saturationModifier(0.8F).build();
    /** A slice of cheese: a good deal more filling than a slice of cake. */
    public static final FoodProperties CHEESE_FOOD = new FoodProperties.Builder().nutrition(4).saturationModifier(0.7F).build();
    /** A slice of honey cake, at a feast: sweet, and a heart of good cheer with it. */
    public static final FoodProperties CAKE_FOOD = new FoodProperties.Builder().nutrition(7).saturationModifier(0.7F)
        .effect(() -> new MobEffectInstance(MobEffects.ABSORPTION, 600, 0), 1.0F).build();
    /** A fish pie: more than bread, nearly a roast. */
    public static final FoodProperties PIE_FOOD = new FoodProperties.Builder().nutrition(8).saturationModifier(0.8F).build();

    // ------------------------------------------------------------------ the things

    public static final DeferredItem<KitchenFoodItem> PACKED_LUNCH = ITEMS.register("packed_lunch",
        () -> new KitchenFoodItem(new Item.Properties().stacksTo(16), LUNCH_FOOD, 32,
            "A full meal in a cloth bundle, eaten where the work is", "Packed by the cook for the far plots, the boats and the road"));

    public static final DeferredBlock<CheeseWheelBlock> CHEESE_WHEEL = BLOCKS.registerBlock("cheese_wheel", CheeseWheelBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.5F).sound(SoundType.WOOL).noOcclusion()
            .forceSolidOn().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> CHEESE_WHEEL_ITEM = ITEMS.register("cheese_wheel",
        () -> new BlockItem(CHEESE_WHEEL.get(), new Item.Properties().stacksTo(16)));

    public static final DeferredItem<Item> CHEESE_SLICE = ITEMS.register("cheese_slice",
        () -> new Item(new Item.Properties().food(CHEESE_FOOD)));

    public static final DeferredItem<KitchenFoodItem> HONEY_CAKE = ITEMS.register("honey_cake",
        () -> new KitchenFoodItem(new Item.Properties().stacksTo(16), CAKE_FOOD, 40,
            "Feast food: a golden slice for a birthday, a wedding or a festival", "Baked by the cook of the beekeeper's honey"));

    public static final DeferredItem<KitchenDrinkItem> MEAD = ITEMS.register("mead",
        () -> new KitchenDrinkItem(new Item.Properties().stacksTo(16),
            () -> List.of(new MobEffectInstance(MobEffects.REGENERATION, 160, 0)),
            "Honey wine: a little regeneration, and the bottle comes back", "Brewed by the brewer for the tavern and the toasts"));

    public static final DeferredItem<KitchenDrinkItem> CIDER = ITEMS.register("cider",
        () -> new KitchenDrinkItem(new Item.Properties().stacksTo(16),
            () -> List.of(new MobEffectInstance(MobEffects.ABSORPTION, 1200, 0)),
            "Pressed apples: a heart of good cheer for a minute", "Brewed by the brewer; the harvest's own drink"));

    public static final DeferredItem<Item> FISH_PIE = ITEMS.register("fish_pie",
        () -> new Item(new Item.Properties().stacksTo(16).food(PIE_FOOD)));

    public static final DeferredItem<KitchenDrinkItem> HERBAL_TEA = ITEMS.register("herbal_tea",
        () -> new KitchenDrinkItem(new Item.Properties().stacksTo(16),
            () -> List.of(new MobEffectInstance(MobEffects.REGENERATION, 100, 0)),
            "A warm cup: a short regeneration; sees a cold off a day sooner", "Steeped by the healer, else the café"));

    public static final DeferredItem<BandageItem> BANDAGE = ITEMS.register("bandage",
        () -> new BandageItem(new Item.Properties().stacksTo(16)));

    /** The eight (and the cheese's slices), in the order the creative tab shows them. */
    public static List<DeferredItem<? extends Item>> all() {
        return List.of(PACKED_LUNCH, CHEESE_WHEEL_ITEM, CHEESE_SLICE, HONEY_CAKE, MEAD, CIDER, FISH_PIE, HERBAL_TEA, BANDAGE);
    }

    /** Joined to the mod's bus (McAssistantMod): the things, the wheel's block, and their places in the creative tabs. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(KitchenItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) {
            for (DeferredItem<? extends Item> it : all()) event.accept(it);
        }
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            for (DeferredItem<? extends Item> it : all()) if (it != BANDAGE) event.accept(it);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(BANDAGE);
    }
}
