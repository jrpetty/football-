package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.DishItem;
import com.jrpetty.mcassistant.item.DishItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [culture2] A town's own table: the dish it is known for, from what its land gives, and a second from what its folk
 * bring in most.
 * <ul>
 * <li><b>The dish.</b> The land chooses it: fish stew on the coast and the rivers, mushroom and game pie in the forest,
 *     sweet berry tart in the pine woods and the snow, the harvest loaf on the plains and the meadows, miner's hotpot in the
 *     hills and the badlands, spiced mutton on the savanna, cocoa cake in the jungle, fen broth in the swamp, and the
 *     well-towns' rabbit stew in the desert. A colony takes its mother's dish with it, and makes it in its own land's
 *     way: the hills' take on the coast's fish stew is made with mutton, and says so on the bowl.</li>
 * <li><b>The second dish</b>, once a trade brings in enough of its makings: a town of fishers takes to fish stew, one of
 *     hunters to game pie, one of herders to hotpot (or spiced mutton on the savanna).</li>
 * <li><b>The cook favours it.</b> Before the café's usual menu, the cook keeps the town's dish in the stores (four, and a
 *     portion more for every four folk, up to twelve), cooked by the game's own recipe the whole way from what the
 *     stores hold (Bench: potatoes baked in the smoker, cane pressed to sugar), never out of a larder on short commons.
 *     A town with no cook has a hand at its works cook it on a feast day.</li>
 * <li><b>The folk prefer it.</b> A folk who eats its own town's dish is the happier for a day ("a taste of home"), and
 *     more at a feast, where it is served before anything else in the stores ("our own dish at the feast"). A dish
 *     from another town is a treat ("a delicacy").</li>
 * <li><b>Neighbours buy it.</b> A caravan setting out takes a couple of the town's dish if the other town's is not the
 *     same (Caravans): it is sold there off the caravan's back like any good, marked as from where it came.</li>
 * <li><b>Players order it.</b> It is the first thing on the café's counter, and on a board on the tavern's bar ("The
 *     house dish"): right-click it to buy a portion.</li>
 * </ul>
 */
public final class Cuisine {

    private Cuisine() {}

    /** A dish's main makings: meat or fish, or something sweet (what a colony's land swaps in for it). */
    enum Kind { SAVOURY, SWEET }

    /** The towns' dishes: what each is called, the main of its makings, and how many a batch makes. */
    public enum Dish {
        FISH_STEW("fish stew", "fish stews", Kind.SAVOURY, 1),
        GAME_PIE("mushroom and game pie", "game pies", Kind.SAVOURY, 2),
        BERRY_TART("sweet berry tart", "berry tarts", Kind.SWEET, 2),
        HARVEST_LOAF("harvest loaf", "harvest loaves", Kind.SWEET, 2),
        HOTPOT("miner's hotpot", "hotpots", Kind.SAVOURY, 1),
        SPICED_MUTTON("spiced mutton", "portions of spiced mutton", Kind.SAVOURY, 1),
        COCOA_CAKE("cocoa cake", "slices of cocoa cake", Kind.SWEET, 2),
        FEN_BROTH("fen broth", "bowls of fen broth", Kind.SAVOURY, 1),
        RABBIT_STEW("rabbit stew", "rabbit stews", Kind.SAVOURY, 1);

        public final String words, plural;
        final Kind kind;
        final int batch;

        Dish(String words, String plural, Kind kind, int batch) {
            this.words = words;
            this.plural = plural;
            this.kind = kind;
            this.batch = batch;
        }

        public Item item() {
            return switch (this) {
                case FISH_STEW -> DishItems.FISH_STEW.get();
                case GAME_PIE -> DishItems.GAME_PIE.get();
                case BERRY_TART -> DishItems.BERRY_TART.get();
                case HARVEST_LOAF -> DishItems.HARVEST_LOAF.get();
                case HOTPOT -> DishItems.HOTPOT.get();
                case SPICED_MUTTON -> DishItems.SPICED_MUTTON.get();
                case COCOA_CAKE -> DishItems.COCOA_CAKE.get();
                case FEN_BROTH -> DishItems.FEN_BROTH.get();
                case RABBIT_STEW -> Items.RABBIT_STEW;
            };
        }

        /** Served in a bowl (the bowl back to whoever ate it, or to the stores at a feast). */
        public boolean bowl() {
            return this == FISH_STEW || this == HOTPOT || this == FEN_BROTH || this == RABBIT_STEW;
        }

        /** Its makings, as the game's recipe has them; the main of them first. */
        List<Bench.Want> makings() {
            return switch (this) {
                case FISH_STEW -> List.of(fish(1), Bench.Want.of(Items.BOWL, 1), Bench.Want.of(Items.BAKED_POTATO, 1),
                    Bench.Want.of(Items.CARROT, 1));
                case GAME_PIE -> List.of(game(1), Bench.Want.of(Items.WHEAT, 2), Bench.Want.of(Items.BROWN_MUSHROOM, 1),
                    Bench.Want.of(Items.RED_MUSHROOM, 1), Bench.Want.of(Items.EGG, 1));
                case BERRY_TART -> List.of(Bench.Want.of(Items.SWEET_BERRIES, 3), Bench.Want.of(Items.WHEAT, 2),
                    Bench.Want.of(Items.SUGAR, 1), Bench.Want.of(Items.EGG, 1));
                case HARVEST_LOAF -> List.of(Bench.Want.of(Items.WHEAT_SEEDS, 1), Bench.Want.of(Items.WHEAT, 3), Bench.Want.of(Items.EGG, 1));
                case HOTPOT -> List.of(Bench.Want.of(Items.COOKED_MUTTON, 1), Bench.Want.of(Items.BOWL, 1),
                    Bench.Want.of(Items.BAKED_POTATO, 2));
                case SPICED_MUTTON -> List.of(Bench.Want.of(Items.COOKED_MUTTON, 1), Bench.Want.of(Items.BEETROOT, 1),
                    Bench.Want.of(Items.SUGAR, 1));
                case COCOA_CAKE -> List.of(Bench.Want.of(Items.COCOA_BEANS, 2), Bench.Want.of(Items.WHEAT, 2),
                    Bench.Want.of(Items.SUGAR, 1), Bench.Want.of(Items.EGG, 1));
                case FEN_BROTH -> List.of(Bench.Want.of(Items.BROWN_MUSHROOM, 1), Bench.Want.of(Items.BOWL, 1),
                    Bench.Want.of(Items.RED_MUSHROOM, 1), Bench.Want.of(Items.BEETROOT, 1));
                case RABBIT_STEW -> List.of(Bench.Want.of(Items.COOKED_RABBIT, 1), Bench.Want.of(Items.BOWL, 1),
                    Bench.Want.of(Items.CARROT, 1), Bench.Want.of(Items.BAKED_POTATO, 1), Bench.Want.of(Items.BROWN_MUSHROOM, 1));
            };
        }

        /** What the main of its makings is, in a word ("fish", "mutton", "honey"). */
        String main() {
            return switch (this) {
                case FISH_STEW -> "fish";
                case GAME_PIE -> "game";
                case BERRY_TART -> "berries";
                case HARVEST_LOAF -> "seed";
                case HOTPOT, SPICED_MUTTON -> "mutton";
                case COCOA_CAKE -> "cocoa";
                case FEN_BROTH -> "mushrooms";
                case RABBIT_STEW -> "rabbit";
            };
        }

        @Nullable
        static Dish byName(@Nullable String s) {
            if (s == null) return null;
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static Bench.Want fish(int n) {
        return new Bench.Want(s -> s.is(Items.COD) || s.is(Items.SALMON), List.of(Items.COD, Items.SALMON), n, n == 1 ? "a fish" : n + " fish");
    }

    private static Bench.Want game(int n) {
        return new Bench.Want(s -> s.is(Items.COOKED_RABBIT) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_CHICKEN),
            List.of(Items.COOKED_RABBIT, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN), n, "roast game");
    }

    /** The dish a land makes of what it gives. */
    public static Dish forLand(Homeland.Land l) {
        return switch (l) {
            case COAST, RIVER -> Dish.FISH_STEW;
            case FOREST -> Dish.GAME_PIE;
            case TAIGA, SNOW -> Dish.BERRY_TART;
            case PLAINS, MEADOW -> Dish.HARVEST_LOAF;
            case MOUNTAIN, BADLANDS -> Dish.HOTPOT;
            case SAVANNA -> Dish.SPICED_MUTTON;
            case JUNGLE -> Dish.COCOA_CAKE;
            case SWAMP -> Dish.FEN_BROTH;
            case DESERT -> Dish.RABBIT_STEW;
        };
    }

    /** What a land puts in a dish of this kind in place of the dish's own main: its staple, and the word for it. */
    record Staple(Bench.Want want, String word) {}

    static Staple staple(Homeland.Land l, Kind k) {
        if (k == Kind.SAVOURY) {
            return switch (l) {
                case COAST, RIVER, SWAMP, SNOW -> new Staple(fish(1), "fish");
                case MOUNTAIN, BADLANDS, SAVANNA -> new Staple(Bench.Want.of(Items.COOKED_MUTTON, 1), "mutton");
                case FOREST, TAIGA, JUNGLE -> new Staple(game(1), "game");
                case DESERT -> new Staple(Bench.Want.of(Items.COOKED_RABBIT, 1), "rabbit");
                case PLAINS, MEADOW -> new Staple(new Bench.Want(s -> s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP)
                    || s.is(Items.COOKED_CHICKEN), List.of(Items.COOKED_PORKCHOP, Items.COOKED_BEEF, Items.COOKED_CHICKEN), 1, "roast meat"), "farmyard meat");
            };
        }
        return switch (l) {
            case TAIGA, SNOW -> new Staple(Bench.Want.of(Items.SWEET_BERRIES, 3), "berries");
            case JUNGLE -> new Staple(Bench.Want.of(Items.COCOA_BEANS, 2), "cocoa");
            case PLAINS, MEADOW -> new Staple(Bench.Want.of(Items.HONEY_BOTTLE, 1), "honey");
            case DESERT, BADLANDS, SAVANNA, MOUNTAIN -> new Staple(Bench.Want.of(Items.SUGAR, 2), "cane sugar");
            case COAST, RIVER, SWAMP, FOREST -> new Staple(Bench.Want.of(Items.APPLE, 2), "apples");
        };
    }

    // ------------------------------------------------------------------ what the town eats

    /** The town's dish, once chosen; null before. */
    @Nullable
    public static Dish dishOf(@Nullable UUID village) {
        return village == null ? null : Dish.byName(TownWays.note(village, "dish"));
    }

    /** Its second dish, from what its folk bring in most; null if none. */
    @Nullable
    public static Dish secondOf(@Nullable UUID village) {
        return village == null ? null : Dish.byName(TownWays.note(village, "dish2"));
    }

    /** The colony's way with its mother's dish: the main it makes it with instead ("mutton"), or null for none. */
    @Nullable
    static String takeOf(UUID village) {
        return TownWays.note(village, "dish.take");
    }

    /** "fish stew", or "the hill-town fish stew, made with mutton". */
    public static String dishWords(UUID village) {
        Dish d = dishOf(village);
        if (d == null) return "nothing of its own yet";
        String take = takeOf(village);
        Homeland.Land l = Homeland.known(village);
        return take == null || l == null ? d.words : "the " + TownWays.landWord(l) + " " + d.words + ", made with " + take;
    }

    /**
     * The town's table worked out (once a day, TownWays.daily): its dish chosen once (its mother's, made its own way,
     * for a colony; its land's for any other), and its second from what its folk bring in most.
     */
    static void choose(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Homeland.Land land = Homeland.of(id);
        if (dishOf(id) == null) {
            Dish mother = Dish.byName(TownWays.note(id, "mother.dish"));
            Dish d = mother != null ? mother : forLand(land);
            TownWays.note(id, "dish", d.name());
            String from = TownWays.motherName(id);
            if (mother != null) {
                Staple s = staple(land, d.kind);
                if (!s.word().equals(d.main()) && d != forLand(land)) {
                    TownWays.note(id, "dish.take", s.word());
                    Villages.tell(id, day, "the settlers brought " + TownWays.of(from == null ? "their old town" : from) + " " + d.words
                        + " with them; here it is a " + TownWays.landWord(land) + " " + d.words + ", made with " + s.word());
                } else {
                    Villages.tell(id, day, "the settlers brought " + TownWays.of(from == null ? "their old town" : from) + " " + d.words
                        + " with them, made the old way");
                }
            } else {
                Villages.tell(id, day, "the cooks of " + Villages.name(id) + " took to making " + d.words + ", of what the land gives");
            }
        }
        Dish second = secondFrom(id);
        Dish had = secondOf(id);
        if (second != had) {
            TownWays.note(id, "dish2", second == null ? null : second.name());
            if (second != null) Villages.tell(id, day, "with so much " + second.main() + " coming in, the town took to making "
                + second.words + " as well");
        }
    }

    /** A second dish from what its folk bring in most, if two or more hands bring it in and it is not the first. */
    @Nullable
    static Dish secondFrom(UUID village) {
        Map<AssistantEntity.StationTask, Integer> t = TownWays.trades(village);
        Dish first = dishOf(village);
        Dish best = null;
        int most = 1;
        for (Map.Entry<AssistantEntity.StationTask, Integer> e : t.entrySet()) {
            Dish d = switch (e.getKey()) {
                case FISH -> Dish.FISH_STEW;
                case HUNT -> Dish.GAME_PIE;
                case RANCH -> Homeland.of(village) == Homeland.Land.SAVANNA ? Dish.SPICED_MUTTON : Dish.HOTPOT;
                default -> null;
            };
            if (d == null || d == first) continue;
            if (e.getValue() > most) {
                most = e.getValue();
                best = d;
            }
        }
        return best;
    }

    /** Tests: this town's dish set so (and its colony's take, if any), as if it had chosen it. */
    public static void setForTests(UUID village, Dish d, @Nullable String take) {
        TownWays.note(village, "dish", d.name());
        TownWays.note(village, "dish.take", take);
    }

    // ------------------------------------------------------------------ the cook

    /** How many of its dish the town likes to have by: four, and one more for every four folk, up to twelve. */
    static int kept(UUID village) {
        return Math.max(4, Math.min(12, 4 + Villages.headcount(village) / 4));
    }

    static int stock(ServerLevel level, UUID village, Dish d) {
        Item it = d.item();
        return Market.stock(level, village, s -> s.is(it));
    }

    /**
     * The cook's turn at the town's dish (Crafts.now, before the café's menu): its own dish while the stores are short
     * of it, then the second dish to half that. Never on short commons: the larder's makings are bread first. What it
     * made, in words, or null.
     */
    @Nullable
    static String cook(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.stationTask() != AssistantEntity.StationTask.COOK || !TownWays.settled(level, v.id())) return null;
        return cookNow(level, v, f);
    }

    @Nullable
    private static String cookNow(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        Leader.Plan plan = Leader.plan(id);
        if (plan == Leader.Plan.FAMINE || plan == Leader.Plan.SHORT) return null;
        Dish d = dishOf(id);
        if (d == null) return null;
        int kept = kept(id);
        if (stock(level, id, d) < kept) {
            ItemStack made = make(level, v, d, f);
            if (!made.isEmpty()) return words(made, d) + ", " + TownWays.of(Villages.name(id)) + " own";
        }
        Dish second = secondOf(id);
        if (second != null && stock(level, id, second) < kept / 2) {
            ItemStack made = make(level, v, second, f);
            if (!made.isEmpty()) return words(made, second);
        }
        return null;
    }

    private static String words(ItemStack made, Dish d) {
        return made.getCount() == 1 ? "a " + d.words : made.getCount() + " " + d.plural;
    }

    /**
     * One batch of a dish cooked into the stores, out of the stores: by the game's own recipe the whole way (Bench), or,
     * for a colony's take on its mother's dish, of the same makings with its own land's staple in place of the main.
     * What was made, or nothing (and nothing taken).
     */
    public static ItemStack make(ServerLevel level, Villages.Village v, Dish d, @Nullable VillageFolkEntity f) {
        UUID id = v.id();
        Bench.Hand hand = Bench.handOf(level, v, f, "cafe");
        String take = d == dishOf(id) ? takeOf(id) : null;
        if (take == null) {
            Bench.Plan p = Bench.plan(level, v, d.item(), 1, hand);
            if (!p.ok()) return ItemStack.EMPTY;
            return Bench.make(level, v, p, f, hand);
        }
        // A colony's take: the same makings, its land's staple in place of the main of them.
        Homeland.Land land = Homeland.of(id);
        List<Bench.Want> wants = new ArrayList<>(d.makings());
        wants.set(0, staple(land, d.kind).want());
        Bench.Plan p = Bench.plan(level, v, wants, hand);
        if (!p.ok() || !Bench.take(level, v, p, f)) return ItemStack.EMPTY;
        if (wants.get(0).kinds().contains(Items.HONEY_BOTTLE)) Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
        ItemStack made = new ItemStack(d.item(), d.batch);
        String from = TownWays.motherName(id);
        made.set(DataComponents.LORE, ItemLore.EMPTY.withLineAdded(Component.literal("A " + TownWays.landWord(land) + " take on "
            + (from == null ? "the old " : TownWays.of(from) + " ") + d.words + ", made with " + take).withStyle(ChatFormatting.GOLD)));
        CompoundTag tag = new CompoundTag();
        tag.putString("mca_take", take);
        made.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        Crafts.store(level, v, made.copy());
        return made;
    }

    /** Tests: one batch of this town's dish cooked now, out of its stores (by its cook, if it has one). */
    public static ItemStack makeForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity cook) {
        Dish d = dishOf(v.id());
        return d == null ? ItemStack.EMPTY : make(level, v, d, cook);
    }

    /** Tests: the cook's turn at the town's dish, now (what it made, or null). */
    @Nullable
    public static String cookForTests(ServerLevel level, Villages.Village v, VillageFolkEntity cook) {
        return cookNow(level, v, cook);
    }

    /** Tests: the town's table chosen afresh now (its dish forgotten first: a colony's mother's taken up, if it has one). */
    public static Dish chooseForTests(ServerLevel level, Villages.Village v) {
        TownWays.note(v.id(), "dish", null);
        TownWays.note(v.id(), "dish.take", null);
        choose(level, v, level.getDayTime() / 24000L);
        return dishOf(v.id());
    }

    /**
     * The town's own minute (TownWays, every half minute or so): a town with no cook has a hand at its works cook its
     * dish in the afternoon of a feast day (the weekly feast, Founding Day, its own festival), out of the stores, while
     * it has fewer than four; and the house dish's board kept on the tavern's bar.
     */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        Dish d = dishOf(id);
        if (d == null || !TownWays.settled(level, id)) return;
        tavernSign(level, v, false);
        if (t < 8000L || t > 11400L) return;
        boolean cook = false;
        for (AssistantEntity a : Villages.folkOf(id)) if (a.stationTask() == AssistantEntity.StationTask.COOK) { cook = true; break; }
        if (cook) return;
        boolean feast = Gatherings.tonight(id, day) == Gatherings.Kind.FEAST || FoundingDay.today(id, day) || TownFeast.today(id, day);
        if (!feast || stock(level, id, d) >= 4) return;
        Leader.Plan plan = Leader.plan(id);
        if (plan == Leader.Plan.FAMINE || plan == Leader.Plan.SHORT) return;
        if (!Bench.plan(level, v, d.item(), 1, Bench.handOf(level, v, null, null)).ok() && takeOf(id) == null) return;
        if (!TownJobs.atWork(level, v, "kitchen", v.centre(), "cooking the town's " + d.words + " for the feast")) return;
        ItemStack made = make(level, v, d, null);
        if (!made.isEmpty()) level.playSound(null, v.centre(), SoundEvents.SMOKER_SMOKE, SoundSource.NEUTRAL, 0.7F, 1.0F);
    }

    // ------------------------------------------------------------------ what it does for the folk

    /** The day each folk last had its town's dish, and how (1 at a meal, 2 a delicacy from away, 3 at a feast). */
    private static final Map<UUID, long[]> ATE = new ConcurrentHashMap<>();
    /** The town a delicacy came from, by who ate it (for its words). */
    private static final Map<UUID, String> FROM = new ConcurrentHashMap<>();

    static void resetForTests() {
        ATE.clear();
        FROM.clear();
    }

    /** Is this one of the towns' dishes (the eight, or the desert's rabbit stew)? */
    public static boolean isDish(ItemStack s) {
        return !s.isEmpty() && (s.getItem() instanceof DishItem || s.is(Items.RABBIT_STEW));
    }

    /** Which dish this is, or null. */
    @Nullable
    static Dish dishFor(ItemStack s) {
        if (s.isEmpty()) return null;
        for (Dish d : Dish.values()) if (s.is(d.item())) return d;
        return null;
    }

    /** The town a stack was carried from, as a delicacy (Caravans), or null. */
    @Nullable
    static String from(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag t = cd.copyTag();
        return t.contains("mca_from") ? t.getString("mca_from") : null;
    }

    /**
     * Something eaten (VillageFolkEntity.ateFood): one of the towns' dishes is remembered for the folk's mood: its own
     * town's dish a taste of home, another town's a delicacy. The bowl back into its pack, as a player's comes back.
     */
    public static void ate(VillageFolkEntity f, ItemStack meal) {
        Dish d = dishFor(meal);
        UUID id = f.ownerId();
        if (d == null || id == null || f.level().isClientSide) return;
        long day = f.level().getDayTime() / 24000L;
        String from = from(meal);
        boolean own = d == dishOf(id) && (from == null || from.equals(Villages.name(id)));
        if (own) mark(f, day, 1);
        else {
            mark(f, day, 2);
            FROM.put(f.getUUID(), from != null ? from : "away");
            f.persona().remember(day, "I had " + (from != null ? TownWays.of(from) + " " : "a ") + d.words + ", a real treat", 2);
        }
        if (d.bowl()) {
            ItemStack left = f.insertItem(new ItemStack(Items.BOWL));
            if (!left.isEmpty() && f.level() instanceof ServerLevel server) {
                Villages.Village v = Villages.get(id);
                if (v != null) Crafts.store(server, v, left);
            }
        }
    }

    private static void mark(VillageFolkEntity f, long day, int how) {
        long[] was = ATE.get(f.getUUID());
        if (was != null && was[0] == day && was[1] >= how) return;
        ATE.put(f.getUUID(), new long[]{ day, how });
        if (ATE.size() > 8192) ATE.clear();
    }

    /**
     * A plate at the feast (Assemblies' feast and Founding Day, the harvest festival, the town's own festival): the
     * town's own dish out of the stores, before anything else, while there is any. Empty if the stores have none (the
     * feast takes whatever else they hold, as it always did). Eaten there and then: the bowl back into the stores.
     */
    public static ItemStack feast(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Dish d = dishOf(v.id());
        if (d == null) return ItemStack.EMPTY;
        Item it = d.item();
        ItemStack s = Crafts.takeOne(level, v, x -> x.is(it));
        if (s.isEmpty()) {
            Dish second = secondOf(v.id());
            if (second == null) return ItemStack.EMPTY;
            Item two = second.item();
            s = Crafts.takeOne(level, v, x -> x.is(two));
            if (s.isEmpty()) return ItemStack.EMPTY;
        }
        long day = level.getDayTime() / 24000L;
        mark(f, day, 3);
        f.meals().ate(level.getGameTime(), s.getHoverName().getString());
        if (dishFor(s) != null && dishFor(s).bowl()) Crafts.store(level, v, new ItemStack(Items.BOWL));
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Nobody makes " + d.words + " like we do!", "Ah, " + d.words + ". Home, that is.",
                "Pass the " + d.words + "!", "Is there more " + d.words + "?"));
        }
        f.persona().remember(day, "we had our own " + d.words + " at the feast", 2);
        return s;
    }

    /** Tests: this folk at the feast now (its town's dish, if the stores have one). */
    public static ItemStack feastForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        ItemStack s = feast(level, v, f);
        f.refreshMood();
        return s;
    }

    /**
     * What the town's dish does for a folk's mood (VillageFolkEntity.refreshMood): its own dish at a feast, today or
     * yesterday, six; at a meal, three; another town's, four.
     */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        long[] a = ATE.get(f.getUUID());
        if (a == null || day - a[0] > 1) return m;
        int how = (int) a[1];
        int lift = how == 3 ? 6 : how == 2 ? 4 : 3;
        why.add(new Object[]{ how == 3 ? "townfeast" : how == 2 ? "delicacy" : "homecooking", lift });
        return m + lift;
    }

    /** The folk's own words for it (FolkTalk.reason). */
    static String moodWords(VillageFolkEntity f, String why) {
        Dish d = dishOf(f.ownerId());
        String dish = d == null ? "our own dish" : d.words;
        return switch (why) {
            case "townfeast" -> "We had " + dish + " at the feast. Nothing like it.";
            case "delicacy" -> "I had " + TownWays.of(FROM.getOrDefault(f.getUUID(), "another town's")).replace("away's", "a") + " cooking — a real treat.";
            default -> "I had " + dish + " for my dinner. A taste of home.";
        };
    }

    /** The card's word for it: what of the town's table it had today. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        long[] a = ATE.get(f.getUUID());
        if (a == null || f.level().getDayTime() / 24000L - a[0] > 1) return null;
        Dish d = dishOf(f.ownerId());
        return switch ((int) a[1]) {
            case 3 -> "had " + (d == null ? "its town's dish" : d.words) + " at the feast";
            case 2 -> "had a delicacy from " + FROM.getOrDefault(f.getUUID(), "away");
            default -> "had " + (d == null ? "its town's dish" : d.words) + " today";
        };
    }

    // ------------------------------------------------------------------ the café, the tavern, the stores

    /**
     * What the stores can let go of a dish (Budget.spare): all but two, kept back for the feast. A dish is the cook's
     * to sell, not the larder's to hoard, or it would never reach the café's counter.
     */
    public static int spare(ServerLevel level, UUID village, ItemStack s) {
        Item it = s.getItem();
        return Math.max(0, Market.stock(level, village, x -> x.is(it) && ItemStack.isSameItemSameComponents(x, s)) - 2);
    }

    /** The café's menu with the town's own dish first (the counter by the door) and its second after it. */
    public static List<ItemStack> ownFirst(UUID village, List<ItemStack> menu) {
        Dish d = dishOf(village), second = secondOf(village);
        if (d == null || menu.isEmpty()) return menu;
        List<ItemStack> out = new ArrayList<>(menu);
        out.sort((a, b) -> Integer.compare(rank(b, d, second), rank(a, d, second)));
        return out;
    }

    private static int rank(ItemStack s, Dish d, @Nullable Dish second) {
        if (s.is(d.item())) return 2;
        return second != null && s.is(second.item()) ? 1 : 0;
    }

    /** The bar's barrel in the tavern, where the house dish's board stands on top (the round's board hangs on its front). */
    @Nullable
    static BlockPos bar(Ledger.Building tav) {
        Direction back = tav.facing(), right = back.getClockWise();
        return tav.anchor().relative(right, -2);
    }

    /** "The house dish": the first line of the board on the tavern's bar. */
    static final String HOUSE = "The house dish";

    /** Is this the house dish's board? */
    static boolean isHouseSign(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof StandingSignBlock)) return false;
        return level.getBlockEntity(pos) instanceof SignBlockEntity sign && HOUSE.equals(sign.getFrontText().getMessage(0, false).getString());
    }

    /**
     * The house dish's board on the tavern's bar: a sign standing on the bar's barrel, out of the stores (or two planks),
     * unless {@code free}; its words kept up to date with the dish and its price. False if there is nowhere for it.
     */
    public static boolean tavernSign(ServerLevel level, Villages.Village v, boolean free) {
        Dish d = dishOf(v.id());
        Ledger.Building tav = Tavern.of(v.id());
        if (d == null || tav == null || !level.isLoaded(tav.anchor())) return false;
        BlockPos barrel = bar(tav);
        if (barrel == null || !level.getBlockState(barrel).is(Blocks.BARREL)) return false;
        BlockPos at = barrel.above();
        BlockState s = level.getBlockState(at);
        if (!(s.getBlock() instanceof StandingSignBlock)) {
            if (!s.isAir()) return false;
            if (!free && !Crafts.sign(level, v)) return false;
            Direction faces = tav.facing().getOpposite();
            level.setBlock(at, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
                RotationSegment.convertToSegment(faces)), 3);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            String name = new ItemStack(d.item()).getHoverName().getString();
            String[] lines = { HOUSE, name.length() > 15 ? d.words.length() > 15 ? d.words.substring(0, 15) : d.words : name,
                price(d) + (price(d) == 1 ? " coin" : " coins"), "(right-click)" };
            SignText now = sign.getFrontText();
            boolean same = true;
            for (int i = 0; i < 4; i++) if (!now.getMessage(i, false).getString().equals(lines[i])) same = false;
            if (!same) {
                SignText text = new SignText();
                for (int i = 0; i < 4; i++) text = text.setMessage(i, Component.literal(lines[i]));
                sign.setText(text, true);
                sign.setText(text, false);
                sign.setWaxed(true);
            }
        }
        return true;
    }

    /** What a portion of the house dish costs at the bar: its worth and a little over, two coins at the least. */
    static int price(Dish d) {
        return Math.max(2, (int) Math.ceil(Prices.each(d.item()) * 1.25));
    }

    /**
     * A player orders the house dish at the tavern's bar: a portion out of the stores, paid for into the treasury, and
     * handed over. What to tell them.
     */
    public static String order(ServerLevel level, Villages.Village v, Player p) {
        UUID id = v.id();
        Dish d = dishOf(id);
        if (d == null) return "The kitchen has nothing of its own yet.";
        if (Standing.of(id, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) return "Nobody here will serve you.";
        if (Laws.banished(id, p.getUUID(), level.getDayTime() / 24000L)) return "You're banished from " + Villages.name(id) + ". Nobody will serve you.";
        Item it = d.item();
        int price = price(d);
        if (Citizens.is(id, p.getUUID())) price = Math.max(1, price - 1);
        if (Market.stock(level, id, s -> s.is(it)) < 1) {
            return "No " + d.words + " left tonight — the cook's making more.";
        }
        if (p.isShiftKeyDown()) return d.words + ": " + price + (price == 1 ? " coin" : " coins") + ". Right-click to order.";
        int coins = Market.coinsHeld(p);
        if (coins < price) return "A " + d.words + " is " + price + " coins. You have " + coins + ".";
        ItemStack s = Crafts.takeOne(level, v, x -> x.is(it));
        if (s.isEmpty()) return "No " + d.words + " left tonight.";
        Market.payOut(p, price);
        Ledger.addCoins(id, price);
        Economy.sold(id, price);
        Stockroom.sold(level, id, Stockroom.Seller.TAVERN, s, 1, price);
        if (!p.getInventory().add(s)) p.drop(s, false);
        level.playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.1F);
        return "A " + d.words + ", " + TownWays.of(Villages.name(id)) + " own, for " + price + (price == 1 ? " coin" : " coins") + ". Enjoy it!";
    }

    /**
     * A caravan setting out (Caravans): a couple of the town's dish on the carrier's back, for a town whose own dish is
     * not the same (or is, made its own way: a colony's take on its mother's dish has the old way as a treat), while the
     * stores have more than the feast's two: marked as from here, sold there off the caravan as any good is. How many
     * went.
     */
    public static int packDelicacy(ServerLevel level, Villages.Village from, VillageFolkEntity carrier, UUID to) {
        Dish d = dishOf(from.id());
        if (d == null || d == dishOf(to) && takeOf(to) == null) return 0;
        Item it = d.item();
        int can = Math.min(2, Market.stock(level, from.id(), s -> s.is(it) && from(s) == null) - 2);
        if (can <= 0) return 0;
        int n = 0;
        for (int i = 0; i < can; i++) {
            ItemStack s = Crafts.takeOne(level, from, x -> x.is(it) && from(x) == null);
            if (s.isEmpty()) break;
            ItemStack lot = s.copyWithCount(1);
            CompoundTag tag = new CompoundTag();
            CustomData had = lot.get(DataComponents.CUSTOM_DATA);
            if (had != null) tag = had.copyTag();
            tag.putString("mca_from", Villages.name(from.id()));
            lot.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            ItemLore lore = lot.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
            lot.set(DataComponents.LORE, lore.withLineAdded(Component.literal("A delicacy from " + Villages.name(from.id()))
                .withStyle(ChatFormatting.GOLD)));
            ItemStack left = carrier.insertGiven(lot);
            if (!left.isEmpty()) {
                Crafts.store(level, from, s);
                break;
            }
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ words

    /** The town's table, for its page. */
    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        Dish d = dishOf(village);
        if (d == null) {
            out.add("No dish of its own yet: its cooks wait on its land to be known.");
            return out;
        }
        out.add("Its own dish: " + dishWords(village) + " (" + new ItemStack(d.item()).getHoverName().getString() + "), the cook's first care: "
            + kept(village) + " kept in the stores, first on the café's counter and on the tavern's board at " + price(d) + " coins.");
        Dish second = secondOf(village);
        if (second != null) out.add("And " + second.words + ", of all the " + second.main() + " its hands bring in.");
        out.add("Served first at every feast: our own dish lifts a folk's spirits for a day or two; a neighbour's is a treat.");
        return out;
    }

    /** "What do you eat here?" */
    static String talk(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Dish d = dishOf(id);
        if (id == null || d == null) return "Whatever's in the stores, same as anybody.";
        String take = takeOf(id);
        String mother = TownWays.motherName(id);
        String s = take != null && mother != null
            ? "Our " + d.words + ". The settlers brought it from " + mother + ", but we make it with " + take + " here — better, if you ask me."
            : "Our " + d.words + "! Nobody makes it like " + Villages.name(id) + ".";
        Dish second = secondOf(id);
        if (second != null) s += " And " + second.words + ", with all the " + second.main() + " we get.";
        if (Tavern.of(id) != null) s += " Ask at the tavern's bar for a portion.";
        else if (Villages.builtAt(id, "cafe") != null) s += " The café has it on the counter by the door.";
        return s;
    }
}
