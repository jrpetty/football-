package com.jrpetty.mcassistant.village;

import com.jrpetty.mcassistant.village.Quill.Block;
import com.jrpetty.mcassistant.village.Quill.Dice;
import com.jrpetty.mcassistant.village.Quill.Voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jrpetty.mcassistant.village.Quill.cap;
import static com.jrpetty.mcassistant.village.Quill.list;
import static com.jrpetty.mcassistant.village.Quill.number;
import static com.jrpetty.mcassistant.village.Quill.ordinal;
import static com.jrpetty.mcassistant.village.Quill.stop;

/**
 * The town's prose: its histories, the lives of its notable folk, the how-to books its folk write out of
 * their own experience, and the teacher's storybooks for the children. [library]
 *
 * <ul>
 * <li><b>Histories</b> ("How Thornhurst Was Founded", "The Great Fire", "The War with Kingsgate", "From Wood to
 * Iron") are told from the town's chronicle, event by event, in the town's own seasons and years, a chapter
 * to a season or an age, with what the historian makes of each (a birth: "There was no sleep for anybody
 * that night"). Nothing is made up: every happening is the chronicle's, word for word inside the telling.</li>
 * <li><b>Lives</b> ("The Life of Marrow") are told from what the town knows of a folk: where it came from, its
 * trades and levels, its finds, its family and friends, its hopes, and its own memories in its own words.</li>
 * <li><b>How-to books</b> ("How to Keep Bees", "Raising a House", "The Night Sky over Thornhurst") are a
 * folk's experience written up: what you need, the steps, what goes wrong, with its own counts in it.</li>
 * <li><b>Storybooks</b> are the teacher's, for the children, starring the town's real children, its pets and
 * its places, a few lines to a page, as a children's book is.</li>
 * </ul>
 */
public final class Tales {

    private Tales() {}

    // ------------------------------------------------------------------ the chronicle, read

    /** One happening out of the chronicle: its day, when in the town's year ("spring, the first year", or ""), and what happened. */
    public record Event(long day, String when, String text) {}

    /** What kind of happening a chronicle line tells of: AGE, FOUNDING, BUILDING, BIRTH, DEATH, LOVE, FIRE, RAID, WAR,
     *  ELECTION, FIND, HARVEST, WEATHER, TRADE, DREAM, GREW, FEAST, BOOK, OTHER. */
    public static String kindOf(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        if (t.contains("came into the") || t.contains("has grown into")) return "AGE";
        if (t.contains(" founded") || t.contains("was founded")) return "FOUNDING";
        if (t.contains("library")) return "BOOK";
        if (t.contains(" fire ") || t.contains("a fire") || t.contains("burnt") || t.contains("burned")) return "FIRE";
        if (t.contains("raider") || t.contains(" raid")) return "RAID";
        if (t.contains(" war ") || t.contains("declared") || t.contains("alliance") || t.contains("treaty") || t.contains("truce")
            || t.contains("peace with") || t.contains("surrender")) return "WAR";
        if (t.contains(" died") || t.contains("was lost") || t.contains("never came home")) return "DEATH";
        if (t.contains("had a child") || t.contains("had twins") || t.contains("had triplets") || t.contains(" was born")) return "BIRTH";
        if (t.contains("were wed") || t.contains("to be wed") || t.contains("are together now")) return "LOVE";
        if (t.contains("was opened") || t.contains("went up") || t.contains("was raised") || t.contains("was finished")
            || t.contains("was built") || t.contains("builders") || t.contains("was hung") || t.contains("was lit")) return "BUILDING";
        if (t.contains("elected")) return "ELECTION";
        if (t.contains("went on show") || t.contains("first diamond") || t.contains(" found ")) return "FIND";
        if (t.contains("harvest")) return "HARVEST";
        if (t.contains("storm") || t.contains("thunder") || t.contains("flood") || t.contains("drought") || t.contains("snow")
            || t.contains("frost")) return "WEATHER";
        if (t.contains("caravan") || t.contains("traders") || t.contains("market day") || t.contains("merchant")) return "TRADE";
        if (t.contains("dream came true")) return "DREAM";
        if (t.contains("grew up") || t.contains("among the grown")) return "GREW";
        if (t.contains("festival") || t.contains("maypole") || t.contains("bonfire") || t.contains("feast") || t.contains("founding day")
            || t.contains("came in")) return "FEAST";
        return "OTHER";
    }

    /** How much a historian makes of a happening: the founding and the ages most, the town's daily business least. */
    public static int weight(String kind) {
        return switch (kind) {
            case "FOUNDING", "AGE", "WAR", "FIRE", "RAID" -> 10;
            case "DEATH", "BUILDING", "LOVE" -> 7;
            case "BIRTH", "FIND", "HARVEST", "ELECTION", "DREAM" -> 6;
            case "WEATHER", "FEAST", "GREW", "BOOK" -> 4;
            case "TRADE" -> 2;
            default -> 3;
        };
    }

    /** What a historian says after a happening of this kind, now and then. True of any town; never a fact it cannot know. */
    private static String colour(String kind, Dice d, Voice v) {
        String s = switch (kind) {
            case "FOUNDING" -> d.pick("Everything since grew out of that day.", "It was not much to look at, by all accounts. It was a start.");
            case "AGE" -> d.pick("Everything changed after that.", "We were a different town by the end of it.", "The old folk still talk about it.");
            case "BUILDING" -> d.pick("It was the work of many hands.", "Folk still remember the day it was done.", "", "", "");
            case "BIRTH" -> d.pick("There was no sleep for anybody that night.", "The whole town came to look.", "", "");
            case "DEATH" -> d.pick("The bell was rung, and the town was quiet.", "We miss them still.", "", "");
            case "LOVE" -> d.pick("Nobody was surprised.", "It was the talk of the well for days.", "", "");
            case "FIRE" -> d.pick("We kept buckets by the doors after that.", "The smell of it hung about for days.");
            case "RAID" -> d.pick("Nobody slept much that night.", "The wall was never thought of as a waste of stone again.");
            case "WAR" -> d.pick("It was a hard time to be alive.", "Nobody who lived through it will forget it.");
            case "FIND" -> d.pick("It is still talked about.", "", "");
            case "HARVEST" -> d.pick("Nobody went hungry that winter.", "");
            case "WEATHER" -> d.pick("The old folk said they'd never seen the like.", "", "");
            case "DREAM" -> d.pick("It does the heart good to see it.", "");
            default -> "";
        };
        if (s.isEmpty() || !v.is("cheerful") || kind.equals("DEATH") || kind.equals("FIRE") || kind.equals("RAID") || kind.equals("WAR")) return s;
        return s.endsWith(".") && d.chance(40) ? s.substring(0, s.length() - 1) + "!" : s;
    }

    /** The words a chronicle line may start with that are not names, and so go small after a lead-in ("Two days later, the shelter..."). */
    private static final java.util.Set<String> COMMON = java.util.Set.of("The", "A", "An", "Our", "Settlers", "Traders", "Passing", "Winter",
        "Spring", "Summer", "Autumn", "Folk", "Up", "Bought", "Could", "With", "On", "No", "Fire", "Five", "Two", "Three", "Four", "Six",
        "Ten", "Every", "Some", "All", "This", "That", "It", "Its", "There");

