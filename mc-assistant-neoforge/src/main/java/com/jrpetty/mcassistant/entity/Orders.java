package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The elder's orders: what the village's leader wants its people to put their backs into for
 * the next few days. An order changes nothing but the village's make-up — a few more hands in
 * one trade, a few fewer elsewhere — and folk move over to it one at a time, at most one a day,
 * from the trades with hands to spare.
 * <ul>
 * <li>The elder looks again every three days. What it orders comes from what the village is
 *     short of (food, timber, stone and iron, safety), how it is doing, and the elder's own
 *     nature and trade: a hardworking miner of an elder digs, a generous one fills the larder,
 *     a grumpy one mans the walls.</li>
 * <li>The order is posted at the head of the quest board on the meeting hall, goes into the
 *     village's history, and every folk can tell you what it is.</li>
 * <li>A player the elder thinks well of (or a citizen) can put it to the elder: "the village
 *     should dig for iron". It may agree.</li>
 * </ul>
 */
public final class Orders {

    private Orders() {}

    public enum Order {
        STEADY("Steady as we go", "We've a good balance. Everybody carry on as you are.", "steady", "carry on", "as we are"),
        LARDER("Fill the larder", "Food first: more hands to the fields and the river.", "food", "larder", "farm", "harvest", "hungry"),
        TIMBER("Timber for the builders", "The builders are waiting on wood: more axes in the woods.", "timber", "wood", "logs", "trees"),
        DIG("Dig deep", "We need stone and iron: more picks in the mine, and the furnaces kept hot.", "dig", "mine", "miner", "iron", "stone", "ores"),
        WATCH("Man the walls", "These are dangerous nights: more of us on the watch.", "watch", "guard", "walls", "defend", "safe"),
        HERDS("Grow the herds", "Wool, leather, milk and eggs: more hands at the pens.", "herds", "animals", "sheep", "cows", "pens", "wool"),
        MARKET("Fill the stalls", "Let's make a name at market: more for the shop, the café and the crafts.", "market", "trade", "shop", "crafts", "sell"),
        RIVER("To the water", "The river feeds us too: more lines in the water.", "fish", "river", "water", "boats");

        public final String title, words;
        final String[] keys;

        Order(String title, String words, String... keys) {
            this.title = title;
            this.words = words;
            this.keys = keys;
        }

        /** How many more (or fewer) hands this order wants in a trade, in tenths of the village. */
        public int boost(StationTask t) {
            return switch (this) {
                case LARDER -> t == StationTask.FARM ? 2 : t == StationTask.FISH ? 1 : 0;
                case TIMBER -> t == StationTask.WOOD ? 2 : 0;
                case DIG -> t == StationTask.MINE ? 2 : t == StationTask.SMELT ? 1 : 0;
                case WATCH -> t == StationTask.GUARD ? 2 : 0;
                case HERDS -> t == StationTask.RANCH ? 2 : 0;
                case MARKET -> t == StationTask.SHOP || t == StationTask.COOK || t == StationTask.SMITH || t == StationTask.TAILOR ? 1 : 0;
                case RIVER -> t == StationTask.FISH ? 2 : 0;
                case STEADY -> 0;
            };
        }

        int total() {
            int n = 0;
            for (StationTask t : StationTask.values()) n += boost(t);
            return n;
        }
    }

    /** Each village's order, and the day it was given. */
    record Given(Order order, long day, String by) {}

    private static final Map<UUID, Given> GIVEN = new ConcurrentHashMap<>();
    /** The day a player last had the orders changed, by village: once a day at most. */
    private static final Map<UUID, Long> PETITIONED = new ConcurrentHashMap<>();
    /** The day a folk last moved trade to follow the order, by village. */
    private static final Map<UUID, Long> MOVED = new ConcurrentHashMap<>();

