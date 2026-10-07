package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [batchB] The town fair, once a year late in summer (the sixth day of summer: Festivals).
 *
 * <p>Four classes: <b>the best bread</b>, <b>the best wool</b>, <b>the biggest fish</b> and <b>the best
 * honey</b>. All day a player can enter something of their own: right-click the board with it, or
 * {@code /village fair enter} with it in hand; it is taken into the fair's keeping (the whole stack in hand:
 * up to a stack of loaves or wool, one fish, sixteen of honey), one entry a class, and comes back after the
 * judging. At dusk the town gathers before the board and the folk bring theirs, up to four a class, only
 * the hands whose trade it is (the cook and the farmers for bread, the rancher and the tailor for wool, the
 * fishers, the beekeeper), the most skilled first: the best of its own work in its pack (its shearing, its
 * catch, its honey) or, having none, the best the stores hold of its trade's work, brought on the trade's
 * behalf. Bread always comes from the stores, the town's baking: the loaves in a folk's pack are its rations
 * (every founder carries sixteen from its starter kit), not its baking, and the fair is not a count of who
 * has eaten least.
 *
 * <p>The elder judges, by rules anybody can check, a tie going to whoever entered first (a guest who entered
 * in the day, then the town's hands, the most skilled first):
 * <ul>
 * <li><b>Bread</b>: the biggest batch, ten points a loaf, up to a stack.</li>
 * <li><b>Wool</b>: the biggest fleece of one colour (ten points a block, up to a stack), five more for a
 *     dyed one (the dye was work too).</li>
 * <li><b>Fish</b>: the heaviest. A salmon weighs five to ten pounds, a cod three to seven, a pufferfish one
 *     and a half to three, a tropical fish half a pound to a pound and a half, as the day falls for each
 *     angler; a skilled fisher lands them a little heavier.</li>
 * <li><b>Honey</b>: thirty points a bottle, ten a comb, up to sixteen.</li>
 * </ul>
 * The winner of each class gets a ribbon (a sheet of the stores' paper, named "Ribbon: best bread, Year 3"
 * and lettered with the fair and the day; none if the stores have no paper) and a small purse out of the
 * treasury (four coins, or what is in it). A folk keeps its ribbon among its own things and remembers the
 * day; a player is handed theirs. Everything entered goes back where it came from: to its folk's pack, to
 * the stores, to the player (who, if they are away, gets it the next time they are in the world). The
 * results go into the chronicle and the books, and onto the board for a few days.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Fair {

    private Fair() {}

    /** The purse to each class's winner, out of the treasury. */
    public static final int PURSE = 4;
    /** Entries of each class at most (a player's always gets in). */
    static final int EACH = 4;

    public enum Category {
        BREAD("best bread", "the best bread"), WOOL("best wool", "the best wool"), FISH("biggest fish", "the biggest fish"),
        HONEY("best honey", "the best honey");

        /** On the ribbon: "best bread". */
        public final String ribbon;
        /** In a sentence: "the best bread". */
        public final String title;

        Category(String ribbon, String title) {
            this.ribbon = ribbon;
            this.title = title;
        }
    }

    /** One entry: its class; whose (a player's, a folk's own, or the stores' entered by a folk for its trade); what; how it was judged. */
    static final class Entry {
        static final int PLAYER = 0, FOLK = 1, STORES = 2;
        final Category cat;
        final int kind;
        final UUID owner;
        final String name;
        final ItemStack item;
        double score;
        String words = "";

        Entry(Category cat, int kind, UUID owner, String name, ItemStack item) {
            this.cat = cat;
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.item = item;
        }

        CompoundTag save(HolderLookup.Provider reg) {
            CompoundTag c = new CompoundTag();
            c.putString("Cat", cat.name());
            c.putInt("Kind", kind);
            c.putUUID("Owner", owner);
            c.putString("Name", name);
            if (!item.isEmpty()) c.put("Item", item.save(reg));
            c.putDouble("Score", score);
            c.putString("Words", words);
            return c;
        }

        @Nullable
        static Entry load(CompoundTag c, HolderLookup.Provider reg) {
            if (!c.hasUUID("Owner")) return null;
            Category cat;
            try {
                cat = Category.valueOf(c.getString("Cat"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            ItemStack item = c.contains("Item") ? ItemStack.parseOptional(reg, c.getCompound("Item")) : ItemStack.EMPTY;
            Entry e = new Entry(cat, c.getInt("Kind"), c.getUUID("Owner"), c.getString("Name"), item);
            e.score = c.getDouble("Score");
            e.words = c.getString("Words");
            return e;
        }
    }

    /** What class a thing goes in at the fair, or null. */
    @Nullable
    public static Category of(ItemStack s) {
        if (s.isEmpty()) return null;
        if (s.is(Items.BREAD)) return Category.BREAD;
        if (s.is(ItemTags.WOOL)) return Category.WOOL;
        if (Economy.RAW_FISH.test(s)) return Category.FISH;
        if (s.is(Items.HONEY_BOTTLE) || s.is(Items.HONEYCOMB)) return Category.HONEY;
        return null;
    }

    /** How many of a thing make an entry, at most. */
    static int most(Category c) {
        return switch (c) {
            case BREAD, WOOL -> 64;
            case FISH -> 1;
            case HONEY -> 16;
        };
    }

    /** The trades whose work each class is. */
    static boolean trade(Category c, AssistantEntity.StationTask t) {
        return switch (c) {
            case BREAD -> t == AssistantEntity.StationTask.FARM || t == AssistantEntity.StationTask.COOK;
            case WOOL -> t == AssistantEntity.StationTask.RANCH || t == AssistantEntity.StationTask.TAILOR;
            case FISH -> t == AssistantEntity.StationTask.FISH;
            case HONEY -> t == AssistantEntity.StationTask.BEEKEEP;
        };
    }

    // ------------------------------------------------------------------ the day

    /** Is the fair taking entries: its day (or its second evening, put off by rain), not judged yet, the judging not begun? */
    public static boolean open(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        long d = Festivals.dayThisYear(id, day, Festivals.Feast.FAIR);
        if (day != d && day != d + 1) return false;
        Festivals.Town town = Festivals.town(id);
        if (town.fairJudged == d || Festivals.keptFor(id, Festivals.Feast.FAIR) == d) return false;
        String now = Assemblies.now(id);
        return now == null || !now.equals(Festivals.Feast.FAIR.words);
    }

    /**
     * A player's entry: the stack in their hand (as much of it as an entry takes) into the fair's keeping.
     * One entry a class: a second takes the place of the first, which goes back. Returns what to tell them.
     */
    public static String enter(ServerLevel level, Player p, @Nullable Villages.Village v, ItemStack held) {
        if (v == null) return "There's no town near enough to hold a fair.";
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (!open(level, v)) {
            long n = Festivals.next(id, day, Festivals.Feast.FAIR);
            return Villages.name(id) + "'s fair is on day " + (n + 1) + (n - day == 1 ? " (tomorrow)" : n > day ? " (in " + (n - day) + " days)" : "")
                + ". Bring your best bread, wool, fish or honey to the board then.";
        }
        Category c = of(held);
        if (c == null) return "The fair takes bread, wool, a fish fresh from the water, or honey (bottles or combs): hold one and try again.";
        Festivals.Town town = Festivals.town(id);
        long d = Festivals.dayThisYear(id, day, Festivals.Feast.FAIR);
        if (town.fairDay != d) {
            returnAll(level, v, town);                                 // last year's, if any were never given back
            town.entries.clear();
            town.awarded.clear();
            town.fairDay = d;
        }
        for (Entry old : new ArrayList<>(town.entries)) {
            if (old.kind == Entry.PLAYER && old.owner.equals(p.getUUID()) && old.cat == c) {
                town.entries.remove(old);
                give(p, old.item.copy());
            }
        }
        ItemStack item = held.split(Math.min(most(c), held.getCount()));
        Entry e = new Entry(c, Entry.PLAYER, p.getUUID(), p.getName().getString(), item);
        score(e, null, d);
        town.entries.add(e);
        Festivals.dirty();
        return "Entered for " + c.title + " at " + Villages.name(id) + "'s fair: " + e.words
            + ". The elder judges this evening before the board; your entry comes back to you after.";
    }

    /** A player right-clicks the board on fair day with something for the fair: entered. Any other day, the board opens as ever. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof VillageBoardBlock)) return;
        ItemStack held = event.getItemStack();
        if (of(held) == null) return;
        Villages.Village v = Villages.nearest(level, event.getPos(), Villages.VILLAGE_RANGE);
        if (v == null || !open(level, v)) return;
        String said = enter(level, event.getEntity(), v, held);
        event.getEntity().sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.GOLD));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    // ------------------------------------------------------------------ the judging

    /** A deterministic number nought to one for this entrant on this day: how the day fell for its angler. */
    static double luck(UUID who, long day) {
        long h = who.getMostSignificantBits() * 31L + who.getLeastSignificantBits() + day * 1_000_003L;
        h ^= h >>> 29;
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return Math.floorMod(h, 1000L) / 1000.0;
    }

    /** The judge's mark for an entry, and what it is, in words ("twelve loaves", "a salmon of 8.4 lb"). */
    static void score(Entry e, @Nullable VillageFolkEntity maker, long day) {
        int n = e.item.getCount();
        int lv = maker == null ? 0 : Math.min(20, maker.veteranLevel());
        String what = e.item.getHoverName().getString().toLowerCase(Locale.ROOT);
        switch (e.cat) {
            case BREAD -> {
                e.score = n * 10;
                e.words = n + (n == 1 ? " loaf" : " loaves");
            }
            case WOOL -> {
                e.score = n * 10 + (e.item.is(Items.WHITE_WOOL) ? 0 : 5);
                e.words = n + " " + what;
            }
            case FISH -> {
                double base, spread;
                if (e.item.is(Items.SALMON)) { base = 5.0; spread = 5.0; }
                else if (e.item.is(Items.COD)) { base = 3.0; spread = 4.0; }
                else if (e.item.is(Items.PUFFERFISH)) { base = 1.5; spread = 1.5; }
                else { base = 0.5; spread = 1.0; }
                double lb = Math.round((base + spread * luck(e.owner, day) + lv * 0.05) * 10.0) / 10.0;
                e.score = lb * 10.0;
                e.words = ("aeiou".indexOf(what.isEmpty() ? 'x' : what.charAt(0)) >= 0 ? "an " : "a ") + what + " of "
                    + String.format(Locale.ROOT, "%.1f", lb) + " lb";
            }
            case HONEY -> {
                boolean bottles = e.item.is(Items.HONEY_BOTTLE);
                e.score = n * (bottles ? 30 : 10);
                e.words = n + (bottles ? (n == 1 ? " bottle of honey" : " bottles of honey") : (n == 1 ? " honeycomb" : " honeycombs"));
            }
        }
    }

    /** The folk's entries brought in (out of their packs, or the stores for their trade), and everything scored. */
    static void judge(ServerLevel level, Villages.Village v, Festivals.Town town, long d) {
        UUID id = v.id();
        if (town.fairDay != d) {
            returnAll(level, v, town);
            town.entries.clear();
            town.awarded.clear();
            town.fairDay = d;
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isBaby() && !f.isShowcase() && !f.isHired()) folk.add(f);
        }
        for (Category c : Category.values()) {
            // The trade's own hands only, the most skilled first (for bread, the cook before the farmers): in that
            // order they come forward, and a tie goes to the first.
            List<VillageFolkEntity> who = new ArrayList<>();
            for (VillageFolkEntity f : folk) if (trade(c, f.stationTask())) who.add(f);
            who.sort(Comparator.comparingInt((VillageFolkEntity f) -> f.stationTask() == AssistantEntity.StationTask.COOK ? 0 : 1)
                .thenComparingInt(f -> -f.veteranLevel()));
            boolean storesIn = false;
            for (VillageFolkEntity f : who) {
                int already = 0;
                for (Entry e : town.entries) if (e.cat == c && e.kind != Entry.PLAYER) already++;
                if (already >= EACH) break;
                boolean entered = false;
                for (Entry e : town.entries) if (e.cat == c && e.owner.equals(f.getUUID())) entered = true;
                if (entered) continue;
                ItemStack mine = c == Category.BREAD ? ItemStack.EMPTY : fromPack(f, c);    // a folk's loaves are its rations
                if (!mine.isEmpty()) {
                    town.entries.add(new Entry(c, Entry.FOLK, f.getUUID(), f.displayNameCap(), mine));
                    continue;
                }
                if (storesIn) continue;
                ItemStack theirs = fromStores(level, v, c);
                if (theirs.isEmpty()) continue;
                storesIn = true;
                town.entries.add(new Entry(c, Entry.STORES, f.getUUID(), f.displayNameCap(), theirs));
            }
        }
        for (Entry e : town.entries) {
            VillageFolkEntity maker = e.kind == Entry.PLAYER ? null
                : level.getEntity(e.owner) instanceof VillageFolkEntity f ? f : null;
            score(e, maker, d);
        }
        Festivals.dirty();
    }

    /** The best it has of a class in its own pack (never a keepsake), taken out of it; or nothing. */
    static ItemStack fromPack(VillageFolkEntity f, Category c) {
        var pack = f.getInventoryItems();
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || of(s) != c || Homes.isKeepsake(s)) continue;
            double mark = c == Category.FISH ? (s.is(Items.SALMON) ? 4 : s.is(Items.COD) ? 3 : s.is(Items.PUFFERFISH) ? 2 : 1)
                : c == Category.HONEY ? Math.min(most(c), s.getCount()) * (s.is(Items.HONEY_BOTTLE) ? 3 : 1)
                : Math.min(most(c), s.getCount());
            if (mark > bestScore) { bestScore = mark; best = i; }
        }
        if (best < 0) return ItemStack.EMPTY;
        ItemStack s = pack.get(best);
        ItemStack out = s.split(Math.min(most(c), s.getCount()));
        if (s.isEmpty()) pack.set(best, ItemStack.EMPTY);
        return out;
    }

    /** The best of the stores' of a class, entered on its trade's behalf: the biggest batch of one kind, the biggest fish. */
    static ItemStack fromStores(ServerLevel level, Villages.Village v, Category c) {
        UUID id = v.id();
        return switch (c) {
            case BREAD -> takeUpTo(level, v, s -> s.is(Items.BREAD), most(c));
            case WOOL -> {
                ItemStack first = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL));
                if (first.isEmpty()) yield ItemStack.EMPTY;
                ItemStack more = takeUpTo(level, v, s -> s.is(first.getItem()), most(c) - 1);
                yield first.copyWithCount(1 + more.getCount());
            }
            case FISH -> {
                for (var kind : new net.minecraft.world.item.Item[]{ Items.SALMON, Items.COD, Items.PUFFERFISH, Items.TROPICAL_FISH }) {
                    ItemStack one = Crafts.takeOne(level, v, s -> s.is(kind));
                    if (!one.isEmpty()) yield one;
                }
                yield ItemStack.EMPTY;
            }
            case HONEY -> {
                ItemStack bottles = takeUpTo(level, v, s -> s.is(Items.HONEY_BOTTLE), most(c));
                yield !bottles.isEmpty() ? bottles : takeUpTo(level, v, s -> s.is(Items.HONEYCOMB), most(c));
            }
        };
    }

    /** As many as the stores have of a thing, up to {@code n}, as one stack (of the first found). */
    static ItemStack takeUpTo(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int n) {
        if (n <= 0) return ItemStack.EMPTY;
        int have = Math.min(n, Market.stock(level, v.id(), what));
        if (have <= 0) return ItemStack.EMPTY;
        ItemStack first = Crafts.takeOne(level, v, what);
        if (first.isEmpty()) return ItemStack.EMPTY;
        int more = have - 1;
        if (more > 0 && !TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, first), more)) more = 0;
        return first.copyWithCount(1 + more);
    }

    /** Each class's entries, the best first; a tie to the first entered (the list's order: a sort that keeps it). */
    static List<Entry> ranked(Festivals.Town town, Category c) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : town.entries) if (e.cat == c) out.add(e);
        out.sort(Comparator.comparingDouble((Entry e) -> -e.score));
        return out;
    }

    /** The judging, line by line, before the board (Festivals.script): each class's entries named, its ribbon given. */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        Villages.Village v = Villages.get(a.village);
        if (v == null) return;
        long d = Festivals.dueOf(a.subject);
        Festivals.Town town = Festivals.town(a.village);
        judge(level, v, town, d);
        String name = Villages.name(a.village);
        s.add(new Assemblies.Line(null, "Welcome, all, to the " + name + " fair! The judging begins.", '!', null));
        boolean any = false;
        for (Category c : Category.values()) {
            List<Entry> es = ranked(town, c);
            if (es.isEmpty()) continue;
            any = true;
            List<String> names = new ArrayList<>();
            for (Entry e : es) names.add(e.name);
            s.add(new Assemblies.Line(null, "For " + c.title + ", " + es.size() + (es.size() == 1 ? " entry: " : " entries: ")
                + String.join(", ", names) + ".", '?', null));
            Entry w = es.get(0);
            s.add(new Assemblies.Line(null, "The ribbon for " + c.title + " goes to " + w.name + ", for " + w.words + "!", '!',
                () -> award(level, v, town, w, d)));
        }
        if (!any) s.add(new Assemblies.Line(null, "Not one entry this year! Next summer I hope to see your best.", '~', null));
        s.add(new Assemblies.Line(null, FolkTalk.pick(r, "Well done, everybody — take your entries home!", "That's the fair. Thank you all!"),
            '!', () -> finish(level, v, d, true)));
    }

    /** A class's ribbon and purse to its winner, once. Returns what was given, in words. */
    static String award(ServerLevel level, Villages.Village v, Festivals.Town town, Entry w, long d) {
        if (!town.awarded.add(w.cat.name())) return "";
        UUID id = v.id();
        int year = Seasons.year(id, d);
        String title = "Ribbon: " + w.cat.ribbon + ", Year " + year;
        ItemStack paper = Crafts.takeOne(level, v, s -> s.is(Items.PAPER) && !s.has(DataComponents.CUSTOM_NAME));
        ItemStack ribbon = ItemStack.EMPTY;
        if (!paper.isEmpty()) {
            ribbon = paper.copyWithCount(1);
            ribbon.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(ChatFormatting.BLUE));
            ribbon.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(Villages.name(id) + " fair, day " + (d + 1)),
                Component.literal(w.words))));
        }
        int coins = Ledger.takeCoins(id, PURSE);
        if (coins > 0) Economy.spent(id, coins);
        long day = level.getDayTime() / 24000L;
        if (w.kind == Entry.PLAYER) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(w.owner);
            List<ItemStack> prize = new ArrayList<>();
            if (!ribbon.isEmpty()) prize.add(ribbon);
            if (coins > 0) prize.add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
            for (ItemStack s : prize) {
                if (p != null) give(p, s);
                else town.owed.add(new Festivals.Owed(w.owner, w.name, s));
            }
            if (p != null) p.sendSystemMessage(Component.literal("You won " + w.cat.title + " at " + Villages.name(id) + "'s fair with "
                + w.words + "!" + (ribbon.isEmpty() ? "" : " A blue ribbon") + (coins > 0 ? (ribbon.isEmpty() ? " " : " and ") + coins
                + (coins == 1 ? " coin" : " coins") + " from the treasury." : ".")).withStyle(ChatFormatting.GOLD));
        } else if (level.getEntity(w.owner) instanceof VillageFolkEntity f && f.isAlive()) {
            f.earn(coins);
            if (!ribbon.isEmpty()) {
                Homes.keepsake(ribbon, f);
                ItemStack left = f.insertGiven(ribbon);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            }
            f.persona().remember(day, "I won the ribbon for " + w.cat.title + " at the fair", 5);
            f.persona().gotAGift(day, "the fair");
            f.sayLater(FolkTalk.pick(f.getRandom(), "Me? Oh, thank you!", "A blue ribbon! Wait till I tell everyone!",
                "Years I've waited for this!"), 30);
        } else if (!ribbon.isEmpty()) {
            Crafts.store(level, v, ribbon);                             // its winner gone: the ribbon is the town's
        }
        Festivals.dirty();
        return w.name + (ribbon.isEmpty() ? "" : ", a ribbon") + (coins > 0 ? ", " + coins + " coins" : "");
    }

    /**
     * The fair over: every class's ribbon given (if the gathering did not get to it), every entry back where it
     * came from, the results into the books and the chronicle. Once.
     */
    static void finish(ServerLevel level, Villages.Village v, long d, boolean gathered) {
        Festivals.Town town = Festivals.town(v.id());
        if (town.fairJudged == d) return;
        if (!gathered) judge(level, v, town, d);                     // nobody gathered: the folk's entries brought to the board now
        List<String> results = new ArrayList<>();
        for (Category c : Category.values()) {
            List<Entry> es = ranked(town, c);
            if (es.isEmpty()) continue;
            Entry w = es.get(0);
            award(level, v, town, w, d);
            results.add(c.ribbon + ": " + w.name + (w.kind == Entry.PLAYER ? " (a guest)" : "") + ", " + w.words);
        }
        returnAll(level, v, town);
        town.entries.clear();
        town.fairJudged = d;
        town.fairResults.clear();
        town.fairResults.addAll(results);
        Festivals.dirty();
        long day = level.getDayTime() / 24000L;
        Villages.tell(v.id(), day, results.isEmpty() ? "the town fair was held, and nobody entered a thing"
            : "the town fair was held" + (gathered ? " before the board" : "") + ": " + String.join("; ", results));
    }

    /** Every entry back: to its folk's pack (else the stores), to the stores, or to its player (or kept for them). */
    static void returnAll(ServerLevel level, Villages.Village v, Festivals.Town town) {
        for (Entry e : town.entries) {
            if (e.item.isEmpty()) continue;
            ItemStack it = e.item.copy();
            switch (e.kind) {
                case Entry.PLAYER -> {
                    ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.owner);
                    if (p != null) {
                        give(p, it);
                        p.sendSystemMessage(Component.literal("Your entry at " + Villages.name(v.id()) + "'s fair is back with you: " + e.words + ".")
                            .withStyle(ChatFormatting.GRAY));
                    } else {
                        town.owed.add(new Festivals.Owed(e.owner, e.name, it));
                    }
                }
                case Entry.FOLK -> {
                    ItemStack left = level.getEntity(e.owner) instanceof VillageFolkEntity f && f.isAlive() ? f.insertGiven(it) : it;
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                }
                default -> Crafts.store(level, v, it);
            }
        }
        town.entries.clear();
        Festivals.dirty();
    }

    private static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /** Once a second (Festivals.tick): owed things handed back to players in the world; a fair whose evenings went by, judged. */
    static void tick(ServerLevel level, Villages.Village v, Festivals.Town town, long day, long t) {
        if (!town.owed.isEmpty()) {
            boolean changed = false;
            for (Festivals.Owed o : new ArrayList<>(town.owed)) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(o.player());
                if (p == null) continue;
                give(p, o.item().copy());
                p.sendSystemMessage(Component.literal("From " + Villages.name(v.id()) + "'s fair, kept for you: "
                    + o.item().getCount() + " " + o.item().getHoverName().getString() + ".").withStyle(ChatFormatting.GOLD));
                town.owed.remove(o);
                changed = true;
            }
            if (changed) Festivals.dirty();
        }
        // Entries waiting on a fair whose two evenings have gone by (a raid, two wet nights): judged at the board.
        if (town.fairDay >= 0 && town.fairJudged != town.fairDay && !town.entries.isEmpty()
                && (day > town.fairDay + 1 || day == town.fairDay + 1 && t >= 14000L)) {
            String now = Assemblies.now(v.id());
            if (now == null || !now.equals(Festivals.Feast.FAIR.words)) {
                finish(level, v, town.fairDay, false);
                Festivals.kept(v.id(), Festivals.Feast.FAIR, town.fairDay, day);
            }
        }
    }

    // ------------------------------------------------------------------ for the tests and the commands

    /** The fair's entries just now: "class|name|kind|count|score|words". */
    public static List<String> entriesForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Entry e : Festivals.town(village).entries) {
            out.add(e.cat + "|" + e.name + "|" + e.kind + "|" + e.item.getCount() + "|" + String.format(Locale.ROOT, "%.1f", e.score) + "|" + e.words);
        }
        return out;
    }

    /** The last fair's ribbons, as the board and the books give them. */
    public static List<String> resultsForTests(UUID village) {
        return new ArrayList<>(Festivals.town(village).fairResults);
    }

    /** /village fair: the next fair, its entries, the last ribbons. */
    public static String status(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        long n = Festivals.next(id, day, Festivals.Feast.FAIR);
        Festivals.Town town = Festivals.town(id);
        StringBuilder sb = new StringBuilder("FAIR " + Villages.name(id) + ": ");
        sb.append(open(level, v) ? "today! Entries are open at the board (right-click it with your entry, or /village fair enter)."
            : "next on day " + (n + 1) + (n - day == 1 ? " (tomorrow)." : " (in " + (n - day) + " days)."));
        if (!town.entries.isEmpty()) {
            List<String> es = new ArrayList<>();
            for (Entry e : town.entries) es.add(e.cat.ribbon + ": " + e.name + " (" + e.words + ")");
            sb.append(" Entries: ").append(String.join("; ", es)).append('.');
        }
        if (!town.fairResults.isEmpty()) sb.append(" Last ribbons (day ").append(town.fairJudged + 1).append("): ")
            .append(String.join("; ", town.fairResults)).append('.');
        return sb.toString();
    }
}
