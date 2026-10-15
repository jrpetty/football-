package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [identity] What a town is famous for, what its deeds are worth, and the title they raise it to.
 *
 * <p><b>Fame.</b> Each morning the town's books (Annals: what it made, item by item, the last fortnight) are read
 * for the things a town can be known for — the finest steel, smoked fish, honey, glass, wool and cloth, fireworks,
 * maps, bread and pies, timber, dressed stone, leather, bricks and pottery, books, garden produce, beef and mutton,
 * gold, diamonds and emeralds — each weighed by how much it makes a day and how skilled its makers are. A town is
 * famous for a thing when it makes enough of it to be talked of and no other town makes it better; two things at
 * most; and it loses the name when another does it better by a tenth, or it stops. Fame does real things: the
 * passing traders pay a quarter more for what it is famous for (Market.trade, Market.sellSurplus); other towns value
 * its famous goods more at the bargaining (TradeTalks: a better price, and their caravans ask for it); and once a
 * season it holds a fair for it, buyers coming from round about for the stores' famous goods at half again the price
 * (coin into its treasury, the goods out of its stores). The board, the folk's talk and other towns' talk say it.
 *
 * <p><b>Renown.</b> Renown was ten for every great work and the museum's finds (Villages.renown), and in most towns it
 * stood at nought all game. Now it is earned by deeds: a war won (ten), a peace made (three), a festival kept, a book
 * written (two), a player honoured as a hero (six), a guest house resolved on, a record harvest, the first diamond, a
 * great work of the town's own (six), a fame fair (two); and it stands on what the town is: eight for each thing it is
 * famous for, two for each master of a trade (level twenty-five, ten at most), two for each trait it has earned.
 *
 * <p><b>The title.</b> Renown raises the town's title (Villages.rank) as well as its age and its size: renown six
 * makes a hamlet of eight a village, eighteen a village of twenty-two a town, forty-five an Iron Age town of forty a
 * city, a hundred and ten a Diamond Age city with a colony a capital. The title gives: a city its mayor's chain (made
 * by the town out of the stores' gold, worn by its leader); a city or a capital better terms with the traders (a
 * twentieth, a tenth) and at the bargaining, and envoys from afar (its neighbours found half again, or twice, as far
 * off: Diplomacy); a capital is the seat for its colonies, whose laws are the capital's (LawBook). And the other towns
 * think the better of a town of renown, a little every day (Diplomacy.daily).
 */
public final class Fame {

    private Fame() {}

    public enum Product {
        STEEL("the finest steel", 6, new StationTask[]{ StationTask.SMELT, StationTask.SMITH }, "iron_ingot", "iron_block", "*iron_sword",
            "*iron_pickaxe", "*iron_axe", "*iron_shovel", "*iron_hoe", "*iron_helmet", "*iron_chestplate", "*iron_leggings", "*iron_boots"),
        FISH("its smoked fish", 10, new StationTask[]{ StationTask.FISH }, "cooked_cod", "cooked_salmon", "cod", "salmon"),
        HONEY("its honey", 3, new StationTask[]{ StationTask.BEEKEEP }, "honey_bottle", "honeycomb", "honey_block"),
        GLASS("its glass", 8, new StationTask[]{ StationTask.SMELT }, "glass", "glass_pane", "*stained_glass"),
        WOOL("its wool and cloth", 6, new StationTask[]{ StationTask.RANCH, StationTask.TAILOR }, "*_wool", "*_carpet"),
        FIREWORKS("its fireworks", 2, new StationTask[]{}, "firework_rocket", "firework_star"),
        MAPS("its maps", 1, new StationTask[]{}, "map", "filled_map"),
        BREAD("its bread and pies", 12, new StationTask[]{ StationTask.COOK }, "bread", "cake", "cookie", "pumpkin_pie"),
        TIMBER("its timber", 48, new StationTask[]{ StationTask.WOOD }, "*_log", "*_planks"),
        STONE("its dressed stone", 24, new StationTask[]{ StationTask.MINE }, "stone_bricks", "smooth_stone", "*polished_", "chiseled_stone_bricks",
            "cut_sandstone"),
        LEATHER("its leather", 4, new StationTask[]{ StationTask.HUNT, StationTask.RANCH }, "leather", "*leather_"),
        BRICKS("its bricks and pottery", 8, new StationTask[]{ StationTask.SMELT }, "brick", "bricks", "*terracotta", "flower_pot", "decorated_pot"),
        BOOKS("its books", 3, new StationTask[]{}, "book", "written_book", "writable_book", "paper"),
        PRODUCE("its garden produce", 20, new StationTask[]{ StationTask.FARM }, "carrot", "potato", "beetroot", "apple", "melon_slice",
            "pumpkin", "sweet_berries"),
        MEAT("its beef and mutton", 8, new StationTask[]{ StationTask.RANCH, StationTask.HUNT }, "beef", "cooked_beef", "mutton",
            "cooked_mutton", "porkchop", "cooked_porkchop", "chicken", "cooked_chicken"),
        GOLD("its gold", 2, new StationTask[]{ StationTask.MINE }, "gold_ingot", "raw_gold", "gold_block"),
        GEMS("its diamonds and emeralds", 0.5, new StationTask[]{ StationTask.MINE }, "diamond", "emerald");

