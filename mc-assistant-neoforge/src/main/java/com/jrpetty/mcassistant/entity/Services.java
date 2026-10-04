package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a village does for a player who asks.
 * <ul>
 * <li><b>A house of your own, to order.</b> Bring the materials (sixty-four planks, thirty-two
 *     cobblestone, eight glass) and say "build me a house": they go into the stores, and the
 *     builders put up a house for you on one of the town's lots, as they do for an honoured
 *     guest. Its key is yours when it stands.</li>
 * <li><b>The town ledger.</b> A book of the village's affairs as they stand today: the
 *     treasury, what is in the stores, who lives where, what is going up and what is next,
 *     what the village is short of, and what is on the quest board.</li>
 * <li><b>The storekeeper serves you.</b> Ask "could I have 16 bread?" A friend of the village
 *     (or a citizen) is given it, a tool or a weapon lent (bring it back within five days); a
 *     stranger can buy it, at the market's price and a little over.</li>
 * </ul>
 */
public final class Services {

    private Services() {}

    // ------------------------------------------------------------------ a house to order

    /** What a house costs a player who brings its makings. */
    static final Object[][] BILL = {
        { "planks", 64, (Predicate<ItemStack>) s -> s.is(ItemTags.PLANKS) },
        { "cobblestone", 32, (Predicate<ItemStack>) s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.STONE) },
        { "glass", 8, (Predicate<ItemStack>) s -> s.is(Items.GLASS) || s.is(Items.GLASS_PANE) },
    };

    /** "Build me a house." */
    @SuppressWarnings("unchecked")
    public static String commission(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Build you a house? Where — we've no village.";
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (!title.atLeast(Standing.Title.STRANGER) || Laws.banished(village, p.getUUID(), day)) {
            return "Build a house for you? Nobody here would lift a finger.";
        }
        Chronicle.Guest g = Chronicle.guest(village, p.getUUID());
        if (g != null) return g.built ? "You've a house here already — your key opens it." : "We're building yours already. Be patient!";
        if (!Villages.hasBuilt(village, "storage")) return "We've not even a storehouse yet. Ask again when we're more settled.";
        List<String> missing = new ArrayList<>();
        for (Object[] line : BILL) {
            int have = carried(p, (Predicate<ItemStack>) line[2]);
            if (have < (Integer) line[1]) missing.add(((Integer) line[1] - have) + " more " + line[0]);
        }
        if (!missing.isEmpty()) {
            return "Bring the makings and our builders will put you up a house on one of the lots: 64 planks, 32 cobblestone "
                + "and 8 glass. You still need " + String.join(", ", missing) + ".";
        }
        for (Object[] line : BILL) {
            for (ItemStack taken : takeFrom(p, (Predicate<ItemStack>) line[2], (Integer) line[1])) {
                ItemStack left = Market.intoStores(level, village, taken);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            }
        }
        String name = p.getName().getString();
        Chronicle.welcome(village, p.getUUID(), name);
        Villages.tell(village, day, name + " brought the makings of a house, and the builders took it on");
        f.persona().feelFor(p.getUUID(), name, 5);
        Standing.stir(village, p.getUUID());
        return "That's everything! Into the stores it goes. Our builders will raise your house on one of the lots — "
            + "come and see me for the key when it stands.";
    }

    static int carried(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    static List<ItemStack> takeFrom(Player p, Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize() && n > 0; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int k = Math.min(n, s.getCount());
            out.add(s.split(k));
            n -= k;
        }
        p.getInventory().setChanged();
        return out;
    }

    // ------------------------------------------------------------------ the town ledger

    private static final int LINES_PER_PAGE = 12;
    private static final int CHARS_PER_LINE = 19;

    /** The town ledger, as it stands today. */
    public static ItemStack ledger(ServerLevel level, UUID village) {
        String name = Villages.name(village);
        long day = level.getDayTime() / 24000L;
        Contentment.View mood = Contentment.of(level, village);
        List<String> entries = new ArrayList<>();
        // The stores.
        Map<String, Integer> stock = new HashMap<>();
        for (BlockPos at : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(at) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) stock.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(stock.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        entries.add("§lIn the stores§r");
        if (sorted.isEmpty()) entries.add("Nothing at all.");
        StringBuilder chunk = new StringBuilder();
        int k = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            if (k++ >= 48) break;
            chunk.append(e.getValue()).append(' ').append(e.getKey().toLowerCase(Locale.ROOT)).append('\n');
            if (k % 8 == 0) { entries.add(chunk.toString().trim()); chunk.setLength(0); }
        }
        if (chunk.length() > 0) entries.add(chunk.toString().trim());
        // Who lives where.
        entries.add("§lWho lives where§r");
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) folk.add(f);
        folk.sort(java.util.Comparator.comparing(VillageFolkEntity::displayNameCap));
        for (VillageFolkEntity f : folk) {
            BlockPos bed = f.bedPos();
            String home = bed == null ? "no bed yet" : "sleeps at " + bed.getX() + ", " + bed.getZ() + houseOf(village, bed);
            entries.add(f.displayNameCap() + " (" + (f.isBaby() ? "a child" : f.stationTask().title.toLowerCase(Locale.ROOT)) + "): " + home);
        }
        for (Chronicle.Guest g : Chronicle.guests(village)) {
            entries.add(g.name + " (a guest" + (Citizens.is(village, g.player) ? ", citizen" : "") + "): "
                + (g.built ? "a house at " + g.x + ", " + g.z : "a house being built"));
        }
        // What is going up, and what is next.
        entries.add("§lBuilding§r");
        List<String> next = Villages.projectsWanted(village);
        if (next.isEmpty()) entries.add("Nothing planned.");
        else {
            List<String> words = new ArrayList<>();
            for (int i = 0; i < Math.min(5, next.size()); i++) words.add(Villages.spoken(next.get(i)));
            entries.add("Next: " + String.join(", then ", words) + ".");
        }
        entries.add("Built so far: " + Ledger.buildings(village).size() + " buildings.");
        // The elder's orders.
        Orders.Order order = Orders.current(village);
        if (order != null) {
            entries.add("§lThe elder's orders§r");
            entries.add(order.title + ". " + order.words);
        }
        // What it is short of.
        entries.add("§lShort of§r");
        List<Villages.Need> needs = Villages.needs(level, village);
        if (needs.isEmpty()) entries.add("Nothing — the village is ready for its next age.");
        for (int i = 0; i < Math.min(6, needs.size()); i++) {
            Villages.Need n = needs.get(i);
            entries.add(n.what() + (n.amount() > 0 ? ": " + n.amount() + " more" : ""));
        }
        // The quest board.
        List<Quests.Posting> board = Quests.postings(village);
        if (!board.isEmpty()) {
            entries.add("§lOn the quest board§r");
            for (Quests.Posting q : board) entries.add(q.words() + " — " + q.reward + " coins" + (q.takenBy == null ? "" : " (" + q.takenName + ")"));
        }
        String front = name + "\n\nThe Town Ledger\n\nDay " + day + "\n" + Villages.ageOf(village).label + "\n"
            + Villages.headcount(village) + " people\nTreasury: " + Ledger.coins(village) + " coins\n"
            + (mood == null ? "" : "Contentment: " + mood.score() + " (" + mood.word() + ")");
        return book(name + " Ledger", "the storekeeper of " + name, front, entries);
    }

    /** "(the 3rd house on the north street)" — or nothing, if the bed is in no building the village knows. */
    private static String houseOf(UUID village, BlockPos bed) {
        int n = 0;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!b.structure().startsWith("house") && !b.structure().equals("guesthouse") && !b.structure().equals("barracks")) continue;
            n++;
            if (b.anchor().distSqr(bed) <= 7 * 7) return " (" + Villages.spoken(b.structure()).replaceFirst("^a new ", "") + " no. " + n + ")";
        }
        return "";
    }

    static ItemStack book(String title, String author, String front, List<String> entries) {
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(Component.literal(front)));
        StringBuilder page = new StringBuilder();
        int used = 0;
        for (String entry : entries) {
            int cost = (entry.replace("§l", "").replace("§r", "").length() + CHARS_PER_LINE - 1) / CHARS_PER_LINE + 1
                + (int) entry.chars().filter(c -> c == '\n').count();
            if (used + cost > LINES_PER_PAGE + 1 && page.length() > 0) {
                if (pages.size() >= 99) break;
                pages.add(Filterable.passThrough(Component.literal(page.toString())));
                page.setLength(0);
                used = 0;
            }
            page.append(entry).append("\n\n");
            used += cost;
        }
        if (page.length() > 0 && pages.size() < 100) pages.add(Filterable.passThrough(Component.literal(page.toString())));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(Filterable.passThrough(title.length() <= 32 ? title : title.substring(0, 32)), author, 0, pages, true));
        return book;
    }

    /** "Could I see the ledger?" */
    public static String ledgerFor(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "We keep no books — there's no village to keep them for.";
        ItemStack book = ledger(level, village);
        if (!p.getInventory().add(book)) p.drop(book, false);
        return "Here's the town ledger, written up to today: the stores, who lives where, what's going up and what we're short of.";
    }

    // ------------------------------------------------------------------ the storekeeper

    /** What each player has borrowed from each village's stores: the thing, and the day. */
    private record Loan(Item item, String name, long day) {}

    private static final Map<UUID, Map<UUID, List<Loan>>> LOANS = new ConcurrentHashMap<>();
    /** How much a friend has been given free today. */
    private static final Map<String, Integer> GIVEN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOANS.clear();
        GIVEN.clear();
        LOANS_READ.clear();
    }

    /** The villages whose loans have been read back from the ledger since the server started. */
    private static final java.util.Set<UUID> LOANS_READ = ConcurrentHashMap.newKeySet();

    /**
     * A village's loans, kept with the world (Ledger note "loans"): a restart used to forget them,
     * and the pickaxe a player had borrowed became theirs to keep with nobody any the wiser.
     */
    private static Map<UUID, List<Loan>> loansOf(UUID village) {
        Map<UUID, List<Loan>> all = LOANS.computeIfAbsent(village, k -> new ConcurrentHashMap<>());
        if (LOANS_READ.add(village)) {
            String saved = com.jrpetty.mcassistant.village.Ledger.note(village, "loans");
            if (saved != null && !saved.isEmpty()) {
                for (String one : saved.split(";")) {
                    String[] f = one.split("\\|");
                    if (f.length != 4) continue;
                    try {
                        Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(f[1]));
                        all.computeIfAbsent(UUID.fromString(f[0]), k -> new ArrayList<>()).add(new Loan(it, f[2], Long.parseLong(f[3])));
                    } catch (RuntimeException ignored) { }
                }
            }
        }
        return all;
    }

    private static void saveLoans(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, List<Loan>> e : LOANS.getOrDefault(village, Map.of()).entrySet()) {
            for (Loan l : e.getValue()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append('|').append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(l.item()))
                  .append('|').append(l.name().replace("|", " ").replace(";", " ")).append('|').append(l.day());
            }
        }
        com.jrpetty.mcassistant.village.Ledger.note(village, "loans", sb.toString());
    }

    private static List<Item> names;
    private static List<String> nameWords;

    /** The item a line of words names, longest name first ("iron pickaxe" before "iron"). */
    @Nullable
    public static Item itemNamed(String text) {
        if (names == null) {
            List<Object[]> all = new ArrayList<>();
            for (Item it : BuiltInRegistries.ITEM) {
                if (it == Items.AIR) continue;
                String n = new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT);
                if (n.length() >= 3) all.add(new Object[]{ it, n });
            }
            all.sort((a, b) -> ((String) b[1]).length() - ((String) a[1]).length());
            List<Item> its = new ArrayList<>();
            List<String> ws = new ArrayList<>();
            for (Object[] o : all) { its.add((Item) o[0]); ws.add((String) o[1]); }
            names = its;
            nameWords = ws;
        }
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ") + " ";
        for (int i = 0; i < names.size(); i++) {
            String n = nameWords.get(i);
            if (t.contains(" " + n + " ") || t.contains(" " + n + "s ") || t.contains(" " + n + "es ")) return names.get(i);
        }
        return null;
    }

    private static final Pattern COUNT = Pattern.compile("\\b(\\d{1,3})\\b");

    static int countIn(String text) {
        Matcher m = COUNT.matcher(text);
        if (m.find()) return Math.max(1, Math.min(64, Integer.parseInt(m.group(1))));
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains(" a stack") || t.contains(" stack of")) return 64;
        if (t.contains(" a dozen")) return 12;
        if (t.contains(" a few")) return 4;
        return 1;
    }

    /** The one who keeps the village's stores: its storekeeper, else its elder. */
    @Nullable
    static VillageFolkEntity keeper(UUID village) {
        VillageFolkEntity elder = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            if (f.stationTask() == AssistantEntity.StationTask.STORE) return f;
            if (f.isElder()) elder = f;
        }
        return elder;
    }

    /** "Could I have 16 bread from the stores?" */
    public static String stores(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Stores? I've none to give from.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "Stores? I've none to give from.";
        VillageFolkEntity keeper = keeper(village);
        if (keeper != null && keeper != f) {
            return "That's for " + keeper.displayNameCap() + " to say — " + (keeper.stationTask() == AssistantEntity.StationTask.STORE
                ? "they keep the stores." : "the elder keeps the stores till we've a storekeeper.");
        }
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(village, p.getUUID(), day)) return "Not a crumb. Not for you.";
        Item it = itemNamed(text);
        if (it == null) return "What would you like from the stores? Say what, and how many — \"could I have 16 bread?\"";
        int want = countIn(text);
        ItemStack sample = new ItemStack(it);
        want = Math.min(want, sample.getMaxStackSize());
        Predicate<ItemStack> what = s -> s.is(it);
        int have = Market.stock(level, village, what);
        String word = sample.getHoverName().getString().toLowerCase(Locale.ROOT);
        if (have <= 0) return "We've no " + word + " in the stores, I'm afraid.";
        int n = Math.min(want, have);
        boolean friend = Citizens.is(village, p.getUUID()) || f.persona().affinity(p.getUUID()) >= 30;
        String name = p.getName().getString();
        // A tool, a weapon, armour: lent to a friend, to be brought back.
        if (friend && sample.getMaxStackSize() == 1) {
            List<Loan> mine = loansOf(village).computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
            if (mine.size() >= 3) return "You've three of our things already. Bring something back first.";
            List<ItemStack> got = take(level, village, what, 1);
            if (got.isEmpty()) return "Somebody's just taken the last one.";
            for (ItemStack s : got) give(p, s);
            mine.add(new Loan(it, word, day));
            saveLoans(village);
            f.persona().remember(day, "I lent " + name + " a " + word, 2);
            return "Here — take the " + word + ". Bring it back when you're done with it, within five days, mind.";
        }
        // A friend's share, free, up to a stack a day.
        String key = village + "/" + p.getUUID() + "/" + day;
        int givenToday = GIVEN.getOrDefault(key, 0);
        if (friend && givenToday + n <= 64) {
            List<ItemStack> got = take(level, village, what, n);
            int count = 0;
            for (ItemStack s : got) { count += s.getCount(); give(p, s); }
            GIVEN.put(key, givenToday + count);
            if (GIVEN.size() > 256) GIVEN.clear();
            return "Take " + count + " " + word + ", friend — there's no charge between us." + (count < want ? " That's all we have." : "");
        }
        // Anybody else pays: the market's price and a little over.
        Market.Good g = Market.goodFor(sample);
        double each = g == null ? 0.5 : g.value();
        int price = (int) Math.max(1, Math.round(each * n * 1.25));
        if (title == Standing.Title.UNWELCOME) price *= 2;
        int coins = Market.coinsHeld(p);
        if (coins < price) return n + " " + word + " would be " + price + " coins. You've " + coins + ".";
        List<ItemStack> got = take(level, village, what, n);
        int count = 0;
        for (ItemStack s : got) { count += s.getCount(); give(p, s); }
        if (count == 0) return "Somebody's just taken the last of it.";
        price = (int) Math.max(1, Math.round(each * count * 1.25)) * (title == Standing.Title.UNWELCOME ? 2 : 1);
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        return "That's " + count + " " + word + " for " + price + (price == 1 ? " coin" : " coins") + ". "
            + (friend ? "" : "Get to know us, and you'll not pay for bread.");
    }

    private static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /** Up to n of a thing out of the stores, as the stacks themselves (enchantments and all). */
    static List<ItemStack> take(ServerLevel level, UUID village, Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (BlockPos at : Villages.storeChests(level, village)) {
            if (n <= 0) break;
            if (!(level.getBlockEntity(at) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && n > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int k = Math.min(n, s.getCount());
                out.add(s.split(k));
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                n -= k;
            }
            c.setChanged();
        }
        return out;
    }

    /** "Here's your pickaxe back": a loan returned, if the player has one and carries the thing. Empty if not. */
    public static String returnLoan(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "";
        List<Loan> mine = loansOf(village).get(p.getUUID());
        if (mine == null || mine.isEmpty()) return "";
        List<String> back = new ArrayList<>();
        for (java.util.Iterator<Loan> i = mine.iterator(); i.hasNext(); ) {
            Loan l = i.next();
            List<ItemStack> got = takeFrom(p, s -> s.is(l.item()), 1);
            if (got.isEmpty()) continue;
            for (ItemStack s : got) {
                ItemStack left = Market.intoStores(level, village, s);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            }
            back.add(l.name());
            i.remove();
        }
        if (back.isEmpty()) return "";
        saveLoans(village);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 3);
        return "The " + String.join(" and the ", back) + " — back safe and sound. Thank you! Ask whenever you need it again.";
    }

    /** Loans five days overdue are given up on, and remembered against the borrower. */
    public static void overdue(ServerLevel level, UUID village, long day) {
        Map<UUID, List<Loan>> all = loansOf(village);
        boolean changed = false;
        for (Map.Entry<UUID, List<Loan>> e : all.entrySet()) {
            for (java.util.Iterator<Loan> i = e.getValue().iterator(); i.hasNext(); ) {
                Loan l = i.next();
                if (day - l.day() <= 5) continue;
                i.remove();
                changed = true;
                Player p = level.getPlayerByUUID(e.getKey());
                String who = p == null ? "a borrower" : p.getName().getString();
                Villages.tell(village, day, who + " never brought back the " + l.name() + " they borrowed from the stores");
                for (AssistantEntity a : Villages.folkOf(village)) {
                    if (a instanceof VillageFolkEntity f) f.persona().feelFor(e.getKey(), who, -4);
                }
                Standing.stir(village, e.getKey());
            }
        }
        if (changed) saveLoans(village);
    }

    /** For the status: what is out on loan. */
    public static Map<String, Integer> onLoan(UUID village) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (List<Loan> l : loansOf(village).values()) for (Loan x : l) out.merge(x.name(), 1, Integer::sum);
        return out;
    }
}