    /** A chronicle line as a clause after a lead-in: its first word small unless it is a name. */
    private static String clause(String text) {
        String t = text.trim();
        if (t.isEmpty()) return t;
        int sp = t.indexOf(' ');
        String first = sp < 0 ? t : t.substring(0, sp);
        if (COMMON.contains(first)) return Character.toLowerCase(t.charAt(0)) + t.substring(1);
        return t;
    }

    /** A chronicle line as a sentence of its own. */
    private static String told(String text) {
        return stop(cap(text.trim()));
    }

    /** How a day's happenings are brought in after the day before: "That same day", "Two days later", "On day 12". */
    private static String lead(long day, long prev, Dice d) {
        if (prev == Long.MIN_VALUE) return "On day " + day + ",";
        long gap = day - prev;
        if (gap <= 0) return d.pick("That same day,", "The same day,");
        if (gap == 1) return d.pick("The next day,", "A day later,", "The day after,");
        if (gap <= 4) return cap(number(gap)) + " days later,";
        return d.pick("On day " + day + ",", "By day " + day + ",", "On day " + day + ",");
    }

    /** One day's happenings told: the lead-in and the chronicle's words, then what the historian makes of the greatest of them. */
    private static String tellDay(List<Event> day, long prev, Dice d, Voice v, List<String> founders) {
        StringBuilder sb = new StringBuilder(lead(day.get(0).day(), prev, d)).append(' ');
        String biggest = "OTHER";
        for (int i = 0; i < day.size(); i++) {
            Event e = day.get(i);
            String k = kindOf(e.text());
            if (weight(k) > weight(biggest)) biggest = k;
            String said = stop(clause(e.text()));
            if (i == 0) sb.append(said);
            else if (i == day.size() - 1 && d.chance(50)) sb.append(" And ").append(said);
            else sb.append(' ').append(cap(said));
            if (k.equals("FOUNDING") && !founders.isEmpty()) {
                sb.append(" There were ").append(number(founders.size())).append(" founders: ").append(list(founders)).append('.');
            }
        }
        String colour = colour(biggest, d, v);
        return cap(sb.toString().trim()) + (colour.isEmpty() ? "" : " " + colour);
    }

    /** What opens a chapter: a word on the season, or on the age. */
    private static String opening(String chapter, Dice d) {
        String c = chapter.toLowerCase(Locale.ROOT);
        if (c.startsWith("spring")) return d.pick("Spring is when things begin.", "The ground was soft and the days were drawing out.");
        if (c.startsWith("summer")) return d.pick("The days grew long and warm.", "Summer came, and the work with it.");
        if (c.startsWith("autumn")) return d.pick("Then autumn, and the work of putting by.", "The leaves turned, and so did the town's thoughts to winter.");
        if (c.startsWith("winter")) return d.pick("And then the cold came.", "Winter is the town's hardest season, and its quietest.");
        if (c.contains("wood age")) return "In the beginning everything was wood: the tools, the houses, the fences round the fields.";
        if (c.contains("stone age")) return "With stone came the furnace, and with the furnace, everything else.";
        if (c.contains("iron age")) return "Iron changed everything: the picks, the swords, the buckets, the very sound of the place.";
        if (c.contains("diamond age")) return "In the Diamond Age the town reached for the best of everything, and mostly got it.";
        if (c.contains("nether age")) return "And then the gateway, and the strange red country beyond it.";
        return "";
    }

    /** The happenings of a stretch of the chronicle, counted: "three born, two weddings, four buildings finished and one laid to rest". */
    private static String tally(List<Event> events) {
        int born = 0, wed = 0, built = 0, died = 0;
        for (Event e : events) {
            switch (kindOf(e.text())) {
                case "BIRTH" -> born++;
                case "LOVE" -> { if (e.text().contains("wed")) wed++; }
                case "BUILDING" -> built++;
                case "DEATH" -> died++;
                default -> { }
            }
        }
        List<String> parts = new ArrayList<>();
        if (born > 0) parts.add(number(born) + (born == 1 ? " child born" : " children born"));
        if (wed > 0) parts.add(number(wed) + (wed == 1 ? " wedding" : " weddings"));
        if (built > 0) parts.add(number(built) + (built == 1 ? " building finished" : " buildings finished"));
        if (died > 0) parts.add(number(died) + " laid to rest");
        return list(parts);
    }

    // ------------------------------------------------------------------ histories

    /**
     * A history to write: its kind (FOUNDING, AGES, FIRE, RAID, WAR, YEAR), the town, what it is about in a word
     * (the rival's name for a war, the year's number for a year), the happenings in order, the founders, the town
     * now (its age, its folk, its motto), and the day it was founded.
     */
    public record History(String kind, String town, String about, List<Event> events, List<String> founders, String age, int folk,
                          String motto, long founded) {}

    /** The history's title. */
    public static String historyTitle(History h, Dice d) {
        return switch (h.kind()) {
            case "FOUNDING" -> Quill.fit(d.pick("How " + h.town() + " Was Founded", "The Founding of " + h.town(), h.town() + ": The First Year"),
                "How " + h.town() + " Was Founded", "The Founding");
            case "AGES" -> Quill.fit("From Wood to " + shortAge(h.age()) + ": How " + h.town() + " Grew", "How " + h.town() + " Grew", "How the Town Grew");
            case "FIRE" -> h.events().size() <= 1 ? Quill.fit("The Great Fire", "The Fire") : Quill.fit("The Fires of " + h.town(), "The Fires");
            case "RAID" -> Quill.fit(d.pick("The Night of the Raiders", "When the Raiders Came", "The Raids on " + h.town()), "The Raiders");
            case "WAR" -> Quill.fit("The War with " + h.about(), "The War");
            case "YEAR" -> Quill.fit("The " + cap(ordinal(Long.parseLong(h.about().isEmpty() ? "1" : h.about()))) + " Year of " + h.town(),
                "The " + cap(ordinal(Long.parseLong(h.about().isEmpty() ? "1" : h.about()))) + " Year", "A Year");
            default -> Quill.fit("A History of " + h.town(), "A History");
        };
    }

    private static String shortAge(String age) {
        return cap(age.replaceFirst("^the ", "").replace(" Age", ""));
    }