        /** "its smoked fish". */
        public final String words;
        /** How much it must make a day, weighed, to be talked of. */
        public final double enough;
        final StationTask[] makers;
        final String[] ids;

        Product(String words, double enough, StationTask[] makers, String... ids) {
            this.words = words;
            this.enough = enough;
            this.makers = makers;
            this.ids = ids;
        }

        /** Is this item (by its id's path) one of these? "*x" matches any id containing x. */
        public boolean matches(String path) {
            for (String i : ids) {
                if (i.startsWith("*") ? path.contains(i.substring(1)) : path.equals(i)) return true;
            }
            return false;
        }

        public boolean matches(ItemStack s) {
            return !s.isEmpty() && matches(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
        }

        /** "smoked fish". */
        public String bare() {
            return words.startsWith("its ") ? words.substring(4) : words;
        }

        @Nullable
        public static Product named(String s) {
            try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** Each town's standing at each product, as last weighed (its morning). */
    private static final Map<UUID, double[]> SCORES = new ConcurrentHashMap<>();
    /** Each town's masters of a trade (level twenty-five), as last counted. */
    private static final Map<UUID, Integer> MASTERS = new ConcurrentHashMap<>();

    static void resetForTests() {
        SCORES.clear();
        MASTERS.clear();
    }

    /** The whole number at the start of some words ("42 fish" is 42), or 0. */
    public static int number(String s) {
        if (s == null) return 0;
        int i = 0, n = 0;
        boolean neg = false;
        s = s.trim();
        if (s.startsWith("-")) { neg = true; i = 1; }
        for (; i < s.length() && Character.isDigit(s.charAt(i)); i++) n = n * 10 + (s.charAt(i) - '0');
        return neg ? -n : n;
    }

    // ------------------------------------------------------------------ weighing what it makes

    /** What the town makes of each product a day over the last fortnight, weighed by its makers' skill. */
    static double[] weigh(UUID village) {
        double[] out = new double[Product.values().length];
        List<Annals.ItemDay> days = Annals.itemDays(village);
        int from = Math.max(0, days.size() - 14);
        int n = days.size() - from;
        if (n <= 0) return out;
        for (int i = from; i < days.size(); i++) {
            for (Map.Entry<String, int[]> e : days.get(i).items().entrySet()) {
                String key = e.getKey();
                String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
                for (Product p : Product.values()) if (p.matches(path)) out[p.ordinal()] += e.getValue()[0];
            }
        }
        Map<StationTask, int[]> levels = new EnumMap<>(StationTask.class);
        for (VillageFolkEntity f : Identity.grown(village)) {
            int[] l = levels.computeIfAbsent(f.stationTask(), k -> new int[2]);
            l[0] += f.veteranLevel();
            l[1]++;
        }
        for (Product p : Product.values()) {
            double skill = 0;
            int k = 0;
            for (StationTask t : p.makers) {
                int[] l = levels.get(t);
                if (l == null || l[1] == 0) continue;
                skill += l[0] / (double) l[1];
                k++;
            }
            if (k > 0) skill /= k;
            out[p.ordinal()] = out[p.ordinal()] / n * (1.0 + Math.min(30, skill) / 50.0);
        }
        return out;
    }

    static double score(UUID village, Product p) {
        double[] s = SCORES.get(village);
        if (s == null) {
            s = weigh(village);
            SCORES.put(village, s);
        }
        return s[p.ordinal()];
    }

    /** The best any other town makes of it, and which. */
    @Nullable
    static Object[] rival(UUID village, Product p) {
        UUID best = null;
        double top = 0;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(village)) continue;
            double x = score(o.id(), p);
            if (x > top) { top = x; best = o.id(); }
        }
        return best == null ? null : new Object[]{ best, top };
    }

