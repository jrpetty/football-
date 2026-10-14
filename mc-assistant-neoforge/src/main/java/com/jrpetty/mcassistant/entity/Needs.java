package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * [shops-homes] What each folk needs and wants, as a shopping list it keeps and makes again each morning: from its age,
 * its trade, its household, its means, the season and what it loves, set against what its home already holds.
 * <ul>
 * <li><b>Necessities</b> (a poor folk buys only these): food for the household's larder, two days of meals a head,
 *     bought by whoever has the most in its purse; the tool of its trade when it has none.</li>
 * <li><b>Household goods</b>: a larder (a barrel) for a home with none, candles for the evenings, a wardrobe (a chest)
 *     when the household chest is filling up.</li>
 * <li><b>Clothes</b> for the winter, when the home holds none.</li>
 * <li><b>Comforts</b> (for those who can run to them): books for a reader, a toy for the household's child, the pet's
 *     treats, a lute for the musical, a cake for a birthday in the house.</li>
 * </ul>
 * The rules are data (RULES): a new want is a new line. The list is the shop's demand (StockKeeper keeps what is on
 * it: wares), the errand's (ShoppingTrips) and the folk's talk.
 */
public final class Needs {

    private Needs() {}

    /** One thing wanted: what (one of it), how many, how much it matters (100: food on the table), why, and its kind. */
    public record Want(ItemStack sample, int count, int priority, String why, Goods.Category category) {
        public String words() {
            return Goods.words(sample, count);
        }
    }

    /** What a rule knows of the folk: itself, its home, its household and its means. */
    record Ctx(ServerLevel level, VillageFolkEntity folk, UUID village, @Nullable Homes.Home home, int members, boolean children,
               Wealth.Tier tier, Seasons.Season season, long day) {

        int held(Predicate<ItemStack> what) {
            return home == null ? 0 : HomeStores.count(level, village, home, what);
        }
    }

    /** A rule: its name, how much it matters, the least means it is bought on, when it applies, and what it wants. */
    record Rule(String id, int priority, Wealth.Tier from, Predicate<Ctx> applies, Function<Ctx, Want> want) {}

    /** The least priority bought at each means: the poor only what keeps them fed and at work. */
    static int floorFor(Wealth.Tier t) {
        return switch (t) {
            case POOR -> 80;
            case GETTING_BY -> 40;
            case COMFORTABLE -> 20;
            default -> 0;
        };
    }

    /** Meals a head the larder keeps (two days of two). */
    static final int LARDER_MEALS = 4;
    /** The most of one thing on a list. */
    static final int MOST = 8;

    private static final Predicate<ItemStack> FOOD = s -> s.get(DataComponents.FOOD) != null && !Goods.drink(s);

    private static Want want(Item it, int n, int priority, String why) {
        ItemStack one = new ItemStack(it);
        return new Want(one, Math.max(1, Math.min(MOST, n)), priority, why, Goods.of(one));
    }

