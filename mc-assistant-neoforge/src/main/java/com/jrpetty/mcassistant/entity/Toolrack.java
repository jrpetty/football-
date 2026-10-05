package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The storehouse's rack of spare tools. A village of a hundred wears through a stone pick or an axe
 * somewhere every few minutes, and each of them used to be made the moment it was missed, by the
 * hand that missed it, out of whatever it could reach — and when it could reach nothing (a pack full
 * of the day's cobblestone, no wood in it for the handle) it stood "needing a pickaxe" with
 * seventeen hundred logs and two thousand stone in the stores. The long game's hundred had a fifth
 * of its miners and woodcutters standing so.
 * <ul>
 * <li><b>Kept in stock, ahead of need.</b> One spare a trade's tool for every four hands that wear
 *     one out (a hoe for every eight farmers: it is only for breaking new ground), never more than
 *     eight: picks for the miners, axes for the woodcutters, hoes, blades for the watch and the
 *     hunters, rods for the fishers, shears for the pen.</li>
 * <li><b>Made of the stores, never of nothing.</b> Whoever is at the stores with a bench to hand
 *     makes them — the storekeeper at its counter, through the day; any hand at the heart of an
 *     evening, or with nothing better to do there — two at a time at most: the head out of the stores'
 *     cobblestone (or iron), the handle out of their sticks, else planks, else a log sawn for it, and
 *     what is left of the log or the planks goes back. A rod is three sticks and two string, shears
 *     two iron.</li>
 * <li><b>The age's tier.</b> Stone, once the stores have stone to spare (the founders came with stone
 *     tools); wood only in the Wood Age, with no stone yet; iron once the village has come to iron and
 *     is no longer putting it by for its age, and never the smith's last bars. Nothing is made while
 *     the founding stores are the storehouse's (Villages.storehouseFirst).</li>
 * <li><b>Handed out.</b> A worker whose tool is gone takes a spare off the rack, booked out in the
 *     storehouse's books; one whose tool is nearly worn through and whose plot is far out has a
 *     courier bring it the spare before it breaks (Couriers), and works on meanwhile; one near the
 *     stores takes it on its way past the counter.</li>
 * </ul>
 * Wear stays as it was: a spare is a tool like any other, and wears through the same.
 */
public final class Toolrack {

    private Toolrack() {}

    /** A tool the trades wear out: the word a courier is sent out with, how its id ends, and what is in
     *  it — the head (cobblestone, planks or iron), the sticks, the string — and one spare for so many hands. */
    public enum Tool {
        PICKAXE("pickaxe", "_pickaxe", 3, 2, 0, 4),
        AXE("axe", "_axe", 3, 2, 0, 4),
        HOE("hoe", "_hoe", 2, 2, 0, 8),
        SWORD("sword", "_sword", 2, 1, 0, 4),
        ROD("fishing rod", "fishing_rod", 0, 3, 2, 4),
        SHEARS("shears", "shears", 2, 0, 0, 4);

        /** What a courier is sent out with (WithdrawGoal.matcherFor reads it), and the books' word. */
        public final String word;
        final String suffix;
        final int head;
        final int sticks;
        final int string;
        /** One spare for this many hands that use one. */
        final int per;

        Tool(String word, String suffix, int head, int sticks, int string, int per) {
            this.word = word;
            this.suffix = suffix;
            this.head = head;
            this.sticks = sticks;
            this.string = string;
            this.per = per;
        }
    }