    /** A history, written. */
    public static Quill.Book history(History h, Voice v, long day) {
        Dice d = Dice.of(h.town(), v.name(), h.kind(), h.about(), day);
        String title = historyTitle(h, d);
        List<Block> b = new ArrayList<>();
        b.add(Block.space());
        b.add(Block.title("§l" + title + "§r"));
        b.add(Block.space());
        b.add(Block.title(d.pick("a history", "as the chronicle tells it", "a history of the town")));
        b.add(Block.space());
        b.add(Block.title("by " + v.name()));
        b.add(Block.title("written on day " + day));
        b.add(Block.page());
        b.add(Block.heading(d.pick("Before we begin", "A word from the author", "Why I wrote this")));
        b.add(Block.para(foreword(h, v, d)));
        // Chapters: by the town's seasons for the first year, by the ages for its growing, by the fire or raid for those.
        Map<String, List<Event>> chapters = chapters(h);
        for (Map.Entry<String, List<Event>> c : chapters.entrySet()) {
            b.add(Block.heading(c.getKey()));
            String open = opening(c.getKey(), d);
            if (!open.isEmpty() && !h.kind().equals("FIRE") && !h.kind().equals("RAID")) b.add(Block.para(open));
            long prev = Long.MIN_VALUE;
            List<Event> day0 = new ArrayList<>();
            for (Event e : c.getValue()) {
                if (!day0.isEmpty() && day0.get(0).day() != e.day()) {
                    b.add(Block.para(tellDay(day0, prev, d, v, h.founders())));
                    prev = day0.get(0).day();
                    day0 = new ArrayList<>();
                }
                day0.add(e);
            }
            if (!day0.isEmpty()) b.add(Block.para(tellDay(day0, prev, d, v, h.founders())));
        }
        if (h.kind().equals("FOUNDING") || h.kind().equals("YEAR") || h.kind().equals("AGES")) {
            String t = tally(h.events());
            if (!t.isEmpty()) b.add(Block.para("In all, the chronicle has " + t + (h.kind().equals("AGES") ? " in those years." : " in that year.")));
        }
        b.add(Block.heading(d.pick("Afterword", "Looking back", "And now")));
        b.add(Block.para(afterword(h, v, d)));
        b.add(Block.para("- " + v.name() + ", day " + day + "."));
        String blurb = switch (h.kind()) {
            case "FOUNDING" -> "a history of the town's first year";
            case "AGES" -> "how the town grew, age by age";
            case "FIRE" -> "a history of the town's fires";
            case "RAID" -> "a history of the raids on the town";
            case "WAR" -> "a history of the war with " + h.about();
            case "YEAR" -> "a history of the town's " + ordinal(Long.parseLong(h.about().isEmpty() ? "1" : h.about())) + " year";
            default -> "a history of the town";
        };
        return new Quill.Book("HISTORY", title, v.name(), Quill.pages(b), blurb, "history:" + h.kind() + ":" + h.about(), List.of());
    }

    /** The happenings put into chapters, each chapter its heading and its happenings in order. */
    private static Map<String, List<Event>> chapters(History h) {
        Map<String, List<Event>> out = new LinkedHashMap<>();
        switch (h.kind()) {
            case "AGES" -> {
                String now = "The Wood Age";
                for (Event e : h.events()) {
                    String k = kindOf(e.text());
                    if (k.equals("AGE") && e.text().toLowerCase(Locale.ROOT).contains("came into")) {
                        String t = e.text();
                        int at = t.toLowerCase(Locale.ROOT).indexOf("the ", t.toLowerCase(Locale.ROOT).indexOf("came into"));
                        if (at >= 0) now = cap(t.substring(at).replaceAll("[.!]$", ""));
                    }
                    out.computeIfAbsent(now, x -> new ArrayList<>()).add(e);
                }
            }
            case "FIRE", "RAID", "WAR" -> {
                int n = 0;
                for (Event e : h.events()) {
                    String head = h.kind().equals("WAR") ? (n == 0 ? "How it began" : "Day " + e.day()) : "Day " + e.day()
                        + (e.when().isEmpty() ? "" : ", " + e.when());
                    out.computeIfAbsent(head, x -> new ArrayList<>()).add(e);
                    n++;
                }
                if (h.kind().equals("WAR") && out.size() > 6) {
                    // A long war in three parts: how it began, the fighting, how it ended.
                    Map<String, List<Event>> three = new LinkedHashMap<>();
                    List<Event> all = h.events();
                    int third = Math.max(1, all.size() / 3);
                    three.put("How it began", new ArrayList<>(all.subList(0, third)));
                    three.put("The fighting", new ArrayList<>(all.subList(third, Math.max(third, all.size() - third))));
                    three.put("How it ended", new ArrayList<>(all.subList(Math.max(third, all.size() - third), all.size())));
                    three.values().removeIf(List::isEmpty);
                    return three;
                }
            }
            default -> {
                // By season: "Spring of the First Year".
                for (Event e : h.events()) {
                    String head = e.when().isEmpty() ? "The early days" : cap(e.when());
                    out.computeIfAbsent(head, x -> new ArrayList<>()).add(e);
                }
            }
        }
        return out;
    }

    private static String foreword(History h, Voice v, Dice d) {
        String what = switch (h.kind()) {
            case "FOUNDING" -> "how " + h.town() + " began";
            case "AGES" -> "how " + h.town() + " grew from a camp to what it is";
            case "FIRE" -> h.events().size() <= 1 ? "the fire" : "the fires the town has known";
            case "RAID" -> "the nights the raiders came";
            case "WAR" -> "the war with " + h.about();
            case "YEAR" -> "the town's " + ordinal(Long.parseLong(h.about().isEmpty() ? "1" : h.about())) + " year";
            default -> "the town";
        };
        String founders = h.founders().isEmpty() || h.kind().equals("FOUNDING") ? "" : " The founders were " + list(h.founders()) + ".";
        String why = switch (v.tone()) {
            case "curious" -> "I wanted to know " + what + ", so I read the chronicle from the start and asked everybody who was there.";
            case "grumpy" -> "Nobody else was going to write down " + what + ", so I have. Read it properly.";
            case "shy" -> "I've tried to set down " + what + " as truly as I can. I hope I've done it justice.";
            case "cheerful" -> "What a story " + what + " is! I've loved every minute of writing it down.";
            case "sociable" -> "Everybody tells " + what + " a little differently at the tavern. Here it is as the chronicle has it, so we can stop arguing.";
            case "hardworking" -> "This is " + what + ", day by day, as the chronicle has it.";
            default -> "This is " + what + ", as the chronicle tells it.";
        };
        String now = h.folk() > 0 ? " Today " + h.town() + " is " + number(h.folk()) + " strong" + (h.age().isEmpty() ? "" : " and in " + h.age()) + "." : "";
        return why + founders + now;
    }

    private static String afterword(History h, Voice v, Dice d) {
        String close = switch (h.kind()) {
            case "FIRE" -> "The fires taught us to keep water by every hearth and a bucket at every door. ";
            case "RAID" -> "The wall stands, and the bell still hangs on the square. That is the lesson of it. ";
            case "WAR" -> "Wars end. Towns go on. " + h.town() + " did. ";
            default -> "";
        };
        String motto = h.motto().isEmpty() ? "" : " Our motto says it best: \"" + stop(h.motto()) + "\"";
        return close + switch (v.tone()) {
            case "curious" -> "I've set down only what the chronicle tells and what folk told me. There's more to find, I'm sure of it.";
            case "grumpy" -> "That's how it was. Anybody who says different wasn't there.";
            case "shy" -> "If I've got anything wrong, I'm sorry. The chronicle has a better memory than I do.";
            case "cheerful" -> "And the best of it, I'm sure, is still to come!";
            case "sociable" -> "Come and find me if you want the parts that didn't make it into the chronicle.";
            default -> d.pick("The rest of the story is still being written.", "The chronicle goes on, and so do we.");
        } + motto;
    }

    // ------------------------------------------------------------------ lives

    /** A memory, a folk's own words, and its day. */
    public record Memory(long day, String text) {}