    static final List<Rule> RULES = List.of(
        // Food for the household's larder: two days of meals a head, bought by its best-off grown member.
        new Rule("food", 100, Wealth.Tier.POOR,
            c -> c.home() != null && !c.folk().isBaby() && provider(c) && c.held(FOOD) < c.members() * LARDER_MEALS / 2,
            c -> want(Items.BREAD, c.members() * LARDER_MEALS - c.held(FOOD), 100, "for the larder")),
        // The tool of its trade, when it has none on it or at home.
        new Rule("tool", 90, Wealth.Tier.POOR,
            c -> { Predicate<ItemStack> t = Cafe.toolFor(c.folk().stationTask()); return t != null && c.folk().stationTask() != AssistantEntity.StationTask.GUARD
                && c.folk().countCarried(t) == 0 && c.held(t) == 0; },
            c -> { Item it = Cafe.plainToolFor(c.folk().stationTask()); return it == null ? null : want(it, 1, 90, "for its work"); }),
        // A larder for a home without one.
        new Rule("larder", 60, Wealth.Tier.GETTING_BY,
            c -> c.home() != null && provider(c) && HomeStores.box(c.level(), c.village(), c.home(), HomeStores.Kind.LARDER) == null,
            c -> want(Items.BARREL, 1, 60, "a larder for the house")),
        // Candles for the evenings.
        new Rule("candles", 50, Wealth.Tier.GETTING_BY,
            c -> c.home() != null && provider(c) && c.held(s -> s.is(ItemTags.CANDLES)) < 2,
            c -> want(Items.CANDLE, 2, 50, "for the evenings")),
        // A wardrobe when the household chest is filling up.
        new Rule("wardrobe", 45, Wealth.Tier.COMFORTABLE,
            c -> c.home() != null && provider(c) && HomeStores.box(c.level(), c.village(), c.home(), HomeStores.Kind.WARDROBE) == null
                && HomeStores.fullness(c.level(), c.village(), c.home(), HomeStores.Kind.CHEST) > 0.6,
            c -> want(Items.CHEST, 1, 45, "a wardrobe: the chest's full")),
        // Something warm for the winter, when the home holds no clothes.
        new Rule("winter", 40, Wealth.Tier.GETTING_BY,
            c -> c.season() == Seasons.Season.WINTER && !c.folk().isBaby() && c.held(Goods::clothes) == 0,
            c -> want(Items.LEATHER_BOOTS, 1, 40, "against the cold")),
        // A cake for a birthday in the house.
        new Rule("birthday", 70, Wealth.Tier.GETTING_BY,
            c -> c.home() != null && provider(c) && birthdaySoon(c),
            c -> want(Items.CAKE, 1, 70, "for a birthday")),
        // Books for a reader.
        new Rule("books", 30, Wealth.Tier.COMFORTABLE,
            c -> c.folk().persona().loves() == Persona.Gift.BOOKS && c.held(s -> s.is(Items.BOOK) || s.is(Items.WRITTEN_BOOK)) < 3,
            c -> want(Items.BOOK, 1, 30, "to read of an evening")),
        // A toy for the household's child.
        new Rule("toy", 35, Wealth.Tier.GETTING_BY,
            c -> c.home() != null && c.children() && provider(c) && c.held(s -> Goods.comfort(s) && !s.is(ItemTags.FLOWERS)) == 0,
            c -> want(com.jrpetty.mcassistant.item.LeisureItems.KITE.get(), 1, 35, "for the little one")),
        // The pet's treats.
        new Rule("pet", 20, Wealth.Tier.COMFORTABLE,
            c -> hasPet(c.folk()) && c.held(s -> s.is(McAssistantMod.PET_TREAT.get())) == 0,
            c -> want(McAssistantMod.PET_TREAT.get(), 2, 20, "for its pet")),
        // A lute for the musical.
        new Rule("lute", 15, Wealth.Tier.WELL_OFF,
            c -> c.folk().persona().loves() == Persona.Gift.MUSIC && c.folk().countCarried(s -> s.is(com.jrpetty.mcassistant.item.LeisureItems.LUTE.get())) == 0
                && c.held(s -> s.is(com.jrpetty.mcassistant.item.LeisureItems.LUTE.get())) == 0,
            c -> want(com.jrpetty.mcassistant.item.LeisureItems.LUTE.get(), 1, 15, "to play of an evening")));

