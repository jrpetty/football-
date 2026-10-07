package com.jrpetty.mcassistant.village;

import com.jrpetty.mcassistant.village.Quill.Block;
import com.jrpetty.mcassistant.village.Quill.Dice;
import com.jrpetty.mcassistant.village.Quill.Voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The town's poems: a wedding, a death, the harvest, the water, a beloved pet, a birth, the town itself, two
 * friends. [library]
 *
 * <p>Every poem is in the ballad's measure, four lines to a verse, the second and fourth rhyming, and what
 * rhymes is written into the verse, never a name: a name stands inside a line, where it cannot spoil the
 * rhyme, however it is spelled. Each kind of poem has its openings, its middles and its endings (and an
 * elegy its verse for the trade the dead worked at and for how they died), and the poet's dice choose among
 * them, so no two poems on the same thing come out alike. What fills them is real: the couple's names and
 * trades, the season, the square they were wed on, the farmer's count of wheat, the cat's name and whose bed
 * it sleeps on. A verse that wants something the town cannot say (a trade, a motto) is passed over.
 *
 * <p>The poet's nature shows round the poem: a shy poet says sorry for it first, a grumpy one says it was
 * asked for, a cheerful one cannot wait for you to read it.
 */
public final class Verse {

    private Verse() {}

    /**
     * What a poem is about: its kind (WEDDING, ELEGY, HARVEST, SEA, PET, BIRTH, TOWN, FRIEND), the town, and
     * the words it is filled with, by name: A and B (the couple, the dead, the farmer, the pet and its keeper,
     * the child), tradeA, tradeB, season, place, n and nwords (a count), crop, kind (cat, dog), kin, parents,
     * age, motto, b1 and b2 (buildings), founders, cause, trade (the trade's key, for an elegy), day.
     */
    public record Subject(String kind, String town, Map<String, String> words) {
        public String get(String k) {
            return words.getOrDefault(k, "");
        }
    }

    /** A verse: where it goes ('O' opening, 'M' middle, 'T' the trade's, 'X' the cause's, 'K' the family's, 'C' close),
     *  what it is for (a trade's key, a cause, a kind of animal; "" for any), and its four lines. */
    private record Stanza(char slot, String tag, String[] lines) {}

    private static final Map<String, List<Stanza>> BANK = new HashMap<>();
    private static final Map<String, String[]> TITLES = new HashMap<>();

    private static void v(String kind, char slot, String tag, String... lines) {
        BANK.computeIfAbsent(kind, k -> new ArrayList<>()).add(new Stanza(slot, tag, lines));
    }

