package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [guard-kit] The watch's kit: the best armour and the best blade the town can make, issued to every guard by
 * the town, and paid for by the town. A guard never pays a coin for its kit.
 * <ul>
 * <li><b>Leather in the Stone Age.</b> Before there is iron the watch goes in leather: a cap of five, a tunic of
 *     eight, trousers of seven and boots of four, the game's own counts, cut by the tailor out of the stores'
 *     leather (by the smith when the town has no tailor, by the shop's workshop once the shop stands). The
 *     stores keep as many of each piece as there are guards who wear worse; and a little leather is always
 *     kept back for the books, the gazette and the frames.</li>
 * <li><b>Iron in the Iron Age.</b> The smith's iron (Crafts.forge), as ever, the age's saving kept.</li>
 * <li><b>Diamond in the Diamond Age.</b> Once the age has come to diamond, the town is not saving its
 *     diamonds for the next age, and the miners have their diamond pick, the smith forges the watch a diamond
 *     sword first and then the chestplate, the leggings, the helmet and the boots, a few diamonds always kept
 *     back. In the Nether Age it takes them on to netherite at the smithing table (Bench), with the stores'
 *     ingot and template. What the maker's hand cannot make yet waits for it (Craftsmanship).</li>
 * <li><b>The best goes on.</b> One way of fitting a guard out, whether the guard goes to the stores itself or
 *     the shop's round fits the watch (Workshop.outfit): each slot the best armour the stores hold that beats
 *     what it wears (netherite, diamond, iron, chainmail, leather, by what the piece is worth as armour); the
 *     blade with the best bite; a bow and a few arrows; a shield. The old piece goes back into the stores for
 *     the next guard or the militia (a guard's own wooden or stone blade stays in its pack).</li>
 * <li><b>No rank on the town's kit.</b> A guard new to the watch wears and wields whatever the town issues
 *     it: it is the town's kit, not a tool it earned the skill for (VillageFolkEntity.mayUseTier).</li>
 * <li><b>The town pays.</b> Every piece is made out of the stores' own leather, iron and diamonds by the
 *     town's makers, and issued free. The shop does not sell the watch its blade, and the store's takings are
 *     the treasury's (StoreFloor, ShopStock), so no coin need change hands between the town and its own shop:
 *     what the kit costs is booked here, at the town's prices (PriceIndex), and shown with the town's money
 *     (Economy.page), on the board, on each guard's card and in /village watch.</li>
 * </ul>
 * The chronicle tells when the watch first goes into iron, into diamond and into netherite.
 */
public final class WatchKit {

    private WatchKit() {}

    // ------------------------------------------------------------------ the ladder

    /** What the watch's kit is made of, worst first. */
    public enum Metal {
        LEATHER("leather", Items.LEATHER), IRON("iron", Items.IRON_INGOT), DIAMOND("diamond", Items.DIAMOND),
        NETHERITE("netherite", Items.NETHERITE_INGOT);

        public final String word;
        final Item stuff;

        Metal(String word, Item stuff) {
            this.word = word;
            this.stuff = stuff;
        }
    }

    /** The pieces of it, in the order the smith makes the diamond: the blade first, then the chest down. */
    enum Kind {
        SWORD(EquipmentSlot.MAINHAND, 2), CHEST(EquipmentSlot.CHEST, 8), LEGS(EquipmentSlot.LEGS, 7),
        HEAD(EquipmentSlot.HEAD, 5), FEET(EquipmentSlot.FEET, 4);

        final EquipmentSlot slot;
        /** Leather, ingots or diamonds a piece takes, as a player makes it. */
        final int takes;

        Kind(EquipmentSlot slot, int takes) {
            this.slot = slot;
            this.takes = takes;
        }

        boolean armour() { return this != SWORD; }
    }

    /** The armour slots, head to foot, in the order a guard is fitted. */
    private static final Kind[] SUIT = { Kind.HEAD, Kind.CHEST, Kind.LEGS, Kind.FEET };

    /** The piece of this kind in this metal (no leather blade). */
    @Nullable
    static Item piece(Kind k, Metal m) {
        return switch (k) {
            case SWORD -> switch (m) {
                case LEATHER -> null;
                case IRON -> Items.IRON_SWORD;
                case DIAMOND -> Items.DIAMOND_SWORD;
                case NETHERITE -> Items.NETHERITE_SWORD;
            };
            case HEAD -> switch (m) {
                case LEATHER -> Items.LEATHER_HELMET;
                case IRON -> Items.IRON_HELMET;
                case DIAMOND -> Items.DIAMOND_HELMET;
                case NETHERITE -> Items.NETHERITE_HELMET;
            };
            case CHEST -> switch (m) {
                case LEATHER -> Items.LEATHER_CHESTPLATE;
                case IRON -> Items.IRON_CHESTPLATE;
                case DIAMOND -> Items.DIAMOND_CHESTPLATE;
                case NETHERITE -> Items.NETHERITE_CHESTPLATE;
            };
            case LEGS -> switch (m) {
                case LEATHER -> Items.LEATHER_LEGGINGS;
                case IRON -> Items.IRON_LEGGINGS;
                case DIAMOND -> Items.DIAMOND_LEGGINGS;
                case NETHERITE -> Items.NETHERITE_LEGGINGS;
            };
            case FEET -> switch (m) {
                case LEATHER -> Items.LEATHER_BOOTS;
                case IRON -> Items.IRON_BOOTS;
                case DIAMOND -> Items.DIAMOND_BOOTS;
                case NETHERITE -> Items.NETHERITE_BOOTS;
            };
        };
    }

    /** Is a thing by this name something the town issues the watch: a piece of armour or a blade, of any metal? */
    static boolean kitPath(String path) {
        return path.endsWith("_helmet") || path.endsWith("_chestplate") || path.endsWith("_leggings") || path.endsWith("_boots")
            || path.endsWith("_sword");
    }

    /** Leather never cut into armour, and kept back for the trades that live on a piece at a time. */
    static final int LEATHER_KEPT = 4;
    /** Diamonds the smith keeps back from the watch's kit, as it does from its own forging. */
    static final int DIAMONDS_KEPT = 2;
    /** The arrows a guard with a bow is given, and how few it carries before it is given more. */
    static final int ARROWS = 16, ARROWS_LOW = 8;

    // ------------------------------------------------------------------ the watch, and how it is kitted

    /** The town's guards: grown, alive, and no showcase. */
    static List<VillageFolkEntity> watch(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity g && g.stationTask() == StationTask.GUARD && !g.isBaby() && g.isAlive() && !g.isShowcase()) {
                out.add(g);
            }
        }
        return out;
    }

    /** Is this a thing of this kind (a blade, or armour for the slot)? */
    static boolean fits(Kind k, ItemStack s) {
        if (s.isEmpty()) return false;
        if (k == Kind.SWORD) return s.getItem() instanceof SwordItem;
        return s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == k.slot;
    }

    /** How good a piece is as what it is, whatever its maker's hand made of it: its armour, or its bite (Workshop). */
    static int worth(Kind k, ItemStack s) {
        if (!fits(k, s)) return k == Kind.SWORD ? -1 : 0;
        return k == Kind.SWORD ? Workshop.blade(s) : Workshop.armour(s);
    }

    /** What a guard is fitted with by: what it is worth as armour or a blade first, and how long it lasts after. */
    static int score(Kind k, ItemStack s) {
        if (!fits(k, s)) return -1;
        return worth(k, s) * 1000 + Math.min(999, Math.max(0, s.getMaxDamage()));
    }

    /** What a guard's own piece is held at against the stores': a piece worn past use is beaten by a fresh one of the same. */
    static int held(Kind k, ItemStack s) {
        if (!fits(k, s)) return -1;
        return Toolrack.left(s) < Workshop.FIT ? worth(k, s) * 1000 - 1 : score(k, s);
    }

    /** What a guard wears (or carries, for the blade) of this kind, as armour or as a bite. */
    static int wears(VillageFolkEntity g, Kind k) {
        return k == Kind.SWORD ? Workshop.bestBlade(g) : worth(k, g.getItemBySlot(k.slot));
    }

    /**
     * How many more of this piece the watch wants made: the guards who wear worse than it, less the pieces as
     * good or better the stores already hold for them (fit to issue). The way the smith keeps its iron.
     */
    static int wanting(ServerLevel level, Villages.Village v, Kind k, Item item) {
        int want = worth(k, new ItemStack(item));
        int worse = 0;
        for (VillageFolkEntity g : watch(v.id())) if (wears(g, k) < want) worse++;
        if (worse == 0) return 0;
        int have = 0;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (fits(k, st) && Toolrack.left(st) >= Workshop.FIT && worth(k, st) >= want) have += st.getCount();
            }
        }
        return worse - have;
    }

    // ------------------------------------------------------------------ the makers

    /** What the town's makers have made for the watch today (by village): who, and what of what. */
    private static final Map<UUID, Deque<String>> MADE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> MADE_DAY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        MADE.clear();
        MADE_DAY.clear();
    }

    /** Is there a tailor in the town (the watch's leather is its work)? */
    static boolean hasTailor(@Nullable UUID village) {
        if (village == null) return false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() == StationTask.TAILOR && !a.isBaby() && a.isAlive()) return true;
        }
        return false;
    }

    /** Is the town putting this by for its age (Villages.needs)? */
    static boolean saving(ServerLevel level, UUID village, Villages.Task task) {
        for (Villages.Need n : Villages.needs(level, village)) if (n.task() == task) return true;
        return false;
    }

    /** Have the miners their diamond pick (one of them carries it, or it waits in the stores)? A town with no
     *  miner has nobody to wait for. */
    static boolean minersHaveTheirPick(ServerLevel level, Villages.Village v) {
        Predicate<ItemStack> pick = s -> s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE);
        boolean miners = false;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() != StationTask.MINE || a.isBaby()) continue;
            miners = true;
            if (a.countCarried(pick) > 0) return true;
        }
        return !miners || Crafts.stock(level, v, pick) > 0;
    }

    /** Leather kept back from the watch's kit: a little for whoever lives on a piece at a time, and one for each
     *  book the library is still short of (eight at the most). */
    static int leatherKept(ServerLevel level, Villages.Village v) {
        int books = Math.max(0, Crafts.booksWanted(level, v) - Crafts.stock(level, v, s -> s.is(Items.BOOK)));
        return LEATHER_KEPT + Math.min(8, books);
    }

    /** Tests: the leather kept back from the watch's kit just now. */
    public static int leatherKeptForTests(ServerLevel level, Villages.Village v) {
        return leatherKept(level, v);
    }

    /** The metals this maker works for the watch, best first: the tailor the leather; the smith the diamond and
     *  the netherite (its iron is its forging's), and the leather when the town has no tailor. */
    private static List<Metal> metalsOf(VillageFolkEntity f) {
        return switch (f.stationTask()) {
            case TAILOR -> List.of(Metal.LEATHER);
            case SMITH -> hasTailor(f.ownerId()) ? List.of(Metal.NETHERITE, Metal.DIAMOND)
                : List.of(Metal.NETHERITE, Metal.DIAMOND, Metal.LEATHER);
            default -> List.of();
        };
    }

    /**
     * Why the watch's piece of this kind in this metal may not be made now, in a few words, or null if it may:
     * the age not come to it, the town putting its diamonds by, the miners' pick first, or the maker's hand
     * ({@code skill} below nought: nobody's hand is asked after).
     */
    @Nullable
    static String waits(ServerLevel level, Villages.Village v, Kind k, Metal m, int skill) {
        Item it = piece(k, m);
        if (it == null) return "no such thing";
        Villages.Age age = Villages.ageOf(v.id());
        if (!Tiers.allows(level, age, it)) return Tiers.of(level, it).label + "'s work, and the town is in " + age.label;
        if (m == Metal.DIAMOND || m == Metal.NETHERITE) {
            if (saving(level, v.id(), Villages.Task.DIAMOND)) return "the town is putting its diamonds by for its age";
            if (!minersHaveTheirPick(level, v)) return "the miners' diamond pick comes first";
        }
        if (m == Metal.IRON && saving(level, v.id(), Villages.Task.IRON) && !WarFooting.ready(v.id())) {
            return "the town is putting its iron by for its age";
        }
        if (skill >= 0 && !Craftsmanship.canMake(skill, it)) return "level " + Craftsmanship.rung(it) + " work, and the maker is level " + skill;
        return null;
    }

    /**
     * A turn of a maker's work for the watch (Crafts: the tailor's, and the smith's after its forging): the first
     * piece, in the smith's order (the blade, then the chest down), that the watch wants more of, in the best
     * metal the maker may make it in and the stores can run to; a lesser metal meanwhile when the best is short.
     * Made the way a player makes it, out of the stores, as good as the maker's hand, and into the stores for
     * the watch to be fitted out of. Returns what it made, or null.
     */
    @Nullable
    public static String make(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        List<Metal> metals = metalsOf(f);
        if (metals.isEmpty() || watch(v.id()).isEmpty()) return null;
        int skill = f.veteranLevel();
        for (Kind k : Kind.values()) {
            for (Metal m : metals) {
                Item it = piece(k, m);
                if (it == null || waits(level, v, k, m, skill) != null) continue;
                if (wanting(level, v, k, it) <= 0) break;           // as good or better is had for every guard
                ItemStack made = makeOne(level, v, f, k, m, it, skill);
                if (made.isEmpty()) continue;                         // short of the makings: a lesser metal meanwhile
                String words = Crafts.named(made);
                noteMade(level, v.id(), f, words + (m == Metal.NETHERITE ? ", at the smithing table"
                    : ", of " + Bench.words(m.stuff, k.takes) + (k == Kind.SWORD ? " and a stick" : "")));
                return words + " for the watch";
            }
        }
        return null;
    }

    /** One piece made, out of the stores and into them: empty if the stores cannot run to it (nothing taken). */
    private static ItemStack makeOne(ServerLevel level, Villages.Village v, VillageFolkEntity f, Kind k, Metal m, Item it, int skill) {
        switch (m) {
            case LEATHER -> {
                if (Crafts.stock(level, v, s -> s.is(Items.LEATHER)) < k.takes + leatherKept(level, v)) return ItemStack.EMPTY;
                if (!Crafts.take(level, v, s -> s.is(Items.LEATHER), k.takes)) return ItemStack.EMPTY;
                ItemStack piece = new ItemStack(it);
                // From a hand of ten years and more, in the town's colour, as the tailor's boots are.
                if (skill >= 10) piece.set(DataComponents.DYED_COLOR, new DyedItemColor(Villages.colour(v.id()), false));
                piece = Craftsmanship.finish(level, piece, skill, f.displayNameCap());
                Crafts.store(level, v, piece.copy());
                return piece;
            }
            case DIAMOND -> {
                if (Crafts.stock(level, v, s -> s.is(Items.DIAMOND)) < k.takes + DIAMONDS_KEPT) return ItemStack.EMPTY;
                if (k == Kind.SWORD && !stick(level, v)) return ItemStack.EMPTY;
                if (!Crafts.take(level, v, s -> s.is(Items.DIAMOND), k.takes)) return ItemStack.EMPTY;
                ItemStack piece = Craftsmanship.finish(level, new ItemStack(it), skill, f.displayNameCap());
                Crafts.store(level, v, piece.copy());
                return piece;
            }
            case NETHERITE -> {
                // The smithing table's work, the whole way (Bench): an ingot of netherite, the template, and a
                // diamond piece made for it out of the stores' diamonds.
                Bench.Hand hand = Bench.handOf(level, v, f, VillageFolkEntity.buildingFor(f.stationTask()));
                Bench.Plan p = Bench.plan(level, v, it, 1, hand);
                return p.ok() ? Bench.make(level, v, p, f, hand) : ItemStack.EMPTY;
            }
            default -> {
                return ItemStack.EMPTY;                               // the iron is the smith's forging (Crafts.forge)
            }
        }
    }

    /** A stick for a blade: one the stores hold, or a plank split for it (the other half back into the stores). */
    private static boolean stick(ServerLevel level, Villages.Village v) {
        if (Crafts.stock(level, v, s -> s.is(Items.STICK)) >= 1) return Crafts.take(level, v, s -> s.is(Items.STICK), 1);
        if (!Crafts.planks(level, v, 1) || !Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), 1)) return false;
        Crafts.store(level, v, new ItemStack(Items.STICK));
        return true;
    }

    private static void noteMade(ServerLevel level, UUID village, VillageFolkEntity f, String what) {
        long day = level.getDayTime() / 24000L;
        Long was = MADE_DAY.put(village, day);
        Deque<String> d = MADE.computeIfAbsent(village, k -> new ArrayDeque<>());
        if (was != null && was != day) d.clear();
        d.addLast(f.displayNameCap() + " (" + f.stationTask().title.toLowerCase(Locale.ROOT) + "): " + what);
        while (d.size() > 12) d.pollFirst();
    }

    /** What the makers made for the watch today. */
    public static List<String> madeToday(ServerLevel level, UUID village) {
        Long day = MADE_DAY.get(village);
        Deque<String> d = MADE.get(village);
        if (d == null || day == null || day != level.getDayTime() / 24000L) return List.of();
        return List.copyOf(d);
    }

    // ------------------------------------------------------------------ fitting a guard out

    /**
     * A guard fitted out of the stores, the town paying: each armour slot given the best piece the stores hold
     * that beats what it wears (its old piece back into the stores), the best blade if it beats its own (an
     * iron blade or better it had goes back; its own wooden or stone one stays in its pack), a bow if it has
     * none and arrows to go with it, and a shield. The one way the watch is kitted out: the guard's own visit
     * to the stores and the shop's round (Workshop.outfit) both come here. Returns what it was given.
     */
    public static List<ItemStack> fit(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        List<ItemStack> given = new ArrayList<>();
        if (g.stationTask() != StationTask.GUARD || g.isBaby() || !g.isAlive() || g.isShowcase()) return given;
        UUID id = v.id();
        String who = g.displayNameCap();
        for (Kind k : SUIT) {
            ItemStack worn = g.getItemBySlot(k.slot);
            Workshop.Found f = Workshop.bestInStores(level, id, st -> fits(k, st), st -> score(k, st), held(k, worn));
            if (f == null) continue;
            ItemStack got = Workshop.takeOut(level, id, f, who);
            if (!worn.isEmpty()) Workshop.backIntoStores(level, v, worn.copy(), who);   // for the next guard, or the militia
            g.setItemSlot(k.slot, got);
            given.add(got.copy());
        }
        blade(level, v, g, given);
        // A bow, and arrows for it; a shield. Any guard may draw a bow when the bell rings, and raises the
        // shield once it has learnt to: the kit is the town's to give, whatever it can do with it yet.
        if (g.countCarried(AssistantEntity.RANGED_WEAPON) == 0 && !g.isPackFull()) {
            Workshop.Found f = Workshop.bestInStores(level, id, st -> st.getItem() instanceof BowItem,
                st -> Math.min(999, st.getMaxDamage()) + (st.isEnchanted() ? 1000 : 0), -1);
            if (f != null) handOver(level, v, g, Workshop.takeOut(level, id, f, who), given);
        }
        if (g.countCarried(AssistantEntity.RANGED_WEAPON) > 0) {
            int have = g.countCarried(s -> s.is(Items.ARROW));
            int n = have >= ARROWS_LOW ? 0 : Math.min(ARROWS - have, Crafts.stock(level, v, s -> s.is(Items.ARROW)));
            if (n > 0 && Crafts.take(level, v, s -> s.is(Items.ARROW), n)) handOver(level, v, g, new ItemStack(Items.ARROW, n), given);
        }
        if (g.countCarried(s -> s.getItem() instanceof ShieldItem) == 0 && !g.isPackFull()) {
            Workshop.Found f = Workshop.bestInStores(level, id, st -> st.getItem() instanceof ShieldItem,
                st -> Math.min(999, st.getMaxDamage()) + (st.isEnchanted() ? 1000 : 0), -1);
            if (f != null) handOver(level, v, g, Workshop.takeOut(level, id, f, who), given);
        }
        if (!given.isEmpty()) issued(level, v, g, given);
        return given;
    }

    /** The best blade the stores hold, if it bites harder than its own: where its own was (in its hand or its
     *  pack), its own back into the stores if it is iron or better; else into its pack. */
    private static void blade(ServerLevel level, Villages.Village v, VillageFolkEntity g, List<ItemStack> given) {
        UUID id = v.id();
        NonNullList<ItemStack> pack = g.getInventoryItems();
        ItemStack main = g.getMainHandItem();
        int mine = held(Kind.SWORD, main), at = fits(Kind.SWORD, main) ? -1 : -2;
        for (int i = 0; i < pack.size(); i++) {
            int sc = held(Kind.SWORD, pack.get(i));
            if (sc > mine) { mine = sc; at = i; }
        }
        Workshop.Found f = Workshop.bestInStores(level, id, st -> fits(Kind.SWORD, st), st -> score(Kind.SWORD, st), mine);
        if (f == null) return;
        ItemStack old = at == -1 ? main : at >= 0 ? pack.get(at) : ItemStack.EMPTY;
        boolean back = !old.isEmpty() && Workshop.blade(old) >= Workshop.blade(new ItemStack(Items.IRON_SWORD));
        if (!back && g.isPackFull()) return;
        ItemStack got = Workshop.takeOut(level, id, f, g.displayNameCap());
        if (back) {
            ItemStack was = old.copy();
            if (at == -1) g.setItemSlot(EquipmentSlot.MAINHAND, got);
            else pack.set(at, got);
            Workshop.backIntoStores(level, v, was, g.displayNameCap());
            given.add(got.copy());
        } else {
            handOver(level, v, g, got, given);
        }
    }

    /** Into its pack; what will not go in, back into the stores. */
    private static void handOver(ServerLevel level, Villages.Village v, VillageFolkEntity g, ItemStack got, List<ItemStack> given) {
        ItemStack left = g.insertGiven(got.copy());
        int in = got.getCount() - left.getCount();
        if (!left.isEmpty()) Workshop.backIntoStores(level, v, left, g.displayNameCap());
        if (in > 0) given.add(got.copyWithCount(in));
    }

    /** Every guard of the town fitted out now (the shop's round, and /village watch now): pieces given. */
    public static int fitAll(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (VillageFolkEntity g : watch(v.id())) n += fit(level, v, g).size();
        return n;
    }

    // ------------------------------------------------------------------ the town pays: the books

    /**
     * What was issued, booked: its worth at the town's prices into the kit's books (kept with the town), the
     * shop's books if the shop keeps the watch (Workshop.toTheWatch: gone to the watch, paid for by the village,
     * no coin), the chronicle the first time the watch goes into iron, diamond or netherite, and a word from
     * the guard.
     */
    static void issued(ServerLevel level, Villages.Village v, VillageFolkEntity g, List<ItemStack> given) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        double worth = 0;
        int pieces = 0;
        List<String> words = new ArrayList<>();
        for (ItemStack s : given) {
            worth += PriceIndex.each(level, id, s) * s.getCount();
            if (!s.is(Items.ARROW)) pieces++;
            words.add(Bench.words(s.getItem(), s.getCount()));
        }
        book(id, day, worth, pieces);
        String said = String.join(", ", words);
        if (Workshop.stands(id) || Workshop.keeper(id) != null) Workshop.toTheWatch(level, v, g, given, said);
        g.brain("issued by the town: " + said);
        for (ItemStack s : given) firstInto(level, v, g, s, day);
        // The shop's words for it (Workshop.outfit had them), and the stores' when there is no shop.
        boolean shop = Workshop.stands(id);
        if (level.getRandom().nextInt(2) == 0) {
            String one = given.size() == 1 ? words.get(0).replaceFirst("^(a|an|\\d+) ", "") : "kit";
            FolkTalk.speak(g, FolkTalk.pick(level.getRandom(), "New " + one + " from " + (shop ? "the shop" : "the stores") + ". Let them come.",
                (shop ? "The shop's" : "The town's") + " fitted me out: " + said + ". The village's coin well spent.",
                "The town's given me " + said + ". Not a coin of it mine — I'll earn it on the wall."));
        }
    }

    /** The first piece of iron, diamond or netherite the town issued its watch: a line in the chronicle. */
    private static void firstInto(ServerLevel level, Villages.Village v, VillageFolkEntity g, ItemStack s, long day) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (!kitPath(path)) return;
        String metal = path.startsWith("netherite_") ? "netherite" : path.startsWith("diamond_") ? "diamond"
            : path.startsWith("iron_") ? "iron" : null;
        if (metal == null) return;
        String key = "watchkit.into." + metal;
        String was = Ledger.note(v.id(), key);
        if (was != null && !was.isEmpty()) return;
        Ledger.note(v.id(), key, Long.toString(day));
        // A town whose watch was already in it before its books kept count (another guard has some): no news.
        for (VillageFolkEntity other : watch(v.id())) {
            if (other != g && other.countCarried(st -> BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().startsWith(metal + "_")
                    && kitPath(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath())) > 0) return;
        }
        Villages.tell(v.id(), day, "the watch went into " + metal + ": " + g.displayNameCap() + " was issued "
            + Bench.words(s.getItem(), 1) + " by the town");
        g.persona().remember(day, "The town issued me " + Bench.words(s.getItem(), 1) + ", the watch's first in " + metal, 4);
    }

    /** The kit's books, kept with the town: all told (hundredths of a coin, and pieces), and the last days'. */
    private static final String TOTAL = "watchkit.total", DAYS = "watchkit.days";
    private static final int DAYS_KEPT = 8;

    private static void book(UUID village, long day, double worth, int pieces) {
        long cents = Math.round(worth * 100);
        long[] all = total(village);
        Ledger.note(village, TOTAL, (all[0] + cents) + "|" + (all[1] + pieces));
        List<long[]> days = days(village);
        if (!days.isEmpty() && days.get(days.size() - 1)[0] == day) {
            days.get(days.size() - 1)[1] += cents;
            days.get(days.size() - 1)[2] += pieces;
        } else {
            days.add(new long[]{ day, cents, pieces });
        }
        while (days.size() > DAYS_KEPT) days.remove(0);
        StringBuilder sb = new StringBuilder();
        for (long[] d : days) sb.append(sb.length() == 0 ? "" : ";").append(d[0]).append(':').append(d[1]).append(':').append(d[2]);
        Ledger.note(village, DAYS, sb.toString());
    }

    /** All told: {hundredths of a coin, pieces}. */
    private static long[] total(UUID village) {
        String s = Ledger.note(village, TOTAL);
        if (s == null || s.isEmpty()) return new long[]{ 0, 0 };
        String[] p = s.split("\\|");
        try {
            return new long[]{ Long.parseLong(p[0]), p.length > 1 ? Long.parseLong(p[1]) : 0 };
        } catch (NumberFormatException e) {
            return new long[]{ 0, 0 };
        }
    }

    /** The last days': {day, hundredths, pieces}, oldest first. */
    private static List<long[]> days(UUID village) {
        List<long[]> out = new ArrayList<>();
        String s = Ledger.note(village, DAYS);
        if (s == null || s.isEmpty()) return out;
        for (String e : s.split(";")) {
            String[] p = e.split(":");
            if (p.length < 3) continue;
            try {
                out.add(new long[]{ Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]) });
            } catch (NumberFormatException ignored) {
                // a day the books cannot read: left out
            }
        }
        return out;
    }

    /** What the kit has cost the town since this day, in whole coins. */
    private static int since(UUID village, long fromDay) {
        long cents = 0;
        for (long[] d : days(village)) if (d[0] >= fromDay) cents += d[1];
        return (int) Math.round(cents / 100.0);
    }

    /** Tests: what the kit has cost the town all told, {whole coins, pieces}. */
    public static int[] costForTests(UUID village) {
        long[] t = total(village);
        return new int[]{ (int) Math.round(t[0] / 100.0), (int) t[1] };
    }

    // ------------------------------------------------------------------ telling it

    /** What it is made of, in a word: "iron", "diamond", "leather" (by the start of its name). */
    private static String metalOf(ItemStack s) {
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int cut = path.indexOf('_');
        String m = cut < 0 ? path : path.substring(0, cut);
        return m.equals("golden") ? "gold" : m;
    }

    /** The ladder, best first, for how a guard is said to be dressed. */
    private static final List<String> LADDER = List.of("netherite", "diamond", "iron", "chainmail", "gold", "turtle", "leather");

    /** What a guard is in: the metal most of its armour is, the better on a tie; or "none" with no armour. */
    static String suitOf(VillageFolkEntity g) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (Kind k : SUIT) {
            ItemStack s = g.getItemBySlot(k.slot);
            if (s.getItem() instanceof ArmorItem) n.merge(metalOf(s), 1, Integer::sum);
        }
        String best = "none";
        int most = 0;
        for (String m : LADDER) {
            int c = n.getOrDefault(m, 0);
            if (c > most) { most = c; best = m; }
        }
        for (Map.Entry<String, Integer> e : n.entrySet()) if (!LADDER.contains(e.getKey()) && e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        return best;
    }

    /** "4 guards, 3 in iron, 1 in leather": the watch, and what it is in. */
    static String watchLine(List<VillageFolkEntity> watch) {
        Map<String, Integer> in = new LinkedHashMap<>();
        for (VillageFolkEntity g : watch) in.merge(suitOf(g), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        List<String> order = new ArrayList<>(LADDER);
        for (String m : in.keySet()) if (!order.contains(m) && !m.equals("none")) order.add(m);
        order.add("none");
        for (String m : order) {
            Integer c = in.get(m);
            if (c == null) continue;
            parts.add(c + (m.equals("none") ? " without armour" : " in " + m));
        }
        return watch.size() + (watch.size() == 1 ? " guard" : " guards") + (parts.isEmpty() ? "" : ", " + String.join(", ", parts));
    }

    /** The board's line: "The watch: 4 guards, 3 in iron, 1 in leather." Null with no watch. */
    @Nullable
    public static String boardLine(UUID village) {
        List<VillageFolkEntity> watch = watch(village);
        return watch.isEmpty() ? null : "The watch: " + watchLine(watch) + ".";
    }

    /** What a guard has of the town's kit, in a line: "iron helmet, iron chestplate, ... diamond sword". */
    static String kitWords(VillageFolkEntity g) {
        List<String> out = new ArrayList<>();
        for (Kind k : SUIT) {
            ItemStack s = g.getItemBySlot(k.slot);
            if (!s.isEmpty()) out.add(name(s));
        }
        ItemStack blade = ItemStack.EMPTY;
        int best = -1;
        for (ItemStack s : g.getInventoryItems()) if (fits(Kind.SWORD, s) && score(Kind.SWORD, s) > best) { best = score(Kind.SWORD, s); blade = s; }
        if (fits(Kind.SWORD, g.getMainHandItem()) && score(Kind.SWORD, g.getMainHandItem()) > best) blade = g.getMainHandItem();
        if (!blade.isEmpty()) out.add(name(blade));
        if (g.countCarried(s -> s.getItem() instanceof BowItem) > 0) out.add("bow");
        int arrows = g.countCarried(s -> s.is(Items.ARROW));
        if (arrows > 0) out.add(arrows + (arrows == 1 ? " arrow" : " arrows"));
        if (g.countCarried(s -> s.getItem() instanceof ShieldItem) > 0) out.add("shield");
        return String.join(", ", out);
    }

    private static String name(ItemStack s) {
        return s.getItem().getDescription().getString().toLowerCase(Locale.ROOT);
    }

    /** The guard's card: "iron helmet, iron chestplate, ... diamond sword: issued by the town". Null for anybody else. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.GUARD || f.isBaby()) return null;
        String kit = kitWords(f);
        return kit.isEmpty() ? "none yet: the town issues it as its makers make it, free"
            : kit + ": issued by the town, not a coin of it from its own purse";
    }

    /** The town's money (Economy.page): what the watch's kit has cost it, in kind. Empty before anything is issued. */
    public static String economyLine(ServerLevel level, UUID village) {
        long[] all = total(village);
        if (all[1] <= 0 && all[0] <= 0) return "";
        int week = since(village, level.getDayTime() / 24000L - 6);
        return "\nThe watch's kit, paid for by the town: " + all[1] + (all[1] == 1 ? " piece" : " pieces") + " issued, "
            + Math.round(all[0] / 100.0) + " coins' worth of the stores' leather, iron and diamonds at the town's prices ("
            + week + " this week), in kind: no coin changes hands, and no guard pays a coin for its kit.";
    }

    /**
     * What is wanted for the watch, kind by kind, in the best the age allows: how many, whose work, and what it
     * waits on ("2 diamond chestplates: Bram the smith; waiting: the town is putting its diamonds by for its age").
     */
    static List<String> orders(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        if (watch(id).isEmpty()) return out;
        Villages.Age age = Villages.ageOf(id);
        for (Kind k : Kind.values()) {
            Metal best = null;
            for (int i = Metal.values().length - 1; i >= 0; i--) {
                Metal m = Metal.values()[i];
                Item it = piece(k, m);
                if (it != null && Tiers.allows(level, age, it)) { best = m; break; }
            }
            if (best == null) continue;
            Item it = piece(k, best);
            int n = wanting(level, v, k, it);
            if (n <= 0) continue;
            VillageFolkEntity maker = makerOf(id, best);
            String whose = maker != null ? maker.displayNameCap() + " the " + maker.stationTask().title.toLowerCase(Locale.ROOT)
                : Workshop.stands(id) ? "the shop's workshop" : "nobody yet (" + (best == Metal.LEATHER ? "a tailor or a smith" : "a smith") + ")";
            String waits = waits(level, v, k, best, maker == null ? -1 : maker.veteranLevel());
            out.add(Bench.words(it, n) + ": " + whose + (waits == null ? ", as the stores run to it" : "; waiting: " + waits));
        }
        int noBow = 0, noShield = 0;
        for (VillageFolkEntity g : watch(id)) {
            if (g.countCarried(AssistantEntity.RANGED_WEAPON) == 0) noBow++;
            if (g.countCarried(s -> s.getItem() instanceof ShieldItem) == 0) noShield++;
        }
        noBow -= Crafts.stock(level, v, s -> s.is(Items.BOW));
        noShield -= Crafts.stock(level, v, s -> s.getItem() instanceof ShieldItem);
        if (noBow > 0) out.add(Bench.words(Items.BOW, noBow) + ": the smith's fletching");
        if (noShield > 0) out.add(Bench.words(Items.SHIELD, noShield) + ": the smith's work"
            + (Tiers.allows(level, age, Items.SHIELD) ? "" : "; waiting: " + Tiers.of(level, Items.SHIELD).label));
        return out;
    }

    /** Who makes the watch's kit in this metal: the tailor the leather (else the smith), the smith the rest. */
    @Nullable
    private static VillageFolkEntity makerOf(UUID village, Metal m) {
        VillageFolkEntity smith = null, tailor = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (f.stationTask() == StationTask.SMITH && (smith == null || f.veteranLevel() > smith.veteranLevel())) smith = f;
            if (f.stationTask() == StationTask.TAILOR && (tailor == null || f.veteranLevel() > tailor.veteranLevel())) tailor = f;
        }
        return m == Metal.LEATHER && tailor != null ? tailor : smith;
    }

    /** What the shop's order book holds for the watch ("2 iron helmets"), if the shop keeps it. */
    static List<String> shopBook(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        if (!Workshop.stands(village)) return out;
        List<Item> things = new ArrayList<>();
        for (Kind k : Kind.values()) for (Metal m : Metal.values()) { Item it = piece(k, m); if (it != null) things.add(it); }
        things.add(Items.SHIELD);
        things.add(Items.BOW);
        things.add(Items.ARROW);
        for (Item it : things) {
            String key = Stockroom.key(new ItemStack(it));
            int n = Workshop.need(level, village, key);
            if (n > 0 && Workshop.why(village, key).contains("the watch")) out.add(Bench.words(it, n));
        }
        return out;
    }

    /** The whole of it, for /village watch: each guard's kit, what is on order for the watch, and its cost. */
    public static List<String> page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        List<VillageFolkEntity> watch = watch(id);
        out.add("The watch of " + Villages.name(id) + ": " + (watch.isEmpty() ? "no guards yet." : watchLine(watch) + ".")
            + " Its kit is the town's: made of the stores' own leather, iron and diamonds by the town's makers, and issued free.");
        for (VillageFolkEntity g : watch) {
            String kit = kitWords(g);
            out.add("  " + g.displayNameCap() + " (level " + g.veteranLevel() + "): " + (kit.isEmpty() ? "nothing yet" : kit));
        }
        List<String> orders = orders(level, v);
        out.add("On order for the watch: " + (orders.isEmpty() ? "nothing; every guard has the best the town can make." : ""));
        for (String o : orders) out.add("  " + o);
        List<String> shop = shopBook(level, id);
        if (!shop.isEmpty()) out.add("The shop's book for the watch: " + String.join(", ", shop) + ".");
        List<String> made = madeToday(level, id);
        if (!made.isEmpty()) out.add("Made for the watch today: " + String.join("; ", made) + ".");
        long[] all = total(id);
        long today = level.getDayTime() / 24000L;
        out.add("What it has cost the town: " + all[1] + (all[1] == 1 ? " piece" : " pieces") + " issued, " + Math.round(all[0] / 100.0)
            + " coins' worth at the town's prices (" + since(id, today) + " today, " + since(id, today - 6) + " this week), in kind."
            + " Not a coin of it from a guard's purse; and the store's takings are the treasury's, so the town pays its own shop nothing.");
        return out;
    }

    // ------------------------------------------------------------------ the command

    /**
     * /village watch (and /village watch kit): the watch's kit, guard by guard, what is on order for it and what it
     * has cost the town. Operators: {@code now} fits every guard out of the stores at once; {@code stage} stands
     * three guards in a row where you are, in leather, iron and diamond, for the pictures (a showcase's kit, for
     * nothing; cleared with /kill @e[tag=watch_kit_lineup]).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("watch")
            .executes(WatchKit::cmdPage)
            .then(Commands.literal("kit").executes(WatchKit::cmdPage))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                int n = fitAll(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal("The watch fitted out of the stores: " + n + " pieces."), false);
                return cmdPage(ctx);
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> at = stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join(" ", at)), false);
                return 1;
            }));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = page(ctx.getSource().getLevel(), v);
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    /** The tag on the stage's guards. */
    static final String LINEUP = "watch_kit_lineup";

    /**
     * The pictures' stage: three guards in a row, two blocks apart, facing south, in the town's kit as the
     * ages bring it (leather and a stone blade; iron, an iron blade and a shield; diamond and a diamond blade) —
     * a showcase's, for nothing. Returns "watch x y z" (the first guard's feet).
     */
    static List<String> stage(ServerLevel level, BlockPos at) {
        List<Entity> old = new ArrayList<>();
        for (Entity e : level.getAllEntities()) if (e.getTags().contains(LINEUP)) old.add(e);
        for (Entity e : old) e.discard();
        Metal[] rows = { Metal.LEATHER, Metal.IRON, Metal.DIAMOND };
        BlockPos first = null;
        for (int i = 0; i < rows.length; i++) {
            int x = at.getX() + i * 2, z = at.getZ();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            VillageFolkEntity g = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (g == null) continue;
            g.moveTo(x + 0.5, y, z + 0.5, 0.0F, 0.0F);
            g.setYHeadRot(0.0F);
            g.setYBodyRot(0.0F);
            g.makeShowcase(StationTask.GUARD);
            for (Kind k : SUIT) g.setItemSlot(k.slot, new ItemStack(piece(k, rows[i])));
            Item blade = rows[i] == Metal.LEATHER ? Items.STONE_SWORD : piece(Kind.SWORD, rows[i]);
            g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(blade));
            if (rows[i] != Metal.LEATHER) g.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            g.rename("The watch in " + rows[i].word);
            g.addTag(LINEUP);
            if (level.addFreshEntity(g) && first == null) first = new BlockPos(x, y, z);
        }
        if (first == null) return List.of("watch none");
        return List.of("watch", Integer.toString(first.getX()), Integer.toString(first.getY()), Integer.toString(first.getZ()));
    }
}