    // ------------------------------------------------------------------ the morning

    /** The morning's look: what it makes weighed, its fame won or lost, a fame fair if it is the season's, its masters counted. */
    static void review(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        UUID id = v.id();
        SCORES.put(id, weigh(id));
        r.seat = seat(id);
        int masters = 0;
        for (VillageFolkEntity f : Identity.grown(id)) if (f.veteranLevel() >= 25) masters++;
        MASTERS.put(id, masters);
        String name = Villages.name(id);
        // Lost: another does it better by a tenth, or the town has all but stopped.
        for (Product p : new ArrayList<>(r.fame.keySet())) {
            double mine = score(id, p);
            Object[] rv = rival(id, p);
            boolean beaten = rv != null && (double) rv[1] > mine * 1.1;
            if (beaten || mine < p.enough * 0.5) {
                r.fame.remove(p);
                String line = name + " is no longer famous for " + p.bare() + (beaten ? ": " + Villages.name((UUID) rv[0]) + " makes it better now" : ": it hardly makes any now");
                r.change(day, line);
                Villages.tell(id, day, line);
            }
        }
        // Won: enough of it, and nobody better.
        List<Product> cands = new ArrayList<>();
        for (Product p : Product.values()) {
            if (r.fame.containsKey(p)) continue;
            double mine = score(id, p);
            if (mine < p.enough) continue;
            Object[] rv = rival(id, p);
            if (rv != null && (double) rv[1] >= mine) continue;
            cands.add(p);
        }
        cands.sort((a, b) -> Double.compare(score(id, b) / b.enough, score(id, a) / a.enough));
        for (Product p : cands) {
            if (r.fame.size() >= 2) break;
            r.fame.put(p, day);
            String line = name + " is famous for " + p.bare() + " now: no town makes more of it, or better";
            r.change(day, line);
            Villages.tell(id, day, line);
            Market.assemblyNews(id, "We're famous! Folk all round talk of " + p.words + ".");
        }
        fair(level, v, r, day);
        chain(level, v, r, day);
    }

    /**
     * Once a season (its fifth day), a town famous for something holds a fair for it: buyers from round about take up
     * to four dozen of the famous thing out of the stores at half again the town's price, the coin into the treasury.
     * The bell is rung, players near are told, and it is one of the town's deeds. Returns the coin taken, 0 for none.
     */
    static int fair(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        UUID id = v.id();
        if (r.fame.isEmpty() || day - r.lastFair < 5 || Math.floorMod(Seasons.dayOfYear(id, day), 7) != 4) return 0;
        return holdFair(level, v, r, day);
    }