    /** A day kept in the village's ledger (so a restart doesn't give a second move or petition), or -1. */
    private static Long savedDay(UUID village, String key) {
        String s = com.jrpetty.mcassistant.village.Ledger.note(village, key);
        try {
            return s == null ? -1L : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public static void resetForTests() {
        GIVEN.clear();
        MOVED.clear();
        PETITIONED.clear();
    }

    @Nullable
    public static Order current(@Nullable UUID village) {
        if (village == null) return null;
        Given g = given(village);
        return g == null ? null : g.order();
    }

    @Nullable
    static Given given(UUID village) {
        Given g = GIVEN.get(village);
        if (g != null) return g;
        // After a restart: the order the village was under, kept with the world.
        String kept = Ledger.note(village, "order");
        if (kept == null || kept.isEmpty()) return null;
        String[] p = kept.split("\\|", 3);
        try {
            g = new Given(Order.valueOf(p[0]), Long.parseLong(p[1]), p.length > 2 ? p[2] : "");
        } catch (RuntimeException e) {
            return null;
        }
        GIVEN.put(village, g);
        return g;
    }

    static void give(UUID village, Order o, long day, String by) {
        Given g = new Given(o, day, by);
        GIVEN.put(village, g);
        Ledger.note(village, "order", o.name() + "|" + day + "|" + by);
    }

    /** The extra share of a trade the village's order asks for. */
    public static int boost(@Nullable UUID village, StationTask t) {
        Order o = current(village);
        return o == null ? 0 : o.boost(t);
    }

    /** What every trade's share is scaled by, so the order's extra hands come out of the others —
     *  only the extra hands it can actually have: a trade the village is too small or too young for
     *  is no part of it. */
    public static double scale(@Nullable UUID village) {
        Order o = current(village);
        if (o == null) return 1.0;
        int extra = 0;
        for (StationTask t : StationTask.values()) if (o.boost(t) > 0 && Villages.wants(village, t)) extra += o.boost(t);
        return 10.0 / (10.0 + extra);
    }

    /** Does this order ask for any trade the village could have now? */
    static boolean possible(@Nullable UUID village, Order o) {
        if (o == Order.STEADY) return true;
        for (StationTask t : StationTask.values()) if (o.boost(t) > 0 && Villages.wants(village, t)) return true;
        return false;
    }

    /** Is the village short of food today? */
    static boolean hungry(ServerLevel level, UUID village) {
        for (Villages.Need n : Villages.needs(level, village)) if (n.task() == Villages.Task.FOOD) return true;
        return false;
    }

    // ------------------------------------------------------------------ the elder decides

    /** Once a day, from the elder's daily look round: every third day, the orders. */
    public static void consider(ServerLevel level, UUID village, long day) {
        consider(level, village, day, false);
    }

    /** As above; {@code now}: the leader calls it this morning, not when the three days are up. */
    public static void consider(ServerLevel level, UUID village, long day, boolean now) {
        Given g = given(village);
        // (A clock set back — /time set — makes the day go backwards: that counts as due.)
        if (!now && g != null && day >= g.day() && day - g.day() < 3) return;
        if (Villages.headcount(village) < 8 || day - Math.max(0, com.jrpetty.mcassistant.village.Chronicle.foundedOn(village)) < 2) return;
        VillageFolkEntity elder = elderOf(village);
        if (elder == null) return;
        Order pick = choose(level, village, elder, g == null ? null : g.order());
        Order was = g == null ? null : g.order();
        give(village, pick, day, elder.displayNameCap());
        if (pick != was) {
            String line = "Elder " + elder.displayNameCap() + " ordered: " + pick.title.toLowerCase(Locale.ROOT)
                + " — " + pick.words.substring(pick.words.indexOf(':') + 1).trim().replaceAll("\\.$", "");
            Villages.tell(village, day, pick == Order.STEADY ? "Elder " + elder.displayNameCap() + " told everybody to carry on as they are" : line);
            FolkTalk.speak(elder, pick.title + "! " + pick.words);
        }
        Villages.Village v = Villages.get(village);
        if (v != null) Quests.paint(level, v);
    }

    @Nullable
    static VillageFolkEntity elderOf(UUID village) {
        UUID id = Villages.elder(village);
        if (id == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.getUUID().equals(id)) return f;
        return null;
    }

    /** What the elder orders: the village's wants first, its own nature and trade after. */
    static Order choose(ServerLevel level, UUID village, VillageFolkEntity elder, @Nullable Order now) {
        Map<Order, Integer> score = new EnumMap<>(Order.class);
        for (Order o : Order.values()) score.put(o, 0);
        score.merge(Order.STEADY, 2, Integer::sum);
        boolean short_ = false;
        for (Villages.Need n : Villages.needs(level, village)) {
            switch (n.task()) {
                case FOOD -> score.merge(Order.LARDER, 5, Integer::sum);
                case LOGS -> score.merge(Order.TIMBER, 4, Integer::sum);
                case STONE, IRON, COAL -> score.merge(Order.DIG, 3, Integer::sum);
                case DIAMOND, OBSIDIAN -> score.merge(Order.DIG, 2, Integer::sum);
                default -> { }
            }
            if (n.task() != Villages.Task.BUILD && n.task() != Villages.Task.HANDS && n.task() != Villages.Task.NONE) short_ = true;
        }
        // "Steady as we go" is no order while the age waits on something the village could go and
        // get: an easygoing thane sat on it for days with the hamlet short of timber.
        if (short_) score.merge(Order.STEADY, -3, Integer::sum);
        // Past the Wood Age nothing asks for timber by name, but every building is half wood: a
        // town of sixty-eight with no logs in its stores for days could not pay for its hall.
        if (Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal()) {
            int logs = Market.stock(level, village, s -> s.is(net.minecraft.tags.ItemTags.LOGS));
            if (logs < 16) score.merge(Order.TIMBER, 7, Integer::sum);         // nothing can be built
            else if (logs < 48) score.merge(Order.TIMBER, 4, Integer::sum);
        }
        Contentment.View c = Contentment.of(level, village);
        if (c != null) {
            if (c.food() < 12) score.merge(Order.LARDER, 4, Integer::sum);
            if (c.safety() < 6) score.merge(Order.WATCH, 4, Integer::sum);
            if (c.score() >= 70 && Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal()) {
                score.merge(Order.MARKET, 3, Integer::sum);
            }
        }
        if (Raids.underAlarm(village)) score.merge(Order.WATCH, 3, Integer::sum);
        boolean fisher = false, tailor = false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() == StationTask.FISH) fisher = true;
            if (a.stationTask() == StationTask.TAILOR) tailor = true;
        }
        if (fisher && c != null && c.food() < 18) score.merge(Order.RIVER, 2, Integer::sum);
        if (tailor) score.merge(Order.HERDS, 2, Integer::sum);
        // Folk sleeping on the ground for want of wool for beds (three to a bed): more sheep.
        if (c != null && c.homes() < 12 && Market.stock(level, village, s -> s.is(net.minecraft.tags.ItemTags.WOOL)) < 9) {
            score.merge(Order.HERDS, 2, Integer::sum);
        }
        // The elder's own nature, and its own trade.
        Social.Life life = elder.life();
        if (life.has(Social.Trait.HARDWORKING)) { score.merge(Order.DIG, 2, Integer::sum); score.merge(Order.TIMBER, 1, Integer::sum); }
        if (life.has(Social.Trait.GENEROUS)) score.merge(Order.LARDER, 2, Integer::sum);
        if (life.has(Social.Trait.GRUMPY)) score.merge(Order.WATCH, 2, Integer::sum);
        if (life.has(Social.Trait.SOCIABLE) || life.has(Social.Trait.CHEERFUL)) score.merge(Order.MARKET, 2, Integer::sum);
        if (life.has(Social.Trait.CURIOUS)) { score.merge(Order.MARKET, 1, Integer::sum); score.merge(Order.RIVER, 1, Integer::sum); }
        if (life.has(Social.Trait.EASYGOING)) score.merge(Order.STEADY, 2, Integer::sum);
        if (life.has(Social.Trait.SHY)) score.merge(Order.STEADY, 1, Integer::sum);
        switch (elder.stationTask()) {
            case FARM -> score.merge(Order.LARDER, 1, Integer::sum);
            case WOOD -> score.merge(Order.TIMBER, 1, Integer::sum);
            case MINE, SMELT -> score.merge(Order.DIG, 1, Integer::sum);
            case GUARD -> score.merge(Order.WATCH, 1, Integer::sum);
            case FISH -> score.merge(Order.RIVER, 1, Integer::sum);
            case RANCH -> score.merge(Order.HERDS, 1, Integer::sum);
            case COOK, SHOP, SMITH, TAILOR, BREW, ENCHANT, BEEKEEP -> score.merge(Order.MARKET, 1, Integer::sum);
            default -> { }
        }
        // An order that wants a trade the village has no use for yet is no order at all: a watch
        // for a village too small for guards, a market for one with no shop or café to man.
        for (Order o : Order.values()) if (!possible(village, o)) score.put(o, -100);
        if (!fisher) score.put(Order.RIVER, -100);
        boolean hungry = hungry(level, village);
        if (hungry && possible(village, Order.LARDER)) score.merge(Order.LARDER, 3, Integer::sum);
        Order best = Order.STEADY;
        int top = Integer.MIN_VALUE;
        for (Map.Entry<Order, Integer> e : score.entrySet()) if (e.getValue() > top) { top = e.getValue(); best = e.getKey(); }
        // A standing order stays unless something else is clearly wanted more — but not while the
        // village goes hungry on an order that is not for food.
        if (now != null && possible(village, now) && score.get(now) >= top - 2 && !(hungry && now != Order.LARDER)
                && !(short_ && now == Order.STEADY && best != Order.STEADY)) return now;
        return best;
    }

