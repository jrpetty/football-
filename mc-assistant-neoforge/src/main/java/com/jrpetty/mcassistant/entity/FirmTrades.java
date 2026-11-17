package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [supply] A trade left to its tradesmen: the town's own makers step back from what a private firm makes.
 *
 * <p>The town's counter (the café's kitchen, the shop's bench, the smith at the anvil, the idle hands baking a batch at the
 * stores) kept its shelves full of everything, firm or none: a toolshop opened by its own folk sold nothing while the
 * shop's bench and the smith kept two of every iron tool in the stores, and the town's cook baked the bread a bakery was
 * opened to bake. A real town leaves a trade to the tradesmen in it. While a firm of the town's own folk makes a ware (it
 * holds some, or has made some in the last two days) and has the hands to make it, the town's makers make none of it for
 * the counter; buyers choose among what there is by price, the walk and the seller's name (Sellers.best). A firm out of
 * a ware for two days, or closed, and the town's makers take it up again: nobody goes without for a firm that has let
 * its trade go. The watch's arms are the town's own business still (Crafts.forge).
 */
public final class FirmTrades {

    private FirmTrades() {}

    /** A firm's ware counts as made by it this many days after it last made or held any. */
    static final int DAYS = 2;

    private static final Map<UUID, Set<String>> LEFT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> AT = new ConcurrentHashMap<>();
    private static volatile boolean off;

    /** Tests: the town's makers never step back (the old way), for a test that wants the town's counter as it was. */
    public static void offForTests(boolean b) {
        off = b;
        LEFT.clear();
        AT.clear();
    }

    /** Is this ware (by its key in the books) left to a firm of the town's own, so the town's makers make none for the counter? */
    public static boolean leftTo(ServerLevel level, UUID village, String key) {
        if (off || village == null || key == null) return false;
        return wares(level, village).contains(key);
    }

    /** The wares the town's firms have taken up, looked at again every ten seconds. */
    static Set<String> wares(ServerLevel level, UUID village) {
        long now = level.getGameTime();
        Long at = AT.get(village);
        Set<String> was = LEFT.get(village);
        if (was != null && at != null && now - at < 200 && now >= at) return was;
        Set<String> out = new HashSet<>();
        long day = Businesses.today(level);
        for (Business b : Enterprise.open(village)) {
            BusinessTypes.BusinessType t = Enterprise.kindOf(village, b);
            if (t == null || t.resells() || Enterprise.hands(village, b).isEmpty()) continue;
            Business.Day recent = b.last(day, DAYS + 1);
            for (String key : t.wares()) {
                Business.Line l = recent.lines.get(key);
                if (Enterprise.held(b, key) > 0 || (l != null && l.made > 0)) out.add(key);
            }
        }
        LEFT.put(village, out);
        AT.put(village, now);
        return out;
    }
}
