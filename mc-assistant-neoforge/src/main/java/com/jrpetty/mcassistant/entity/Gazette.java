package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gazette: the town's paper, a written book open on a lectern in the meeting hall. [townlife]
 *
 * <p>Each morning it is written up afresh with yesterday in the town — who was born, who died, what
 * went up, anything else the chronicle took down — the elder's order, and the market's prices: what
 * the stores sell their goods for, the most plentiful first, and what the town is buying. A player
 * reads it as any book on a lectern; the folk leave it there.
 *
 * <p>The hall's drawing has no lectern of its own, so the gazette's stands beside the elder's chair
 * at the far end (a drawing that does have one gets the gazette on that). The lectern comes out of
 * the stores, or is made of three books and eight planks from them, as the school's is. The book is
 * a book from the stores, or one made of three paper and a leather the town has; with neither, there
 * is no gazette — the town has nothing to print it on. Once it has its book, the same one is written
 * up each morning. A book a player has put on the lectern is the player's, and is left alone.
 */
public final class Gazette {

    private Gazette() {}

    /** The mark the gazette carries (its issue's day), so the town knows its own book from a player's. */
    private static final String MARK = "mca_gazette";
    /** At most so many of each kind of news in an issue (a page holds a dozen lines). */
    private static final int EACH = 3;

