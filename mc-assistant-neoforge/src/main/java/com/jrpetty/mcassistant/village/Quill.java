package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The writer's desk: what every book the town writes is made with. [library]
 *
 * <p>The town's books (the trades' books of best practice, the histories, the poems, the how-to books, the
 * lives and the children's stories: TradeBookWriter, Verse, Tales) are written out of plain words and
 * numbers, with no world to hand, so the same writing can be tried out in a test with no game running and
 * read. Here is what they share:
 * <ul>
 * <li><b>The dice.</b> Every choice a writer makes (which stanza, which turn of phrase) is thrown on dice
 * seeded from the town, the writer and the subject, so a book written twice from the same facts is the same
 * book, and two writers on one subject write two different ones.</li>
 * <li><b>Words.</b> Numbers as a writer writes them ("forty-two", "the third"), lists ("bread, wool and
 * coal"), a or an, plurals.</li>
 * <li><b>The voice.</b> A writer's two traits and its quirk colour how it puts things: the grumpy are short
 * with the reader, the shy apologise, the cheerful cannot help an exclamation mark, the curious wonder aloud.</li>
 * <li><b>The page.</b> A written book's page is 114 pixels wide and shows fourteen lines; a page here holds
 * thirteen, every line wrapped to the width at its spaces (by the font's own letter widths, as Archive has
 * them), a heading never left at the foot of a page, a short verse kept whole, a title page set in the middle.</li>
 * </ul>
 */
public final class Quill {

    private Quill() {}

    // ------------------------------------------------------------------ the dice

    /** A writer's dice: the same seed throws the same numbers, game or no game. */
    public static final class Dice {
        private long s;

        public Dice(long seed) {
            this.s = seed ^ 0x5DEECE66DL;
            next();
        }

        /** Dice seeded from words: the town, the writer, the subject. */
        public static Dice of(Object... parts) {
            long h = 1125899906842597L;
            for (Object o : parts) {
                String t = String.valueOf(o);
                for (int i = 0; i < t.length(); i++) h = 31 * h + t.charAt(i);
                h = h * 0x9E3779B97F4A7C15L + 7;
            }
            return new Dice(h);
        }

        private long next() {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            return s;
        }

        /** Nought up to (not including) n. */
        public int roll(int n) {
            if (n <= 1) return 0;
            return (int) Math.floorMod(next(), (long) n);
        }

        /** True so many times in a hundred. */
        public boolean chance(int percent) {
            return roll(100) < percent;
        }

        public String pick(String... options) {
            return options[roll(options.length)];
        }

        public <T> T pick(List<T> options) {
            return options.get(roll(options.size()));
        }

        /** Up to n of these, in a new order. */
        public <T> List<T> some(Collection<T> from, int n) {
            List<T> all = new ArrayList<>(from);
            for (int i = all.size() - 1; i > 0; i--) {
                int j = roll(i + 1);
                T t = all.get(i);
                all.set(i, all.get(j));
                all.set(j, t);
            }
            return all.size() <= n ? all : new ArrayList<>(all.subList(0, n));
        }
    }

    // ------------------------------------------------------------------ words

    private static final String[] ONES = { "nought", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen" };
    private static final String[] TENS = { "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };
    private static final String[] NTHS = { "nought", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
        "eighteenth", "nineteenth", "twentieth" };

    /** A number as a writer writes it in a sentence: in words to a hundred, in figures past it ("1,240"). */
    public static String number(long n) {
        if (n < 0) return "minus " + number(-n);
        if (n < 20) return ONES[(int) n];
        if (n < 100) return TENS[(int) n / 10] + (n % 10 == 0 ? "" : "-" + ONES[(int) n % 10]);
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A number in words, the whole way, for verse ("two hundred and twelve"); in figures past ten thousand. */
    public static String spelled(long n) {
        if (n < 100) return number(n);
        if (n >= 10000) return number(n);
        StringBuilder sb = new StringBuilder();
        if (n >= 1000) {
            sb.append(ONES[(int) (n / 1000)]).append(" thousand");
            n %= 1000;
            if (n == 0) return sb.toString();
            sb.append(n < 100 ? " and " : " ");
        }
        if (n >= 100) {
            sb.append(ONES[(int) (n / 100)]).append(" hundred");
            n %= 100;
            if (n == 0) return sb.toString();
            sb.append(" and ");
        }
        sb.append(number(n));
        return sb.toString();
    }

    /** "first", "twelfth", "21st". */
    public static String ordinal(long n) {
        if (n >= 0 && n < NTHS.length) return NTHS[(int) n];
        long t = n % 100;
        String suffix = t >= 11 && t <= 13 ? "th" : switch ((int) (n % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    /** So many of a thing: "one hive", "three hives", "212 wheat" (a heap's name is the same many or one). */
    public static String count(long n, String one, String many) {
        return number(n) + " " + (n == 1 ? one : many);
    }

    /** "bread, wool and coal"; "" for none. */
    public static String list(List<String> items) {
        List<String> l = new ArrayList<>();
        for (String s : items) if (s != null && !s.isEmpty()) l.add(s);
        if (l.isEmpty()) return "";
        if (l.size() == 1) return l.get(0);
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    /** The first letter made a capital. */
    public static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        if (s.startsWith("§") && s.length() > 2) i = 2;
        return s.substring(0, i) + Character.toUpperCase(s.charAt(i)) + s.substring(i + 1);
    }

    /** "an apple", "a pear". */
    public static String a(String word) {
        if (word == null || word.isEmpty()) return "";
        char c = Character.toLowerCase(word.charAt(0));
        boolean vowel = "aeio".indexOf(c) >= 0 || c == 'u' && !word.toLowerCase(Locale.ROOT).startsWith("use");
        return (vowel ? "an " : "a ") + word;
    }

    /** "Marrow's", "Moss's". */
    public static String his(String name) {
        return name + "'s";
    }

    /** A sentence made to end as a sentence does. */
    public static String stop(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return t;
        char c = t.charAt(t.length() - 1);
        // A sentence that ends on a quotation has its stop inside the marks if the quotation was a sentence of its
        // own ("Keep it dry."), and after them if it was only a name or a motto ("Stone by Stone").
        if ((c == '"' || c == '\'') && t.length() > 1) {
            char in = t.charAt(t.length() - 2);
            return in == '.' || in == '!' || in == '?' ? t : t + ".";
        }
        return c == '.' || c == '!' || c == '?' || c == ':' ? t : t + ".";
    }

    /** "day 12"; with its season, if there is one: "day 12, in the spring of the town's second year". */
    public static String onDay(long day) {
        return "day " + day;
    }

    // ------------------------------------------------------------------ the voice

    /**
     * Who is writing: its name, its traits (as the game names them: "cheerful", "grumpy"...), its quirk
     * ("hums while working"), its pastime and its trade. What it says, it says its own way.
     */
    public record Voice(String name, Set<String> traits, String quirk, String hobby, String trade) {

        public static Voice of(String name, Collection<String> traits, String quirk, String hobby, String trade) {
            Set<String> t = new LinkedHashSet<>();
            for (String s : traits) if (s != null) t.add(s.toLowerCase(Locale.ROOT));
            return new Voice(name == null ? "" : name, t, quirk == null ? "" : quirk, hobby == null ? "" : hobby,
                trade == null ? "" : trade);
        }

        public boolean is(String trait) {
            return traits.contains(trait);
        }

        /** The trait that sets the tone, the loudest first. */
        public String tone() {
            for (String t : new String[]{ "grumpy", "cheerful", "shy", "curious", "hardworking", "easygoing", "sociable", "generous" }) {
                if (traits.contains(t)) return t;
            }
            return "plain";
        }

        /** A sentence put its way: the cheerful one's full stop now and then an exclamation mark. */
        public String say(Dice d, String sentence) {
            String s = stop(sentence);
            if (is("cheerful") && s.endsWith(".") && d.chance(35)) s = s.substring(0, s.length() - 1) + "!";
            return s;
        }

        /** How it opens a thing it wants the reader to mark. */
        public String mark(Dice d) {
            return switch (tone()) {
                case "grumpy" -> d.pick("Listen.", "Mark this.", "I'll say it once.", "Pay attention here.");
                case "cheerful" -> d.pick("Now here's a good one:", "Oh, and this:", "Here's my favourite:", "A happy secret:");
                case "shy" -> d.pick("If I may:", "I think — I'm fairly sure —", "For what it's worth:", "One small thing:");
                case "curious" -> d.pick("I tried this, to see:", "Here's something I found out:", "A thing I wondered about:", "Try this:");
                case "hardworking" -> d.pick("Do this, every day:", "No shortcuts here:", "This is the work:", "Every morning:");
                case "easygoing" -> d.pick("No rush, but:", "Easy does it:", "Take it gently:", "Here's an easy one:");
                case "sociable" -> d.pick("As I always tell the others:", "Ask anyone here and they'll say:", "We all agree on this:", "Between ourselves:");
                case "generous" -> d.pick("Take this from me, freely:", "Here's something worth having:", "I'll share this:", "This one's yours:");
                default -> d.pick("Mark this:", "Remember:", "Here's the thing:", "Note this:");
            };
        }

        /** A wondering aside of the curious, or nothing. */
        public String wonder(Dice d) {
            if (!is("curious") || !d.chance(60)) return "";
            return " " + d.pick("I wonder why that is.", "Nobody has told me why. One day I'll find out.",
                "If you know why, write it in the margin.", "Strange, isn't it?");
        }

        /** Its quirk, in a word of its own, or nothing: "(I hum while I work. The wheat doesn't mind.)". */
        public String quirkAside(Dice d) {
            return switch (quirk) {
                case "hums while working" -> "(I hum while I work. The " + d.pick("wheat", "rock", "work", "others") + " don't seem to mind.)";
                case "collects pretty stones" -> "(Keep an eye out for pretty stones. I've a shelf of them at home, and every one has a story.)";
                case "never misses a sunrise" -> "Be up with the sun. I've never missed a sunrise, and I've never been sorry.";
                case "talks to the chickens" -> "Talk to the animals. The chickens and I understand each other, mostly.";
                case "is afraid of the dark" -> "I'll own I don't care for the dark. Light everything. It's no shame.";
                case "counts everything twice" -> "Count it twice. I do, and I've never once been short.";
                case "can't sit still" -> "I can't sit still, as everybody knows, so take my word for it: there's always another thing to do.";
                case "keeps a diary" -> "I keep a diary, so I can tell you the days. Keep one yourself; the town forgets more than it knows.";
                case "names every tool" -> "Name your tools. Mine is called " + d.pick("Old Meg", "Bess", "the Judge", "Sweet Fanny", "Grumble")
                    + ", and she has never once let me down.";
                case "hates the rain" -> "I hate the rain, I'll not pretend otherwise; but the work doesn't stop for it, so nor do I.";
                case "loves the rain" -> "I love a wet day. The town goes quiet and the work goes on, and you can hear yourself think.";
                case "tells terrible jokes" -> "";                              // its joke is told elsewhere
                case "always knows the time" -> "Keep the hours. I always know the time, and so should anybody with work to do.";
                case "whistles badly" -> "(I whistle while I work. Badly, I'm told. The work gets done all the same.)";
                case "sings in the bath" -> "(I sing in the bath. That has nothing to do with any of this, but it's true.)";
                case "remembers every birthday" -> "And remember the birthdays of the folk you work with. A good word on the day goes a long way.";
                default -> "";
            };
        }

        /** How it signs off a book it means to be used. */
        public String farewell(Dice d, String work) {
            return switch (tone()) {
                case "grumpy" -> d.pick("That's all. Now put the book down and go and do it.", "There. You've no excuse now.",
                    "Read it twice. Then get to work.");
                case "cheerful" -> d.pick("Happy " + work + ", and may the work go well!", "Good luck to you — you'll be grand!",
                    "Off you go, and enjoy every minute of it!");
                case "shy" -> d.pick("I hope some of this is useful. Do ask me if it isn't — I don't bite.",
                    "That's everything I know, more or less. I'm sorry it isn't more.", "I'm no writer. I hope it helps anyway.");
                case "curious" -> d.pick("And if you find a better way, put it in the next edition. I'd love to know.",
                    "There's more to find out. Go and find it.", "I've more questions than answers still. Perhaps you'll answer some.");
                case "hardworking" -> d.pick("The work won't do itself. Up early, and at it.", "That's the trade. Now earn your bread.",
                    "No more reading. There's work waiting.");
                case "easygoing" -> d.pick("Don't fret over it. It all comes right in the end.", "Take your time. The work keeps.",
                    "No hurry. Learn it at your own pace.");
                case "sociable" -> d.pick("Come and find me at the tavern and we'll talk it over.", "Ask any of us. We like to be asked.",
                    "Find me of an evening and I'll tell you the rest.");
                case "generous" -> d.pick("Anything here you don't follow, ask: I'll show you with my own hands.",
                    "Whatever you need, the stores and I will see you right.", "Take what's useful. It's yours.");
                default -> d.pick("That is the trade as I know it.", "Use it well.", "So much for the book. The rest is practice.");
            };
        }
    }

    // ------------------------------------------------------------------ the page

    /** A book's page: how wide in pixels, how many lines it holds here (the screen shows fourteen), how many pages a book holds. */
    public static final int PAGE_PX = 114, LINES = 13, MOST_PAGES = 100;
    /** What lines are wrapped to: a little inside the page, so a letter wider than reckoned never tips one over. */
    public static final int WRAP_PX = PAGE_PX - 4;
    /** A written book's title, at most (the game's own limit). */
    public static final int TITLE_MAX = 32;

    /** About how wide this is in a book's font, in pixels: "§l" bold adds one to every letter after it. */
    public static int px(String s) {
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                if (code == 'l') bold = true;
                else if (code == 'r') bold = false;
                continue;
            }
            w += glyph(c) + (bold ? 1 : 0);
        }
        return w;
    }

    /** One letter's width with the gap after it, as the game's own font has them (Archive.glyph). */
    private static int glyph(char c) {
        return switch (c) {
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case 'l', '`' -> 3;
            case ' ', 'I', 't', '[', ']' -> 4;
            case '(', ')', '{', '}', '<', '>', 'f', 'k', '"', '*' -> 5;
            case '@', '~' -> 7;
            default -> c < 128 ? 6 : 7;
        };
    }

    /** Words the book's font has no trouble with: the long dash and the curly quotes made plain. */
    public static String plain(String s) {
        return s.replace('—', '-').replace('–', '-').replace("…", "...").replace('’', '\'').replace('‘', '\'')
            .replace('“', '"').replace('”', '"').replace('\n', ' ');
    }

    /** Text wrapped at its spaces into lines no wider than this, every line after the first indented so;
     *  a word too long for a line is cut. Bold carries on into the next line. */
    public static List<String> wrap(String text, int width, String indent) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String lead = "";
        for (String word : plain(text).trim().split(" +")) {
            if (word.isEmpty()) continue;
            String tried = line.length() == 0 ? lead + word : line + " " + word;
            if (px(tried) <= width) {
                line.setLength(0);
                line.append(tried);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                lead = indent + (boldOpen(line.toString()) ? "§l" : "");
            }
            line.setLength(0);
            String rest = lead + word;
            while (px(rest) > width) {
                int cut = rest.length() - 1;
                while (cut > 1 && px(rest.substring(0, cut)) > width) cut--;
                out.add(rest.substring(0, cut));
                rest = indent + rest.substring(cut);
            }
            line.append(rest);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    public static List<String> wrap(String text, int width) {
        return wrap(text, width, "");
    }

    private static boolean boldOpen(String s) {
        int on = s.lastIndexOf("§l"), off = s.lastIndexOf("§r");
        return on >= 0 && on > off;
    }

    /** A line set in the middle of the page, with spaces before it. */
    public static String centred(String s) {
        int pad = Math.max(0, (WRAP_PX - px(s)) / 2 / 4);
        return " ".repeat(pad) + s;
    }

    /**
     * One piece of a book, in the order it is read: a title-page line ('T', set in the middle), a heading
     * ('H', bold, kept with what follows), a paragraph ('P'), a line of verse ('V', run on with an indent if
     * too long), the end of a verse ('E': kept together with the lines before it, if they fit a page), an
     * item in a list ('I', run on under itself), a blank line ('S'), and a new page ('N').
     */
    public record Block(char kind, String text) {
        public static Block title(String s) { return new Block('T', s); }
        public static Block heading(String s) { return new Block('H', s); }
        public static Block para(String s) { return new Block('P', s); }
        public static Block verse(String s) { return new Block('V', s); }
        public static Block stanzaEnd() { return new Block('E', ""); }
        public static Block item(String s) { return new Block('I', s); }
        public static Block space() { return new Block('S', ""); }
        public static Block page() { return new Block('N', ""); }
    }

    /** Lines that go on one page together, and whether the lines after them must start on the same page. */
    private record Chunk(List<String> lines, boolean keepWithNext, boolean pageBreak) {}

    /**
     * A book's blocks laid out as pages: every line no wider than the page, no page longer than it holds,
     * a heading never alone at the foot of a page, the first two lines and the last two of a paragraph kept
     * together, a stanza not split if it fits a page of its own, and no page beginning with a blank line.
     * At most so many pages.
     */
    public static List<String> pages(List<Block> blocks) {
        List<Chunk> chunks = new ArrayList<>();
        List<String> stanza = new ArrayList<>();
        for (Block b : blocks) {
            if (b.kind() != 'V' && !stanza.isEmpty()) stanza(stanza, chunks);
            switch (b.kind()) {
                case 'N' -> chunks.add(new Chunk(List.of(), false, true));
                case 'T' -> {
                    List<String> l = new ArrayList<>();
                    for (String w : wrap(b.text(), WRAP_PX)) l.add(centred(w));
                    chunks.add(new Chunk(l, false, false));
                }
                case 'H' -> chunks.add(new Chunk(wrap("§l" + b.text() + "§r", WRAP_PX), true, false));
                case 'P', 'I' -> {
                    List<String> lines = wrap(b.text(), WRAP_PX, b.kind() == 'I' ? "  " : "");
                    if (lines.size() <= 4) {
                        chunks.add(new Chunk(lines, false, false));
                    } else {
                        chunks.add(new Chunk(lines.subList(0, 2), false, false));
                        for (int i = 2; i < lines.size() - 2; i++) chunks.add(new Chunk(List.of(lines.get(i)), false, false));
                        chunks.add(new Chunk(lines.subList(lines.size() - 2, lines.size()), false, false));
                    }
                }
                case 'V' -> stanza.add(b.text());
                case 'E' -> { }
                default -> chunks.add(new Chunk(List.of(""), false, false));              // 'S'
            }
        }
        if (!stanza.isEmpty()) stanza(stanza, chunks);
        List<String> out = new ArrayList<>();
        List<String> page = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            if (c.pageBreak()) {
                newPage(page, out);
                continue;
            }
            int need = c.lines().size();
            if (c.keepWithNext()) {
                // A heading wants room for itself and the first lines of whatever follows it.
                for (int j = i + 1; j < chunks.size(); j++) {
                    Chunk n = chunks.get(j);
                    if (n.pageBreak()) break;
                    if (n.lines().size() == 1 && n.lines().get(0).isEmpty()) continue;
                    need += Math.min(LINES - need, n.lines().size());
                    break;
                }
            }
            if (!page.isEmpty() && page.size() + need > LINES) newPage(page, out);
            for (String l : c.lines()) {
                if (page.size() >= LINES) newPage(page, out);
                if (page.isEmpty() && l.isEmpty()) continue;                         // no page starts blank
                page.add(l);
            }
        }
        newPage(page, out);
        return out.size() <= MOST_PAGES ? out : new ArrayList<>(out.subList(0, MOST_PAGES));
    }

    /** A stanza's lines (wrapped with a hanging indent) as one chunk if it fits a page, and a blank line after it. */
    private static void stanza(List<String> verse, List<Chunk> chunks) {
        List<String> lines = new ArrayList<>();
        for (String v : verse) lines.addAll(wrap(v, WRAP_PX, "  "));
        verse.clear();
        if (lines.size() <= LINES) {
            chunks.add(new Chunk(lines, false, false));
        } else {
            for (String l : lines) chunks.add(new Chunk(List.of(l), false, false));
        }
        chunks.add(new Chunk(List.of(""), false, false));
    }

    /** The page so far, done: its blank lines off the end, and onto the book if anything is on it. */
    private static void newPage(List<String> page, List<String> out) {
        while (!page.isEmpty() && page.get(page.size() - 1).isBlank()) page.remove(page.size() - 1);
        if (!page.isEmpty()) out.add(String.join("\n", page));
        page.clear();
    }

    // ------------------------------------------------------------------ the book

    /**
     * A book written: what kind it is (TRADE, HISTORY, POEM, HOWTO, LIFE, STORY), its title (no longer than
     * a book's title may be), its author, its pages, a line about it for the catalogue, what it is about (so a
     * subject is written once), and for a new edition what is new in it, in a line each.
     */
    public record Book(String kind, String title, String author, List<String> pages, String blurb, String subject, List<String> news) {}

    /** A title cut to fit a book's: the first of these that fits, else the last cut short. */
    public static String fit(String... tries) {
        for (String t : tries) if (t != null && !t.isEmpty() && t.length() <= TITLE_MAX) return t;
        String last = tries[tries.length - 1];
        return last.length() <= TITLE_MAX ? last : last.substring(0, TITLE_MAX).trim();
    }
}
