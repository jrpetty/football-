package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [fashion] The tailor follows the fashion: its book of garments on order, made at the loom out of the stores' real
 * wool and leather (and gold for a waistcoat's buttons) and dyed with the stores' dyes, which come of the meadows'
 * flowers, the cocoa, the squid's ink, lapis and bone meal.
 * <ul>
 * <li><b>The book.</b> A folk that wants the season's look and finds none in the town's stock has it put on the
 *     tailor's book (Fashion.look): one thing each, the setter's and the show's rosette first, then the oldest. The
 *     shop's books hear of each as a sale it had not got, so a thing everybody wants is dearer (PriceIndex).</li>
 * <li><b>The making.</b> Between its beds and the watch's leather (Crafts.tailor), a piece at a time: the garment
 *     made by its recipe (data/mc_assistant/recipe), the whole way from what the stores hold (Bench: a sheep's wool,
 *     the string spun from it, a cow's leather), and its dye out of the stores or made there and then by the game's own
 *     recipes (a poppy into red dye, red and blue into purple). Then dyed, as the game dyes leather (the garment and
 *     the dye together), finished as good as its hand (Craftsmanship: its mark on it), and put in the stores for the
 *     one who ordered it to buy. Nothing above the town's age: a waistcoat waits for the Iron Age's gold.</li>
 * <li><b>Short of a dye.</b> The book says so ("short of a red dye"), the stores' books hear of the dye as wanted, and
 *     a farm hand is sent to pick the flowers that make it, out in the country round the town (never a garden's), or,
 *     with none to be found, to sow some with bone meal on the grass (TownJobs, "dyeflowers").</li>
 * <li><b>The season's stock.</b> With nothing on the book, early in a season, the season's thing in the season's
 *     colour, for the shop's shelves, while the stores have wool to spare.</li>
 * <li><b>The show's rosette.</b> The day before a fashion show (FashionShow), a blue rosette of wool, paper and string.</li>
 * </ul>
 */
public final class Tailoring {

    private Tailoring() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    static final String BOOK = "fashion.book", MADE = "fashion.made";
    /** How long a piece the stores could not run to waits before it is tried again (ticks). */
    static final long RETRY = 600L;
    /** The stores' wool kept by before the tailor makes for the shop's shelves (nobody's order). */
    static final int STOCK_WOOL = 12;
    /** The season's thing kept on the shelves in its colour, early in a season. */
    static final int STOCK = 2;

    /** One thing on the book: for whom (none: the shop's shelves, or the show), what, which colour, since when. */
    static final class Order {
        final int id;
        @Nullable final UUID folk;
        final String name;
        final Garment kind;
        final int colour;
        final long day;
        /** The setter's and the show's: made before the rest. */
        final boolean first;
        /** What it waits on, in words ("short of a red dye"), or nothing. */
        String status = "";
        long tried = -100000L;

        Order(int id, @Nullable UUID folk, String name, Garment kind, int colour, long day, boolean first) {
            this.id = id;
            this.folk = folk;
            this.name = name;
            this.kind = kind;
            this.colour = colour;
            this.day = day;
            this.first = first;
        }

        String words() {
            return kind.a(colour) + " for " + name;
        }
    }

    private static final Map<UUID, List<Order>> BOOKS = new ConcurrentHashMap<>();
    /** The dyes each town is short of for its book, and till when it looks for their flowers (game time). */
    private static final Map<UUID, Map<Item, Long>> DYES_WANTED = new ConcurrentHashMap<>();
    /** Where the next flower is that a hand is picking (or the grass it is sowing), and what. */
    private static final Map<UUID, BlockPos> FLOWER_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FLOWER_LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PICKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        BOOKS.clear();
        DYES_WANTED.clear();
        FLOWER_AT.clear();
        FLOWER_LOOKED.clear();
        PICKED.clear();
    }

    /** The town's book, the first first and then the oldest. */
    static List<Order> book(UUID village) {
        return BOOKS.computeIfAbsent(village, Tailoring::load);
    }

    /** Is something on the book for this folk? */
    static boolean onBook(UUID village, UUID folk) {
        for (Order o : book(village)) if (folk.equals(o.folk)) return true;
        return false;
    }

    /** What this folk's order waits on, for its card: " (short of a red dye)", or nothing. */
    static String statusFor(UUID village, UUID folk) {
        for (Order o : book(village)) if (folk.equals(o.folk)) return o.status.isEmpty() ? "" : " (" + o.status + ")";
        return "";
    }

    /**
     * On the book for this folk (one thing each: a new want takes the old one's place), and the shop's books told of it
     * as a sale it had not got (the prices: what is wanted and not there gets dearer).
     */
    static void order(ServerLevel level, Villages.Village v, VillageFolkEntity f, Garment kind, int colour, long day, boolean first) {
        UUID id = v.id();
        List<Order> b = book(id);
        b.removeIf(o -> f.getUUID().equals(o.folk));
        b.add(new Order(nextId(b), f.getUUID(), f.displayNameCap(), kind, colour, day, first));
        sort(b);
        f.style().ordered = true;
        Stockroom.missed(level, id, Stockroom.Seller.SHOP, kind.dyed(colour));
        f.brain("put " + kind.a(colour) + " on the tailor's book");
        save(id);
    }

    /** One for nobody in particular (the show's rosette, the shop's shelves). */
    static void orderFor(UUID village, String forWhom, Garment kind, int colour, long day, boolean first) {
        List<Order> b = book(village);
        b.add(new Order(nextId(b), null, forWhom, kind, colour, day, first));
        sort(b);
        save(village);
    }

    /** It has what it wanted another way (bought one made for somebody else, been given one): off the book. */
    static void done(UUID village, UUID folk) {
        if (book(village).removeIf(o -> folk.equals(o.folk))) save(village);
    }

    private static int nextId(List<Order> b) {
        int n = 0;
        for (Order o : b) n = Math.max(n, o.id);
        return n + 1;
    }

    private static void sort(List<Order> b) {
        b.sort(Comparator.comparingInt((Order o) -> o.first ? 0 : 1).thenComparingLong(o -> o.day).thenComparingInt(o -> o.id));
    }

    // ------------------------------------------------------------------ the tailor's turn (Crafts.tailor)

    /**
     * The tailor's turn at the fashion, between its beds and the watch's leather (Crafts.tailor): the first thing on
     * the book the town's age allows and the stores can run to, made and dyed; else, early in a season, the season's
     * thing for the shop. What it made, in words, or null.
     */
    @Nullable
    public static String work(ServerLevel level, Villages.Village v, VillageFolkEntity tailor, @Nullable BlockPos loom) {
        UUID id = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        Villages.Age age = Villages.ageOf(id);
        Bench.Hand hand = Bench.handOf(level, v, tailor, VillageFolkEntity.buildingFor(AssistantEntity.StationTask.TAILOR));
        String at = loom != null ? ", at the loom" : "";
        List<Order> b = book(id);
        for (Order o : new ArrayList<>(b)) {
            if (!o.status.isEmpty() && now - o.tried < RETRY && now >= o.tried) continue;
            Item it = o.kind.item();
            if (!Tiers.allows(level, age, it)) {
                o.status = Tiers.refusal(level, age, it);
                o.tried = now;
                continue;
            }
            ItemStack made = make(level, v, tailor, hand, o.kind, o.colour, o);
            // The show's rosette, for want of a blue dye, plain: there must be a rosette.
            if (made.isEmpty() && o.kind == Garment.ROSETTE) made = make(level, v, tailor, hand, o.kind, Garment.NATURAL, o);
            if (made.isEmpty()) continue;
            b.remove(o);
            save(id);
            if (o.folk != null && level.getEntity(o.folk) instanceof VillageFolkEntity buyer && buyer.isAlive()
                    && buyer.distanceToSqr(tailor) < 32 * 32) {
                buyer.sayLater(FolkTalk.pick(buyer.getRandom(), "My " + o.kind.noun + "'s ready? Oh, I can't wait!",
                    "Is that mine? " + Garment.colourCap(Garment.colourOf(made)) + " — perfect."), 60);
            }
            return o.kind.a(Garment.colourOf(made)) + " for " + o.name + at;
        }
        // Nothing on the book: the season's thing for the shop's shelves, early in the season, while wool is to spare.
        Fashion.Trend t = Fashion.trend(id);
        if (!t.set() || day - t.since > 4 || Crafts.stock(level, v, s -> s.is(ItemTags.WOOL)) < STOCK_WOOL) return null;
        Garment g = t.kind != null && t.kind.dyeable() && Tiers.allows(level, age, t.kind.item()) ? t.kind : Garment.WOOL_SCARF;
        int have = Fashion.stockOf(level, id, s -> Garment.of(s) == g && Garment.colourOf(s) == t.colour && !Fashion.secondHand(s), false);
        if (have >= STOCK) return null;
        ItemStack made = make(level, v, tailor, hand, g, t.colour, null);
        return made.isEmpty() ? null : g.a(t.colour) + " for the shop's shelves" + at;
    }

    /**
     * One garment made out of the stores, now: the thing by its recipe and its dye (out of the stores, or made of their
     * flowers), the whole way (Bench); dyed as the game dyes; finished as good as the hand; marked with whom it was made
     * for; into the stores. Nothing if the stores cannot run to it (and the book says what it is short of).
     */
    static ItemStack make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity tailor, Bench.Hand hand, Garment kind, int colour,
                          @Nullable Order o) {
        UUID id = v.id();
        List<Bench.Want> wants = new ArrayList<>();
        wants.add(Bench.Want.of(kind.item(), 1));
        Item dye = colour >= 0 && colour < Garment.NATURAL && kind.dyeable() ? DyeItem.byColor(DyeColor.byId(colour)) : null;
        if (dye != null) wants.add(Bench.Want.of(dye, 1));
        Bench.Plan p = Bench.plan(level, v, wants, hand);
        if (!p.ok()) {
            if (o != null) {
                o.status = "short of " + p.shortOf + (p.why.isEmpty() ? "" : " (" + p.why + ")");
                o.tried = level.getGameTime();
            }
            if (dye != null && (p.missing == dye || p.missing instanceof DyeItem || isFlower(p.missing))) wantDye(level, v, dye);
            return ItemStack.EMPTY;
        }
        if (!Bench.take(level, v, p, tailor)) return ItemStack.EMPTY;
        ItemStack g = kind.dyed(colour);
        if (tailor != null) g = Craftsmanship.finish(level, g, hand.skill(), hand.maker());
        if (o != null && o.folk != null) {
            CompoundTag mark = new CompoundTag();
            mark.putString("for", o.name);
            CustomData.update(DataComponents.CUSTOM_DATA, g, tag -> tag.put("mca_made_for", mark));
            ItemLore lore = g.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
            g.set(DataComponents.LORE, lore.withLineAdded(Component.literal("Made for " + o.name).withStyle(net.minecraft.ChatFormatting.GRAY)));
        }
        Crafts.store(level, v, g.copy());
        made(id, level.getDayTime() / 24000L);
        LOG.info("[MCA-FASHION] {}: {} made {} ({})", Villages.name(id), tailor == null ? "the tailor" : tailor.displayNameCap(),
            kind.a(colour), p.chain());
        return g;
    }

    // ------------------------------------------------------------------ dyes, and the flowers that make them

    /** A dye the book is short of: wanted in the stores' books (the price), and its flowers looked for, for a while. */
    static void wantDye(ServerLevel level, Villages.Village v, Item dye) {
        Map<Item, Long> wanted = DYES_WANTED.computeIfAbsent(v.id(), k -> new ConcurrentHashMap<>());
        Long until = wanted.get(dye);
        if (until == null || until < level.getGameTime()) Stockroom.missed(level, v.id(), Stockroom.Seller.STORES, new ItemStack(dye));
        wanted.put(dye, level.getGameTime() + 6000L);
    }

    private static boolean isFlower(@Nullable Item it) {
        return it instanceof BlockItem b && b.getBlock().defaultBlockState().is(BlockTags.FLOWERS);
    }

    /**
     * The flowers that make this dye, by the game's own recipes: a flower made into it (a poppy into red dye), or into
     * the dyes it is mixed of (purple: a red's and a blue's), two mixings deep.
     */
    static Set<Item> flowersFor(ServerLevel level, Item dye) {
        Set<Item> out = new LinkedHashSet<>();
        flowersFor(level, dye, out, 0, new HashSet<>());
        return out;
    }

    private static void flowersFor(ServerLevel level, Item dye, Set<Item> out, int depth, Set<Item> seen) {
        if (depth > 2 || !seen.add(dye)) return;
        for (RecipeBook.Way w : RecipeBook.waysFor(level, dye)) {
            if (w.fire() != RecipeBook.Fire.NONE) continue;
            for (RecipeBook.Part part : w.parts()) {
                for (ItemStack s : part.ingredient().getItems()) {
                    Item it = s.getItem();
                    if (isFlower(it)) out.add(it);
                    else if (it instanceof DyeItem && w.parts().size() > 1) flowersFor(level, it, out, depth + 1, seen);
                }
            }
        }
    }

    /**
     * The town's look at its dyes (Fashion.tick): before a fashion show, a rosette on the book; for a dye the book is
     * short of, a farm hand sent out to pick the flowers that make it, or, with none growing round the town, to sow
     * some with the stores' bone meal on the grass.
     */
    static void tick(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        FashionShow.rosetteDue(level, v, day);
        // An order a fortnight old that nothing has come of is let go: its folk wants afresh, or has gone off the idea.
        if (book(id).removeIf(o -> day - o.day > 14)) save(id);
        Map<Item, Long> wanted = DYES_WANTED.get(id);
        if (wanted == null || wanted.isEmpty()) return;
        long now = level.getGameTime();
        wanted.entrySet().removeIf(e -> e.getValue() < now);
        Set<Item> flowers = new LinkedHashSet<>();
        for (Item dye : wanted.keySet()) {
            if (Crafts.stock(level, v, s -> s.is(dye)) > 0) continue;            // in the stores now
            flowers.addAll(flowersFor(level, dye));
        }
        if (flowers.isEmpty()) return;
        BlockPos at = FLOWER_AT.get(id);
        if (at == null || !level.isLoaded(at) || !wanted(level, at, flowers)) {
            Long looked = FLOWER_LOOKED.get(id);
            if (looked != null && now - looked < 600L && now >= looked) return;     // looked lately, and none
            FLOWER_LOOKED.put(id, now);
            at = findFlower(level, v, flowers);
            if (at == null) at = grassToSow(level, v);
            if (at == null) return;
            FLOWER_AT.put(id, at);
        }
        BlockState st = level.getBlockState(at);
        boolean flower = st.is(BlockTags.FLOWERS);
        String what = flower ? "picking " + Bench.plural(st.getBlock().asItem().getDescription().getString().toLowerCase(Locale.ROOT))
            + " for the tailor's dyes" : "sowing flowers with bone meal for the tailor's dyes";
        if (!TownJobs.atWork(level, v, "dyeflowers", at, what, AssistantEntity.StationTask.FARM)) return;
        if (flower) {
            Item it = st.getBlock().asItem();
            level.destroyBlock(at, false);
            Crafts.store(level, v, new ItemStack(it));
            PICKED.merge(id, 1, Integer::sum);
            FLOWER_AT.remove(id);
            return;
        }
        // Bone meal on the grass: the stores' (a bone makes three), and whatever the meadow brings up.
        if (Crafts.stock(level, v, s -> s.is(Items.BONE_MEAL)) < 1 && Crafts.take(level, v, s -> s.is(Items.BONE), 1)) {
            Crafts.store(level, v, new ItemStack(Items.BONE_MEAL, 3));
        }
        BlockPos ground = at.below();
        BlockState g = level.getBlockState(ground);
        if (g.getBlock() instanceof BonemealableBlock m && Crafts.take(level, v, s -> s.is(Items.BONE_MEAL), 1)) {
            m.performBonemeal(level, level.getRandom(), ground, g);
            level.levelEvent(1505, ground, 15);
        }
        FLOWER_AT.remove(id);
        FLOWER_LOOKED.remove(id);
    }

    private static boolean wanted(ServerLevel level, BlockPos at, Set<Item> flowers) {
        BlockState st = level.getBlockState(at);
        if (st.is(BlockTags.FLOWERS)) return flowers.contains(st.getBlock().asItem());
        return level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK) && st.canBeReplaced();
    }

    /** A wild flower of one of these kinds out in the country round the town (never in a garden or by a grave), or null. */
    @Nullable
    static BlockPos findFlower(ServerLevel level, Villages.Village v, Set<Item> flowers) {
        BlockPos c = v.centre();
        int from = Math.max(8, Villages.townReach(v.id()) - 4), to = from + 40;
        int looked = 0;
        for (int ring = from; ring <= to; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz += Math.abs(dx) == ring ? 1 : 2 * ring) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    if (++looked > 6000) return null;
                    BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    BlockState st = level.getBlockState(p);
                    if (!st.is(BlockTags.FLOWERS) || !flowers.contains(st.getBlock().asItem())) continue;
                    if (Land.inABuilding(v.id(), p)) continue;
                    return p;
                }
            }
        }
        return null;
    }

    /** A patch of open grass at the town's edge to sow flowers on, if the stores have bone meal (or bones) for it. */
    @Nullable
    static BlockPos grassToSow(ServerLevel level, Villages.Village v) {
        if (Crafts.stock(level, v, s -> s.is(Items.BONE_MEAL) || s.is(Items.BONE)) <= 0) return null;
        BlockPos c = v.centre();
        int r = Villages.townReach(v.id()) + 6;
        for (int i = 0; i < 24; i++) {
            double a = level.getRandom().nextDouble() * Math.PI * 2;
            int x = c.getX() + (int) (Math.cos(a) * r), z = c.getZ() + (int) (Math.sin(a) * r);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK) && level.getBlockState(p).canBeReplaced()
                    && !Villages.onFarmland(v.id(), x - c.getX(), z - c.getZ(), 2, 2)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ the books

    private static void made(UUID village, long day) {
        int[] w = week(village, day);
        w[0]++;
        StringBuilder sb = new StringBuilder().append(day).append(';');
        for (int i = 0; i < 7; i++) sb.append(i == 0 ? "" : ",").append(w[i]);
        Ledger.note(village, MADE, sb.toString());
    }

    /** The week's garments made, a day each, today first. */
    static int[] week(UUID village, long today) {
        int[] w = new int[7];
        String s = Ledger.note(village, MADE);
        if (s == null || s.isEmpty()) return w;
        try {
            String[] p = s.split(";");
            long day = Long.parseLong(p[0]);
            int shift = (int) Math.max(0, Math.min(7, today - day));
            String[] xs = p[1].split(",");
            for (int i = 0; i < 7 && i < xs.length; i++) if (i + shift < 7) w[i + shift] = Integer.parseInt(xs[i].trim());
        } catch (RuntimeException e) {
            return new int[7];
        }
        return w;
    }

    /** The book and the week, for the Fashion page. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        ListTag list = new ListTag();
        for (Order o : book(id)) {
            CompoundTag c = new CompoundTag();
            c.putString("for", o.name);
            c.putString("what", o.kind.a(o.colour));
            c.putInt("colour", o.colour);
            c.putLong("day", o.day);
            c.putString("status", o.status);
            c.putBoolean("first", o.first);
            list.add(c);
        }
        out.put("book", list);
        int n = 0;
        for (int x : week(id, level.getDayTime() / 24000L)) n += x;
        out.putInt("week", n);
        out.putInt("picked", PICKED.getOrDefault(id, 0));
        List<String> dyes = new ArrayList<>();
        Map<Item, Long> wanted = DYES_WANTED.get(id);
        if (wanted != null) for (Item d : wanted.keySet()) dyes.add(new ItemStack(d).getHoverName().getString().toLowerCase(Locale.ROOT));
        out.putString("dyes", String.join(", ", dyes));
        VillageFolkEntity tailor = null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.TAILOR) { tailor = f; break; }
        }
        out.putString("tailor", tailor == null ? "" : tailor.displayNameCap() + ", level " + tailor.veteranLevel());
        return out;
    }

    /** The book in lines (/village fashion). */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        List<Order> b = book(id);
        int n = 0;
        for (int x : week(id, level.getDayTime() / 24000L)) n += x;
        out.add("The tailor's book: " + (b.isEmpty() ? "nothing on order" : b.size() + " on order") + "; " + n + " made this week.");
        for (Order o : b) out.add("  " + o.words() + ", since day " + o.day + (o.status.isEmpty() ? "" : ": " + o.status));
        Map<Item, Long> wanted = DYES_WANTED.get(id);
        if (wanted != null && !wanted.isEmpty()) {
            List<String> dyes = new ArrayList<>();
            for (Item d : wanted.keySet()) dyes.add(BuiltInRegistries.ITEM.getKey(d).getPath().replace('_', ' '));
            out.add("Short of dye: " + String.join(", ", dyes) + "; " + PICKED.getOrDefault(id, 0) + " flowers picked for it.");
        }
        return out;
    }

    private static void save(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Order o : book(village)) {
            sb.append(sb.length() == 0 ? "" : ";").append(o.id).append(',').append(o.folk == null ? "" : o.folk.toString()).append(',')
                .append(o.name.replace(',', ' ').replace(';', ' ')).append(',').append(o.kind.name()).append(',').append(o.colour).append(',')
                .append(o.day).append(',').append(o.first ? 1 : 0);
        }
        Ledger.note(village, BOOK, sb.toString());
    }

    private static List<Order> load(UUID village) {
        List<Order> out = new java.util.concurrent.CopyOnWriteArrayList<>();
        String s = Ledger.note(village, BOOK);
        if (s == null || s.isEmpty()) return out;
        for (String one : s.split(";")) {
            try {
                String[] p = one.split(",", -1);
                out.add(new Order(Integer.parseInt(p[0]), p[1].isEmpty() ? null : UUID.fromString(p[1]), p[2], Garment.valueOf(p[3]),
                    Integer.parseInt(p[4]), Long.parseLong(p[5]), p[6].equals("1")));
            } catch (RuntimeException e) {
                // An order the town cannot read back is let go.
            }
        }
        List<Order> sorted = new ArrayList<>(out);
        sort(sorted);
        return new java.util.concurrent.CopyOnWriteArrayList<>(sorted);
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: the tailor's turn at the book now. What it made, or null. */
    @Nullable
    public static String workForTests(ServerLevel level, VillageFolkEntity tailor) {
        Villages.Village v = Villages.get(tailor.ownerId());
        return v == null ? null : work(level, v, tailor, null);
    }

    /** Tests: every order that was short of something tried again at the next turn, not half a minute on. */
    public static void resetRetryForTests(UUID village) {
        for (Order o : book(village)) o.tried = -100000L;
    }

    /** Tests: the book, a line each ("a crimson long coat for Ada: short of a red dye"). */
    public static List<String> bookForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Order o : book(village)) out.add(o.words() + (o.status.isEmpty() ? "" : ": " + o.status));
        return out;
    }

    /** Tests: the flowers that make a dye, by the game's recipes. */
    public static Set<Item> flowersForTests(ServerLevel level, Item dye) {
        return flowersFor(level, dye);
    }
}