    /** The day each town's gazette was last written up. */
    private static final Map<UUID, Long> WRITTEN = new ConcurrentHashMap<>();
    /** Where the gazette's lectern stands in each hall, by the hall's anchor. */
    private static final Map<Long, BlockPos> SPOTS = new ConcurrentHashMap<>();
    /** What the town lacks for it, said once (the log and the tests). */
    private static final Map<UUID, String> WANTS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WRITTEN.clear();
        SPOTS.clear();
        WANTS.clear();
    }

    /** The town's look at its gazette (TownLife.tick, every ten seconds): from the morning, once a day. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = Math.floorDiv(dt, 24000L), t = Math.floorMod(dt, 24000L);
        if (t < 500L || t >= 12000L || Villages.headcount(id) < TownJobs.SETTLED) return;
        Long done = WRITTEN.get(id);
        if (done != null && done == day) return;
        String did = write(level, v, day);
        if (did != null) LOG.info("[MCA-GAZETTE] {}: {}", Villages.name(id), did);
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The meeting hall, if the town has one. */
    @Nullable
    static Ledger.Building hall(UUID village) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("hall")) return b;
        return null;
    }

    /**
     * The day's gazette, as far as the town can get it: the lectern, the book, the issue. What was done,
     * in words, or null if it is waiting (on a hand, a book or a lectern). Marks the day done once it is.
     */
    @Nullable
    static String write(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Ledger.Building hall = hall(id);
        if (hall == null || !level.isLoaded(hall.anchor())) return null;
        BlockPos at = lecternSpot(level, hall);
        if (at == null) return null;
        BlockState st = level.getBlockState(at);
        String did = "";
        if (!(st.getBlock() instanceof LecternBlock)) {
            if (!st.canBeReplaced()) return null;
            // Nobody is sent for it till the stores can pay for it.
            if (!lecternToHand(level, v)) {
                want(id, "a lectern, or three books and eight planks, for the gazette's lectern in the hall");
                return null;
            }
            if (!TownJobs.atWork(level, v, "gazette", at, "setting up a lectern in the meeting hall")) return null;
            if (!payForLectern(level, v)) return null;
            level.setBlock(at, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, hall.facing().getOpposite()), 3);
            st = level.getBlockState(at);
            did = "a lectern set up in the meeting hall; ";
        }
        if (!(level.getBlockEntity(at) instanceof LecternBlockEntity le)) return null;
        if (st.getValue(LecternBlock.HAS_BOOK)) {
            ItemStack there = le.getBook();
            if (!isGazette(there)) {                                   // somebody else's book: theirs to read
                WRITTEN.put(id, day);
                return null;
            }
            if (issue(there) == day) {
                WRITTEN.put(id, day);
                return null;
            }
            if (!TownJobs.atWork(level, v, "gazette", at, "writing up the gazette")) return null;
            le.setBook(issueOf(level, v, day));
            le.setChanged();
            WRITTEN.put(id, day);
            WANTS.remove(id);
            return did + "the gazette written up for day " + day;
        }
        // A fresh book for it: out of the stores, or made of their paper and leather. None, no gazette.
        if (!bookToHand(level, v)) {
            want(id, "a book, or three paper and a leather, for the gazette");
            return did.isEmpty() ? null : did + "no book for the gazette";
        }
        if (!TownJobs.atWork(level, v, "gazette", at, "laying the gazette in the meeting hall")) return null;
        String paidWith = payForBook(level, v);
        if (paidWith == null) return null;
        ItemStack paper = issueOf(level, v, day);
        if (!LecternBlock.tryPlaceBook(null, level, at, st, paper)) {
            Crafts.store(level, v, new ItemStack(Items.BOOK));          // the book it would have been, back in the stores
            return null;
        }
        WRITTEN.put(id, day);
        WANTS.remove(id);
        return did + "the first gazette, on " + paidWith + ", laid open on the hall's lectern";
    }

    private static void want(UUID id, String what) {
        if (!what.equals(WANTS.put(id, what))) LOG.info("[MCA-GAZETTE] {} wants {}", Villages.name(id), what);
    }

    /** Can the stores run to a lectern (one put by, or three books and eight planks)? */
    private static boolean lecternToHand(ServerLevel level, Villages.Village v) {
        if (Crafts.stock(level, v, s -> s.is(Items.LECTERN)) > 0) return true;
        return Crafts.stock(level, v, s -> s.is(Items.BOOK)) >= 3 && (Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) >= 8
            || Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS)) >= 2);
    }

    /** Can the stores run to a book for it (a book, a book and quill, or three paper and a leather)? */
    private static boolean bookToHand(ServerLevel level, Villages.Village v) {
        if (Crafts.stock(level, v, s -> s.is(Items.BOOK) || s.is(Items.WRITABLE_BOOK)) > 0) return true;
        return Crafts.stock(level, v, s -> s.is(Items.PAPER)) >= 3 && Crafts.stock(level, v, s -> s.is(Items.LEATHER)) >= 1;
    }

    /** A lectern out of the stores, or made of three books and eight planks from them, as the school's is. All or nothing. */
    private static boolean payForLectern(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.LECTERN), 1)) return true;
        if (Crafts.stock(level, v, s -> s.is(Items.BOOK)) < 3 || !Crafts.take(level, v, s -> s.is(Items.BOOK), 3)) return false;
        if (Crafts.usePlanks(level, v, 8)) return true;
        Crafts.store(level, v, new ItemStack(Items.BOOK, 3));
        return false;
    }

    /** The book: a book (or a book and quill) out of the stores, or one made of three paper and a leather. What it was, or null. */
    @Nullable
    private static String payForBook(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.BOOK), 1)) return "a book from the stores";
        if (Crafts.take(level, v, s -> s.is(Items.WRITABLE_BOOK), 1)) return "a book and quill from the stores";
        if (Crafts.stock(level, v, s -> s.is(Items.PAPER)) < 3 || Crafts.stock(level, v, s -> s.is(Items.LEATHER)) < 1) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.PAPER), 3)) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.LEATHER), 1)) {
            Crafts.store(level, v, new ItemStack(Items.PAPER, 3));
            return null;
        }
        return "a book made of the stores' paper and leather";
    }

    // ------------------------------------------------------------------ where it lies

    /**
     * The gazette's lectern in this hall: the drawing's own lectern if it has one, else a floor spot beside
     * the elder's chair at the far end (two to its side, then a step toward the door). Worked out once a hall.
     */
    @Nullable
    static BlockPos lecternSpot(ServerLevel level, Ledger.Building hall) {
        BlockPos known = SPOTS.get(hall.anchor().asLong());
        if (known != null && (level.getBlockState(known).getBlock() instanceof LecternBlock || level.getBlockState(known).canBeReplaced())) {
            return known;
        }
        BlockPos chair = null;
        for (BuildGoal.Placement p : BuildGoal.plan(hall.structure(), hall.anchor(), hall.facing(), 13)) {
            if (p.part() == BuildGoal.Part.LECTERN) {
                SPOTS.put(hall.anchor().asLong(), p.pos());
                return p.pos();
            }
            if (p.part() == BuildGoal.Part.BLOCK && p.style() == Blueprints.Style.ROOF_STAIR && p.way() == Blueprints.Way.BACK
                    && p.pos().getY() == hall.anchor().getY()) {
                chair = p.pos();
            }
        }
        if (chair == null) return null;
        Direction right = hall.facing().getClockWise(), front = hall.facing().getOpposite();
        List<BlockPos> tries = List.of(chair.relative(right, 2), chair.relative(right.getOpposite(), 2),
            chair.relative(right, 2).relative(front), chair.relative(right.getOpposite(), 2).relative(front));
        for (BlockPos p : tries) {
            BlockState st = level.getBlockState(p);
            boolean ours = st.getBlock() instanceof LecternBlock;
            if (!ours && (!st.canBeReplaced() || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP))) continue;
            SPOTS.put(hall.anchor().asLong(), p.immutable());
            return p.immutable();
        }
        return null;
    }

    // ------------------------------------------------------------------ the issue

    /** Is this the town's gazette (and not a book a player put on the lectern)? */
    public static boolean isGazette(ItemStack s) {
        return s.is(Items.WRITTEN_BOOK) && s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(MARK);
    }

    /** The day of an issue, or -1. */
    static long issue(ItemStack s) {
        CompoundTag tag = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(MARK) ? tag.getLong(MARK) : -1L;
    }

    /** The day's issue: yesterday in the town, the elder's order and the market's prices. */
    static ItemStack issueOf(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        String town = Villages.name(id);
        long founded = Chronicle.foundedOn(id);
        String front = "§lThe " + town + " Gazette§r\nDay " + day + "\n\n" + Villages.headcount(id) + " folk, "
            + Villages.ageOf(id).label + (founded >= 0 ? "\nFounded day " + founded : "") + "\n\nYesterday in " + town + ", day " + (day - 1) + ".";
        List<String> born = new ArrayList<>(), died = new ArrayList<>(), built = new ArrayList<>(), other = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(id)) {
            if (e.day() != day - 1) continue;
            String s = capital(e.text());
            String low = e.text().toLowerCase(java.util.Locale.ROOT);
            if (low.contains("had a child") || low.contains(" had twins") || low.contains(" had triplets") || low.contains(" had quadruplets")
                    || low.contains(" was born")) born.add(s);
            else if (low.contains(" died") || low.contains(" was lost")) died.add(s);
            else if ((low.endsWith("went up") || low.contains(" was raised") || low.contains(" was finished") || low.contains(" was hung")
                    || low.contains("built ")) && !low.contains("notice")) built.add(s);
            else other.add(s);
        }
        List<String> entries = new ArrayList<>();
        entries.add(section("Born", born, "Nobody."));
        entries.add(section("Died", died, "Nobody, thank goodness."));
        entries.add(section("Built", built, "Nothing new went up."));
        Orders.Given g = Orders.given(id);
        entries.add("§lThe elder's order§r\n" + (g == null ? "None given."
            : g.order().title + ". " + g.order().words + (g.by().isEmpty() ? "" : " — " + g.by()) + ", day " + g.day()));
        entries.add(prices(level, v, day));
        if (!other.isEmpty()) entries.add(section("Also", other, ""));
        ItemStack book = Services.book("The " + town + " Gazette", town, front, entries);
        CompoundTag mark = new CompoundTag();
        mark.putLong(MARK, day);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        return book;
    }

    private static String section(String title, List<String> items, String none) {
        StringBuilder sb = new StringBuilder("§l").append(title).append("§r");
        if (items.isEmpty()) return sb.append('\n').append(none).toString();
        for (int i = 0; i < Math.min(EACH, items.size()); i++) sb.append('\n').append(items.get(i));
        if (items.size() > EACH) sb.append("\n…and ").append(items.size() - EACH).append(" more.");
        return sb.toString();
    }

    /**
     * The market's prices: the four goods the stores hold most of, at what the town asks for a lot of each,
     * and the goods it is buying, at what it pays. The stores read once.
     */
    static String prices(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        boolean md = Market.marketDay(id, day);
        List<ItemStack> held = new ArrayList<>();
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) held.add(c.getItem(i));
        }
        List<Market.Good> goods = new ArrayList<>();
        Map<Market.Good, Integer> stock = new java.util.HashMap<>();
        for (Market.Good g : Market.GOODS) {
            int n = 0;
            for (ItemStack s : held) if (g.what().test(s)) n += s.getCount();
            stock.put(g, n);
            if (n >= g.bundle()) goods.add(g);
        }
        goods.sort(Comparator.comparingInt((Market.Good g) -> -stock.get(g) / Math.max(1, g.bundle())));
        StringBuilder sb = new StringBuilder("§lPrices§r").append(md ? " (market day)" : "");
        if (goods.isEmpty()) sb.append("\nThe stalls are bare.");
        for (int i = 0; i < Math.min(4, goods.size()); i++) {
            Market.Good g = goods.get(i);
            sb.append('\n').append(g.bundle()).append(' ').append(g.name()).append(": ").append(Market.sellPrice(g, stock.get(g), md)).append('c');
        }
        List<Market.Good> wanted = Market.wanted(level, id);
        if (!wanted.isEmpty() && Ledger.coins(id) > 0) {
            sb.append("\nBuying:");
            for (int i = 0; i < Math.min(2, wanted.size()); i++) {
                Market.Good g = wanted.get(i);
                sb.append('\n').append(g.bundle()).append(' ').append(g.name()).append(": ")
                    .append(Market.buyPrice(g, stock.getOrDefault(g, 0), md)).append('c');
            }
        }
        return sb.toString();
    }

    private static String capital(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + (s.endsWith(".") || s.endsWith("!") ? "" : ".");
    }

    // ------------------------------------------------------------------ the tests

    /** For the tests: today's gazette written now, whatever the hour. What was done, or null. */
    @Nullable
    public static String writeForTests(ServerLevel level, Villages.Village v) {
        WRITTEN.remove(v.id());
        return write(level, v, Math.floorDiv(level.getDayTime(), 24000L));
    }

    /** For the tests: where the gazette's lectern stands (or would), or null with no hall. */
    @Nullable
    public static BlockPos lecternForTests(ServerLevel level, UUID village) {
        Ledger.Building hall = hall(village);
        return hall == null ? null : lecternSpot(level, hall);
    }

    /** For the tests: every page of a book, one string. */
    public static String textForTests(ItemStack book) {
        WrittenBookContent c = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (c == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var page : c.pages()) sb.append(page.raw().getString()).append('\n');
        return sb.toString();
    }
}
