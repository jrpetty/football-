package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.RecipeBook.Fire;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The maker's bench: how anybody who sells things works out, from the game's own recipes, the whole
 * way from what the village's stores hold to the thing it means to sell — logs to planks to sticks to
 * a pick, wheat to bread, sand to glass to a bottle, cane to sugar to a pie — and then makes it, every
 * step of it, out of what the stores can spare.
 * <ul>
 * <li><b>Real recipes.</b> Every way is the game's own (RecipeBook.waysFor): crafting at the bench (or
 *     in the hand, for what fits two by two), and firing in a furnace, a smoker or over a campfire. A
 *     modpack's things are made the same way, from their own recipes.</li>
 * <li><b>What the stores hold.</b> A part comes out of the stores if they hold it; else it is made,
 *     the same way, from what they do, six makings deep at most. Whatever a step makes and the next
 *     does not use (the rest of a log's four planks, a slab's other five) goes back into the stores,
 *     and so does what a recipe leaves behind (a cake's three buckets).</li>
 * <li><b>What the village keeps back</b> (keeps): never what it is short of for its age, nor the
 *     builders' working timber and stone, the smith's iron, the coal, the beds' wool or the seed.</li>
 * <li><b>The hand.</b> Nothing above the maker's hand (Craftsmanship: a beginner makes no armour), and
 *     what is one to a stack comes out as good as that hand, with its mark on it.</li>
 * <li><b>The bench and the fire.</b> A three-by-three recipe wants a crafting table (its own, the
 *     stores', its building's, or one borrowed off a neighbour); a firing wants a
 *     furnace (the smeltery's will do), the café's smoker, or for food the tavern's hearth, and its
 *     fuel (a coal fires eight, a plank one and a half: the game's burn times), the hearth excepted.
 *     With no table or furnace to hand the maker makes one first, of the stores' planks or
 *     cobblestone, and keeps it.</li>
 * </ul>
 * If the stores cannot run to it, the plan says what is missing ("2 string", and why: "put by for the
 * age"), and nothing is taken.
 */
public final class Bench {

    private Bench() {}

    /** How deep a chain of makings goes (a pick is logs, planks, sticks and the pick: three). */
    private static final int DEEPEST = 6;
    /** How many makings the planner looks at before it gives a thing up: a guard on its time. */
    private static final int MOST_LOOKS = 1500;
    /** How many of the things an ingredient accepts it tries to make, when the stores hold none. */
    private static final int MOST_CHOICES = 8;
    /** A furnace's firing: what the burn times are counted against. */
    private static final int FIRING = 200;

    // ------------------------------------------------------------------ the hand

    /** Who is at the bench and what it has to work with: its level at the trade (Craftsmanship), its
     *  name (for its mark; empty with nobody at the bench, and then no mark), and whether a crafting
     *  table, a furnace, a smoker and a hearth are to hand. */
    public record Hand(int skill, String maker, boolean table, boolean furnace, boolean smoker, boolean campfire) {}

    private static final Map<String, long[]> STANDS = new ConcurrentHashMap<>();

    public static void resetForTests() { STANDS.clear(); }

    /**
     * The hand at a seller's bench: the maker's own level and name (a stand-in's, nought, with no
     * maker), and its tools: a crafting table carried, in the stores or standing in its building; a
     * furnace the same, or the smeltery's; the smoker in its building; and the tavern's hearth.
     */
    public static Hand handOf(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, @Nullable String building) {
        int skill = f == null ? 0 : f.veteranLevel();
        String maker = f == null ? "" : f.displayNameCap();
        // A crafting table is the commonest thing in a village (every folk carries one from its first
        // day): its own, the stores', its building's, or one borrowed off whoever has one.
        boolean table = toHand(level, v, f, Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE, building);
        if (!table) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a.countCarried(s -> s.is(Items.CRAFTING_TABLE)) > 0) { table = true; break; }
            }
        }
        boolean furnace = toHand(level, v, f, Items.FURNACE, Blocks.FURNACE, building)
            || Villages.hasBuilt(v.id(), "smeltery");
        boolean smoker = toHand(level, v, f, Items.SMOKER, Blocks.SMOKER, building);
        boolean campfire = Tavern.of(v.id()) != null || standsIn(level, v, building, Blocks.CAMPFIRE);
        return new Hand(skill, maker, table, furnace, smoker, campfire);
    }

    private static boolean toHand(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, Item item, Block block,
                                  @Nullable String building) {
        if (f != null && f.countCarried(s -> s.is(item)) > 0) return true;
        if (Market.stock(level, v.id(), s -> s.is(item)) > 0) return true;
        return standsIn(level, v, building, block);
    }

    /** Does one of these stand in the village's building of this kind? Looked at once a minute. */
    static boolean standsIn(ServerLevel level, Villages.Village v, @Nullable String building, Block block) {
        if (building == null) return false;
        String key = v.id() + "/" + building + "/" + BuiltInRegistries.BLOCK.getKey(block);
        long now = level.getGameTime();
        long[] seen = STANDS.get(key);
        if (seen != null && now - seen[0] < 1200L && now >= seen[0]) return seen[1] != 0L;
        boolean found = false;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!b.structure().equals(building) || !level.isLoaded(b.anchor())) continue;
            BlockPos a = b.anchor();
            for (BlockPos p : BlockPos.betweenClosed(a.offset(-8, -2, -8), a.offset(8, 8, 8))) {
                if (level.getBlockState(p).is(block)) { found = true; break; }
            }
            if (found) break;
        }
        STANDS.put(key, new long[]{ now, found ? 1L : 0L });
        return found;
    }

    // ------------------------------------------------------------------ what the village keeps back

    /** A kind of thing the village keeps some of back from any maker, and why. */
    private record Kept(Predicate<ItemStack> what, int keep, String why) {}

    /**
     * What the village keeps back from anybody making things to sell (the café's cook, the shop's bench,
     * the storekeeper making to order):
     * <ul>
     * <li>whatever it is short of for its age, all of it (timber in the Wood Age, stone and coal in the
     *     Stone Age, iron, diamonds, obsidian);</li>
     * <li>the builders' working timber (sixteen logs, forty-eight planks) and stone (sixty-four, or what
     *     the age holds back);</li>
     * <li>the smith's last sixteen bars (twenty-four while there is a smith), the coal's last eight, the
     *     gold for the mint, the beds' wool while beds wait, and the last twelve of every seed crop;</li>
     * <li>a little string, leather, feathers and torches for the trades that live on them, and the stone
     *     bricks the masons keep put by.</li>
     * </ul>
     */
    private static List<Kept> keeps(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Set<Villages.Task> short_ = EnumSet.noneOf(Villages.Task.class);
        for (Villages.Need n : Villages.needs(level, id)) short_.add(n.task());
        boolean smith = false;
        for (AssistantEntity a : Villages.folkOf(id)) if (a.stationTask() == AssistantEntity.StationTask.SMITH) { smith = true; break; }
        int all = Integer.MAX_VALUE;
        List<Kept> k = new ArrayList<>();
        boolean timber = short_.contains(Villages.Task.LOGS);
        k.add(new Kept(s -> s.is(ItemTags.LOGS), timber ? all : 16, timber ? "put by for the age" : "the builders' timber"));
        k.add(new Kept(s -> s.is(ItemTags.PLANKS), timber ? all : 48, timber ? "put by for the age" : "the builders' timber"));
        boolean stone = short_.contains(Villages.Task.STONE);
        k.add(new Kept(s -> Masonry.plain(s.getItem()), stone ? all : Math.max(Masonry.BUILDERS_STONE, Villages.stoneHeldBack(id)),
            stone ? "put by for the age" : "the builders' stone"));
        boolean coal = short_.contains(Villages.Task.COAL);
        k.add(new Kept(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), coal ? all : 8, coal ? "put by for the age" : "the furnaces' coal"));
        boolean iron = short_.contains(Villages.Task.IRON);
        k.add(new Kept(s -> s.is(Items.IRON_INGOT), iron ? all : Crafts.IRON_KEPT + (smith ? 8 : 0),
            iron ? "put by for the age" : "the smith's iron"));
        k.add(new Kept(s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE), iron ? all : 16,
            iron ? "put by for the age" : "the smelter's ore"));
        boolean diamonds = short_.contains(Villages.Task.DIAMOND);
        k.add(new Kept(s -> s.is(Items.DIAMOND), diamonds ? all : 5, diamonds ? "put by for the age" : "the smith's diamonds"));
        boolean obsidian = short_.contains(Villages.Task.OBSIDIAN);
        k.add(new Kept(s -> s.is(Items.OBSIDIAN), obsidian ? all : 14, "put by for the gateway"));
        k.add(new Kept(s -> s.is(Items.GOLD_INGOT), 27, "the mint's gold"));
        boolean beds = Market.bedsShort(id) > 0;
        k.add(new Kept(s -> s.is(ItemTags.WOOL), beds ? all : 3, beds ? "for the beds the houses wait on" : "the tailor's wool"));
        for (Item seed : new Item[]{ Items.WHEAT, Items.POTATO, Items.CARROT, Items.BEETROOT }) {
            k.add(new Kept(s -> s.is(seed), 12, "seed for the fields"));
        }
        k.add(new Kept(s -> s.is(Items.STRING), 2, "the fishers' and the smith's string"));
        k.add(new Kept(s -> s.is(Items.LEATHER), 2, "the tailor's leather"));
        k.add(new Kept(s -> s.is(Items.FEATHER), 4, "the watch's arrows"));
        k.add(new Kept(s -> s.is(Items.TORCH), 8, "the village's lights"));
        k.add(new Kept(s -> s.is(Items.STONE_BRICKS), Masonry.keep(id, Items.STONE_BRICKS), "the masons' stone"));
        return k;
    }

    /** What the stores hold that a maker may use: the plain stacks (nobody's marked work, nothing
     *  enchanted or named), less what the village keeps back, the keep off the biggest piles first. */
    private static Map<Item, Integer> free(ServerLevel level, Villages.Village v, Map<Item, Integer> held, Map<Item, String> why) {
        Map<Item, Integer> free = new HashMap<>(held);
        for (Kept k : keeps(level, v)) {
            if (k.keep() <= 0) continue;
            List<Item> in = new ArrayList<>();
            for (Item it : held.keySet()) if (k.what().test(new ItemStack(it))) in.add(it);
            if (in.isEmpty()) continue;
            in.sort((a, b) -> Integer.compare(held.get(b), held.get(a)));
            int left = k.keep();
            for (Item it : in) {
                why.put(it, k.why());
                if (left <= 0) continue;
                int n = free.getOrDefault(it, 0);
                int off = Math.min(n, left);
                free.put(it, n - off);
                left -= off;
            }
        }
        free.entrySet().removeIf(e -> e.getValue() <= 0);
        return free;
    }

    /**
     * The stores' plain stacks by item, each with what a maker may use of it (the rest is kept back),
     * and why the village keeps it ({@code why}, filled in): the town's books' Stock page.
     */
    public static Map<Item, int[]> keepBook(ServerLevel level, Villages.Village v, Map<Item, String> why) {
        Map<Item, Integer> held = held(level, v.id());
        Map<Item, Integer> free = free(level, v, held, why);
        Map<Item, int[]> out = new HashMap<>();
        for (Map.Entry<Item, Integer> e : held.entrySet()) out.put(e.getKey(), new int[]{ e.getValue(), free.getOrDefault(e.getKey(), 0) });
        return out;
    }

    /** What the stores hold, plain stacks only, by item. */
    static Map<Item, Integer> held(ServerLevel level, UUID village) {
        Map<Item, Integer> held = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                // Plain stacks only, and never anybody's tools, arms or armour: nothing is melted
                // down for its nuggets, nor anybody's marked work used up.
                if (s.isEmpty() || !s.getComponentsPatch().isEmpty() || s.isDamageableItem()) continue;
                held.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return held;
    }

    // ------------------------------------------------------------------ the plan

    /** One step of the work: so many of a thing made, so many times over, where. */
    public record Step(Item made, int times, int each, Fire fire, boolean bench) {
        public int count() { return times * each; }

        /** "8 oak planks", "a crafting table". */
        public String words() {
            return Bench.words(made, count());
        }

        /** Where: "at the bench", "by hand", "in the furnace", "in the smoker", "over the hearth". */
        public String where() {
            return switch (fire) {
                case NONE -> bench ? "at the bench" : "by hand";
                case FURNACE -> "in the furnace";
                case SMOKER -> "in the smoker";
                case CAMPFIRE -> "over the hearth";
            };
        }
    }

    /** Something wanted for a piece of work: what will do, how many, and in a word. */
    public record Want(Predicate<ItemStack> what, List<Item> kinds, int count, String words) {
        public static Want of(Item it, int n) {
            return new Want(s -> s.is(it), List.of(it), n, Bench.words(it, n));
        }
    }

    /** A worked-out piece of work: the steps in order, what comes out of the stores, what goes back,
     *  what the maker keeps (a table, a furnace), and what it makes — or what it is short of. */
    public static final class Plan {
        @Nullable public final Item target;
        public final int made;
        public final List<Step> steps;
        public final Map<Item, Integer> takes;
        public final Map<Item, Integer> back;
        public final List<Item> tools;
        /** What it is short of, in words ("2 string"), or null if it can be made. */
        @Nullable public final String shortOf;
        /** The thing it is short of, and how many: for the quest board. */
        @Nullable public final Item missing;
        public final int missingCount;
        /** Why, when the stores have it but the village keeps it back ("put by for the age"); else empty. */
        public final String why;

        Plan(@Nullable Item target, int made, List<Step> steps, Map<Item, Integer> takes, Map<Item, Integer> back, List<Item> tools,
             @Nullable String shortOf, @Nullable Item missing, int missingCount, String why) {
            this.target = target;
            this.made = made;
            this.steps = steps;
            this.takes = takes;
            this.back = back;
            this.tools = tools;
            this.shortOf = shortOf;
            this.missing = missing;
            this.missingCount = missingCount;
            this.why = why;
        }

        public boolean ok() { return shortOf == null; }

        /** What went into it at the price list's worth: what comes out of the stores, less what goes back
         *  and what the maker keeps to use again. */
        public double cost() {
            double c = 0;
            for (Map.Entry<Item, Integer> e : takes.entrySet()) c += Prices.each(e.getKey()) * e.getValue();
            for (Map.Entry<Item, Integer> e : back.entrySet()) c -= Prices.each(e.getKey()) * e.getValue();
            for (Item t : tools) c -= Prices.each(t);
            return Math.max(0, c);
        }

        /** The way it went, in words: "2 oak logs, into 8 oak planks, 4 sticks and an iron pickaxe". */
        public String chain() {
            if (!ok()) return "short of " + shortOf + (why.isEmpty() ? "" : " (" + why + ")");
            List<String> in = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : takes.entrySet()) {
                if (in.size() >= 3) { in.add("more"); break; }
                in.add(Bench.words(e.getKey(), e.getValue()));
            }
            List<String> out = new ArrayList<>();
            for (Step s : steps) out.add(s.words() + (s.fire() == Fire.NONE ? "" : " " + s.where()));
            if (out.isEmpty()) return "out of " + String.join(", ", in);                 // a recipe of its own: nothing to make first
            return (in.isEmpty() ? "" : String.join(", ", in) + ", into ") + String.join(", ", out);
        }
    }

    /** What a plan is worked out on: what is free (the stores' spare, and what the plan has made and
     *  not used), of that what the plan made itself, what it takes, its steps, and its tools. */
    private static final class State {
        final Map<Item, Integer> free;
        final Map<Item, Integer> spare;
        final Map<Item, Integer> takes;
        final List<Step> steps;
        final List<Item> tools;
        boolean table, furnace;

        State(Map<Item, Integer> free, boolean table, boolean furnace) {
            this(new HashMap<>(free), new HashMap<>(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(), table, furnace);
        }

        private State(Map<Item, Integer> free, Map<Item, Integer> spare, Map<Item, Integer> takes, List<Step> steps, List<Item> tools,
                      boolean table, boolean furnace) {
            this.free = free;
            this.spare = spare;
            this.takes = takes;
            this.steps = steps;
            this.tools = tools;
            this.table = table;
            this.furnace = furnace;
        }

        State copy() {
            return new State(new HashMap<>(free), new HashMap<>(spare), new LinkedHashMap<>(takes), new ArrayList<>(steps),
                new ArrayList<>(tools), table, furnace);
        }

        void set(State o) {
            free.clear(); free.putAll(o.free);
            spare.clear(); spare.putAll(o.spare);
            takes.clear(); takes.putAll(o.takes);
            steps.clear(); steps.addAll(o.steps);
            tools.clear(); tools.addAll(o.tools);
            table = o.table;
            furnace = o.furnace;
        }
    }

    /** The planner's working: the level, the hand, what the stores hold and why the village keeps
     *  what it keeps, a count of what it has looked at, and the first thing it found missing. */
    private static final class Ctx {
        final ServerLevel level;
        final Hand hand;
        final Map<Item, Integer> held;
        final Map<Item, String> why;
        final Map<Item, ItemStack> samples = new HashMap<>();
        int looks;
        @Nullable Item missing;
        int missingCount;
        int missingDepth = Integer.MAX_VALUE;
        String missingWords = "";
        String missingWhy = "";

        Ctx(ServerLevel level, Hand hand, Map<Item, Integer> held, Map<Item, String> why) {
            this.level = level;
            this.hand = hand;
            this.held = held;
            this.why = why;
        }

        ItemStack sample(Item it) {
            return samples.computeIfAbsent(it, ItemStack::new);
        }

        void lacking(int depth, List<Item> kinds, Predicate<ItemStack> what, int n, String words) {
            if (depth >= missingDepth || kinds.isEmpty()) return;
            missingDepth = depth;
            missing = kinds.get(0);
            missingCount = n;
            missingWords = words;
            // The stores have it, and the village keeps it back: say so.
            int there = 0;
            String reason = "";
            for (Map.Entry<Item, Integer> e : held.entrySet()) {
                if (!what.test(sample(e.getKey()))) continue;
                there += e.getValue();
                if (reason.isEmpty()) reason = why.getOrDefault(e.getKey(), "");
            }
            missingWhy = there >= n && !reason.isEmpty() ? reason : "";
        }
    }

    /** How many of this thing to make, at this hand, out of these stores: the whole way, worked out. */
    public static Plan plan(ServerLevel level, Villages.Village v, Item target, int count, Hand hand) {
        Map<Item, Integer> held = held(level, v.id());
        Map<Item, String> why = new HashMap<>();
        Ctx c = new Ctx(level, hand, held, why);
        State s = new State(free(level, v, held, why), hand.table(), hand.furnace());
        if (!Craftsmanship.canMake(hand.skill(), target)) {
            return new Plan(target, 0, List.of(), Map.of(), Map.of(), List.of(), "the hand for it",
                null, 0, "it's level " + Craftsmanship.rung(target) + " work, and I'm level " + hand.skill());
        }
        if (!produce(c, s, target, Math.max(1, count), 0, new HashSet<>())) {
            if (c.missing == null) {
                boolean none = RecipeBook.waysFor(level, target).isEmpty();
                return new Plan(target, 0, List.of(), Map.of(), Map.of(), List.of(), none ? "a way to make it" : "the makings",
                    null, 0, "");
            }
            return new Plan(target, 0, List.of(), Map.of(), Map.of(), List.of(), c.missingWords, c.missing, c.missingCount, c.missingWhy);
        }
        int made = s.spare.getOrDefault(target, 0);
        Map<Item, Integer> back = new LinkedHashMap<>(s.spare);
        back.remove(target);
        back.entrySet().removeIf(e -> e.getValue() <= 0);
        return new Plan(target, made, List.copyOf(s.steps), Map.copyOf(s.takes), back, List.copyOf(s.tools), null, null, 0, "");
    }

    /** These things wanted for a piece of work of a maker's own (the café's drinks): out of the stores,
     *  or made, the same way. Nothing is made for its own sake; the plan's takes are the wants. */
    public static Plan plan(ServerLevel level, Villages.Village v, List<Want> wants, Hand hand) {
        Map<Item, Integer> held = held(level, v.id());
        Map<Item, String> why = new HashMap<>();
        Ctx c = new Ctx(level, hand, held, why);
        State s = new State(free(level, v, held, why), hand.table(), hand.furnace());
        for (Want w : wants) {
            if (!need(c, s, w.what(), w.kinds(), w.count(), 0, new HashSet<>(), false, w.words())) {
                String words = c.missing == null ? w.words() : c.missingWords;
                return new Plan(null, 0, List.of(), Map.of(), Map.of(), List.of(), words, c.missing, c.missingCount, c.missingWhy);
            }
        }
        Map<Item, Integer> back = new LinkedHashMap<>(s.spare);
        back.entrySet().removeIf(e -> e.getValue() <= 0);
        return new Plan(null, 0, List.copyOf(s.steps), Map.copyOf(s.takes), back, List.copyOf(s.tools), null, null, 0, "");
    }

    /**
     * So many of what this wants: out of what is free (what the plan made itself first, then the
     * biggest piles), and the rest made, trying each thing that would do, the likeliest first.
     */
    private static boolean need(Ctx c, State s, Predicate<ItemStack> what, List<Item> kinds, int n, int depth, Set<Item> path,
                                boolean crafting, String words) {
        if (n <= 0) return true;
        List<Item> have = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : s.free.entrySet()) {
            if (e.getValue() > 0 && what.test(c.sample(e.getKey()))) have.add(e.getKey());
        }
        have.sort((a, b) -> {
            int sa = s.spare.getOrDefault(a, 0), sb = s.spare.getOrDefault(b, 0);
            if (sa != sb) return Integer.compare(sb, sa);
            return Integer.compare(s.free.get(b), s.free.get(a));
        });
        for (Item it : have) {
            int k = Math.min(n, s.free.get(it));
            use(c, s, it, k, crafting);
            n -= k;
            if (n == 0) return true;
        }
        for (Item it : choices(c, s, kinds)) {
            State t = s.copy();
            if (produce(c, t, it, n, depth + 1, path)) {
                use(c, t, it, n, crafting);
                s.set(t);
                return true;
            }
        }
        c.lacking(depth, kinds, what, n, words.isEmpty() ? wordsFor(kinds, n) : words);
        return false;
    }

    /** So many of a thing used: out of what the plan made first, the rest out of the stores; and what a
     *  crafting leaves behind (a bucket, a bottle) back where it came from. */
    private static void use(Ctx c, State s, Item it, int k, boolean crafting) {
        s.free.merge(it, -k, Integer::sum);
        int fromSpare = Math.min(k, s.spare.getOrDefault(it, 0));
        if (fromSpare > 0) s.spare.merge(it, -fromSpare, Integer::sum);
        if (k - fromSpare > 0) s.takes.merge(it, k - fromSpare, Integer::sum);
        if (crafting) {
            ItemStack one = c.sample(it);
            if (one.hasCraftingRemainingItem()) {
                ItemStack rem = one.getCraftingRemainingItem();
                if (!rem.isEmpty()) {
                    s.free.merge(rem.getItem(), rem.getCount() * k, Integer::sum);
                    s.spare.merge(rem.getItem(), rem.getCount() * k, Integer::sum);
                }
            }
        }
    }

    /** The things that would do for an ingredient that can be made at all, the likeliest first: one
     *  whose usual way's parts the stores hold some of before one whose they do not. */
    private static List<Item> choices(Ctx c, State s, List<Item> kinds) {
        List<Item> out = new ArrayList<>();
        Map<Item, Integer> score = new HashMap<>();
        for (Item it : kinds) {
            if (out.contains(it)) continue;
            List<RecipeBook.Way> ways = RecipeBook.waysFor(c.level, it);
            if (ways.isEmpty()) continue;
            int best = -100;
            for (RecipeBook.Way w : ways) {
                int sc = 0;
                for (RecipeBook.Part p : w.parts()) {
                    int free = freeOf(c, s, p.ingredient());
                    sc += free >= p.count() ? 2 : free > 0 ? 1 : 0;
                }
                best = Math.max(best, sc * 4 - w.parts().size() + (w.canonical() ? 1 : 0));
            }
            out.add(it);
            score.put(it, best);
        }
        out.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        return out.size() > MOST_CHOICES ? out.subList(0, MOST_CHOICES) : out;
    }

    private static int freeOf(Ctx c, State s, Predicate<ItemStack> what) {
        int n = 0;
        for (Map.Entry<Item, Integer> e : s.free.entrySet()) if (e.getValue() > 0 && what.test(c.sample(e.getKey()))) n += e.getValue();
        return n;
    }

    /**
     * So many of this made, by the first of the game's ways for it this hand can work and the stores
     * can run to: its parts needed (made in their turn), its bench or its fire to hand, and the fuel.
     * What it makes goes to what is free; the caller uses what it wanted of it.
     */
    private static boolean produce(Ctx c, State s, Item item, int n, int depth, Set<Item> path) {
        if (depth > DEEPEST || path.contains(item) || ++c.looks > MOST_LOOKS) return false;
        if (!Craftsmanship.canMake(c.hand.skill(), item)) return false;
        List<RecipeBook.Way> ways = new ArrayList<>(RecipeBook.waysFor(c.level, item));
        if (ways.isEmpty()) return false;
        // The ways this hand has the fire for, the free hearth first; then as the book has them.
        ways.removeIf(w -> !fireToHand(c, s, w.fire()));
        ways.sort((a, b) -> Integer.compare(firePreference(c, s, a.fire()), firePreference(c, s, b.fire())));
        path.add(item);
        try {
            for (RecipeBook.Way w : ways) {
                State t = s.copy();
                int times = (n + w.yield() - 1) / w.yield();
                // The makings first (what it is short of is said of them before the bench), then the bench
                // or the furnace, made if there is none to hand, then the fuel.
                boolean all = true;
                for (RecipeBook.Part p : w.parts()) {
                    List<Item> kinds = kinds(p.ingredient());
                    if (!need(c, t, p.ingredient(), kinds, p.count() * times, depth, path, w.fire() == Fire.NONE, "")) { all = false; break; }
                }
                if (!all) continue;
                if (w.fire() == Fire.NONE && w.needsTable() && !t.table && !setUp(c, t, Items.CRAFTING_TABLE, depth, path)) continue;
                if (w.fire() == Fire.FURNACE && !t.furnace && !setUp(c, t, Items.FURNACE, depth, path)) continue;
                if ((w.fire() == Fire.FURNACE || w.fire() == Fire.SMOKER) && !fuel(c, t, times, depth)) continue;
                int made = times * w.yield();
                t.free.merge(item, made, Integer::sum);
                t.spare.merge(item, made, Integer::sum);
                t.steps.add(new Step(item, times, w.yield(), w.fire(), w.needsTable()));
                s.set(t);
                return true;
            }
            return false;
        } finally {
            path.remove(item);
        }
    }

    private static boolean fireToHand(Ctx c, State s, Fire f) {
        return switch (f) {
            case NONE -> true;
            case FURNACE -> true;                       // one to hand, or one made first (setUp)
            case SMOKER -> c.hand.smoker();
            case CAMPFIRE -> c.hand.campfire();
        };
    }

    /** The bench first; then the smoker, the hearth (no fuel, if the smoker has none), a furnace to hand,
     *  and last a furnace still to be made. */
    private static int firePreference(Ctx c, State s, Fire f) {
        return switch (f) {
            case NONE -> 0;
            case SMOKER -> 1;
            case CAMPFIRE -> 2;
            case FURNACE -> s.furnace ? 3 : 4;
        };
    }

    /** A crafting table or a furnace the maker has not got: made first, of the stores, and kept. */
    private static boolean setUp(Ctx c, State t, Item tool, int depth, Set<Item> path) {
        if (!produce(c, t, tool, 1, depth + 1, path)) {
            c.lacking(depth, List.of(tool), x -> x.is(tool), 1, tool == Items.CRAFTING_TABLE ? "a crafting table" : "a furnace");
            return false;
        }
        t.free.merge(tool, -1, Integer::sum);
        t.spare.merge(tool, -1, Integer::sum);
        t.tools.add(tool);
        if (tool == Items.CRAFTING_TABLE) t.table = true;
        if (tool == Items.FURNACE) t.furnace = true;
        return true;
    }

    /**
     * The fuel for so many firings, out of what is free (never made for it): coal or charcoal first,
     * then planks, then logs, by the game's own burn times. Nothing burns the builders' timber, the
     * coal the age wants, or anything the village keeps back.
     */
    private static boolean fuel(Ctx c, State s, int firings, int depth) {
        List<Predicate<ItemStack>> kinds = List.of(x -> x.is(Items.CHARCOAL) || x.is(Items.COAL), x -> x.is(ItemTags.PLANKS),
            x -> x.is(ItemTags.LOGS));
        for (Predicate<ItemStack> kind : kinds) {
            for (Map.Entry<Item, Integer> e : new ArrayList<>(s.free.entrySet())) {
                ItemStack one = c.sample(e.getKey());
                if (e.getValue() <= 0 || !kind.test(one)) continue;
                int burns = one.getBurnTime(RecipeType.SMELTING);
                if (burns <= 0) continue;
                int want = (firings * FIRING + burns - 1) / burns;
                if (e.getValue() < want) continue;
                use(c, s, e.getKey(), want, false);
                return true;
            }
        }
        c.lacking(depth, List.of(Items.COAL), x -> x.is(Items.COAL) || x.is(Items.CHARCOAL), Math.max(1, (firings + 7) / 8),
            "fuel for the fire");
        return false;
    }

    /** The things an ingredient accepts, each once. */
    private static List<Item> kinds(Ingredient ing) {
        List<Item> out = new ArrayList<>();
        for (ItemStack s : ing.getItems()) if (!out.contains(s.getItem())) out.add(s.getItem());
        return out;
    }

    // ------------------------------------------------------------------ the making

    /**
     * Do the work a plan worked out: everything it takes out of the stores (all of it, or none), the
     * rest of what it made and a crafting's leftovers back into them, a table or a furnace it made to
     * the maker. False, and nothing taken, if the stores changed under it.
     */
    public static boolean take(ServerLevel level, Villages.Village v, Plan p, @Nullable VillageFolkEntity f) {
        if (!p.ok()) return false;
        Map<Item, Integer> took = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : p.takes.entrySet()) {
            Item it = e.getKey();
            if (!Crafts.take(level, v, s -> s.is(it) && s.getComponentsPatch().isEmpty(), e.getValue())) {
                for (Map.Entry<Item, Integer> b : took.entrySet()) Crafts.giveBack(level, v, b.getKey(), b.getValue());
                return false;
            }
            took.put(it, e.getValue());
        }
        for (Map.Entry<Item, Integer> e : p.back.entrySet()) Crafts.giveBack(level, v, e.getKey(), e.getValue());
        for (Item tool : p.tools) {
            ItemStack t = new ItemStack(tool);
            ItemStack left = f == null ? t : f.insertItem(t);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        Budget.forget(v.id());
        return true;
    }

    /**
     * Make what a plan worked out, into the stores: what is one to a stack as good as the hand (and with
     * its mark: Craftsmanship), the rest as it comes. Returns what was made, or empty if nothing was.
     */
    public static ItemStack make(ServerLevel level, Villages.Village v, Plan p, @Nullable VillageFolkEntity f, Hand hand) {
        if (p.target == null || p.made <= 0 || !take(level, v, p, f)) return ItemStack.EMPTY;
        ItemStack one = new ItemStack(p.target);
        if (one.getMaxStackSize() == 1) {
            ItemStack last = ItemStack.EMPTY;
            for (int i = 0; i < p.made; i++) {
                last = new ItemStack(p.target);
                if (!hand.maker().isEmpty()) last = Craftsmanship.finish(level, last, hand.skill(), hand.maker());
                Crafts.store(level, v, last.copy());
            }
            return last.copyWithCount(p.made);
        }
        Crafts.giveBack(level, v, p.target, p.made);
        return new ItemStack(p.target, p.made);
    }

    // ------------------------------------------------------------------ words

    /** "a chest", "8 torches", "4 sticks", "a loaf of bread", "2 string". */
    static String words(Item it, int n) {
        String name = new ItemStack(it).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        if (it == Items.BREAD) return n == 1 ? "a loaf of bread" : n + " loaves of bread";
        if (n != 1) return n + " " + plural(name);
        if (plural(name).equals(name)) return "1 " + name;                     // counted by the heap: "1 string"
        return (name.matches("^[aeiou].*") ? "an " : "a ") + name;
    }

    /** Things counted by the heap, not one by one: "16 wheat", "2 string", "6 cooked beef". */
    private static final List<String> HEAPS = List.of("s", "glass", "wool", "string", "bread", "leather", "iron", "wheat", "sugar",
        "coal", "cobblestone", "lapis lazuli", "beef", "mutton", "cod", "salmon", "chicken", "cane", "paper", "sand", "gravel",
        "clay", "dirt", "stone", "deepslate", "kelp", "gunpowder", "redstone");

    static String plural(String name) {
        for (String h : HEAPS) if (name.endsWith(h)) return name;
        if (name.endsWith("ch") || name.endsWith("sh") || name.endsWith("x") || name.endsWith("potato")) return name + "es";
        if (name.endsWith("y") && !name.endsWith("ey") && !name.endsWith("ay")) return name.substring(0, name.length() - 1) + "ies";
        return name + "s";
    }

    /** What an ingredient wants, in words: "3 planks" for any plank, "a white wool" for one. */
    private static String wordsFor(List<Item> kinds, int n) {
        if (kinds.isEmpty()) return n + " of something";
        if (kinds.size() == 1) return words(kinds.get(0), n);
        // The family the kinds share: oak_planks, spruce_planks... are planks; white_wool... wool.
        String first = BuiltInRegistries.ITEM.getKey(kinds.get(0)).getPath();
        String common = first;
        for (Item it : kinds) {
            String p = BuiltInRegistries.ITEM.getKey(it).getPath();
            while (!common.isEmpty() && !(p.endsWith(common) && (p.length() == common.length() || p.charAt(p.length() - common.length() - 1) == '_'))) {
                int cut = common.indexOf('_');
                common = cut < 0 ? "" : common.substring(cut + 1);
            }
        }
        if (common.isEmpty()) return words(kinds.get(0), n);
        String family = common.replace('_', ' ');
        return n + " " + (n == 1 ? family : plural(family));
    }
}
