package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Laws;
import com.jrpetty.mcassistant.entity.Library;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.LibraryRecords;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The town library [library] (entity/Library, TradeBooks, Authors; village/TradeBookWriter, Verse, Tales):
 * <ul>
 * <li><b>lb01</b>: the library stands and holds real books: the masters write their trades' books on book and quills
 * made out of the stores, the newest open on the lectern and the one before it on a shelf the librarian put up out of
 * the stores' chiseled bookshelves; every book marked as the library's.</li>
 * <li><b>lb02</b>: each trade has one book, with the town's real numbers in it (the week's wheat, the best day, the
 * master's name), and a second edition leaves it one book still, with the first kept as an old edition.</li>
 * <li><b>lb03</b>: a new record harvest, the Iron Age and a farmer's death: the next edition opens with all three, and
 * the old edition stays on the shelf.</li>
 * <li><b>lb04</b>: a wedding the chronicle tells of gets a poem, and it names the couple.</li>
 * <li><b>lb05</b>: a player borrows a book from the librarian and brings it back on time; borrows again and brings it
 * back two days late: a fine of four, owed, and the librarian thinks the less of them.</li>
 * <li><b>lb06</b>: of two apprentice farmers, the one that read the Farmer's Book learns its trade a quarter quicker,
 * and works a little quicker for it.</li>
 * <li><b>lb07</b>: every book is made of real paper and ink out of the stores: sugar cane into paper, leather and the
 * paper into a book, an ink sac and a feather into a book and quill; with the one ink sac gone, no second book.</li>
 * </ul>
 * Each on its own ground in x 1100000-1119999, z 66000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class LibraryGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ the scene

    /** A town of these trades at x: the first folk raised founds it; each set to its trade, its persona rolled. */
    private static List<VillageFolkEntity> town(ServerLevel level, int x, StationTask... trades) {
        Kit.reset(level);
        level.setDayTime(6000);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.east(i * 2 - trades.length), 0.0F);
            if (f == null) continue;
            f.setJob(trades[i]);
            f.ensurePersona();
            out.add(f);
        }
        return out;
    }

    /** Nothing in the town's stores but what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    /** A marked store chest near the heart, filled with these. */
    private static void stores(ServerLevel level, int x, int dx, int dz, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, x + dx, Z + dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
    }

    /** The library, stamped on cleared, level ground (as the showcase stamps a building) and in the town's register. */
    private static Ledger.Building library(ServerLevel level, UUID village, int x) {
        BlockPos at = Kit.surface(level, x + 24, Z);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, 0, -7), at.offset(7, 12, 7))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, -3, -7), at.offset(7, -1, 7))) level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        BuildGoal.stamp(level, Library.STRUCTURE, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, Library.STRUCTURE, at, Direction.NORTH);
        return new Ledger.Building(Library.STRUCTURE, at.immutable(), Direction.NORTH);
    }

    /** The makings of so many book and quills: sugar cane for the paper, leather, feathers and ink sacs (over what the town keeps back). */
    private static ItemStack[] makings(int books) {
        return new ItemStack[]{ new ItemStack(Items.SUGAR_CANE, 3 * books), new ItemStack(Items.LEATHER, 2 + books),
            new ItemStack(Items.FEATHER, 4 + books), new ItemStack(Items.INK_SAC, books) };
    }

    /** "annals.items/<day>": what each trade made that day, as the morning's books write it ("wheat=40/0/FARM;..."). */
    private static void made(UUID village, long day, String items) {
        Ledger.note(village, "annals.items/" + day, items);
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    /** A book's words as one line: its pages run together, the bold marks and the line breaks out. */
    private static String text(LibraryRecords.Title t) {
        return String.join("\n", t.pages).replaceAll("§.", "").replaceAll("\\s+", " ");
    }

    private static void logBook(String tag, LibraryRecords.Title t, int pages) {
        Kit.log(tag + " \"" + t.title + "\" by " + t.author + " (" + t.kind + (t.edition > 0 ? ", edition " + t.edition : "") + ", "
            + t.pages.size() + " pages, at " + t.where + ")");
        for (int i = 0; i < Math.min(pages, t.pages.size()); i++) Kit.log(tag + " page " + (i + 1) + ":\n" + t.pages.get(i).replace("§l", "").replace("§r", ""));
    }

    private static LibraryRecords.Title current(UUID village, StationTask t) {
        return LibraryRecords.shelf(village).tradeBook(t.name());
    }

    // ============================================================ lb01 the library stands, and holds books

    /**
     * A town with a farmer and a miner, each master of its trade, and the library stamped: its lectern stands, its shelf
     * places are empty. Two books' makings in the stores, and two chiseled bookshelves: the librarian is chosen (the reader),
     * the two masters write their trades' books on book and quills made out of the stores; the second goes open on the
     * lectern and the first onto a shelf put up out of the stores. Both are written books, signed by their masters, marked
     * as the library's, every page fitting a book's page.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lb01_library_stands_and_holds_books")
    public static void lb01_library_stands_and_holds_books(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1100000;
        List<VillageFolkEntity> folk = town(level, x, StationTask.FARM, StationTask.MINE, StationTask.SHOP);
        helper.assertTrue(folk.size() == 3, "a town of three");
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            folk.get(0).tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(14));
            folk.get(1).tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(11));
            folk.get(0).life().setTraitsForTests(Social.Trait.HARDWORKING, Social.Trait.GRUMPY);
            folk.get(1).life().setTraitsForTests(Social.Trait.EASYGOING, Social.Trait.SOCIABLE);
            Culture.hobbyForTests(folk.get(0), Persona.Hobby.WALKING);
            Culture.hobbyForTests(folk.get(1), Persona.Hobby.CARDS);
            Culture.hobbyForTests(folk.get(2), Persona.Hobby.READING);
            folk.get(2).life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.SHY);
            emptyStores(level, id);
            Ledger.Building b = library(level, id, x);
            ItemStack[] m = makings(2);
            stores(level, x, 4, 4, m[0], m[1], m[2], m[3], new ItemStack(Items.CHISELED_BOOKSHELF, 2));
            helper.assertTrue(Library.stands(id), "the library is in the town's register");
            int ink0 = stock(level, id, s -> s.is(Items.INK_SAC)), shelves0 = stock(level, id, s -> s.is(Items.CHISELED_BOOKSHELF));
            Library.instantForTests(true);
            try {
                for (int i = 0; i < 4 && LibraryRecords.shelf(id).books.size() < 2; i++) Library.tickForTests(level, v);
            } finally {
                Library.instantForTests(false);
            }
            LibraryRecords.Shelf shelf = LibraryRecords.shelf(id);
            for (String l : Library.status(level, v)) Kit.log("lb01 " + l);
            List<String> shelved = Library.shelvedForTests(level, id);
            Kit.log("lb01 on the shelves: " + shelved);
            helper.assertTrue(shelf.librarianName.equals(folk.get(2).displayNameCap()), "the reader is the librarian: " + shelf.librarianName);
            helper.assertTrue(shelf.books.size() == 2 && shelved.size() == 2, "two books written and shelved: " + shelf.books.size() + ", " + shelved);
            ItemStack open = level.getBlockEntity(Library.lecternForTests(id)) instanceof LecternBlockEntity l ? l.getBook() : ItemStack.EMPTY;
            WrittenBookContent c = open.get(DataComponents.WRITTEN_BOOK_CONTENT);
            helper.assertTrue(c != null && Library.isLibraryBook(open), "a written book of the library's lies open on the lectern: " + open);
            helper.assertTrue(c.title().raw().contains("Book") && (c.author().equals(folk.get(0).displayNameCap()) || c.author().equals(folk.get(1).displayNameCap())),
                "a trade's book, signed by its master: " + c.title().raw() + " by " + c.author());
            boolean onAShelf = false;
            for (int i = 0; i < 4; i++) {
                if (level.getBlockEntity(Library.shelfForTests(id, i)) instanceof ChiseledBookShelfBlockEntity sh) {
                    for (int s = 0; s < 6; s++) if (Library.isLibraryBook(sh.getItem(s))) onAShelf = true;
                }
            }
            helper.assertTrue(onAShelf, "the first book moved onto a chiseled bookshelf beside the lectern");
            helper.assertTrue(stock(level, id, s -> s.is(Items.CHISELED_BOOKSHELF)) == shelves0 - 1, "the shelf came out of the stores");
            helper.assertTrue(stock(level, id, s -> s.is(Items.INK_SAC)) == ink0 - 2, "an ink sac a book, out of the stores");
            for (Filterable<Component> p : c.pages()) {
                String[] lines = p.raw().getString().split("\n", -1);
                helper.assertTrue(lines.length <= 13, "no page holds more lines than a book shows: " + lines.length);
            }
            for (LibraryRecords.Title t : shelf.books) logBook("lb01", t, 3);
            String told = chronicle(id);
            helper.assertTrue(told.contains("was made the town's librarian") && told.contains("book of best practice, for the library"),
                "the chronicle tells of the librarian and the books");
            helper.succeed();
        });
    }

    // ============================================================ lb02 one book a trade, the town's own numbers in it

    /**
     * A farmer (the master, level fourteen), an apprentice farmer and a miner; five days of the item books: forty wheat a
     * day (ninety-eight on the third) and twelve carrots by the farmers, cobblestone and raw iron by the miner. The
     * Farmer's Book says the week's 258 wheat and 60 carrots, the best day's ninety-eight wheat and on which day, and the
     * master's name; the Miner's Book its own. Written again, the farm still has one current book, and its first edition
     * is kept, superseded.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lb02_each_trade_has_one_book")
    public static void lb02_each_trade_has_one_book(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1102000;
        List<VillageFolkEntity> folk = town(level, x, StationTask.FARM, StationTask.FARM, StationTask.MINE);
        helper.assertTrue(folk.size() == 3, "a town of three");
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            VillageFolkEntity master = folk.get(0);
            master.tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(14));
            folk.get(1).tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(2));
            folk.get(2).tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(9));
            emptyStores(level, id);
            library(level, id, x);
            ItemStack[] m = makings(3);
            stores(level, x, 4, 4, m[0], m[1], m[2], m[3], new ItemStack(Items.CHISELED_BOOKSHELF, 2));
            long day = level.getDayTime() / 24000L;
            for (int d = 1; d <= 5; d++) {
                made(id, day - 6 + d, "wheat=" + (d == 3 ? 98 : 40) + "/0/FARM;carrot=12/0/FARM;cobblestone=120/0/MINE;raw_iron=9/0/MINE");
            }
            LibraryRecords.Title farm = Library.writeTradeBookForTests(level, v, StationTask.FARM);
            LibraryRecords.Title mine = Library.writeTradeBookForTests(level, v, StationTask.MINE);
            helper.assertTrue(farm != null && mine != null, "the farmer's and the miner's books written");
            logBook("lb02", farm, 8);
            logBook("lb02", mine, 4);
            String all = text(farm);
            long best = day - 6 + 3;
            helper.assertTrue(farm.title.startsWith("The Farmer's Book") && farm.author.equals(master.displayNameCap()),
                "the farmer's book, by the master farmer: " + farm.title + " by " + farm.author);
            helper.assertTrue(all.contains("258 wheat") && all.contains("60 carrots"), "the week's wheat and carrots, as the books have them");
            helper.assertTrue(all.contains("ninety-eight wheat") && all.contains("day " + best), "the best day, its wheat and its day (" + best + ")");
            helper.assertTrue(all.contains(folk.get(1).displayNameCap()), "the apprentice among the hands");
            String mined = text(mine);
            helper.assertTrue(mined.contains("cobblestone") && mined.contains("raw iron"), "the miner's book has the mine's own numbers");
            // A second edition: still one book for the farm, the first kept as an old one.
            LibraryRecords.Title again = Library.writeTradeBookForTests(level, v, StationTask.FARM);
            LibraryRecords.Shelf shelf = LibraryRecords.shelf(id);
            int currentFarm = 0, oldFarm = 0;
            for (LibraryRecords.Title t : shelf.books) {
                if (!t.kind.equals("TRADE") || !t.trade.equals("FARM")) continue;
                if (t.superseded) oldFarm++;
                else currentFarm++;
            }
            Kit.log("lb02 the farm's books: " + currentFarm + " current, " + oldFarm + " old; on the shelves: " + Library.shelvedForTests(level, id));
            helper.assertTrue(again != null && again.edition == 2 && currentFarm == 1 && oldFarm == 1,
                "one current Farmer's Book (edition " + (again == null ? 0 : again.edition) + "), and the first kept");
            helper.succeed();
        });
    }

    // ============================================================ lb03 the next edition says what changed

    /**
     * The Farmer's Book's first edition, then: a record day (150 wheat), the town come into the Iron Age, and a farmer
     * died in a fall. The second edition opens with all three in "New in this edition", its pages tell them, the first
     * edition is superseded and still on the shelf, and the chronicle says the edition came out with them in it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lb03_the_next_edition_says_so")
    public static void lb03_the_next_edition_says_so(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1104000;
        List<VillageFolkEntity> folk = town(level, x, StationTask.FARM, StationTask.FARM);
        helper.assertTrue(folk.size() == 2, "a town of two");
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            VillageFolkEntity master = folk.get(0);
            master.tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(16));
            Villages.ageForTests(id, Villages.Age.STONE);
            emptyStores(level, id);
            library(level, id, x);
            ItemStack[] m = makings(2);
            stores(level, x, 4, 4, m[0], m[1], m[2], m[3], new ItemStack(Items.CHISELED_BOOKSHELF, 2));
            long day = level.getDayTime() / 24000L;
            for (int d = 1; d <= 4; d++) made(id, day - 5 + d, "wheat=" + (40 + d) + "/0/FARM");
            LibraryRecords.Title first = Library.writeTradeBookForTests(level, v, StationTask.FARM);
            helper.assertTrue(first != null && first.edition == 1, "the first edition");
            // The town moves on: a record day, a new age, a death in the fields.
            made(id, day, "wheat=150/0/FARM");
            Villages.ageForTests(id, Villages.Age.IRON);
            Villages.tell(id, day, "the village came into the Iron Age");
            Ledger.buried(id, new Ledger.Grave("Fern", day - 40, day, "in a fall", "", "", "Farmer"));
            LibraryRecords.Title second = Library.writeTradeBookForTests(level, v, StationTask.FARM);
            helper.assertTrue(second != null && second.edition == 2, "the second edition");
            logBook("lb03", second, 7);
            Kit.log("lb03 new in it: " + second.news);
            String news = String.join(" ", second.news);
            helper.assertTrue(news.contains("record") && news.contains("150"), "the new record in the news: " + news);
            helper.assertTrue(news.contains("Iron Age"), "the new age in the news: " + news);
            helper.assertTrue(news.contains("Fern"), "the death in the news: " + news);
            String all = text(second);
            helper.assertTrue(all.contains("New in this edition") && all.contains("iron hoes"), "the edition says so, and what the Iron Age brings");
            helper.assertTrue(all.contains("we lost Fern"), "and the lesson, in its place");
            LibraryRecords.Title old = null;
            for (LibraryRecords.Title t : LibraryRecords.shelf(id).books) if (t.id == first.id) old = t;
            List<String> shelved = Library.shelvedForTests(level, id);
            int copies = 0;
            for (String s : shelved) if (s.equals(first.title)) copies++;
            helper.assertTrue(old != null && old.superseded && (old.where.startsWith("shelf ") || old.where.equals("lectern")),
                "the first edition kept, superseded, on a shelf: " + (old == null ? "gone" : old.where));
            helper.assertTrue(copies == 2, "both editions stand in the library: " + shelved);
            helper.assertTrue(chronicle(id).contains("second edition of " + second.title), "the chronicle tells of the new edition");
            helper.succeed();
        });
    }

    // ============================================================ lb04 a wedding poem names the couple

    /**
     * Two folk wed (the chronicle says so), and a third who loves reading: the wedding wants a poem, and the poem the
     * town's writer writes on it names the couple, in its title or its lines.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lb04_a_wedding_poem")
    public static void lb04_a_wedding_poem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1106000;
        List<VillageFolkEntity> folk = town(level, x, StationTask.FARM, StationTask.SMELT, StationTask.SHOP);
        helper.assertTrue(folk.size() == 3, "a town of three");
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            VillageFolkEntity a = folk.get(0), b = folk.get(1), poet = folk.get(2);
            Culture.hobbyForTests(poet, Persona.Hobby.READING);
            poet.life().setTraitsForTests(Social.Trait.CHEERFUL, Social.Trait.CURIOUS);
            emptyStores(level, id);
            library(level, id, x);
            ItemStack[] m = makings(1);
            stores(level, x, 4, 4, m[0], m[1], m[2], m[3]);
            long day = level.getDayTime() / 24000L;
            Villages.tell(id, day, a.displayNameCap() + " and " + b.displayNameCap() + " were wed");
            List<String> ideas = Library.ideasForTests(level, v);
            Kit.log("lb04 the town could write: " + ideas);
            helper.assertTrue(ideas.contains("poem:WEDDING:" + a.displayNameCap() + "|" + b.displayNameCap()), "the wedding wants a poem: " + ideas);
            LibraryRecords.Title poem = Library.writeBookForTests(level, v, "poem:WEDDING");
            helper.assertTrue(poem != null && poem.kind.equals("POEM"), "a poem written");
            logBook("lb04", poem, 6);
            String all = text(poem);
            helper.assertTrue(all.contains(a.displayNameCap()) && all.contains(b.displayNameCap()),
                "the poem names the couple, " + a.displayNameCap() + " and " + b.displayNameCap());
            helper.assertTrue(!poem.author.equals(a.displayNameCap()) && !poem.author.equals(b.displayNameCap()),
                "written for the couple, not by them: " + poem.author);
            helper.assertTrue(chronicle(id).contains(poet.displayNameCap() + "'s new poem"), "the chronicle has the new poem");
            helper.succeed();
        });
    }

    // ============================================================ lb05 a player borrows a book and brings it back

    /**
     * The Farmer's Book on the shelves and a librarian: a player asks to borrow it, and has it in hand, marked as
     * borrowed, its shelf slot empty and the loan written down, due in three days; brings it back, and it is on the shelf
     * again, the loan closed. Borrowed again and brought back two days late: four coins' fine, owed (the player has none),
     * and the librarian thinks the less of the player.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lb05_borrow_and_return")
    public static void lb05_borrow_and_return(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1108000;
        List<VillageFolkEntity> folk = town(level, x, StationTask.FARM, StationTask.SHOP);
        helper.assertTrue(folk.size() == 2, "a town of two");
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            folk.get(0).tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(12));
            folk.get(0).life().setTraitsForTests(Social.Trait.HARDWORKING, Social.Trait.GRUMPY);
            Culture.hobbyForTests(folk.get(0), Persona.Hobby.WALKING);
            VillageFolkEntity lib = folk.get(1);
            Culture.hobbyForTests(lib, Persona.Hobby.READING);
            lib.life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.CHEERFUL);
            emptyStores(level, id);
            library(level, id, x);
            ItemStack[] m = makings(1);
            stores(level, x, 4, 4, m[0], m[1], m[2], m[3], new ItemStack(Items.CHISELED_BOOKSHELF, 1));
            Library.instantForTests(true);
            try {
                Library.tickForTests(level, v);                                      // the librarian chosen, the Farmer's Book written
            } finally {
                Library.instantForTests(false);
            }
            LibraryRecords.Title book = current(id, StationTask.FARM);
            helper.assertTrue(book != null && Library.isLibrarian(lib), "the Farmer's Book on the shelves, and " + lib.displayNameCap() + " the librarian");
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            long day = level.getDayTime() / 24000L;
            String asked = FolkTalk.answer(lib, p, TalkTopic.SAY, "Could I borrow the farmer's book?");
            Kit.log("lb05 borrow: " + asked);
            ItemStack had = ItemStack.EMPTY;
            for (ItemStack s : p.getInventory().items) if (Library.isLibraryBook(s)) had = s;
            LibraryRecords.Shelf shelf = LibraryRecords.shelf(id);
            helper.assertTrue(!had.isEmpty() && had.get(DataComponents.WRITTEN_BOOK_CONTENT).title().raw().equals(book.title),
                "the player has the Farmer's Book: " + asked);
            helper.assertTrue(had.get(DataComponents.LORE) != null, "marked with the day it is due back");
            helper.assertTrue(shelf.loans.size() == 1 && shelf.loans.get(0).due == day + Library.LOAN_DAYS && book.where.equals("loan"),
                "the loan written down, due in three days");
            helper.assertTrue(!Library.shelvedForTests(level, id).contains(book.title), "and the book is off the shelf");
            int before = lib.persona().affinity(p.getUUID());
            String back = FolkTalk.answer(lib, p, TalkTopic.SAY, "Here's your book back, thank you.");
            Kit.log("lb05 back on time: " + back);
            helper.assertTrue(shelf.loans.isEmpty() && Library.shelvedForTests(level, id).contains(book.title)
                && (book.where.startsWith("shelf ") || book.where.equals("lectern")), "back on the shelf, the loan closed: " + book.where);
            int onTime = lib.persona().affinity(p.getUUID());
            // Again, and two days late.
            FolkTalk.answer(lib, p, TalkTopic.SAY, "Could I borrow the farmer's book again?");
            helper.assertTrue(shelf.loans.size() == 1, "borrowed again");
            long late = shelf.loans.get(0).due + 2;
            level.setDayTime(late * 24000L + 6000L);
            Library.overdueForTests(level, v, late);
            String lateBack = FolkTalk.answer(lib, p, TalkTopic.SAY, "Here's your book back.");
            int after = lib.persona().affinity(p.getUUID());
            int owes = Laws.owes(id, p.getUUID());
            Kit.log("lb05 back late: " + lateBack + " (owes " + owes + "; the librarian's opinion " + before + " -> " + onTime + " -> " + after + ")");
            helper.assertTrue(shelf.loans.isEmpty() && Library.shelvedForTests(level, id).contains(book.title), "back on the shelf again");
            helper.assertTrue(owes == 2 * Library.FINE_A_DAY, "two days late: a fine of four, owed: " + owes);
            helper.assertTrue(after < onTime, "and the librarian thinks the less of the player: " + onTime + " -> " + after);
            helper.succeed();
        });
    }

    // ============================================================ lb06 an apprentice who read its trade's book learns faster

    /**
     * The Farmer's Book on the shelves; two apprentice farmers, the same in everything but this: one has read it. The
     * same work's worth of experience to each: the reader learns a quarter more of its trade, and its pace has the book
     * in it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lb06_an_apprentice_reads")
    public static void lb06_an_apprentice_reads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1110000;
        List<VillageFolkEntity> folk = town(level, x, StationTask.FARM, StationTask.FARM, StationTask.FARM);
        helper.assertTrue(folk.size() == 3, "a town of three");
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            folk.get(0).tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(15));
            VillageFolkEntity reader = folk.get(1), other = folk.get(2);
            for (VillageFolkEntity f : List.of(reader, other)) {
                f.tradeXpForTests(StationTask.FARM, 0);
                f.life().setTraitsForTests(Social.Trait.CHEERFUL, Social.Trait.SOCIABLE);
            }
            emptyStores(level, id);
            library(level, id, x);
            ItemStack[] m = makings(1);
            stores(level, x, 4, 4, m[0], m[1], m[2], m[3]);
            LibraryRecords.Title book = Library.writeTradeBookForTests(level, v, StationTask.FARM);
            helper.assertTrue(book != null, "the Farmer's Book written");
            helper.assertTrue(Library.readTradeBookForTests(reader), "one apprentice reads it");
            int r0 = reader.xpInTrade(StationTask.FARM), o0 = other.xpInTrade(StationTask.FARM);
            reader.awardXp(100);
            other.awardXp(100);
            int r = reader.xpInTrade(StationTask.FARM) - r0, o = other.xpInTrade(StationTask.FARM) - o0;
            Kit.log("lb06 the same work: the reader learned " + r + ", the other " + o + "; the reader's pace: " + reader.paceLine()
                + "; its card: " + Library.cardLine(reader));
            helper.assertTrue(o == 100 && r == 100 + 100 * Library.STUDY_PERCENT / 100, "a quarter more for the reader: " + r + " against " + o);
            helper.assertTrue(Library.workPercent(reader) == Library.PACE_NEW && Library.workPercent(other) == 0,
                "and a little quicker at the work: " + Library.workPercent(reader) + "% against " + Library.workPercent(other) + "%");
            helper.assertTrue(Library.cardLine(reader).contains("has read its trade's book"), "its card says so");
            helper.succeed();
        });
    }

    // ============================================================ lb07 every book made of real paper and ink

    /**
     * The stores hold sugar cane for three paper, leather and feathers, and one ink sac. The farmer's book is written on
     * a book and quill made the whole way out of them (cane into paper, paper and leather into a book, the book, the ink
     * sac and a feather into the book and quill); the miner's is not written at all: no ink, no book, and the library
     * says what it is short of.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "lb07_real_paper_and_ink")
    public static void lb07_real_paper_and_ink(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1112000;
        List<VillageFolkEntity> folk = town(level, x, StationTask.FARM, StationTask.MINE);
        helper.assertTrue(folk.size() == 2, "a town of two");
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            folk.get(0).tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(12));
            folk.get(1).tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(12));
            emptyStores(level, id);
            library(level, id, x);
            stores(level, x, 4, 4, makings(1));
            int cane0 = stock(level, id, s -> s.is(Items.SUGAR_CANE)), ink0 = stock(level, id, s -> s.is(Items.INK_SAC)),
                leather0 = stock(level, id, s -> s.is(Items.LEATHER)), feather0 = stock(level, id, s -> s.is(Items.FEATHER));
            LibraryRecords.Title farm = Library.writeTradeBookForTests(level, v, StationTask.FARM);
            LibraryRecords.Title mine = Library.writeTradeBookForTests(level, v, StationTask.MINE);
            int cane = stock(level, id, s -> s.is(Items.SUGAR_CANE)), ink = stock(level, id, s -> s.is(Items.INK_SAC)),
                leather = stock(level, id, s -> s.is(Items.LEATHER)), feather = stock(level, id, s -> s.is(Items.FEATHER));
            Kit.log("lb07 cane " + cane0 + " -> " + cane + ", ink " + ink0 + " -> " + ink + ", leather " + leather0 + " -> " + leather
                + ", feathers " + feather0 + " -> " + feather + "; the farmer's book " + (farm == null ? "not written" : "written")
                + ", the miner's " + (mine == null ? "not written" : "written") + "; short of: " + Library.shortForTests(id));
            helper.assertTrue(farm != null, "the farmer's book written");
            helper.assertTrue(cane0 - cane == 3 && ink0 - ink == 1 && leather0 - leather == 1 && feather0 - feather == 1,
                "made of three cane's paper, a leather, an ink sac and a feather out of the stores");
            helper.assertTrue(mine == null && LibraryRecords.shelf(id).books.size() == 1, "no ink, no second book");
            helper.assertTrue(Library.shortForTests(id).contains("ink") || Library.shortForTests(id).contains("paper")
                || Library.shortForTests(id).contains("book"), "and the library says what it is short of: " + Library.shortForTests(id));
            helper.succeed();
        });
    }

    private static String chronicle(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append("[day ").append(e.day()).append("] ").append(e.text()).append('\n');
        return sb.toString();
    }
}