    /**
     * A life to write: the folk's name; whether it lives; its years; its trade and level, and its other trades in a line;
     * its traits, pastime and quirk; what it hoped for and whether it came true; where it came from, the day it was
     * born or came, its parents, partner, children and friends; its own memories; its deeds in a line; its finds;
     * its knacks; the day and the way it died; why it is notable ("the founder", "the town's elder"...); and the
     * chronicle's lines that name it.
     */
    public record Life(String name, boolean living, int age, String trade, int level, String otherTrades, List<String> traits,
                       String hobby, String quirk, String hope, boolean hopeMet, String origin, long born, String parents,
                       String partner, List<String> children, List<String> friends, List<Memory> memories, String deeds,
                       List<String> finds, List<String> knacks, long died, String cause, String notable, List<Event> mentions) {}

    public static String lifeTitle(Life l, String town, Dice d) {
        return Quill.fit(d.pick("The Life of " + l.name(), l.name() + ": A Life", l.name() + " of " + town),
            "The Life of " + l.name(), l.name());
    }

    /** A life, written by a folk who knew it ({@code knewAs}: "my friend", "my mother", or "" for none). */
    public static Quill.Book life(Life l, String town, Voice v, String knewAs, long day) {
        Dice d = Dice.of(town, v.name(), l.name(), day);
        String title = lifeTitle(l, town, d);
        String n = l.name();
        List<Block> b = new ArrayList<>();
        b.add(Block.space());
        b.add(Block.title("§l" + title + "§r"));
        b.add(Block.space());
        b.add(Block.title(l.living() ? "a life, so far" : "a life"));
        b.add(Block.space());
        b.add(Block.title("by " + v.name()));
        b.add(Block.title("day " + day));
        b.add(Block.page());
        // Why this life.
        String opening = "This is the life of " + n + ", " + l.notable() + ".";
        if (!knewAs.isEmpty()) opening += " " + n + " is " + knewAs + (l.living() ? "" : ", or was") + ", so I have not tried to be fair. "
            + d.pick("I've tried to be true.", "I've tried to be honest instead.");
        b.add(Block.heading(d.pick("Who " + n + " " + (l.living() ? "is" : "was"), "This book", n)));
        b.add(Block.para(opening));
        // Beginnings.
        b.add(Block.heading(d.pick("Beginnings", "The early days", "Where " + n + " came from")));
        String start = switch (l.origin()) {
            case "a founder of the village" -> n + " was one of the founders of " + town + (l.born() >= 0 ? "" : "") + ", there on the first day when the town was a fire, a chest and not much else.";
            case "born here" -> n + " was born in " + town + (l.born() >= 0 ? " on day " + l.born() : "") + (l.parents().isEmpty() ? "." : ", to " + l.parents() + ".");
            default -> l.origin().isEmpty() ? n + " came to " + town + " long enough ago that nobody remembers when."
                : n + " " + l.origin() + (l.born() >= 0 ? ", on day " + l.born() : "") + ".";
        };
        if (!l.parents().isEmpty() && !l.origin().equals("born here")) start += " " + cap(n) + "'s parents were " + l.parents() + ".";
        String nature = l.traits().isEmpty() ? "" : " Everybody would tell you " + n + " " + (l.living() ? "is" : "was") + " "
            + list(l.traits()) + "; " + d.pick("they'd be right.", "they'd not be wrong.", "I'd say the same.");
        b.add(Block.para(start + nature));
        // At work.
        if (!l.trade().isEmpty()) {
            b.add(Block.heading("At work"));
            String work = n + " " + (l.living() ? "is" : "was") + " " + Quill.a(l.trade()) + (l.level() > 0 ? ", level " + l.level() : "") + ".";
            if (!l.otherTrades().isEmpty()) work += " Before that, or besides: " + l.otherTrades() + ".";
            if (!l.deeds().isEmpty()) work += " " + d.pick("The town's books credit " + n + " with ", "All told, ") + l.deeds() + ".";
            b.add(Block.para(work));
            if (!l.knacks().isEmpty()) b.add(Block.para("Of the knacks a working life brings, " + n + " chose " + list(l.knacks()) + "."));
            for (String f : l.finds()) b.add(Block.para(stop(cap(f))));
        }
        // Family and friends.
        if (!l.partner().isEmpty() || !l.children().isEmpty() || !l.friends().isEmpty()) {
            b.add(Block.heading(d.pick("Family and friends", "The people", "Home")));
            List<String> s = new ArrayList<>();
            if (!l.partner().isEmpty()) s.add(n + "'s partner " + (l.living() ? "is" : "was") + " " + l.partner() + ".");
            if (!l.children().isEmpty()) s.add((l.children().size() == 1 ? "There is one child, " : "There are " + number(l.children().size())
                + " children: ") + list(l.children()) + ".");
            if (!l.friends().isEmpty()) s.add("Ask " + list(l.friends().subList(0, Math.min(3, l.friends().size()))) + " about " + n
                + " and you'll be there all evening.");
            b.add(Block.para(String.join(" ", s)));
        }
        // In its own words: its memories.
        if (!l.memories().isEmpty()) {
            b.add(Block.heading(d.pick("In " + n + "'s own words", "What " + n + " remembers", "Remembered")));
            b.add(Block.para(d.pick("I asked " + n + " what stayed with them. This is what " + n + " told me.",
                n + " kept the days that mattered. Here are some of them.")));
            int k = 0;
            for (Memory m : l.memories()) {
                if (k++ >= 8) break;
                b.add(Block.item("Day " + m.day() + ": \"" + stop(cap(m.text())) + "\""));
            }
        }
        // Hopes and pleasures.
        List<String> heart = new ArrayList<>();
        if (!l.hobby().isEmpty()) heart.add(n + " " + (l.living() ? "loves" : "loved") + " " + l.hobby() + ".");
        if (!l.quirk().isEmpty()) heart.add(cap(n) + " " + l.quirk() + (l.living() ? "" : ", always") + ".");
        if (!l.hope().isEmpty()) {
            // The hope in its own words ("to be the best at my trade in the village"), quoted, as it was said.
            String hope = "\"" + cap(l.hope()) + ".\"";
            heart.add(l.hopeMet() ? "When I asked " + n + " what they hoped for, the answer was: " + hope + " " + d.pick("It came true.",
                n + " lived to see it come true.") : "Ask " + n + " what they hope for and you'll hear: " + hope
                + (l.living() ? " There's time yet." : " It didn't come to pass, but the hoping was worth it."));
        }
        if (!heart.isEmpty()) {
            b.add(Block.heading(d.pick("What " + n + " loves", "The heart of it", "Hopes")));
            b.add(Block.para(String.join(" ", heart)));
        }
        // The town remembers.
        if (!l.mentions().isEmpty()) {
            b.add(Block.heading("What the chronicle says"));
            for (Event e : l.mentions()) b.add(Block.item("Day " + e.day() + ": " + told(e.text())));
        }
        // The end, or not yet.
        b.add(Block.heading(l.living() ? "Still writing it" : "The end of the story"));
        if (l.living()) {
            b.add(Block.para(n + " is " + number(l.age()) + " now. " + d.pick("The story isn't over, and I hope to add to this book for years yet.",
                "There's more to come, I'm sure of it.", "Ask " + n + " yourself: the best parts are never written down.")));
        } else {
            String cause = l.cause().isEmpty() ? "" : ", " + l.cause();
            b.add(Block.para(n + " died on day " + l.died() + cause + ", aged " + number(l.age()) + ". "
                + d.pick("The bell was rung, and " + town + " was quiet for a day.", "We laid " + n + " to rest, and the town went on, as towns must.",
                "I think of " + n + " most days.")));
        }
        String last = switch (v.tone()) {
            case "grumpy" -> "There. That's " + n + ". Don't tell them I said all this.";
            case "cheerful" -> "And I wouldn't change a day of it!";
            case "shy" -> "I hope I've done " + n + " justice.";
            case "sociable" -> "Buy " + n + " a drink and you'll hear the rest.";
            default -> "";
        };
        if (!last.isEmpty()) b.add(Block.para(last));
        b.add(Block.para("- " + v.name() + ", day " + day + "."));
        String blurb = (l.living() ? "the life so far of " : "the life of ") + n + ", " + l.notable();
        return new Quill.Book("LIFE", title, v.name(), Quill.pages(b), blurb, "life:" + n, List.of());
    }