    static int holdFair(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        UUID id = v.id();
        if (r.fame.isEmpty()) return 0;
        Product p = r.fame.keySet().iterator().next();
        Predicate<ItemStack> what = p::matches;
        int have = Market.stock(level, id, what);
        int n = Math.min(48, have / 2);
        r.lastFair = day;
        if (n < 4) {
            r.change(day, "the " + p.bare() + " fair was called off: the stores had too little of it to sell");
            return 0;
        }
        ItemStack one = ItemStack.EMPTY;
        for (net.minecraft.core.BlockPos at : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(at) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && one.isEmpty(); i++) if (what.test(c.getItem(i))) one = c.getItem(i).copyWithCount(1);
            if (!one.isEmpty()) break;
        }
        double each = one.isEmpty() ? 1.0 : Math.max(0.1, PriceIndex.each(level, id, one));
        if (!TownWork.take(level, v, what, n)) return 0;
        int paid = Math.max(1, (int) Math.round(n * each * 1.5));
        Ledger.addCoins(id, paid);
        Economy.sold(id, paid);
        String line = "the " + p.bare() + " fair: buyers came from round about and paid " + paid + " coin for " + n + " of " + Villages.name(id) + "'s " + p.bare();
        r.change(day, line);
        Villages.tell(id, day, line);
        Identity.event(id, Identity.Ev.FAME_FAIR, day);
        level.playSound(null, v.centre(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0F, 1.2F);
        for (ServerPlayer pl : level.players()) {
            if (pl.blockPosition().closerThan(v.centre(), 96)) pl.displayClientMessage(Component.literal("The " + Villages.name(id) + " "
                + p.bare() + " fair is on today!"), true);
        }
        return paid;
    }

