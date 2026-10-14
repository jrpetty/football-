package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.PlaceSettingBlock;
import com.jrpetty.mcassistant.entity.DiningPlaces.Kind;
import com.jrpetty.mcassistant.entity.DiningPlaces.Station;
import com.jrpetty.mcassistant.item.DishItems;
import com.jrpetty.mcassistant.item.KitchenItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * [dining] The menu: what each kind of place serves, at which meal, made how and where, and today's card from the town's
 * real stock, its own dish and the season, priced by the town's one asking price.
 * <ul>
 * <li><b>The dishes</b> (DISHES, data): each with its course (starters, mains, desserts, drinks), the station it is
 *     cooked at, how long a plain cook takes over it, the places that serve it and the meals it is served at, and how it
 *     is got: <i>made to order</i> from its makings by the game's own recipe (Bench: raw beef roasted in the smoker, a
 *     stew over the hearth) with what is ready in the stores to fall back on; <i>ready first</i> (bread, pies, the
 *     café's bottles: what the kitchen keeps made), made if there is none; or <i>from the shelf</i> only. A cake is
 *     served by the slice (seven to a cake), a cheese wheel by the slice (four): the rest is kept for the next order.</li>
 * <li><b>Today's card</b> (card): for this place and this meal, each dish with its price, what is in it, the hunger it
 *     gives, whether it is sold out (nothing ready and nothing to make it of), the town's own dish marked "local", and
 *     the head cook's special: the town's dish if it can be made, else the main the stores hold most makings for. Warming
 *     dishes lead in autumn and winter, the cold ones in summer.</li>
 * <li><b>The price</b>: the town's asking price for the dish (Purchases.priceEach: the market's one spread, the slow
 *     ware marked down), and a little for the table: a tenth at the café, a sixth at the tavern, a quarter at the
 *     restaurant. A player pays it in whole coins (rounded up), a folk to the hundredth (Purchases.charge).</li>
 * </ul>
 */
public final class DiningMenu {

    private DiningMenu() {}

    /** The courses, in the order the card shows them and the kitchen sends them out (drinks first). */
    public enum Course {
        DRINKS("Drinks"), STARTERS("Starters"), MAINS("Mains"), DESSERTS("Desserts");

        public final String title;

        Course(String title) { this.title = title; }
    }

    /** The meals a place serves at. */
    public enum Meal { BREAKFAST, LUNCH, DINNER }

    /** How a dish is got for an order. */
    public enum Got { MADE, READY_FIRST, SHELF }

    /** One dish on the menu (data): what is served, and what it is cut from ({@code whole}, null for a dish served as it
     *  comes) and into how many {@code portions} (a cake's seven slices, a wheel's four). */
    public record Dish(String id, String name, Supplier<ItemStack> item, @Nullable Supplier<ItemStack> whole, Course course, Station station, int seconds, Set<Kind> where,
                       Set<Meal> meals, Got got, int portions, String about) {

        public ItemStack one() { return item.get().copyWithCount(1); }

        boolean drink() { return course == Course.DRINKS; }
    }

    private static final Set<Kind> ALL = EnumSet.allOf(Kind.class);
    private static final Set<Kind> FOOD_HALLS = EnumSet.of(Kind.CAFE, Kind.RESTAURANT);
    private static final Set<Kind> EVENING = EnumSet.of(Kind.TAVERN, Kind.RESTAURANT);
    private static final Set<Meal> ANY = EnumSet.allOf(Meal.class);
    private static final Set<Meal> LUNCH_DINNER = EnumSet.of(Meal.LUNCH, Meal.DINNER);
    private static final Set<Meal> BREAKFAST = EnumSet.of(Meal.BREAKFAST, Meal.LUNCH);

    private static volatile List<Dish> DISHES;

