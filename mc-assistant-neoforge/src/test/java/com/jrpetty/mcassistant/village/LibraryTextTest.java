package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The town library's writing [library] (Quill, TradeBookWriter, Verse, Tales), with no world to hand: a town's worth of
 * books written out of plain facts, every kind of them in every writer's voice, and gone over as a reader would: every
 * page fits a book's page (thirteen lines, none wider than the page), every title fits a book's title, nothing is left
 * unfilled ("{A}", "null"), the trade's book has the town's own numbers and names in it and its new edition says what
 * changed, and a wedding poem names the couple. Run with -Dlibrary.samples=true, it prints a shelf of sample books to
 * read.
 */
class LibraryTextTest {

    private static final String TOWN = "Thornhurst";
    private static final List<List<String>> VOICES = List.of(
        List.of("cheerful", "curious"), List.of("grumpy", "hardworking"), List.of("shy", "generous"), List.of("easygoing", "sociable"),
        List.of("curious", "shy"), List.of("hardworking", "cheerful"), List.of("sociable", "generous"), List.of());
    private static final String[] QUIRKS = { "names every tool", "tells terrible jokes", "keeps a diary", "hums while working", "",
        "counts everything twice", "is afraid of the dark", "loves the rain" };

    private static Quill.Voice voice(String name, int i) {
        return Quill.Voice.of(name, VOICES.get(i % VOICES.size()), QUIRKS[i % QUIRKS.length], "reading", "farmer");
    }

    // ------------------------------------------------------------------ a town's facts

    static TradeBookWriter.Facts farm(int edition, List<TradeBookWriter.Change> changes, int voice) {
        TradeBookWriter.Person marrow = new TradeBookWriter.Person("Marrow", 22, VOICES.get(voice % VOICES.size()), QUIRKS[voice % QUIRKS.length],
            "gardening", 34, "a founder of the village", 3, List.of("Green Thumb on day 30"), "irrigation", "I brought in the record harvest", 33, "Tansy");
        return new TradeBookWriter.Facts(TOWN, "FARM", 41, "summer, the town's second year", edition, edition > 1 ? List.of(12L) : List.of(), marrow,
            List.of(new TradeBookWriter.Hand("Marrow", 22, ""), new TradeBookWriter.Hand("Cobb", 6, "has read this book, and learns the quicker for it"),
                new TradeBookWriter.Hand("Bramble", 11, ""), new TradeBookWriter.Hand("Wick", 2, "")),
            List.of(new TradeBookWriter.Made("wheat", 3412, 258, 98, 33), new TradeBookWriter.Made("carrots", 870, 60, 41, 30),
                new TradeBookWriter.Made("potatoes", 610, 40, 22, 18)),
            38, 358, 300, 98, 33, "wheat", 4892, 46, 1240,
            List.of(new TradeBookWriter.Lesson(40, "drought", "", "the wells ran low and the east field dried out"),
                new TradeBookWriter.Lesson(51, "death", "Fern", "in a fall")),
            "the Iron Age", 22, "the Diamond Age", 5,
            List.of("My own field grows at 2.20 times the wild's pace: it's well watered, lit at night, and there's a composter by it.",
                "It's summer now. The fields still grow well. Keep them watered."),
            changes);
    }

