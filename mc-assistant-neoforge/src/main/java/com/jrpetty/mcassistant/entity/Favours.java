package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [batchF] Good turns between neighbours. Of an evening (or on the day of rest), a folk short of something goes
 * round to a neighbour (a folk living within a street or two of it, not one it cannot abide) and asks:
 * <ul>
 * <li><b>the loan of a tool</b>: a hand without the tool its trade wants (an axe, a pickaxe, a hoe, a rod...)
 *     borrows one out of a neighbour's own pack, if the neighbour has one it does not need (another trade's
 *     tool, or a second of its own);</li>
 * <li><b>a cup of sugar</b>: a folk that has gone without a meal and has nothing to eat in its pack borrows a
 *     bite from a neighbour with plenty (sugar if it has any, else the plainest food it carries);</li>
 * <li><b>a hand with a load</b>: a neighbour sees a folk come in off work with its pack full, takes half
 *     of the load on its own back and carries it to the stores for it.</li>
 * </ul>
 * Every item changes packs for real. A good turn is paid back: the tool handed back once the borrower has
 * its own (or, two days on and still wanted, bought off the lender for two coins), a bite of food returned
 * once there is food to spare (or a coin), the help with a load repaid with a coin. Both think the better
 * of each other, more again when the debt is settled; and a folk that has done five good turns is known
 * about the town as a good neighbour — the chronicle says so, and its card and the town's books.
 */
public final class Favours {

    private Favours() {}

    public enum Kind {
        TOOL("the loan of a tool"), FOOD("a cup of sugar"), CARRY("a hand with a load");

        public final String words;

        Kind(String words) { this.words = words; }
    }

    /** Good turns that make a good neighbour. */
    public static final int GOOD = 5;
    /** How far apart two folk's homes can be and still be neighbours. */
    static final int NEIGHBOURS = 24;
    /** Days a loan or a bite is waited on before it is paid for in coin. */
    static final int GRACE = 2;

    /** A walk on a good turn: who asks whom for what (or carries what, or pays back what). */
    static final class Errand {
        final Kind kind;
        final UUID other;
        final boolean repay;
        final int since;
        final String need;
        final List<ItemStack> load = new ArrayList<>();

        Errand(Kind kind, UUID other, boolean repay, String need, int since) {
            this.kind = kind;
            this.other = other;
            this.repay = repay;
            this.need = need;
            this.since = since;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ASKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ERRANDS.clear();
        ASKED.clear();
    }

    // ------------------------------------------------------------------ what a folk might be short of

    /** The tool a trade's checklist wants ("an axe", "a pickaxe"...), as a test of an item, or null. */
    @Nullable
    static Predicate<ItemStack> tool(String need) {
        String n = need.toLowerCase(Locale.ROOT);
        if (n.contains("pickaxe")) return s -> s.getItem() instanceof PickaxeItem;
        if (n.contains("axe")) return s -> s.getItem() instanceof AxeItem;
        if (n.contains("shovel")) return s -> s.getItem() instanceof ShovelItem;
        if (n.contains("hoe")) return s -> s.getItem() instanceof HoeItem;
        if (n.contains("fishing rod")) return s -> s.getItem() instanceof FishingRodItem;
        if (n.contains("sword")) return s -> s.getItem() instanceof SwordItem;
        if (n.contains("shears")) return s -> s.getItem() instanceof ShearsItem;
        return null;
    }

    /** Does its own trade use this sort of tool (so it keeps one for itself)? */
    static boolean usesIt(VillageFolkEntity f, String need) {
        String n = need.toLowerCase(Locale.ROOT);
        return switch (f.stationTask()) {
            case MINE -> n.contains("pickaxe") || n.contains("shovel");
            case WOOD -> n.contains("axe") && !n.contains("pickaxe");
            case FARM -> n.contains("hoe");
            case FISH -> n.contains("fishing rod");
            case GUARD, HUNT -> n.contains("sword");
            case RANCH -> n.contains("shears");
            default -> false;
        };
    }

    static int count(VillageFolkEntity f, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    /** One of these out of its pack, or EMPTY. */
    static ItemStack takeOne(VillageFolkEntity f, Predicate<ItemStack> what) {
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !what.test(s)) continue;
            return s.split(1);
        }
        return ItemStack.EMPTY;
    }

    static boolean food(ItemStack s) {
        return s.get(DataComponents.FOOD) != null || s.is(Items.SUGAR);
    }

