package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [player-civic] What the masters of the town's trades make, and teach: registered here, out of the way of the
 * mod's own register, and joined to it with one line (McAssistantMod).
 * <ul>
 * <li><b>The reinforced pickaxe</b>: an iron pickaxe riveted with three more bars and a copper strap. Iron at the
 *     face, but it lasts three times as long (750 uses) and bites a little quicker. A master smith beats one for
 *     the miners and the cave dwellers when the stores can spare it; they carry it as any pick.</li>
 * <li><b>The brewer's stout</b> (BrewersStoutItem): poured at the tavern, brewed by the master brewer.</li>
 * <li><b>The farmhouse pie</b>: pumpkin, egg, carrot and three wheat, two pies to a baking; a hearty meal (ten
 *     hunger). The master cook bakes it into the stores when it has the makings, and the town eats it.</li>
 * <li><b>The apprentice's journal</b> (ApprenticeJournalItem): a book, a feather, an ink sac and a strap of leather.</li>
 * </ul>
 * The first three a player crafts only once taught (TrainedRecipe); the journal anybody may bind.
 */
public final class CivicItems {

    private CivicItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, McAssistantMod.MODID);

    /** Iron at the face, three times the wear in it, a touch quicker. */
    public static final Tier REINFORCED = new SimpleTier(BlockTags.INCORRECT_FOR_IRON_TOOL, 750, 6.8F, 2.0F, 14,
        () -> Ingredient.of(Items.IRON_INGOT));

    public static final DeferredItem<PickaxeItem> REINFORCED_PICKAXE = ITEMS.register("reinforced_pickaxe",
        () -> new PickaxeItem(REINFORCED, new Item.Properties().attributes(PickaxeItem.createAttributes(REINFORCED, 1.0F, -2.8F))));

    public static final DeferredItem<BrewersStoutItem> BREWERS_STOUT = ITEMS.register("brewers_stout",
        () -> new BrewersStoutItem(new Item.Properties().stacksTo(16).food(new FoodProperties.Builder()
            .nutrition(2).saturationModifier(0.3F).alwaysEdible()
            .effect(() -> new MobEffectInstance(MobEffects.DIG_SPEED, 2400, 0), 1.0F).build())));

    public static final DeferredItem<Item> FARMHOUSE_PIE = ITEMS.register("farmhouse_pie",
        () -> new Item(new Item.Properties().stacksTo(16).food(new FoodProperties.Builder()
            .nutrition(10).saturationModifier(0.6F).build())));

    public static final DeferredItem<ApprenticeJournalItem> APPRENTICE_JOURNAL = ITEMS.register("apprentice_journal",
        () -> new ApprenticeJournalItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<TrainedRecipe>> TRAINED =
        SERIALIZERS.register("trained", TrainedRecipe.Serializer::new);

    /** Joined to the mod's bus (McAssistantMod): the items, the master's recipes, and their places in the creative tabs. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        SERIALIZERS.register(modBus);
        modBus.addListener(CivicItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) {
            event.accept(APPRENTICE_JOURNAL);
            event.accept(REINFORCED_PICKAXE);
            event.accept(BREWERS_STOUT);
            event.accept(FARMHOUSE_PIE);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(REINFORCED_PICKAXE);
            event.accept(APPRENTICE_JOURNAL);
        }
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            event.accept(BREWERS_STOUT);
            event.accept(FARMHOUSE_PIE);
        }
    }
}