    static List<Quill.Book> shelf() {
        List<Quill.Book> out = new ArrayList<>();
        // Every trade's book, in every voice.
        String[] keys = { "FARM", "MINE", "WOOD", "FISH", "RANCH", "GUARD", "SMELT", "COOK", "SMITH", "TAILOR", "BEEKEEP", "BREW", "ENCHANT",
            "SHOP", "STORE", "HAUL", "HUNT", "SCOUT", "BANK", "CAVE", "LIBRARY", "NONE" };     // [weave] the librarian's own book
        for (int i = 0; i < keys.length; i++) {
            TradeBookWriter.Facts f = farm(1 + i % 3, i % 3 == 0 ? List.of() : List.of(new TradeBookWriter.Change("record", "wheat", "", 33, 98, 74),
                new TradeBookWriter.Change("age", "the Iron Age", "", 22, 0, 0), new TradeBookWriter.Change("master", "Marrow", "Tansy", -1, 0, 0)), i);
            out.add(TradeBookWriter.write(new TradeBookWriter.Facts(f.town(), keys[i], f.day(), f.date(), f.edition(), f.earlier(), f.master(), f.hands(),
                i % 4 == 3 ? List.of() : f.made(), i % 4 == 3 ? 0 : f.days(), f.week(), f.lastWeek(), f.best(), f.bestOn(), f.bestWhat(), f.allTime(),
                f.worth(), f.deeds(), i % 2 == 0 ? f.lessons() : List.of(), f.age(), f.ageSince(), f.nextAge(), f.research(), f.notes(), f.changes())));
        }
        // [weave] The librarian's own book (its shelves, not a harvest), and the cave team's with what the caves have taught it.
        TradeBookWriter.Person ada = new TradeBookWriter.Person("Ada", 0, List.of("curious", "shy"), "keeps a diary", "reading", 41,
            "born here", 20, List.of(), "", "", -1, "");
        out.add(TradeBookWriter.write(new TradeBookWriter.Facts(TOWN, "LIBRARY", 58, "summer, the town's second year", 1, List.of(), ada,
            List.of(new TradeBookWriter.Hand("Ada", 0, "the librarian")),
            List.of(new TradeBookWriter.Made("books", 14, 3, 0, -1), new TradeBookWriter.Made("readings", 37, 0, 0, -1),
                new TradeBookWriter.Made("loans", 6, 0, 0, -1)),
            1, 3, 0, 9, -1, "The Farmer's Book of Thornhurst", 37, 0, 6, List.of(), "the Iron Age", -1, "", 0,
            List.of("We write our own books here: six books of best practice, four poems, two histories and two lives, by seven hands. Every one of them is about this town, and true.",
                "Eleven folk have sat and read here. An apprentice who reads its trade's book learns the quicker for it.",
                "Players have borrowed six books, and one is out now; one never came back, and I wrote each out again; the fines came to ten coins."),
            List.of())));
        TradeBookWriter.Person bram = new TradeBookWriter.Person("Bram", 14, List.of("hardworking"), "", "walking", 33, "came from Ashford",
            30, List.of(), "", "I found the diamonds in the deep cave", 50, "");
        out.add(TradeBookWriter.write(new TradeBookWriter.Facts(TOWN, "CAVE", 60, "summer, the town's second year", 2, List.of(48L), bram,
            List.of(new TradeBookWriter.Hand("Bram", 14, ""), new TradeBookWriter.Hand("Fen", 6, "")),
            List.of(new TradeBookWriter.Made("raw iron", 212, 40, 31, 52), new TradeBookWriter.Made("coal", 140, 22, 18, 55)),
            12, 62, 48, 31, 52, "raw iron", 352, 9, 41,
            List.of(new TradeBookWriter.Lesson(54, "lost", "", "Fen's partner Tam was lost in the caves north-east of the town")),
            "the Iron Age", 30, "the Diamond Age", 0,
            List.of("Between us we've found seven caves and twelve veins of ore, ninety-one of it mined and brought home.",
                "The richest vein we know is diamond, four blocks of it, found by Bram on day 50.",
                "We've come on two spawners down there. Break it or light it up: never leave one as you found it.",
                "Our last haul, as the books have it: the team (Bram and Fen), two days: 18 raw iron, 9 coal and 2 diamonds; 23 ore mined; 31 torches set of 48 drawn.",
                "We have lost one of us below. Never go down alone, and never past your torches.",
                "Light every fifteen blocks: a torch on your right going in, so the way out is on your left coming home. We've set 212 torches so far of the 300 we drew."),
            List.of(new TradeBookWriter.Change("lesson", "On day 54 Fen's partner Tam was lost in the caves north-east of the town.", "", 54, 0, 0)))));
        out.add(Tales.life(new Tales.Life("Tam", false, 29, "cave dweller", 0, "", List.of(), "", "", "", false, "", 4, "Holt and Wren", "Fen",
            List.of(), List.of(), List.of(), "", List.of("Tam found a vein of gold on day 47."), List.of(), 54, "in the caves",
            "who went down into the caves for the town, and did not come home",
            List.of(new Tales.Event(54, "", "Tam was lost in the caves north-east of the town"))), TOWN, voice("Fen", 2), "my partner", 60));
        // Every kind of poem, by every voice.
        Map<String, Map<String, String>> subjects = new HashMap<>();
        subjects.put("WEDDING", Map.of("A", "Cobb", "B", "Wick", "tradeA", "farmer", "tradeB", "smelter", "season", "summer", "place", "the courtyard", "day", "41"));
        subjects.put("ELEGY", Map.of("A", "Fern", "tradeA", "miner", "trade", "MINE", "cause", "fall", "nwords", "forty-one", "kin", "Holt",
            "children", "Pip and Wren", "season", "autumn", "day", "51"));
        subjects.put("HARVEST", Map.of("A", "Marrow", "crop", "wheat", "nwords", "ninety-eight", "season", "summer", "day", "33"));
        subjects.put("SEA", Map.of("A", "Rook", "nwords", "Two hundred and twelve", "season", "winter", "day", "50"));
        subjects.put("PET", Map.of("A", "Smudge", "B", "Tansy", "kind", "cat", "season", "spring", "day", "12"));
        subjects.put("BIRTH", Map.of("A", "Pip", "parents", "Marrow and Tansy", "parentA", "Marrow", "parentB", "Tansy", "season", "spring", "day", "3"));
        subjects.put("TOWN", Map.of("founders", "five", "nwords", "forty-one", "b1", "meeting hall", "b2", "smeltery", "age", "the Iron Age",
            "motto", "Stone by Stone", "season", "spring", "day", "60"));
        subjects.put("FRIEND", Map.of("A", "Bramble", "B", "Holt", "season", "summer", "day", "20"));
        // [weave] a great work opened, and a famous auction
        subjects.put("WORKS", Map.of("A", "the bridge", "B", "day 52", "nwords", "seventeen", "stones", "two hundred and forty", "season", "autumn", "day", "52"));
        subjects.put("AUCTION", Map.of("A", "Mara the smith", "B", "a diamond", "nwords", "forty-eight", "season", "spring", "day", "56"));
        int n = 0;
        for (String kind : Verse.kinds()) {
            for (int v = 0; v < 3; v++) out.add(Verse.write(new Verse.Subject(kind, TOWN, subjects.get(kind)), voice("Ada", n++), 42 + v));
        }
        Map<String, String> dog = new HashMap<>(subjects.get("PET"));
        dog.put("A", "Biscuit");
        dog.put("kind", "dog");
        out.add(Verse.write(new Verse.Subject("PET", TOWN, dog), voice("Holt", 3), 44));
        // Histories.
        List<Tales.Event> ev = new ArrayList<>();
        ev.add(new Tales.Event(0, "spring of the first year", "Thornhurst was founded"));
        ev.add(new Tales.Event(0, "spring of the first year", "the founders chose the town's banner (a golden hive on red) and its motto, \"Stone by Stone\""));
        ev.add(new Tales.Event(2, "spring of the first year", "The shelter was finished"));
        ev.add(new Tales.Event(3, "spring of the first year", "Marrow and Tansy had a child, Pip"));
        ev.add(new Tales.Event(9, "summer of the first year", "the village came into the Stone Age"));
        ev.add(new Tales.Event(11, "summer of the first year", "Cobb and Wick were wed"));
        ev.add(new Tales.Event(12, "summer of the first year", "5 raiders came at the north gate in the night; the watch killed 5 of them and nobody was lost"));
        ev.add(new Tales.Event(16, "autumn of the first year", "the town brought in its record harvest, 46 coins' worth in a day, from Marrow's field"));
        ev.add(new Tales.Event(18, "autumn of the first year", "Fern died, aged 41, in a fall"));
        ev.add(new Tales.Event(23, "winter of the first year", "the meeting hall was opened"));
        List<String> founders = List.of("Marrow", "Tansy", "Bramble", "Rook", "Fern");
        out.add(Tales.history(new Tales.History("FOUNDING", TOWN, "", ev, founders, "the Iron Age", 41, "Stone by Stone", 0), voice("Ada", 4), 60));
        out.add(Tales.history(new Tales.History("AGES", TOWN, "IRON", List.of(ev.get(0), ev.get(2), ev.get(4), ev.get(9),
            new Tales.Event(30, "", "the village came into the Iron Age"), new Tales.Event(33, "", "the market was opened")), founders, "the Iron Age",
            41, "", 0), voice("Rook", 1), 61));
        out.add(Tales.history(new Tales.History("FIRE", TOWN, "1", List.of(new Tales.Event(33, "spring of the second year",
            "a fire at the smeltery, started by a spark from the furnace, was put out by Rook and Holt with a bucket of water")), founders,
            "the Iron Age", 41, "Stone by Stone", 0), voice("Holt", 3), 62));
        out.add(Tales.history(new Tales.History("WAR", TOWN, "Kingsgate", List.of(new Tales.Event(40, "", "we declared war on Kingsgate, who would not meet our demands: twenty iron"),
            new Tales.Event(43, "", "the war banner was hung at last, for the war with Kingsgate"),
            new Tales.Event(47, "", "Kingsgate and Thornhurst made peace before the board")), founders, "the Iron Age", 41, "Stone by Stone", 0), voice("Tansy", 0), 63));
        out.add(Tales.history(new Tales.History("YEAR", TOWN, "1", ev, founders, "the Iron Age", 41, "", 0), voice("Bramble", 6), 64));
        // [weave] The great flood, the great fire, and the smugglers' tale, from their chronicle lines.
        out.add(Tales.history(new Tales.History("FLOOD", TOWN, "45", List.of(
            new Tales.Event(43, "spring of the second year", "rain on four of the last seven days: the river is high"),
            new Tales.Event(44, "spring of the second year", "The great flood: the river came up over the low ground, 412 cells under water, 2 houses flooded, 31 crops spoiled in the low fields"),
            new Tales.Event(44, "spring of the second year", "Marrow carried the Tansy family's things out of the flood"),
            new Tales.Event(46, "spring of the second year", "The river began to go down: the rain has stopped"),
            new Tales.Event(47, "spring of the second year", "The river went back within its banks, and the low ground came out of the water"),
            new Tales.Event(50, "spring of the second year", "a levee of 38 blocks was raised along the bank where the river came over")),
            founders, "the Iron Age", 41, "Stone by Stone", 0), voice("Rook", 2), 66));
        out.add(Tales.history(new Tales.History("GREATFIRE", TOWN, "34", List.of(
            new Tales.Event(33, "spring of the second year", "Fire at the smithy! (a spark from the forge) The bell rang and the town turned out"),
            new Tales.Event(33, "spring of the second year", "the fire burnt 14 blocks of the smithy: it will be rebuilt from the stores"),
            new Tales.Event(33, "spring of the second year", "a fire at the smithy, started by a spark from the forge, was put out by Rook and Holt with a bucket of water, a chain of six passing 22 buckets"),
            new Tales.Event(36, "spring of the second year", "The smithy was rebuilt after the fire: 14 blocks put back out of the stores")),
            founders, "the Iron Age", 41, "", 0), voice("Holt", 5), 67));
        out.add(Tales.history(new Tales.History("SMUGGLERS", TOWN, "12", List.of(
            new Tales.Event(40, "How it began", "Cobb came to Steve with it: \"Somebody's been at the stores. Forty bread gone, and the books don't add up.\""),
            new Tales.Event(41, "The gossip", "Wren said Ash has been out after dark, towards the caves north of the town."),
            new Tales.Event(41, "The camp", "A camp in a cave north of the town: a lantern, a bedroll, and the town's bread in a chest."),
            new Tales.Event(42, "The ledger", "The smugglers' ledger: thirty-two bread, nine iron, and a note signed R."),
            new Tales.Event(44, "How it ended", "The ledger and Ash's face told the council enough. The council found them guilty and fined them into the treasury.")),
            founders, "the Iron Age", 41, "", 0), voice("Tansy", 6), 68));
        // Lives.
        out.add(Tales.life(new Tales.Life("Marrow", true, 46, "farmer", 14, "miner 3", List.of("cheerful", "curious"), "growing flowers",
            "names every tool", "to be the best at my trade in the village", true, "a founder of the village", -1, "", "Tansy", List.of("Pip", "Wren"),
            List.of("Bramble", "Holt", "Cobb"), List.of(new Tales.Memory(3, "Tansy and I had a child, Pip"), new Tales.Memory(16, "I brought in the record harvest"),
                new Tales.Memory(18, "I lost Fern")), "1,240 crops brought in", List.of("Marrow brought in the town's record harvest on day 16."),
            List.of("Green Thumb"), -1, "", "one of the founders of Thornhurst", List.of(ev.get(7))), TOWN, voice("Tansy", 0), "my partner", 60));
        out.add(Tales.life(new Tales.Life("Fern", false, 64, "miner", 0, "", List.of(), "", "", "", false, "", 2, "Ash and Elm", "Holt", List.of("Pip"),
            List.of(), List.of(), "", List.of("Fern mined the town's first diamond on day 31."), List.of(), 70, "in a fall", "who lived to 64",
            List.of(new Tales.Event(31, "", "Fern mined the town's first diamond"))), TOWN, voice("Holt", 2), "my partner", 72));
        // How-to books on every topic.
        int k = 0;
        for (String topic : Tales.topics()) {
            out.add(Tales.howTo(new Tales.HowTo(topic, TOWN, k % 2 == 0 ? "beekeeper" : "", 12, k % 3 == 0 ? 340 : 0, "honeycombs taken",
                List.of("the smeltery, north of the square", "the meeting hall, east of the square", "the school, south of the square")), voice("Ada", k), 50 + k));
            k++;
        }
        // Storybooks, with and without a pet, a guard and the rest.
        Tales.Story full = new Tales.Story(TOWN, List.of("Pip", "Wren"), "Smudge", "cat", "Tansy", "Holt", "Marrow", "Rook", "Cobb", "Tansy", "Bramble",
            List.of("the well", "the school", "the bell tower", "the bakery"), "farmer");
        Tales.Story bare = new Tales.Story(TOWN, List.of("Pip"), "", "", "", "", "", "", "", "", "", List.of(), "");
        for (int i = 0; i < 6; i++) out.add(Tales.story(full, voice("Tansy", i), 50 + i));
        out.add(Tales.story(bare, voice("Tansy", 1), 70));
        return out;
    }