    static {
        // ---------------------------------------------------------------- a wedding
        TITLES.put("WEDDING", new String[]{ "For {A} and {B}", "A Wedding in {town}", "The Wedding on {Place}", "{A} and {B}, Wed" });
        v("WEDDING", 'O', "", "The bell rang out across {town},", "the {season} sky was clear,",
            "and all of us put down our tools", "and came to see and cheer.");
        v("WEDDING", 'O', "", "We swept {place} before the dawn", "and hung the bunting high,",
            "for {A} and {B} were to be wed", "beneath the open sky.");
        v("WEDDING", 'O', "", "There was news in {town} that morning,", "there was laughter at the well:",
            "{A} and {B} were to be wed,", "and everyone must tell.");
        v("WEDDING", 'M', "trades", "{A} the {tradeA}, {B} the {tradeB},", "stood up before us there;",
            "the whole of {town} came to see,", "and half of it to stare.");
        v("WEDDING", 'M', "", "Who'd have thought, not long ago,", "when {A} first said hello,",
            "that {B} would blush and look away,", "and then say yes, not no?");
        v("WEDDING", 'M', "", "They promised by the old town well", "to keep each other warm,",
            "to share the bread and share the work", "and share the roof in storm.");
        v("WEDDING", 'M', "", "The tables groaned with bread and pie,", "the fiddles played till late,",
            "and {A} danced {B} round {place}", "and round and out the gate.");
        v("WEDDING", 'M', "", "The children threw the petals high", "and chased them round the square;",
            "the old folk wept, the young folk cheered,", "and love was in the air.");
        v("WEDDING", 'C', "", "So raise a cup to {A} and {B}", "and to the years ahead:",
            "may every morning find them glad", "and every night well fed.");
        v("WEDDING", 'C', "", "May {town} be kind to both of them,", "its hearth be warm and bright,",
            "its winters short, its harvests long,", "its every quarrel light.");
        v("WEDDING", 'C', "", "And when they're old and slow and grey", "and sitting by the door,",
            "may {A} still look at {B} that way,", "and love them all the more.");

        // ---------------------------------------------------------------- a death
        TITLES.put("ELEGY", new String[]{ "For {A}", "Lines for {A}", "The Bell for {A}", "{A}, Remembered" });
        v("ELEGY", 'O', "years", "They rang the bell for {A} today,", "slow, as the old bells do;",
            "for {nwords} years {A} walked these streets,", "and every face {A} knew.");
        v("ELEGY", 'O', "", "The door is shut, the fire is out,", "the chair beside it bare;",
            "and {A}, who sat there every night,", "will not be sitting there.");
        v("ELEGY", 'O', "", "It was a {season} morning,", "and nothing seemed amiss,",
            "and then the word went round the town,", "and all it said was this:");
        v("ELEGY", 'T', "FARM", "{A}, who turned the {town} soil", "and brought the harvest in,",
            "who knew the rain a day before", "by the ache beneath the skin.");
        v("ELEGY", 'T', "MINE", "{A}, who went down in the dark", "where none of us would go,",
            "and brought up iron for the town", "from the hollow rock below.");
        v("ELEGY", 'T', "WOOD", "{A}, who walked the evening woods", "with an axe across the back,",
            "and planted where the oaks had stood", "along the felling track.");
        v("ELEGY", 'T', "FISH", "{A}, who read the water's face", "the way we read a book,",
            "and knew which pool the salmon liked", "and which the cod forsook.");
        v("ELEGY", 'T', "GUARD", "{A}, who kept the wall at night", "when all the town was sleeping,",
            "and never once was heard to say", "it wasn't worth the keeping.");
        v("ELEGY", 'T', "SMITH", "{A}, who stood beside the fire", "from first light to the last,",
            "and hammered out the town's own tools", "and made them good and fast.");
        v("ELEGY", 'T', "SMELT", "{A}, who stood beside the fire", "from first light to the last,",
            "and fed the furnaces all day", "and fed them good and fast.");
        v("ELEGY", 'T', "COOK", "{A}, who fed us, every one,", "with bread and stew and pie,",
            "and kept a plate for latecomers", "and never asked them why.");
        v("ELEGY", 'T', "RANCH", "{A}, who knew each beast by name", "and brought them in at night,",
            "and shut the gate, and fed the herd,", "and saw that all was right.");
        v("ELEGY", 'T', "", "{A} the {tradeA}, kind and plain,", "who'd stop and pass the day;",
            "who knew the name of every child", "and always knew the way.");
        v("ELEGY", 'X', "old age", "{A} went to sleep one night", "and simply did not wake:",
            "the kind of going all of us", "would gladly choose to make.");
        v("ELEGY", 'X', "drowning", "The water kept {A} at the last;", "it does not give things back.",
            "We walk the bank a little slower now", "along the fishers' track.");
        v("ELEGY", 'X', "fall", "One step too far, one stone too loose:", "it takes no more than that;",
            "and now there is an empty place", "where {A} so often sat.");
        v("ELEGY", 'X', "fire", "The fire came, as fires come,", "too quick for any cry;",
            "and we who stood and watched it burn", "still cannot tell you why.");
        v("ELEGY", 'X', "fight", "{A} stood up when the bell rang out", "and did not run away;",
            "the night was long, the wall held fast,", "but {A} is gone today.");
        v("ELEGY", 'X', "cold", "The cold came down on {town}", "and stayed too long that year;",
            "and {A}, who'd seen so many through,", "did not see this one clear.");
        v("ELEGY", 'X', "", "We cannot say why it was {A};", "the world does what it will;",
            "but where {A} stood, a gap is left", "that none of us can fill.");
        v("ELEGY", 'K', "kin", "{kin} sits by the fire tonight", "and sets an extra plate,",
            "and when the evening's growing cold", "still listens at the gate.");
        v("ELEGY", 'K', "children", "The children ask where {A} has gone;", "we tell them what we know:",
            "that love is kept, and nothing loved", "is ever quite let go.");
        v("ELEGY", 'C', "", "So lay the flowers, say the words,", "and ring the bell once more:",
            "{A}, rest easy. {town} will keep", "the path up to your door.");
        v("ELEGY", 'C', "", "Sleep well, {A}. The work is done,", "the tools are hung away;",
            "and {town} will say your name again", "on every Founding Day.");

        // ---------------------------------------------------------------- the harvest
        TITLES.put("HARVEST", new String[]{ "Harvest Home", "{A}'s Field", "The Harvest of Day {day}", "A Song for the Harvest" });
        v("HARVEST", 'O', "", "The {crop} stood high in {town}'s fields,", "as gold as gold can be,",
            "and {A} went out at break of day", "and came home after tea.");
        v("HARVEST", 'O', "", "All {season} long the rain was kind,", "and all {season} long the sun,",
            "and every row that {A} had sown", "came ripe, and every one.");
        v("HARVEST", 'M', "count", "By dusk the count was {nwords}:", "the most we'd ever had;",
            "the storehouse groaned, the baker sang,", "and everyone was glad.");
        v("HARVEST", 'M', "", "The children rode the laden carts,", "the dogs ran at the wheels,",
            "and nobody in {town} that night", "went short of evening meals.");
        v("HARVEST", 'M', "", "And when the frost is on the pane", "and the snow is at the door,",
            "we'll eat the bread of {A}'s field", "and never ask for more.");
        v("HARVEST", 'M', "", "They cut it and they carried it", "and stacked it to the eaves;",
            "and {A} went home and slept like one", "who knows what work achieves.");
        v("HARVEST", 'C', "", "So fill the jugs and break the bread,", "and thank the rain and sun,",
            "and thank the hands that did the work", "when all the work was done.");
        v("HARVEST", 'C', "", "Here's to the seed and here's to the field,", "and here's to the {crop} that grew,",
            "and here's to {A}, who brought it in,", "and here's to all of you.");

        // ---------------------------------------------------------------- the water
        TITLES.put("SEA", new String[]{ "The Fisher's Song", "Out on the Water", "{A} at the Water", "The Water at {town}" });
        v("SEA", 'O', "", "Before the bell, before the bread,", "before the first cock crows,",
            "{A} is out upon the water", "where the cold wind blows.");
        v("SEA", 'O', "", "The water's grey at morning", "and silver in the noon,",
            "and black as ink by evening", "beneath the rising moon.");
        v("SEA", 'M', "", "The water gives and never says", "what it will give tomorrow:",
            "one day a net of silver fish,", "the next day only sorrow.");
        v("SEA", 'M', "", "{A} knows the water's every mood,", "its temper and its calm,",
            "and reads the ripples like a book,", "the wind upon a palm.");
        v("SEA", 'M', "", "There's salt upon the jetty boards", "and scales upon the stair,",
            "and {A}'s line is in the water", "and {A} is sitting there.");
        v("SEA", 'M', "count", "{nwords} fish, the town's books say,", "have come up from the deep;",
            "and every one was caught by hand", "while the rest of us were asleep.");
        v("SEA", 'C', "", "So here's to {A} and all who fish,", "who know the water's mind,",
            "who leave the warm hearth every dawn", "and bring back what they find.");
        v("SEA", 'C', "", "And when the last boat's in at night", "and the last line's wound away,",
            "the water hushes, and lies still,", "and waits for another day.");

        // ---------------------------------------------------------------- a pet
        TITLES.put("PET", new String[]{ "{A}", "Ode to {A}", "{A}, a {Kind}", "The {Kind} of {town}" });
        v("PET", 'O', "", "There is a {kind} in {town}", "who answers, now and then,",
            "to {A}, and who came in from the wild", "and won't go out again.");
        v("PET", 'O', "", "Of all the beasts in {town},", "the ones that walk and creep,",
            "there's one that {B} loves the best,", "and it is fast asleep.");
        v("PET", 'M', "cat", "{A} has a corner by the fire", "and a corner by the door",
            "and a corner on {B}'s bed", "and wants a corner more.");
        v("PET", 'M', "cat", "{A} brings {B} little gifts:", "a mouse, a bird, a shoe;",
            "and sits and waits for gratitude,", "as any cat would do.");
        v("PET", 'M', "dog", "{A} meets {B} at the gate", "each evening, rain or shine,",
            "and wags as if to say, 'You're late!", "But never mind. You're mine.'");
        v("PET", 'M', "dog", "{A} has walked the whole of {town}", "and sniffed at every stone,",
            "and knows each street and every door", "and every hidden bone.");
        v("PET", 'M', "", "When {B} is low and troubles come,", "as troubles sometimes do,",
            "{A} comes and leans against a knee,", "and that will see them through.");
        v("PET", 'C', "", "So here's to {A}, the finest {kind}", "that ever chased a hare,",
            "who has no trade and pays no rent", "and sleeps in {B}'s chair.");
        v("PET", 'C', "", "No coin, no trade, no work, no worth,", "the books would have you say;",
            "but {B} would not swap {A}", "for all the gold in the world today.");

        // ---------------------------------------------------------------- a birth
        TITLES.put("BIRTH", new String[]{ "For {A}, Newly Born", "A New Name in {town}", "Welcome, {A}", "Cradle Song for {A}" });
        v("BIRTH", 'O', "", "A cry went up in {town}", "in the small hours of the night,",
            "and {parents} came down the stair", "with faces shining bright.");
        v("BIRTH", 'O', "", "There's a new name in the chronicle", "and a cradle by the bed,",
            "and half of {town} has called round", "to see the little head.");
        v("BIRTH", 'M', "", "They've called the little one {A},", "a name to grow into;",
            "may every road that {A} walks", "lead home again to you.");
        v("BIRTH", 'M', "", "Ten fingers and ten tiny toes,", "a voice to wake the dead;",
            "and {parentA} can't stop smiling now,", "and nor can {parentB}, it's said.");
        v("BIRTH", 'C', "", "Sleep, little {A}, and grow strong", "in {town}'s good air;",
            "there's bread and fire and work enough,", "and love enough to spare.");
        v("BIRTH", 'C', "", "One day you'll farm, or mine, or fish,", "or keep the wall at night;",
            "but sleep for now, small {A}, and know", "that everything's all right.");

        // ---------------------------------------------------------------- the town
        TITLES.put("TOWN", new String[]{ "{town}", "In Praise of {town}", "Our Town", "A Song for {town}" });
        v("TOWN", 'O', "count", "When {town} was a camp of {founders},", "a fire, a chest, a tree,",
            "who'd have thought we'd see the day", "we'd number {nwords}? Not me!");
        v("TOWN", 'O', "", "I've walked the roads to other towns", "and seen their fields and spires,",
            "but none of them is {town},", "and none of them has our fires.");
        v("TOWN", 'M', "built", "We raised the {b1} and the {b2},", "we dug the well down deep,",
            "we built the houses one by one", "for all of us to sleep.");
        v("TOWN", 'M', "age", "We came into {age}", "with stone and sweat and song,",
            "and every one of us can say", "we helped the town along.");
        v("TOWN", 'M', "motto", "'{motto}' is our word,", "and carved above the door;",
            "and every one of us would say", "we mean it, and much more.");
        v("TOWN", 'M', "", "We've known the lean days and the fat,", "the long nights and the short;",
            "and {town} has come through all of them", "the way a good town ought.");
        v("TOWN", 'C', "", "So here's to {town}, the best of towns,", "and here's to all who stay,",
            "and here's to every one of us", "who builds it day by day.");

        // ---------------------------------------------------------------- two friends
        TITLES.put("FRIEND", new String[]{ "{A} and {B}", "Two Friends", "On Friendship", "For {A}, from Us All" });
        v("FRIEND", 'O', "", "{A} and {B} have been friends", "for longer than I've known;",
            "they argue over everything", "and never walk alone.");
        v("FRIEND", 'M', "", "If {A} is down, then {B} knows", "before a word is said;",
            "and if {B}'s ill, then {A} comes round", "with soup and fresh-baked bread.");
        v("FRIEND", 'M', "", "They share their bread, they share their work,", "they share each other's woes,",
            "and where you see the one of them,", "the other surely goes.");
        v("FRIEND", 'C', "", "So here's to friends, the best of things,", "worth more than gold or land:",
            "two people walking down the street", "who need not hold a hand.");

        // ---------------------------------------------------------------- [weave] a great work opened
        TITLES.put("WORKS", new String[]{ "The Opening of {Work}", "{Work}", "For the Hands Who Built {Work}", "A Song for {Work}" });
        v("WORKS", 'O', "", "The ribbon's cut on {A},", "the last stone's laid and set,",
            "and all the hands of {town}", "are standing on it yet.");
        v("WORKS", 'O', "", "We voted for it in the hall", "and turned out with the dawn,",
            "and stone by stone we laid {A}", "till every gap was gone.");
        v("WORKS", 'M', "count", "{stones} stones went into it,", "and each one set by hand;",
            "{nwords} of us to lift them", "and carry them overland.");
        v("WORKS", 'M', "", "The masons and the miners came,", "the farmers left the plough,",
            "and every hand in {town}", "can say, 'I built that,' now.");
        v("WORKS", 'M', "", "The elder said what it had cost", "and what it meant to bring,",
            "then took the shears and cut the cord,", "and all of us did sing.");
        v("WORKS", 'C', "", "So cross it, friends, and stamp your feet,", "and think on those who made it:",
            "there's not a stone in all of it", "but someone's hands have laid it.");
        v("WORKS", 'C', "", "And when we're old and slow and grey", "we'll bring the young ones by,",
            "and tell them how we built {A}", "beneath this very sky.");

        // ---------------------------------------------------------------- [weave] a famous auction
        TITLES.put("AUCTION", new String[]{ "The Ballad of the Auction", "Going, Going, Gone", "Sold to {A}", "The Day of the Auction" });
        v("AUCTION", 'O', "", "It was market day in {town}", "and the square was packed and loud,",
            "for the auctioneer had a lot to sell", "that drew the whole town's crowd.");
        v("AUCTION", 'O', "", "They held it up for all to see,", "the finest of the day:",
            "{B}, and every purse in {town}", "was itching for the fray.");
        v("AUCTION", 'M', "count", "The bids went up by ones and twos,", "then up by fives and more,",
            "till {nwords} coins was called aloud", "and the crowd began to roar.");
        v("AUCTION", 'M', "", "'Going once!' the hammer hung,", "'and going twice!' it said;",
            "and {A} held up a steady hand", "while others shook their head.");
        v("AUCTION", 'M', "", "A rancher bid, a smith bid too,", "a collector at the back;",
            "but {A} would not be beaten down", "nor give an inch of slack.");
        v("AUCTION", 'C', "count", "So 'Sold!' they cried to {A},", "for {nwords} coins and true;",
            "and {town} still tells it at the inn", "the way I've told it you.");
        v("AUCTION", 'C', "", "And if you're at the square one day", "when the hammer's raised on high,",
            "remember {A}, who would not budge", "and let no bargain by.");
    }