    /**
     * A city's mayor's chain: made once out of the stores' gold (a golden breastplate, or eight ingots), named and worn
     * by its leader; passed on to the next leader from whoever wears it; made again (three times at most) if it is lost.
     */
    static void chain(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        UUID id = v.id();
        if (Villages.rank(id).ordinal() < Villages.Rank.CITY.ordinal()) return;
        VillageFolkEntity leader = Identity.leader(id);
        if (leader == null || isChain(leader.getItemBySlot(EquipmentSlot.CHEST))) return;
        if (!leader.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) return;        // a guard's own breastplate is not taken off it for the chain
        ItemStack chain = ItemStack.EMPTY;
        for (VillageFolkEntity f : Identity.grown(id)) {
            if (f != leader && isChain(f.getItemBySlot(EquipmentSlot.CHEST))) {
                chain = f.getItemBySlot(EquipmentSlot.CHEST).copy();
                f.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                break;
            }
        }
        boolean made = false;
        if (chain.isEmpty()) {
            if (r.chainGiven >= 3) return;
            if (!TownWork.take(level, v, s -> s.is(Items.GOLDEN_CHESTPLATE), 1) && !TownWork.take(level, v, s -> s.is(Items.GOLD_INGOT), 8)) return;
            chain = new ItemStack(Items.GOLDEN_CHESTPLATE);
            chain.set(DataComponents.CUSTOM_NAME, Component.literal(CHAIN + " of " + Villages.name(id)));
            chain.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Made on day " + (day + 1) + ", when "
                + Villages.name(id) + " became a city.").withStyle(net.minecraft.ChatFormatting.GRAY))));
            r.chainGiven++;
            made = true;
        }
        leader.setItemSlot(EquipmentSlot.CHEST, chain);
        String line = made ? "the town made its mayor's chain out of the stores' gold, and " + leader.displayNameCap() + " wears it"
            : leader.displayNameCap() + " took up the mayor's chain";
        r.change(day, line);
        Villages.tell(id, day, line);
        Identity.dirty();
    }

    static final String CHAIN = "Mayor's Chain";

    static boolean isChain(ItemStack s) {
        return s.is(Items.GOLDEN_CHESTPLATE) && s.has(DataComponents.CUSTOM_NAME) && s.getHoverName().getString().startsWith(CHAIN);
    }

    // ------------------------------------------------------------------ renown

    /** What a deed is worth to the town's renown. */
    static int worth(Identity.Ev e) {
        return switch (e) {
            case WAR_WON -> 10;
            case PEACE -> 3;
            case FESTIVAL, CATCH, DEAL -> 1;
            case BOOK, GUEST, HARVEST, FAME_FAIR -> 2;
            case HERO, BIG_WORK -> 6;
            case DIAMOND -> 3;
            default -> 0;
        };
    }

    /** A deed: onto the town's renown, and into its book of deeds. */
    static void onEvent(Identity.Rec r, Identity.Ev e, long day) {
        int w = worth(e);
        if (w <= 0) return;
        r.deeds += w;
        r.deedLog.add(day + "|+" + w + " " + e.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        while (r.deedLog.size() > 24) r.deedLog.remove(0);
    }

    /** The renown it has earned by its deeds and its standing (Villages.renown adds the great works and the museum). */
    public static int renown(@Nullable UUID village) {
        if (Identity.neutral()) return 0;
        Identity.Rec r = Identity.known(village);
        if (r == null) return 0;
        int traits = 0;
        for (TownTraits.Trait t : r.traits.keySet()) if (t != TownTraits.Trait.MOURNING && t != TownTraits.Trait.RAID_SCARRED) traits++;
        return r.deeds + 8 * r.fame.size() + 2 * Math.min(10, MASTERS.getOrDefault(village, 0)) + 2 * traits;
    }

    /** "deeds 24, fame 8, masters 4, traits 6". */
    static String renownWords(UUID village) {
        Identity.Rec r = Identity.known(village);
        if (r == null) return "no deeds yet";
        int traits = 0;
        for (TownTraits.Trait t : r.traits.keySet()) if (t != TownTraits.Trait.MOURNING && t != TownTraits.Trait.RAID_SCARRED) traits++;
        return "deeds " + r.deeds + ", fame " + 8 * r.fame.size() + ", masters " + 2 * Math.min(10, MASTERS.getOrDefault(village, 0))
            + ", traits " + 2 * traits + ", great works " + Museum.GREAT_WORK_RENOWN * Villages.greatWorks(village) + ", the museum " + Museum.renown(village);
    }

    /**
     * The town's title, raised by renown where its age and size alone would not (Villages.rank): renown six makes a
     * hamlet of eight a village; eighteen a village of twenty-two a town; forty-five an Iron Age town of forty a city;
     * a hundred and ten a Diamond Age city with a colony of its own a capital.
     */
    public static Villages.Rank lift(@Nullable UUID village, Villages.Rank standard, Villages.Age age, int folk, int renown, int colonies) {
        if (Identity.neutral() || Identity.known(village) == null) return standard;
        Villages.Rank r = standard;
        if (r == Villages.Rank.HAMLET && folk >= 8 && renown >= 6) r = Villages.Rank.VILLAGE;
        if (r == Villages.Rank.VILLAGE && folk >= 22 && renown >= 18 && age.ordinal() >= Villages.Age.STONE.ordinal()) r = Villages.Rank.TOWN;
        if (r == Villages.Rank.TOWN && folk >= 40 && renown >= 45 && age.ordinal() >= Villages.Age.IRON.ordinal()) r = Villages.Rank.CITY;
        if (r == Villages.Rank.CITY && renown >= 110 && colonies >= 1 && age.ordinal() >= Villages.Age.DIAMOND.ordinal()) r = Villages.Rank.CAPITAL;
        return r;
    }

    /** "or by renown: 18 and twenty-two folk", for the page. */
    static String renownNext(UUID village) {
        return switch (Villages.rank(village)) {
            case HAMLET -> "or renown 6 and eight folk";
            case VILLAGE -> "or renown 18 and twenty-two folk in the Stone Age";
            case TOWN -> "or renown 45 and forty folk in the Iron Age";
            case CITY -> "or renown 110, the Diamond Age and a colony";
            case CAPITAL -> "the highest title there is";
        };
    }

    /** A capital's colony: its capital, the seat of its law (LawBook.value); else null. */
    @Nullable
    public static UUID seat(@Nullable UUID village) {
        if (village == null) return null;
        UUID mother = Ledger.links().get(village);
        if (mother == null || Identity.known(mother) == null) return null;
        return Villages.rank(mother) == Villages.Rank.CAPITAL ? mother : null;
    }

    // ------------------------------------------------------------------ what it does

    /** What the passing traders pay for a lot, against the usual (Market.trade, sellSurplus): a quarter more for its famous goods. */
    public static double traderPremium(@Nullable UUID village, Market.Good g) {
        if (Identity.neutral() || Identity.known(village) == null) return 1.0;
        Identity.Rec r = Identity.known(village);
        double x = 1.0;
        for (Product p : r.fame.keySet()) {
            if (famousGood(p, g)) { x *= 1.25; break; }
        }
        if (r.has(TownTraits.Trait.MERCHANT_PRINCES)) x *= 1.05;
        Villages.Rank rank = Villages.rank(village);
        if (rank == Villages.Rank.CITY) x *= 1.05;
        else if (rank == Villages.Rank.CAPITAL) x *= 1.10;
        return x;
    }

    /** Does a market good come under a famous product? (By the items it stands for.) */
    static boolean famousGood(Product p, Market.Good g) {
        for (String id : p.ids) {
            if (id.startsWith("*")) continue;
            Item it = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(id));
            if (it != Items.AIR && g.what().test(new ItemStack(it))) return true;
        }
        return false;
    }

    /**
     * What a buyer thinks one of a seller's goods worth, against its own reckoning (TradeTalks.lay): its laws on
     * tariffs (LawBook.importFactor), a seventh more for what the seller is famous for (its caravans ask for it), and a
     * little more from a city or a capital.
     */
    public static double importWorth(@Nullable UUID buyer, @Nullable UUID seller, Item item) {
        if (Identity.neutral()) return 1.0;
        double x = LawBook.importFactor(buyer);
        Identity.Rec s = Identity.known(seller);
        if (s != null) {
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            for (Product p : s.fame.keySet()) if (p.matches(path)) { x *= 1.15; break; }
            Villages.Rank rank = Villages.rank(seller);
            if (rank == Villages.Rank.CITY) x *= 1.03;
            else if (rank == Villages.Rank.CAPITAL) x *= 1.06;
        }
        return x;
    }

    /** How much further off a town finds its neighbours (Diplomacy.neighbours): envoys from afar for a city or a capital. */
    public static double reach(@Nullable UUID village) {
        if (Identity.neutral() || Identity.known(village) == null) return 1.0;
        Villages.Rank r = Villages.rank(village);
        return r == Villages.Rank.CAPITAL ? 2.0 : r == Villages.Rank.CITY ? 1.5 : 1.0;
    }

    /** How the two think the better of each other for their renown, a day (Diplomacy.daily); a capital and its colony, more. */
    static int esteem(UUID a, UUID b) {
        int most = Math.max(renown(a) + Villages.greatWorks(a) * Museum.GREAT_WORK_RENOWN, renown(b) + Villages.greatWorks(b) * Museum.GREAT_WORK_RENOWN);
        int d = most >= 100 ? 2 : most >= 40 ? 1 : 0;
        if (b.equals(seat(a)) || a.equals(seat(b))) d += 2;
        return d;
    }

    // ------------------------------------------------------------------ the words

    /** "its smoked fish and its glass", or "". */
    public static String words(@Nullable UUID village) {
        if (Identity.neutral()) return "";
        Identity.Rec r = Identity.known(village);
        if (r == null || r.fame.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (Product p : r.fame.keySet()) out.add(p.words);
        return String.join(" and ", out);
    }

    /** What other towns are famous for, as a folk says it ("Over in Ashford they're famous for their glass."), or null. */
    @Nullable
    static String neighbourWord(UUID village) {
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(village)) continue;
            Identity.Rec r = Identity.known(o.id());
            if (r == null || r.fame.isEmpty()) continue;
            Product p = r.fame.keySet().iterator().next();
            return "Over in " + Villages.name(o.id()) + " they're famous for " + p.words.replace("its ", "their ") + ".";
        }
        return null;
    }

    static CompoundTag report(ServerLevel level, UUID village, Identity.Rec r) {
        CompoundTag t = new CompoundTag();
        ListTag famous = new ListTag();
        for (Map.Entry<Product, Long> e : r.fame.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("what", e.getKey().bare());
            c.putLong("since", e.getValue());
            c.putString("rate", String.format(Locale.ROOT, "%.1f", score(village, e.getKey())));
            Object[] rv = rival(village, e.getKey());
            c.putString("rival", rv == null ? "no other town makes it" : "next best, " + Villages.name((UUID) rv[0]) + " at "
                + String.format(Locale.ROOT, "%.1f", (double) rv[1]));
            famous.add(c);
        }
        t.put("famous", famous);
        // Its best, by how near each is to being talked of, for a town not yet famous.
        List<String> near = new ArrayList<>();
        Product[] all = Product.values().clone();
        java.util.Arrays.sort(all, (a, b) -> Double.compare(score(village, b) / b.enough, score(village, a) / a.enough));
        for (Product p : all) {
            if (near.size() >= 3 || score(village, p) <= 0) break;
            near.add(p.bare() + " " + String.format(Locale.ROOT, "%.1f", score(village, p)) + " a day (talked of at " + p.enough + ")");
        }
        t.put("near", Identity.strings(near));
        t.putInt("renown", Villages.renown(village));
        t.putString("renownParts", renownWords(village));
        t.putString("rank", Villages.rank(village).label);
        t.putString("next", Villages.nextRankNote(village) + "; " + renownNext(village));
        List<String> deeds = new ArrayList<>();
        for (int i = r.deedLog.size() - 1; i >= 0 && deeds.size() < 8; i--) {
            String[] p = r.deedLog.get(i).split("\\|", 2);
            if (p.length == 2) deeds.add("Day " + (number(p[0]) + 1) + ": " + p[1]);
        }
        t.put("deeds", Identity.strings(deeds));
        List<String> perks = new ArrayList<>();
        Villages.Rank rank = Villages.rank(village);
        if (rank.ordinal() >= Villages.Rank.CITY.ordinal()) {
            perks.add("a mayor's chain for its leader");
            perks.add("traders pay " + (rank == Villages.Rank.CAPITAL ? "a tenth" : "a twentieth") + " more");
            perks.add("envoys from " + (rank == Villages.Rank.CAPITAL ? "twice" : "half again") + " as far off");
        }
        if (rank == Villages.Rank.CAPITAL) perks.add("the seat of its colonies: their laws are its laws");
        if (!r.fame.isEmpty()) {
            perks.add("a quarter more from the traders for what it is famous for, and a better price at the bargaining");
            perks.add("a fair for it every season");
        }
        t.put("perks", Identity.strings(perks));
        UUID s = seat(village);
        if (s != null) t.putString("seat", "A colony of the capital, " + Villages.name(s) + ": its laws are the capital's.");
        return t;
    }

    static String line(ServerLevel level, UUID village, Identity.Rec r) {
        String f = words(village);
        return (f.isEmpty() ? "not yet famous for anything" : "famous for " + f) + "; last fair day " + (r.lastFair < 0 ? "never" : Long.toString(r.lastFair + 1))
            + "; deeds: " + (r.deedLog.isEmpty() ? "none yet" : String.join(", ", r.deedLog).replace('|', ' '));
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town's books say it made so many of an item a day, for so many days up to yesterday. */
    public static void madeForTests(UUID village, String itemPath, int perDay, int days, long today) {
        for (int i = 1; i <= days; i++) {
            String key = "annals.items/" + (today - i);
            String was = Ledger.note(village, key);
            String add = itemPath + "=" + perDay + "/0/";
            Ledger.note(village, key, was == null || was.isEmpty() || was.equals("-") ? add : was + ";" + add);
        }
        SCORES.remove(village);
    }

    /** Tests: the town's standing at a product. */
    public static double scoreForTests(UUID village, Product p) {
        SCORES.remove(village);
        return score(village, p);
    }

    /** Tests: hold the town's fame fair now. Returns the coin taken. */
    public static int fairForTests(ServerLevel level, Villages.Village v) {
        Identity.Rec r = Identity.rec(v.id());
        return holdFair(level, v, r, level.getDayTime() / 24000L);
    }

    /** Tests: the morning's look at its fame (and renown, the fair and the chain), now. */
    public static void reviewForTests(ServerLevel level, Villages.Village v, long day) {
        review(level, v, Identity.rec(v.id()), day);
    }
}