    /** The plainest bite a folk can spare: sugar if it has any, else the cheapest food in its pack. */
    @Nullable
    static Item plainest(VillageFolkEntity f) {
        if (count(f, s -> s.is(Items.SUGAR)) > 0) return Items.SUGAR;
        Item best = null;
        int bestNut = Integer.MAX_VALUE;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || s.get(DataComponents.FOOD) == null) continue;
            int nut = s.get(DataComponents.FOOD).nutrition();
            if (nut < bestNut) { bestNut = nut; best = s.getItem(); }
        }
        return best;
    }

    /** The neighbours of a folk: grown, awake, about, living a street or two away, and not on bad terms. */
    static List<VillageFolkEntity> neighbours(VillageFolkEntity f) {
        List<VillageFolkEntity> out = new ArrayList<>();
        UUID id = f.ownerId();
        BlockPos home = Civics.home(f);
        if (id == null) return out;
        for (VillageFolkEntity o : Civics.grown(id)) {
            if (o == f || o.isSleeping() || f.life().affinity(o.getUUID()) <= Social.RIVAL || o.life().affinity(f.getUUID()) <= Social.RIVAL) continue;
            BlockPos theirs = Civics.home(o);
            boolean near = home != null && theirs != null && home.distSqr(theirs) <= NEIGHBOURS * NEIGHBOURS || o.distanceToSqr(f) <= 16 * 16;
            if (near) out.add(o);
        }
        out.sort((a, b) -> Double.compare(a.distanceToSqr(f), b.distanceToSqr(f)));
        return out;
    }

    // ------------------------------------------------------------------ the round of the town

    /**
     * Every five seconds, of an evening (or on the day of rest): a debt paid back, or one good turn asked for, by
     * somebody free to (one a look). Neighbours call round when the day's work is done.
     */
    static void tick(ServerLevel level, Villages.Village v) {
        long day = Civics.day(level), t = level.getDayTime() % 24000L;
        boolean evening = t >= 11000 && t < 13000 || RestDay.today(v.id(), day) && t >= 3600 && t < 12000;
        for (VillageFolkEntity a : Civics.grown(v.id())) {
            // A debt nobody has come to settle in ten days (the lender gone, or never about) is let drop.
            ListTag owes = Civics.list(Civics.folk(a.getUUID()), "owes");
            for (int i = owes.size() - 1; i >= 0; i--) if (day - owes.getCompound(i).getLong("day") > 10) owes.remove(i);
            if (!evening || ERRANDS.containsKey(a.getUUID()) || !Civics.free(a)) continue;
            if (ASKED.getOrDefault(a.getUUID(), -1L) == day) continue;           // one good turn asked or paid back a day
            CompoundTag d = repayable(level, a, day);
            if (d != null) {
                ASKED.put(a.getUUID(), day);
                ERRANDS.put(a.getUUID(), new Errand(kind(d), Post.uuid(d, "to"), true, d.getString("item"), a.tickCount));
                return;
            }
            Errand e = need(level, a);
            if (e != null) {
                ASKED.put(a.getUUID(), day);
                ERRANDS.put(e.kind == Kind.CARRY ? e.other : a.getUUID(), e.kind == Kind.CARRY
                    ? new Errand(Kind.CARRY, a.getUUID(), false, "", a.tickCount) : e);
                return;
            }
        }
    }

    /** What this folk might ask a neighbour for just now, and of whom; or, for a load, which neighbour helps. */
    @Nullable
    static Errand need(ServerLevel level, VillageFolkEntity a) {
        for (String need : a.missingEssentials()) {
            Predicate<ItemStack> t = tool(need);
            if (t == null) continue;
            for (VillageFolkEntity b : neighbours(a)) {
                int has = count(b, t);
                if (has > 1 || has == 1 && !usesIt(b, need)) return new Errand(Kind.TOOL, b.getUUID(), false, need, a.tickCount);
            }
        }
        if (a.countFood() == 0 && a.meals().missedInRow() > 0) {            // nothing in its pack, and a meal gone without
            for (VillageFolkEntity b : neighbours(a)) {
                if (count(b, Favours::food) >= 3) return new Errand(Kind.FOOD, b.getUUID(), false, "food", a.tickCount);
            }
        }
        int goods = count(a, s -> Market.goodFor(s) != null);
        if (goods >= 48) {
            for (VillageFolkEntity b : neighbours(a)) {
                if (Civics.free(b) && !ERRANDS.containsKey(b.getUUID()) && b.distanceToSqr(a) < 12 * 12) {
                    return new Errand(Kind.CARRY, b.getUUID(), false, "", a.tickCount);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the folk's part

    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return null;
        VillageFolkEntity other = Civics.find(level, e.other);
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (other == null || v == null || f.tickCount - e.since > 1600 || f.tickCount < e.since || Raids.underAlarm(id)) {
            drop(level, f, e, v);
            return null;
        }
        if (e.kind == Kind.CARRY) return carry(f, level, e, other, v);
        if (f.distanceToSqr(other) > 3.0 * 3.0) {
            Civics.goTo(f, other.blockPosition(), 2.5, 0.85);
            return (e.repay ? "on its way to pay " : "on its way to ask ") + other.displayNameCap() + (e.repay ? " back" : " a favour");
        }
        ERRANDS.remove(f.getUUID());
        f.getNavigation().stop();
        f.getLookControl().setLookAt(other, 30.0F, 30.0F);
        other.getLookControl().setLookAt(f, 30.0F, 30.0F);
        if (e.repay) repay(level, f, other, Civics.day(level));
        else ask(level, f, other, e.kind, e.need);
        return "with its neighbour " + other.displayNameCap();
    }

    /** A hand with a load: half the other's goods onto its own back, then to the stores with them. */
    private static String carry(VillageFolkEntity f, ServerLevel level, Errand e, VillageFolkEntity other, Villages.Village v) {
        if (e.load.isEmpty()) {
            if (f.distanceToSqr(other) > 3.0 * 3.0) {
                Civics.goTo(f, other.blockPosition(), 2.5, 0.85);
                return "going to give " + other.displayNameCap() + " a hand";
            }
            if (!shoulder(level, f, other, e)) {
                ERRANDS.remove(f.getUUID());
                return null;
            }
            return "carrying " + other.displayNameCap() + "'s load to the stores";
        }
        // The stores are at the heart (the storehouse and its chests round the square): there, it is put in.
        if (!Civics.goTo(f, v.centre(), 5.0, 0.8)) return "carrying " + other.displayNameCap() + "'s load to the stores";
        ERRANDS.remove(f.getUUID());
        delivered(level, f, other, e, v);
        return "at the stores";
    }

    /** Half of the other's goods taken onto its own back (as much as it has room for). */
    static boolean shoulder(ServerLevel level, VillageFolkEntity helper, VillageFolkEntity other, Errand e) {
        int goods = count(other, s -> Market.goodFor(s) != null);
        int half = goods / 2;
        for (ItemStack s : other.getInventoryItems()) {
            if (half <= 0) break;
            if (s.isEmpty() || Market.goodFor(s) == null) continue;
            int n = Math.min(half, s.getCount());
            ItemStack lot = s.copyWithCount(n);
            ItemStack left = helper.insertItem(lot);
            int moved = n - left.getCount();
            if (moved <= 0) break;
            s.shrink(moved);
            e.load.add(lot.copyWithCount(moved));
            half -= moved;
        }
        if (e.load.isEmpty()) return false;
        FolkTalk.speak(helper, FolkTalk.pick(helper.getRandom(), "Here, give me some of that.", "That's too much for one. I'll take half."));
        other.sayLater("Oh, thank you! That's kind.", 40);
        return true;
    }

    /** The load into the stores (out of its own pack), and the good turn owed. */
    static void delivered(ServerLevel level, VillageFolkEntity helper, VillageFolkEntity other, Errand e, Villages.Village v) {
        int n = 0;
        for (ItemStack lot : e.load) {
            int want = lot.getCount();
            for (ItemStack s : helper.getInventoryItems()) {
                if (want <= 0) break;
                if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, lot)) continue;
                int k = Math.min(want, s.getCount());
                ItemStack out = s.split(k);
                TownWork.give(level, v, out);
                if (!out.isEmpty()) helper.insertItem(out);              // the stores full: kept, and put away with its own
                n += k - out.getCount();
                want -= k;
            }
        }
        e.load.clear();
        long day = Civics.day(level);
        owe(other, helper, Kind.CARRY, "", day);
        favour(level, helper, other, day);
        FolkTalk.speak(helper, "There — " + other.displayNameCap() + "'s load is in the stores.");
        other.persona().remember(day, helper.displayNameCap() + " carried my load to the stores for me", 3);
    }

    /**
     * The errand let go (too long about it, or the alarm): a load it had taken on stays on its back, and goes
     * into the stores with its own things when next it puts them away.
     */
    private static void drop(ServerLevel level, VillageFolkEntity f, Errand e, @Nullable Villages.Village v) {
        ERRANDS.remove(f.getUUID());
        e.load.clear();
    }

    /** A neighbour asked, and the thing handed over: the loan of a tool, or a bite to eat. */
    static boolean ask(ServerLevel level, VillageFolkEntity a, VillageFolkEntity b, Kind kind, String need) {
        long day = Civics.day(level);
        if (kind == Kind.TOOL) {
            Predicate<ItemStack> t = tool(need);
            if (t == null) return false;
            int has = count(b, t);
            if (has == 0 || has == 1 && usesIt(b, need)) {
                FolkTalk.speak(a, "Could I borrow " + need + "?");
                b.sayLater("Sorry — I need mine.", 40);
                return false;
            }
            ItemStack lent = takeOne(b, t);
            if (lent.isEmpty()) return false;
            ItemStack left = a.insertGiven(lent);
            if (!left.isEmpty()) {
                b.insertItem(left);                                   // no room in its pack: the loan is off
                return false;
            }
            FolkTalk.speak(a, FolkTalk.pick(a.getRandom(), "Could I borrow " + need + "? Mine's gone.", "You wouldn't have " + need + " to lend?"));
            b.sayLater(FolkTalk.pick(b.getRandom(), "Here — bring it back when you've your own.", "Of course. Take it."), 40);
            owe(a, b, Kind.TOOL, key(lent.getItem()), day);
        } else if (kind == Kind.FOOD) {
            Item bite = plainest(b);
            if (bite == null || count(b, Favours::food) < 2) return false;
            ItemStack given = takeOne(b, s -> s.is(bite));
            if (given.isEmpty()) return false;
            ItemStack left = a.insertGiven(given);
            if (!left.isEmpty()) {
                b.insertItem(left);
                return false;
            }
            FolkTalk.speak(a, bite == Items.SUGAR ? "Could you spare a cup of sugar?" : "Could you spare a bite? There's nothing in my pack.");
            b.sayLater(FolkTalk.pick(b.getRandom(), "Here you are.", "Take this. Pay me back when you can."), 40);
            owe(a, b, Kind.FOOD, key(bite), day);
        } else {
            return false;
        }
        favour(level, b, a, day);
        a.persona().remember(day, b.displayNameCap() + " lent me " + (kind == Kind.TOOL ? need : "a bite to eat"), 2);
        return true;
    }

    /** Owed: written down against the borrower. */
    static void owe(VillageFolkEntity borrower, VillageFolkEntity lender, Kind kind, String item, long day) {
        ListTag owes = Civics.list(Civics.folk(borrower.getUUID()), "owes");
        CompoundTag d = new CompoundTag();
        d.putString("to", lender.getUUID().toString());
        d.putString("toName", lender.displayNameCap());
        d.putString("kind", kind.name());
        d.putString("item", item);
        d.putLong("day", day);
        owes.add(d);
        Civics.changed();
    }

    /** A good turn done: the two warmer to each other, the day the brighter, and the helper's tally (a good neighbour at five). */
    static void favour(ServerLevel level, VillageFolkEntity helper, VillageFolkEntity helped, long day) {
        helper.life().feel(helped.getUUID(), helped.displayNameCap(), 6);
        helped.life().feel(helper.getUUID(), helper.displayNameCap(), 10);
        Civics.glad(helper, Civics.FAVOUR, day);
        Civics.glad(helped, Civics.FAVOUR, day);
        CompoundTag me = Civics.folk(helper.getUUID());
        me.putInt("favours", me.getInt("favours") + 1);
        if (me.getInt("favours") >= GOOD && !me.getBoolean("good")) {
            me.putBoolean("good", true);
            me.putLong("goodSince", day);
            UUID id = helper.ownerId();
            if (id != null) Villages.tell(id, day, helper.displayNameCap() + " has done so many good turns about the street that the town calls it a good neighbour");
            helper.persona().remember(day, "the town calls me a good neighbour", 6);
        }
        Civics.changed();
    }

    // ------------------------------------------------------------------ paying back

    static Kind kind(CompoundTag d) {
        try {
            return Kind.valueOf(d.getString("kind"));
        } catch (IllegalArgumentException e) {
            return Kind.FOOD;
        }
    }

    static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    @Nullable
    static Item item(String key) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** A debt this folk could settle now, with its lender about: or null. */
    @Nullable
    static CompoundTag repayable(ServerLevel level, VillageFolkEntity a, long day) {
        for (Tag t : Civics.list(Civics.folk(a.getUUID()), "owes")) {
            if (!(t instanceof CompoundTag d)) continue;
            VillageFolkEntity lender = Civics.find(level, Post.uuid(d, "to"));
            if (lender == null || lender.isSleeping()) continue;
            if (canRepay(a, d, day)) return d;
        }
        return null;
    }

    /** Can it pay this back: the tool, now it has its own; a bite, now it has food to spare; else, after a while, coin. */
    static boolean canRepay(VillageFolkEntity a, CompoundTag d, long day) {
        boolean late = day - d.getLong("day") >= GRACE;
        Item it = item(d.getString("item"));
        return switch (kind(d)) {
            case TOOL -> it != null && count(a, s -> s.is(it)) >= 1 && (count(a, sortOf(it)) >= 2 || !needsStill(a, it))
                || late && a.purse() >= 2;
            case FOOD -> count(a, Favours::food) >= 3 || late && a.purse() >= 1;
            case CARRY -> day > d.getLong("day") && a.purse() >= 1;
        };
    }

    /** Any tool of the same sort (an axe of any make, for an axe): one of its own besides the lent one will do. */
    static Predicate<ItemStack> sortOf(Item it) {
        Predicate<ItemStack> sort = tool(word(it));
        return s -> s.is(it) || sort != null && !s.isEmpty() && sort.test(s);
    }

    /** "an axe", "a pickaxe"...: the checklist's word for this tool. */
    static String word(Item it) {
        return it instanceof PickaxeItem ? "a pickaxe" : it instanceof AxeItem ? "an axe" : it instanceof HoeItem ? "a hoe"
            : it instanceof ShovelItem ? "a shovel" : it instanceof FishingRodItem ? "a fishing rod" : it instanceof SwordItem ? "a sword" : "shears";
    }

    /** Does its trade still want this tool (it has no other of the sort)? */
    private static boolean needsStill(VillageFolkEntity a, Item it) {
        return usesIt(a, word(it));
    }

    /** Paid back: the thing itself where it can, else coin out of its purse into the lender's. */
    static boolean repay(ServerLevel level, VillageFolkEntity a, VillageFolkEntity lender, long day) {
        ListTag owes = Civics.list(Civics.folk(a.getUUID()), "owes");
        for (int i = 0; i < owes.size(); i++) {
            CompoundTag d = owes.getCompound(i);
            if (!d.getString("to").equals(lender.getUUID().toString()) || !canRepay(a, d, day)) continue;
            Item it = item(d.getString("item"));
            String said;
            boolean ok = false;
            if (kind(d) == Kind.TOOL && it != null && count(a, s -> s.is(it)) >= 1 && (count(a, sortOf(it)) >= 2 || !needsStill(a, it))) {
                ItemStack back = takeOne(a, s -> s.is(it));
                ItemStack left = lender.insertGiven(back);
                if (left.isEmpty()) { ok = true; said = "Here's your " + it.getDescription().getString().toLowerCase(Locale.ROOT) + " back — thank you."; }
                else { a.insertItem(left); said = ""; }
            } else if (kind(d) == Kind.FOOD && count(a, Favours::food) >= 3) {
                Item bite = plainest(a);
                ItemStack back = bite == null ? ItemStack.EMPTY : takeOne(a, s -> s.is(bite));
                ItemStack left = back.isEmpty() ? ItemStack.EMPTY : lender.insertGiven(back);
                if (!back.isEmpty() && left.isEmpty()) { ok = true; said = "I owe you a bite — here."; }
                else { if (!left.isEmpty()) a.insertItem(left); said = ""; }
            } else {
                int coins = kind(d) == Kind.TOOL ? 2 : 1;
                if (a.spend(coins)) {
                    lender.earn(coins);
                    ok = true;
                    said = kind(d) == Kind.TOOL ? "I'll keep the tool, if you'll let me — here's two coins for it."
                        : kind(d) == Kind.CARRY ? "For carrying my load the other day. Go on, take it." : "A coin for the bite you lent me.";
                } else {
                    said = "";
                }
            }
            if (!ok) return false;
            owes.remove(i);
            FolkTalk.speak(a, said);
            lender.sayLater(FolkTalk.pick(lender.getRandom(), "Any time.", "That's what neighbours are for."), 40);
            a.life().feel(lender.getUUID(), lender.displayNameCap(), 4);
            lender.life().feel(a.getUUID(), a.displayNameCap(), 4);
            CompoundTag me = Civics.folk(a.getUUID());
            me.putInt("repaid", me.getInt("repaid") + 1);
            Civics.changed();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ where the player sees it

    static String cardLine(VillageFolkEntity f) {
        CompoundTag t = Civics.folk(f.getUUID());
        List<String> parts = new ArrayList<>();
        int done = t.getInt("favours");
        if (t.getBoolean("good")) parts.add("a good neighbour (" + done + " good turns)");
        else if (done > 0) parts.add(done + (done == 1 ? " good turn" : " good turns") + " done its neighbours");
        ListTag owes = Civics.list(t, "owes");
        if (!owes.isEmpty()) {
            CompoundTag d = owes.getCompound(0);
            parts.add("owes " + d.getString("toName") + " " + (kind(d) == Kind.TOOL ? "a tool" : kind(d) == Kind.FOOD ? "a bite" : "a good turn"));
        }
        return String.join("; ", parts);
    }

    static List<String> book(ServerLevel level, UUID village) {
        List<String> good = new ArrayList<>();
        int owed = 0;
        for (VillageFolkEntity f : Civics.grown(village)) {
            CompoundTag t = Civics.folk(f.getUUID());
            if (t.getBoolean("good")) good.add(f.displayNameCap() + " (" + t.getInt("favours") + ")");
            owed += Civics.list(t, "owes").size();
        }
        List<String> out = new ArrayList<>();
        if (!good.isEmpty()) out.add("Good neighbours: " + String.join(", ", good) + ".");
        if (owed > 0) out.add(owed + (owed == 1 ? " good turn" : " good turns") + " still to be paid back about the town.");
        return out;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: the asker asks this neighbour now ("an axe", "food"): true if it was given. */
    public static boolean askForTests(ServerLevel level, VillageFolkEntity a, VillageFolkEntity b, String need) {
        return ask(level, a, b, need.equals("food") ? Kind.FOOD : Kind.TOOL, need);
    }

    /** Tests: the helper takes half the other's load and carries it into the stores now. */
    public static int carryForTests(ServerLevel level, VillageFolkEntity helper, VillageFolkEntity other) {
        Villages.Village v = helper.ownerId() == null ? null : Villages.get(helper.ownerId());
        Errand e = new Errand(Kind.CARRY, other.getUUID(), false, "", helper.tickCount);
        if (v == null || !shoulder(level, helper, other, e)) return 0;
        int n = 0;
        for (ItemStack s : e.load) n += s.getCount();
        delivered(level, helper, other, e, v);
        return n;
    }

    /** Tests: whatever this folk can pay back now to this lender, paid (true if something was). */
    public static boolean repayForTests(ServerLevel level, VillageFolkEntity a, VillageFolkEntity lender, long day) {
        return repay(level, a, lender, day);
    }

    /** Tests: what it owes, in words ("TOOL minecraft:iron_axe to Ash"). */
    public static List<String> owesForTests(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        for (Tag t : Civics.list(Civics.folk(f.getUUID()), "owes")) {
            if (t instanceof CompoundTag d) out.add(d.getString("kind") + " " + d.getString("item") + " to " + d.getString("toName"));
        }
        return out;
    }

    /** Tests: this folk has gone without a meal (so a bite is worth asking a neighbour for). */
    public static void hungryForTests(VillageFolkEntity f) {
        f.meals().missedInRow = Math.max(1, f.meals().missedInRow);
    }

    /** Tests: {good turns done, good neighbour 0/1}. */
    public static int[] standingForTests(VillageFolkEntity f) {
        CompoundTag t = Civics.folk(f.getUUID());
        return new int[]{ t.getInt("favours"), t.getBoolean("good") ? 1 : 0 };
    }

    /** Tests: what this folk would ask a neighbour for now, and of whom ("TOOL an axe of Bree"), or null. */
    @Nullable
    public static String needForTests(ServerLevel level, VillageFolkEntity a) {
        Errand e = need(level, a);
        if (e == null) return null;
        VillageFolkEntity o = Civics.find(level, e.other);
        return e.kind + " " + e.need + " of " + (o == null ? "?" : o.displayNameCap());
    }
}