    // ------------------------------------------------------------------ the checks

    @Test
    @DisplayName("every page of every book fits a book's page, and every title a book's title")
    void everyPageFits() {
        for (Quill.Book b : shelf()) {
            assertTrue(!b.title().isEmpty() && b.title().length() <= Quill.TITLE_MAX, "a title that fits: '" + b.title() + "'");
            assertTrue(!b.pages().isEmpty() && b.pages().size() <= Quill.MOST_PAGES, b.title() + ": " + b.pages().size() + " pages");
            for (String p : b.pages()) {
                String[] lines = p.split("\n", -1);
                assertTrue(lines.length <= Quill.LINES, b.title() + ": a page of " + lines.length + " lines:\n" + p);
                assertTrue(!p.isBlank(), b.title() + ": a blank page");
                for (String l : lines) assertTrue(Quill.px(l) <= Quill.PAGE_PX, b.title() + ": '" + l + "' is " + Quill.px(l) + " wide");
            }
        }
    }

    @Test
    @DisplayName("nothing is left unfilled: no {slot}, no null, no doubled word")
    void nothingUnfilled() {
        for (Quill.Book b : shelf()) {
            String all = String.join(" ", b.pages()) + " " + b.title() + " " + b.blurb();
            assertTrue(!all.contains("{") && !all.contains("}"), b.title() + ": an unfilled slot in\n" + all);
            assertTrue(!all.contains("null"), b.title() + ": 'null' in\n" + all);
            assertTrue(!all.matches("(?s).*\\b(the the|a a|of of|and and)\\b.*"), b.title() + ": a doubled word in\n" + all);
            assertTrue(!all.contains(" ,") && !all.contains(",,") && !all.contains(".."), b.title() + ": stray punctuation in\n" + all);
        }
    }