    /** Is this folk the one of its household that does the household's shopping (the most in its purse, grown)? */
    static boolean provider(Ctx c) {
        if (c.home() == null) return false;
        VillageFolkEntity best = null;
        for (UUID m : c.home().members) {
            if (!(c.level().getEntity(m) instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (best == null || f.purse() > best.purse()) best = f;
        }
        return best == null || best == c.folk();
    }

    private static boolean birthdaySoon(Ctx c) {
        for (UUID m : c.home().members) {
            if (c.level().getEntity(m) instanceof VillageFolkEntity f
                && (Birthdays.isBirthday(f, c.day()) || Birthdays.isBirthday(f, c.day() + 1))) return true;
        }
        return false;
    }

    private static boolean hasPet(VillageFolkEntity f) {
        String line = Pets.cardLine(f);
        return line != null && !line.isEmpty();
    }

    // ------------------------------------------------------------------ the lists

    private record Made(long day, List<Want> wants) {}

    private static final Map<UUID, Made> LISTS = new ConcurrentHashMap<>();
    /** What each folk bought from its list lately, for its talk and its card ("bread and candles, yesterday"). */
    private static final Map<UUID, String> BOUGHT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LISTS.clear();
        BOUGHT.clear();
    }

    /** This folk's shopping list today, the most needed first, within its means (made once a day). */
    public static List<Want> of(ServerLevel level, VillageFolkEntity f) {
        long day = level.getDayTime() / 24000L;
        Made l = LISTS.get(f.getUUID());
        if (l != null && l.day() == day) return l.wants();
        List<Want> wants = make(level, f, day);
        LISTS.put(f.getUUID(), new Made(day, wants));
        return wants;
    }

    /** Make it again now (the tests, and after a purchase changes the home). */
    public static List<Want> remake(ServerLevel level, VillageFolkEntity f) {
        LISTS.remove(f.getUUID());
        return of(level, f);
    }

    private static List<Want> make(ServerLevel level, VillageFolkEntity f, long day) {
        UUID village = f.ownerId();
        if (village == null) return List.of();
        Homes.Home home = Homes.homeOf(village, f.getUUID());
        int members = home == null ? 1 : Math.max(1, home.members.size());
        boolean children = false;
        if (home != null) for (UUID m : home.members) if (level.getEntity(m) instanceof VillageFolkEntity o && o.isBaby()) children = true;
        Wealth.Tier tier = Wealth.tier(f);
        Ctx c = new Ctx(level, f, village, home, members, children, tier, Seasons.season(village, day), day);
        int floor = floorFor(tier);
        List<Want> out = new ArrayList<>();
        for (Rule r : RULES) {
            if (r.priority() < floor || tier.ordinal() < r.from().ordinal()) continue;
            try {
                if (!r.applies().test(c)) continue;
                Want w = r.want().apply(c);
                if (w != null && w.count() > 0 && !w.sample().isEmpty()) out.add(w);
            } catch (RuntimeException e) {
                // A rule that cannot read what it needs today (a home half-loaded) is left off the list, not the day.
            }
        }
        out.sort(Comparator.comparingInt(Want::priority).reversed());
        return List.copyOf(out);
    }

    /** Bought: off the list for today, and remembered for its talk. */
    static void bought(VillageFolkEntity f, List<String> what, long day) {
        LISTS.remove(f.getUUID());
        if (!what.isEmpty()) BOUGHT.put(f.getUUID(), String.join(" and ", what) + "|" + day);
    }

    /** The card's line: what it wants, and what it last bought. Null with neither. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.ownerId() == null) return null;
        List<Want> wants = of(level, f);
        String had = BOUGHT.get(f.getUUID());
        StringBuilder sb = new StringBuilder();
        if (!wants.isEmpty()) {
            sb.append("Wants ");
            for (int i = 0; i < Math.min(3, wants.size()); i++) {
                if (i > 0) sb.append(i == Math.min(3, wants.size()) - 1 ? " and " : ", ");
                sb.append(wants.get(i).words()).append(" (").append(wants.get(i).why()).append(")");
            }
            sb.append(".");
        }
        if (had != null) {
            String[] p = had.split("\\|");
            long ago = level.getDayTime() / 24000L - Long.parseLong(p[1]);
            if (sb.length() > 0) sb.append(" ");
            sb.append("Bought ").append(p[0]).append(ago == 0 ? " today." : ago == 1 ? " yesterday." : " " + ago + " days ago.");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** A word of it, for its talk: what it is after at the shop. Null with nothing on its list. */
    @Nullable
    public static String talk(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return null;
        List<Want> wants = of(level, f);
        if (wants.isEmpty()) return null;
        Want w = wants.get(0);
        return "I must get " + w.words() + " at the shop, " + w.why() + ".";
    }

    /** What the town's folk want, by ware, for the shop's range (StockKeeper.range): what is on their lists today. */
    static Map<String, StockKeeper.Ware> wares(ServerLevel level, Villages.Village v) {
        Map<String, StockKeeper.Ware> out = new java.util.LinkedHashMap<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            Made l = LISTS.get(f.getUUID());
            if (l == null) continue;
            for (Want w : l.wants()) {
                String key = Stockroom.key(w.sample());
                if (out.containsKey(key)) continue;
                boolean food = w.sample().get(DataComponents.FOOD) != null;
                Item make = food || RecipeBook.waysFor(level, w.sample().getItem()).isEmpty() ? null : w.sample().getItem();
                Item item = w.sample().getItem();
                out.put(key, new StockKeeper.Ware(key, s -> s.is(item), w.sample().copy(), food, make, 1, 1));
            }
        }
        return out;
    }

    /** Tests: this folk's list, as "item×n (why)". */
    public static List<String> listForTests(ServerLevel level, VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        for (Want w : remake(level, f)) {
            out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(w.sample().getItem()).getPath() + "x" + w.count()
                + " (" + w.why().toLowerCase(Locale.ROOT) + ")");
        }
        return out;
    }
}