    /** Every dish there is (made once: the items are registered by then). */
    public static List<Dish> dishes() {
        List<Dish> d = DISHES;
        if (d != null) return d;
        List<Dish> out = new ArrayList<>();
        // Drinks: the café's bottles (made in threes at its brewing stand), the cellar's mead and cider, the healer's tea.
        for (Cafe.Drink dr : Cafe.DRINKS) {
            ItemStack one = Cafe.drink(dr);
            out.add(new Dish("drink/" + dr.id(), dr.name(), () -> one, null, Course.DRINKS, Station.BREWING, 2, ALL, ANY, Got.SHELF, 1,
                "Pressed and bottled by the café"));
        }
        out.add(new Dish("mead", "Mead", () -> new ItemStack(KitchenItems.MEAD.get()), null, Course.DRINKS, Station.BREWING, 2, EVENING, LUNCH_DINNER,
            Got.SHELF, 1, "Honey wine from the brewer"));
        out.add(new Dish("cider", "Cider", () -> new ItemStack(KitchenItems.CIDER.get()), null, Course.DRINKS, Station.BREWING, 2, EVENING, LUNCH_DINNER,
            Got.SHELF, 1, "Pressed apples, from the cellar"));
        out.add(new Dish("herbal_tea", "Herbal tea", () -> new ItemStack(KitchenItems.HERBAL_TEA.get()), null, Course.DRINKS, Station.BREWING, 3, FOOD_HALLS,
            ANY, Got.SHELF, 1, "Berries steeped in hot water"));
        // Starters.
        out.add(new Dish("bread", "Fresh bread", () -> new ItemStack(Items.BREAD), null, Course.STARTERS, Station.BENCH, 3, ALL, ANY, Got.READY_FIRST, 1,
            "Baked of the farmers' wheat"));
        out.add(new Dish("cheese", "Cheese plate", () -> new ItemStack(KitchenItems.CHEESE_SLICE.get()),
            () -> new ItemStack(KitchenItems.CHEESE_WHEEL_ITEM.get()), Course.STARTERS, Station.BENCH, 2, ALL,
            LUNCH_DINNER, Got.SHELF, 4, "A slice off the cook's own wheel"));
        out.add(new Dish("beetroot_soup", "Beetroot soup", () -> new ItemStack(Items.BEETROOT_SOUP), null, Course.STARTERS, Station.HEARTH, 6, ALL,
            LUNCH_DINNER, Got.MADE, 1, "Beetroot from the gardens, in a bowl"));
        out.add(new Dish("mushroom_stew", "Mushroom stew", () -> new ItemStack(Items.MUSHROOM_STEW), null, Course.STARTERS, Station.HEARTH, 6, ALL,
            LUNCH_DINNER, Got.MADE, 1, "Both kinds of mushroom, in a bowl"));
        out.add(new Dish("baked_potato", "Baked potato", () -> new ItemStack(Items.BAKED_POTATO), null, Course.STARTERS, Station.SMOKER, 5, ALL, LUNCH_DINNER,
            Got.READY_FIRST, 1, "Out of the smoker, in its jacket"));
        out.add(new Dish("salad", "Beetroot salad", () -> new ItemStack(Items.BEETROOT), null, Course.STARTERS, Station.BENCH, 2, FOOD_HALLS, LUNCH_DINNER,
            Got.SHELF, 1, "Fresh from the gardens"));
        // Mains: the roasts and the fish, to order from the raw; the stews; the pies; the towns' dishes (the special).
        Object[][] roasts = { { "beef", "Roast beef", Items.COOKED_BEEF, "The rancher's beef, roasted" },
            { "pork", "Roast pork", Items.COOKED_PORKCHOP, "A chop off the rancher's pigs, roasted" },
            { "mutton", "Roast mutton", Items.COOKED_MUTTON, "The rancher's mutton, roasted" },
            { "chicken", "Roast chicken", Items.COOKED_CHICKEN, "A bird from the coop, roasted" } };
        for (Object[] r : roasts) {
            Item it = (Item) r[2];
            out.add(new Dish(r[0].toString(), r[1].toString(), () -> new ItemStack(it), null, Course.MAINS, Station.SMOKER, 8, ALL, LUNCH_DINNER, Got.MADE, 1,
                r[3].toString()));
        }
        out.add(new Dish("cod", "Grilled cod", () -> new ItemStack(Items.COOKED_COD), null, Course.MAINS, Station.SMOKER, 7, ALL, LUNCH_DINNER, Got.MADE, 1,
            "The fishers' catch, grilled"));
        out.add(new Dish("salmon", "Grilled salmon", () -> new ItemStack(Items.COOKED_SALMON), null, Course.MAINS, Station.SMOKER, 7, ALL, LUNCH_DINNER,
            Got.MADE, 1, "The fishers' catch, grilled"));
        out.add(new Dish("rabbit_stew", "Rabbit stew", () -> new ItemStack(Items.RABBIT_STEW), null, Course.MAINS, Station.HEARTH, 9, ALL, LUNCH_DINNER,
            Got.MADE, 1, "Rabbit, carrot, potato and mushroom"));
        out.add(new Dish("fish_pie", "Fish pie", () -> new ItemStack(KitchenItems.FISH_PIE.get()), null, Course.MAINS, Station.FURNACE, 9, ALL, LUNCH_DINNER,
            Got.READY_FIRST, 1, "Fish, potato and egg under a crust"));
        for (Cuisine.Dish cd : Cuisine.Dish.values()) {
            if (cd == Cuisine.Dish.RABBIT_STEW) continue;
            Course c = cd.kind == Cuisine.Kind.SWEET && cd != Cuisine.Dish.HARVEST_LOAF ? Course.DESSERTS : Course.MAINS;
            Station st = cd.bowl() ? Station.HEARTH : c == Course.DESSERTS ? Station.FURNACE : Station.SMOKER;
            out.add(new Dish("town/" + cd.name().toLowerCase(java.util.Locale.ROOT), cap(cd.words), () -> new ItemStack(cd.item()), null, c, st, 10,
                ALL, LUNCH_DINNER, Got.READY_FIRST, 1, "A town's own dish: " + cd.main() + " and more"));
        }
        // Desserts.
        out.add(new Dish("cake", "Slice of cake", () -> new ItemStack(Items.CAKE), () -> new ItemStack(Items.CAKE), Course.DESSERTS, Station.BENCH, 2, FOOD_HALLS, ANY, Got.READY_FIRST, 7,
            "Milk, sugar, egg and wheat"));
        out.add(new Dish("pumpkin_pie", "Pumpkin pie", () -> new ItemStack(Items.PUMPKIN_PIE), null, Course.DESSERTS, Station.FURNACE, 6, ALL, ANY,
            Got.READY_FIRST, 1, "Pumpkin, egg and sugar"));
        out.add(new Dish("cookies", "Cookies", () -> new ItemStack(Items.COOKIE), null, Course.DESSERTS, Station.BENCH, 2, FOOD_HALLS, ANY, Got.READY_FIRST, 1,
            "Wheat and cocoa, still warm"));
        out.add(new Dish("honey_cake", "Honey cake", () -> new ItemStack(KitchenItems.HONEY_CAKE.get()), null, Course.DESSERTS, Station.BENCH, 2, FOOD_HALLS,
            ANY, Got.SHELF, 1, "The beekeeper's honey, baked"));
        DISHES = d = List.copyOf(out);
        return d;
    }