    // ------------------------------------------------------------------ the writing

    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z0-9]+)}");

    /** The kinds of poem there are. */
    public static List<String> kinds() {
        return List.of("WEDDING", "ELEGY", "HARVEST", "SEA", "PET", "BIRTH", "TOWN", "FRIEND", "WORKS", "AUCTION");   // [weave] the works, the auction
    }

    /** A poem on this, by this poet: its title, its dedication, its verses, and a word from the poet. */
    public static Quill.Book write(Subject s, Voice poet, long day) {
        Dice d = Dice.of(s.town(), poet.name(), s.kind(), s.get("A"), s.get("B"), day);
        List<Stanza> chosen = choose(s, d);
        String title = Quill.fit(fill(d.pick(TITLES.getOrDefault(s.kind(), new String[]{ "A Poem" })), s),
            fill(TITLES.getOrDefault(s.kind(), new String[]{ "A Poem" })[0], s), "A Poem");
        List<Block> b = new ArrayList<>();
        b.add(Block.title("§l" + title + "§r"));
        b.add(Block.title("by " + poet.name()));
        b.add(Block.space());
        String dedication = dedication(s);
        if (!dedication.isEmpty()) {
            b.add(Block.title(dedication));
            b.add(Block.space());
        }
        String before = before(poet, d, s);
        if (!before.isEmpty()) {
            b.add(Block.para(before));
            b.add(Block.space());
        }
        for (Stanza st : chosen) {
            for (String l : st.lines()) b.add(Block.verse(fill(l, s)));
            b.add(Block.stanzaEnd());
        }
        b.add(Block.para("- " + poet.name() + ", day " + day));
        String about = blurb(s);
        return new Quill.Book("POEM", title, poet.name(), Quill.pages(b), about, subject(s), List.of());
    }

    /** The verses for this poem, in the shape its kind takes. */
    private static List<Stanza> choose(Subject s, Dice d) {
        List<Stanza> all = BANK.getOrDefault(s.kind(), List.of());
        List<Stanza> out = new ArrayList<>();
        String shape = switch (s.kind()) {
            case "ELEGY" -> "OTXKC";
            case "WEDDING" -> "OMMC";
            case "HARVEST", "SEA", "PET" -> "OMMC";
            case "BIRTH", "TOWN", "WORKS", "AUCTION" -> "OMMC";                // [weave]
            default -> "OMC";
        };
        for (char slot : shape.toCharArray()) {
            List<Stanza> fits = new ArrayList<>();
            for (Stanza st : all) if (st.slot() == slot && fits(st, s) && !out.contains(st)) fits.add(st);
            if (fits.isEmpty()) continue;
            // A verse for this very trade, cause or kind of animal before a verse for anything.
            List<Stanza> own = new ArrayList<>();
            for (Stanza st : fits) if (!st.tag().isEmpty() && own(st, s)) own.add(st);
            out.add(d.pick(own.isEmpty() || slot == 'M' && d.chance(40) ? fits : own));
        }
        return out;
    }

    /** Can this verse be filled from what the town knows? */
    private static boolean fits(Stanza st, Subject s) {
        for (String l : st.lines()) {
            Matcher m = SLOT.matcher(l);
            while (m.find()) if (value(m.group(1), s).isEmpty()) return false;
        }
        String tag = st.tag();
        if (tag.isEmpty()) return true;
        return switch (st.slot()) {
            case 'T' -> s.get("trade").equals(tag);
            case 'X' -> s.get("cause").equals(tag);
            case 'K' -> tag.equals("kin") ? !s.get("kin").isEmpty() : !s.get("children").isEmpty();
            default -> switch (tag) {
                case "cat", "dog" -> s.get("kind").equals(tag);
                case "trades" -> !s.get("tradeA").isEmpty() && !s.get("tradeB").isEmpty();
                case "count", "years" -> !s.get("nwords").isEmpty();
                case "built" -> !s.get("b1").isEmpty();
                case "age" -> !s.get("age").isEmpty();
                case "motto" -> !s.get("motto").isEmpty();
                default -> true;
            };
        };
    }

    /** A verse written for this subject in particular (its trade, its cause, its animal). */
    private static boolean own(Stanza st, Subject s) {
        return switch (st.slot()) {
            case 'T' -> s.get("trade").equals(st.tag());
            case 'X' -> s.get("cause").equals(st.tag());
            default -> true;
        };
    }

    private static String value(String key, Subject s) {
        return switch (key) {
            case "town" -> s.town();
            case "Kind" -> Quill.cap(s.get("kind"));
            case "Place" -> titleCase(s.get("place").replaceFirst("^the ", ""));
            case "Work" -> titleCase(s.get("A"));                              // [weave] "the Stone Bridge"
            default -> s.get(key);
        };
    }

    private static String fill(String line, Subject s) {
        Matcher m = SLOT.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(value(m.group(1), s)));
        m.appendTail(sb);
        return Quill.cap(sb.toString());
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.equals("of") || w.equals("the") ? w : Quill.cap(w));
        }
        return sb.toString();
    }

    /** The line under the title: who and what it is for. */
    private static String dedication(Subject s) {
        String day = s.get("day");
        return switch (s.kind()) {
            case "WEDDING" -> "for " + s.get("A") + " and " + s.get("B") + (day.isEmpty() ? "" : ", wed on day " + day);
            case "ELEGY" -> "for " + s.get("A") + (day.isEmpty() ? "" : ", who died on day " + day);
            case "HARVEST" -> "for the harvest" + (day.isEmpty() ? "" : " of day " + day);
            case "BIRTH" -> "for " + s.get("A") + (day.isEmpty() ? "" : ", born on day " + day);
            case "PET" -> "for " + s.get("A") + ", and for " + s.get("B");
            case "FRIEND" -> "for " + s.get("A") + " and " + s.get("B");
            case "WORKS" -> "for the opening of " + s.get("A") + (day.isEmpty() ? "" : ", day " + day);                     // [weave]
            case "AUCTION" -> "for " + s.get("A") + ", who bought " + s.get("B") + (day.isEmpty() ? "" : " on day " + day);
            default -> "";
        };
    }

    /** A word from the poet before it, in its own manner. */
    private static String before(Voice poet, Dice d, Subject s) {
        String what = switch (s.kind()) {
            case "WEDDING" -> "the wedding";
            case "ELEGY" -> s.get("A");
            case "HARVEST" -> "the harvest";
            case "SEA" -> "the fishers";
            case "PET" -> s.get("A");
            case "BIRTH" -> "the baby";
            case "TOWN" -> "the town";
            case "WORKS" -> "the opening";                                     // [weave]
            case "AUCTION" -> "the auction";
            default -> "them";
        };
        return switch (poet.tone()) {
            case "shy" -> d.pick("I'm no poet. But I wanted to write something for " + what + ", so here it is. Please don't laugh.",
                "I almost didn't put this on the shelf. I hope it's all right.");
            case "grumpy" -> d.pick("Somebody asked me for a poem for " + what + ". Here it is. Don't ask again.",
                "I don't hold with poems as a rule. This one wrote itself.");
            case "cheerful" -> d.pick("I couldn't help it - I had to write something for " + what + "! Read it out loud!",
                "This one's for " + what + ", with all my heart!");
            case "curious" -> "I wondered whether a poem could hold " + what + ". This is my try.";
            case "sociable" -> "Written for " + what + ", and meant to be read out at the tavern.";
            default -> "";
        };
    }

    private static String blurb(Subject s) {
        return switch (s.kind()) {
            case "WEDDING" -> "a poem for the wedding of " + s.get("A") + " and " + s.get("B");
            case "ELEGY" -> "a poem in memory of " + s.get("A");
            case "HARVEST" -> "a poem for the harvest" + (s.get("A").isEmpty() ? "" : " and " + s.get("A") + "'s field");
            case "SEA" -> "a poem of the water and the fishers";
            case "PET" -> "a poem for " + s.get("A") + ", " + s.get("B") + "'s " + s.get("kind");
            case "BIRTH" -> "a poem for the birth of " + s.get("A");
            case "TOWN" -> "a poem in praise of " + s.town();
            case "FRIEND" -> "a poem for " + s.get("A") + " and " + s.get("B") + ", friends";
            case "WORKS" -> "a poem for the opening of " + s.get("A");             // [weave]
            case "AUCTION" -> "a ballad of the auction, and " + s.get("A") + "'s famous bid";
            default -> "a poem";
        };
    }

    /** What the poem is about, so it is written once: "poem:WEDDING:Ada|Bram". */
    public static String subject(Subject s) {
        return "poem:" + s.kind() + ":" + s.get("A") + "|" + s.get("B");
    }
}