    @Test
    @DisplayName("the trade's book has the town's own numbers, names and lessons, and its new edition says what changed")
    void theTradeBookIsTheTowns() {
        Quill.Book first = TradeBookWriter.write(farm(1, List.of(), 0));
        String a = flat(first);
        assertTrue(first.title().equals("The Farmer's Book of Thornhurst"), first.title());
        assertTrue(a.contains("258 wheat") && a.contains("ninety-eight wheat") && a.contains("day 33"), "the week and the record:\n" + a);
        assertTrue(a.contains("Cobb") && a.contains("Wick") && a.contains("Marrow"), "the hands by name");
        assertTrue(a.contains("we lost Fern") && a.contains("dried out"), "the lessons");
        assertTrue(a.contains("iron hoes"), "what the age allows");
        Quill.Book second = TradeBookWriter.write(farm(2, List.of(new TradeBookWriter.Change("record", "wheat", "", 44, 150, 98),
            new TradeBookWriter.Change("age", "the Iron Age", "", 40, 0, 0)), 1));
        String b = flat(second);
        assertTrue(b.contains("New in this edition") && b.contains("A new record: 150 wheat") && b.contains("The old best was ninety-eight"),
            "the new edition says what changed:\n" + b);
        assertTrue(second.news().size() == 2, "and its news, a line a change: " + second.news());
    }