    @Nullable
    public static Dish byId(String id) {
        for (Dish d : dishes()) if (d.id().equals(id)) return d;
        return null;
    }

    /** The dish this item is served as, or null. */
    @Nullable
    static Dish forItem(ItemStack s) {
        for (Dish d : dishes()) if (ItemStack.isSameItem(d.one(), s) && (!s.is(Items.POTION) || ItemStack.isSameItemSameComponents(d.one(), s))) return d;
        return null;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the meal and the season

    /** The meal on at this hour (breakfast to ten, lunch to four, dinner after). */
    public static Meal meal(long timeOfDay) {
        long t = Math.floorMod(timeOfDay, 24000L);
        if (t >= 22000L || t < 4000L) return Meal.BREAKFAST;
        return t < 10000L ? Meal.LUNCH : Meal.DINNER;
    }

    /** A warming dish (a stew, a soup, a pie, a roast): first on the card in autumn and winter. */
    static boolean warming(Dish d) {
        return d.station() == Station.HEARTH || d.station() == Station.SMOKER || d.station() == Station.FURNACE;
    }

    // ------------------------------------------------------------------ what the kitchen has

    /** The kitchen's portions already cut (a cake's slices, a wheel's), by place and dish: kept for the next order. */
    static final Map<String, Integer> CUT = new ConcurrentHashMap<>();

    static String cutKey(DiningPlaces.Place p, Dish d) {
        return p.building.anchor().asLong() + "/" + d.id();
    }

    /** What the stores hold, by item, counted once for a card (plain and marked stacks alike). */
    static Map<Item, Integer> held(ServerLevel level, UUID village) {
        Map<Item, Integer> out = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    /** Ready in the stores: as many as the stores can spare (the town's own larder kept back: Budget), the café's
     *  bottles matched to the drink. */
    static int ready(ServerLevel level, UUID village, Dish d, Map<Item, Integer> held) {
        ItemStack one = d.one();
        if (one.is(Items.POTION)) return Market.stock(level, village, s -> ItemStack.isSameItemSameComponents(s, one));
        int n = spareOf(level, village, one, held);
        if (d.whole() != null && !ItemStack.isSameItem(d.whole().get(), one)) n += spareOf(level, village, d.whole().get(), held) * d.portions();
        else if (d.whole() != null) n *= d.portions();
        return n;
    }

    private static int spareOf(ServerLevel level, UUID village, ItemStack one, Map<Item, Integer> held) {
        int n = held.getOrDefault(one.getItem(), 0);
        return n <= 0 ? 0 : Math.max(0, Math.min(n, Budget.spare(level, village, one)));
    }

    /** Can the kitchen make one now, out of the stores (the whole way, by the game's recipe)? */
    static boolean makeable(ServerLevel level, Villages.Village v, DiningPlaces.Place p, Dish d, @Nullable VillageFolkEntity cook) {
        if (d.got() == Got.SHELF) return false;
        Bench.Hand hand = Bench.handOf(level, v, cook, p.kind.structure);          // the place's own smoker, oven and hearth
        ItemStack target = d.whole() != null ? d.whole().get() : d.one();
        return Bench.plan(level, v, target.getItem(), 1, hand).ok();
    }

    // ------------------------------------------------------------------ today's card

    /** One line of today's card. */
    public record Line(Dish dish, int price, double each, boolean soldOut, boolean special, boolean local, int hunger, String about) {}

    private record Card(long at, List<Line> lines) {}

    private static final Map<String, Card> CARDS = new ConcurrentHashMap<>();
    /** How long a card is kept before it is made again from the stores (ticks). */
    static final long CARD_FOR = 200L;

    public static void resetForTests() {
        CARDS.clear();
        CUT.clear();
    }

    /** The card made again next time it is asked for (an order taken, a dish struck off). */
    static void stale(DiningPlaces.Place p) {
        CARDS.keySet().removeIf(k -> k.startsWith(p.building.anchor().asLong() + "/"));
    }

    /** Today's card at this place for this meal: drinks, starters, mains, desserts; the special and the local dish first,
     *  the warming dishes before the cold in the cold seasons; sold out last. */
    public static List<Line> card(ServerLevel level, DiningPlaces.Place p, Meal meal) {
        String key = p.building.anchor().asLong() + "/" + meal;
        long now = level.getGameTime();
        Card c = CARDS.get(key);
        if (c != null && now - c.at() < CARD_FOR && now >= c.at()) return c.lines();
        Villages.Village v = Villages.get(p.village);
        if (v == null) return List.of();
        Map<Item, Integer> held = held(level, p.village);
        Cuisine.Dish own = Cuisine.dishOf(p.village);
        Dish special = special(level, v, p, meal, held);
        Seasons.Season season = Seasons.season(p.village, level.getDayTime() / 24000L);
        boolean cold = season == Seasons.Season.AUTUMN || season == Seasons.Season.WINTER;
        List<Line> out = new ArrayList<>();
        for (Dish d : dishes()) {
            if (!d.where().contains(p.kind) || !d.meals().contains(meal)) continue;
            boolean local = own != null && d.one().is(own.item());
            boolean available = portionsCut(p, d) > 0 || ready(level, p.village, d, held) > 0 || makeable(level, v, p, d, null);
            double each = each(level, p, d, null);
            FoodProperties f = PlaceSettingBlock.foodOf(d.one());
            out.add(new Line(d, coins(each), each, !available, d == special, local, f == null ? 0 : f.nutrition(), d.about()));
        }
        out.sort((a, b) -> {
            if (a.dish().course() != b.dish().course()) return a.dish().course().compareTo(b.dish().course());
            if (a.soldOut() != b.soldOut()) return a.soldOut() ? 1 : -1;
            if (a.special() != b.special()) return a.special() ? -1 : 1;
            if (a.local() != b.local()) return a.local() ? -1 : 1;
            boolean aw = warming(a.dish()), bw = warming(b.dish());
            if (aw != bw) return (aw == cold) ? -1 : 1;
            return 0;
        });
        List<Line> kept = List.copyOf(out);
        CARDS.put(key, new Card(now, kept));
        return kept;
    }

    /** The head cook's special today: the town's own dish if it can be had, else the main the stores could make or hold
     *  the most of. Null if nothing on the mains can be had. */
    @Nullable
    static Dish special(ServerLevel level, Villages.Village v, DiningPlaces.Place p, Meal meal, Map<Item, Integer> held) {
        Cuisine.Dish own = Cuisine.dishOf(v.id());
        Dish best = null;
        int bestN = 0;
        for (Dish d : dishes()) {
            if (d.course() != Course.MAINS || !d.where().contains(p.kind) || !d.meals().contains(meal)) continue;
            int n = ready(level, v.id(), d, held) * 2 + (d.got() == Got.MADE ? makingsHeld(d, held) : 0);
            if (own != null && d.one().is(own.item()) && (n > 0 || makeable(level, v, p, d, null))) return d;
            if (n > bestN) { bestN = n; best = d; }
        }
        return best;
    }

    /** How much of a made dish's main making the stores hold (the raw beef for the roast): a rough measure of plenty. */
    private static int makingsHeld(Dish d, Map<Item, Integer> held) {
        Item cooked = d.one().getItem();
        Item raw = cooked == Items.COOKED_BEEF ? Items.BEEF : cooked == Items.COOKED_PORKCHOP ? Items.PORKCHOP
            : cooked == Items.COOKED_MUTTON ? Items.MUTTON : cooked == Items.COOKED_CHICKEN ? Items.CHICKEN
            : cooked == Items.COOKED_COD ? Items.COD : cooked == Items.COOKED_SALMON ? Items.SALMON : null;
        return raw == null ? 0 : held.getOrDefault(raw, 0);
    }

    /** The share added for being served at a table, by place: a tenth at the café, a sixth at the tavern, a quarter at
     *  the restaurant. */
    static double tableShare(Kind k) {
        return switch (k) {
            case CAFE -> 1.10;
            case TAVERN -> 1.16;
            case RESTAURANT -> 1.25;
        };
    }

    /** A serving's price to the hundredth (a slice: its share of the whole), for this buyer (a folk's knack; null for a
     *  player). */
    static double each(ServerLevel level, DiningPlaces.Place p, Dish d, @Nullable VillageFolkEntity buyer) {
        double whole = Purchases.priceEach(level, p.village, d.one(), buyer);
        return Math.max(0.05, whole / Math.max(1, d.portions()) * tableShare(p.kind));
    }

    /** A price in whole coins, as a player pays it: rounded up, a coin at least. */
    static int coins(double each) {
        return Math.max(1, (int) Math.ceil(each - 0.001));
    }

    /** Portions of this dish already cut at this place and waiting (a cake's slices). */
    static int portionsCut(DiningPlaces.Place p, Dish d) {
        return d.portions() <= 1 ? 0 : CUT.getOrDefault(cutKey(p, d), 0);
    }

    /** The menu board's four lines: the place, the special and its price, two more dishes, the hours. */
    static String[] board(ServerLevel level, DiningPlaces.Place p) {
        List<Line> card = card(level, p, meal(level.getDayTime()));
        String special = "", more = "";
        for (Line l : card) {
            if (l.soldOut()) continue;
            if (l.special() && special.isEmpty()) special = "Special: " + l.dish().name() + " " + l.price() + "c";
            else if (l.dish().course() == Course.MAINS && more.isEmpty()) more = l.dish().name() + " " + l.price() + "c";
        }
        if (special.isEmpty()) special = more.isEmpty() ? "Today's menu inside" : more;
        return new String[]{ "~ " + cap(p.words().replace("the ", "")) + " ~", clip(special), clip(more.equals(special) ? "" : more),
            p.kind.role.hours.words() };
    }

    private static String clip(String s) {
        return s.length() <= 15 ? s : s.substring(0, 14) + ".";
    }
}