    // ------------------------------------------------------------------ how-to books

    /**
     * A how-to book to write: its topic (BEES, BUILD, BREAD, FISH, FLOWERS, STARS, WHITTLING, CARDS, WALKS, MUSIC, READING,
     * HORSES, IRON, TREES, SHEEP, WATCH), the town, the author's trade and level at it, a count out of its experience and
     * what it counts ("flowers planted"), and the places it names (the buildings for a walk, the buildings it built).
     */
    public record HowTo(String topic, String town, String trade, int level, long count, String counted, List<String> places) {}

    private record Topic(String[] titles, String intro, String[] need, String[] steps, String[] wrong, String close) {}

    private static final Map<String, Topic> TOPICS = new LinkedHashMap<>();

    private static void topic(String key, Topic t) {
        TOPICS.put(key, t);
    }

    static {
        topic("BEES", new Topic(new String[]{ "How to Keep Bees", "Bees, and How to Keep Them", "The Beekeeper's Year" },
            "bees are the gentlest beasts in the world, if you're gentle with them",
            new String[]{ "a hive (or three honeycomb and six planks to make one)", "shears, for the comb", "glass bottles, for the honey",
                "a campfire, for the smoke", "flowers, and plenty of them" },
            new String[]{ "Set the hive on open ground, with room round it and the morning sun on its face.",
                "Plant flowers all round it. Poppies, dandelions, cornflowers: whatever the meadow will spare. No flowers, no honey.",
                "Light a campfire a block or two under the hive. The smoke keeps the bees calm when you come for the honey.",
                "Wait. When the hive is full you'll see the honey dripping from it.",
                "Take the comb with shears, or the honey with a bottle. Never both at once; the bees have to eat too.",
                "When the bees are many, feed two of them a flower each and there'll be a third.",
                "Make a new hive out of comb and planks when the old ones fill. Four is plenty for one keeper." },
            new String[]{ "If the bees come at you, you've no smoke under the hive. Back away and light the fire first.",
                "Never break a hive with the bees in it. They'll never forgive you, and neither will I.",
                "If there's no honey, there are no flowers. Plant more." },
            "Mind the stings, love the bees, and the honey will come."));
        topic("BUILD", new Topic(new String[]{ "Raising a House", "Raising a Barn", "How to Build a House" },
            "a house is only planks and stone until somebody sleeps in it",
            new String[]{ "planks, and plenty", "logs for the posts and beams", "cobblestone for the footing", "glass for the windows",
                "a door, and a bed to go behind it" },
            new String[]{ "Level the lot first. A house built on a slope is a house that leans.",
                "Lay the footing of stone, a block below the ground, so the walls have something to stand on.",
                "Raise the corner posts of logs, then the walls between them, a course at a time.",
                "Leave the windows where the light falls, and the door facing the street.",
                "Lay the beams across, then the ceiling, then the roof, from the eaves up to the ridge.",
                "Hang the door. Put the bed in. Light the lamp. Now it's a house." },
            new String[]{ "If you run short of planks halfway, stop and fetch more. Never patch a wall with dirt.",
                "If the roof leaks, the stairs are on the wrong way. Turn them.",
                "If nobody wants to live in it, it's too dark. More windows." },
            "Build it to last, and build it for the folk who'll live in it."));
        topic("BREAD", new Topic(new String[]{ "How to Bake Bread", "Bread for Beginners", "The Daily Loaf" },
            "bread is the first thing a town needs and the last thing it thinks about",
            new String[]{ "wheat: three ears to a loaf", "a crafting table", "hungry folk" },
            new String[]{ "Bring in the wheat when it's golden, not before.", "Put three wheat in a row at the bench.",
                "Take out your loaf.", "Make enough for everybody: three meals a head a day, if you want them working.",
                "Put the loaves in the stores where folk can find them." },
            new String[]{ "If there's no wheat, there's no bread. Go and help the farmers.",
                "If the loaves go missing from the stores, that's what they're for." },
            "That's all there is to it. The trick is doing it every day."));
        topic("FISH", new Topic(new String[]{ "How to Catch a Fish", "Fishing for Beginners", "A Line in the Water" },
            "a fish is the easiest supper in the world to catch, if you've the patience",
            new String[]{ "a rod: three sticks and two string", "open water", "patience", "a wet day, if you can get one" },
            new String[]{ "Find open water: away from the bank, out from under the trees, and deep if you can.",
                "Cast your line and watch the bobber. Don't stare at it; it knows.",
                "When the bobber dips, pull. Not before.", "Take it home: cod for the stores, salmon for a feast.",
                "Fish in the rain. They bite better wet, though nobody can tell me why." },
            new String[]{ "If nothing bites, move. The fish aren't coming to you.", "If you pull up an old boot, keep it. Somebody will want it.",
                "Never fish alone in a storm." },
            "Sit still, be patient, and the water will feed you."));
        topic("FLOWERS", new Topic(new String[]{ "How to Grow Flowers", "Flowers by the Door", "A Garden for Every House" },
            "a flower by the door does more for a house than a new roof",
            new String[]{ "a flower or two to start with (the meadow has plenty)", "bone meal", "a patch of grass by your door" },
            new String[]{ "Choose a patch of grass where you'll see it every day: by the door is best.",
                "Bring a flower in from the meadow. Poppies are easy, and so are dandelions.",
                "Put a little bone meal on the grass round it, and new flowers come up of their own accord.",
                "Keep the paths clear so nobody treads on them.", "Give some away. A neighbour's garden is the best thing for yours." },
            new String[]{ "If they won't grow, there's not enough light. Flowers want the sky.", "If a rabbit eats them, plant more. Rabbits win sometimes." },
            "A town with flowers in it is a town folk want to live in."));
        topic("STARS", new Topic(new String[]{ "The Night Sky over {town}", "A Guide to the Stars", "What the Stars Say" },
            "the sky over the town is the same sky the whole world sees, and still it's ours",
            new String[]{ "a clear night", "a dark spot away from the lamps", "a spyglass, if the smith can spare the copper" },
            new String[]{ "Wait for a clear night. Clouds are no good to anybody.", "Walk out past the last lamp and let your eyes get used to the dark.",
                "Find the Great Creeper first: four bright stars in a square, and a tail of three.",
                "From his tail, follow the line to the Anvil, low over the hills.",
                "Look for the moon. It changes every night, a little at a time, and comes round again.",
                "Make a wish on the first shooting star. Don't tell anybody what it was." },
            new String[]{ "If you see something moving in the dark that isn't a star, go home.", "If you fall asleep out there, the watch will wake you. Probably." },
            "Look up now and then. It puts the day in its place."));
        topic("WHITTLING", new Topic(new String[]{ "Whittling for Beginners", "A Knife and a Stick", "The Art of Whittling" },
            "there's a bowl in every plank, if you're patient enough to find it",
            new String[]{ "a plank or a good stick", "a sharp edge (an axe will do)", "an evening with nothing else to do" },
            new String[]{ "Sit somewhere comfortable. Whittling is slow.", "Hold the wood firm and cut away from yourself, always.",
                "Take a little at a time. You can't put it back.", "A bowl is the best thing to start with: hollow the middle out slowly.",
                "When it's done, give it away. A bowl is for sharing soup." },
            new String[]{ "If you cut yourself, you weren't cutting away from yourself.", "If it splits, it was a bad plank. Not your fault. Probably." },
            "Patience and a sharp edge: that's whittling, and most other things."));
        topic("CARDS", new Topic(new String[]{ "How to Win at Cards", "Cards, and How to Lose Them", "A Hand of Cards" },
            "cards are how folk in {town} pass an evening and lose an argument",
            new String[]{ "a deck (paper and ink will make one)", "a friend", "a table", "a good poker face" },
            new String[]{ "Deal five each, face down.", "Look at your cards without moving your face. This is the hardest part.",
                "Bet a button, a bead or a promise. Never your bread.", "Lay down your best, and say nothing.",
                "Win graciously. Lose more graciously still: you'll do it more often." },
            new String[]{ "If you're losing, your face is giving you away.", "If you're always winning, they've stopped playing with you." },
            "It's not the winning. It's the company. (It's the winning.)"));
        topic("WALKS", new Topic(new String[]{ "Walks Round {town}", "A Walker's Guide to {town}", "On Foot in {town}" },
            "the best way to know a town is to walk it, slowly, at dusk",
            new String[]{ "good boots", "an evening", "somebody to talk to, or nobody" },
            new String[]{ "Start at the well on the square, where every walk in {town} begins." },
            new String[]{ "If you get lost, look for the bell tower, or the smoke of the smeltery.", "If it's dark, carry a light, and walk home by the lamps." },
            "Walk it often. A town changes when you're not looking."));
        topic("MUSIC", new Topic(new String[]{ "Tunes for the Well", "How to Play a Tune", "Music of an Evening" },
            "a tune at the well of an evening brings the town together like nothing else",
            new String[]{ "a horn or a flute", "a place to stand (the well is best)", "the courage to begin" },
            new String[]{ "Stand by the well as the work stops for the day.", "Start slow and soft. Folk come to a quiet tune quicker than a loud one.",
                "Play the old songs first. Everybody knows them.", "When a crowd comes, play something they can dance to.", "Stop before they're tired of you." },
            new String[]{ "If nobody comes, play louder. Then softer. One of them works.", "If they throw things, it's not a good song." },
            "Play for the town, and the town will sing back."));
        topic("READING", new Topic(new String[]{ "How to Read a Book", "On Reading", "A Book a Week" },
            "a book is the only way to hear a voice that's gone quiet",
            new String[]{ "a book (the library lends them)", "a chair", "a lamp", "a quiet hour" },
            new String[]{ "Borrow a book from the library. Bring it back in time.", "Find a chair by a lamp, out of the way of the door.",
                "Start at the beginning. You'd be surprised how many don't.", "Read slowly. The book isn't going anywhere.",
                "When it's done, tell somebody what you thought. That's half the pleasure." },
            new String[]{ "If you fall asleep, it was a long day, not a bad book. Probably.", "If you can't put it down, that's how it should be." },
            "Read everything. The trade books first, then the poems."));
        topic("HORSES", new Topic(new String[]{ "How to Tame a Horse", "Horses, and How to Keep Them", "Into the Saddle" },
            "a horse is the best friend a courier ever had",
            new String[]{ "a wild horse", "patience", "a saddle", "hay and apples" },
            new String[]{ "Walk up slowly with an apple held out.", "Climb on. It will throw you off. Climb on again.",
                "Keep at it until it stops bucking and the hearts show. Now it trusts you.", "Saddle it, and ride it home gently.",
                "Feed it, stable it, and brush it down. A horse remembers kindness." },
            new String[]{ "If it keeps throwing you, it's not ready. Neither are you, perhaps.", "If it wanders off, you forgot to shut the gate." },
            "Be patient, be kind, and keep the stable gate shut."));
        topic("IRON", new Topic(new String[]{ "Finding Iron", "Where the Iron Is", "Iron, and How to Dig It" },
            "the whole Iron Age is just the iron, and the iron is just a matter of knowing where to dig",
            new String[]{ "a stone pick at the least", "torches", "a sack of bread", "a way back up" },
            new String[]{ "Go to the mine. Never dig under the town.", "Cut stairs down into the middle depths: iron likes the middle.",
                "Look for the rust-coloured flecks in the stone. That's raw iron.", "Dig the whole vein; there's always more behind the first.",
                "Light every turning as you go, and come back up the stairs you cut." },
            new String[]{ "If you hear lava, stop digging that way.", "If your pick breaks, you dug too long. Bring a spare." },
            "Iron is patience in stone. Dig steady."));
        topic("TREES", new Topic(new String[]{ "Felling a Tree", "The Woodcutter's Way", "Timber!" },
            "a tree gives everything and asks only to be replaced",
            new String[]{ "an axe", "saplings to plant back", "a strong back" },
            new String[]{ "Pick a tree away from the houses.", "Fell it from the bottom; take the whole trunk, every log.",
                "Clear the leaves and pick up the saplings that fall.", "Plant a sapling where the tree stood, before you walk away.",
                "Carry the logs to the stores. The builders are always waiting." },
            new String[]{ "If the top of the tree is left hanging, climb up and finish it.", "If the woods are thinning, you're not planting enough." },
            "For every tree you take, put one back."));
        topic("SHEEP", new Topic(new String[]{ "Keeping Sheep", "Wool, and Where It Comes From", "The Shepherd's Guide" },
            "a sheep gives wool forever if you only shear it",
            new String[]{ "two sheep", "wheat", "shears", "a pen with a gate" },
            new String[]{ "Bring two sheep into the pen with a handful of wheat.", "Feed them a wheat each and there'll be a lamb.",
                "Shear them when the wool is thick. It grows back.", "Shut the gate. Every time.", "Never take the last pair." },
            new String[]{ "If the lambs don't come, the sheep are too crowded.", "If a sheep goes missing, somebody left the gate open. Was it you?" },
            "Look after the flock and the flock looks after the town."));
        topic("WATCH", new Topic(new String[]{ "Keeping Watch", "A Night on the Wall", "How to Keep a Town Safe" },
            "the watch is the reason the rest of the town sleeps",
            new String[]{ "a sword or a bow", "armour from the smith", "a torch", "sharp eyes and a steady heart" },
            new String[]{ "Light the wall and the gates before dusk.", "Go to your post when the bell rings, and stay at it.",
                "Watch the dark, not the lamps.", "Never chase anything out of the gate.", "At dawn, walk the edges and see off the stragglers." },
            new String[]{ "If you're afraid, good. The brave ones get careless.", "If you're alone, ring the bell. That's what it's for." },
            "Keep the wall, and the town will keep you."));
    }

