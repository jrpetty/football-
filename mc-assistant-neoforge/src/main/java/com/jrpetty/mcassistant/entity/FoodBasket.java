package com.jrpetty.mcassistant.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ-1d] What a household puts in its basket: not only the cheapest bellyful.
 *
 * <p>A folk choosing its food at the counter weighed only how filling a thing was against its price (Purchases.choose), so
 * once the fields came good a whole town lived on glut carrots at a few pennies and walked past the baker's bread. Real
 * households buy a basket: a staple, something cooked, a change from yesterday. Two things are weighed now beside the
 * price and how filling it is:
 * <ul>
 * <li><b>Taste.</b> A made meal (bread, a cooked joint or fish, a baked potato, a stew, a pie, a cake) is worth more to a
 *     body than the raw stuff of it; a raw vegetable or fruit a little less; raw meat or fish, that wants cooking, least.</li>
 * <li><b>Variety.</b> The same thing bought again and again palls: each time it is among the folk's last eight buys it is
 *     worth a little less, so the basket turns over (a farmer's carrots one day, the baker's bread the next).</li>
 * </ul>
 * The poor still mind the price most (Purchases.choose's care by means); this only gives the made thing its due. Kept in
 * memory (a folk's last buys are forgotten with a restart, as a body forgets what it ate last week).
 */
public final class FoodBasket {

    private FoodBasket() {}

    /** The last buys remembered for variety. */
    static final int REMEMBERED = 8;
    /** Each time a thing is among the last buys it is worth so much less (one over one and this, times it). */
    static final double PALLS = 0.3;

    private static final Map<UUID, ArrayDeque<String>> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
    }

    /** How a body values this as a meal, beside how filling it is: a made meal the more, raw meat the less. */
    public static double taste(ItemStack s) {
        if (s.is(Items.BREAD) || s.is(Items.BAKED_POTATO) || s.is(Items.PUMPKIN_PIE) || s.is(Items.CAKE) || s.is(Items.MUSHROOM_STEW)
            || s.is(Items.RABBIT_STEW) || s.is(Items.BEETROOT_SOUP) || s.is(Items.SUSPICIOUS_STEW) || s.is(Items.COOKIE)) return 1.4;
        if (s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_CHICKEN)
            || s.is(Items.COOKED_RABBIT) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON) || s.is(Items.DRIED_KELP)) return 1.3;
        if (s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN) || s.is(Items.RABBIT)
            || s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.TROPICAL_FISH)) return 0.55;
        if (s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT) || s.is(Items.WHEAT) || s.is(Items.MELON_SLICE)
            || s.is(Items.SWEET_BERRIES) || s.is(Items.GLOW_BERRIES) || s.is(Items.APPLE) || s.is(Items.KELP)) return 0.85;
        // A mod's own dish: by whether it fills like a meal.
        var fp = s.get(net.minecraft.core.component.DataComponents.FOOD);
        return fp != null && fp.nutrition() >= 6 ? 1.2 : 1.0;
    }

    /**
     * [supply] Each time something of the same kind of food (the roots, the baker's, the meat, the fish, the fruit) is among
     * the last buys, it palls a little too: a household that has eaten potatoes, baked potatoes and carrots all week wants a
     * loaf, whatever the potatoes cost. Judged by the item alone, a town lived on roots and walked past its baker: the potato
     * raw, baked and the carrot were three different things to it.
     */
    static final double PALLS_KIND = 0.2;

    /** [supply] The kind of food a thing is, for variety: "roots", "bakes", "meat", "fish", "fruit", "stew", or its own key. */
    static String kindOf(ItemStack s) {
        if (s.is(Items.POTATO) || s.is(Items.BAKED_POTATO) || s.is(Items.CARROT) || s.is(Items.BEETROOT) || s.is(Items.BEETROOT_SOUP)
            || s.is(Items.POISONOUS_POTATO)) return "roots";
        if (s.is(Items.BREAD) || s.is(Items.COOKIE) || s.is(Items.CAKE) || s.is(Items.PUMPKIN_PIE) || s.is(Items.WHEAT)) return "bakes";
        if (s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN) || s.is(Items.RABBIT) || s.is(Items.COOKED_BEEF)
            || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_CHICKEN) || s.is(Items.COOKED_RABBIT)
            || s.is(Items.RABBIT_STEW)) return "meat";
        if (s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.TROPICAL_FISH) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON)
            || s.is(Items.DRIED_KELP) || s.is(Items.KELP)) return "fish";
        if (s.is(Items.APPLE) || s.is(Items.MELON_SLICE) || s.is(Items.SWEET_BERRIES) || s.is(Items.GLOW_BERRIES)) return "fruit";
        if (s.is(Items.MUSHROOM_STEW) || s.is(Items.SUSPICIOUS_STEW)) return "stew";
        return Stockroom.key(s);
    }

    /** How fresh a change this is to the folk: one, less for each time it (or [supply] its kind of food) is among its last buys. */
    public static double variety(UUID folk, ItemStack s) {
        ArrayDeque<String> q = LAST.get(folk);
        if (q == null || q.isEmpty()) return 1.0;
        String k = Stockroom.key(s), kind = kindOf(s);
        int n = 0, same = 0;
        synchronized (q) {
            for (String x : q) {
                if (x.equals(k)) n++;
                else if (kind.equals(KIND_OF.getOrDefault(x, x))) same++;   // [supply]
            }
        }
        return 1.0 / (1.0 + PALLS * n + PALLS_KIND * same);
    }

    /** [supply] Each remembered key's kind of food (filled as things are bought). */
    private static final Map<String, String> KIND_OF = new ConcurrentHashMap<>();

    /** What the folk sees in it for its basket: taste and variety together. */
    public static double appeal(UUID folk, ItemStack s) {
        return taste(s) * variety(folk, s);
    }

    /** Bought: remembered among its last buys. */
    public static void bought(UUID folk, ItemStack s) {
        if (folk == null || s.isEmpty()) return;
        if (LAST.size() > 8192) LAST.clear();
        ArrayDeque<String> q = LAST.computeIfAbsent(folk, k -> new ArrayDeque<>());
        KIND_OF.putIfAbsent(Stockroom.key(s), kindOf(s));                // [supply]
        synchronized (q) {
            q.add(Stockroom.key(s));
            while (q.size() > REMEMBERED) q.poll();
        }
    }
}
