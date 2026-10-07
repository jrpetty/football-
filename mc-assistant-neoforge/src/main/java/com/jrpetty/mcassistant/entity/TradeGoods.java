package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.CivicItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [player-civic] The masters' own goods, made and used by the folk (CivicItems): nothing from nothing, all of it out of
 * the town's stores.
 * <ul>
 * <li><b>The reinforced pickaxe.</b> A master smith (level twenty-five) rivets one out of an iron pickaxe, three bars
 *     and a copper strap when the town has miners or cave dwellers and fewer than two in the stores, and the iron
 *     is not being put by for the age. They take it out of the stores as they would any pick, and it lasts them three
 *     times as long.</li>
 * <li><b>The brewer's stout.</b> A master brewer brews it for the tavern, a bottle, two wheat and a spoon of sugar
 *     (while the town is fed: the wheat is food first), up to six in the stores. Of an evening at the tavern a folk
 *     with the coin buys one at the bar, half the time, before the café's drinks: it digs the better for it.</li>
 * <li><b>[itemaudit] With no master yet</b> the town's best hand at the trade makes them, one turn in three (hand): a
 *     town does not wait months for its first master to eat a pie.</li>
 * <li><b>The farmhouse pie.</b> A master cook bakes two out of a pumpkin, an egg, a carrot and three wheat, up to
 *     eight in the stores; the town eats them like any food (ten hunger a pie).</li>
 * <li><b>The apprentice's journal.</b> The tailor binds one from a book, a feather, an ink sac and a strap of leather
 *     (not the scribe's book and quill: a journal is bound to last) when the town's
 *     young apprentices or a player's master want one. A child learning a trade at a grown-up's side takes one out
 *     of the stores, carries it, and writes up its day in it: a little of its trade's experience a day, put by for
 *     the day it takes the trade up (VillageFolkEntity.schoolXp).</li>
 * </ul>
 */
public final class TradeGoods {

    private TradeGoods() {}

    static final int PICKS_KEPT = 2, STOUTS_KEPT = 6, PIES_KEPT = 8;

    /** The day each child last wrote up its journal. */
    private static final Map<UUID, Long> WROTE = new ConcurrentHashMap<>();

    /** [itemaudit] Each hand's turns at the masters' goods with no master in the town: one in three makes them. */
    private static final Map<UUID, Integer> TURNS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WROTE.clear();
        TURNS.clear();
    }

    /** Tests: a master's own piece of work at its bench, now (what was made, or null). */
    @Nullable
    public static String craftForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return craft(level, v, f);
    }

    /** [itemaudit] Tests: a stout at the tavern's bar, as an evening there would have it (half the time). */
    public static boolean stoutForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        return stout(level, v, f, day);
    }

    /** [itemaudit] Tests: is this the hand that makes its trade's masters' goods, master or best hand (hand)? */
    public static boolean handForTests(Villages.Village v, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        return hand(v, f, t, f.tradeLevel(t) >= Lessons.MASTER);
    }

    /** [itemaudit] Tests: a reinforced pickaxe riveted in the stores by this smith's hand, marked with its name. */
    public static boolean pickByForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return makeOne(level, v, CivicItems.REINFORCED_PICKAXE.get(), f);
    }

    /** A master's own piece of work at its bench, if one is wanted and the stores have the makings (Crafts.now). */
    @Nullable
    static String craft(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        boolean master = f.tradeLevel(t) >= Lessons.MASTER;
        switch (t) {
            case SMITH -> {
                if (!hand(v, f, t, master) || !digs(v.id()) || Crafts.stock(level, v, s -> s.is(CivicItems.REINFORCED_PICKAXE.get())) >= PICKS_KEPT) return null;
                if (Crafts.savingIron(level, v) || Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) < 3 + Crafts.IRON_KEPT) return null;
                if (!turn(f, master) || !makeOne(level, v, CivicItems.REINFORCED_PICKAXE.get(), f)) return null;
                return "a reinforced pickaxe for the miners";
            }
            case BREW -> {
                if (!hand(v, f, t, master) || Tavern.of(v.id()) == null || Crafts.stock(level, v, s -> s.is(CivicItems.BREWERS_STOUT.get())) >= STOUTS_KEPT) return null;
                Leader.Plan plan = Leader.plan(v.id());
                if (plan == Leader.Plan.FAMINE || plan == Leader.Plan.SHORT) return null;      // the wheat is bread first
                if (!turn(f, master) || !makeOne(level, v, CivicItems.BREWERS_STOUT.get())) return null;
                return "a brewer's stout for the tavern";
            }
            case COOK -> {
                if (!hand(v, f, t, master) || Crafts.stock(level, v, s -> s.is(CivicItems.FARMHOUSE_PIE.get())) >= PIES_KEPT) return null;
                if (!turn(f, master) || !makeOne(level, v, CivicItems.FARMHOUSE_PIE.get())) return null;
                return "two farmhouse pies";
            }
            case TAILOR -> {
                if (Crafts.stock(level, v, s -> s.is(CivicItems.APPRENTICE_JOURNAL.get())) >= journalsWanted(v.id())) return null;
                if (!makeOne(level, v, CivicItems.APPRENTICE_JOURNAL.get())) return null;
                return "an apprentice's journal";
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * [itemaudit] Whose work the masters' goods are: the town's master of the trade's; and, while the town has none, its
     * best hand at the trade's. A smith takes weeks at the anvil to reach level ten and months to reach twenty-five, and
     * a town that waited for a master went without its pies, its stout and its miners' picks the whole of a long game.
     * A master makes them at every turn; a hand not yet a master one turn in three (turn), and a pick of its making is as
     * good as its hand (Craftsmanship: a beginner's wears through sooner and carries a beginner's mark).
     */
    static boolean hand(Villages.Village v, VillageFolkEntity f, StationTask t, boolean master) {
        if (master) return true;
        if (PlayerTrades.masterOf(v.id(), t) != null) return false;               // the town's master makes them
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity o) || o.isBaby() || o.isShowcase() || o.stationTask() != t) continue;
            if (best == null || o.tradeLevel(t) > best.tradeLevel(t)) best = o;
        }
        return best == f;
    }

    /** [itemaudit] A master's turn is every turn; a hand's not yet a master, one turn in three. */
    static boolean turn(VillageFolkEntity f, boolean master) {
        return master || TURNS.merge(f.getUUID(), 1, Integer::sum) % 3 == 0;
    }

    /** Does the town dig (miners or cave dwellers)? */
    static boolean digs(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() == StationTask.MINE || a.stationTask() == StationTask.CAVE) return true;
        }
        return false;
    }

    /** The journals the town's young apprentices still want, and one for a player taken on. */
    static int journalsWanted(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity k && k.isBaby() && k.apprenticedTo() != StationTask.NONE
                    && k.countCarried(s -> s.is(CivicItems.APPRENTICE_JOURNAL.get())) == 0) n++;
        }
        return Math.min(4, n + 1);
    }

    /**
     * One of these made in the stores out of the stores' makings, as its recipe has it (all or nothing). Two pies
     * to a baking.
     */
    static boolean makeOne(ServerLevel level, Villages.Village v, Item what) {
        return makeOne(level, v, what, null);
    }

    /** As makeOne, by this hand (the pick finished as good as it, marked with its name): the master smith's if null. */
    static boolean makeOne(ServerLevel level, Villages.Village v, Item what, @Nullable VillageFolkEntity by) {
        if (what == CivicItems.REINFORCED_PICKAXE.get()) {
            if (Crafts.stock(level, v, s -> s.is(Items.IRON_PICKAXE)) < 1 || Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) < 3
                    || Crafts.stock(level, v, s -> s.is(Items.COPPER_INGOT)) < 1) return false;
            ItemStack old = Crafts.takeOne(level, v, s -> s.is(Items.IRON_PICKAXE));
            if (old.isEmpty()) return false;
            if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 3)) { Crafts.store(level, v, old); return false; }
            if (!Crafts.take(level, v, s -> s.is(Items.COPPER_INGOT), 1)) {
                Crafts.store(level, v, old);
                Crafts.store(level, v, new ItemStack(Items.IRON_INGOT, 3));
                return false;
            }
            VillageFolkEntity smith = by != null ? by : PlayerTrades.masterOf(v.id(), StationTask.SMITH);
            ItemStack made = new ItemStack(CivicItems.REINFORCED_PICKAXE.get());
            if (smith != null) made = Craftsmanship.finish(level, made, smith.tradeLevel(StationTask.SMITH), smith.displayNameCap());
            Crafts.store(level, v, made);
            return true;
        }
        if (what == CivicItems.BREWERS_STOUT.get()) {
            return all(level, v, new Item[]{ Items.GLASS_BOTTLE, Items.WHEAT, Items.SUGAR }, new int[]{ 1, 2, 1 }, new ItemStack(what));
        }
        if (what == CivicItems.FARMHOUSE_PIE.get()) {
            return all(level, v, new Item[]{ Items.PUMPKIN, Items.EGG, Items.CARROT, Items.WHEAT }, new int[]{ 1, 1, 1, 3 }, new ItemStack(what, 2));
        }
        if (what == CivicItems.APPRENTICE_JOURNAL.get()) {
            return all(level, v, new Item[]{ Items.BOOK, Items.FEATHER, Items.INK_SAC, Items.LEATHER }, new int[]{ 1, 1, 1, 1 }, new ItemStack(what));
        }
        return false;
    }

    /** These makings out of the stores, all of them or none, and the thing made into the stores. */
    private static boolean all(ServerLevel level, Villages.Village v, Item[] parts, int[] counts, ItemStack made) {
        for (int i = 0; i < parts.length; i++) {
            Item it = parts[i];
            if (Crafts.stock(level, v, s -> s.is(it)) < counts[i]) return false;
        }
        for (int i = 0; i < parts.length; i++) {
            Item it = parts[i];
            if (!Crafts.take(level, v, s -> s.is(it), counts[i])) {
                for (int j = 0; j < i; j++) Crafts.store(level, v, new ItemStack(parts[j], counts[j]));   // put back what was taken
                return false;
            }
        }
        Crafts.store(level, v, made);
        return true;
    }

    /** A journal for a player the master has just taken on: out of the stores, or bound there of the stores' makings. */
    static ItemStack journalFor(ServerLevel level, Villages.Village v) {
        Item j = CivicItems.APPRENTICE_JOURNAL.get();
        if (Crafts.stock(level, v, s -> s.is(j)) < 1 && !makeOne(level, v, j)) return ItemStack.EMPTY;
        return Crafts.take(level, v, s -> s.is(j), 1) ? new ItemStack(j) : ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ the tavern

    /**
     * A stout at the bar (Tavern.drink, once an evening): half the time, while the stores have one and the folk the
     * coin. Paid into the treasury and drunk there and then: Haste for the morning's digging, and the bottle back.
     * True if it had one (and so has had its drink for the evening).
     */
    static boolean stout(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        Item stout = CivicItems.BREWERS_STOUT.get();
        if (f.getRandom().nextBoolean() || Crafts.stock(level, v, s -> s.is(stout)) < 1) return false;
        int price = Math.max(2, (int) Math.ceil(Prices.each(stout) * 1.2));
        if (f.purse() < price || !Crafts.take(level, v, s -> s.is(stout), 1)) return false;
        if (!f.spend(price)) {
            Crafts.store(level, v, new ItemStack(stout));
            return false;
        }
        Ledger.addCoins(v.id(), price);
        Economy.spentInTown(v.id(), price);
        f.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 2400, 0));
        Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A stout, please — the brewer's best.", "Nothing like a stout after a day's work.",
                "That'll put the dig back in me."));
        }
        f.brain("bought a brewer's stout at the tavern for " + price);
        return true;
    }

    // ------------------------------------------------------------------ the young apprentices

    /**
     * Once a day (PlayerCivic.tick): a child at a grown-up's side takes a journal out of the stores if it has none,
     * and one that has one writes up its day in it.
     */
    static void journals(ServerLevel level, Villages.Village v, long day) {
        Item j = CivicItems.APPRENTICE_JOURNAL.get();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity k) || !k.isBaby() || k.apprenticedTo() == StationTask.NONE) continue;
            if (k.countCarried(s -> s.is(j)) == 0) {
                if (Crafts.stock(level, v, s -> s.is(j)) < 1 || !Crafts.take(level, v, s -> s.is(j), 1)) continue;
                ItemStack left = k.insertItem(new ItemStack(j));
                if (!left.isEmpty()) { Crafts.store(level, v, left); continue; }
                k.persona().remember(day, "I was given an apprentice's journal of my own", 3);
                FolkTalk.speak(k, "A journal of my own! I'm going to write down everything.");
                WROTE.put(k.getUUID(), day);
                continue;
            }
            if (WROTE.getOrDefault(k.getUUID(), -1L) == day) continue;
            WROTE.put(k.getUUID(), day);
            k.schoolXp(k.apprenticedTo(), PlayerTrades.JOURNAL_XP);
        }
    }
}