    /** What doing a topic is called, for a farewell ("Happy beekeeping"). */
    private static String workOf(String topic) {
        return switch (topic) {
            case "BEES" -> "beekeeping";
            case "BUILD" -> "building";
            case "BREAD" -> "baking";
            case "FISH" -> "fishing";
            case "FLOWERS" -> "gardening";
            case "STARS" -> "stargazing";
            case "WHITTLING" -> "whittling";
            case "CARDS" -> "playing";
            case "WALKS" -> "walking";
            case "MUSIC" -> "playing";
            case "READING" -> "reading";
            case "HORSES" -> "riding";
            case "IRON" -> "digging";
            case "TREES" -> "felling";
            case "SHEEP" -> "shepherding";
            case "WATCH" -> "watching";
            default -> "work";
        };
    }

    /** The topics there are. */
    public static List<String> topics() {
        return new ArrayList<>(TOPICS.keySet());
    }

    /** A how-to book, written. */
    public static Quill.Book howTo(HowTo h, Voice v, long day) {
        Topic t = TOPICS.getOrDefault(h.topic(), TOPICS.get("READING"));
        Dice d = Dice.of(h.town(), v.name(), h.topic(), day);
        String title = Quill.fit(d.pick(t.titles()).replace("{town}", h.town()), t.titles()[0].replace("{town}", h.town()), t.titles()[0].replace("{town}", "the Town"));
        List<Block> b = new ArrayList<>();
        b.add(Block.space());
        b.add(Block.title("§l" + title + "§r"));
        b.add(Block.space());
        b.add(Block.title("from experience"));
        b.add(Block.space());
        b.add(Block.title("by " + v.name()));
        b.add(Block.title("day " + day));
        b.add(Block.page());
        b.add(Block.heading(d.pick("Why listen to me", "First of all", "A word first")));
        String exp = h.count() > 0 ? " My tally so far: " + number(h.count()) + " " + h.counted() + ", so I've made most of the mistakes already." : "";
        String trade = h.trade().isEmpty() ? "" : " I'm " + Quill.a(h.trade()) + (h.level() > 0 ? ", level " + h.level() : "") + ", by trade.";
        b.add(Block.para(cap(t.intro().replace("{town}", h.town())) + "." + trade + exp + switch (v.tone()) {
            case "grumpy" -> " Do it my way and you'll not go far wrong.";
            case "cheerful" -> " It's the best thing in the world, and I want everybody to try it!";
            case "shy" -> " I hope this is some use. It's how I do it, anyway.";
            case "curious" -> " I'm still learning, and I've written down everything I've found out.";
            default -> "";
        }));
        b.add(Block.heading("You will need"));
        for (String n : t.need()) b.add(Block.item("- " + n));
        b.add(Block.heading(d.pick("What to do", "The steps", "How it's done")));
        List<String> steps = new ArrayList<>(List.of(t.steps()));
        if (h.topic().equals("WALKS")) {
            // A walk round the town's own buildings, as they stand.
            String[] on = { "From there, walk on to ", "Then to ", "Next, ", "On, then, to ", "After that, " };
            int k = d.roll(on.length);
            for (String p : h.places()) steps.add(on[k++ % on.length] + p + ".");
            steps.add("And back to the well, the way you came, as the lamps are lit.");
        }
        if (h.topic().equals("BUILD") && !h.places().isEmpty()) {
            steps.add("That's how we built " + list(h.places().subList(0, Math.min(4, h.places().size()))) + ", and they stand still.");
        }
        int i = 1;
        for (String s : steps) b.add(Block.item(i++ + ". " + s.replace("{town}", h.town())));
        b.add(Block.heading(d.pick("If it goes wrong", "Trouble", "When things go wrong")));
        for (String w : t.wrong()) b.add(Block.para(w));
        b.add(Block.space());
        b.add(Block.para(t.close() + " " + v.farewell(d, workOf(h.topic()))));
        b.add(Block.para("- " + v.name() + ", day " + day + "."));
        // The title in the blurb's own case, all but the town's name, which keeps its capital.
        String blurb = "a how-to book: " + title.toLowerCase(Locale.ROOT).replace(h.town().toLowerCase(Locale.ROOT), h.town());
        return new Quill.Book("HOWTO", title, v.name(), Quill.pages(b), blurb, "howto:" + h.topic(), List.of());
    }