    // ------------------------------------------------------------------ folk follow the order

    /** A folk with hands to spare, by the order, moves to the trade it wants — one a day, a village. */
    @Nullable
    public static StationTask move(UUID village, VillageFolkEntity f, long day) {
        Order o = current(village);
        Long moved = MOVED.computeIfAbsent(village, k -> savedDay(k, "orders.moved"));
        if (o == null || o == Order.STEADY || (moved != null && moved == day)) return null;
        StationTask mine = f.stationTask();
        // Never off the watch: an order shifts who farms and who digs, it does not strip the walls
        // (under "fill the stalls" the long game's guards went to the shop counters one a day).
        if (mine == StationTask.NONE || mine == StationTask.GUARD || o.boost(mine) > 0 || mine.isCraft()) return null;
        // And never off the fields while the village is short of food.
        if (mine == StationTask.FARM && f.level() instanceof ServerLevel level && hungry(level, village)) return null;
        double spare = Villages.share(village, mine);
        if (spare < 0.6) return null;                                   // not a hand to spare
        StationTask want = null;
        double most = 0.5;
        for (StationTask t : StationTask.values()) {
            if (o.boost(t) <= 0) continue;
            double short_ = -Villages.share(village, t);
            if (short_ >= most) { most = short_; want = t; }
        }
        return want;
    }