    @Test
    @DisplayName("a wedding poem names the couple, in four-line verses")
    void aWeddingPoemNamesTheCouple() {
        for (int i = 0; i < 12; i++) {
            Quill.Book p = Verse.write(new Verse.Subject("WEDDING", TOWN, Map.of("A", "Cobb", "B", "Wick", "season", "summer", "place", "the square",
                "day", "41")), voice("Ada", i), 40 + i);
            String all = flat(p);
            assertTrue(all.contains("Cobb") && all.contains("Wick"), "the couple named:\n" + all);
            assertTrue(p.kind().equals("POEM") && p.subject().equals("poem:WEDDING:Cobb|Wick"), p.subject());
        }
    }

    @Test
    @DisplayName("[weave] the new work's books say what happened: the caves, the library, the flood, the fire, the smugglers, the lost, the works, the sale")
    void theNewBooksSayWhatHappened() {
        Map<String, String> byTitle = new HashMap<>();
        for (Quill.Book b : shelf()) byTitle.put(b.title(), flat(b));       // the last of a title: the weave's own (after the trades' round)
        String caves = byTitle.get("The Cave Dweller's Book");
        assertTrue(caves != null && caves.contains("Light every fifteen blocks") && caves.contains("We have lost one of us below")
            && caves.contains("The richest vein we know is diamond"), "the cave dwellers' book: the light, the lost, the veins:\n" + caves);
        String lib = byTitle.get("The Librarian's Book");
        assertTrue(lib != null && lib.contains("how the library is kept") && lib.contains("fourteen books") && lib.contains("The Farmer's Book")
            && lib.contains("two coins a day"), "the librarian's own book: the shelves, the most read, the fines:\n" + lib);
        String flood = byTitle.get("The Great Flood of Thornhurst");
        assertTrue(flood != null && flood.contains("the great flood: the river came up") && flood.contains("levee") && !flood.contains("of day 45:"),
            "the flood's history, its lines told on their days:\n" + flood);
        String fire = byTitle.get("The Great Fire of Thornhurst");
        assertTrue(fire != null && fire.contains("burnt 14 blocks") && fire.contains("rebuilt after the fire:") && fire.contains("every block of it"),
            "the great fire and the rebuilding after it:\n" + fire);
        String smugglers = byTitle.get("The Smugglers' Cave");
        assertTrue(smugglers != null && smugglers.contains("How it began") && smugglers.contains("How it ended") && smugglers.contains("ledger"),
            "the smugglers' tale, from first word to verdict:\n" + smugglers);
        String tam = byTitle.get("Tam of Thornhurst");
        assertTrue(tam != null && tam.contains("did not come home") && tam.contains("in the caves"), "the life of the one lost below:\n" + tam);
        boolean bridge = false, sale = false;
        for (Map.Entry<String, String> e : byTitle.entrySet()) {
            bridge |= e.getValue().contains("bridge") && e.getKey().contains("Bridge");
            sale |= e.getValue().contains("Mara the smith") && e.getValue().contains("forty-eight");
        }
        assertTrue(bridge && sale, "a poem for the bridge's opening, and a ballad of the famous sale");
    }

    @Test
    @DisplayName("a shelf of sample books, printed with -Dlibrary.samples=true")
    void samples() {
        if (!Boolean.getBoolean("library.samples")) return;
        for (Quill.Book b : shelf()) {
            System.out.println("==================== " + b.title() + " by " + b.author() + " [" + b.kind() + ", " + b.pages().size() + " pages] " + b.blurb());
            int i = 1;
            for (String p : b.pages()) {
                System.out.println("----- page " + i++);
                System.out.println(p.replace("§l", "").replace("§r", ""));
            }
        }
    }

    private static String flat(Quill.Book b) {
        return String.join("\n", b.pages()).replaceAll("§.", "").replaceAll("\\s+", " ");
    }
}