    /** The most of one kind the rack keeps, however big the village. */
    static final int MOST = 8;
    /** How often the rack is looked to, in a village (ticks), and how many are made at a look. */
    static final long EVERY = 200L;
    static final int AT_A_LOOK = 2;
    /** Cobblestone the stores keep back from the rack in the Wood Age: the builders' working stock
     *  (Masonry), the young village's walls before its spares. From the Stone Age, with the mines
     *  bringing it in by the stack, only a little is kept back: the smelter fires what is over the
     *  builders' stock (Links.stone), and a rack that waited for more than that waited for ever. A
     *  worker whose tool is gone is still made one there and then out of whatever there is
     *  (VillageFolkEntity.stoneToolFromTheStores). */
    static final int STONE_SPARE = Masonry.BUILDERS_STONE;
    static final int STONE_SPARE_LATER = 16;
    /** Planks' worth of timber the stores keep back from the rack's handles: the builders' forty-eight. */
    static final int WOOD_SPARE = 48;
    /** A tool with this much of its wear left, or less, is "nearly gone" (percent). */
    public static final int WORN = 20;
    /** A tool on the rack counts as a spare with at least this much left (percent). */
    static final int SPARE = 50;

    /** When each village's rack was last looked to. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Each village's tally: {made, handed out}, for the books and the tests. */
    private static final Map<UUID, int[]> TALLY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        TALLY.clear();
    }

    // ------------------------------------------------------------------ what is what

    /** The tool this trade wears out, or null if it wears none. */
    @Nullable
    public static Tool of(StationTask t) {
        return switch (t) {
            case MINE -> Tool.PICKAXE;
            case WOOD -> Tool.AXE;
            case FARM -> Tool.HOE;
            case GUARD, HUNT -> Tool.SWORD;
            case FISH -> Tool.ROD;
            case RANCH -> Tool.SHEARS;
            default -> null;
        };
    }

    /** Is this one of that kind of tool (any metal)? */
    public static boolean is(Tool tool, ItemStack s) {
        if (s.isEmpty()) return false;
        return switch (tool) {
            case ROD -> s.is(Items.FISHING_ROD);
            case SHEARS -> s.is(Items.SHEARS);
            default -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(tool.suffix);
        };
    }

    /** How much of its wear a tool has left, in percent (a hundred for one that does not wear). */
    public static int left(ItemStack s) {
        if (!s.isDamageableItem() || s.getMaxDamage() <= 0) return 100;
        return (s.getMaxDamage() - s.getDamageValue()) * 100 / s.getMaxDamage();
    }

    /** Nearly worn through. */
    public static boolean worn(ItemStack s) {
        return left(s) <= WORN;
    }

    /** What a spare is: plain or marked by its maker, but not somebody's half-worn old one. */
    static boolean spare(Tool tool, ItemStack s) {
        return is(tool, s) && left(s) >= SPARE;
    }

    /** How good a tool is: its metal. */
    static int metal(ItemStack s) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return path.startsWith("netherite_") ? 5 : path.startsWith("diamond_") ? 4 : path.startsWith("iron_") ? 3
            : path.startsWith("stone_") ? 2 : path.startsWith("golden_") ? 1 : 0;
    }

    // ------------------------------------------------------------------ how many

    /** How many hands of this village wear this kind of tool out. */
    static int users(UUID village, Tool tool) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby() || !a.isAlive()) continue;
            if (of(a.stationTask()) == tool) n++;
        }
        return n;
    }

    /** The spares of this kind the rack keeps: one for every so many hands that use one, at least one
     *  where anybody does, never more than eight. */
    public static int wanted(UUID village, Tool tool) {
        int users = users(village, tool);
        if (users <= 0) return 0;
        return Math.min(MOST, Math.max(1, (users + tool.per - 1) / tool.per));
    }

    /** The spares of this kind on the rack now: in the stores, and not half worn. */
    public static int onRack(ServerLevel level, UUID village, Tool tool) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (spare(tool, s)) n += s.getCount();
            }
        }
        return n;
    }

    /** The kinds the rack is short of, the shortest first. */
    static List<Tool> shortest(ServerLevel level, UUID village) {
        List<Tool> out = new ArrayList<>();
        Map<Tool, Integer> gap = new java.util.EnumMap<>(Tool.class);
        for (Tool t : Tool.values()) {
            int want = wanted(village, t);
            if (want <= 0) continue;
            int g = want - onRack(level, village, t);
            if (g <= 0) continue;
            gap.put(t, g);
            out.add(t);
        }
        out.sort((a, b) -> Integer.compare(gap.get(b), gap.get(a)));
        return out;
    }

    /** Tests and the books: {made, handed out} since the start. */
    public static int[] tally(UUID village) {
        int[] t = TALLY.get(village);
        return t == null ? new int[2] : t.clone();
    }

    // ------------------------------------------------------------------ the making

    private static final Predicate<ItemStack> STONE = s -> s.is(ItemTags.STONE_TOOL_MATERIALS) && s.getComponentsPatch().isEmpty();
    private static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT) && s.getComponentsPatch().isEmpty();
    private static final Predicate<ItemStack> STRING = s -> s.is(Items.STRING) && s.getComponentsPatch().isEmpty();
    private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK) && s.getComponentsPatch().isEmpty();
    private static final Predicate<ItemStack> PLANK = s -> s.is(ItemTags.PLANKS) && s.getComponentsPatch().isEmpty();
    private static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS) && s.getComponentsPatch().isEmpty();

    /** Timber in the stores, counted in planks: a log is four, a stick half of one. */
    private static int timber(ServerLevel level, UUID village) {
        return Market.stock(level, village, LOG) * 4 + Market.stock(level, village, PLANK) + Market.stock(level, village, STICK) / 2;
    }

    /**
     * What the rack makes of this kind just now, at the age's tier and out of what the stores can
     * spare; or null if it can make none. Iron once the village has come to iron and is not putting
     * it by, with the smith's bars left; stone with stone to spare; wood only in the Wood Age.
     */
    @Nullable
    static Item tierFor(ServerLevel level, Villages.Village v, Tool tool) {
        UUID id = v.id();
        Villages.Age age = Villages.ageOf(id);
        int iron = Market.stock(level, id, IRON);
        boolean ironFree = age.ordinal() >= Villages.Age.STONE.ordinal() && !Crafts.savingIron(level, v);
        if (tool == Tool.ROD) {
            int string = Market.stock(level, id, STRING);
            return string >= tool.string + 2 && timber(level, id) >= 2 + WOOD_SPARE ? Items.FISHING_ROD : null;
        }
        if (tool == Tool.SHEARS) {
            return ironFree && iron >= tool.head + Crafts.IRON_KEPT ? Items.SHEARS : null;
        }
        int handle = (tool.sticks + 1) / 2;
        if (timber(level, id) < handle + WOOD_SPARE) return null;
        String kind = tool.suffix.substring(1);
        if (age.ordinal() >= Villages.Age.IRON.ordinal() && ironFree && iron >= tool.head + Crafts.IRON_KEPT + 8) {
            return item("iron_" + kind);
        }
        int stoneKept = age == Villages.Age.WOOD ? STONE_SPARE : STONE_SPARE_LATER;
        if (Market.stock(level, id, STONE) >= tool.head + stoneKept) return item("stone_" + kind);
        if (age == Villages.Age.WOOD && timber(level, id) >= tool.head + handle + WOOD_SPARE) return item("wooden_" + kind);
        return null;
    }

    @Nullable
    private static Item item(String path) {
        Item it = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(path));
        return it == Items.AIR ? null : it;
    }

    /**
     * One of these made of the stores and put on the rack: the head, the handle and the string taken
     * out (all of it, or none), what is left of a log or the planks sawn for it put back, and the tool
     * as good as the maker's hand. Returns it in words, or null if the stores could not run to it.
     */
    @Nullable
    static String make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity maker, Tool tool, Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        boolean wooden = path.startsWith("wooden_");
        Predicate<ItemStack> head = path.startsWith("iron_") || item == Items.SHEARS ? IRON : wooden ? null : STONE;
        List<ItemStack> took = new ArrayList<>();
        if (head != null && tool.head > 0) {
            ItemStack kind = sample(level, v, head);
            if (kind.isEmpty() || !Crafts.take(level, v, head, tool.head)) return null;
            took.add(kind.copyWithCount(tool.head));
        }
        if (tool.string > 0) {
            if (!Crafts.take(level, v, STRING, tool.string)) {
                giveBack(level, v, took);
                return null;
            }
            took.add(new ItemStack(Items.STRING, tool.string));
        }
        if (!handle(level, v, tool.sticks, wooden ? tool.head : 0, took)) {
            giveBack(level, v, took);
            return null;
        }
        ItemStack made = new ItemStack(item);
        if (maker != null) made = Craftsmanship.finish(level, made, maker.veteranLevel(), maker.displayNameCap());
        Crafts.store(level, v, made.copy());
        TALLY.computeIfAbsent(v.id(), k -> new int[2])[0]++;
        return Crafts.named(made);
    }

    /** The first plain stack of what matches in the stores, as a sample of what will be taken. */
    private static ItemStack sample(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) return s.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void giveBack(ServerLevel level, Villages.Village v, List<ItemStack> took) {
        for (ItemStack s : took) Crafts.giveBack(level, v, s.getItem(), s.getCount());
    }

    /**
     * The wood a tool wants, out of the stores: so many sticks (the stores' own first, else sawn from
     * planks, two to four sticks) and so many planks besides (a wooden head), the planks out of the
     * stores' own, else a log sawn into four. What a log or a pair of planks makes and the tool does not
     * use goes back. All or nothing: false, and nothing kept, if there is not the wood for it.
     */
    private static boolean handle(ServerLevel level, Villages.Village v, int sticks, int planks, List<ItemStack> took) {
        UUID id = v.id();
        List<ItemStack> mine = new ArrayList<>();
        int fromStores = Math.min(sticks, Market.stock(level, id, STICK));
        if (fromStores > 0 && Crafts.take(level, v, STICK, fromStores)) {
            mine.add(new ItemStack(Items.STICK, fromStores));
            sticks -= fromStores;
        }
        int sawings = (sticks + 3) / 4;                         // two planks make four sticks
        int spareSticks = sawings * 4 - sticks;
        int needPlanks = planks + sawings * 2;
        int plankFrom = Math.min(needPlanks, Market.stock(level, id, PLANK));
        if (plankFrom > 0) {
            if (Crafts.take(level, v, PLANK, plankFrom)) {
                mine.add(new ItemStack(Items.OAK_PLANKS, plankFrom));
                needPlanks -= plankFrom;
            }
        }
        int logs = (needPlanks + 3) / 4;                        // a log makes four planks
        int sparePlanks = logs * 4 - needPlanks;
        if (logs > 0) {
            if (!Crafts.take(level, v, LOG, logs)) {
                giveBack(level, v, mine);
                return false;
            }
            mine.add(new ItemStack(Items.OAK_LOG, logs));
        }
        // What the sawing left over goes back into the stores: never thrown away.
        if (sparePlanks > 0) Crafts.giveBack(level, v, Items.OAK_PLANKS, sparePlanks);
        if (spareSticks > 0) Crafts.giveBack(level, v, Items.STICK, spareSticks);
        took.addAll(mine);
        return true;
    }

    // ------------------------------------------------------------------ the rack kept

    /** Is this hand at the stores (the storehouse's door, or the chests round the heart)? */
    static boolean atTheStores(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos here = f.blockPosition();
        if (Villages.inStoreArea(v.id(), here)) return true;
        BlockPos spot = Storehouses.standingSpot(level, v.id());
        return spot != null && spot.distSqr(here) <= 16.0 * 16.0;
    }

    /**
     * The rack looked to by a hand at the stores: the kind it is shortest of, made of the stores' own
     * goods, two at most. Once in ten seconds a village, whoever looks. Returns what was made, in words,
     * or null.
     */
    @Nullable
    public static String tend(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || f.isBaby() || f.isSleeping()) return null;
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < EVERY && now >= last) return null;
        if (!atTheStores(level, v, f)) return null;
        LOOKED.put(id, now);
        return restock(level, v, f, AT_A_LOOK);
    }

    /** Make up to {@code most} spares, shortest kind first, as a hand at the stores would. Returns
     *  what was made, in words, or null. (Tests call it straight.) */
    @Nullable
    public static String restock(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity maker, int most) {
        UUID id = v.id();
        // The founding stores are the storehouse's until it stands (Villages.storehouseFirst).
        if (!Storehouses.stands(id) && Villages.storehouseFirst(id, level.getGameTime())) return null;
        // Once there is a shop with somebody at its bench, the rack is its workshop's to keep (Workshop), of the
        // age's best the stores can run to: the spares are on its order book, first after the watch's.
        if (Workshop.keepsTheRack(level, v)) return null;
        // A bench to hand: its own (every folk carries one), the stores', or a neighbour's.
        if (!Bench.handOf(level, v, maker, null).table()) return null;
        List<String> made = new ArrayList<>();
        // The rack is the storehouse's: its making is booked to the storehouse's trade, whoever made it.
        Economy.openCraft(id, StationTask.STORE);
        try {
            for (int n = 0; n < most; n++) {
                // The kind it is shortest of first; one that cannot be made just now (no string for a
                // rod, no iron to spare for shears) does not hold up the next.
                String one = null;
                for (Tool t : shortest(level, id)) {
                    Item item = tierFor(level, v, t);
                    if (item != null && (one = make(level, v, maker, t, item)) != null) break;
                }
                if (one == null) break;
                made.add(one);
            }
        } finally {
            Economy.closeCraft();
        }
        if (made.isEmpty()) return null;
        String words = String.join(" and ", made);
        if (maker != null) {
            maker.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, maker.blockPosition(), SoundEvents.VILLAGER_WORK_TOOLSMITH, SoundSource.NEUTRAL, 0.6F, 1.0F);
            maker.note(AssistantEntity.Deed.THINGS_MADE, made.size());
            maker.brain("made " + words + " for the storehouse's rack");
            if (level.getRandom().nextInt(4) == 0) {
                FolkTalk.speak(maker, FolkTalk.pick(level.getRandom(), "There — " + words + " on the rack, for whoever breaks one next.",
                    "A spare or two for the rack: " + words + ".", "Nobody stands idle for want of a tool in this village."));
            }
        }
        return words;
    }

    // ------------------------------------------------------------------ handed out

    /**
     * A spare of this kind off the rack and into this hand's pack: the best the stores hold that it may
     * use, and not half worn if there is better. Booked out in the storehouse's books. Returns it, or
     * empty if the rack has none (or the pack has no room for it: then it stays on the rack).
     */
    public static ItemStack issue(ServerLevel level, Villages.Village v, VillageFolkEntity f, Tool tool) {
        if (f.isPackFull()) return ItemStack.EMPTY;            // a tool takes a slot of its own: it banks its load first
        Container best = null;
        int bestSlot = -1, bestScore = Integer.MIN_VALUE;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!is(tool, s) || worn(s) || !f.mayUseTier(s)) continue;
                int score = metal(s) * 200 + left(s);
                if (score > bestScore) { bestScore = score; best = c; bestSlot = i; }
            }
        }
        if (best == null) return ItemStack.EMPTY;
        ItemStack got = best.getItem(bestSlot).split(1);
        if (best.getItem(bestSlot).isEmpty()) best.setItem(bestSlot, ItemStack.EMPTY);
        best.setChanged();
        ItemStack left = f.insertGiven(got.copy());
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);                   // no room in its pack after all: back it goes
            return ItemStack.EMPTY;
        }
        // Out of the storehouse: in its books, handed over at the counter or booked out to a far plot.
        if (best instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity) Storekeeping.handedOut(f, List.of(got.copy()));
        TALLY.computeIfAbsent(v.id(), k -> new int[2])[1]++;
        return got;
    }
}
