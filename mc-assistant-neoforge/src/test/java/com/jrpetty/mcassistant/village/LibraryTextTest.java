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
            "SHOP", "STORE", "HAUL", "HUNT", "SCOUT", "BANK", "CAVE", "NONE" };
        for (int i = 0; i < keys.length; i++) {
            TradeBookWriter.Facts f = farm(1 + i % 3, i % 3 == 0 ? List.of() : List.of(new TradeBookWriter.Change("record", "wheat", "", 33, 98, 74),
                new TradeBookWriter.Change("age", "the Iron Age", "", 22, 0, 0), new TradeBookWriter.Change("master", "Marrow", "Tansy", -1, 0, 0)), i);
            out.add(TradeBookWriter.write(new TradeBookWriter.Facts(f.town(), keys[i], f.day(), f.date(), f.edition(), f.earlier(), f.master(), f.hands(),
                i % 4 == 3 ? List.of() : f.made(), i % 4 == 3 ? 0 : f.days(), f.week(), f.lastWeek(), f.best(), f.bestOn(), f.bestWhat(), f.allTime(),
                f.worth(), f.deeds(), i % 2 == 0 ? f.lessons() : List.of(), f.age(), f.ageSince(), f.nextAge(), f.research(), f.notes(), f.changes())));
        }
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