    /** A folk did move to follow the order (once it had found its ground): that is the day's move. */
    public static void moved(UUID village, long day) {
        MOVED.put(village, day);
        com.jrpetty.mcassistant.village.Ledger.note(village, "orders.moved", Long.toString(day));
    }

    // ------------------------------------------------------------------ talk

    /** "What are the elder's orders?" — and, to the elder, a player's say in them. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null) return "Orders? Nobody gives me orders. I've no village.";
        VillageFolkEntity elder = elderOf(village);
        String lower = text.toLowerCase(Locale.ROOT);
        if (elder == f && !lower.isEmpty()) {
            Order asked = named(lower);
            if (asked != null) return petition(f, p, village, asked);
        }
        Given g = given(village);
        String next = Villages.nextProject(village);
        String building = next == null ? "" : " We're building " + Villages.spoken(next) + " next.";
        if (g == null) {
            return (elder == null ? "We've no elder yet to give orders." : "Elder " + elder.displayNameCap() + " hasn't given any orders yet.")
                + building;
        }
        String who = elder == f ? "I've told everybody" : "Elder " + g.by() + " says";
        return who + ": " + g.order().title.toLowerCase(Locale.ROOT) + ". " + g.order().words + Leader.plan(f) + building
            + (elder == f ? " If you think we should be doing something else, tell me." : "");
    }

    /**
     * Which order a player's words ask for: whole words only ("ore" was found in "more" and
     * "store", so asking for more guards ordered the village down the mine), a word may run on
     * ("guards", "fishing"), and the order the words name most often wins.
     */
    @Nullable
    public static Order named(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ") + " ";
        Order best = null;
        int bestHits = 0;
        for (Order o : Order.values()) {
            int hits = 0;
            for (String k : o.keys) {
                if (k.contains(" ") ? t.contains(" " + k + " ") : t.contains(" " + k)) hits++;
            }
            if (hits > bestHits) { bestHits = hits; best = o; }
        }
        return best;
    }

