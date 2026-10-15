package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * [culture2] The towns' own dishes: what each land's kitchens make of what that land gives, registered here out of the
 * way of the mod's own register and joined to it with one line (McAssistantMod). Each is made by the game's own
 * recipe (data/mc_assistant/recipe), by a player at a crafting table or by a town's cook out of its stores
 * (entity/Cuisine), and each does a little good besides filling a belly.
 * <ul>
 * <li><b>Fish stew</b> (the coast and the rivers): a bowl, a cod or a salmon, a baked potato and a carrot.</li>
 * <li><b>Mushroom and game pie</b> (the forest): two wheat, a brown and a red mushroom, an egg and roast rabbit, pork or
 *     chicken; two pies.</li>
 * <li><b>Sweet berry tart</b> (the pine woods and the snow): three sweet berries, two wheat, sugar and an egg; two.</li>
 * <li><b>Harvest loaf</b> (the plains and the meadows): three wheat, an egg to glaze it and a handful of seed on its
 *     crust, plaited like a sheaf; two. (The kitchen's honey cake is the feast's sweet: Kitchen.)</li>
 * <li><b>Miner's hotpot</b> (the mountains and the badlands): a bowl, roast mutton and two baked potatoes.</li>
 * <li><b>Spiced mutton</b> (the savanna): roast mutton glazed with a beetroot and sugar.</li>
 * <li><b>Cocoa cake</b> (the jungle): two cocoa beans, two wheat, sugar and an egg; two slices.</li>
 * <li><b>Fen broth</b> (the swamp): a bowl, a brown and a red mushroom and a beetroot.</li>
 * </ul>
 * The desert's own dish is the game's rabbit stew, which needs nothing new: the well-towns roast what the sand gives.
 */
public final class DishItems {

    private DishItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    private static FoodProperties food(int nutrition, float saturation, Holder<MobEffect> effect, int ticks) {
        FoodProperties.Builder b = new FoodProperties.Builder().nutrition(nutrition).saturationModifier(saturation);
        if (effect != null) b.effect(() -> new MobEffectInstance(effect, ticks, 0), 1.0F);
        return b.build();
    }

    private static DeferredItem<DishItem> dish(String id, int nutrition, float saturation, Holder<MobEffect> effect, int ticks,
                                               boolean bowl, String whose, String good) {
        return ITEMS.register(id, () -> new DishItem(new Item.Properties().stacksTo(16).food(food(nutrition, saturation, effect, ticks)),
            bowl, whose, good));
    }

    public static final DeferredItem<DishItem> FISH_STEW = dish("fish_stew", 10, 0.8F, MobEffects.WATER_BREATHING, 1200, true,
        "The coast towns' own dish", "Water Breathing for a minute; the bowl comes back");
    public static final DeferredItem<DishItem> GAME_PIE = dish("game_pie", 9, 0.75F, MobEffects.NIGHT_VISION, 600, false,
        "The forest towns' own dish", "Night Vision for half a minute: a hunter's supper");
    public static final DeferredItem<DishItem> BERRY_TART = dish("berry_tart", 6, 0.6F, MobEffects.MOVEMENT_SPEED, 600, false,
        "The pine-wood towns' own dish", "Speed for half a minute");
    public static final DeferredItem<DishItem> HARVEST_LOAF = dish("harvest_loaf", 7, 0.75F, MobEffects.REGENERATION, 100, false,
        "The plains towns' own dish", "A little Regeneration");
    public static final DeferredItem<DishItem> HOTPOT = dish("hotpot", 10, 0.9F, MobEffects.DAMAGE_RESISTANCE, 900, true,
        "The hill towns' own dish", "Resistance for three quarters of a minute; the bowl comes back");
    public static final DeferredItem<DishItem> SPICED_MUTTON = dish("spiced_mutton", 9, 0.85F, MobEffects.FIRE_RESISTANCE, 600, false,
        "The savanna towns' own dish", "Fire Resistance for half a minute: the spice's heat");
    public static final DeferredItem<DishItem> COCOA_CAKE = dish("cocoa_cake", 6, 0.5F, MobEffects.DIG_SPEED, 900, false,
        "The jungle towns' own dish", "Haste for three quarters of a minute");
    public static final DeferredItem<DishItem> FEN_BROTH = dish("fen_broth", 8, 0.6F, MobEffects.NIGHT_VISION, 900, true,
        "The fen towns' own dish", "Night Vision for three quarters of a minute; the bowl comes back");

    /** All eight, in the order the showcase and the creative tab set them out. */
    public static List<DeferredItem<DishItem>> all() {
        return List.of(FISH_STEW, GAME_PIE, BERRY_TART, HARVEST_LOAF, HOTPOT, SPICED_MUTTON, COCOA_CAKE, FEN_BROTH);
    }

    /** Joined to the mod's bus (McAssistantMod): the dishes, and their places in the creative tabs. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(DishItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey()) || event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            for (DeferredItem<DishItem> d : all()) event.accept(d);
        }
    }
}