    // ------------------------------------------------------------------ the children's stories

    /**
     * A storybook to write: the town, its children (their names), the pet and whose it is (and what kind), the
     * grown folk a story might meet (a guard, a farmer, a miner, a fisher, the teacher, the elder), the town's
     * places ("the well", "the school", "the bell tower"...), and what the first child leans to at school.
     */
    public record Story(String town, List<String> children, String pet, String petKind, String petKeeper, String guard,
                        String farmer, String miner, String fisher, String teacher, String elder, List<String> places, String leaning) {}

    /** A storybook, written by the teacher. */
    public static Quill.Book story(Story s, Voice v, long day) {
        Dice d = Dice.of(s.town(), v.name(), s.children(), day);
        String c = s.children().isEmpty() ? "Little Pip" : s.children().get(0);
        String c2 = s.children().size() > 1 ? s.children().get(1) : "";
        List<String> plots = new ArrayList<>();
        if (!s.pet().isEmpty()) plots.add("PET");
        if (!s.guard().isEmpty()) plots.add("BELL");
        if (!s.farmer().isEmpty()) plots.add("SEED");
        if (!s.leaning().isEmpty() || !s.farmer().isEmpty() || !s.miner().isEmpty()) plots.add("TRADES");
        plots.add("BRICK");
        String plot = d.pick(plots);
        List<String> pages = new ArrayList<>();
        String title;
        switch (plot) {
            case "PET" -> {
                String kind = s.petKind().isEmpty() ? "cat" : s.petKind();
                title = Quill.fit(c + " and the Lost " + cap(kind), "The Lost " + cap(kind));
                String p = s.pet();
                pages.add("In the town of " + s.town() + " there lived " + Quill.a(kind) + " called " + p + ".");
                pages.add(p + " belonged to " + (s.petKeeper().isEmpty() ? "everybody, and to nobody" : s.petKeeper()) + ". But " + p + " loved " + c + " best of all.");
                pages.add("One morning, " + c + " came down for breakfast, and " + p + " was not there.");
                pages.add("\"" + p + "!\" called " + c + ". \"" + p + "! Where are you?\"\n\nNobody answered.");
                List<String> looks = d.some(s.places().isEmpty() ? List.of("the well", "the square", "the fields") : s.places(), 3);
                for (String place : looks) {
                    pages.add(c + " looked by " + place + ".\n\n" + d.pick("No " + kind + ".", "Not a whisker.", "Nothing but a beetle.", "Only a puddle."));
                }
                if (!s.farmer().isEmpty()) pages.add(c + " asked " + s.farmer() + " the farmer.\n\n\"Not in my wheat, I hope!\" said " + s.farmer() + ". \"Try somewhere warm.\"");
                pages.add("Somewhere warm, thought " + c + ". Where is the warmest place in all of " + s.town() + "?");
                pages.add("And there, in the library, in a patch of sun on the reading table, curled up on a book of poems, was " + p + ".\n\nFast asleep.");
                pages.add(c + " picked " + p + " up and carried " + p + " home. " + p + " purred all the way.");
                pages.add("And from that day on, " + p + " slept on " + c + "'s bed, which is where " + p + " had wanted to sleep all along.\n\n§lThe End§r");
            }
            case "BELL" -> {
                title = Quill.fit("The Night the Bell Rang", "The Bell");
                pages.add("Every night in " + s.town() + ", when the lamps are lit, the town goes to sleep.");
                pages.add("But one night, " + c + " woke up. Something was ringing.\n\nClang! Clang! Clang!");
                pages.add("It was the bell. " + c + " pulled the blanket up very high.");
                pages.add("\"What is it?\" whispered " + c + ".\n\n" + (c2.isEmpty() ? "The dark said nothing." : c2 + " was asleep, and said nothing at all."));
                pages.add(c + " crept to the window. Up on the wall, by a lantern, stood " + s.guard() + " of the watch, with a bow and a steady face.");
                pages.add(s.guard() + " looked down and saw " + c + " at the window, and waved.\n\n\"Back to bed!\" called " + s.guard() + ". \"The wall is strong, and I am awake.\"");
                pages.add("So " + c + " went back to bed. And outside, all night long, " + s.guard() + " kept the wall.");
                pages.add("In the morning the sun came up over " + s.town() + ", and everybody was safe, and the bell was quiet.");
                pages.add("\"When I grow up,\" said " + c + " at breakfast, \"I'm going to keep the wall too.\"\n\n§lThe End§r");
            }
            case "SEED" -> {
                title = Quill.fit("The Seed That Wouldn't Grow", "The Little Seed");
                pages.add(c + " had a seed. It was very small, and very brown, and " + c + " loved it.");
                pages.add(c + " planted it by the door and waited.\n\nAnd waited.\n\nNothing happened.");
                pages.add("\"Why won't it grow?\" " + c + " asked " + s.farmer() + ", who was the best farmer in " + s.town() + ".");
                pages.add(s.farmer() + " knelt down and looked at the little patch of earth.\n\n\"It wants three things,\" said " + s.farmer() + ". \"Water. Light. And time.\"");
                pages.add("So " + c + " brought water from the well in two cupped hands, and spilled most of it, and brought more.");
                pages.add("And " + c + " put a torch beside it, so it would have light even at night.");
                pages.add("And time? Time was the hardest. " + c + " was not very good at time.");
                pages.add("But one morning in spring, there it was: one green shoot, as small as a whisper.");
                pages.add("\"You grew!\" said " + c + ".\n\nAnd it went on growing, the way good things do, a little every day.\n\n§lThe End§r");
            }
            case "TRADES" -> {
                title = Quill.fit(c + " Wants to Be Everything", "What Shall I Be?");
                pages.add(c + " lived in " + s.town() + ", and did not know what to be when " + c + " grew up.");
                if (!s.farmer().isEmpty()) pages.add(c + " went to see " + s.farmer() + " in the fields.\n\n\"Be a farmer,\" said " + s.farmer() + ". \"Everybody needs bread.\"");
                if (!s.miner().isEmpty()) pages.add(c + " went to see " + s.miner() + " at the mine.\n\n\"Be a miner,\" said " + s.miner() + ". \"Everybody needs iron.\"");
                if (!s.fisher().isEmpty()) pages.add(c + " went to see " + s.fisher() + " by the water.\n\n\"Be a fisher,\" said " + s.fisher() + ". \"Everybody needs a quiet morning.\"");
                if (!s.guard().isEmpty()) pages.add(c + " went to see " + s.guard() + " on the wall.\n\n\"Be a guard,\" said " + s.guard() + ". \"Everybody needs to sleep safe.\"");
                pages.add("\"But I want to be ALL of them,\" said " + c + ".");
                pages.add(c + " asked " + (s.teacher().isEmpty() ? "the teacher" : s.teacher() + " the teacher") + ".\n\n\"Then learn a little of everything,\" said "
                    + (s.teacher().isEmpty() ? "the teacher" : s.teacher()) + ", \"and a lot of one thing. And see which one makes you happiest.\"");
                String like = s.leaning().equals("farmer") && !s.farmer().isEmpty() ? ", like " + s.farmer()
                    : s.leaning().equals("miner") && !s.miner().isEmpty() ? ", like " + s.miner()
                    : s.leaning().equals("guard") && !s.guard().isEmpty() ? ", like " + s.guard()
                    : s.leaning().equals("fisher") && !s.fisher().isEmpty() ? ", like " + s.fisher() : "";
                pages.add(s.leaning().isEmpty() ? "So " + c + " learned a little of everything. And one day, " + c + " knew."
                    : "So " + c + " learned a little of everything. And one day, " + c + " knew exactly what to be: " + Quill.a(s.leaning()) + like + ".");
                pages.add("Because a town needs everybody. And everybody needs a town.\n\n§lThe End§r");
            }
            default -> {
                String building = s.places().isEmpty() ? "the meeting hall" : d.pick(s.places());
                title = Quill.fit("The Littlest Builder", "The Smallest Stone");
                pages.add("When the builders of " + s.town() + " raised " + building + ", everybody helped.");
                pages.add("Everybody except " + c + ", who was too small to carry a plank.\n\n\"Go and play,\" said the builders. Kindly.");
                pages.add("But " + c + " did not want to play. " + c + " wanted to build.");
                pages.add("So " + c + " found one small stone, the smallest in the whole pile, and carried it with both hands.");
                pages.add("It was heavy. It took all morning. " + c + " had to stop four times.");
                pages.add("At last " + c + " gave it to the builders, and they laid it right at the bottom, in the corner.");
                pages.add("\"Every stone holds up the ones above it,\" said the builders. \"Even the smallest.\"");
                pages.add("And " + building + " stands there still. And at the bottom, in the corner, is " + c + "'s stone.\n\n§lThe End§r");
            }
        }
        List<Block> b = new ArrayList<>();
        b.add(Block.space());
        b.add(Block.space());
        b.add(Block.title("§l" + title + "§r"));
        b.add(Block.space());
        b.add(Block.title("a story for the children of " + s.town()));
        b.add(Block.space());
        b.add(Block.title("by " + v.name()));
        b.add(Block.page());
        for (String p : pages) {
            for (String para : p.split("\n\n")) {
                b.add(Block.para(para));
                b.add(Block.space());
            }
            b.add(Block.page());
        }
        return new Quill.Book("STORY", title, v.name(), Quill.pages(b), "a storybook for the children, starring " + c,
            "story:" + plot + ":" + c, List.of());
    }
}