    /** A player asks the elder for an order. It agrees if it thinks well of the player. */
    static String petition(VillageFolkEntity elder, Player p, UUID village, Order asked) {
        long day = elder.level().getDayTime() / 24000L;
        int warmth = elder.persona().affinity(p.getUUID());
        boolean citizen = Citizens.is(village, p.getUUID());
        Order now = current(village);
        if (asked == now) return "That's what I've ordered already: " + asked.title.toLowerCase(Locale.ROOT) + ".";
        if (Laws.banished(village, p.getUUID(), day) || warmth <= -50) return "You? Tell me how to run this village? Leave me be.";
        if (!possible(village, asked)) return "We've nobody for that yet — we're too small a place. Ask me again when we've grown.";
        if (warmth < 40 && !(citizen && warmth >= 0)) return "I'll think on it. But I know this village, and you don't — not yet.";
        Long last = PETITIONED.computeIfAbsent(village, k -> savedDay(k, "orders.petitioned"));
        if (last != null && last == day) return "I've changed the orders once today already. Let them settle.";
        PETITIONED.put(village, day);
        com.jrpetty.mcassistant.village.Ledger.note(village, "orders.petitioned", Long.toString(day));
        give(village, asked, day, elder.displayNameCap());
        Villages.tell(village, day, "Elder " + elder.displayNameCap() + " ordered: " + asked.title.toLowerCase(Locale.ROOT)
            + ", as " + p.getName().getString() + " asked");
        FolkTalk.speak(elder, asked.title + "! " + asked.words);
        Villages.Village v = Villages.get(village);
        if (v != null && elder.level() instanceof ServerLevel level) Quests.paint(level, v);
        return "You may be right. Very well: " + asked.title.toLowerCase(Locale.ROOT) + ". " + asked.words;
    }

    /** The board's head: the order, in four short lines. */
    @Nullable
    public static String[] sign(UUID village) {
        Given g = given(village);
        if (g == null) return null;
        String t = g.order().title;
        String a = t, b = "";
        if (t.length() > 15) {
            int cut = t.lastIndexOf(' ', 15);
            if (cut > 0) { a = t.substring(0, cut); b = t.substring(cut + 1); }
        }
        return new String[]{ "ELDER'S ORDERS", a, b.length() > 15 ? b.substring(0, 15) : b, ("- " + g.by()).length() > 15
            ? ("- " + g.by()).substring(0, 15) : "- " + g.by() };
    }

    /** All the orders there are, for the docs and the status. */
    public static List<Order> all() {
        return List.of(Order.values());
    }
}
